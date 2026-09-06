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

    /**
     * Policy routing table the daemon writes itself (patch 13, Android router):
     * peer /32s, accepted subnet routes, 100.100.100.100/32, `default dev
     * tailscale0` while an exit node is selected and `throw` routes for the LAN
     * prefixes when LAN access is allowed. The app never adds to, flushes or
     * verify-fails on this table — it only points a rule at it.
     */
    private const val DAEMON_TABLE = "52"

    /**
     * SO_MARK the root daemon sets on every socket it opens itself (patch 16,
     * net/netns/netns_android.go TailsocksBypassMark — the two values must stay
     * equal). Bit 25 sits outside Android's Fwmark layout (bits 0-20) and apart
     * from [MARK_BIT] (bit 24); the low 16 bits stay zero, so netd's
     * `fwmark 0x0/0xffff lookup <default network>` still matches marked packets.
     */
    private const val BYPASS_MARK = "0x2000000"

    /**
     * Mask of the pref-200 catch-all: [BYPASS_MARK] plus Android's
     * protectedFromVpn bit (0x20000). A packet with either bit set skips the
     * daemon's table: the daemon's own WireGuard/DERP/control traffic (which
     * would otherwise loop the moment table 52 holds a default route) and the
     * sockets Android deliberately keeps off VPNs (network validation probes,
     * MMS/IMS, other VPN apps' protected sockets) — the same `fwmark 0x0/0x20000`
     * idiom netd uses for its own VPN rules.
     */
    private const val CATCH_ALL_MASK = "0x2020000"
    private const val CATCH_ALL_PRIO = "200"

    /**
     * Priority of the per-app exclusion rules, above the catch-all so an
     * excluded uid never consults the daemon's table at all.
     *
     * A mark set in mangle OUTPUT is too late for TCP: the route and with it
     * the source address are chosen at connect(), so a marked packet left with
     * the tailnet address on the physical interface and no reply ever came
     * back. A uid rule is evaluated at connect() and fixes both — measured on
     * the APatch phone: `ip route get 1.1.1.1 uid <excluded>` went from
     * "dev tailscale0 table 52 src 100.92.68.28" to "via 192.168.1.254 dev
     * wlan0 src 192.168.1.94" while other uids kept using the tunnel.
     */
    private const val EXCLUDE_PRIO = "190"

    /** Android's protectedFromVpn bit; a socket carrying it is routed by the physical network. */
    private const val PROTECT_MARK = "0x20000"

    /**
     * The band netd keeps its own rules in: per-network rules, the VPN
     * membership rules (measured at 13000 and 16000-17000 on the APatch phone)
     * and the fallthrough rules above them. Nothing of ours lives here.
     *
     * Two things read this band. The exclusion rules jump to the lowest
     * priority found inside it rather than to a hardcoded 16000 — the number
     * differs between Android versions, and `goto` to a priority that holds no
     * rule is accepted by the kernel and then silently skipped. And the
     * coexistence probe looks here for another tunnel's per-uid rules, which is
     * the only trace a foreign VPN client leaves that a root shell can read.
     */
    private const val NETD_RULE_MIN = "10000"
    private const val NETD_RULE_MAX = "32000"

    /**
     * Destinations kept out of the exit node, as `throw` routes in the daemon's
     * table: the lookup falls through to Android's own rules, which send them
     * out of the physical interface.
     *
     * Without this, selecting an exit node in Root Mode takes the local network
     * with it — the router, a NAS, a printer, adb over Wi-Fi — because the
     * pref-200 catch-all matches every unmarked local packet, LAN included.
     * Measured on the APatch phone: `ip route get 192.168.1.100` answered
     * `dev tailscale0 table 52` until these were added. The list is the user's
     * own TUN exclusions, so both tunnel modes exclude the same things, plus
     * link-local, which nothing routes anyway.
     */
    private const val LINK_LOCAL_V4 = "169.254.0.0/16"
    private const val LINK_LOCAL_V6 = "fe80::/10"

    /** Accepts only a bare CIDR — the list comes from a user-editable setting. */
    private val CIDR_V4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")
    private val CIDR_V6 = Regex("^[0-9a-fA-F:]{2,45}/\\d{1,3}$")

    /** Pre-fix rp_filter value of `all`, kept so cleanup can restore it. */
    private const val RP_FILTER_ORIG = "$ROOT_ENV_DIR/rp_filter.orig"

    /**
     * Run boundaries in the daemon log. The daemon appends to one file across
     * runs and prints no start banner in this build (`ts_omit_logtail` drops
     * logpolicy and with it `Program starting:`), so "this run" is bounded by
     * the later of two lines:
     *  - [RUN_MARKER], which each launcher — the boot script (tailscaled.sh)
     *    and [startRootDaemon] — appends right before starting the daemon;
     *  - [DAEMON_START_LINE], which cmd/tailscaled logs unconditionally on every
     *    start, immediately before it creates the engine — and the engine's
     *    magicsock listener is what triggers the netns probe, so the probe's
     *    line always follows it within a run. This one also bounds a daemon
     *    that was started by hand (no [RUN_MARKER]), so an earlier run's line
     *    can never vouch for it.
     */
    private const val RUN_MARKER = "TailSocks: daemon start"
    private const val DAEMON_START_LINE = "wgengine.NewUserspaceEngine(tun"

    /** Lines the app or the boot script write into the daemon log carry this tag; none of them is daemon output. */
    private const val APP_LINE_TAG = "TailSocks:"

    /**
     * The netns probe (patch 16) logs exactly one line per run containing
     * [SO_MARK_LINE]: [SO_MARK_OK] on success, `netns: SO_MARK unavailable (…)`
     * otherwise. Daemon lines carry Go's `YYYY/MM/DD HH:MM:SS` prefix and a
     * component prefix such as `magicsock:` in front, so both are matched as
     * substrings — and the success match is the daemon's exact contiguous text,
     * never two loose tokens (a message that merely mentions both would
     * otherwise open the gate).
     */
    private const val SO_MARK_LINE = "netns: SO_MARK"
    private const val SO_MARK_OK = "netns: SO_MARK $BYPASS_MARK set on tailscaled sockets"

    /** Backward log scan in [soMarkVerdict]: bytes read per step, and how far back to look before giving up. */
    private const val SCAN_CHUNK = 1 shl 20
    private const val SCAN_LIMIT = 256L shl 20

    /** Markers the apply script prints when a catch-all did not land; distinct so v4 → WARN, v6 → INFO. */
    private const val V4_CATCH_ALL_MISSING = "verify: v4 exit-node catch-all NOT installed (ip dropped the fwmark mask)"
    private const val V6_CATCH_ALL_MISSING = "verify: v6 exit-node catch-all not installed"

    /**
     * Printed by the apply script when an `-m owner` rule was refused — the
     * match is a kernel module (`xt_owner`) and some ROM kernels ship without
     * it. Only the DNS half of the per-app exclusions is lost: the uid RETURN
     * rules at the top of [CHAIN_DNS] never land, so an excluded app's port 53
     * is redirected into MagicDNS like everyone else's. Its routing exclusion
     * is unaffected — that is a policy rule, not an iptables match — so the app
     * ends up outside the tunnel with its DNS inside it. Every other rule is
     * unaffected, so the script carries on and the caller turns this into a WARN.
     */
    private const val OWNER_MATCH_MISSING = "verify: -m owner unavailable, per-app DNS exclusions not installed"

    /**
     * Printed by the apply script when an exclusion rule was accepted with a
     * `goto` target the kernel could not resolve. `ip rule add ... goto <pref>`
     * never fails for a priority that holds no rule: the rule is installed,
     * printed with `[detached]` or `unresolved`, and skipped at lookup time —
     * so the excluded uid silently falls back into the catch-all. The script
     * deletes such a rule, installs the physical-table form instead and prints
     * this for the caller to surface.
     */
    private const val GOTO_UNRESOLVED = "verify: exclusion goto target unresolved, physical table used instead"

    /**
     * Log of the daemon launched by [startRootDaemon] and the file size just
     * before the launch. Lets [daemonMarksSockets] ignore lines from earlier runs
     * even when the caller has no Context; both stay unset when the app merely
     * attached to a daemon that was already running (boot script), in which case
     * the last [RUN_MARKER] line the script wrote bounds the current run instead.
     */
    @Volatile private var lastDaemonLogFile: File? = null
    @Volatile private var daemonLogStartOffset: Long = -1L

    /** Dedicated iptables chains. Owning named chains makes every rule we install
     *  idempotent, inspectable and removable in one shot — unlike appending to
     *  the shared OUTPUT/FORWARD chains, which accumulates duplicates. */
    private const val CHAIN_MARK = "TAILSOCKS_MARK"
    private const val CHAIN_DNS = "TAILSOCKS_DNS"

    /**
     * Legacy per-app exclusion chain, no longer installed — kept only so that
     * upgrades remove it.
     *
     * It gave every excluded uid [BYPASS_MARK] in mangle OUTPUT, hooked from
     * OUTPUT with no match of its own: the one rule we ever installed that
     * inspected every locally generated packet on the device. It was also
     * redundant — a mark set in mangle OUTPUT comes too late for TCP (the route
     * and the source address are chosen at connect()), which is why the pref-
     * [EXCLUDE_PRIO] uid rules were added in the first place, and those do the
     * whole job on their own.
     */
    private const val CHAIN_BYPASS = "TAILSOCKS_BYPASS"

    private const val CGNAT_V4 = "100.64.0.0/10"
    private const val TAILNET_V6 = "fd7a:115c:a1e0::/48"
    private const val MAGIC_DNS = "100.100.100.100"

    /** Result of a root shell invocation, including everything it printed. */
    data class SuResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private fun rootLog(level: String, message: String) {
        // Both, deliberately. The in-app ROOT tab is what a user reads; logcat is
        // the only copy reachable while debugging on a device, and the routing
        // decisions are exactly what one needs to see there.
        Log.i(TAG, "[$level] $message")
        try {
            appctr.Appctr.logAndroid(level, "ROOT", message)
        } catch (e: Exception) {
            // The Go bridge may not be loaded yet; the logcat line above stands.
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
            if (exitCode != 0 && tag.endsWith("-check")) {
                // Probes ([ -f script ] && echo exists) report "not installed" as a
                // non-zero exit; that is an answer, not a failure, and logging it as
                // ERROR made the ROOT tab look broken on every settings visit.
                Log.d(TAG, "[$tag] exit=$exitCode (not present)")
            } else if (exitCode != 0) {
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
    fun isDaemonAlive(socketPath: String): Boolean = probeSocket(socketPath) == null

    /**
     * Connects to the daemon socket once. Returns null on success, otherwise the
     * reason — [allowSocketConnect] needs to tell "the daemon is not there" from
     * "SELinux refused the connect", which look identical to [isDaemonAlive].
     */
    private fun probeSocket(socketPath: String): Exception? {
        if (!File(socketPath).exists()) return java.io.FileNotFoundException("no socket at $socketPath")
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                if (socket.isConnected) null else java.io.IOException("connect returned an unconnected socket")
            }
        } catch (e: Exception) {
            Log.d(TAG, "probeSocket: connect failed: ${e.message}")
            e
        }
    }

    /** A connect refused by policy, as opposed to a daemon that is simply not running. */
    private fun isPolicyDenial(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("EACCES") || msg.contains("Permission denied", ignoreCase = true)
    }

    /** SELinux type of a context like `u:r:untrusted_app_32:s0:c512,c768`. */
    private val selinuxTypeName = Regex("^[a-z][a-z0-9_]*$")

    private fun selinuxType(context: String): String? {
        val fields = context.replace("\u0000", "").trim().split(":")
        if (fields.size < 3) return null
        val type = fields[2]
        return if (selinuxTypeName.matches(type)) type else null
    }

    /** The domain this app runs in, straight from the kernel. */
    private fun appDomain(): String? = try {
        selinuxType(File("/proc/self/attr/current").readText())
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read own SELinux context: ${e.message}")
        null
    }

    /**
     * The domain the root daemon runs in. Matched by its `--socket=` argument,
     * like [stopRootDaemon], so a second profile or an unrelated tailscaled is
     * never sampled. Falls back to the domain of the root shell itself, which is
     * what the daemon inherits when it is spawned from one.
     */
    private fun daemonDomain(socketPath: String): String? {
        val pat = shQuote("--socket=$socketPath")
        val script = buildString {
            append("pid=\$(pgrep -f -- $pat 2>/dev/null | head -n1)\n")
            append("if [ -n \"\$pid\" ]; then cat /proc/\$pid/attr/current; else cat /proc/self/attr/current; fi\n")
        }
        val res = runSu("daemon-domain", script, timeoutMs = 10_000L)
        if (!res.ok) return null
        return res.output.lineSequence().firstNotNullOfOrNull { selinuxType(it) }
    }

    /**
     * Makes the app able to reach the root daemon socket when SELinux is what is
     * stopping it — and does nothing at all otherwise.
     *
     * The daemon is started from a root shell, so it runs in the root solution's
     * domain while the app connects from its own. Stock policy grants no
     * `connectto` across that pair, and the connect fails with EACCES.
     *
     * Up to 3.6 the fix was a fixed `allow untrusted_app magisk
     * unix_stream_socket connectto`, injected on every service start and on
     * every boot. It named domains nobody had looked up: `untrusted_app` is not
     * what an app with a modern targetSdk runs as, and `magisk` is not the
     * domain of a KernelSU or APatch daemon — so on many devices it patched the
     * policy of every untrusted app on the device and still did not fix
     * anything. Now both domains are read at runtime, and the rule is only
     * applied after a connect was actually denied.
     *
     * Returns true when the socket is reachable afterwards.
     */
    fun allowSocketConnect(socketPath: String): Boolean {
        val error = probeSocket(socketPath) ?: return true
        if (!isPolicyDenial(error)) return false
        return injectSocketConnectRule(socketPath) && isDaemonAlive(socketPath)
    }

    private fun injectSocketConnectRule(socketPath: String): Boolean {
        val app = appDomain()
        val daemon = daemonDomain(socketPath)
        if (app == null || daemon == null) {
            rootLog("ERROR", "SELinux denied the daemon socket, but the domains could not be read (app=$app daemon=$daemon)")
            return false
        }
        if (app == daemon) {
            rootLog("ERROR", "SELinux denied the daemon socket although both sides run as $app; not a domain-transition problem")
            return false
        }
        val rule = shQuote("allow $app $daemon unix_stream_socket connectto")
        // magiskpolicy (Magisk, and APatch which ships the same tool), then
        // KernelSU's ksud, then SuperSU's supolicy. Whichever exists wins; the
        // rule is live-only and gone after a reboot.
        val script = "magiskpolicy --live $rule 2>/dev/null || " +
            "ksud sepolicy patch $rule 2>/dev/null || " +
            "supolicy --live $rule 2>/dev/null"
        val res = runSu("sepolicy-allow", script, timeoutMs = 10_000L)
        if (!res.ok) {
            rootLog("ERROR", "No SELinux policy tool accepted: allow $app $daemon unix_stream_socket connectto")
            return false
        }
        rootLog("INFO", "SELinux denied the app -> daemon socket connect; injected: allow $app $daemon unix_stream_socket connectto")
        return true
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
            // Everything the daemon appends from here on belongs to this run.
            lastDaemonLogFile = File(logFile)
            daemonLogStartOffset = File(logFile).length()

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
            // The daemon has no VpnService to protect its sockets, so it sets
            // Android's protect bit itself unless the user wants the opposite:
            // its own traffic riding whatever VPN client holds the tunnel.
            if (!GlobalSettings.isRootVpnBypassEnabled(context)) {
                env.append("export TS_VPN_BYPASS=0\n")
            }

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
            // Run marker first: everything the daemon prints in this run follows
            // it, which is what daemonMarksSockets keys on (the boot script writes
            // the same line). A missing marker means "unverified", never "marks".
            sb.append("echo ${shQuote(RUN_MARKER)} >> ${shQuote(logFile)}\n")
            sb.append("nohup $cmd >> ${shQuote(logFile)} 2>&1 &\n")
            // The daemon runs as root, so the file is root-owned; 0644 lets the
            // app read it for the Logs screen. It sits in the app's private data
            // directory (0700), so nothing else can. 0600 left the Logs screen
            // empty in Root Mode.
            sb.append("chmod 644 ${shQuote(logFile)} 2>/dev/null || true\n")
            // No SELinux rule is injected here any more: allowSocketConnect adds
            // one below, with the real domains, and only if a connect is denied.
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
            var policyPatched = false
            while (attempts < 25) {
                val error = probeSocket(socketPath)
                if (error == null) {
                    Log.i(TAG, "Root daemon socket is accepting connections at $socketPath")
                    rootLog("INFO", "Root daemon started (socket ready)")
                    return true
                }
                // The socket exists and the kernel refused us: that is policy,
                // not a daemon that failed to start. Fix it once, then keep
                // probing as before.
                if (!policyPatched && isPolicyDenial(error)) {
                    policyPatched = true
                    if (injectSocketConnectRule(socketPath) && isDaemonAlive(socketPath)) {
                        rootLog("INFO", "Root daemon started (socket ready)")
                        return true
                    }
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
     * @param context           used to locate the daemon log, which decides whether
     *                          the exit-node catch-all (pref 200 → table 52) may be
     *                          installed: only a daemon that has logged that it marks
     *                          its sockets gets it. Without a Context the log of the
     *                          daemon [startRootDaemon] launched is used; if there is
     *                          none either, the catch-all is skipped with a WARN.
     * @param foreignVpn        the caller's ConnectivityManager answer to "does another
     *                          app hold Android's VPN slot?", or null when it has none.
     *                          It is OR-ed with [detectForeignVpn], which sees a tunnel
     *                          this app is not a member of — the case ConnectivityManager
     *                          cannot report.
     * @param takeDeviceAnyway  the user's sticky override. Installs the full ruleset even
     *                          while another tunnel holds the device, which breaks that
     *                          tunnel's apps; named in the log every time it is used.
     *
     * The install is tiered. Tailnet reachability (the pref-100 rules, the
     * mangle mark chain, the tailscale0 FORWARD pair) is installed
     * unconditionally: it is scoped to tailnet destinations and sits below
     * netd's rules, so it coexists with any other tunnel by construction.
     * Default-route capture (the pref-[CATCH_ALL_PRIO] catch-all, the LAN throw
     * routes, the rp_filter loosening) and the device-wide DNS redirect claim
     * the whole device and have exactly one owner: when another VPN holds the
     * slot both are yielded whole, and anything an earlier apply or the boot
     * script left behind is actively removed rather than merely not installed.
     * The per-app exclusion rules go with them — they exist only to carve uids
     * out of the catch-all, and jumping a uid over netd's rules while we route
     * nothing takes it out of the foreign tunnel too.
     */
    fun applyTailscale0Routing(
        dnsRedirect: Boolean = true,
        dnsBypassAddrs: List<String> = emptyList(),
        context: Context? = null,
        foreignVpn: Boolean? = null,
        takeDeviceAnyway: Boolean = false,
        probeResult: ForeignVpnVerdict? = null
    ): Boolean {
        val logFile = context?.let { rootDaemonLogFile(it) } ?: lastDaemonLogFile
        var verdict = logFile?.let { soMarkVerdict(it) } ?: SoMarkVerdict(false, "no daemon log to check")
        // A log that never carried the probe line is silence, not a "no". Ask the
        // process itself before withholding the exit node. An explicit negative
        // from the daemon ("daemon logged: ...") is left alone.
        if (!verdict.marks && !verdict.reason.startsWith("daemon logged")) {
            daemonMarksByEnviron()?.let { verdict = it }
        }
        val marks = verdict.marks
        if (!marks) {
            rootLog(
                "WARN",
                "exit node unavailable: daemon does not mark sockets (${verdict.reason}); " +
                    "the pref-$CATCH_ALL_PRIO catch-all is not installed, tailnet routing is unaffected"
            )
        }

        // Two independent detectors, OR-ed: the caller's ConnectivityManager
        // read only sees a tunnel this app is a member of, the root-side probe
        // only one that left rules behind. Either is enough to yield — the safe
        // direction is "someone else owns the device", never the reverse.
        // The caller may have run the probe already to decide whether anything
        // changed; running it twice would answer the same question with a second
        // root shell, and could answer it differently mid-flight.
        val probe = probeResult ?: detectForeignVpn()
        val foreignPresent = foreignVpn == true || probe.present
        val foreignReason = when {
            foreignVpn == true && probe.present -> "${probe.reason}; the system also reports a VPN transport"
            foreignVpn == true -> "the system reports a VPN transport, ${probe.reason}"
            else -> probe.reason
        }
        // Owning the device means owning the default route and port 53 on it.
        val ownDevice = !foreignPresent || takeDeviceAnyway
        // What the other tunnel leaves on the table: the apps its owner told it
        // to bypass. Android hands those to the physical network, so carrying
        // them costs that tunnel nothing — and it is the whole point of sharing
        // a device rather than taking turns on it.
        val selfUid = context?.applicationInfo?.uid?.toLong()
        val gaps = if (ownDevice) emptyList() else foreignBypassGaps(probe.memberRanges, selfUid)
        val shared = !ownDevice && gaps.isNotEmpty()
        val installCatchAll = marks && (ownDevice || shared)
        val installDns = dnsRedirect && (ownDevice || shared)
        // Its own resolution must stay its own. Matching by uid cannot do this —
        // an app's getaddrinfo is sent by the platform resolver, not by the app —
        // but netd stamps the network id into the mark, and that survives.
        val dnsExemptNetIds = if (ownDevice) emptyList() else probe.netIds
        // The inputs behind the decision, named. Without this the difference
        // between "nobody else is here", "they took everything" and "we could
        // not read what they took" is invisible from a device log, and the
        // three call for very different fixes.
        rootLog(
            "INFO",
            "coexistence inputs: marks=$marks, foreign=$foreignPresent, override=$takeDeviceAnyway, " +
                "ranges=${probe.memberRanges.size}, gaps=${gaps.size}" +
                (if (gaps.isEmpty()) "" else " (" + gaps.joinToString(", ") { "${it.first}-${it.last}" } + ")") +
                ", netIds=" + (if (probe.netIds.isEmpty()) "none" else probe.netIds.joinToString(", ") { "0x%x".format(it) })
        )
        if (!foreignPresent) {
            rootLog("INFO", "coexistence: this device is ours ($foreignReason); installing every tier")
        } else if (takeDeviceAnyway) {
            rootLog(
                "WARN",
                "another tunnel holds this device ($foreignReason), but \"take the device anyway\" is on: " +
                    "installing the exit-node catch-all and the device-wide DNS redirect regardless — " +
                    "the other tunnel's apps lose their traffic and their name resolution while TailSocks runs"
            )
        } else if (shared) {
            rootLog(
                "INFO",
                "another tunnel holds this device ($foreignReason), and it does not claim every app: " +
                    "carrying the ${gaps.size} uid range(s) it bypasses (${gaps.joinToString(", ") { "${it.first}-${it.last}" }}) " +
                    "through the exit node, leaving its own apps and its DNS" +
                    (if (dnsExemptNetIds.isEmpty()) "" else " (netId " + dnsExemptNetIds.joinToString(", ") { "0x%x".format(it) } + ")") +
                    " to it"
            )
        } else {
            rootLog(
                "WARN",
                "another tunnel holds this device ($foreignReason): installing tailnet reachability only. " +
                    "No exit-node catch-all, no device-wide DNS redirect, no per-app exclusion rules, and " +
                    "rp_filter is put back. Tailnet addresses, MagicDNS names and the loopback proxies keep " +
                    "working; exit nodes and system-wide MagicDNS do not. Turn on \"take the device anyway\" " +
                    "to override, at that tunnel's expense"
            )
        }

        // Root Mode is device-wide by construction; these uids are the user's
        // opt-out and are resolved fresh on every apply (an app can be
        // installed, removed or reinstalled with another uid between runs).
        val bypassUids = excludedUids(context)
        // Resolved once: the same list installs the throw routes and, when we
        // yield, removes exactly the ones we wrote — never the daemon's own.
        val localV4 = localExclusions(context, v6 = false)
        val localV6 = localExclusions(context, v6 = true)

        val sb = StringBuilder()
        sb.append(legacyRuleCleanup())

        // --- IPv4 policy routing ---
        sb.append("ip route replace $CGNAT_V4 dev tailscale0 table $ROUTE_TABLE metric 1\n")
        sb.append("ip rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip rule add fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE priority 100\n")
        // A destination rule as well as the fwmark one: the mark is applied in
        // mangle OUTPUT, AFTER the kernel has already picked the source address
        // from the default (Wi-Fi/cellular) table. A root-owned socket — the
        // daemon's own DNS forwarder talking to a split-DNS resolver on a peer —
        // therefore left tailscale0 with the Wi-Fi address as source and never
        // got an answer. Matching on destination makes the first lookup land in
        // table 1099, so the source is the tailnet address.
        //
        // `iif lo` scopes it to locally generated packets: without it we also
        // claimed packets being forwarded through this device (a subnet router,
        // a tethered client) and answered them with our own source address.
        // No fwmark selector here on purpose — that very DNS forwarder socket is
        // unmarked, and a marked-only rule would send it back to the physical
        // table, which is the bug this rule exists to fix.
        sb.append("while ip rule del to $CGNAT_V4 iif lo table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del to $CGNAT_V4 table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("ip rule add to $CGNAT_V4 iif lo table $ROUTE_TABLE priority 100\n")

        sb.append("iptables -t mangle -N $CHAIN_MARK 2>/dev/null || iptables -t mangle -F $CHAIN_MARK\n")
        sb.append("iptables -t mangle -A $CHAIN_MARK -j MARK --set-xmark $MARK_BIT/$MARK_MASK\n")
        sb.append("iptables -t mangle -C OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || iptables -t mangle -A OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK\n")

        sb.append("iptables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o tailscale0 -j ACCEPT\n")
        sb.append("iptables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i tailscale0 -j ACCEPT\n")

        // --- Per-app exclusions: uid rules, both families in one call ---
        // The teardown runs whatever we install: it clears the rules of an
        // earlier apply and the mangle chain of an earlier version. The install
        // only follows the catch-all — an exclusion is a hole in the catch-all,
        // and without one it would just take the uid out of the foreign tunnel.
        sb.append(bypassChainTeardown("iptables"))
        sb.append(excludeRulesTeardown())
        if (installCatchAll) sb.append(excludeRulesInstall(bypassUids))

        // --- Exit-node catch-all (IPv4): unmarked local traffic → daemon's table 52 ---
        sb.append(staleDaemonRulePurge("ip"))
        if (installCatchAll) {
            sb.append(catchAllInstall("ip", V4_CATCH_ALL_MISSING, bestEffort = false, scope = gaps))
            sb.append(localBypassRoutes("ip", localV4))
            sb.append(rpFilterLoosen())
        } else {
            // Skipping the install removes nothing: the delete loop lives inside
            // catchAllInstall, so a rule from an earlier apply or from the boot
            // script (which has no VPN check at all) would survive untouched and
            // keep capturing the other tunnel's apps.
            sb.append(catchAllTeardown("ip"))
            sb.append(localBypassRoutesRemove("ip", localV4))
            // Loose reverse-path filtering exists only because table 52 is
            // consulted for unmarked lookups. With the catch-all gone it is a
            // gratuitous system-wide change, so it goes back.
            sb.append(rpFilterRestore())
        }

        // --- IPv6 policy routing ---
        sb.append("ip -6 route replace $TAILNET_V6 dev tailscale0 table $ROUTE_TABLE metric 1 2>/dev/null || true\n")
        sb.append("ip -6 rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null || true\n")
        sb.append("ip -6 rule add fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE priority 100 2>/dev/null || true\n")
        // `iif lo` for the same reason as the v4 twin above, and no fwmark
        // selector for the same reason either.
        sb.append("while ip -6 rule del to $TAILNET_V6 iif lo table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del to $TAILNET_V6 table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("ip -6 rule add to $TAILNET_V6 iif lo table $ROUTE_TABLE priority 100 2>/dev/null || true\n")

        sb.append("ip6tables -t mangle -N $CHAIN_MARK 2>/dev/null || ip6tables -t mangle -F $CHAIN_MARK\n")
        sb.append("ip6tables -t mangle -A $CHAIN_MARK -j MARK --set-xmark $MARK_BIT/$MARK_MASK 2>/dev/null || true\n")
        sb.append("ip6tables -t mangle -C OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || ip6tables -t mangle -A OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || true\n")

        sb.append("ip6tables -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || true\n")
        sb.append("ip6tables -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || ip6tables -I FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || true\n")

        // --- Per-app exclusions (IPv6): the v6 uid rules are emitted above ---
        sb.append(bypassChainTeardown("ip6tables"))

        // --- Exit-node catch-all (IPv6) ---
        // The purge is harmless anywhere; the rule itself only where IPv6 exists
        // and is enabled, otherwise `ip -6 rule add` fails on every apply and the
        // marker would raise a false alarm each time.
        sb.append(staleDaemonRulePurge("ip -6"))
        if (installCatchAll) {
            sb.append("if [ -d /proc/sys/net/ipv6 ] && [ \"\$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null)\" = \"0\" ]; then\n")
            sb.append(catchAllInstall("ip -6", V6_CATCH_ALL_MISSING, bestEffort = true, scope = gaps))
            sb.append(localBypassRoutes("ip -6", localV6))
            sb.append("fi\n")
        } else {
            // No IPv6 guard on the removal: a rule can only be there if IPv6 was
            // up when it was installed, and deleting what is not there is free.
            sb.append(catchAllTeardown("ip -6"))
            sb.append(localBypassRoutesRemove("ip -6", localV6))
        }

        // --- System-wide DNS redirect ---
        // The teardown is unconditional, so yielding this tier is expressed by
        // not installing it: the chain and both nat OUTPUT jumps are gone by the
        // time the script reaches here, whoever put them there.
        sb.append(dnsChainTeardown())
        if (installDns) {
            sb.append("iptables -t nat -N $CHAIN_DNS 2>/dev/null || iptables -t nat -F $CHAIN_DNS\n")
            // First, before anything else in the chain: whatever the other
            // tunnel resolves for its own apps goes through untouched. netd
            // stamps its network id into the low 16 bits of the mark, and it is
            // still there when the platform resolver sends the query on an
            // app's behalf — which is the only reason this can work at all,
            // since the uid on that packet is the resolver's, not the app's.
            for (netId in dnsExemptNetIds) {
                sb.append(
                    "iptables -t nat -A $CHAIN_DNS -m mark --mark 0x%x/0xffff -j RETURN 2>/dev/null || true\n"
                        .format(netId)
                )
            }
            // The apps the user carved out of Root Mode. This covers only the
            // ones that resolve for themselves — Chrome's async resolver, DoH
            // and DoT clients, curl. An ordinary app's getaddrinfo is sent by
            // the platform resolver under its own uid, so a uid match cannot
            // see it, and those names still go to MagicDNS. Measured: this
            // RETURN's counter stays at zero while the DNAT's climbs.
            for (uid in bypassUids) {
                sb.append("iptables -t nat -A $CHAIN_DNS -m owner --uid-owner $uid -j RETURN 2>/dev/null || echo '$OWNER_MATCH_MISSING'\n")
            }
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
        if (installDns) {
            sb.append("iptables -t nat -S $CHAIN_DNS >/dev/null 2>&1 || { echo 'verify: $CHAIN_DNS chain missing'; exit 1; }\n")
        }
        sb.append("echo 'verify: ok'\n")

        val res = runSu("routing-apply", sb.toString())
        // Checked before the exit code: an ignored -m owner rule cannot fail the
        // script (it is `|| echo`), but it must never pass unnoticed either.
        if (res.output.contains(OWNER_MATCH_MISSING)) {
            rootLog(
                "WARN",
                "per-app DNS exclusions NOT applied: this kernel has no iptables `-m owner` match, so the " +
                    "excluded apps' DNS is redirected into MagicDNS like every other app's" +
                    (if (installCatchAll) ", while their traffic is routed out of the tunnel — an app outside " +
                        "the tunnel resolving inside it" else "") +
                    ". Turn the system-wide DNS redirect off to give them the system resolver back"
            )
        }
        if (res.output.contains(GOTO_UNRESOLVED)) {
            rootLog(
                "WARN",
                "per-app exclusions: this device's routing rules could not be jumped over, so the excluded " +
                    "apps are pinned to the physical network instead; while another VPN is up they will not " +
                    "ride it either"
            )
        }
        if (bypassUids.isNotEmpty()) {
            val what = when {
                installCatchAll -> "skip the exit node (their DNS still goes to MagicDNS unless they resolve for themselves)"
                installDns -> "are carved out of the DNS redirect, as far as a uid match can reach"
                else -> "need no rules of their own: nothing on this device is captured"
            }
            rootLog("INFO", "per-app exclusions: uid ${bypassUids.joinToString(",")} $what")
        }
        if (res.ok) {
            // The catch-all verify is non-fatal (tailnet routing does not depend on
            // it) but must not pass silently: a missing v4 rule means no exit node.
            val v4Missing = res.output.contains(V4_CATCH_ALL_MISSING)
            val v6Missing = res.output.contains(V6_CATCH_ALL_MISSING)
            if (v4Missing) {
                rootLog(
                    "WARN",
                    "exit-node catch-all (pref $CATCH_ALL_PRIO -> table $DAEMON_TABLE) NOT installed: this ip binary " +
                        "dropped the fwmark mask and a maskless rule would loop the daemon's own traffic; exit nodes unavailable"
                )
            }
            if (v6Missing) {
                rootLog("INFO", "IPv6 exit-node catch-all not installed (ip -6 rule add refused); IPv6 traffic will not follow an exit node")
            }
            val catchAll = when {
                shared -> "scoped to the ${gaps.size} uid range(s) the other tunnel bypasses"
                !ownDevice -> "yielded, another tunnel holds the device"
                !marks -> "skipped, daemon does not mark sockets"
                v4Missing -> "not installed, mask dropped"
                v6Missing -> "v4 only"
                else -> "installed"
            }
            val dnsState = when {
                installDns && shared -> "on, minus the other tunnel's own network"
                installDns -> "on"
                !dnsRedirect -> "off"
                else -> "yielded, another tunnel holds the device"
            }
            val tiers = when {
                ownDevice -> "tailnet + default route + device-wide DNS"
                shared -> "tailnet + the apps the other tunnel bypasses"
                else -> "tailnet only"
            }
            rootLog(
                "INFO",
                "tailscale0 routing applied, $tiers (table $ROUTE_TABLE, exit-node catch-all pref $CATCH_ALL_PRIO -> table $DAEMON_TABLE: $catchAll, " +
                    "dns redirect=$dnsState" +
                    if (dnsBypassAddrs.isEmpty()) ")" else ", bypass=${dnsBypassAddrs.joinToString(",")})"
            )
        }
        return res.ok
    }

    /** Outcome of the root-side coexistence probe: [present] is the gate, [reason] is what the ROOT log and Check Routing show. */
    /**
     * @param memberRanges uid ranges the other tunnel claims, exactly as netd
     *                     published them. The gaps between them are the apps it
     *                     bypasses — the ones we may still carry.
     * @param netIds       network ids of the other tunnel, from the fwmark on
     *                     its own rules. Used to leave its DNS alone.
     */
    class ForeignVpnVerdict(
        val present: Boolean,
        val reason: String,
        val memberRanges: List<LongRange> = emptyList(),
        val netIds: List<Int> = emptyList()
    )

    /**
     * A stable description of what the other tunnel is currently claiming.
     *
     * Folded into the apply's signature: "some VPN is present" does not change
     * when its owner moves an app in or out of its bypass list, but the rules we
     * build from it do, and without this the re-read would be discarded as a
     * repeat of the last apply.
     */
    fun foreignRoutingShape(verdict: ForeignVpnVerdict?): String {
        if (verdict == null || !verdict.present) return "none"
        val ranges = verdict.memberRanges.sortedBy { it.first }.joinToString(",") { "${it.first}-${it.last}" }
        val ids = verdict.netIds.sorted().joinToString(",")
        return "$ranges;$ids"
    }

    /** Highest uid netd writes rules for; the ranges are dense below it. */
    private const val MAX_ANDROID_UID = 99_999L

    /**
     * The uids the other tunnel does *not* claim.
     *
     * netd publishes VPN membership as uid ranges; everything between them is an
     * app its owner put in the bypass list, which Android then routes over the
     * physical network. Those are the apps we can still carry without touching
     * anything that belongs to the other tunnel — which is what makes coexisting
     * something better than "we switch off".
     *
     * Our own uid is dropped: the other tunnel excludes us so we always show up
     * in a gap, and routing the app's own traffic into our table buys nothing.
     */
    private fun foreignBypassGaps(ranges: List<LongRange>, selfUid: Long?): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val gaps = mutableListOf<LongRange>()
        var next = 0L
        for (r in sorted) {
            if (r.first > next) gaps.add(next..(r.first - 1))
            if (r.last + 1 > next) next = r.last + 1
        }
        if (next <= MAX_ANDROID_UID) gaps.add(next..MAX_ANDROID_UID)
        if (selfUid == null) return gaps
        // Split around our own uid rather than dropping the whole gap it sits in.
        return gaps.flatMap { g ->
            if (selfUid !in g) listOf(g) else buildList {
                if (g.first <= selfUid - 1) add(g.first..(selfUid - 1))
                if (selfUid + 1 <= g.last) add((selfUid + 1)..g.last)
            }
        }
    }

    /**
     * Looks for another VPN client that owns this device, from a root shell and
     * without a Context.
     *
     * ConnectivityManager can only report a VPN transport the calling app is a
     * member of, so a tunnel that excludes TailSocks is invisible to it — and
     * that is exactly the tunnel we would break. Two independent traces are
     * cross-checked instead, and both must be there:
     *  - a tunnel interface that is not ours. Only `tun`/`ppp`/`wg`/`ipsec`
     *    names count, so our own `tailscale0` can never match; a name is never
     *    proof on its own either (a stopped client leaves its interface behind,
     *    and a kernel WireGuard tunnel may be routed by hand).
     *  - a per-uid rule inside netd's own band pointing at a table that is
     *    neither ours nor a physical one. That is how netd expresses "these apps
     *    are members of a VPN", and nothing else on the device writes it.
     *
     * The physical tables are read back through Android's protectedFromVpn mark,
     * the same way [excludeRulesInstall] finds them, so the Wi-Fi and mobile
     * tables are never mistaken for a tunnel's.
     */
    fun detectForeignVpn(): ForeignVpnVerdict {
        val script = buildString {
            append("PHYS=$(ip route get 1.1.1.1 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            append("PHYS6=$(ip -6 route get 2001:4860:4860::8888 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            append("ip -o link show 2>/dev/null | grep -Eo '(tun[0-9]+|ppp[0-9]+|wg[0-9]+|ipsec[0-9]+)' | sort -u | while read -r n; do\n")
            append("    echo \"FOREIGN_IF \$n\"\n")
            append("done\n")
            // `ip rule show` prints one rule per line, priority first; the table
            // is the word after `lookup`. Parsed with shell builtins only —
            // awk is not on every Android image.
            for (ip in listOf("ip", "ip -6")) {
                append("$ip rule show 2>/dev/null | while read -r p rest; do\n")
                append("    p=\${p%:}\n")
                append("    case \"\$p\" in ''|*[!0-9]*) continue;; esac\n")
                append("    [ \"\$p\" -ge $NETD_RULE_MIN ] || continue\n")
                append("    [ \"\$p\" -le $NETD_RULE_MAX ] || continue\n")
                append("    case \"\$rest\" in *uidrange*) ;; *) continue;; esac\n")
                append("    case \"\$rest\" in *'lookup '*) ;; *) continue;; esac\n")
                append("    t=\${rest##*lookup }\n")
                append("    t=\${t%% *}\n")
                append("    case \"\$t\" in $ROUTE_TABLE|$DAEMON_TABLE|main|local|default|tailscale0|'') continue;; esac\n")
                append("    [ \"\$t\" = \"\$PHYS\" ] && continue\n")
                append("    [ \"\$t\" = \"\$PHYS6\" ] && continue\n")
                append("    u=\"\"; case \"\$rest\" in *'uidrange '*) u=\${rest#*uidrange }; u=\${u%% *};; esac\n")
                append("    m=\"\"; case \"\$rest\" in *'fwmark '*) m=\${rest#*fwmark }; m=\${m%% *};; esac\n")
                append("    [ -n \"\$u\" ] && echo \"FOREIGN_UIDS \$t \$u\"\n")
                append("    [ -n \"\$m\" ] && echo \"FOREIGN_MARK \$t \$m\"\n")
                append("    echo \"FOREIGN_RULE \$p \$t\"\n")
                append("done\n")
            }
        }
        val res = runSu("coexist-probe", script, timeoutMs = 10_000L)
        if (!res.ok) {
            // A probe we could not run is not evidence of a free device, but
            // refusing to install anything on it would be worse: the same root
            // shell failure would already have broken the apply itself.
            return ForeignVpnVerdict(false, "the coexistence probe could not be run")
        }
        fun values(tag: String) = res.output.lineSequence()
            .filter { it.startsWith("$tag ") }
            .map { it.removePrefix("$tag ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val ifaces = values("FOREIGN_IF")
        // Membership counts only when it points at the tunnel itself. netd names
        // a network's table after its interface, so "lookup tun0" is the other
        // client's and "lookup wlan0_local" is not — and that distinction is
        // load-bearing: the physical *local* table carries a uidrange spanning
        // every uid on the device, and folding it in leaves no gaps at all,
        // which silently turns the partial yield back into a full one.
        fun forTunnel(tag: String) = values(tag).mapNotNull { line ->
            val table = line.substringBefore(' ').trim()
            val rest = line.substringAfter(' ', "").trim()
            if (table.isEmpty() || rest.isEmpty() || table !in ifaces) null else rest
        }
        // "10355-10374" as netd printed it. A malformed line is dropped rather
        // than guessed at: a wrong range would hand the other tunnel's apps to us.
        val memberRanges = forTunnel("FOREIGN_UIDS").mapNotNull { spec ->
            val parts = spec.split("-")
            if (parts.size != 2) return@mapNotNull null
            val lo = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
            val hi = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            if (lo > hi) null else lo..hi
        }.distinct()
        // "0x10084/0x1ffff" -> netId 0x84. The low 16 bits are netd's network id;
        // the bits above it (explicitly-selected, permission) vary per rule.
        val netIds = forTunnel("FOREIGN_MARK").mapNotNull { spec ->
            val value = spec.substringBefore('/').trim().removePrefix("0x").removePrefix("0X")
            value.toIntOrNull(16)?.and(0xffff)
        }.filter { it != 0 }.distinct()
        // One rule per uid range, so a tunnel with many member apps prints many
        // identical pref/table pairs; the log only needs to name the tunnel.
        val rules = values("FOREIGN_RULE").take(4)
        return when {
            ifaces.isNotEmpty() && rules.isNotEmpty() -> ForeignVpnVerdict(
                true,
                "${ifaces.joinToString(", ")} is up and this device's routing sends apps to it (${rules.joinToString("; ")})",
                memberRanges,
                netIds
            )
            ifaces.isNotEmpty() -> ForeignVpnVerdict(
                false,
                "${ifaces.joinToString(", ")} exists but no app is routed into it"
            )
            rules.isNotEmpty() -> ForeignVpnVerdict(
                false,
                "apps are routed to ${rules.joinToString("; ")} but no tunnel interface is up"
            )
            else -> ForeignVpnVerdict(false, "no other tunnel interface and no foreign per-app routing rules")
        }
    }

    /** Removes every rule this app installs, including those from older versions. */
    fun cleanupTailscale0Routing(): Boolean {
        val sb = StringBuilder()
        sb.append(dnsChainTeardown())
        sb.append(bypassChainTeardown("iptables"))
        sb.append(bypassChainTeardown("ip6tables"))
        sb.append(excludeRulesTeardown())

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
        // Both spellings of the destination rule: the selector-less delete
        // matches the `iif lo` form too on every kernel we ship against, but an
        // orphaned pref-100 rule is not worth trusting that to.
        sb.append("while ip rule del to $CGNAT_V4 iif lo table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del to $TAILNET_V6 iif lo table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del to $CGNAT_V4 table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del to $TAILNET_V6 table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del fwmark $LEGACY_MARK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip rule del fwmark $LEGACY_MARK lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $LEGACY_MARK table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        sb.append("while ip -6 rule del fwmark $LEGACY_MARK lookup $ROUTE_TABLE 2>/dev/null; do :; done\n")

        // Exit-node catch-all, stale desktop rules and the rp_filter change. Table
        // 52 itself is left alone on purpose: it belongs to the daemon (which may
        // keep running with "Terminate Root Daemon on Stop" off); router.Close()
        // deletes its throw routes and the kernel drops the rest with tailscale0.
        sb.append(catchAllTeardown("ip"))
        sb.append(catchAllTeardown("ip -6"))
        // The local-network bypass we wrote into the daemon's table. Everything
        // except the daemon's own loopback throw goes; a range the daemon threw
        // for its own reasons would be re-added at its next Reconfig, and with
        // the pref-$CATCH_ALL_PRIO rule gone nothing consults this table anyway.
        sb.append(
            "ip route show table $DAEMON_TABLE 2>/dev/null | while read t c rest; do\n" +
                "    [ \"\$t\" = throw ] || continue\n" +
                "    [ \"\$c\" = 127.0.0.0/8 ] && continue\n" +
                "    ip route del throw \"\$c\" table $DAEMON_TABLE 2>/dev/null || true\n" +
                "done\n"
        )
        sb.append(
            "ip -6 route show table $DAEMON_TABLE 2>/dev/null | while read t c rest; do\n" +
                "    [ \"\$t\" = throw ] || continue\n" +
                "    ip -6 route del throw \"\$c\" table $DAEMON_TABLE 2>/dev/null || true\n" +
                "done\n"
        )
        sb.append(staleDaemonRulePurge("ip"))
        sb.append(staleDaemonRulePurge("ip -6"))
        sb.append(rpFilterRestore())

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

    /**
     * Deletes the desktop-Linux rules a pre-4.0 core left behind (a killed
     * daemon never ran Close(); a stale `5270: from all lookup 52` would swallow
     * the new daemon's marked packets). Matched by content, not by priority
     * alone, so a third-party rule that happens to sit at 52xx survives; a no-op
     * when nothing is there. [ip] is `ip` or `ip -6`.
     */
    private fun staleDaemonRulePurge(ip: String): String = buildString {
        append("while $ip rule del pref 5210 lookup main 2>/dev/null; do :; done\n")
        append("while $ip rule del pref 5230 lookup default 2>/dev/null; do :; done\n")
        append("while $ip rule del pref 5250 type unreachable 2>/dev/null; do :; done\n")
        append("while $ip rule del pref 5270 lookup $DAEMON_TABLE 2>/dev/null; do :; done\n")
    }

    /**
     * Installs the catch-all `fwmark 0x0/0x2020000 iif lo lookup 52 priority 200`:
     * every locally generated packet without the daemon's bypass bit or Android's
     * protectedFromVpn bit consults the daemon's table first (peer /32s, subnet
     * routes, exit-node default, LAN throws) and falls through to netd when
     * nothing matches. `iif lo` = output lookups only, so forwarded and tethered
     * packets stay on netd's rules. The old rule is removed by content first
     * (any pref-200 rule pointing at table 52, so an earlier mask variant goes
     * too), and the result is read back: a rule that prints without the mask
     * (bare `fwmark 0x0` / `fwmark 0`) has kernel mask 0 and matches EVERY
     * packet, daemon included — that is a routing loop, so it is deleted again
     * and [missingMarker] is printed for the caller to surface. iproute2 4.x
     * prints the zero mark as `0x0`, 5.x and later (`%#llx`) as a bare `0`;
     * the pattern accepts both.
     */
    /**
     * The user's TUN exclusions plus link-local, filtered to plain CIDRs of the
     * right family. A Context is needed to read the setting; without one only
     * link-local is excluded, which keeps the shell script valid but leaves the
     * LAN inside the tunnel — callers pass a Context.
     */
    private fun localExclusions(context: Context?, v6: Boolean): List<String> {
        val fromSettings = context?.let { GlobalSettings.getTunExcludedCIDRs(it) } ?: ""
        val all = (fromSettings.split(',', ';', ' ', '\n') + listOf(LINK_LOCAL_V4, LINK_LOCAL_V6))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val wanted = all.filter { if (v6) it.contains(':') else !it.contains(':') }
        val valid = wanted.filter { if (v6) CIDR_V6.matches(it) else CIDR_V4.matches(it) }
        val dropped = wanted - valid.toSet()
        if (dropped.isNotEmpty()) {
            rootLog("WARN", "exit-node exclusions ignored, not plain CIDRs: ${dropped.joinToString(", ")}")
        }
        return valid.distinct()
    }

    /**
     * The user's excluded apps as uids, resolved at apply time.
     *
     * The same list the TUN mode hands to `addDisallowedApplication`, so one
     * setting governs both tunnel modes. A package that is no longer installed
     * is skipped and named in the log rather than failing the apply, and
     * without a Context (the boot path) nothing is excluded — the device-wide
     * behaviour, which is what earlier versions always did.
     */
    private fun excludedUids(context: Context?): List<Int> {
        if (context == null) return emptyList()
        val pkgs = GlobalSettings.getTunExcludedApps(context).map { it.trim() }.filter { it.isNotEmpty() }
        if (pkgs.isEmpty()) return emptyList()
        val pm = context.packageManager
        val uids = mutableListOf<Int>()
        val skipped = mutableListOf<String>()
        for (pkg in pkgs.sorted()) {
            try {
                uids.add(pm.getApplicationInfo(pkg, 0).uid)
            } catch (e: Exception) {
                skipped.add(pkg)
            }
        }
        if (skipped.isNotEmpty()) {
            rootLog("INFO", "excluded apps not installed, skipped: ${skipped.joinToString(", ")}")
        }
        return uids.distinct().sorted()
    }

    /**
     * Hands every excluded uid back to Android's own routing instead of forcing
     * it onto one interface.
     *
     * A `goto` into netd's band jumps over the pref-[CATCH_ALL_PRIO] catch-all
     * and lands on netd's rules, so the app ends up wherever the platform says
     * it belongs — inside another VPN client's tunnel when one is up, on the
     * physical network when none is. Forcing `lookup <physical table>` instead
     * took the app out of that VPN as well, which is why Chrome went dark with
     * a "bad config" DNS error while its tunnel client saw no requests at all:
     * we had already claimed its packets.
     *
     * The target is discovered, not assumed: the lowest priority at or above
     * [NETD_RULE_MIN] that this device actually has a rule at. 16000 used to be
     * hardcoded, and a `goto` to a priority holding no rule is a rule the
     * kernel accepts, prints as `[detached]` or `unresolved`, and then skips at
     * lookup time — so the exclusion looked installed and did nothing. Every
     * rule is therefore read back, and a detached one is deleted and replaced
     * by the physical-table form with [GOTO_UNRESOLVED] printed for the caller.
     *
     * The pref-100 rules stay above this, so an excluded app still reaches
     * tailnet addresses. Being priority-based and network-agnostic, the rule
     * needs no refresh when Wi-Fi gives way to mobile data.
     */
    private fun excludeRulesInstall(uids: List<Int>): String {
        if (uids.isEmpty()) return ""
        return buildString {
            append("PHYS=$(ip route get 1.1.1.1 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            append("PHYS6=$(ip -6 route get 2001:4860:4860::8888 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            // `ip rule show` prints in ascending priority order, so the first
            // line at or above the band's floor carries the jump target. Read
            // with shell builtins: a sed substitution over the whole list emits
            // a line per rule and the first one would not be the lowest match.
            for ((v, ip) in listOf("" to "ip", "6" to "ip -6")) {
                append("GOTO$v=$($ip rule show 2>/dev/null | while read -r p rest; do\n")
                append("    p=\${p%:}\n")
                append("    case \"\$p\" in ''|*[!0-9]*) continue;; esac\n")
                append("    [ \"\$p\" -ge $NETD_RULE_MIN ] && { echo \"\$p\"; break; }\n")
                append("done)\n")
            }
            for (uid in uids) {
                for ((ip, goto, phys) in listOf(
                    Triple("ip", "\$GOTO", "\$PHYS"),
                    Triple("ip -6", "\$GOTO6", "\$PHYS6")
                )) {
                    append("while $ip rule del uidrange $uid-$uid priority $EXCLUDE_PRIO 2>/dev/null; do :; done\n")
                    append(
                        "{ [ -n \"$goto\" ] && $ip rule add uidrange $uid-$uid goto \"$goto\" priority $EXCLUDE_PRIO 2>/dev/null; } || true\n"
                    )
                    // A goto the kernel could not resolve: the rule exists but is
                    // never evaluated, which is worse than no rule at all.
                    append(
                        "if $ip rule show 2>/dev/null | grep '^$EXCLUDE_PRIO:' | grep -F 'uidrange $uid-$uid' | " +
                            "grep -qE '\\[detached\\]| unresolved'; then\n"
                    )
                    append("    while $ip rule del uidrange $uid-$uid priority $EXCLUDE_PRIO 2>/dev/null; do :; done\n")
                    append("    echo '$GOTO_UNRESOLVED'\n")
                    append("fi\n")
                    // Fallback, also taken when no rule exists in netd's band at
                    // all: pin the uid to the physical table by hand.
                    append(
                        "$ip rule show 2>/dev/null | grep -q '^$EXCLUDE_PRIO:.*uidrange $uid-$uid' || " +
                            "{ [ -n \"$phys\" ] && $ip rule add uidrange $uid-$uid iif lo lookup \"$phys\" priority $EXCLUDE_PRIO 2>/dev/null; } || true\n"
                    )
                }
            }
        }
    }

    /** Removes the pref-[EXCLUDE_PRIO] rules whatever uids they carry. */
    private fun excludeRulesTeardown(): String = buildString {
        append("ip rule show 2>/dev/null | sed -n 's/^$EXCLUDE_PRIO:[^u]*uidrange \\([0-9]*\\)-\\([0-9]*\\).*/\\1 \\2/p' | while read a b; do ip rule del uidrange \"\$a\"-\"\$b\" priority $EXCLUDE_PRIO 2>/dev/null || true; done\n")
        append("ip -6 rule show 2>/dev/null | sed -n 's/^$EXCLUDE_PRIO:[^u]*uidrange \\([0-9]*\\)-\\([0-9]*\\).*/\\1 \\2/p' | while read a b; do ip -6 rule del uidrange \"\$a\"-\"\$b\" priority $EXCLUDE_PRIO 2>/dev/null || true; done\n")
    }

    /**
     * Detaches and drops the legacy per-app chain; safe to run when it does not
     * exist. Nothing installs [CHAIN_BYPASS] any more, but an upgrade from a
     * build that did must not leave its OUTPUT hook behind, so this keeps
     * running on every apply and every cleanup.
     */
    private fun bypassChainTeardown(ipt: String): String = buildString {
        append("$ipt -t mangle -D OUTPUT -j $CHAIN_BYPASS 2>/dev/null || true\n")
        append("$ipt -t mangle -F $CHAIN_BYPASS 2>/dev/null || true\n")
        append("$ipt -t mangle -X $CHAIN_BYPASS 2>/dev/null || true\n")
    }

    /**
     * `throw` routes survive the daemon's own Reconfig — verified on the APatch
     * phone by toggling the exit node with them installed — so they are written
     * once here rather than being refreshed on every netmap change.
     */
    private fun localBypassRoutes(ip: String, cidrs: List<String>): String = buildString {
        for (c in cidrs) {
            append("$ip route replace throw ${shQuote(c)} table $DAEMON_TABLE 2>/dev/null || true\n")
        }
    }

    /**
     * Removes exactly the throws [localBypassRoutes] writes, by prefix.
     *
     * The cleanup path sweeps every throw out of table 52 instead, which is
     * fine at teardown (the daemon is going away, and it re-adds its own at the
     * next Reconfig) but far too blunt while the daemon keeps running: it would
     * churn the LAN throws the daemon wrote for its own reasons on every apply.
     */
    private fun localBypassRoutesRemove(ip: String, cidrs: List<String>): String = buildString {
        for (c in cidrs) {
            append("$ip route del throw ${shQuote(c)} table $DAEMON_TABLE 2>/dev/null || true\n")
        }
    }

    /**
     * Drains the catch-all by priority and table rather than by mark, so an
     * earlier mask variant — or the one the boot script installs before any app
     * process exists — goes as well.
     */
    private fun catchAllTeardown(ip: String): String =
        "while $ip rule del priority $CATCH_ALL_PRIO lookup $DAEMON_TABLE 2>/dev/null; do :; done\n"

    /**
     * @param scope uid ranges to capture. Empty means the whole device, which is
     *              the only correct answer when nothing else owns it.
     *
     * Scoping is what lets us share a phone with another tunnel instead of
     * switching off next to it: netd routes that tunnel's members from priority
     * 13000, we sit at [CATCH_ALL_PRIO] and would outvote it for every uid, so we
     * ask only for the uids it does not claim. One rule per range, same selector
     * as the device-wide form — `ip rule` has no "not these uids", and a rule per
     * gap is exactly as many as the other tunnel itself installs.
     */
    private fun catchAllInstall(
        ip: String,
        missingMarker: String,
        bestEffort: Boolean,
        scope: List<LongRange> = emptyList()
    ): String = buildString {
        val tolerate = if (bestEffort) " 2>/dev/null || true" else ""
        append("while $ip rule del priority $CATCH_ALL_PRIO lookup $DAEMON_TABLE 2>/dev/null; do :; done\n")
        if (scope.isEmpty()) {
            append("$ip rule add fwmark 0x0/$CATCH_ALL_MASK iif lo lookup $DAEMON_TABLE priority $CATCH_ALL_PRIO$tolerate\n")
        } else {
            // Best-effort per range even on IPv4: one range failing must not cost
            // the others, and the read-back below still catches "none landed".
            for (r in scope) {
                append(
                    "$ip rule add fwmark 0x0/$CATCH_ALL_MASK iif lo uidrange ${r.first}-${r.last} " +
                        "lookup $DAEMON_TABLE priority $CATCH_ALL_PRIO 2>/dev/null || true\n"
                )
            }
        }
        append(
            "$ip rule show 2>/dev/null | grep -qE '^$CATCH_ALL_PRIO:.*fwmark (0x0|0)/$CATCH_ALL_MASK.*lookup $DAEMON_TABLE' || " +
                "{ while $ip rule del priority $CATCH_ALL_PRIO lookup $DAEMON_TABLE 2>/dev/null; do :; done; echo '$missingMarker'; }\n"
        )
    }

    /**
     * Reverse-path filter must be loose once table 52 is consulted for unmarked
     * lookups: replies to the daemon's marked sockets arrive on Wi-Fi/cellular,
     * are rp_filter-checked with mark 0, resolve to tailscale0 through table 52
     * and are dropped as martians under strict mode (1). The kernel uses
     * max(all, <iface>), so raising `all` to 2 is sufficient. The previous value
     * of `all` is saved once, through a temp file and mv so a partial write can
     * never leave a bad value for [rpFilterRestore]; the directory is created
     * here as well because the boot script reaches this without startRootDaemon.
     */
    private fun rpFilterLoosen(): String = buildString {
        val dir = shQuote(ROOT_ENV_DIR)
        val orig = shQuote(RP_FILTER_ORIG)
        val tmp = shQuote("$RP_FILTER_ORIG.tmp")
        append("mkdir -p $dir && chmod 700 $dir\n")
        append("for f in /proc/sys/net/ipv4/conf/*/rp_filter; do\n")
        append("    if [ \"\$(cat \"\$f\" 2>/dev/null)\" = \"1\" ]; then\n")
        append("        [ -f $orig ] || { cat /proc/sys/net/ipv4/conf/all/rp_filter > $tmp && mv $tmp $orig; }\n")
        append("        echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter\n")
        append("        break\n")
        append("    fi\n")
        append("done\n")
    }

    /** Puts `all/rp_filter` back to what [rpFilterLoosen] found, if it changed anything. */
    private fun rpFilterRestore(): String = buildString {
        val orig = shQuote(RP_FILTER_ORIG)
        append("if [ -f $orig ]; then\n")
        append("    case \"\$(cat $orig 2>/dev/null)\" in 0|1|2) cat $orig > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true;; esac\n")
        append("    rm -f $orig\n")
        append("fi\n")
    }

    /** Outcome of the daemon-log check: [marks] is the gate, [reason] is what the ROOT log and Check Routing show. */
    class SoMarkVerdict(val marks: Boolean, val reason: String)

    /**
     * Whether the daemon currently running has shown that it marks its own
     * sockets with [BYPASS_MARK]: its log carries [SO_MARK_OK] for THIS run.
     *
     * The daemon appends to one file across runs, so only the current run's
     * part counts. The file is scanned backwards from its end, line by line:
     *  - the last daemon line containing [SO_MARK_LINE] (the probe logs exactly
     *    once per run, `set` or `unavailable`) is the candidate verdict — but it
     *    counts only once a run start is found BELOW it;
     *  - a run start is a [RUN_MARKER] or [DAEMON_START_LINE] line, or the byte
     *    offset [startRootDaemon] recorded when it is this log and lies inside
     *    the file. Meeting one before any probe line means this run has logged
     *    no probe line (yet) → false;
     *  - the start of the file, or [SCAN_LIMIT] bytes, without a run start →
     *    the run cannot be bounded (log rewritten underneath, unknown core) →
     *    false. Never "the last line anywhere in the file": an earlier run's
     *    line must not vouch for this one.
     * Lines tagged [APP_LINE_TAG] are the app's and the script's own and are
     * never evidence. The scan reads [SCAN_CHUNK] at a time, so a daemon that
     * has been running for days (adopted, "Terminate Root Daemon on Stop" off)
     * is judged correctly however large its log has grown.
     *
     * Anything unreadable or absent counts as "does not mark": without the mark
     * the pref-200 catch-all would route the daemon's own tunnel packets into
     * tailscale0, so the safe failure mode is "no exit node", never a loop.
     */
    fun daemonMarksSockets(logFile: File): Boolean = soMarkVerdict(logFile).marks

    /**
     * Second opinion for the socket-marking gate, taken from the running daemon
     * instead of from its log.
     *
     * The log is not a reliable witness: it is truncated, it outlives the app
     * across an update, and a daemon that has been up for a while has long since
     * scrolled its startup lines away — on a device checked tonight it held no
     * probe line at all, so the gate could never open and the exit node was
     * withheld for a reason that had nothing to do with routing.
     *
     * The binary is ours and marks its sockets unconditionally; the only way to
     * turn that off is TS_VPN_BYPASS=0, which we put in the daemon's environment
     * ourselves. So reading that environment answers the question directly.
     * Returns null when nothing can be read, which leaves the log's verdict
     * standing — the gate's failure mode is "no exit node", never a loop.
     */
    private fun daemonMarksByEnviron(): SoMarkVerdict? {
        val script = buildString {
            append("for p in \$(pidof libtailscale.so 2>/dev/null); do\n")
            append("    [ -r /proc/\$p/environ ] || continue\n")
            append("    if tr '\\0' '\\n' < /proc/\$p/environ | grep -qx 'TS_VPN_BYPASS=0'; then\n")
            append("        echo SOMARK_ENV_DISABLED\n")
            append("    else\n")
            append("        echo SOMARK_ENV_ENABLED\n")
            append("    fi\n")
            append("    break\n")
            append("done\n")
        }
        val res = runSu("somark-environ", script, timeoutMs = 8_000L)
        if (!res.ok) return null
        return when {
            res.output.contains("SOMARK_ENV_ENABLED") ->
                SoMarkVerdict(true, "the running daemon is ours and socket marking is not disabled")
            res.output.contains("SOMARK_ENV_DISABLED") ->
                SoMarkVerdict(false, "the running daemon was started with TS_VPN_BYPASS=0")
            else -> null
        }
    }

    private fun soMarkVerdict(logFile: File): SoMarkVerdict {
        val recorded = if (lastDaemonLogFile == logFile) daemonLogStartOffset else -1L
        val noProbe = "no '$SO_MARK_LINE' line since this run's start in ${logFile.path}"
        try {
            java.io.RandomAccessFile(logFile, "r").use { raf ->
                val len = raf.length()
                val bounded = recorded in 0..len
                val floor = if (bounded) recorded else 0L
                val buf = ByteArray(SCAN_CHUNK)
                // Leading partial line of the chunk read before this one (later in the file).
                var carry = ""
                var pos = len
                // Verdict of the last probe line met, pending a run start below it.
                var probe: SoMarkVerdict? = null
                while (pos > floor) {
                    if (len - pos >= SCAN_LIMIT) {
                        return SoMarkVerdict(false, "no run start within the last ${SCAN_LIMIT shr 20} MiB of ${logFile.path}; run unverified")
                    }
                    val start = maxOf(floor, pos - SCAN_CHUNK)
                    val n = (pos - start).toInt()
                    raf.seek(start)
                    raf.readFully(buf, 0, n)
                    // ISO-8859-1 decodes bytes 1:1, so no multi-byte sequence can straddle a chunk boundary.
                    val lines = (String(buf, 0, n, Charsets.ISO_8859_1) + carry).split('\n')
                    // Unless this chunk begins at the floor (a line start by
                    // construction), its first segment continues a line from the
                    // chunk below and is judged together with that one.
                    val first = if (start > floor) 1 else 0
                    carry = if (start > floor) lines[0] else ""
                    for (i in lines.indices.reversed()) {
                        if (i < first) break
                        val line = lines[i]
                        if (line.contains(RUN_MARKER) || line.contains(DAEMON_START_LINE)) {
                            return probe ?: SoMarkVerdict(false, noProbe)
                        }
                        if (probe != null || line.contains(APP_LINE_TAG) || !line.contains(SO_MARK_LINE)) continue
                        probe = if (line.contains(SO_MARK_OK)) SoMarkVerdict(true, "daemon logged '$SO_MARK_OK'")
                        else SoMarkVerdict(false, "daemon logged: ${line.trim()}")
                    }
                    pos = start
                }
                if (bounded) {
                    // The recorded launch offset is a run start by construction.
                    return probe ?: SoMarkVerdict(false, "no '$SO_MARK_LINE' line yet for the run started at byte $recorded of ${logFile.path}")
                }
                return SoMarkVerdict(false, "no run start ('$RUN_MARKER' or '$DAEMON_START_LINE') in ${logFile.path}; run unverified")
            }
        } catch (e: Exception) {
            Log.w(TAG, "soMarkVerdict: cannot read ${logFile.path}: ${e.message}")
            return SoMarkVerdict(false, "cannot read ${logFile.path} (${e.message})")
        }
    }

    /**
     * Dumps the live policy-routing state for the Root Mode diagnostics screen:
     * the complete rule lists (the daemon's table 52 and any stale 52xx rules
     * were invisible while this filtered on 1099), both tables, the route
     * decision with and without the daemon's bypass mark, rp_filter, the
     * daemon log's last run marker and SO_MARK probe line, and the verdict
     * [soMarkVerdict] reaches from them (why pref 200 was or was not installed).
     *
     * It also answers the questions a broken device actually raises: where an
     * excluded uid's packets go, where the LAN goes, whether any rule is
     * installed but detached, whether anything but us claims an fwmark, how
     * busy the DNS chain is, and whether another tunnel owns the device — the
     * decision [applyTailscale0Routing] made, with its reason.
     */
    fun dumpRoutingState(context: Context? = null): String {
        val logFile = context?.let { rootDaemonLogFile(it) } ?: lastDaemonLogFile
        val orig = shQuote(RP_FILTER_ORIG)
        val uids = excludedUids(context)
        // A route lookup needs an address, not a prefix; the network address of
        // the first exclusion is enough to show which way the LAN goes.
        val lanProbe = localExclusions(context, v6 = false).firstOrNull()?.substringBefore('/')
        // Its own root shell, so it runs before the dump script is assembled and
        // its verdict is printed as part of the dump rather than out of band.
        val coexist = detectForeignVpn()
        val script = buildString {
            append("echo '--- ip rule ---'\n")
            append("ip rule show 2>/dev/null || echo '(ip rule show failed)'\n")
            append("echo '--- ip -6 rule ---'\n")
            append("ip -6 rule show 2>/dev/null || echo '(ip -6 rule show failed)'\n")
            append("echo '--- other tunnels ---'\n")
            append("ip -o link show 2>/dev/null | grep -Eo '(tun[0-9]+|ppp[0-9]+|wg[0-9]+)' | grep -v tailscale0 | sort -u || echo '(none)'\n")
            append("echo '--- table $ROUTE_TABLE (app) ---'\n")
            append("ip route show table $ROUTE_TABLE 2>/dev/null | grep . || echo '(empty)'\n")
            append("echo '--- table $DAEMON_TABLE (daemon: peers, subnet routes, exit-node default, LAN throws) ---'\n")
            append("ip route show table $DAEMON_TABLE 2>/dev/null | grep . || echo '(empty)'\n")
            append("echo '--- ip -6 route table $DAEMON_TABLE ---'\n")
            append("ip -6 route show table $DAEMON_TABLE 2>/dev/null | grep . || echo '(empty)'\n")
            append("echo '--- tailscale0 routes, all tables ---'\n")
            append("ip route show table all 2>/dev/null | grep tailscale0 || echo '(none)'\n")
            append("echo '--- default routes in table $ROUTE_TABLE (expected 0; one means we captured the device) ---'\n")
            append("ip route show table $ROUTE_TABLE 2>/dev/null | grep -c '^default'\n")
            append("echo '--- route get 8.8.8.8: unmarked, then with the daemon bypass mark $BYPASS_MARK ---'\n")
            append("ip route get 8.8.8.8 2>&1\n")
            append("ip route get 8.8.8.8 mark $BYPASS_MARK 2>&1\n")
            if (uids.isNotEmpty()) {
                append("echo '--- route get per excluded uid (must not be tailscale0 while an exit node is up) ---'\n")
                for (uid in uids) {
                    append("printf 'uid %s v4: ' $uid; ip route get 8.8.8.8 uid $uid 2>&1 | head -n 1\n")
                    append("printf 'uid %s v6: ' $uid; ip -6 route get 2001:4860:4860::8888 uid $uid 2>&1 | head -n 1\n")
                }
            }
            if (lanProbe != null) {
                append("echo '--- route get $lanProbe (first excluded local prefix; tailscale0 means the LAN is inside the exit node) ---'\n")
                append("ip route get ${shQuote(lanProbe)} 2>&1 | head -n 1\n")
            }
            append("echo '--- rules installed but never evaluated ([detached] / unresolved goto targets) ---'\n")
            append("{ ip rule show 2>/dev/null; ip -6 rule show 2>/dev/null; } | grep -E '\\[detached\\]| unresolved' || echo '(none)'\n")
            append("echo '--- every fwmark claim in mangle, ours and anyone else's ---'\n")
            append("iptables -t mangle -S 2>/dev/null | grep -- '--set-xmark' || echo '(none)'\n")
            append("echo '--- rp_filter ---'\n")
            append("for f in /proc/sys/net/ipv4/conf/*/rp_filter; do printf '%s=%s\\n' \"\${f#/proc/sys/net/ipv4/conf/}\" \"\$(cat \"\$f\" 2>/dev/null)\"; done 2>/dev/null\n")
            append("[ -f $orig ] && echo \"saved original all=\$(cat $orig)\"\n")
            append("echo '--- daemon log: last run marker, last SO_MARK probe line (any run), gate verdict ---'\n")
            if (logFile != null) {
                val lf = shQuote(logFile.absolutePath)
                append("grep -n '$RUN_MARKER' $lf 2>/dev/null | tail -n 1 | grep . || echo '(no \"$RUN_MARKER\" line: daemon not started by TailSocks)'\n")
                append("grep '$SO_MARK_LINE' $lf 2>/dev/null | grep -v '$APP_LINE_TAG' | tail -n 1 | grep . || echo '(no SO_MARK line in daemon log)'\n")
                val verdict = soMarkVerdict(logFile)
                append("echo ${shQuote("gate: " + (if (verdict.marks) "daemon marks sockets" else "pref-$CATCH_ALL_PRIO withheld") + " (" + verdict.reason + ")")}\n")
            } else {
                append("echo '(daemon log path unknown)'\n")
            }
            append("echo '--- coexistence (which tiers the last apply installed, and why) ---'\n")
            append(
                "echo " + shQuote(
                    (if (coexist.present) "another tunnel owns this device" else "this device is ours") +
                        " (" + coexist.reason + ")"
                ) + "\n"
            )
            append("echo '--- mangle $CHAIN_MARK ---'\n")
            append("iptables -t mangle -S $CHAIN_MARK 2>/dev/null || echo '(absent)'\n")
            append("echo '--- mangle $CHAIN_BYPASS (removed in 4.0; anything here is upgrade residue) ---'\n")
            append("iptables -t mangle -S $CHAIN_BYPASS 2>/dev/null || echo '(absent)'\n")
            append("echo '--- nat $CHAIN_DNS ---'\n")
            append("iptables -t nat -S $CHAIN_DNS 2>/dev/null || echo '(absent)'\n")
            append("echo '--- nat $CHAIN_DNS counters ---'\n")
            append("iptables -t nat -L $CHAIN_DNS -n -v -x 2>/dev/null || echo '(absent)'\n")
            append("echo '--- tailscale0 ---'\n")
            append("ip -br addr show tailscale0 2>/dev/null || echo '(interface missing)'\n")
            append("echo '--- ip binary ---'\n")
            append("ip -V 2>&1 | head -n 1\n")
        }
        return runSu("routing-dump", script, timeoutMs = 10_000L).output
    }

    /**
     * The file the Root Mode daemon appends to. Must stay in sync with the path
     * TailscaledService hands to [startRootDaemon] and with the default there.
     */
    fun rootDaemonLogFile(context: Context): File {
        val dataDir = context.filesDir.parentFile ?: context.filesDir
        return File(dataDir, "logs/tailscaled.log")
    }

    /**
     * Truncates the Root Mode daemon log in place.
     *
     * The daemon writes the file as root, so the app uid can read it (0644) but
     * cannot open it for writing, and `File.writeText("")` silently fails with
     * EACCES. `: >` truncates through the root shell and keeps the inode: the
     * daemon holds the file open with O_APPEND, so its next line lands at the
     * new end of the file and no restart is needed.
     */
    fun clearRootDaemonLog(context: Context): Boolean {
        val path = rootDaemonLogFile(context).absolutePath
        val res = runSu("daemon-log-clear", ": > ${shQuote(path)}\n", timeoutMs = 10_000L)
        return res.ok
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

    /**
     * Gives the daemon's state directory back to the app's uid.
     *
     * The root daemon writes `tailscaled.state` and `profile-data/` as root,
     * 0600 — it rewrites them atomically, so every login and every prefs change
     * produces a fresh root-owned file. The moment the app runs a daemon of its
     * own again (Root Mode off, or root simply unavailable) it cannot read its
     * own state, starts from nothing, and asks the user to log in while the node
     * is still registered. Observed on WSA: state owned by root, the app back at
     * "authentication required" with an empty peer list.
     *
     * Called whenever the root daemon is stopped, which is the last moment root
     * is guaranteed to be available.
     */
    fun handStateBackToApp(context: Context): Boolean {
        val uid = context.applicationInfo.uid
        val dir = File(context.filesDir, "states").absolutePath
        if (!File(dir).exists()) return true
        val script = buildString {
            append("[ -d ${shQuote(dir)} ] || exit 0\n")
            append("chown -R $uid:$uid ${shQuote(dir)} 2>/dev/null || true\n")
            // Ownership is what matters; keep the modes the daemon expects.
            append("find ${shQuote(dir)} -type d -exec chmod 700 {} + 2>/dev/null || true\n")
            append("find ${shQuote(dir)} -type f -exec chmod 600 {} + 2>/dev/null || true\n")
        }
        val res = runSu("state-chown", script, timeoutMs = 15_000L)
        if (!res.ok) {
            rootLog("WARN", "could not hand the daemon state back to the app; a non-root start may ask you to log in again")
        }
        return res.ok
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
