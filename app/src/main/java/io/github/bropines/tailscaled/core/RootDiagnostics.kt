package io.github.bropines.tailscaled.core

import android.content.Context
import io.github.bropines.tailscaled.core.RootDaemon.rootDaemonLogFile
import io.github.bropines.tailscaled.core.RootExclusions.excludedUids
import io.github.bropines.tailscaled.core.RootExclusions.manualExclusions
import io.github.bropines.tailscaled.core.RootForeignVpn.detectForeignVpn
import io.github.bropines.tailscaled.core.RootRouting.BYPASS_MARK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_PRIO
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_BYPASS
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_DNS
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_MARK
import io.github.bropines.tailscaled.core.RootRouting.DAEMON_TABLE
import io.github.bropines.tailscaled.core.RootRouting.LAN_RETURN_PRIO
import io.github.bropines.tailscaled.core.RootRouting.LEGACY_ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.RP_FILTER_ORIG
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.APP_LINE_TAG
import io.github.bropines.tailscaled.core.RootUtils.RUN_MARKER
import io.github.bropines.tailscaled.core.RootUtils.SO_MARK_LINE
import io.github.bropines.tailscaled.core.RootUtils.shQuote
import io.github.bropines.tailscaled.core.SoMarkGate.soMarkVerdict

/**
 * Check Routing: [dumpRoutingState] collects every rule, route, chain and
 * verdict Root Mode depends on into one plain-text report.
 */
internal object RootDiagnostics {
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
}
