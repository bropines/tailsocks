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

    /** Policy routing table and firewall mark reserved for the tailscale0 interface. */
    private const val ROUTE_TABLE = "1099"

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
            env.append("export TS_LOGS_DIR=\"$logsDir\"\n")
            env.append("export TS_NO_LOGS_NO_SUPPORT=true\n")
            env.append("export TS_AUTH_ONCE=true\n")
            // Must match the addresses excluded from the DNS redirect in
            // applyTailscale0Routing, otherwise the daemon's bootstrap queries are
            // redirected into MagicDNS before MagicDNS can answer anything.
            val fallbacks = dnsFallbacks.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
            env.append("export TS_DNS_FALLBACK=\"${fallbacks.joinToString(",")}\"\n")

            if (taildropDir.isNotEmpty()) {
                env.append("export TS_TAILDROP_DIR=\"$taildropDir\"\n")
            }

            if (controlProxy.isNotEmpty()) {
                val staticOverride = resolveProxyHostStatic(controlProxy)
                if (staticOverride.isNotEmpty()) {
                    env.append("export TS_STATIC_HOSTS=\"$staticOverride\"\n")
                }
                if (controlProxy.startsWith("socks5://")) {
                    env.append("export ALL_PROXY=\"$controlProxy\"\n")
                } else {
                    env.append("export HTTP_PROXY=\"$controlProxy\"\n")
                    env.append("export HTTPS_PROXY=\"$controlProxy\"\n")
                }
            }

            try {
                val envFile = File(dataDir, "files/control_proxy.env")
                envFile.parentFile?.mkdirs()
                envFile.writeText(env.toString())
                Log.d(TAG, "Wrote control_proxy.env for service.d autostart")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write control_proxy.env: ${e.message}")
            }

            val cmd = mutableListOf<String>().apply {
                add(tailscaledBin)
                add("--statedir=$stateDir")
                add("--socket=$socketPath")
                if (socksAddr.isNotEmpty() && socksAddr != "none") {
                    add("--socks5-server=$socksAddr")
                }
                if (tunMode) {
                    add("--tun=tailscale0")
                } else {
                    add("--tun=userspace-networking")
                }
                if (httpAddr.isNotEmpty()) {
                    add("--outbound-http-proxy-listen=$httpAddr")
                }
            }.joinToString(" ")

            val sb = StringBuilder(env)
            sb.append("nohup $cmd >> \"$logFile\" 2>&1 &\n")
            sb.append("chmod 666 \"$logFile\" 2>/dev/null || true\n")
            sb.append("magiskpolicy --live \"allow untrusted_app magisk unix_stream_socket connectto\" 2>/dev/null || supolicy --live \"allow untrusted_app magisk unix_stream_socket connectto\" 2>/dev/null || true\n")
            sb.append("for i in \$(seq 1 30); do\n")
            sb.append("    if [ -S \"$socketPath\" ] || [ -e \"$socketPath\" ]; then\n")
            sb.append("        chmod 777 \"$socketPath\"\n")
            sb.append("        chcon u:object_r:app_data_file:s0 \"$socketPath\" 2>/dev/null || true\n")
            sb.append("        chmod 777 \"$stateDir\" 2>/dev/null || true\n")
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

    fun updateRootHosts(hostsMap: Map<String, String>): Boolean {
        val sb = StringBuilder()
        sb.append("mkdir -p /data/adb/tailshosts\n")
        sb.append("umount /system/etc/hosts 2>/dev/null || true\n")
        sb.append("cp /system/etc/hosts /data/adb/tailshosts/hosts 2>/dev/null || printf '127.0.0.1 localhost\\n::1 ip6-localhost\\n' > /data/adb/tailshosts/hosts\n")
        for ((ip, domain) in hostsMap) {
            sb.append("echo '$ip $domain' >> /data/adb/tailshosts/hosts\n")
        }
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
        sb.append("ip rule del fwmark $ROUTE_TABLE table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip rule add fwmark $ROUTE_TABLE table $ROUTE_TABLE priority 100\n")

        sb.append("iptables -t mangle -N $CHAIN_MARK 2>/dev/null || iptables -t mangle -F $CHAIN_MARK\n")
        sb.append("iptables -t mangle -A $CHAIN_MARK -j MARK --set-mark $ROUTE_TABLE\n")
        sb.append("iptables -t mangle -C OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || iptables -t mangle -A OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK\n")

        sb.append("iptables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o tailscale0 -j ACCEPT\n")
        sb.append("iptables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i tailscale0 -j ACCEPT\n")

        // --- IPv6 policy routing ---
        sb.append("ip -6 route replace $TAILNET_V6 dev tailscale0 table $ROUTE_TABLE metric 1 2>/dev/null || true\n")
        sb.append("ip -6 rule del fwmark $ROUTE_TABLE table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip -6 rule add fwmark $ROUTE_TABLE table $ROUTE_TABLE priority 100 2>/dev/null || true\n")

        sb.append("ip6tables -t mangle -N $CHAIN_MARK 2>/dev/null || ip6tables -t mangle -F $CHAIN_MARK\n")
        sb.append("ip6tables -t mangle -A $CHAIN_MARK -j MARK --set-mark $ROUTE_TABLE 2>/dev/null || true\n")
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

        sb.append("while ip rule del fwmark $ROUTE_TABLE table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del fwmark $ROUTE_TABLE lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $ROUTE_TABLE table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $ROUTE_TABLE lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")

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
        append("while iptables -t mangle -D OUTPUT -d $CGNAT_V4 -j MARK --set-mark $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while ip6tables -t mangle -D OUTPUT -d $TAILNET_V6 -j MARK --set-mark $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -d $CGNAT_V4 -p udp --dport 53 -j ACCEPT 2>/dev/null; do :; done\n")
        append("while iptables -t nat -D OUTPUT -d $CGNAT_V4 -p tcp --dport 53 -j ACCEPT 2>/dev/null; do :; done\n")
    }

    /** Dumps the rules we own, for the Root Mode diagnostics screen. */
    fun dumpRoutingState(): String {
        val script = buildString {
            append("echo '--- ip rule ---'\n")
            append("ip rule list 2>/dev/null | grep -i $ROUTE_TABLE || echo '(no v4 rule)'\n")
            append("ip -6 rule list 2>/dev/null | grep -i $ROUTE_TABLE || echo '(no v6 rule)'\n")
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
        val script = buildString {
            append("pkill -15 -f 'libtailscale.so' 2>/dev/null || killall -15 tailscaled 2>/dev/null || true\n")
            append("sleep 1\n")
            append("pkill -9 -f 'libtailscale.so' 2>/dev/null || killall -9 tailscaled 2>/dev/null || true\n")
            if (socketPath.isNotEmpty()) {
                append("rm -f \"$socketPath\"\n")
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
                val scriptContent = context.assets.open("scripts/tailscaled.sh").bufferedReader().use { it.readText() }
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

                    mount -o remount,rw /product/bin 2>/dev/null && cp "${tempFile.absolutePath}" /product/bin/tailscale && chmod 755 /product/bin/tailscale && chcon u:object_r:system_file:s0 /product/bin/tailscale && mount -o remount,ro /product/bin 2>/dev/null || true
                    mount -o remount,rw /system 2>/dev/null || true
                    cp "${tempFile.absolutePath}" "$CLI_SCRIPT_PATH" 2>/dev/null && chmod 755 "$CLI_SCRIPT_PATH" || true
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
