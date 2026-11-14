package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.core.RootDnsGate.lastMagicDnsReason
import io.github.bropines.tailscaled.core.RootDnsGate.noteDnsHookState
import io.github.bropines.tailscaled.core.RootExclusions.autoLanExclusions
import io.github.bropines.tailscaled.core.RootExclusions.cgnatInterfacePrefixes
import io.github.bropines.tailscaled.core.RootExclusions.excludedUids
import io.github.bropines.tailscaled.core.RootExclusions.manualExclusions
import io.github.bropines.tailscaled.core.RootForeignVpn.carryableGaps
import io.github.bropines.tailscaled.core.RootRouting.BYPASS_MARK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_MASK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_PRIO
import io.github.bropines.tailscaled.core.RootRouting.CGNAT_V4
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_DNS
import io.github.bropines.tailscaled.core.RootRouting.DAEMON_TABLE
import io.github.bropines.tailscaled.core.RootRouting.DNS_V6_NO_NAT
import io.github.bropines.tailscaled.core.RootRouting.GOTO_UNRESOLVED
import io.github.bropines.tailscaled.core.RootRouting.LEGACY_ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.MAGIC_DNS
import io.github.bropines.tailscaled.core.RootRouting.OWNER_MATCH_MISSING
import io.github.bropines.tailscaled.core.RootRouting.ROUTE_TABLE
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
import io.github.bropines.tailscaled.core.SoMarkGate.daemonMarksByEnviron
import io.github.bropines.tailscaled.core.SoMarkGate.soMarkVerdict
import java.io.File

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

    // Moved to RootDaemon.kt, RootSelinux.kt; kept here as the entry point callers use.
    fun isDaemonAlive(socketPath: String): Boolean = RootDaemon.isDaemonAlive(socketPath)
    fun probeDaemon(socketPath: String): RootAdoption.Probe = RootDaemon.probeDaemon(socketPath)
    fun isDaemonProcessAlive(socketPath: String): Boolean = RootDaemon.isDaemonProcessAlive(socketPath)
    fun wakeRootDaemon(socketPath: String): Boolean = RootDaemon.wakeRootDaemon(socketPath)
    fun awaitRootDaemon(socketPath: String, cancelled: () -> Boolean): RootAdoption.Step = RootDaemon.awaitRootDaemon(socketPath, cancelled)
    fun allowSocketConnect(socketPath: String): Boolean = RootSelinux.allowSocketConnect(socketPath)

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

    // Moved to RootDaemon.kt; kept here as the entry point callers use.
    fun writeRootEnvFile(context: Context, spec: RootDaemonSpec): Boolean = RootDaemon.writeRootEnvFile(context, spec)
    fun startRootDaemon(context: Context, spec: RootDaemonSpec): Boolean = RootDaemon.startRootDaemon(context, spec)
    internal const val LAUNCH_SKIPPED_RUNNING = RootDaemon.LAUNCH_SKIPPED_RUNNING
    internal const val LAUNCH_SKIPPED_LOCKED = RootDaemon.LAUNCH_SKIPPED_LOCKED
    internal fun launchLockDir(dataDir: String): String = RootDaemon.launchLockDir(dataDir)
    internal fun launchGuardScript(lockDir: String, socketPath: String): String = RootDaemon.launchGuardScript(lockDir, socketPath)

    /**
     * Quotes a value for safe use inside a single-quoted shell word. Line breaks
     * are dropped: no root command argument legitimately contains one, and a
     * newline would end the line inside the env file the boot script reads.
     */
    internal fun shQuote(value: String): String =
        "'" + value.replace("\r", "").replace("\n", "").replace("'", "'\\''") + "'"

    // Moved to RootHosts.kt; kept here as the entry point callers use.
    internal fun isIpLiteral(value: String): Boolean = RootHosts.isIpLiteral(value)
    fun updateRootHosts(hostsMap: Map<String, String>): Boolean = RootHosts.updateRootHosts(hostsMap)
    internal fun hostsMarker(hostsFile: String): String = RootHosts.hostsMarker(hostsFile)
    internal fun hostsUnbindScript(hostsFile: String, marker: String): String = RootHosts.hostsUnbindScript(hostsFile, marker)

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
        val dnsHooked: Boolean,
        /** The IPv4 pref-[CATCH_ALL_PRIO] rule went in and was read back: [missingRootRules] expects it. */
        val catchAllInstalled: Boolean = false
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
            dnsHooked = dnsHooked,
            catchAllInstalled = installCatchAll && res.ok && !res.output.contains(V4_CATCH_ALL_MISSING)
        )
    }

    /**
     * What of the last apply's ruleset is gone from the device ([RootRulesPresence]),
     * read in one quiet, read-only root shell; null when the shell gave no complete answer.
     */
    fun missingRootRules(expectCatchAll: Boolean): List<String>? {
        val res = runSu("rules-check", RootRulesPresence.script(), timeoutMs = 10_000L, quiet = true)
        if (!res.ok) return null
        return RootRulesPresence.missing(res.output, expectCatchAll)
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

    // Moved to RootForeignVpn.kt; kept here as the entry point callers use.
    internal fun splitAround(range: LongRange, holes: Collection<Long>): List<LongRange> = RootForeignVpn.splitAround(range, holes)
    internal fun foreignBypassGaps(ranges: List<LongRange>): List<LongRange> = RootForeignVpn.foreignBypassGaps(ranges)
    fun detectForeignVpn(): ForeignVpnVerdict = RootForeignVpn.detectForeignVpn()
    internal fun parseForeignVpnProbe(output: String): ForeignVpnVerdict = RootForeignVpn.parseForeignVpnProbe(output)

    /** Removes every rule this app installs, including those from older versions. */
    fun cleanupTailscale0Routing(): Boolean {
        val res = runSu("routing-cleanup", routingCleanupScript())
        if (res.ok) noteRoutingRemoved()
        return res.ok
    }

    /** What a finished routing cleanup records, whichever shell ran it. */
    internal fun noteRoutingRemoved() {
        noteDnsHookState(false, "the Root Mode ruleset was removed")
        rootLog("INFO", "tailscale0 routing removed")
    }

    // Moved to RootRoutingScript.kt, RootDnsGate.kt; kept here as the entry point callers use.
    internal fun routingCleanupScript(): String = RootRoutingScript.routingCleanupScript()
    fun setDnsRedirectHooked(hooked: Boolean): Boolean = RootDnsGate.setDnsRedirectHooked(hooked)
    fun magicDnsAnswers(context: Context): Boolean = RootDnsGate.magicDnsAnswers(context)

    /**
     * A name under [UPSTREAM_PROBE_ZONE] that no cache can hold an answer for:
     * a random 64-bit label, new on every call.
     */
    internal fun upstreamProbeName(random: java.util.Random = probeRandom): String =
        "tsprobe-%016x.%s".format(random.nextLong(), UPSTREAM_PROBE_ZONE)

    /** NOERROR and NXDOMAIN are answers from upstream; every other code is not. */
    internal fun upstreamProbeHealthy(rcode: Int): Boolean = rcode == 0 || rcode == 3

    private val probeRandom = java.security.SecureRandom()

    /**
     * A zone that resolves everywhere, is never a tailnet name and answers a
     * label it does not have with NXDOMAIN rather than a wildcard.
     */
    internal const val UPSTREAM_PROBE_ZONE = "gstatic.com"

    // Moved to RootRoutingScript.kt, RootExclusions.kt; kept here as the entry point callers use.
    internal fun legacyRuleCleanup(): String = RootRoutingScript.legacyRuleCleanup()
    internal fun filterCidrs(fromSettings: String, v6: Boolean): Pair<List<String>, List<String>> = RootExclusions.filterCidrs(fromSettings, v6)
    internal fun ipv4Value(bytes: ByteArray): Long = RootExclusions.ipv4Value(bytes)
    internal fun ipv4Network(bytes: ByteArray, len: Int): String = RootExclusions.ipv4Network(bytes, len)
    fun localLanSignature(): String = RootExclusions.localLanSignature()
    internal fun excludeRulesInstall(uids: List<Int>): String = RootRoutingScript.excludeRulesInstall(uids)
    fun cgnatGuardSignature(): String = RootExclusions.cgnatGuardSignature()

    /** Outcome of the daemon-log check: [marks] is the gate, [reason] is what the ROOT log and Check Routing show. */
    class SoMarkVerdict(val marks: Boolean, val reason: String)

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

    // Moved to RootDiagnostics.kt, RootDaemon.kt, RootIntegration.kt; kept here as the entry point callers use.
    fun dumpRoutingState(context: Context): String = RootDiagnostics.dumpRoutingState(context)
    fun rootDaemonLogFile(context: Context): File = RootDaemon.rootDaemonLogFile(context)
    fun clearRootDaemonLog(context: Context): Boolean = RootDaemon.clearRootDaemonLog(context)
    fun stopRootDaemon(socketPath: String = "", handBackTo: Context? = null): Boolean = RootDaemon.stopRootDaemon(socketPath, handBackTo)
    internal fun daemonKillScript(socketPath: String): String = RootDaemon.daemonKillScript(socketPath)
    fun handStateBackToApp(context: Context): Boolean = RootDaemon.handStateBackToApp(context)
    internal fun stateHandBackScript(uid: Int, dir: String, taildrop: String): String = RootDaemon.stateHandBackScript(uid, dir, taildrop)
    fun setServiceScriptInstalled(context: Context, install: Boolean): Boolean = RootIntegration.setServiceScriptInstalled(context, install)
    fun isServiceScriptInstalled(): Boolean = RootIntegration.isServiceScriptInstalled()
    fun serviceScriptState(context: Context): RootBootScript.State = RootIntegration.serviceScriptState(context)
    fun rootIntegrationState(context: Context): Pair<RootBootScript.State, Boolean> = RootIntegration.rootIntegrationState(context)
    fun setTailscaleCliInstalled(context: Context, install: Boolean): Boolean = RootIntegration.setTailscaleCliInstalled(context, install)
    fun isTailscaleCliInstalled(): Boolean = RootIntegration.isTailscaleCliInstalled()
}
