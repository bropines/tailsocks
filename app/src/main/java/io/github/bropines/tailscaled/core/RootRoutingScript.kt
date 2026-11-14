package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_MASK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_PRIO
import io.github.bropines.tailscaled.core.RootRouting.CGNAT_V4
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_BYPASS
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_DNS
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_MARK
import io.github.bropines.tailscaled.core.RootRouting.DAEMON_TABLE
import io.github.bropines.tailscaled.core.RootRouting.EXCLUDE_PRIO
import io.github.bropines.tailscaled.core.RootRouting.GOTO_UNRESOLVED
import io.github.bropines.tailscaled.core.RootRouting.LAN_RETURN_PRIO
import io.github.bropines.tailscaled.core.RootRouting.LEGACY_MARK
import io.github.bropines.tailscaled.core.RootRouting.LEGACY_ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.MAGIC_DNS
import io.github.bropines.tailscaled.core.RootRouting.MARK_BIT
import io.github.bropines.tailscaled.core.RootRouting.MARK_MASK
import io.github.bropines.tailscaled.core.RootRouting.NETD_RULE_MIN
import io.github.bropines.tailscaled.core.RootRouting.PROTECT_MARK
import io.github.bropines.tailscaled.core.RootRouting.ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.RP_FILTER_ORIG
import io.github.bropines.tailscaled.core.RootRouting.TAILNET_V6
import io.github.bropines.tailscaled.core.RootRouting.UIDRANGE_MISSING_V4
import io.github.bropines.tailscaled.core.RootRouting.UIDRANGE_MISSING_V6
import io.github.bropines.tailscaled.core.RootUtils.DAEMON_NO_ADDRESS
import io.github.bropines.tailscaled.core.RootUtils.DAEMON_NO_EXIT_ROUTE
import io.github.bropines.tailscaled.core.RootUtils.hostsMarker
import io.github.bropines.tailscaled.core.RootUtils.hostsUnbindScript
import io.github.bropines.tailscaled.core.RootUtils.paths
import io.github.bropines.tailscaled.core.RootUtils.shQuote

/**
 * The shell fragments the Root Mode ruleset is built from — every one a
 * pure `String` builder with no I/O: the tailnet tier (pref-100 rules, the
 * mangle mark chain), the catch-all, the per-app exclusion rules, the LAN
 * throw routes and return rules, the CGNAT guard, the DNS chain hooks, the
 * rp_filter loosening, the table teardowns and legacy-rule cleanup, the
 * verify tail of the apply script and the whole cleanup script.
 * [RootUtils.applyTailscale0Routing] decides which of them go into one apply;
 * `RootScriptSnapshotTest` pins their output.
 */
internal object RootRoutingScript {
    /**
     * The end of the apply script: verify rather than trust. The shell's exit
     * code otherwise only reflects the last command, and the script has no
     * `set -e` on purpose — so each promise the ruleset makes is read back, and
     * a broken one ends the script with status 1, which sends the apply through
     * the caller's retry (three attempts per set of inputs) and the ERROR log
     * instead of being recorded as applied:
     *  - table 53 holds a route (a missing tailscale0 fails here);
     *  - both priority-100 rules into table 53, the mark rule and the
     *    destination rule (`ip rule add` can fail on its own);
     *  - the mangle OUTPUT jump into [CHAIN_MARK];
     *  - with device-wide DNS, [CHAIN_DNS] and both of its DNAT lines, which
     *    nothing else checked: a chain left holding only its RETURNs passed.
     *
     * Two daemon-side checks print a line and fail nothing, and run first, so
     * a failed verify cannot hide them: whether tailscale0 carries a tailnet
     * address, and — with the catch-all installed and an exit node selected
     * ([checkExitRoute]) — whether the daemon's table 52 has its default route.
     * Both are the daemon's router's work, which no re-apply of ours repairs.
     */
    internal fun applyVerifyScript(installDns: Boolean, checkExitRoute: Boolean): String = buildString {
        append(
            "ip addr show dev tailscale0 2>/dev/null | grep -qE 'inet6? (100\\.|fd7a:115c:a1e0:)' || " +
                "echo '$DAEMON_NO_ADDRESS'\n"
        )
        if (checkExitRoute) {
            append("ip route show table $DAEMON_TABLE 2>/dev/null | grep -q '^default' || echo '$DAEMON_NO_EXIT_ROUTE'\n")
        }
        append("ip route show table $ROUTE_TABLE 2>/dev/null | grep -q . || { echo 'verify: table $ROUTE_TABLE is empty'; exit 1; }\n")
        append("TS_RULES=\$(ip rule show 2>/dev/null)\n")
        append(
            "printf '%s\\n' \"\$TS_RULES\" | grep -q '^100:.*fwmark $MARK_BIT/$MARK_MASK lookup $ROUTE_TABLE' || " +
                "{ echo 'verify: priority-100 fwmark rule into table $ROUTE_TABLE missing'; exit 1; }\n"
        )
        append(
            "printf '%s\\n' \"\$TS_RULES\" | grep -q '^100:.*to 100\\.64\\.0\\.0/10 .*lookup $ROUTE_TABLE' || " +
                "{ echo 'verify: priority-100 tailnet destination rule into table $ROUTE_TABLE missing'; exit 1; }\n"
        )
        append(
            "iptables -w -t mangle -C OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || " +
                "{ echo 'verify: mangle OUTPUT jump into $CHAIN_MARK missing'; exit 1; }\n"
        )
        if (installDns) {
            append("iptables -w -t nat -S $CHAIN_DNS >/dev/null 2>&1 || { echo 'verify: $CHAIN_DNS chain missing'; exit 1; }\n")
            append(
                "[ \"\$(iptables -w -t nat -S $CHAIN_DNS 2>/dev/null | grep -c -- '-j DNAT')\" -ge 2 ] || " +
                    "{ echo 'verify: $CHAIN_DNS lacks its DNAT lines'; exit 1; }\n"
            )
        }
        append("echo 'verify: ok'\n")
    }

