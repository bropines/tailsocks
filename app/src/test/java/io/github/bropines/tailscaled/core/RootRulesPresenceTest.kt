package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.RootRulesPresence.CHECK_INTERVAL_MS
import io.github.bropines.tailscaled.core.RootRulesPresence.EARLY_CHECK_GAP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading back whether the Root Mode ruleset is still on the device, and when to ask. */
class RootRulesPresenceTest {

    // As `ip rule show` prints them on the Redmi (2026-11-15), filtered as the script does.
    private val mark = "100:\tfrom all fwmark 0x1000000/0x1000000 lookup 53 "
    private val tailnet = "100:\tfrom all to 100.64.0.0/10 iif lo lookup 53 "
    private val catchAll = "200:\tfrom all fwmark 0x0/0x2020000 iif lo lookup 52 "
    private val scopedCatchAll = "200:\tfrom all fwmark 0x0/0x2020000 iif lo uidrange 0-10353 lookup 52 "
    private val route53 = "100.64.0.0/10 dev tailscale0 scope link metric 1 "

    private fun out(rules: List<String>, table: List<String>, end: Boolean = true) = buildString {
        append("@@rules\n")
        rules.forEach { append(it).append('\n') }
        append("@@table53\n")
        table.forEach { append(it).append('\n') }
        if (end) append("@@end\n")
    }

    private data class Row(val name: String, val output: String, val expectCatchAll: Boolean, val expected: List<String>?)

    @Test
    fun `missing pieces are named, and an incomplete answer is no answer`() {
        val fwmark = "the priority-100 fwmark rule"
        val dest = "the priority-100 tailnet rule"
        val t53 = "the route in table 53"
        val ca = "the priority-200 catch-all"
        val table = listOf(
            Row("whole, with catch-all", out(listOf(mark, tailnet, catchAll), listOf(route53)), true, emptyList()),
            Row("whole, scoped catch-all", out(listOf(mark, tailnet, scopedCatchAll), listOf(route53)), true, emptyList()),
            Row("tailnet only, yielded", out(listOf(mark, tailnet), listOf(route53)), false, emptyList()),
            Row("netd flushed every rule", out(emptyList(), listOf(route53)), true, listOf(fwmark, dest, ca)),
            Row("flushed, yielded run", out(emptyList(), listOf(route53)), false, listOf(fwmark, dest)),
            Row("catch-all alone gone", out(listOf(mark, tailnet), listOf(route53)), true, listOf(ca)),
            Row("table 53 emptied", out(listOf(mark, tailnet, catchAll), emptyList()), true, listOf(t53)),
            Row("fwmark rule alone gone", out(listOf(tailnet, catchAll), listOf(route53)), true, listOf(fwmark)),
            // A rule into another table is not ours.
            Row(
                "pref 100 into a foreign table",
                out(listOf("100:\tfrom all fwmark 0x1000000/0x1000000 lookup 530 ", tailnet), listOf(route53)), false,
                listOf(fwmark)
            ),
            Row("shell cut short", out(listOf(mark, tailnet), listOf(route53), end = false), true, null),
            Row("nothing at all", "", true, null),
            Row("su refused", "Permission denied\n", false, null),
        )
        for (r in table) {
            assertEquals(r.name, r.expected, RootRulesPresence.missing(r.output, r.expectCatchAll))
        }
    }

    @Test
    fun `the script's own output parses as a whole ruleset when the rules are there`() {
        // The script echoes its markers itself; the lines in between are what the device prints.
        val printed = RootRulesPresence.script().lines()
            .filter { it.startsWith("echo '") }
            .map { it.removePrefix("echo '").removeSuffix("'") }
        assertEquals(listOf("@@rules", "@@table53", "@@end"), printed)
    }

    @Test
    fun `the check runs once a minute, and early on a DNS gate miss`() {
        val t0 = 1_000_000L
        val table = listOf(
            // (since the last check, gate missed) -> due
            Triple(0L, false, false),
            Triple(CHECK_INTERVAL_MS - 1, false, false),
            Triple(CHECK_INTERVAL_MS, false, true),
            Triple(5 * CHECK_INTERVAL_MS, false, true),
            Triple(EARLY_CHECK_GAP_MS - 1, true, false),
            Triple(EARLY_CHECK_GAP_MS, true, true),
            Triple(CHECK_INTERVAL_MS, true, true),
        )
        for ((since, miss, expected) in table) {
            assertEquals("since=$since miss=$miss", expected, RootRulesPresence.checkDue(t0 + since, t0, miss))
        }
    }

    @Test
    fun `without a gate miss the steady state costs one root shell a minute`() {
        var last = 0L
        var shells = 0
        // Fifteen-second ticks over ten minutes, the first check measured from an apply at t=0.
        for (tick in 1..40) {
            val now = tick * 15_000L
            if (RootRulesPresence.checkDue(now, last, dnsGateMiss = false)) { shells++; last = now }
        }
        assertEquals(10, shells)
        assertNull(RootRulesPresence.missing("@@rules\n@@end\n", false))
    }
}
