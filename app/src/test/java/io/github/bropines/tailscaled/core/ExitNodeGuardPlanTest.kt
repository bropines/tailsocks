package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.ExitNodeGuard.Watch
import io.github.bropines.tailscaled.core.GuardTrigger.HEALTH
import io.github.bropines.tailscaled.core.GuardTrigger.NETMAP
import io.github.bropines.tailscaled.core.GuardTrigger.PREFS
import io.github.bropines.tailscaled.core.GuardTrigger.START
import io.github.bropines.tailscaled.core.GuardTrigger.STATE
import io.github.bropines.tailscaled.core.GuardTrigger.TIMER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the exit-node guard reads the full status: which trigger reads in which state, when the
 * next timed look is due, and how the floor spaces event-driven reads out (ServiceRefreshPlan.kt).
 */
class ExitNodeGuardPlanTest {

    @Test
    fun `each daemon event kind is its own trigger`() {
        assertEquals(
            setOf(STATE, HEALTH, PREFS, NETMAP),
            exitNodeGuardTriggersFor(DaemonEventKind.entries.toSet()),
        )
        assertEquals(setOf(NETMAP), exitNodeGuardTriggersFor(setOf(DaemonEventKind.NETMAP)))
        assertEquals(emptySet<GuardTrigger>(), exitNodeGuardTriggersFor(emptySet()))
    }

    @Test
    fun `which trigger reads in which state`() {
        // The full table, written out: a row per state, the triggers that read in it.
        val reads = mapOf(
            Watch.IDLE to emptySet(),
            Watch.UNJUDGED to setOf(START, STATE, HEALTH, PREFS, NETMAP, TIMER),
            Watch.WATCHING to setOf(START, STATE, PREFS, NETMAP, TIMER),
            Watch.TIMING to setOf(START, STATE, PREFS, NETMAP, TIMER),
            Watch.WARNED to setOf(START, STATE, PREFS, NETMAP, TIMER),
            Watch.PAUSED to setOf(START, STATE, PREFS, NETMAP, TIMER),
        )
        assertEquals(Watch.entries.toSet(), reads.keys)
        for ((watch, expected) in reads) for (trigger in GuardTrigger.entries) {
            assertEquals("$watch on $trigger", trigger in expected, exitNodeGuardReads(setOf(trigger), watch))
        }
    }

    @Test
    fun `a folded pass reads when any of its triggers would, and nothing reads nothing`() {
        assertTrue(exitNodeGuardReads(setOf(HEALTH, NETMAP), Watch.WATCHING))
        assertFalse(exitNodeGuardReads(setOf(HEALTH), Watch.WATCHING))
        assertFalse(exitNodeGuardReads(GuardTrigger.entries.toSet(), Watch.IDLE))
        for (watch in Watch.entries) assertFalse("$watch", exitNodeGuardReads(emptySet(), watch))
    }

    @Test
    fun `the next timed look per state`() {
        val grace = ExitNodeGuard.OFFLINE_GRACE_MS
        assertNull(exitNodeGuardNextLookMs(Watch.IDLE, 0L))
        assertEquals(EXIT_GUARD_SAFETY_MS, exitNodeGuardNextLookMs(Watch.WATCHING, 0L))
        // The grace ends when it ends, whatever woke the look that saw the node offline.
        assertEquals(grace, exitNodeGuardNextLookMs(Watch.TIMING, 0L))
        assertEquals(grace - 7_000L, exitNodeGuardNextLookMs(Watch.TIMING, 7_000L))
        assertEquals(0L, exitNodeGuardNextLookMs(Watch.TIMING, grace + 1))
        assertEquals(EXIT_GUARD_AWAIT_MS, exitNodeGuardNextLookMs(Watch.WARNED, grace * 3))
        assertEquals(EXIT_GUARD_AWAIT_MS, exitNodeGuardNextLookMs(Watch.PAUSED, 0L))
        assertEquals(EXIT_GUARD_AWAIT_MS, exitNodeGuardNextLookMs(Watch.UNJUDGED, 0L))
    }

    @Test
    fun `the values keep their reasons`() {
        // A few minutes, not never; the fallback and the floor no tighter than the old poll's
        // actual cadence (20 s on a 15 s tick); the grace fits inside the fallback.
        assertTrue(EXIT_GUARD_SAFETY_MS in 2 * 60_000L..10 * 60_000L)
        assertEquals(30_000L, EXIT_GUARD_AWAIT_MS)
        assertEquals(30_000L, EXIT_GUARD_FLOOR_MS)
        assertTrue(ExitNodeGuard.OFFLINE_GRACE_MS < EXIT_GUARD_FLOOR_MS)
    }

    @Test
    fun `events are spaced out by the floor, the timer is not`() {
        val f = EXIT_GUARD_FLOOR_MS
        val last = 100_000L
        assertTrue("first read of a run", exitNodeGuardMayRead(setOf(START), Watch.UNJUDGED, last, Long.MIN_VALUE / 2))
        assertFalse(exitNodeGuardMayRead(setOf(NETMAP), Watch.WATCHING, last + f - 1, last))
        assertTrue(exitNodeGuardMayRead(setOf(NETMAP), Watch.WATCHING, last + f, last))
        assertTrue("a timed look", exitNodeGuardMayRead(setOf(TIMER), Watch.TIMING, last + 1, last))
        assertTrue(exitNodeGuardMayRead(setOf(NETMAP, TIMER), Watch.WARNED, last + 1, last))
        // The floor never makes a read out of a trigger that does not call for one.
        assertFalse(exitNodeGuardMayRead(setOf(HEALTH), Watch.WATCHING, last + f * 10, last))
        assertFalse(exitNodeGuardMayRead(setOf(TIMER), Watch.IDLE, last + f * 10, last))
    }

