package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.core.RootRouting.BYPASS_MARK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_MASK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_PRIO
import io.github.bropines.tailscaled.core.RootRouting.CGNAT_V4
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_BYPASS
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_DNS
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_MARK
import io.github.bropines.tailscaled.core.RootRouting.DAEMON_TABLE
import io.github.bropines.tailscaled.core.RootRouting.DNS_CHAIN_ABSENT
import io.github.bropines.tailscaled.core.RootRouting.DNS_HOOK_FAILED
import io.github.bropines.tailscaled.core.RootRouting.DNS_V6_NO_NAT
import io.github.bropines.tailscaled.core.RootRouting.GOTO_UNRESOLVED
import io.github.bropines.tailscaled.core.RootRouting.LAN_RETURN_PRIO
import io.github.bropines.tailscaled.core.RootRouting.LEGACY_ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.MAGIC_DNS
import io.github.bropines.tailscaled.core.RootRouting.NETD_RULE_MAX
import io.github.bropines.tailscaled.core.RootRouting.NETD_RULE_MIN
import io.github.bropines.tailscaled.core.RootRouting.OWNER_MATCH_MISSING
import io.github.bropines.tailscaled.core.RootRouting.PROTECT_MARK
import io.github.bropines.tailscaled.core.RootRouting.ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.RP_FILTER_ORIG
import io.github.bropines.tailscaled.core.RootRouting.UIDRANGE_MISSING_V4
import io.github.bropines.tailscaled.core.RootRouting.UIDRANGE_MISSING_V6
import io.github.bropines.tailscaled.core.RootRouting.V4_CATCH_ALL_MISSING
import io.github.bropines.tailscaled.core.RootRouting.V6_CATCH_ALL_MISSING
import io.github.bropines.tailscaled.core.RootRoutingScript.bypassChainTeardown
import io.github.bropines.tailscaled.core.RootRoutingScript.catchAllInstall
import io.github.bropines.tailscaled.core.RootRoutingScript.catchAllTeardown
import io.github.bropines.tailscaled.core.RootRoutingScript.cgnatGuardRoutes
import io.github.bropines.tailscaled.core.RootRoutingScript.dnsChainTeardown
import io.github.bropines.tailscaled.core.RootRoutingScript.dnsHookInstall
import io.github.bropines.tailscaled.core.RootRoutingScript.dnsHookRemove
import io.github.bropines.tailscaled.core.RootRoutingScript.excludeRulesTeardown
import io.github.bropines.tailscaled.core.RootRoutingScript.localBypassRoutes
import io.github.bropines.tailscaled.core.RootRoutingScript.localBypassRoutesRemove
import io.github.bropines.tailscaled.core.RootRoutingScript.localReturnRulesInstall
import io.github.bropines.tailscaled.core.RootRoutingScript.localReturnRulesRemove
import io.github.bropines.tailscaled.core.RootRoutingScript.routeTableTeardown
import io.github.bropines.tailscaled.core.RootRoutingScript.rpFilterLoosen
import io.github.bropines.tailscaled.core.RootRoutingScript.rpFilterRestore
import io.github.bropines.tailscaled.core.RootRoutingScript.staleDaemonRulePurge
import io.github.bropines.tailscaled.core.RootShell.rootLog
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.models.StatusResponse
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The daemon's answer to `/localapi/v0/dns-query`: the raw DNS response
 * message, which Go encodes as base64 because the field is a `[]byte`.
 */
@Serializable
private class DnsQueryResponse(@SerialName("Bytes") val bytes: String? = null)

object RootUtils {
    internal const val TAG = "RootUtils"

    /**
     * This package's files outside the sandbox — the boot script, the env file,
     * the CLI wrapper, the hosts copy. The debug build has its own (`-dev`), so
     * it never overwrites the release app's; the ruleset below is shared.
     */
    internal val paths: RootPaths get() = RootPaths.current

    /**
     * What the root daemon's sockets may carry (netns_android.go socketMark): [BYPASS_MARK] with
     * Android's protectedFromVpn bit — the default — or [BYPASS_MARK] alone under TS_VPN_BYPASS=0.
     * For the dev network trace, which asks the kernel how such a packet is routed.
     */
    internal val DAEMON_SOCKET_MARKS = listOf(CATCH_ALL_MASK, BYPASS_MARK)

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

    /** Interface name prefixes that are tunnels or bridges, never the LAN this device sits on. */
    private val NON_LAN_INTERFACES = listOf("tailscale", "tun", "ppp", "wg", "ipsec", "docker", "veth", "clat", "dummy")

    /** Accepts only a bare CIDR — the list comes from a user-editable setting. */
    private val CIDR_V4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")
    private val CIDR_V6 = Regex("^[0-9a-fA-F:]{2,45}/\\d{1,3}$")

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
    internal const val RUN_MARKER = "TailSocks: daemon start"
    internal const val DAEMON_START_LINE = "wgengine.NewUserspaceEngine(tun"

    /** Lines the app or the boot script write into the daemon log carry this tag; none of them is daemon output. */
    internal const val APP_LINE_TAG = "TailSocks:"

    /**
     * The netns probe (patch 16) logs exactly one line per run containing
     * [SO_MARK_LINE]: [SO_MARK_OK] on success, `netns: SO_MARK unavailable (…)`
     * otherwise. Daemon lines carry Go's `YYYY/MM/DD HH:MM:SS` prefix and a
     * component prefix such as `magicsock:` in front, so both are matched as
     * substrings — and the success match is the daemon's exact contiguous text,
     * never two loose tokens (a message that merely mentions both would
     * otherwise open the gate).
     */
    internal const val SO_MARK_LINE = "netns: SO_MARK"
    internal const val SO_MARK_OK = "netns: SO_MARK $BYPASS_MARK set on tailscaled sockets"

    /** Backward log scan in [soMarkVerdict]: bytes read per step, and how far back to look before giving up. */
    internal const val SCAN_CHUNK = 1 shl 20
    internal const val SCAN_LIMIT = 256L shl 20

    /**
     * Above this size the daemon log is cut to its last [LOG_TRIM_KEEP_LINES]
     * lines before a daemon is started — by the boot script, and by
     * [startRootDaemon] with the same three lines ([logTrimScript]). The log is
     * root-owned and only ever appended to; before the app's start path
     * trimmed it too, a phone without the boot script grew it without bound.
     */
    internal const val LOG_TRIM_BYTES = 2_097_152L
    internal const val LOG_TRIM_KEEP_LINES = 500

    /**
     * Log of the daemon launched by [startRootDaemon] and the file size just
     * before the launch: a run start [soMarkVerdict] can use even before the
     * daemon has written anything. Both stay unset when the app merely attached
     * to a daemon that was already running (boot script), in which case the
     * last [RUN_MARKER] line the script wrote bounds the current run instead.
     */
    @Volatile private var lastDaemonLogFile: File? = null
    @Volatile private var daemonLogStartOffset: Long = -1L

    /** Result of a root shell invocation, including everything it printed. */
    data class SuResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** [NetTrace]'s read-only snapshot shell; its output, whatever the exit. */
    fun netTraceShell(script: String): String = runSu("nettrace", script, timeoutMs = 30_000L, quiet = true).output

    /** Why the last MagicDNS probe passed or failed, for [NetTrace]. */
    val magicDnsReason: String get() = lastMagicDnsReason

    /**
     * Why the device-wide DNS redirect must stay off whatever MagicDNS says, or
     * null when nothing holds it. Set by the service while the default network
     * is not VALIDATED.
     *
     * Android validates a network by resolving its probe host through the
     * system resolver, and the redirect hands that query to MagicDNS. MagicDNS
     * forwards it over the tailnet when the tailnet's DNS server is a peer, and
     * the tunnel cannot come up yet: on some ROMs netd installs the catch-all
     * rule the daemon's marked sockets rely on only once the network has
     * validated. Validation waits for DNS, DNS waits for the tunnel, the tunnel
     * waits for validation — mobile data toggled off and on stayed offline until
     * the redirect was switched off by hand. Holding the redirect off until
     * validation lets the probe reach the carrier's resolver and breaks that
     * loop; the health gate arms it again once MagicDNS really answers.
     */
    @Volatile var dnsRedirectHold: String? = null

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

    /**
     * One look at the daemon a start may adopt: a connect, then the status with a
     * read timeout. LocalAPI holds a request until the daemon's backend exists,
     * so without the timeout a daemon on its way up parked the start thread for
     * as long as it took — and one that never got there, for good.
     */
    fun probeDaemon(socketPath: String): RootAdoption.Probe {
        val error = probeSocket(socketPath)
        // Denied by policy means it is there; allowSocketConnect, run before the
        // first probe, is what repairs that, and the status read below fails the
        // same way until it has.
        if (error != null && !isPolicyDenial(error)) return RootAdoption.Probe.ABSENT
        // Only BackendState is read, so the peers are not asked for: the default answer carries the whole netmap.
        return RootAdoption.classify(localApiGet(socketPath, "/localapi/v0/status?peers=false", RootAdoption.PROBE_TIMEOUT_MS))
    }

    /**
     * Whether a daemon process for this socket exists, as root sees it. Matched by
     * its `--socket=` argument like [stopRootDaemon]; the app cannot see a root
     * process in /proc. False when the root shell itself fails — a start then
     * goes ahead as it always did.
     */
    fun isDaemonProcessAlive(socketPath: String): Boolean {
        val pat = shQuote("--socket=$socketPath")
        val res = runSu("daemon-alive-check", "pgrep -f -- $pat >/dev/null 2>&1 && echo alive\n", timeoutMs = 10_000L, quiet = true)
        return res.ok && res.output.contains("alive")
    }

    /**
     * Tells the root daemon that the network moved: SIGUSR1 makes it re-read
     * the default route from TS_NETROUTE_FILE and wake its network monitor
     * (patch 06), as the app's own child daemon is woken from the bridge. The
     * app cannot signal a root process itself. Matched by its `--socket=`
     * argument and, like [daemonMarksByEnviron], only a process with a state
     * directory: the CLI is the same binary.
     */
    fun wakeRootDaemon(socketPath: String): Boolean {
        val pat = shQuote("--socket=$socketPath")
        val script = buildString {
            append("for p in \$(pgrep -f -- $pat 2>/dev/null); do\n")
            append("    tr '\\0' '\\n' < /proc/\$p/cmdline 2>/dev/null | grep -q '^--statedir=' || continue\n")
            append("    kill -USR1 \$p 2>/dev/null && echo woken\n")
            append("done\n")
        }
        val res = runSu("daemon-wake", script, timeoutMs = 8_000L, quiet = true)
        return res.ok && res.output.contains("woken")
    }

