package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object RootUtils {
    private const val TAG = "RootUtils"
    const val SERVICE_D_DIR = "/data/adb/service.d"
    const val SERVICE_SCRIPT_PATH = "$SERVICE_D_DIR/tailscaled.sh"

    /** Root-owned home of everything the boot script consumes; the app uid cannot write here. */
    const val ROOT_ENV_DIR = "/data/adb/tailsocks"
    const val ROOT_ENV_FILE = "$ROOT_ENV_DIR/control_proxy.env"

    /** Policy routing table reserved for the tailscale0 interface. */
    private const val ROUTE_TABLE = "1099"

    /**
     * Firewall mark used to steer tailnet traffic into [ROUTE_TABLE].
     *
     * Android packs its own routing decision into fwmark: the low 16 bits are the
     * netId, above them sit the explicit/protect/permission flags. Writing a bare
     * `--set-mark 1099` overwrites all of it, which tells netd the packet belongs
     * to network 1099 and breaks routing whenever another VPN owns the default
     * network. A single high bit is set through a mask instead, leaving netd's
     * bookkeeping intact.
     */
    private const val MARK_BIT = "0x1000000"
    private const val MARK_MASK = "0x1000000"

    /** Pre-3.6 mark, still removed on cleanup so upgrades do not leave it behind. */
    private const val LEGACY_MARK = "1099"

    /** Dedicated iptables chains. Owning named chains makes every rule we install
     *  idempotent, inspectable and removable in one shot — unlike appending to
     *  the shared OUTPUT/FORWARD chains, which accumulates duplicates. */
    private const val CHAIN_MARK = "TAILSOCKS_MARK"
    private const val CHAIN_DNS = "TAILSOCKS_DNS"

    private const val CGNAT_V4 = "100.64.0.0/10"
    private const val TAILNET_V6 = "fd7a:115c:a1e0::/48"
    private const val MAGIC_DNS = "100.100.100.100"

    /** Result of a root shell invocation, including everything it printed. */
    data class SuResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private fun rootLog(level: String, message: String) {
        try {
            appctr.Appctr.logAndroid(level, "ROOT", message)
        } catch (e: Exception) {
            // The Go bridge may not be loaded yet; the logcat entry below still applies.
        }
    }

    /**
     * Runs a script in a root shell and returns its exit code and combined output.
     *
     * Both pipes are drained on separate threads: a script that prints more than
     * the pipe buffer (an `iptables -S` dump, for instance) would otherwise block
     * forever, and the shell is killed if `su` never returns — an ungranted root
     * prompt must not hang the caller.
     */
    private fun runSu(tag: String, script: String, timeoutMs: Long = 20_000L): SuResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val output = StringBuilder()

            val drain = { stream: java.io.InputStream ->
                Thread {
                    try {
                        stream.bufferedReader().forEachLine { line ->
                            synchronized(output) { output.append(line).append('\n') }
                        }
                    } catch (e: Exception) {
                        // Stream closed together with the process.
                    }
                }.apply { isDaemon = true; start() }
            }
            val outThread = drain(process.inputStream)
            val errThread = drain(process.errorStream)

            try {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(script)
                    writer.write("\nexit\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$tag] failed to feed script to su: ${e.message}")
            }

            val waiter = Thread {
                try { process.waitFor() } catch (e: InterruptedException) { /* timed out */ }
            }.apply { isDaemon = true; start() }
            waiter.join(timeoutMs)

            val exitCode = if (waiter.isAlive) {
                Log.e(TAG, "[$tag] root shell timed out after ${timeoutMs}ms, killing it")
                rootLog("ERROR", "$tag: root shell timed out after ${timeoutMs}ms")
                process.destroy()
                -1
            } else {
                try { process.exitValue() } catch (e: IllegalThreadStateException) { -1 }
            }

            outThread.join(TimeUnit.SECONDS.toMillis(2))
            errThread.join(TimeUnit.SECONDS.toMillis(2))

            val text = synchronized(output) { output.toString().trim() }
            if (exitCode != 0) {
                Log.e(TAG, "[$tag] exit=$exitCode output=$text")
                rootLog("ERROR", "$tag failed (exit $exitCode)${if (text.isEmpty()) "" else ": $text"}")
            } else {
                Log.d(TAG, "[$tag] exit=0 output=$text")
                if (text.isNotEmpty()) rootLog("INFO", "$tag: $text")
            }
            SuResult(exitCode, text)
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] root shell failed: ${e.message}", e)
            rootLog("ERROR", "$tag: root shell unavailable (${e.message})")
            SuResult(-1, e.message ?: "")
        }
    }

    /**
     * Checks if the root daemon is actually alive by attempting a real
     * LocalSocket connect() to the Unix domain socket.
     * Returns false if the file doesn't exist or the connect() is refused.
     */
    fun isDaemonAlive(socketPath: String): Boolean {
        if (!File(socketPath).exists()) return false
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.isConnected
            }
        } catch (e: Exception) {
            Log.d(TAG, "isDaemonAlive: connect failed: ${e.message}")
            false
        }
    }

    fun isRootAvailable(): Boolean {
        val res = runSu("root-check", "id", timeoutMs = 10_000L)
        Log.d(TAG, "Root check exitCode=${res.exitCode} output=${res.output}")
        return res.ok && res.output.contains("uid=0")
    }

    fun startRootDaemon(
        context: Context,
        stateDir: String,
        socketPath: String,
        logFilePath: String,
        socksAddr: String = "127.0.0.1:1053",
        httpAddr: String = "",
        controlProxy: String = "",
        taildropDir: String = "",
        tunMode: Boolean = true,
        dnsFallbacks: List<String> = listOf("1.1.1.1", "8.8.8.8")
    ): Boolean {
        return try {
            val tailscaledBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
            val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            val logsDir = File(dataDir, "logs").apply { mkdirs() }.absolutePath
            val logFile = if (logFilePath.isNotEmpty()) logFilePath else "$logsDir/tailscaled.log"

            val socketFile = File(socketPath)
            socketFile.parentFile?.mkdirs()

            val env = StringBuilder()
            env.append("export TS_LOGS_DIR=${shQuote(logsDir)}\n")
            env.append("export TS_NO_LOGS_NO_SUPPORT=true\n")
            env.append("export TS_AUTH_ONCE=true\n")
            // Must match the addresses excluded from the DNS redirect in
            // applyTailscale0Routing, otherwise the daemon's bootstrap queries are
            // redirected into MagicDNS before MagicDNS can answer anything.
            val fallbacks = dnsFallbacks.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
            env.append("export TS_DNS_FALLBACK=${shQuote(fallbacks.joinToString(","))}\n")

            if (taildropDir.isNotEmpty()) {
                env.append("export TS_TAILDROP_DIR=${shQuote(taildropDir)}\n")
            }

            // Single-quoted: the control proxy URL carries a user-supplied
            // password, and this file is sourced by the boot script as root, so
            // a value containing $(...) or backticks would otherwise run as root
            // on every boot.
            if (controlProxy.isNotEmpty()) {
                val staticOverride = resolveProxyHostStatic(controlProxy)
                if (staticOverride.isNotEmpty()) {
                    env.append("export TS_STATIC_HOSTS=${shQuote(staticOverride)}\n")
                }
                if (controlProxy.startsWith("socks5://")) {
                    env.append("export ALL_PROXY=${shQuote(controlProxy)}\n")
                } else {
                    env.append("export HTTP_PROXY=${shQuote(controlProxy)}\n")
                    env.append("export HTTPS_PROXY=${shQuote(controlProxy)}\n")
                }
            }

            // The boot script sources this file as root on every boot, so it must
            // not live anywhere the app uid can write: a restored backup used to
            // be able to drop an attacker's files/control_proxy.env there. It is
            // written through su into a root-owned directory, 0600, via a quoted
            // heredoc (no expansion), and the old app-writable copy is removed.
            val envScript = StringBuilder()
                .append("mkdir -p ").append(ROOT_ENV_DIR).append(" && chmod 700 ").append(ROOT_ENV_DIR).append('\n')
                .append("cat > ").append(ROOT_ENV_FILE).append(" <<'TAILSOCKS_ENV_EOF'\n")
                .append(env)
                .append("TAILSOCKS_ENV_EOF\n")
                .append("chmod 600 ").append(ROOT_ENV_FILE).append('\n')
                .append("rm -f ").append(shQuote("$dataDir/files/control_proxy.env")).append('\n')
            if (!runSu("control-proxy-env", envScript.toString()).ok) {
                Log.w(TAG, "Failed to write the root-owned control_proxy.env; the boot script will start without proxy settings")
            }

            // Every argument is quoted: the listen addresses come from free-text
            // settings fields and are interpolated into a root shell.
            val cmd = mutableListOf<String>().apply {
                add(shQuote(tailscaledBin))
                add("--statedir=" + shQuote(stateDir))
                add("--socket=" + shQuote(socketPath))
                if (socksAddr.isNotEmpty() && socksAddr != "none") {
                    add("--socks5-server=" + shQuote(socksAddr))
                }
                if (tunMode) {
                    add("--tun=tailscale0")
                } else {
                    add("--tun=userspace-networking")
                }
                if (httpAddr.isNotEmpty()) {
                    add("--outbound-http-proxy-listen=" + shQuote(httpAddr))
                }
            }.joinToString(" ")

            val sb = StringBuilder(env)
            sb.append("nohup $cmd >> ${shQuote(logFile)} 2>&1 &\n")
            sb.append("chmod 600 ${shQuote(logFile)} 2>/dev/null || true\n")
            sb.append("magiskpolicy --live \"allow untrusted_app magisk unix_stream_socket connectto\" 2>/dev/null || supolicy --live \"allow untrusted_app magisk unix_stream_socket connectto\" 2>/dev/null || true\n")
            sb.append("for i in \$(seq 1 30); do\n")
            sb.append("    if [ -S ${shQuote(socketPath)} ] || [ -e ${shQuote(socketPath)} ]; then\n")
            sb.append("        chmod 666 ${shQuote(socketPath)}\n")
            sb.append("        chcon u:object_r:app_data_file:s0 ${shQuote(socketPath)} 2>/dev/null || true\n")
            sb.append("        chmod 700 ${shQuote(stateDir)} 2>/dev/null || true\n")
            sb.append("        break\n")
            sb.append("    fi\n")
            sb.append("    sleep 0.2\n")
            sb.append("done\n")
            // Routing is intentionally not configured here: it is applied by
            // TailscaledService once tailscale0 exists, and the DNS redirect only
            // after the daemon reaches the Running state.

            val res = runSu("daemon-start", sb.toString(), timeoutMs = 30_000L)
            Log.d(TAG, "Root daemon launch result exitCode=${res.exitCode}")

            // Verify the daemon really came up rather than trusting the exit code.
            var attempts = 0
            while (attempts < 25) {
                if (isDaemonAlive(socketPath)) {
                    Log.i(TAG, "Root daemon socket is accepting connections at $socketPath")
                    rootLog("INFO", "Root daemon started (socket ready)")
                    return true
                }
                Thread.sleep(200)
                attempts++
            }
            rootLog("ERROR", "Root daemon did not open $socketPath within 5s")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root daemon: ${e.message}", e)
            rootLog("ERROR", "Failed to start root daemon: ${e.message}")
            false
        }
    }

    /**
     * Quotes a value for safe use inside a single-quoted shell word. Line breaks
     * are dropped: no root command argument legitimately contains one, and a
     * newline would end the line inside the env file the boot script reads.
     */
    private fun shQuote(value: String): String =
        "'" + value.replace("\r", "").replace("\n", "").replace("'", "'\\''") + "'"

    /** A hosts entry may only contain characters valid in a DNS name. */
    private val hostLabel = Regex("^[A-Za-z0-9._-]{1,253}$")

    private val ipv4Literal = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6Literal = Regex("^[0-9A-Fa-f:]{2,45}$")

    /** An address is written to a root-owned file, so it must really be one. */
    private fun isIpLiteral(value: String): Boolean = when {
        ipv4Literal.matches(value) -> value.split(".").all { (it.toIntOrNull() ?: 256) <= 255 }
        ipv6Literal.matches(value) -> value.contains(":")
        else -> false
    }

    /**
     * Publishes tailnet names into /system/etc/hosts.
     *
     * Peer names are self-reported and are not validated by the control plane,
     * so they are treated as hostile input: anything that is not a plain DNS
     * label is dropped, and the file is written through a quoted heredoc rather
     * than one shell `echo` per entry. Interpolating a peer name into a root
     * shell let any device on the tailnet run code as root on this phone.
     */
    fun updateRootHosts(hostsMap: Map<String, String>): Boolean {
        val lines = mutableListOf<String>()
        for ((ip, aliases) in hostsMap) {
            if (!isIpLiteral(ip)) {
                Log.w(TAG, "hosts-sync: skipping non-address entry '$ip'")
                continue
            }
            val safe = aliases.split(" ").filter { it.isNotEmpty() && hostLabel.matches(it) }
            if (safe.isEmpty()) {
                Log.w(TAG, "hosts-sync: skipping entry with no usable names for $ip")
                continue
            }
            lines.add("$ip ${safe.joinToString(" ")}")
        }
        if (lines.isEmpty()) return true

        // 'TAILSOCKS_EOF' is quoted, so the shell performs no expansion at all
        // on the body — the entries are copied through verbatim.
        val sb = StringBuilder()
        sb.append("set -e\n")
        sb.append("mkdir -p /data/adb/tailshosts\n")
        sb.append("umount /system/etc/hosts 2>/dev/null || true\n")
        sb.append("cp /system/etc/hosts /data/adb/tailshosts/hosts 2>/dev/null || printf '127.0.0.1 localhost\\n::1 ip6-localhost\\n' > /data/adb/tailshosts/hosts\n")
        sb.append("cat >> /data/adb/tailshosts/hosts <<'TAILSOCKS_EOF'\n")
        for (line in lines) sb.append(line).append('\n')
        sb.append("TAILSOCKS_EOF\n")
        sb.append("chmod 644 /data/adb/tailshosts/hosts\n")
        sb.append("chcon u:object_r:system_file:s0 /data/adb/tailshosts/hosts 2>/dev/null || true\n")
        sb.append("mount -o bind /data/adb/tailshosts/hosts /system/etc/hosts 2>/dev/null || true\n")
        return runSu("hosts-sync", sb.toString()).ok
    }

    /**
     * Installs policy routing for the native `tailscale0` interface.
     *
     * @param dnsRedirect       whether system-wide DNS should be redirected to MagicDNS.
     *                          Only meaningful when the daemon actually serves DNS
     *                          (`accept-dns` on): redirecting to a resolver that is
     *                          not answering would break DNS for the whole device.
     * @param dnsBypassAddrs    upstream resolver IPs that must reach port 53 directly.
     *                          Without them the daemon's own upstream queries are
     *                          redirected back into MagicDNS, which resolves nothing
     *                          and loops.
     */
    fun applyTailscale0Routing(
        dnsRedirect: Boolean = true,
        dnsBypassAddrs: List<String> = emptyList()
    ): Boolean {
        val sb = StringBuilder()
        sb.append(legacyRuleCleanup())

        // --- IPv4 policy routing ---
        sb.append("ip route replace $CGNAT_V4 dev tailscale0 table $ROUTE_TABLE metric 1\n")
        sb.append("ip rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip rule add fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE priority 100\n")

        sb.append("iptables -t mangle -N $CHAIN_MARK 2>/dev/null || iptables -t mangle -F $CHAIN_MARK\n")
        sb.append("iptables -t mangle -A $CHAIN_MARK -j MARK --set-xmark $MARK_BIT/$MARK_MASK\n")
        sb.append("iptables -t mangle -C OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || iptables -t mangle -A OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK\n")

        sb.append("iptables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o tailscale0 -j ACCEPT\n")
        sb.append("iptables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i tailscale0 -j ACCEPT\n")

        // --- IPv6 policy routing ---
        sb.append("ip -6 route replace $TAILNET_V6 dev tailscale0 table $ROUTE_TABLE metric 1 2>/dev/null || true\n")
        sb.append("ip -6 rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip -6 rule add fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE priority 100 2>/dev/null || true\n")

        sb.append("ip6tables -t mangle -N $CHAIN_MARK 2>/dev/null || ip6tables -t mangle -F $CHAIN_MARK\n")
        sb.append("ip6tables -t mangle -A $CHAIN_MARK -j MARK --set-xmark $MARK_BIT/$MARK_MASK 2>/dev/null || true\n")
        sb.append("ip6tables -t mangle -C OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || ip6tables -t mangle -A OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || true\n")

        sb.append("ip6tables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || true\n")
        sb.append("ip6tables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || true\n")

        // --- System-wide DNS redirect ---
        sb.append(dnsChainTeardown())
        if (dnsRedirect) {
            sb.append("iptables -t nat -N $CHAIN_DNS 2>/dev/null || iptables -t nat -F $CHAIN_DNS\n")
            // Queries already aimed at the tailnet (MagicDNS itself, Split DNS
            // resolvers living on peers) must never be rewritten.
            sb.append("iptables -t nat -A $CHAIN_DNS -d $CGNAT_V4 -j RETURN\n")
            // Upstream resolvers the daemon and our own DNS proxy forward to.
            for (addr in dnsBypassAddrs.distinct()) {
                sb.append("iptables -t nat -A $CHAIN_DNS -d $addr -j RETURN\n")
            }
            sb.append("iptables -t nat -A $CHAIN_DNS -p udp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53\n")
            sb.append("iptables -t nat -A $CHAIN_DNS -p tcp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53\n")
            sb.append("iptables -t nat -C OUTPUT -p udp --dport 53 -j $CHAIN_DNS 2>/dev/null || iptables -t nat -I OUTPUT 1 -p udp --dport 53 -j $CHAIN_DNS\n")
            sb.append("iptables -t nat -C OUTPUT -p tcp --dport 53 -j $CHAIN_DNS 2>/dev/null || iptables -t nat -I OUTPUT 1 -p tcp --dport 53 -j $CHAIN_DNS\n")
        }

        // Verify rather than trust: the shell's exit code otherwise only reflects
        // the last command, and a failed route on a missing tailscale0 would be
        // reported as success.
        sb.append("ip route show table $ROUTE_TABLE 2>/dev/null | grep -q . || { echo 'verify: table $ROUTE_TABLE is empty'; exit 1; }\n")
        if (dnsRedirect) {
            sb.append("iptables -t nat -S $CHAIN_DNS >/dev/null 2>&1 || { echo 'verify: $CHAIN_DNS chain missing'; exit 1; }\n")
        }
        sb.append("echo 'verify: ok'\n")

        val res = runSu("routing-apply", sb.toString())
        if (res.ok) {
            rootLog(
                "INFO",
                "tailscale0 routing applied (table $ROUTE_TABLE, dns redirect=$dnsRedirect" +
                    if (dnsBypassAddrs.isEmpty()) ")" else ", bypass=${dnsBypassAddrs.joinToString(",")})"
            )
        }
        return res.ok
    }

    /** Removes every rule this app installs, including those from older versions. */
    fun cleanupTailscale0Routing(): Boolean {
        val sb = StringBuilder()
        sb.append(dnsChainTeardown())

        sb.append("iptables -t mangle -D OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("iptables -t mangle -F $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("iptables -t mangle -X $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("ip6tables -t mangle -D OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("ip6tables -t mangle -F $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("ip6tables -t mangle -X $CHAIN_MARK 2>/dev/null || true\n")

        sb.append("while iptables -D FORWARD -o tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")
        sb.append("while iptables -D FORWARD -i tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")
        sb.append("while ip6tables -D FORWARD -o tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")
        sb.append("while ip6tables -D FORWARD -i tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")

        sb.append("while ip rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del fwmark $LEGACY_MARK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del fwmark $LEGACY_MARK lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $LEGACY_MARK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $LEGACY_MARK lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")

        sb.append("ip route flush table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip -6 route flush table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip route del $CGNAT_V4 dev tailscale0 2>/dev/null || true\n")
        sb.append("ip -6 route del $TAILNET_V6 dev tailscale0 2>/dev/null || true\n")

        sb.append(legacyRuleCleanup())
        sb.append("umount /system/etc/hosts 2>/dev/null || true\n")

        val res = runSu("routing-cleanup", sb.toString())
        if (res.ok) rootLog("INFO", "tailscale0 routing removed")
        return res.ok
    }

    /** Detaches and drops our nat chain; safe to run when it does not exist. */
    private fun dnsChainTeardown(): String = buildString {
        append("iptables -t nat -D OUTPUT -p udp --dport 53 -j $CHAIN_DNS 2>/dev/null || true\n")
        append("iptables -t nat -D OUTPUT -p tcp --dport 53 -j $CHAIN_DNS 2>/dev/null || true\n")
        append("iptables -t nat -F $CHAIN_DNS 2>/dev/null || true\n")
        append("iptables -t nat -X $CHAIN_DNS 2>/dev/null || true\n")
    }

    /**
     * Deletes the un-chained rules installed by TailSocks 3.5.x and earlier.
     * Those were appended directly to OUTPUT/FORWARD and piled up on every
     * restart, so they are drained here until none are left.
     */
    private fun legacyRuleCleanup(): String = buildString {
        append("while iptables -t mangle -D OUTPUT -d $CGNAT_V4 -j MARK --set-mark $LEGACY_MARK 2>/dev/null; do :; done\n")
        append("while ip6tables -t mangle -D OUTPUT -d $TAILNET_V6 -j MARK --set-mark $LEGACY_MARK 2>/dev/null; do :; done\n")
        append("while ip rule del fwmark $LEGACY_MARK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while ip rule del fwmark $LEGACY_MARK lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -d $CGNAT_V4 -p udp --dport 53 -j ACCEPT 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -d $CGNAT_V4 -p tcp --dport 53 -j ACCEPT 2>/dev/null; do :; done\n")
    }

    /** Dumps the rules we own, for the Root Mode diagnostics screen. */
    fun dumpRoutingState(): String {
        val script = buildString {
            append("echo '--- ip rule ---'\n")
            append("ip rule list 2>/dev/null | grep -i '$ROUTE_TABLE' || echo '(no v4 rule)'\n")
            append("ip -6 rule list 2>/dev/null | grep -i '$ROUTE_TABLE' || echo '(no v6 rule)'\n")
            append("echo '--- other tunnels ---'\n")
            append("ip -o link show 2>/dev/null | grep -Eo '(tun[0-9]+|ppp[0-9]+|wg[0-9]+)' | grep -v tailscale0 | sort -u || echo '(none)'\n")
            append("echo '--- table $ROUTE_TABLE ---'\n")
            append("ip route show table $ROUTE_TABLE 2>/dev/null || echo '(empty)'\n")
            append("echo '--- mangle $CHAIN_MARK ---'\n")
            append("iptables -t mangle -S $CHAIN_MARK 2>/dev/null || echo '(absent)'\n")
            append("echo '--- nat $CHAIN_DNS ---'\n")
            append("iptables -t nat -S $CHAIN_DNS 2>/dev/null || echo '(absent)'\n")
            append("echo '--- tailscale0 ---'\n")
            append("ip -br addr show tailscale0 2>/dev/null || echo '(interface missing)'\n")
        }
        return runSu("routing-dump", script, timeoutMs = 10_000L).output
    }

    fun stopRootDaemon(socketPath: String = ""): Boolean {
        cleanupTailscale0Routing()
        // Match only our own daemon by its --socket argument, which contains this
        // app's private data path. `pkill -f libtailscale.so` matched the full
        // command line of every process on the device, so a second profile, a
        // Termux tailscaled, or any unrelated app shipping that library was
        // killed as root too.
        val script = buildString {
            if (socketPath.isNotEmpty()) {
                val pat = shQuote("--socket=$socketPath")
                append("pkill -15 -f -- $pat 2>/dev/null || true\n")
                append("sleep 1\n")
                append("pkill -9 -f -- $pat 2>/dev/null || true\n")
                append("rm -f ${shQuote(socketPath)}\n")
            } else {
                // No socket path to scope by; fall back to the exact process name.
                append("killall -15 libtailscale.so 2>/dev/null || true\n")
                append("sleep 1\n")
                append("killall -9 libtailscale.so 2>/dev/null || true\n")
            }
        }
        val res = runSu("daemon-stop", script)
        Log.d(TAG, "Root daemon stopped exitCode=${res.exitCode}")
        rootLog("INFO", "Root daemon stopped")
        return true
    }

    fun setServiceScriptInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                // The daemon path is baked in, like the CLI wrapper's: the script
                // used to fall back to `find /data/app -name libtailscale.so`, which
                // would exec a same-named library from any other app as root.
                // BootReceiver re-installs the script on every app update, so the
                // path follows the install directory.
                val daemonBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
                val scriptContent = context.assets.open("scripts/tailscaled.sh").bufferedReader().use { it.readText() }
                    .replace("%PKG_NAME%", context.packageName)
                    .replace("%DAEMON_BIN%", daemonBin)
                val tempFile = File(context.cacheDir, "tailscaled.sh").apply { writeText(scriptContent) }

                val cmd = """
                    mkdir -p "$SERVICE_D_DIR"
                    cp "${tempFile.absolutePath}" "$SERVICE_SCRIPT_PATH"
                    chmod 755 "$SERVICE_SCRIPT_PATH"
                    rm -f "${tempFile.absolutePath}"
                """.trimIndent()

                runSu("service-script-install", cmd).ok
            } else {
                runSu("service-script-remove", "rm -f \"$SERVICE_SCRIPT_PATH\"").ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage service.d script: ${e.message}", e)
            rootLog("ERROR", "Failed to manage autostart script: ${e.message}")
            false
        }
    }

    fun isServiceScriptInstalled(): Boolean {
        val res = runSu("service-script-check", "[ -f \"$SERVICE_SCRIPT_PATH\" ] && echo 'exists'", timeoutMs = 10_000L)
        return res.ok && res.output.contains("exists")
    }

    const val CLI_SCRIPT_PATH = "/system/bin/tailscale"
    const val ALT_CLI_SCRIPT_PATH = "/data/adb/service.d/tailscale"
    const val MAGISK_MODULE_CLI_PATH = "/data/adb/modules/tailscaled/system/bin/tailscale"

    fun setTailscaleCliInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                val cliBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale_cli.so").absolutePath
                val pkgName = context.packageName

                val scriptContent = context.assets.open("scripts/tailscale_cli.sh").bufferedReader().use { it.readText() }
                    .replace("%PKG_NAME%", pkgName)
                    .replace("%CLI_BIN%", cliBin)

                val tempFile = File(context.cacheDir, "tailscale_cli_wrapper.sh").apply {
                    writeText(scriptContent)
                }

                val cmd = """
                    mkdir -p "$SERVICE_D_DIR"
                    cp "${tempFile.absolutePath}" "$ALT_CLI_SCRIPT_PATH"
                    chmod 755 "$ALT_CLI_SCRIPT_PATH"

                    mkdir -p "/data/adb/modules/tailscaled/system/bin"
                    rm -f "/data/adb/modules/tailscaled/disable" "/data/adb/modules/tailscaled/remove"
                    printf 'id=tailscaled\nname=TailSocks CLI Integration\nversion=v1.0\nversionCode=100\nauthor=TailSocks\ndescription=Tailscale CLI binary overlay\n' > /data/adb/modules/tailscaled/module.prop
                    cp "${tempFile.absolutePath}" "$MAGISK_MODULE_CLI_PATH"
                    chmod 755 "$MAGISK_MODULE_CLI_PATH"
                    chcon u:object_r:system_file:s0 "$MAGISK_MODULE_CLI_PATH" 2>/dev/null || true

                    if mount -o remount,rw /product/bin 2>/dev/null; then
                        cp "${tempFile.absolutePath}" /product/bin/tailscale 2>/dev/null && chmod 755 /product/bin/tailscale && chcon u:object_r:system_file:s0 /product/bin/tailscale 2>/dev/null
                        mount -o remount,ro /product/bin 2>/dev/null || true
                    fi
                    if mount -o remount,rw /system 2>/dev/null; then
                        cp "${tempFile.absolutePath}" "$CLI_SCRIPT_PATH" 2>/dev/null && chmod 755 "$CLI_SCRIPT_PATH" 2>/dev/null
                        mount -o remount,ro /system 2>/dev/null || true
                    fi
                    rm -f "${tempFile.absolutePath}"
                """.trimIndent()

                runSu("cli-install", cmd).ok
            } else {
                val cmd = """
                    rm -f "$CLI_SCRIPT_PATH" "$ALT_CLI_SCRIPT_PATH" "$MAGISK_MODULE_CLI_PATH"
                    mount -o remount,rw /product/bin 2>/dev/null && rm -f /product/bin/tailscale && mount -o remount,ro /product/bin 2>/dev/null || true
                """.trimIndent()
                runSu("cli-remove", cmd).ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage CLI wrapper script: ${e.message}", e)
            rootLog("ERROR", "Failed to manage CLI wrapper: ${e.message}")
            false
        }
    }

    fun isTailscaleCliInstalled(): Boolean {
        val check = "([ -f \"$CLI_SCRIPT_PATH\" ] || [ -f \"$ALT_CLI_SCRIPT_PATH\" ] || [ -f \"$MAGISK_MODULE_CLI_PATH\" ] || [ -f \"/product/bin/tailscale\" ]) && echo 'exists'"
        val res = runSu("cli-check", check, timeoutMs = 10_000L)
        return res.ok && res.output.contains("exists")
    }

    private fun resolveProxyHostStatic(proxyUrl: String): String {
        if (proxyUrl.isBlank()) return ""
        return try {
            val uri = java.net.URI(proxyUrl)
            val host = uri.host ?: return ""
            if (host.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$")) || host.contains(":")) {
                return ""
            }
            var ip: String? = null
            try {
                val addrs = java.net.InetAddress.getAllByName(host)
                if (addrs.isNotEmpty()) {
                    ip = addrs[0].hostAddress
                }
            } catch (e: Exception) {
                Log.w(TAG, "System DNS failed to resolve '$host', trying direct UDP DNS to 1.1.1.1...")
            }
            if (ip.isNullOrBlank()) {
                ip = resolveHostViaUdpDns(host)
            }
            if (!ip.isNullOrBlank()) {
                val formattedIp = if (ip.contains(":")) "[$ip]" else ip
                val override = "$host=$formattedIp"
                Log.i(TAG, "Static DNS override resolved: $override")
                override
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve proxy host in Kotlin: ${e.message}")
            ""
        }
    }

    private fun resolveHostViaUdpDns(host: String): String? {
        return try {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 2000
            val dnsServer = java.net.InetAddress.getByName("1.1.1.1")

            val baos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(baos)
            dos.writeShort(0x1234)
            dos.writeShort(0x0100)
            dos.writeShort(0x0001)
            dos.writeShort(0x0000)
            dos.writeShort(0x0000)
            dos.writeShort(0x0000)

            for (part in host.split(".")) {
                val bytes = part.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)
            dos.writeShort(0x0001)
            dos.writeShort(0x0001)

            val query = baos.toByteArray()
            val packet = java.net.DatagramPacket(query, query.size, dnsServer, 53)
            socket.send(packet)

            val buf = ByteArray(512)
            val recvPacket = java.net.DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)
            socket.close()

            val data = recvPacket.data
            val length = recvPacket.length
            if (length > 12) {
                val ancnt = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
                if (ancnt > 0) {
                    for (i in (length - 4) downTo 12) {
                        val b0 = data[i].toInt() and 0xff
                        val b1 = data[i + 1].toInt() and 0xff
                        val b2 = data[i + 2].toInt() and 0xff
                        val b3 = data[i + 3].toInt() and 0xff
                        if (b0 in 1..254 && b3 in 1..254 && b0 != 127) {
                            return "$b0.$b1.$b2.$b3"
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Direct UDP DNS resolution failed for '$host': ${e.message}")
            null
        }
    }
}