    private data class Due(
        val watch: Watch,
        val offlineFor: Long,
        val read: Boolean,
        val heldBack: Boolean,
        val previous: Long?,
        val expected: Long?,
    )

    @Test
    fun `when the guard looks next`() {
        val now = 1_000_000L
        val last = now - 10_000L // the last read, for the floor (read=false rows)
        val f = EXIT_GUARD_FLOOR_MS
        val table = listOf(
            // A read schedules afresh.
            Due(Watch.WATCHING, 0L, true, false, now + 5L, now + EXIT_GUARD_SAFETY_MS),
            Due(Watch.TIMING, 0L, true, false, null, now + ExitNodeGuard.OFFLINE_GRACE_MS),
            Due(Watch.PAUSED, 0L, true, false, null, now + EXIT_GUARD_AWAIT_MS),
            Due(Watch.IDLE, 0L, false, false, now + 5L, null),
            // A look that did not read keeps what was scheduled: health events never postpone the net.
            Due(Watch.WATCHING, 0L, false, false, now + 60_000L, now + 60_000L),
            Due(Watch.WATCHING, 0L, false, false, null, now + EXIT_GUARD_SAFETY_MS),
            // Held back by the floor: carried out when it ends, unless something is due sooner.
            Due(Watch.WATCHING, 0L, false, true, now + EXIT_GUARD_SAFETY_MS, last + f),
            Due(Watch.TIMING, 0L, false, true, now + 5_000L, now + 5_000L),
            Due(Watch.UNJUDGED, 0L, false, true, null, last + f),
        )
        for (row in table) {
            assertEquals(
                row.toString(), row.expected,
                exitNodeGuardDueAt(row.watch, row.offlineFor, row.read, row.heldBack, now, last, row.previous),
            )
        }
    }

    @Test
    fun `where the guard stands, from what it holds`() {
        // selected, paused, judged, timing, warned -> watch
        val table = listOf(
            listOf("", "", false, false, false) to Watch.IDLE,
            listOf("", "", true, true, true) to Watch.IDLE,
            listOf("", "nA", true, false, false) to Watch.PAUSED,
            listOf("nA", "", false, false, false) to Watch.UNJUDGED,
            listOf("nA", "", false, true, true) to Watch.UNJUDGED,
            listOf("nA", "", true, false, false) to Watch.WATCHING,
            listOf("nA", "", true, true, false) to Watch.TIMING,
            listOf("nA", "", true, true, true) to Watch.WARNED,
            // A selected node outranks a stale pause, as in step().
            listOf("nB", "nA", true, false, false) to Watch.WATCHING,
        )
        for ((args, expected) in table) {
            val w = ExitNodeGuard.watch(args[0] as String, args[1] as String, args[2] as Boolean, args[3] as Boolean, args[4] as Boolean)
            assertEquals(args.toString(), expected, w)
        }
    }

    /**
     * The reads per minute this plan costs, walked on a virtual clock for the four states the
     * change was measured in, next to what the 20-second gate on the 15-second tick cost.
     */
    @Test
    fun `reads per minute against the old poll`() {
        assertEquals("node online, nothing changes", 0, readsInAMinute(Watch.WATCHING))
        assertEquals("node paused, nothing changes", 2, readsInAMinute(Watch.PAUSED))
        assertEquals("node offline past the grace, nothing changes", 2, readsInAMinute(Watch.WARNED))
        assertEquals("no exit node", 0, readsInAMinute(Watch.IDLE))
        // The old gate: a read when 20 s had passed, looked at every 15 s — every 30 s.
        var oldReads = 0
        var lastRead = -1_000_000L
        for (t in 0L until 60_000L step 15_000L) if (t - lastRead >= 20_000L) { oldReads++; lastRead = t }
        assertEquals(2, oldReads)
    }

    @Test
    fun `an outage is warned about at the end of the grace with no further event`() {
        // Seen offline by a netmap read at 0; TIMING asks for a look at the grace's end.
        val due = exitNodeGuardDueAt(Watch.TIMING, 0L, read = true, heldBack = false, nowMs = 0L, lastReadAtMs = 0L, previousDueAtMs = null)
        assertEquals(ExitNodeGuard.OFFLINE_GRACE_MS, due)
        assertTrue(exitNodeGuardMayRead(setOf(TIMER), Watch.TIMING, due!!, 0L))
    }

    @Test
    fun `a sustained netmap storm reads no more often than the floor`() {
        // A netmap event every 2 s (the bridge's longest debounce) for ten minutes.
        var last = Long.MIN_VALUE / 2
        var reads = 0
        for (t in 0L until 600_000L step 2_000L) {
            if (exitNodeGuardMayRead(setOf(NETMAP), Watch.WATCHING, t, last)) { reads++; last = t }
        }
        assertEquals(20, reads) // one per 30 s
    }

    /** Timed reads in the minute after a read that left the guard in [watch], with no events. */
    private fun readsInAMinute(watch: Watch): Int {
        var reads = 0
        var due = exitNodeGuardDueAt(watch, 0L, read = true, heldBack = false, nowMs = 0L, lastReadAtMs = 0L, previousDueAtMs = null)
        while (due != null && due <= 60_000L) {
            if (exitNodeGuardMayRead(setOf(TIMER), watch, due, 0L)) reads++
            due = exitNodeGuardDueAt(watch, 0L, read = true, heldBack = false, nowMs = due, lastReadAtMs = due, previousDueAtMs = due)
        }
        return reads
    }
}