    /**
     * Waits for a daemon that exists to answer, within [RootAdoption.BUDGET_MS],
     * and says what the start does with it ([RootAdoption.next]). A daemon that
     * answers at once costs one probe; no daemon process costs one root shell.
     * [cancelled] is asked between probes, so a Stop does not wait out the
     * budget; that is the one case that returns [RootAdoption.Step.WAIT].
     */
    fun awaitRootDaemon(socketPath: String, cancelled: () -> Boolean): RootAdoption.Step {
        var probe = probeDaemon(socketPath)
        if (probe == RootAdoption.Probe.READY) return RootAdoption.Step.ADOPT
        val started = android.os.SystemClock.elapsedRealtime()
        var alive = isDaemonProcessAlive(socketPath)
        var aliveCheckedAt = started
        if (alive) rootLog("INFO", "A root daemon for this app is running but not answering yet ($probe); waiting for it")
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            val step = RootAdoption.next(probe, alive, now - started)
            if (step != RootAdoption.Step.WAIT || cancelled()) {
                val waited = now - started
                when {
                    step == RootAdoption.Step.ADOPT && waited > 0 ->
                        rootLog("INFO", "The root daemon answered after ${waited} ms; adopting it")
                    step == RootAdoption.Step.START_FRESH && waited > RootAdoption.PROBE_INTERVAL_MS ->
                        rootLog("INFO", "The root daemon went away while it was being waited for (${waited} ms)")
                    step == RootAdoption.Step.GIVE_UP ->
                        rootLog("WARN", "The root daemon did not answer within ${waited} ms ($probe); replacing it")
                }
                return step
            }
            Thread.sleep(RootAdoption.PROBE_INTERVAL_MS)
            probe = probeDaemon(socketPath)
            val checked = android.os.SystemClock.elapsedRealtime()
            if (probe != RootAdoption.Probe.READY && checked - aliveCheckedAt >= RootAdoption.PROCESS_RECHECK_MS) {
                alive = isDaemonProcessAlive(socketPath)
                aliveCheckedAt = checked
            }
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

    /**
     * Everything a root daemon is started with, whether by the app now or by the
     * boot script after the next reboot. One object so the two launch paths
     * cannot drift: the app-driven start puts these into the daemon's command
     * line and environment, and writes the same values into the root-owned env
     * file the boot script parses (see writeRootEnvFile).
     */
    data class RootDaemonSpec(
        val stateDir: String,
        val socketPath: String,
        val logFilePath: String = "",
        val socksAddr: String = "",
        val httpAddr: String = "",
        val socksUser: String = "",
        val socksPass: String = "",
        val controlProxy: String = "",
        val taildropDir: String = "",
        val tunMode: Boolean = true,
        val dnsFallbacks: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        /** Report OS "android" and the real device instead of the patch-06 masquerade. */
        val honestHostinfo: Boolean = false,
        val osVersion: String = "",
        val deviceModel: String = "",
        val installSource: String = "",
        /** Start from the saved network map when control grants it ([NetmapCache]). */
        val netmapCache: Boolean = true,
        /** The resolver may fall back to the public DNS servers ([GlobalSettings.isDnsRescueEnabled]). */
        val dnsRescue: Boolean = true,
        /** The control connection goes straight to port 443 ([ControlPort]). */
        val controlTlsOnly: Boolean = true,
    )

    /**
     * The `export NAME='value'` lines both root launch paths share. Every value
     * is single-quoted through shQuote — the boot script accepts nothing else
     * (assets/scripts/tailscaled.sh parses the file instead of sourcing it),
     * and a user-supplied value must never be expanded by a root shell.
     */
    private fun rootDaemonEnv(context: Context, spec: RootDaemonSpec, logsDir: String): String {
        val env = StringBuilder()
        env.append("export TS_LOGS_DIR=${shQuote(logsDir)}\n")
        env.append("export TS_NO_LOGS_NO_SUPPORT=${shQuote("true")}\n")
        env.append("export TS_AUTH_ONCE=${shQuote("true")}\n")
        // Go finds no /etc/localtime on Android: without TZ the daemon's log file is
        // UTC, and the Logs screen, which reads its stamps as local time, misplaces it.
        env.append("export TZ=${shQuote(java.util.TimeZone.getDefault().id)}\n")
        // Must match the addresses excluded from the DNS redirect in
        // applyTailscale0Routing, otherwise the daemon's bootstrap queries are
        // redirected into MagicDNS before MagicDNS can answer anything.
        val fallbacks = spec.dnsFallbacks.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        env.append("export TS_DNS_FALLBACK=${shQuote(fallbacks.joinToString(","))}\n")
        // The interface that carries the default route, which the daemon cannot
        // learn on Android: the app keeps this file current and wakes the daemon
        // ([wakeRootDaemon]). Without it every network change looked minor, so a
        // daemon that lost mobile data never rebound and stayed on dead relays.
        env.append("export TS_NETROUTE_FILE=${shQuote(File(context.filesDir, "netroute").absolutePath)}\n")
        // The daemon has no VpnService to protect its sockets, so it sets
        // Android's protect bit itself unless the user wants the opposite:
        // its own traffic riding whatever VPN client holds the tunnel. Quoted:
        // up to 4.1.0 this line was written bare and the boot script's parser
        // dropped it, so "Ignore other VPNs = off" reverted after every reboot.
        if (!GlobalSettings.isRootVpnBypassEnabled(context)) {
            env.append("export TS_VPN_BYPASS=${shQuote("0")}\n")
        }
        if (spec.taildropDir.isNotEmpty()) {
            env.append("export TS_TAILDROP_DIR=${shQuote(spec.taildropDir)}\n")
        }
        // The SOCKS5 password. Only the userspace path used to pass it, so a
        // Root Mode proxy — LAN-exposed or not — accepted anyone. The file is
        // root-owned 0600 and already carries the control-proxy password.
        // Both or neither, as everywhere else (see appctr/daemon.go).
        if (spec.socksUser.isNotEmpty() && spec.socksPass.isNotEmpty()) {
            env.append("export TS_SOCKS5_USER=${shQuote(spec.socksUser)}\n")
            env.append("export TS_SOCKS5_PASS=${shQuote(spec.socksPass)}\n")
        }
        if (spec.honestHostinfo) {
            env.append("export TS_HONEST_HOSTINFO=${shQuote("1")}\n")
            env.append("export TS_HI_OS_VERSION=${shQuote(spec.osVersion)}\n")
            env.append("export TS_HI_DEVICE_MODEL=${shQuote(spec.deviceModel)}\n")
            env.append("export TS_HI_PACKAGE=${shQuote(spec.installSource)}\n")
        }
        // Off only: on is upstream's default, and an unset variable keeps it.
        if (!spec.netmapCache) {
            env.append("export ${NetmapCache.ENV}=${shQuote("false")}\n")
        }
        if (!spec.dnsRescue) {
            env.append("export TS_DNS_RESCUE=${shQuote("0")}\n")
        }
        if (spec.controlTlsOnly) {
            env.append("export ${ControlPort.ENV}=${shQuote("true")}\n")
        }

        // Single-quoted: the control proxy URL carries a user-supplied
        // password, and this file is read by the boot script as root, so
        // a value containing $(...) or backticks would otherwise run as root
        // on every boot.
        if (spec.controlProxy.isNotEmpty()) {
            val staticOverride = resolveProxyHostStatic(spec.controlProxy)
            if (staticOverride.isNotEmpty()) {
                env.append("export TS_STATIC_HOSTS=${shQuote(staticOverride)}\n")
            }
            if (spec.controlProxy.startsWith("socks5://")) {
                env.append("export ALL_PROXY=${shQuote(spec.controlProxy)}\n")
            } else {
                env.append("export HTTP_PROXY=${shQuote(spec.controlProxy)}\n")
                env.append("export HTTPS_PROXY=${shQuote(spec.controlProxy)}\n")
            }
        }

        // What the boot script needs to build the same command line the app
        // builds below. Without these a daemon started at boot had no SOCKS5 or
        // HTTP listener (Taildrive in Files and LAN clients dead until a manual
        // restart), always a kernel TUN, and whichever profile `find` saw first.
        env.append("export TAILSOCKS_STATE_DIR=${shQuote(spec.stateDir)}\n")
        if (spec.socksAddr.isNotEmpty() && spec.socksAddr != "none") {
            env.append("export TAILSOCKS_SOCKS_ADDR=${shQuote(spec.socksAddr)}\n")
        }
        if (spec.httpAddr.isNotEmpty()) {
            env.append("export TAILSOCKS_HTTP_ADDR=${shQuote(spec.httpAddr)}\n")
        }
        env.append("export TAILSOCKS_TUN=${shQuote(if (spec.tunMode) "tailscale0" else "userspace-networking")}\n")
        return env.toString()
    }

    /**
     * Writes the root-owned env file the boot script parses.
     *
     * The boot script reads this file as root on every boot, so it must not
     * live anywhere the app uid can write: a restored backup used to be able to
     * drop an attacker's files/control_proxy.env there. It is written through
     * su into a root-owned directory, 0600, via a quoted heredoc (no expansion),
     * and the old app-writable copy is removed. Called from every Root Mode
     * start — the app's own launch and the attach to a daemon the boot script
     * started — so the next boot always uses the current settings.
     */
    fun writeRootEnvFile(context: Context, spec: RootDaemonSpec): Boolean {
        val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
        val logsDir = File(dataDir, "logs").apply { mkdirs() }.absolutePath
        return writeRootEnvFile(rootDaemonEnv(context, spec, logsDir), dataDir)
    }

    private fun writeRootEnvFile(env: String, dataDir: String): Boolean {
        val ok = runSu("control-proxy-env", rootEnvFileScript(env, dataDir)).ok
        if (!ok) {
            Log.w(TAG, "Failed to write the root-owned control_proxy.env; the boot script will start with stale or no settings")
        }
        return ok
    }

    /** The env file write as a script; [startRootDaemon] runs it in its launch shell. */
    private fun rootEnvFileScript(env: String, dataDir: String): String {
        val envScript = StringBuilder()
            .append("mkdir -p ").append(paths.envDir).append(" && chmod 700 ").append(paths.envDir).append('\n')
            .append("cat > ").append(paths.envFile).append(" <<'TAILSOCKS_ENV_EOF'\n")
            // Who wrote it, in the boot script's header style. The file has no
            // format version — the script accepts `export NAME='value'` lines
            // and skips everything else, this comment included. Each package
            // has its own file (RootPaths); the line says which build wrote it.
            .append(RootBootScript.envFileHeader(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME))
            .append(env)
            .append("TAILSOCKS_ENV_EOF\n")
            .append("chmod 600 ").append(paths.envFile).append('\n')
            .append("rm -f ").append(shQuote("$dataDir/files/control_proxy.env")).append('\n')
        return envScript.toString()
    }

    fun startRootDaemon(context: Context, spec: RootDaemonSpec): Boolean {
        return try {
            val tailscaledBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
            val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            val logsDir = File(dataDir, "logs").apply { mkdirs() }.absolutePath
            val logFile = if (spec.logFilePath.isNotEmpty()) spec.logFilePath else "$logsDir/tailscaled.log"
            // Everything the daemon appends from here on belongs to this run —
            // unless the script below trims the log first, which moves every
            // offset; the run marker it writes bounds the run then.
            lastDaemonLogFile = File(logFile)
            val logLength = File(logFile).length()
            daemonLogStartOffset = if (logLength > LOG_TRIM_BYTES) -1L else logLength
            soMarkScan.reset()

            val socketFile = File(spec.socketPath)
            socketFile.parentFile?.mkdirs()

            val env = rootDaemonEnv(context, spec, logsDir)

            // Every argument is quoted: the listen addresses come from free-text
            // settings fields and are interpolated into a root shell.
            val cmd = mutableListOf<String>().apply {
                add(shQuote(tailscaledBin))
                add("--statedir=" + shQuote(spec.stateDir))
                add("--socket=" + shQuote(spec.socketPath))
                if (spec.socksAddr.isNotEmpty() && spec.socksAddr != "none") {
                    add("--socks5-server=" + shQuote(spec.socksAddr))
                }
                if (spec.tunMode) {
                    add("--tun=tailscale0")
                } else {
                    add("--tun=userspace-networking")
                }
                if (spec.httpAddr.isNotEmpty()) {
                    add("--outbound-http-proxy-listen=" + shQuote(spec.httpAddr))
                }
            }.joinToString(" ")

            // The guard first: no launch while a daemon for this socket exists or
            // another launch of one is in progress (see [launchGuardScript]).
            // Then the env file the boot script reads next boot, and the same
            // variables exported for this launch — one root shell for both; they
            // used to be two, in this order.
            val sb = StringBuilder(launchGuardScript(launchLockDir(dataDir), spec.socketPath))
            sb.append(rootEnvFileScript(env, dataDir))
            sb.append(env)
            // The boot script's trim, before the marker so the marker survives it.
            sb.append(logTrimScript(logFile))
            // Run marker first: everything the daemon prints in this run follows
            // it, which is what the SO_MARK gate keys on (the boot script writes
            // the same line). A missing marker means "unverified", never "marks".
            sb.append("echo ${shQuote(RUN_MARKER)} >> ${shQuote(logFile)}\n")
            // Received Taildrop files are created by the root daemon with 0666
            // (feature/taildrop/send.go, ext.go) minus this shell's umask. Some su
            // implementations run with 077, which leaves them unreadable to the
            // app until handStateBackToApp chowns the folder at daemon stop — the
            // "File received" notification would then open into EACCES. 022 gives
            // 0644: readable by the app uid, inside its own 0700 data directory.
            // Nothing else widens: state files are written 0600 explicitly, the
            // socket and the log get their modes below. Same line in the boot
            // script (assets/scripts/tailscaled.sh).
            sb.append("umask 022\n")
            sb.append("nohup $cmd >> ${shQuote(logFile)} 2>&1 &\n")
            // The daemon runs as root, so the file is root-owned; 0644 lets the
            // app read it for the Logs screen. It sits in the app's private data
            // directory (0700), so nothing else can. 0600 left the Logs screen
            // empty in Root Mode.
            sb.append("chmod 644 ${shQuote(logFile)} 2>/dev/null || true\n")
            // No SELinux rule is injected here any more: allowSocketConnect adds
            // one below, with the real domains, and only if a connect is denied.
            sb.append("for i in \$(seq 1 30); do\n")
            sb.append("    if [ -S ${shQuote(spec.socketPath)} ] || [ -e ${shQuote(spec.socketPath)} ]; then\n")
            sb.append("        chmod 666 ${shQuote(spec.socketPath)}\n")
            sb.append("        chcon u:object_r:app_data_file:s0 ${shQuote(spec.socketPath)} 2>/dev/null || true\n")
            sb.append("        chmod 700 ${shQuote(spec.stateDir)} 2>/dev/null || true\n")
            sb.append("        break\n")
            sb.append("    fi\n")
            sb.append("    sleep 0.2\n")
            sb.append("done\n")
            // Routing is intentionally not configured here: it is applied by
            // TailscaledService once tailscale0 exists, and the DNS redirect only
            // after the daemon reaches the Running state.

            val res = runSu("daemon-start", sb.toString(), timeoutMs = 30_000L)
            Log.d(TAG, "Root daemon launch result exitCode=${res.exitCode}")
            if (!res.ok) {
                Log.w(TAG, "The daemon-start shell failed; the root-owned control_proxy.env may not have been written")
            }
            // Nothing was launched: the daemon that exists, or the one another
            // launch is bringing up, is the one to attach to. It opens its socket
            // in its own time, so the wait is the adoption budget rather than the
            // five seconds a launch of our own gets; and its run started at the
            // marker that launch wrote, not at the offset sampled above.
            val skipped = res.output.contains(LAUNCH_SKIPPED_RUNNING) || res.output.contains(LAUNCH_SKIPPED_LOCKED)
            if (skipped) {
                daemonLogStartOffset = -1L
                rootLog("INFO", "No second root daemon launched for this socket; waiting for the one that is there to answer")
            }
            val waitMs = if (skipped) RootAdoption.BUDGET_MS else 5_000L

            // Verify the daemon really came up rather than trusting the exit code.
            var attempts = 0
            var policyPatched = false
            while (attempts < waitMs / 200) {
                val error = probeSocket(spec.socketPath)
                if (error == null) {
                    Log.i(TAG, "Root daemon socket is accepting connections at ${spec.socketPath}")
                    rootLog("INFO", "Root daemon started (socket ready)")
                    return true
                }
                // The socket exists and the kernel refused us: that is policy,
                // not a daemon that failed to start. Fix it once, then keep
                // probing as before.
                if (!policyPatched && isPolicyDenial(error)) {
                    policyPatched = true
                    if (injectSocketConnectRule(spec.socketPath) && isDaemonAlive(spec.socketPath)) {
                        rootLog("INFO", "Root daemon started (socket ready)")
                        return true
                    }
                }
                Thread.sleep(200)
                attempts++
            }
            rootLog("ERROR", "Root daemon did not open ${spec.socketPath} within ${waitMs / 1000}s")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root daemon: ${e.message}", e)
            rootLog("ERROR", "Failed to start root daemon: ${e.message}")
            false
        }
    }

    /** Printed by [launchGuardScript] when a daemon for the socket is already running. */
    internal const val LAUNCH_SKIPPED_RUNNING = "launch: skipped, a daemon for this socket is already running"
    /** Printed by [launchGuardScript] when another launch holds the lock. */
    internal const val LAUNCH_SKIPPED_LOCKED = "launch: skipped, another launch for this socket is in progress"

    /** The launch lock: a directory in the app's files dir, created by root with mkdir. */
    internal fun launchLockDir(dataDir: String): String = "$dataDir/files/root-daemon-launch.lock"

    /**
     * The head of the launch script: makes "is there a daemon for this socket?"
     * and "start one" a single step, whoever runs the script.
     *
     * The app decides in Kotlin whether to launch ([awaitRootDaemon]), and two
     * start sequences that both asked before either had launched each started a
     * daemon — the second one's start-up cleanup then stripped the first one's
     * tailnet addresses from tailscale0, and the routes went with them. The
     * service no longer runs two sequences ([StartGate]), but the launch itself
     * must not depend on that: it takes a lock, then looks for the process, and
     * launches only with the lock held and no process found.
     *
     * The lock is a directory, because `mkdir` is atomic and exists in every
     * shell; it holds the owner's pid and is removed when the shell exits (the
     * EXIT trap, which INT/TERM/HUP are routed through). A shell killed outright
     * — su's timeout — leaves it behind, so a lock whose owner is gone, or one
     * older than any launch can take, is taken over. A lock with no pid yet is
     * a launch between its `mkdir` and its first write, and is left alone unless
     * it is that old. Either refusal prints a [LAUNCH_SKIPPED_LOCKED] or
     * [LAUNCH_SKIPPED_RUNNING] line and ends the script with status 0; the
     * caller then waits for the socket of the daemon that is there.
     */
    internal fun launchGuardScript(lockDir: String, socketPath: String): String = buildString {
        append("TS_LAUNCH_LOCK=").append(shQuote(lockDir)).append('\n')
        append("TS_LAUNCH_PAT=").append(shQuote("--socket=$socketPath")).append('\n')
        append("ts_launch_lock() {\n")
        append("    mkdir \"\$TS_LAUNCH_LOCK\" 2>/dev/null && { echo \$\$ > \"\$TS_LAUNCH_LOCK/pid\"; return 0; }\n")
        append("    h=\$(cat \"\$TS_LAUNCH_LOCK/pid\" 2>/dev/null)\n")
        // An age that cannot be read counts as fresh: only the owner's pid can then free it.
        append("    b=\$(stat -c %Y \"\$TS_LAUNCH_LOCK\" 2>/dev/null) || b=\$(date +%s)\n")
        append("    if [ \$((\$(date +%s) - b)) -le $LAUNCH_LOCK_STALE_S ]; then\n")
        append("        [ -z \"\$h\" ] && return 1\n")
        append("        kill -0 \"\$h\" 2>/dev/null && return 1\n")
        append("    fi\n")
        append("    rm -rf \"\$TS_LAUNCH_LOCK\"\n")
        append("    mkdir \"\$TS_LAUNCH_LOCK\" 2>/dev/null || return 1\n")
        append("    echo \$\$ > \"\$TS_LAUNCH_LOCK/pid\"\n")
        append("}\n")
        append("ts_launch_lock || { echo ").append(shQuote(LAUNCH_SKIPPED_LOCKED)).append("; exit 0; }\n")
        append("trap 'rm -rf \"\$TS_LAUNCH_LOCK\"' EXIT\n")
        append("trap 'exit 1' HUP INT TERM\n")
        append("if pgrep -f -- \"\$TS_LAUNCH_PAT\" >/dev/null 2>&1; then echo ").append(shQuote(LAUNCH_SKIPPED_RUNNING)).append("; exit 0; fi\n")
    }

    /** A launch lock older than this is a leftover: su gives a launch 30 s, then kills it. */
    private const val LAUNCH_LOCK_STALE_S = 60

    /**
     * Quotes a value for safe use inside a single-quoted shell word. Line breaks
     * are dropped: no root command argument legitimately contains one, and a
     * newline would end the line inside the env file the boot script reads.
     */
    internal fun shQuote(value: String): String =
        "'" + value.replace("\r", "").replace("\n", "").replace("'", "'\\''") + "'"

    /** A hosts entry may only contain characters valid in a DNS name. */
    private val hostLabel = Regex("^[A-Za-z0-9._-]{1,253}$")

    private val ipv4Literal = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6Literal = Regex("^[0-9A-Fa-f:]{2,45}$")

    /** An address is written to a root-owned file, so it must really be one. */
    internal fun isIpLiteral(value: String): Boolean = when {
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
        val hosts = paths.hostsFile
        // Only our own bind comes off, and before `set -e`: the unbind is a
        // loop of tests that end in a non-zero status by design. Whatever else
        // is mounted there — Magisk's systemless hosts, AdAway — stays, and its
        // entries become the base ours are appended to.
        sb.append(hostsUnbindScript(hosts, hostsMarker(hosts)))
        sb.append("set -e\n")
        sb.append("mkdir -p ${paths.hostsDir}\n")
        sb.append("cp /system/etc/hosts $hosts 2>/dev/null || printf '127.0.0.1 localhost\\n::1 ip6-localhost\\n' > $hosts\n")
        sb.append("cat >> $hosts <<'TAILSOCKS_EOF'\n")
        // How the next sync and the stop know the file at /system/etc/hosts is ours.
        sb.append(hostsMarker(hosts)).append('\n')
        for (line in lines) sb.append(line).append('\n')
        sb.append("TAILSOCKS_EOF\n")
        sb.append("chmod 644 $hosts\n")
        sb.append("chcon u:object_r:system_file:s0 $hosts 2>/dev/null || true\n")
        sb.append("mount -o bind $hosts /system/etc/hosts 2>/dev/null || true\n")
        return runSu("hosts-sync", sb.toString()).ok
    }

    /**
     * The line [updateRootHosts] writes into its copy of the hosts file. Names
     * the copy, so the release app and the debug build (each with its own
     * file, [RootPaths]) never take each other's for their own. No peer entry
     * can equal it: entries start with an address, this with `#`.
     */
    internal fun hostsMarker(hostsFile: String): String = "# TailSocks MagicDNS names, $hostsFile"

    /**
     * Unmounts what is bound over `/system/etc/hosts` only while it is ours,
     * and keeps going while ours is what is visible (a bind left stacked by an
     * earlier failure), four layers at most.
     *
     * Every sync and every stop used to run a bare `umount /system/etc/hosts`.
     * On the first sync after a boot our bind is not there yet, so that removed
     * whatever another root module had put there (Magisk's systemless hosts,
     * AdAway) and took the stock file as the base. Ours is recognised two ways,
     * either one enough: the topmost mount on that path in
     * `/proc/self/mountinfo` has our copy as its source (its root field is the
     * path inside the `/data` filesystem, or the full path), or the visible
     * file carries [hostsMarker] — which also covers a bind made by a build
     * that wrote no marker, and a mount namespace whose mountinfo names the
     * source differently. Field 4 of mountinfo is the root, field 5 the mount
     * point; read with shell builtins.
     */
    internal fun hostsUnbindScript(hostsFile: String, marker: String): String = buildString {
        val full = shQuote(hostsFile)
        val rel = shQuote(hostsFile.removePrefix("/data"))
        append("i=0\n")
        append("while [ \$i -lt 4 ]; do\n")
        append("    i=\$((i+1))\n")
        append("    top=\"\"\n")
        append("    while read -r f1 f2 f3 root mnt rest; do\n")
        append("        if [ \"\$mnt\" = /system/etc/hosts ]; then top=\"\$root\"; fi\n")
        append("    done < /proc/self/mountinfo\n")
        append("    ours=0\n")
        append("    case \"\$top\" in $full|$rel) ours=1;; esac\n")
        append("    [ \$ours = 1 ] || grep -qxF ${shQuote(marker)} /system/etc/hosts 2>/dev/null || break\n")
        append("    umount /system/etc/hosts 2>/dev/null || break\n")
        append("done\n")
    }

    /**
     * What an apply actually put on the device.
     *
     * A boolean could only say "the script ran", and three different things can
     * come out of one apply — the whole device, the share of it another tunnel
     * leaves us, or tailnet reachability alone — while the caller has to persist
     * which one for the dashboard to be honest about the exit node. The DNS
     * fields are separate from that: the redirect can be built and deliberately
     * left unhooked because MagicDNS is not answering, which is neither a
     * failure nor a yield.
     *
     * A failed apply still leaves rules on the device — the tailnet tier is
     * installed before anything can fail — which is why the caller records
     * them as installed before the script runs, not from [ok].
     *
     * @param ok                every rule the script was asked to install is there.
     * @param shared            the catch-all was scoped to the uid ranges another
     *                          tunnel bypasses, rather than taking the device.
     * @param dnsChainInstalled TAILSOCKS_DNS exists and is populated. This is what
     *                          the script wrote, not what [ok] says: the DNS
     *                          section runs before every verify line.
     * @param dnsHooked         the nat OUTPUT jumps are in, i.e. the redirect is
     *                          live. False with [dnsChainInstalled] true means the
     *                          health gate is holding it back; [setDnsRedirectHooked]
     *                          arms it again without a new apply. The caller has to
     *                          read both — a chain reported as hooked when it is not
     *                          is a chain nothing will ever arm.
     */
    class RoutingApplyResult(
        val ok: Boolean,
        val shared: Boolean,
        val dnsChainInstalled: Boolean,
        val dnsHooked: Boolean
    )

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
     *                          its sockets gets it (or, when the log is silent, one
     *                          that is the binary we ship). Also the settings, the
     *                          package manager and the daemon socket.
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
     *
     * @return what went on the device, as [RoutingApplyResult]; the caller
     *         persists it, because nothing else can tell the three outcomes apart
     *         once the script has exited.
     */
    fun applyTailscale0Routing(
        dnsRedirect: Boolean = true,
        dnsBypassAddrs: List<String> = emptyList(),
        context: Context,
        foreignVpn: Boolean? = null,
        takeDeviceAnyway: Boolean = false,
        probeResult: ForeignVpnVerdict? = null,
        exitNodeSelected: Boolean = false
    ): RoutingApplyResult {
        var verdict = soMarkVerdict(rootDaemonLogFile(context))
        // A log that never carried the probe line is silence, not a "no". Ask the
        // process itself before withholding the exit node. An explicit negative
        // from the daemon ("daemon logged: ...") is left alone.
        if (!verdict.marks && !verdict.reason.startsWith("daemon logged")) {
            daemonMarksByEnviron(context)?.let { verdict = it }
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
        val selfUid = context.applicationInfo?.uid?.toLong()
        val gaps = if (ownDevice) emptyList() else carryableGaps(probe.memberRanges, selfUid, context)
        val shared = !ownDevice && gaps.isNotEmpty()
        val installCatchAll = marks && (ownDevice || shared)
        val installDns = dnsRedirect && (ownDevice || shared)
        // The health gate, see [magicDnsAnswers]: the chain is built whenever
        // this tier is ours to install, but the two nat OUTPUT jumps that make
        // it live only go in while the daemon's resolver is answering.
        val hold = dnsRedirectHold
        if (installDns && hold != null) lastMagicDnsReason = hold
        val dnsHealthy = installDns && hold == null && magicDnsAnswers(context)
        if (installDns && !dnsHealthy) {
            rootLog(
                "WARN",
                "device-wide DNS redirect built but NOT armed: $lastMagicDnsReason. Port 53 stays with the " +
                    "system resolver, and the chain is left in place so it can be armed again the moment " +
                    "MagicDNS answers"
            )
        }
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
        // Manual exclusions become throws in table 52, where longest-prefix
        // match still lets a more specific accepted route win; the prefixes the
        // device itself is on become pref-150 rules, which the daemon's
        // RouteReplace cannot overwrite.
        val manualV4 = manualExclusions(context, v6 = false)
        val manualV6 = manualExclusions(context, v6 = true)
        val autoV4 = autoLanExclusions(context, v6 = false)
        val autoV6 = autoLanExclusions(context, v6 = true)
        // Read from Java rather than from the shell: this is a property of the
        // device's own interfaces, and the walk is the one upstream does.
        val cgnatGuard = cgnatInterfacePrefixes()
        if (cgnatGuard.isNotEmpty()) {
            rootLog(
                "INFO",
                "CGNAT guard: " + cgnatGuard.joinToString(", ") { "${it.second} on ${it.first}" } +
                    " sits inside 100.64.0.0/10 without being the tailnet; a throw route in table $ROUTE_TABLE " +
                    "keeps that traffic on its own network instead of sending it to tailscale0"
            )
        }

        val sb = StringBuilder()
        sb.append(legacyRuleCleanup())
        // 4.0 moved our table from 1099 to [ROUTE_TABLE]. Every apply removes
        // what the old one still holds: an upgrade must not leave rules pointing
        // at a table nothing refreshes, and on a device that reaches interface
        // index 99 that table now belongs to netd.
        sb.append(routeTableTeardown(LEGACY_ROUTE_TABLE, ourThrows = false))

        // --- IPv4 policy routing ---
        sb.append(tailnetRouteV4())
        // Immediately after the /10, because these are the holes in it.
        sb.append(cgnatGuardRoutes(cgnatGuard.map { it.second }))
        sb.append(tailnetRulesV4())

        // --- Per-app exclusions: uid rules, both families in one call ---
        // The teardown runs whatever we install: it clears the rules of an
        // earlier apply and the mangle chain of an earlier version. The install
        // only follows the catch-all — an exclusion is a hole in the catch-all,
        // and without one it would just take the uid out of the foreign tunnel.
        sb.append(bypassChainTeardown("iptables -w"))
        sb.append(excludeRulesTeardown())
        if (installCatchAll) sb.append(excludeRulesInstall(bypassUids))

        // --- Exit-node catch-all (IPv4): unmarked local traffic → daemon's table 52 ---
        sb.append(staleDaemonRulePurge("ip"))
        if (installCatchAll) {
            sb.append(localBypassRoutes("ip", manualV4))
            sb.append(localReturnRulesRemove("ip"))
            sb.append(localReturnRulesInstall("ip", autoV4))
            sb.append(catchAllInstall("ip", V4_CATCH_ALL_MISSING, bestEffort = false, scope = gaps))
            sb.append(rpFilterLoosen())
        } else {
            // Skipping the install removes nothing: the delete loop lives inside
            // catchAllInstall, so a rule from an earlier apply or from the boot
            // script (which has no VPN check at all) would survive untouched and
            // keep capturing the other tunnel's apps.
            sb.append(catchAllTeardown("ip"))
            sb.append(localBypassRoutesRemove("ip", manualV4))
            sb.append(localReturnRulesRemove("ip"))
            // Loose reverse-path filtering exists only because table 52 is
            // consulted for unmarked lookups. With the catch-all gone it is a
            // gratuitous system-wide change, so it goes back.
            sb.append(rpFilterRestore())
        }

        // --- IPv6 policy routing ---
        sb.append(tailnetTierV6())

        // --- Per-app exclusions (IPv6): the v6 uid rules are emitted above ---
        sb.append(bypassChainTeardown("ip6tables -w"))

        // --- Exit-node catch-all (IPv6) ---
        // The purge is harmless anywhere; the rule itself only where IPv6 exists
        // and is enabled, otherwise `ip -6 rule add` fails on every apply and the
        // marker would raise a false alarm each time.
        sb.append(staleDaemonRulePurge("ip -6"))
        if (installCatchAll) {
            sb.append(localBypassRoutes("ip -6", manualV6))
            sb.append(localReturnRulesRemove("ip -6"))
            sb.append("if [ -d /proc/sys/net/ipv6 ] && [ \"\$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null)\" = \"0\" ]; then\n")
            sb.append(localReturnRulesInstall("ip -6", autoV6))
            sb.append(catchAllInstall("ip -6", V6_CATCH_ALL_MISSING, bestEffort = true, scope = gaps))
            sb.append("fi\n")
        } else {
            // No IPv6 guard on the removal: a rule can only be there if IPv6 was
            // up when it was installed, and deleting what is not there is free.
            sb.append(catchAllTeardown("ip -6"))
            sb.append(localBypassRoutesRemove("ip -6", manualV6))
            sb.append(localReturnRulesRemove("ip -6"))
        }

        // --- System-wide DNS redirect ---
        // The teardown is unconditional, so yielding this tier is expressed by
        // not installing it: the chain and both nat OUTPUT jumps are gone by the
        // time the script reaches here, whoever put them there.
        sb.append(dnsChainTeardown())
        if (installDns) {
            sb.append("iptables -w -t nat -N $CHAIN_DNS 2>/dev/null || iptables -w -t nat -F $CHAIN_DNS\n")
            // First, before anything else in the chain: whatever the other
            // tunnel resolves for its own apps goes through untouched. netd
            // stamps its network id into the low 16 bits of the mark, and it is
            // still there when the platform resolver sends the query on an
            // app's behalf — which is the only reason this can work at all,
            // since the uid on that packet is the resolver's, not the app's.
            for (netId in dnsExemptNetIds) {
                sb.append(
                    "iptables -w -t nat -A $CHAIN_DNS -m mark --mark 0x%x/0xffff -j RETURN 2>/dev/null || true\n"
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
                sb.append("iptables -w -t nat -A $CHAIN_DNS -m owner --uid-owner $uid -j RETURN 2>/dev/null || echo '$OWNER_MATCH_MISSING'\n")
            }
            // Queries already aimed at the tailnet (MagicDNS itself, Split DNS
            // resolvers living on peers) must never be rewritten.
            sb.append("iptables -w -t nat -A $CHAIN_DNS -d $CGNAT_V4 -j RETURN\n")
            // Upstream resolvers the daemon and our own DNS proxy forward to.
            for (addr in dnsBypassAddrs.distinct()) {
                sb.append("iptables -w -t nat -A $CHAIN_DNS -d $addr -j RETURN\n")
            }
            sb.append("iptables -w -t nat -A $CHAIN_DNS -p udp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53\n")
            sb.append("iptables -w -t nat -A $CHAIN_DNS -p tcp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53\n")
            // The chain is complete either way; only these two lines redirect
            // anything, and they wait for a resolver that answers.
            if (dnsHealthy) sb.append(dnsHookInstall())
            // Whether the same capture is possible over IPv6 at all. Asked, not
            // assumed: `ip6tables -t nat` exists on some kernels and not on
            // others, and the answer decides what the summary is allowed to claim.
            // The v6-up test is written out here rather than reusing the V6UP
            // variable: that one is set by the exclusions fragment, which is
            // only appended when there are uids to exclude.
            sb.append(
                "[ -d /proc/sys/net/ipv6 ] && [ \"\$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null)\" = \"0\" ] && " +
                    "{ ip6tables -w -t nat -S >/dev/null 2>&1 || echo '$DNS_V6_NO_NAT'; } || true\n"
            )
        }

        sb.append(applyVerifyScript(installDns, checkExitRoute = installCatchAll && exitNodeSelected))

        // Before the script, not after it: the first thing it does is write the
        // tailnet tier, so from here on there are rules on this device whatever
        // happens next. A failed apply used to leave them with nothing recording
        // that they existed — not the next apply, not the stop path — and they
        // stayed until the phone was rebooted.
        GlobalSettings.setRootRoutingInstalled(context, true)

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
        // The routing half of a per-app exclusion is a uid rule, and an `ip`
        // built without uid-range support installs neither form of it. Nothing
        // said so: the summary below announced the exclusion either way.
        val uidRulesRefused = res.output.contains(UIDRANGE_MISSING_V4)
        if (uidRulesRefused) {
            rootLog(
                "WARN",
                "per-app exclusions NOT applied: this device's `ip` accepted no uidrange rule at all, so the " +
                    "excluded apps stay inside the tunnel exactly like every other app. Nothing in Root Mode " +
                    "can carve them out on this kernel"
            )
        }
        if (res.output.contains(DNS_V6_NO_NAT)) {
            GlobalSettings.setRootDnsV6Unsupported(context, true)
            rootLog(
                "WARN",
                "device-wide DNS covers IPv4 only: this kernel has no IPv6 `nat` table, so there is nowhere to " +
                    "write the v6 redirect. A query sent over IPv6 reaches its resolver directly instead of " +
                    "MagicDNS — tailnet names still resolve, because the daemon's own resolver is asked over IPv4"
            )
        } else if (installDns) {
            GlobalSettings.setRootDnsV6Unsupported(context, false)
        }
        // Not ours to fix, and not a reason to fail the apply: re-applying our
        // rules does not give tailscale0 its address back. But they must not pass
        // silently either — a tailscale0 stripped by a second daemon's start-up
        // cleanup ran for a whole session with the card saying Active while
        // nothing on the phone reached the tailnet or the exit node.
        if (res.output.contains(DAEMON_NO_ADDRESS)) {
            rootLog(
                "WARN",
                "daemon-side problem: tailscale0 carries no tailnet address, so the kernel has no route " +
                    "into the tailnet and table $DAEMON_TABLE is empty. The daemon's router did not configure the " +
                    "interface (or something removed its addresses); the app's rules cannot fix that. Stop and start the connection"
            )
        }
        if (res.output.contains(DAEMON_NO_EXIT_ROUTE)) {
            rootLog(
                "WARN",
                "daemon-side problem: an exit node is selected but table $DAEMON_TABLE has no default route, so " +
                    "the pref-$CATCH_ALL_PRIO catch-all falls through and traffic leaves on the physical network. " +
                    "The daemon has not routed the exit node (yet); the app's rules cannot fix that"
            )
        }
        if (res.output.contains(UIDRANGE_MISSING_V6)) {
            rootLog(
                "INFO",
                "per-app exclusions not applied to IPv6: `ip -6` accepted no uidrange rule. The excluded apps " +
                    "leave the tunnel over IPv4 and stay in it over IPv6"
            )
        }
        if (bypassUids.isNotEmpty()) {
            val what = when {
                uidRulesRefused -> "could NOT be excluded (see the warning above); they are routed like every other app"
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
                installDns && !dnsHealthy && hold != null -> "built but not armed, $hold"
                installDns && !dnsHealthy -> "built but not armed, MagicDNS is not answering"
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
        // The jumps are part of the script above, so the cached hook state is
        // set from what it did rather than by calling out to another shell.
        //
        // Deliberately not conditioned on [SuResult.ok]: the script has no
        // `set -e` and every verify line runs after the DNS section, so whatever this
        // section was asked to install is on the device whether the verify
        // passed or not. Reporting a live redirect as absent left the health
        // gate — the only thing that can take it off again — disarmed against a
        // redirect that was still pointing the whole phone at 100.100.100.100.
        val dnsHooked = installDns && dnsHealthy
        noteDnsHookState(
            dnsHooked,
            if (installDns) lastMagicDnsReason
            else "the device-wide DNS redirect is not this device's to install"
        )
        return RoutingApplyResult(
            ok = res.ok,
            shared = shared,
            dnsChainInstalled = installDns,
            dnsHooked = dnsHooked
        )
    }

    // Moved to RootRoutingScript.kt; kept here as the entry point callers use.
    internal fun applyVerifyScript(installDns: Boolean, checkExitRoute: Boolean): String = RootRoutingScript.applyVerifyScript(installDns, checkExitRoute)

    /** Printed by [applyVerifyScript] when tailscale0 has no tailnet address. */
    internal const val DAEMON_NO_ADDRESS = "daemon: tailscale0 has no tailnet address"
    /** Printed by [applyVerifyScript] when an exit node is selected and table 52 has no default route. */
    internal const val DAEMON_NO_EXIT_ROUTE = "daemon: no default route in table 52 with an exit node selected"

    // Moved to RootRoutingScript.kt; kept here as the entry point callers use.
    internal fun tailnetRouteV4(): String = RootRoutingScript.tailnetRouteV4()
    internal fun tailnetRulesV4(): String = RootRoutingScript.tailnetRulesV4()
    internal fun tailnetTierV6(): String = RootRoutingScript.tailnetTierV6()

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
    /**
     * uids of apps that provide a `VpnService` of their own.
     *
     * A VPN client is always outside its own tunnel — it would loop its relay
     * traffic through itself otherwise — so its uid is always a gap, every time,
     * whether or not its owner bypassed anything. Carrying it would route the
     * other client's relay out through our exit node, which nobody asked for,
     * and would keep [foreignBypassGaps] from ever returning empty, so the full
     * yield could never be reached. Android knows who these apps are; we ask it.
     */
    private fun vpnClientUids(vpnServices: List<android.content.pm.ResolveInfo>?): Set<Long> =
        vpnServices?.mapNotNull { it.serviceInfo?.applicationInfo?.uid?.toLong() }?.toSet() ?: emptySet()

    /** Every app's `VpnService`, asked once per apply for both uses below; null when the query failed. */
    private fun vpnServices(context: Context): List<android.content.pm.ResolveInfo>? = runCatching {
        context.packageManager.queryIntentServices(Intent("android.net.VpnService"), PackageManager.MATCH_ALL)
    }.getOrNull()

    /**
     * uids we are willing to carry: installed applications that are not VPN
     * clients and not us.
     *
     * Built as a positive set on purpose. Subtracting the bad uids instead meant
     * trusting what the framework reports for an arbitrary uid, and a gap can
     * contain uids no application owns at all — Android reserves an SDK sandbox
     * uid beside every app (appId + 10000), so netd's membership list has a hole
     * there too. Measured on the device: uid 20354 sat in a gap with no package
     * behind it, and carrying it wrote a rule that could never match anything.
     */
    private fun carryableUids(context: Context, vpnServices: List<android.content.pm.ResolveInfo>?): Set<Long>? {
        val pm = context.packageManager
        vpnServices ?: return null
        return runCatching {
            val vpnPackages = vpnServices
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
            val selfUid = context.applicationInfo?.uid?.toLong()
            pm.getInstalledApplications(0)
                .filter { it.packageName !in vpnPackages }
                .map { it.uid.toLong() }
                .filterNot { it == selfUid }
                .toSet()
        }.getOrNull()
    }

    /** Splits [range] around [holes], dropping the holes themselves. */
    internal fun splitAround(range: LongRange, holes: Collection<Long>): List<LongRange> {
        val sorted = holes.filter { it in range }.sorted()
        if (sorted.isEmpty()) return listOf(range)
        val out = mutableListOf<LongRange>()
        var start = range.first
        for (h in sorted) {
            if (start <= h - 1) out.add(start..(h - 1))
            start = h + 1
        }
        if (start <= range.last) out.add(start..range.last)
        return out
    }

    /** Beyond this many uids a gap is kept whole rather than resolved app by app. */
    private const val GAP_RESOLVE_LIMIT = 64

    internal fun foreignBypassGaps(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val gaps = mutableListOf<LongRange>()
        var next = 0L
        for (r in sorted) {
            if (r.first > next) gaps.add(next..(r.first - 1))
            if (r.last + 1 > next) next = r.last + 1
        }
        if (next <= MAX_ANDROID_UID) gaps.add(next..MAX_ANDROID_UID)
        return gaps
    }

    /**
     * The gaps we may actually carry: everything the other tunnel bypassed, minus
     * ourselves and minus every uid that is a VPN client or nothing at all.
     *
     * A small gap is resolved uid by uid, which is the common case — a handful of
     * apps the user picked. A large one is kept whole and only known VPN uids are
     * cut out of it, so a client running in "tunnel only these apps" mode cannot
     * make us walk thousands of uids or install a rule per app.
     */
    private fun carryableGaps(ranges: List<LongRange>, selfUid: Long?, context: Context): List<LongRange> {
        val raw = foreignBypassGaps(ranges)
        if (raw.isEmpty()) return raw
        val services = vpnServices(context)
        val vpnUids = vpnClientUids(services) + listOfNotNull(selfUid)
        val carryable = carryableUids(context, services)
        return raw.flatMap { g ->
            val span = g.last - g.first + 1
            when {
                // The ordinary case: a handful of apps the user picked. Name them
                // one by one, so nothing is captured that no app owns.
                carryable != null && span <= GAP_RESOLVE_LIMIT ->
                    (g.first..g.last).filter { it in carryable }.map { it..it }
                // A client tunnelling only a few apps leaves most of the device in
                // the gaps. Keep those whole rather than writing a rule per app;
                // the uids that own nothing cost a rule that never matches, which
                // is cheaper than hundreds of rules that do.
                else -> splitAround(g, vpnUids)
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
                append("    case \"\$t\" in $ROUTE_TABLE|$LEGACY_ROUTE_TABLE|$DAEMON_TABLE|main|local|default|tailscale0|'') continue;; esac\n")
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
        return parseForeignVpnProbe(res.output)
    }

    /** The coexistence probe's `FOREIGN_*` lines, read into a verdict. */
    internal fun parseForeignVpnProbe(output: String): ForeignVpnVerdict {
        fun values(tag: String) = output.lineSequence()
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
        val res = runSu("routing-cleanup", routingCleanupScript())
        if (res.ok) noteRoutingRemoved()
        return res.ok
    }

    /** What a finished routing cleanup records, whichever shell ran it. */
    private fun noteRoutingRemoved() {
        noteDnsHookState(false, "the Root Mode ruleset was removed")
        rootLog("INFO", "tailscale0 routing removed")
    }

    // Moved to RootRoutingScript.kt; kept here as the entry point callers use.
    internal fun routingCleanupScript(): String = RootRoutingScript.routingCleanupScript()

    /** What the last hook/unhook put on the device, so only real transitions are logged. */
    @Volatile private var dnsRedirectHooked: Boolean? = null

    /** The verdict [magicDnsAnswers] last reached, named in the hook log as the reason. */
    @Volatile private var lastMagicDnsReason: String = "MagicDNS has not been probed yet"

    /**
     * Hooks or unhooks the device-wide DNS redirect, leaving [CHAIN_DNS] built
     * either way. Returns true when the device is in the requested state
     * afterwards; safe to call when it already was.
     *
     * This is the health gate's actuator: the redirect points every port-53
     * packet on the phone at 100.100.100.100, so it may only be armed while
     * something answers there ([magicDnsAnswers]), and must come off again the
     * moment nothing does — without tearing down a chain that would then have
     * to be rebuilt from inputs only an apply holds.
     */
    fun setDnsRedirectHooked(hooked: Boolean): Boolean = setDnsRedirectHooked(hooked, lastMagicDnsReason)

    private fun setDnsRedirectHooked(hooked: Boolean, reason: String): Boolean {
        val script = buildString {
            if (hooked) {
                // A jump to a chain that does not exist is refused by iptables,
                // and the caller would be left believing DNS is redirected.
                append("iptables -w -t nat -S $CHAIN_DNS >/dev/null 2>&1 || { echo '$DNS_CHAIN_ABSENT'; exit 1; }\n")
                append(dnsHookInstall())
                // Both, read back: a UDP jump alone would leave every TCP query
                // — a large answer, a DoT-less resolver retrying — outside
                // MagicDNS while the caller was told the redirect is on.
                for (proto in listOf("udp", "tcp")) {
                    append(
                        "iptables -w -t nat -C OUTPUT -p $proto --dport 53 -j $CHAIN_DNS 2>/dev/null || " +
                            "{ echo '$DNS_HOOK_FAILED'; exit 1; }\n"
                    )
                }
            } else {
                append(dnsHookRemove())
            }
        }
        val res = runSu(if (hooked) "dns-redirect-arm" else "dns-redirect-disarm", script, timeoutMs = 10_000L)
        if (!res.ok) {
            rootLog(
                "WARN",
                if (hooked) {
                    "device-wide DNS redirect could not be armed (" +
                        (if (res.output.contains(DNS_CHAIN_ABSENT)) "the $CHAIN_DNS chain is not on this device; a full routing apply has to build it"
                        else "iptables refused the nat OUTPUT jump") + "); port 53 stays with the system resolver"
                } else {
                    "device-wide DNS redirect could not be removed; port 53 still goes to MagicDNS"
                }
            )
            return false
        }
        noteDnsHookState(hooked, reason)
        return true
    }

    /**
     * Logs a change of the redirect's state, naming what caused it. Repeats say
     * nothing, and neither does the first apply of a run: until then there is no
     * previous state to have changed from, and the apply's own summary already
     * reports what it installed.
     */
    private fun noteDnsHookState(hooked: Boolean, reason: String) {
        val previous = dnsRedirectHooked
        dnsRedirectHooked = hooked
        if (previous == null || previous == hooked) return
        rootLog(
            "INFO",
            if (hooked) {
                "device-wide DNS redirect armed: port 53 on this device now goes to MagicDNS ($reason)"
            } else {
                "device-wide DNS redirect disarmed: port 53 goes back to the system resolver ($reason). " +
                    "The $CHAIN_DNS chain stays built, so re-arming it is two rules"
            }
        )
    }

    /**
     * Whether the daemon's own resolver answers a query right now.
     *
     * The redirect above sends every port-53 packet on the device to
     * 100.100.100.100, so it must only be armed while there is a working
     * resolver behind that address. The state that breaks this is not a crash
     * and does not show up on the health bus: turning "Accept DNS" off leaves
     * the daemon running with an empty dns.Config, and upstream's forwarder
     * deliberately does not raise dnsForwarderFailing for it — see
     * net/dns/resolver/forwarder.go, "No upstream resolver for this name isn't
     * a forwarder failure", which then logs "no upstream resolvers set,
     * returning SERVFAIL". Every query on the device would get SERVFAIL while
     * the bus stays quiet, which is why this asks the resolver instead of
     * waiting to be told.
     *
     * Two questions. The node's own MagicDNS name first: a configured resolver
     * answers it from its in-memory host map without touching the network, so
     * a failure there is a broken configuration, not a flaky link. Then a name
     * outside the tailnet ([upstreamProbeName]), which the resolver has to
     * forward. That is the query every app on the phone makes, and the own name
     * alone could not see its path fail: when the tailnet's DNS server is a peer
     * and the tunnel is down, the own name kept passing while every lookup on
     * the device timed out — and the daemon's "DNS unavailable" warning is
     * hidden behind its network-down one, so the health bus said nothing either.
     *
     * The outside name is a fresh random label under [UPSTREAM_PROBE_ZONE] on
     * every probe. A fixed name (`connectivitycheck.gstatic.com` until now) can
     * be answered from a cache on the way — the daemon's, the tailnet DNS
     * server's — for as long as its TTL, while the upstream behind it is dead;
     * a name nobody has asked before has to travel to the zone's own servers.
     * Their answer is NXDOMAIN, which counts as healthy, as NOERROR does
     * ([upstreamProbeHealthy]); SERVFAIL, REFUSED, any other code and silence
     * mean nothing upstream is reachable.
     *
     * What this validates is the resolver and what it forwards to, not the
     * path to it. localapi is answered in-process, inside the daemon, and never
     * traverses the nat OUTPUT DNAT — this is not an end-to-end test of the
     * redirect, and a device whose iptables rules are wrong will still pass it.
     *
     * No root shell: two unix-socket reads, the second waiting at most
     * [UPSTREAM_PROBE_TIMEOUT_MS] for the forwarder to give up.
     */
    fun magicDnsAnswers(context: Context): Boolean {
        val name = selfDnsName(context)
        if (name.isNullOrEmpty()) {
            lastMagicDnsReason = "the daemon reports no MagicDNS name for this node"
            return false
        }
        val own = dnsQuery(context, name, 3_000)
        if (own.rcode != 0) {
            lastMagicDnsReason = own.failure ?: "the daemon's resolver returned ${rcodeName(own.rcode)} for this node's own name $name"
            return false
        }
        val probeName = upstreamProbeName()
        val upstream = dnsQuery(context, probeName, UPSTREAM_PROBE_TIMEOUT_MS)
        if (upstream.failure != null || !upstreamProbeHealthy(upstream.rcode)) {
            lastMagicDnsReason = upstream.failure?.let { "own name answered, but $it" }
                ?: "own name answered, but the daemon's resolver returned ${rcodeName(upstream.rcode)} for " +
                    "$probeName: it cannot reach its upstream DNS servers"
            return false
        }
        lastMagicDnsReason = "the daemon's resolver answered $name (${own.answers} record(s)) and " +
            "a fresh name under $UPSTREAM_PROBE_ZONE (${rcodeName(upstream.rcode)})"
        return true
    }

    /**
     * A name under [UPSTREAM_PROBE_ZONE] that no cache can hold an answer for:
     * a random 64-bit label, new on every call.
     */
    internal fun upstreamProbeName(random: java.util.Random = probeRandom): String =
        "tsprobe-%016x.%s".format(random.nextLong(), UPSTREAM_PROBE_ZONE)

    /** NOERROR and NXDOMAIN are answers from upstream; every other code is not. */
    internal fun upstreamProbeHealthy(rcode: Int): Boolean = rcode == 0 || rcode == 3

    private val probeRandom = java.security.SecureRandom()

    private class DnsProbe(val rcode: Int, val answers: Int, val failure: String?)

    /** One query through localapi; [DnsProbe.failure] says why there was no DNS message. */
    private fun dnsQuery(context: Context, name: String, timeoutMs: Int): DnsProbe {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        val body = localApiGet(context, "/localapi/v0/dns-query?name=$encoded&type=A", timeoutMs)
            ?: return DnsProbe(-1, 0, "the daemon's localapi did not answer a DNS query for $name")
        val packed = runCatching {
            AppJson.decodeFromString(DnsQueryResponse.serializer(), body).bytes
                ?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        }.getOrNull()
        // A DNS message shorter than its own header is not one; so is the plain
        // text an error status carries, which is quoted here because "the
        // resolver said no" and "localapi said no" call for different fixes.
        if (packed == null || packed.size < 12) {
            return DnsProbe(-1, 0, "the daemon's localapi returned no DNS message for $name (${body.trim().take(80)})")
        }
        val rcode = packed[3].toInt() and 0x0f
        val answers = ((packed[6].toInt() and 0xff) shl 8) or (packed[7].toInt() and 0xff)
        return DnsProbe(rcode, answers, null)
    }

    /**
     * A zone that resolves everywhere, is never a tailnet name and answers a
     * label it does not have with NXDOMAIN rather than a wildcard.
     */
    internal const val UPSTREAM_PROBE_ZONE = "gstatic.com"
    /** Longer than the forwarder's own retries take to end in SERVFAIL. */
    private const val UPSTREAM_PROBE_TIMEOUT_MS = 6_000

    private fun rcodeName(rcode: Int): String = when (rcode) {
        0 -> "NOERROR"
        1 -> "FORMERR"
        2 -> "SERVFAIL"
        3 -> "NXDOMAIN"
        4 -> "NOTIMP"
        5 -> "REFUSED"
        else -> "rcode $rcode"
    }

    /**
     * This node's MagicDNS name.
     *
     * From the bridge when it is attached, which costs nothing — it is served
     * from the state the daemon already pushed over the bus. Root Mode outlives
     * the app process, though, so the bridge may be attached to nothing while
     * the daemon runs perfectly well; localapi answers then.
     */
    private fun selfDnsName(context: Context): String? {
        val fromBridge = runCatching { appctr.Appctr.getSelfDNSName() }.getOrNull()
        if (!fromBridge.isNullOrBlank()) return fromBridge.trimEnd('.')
        // Only Self is read: without peers=false the daemon serialises every peer of the tailnet for it.
        val status = localApiGet(context, "/localapi/v0/status?peers=false") ?: return null
        val self = runCatching { AppJson.decodeFromString(StatusResponse.serializer(), status).self }.getOrNull()
        return self?.dnsName?.trimEnd('.')?.ifBlank { null }
    }

    /**
     * One blocking GET against the daemon's localapi, bounded in time.
     *
     * [LocalApiClient] speaks the same protocol, but it is suspend-only and sets
     * no read timeout; this runs on the routing thread, where a daemon that
     * accepts the connection and then says nothing must not park it forever.
     * Returns the response body, or null when anything at all went wrong — the
     * callers treat "no answer" and "a bad answer" alike.
     */
    private fun localApiGet(context: Context, path: String, timeoutMs: Int = 3_000): String? =
        localApiGet(File(context.filesDir, "tailscaled.sock").absolutePath, path, timeoutMs)

    private fun localApiGet(socketPath: String, path: String, timeoutMs: Int): String? {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = timeoutMs
                val request = "GET $path HTTP/1.1\r\nHost: local-tailscaled.sock\r\nConnection: close\r\n\r\n"
                socket.outputStream.apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }
                // ISO-8859-1 decodes bytes 1:1, so string offsets are byte
                // offsets — which is what the chunk lengths below are counted in.
                httpBody(socket.inputStream.readBytes().toString(Charsets.ISO_8859_1))
            }
        } catch (e: Exception) {
            Log.d(TAG, "localApiGet $path failed: ${e.message}")
            null
        }
    }

    /**
     * The body of an HTTP/1.1 response, de-chunked if it has to be.
     *
     * Go's server picks chunked transfer encoding whenever it cannot size the
     * body up front, which it can for a small handler like dns-query and cannot
     * for the status dump, so both forms really do arrive here.
     */
    private fun httpBody(response: String): String? {
        val sep = response.indexOf("\r\n\r\n")
        if (sep < 0) return null
        val head = response.substring(0, sep)
        val body = response.substring(sep + 4)
        if (!head.contains("Transfer-Encoding: chunked", ignoreCase = true)) {
            return body.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
        }
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val eol = body.indexOf("\r\n", i)
            if (eol < 0) break
            // The size line may carry chunk extensions after a semicolon.
            val size = body.substring(i, eol).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = eol + 2
            val end = minOf(start + size, body.length)
            out.append(body, start, end)
            i = end + 2
        }
        return out.toString().toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
    }

    // Moved to RootRoutingScript.kt; kept here as the entry point callers use.
    internal fun legacyRuleCleanup(): String = RootRoutingScript.legacyRuleCleanup()

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
     * The user's own exclusions plus link-local, filtered to plain CIDRs of the
     * right family. These are written as `throw` routes in the daemon's table,
     * where longest-prefix match lets a more specific accepted subnet route win
     * over a broader exclusion.
     */
    private fun manualExclusions(context: Context, v6: Boolean): List<String> {
        val (valid, dropped) = filterCidrs(GlobalSettings.getTunExcludedCIDRs(context), v6)
        if (dropped.isNotEmpty()) {
            rootLog("WARN", "exit-node exclusions ignored, not plain CIDRs: ${dropped.joinToString(", ")}")
        }
        return valid
    }

    /**
     * The setting's text split into CIDRs of one family, link-local added:
     * the ones kept (in order, once each) and the ones dropped for not being
     * plain CIDRs.
     */
    internal fun filterCidrs(fromSettings: String, v6: Boolean): Pair<List<String>, List<String>> {
        val all = (fromSettings.split(',', ';', ' ', '\n') + listOf(LINK_LOCAL_V4, LINK_LOCAL_V6))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val wanted = all.filter { if (v6) it.contains(':') else !it.contains(':') }
        val valid = wanted.filter { if (v6) CIDR_V6.matches(it) else CIDR_V4.matches(it) }
        val dropped = wanted - valid.toSet()
        return valid.distinct() to dropped
    }

    /**
     * Prefixes this device actually sits on, when the avoidance setting is on.
     * These are written as pref-[LAN_RETURN_PRIO] rules instead of throws: the
     * conflict they exist for is an accepted route with the *same* prefix, which
     * the daemon's RouteReplace would overwrite in table 52.
     */
    private fun autoLanExclusions(context: Context, v6: Boolean): List<String> =
        if (GlobalSettings.isRootYieldLanEnabled(context)) ownLanPrefixes(v6) else emptyList()

    /**
     * Prefixes this device holds on a real interface: the LAN the platform
     * routes on its own. CGNAT and link-local are dropped (the first belongs to
     * [cgnatInterfacePrefixes], the second to nothing), and so is every
     * interface that is a tunnel rather than a link.
     */
    private fun ownLanPrefixes(v6: Boolean): List<String> {
        val found = mutableListOf<String>()
        val interfaces =
            try {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (e: Exception) {
                return emptyList()
            }
        for (nif in interfaces) {
            val name = nif.name ?: continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (NON_LAN_INTERFACES.any { name.startsWith(it) }) continue
            for (ia in nif.interfaceAddresses.orEmpty()) {
                val addr = ia.address ?: continue
                val len = ia.networkPrefixLength.toInt()
                if (!v6 && addr is java.net.Inet4Address && len in 1..32) {
                    val value = ipv4Value(addr.address)
                    if (value in 0x64400000L..0x647fffffL) continue
                    if (value in 0xa9fe0000L..0xa9feffffL) continue
                    if (value in 0x7f000000L..0x7fffffffL) continue
                    found.add(ipv4Network(addr.address, len))
                } else if (v6 && addr is java.net.Inet6Address && len in 1..128) {
                    if (addr.isLinkLocalAddress || addr.isLoopbackAddress) continue
                    val bytes = addr.address
                    val network = ByteArray(bytes.size) { i ->
                        val keep = (len - i * 8).coerceIn(0, 8)
                        if (keep == 0) 0 else (bytes[i].toInt() and (0xff shl (8 - keep))).toByte()
                    }
                    val masked = java.net.InetAddress.getByAddress(network) as java.net.Inet6Address
                    found.add("${masked.hostAddress}/$len")
                }
            }
        }
        return found.distinct()
    }

    /** An IPv4 address's four bytes as an unsigned number. */
    internal fun ipv4Value(bytes: ByteArray): Long = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

    /** `a.b.c.d/len` for the network an IPv4 address of prefix length [len] (1..32) sits in. */
    internal fun ipv4Network(bytes: ByteArray, len: Int): String {
        val mask = (0xffffffffL shl (32 - len)) and 0xffffffffL
        val network = ipv4Value(bytes) and mask
        val dotted = (24 downTo 0 step 8).joinToString(".") { ((network shr it) and 0xff).toString() }
        return "$dotted/$len"
    }

    /** Signature for the routing-apply gate: a changed LAN prefix must re-apply the rules. */
    fun localLanSignature(): String = (ownLanPrefixes(false) + ownLanPrefixes(v6 = true)).sorted().joinToString(",")

    /**
     * The user's excluded apps as uids, resolved at apply time.
     *
     * The same list the TUN mode hands to `addDisallowedApplication`, so one
     * setting governs both tunnel modes. A package that is no longer installed
     * is skipped and named in the log rather than failing the apply.
     */
    private fun excludedUids(context: Context): List<Int> {
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

    // Moved to RootRoutingScript.kt; kept here as the entry point callers use.
    internal fun excludeRulesInstall(uids: List<Int>): String = RootRoutingScript.excludeRulesInstall(uids)

    /**
     * Prefixes inside 100.64.0.0/10 that belong to an interface which is not
     * ours, paired with the interface that holds them.
     *
     * `to 100.64.0.0/10 iif lo lookup <ours> priority 100` is unconditional and
     * sits above every per-app exclusion, so nothing on the device can opt out
     * of it. That is right while the CGNAT range means "the tailnet" and wrong
     * the moment it does not: a carrier handing out a 100.64.0.0/10 WAN address,
     * or another VPN client whose relay or tun sits there, and we capture
     * traffic that has nothing to do with Tailscale — with no way for the user
     * to get it back. Upstream guards the same case in ipn/ipnlocal/local.go
     * through netmon's HasCGNATInterface(), which walks the interfaces for a
     * prefix overlapping the range and skips Tailscale's own; this is that walk,
     * from Java, and every prefix it returns becomes a `throw` route in our
     * table. Longest-prefix match inside the table picks the throw over our /10
     * and the lookup falls through to the rules below ours: the pref-
     * [CATCH_ALL_PRIO] catch-all where it is installed, so a tailnet peer that
     * happens to sit inside the colliding prefix is still reached through its
     * own /32 in the daemon's table, and netd otherwise, which routes it exactly
     * as it did before Root Mode started.
     *
     * A prefix shorter than /11 would cover more than the CGNAT range itself and
     * a /10 would replace our own route outright, so those are refused and named
     * rather than installed.
     */
    private fun cgnatInterfacePrefixes(): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val interfaces = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            rootLog("WARN", "CGNAT guard skipped: this device's interface list could not be read (${e.message})")
            return emptyList()
        }
        for (nif in interfaces) {
            val name = nif.name ?: continue
            // Ours by name, the way upstream's isTailscaleInterface does it: the
            // whole point is to find someone else's CGNAT address.
            if (name.startsWith("tailscale")) continue
            if (!(runCatching { nif.isUp }.getOrDefault(false))) continue
            for (ia in nif.interfaceAddresses.orEmpty()) {
                val addr = ia.address as? java.net.Inet4Address ?: continue
                val value = ipv4Value(addr.address)
                if (value !in 0x64400000L..0x647fffffL) continue
                val len = ia.networkPrefixLength.toInt()
                if (len !in 11..32) {
                    rootLog(
                        "WARN",
                        "$name holds ${addr.hostAddress}/$len inside 100.64.0.0/10, but a /$len throw route would " +
                            "cover the tailnet itself; leaving the tailnet rule alone and that interface captured"
                    )
                    continue
                }
                found.add(name to ipv4Network(addr.address, len))
            }
        }
        return found.distinctBy { it.second }
    }

    /**
     * The CGNAT guard as a routing-signature term: every colliding prefix, in a
     * stable order.
     *
     * The guard is the one input of an apply that is read from the device rather
     * than from a setting, and an apply is skipped while its inputs are
     * unchanged. Without this term the walk below is repeated only when
     * something else happens to change: a carrier that hands out a
     * 100.64.0.0/10 address after the last apply is captured for the rest of the
     * session, and a throw for an interface that has gone stays in our table.
     * It belongs in that list rather than in a callback because it costs what
     * every other member costs — no root shell, no network — and because the
     * question it answers is "would the ruleset differ?", which is the only
     * question the signature asks.
     */
    fun cgnatGuardSignature(): String =
        cgnatInterfacePrefixes().map { it.second }.sorted().joinToString(",")

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
     *
     * Read by [SoMarkLogScan]: after the first full scan of a run only the
     * bytes appended since are read.
     */
    private fun soMarkVerdict(logFile: File): SoMarkVerdict =
        soMarkScan.verdict(logFile, if (lastDaemonLogFile == logFile) daemonLogStartOffset else -1L)

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
    private fun daemonMarksByEnviron(context: Context): SoMarkVerdict? {
        val libDir = context.applicationInfo?.nativeLibraryDir ?: return null
        val script = buildString {
            append("for p in \$(pidof libtailscale.so 2>/dev/null); do\n")
            // The binary is the CLI as well (ts_include_cli): a `tailscale`
            // command running through the root wrapper is the same file under the
            // same name. Only a process with a state directory is a daemon.
            append("    tr '\\0' '\\n' < /proc/\$p/cmdline 2>/dev/null | grep -q '^--statedir=' || continue\n")
            append("    exe=\$(readlink /proc/\$p/exe 2>/dev/null)\n")
            append("    case \"\$exe\" in ${shQuote(libDir)}*) echo SOMARK_ENV_OURS; break;; esac\n")
            append("done\n")
        }
        val res = runSu("somark-environ", script, timeoutMs = 8_000L)
        if (!res.ok) return null
        // Note what is NOT checked: TS_VPN_BYPASS. Patch 16's socketMark() returns
        // the bypass bit unconditionally and only adds Android's protect bit when
        // that setting is on, so a daemon started with TS_VPN_BYPASS=0 still skips
        // our table — which is the only thing the catch-all's mask asks about.
        // Reading that variable as "does not mark" withheld the exit node for a
        // reason that does not exist.
        return if (res.output.contains("SOMARK_ENV_OURS")) {
            SoMarkVerdict(true, "the running daemon is the binary we ship, which marks its own sockets")
        } else {
            null
        }
    }

    /** Remembers how far the daemon log was read, so an apply reads only what was appended since. */
    private val soMarkScan = SoMarkLogScan()

    /**
     * Cuts the daemon log to its last [LOG_TRIM_KEEP_LINES] lines when it is
     * over [LOG_TRIM_BYTES]: the boot script's three lines, for the app's own
     * start. Run only before a daemon starts — `mv` gives the file a new inode,
     * which a running daemon would not follow.
     */
    internal fun logTrimScript(logFile: String): String {
        val f = shQuote(logFile)
        val tmp = shQuote("$logFile.tmp")
        return buildString {
            append("if [ -f $f ]; then\n")
            append("    LOG_SIZE=\"\$(wc -c < $f 2>/dev/null || echo 0)\"\n")
            append("    if [ \"\$LOG_SIZE\" -gt $LOG_TRIM_BYTES ] 2>/dev/null; then\n")
            append("        tail -n $LOG_TRIM_KEEP_LINES $f > $tmp && mv $tmp $f\n")
            append("    fi\n")
            append("fi\n")
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
    fun dumpRoutingState(context: Context): String {
        val logFile = rootDaemonLogFile(context)
        val orig = shQuote(RP_FILTER_ORIG)
        val uids = excludedUids(context)
        // A route lookup needs an address, not a prefix; the network address of
        // the first exclusion is enough to show which way the LAN goes.
        val lanProbe = manualExclusions(context, v6 = false).firstOrNull()?.substringBefore('/')
        // Its own root shell, so it runs before the dump script is assembled and
        // its verdict is printed as part of the dump rather than out of band.
        val coexist = detectForeignVpn()
        val taildropDir = TaildropPaths.dir(context, AccountManager.getActiveAccount(context).id).absolutePath
        val script = buildString {
            append("echo '--- ip rule ---'\n")
            append("ip rule show 2>/dev/null || echo '(ip rule show failed)'\n")
            append("echo '--- ip -6 rule ---'\n")
            append("ip -6 rule show 2>/dev/null || echo '(ip -6 rule show failed)'\n")
            append("echo '--- local prefixes returned to the platform (pref $LAN_RETURN_PRIO; empty when nothing overlaps) ---'\n")
            append("{ ip rule show 2>/dev/null | grep '^$LAN_RETURN_PRIO:'; ip -6 rule show 2>/dev/null | grep '^$LAN_RETURN_PRIO:'; } | grep . || echo '(none)'\n")
            append("echo '--- other tunnels ---'\n")
            append("ip -o link show 2>/dev/null | grep -Eo '(tun[0-9]+|ppp[0-9]+|wg[0-9]+)' | grep -v tailscale0 | sort -u || echo '(none)'\n")
            append("echo '--- table $ROUTE_TABLE (app: the tailnet prefix, and a throw per foreign CGNAT prefix) ---'\n")
            append("ip route show table $ROUTE_TABLE 2>/dev/null | grep . || echo '(empty)'\n")
            append("echo '--- table $LEGACY_ROUTE_TABLE (our table before 4.0; ours only if it names tailscale0, otherwise netd's) ---'\n")
            append("ip route show table $LEGACY_ROUTE_TABLE 2>/dev/null | grep . || echo '(empty)'\n")
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
            append("iptables -w -t mangle -S 2>/dev/null | grep -- '--set-xmark' || echo '(none)'\n")
            append("echo '--- rp_filter ---'\n")
            append("for f in /proc/sys/net/ipv4/conf/*/rp_filter; do printf '%s=%s\\n' \"\${f#/proc/sys/net/ipv4/conf/}\" \"\$(cat \"\$f\" 2>/dev/null)\"; done 2>/dev/null\n")
            append("[ -f $orig ] && echo \"saved original all=\$(cat $orig)\"\n")
            append("echo '--- daemon log: last run marker, last SO_MARK probe line (any run), gate verdict ---'\n")
            val lf = shQuote(logFile.absolutePath)
            append("grep -n '$RUN_MARKER' $lf 2>/dev/null | tail -n 1 | grep . || echo '(no \"$RUN_MARKER\" line: daemon not started by TailSocks)'\n")
            append("grep '$SO_MARK_LINE' $lf 2>/dev/null | grep -v '$APP_LINE_TAG' | tail -n 1 | grep . || echo '(no SO_MARK line in daemon log)'\n")
            val verdict = soMarkVerdict(logFile)
            append("echo ${shQuote("gate: " + (if (verdict.marks) "daemon marks sockets" else "pref-$CATCH_ALL_PRIO withheld") + " (" + verdict.reason + ")")}\n")
            append("echo '--- coexistence (which tiers the last apply installed, and why) ---'\n")
            append(
                "echo " + shQuote(
                    (if (coexist.present) "another tunnel owns this device" else "this device is ours") +
                        " (" + coexist.reason + ")"
                ) + "\n"
            )
            append("echo '--- mangle $CHAIN_MARK ---'\n")
            append("iptables -w -t mangle -S $CHAIN_MARK 2>/dev/null || echo '(absent)'\n")
            append("echo '--- mangle $CHAIN_BYPASS (removed in 4.0; anything here is upgrade residue) ---'\n")
            append("iptables -w -t mangle -S $CHAIN_BYPASS 2>/dev/null || echo '(absent)'\n")
            append("echo '--- nat OUTPUT jumps into $CHAIN_DNS (without these the redirect is built but inert) ---'\n")
            append("iptables -w -t nat -S OUTPUT 2>/dev/null | grep -- '-j $CHAIN_DNS' || echo '(unhooked)'\n")
            append("echo '--- nat $CHAIN_DNS ---'\n")
            append("iptables -w -t nat -S $CHAIN_DNS 2>/dev/null || echo '(absent)'\n")
            append("echo '--- nat $CHAIN_DNS counters ---'\n")
            append("iptables -w -t nat -L $CHAIN_DNS -n -v -x 2>/dev/null || echo '(absent)'\n")
            append("echo '--- tailscale0 ---'\n")
            append("ip -br addr show tailscale0 2>/dev/null || echo '(interface missing)'\n")
            // The real owner and mode bits of what the root daemon wrote, to read in
            // Check Routing rather than guess at. Expected while the daemon runs:
            // root-owned, 0644 (0666 under the start scripts' umask 022); after it
            // stops, owned by the app uid (handStateBackToApp).
            append("echo '--- taildrop dir (received files; expect 0644 while the root daemon runs, app-owned after it stops) ---'\n")
            append("ls -la ${shQuote(taildropDir)} 2>&1 | head -n 40\n")
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

    /** Printed by the stop shell once each part has run, so one shell can report three results. */
    private const val STOP_CLEANUP_DONE = "TAILSOCKS_STOP: routing cleanup done"
    private const val STOP_HANDBACK_DONE = "TAILSOCKS_STOP: state handed back"

    /**
     * Removes the routing, terminates the root daemon and, given [handBackTo],
     * hands its state back to the app — in one root shell.
     *
     * The stop used to spend four: the caller's cleanup, the same cleanup again
     * as this function's first line, the kill, and the chown — about ninety
     * delete loops run twice, and a fixed one-second sleep between SIGTERM and
     * SIGKILL. The commands and their order are what they were; the second
     * cleanup pass is gone, and the sleep is a poll that ends as soon as the
     * daemon has exited, still SIGKILL after one second at the latest.
     *
     * Always true, as before: no caller can do anything with a failed stop.
     */
    fun stopRootDaemon(socketPath: String = "", handBackTo: Context? = null): Boolean {
        val handBack = handBackTo?.let { stateHandBackScript(it) }
        val script = buildString {
            append(routingCleanupScript())
            append("echo '$STOP_CLEANUP_DONE'\n")
            append(daemonKillScript(socketPath))
            if (handBack != null) {
                append(handBack)
                append("echo '$STOP_HANDBACK_DONE'\n")
            }
        }
        val res = runSu("daemon-stop", script, timeoutMs = 30_000L, quiet = true)
        Log.d(TAG, "Root daemon stopped exitCode=${res.exitCode}")
        val other = res.output.lineSequence()
            .filter { it.isNotBlank() && it != STOP_CLEANUP_DONE && it != STOP_HANDBACK_DONE }
            .joinToString("\n")
        if (res.ok && other.isNotEmpty()) rootLog("INFO", "daemon-stop: $other")
        if (res.output.contains(STOP_CLEANUP_DONE)) noteRoutingRemoved()
        rootLog("INFO", "Root daemon stopped")
        if (handBack != null && !res.output.contains(STOP_HANDBACK_DONE)) {
            rootLog("WARN", "could not hand the daemon state back to the app; a non-root start may ask you to log in again")
        }
        return true
    }

    /**
     * SIGTERM, up to a second for the daemon to exit, then SIGKILL, for the
     * daemon matched by its `--socket=` argument and nothing else. That argument
     * contains this app's private data path. `pkill -f libtailscale.so` matched
     * the full command line of every process on the device, so a second profile,
     * a Termux tailscaled, or any unrelated app shipping that library was killed
     * as root too.
     */
    internal fun daemonKillScript(socketPath: String): String = buildString {
        if (socketPath.isNotEmpty()) {
            val pat = shQuote("--socket=$socketPath")
            append("pkill -15 -f -- $pat 2>/dev/null || true\n")
            append("i=0; while [ \$i -lt 10 ] && pgrep -f -- $pat >/dev/null 2>&1; do sleep 0.1; i=\$((i+1)); done\n")
            append("pkill -9 -f -- $pat 2>/dev/null || true\n")
            append("rm -f ${shQuote(socketPath)}\n")
        } else {
            // No socket path to scope by; fall back to the exact process name.
            append("killall -15 libtailscale.so 2>/dev/null || true\n")
            append("i=0; while [ \$i -lt 10 ] && pidof libtailscale.so >/dev/null 2>&1; do sleep 0.1; i=\$((i+1)); done\n")
            append("killall -9 libtailscale.so 2>/dev/null || true\n")
        }
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
        val script = (stateHandBackScript(context) ?: return true) + "exit 0\n"
        val res = runSu("state-chown", script, timeoutMs = 15_000L)
        if (!res.ok) {
            rootLog("WARN", "could not hand the daemon state back to the app; a non-root start may ask you to log in again")
        }
        return res.ok
    }

    /** [handStateBackToApp]'s commands, without the `exit`; null when there is nothing to hand back. */
    private fun stateHandBackScript(context: Context): String? {
        val uid = context.applicationInfo.uid
        val dir = File(context.filesDir, "states").absolutePath
        // Received Taildrop files: the root daemon writes them into files/taildrop/<account>
        // as root (feature/taildrop/send.go creates the partial, ext.go renames it). The
        // start scripts set umask 022 so the app can read them while the daemon runs;
        // this hands over ownership once root is about to go away, so a later non-root
        // session can rename or rewrite them too. Ownership only — modes are left alone.
        val taildrop = File(context.filesDir, "taildrop").absolutePath
        if (!File(dir).exists() && !File(taildrop).exists()) return null
        return stateHandBackScript(uid, dir, taildrop)
    }

    internal fun stateHandBackScript(uid: Int, dir: String, taildrop: String): String = buildString {
        append("if [ -d ${shQuote(dir)} ]; then\n")
        append("  chown -R $uid:$uid ${shQuote(dir)} 2>/dev/null || true\n")
        // Ownership is what matters; keep the modes the daemon expects.
        append("  find ${shQuote(dir)} -type d -exec chmod 700 {} + 2>/dev/null || true\n")
        append("  find ${shQuote(dir)} -type f -exec chmod 600 {} + 2>/dev/null || true\n")
        append("fi\n")
        append("[ -d ${shQuote(taildrop)} ] && chown -R $uid:$uid ${shQuote(taildrop)} 2>/dev/null\n")
    }

    fun setServiceScriptInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                // The daemon path is baked in, like the CLI wrapper's: the script
                // used to fall back to `find /data/app -name libtailscale.so`, which
                // would exec a same-named library from any other app as root.
                // BootReceiver re-installs the script on every app update, so the
                // path follows the install directory.
                // The version line goes in with it (RootBootScript), so Settings
                // can tell a copy an older build left behind.
                val scriptContent = RootBootScript.render(
                    bootScriptTemplate(context), context.packageName, bootScriptDaemonBin(context),
                    BuildConfig.VERSION_NAME
                )
                val tempFile = File(context.cacheDir, "tailscaled.sh").apply { writeText(scriptContent) }

                val cmd = """
                    mkdir -p "${RootPaths.SERVICE_D_DIR}"
                    cp "${tempFile.absolutePath}" "${paths.serviceScript}"
                    chmod 755 "${paths.serviceScript}"
                    rm -f "${tempFile.absolutePath}"
                """.trimIndent()

                runSu("service-script-install", cmd).ok
            } else {
                // This package's script only; the other build's is not ours.
                runSu("service-script-remove", "rm -f \"${paths.serviceScript}\"").ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage service.d script: ${e.message}", e)
            rootLog("ERROR", "Failed to manage autostart script: ${e.message}")
            false
        }
    }

    fun isServiceScriptInstalled(): Boolean {
        val res = runSu("service-script-check", "[ -f \"${paths.serviceScript}\" ] && echo 'exists'", timeoutMs = 10_000L)
        return res.ok && res.output.contains("exists")
    }

    private fun bootScriptTemplate(context: Context): String =
        context.assets.open("scripts/tailscaled.sh").bufferedReader().use { it.readText() }

    private fun bootScriptDaemonBin(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath

    /**
     * The installed boot script against the one this build would install: absent,
     * current, or outdated (another version, or no version line at all). Reads the
     * first lines of the file through `su` — `/data/adb` is root's alone.
     */
    fun serviceScriptState(context: Context): RootBootScript.State {
        val marker = "TAILSOCKS_SCRIPT_PRESENT"
        val res = runSu(
            "service-script-check",
            "[ -f \"${paths.serviceScript}\" ] && { echo '$marker'; head -n ${RootBootScript.HEADER_SEARCH_LINES} \"${paths.serviceScript}\"; }",
            timeoutMs = 10_000L,
            quiet = true
        )
        val head = if (res.ok && res.output.contains(marker)) res.output.substringAfter(marker) else null
        val expected = runCatching {
            RootBootScript.expectedVersion(bootScriptTemplate(context), context.packageName, bootScriptDaemonBin(context))
        }.getOrDefault("")
        return RootBootScript.state(head, expected)
    }

    /**
     * [serviceScriptState] and [isTailscaleCliInstalled] in one root shell, for
     * the boot receiver, which used to spend four (check and reinstall, twice)
     * inside its broadcast budget.
     */
    fun rootIntegrationState(context: Context): Pair<RootBootScript.State, Boolean> {
        val present = "TAILSOCKS_SCRIPT_PRESENT"
        val end = "TAILSOCKS_SCRIPT_END"
        val cli = "TAILSOCKS_CLI_PRESENT"
        val res = runSu(
            "root-integration-check",
            "[ -f \"${paths.serviceScript}\" ] && { echo '$present'; head -n ${RootBootScript.HEADER_SEARCH_LINES} \"${paths.serviceScript}\"; echo '$end'; }\n" +
                "{ ${paths.cliCheckScript()}; } | grep -q exists && echo '$cli'\n" +
                "true\n",
            timeoutMs = 10_000L,
            quiet = true
        )
        val head = if (res.ok && res.output.contains(present)) res.output.substringAfter(present).substringBefore(end) else null
        val expected = runCatching {
            RootBootScript.expectedVersion(bootScriptTemplate(context), context.packageName, bootScriptDaemonBin(context))
        }.getOrDefault("")
        return RootBootScript.state(head, expected) to (res.ok && res.output.contains(cli))
    }

    fun setTailscaleCliInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                // The daemon binary is the CLI too (ts_include_cli); the wrapper
                // sets TS_BE_CLI so it runs as one.
                val cliBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
                val scriptContent = context.assets.open("scripts/tailscale_cli.sh").bufferedReader().use { it.readText() }
                    .replace("%PKG_NAME%", context.packageName)
                    .replace("%CLI_BIN%", cliBin)

                val tempFile = File(context.cacheDir, "tailscale_cli_wrapper.sh").apply {
                    writeText(scriptContent)
                }
                runSu("cli-install", paths.cliInstallScript(tempFile.absolutePath)).ok
            } else {
                // This package's wrapper only, from wherever it went.
                runSu("cli-remove", paths.cliRemoveScript()).ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage CLI wrapper script: ${e.message}", e)
            rootLog("ERROR", "Failed to manage CLI wrapper: ${e.message}")
            false
        }
    }

    fun isTailscaleCliInstalled(): Boolean {
        val res = runSu("cli-check", paths.cliCheckScript(), timeoutMs = 10_000L)
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

    /**
     * One A query to 1.1.1.1 over UDP, for when the system resolver could not
     * answer. The reply is parsed ([DnsWire.firstA]): it must answer this query's
     * id with NOERROR, and the address is the first A record's. The packet used
     * to be scanned from its end for any four bytes that looked like an address,
     * which a trailing CNAME, SOA or OPT record turned into a wrong one — handed
     * to the root daemon as the control proxy's address. The socket is closed on
     * every path; a timeout, the common failure, used to leak it.
     */
    private fun resolveHostViaUdpDns(host: String): String? {
        return try {
            val id = java.security.SecureRandom().nextInt(0x10000)
            val query = DnsWire.aQuery(host, id) ?: return null
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val dnsServer = java.net.InetAddress.getByName("1.1.1.1")
                socket.send(java.net.DatagramPacket(query, query.size, dnsServer, 53))
                val buf = ByteArray(512)
                val reply = java.net.DatagramPacket(buf, buf.size)
                socket.receive(reply)
                DnsWire.firstA(reply.data, reply.length, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct UDP DNS resolution failed for '$host': ${e.message}")
            null
        }
    }
}
