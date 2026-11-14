package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.DaemonEventKind.HEALTH
import io.github.bropines.tailscaled.core.DaemonEventKind.NETMAP
import io.github.bropines.tailscaled.core.DaemonEventKind.PREFS
import io.github.bropines.tailscaled.core.DaemonEventKind.STATE
import io.github.bropines.tailscaled.core.ServiceRefresh.CARD
import io.github.bropines.tailscaled.core.ServiceRefresh.FORGET_EXIT_NODE_NAME
import io.github.bropines.tailscaled.core.ServiceRefresh.RETRY_EXIT_NODE_NAME
import io.github.bropines.tailscaled.core.ServiceRefresh.SHARE_TARGETS
import io.github.bropines.tailscaled.core.ServiceRefresh.WIDGETS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the foreground service refreshes for which daemon event, and when its safety net reads. */
class ServiceRefreshPlanTest {

    private data class Row(val changed: Set<DaemonEventKind>, val interactive: Boolean, val expected: Set<ServiceRefresh>)

    @Test
    fun `each event kind calls for its refreshes`() {
        val table = listOf(
            Row(setOf(STATE), true, setOf(CARD, SHARE_TARGETS, WIDGETS)),
            Row(setOf(STATE), false, setOf(CARD, SHARE_TARGETS)),
            Row(setOf(HEALTH), true, setOf(CARD)),
            Row(setOf(HEALTH), false, setOf(CARD)),
            Row(setOf(PREFS), true, setOf(CARD, FORGET_EXIT_NODE_NAME, WIDGETS)),
            Row(setOf(PREFS), false, setOf(CARD, FORGET_EXIT_NODE_NAME)),
            Row(setOf(NETMAP), true, setOf(SHARE_TARGETS, RETRY_EXIT_NODE_NAME, WIDGETS)),
            Row(setOf(NETMAP), false, setOf(SHARE_TARGETS, RETRY_EXIT_NODE_NAME)),
            // A burst folded into one pass asks for the union, once.
            Row(setOf(HEALTH, NETMAP), false, setOf(CARD, SHARE_TARGETS, RETRY_EXIT_NODE_NAME)),
            Row(
                setOf(STATE, HEALTH, PREFS, NETMAP), true,
                setOf(CARD, FORGET_EXIT_NODE_NAME, RETRY_EXIT_NODE_NAME, SHARE_TARGETS, WIDGETS)
            ),
            Row(emptySet(), true, emptySet()),
        )
        for (row in table) {
            assertEquals(row.toString(), row.expected, serviceRefreshesFor(row.changed, row.interactive))
        }
    }

    @Test
    fun `health and prefs never read the full status for the share sheet`() {
        assertFalse(SHARE_TARGETS in serviceRefreshesFor(setOf(HEALTH), true))
        assertFalse(SHARE_TARGETS in serviceRefreshesFor(setOf(PREFS), true))
    }

    @Test
    fun `the safety net reads the card, and the widgets only with the screen on`() {
        assertEquals(setOf(CARD, WIDGETS), serviceSafetyNetRefreshes(interactive = true))
        assertEquals(setOf(CARD), serviceSafetyNetRefreshes(interactive = false))
    }

    @Test
    fun `the card worker wakes for the safety net or a warning coming of age`() {
        val s = SERVICE_SAFETY_MS
        // now, last read, warning settles at (0 = none), expected delay
        val table = listOf(
            listOf(1_000L, 1_000L, 0L, s),                    // just read: the whole safety interval
            listOf(11_000L, 1_000L, 0L, s - 10_000L),         // part of it gone
            listOf(1_000L + s, 1_000L, 0L, 0L),               // due now
            listOf(5_000L + s, 1_000L, 0L, 0L),               // overdue: never negative
            listOf(1_000L, 1_000L, 6_000L, 5_000L),           // a hidden warning shows in 5 s: sooner
            listOf(1_000L, 1_000L, 1_000L + s + 5_000L, s),   // one that shows after the net: the net
            listOf(10_000L, 10_000L, 8_000L, s),              // came of age before the read: no extra wake
        )
        for ((now, last, settles, expected) in table) {
            assertEquals("now=$now last=$last settles=$settles", expected, serviceCardWakeDelayMs(now, last, settles))
        }
    }

    private data class Retry(val resolved: Boolean, val now: Long, val lastFetch: Long, val due: Boolean)

    @Test
    fun `a netmap event retries only an unresolved exit node name, at most every ten seconds`() {
        val r = EXIT_NODE_NAME_RETRY_MS
        val table = listOf(
            Retry(true, 100_000L, 0L, false),               // a found name stays
            Retry(true, 100_000L, 99_000L, false),
            Retry(false, 100_000L, 0L, true),               // never fetched
            Retry(false, 100_000L, 100_000L - r, true),     // exactly the gap
            Retry(false, 100_000L, 100_000L - r + 1, false), // just inside it
        )
        for (row in table) {
            assertEquals(row.toString(), row.due, exitNodeNameRetryDue(row.resolved, row.now, row.lastFetch))
        }
    }
}