    /**
     * The tailnet tier's (T1) IPv4 route into table 53. Written by the boot
     * script too, line for line ([RootBootScriptDriftTest] checks the two).
     */
    internal fun tailnetRouteV4(): String = "ip route replace $CGNAT_V4 dev tailscale0 table $ROUTE_TABLE metric 1\n"

    /** T1's IPv4 rules and chains: the two priority-100 rules, TAILSOCKS_MARK, the FORWARD pair. The boot script writes them too. */
    internal fun tailnetRulesV4(): String = buildString {
        append("ip rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null || true\n")
        append("ip rule add fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE priority 100\n")
        // A destination rule as well as the fwmark one: the mark is applied in
        // mangle OUTPUT, AFTER the kernel has already picked the source address
        // from the default (Wi-Fi/cellular) table. A root-owned socket — the
        // daemon's own DNS forwarder talking to a split-DNS resolver on a peer —
        // therefore left tailscale0 with the Wi-Fi address as source and never
        // got an answer. Matching on destination makes the first lookup land in
        // our own table, so the source is the tailnet address.
        //
        // `iif lo` scopes it to locally generated packets: without it we also
        // claimed packets being forwarded through this device (a subnet router,
        // a tethered client) and answered them with our own source address.
        // No fwmark selector here on purpose — that very DNS forwarder socket is
        // unmarked, and a marked-only rule would send it back to the physical
        // table, which is the bug this rule exists to fix.
        append("while ip rule del to $CGNAT_V4 iif lo table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while ip rule del to $CGNAT_V4 table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("ip rule add to $CGNAT_V4 iif lo table $ROUTE_TABLE priority 100\n")

        append("iptables -w -t mangle -N $CHAIN_MARK 2>/dev/null || iptables -w -t mangle -F $CHAIN_MARK\n")
        append("iptables -w -t mangle -A $CHAIN_MARK -j MARK --set-xmark $MARK_BIT/$MARK_MASK\n")
        append("iptables -w -t mangle -C OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || iptables -w -t mangle -A OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK\n")

        append("iptables -w -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || iptables -w -I FORWARD -o tailscale0 -j ACCEPT\n")
        append("iptables -w -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || iptables -w -I FORWARD -i tailscale0 -j ACCEPT\n")
    }

    /** T1's IPv6 half: route, rules, chain and FORWARD pair, all best-effort. The boot script writes them too. */
    internal fun tailnetTierV6(): String = buildString {
        append("ip -6 route replace $TAILNET_V6 dev tailscale0 table $ROUTE_TABLE metric 1 2>/dev/null || true\n")
        append("ip -6 rule del fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE 2>/dev/null || true\n")
        append("ip -6 rule add fwmark $MARK_BIT/$MARK_MASK table $ROUTE_TABLE priority 100 2>/dev/null || true\n")
        // `iif lo` for the same reason as the v4 twin above, and no fwmark
        // selector for the same reason either.
        append("while ip -6 rule del to $TAILNET_V6 iif lo table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while ip -6 rule del to $TAILNET_V6 table $ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("ip -6 rule add to $TAILNET_V6 iif lo table $ROUTE_TABLE priority 100 2>/dev/null || true\n")

        append("ip6tables -w -t mangle -N $CHAIN_MARK 2>/dev/null || ip6tables -w -t mangle -F $CHAIN_MARK\n")
        append("ip6tables -w -t mangle -A $CHAIN_MARK -j MARK --set-xmark $MARK_BIT/$MARK_MASK 2>/dev/null || true\n")
        append("ip6tables -w -t mangle -C OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || ip6tables -w -t mangle -A OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || true\n")

        append("ip6tables -w -C FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || ip6tables -w -I FORWARD -o tailscale0 -j ACCEPT 2>/dev/null || true\n")
        append("ip6tables -w -C FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || ip6tables -w -I FORWARD -i tailscale0 -j ACCEPT 2>/dev/null || true\n")
    }

    /**
     * The routing cleanup as a script: run on its own by [cleanupTailscale0Routing],
     * and in the same shell as the kill by [stopRootDaemon]. No `exit` in it, so
     * it can be followed by more commands.
     */
    internal fun routingCleanupScript(): String {
        val sb = StringBuilder()
        sb.append(dnsChainTeardown())
        sb.append(bypassChainTeardown("iptables -w"))
        sb.append(bypassChainTeardown("ip6tables -w"))
        sb.append(excludeRulesTeardown())

        sb.append("iptables -w -t mangle -D OUTPUT -d $CGNAT_V4 -j $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("iptables -w -t mangle -F $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("iptables -w -t mangle -X $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("ip6tables -w -t mangle -D OUTPUT -d $TAILNET_V6 -j $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("ip6tables -w -t mangle -F $CHAIN_MARK 2>/dev/null || true\n")
        sb.append("ip6tables -w -t mangle -X $CHAIN_MARK 2>/dev/null || true\n")

        sb.append("while iptables -w -D FORWARD -o tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")
        sb.append("while iptables -w -D FORWARD -i tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")
        sb.append("while ip6tables -w -D FORWARD -o tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")
        sb.append("while ip6tables -w -D FORWARD -i tailscale0 -j ACCEPT 2>/dev/null; do :; done\n")

        // Ours, and the one 4.0 moved away from: an upgrade that stops Root
        // Mode must collect both, and neither is ever flushed — see [ROUTE_TABLE].
        sb.append(routeTableTeardown(ROUTE_TABLE, ourThrows = true))
        sb.append(routeTableTeardown(LEGACY_ROUTE_TABLE, ourThrows = false))

        // Exit-node catch-all, stale desktop rules and the rp_filter change. Table
        // 52 itself is left alone on purpose: it belongs to the daemon (which may
        // keep running with "Terminate Root Daemon on Stop" off); router.Close()
        // deletes its throw routes and the kernel drops the rest with tailscale0.
        sb.append(catchAllTeardown("ip"))
        sb.append(catchAllTeardown("ip -6"))
        sb.append(localReturnRulesRemove("ip"))
        sb.append(localReturnRulesRemove("ip -6"))
        // Throws an older build wrote into the daemon's table for the same
        // purpose. Everything except the daemon's own loopback throw goes; a
        // range the daemon threw for its own reasons is re-added at its next
        // Reconfig, and with the pref-$CATCH_ALL_PRIO rule gone nothing
        // consults this table anyway.
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

        sb.append("ip route del $CGNAT_V4 dev tailscale0 2>/dev/null || true\n")
        sb.append("ip -6 route del $TAILNET_V6 dev tailscale0 2>/dev/null || true\n")

        sb.append(legacyRuleCleanup())
        // Our hosts bind, and nobody else's (see [hostsUnbindScript]).
        sb.append(hostsUnbindScript(paths.hostsFile, hostsMarker(paths.hostsFile)))
        return sb.toString()
    }

    /** Detaches and drops our nat chain; safe to run when it does not exist. */
    internal fun dnsChainTeardown(): String = buildString {
        append(dnsHookRemove())
        append("iptables -w -t nat -F $CHAIN_DNS 2>/dev/null || true\n")
        append("iptables -w -t nat -X $CHAIN_DNS 2>/dev/null || true\n")
    }

    /**
     * The two `nat OUTPUT` jumps into [CHAIN_DNS] — the only thing that makes
     * the redirect live; the chain itself is inert until they exist.
     *
     * They are emitted apart from the chain because the health gate needs to
     * take port 53 back from a resolver that is not answering without throwing
     * the chain away: the exemptions in it (the other tunnel's netId, the
     * excluded uids, the upstream resolvers) are rebuilt from state the caller
     * had at apply time, and re-deriving them just to re-arm would mean a full
     * apply for what is two lines of iptables.
     *
     * Position 1 on purpose: netd's own DNS rules sit in OUTPUT too, and a jump
     * appended after them never sees the packet.
     */
    internal fun dnsHookInstall(): String = buildString {
        for (proto in listOf("udp", "tcp")) {
            append(
                "iptables -w -t nat -C OUTPUT -p $proto --dport 53 -j $CHAIN_DNS 2>/dev/null || " +
                    "iptables -w -t nat -I OUTPUT 1 -p $proto --dport 53 -j $CHAIN_DNS\n"
            )
        }
    }

    /** Drains the jumps, duplicates included; a no-op when none are there. */
    internal fun dnsHookRemove(): String = buildString {
        for (proto in listOf("udp", "tcp")) {
            append("while iptables -w -t nat -D OUTPUT -p $proto --dport 53 -j $CHAIN_DNS 2>/dev/null; do :; done\n")
        }
    }

    /**
     * Deletes the un-chained rules installed by TailSocks 3.5.x and earlier.
     * Those were appended directly to OUTPUT/FORWARD and piled up on every
     * restart, so they are drained here until none are left.
     */
    internal fun legacyRuleCleanup(): String = buildString {
        append("while iptables -w -t mangle -D OUTPUT -d $CGNAT_V4 -j MARK --set-mark $LEGACY_MARK 2>/dev/null; do :; done\n")
        append("while ip6tables -w -t mangle -D OUTPUT -d $TAILNET_V6 -j MARK --set-mark $LEGACY_MARK 2>/dev/null; do :; done\n")
        append("while ip rule del fwmark $LEGACY_MARK table $LEGACY_ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while ip rule del fwmark $LEGACY_MARK lookup $LEGACY_ROUTE_TABLE 2>/dev/null; do :; done\n")
        append("while iptables -w -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53 2>/dev/null; do :; done\n")
        append("while iptables -w -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination $MAGIC_DNS:53 2>/dev/null; do :; done\n")
        append("while iptables -w -t nat -D OUTPUT -d $CGNAT_V4 -p udp --dport 53 -j ACCEPT 2>/dev/null; do :; done\n")
        append("while iptables -w -t nat -D OUTPUT -d $CGNAT_V4 -p tcp --dport 53 -j ACCEPT 2>/dev/null; do :; done\n")
    }

    /**
     * Deletes the desktop-Linux rules a pre-4.0 core left behind (a killed
     * daemon never ran Close(); a stale `5270: from all lookup 52` would swallow
     * the new daemon's marked packets). Matched by content, not by priority
     * alone, so a third-party rule that happens to sit at 52xx survives; a no-op
     * when nothing is there. [ip] is `ip` or `ip -6`.
     */
    internal fun staleDaemonRulePurge(ip: String): String = buildString {
        append("while $ip rule del pref 5210 lookup main 2>/dev/null; do :; done\n")
        append("while $ip rule del pref 5230 lookup default 2>/dev/null; do :; done\n")
        append("while $ip rule del pref 5250 type unreachable 2>/dev/null; do :; done\n")
        append("while $ip rule del pref 5270 lookup $DAEMON_TABLE 2>/dev/null; do :; done\n")
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
    internal fun excludeRulesInstall(uids: List<Int>): String {
        if (uids.isEmpty()) return ""
        return buildString {
            append("PHYS=$(ip route get 1.1.1.1 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            append("PHYS6=$(ip -6 route get 2001:4860:4860::8888 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            // Whether the v6 half is expected to work at all, so a device with
            // IPv6 switched off cannot raise the "no uidrange support" alarm.
            append("V6UP=0\n")
            append("if [ -d /proc/sys/net/ipv6 ] && [ \"\$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null)\" = \"0\" ]; then V6UP=1; fi\n")
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
            // Per family, in passes over all uids rather than uid by uid: every
            // rule is added first, then the rule list is read back once and each
            // uid is checked against that snapshot with `case`, a shell builtin.
            // Reading the list per uid and per check cost three `ip rule show`
            // and five `grep` per uid and family, ~20 processes per excluded app
            // inside every apply. The checks are the same and still run after
            // the add they verify; only the reads are shared. The snapshots keep
            // pref-[EXCLUDE_PRIO] lines only, which nothing but these rules
            // uses, so a match anywhere in one is a match on one of those lines.
            for ((v, ip) in listOf("" to "ip", "6" to "ip -6")) {
                val goto = "\$GOTO$v"
                val phys = "\$PHYS$v"
                fun fallback(uid: Int) =
                    "{ [ -n \"$phys\" ] && $ip rule add uidrange $uid-$uid iif lo lookup \"$phys\" priority $EXCLUDE_PRIO 2>/dev/null; } || true"
                for (uid in uids) {
                    append("while $ip rule del uidrange $uid-$uid priority $EXCLUDE_PRIO 2>/dev/null; do :; done\n")
                    append(
                        "{ [ -n \"$goto\" ] && $ip rule add uidrange $uid-$uid goto \"$goto\" priority $EXCLUDE_PRIO 2>/dev/null; } || true\n"
                    )
                }
                append("EXCL$v=\$($ip rule show 2>/dev/null | grep '^$EXCLUDE_PRIO:')\n")
                // A goto the kernel could not resolve: the rule exists but is
                // never evaluated, which is worse than no rule at all.
                append("EXCL_DET$v=\$(echo \"\$EXCL$v\" | grep -E '\\[detached\\]| unresolved')\n")
                for (uid in uids) {
                    // Fallback, also taken when no rule exists in netd's band at
                    // all: pin the uid to the physical table by hand. A detached
                    // rule is deleted first, which leaves the uid with none.
                    append("case \"\$EXCL_DET$v\" in *'uidrange $uid-$uid'*)\n")
                    append("    while $ip rule del uidrange $uid-$uid priority $EXCLUDE_PRIO 2>/dev/null; do :; done\n")
                    append("    echo '$GOTO_UNRESOLVED'\n")
                    append("    ${fallback(uid)};;\n")
                    append("*)\n")
                    append("    case \"\$EXCL$v\" in *'uidrange $uid-$uid'*) ;; *) ${fallback(uid)};; esac;;\n")
                    append("esac\n")
                }
                // Neither form landed. On a device whose `ip` was built
                // without uid-range support that is what always happens, and
                // both attempts are silent about it: `ip rule add` fails, the
                // `|| true` swallows it, and the summary used to announce an
                // exclusion that does not exist. A second read, after the
                // fallbacks.
                val marker = if (v.isEmpty()) UIDRANGE_MISSING_V4 else UIDRANGE_MISSING_V6
                val check = buildString {
                    append("EXCL$v=\$($ip rule show 2>/dev/null | grep '^$EXCLUDE_PRIO:')\n")
                    for (uid in uids) {
                        append("case \"\$EXCL$v\" in *'uidrange $uid-$uid'*) ;; *) echo '$marker';; esac\n")
                    }
                }
                if (v.isEmpty()) {
                    append(check)
                } else {
                    // Only where IPv6 is up, so a device with it off cannot
                    // raise the alarm.
                    append("if [ \"\$V6UP\" = 1 ]; then\n")
                    append(check)
                    append("fi\n")
                }
            }
        }
    }

    /** Removes the pref-[EXCLUDE_PRIO] rules whatever uids they carry. */
    internal fun excludeRulesTeardown(): String = buildString {
        append("ip rule show 2>/dev/null | sed -n 's/^$EXCLUDE_PRIO:[^u]*uidrange \\([0-9]*\\)-\\([0-9]*\\).*/\\1 \\2/p' | while read a b; do ip rule del uidrange \"\$a\"-\"\$b\" priority $EXCLUDE_PRIO 2>/dev/null || true; done\n")
        append("ip -6 rule show 2>/dev/null | sed -n 's/^$EXCLUDE_PRIO:[^u]*uidrange \\([0-9]*\\)-\\([0-9]*\\).*/\\1 \\2/p' | while read a b; do ip -6 rule del uidrange \"\$a\"-\"\$b\" priority $EXCLUDE_PRIO 2>/dev/null || true; done\n")
    }

    /**
     * Detaches and drops the legacy per-app chain; safe to run when it does not
     * exist. Nothing installs [CHAIN_BYPASS] any more, but an upgrade from a
     * build that did must not leave its OUTPUT hook behind, so this keeps
     * running on every apply and every cleanup.
     */
    internal fun bypassChainTeardown(ipt: String): String = buildString {
        append("$ipt -t mangle -D OUTPUT -j $CHAIN_BYPASS 2>/dev/null || true\n")
        append("$ipt -t mangle -F $CHAIN_BYPASS 2>/dev/null || true\n")
        append("$ipt -t mangle -X $CHAIN_BYPASS 2>/dev/null || true\n")
    }

    /**
     * The user's own exclusions, as `throw` routes in the daemon's table.
     *
     * Table 52 is searched with longest-prefix match, so a broad exclusion
     * (`192.168.0.0/16`) still lets a more specific accepted route
     * (`192.168.1.0/24`) win — which is what a user asking for the tailnet's
     * `/24` expects. A pref-150 rule cannot express that: it matches the
     * destination before table 52 is consulted at all.
     */
    internal fun localBypassRoutes(ip: String, cidrs: List<String>): String = buildString {
        for (c in cidrs) {
            append("$ip route replace throw ${shQuote(c)} table $DAEMON_TABLE 2>/dev/null || true\n")
        }
    }

    /** Removes exactly the throws [localBypassRoutes] writes, by prefix. */
    internal fun localBypassRoutesRemove(ip: String, cidrs: List<String>): String = buildString {
        for (c in cidrs) {
            append("$ip route del throw ${shQuote(c)} table $DAEMON_TABLE 2>/dev/null || true\n")
        }
    }

    /**
     * Hands [prefixes] back to Android's own routing instead of the daemon's
     * table.
     *
     * A `throw` route in table 52 cannot do this: the daemon installs its subnet
     * routes with netlink RouteReplace, so the next netmap update overwrites a
     * same-prefix throw. A rule is not touched by the router at all, and `goto`
     * lands on the platform's own rules exactly as if the pref-[CATCH_ALL_PRIO]
     * catch-all did not match the destination.
     *
     * The priority belongs to this purpose alone: the daemon installs no rules
     * on Android, netd's start at [NETD_RULE_MIN], and this app's other rules
     * use 100, [EXCLUDE_PRIO] and [CATCH_ALL_PRIO].
     */
    internal fun localReturnRulesInstall(ip: String, prefixes: List<String>): String = buildString {
        if (prefixes.isEmpty()) return@buildString
        val v6 = ip == "ip -6"
        val probe = if (v6) "2001:4860:4860::8888" else "1.1.1.1"
        append("GOTO_LAN=$($ip rule show 2>/dev/null | while read -r p rest; do\n")
        append("    p=\${p%:}\n")
        append("    case \"\$p\" in ''|*[!0-9]*) continue;; esac\n")
        append("    [ \"\$p\" -ge $NETD_RULE_MIN ] && { echo \"\$p\"; break; }\n")
        append("done)\n")
        append("PHYS_LAN=$($ip route get $probe mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
        for (c in prefixes) {
            append(
                "{ [ -n \"\$GOTO_LAN\" ] && $ip rule add to ${shQuote(c)} iif lo goto \"\$GOTO_LAN\" priority $LAN_RETURN_PRIO 2>/dev/null; } || true\n"
            )
            append(
                "$ip rule show 2>/dev/null | grep -q '^$LAN_RETURN_PRIO:.*to $c' || " +
                    "{ [ -n \"\$PHYS_LAN\" ] && $ip rule add to ${shQuote(c)} iif lo lookup \"\$PHYS_LAN\" priority $LAN_RETURN_PRIO 2>/dev/null; } || true\n"
            )
        }
    }

    /** Removes every pref-[LAN_RETURN_PRIO] rule; nothing else on the device writes that priority. */
    internal fun localReturnRulesRemove(ip: String): String =
        "while $ip rule del priority $LAN_RETURN_PRIO 2>/dev/null; do :; done\n"

    /**
     * Writes the CGNAT guard into our own table and removes the throws that no
     * longer apply — an interface that went away, or a carrier prefix that
     * changed on the next attach.
     *
     * Added before the sweep, never after: between a delete and an add the /10
     * rule would be back in force, and the traffic this exists to protect would
     * spend that window inside the tunnel. Nothing but this writes a `throw`
     * into our table, so anything else there is ours from an earlier apply.
     */
    internal fun cgnatGuardRoutes(prefixes: List<String>): String = buildString {
        for (p in prefixes) {
            append("ip route replace throw ${shQuote(p)} table $ROUTE_TABLE 2>/dev/null || true\n")
        }
        append("ip route show table $ROUTE_TABLE 2>/dev/null | while read -r t c rest; do\n")
        append("    [ \"\$t\" = throw ] || continue\n")
        for (p in prefixes) {
            append("    [ \"\$c\" = ${shQuote(p)} ] && continue\n")
        }
        append("    ip route del throw \"\$c\" table $ROUTE_TABLE 2>/dev/null || true\n")
        append("done\n")
    }

    /**
     * Removes every rule and every route this app has ever pointed at [table].
     *
     * Deletion is by content throughout. `ip route flush table 1099` used to end
     * the cleanup, and on a device where some netdev reached interface index 99
     * that table is netd's, holding a live network's routes — see [ROUTE_TABLE].
     * Naming what we wrote can only ever remove what we wrote.
     *
     * @param ourThrows whether `throw` routes in the table are ours to sweep.
     *                  True only for [ROUTE_TABLE], which nothing else writes to;
     *                  the legacy table may belong to netd by now and never held
     *                  a throw of ours anyway.
     */
    internal fun routeTableTeardown(table: String, ourThrows: Boolean): String = buildString {
        append("while ip rule del fwmark $MARK_BIT/$MARK_MASK table $table 2>/dev/null; do :; done\n")
        append("while ip -6 rule del fwmark $MARK_BIT/$MARK_MASK table $table 2>/dev/null; do :; done\n")
        // Both spellings of the destination rule: the selector-less delete
        // matches the `iif lo` form too on every kernel we ship against, but an
        // orphaned pref-100 rule is not worth trusting that to.
        append("while ip rule del to $CGNAT_V4 iif lo table $table 2>/dev/null; do :; done\n")
        append("while ip -6 rule del to $TAILNET_V6 iif lo table $table 2>/dev/null; do :; done\n")
        append("while ip rule del to $CGNAT_V4 table $table 2>/dev/null; do :; done\n")
        append("while ip -6 rule del to $TAILNET_V6 table $table 2>/dev/null; do :; done\n")
        append("while ip rule del fwmark $LEGACY_MARK table $table 2>/dev/null; do :; done\n")
        append("while ip rule del fwmark $LEGACY_MARK lookup $table 2>/dev/null; do :; done\n")
        append("while ip -6 rule del fwmark $LEGACY_MARK table $table 2>/dev/null; do :; done\n")
        append("while ip -6 rule del fwmark $LEGACY_MARK lookup $table 2>/dev/null; do :; done\n")
        append("ip route del $CGNAT_V4 dev tailscale0 table $table 2>/dev/null || true\n")
        append("ip -6 route del $TAILNET_V6 dev tailscale0 table $table 2>/dev/null || true\n")
        if (ourThrows) {
            append("ip route show table $table 2>/dev/null | while read -r t c rest; do\n")
            append("    [ \"\$t\" = throw ] || continue\n")
            append("    ip route del throw \"\$c\" table $table 2>/dev/null || true\n")
            append("done\n")
        }
    }

    /**
     * Drains the catch-all by priority and table rather than by mark, so an
     * earlier mask variant — or the one the boot script installs before any app
     * process exists — goes as well.
     */
    internal fun catchAllTeardown(ip: String): String =
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
    internal fun catchAllInstall(
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
     * The record is the ruleset's, not a package's ([RootPaths.RP_FILTER_ORIG]).
     */
    internal fun rpFilterLoosen(): String = buildString {
        val dir = shQuote(RP_FILTER_ORIG.substringBeforeLast('/'))
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
    internal fun rpFilterRestore(): String = buildString {
        val orig = shQuote(RP_FILTER_ORIG)
        append("if [ -f $orig ]; then\n")
        append("    case \"\$(cat $orig 2>/dev/null)\" in 0|1|2) cat $orig > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true;; esac\n")
        append("    rm -f $orig\n")
        append("fi\n")
    }
}
