package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_MASK
import io.github.bropines.tailscaled.core.RootRouting.CATCH_ALL_PRIO
import io.github.bropines.tailscaled.core.RootRouting.DAEMON_TABLE
import io.github.bropines.tailscaled.core.RootRouting.MARK_BIT
import io.github.bropines.tailscaled.core.RootRouting.MARK_MASK
import io.github.bropines.tailscaled.core.RootRouting.ROUTE_TABLE

/**
 * Whether the Root Mode ruleset the last apply installed is still on the device.
 *
 * The apply is skipped while its inputs are unchanged (the signature in
 * TailscaledService.applyRootRoutingIfNeeded), and nothing about the inputs moves
 * when something outside the app deletes the rules — netd flushing its rule
 * table on a network change, another root tool, a user at a shell. Up to this
 * check the service went on saying "Traffic is routed" over a device with no
 * pref-100 rules, peers unreachable, until the connection was stopped and
 * started by hand.
 *
 * The check reads back what a netd flush takes: the two pref-100 rules into
 * table [ROUTE_TABLE], the route in that table, and the pref-[CATCH_ALL_PRIO]
 * catch-all when the apply installed it. The per-app (190) and LAN-return (150)
 * rules are not required: an apply may legitimately install none of them (no
 * exclusions, an unresolvable `goto`), and a flush that removed them removed the
 * pref-100 pair too. Table [DAEMON_TABLE]'s default route is the daemon's router's
 * work — an apply of ours does not bring it back — so it is not a reason to
 * re-apply either; the apply's own verify names it when it is missing.
 *
 * A root shell costs a `su` round trip, so the check runs on its own slow clock
 * ([checkDue]) and early when the DNS gate reports a miss — the gate's probe is
 * free, and a tailnet DNS server that stopped answering is exactly what missing
 * rules look like from the service.
 */
internal object RootRulesPresence {
    /** The steady cadence: one root shell a minute while Root Mode routes the device. */
    const val CHECK_INTERVAL_MS = 60_000L

    /** The early check on a DNS gate miss is still spaced by at least one refresh tick. */
    const val EARLY_CHECK_GAP_MS = 15_000L

    private const val RULES = "@@rules"
    private const val TABLE = "@@table$ROUTE_TABLE"
    private const val END = "@@end"

    /**
     * The read-only script: the app's pref-100 and pref-[CATCH_ALL_PRIO] rule lines and the
     * first route of table [ROUTE_TABLE], between markers so a shell that died half-way is
     * told apart from rules that are gone. Writes nothing.
     */
    fun script(): String = buildString {
        append("echo '$RULES'\n")
        append("ip rule show 2>/dev/null | grep -E '^(100|$CATCH_ALL_PRIO):'\n")
        append("echo '$TABLE'\n")
        append("ip route show table $ROUTE_TABLE 2>/dev/null | head -n 1\n")
        append("echo '$END'\n")
    }

    /**
     * What is missing, by name, from [output] of [script]; empty when the ruleset is whole.
     * Null when the output is not a complete answer (the shell failed, timed out, was
     * refused): that is not evidence of anything, and must not trigger an apply.
     */
    fun missing(output: String, expectCatchAll: Boolean): List<String>? {
        val lines = output.lines().map { it.trim() }
        val rulesAt = lines.indexOf(RULES)
        val tableAt = lines.indexOf(TABLE)
        val endAt = lines.indexOf(END)
        if (rulesAt < 0 || tableAt <= rulesAt || endAt <= tableAt) return null
        val rules = lines.subList(rulesAt + 1, tableAt)
        val table = lines.subList(tableAt + 1, endAt).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        if (rules.none { MARK_RULE.containsMatchIn(it) }) out += "the priority-100 fwmark rule"
        if (rules.none { TAILNET_RULE.containsMatchIn(it) }) out += "the priority-100 tailnet rule"
        if (table.isEmpty()) out += "the route in table $ROUTE_TABLE"
        if (expectCatchAll && rules.none { CATCH_ALL_RULE.containsMatchIn(it) }) {
            out += "the priority-$CATCH_ALL_PRIO catch-all"
        }
        return out
    }

    /**
     * Whether the tick should spend a root shell on the check now: once every
     * [CHECK_INTERVAL_MS], or sooner when the DNS gate counted a miss on this tick
     * ([dnsGateMiss]) and the last check is at least [EARLY_CHECK_GAP_MS] old. A successful
     * apply counts as a check ([lastCheckMs] is its time): it has just read the rules back
     * itself.
     */
    fun checkDue(nowMs: Long, lastCheckMs: Long, dnsGateMiss: Boolean): Boolean {
        val since = nowMs - lastCheckMs
        if (since >= CHECK_INTERVAL_MS) return true
        return dnsGateMiss && since >= EARLY_CHECK_GAP_MS
    }

    // The same patterns the apply's verify tail greps for (RootRoutingScript.applyVerifyScript).
    private val MARK_RULE = Regex("^100:.*fwmark $MARK_BIT/$MARK_MASK lookup $ROUTE_TABLE\\b")
    private val TAILNET_RULE = Regex("^100:.*to 100\\.64\\.0\\.0/10 .*lookup $ROUTE_TABLE\\b")
    private val CATCH_ALL_RULE = Regex("^$CATCH_ALL_PRIO:.*fwmark (0x0|0)/$CATCH_ALL_MASK.*lookup $DAEMON_TABLE\\b")
}
