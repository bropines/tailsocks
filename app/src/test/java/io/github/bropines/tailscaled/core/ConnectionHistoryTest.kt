package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.ConnectionKind.CONNECTED
import io.github.bropines.tailscaled.core.ConnectionKind.NEEDS_YOU
import io.github.bropines.tailscaled.core.ConnectionKind.NO_DATA
import io.github.bropines.tailscaled.core.ConnectionKind.RECOVERING
import io.github.bropines.tailscaled.core.ConnectionKind.STOPPED
import io.github.bropines.tailscaled.core.ConnectionKind.TROUBLE
import io.github.bropines.tailscaled.core.ConnectionKind.WAITING_FOR_NETWORK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connection timeline's rules (ConnectionLog): what is written, what is kept, and how a window reads. */
class ConnectionHistoryTest {

    private val min = 60_000L
    private val hour = 60 * min

    /** A day after the epoch, so that times before the window stay positive. */
    private val t0 = 24 * hour

    private fun ev(minute: Long, kind: ConnectionKind) = ConnectionEvent(t0 + minute * min, kind)
    private fun seg(kind: ConnectionKind, from: Long, to: Long, openStart: Boolean = false, openEnd: Boolean = false) =
        ConnectionSegment(kind, t0 + from * min, t0 + to * min, openStart, openEnd)

    /** Feeds observations through [ConnectionLog.appended] as one process would. */
    private fun MutableList<ConnectionEvent>.observe(kind: ConnectionKind?, at: Long, lastAlive: Long = 0, first: Boolean = false) {
        addAll(ConnectionLog.appended(this, lastAlive, kind, at, first))
    }

    // --- Card states --------------------------------------------------------------------------

    @Test
    fun everyCardStateHasAKind() {
        assertEquals(CONNECTED, connectionKindOf(CardState.CONNECTED))
        for (s in listOf(CardState.CONNECTING, CardState.STARTING, CardState.RESTARTING, CardState.RECONNECTING)) {
            assertEquals(RECOVERING, connectionKindOf(s))
        }
        for (s in listOf(CardState.DEGRADED, CardState.CONNECTION_LOST, CardState.ROOT_FAILED)) {
            assertEquals(TROUBLE, connectionKindOf(s))
        }
        assertEquals(WAITING_FOR_NETWORK, connectionKindOf(CardState.WAITING_FOR_NETWORK))
        for (s in listOf(CardState.NEEDS_LOGIN, CardState.AWAITING_APPROVAL, CardState.OS_REFUSED)) {
            assertEquals(NEEDS_YOU, connectionKindOf(s))
        }
        // The stop begins where it was asked for; the service says nothing after it.
        assertEquals(STOPPED, connectionKindOf(CardState.STOPPING))
    }

    @Test
    fun codesAreUniqueAndReadBack() {
        assertEquals(ConnectionKind.entries.size, ConnectionKind.entries.map { it.code }.toSet().size)
        for (k in ConnectionKind.entries) assertEquals(k, ConnectionKind.ofCode(k.code))
        assertNull(ConnectionKind.ofCode('?'))
    }

    // --- Writing ------------------------------------------------------------------------------

    @Test
    fun onlyAChangeOfKindIsAnEvent() {
        val log = mutableListOf<ConnectionEvent>()
        log.observe(RECOVERING, t0, first = true)
        // STARTING → CONNECTING → RECONNECTING are one kind: one event.
        log.observe(RECOVERING, t0 + 1000)
        log.observe(RECOVERING, t0 + 2000)
        log.observe(CONNECTED, t0 + 3000)
        log.observe(CONNECTED, t0 + 4000)
        assertEquals(listOf(ConnectionEvent(t0, RECOVERING), ConnectionEvent(t0 + 3000, CONNECTED)), log)
    }

    @Test
    fun heartbeatAddsNoEvent() {
        val log = mutableListOf(ev(0, CONNECTED))
        assertTrue(ConnectionLog.appended(log, t0, null, t0 + hour, firstWriteOfProcess = false).isEmpty())
    }

    @Test
    fun clockSetBackKeepsTheOrder() {
        val log = mutableListOf(ev(10, CONNECTED))
        log.observe(TROUBLE, t0 + 3 * min)
        assertEquals(ev(10, TROUBLE), log.last())
    }

    @Test
    fun deadProcessLeavesAGapFromWhenItWasLastAlive() {
        // Connected at 0, heartbeats until minute 30, then silence: the process was killed.
        val log = mutableListOf(ev(0, CONNECTED))
        // Five hours later a new process starts the service.
        log.observe(RECOVERING, t0 + 330 * min, lastAlive = t0 + 30 * min, first = true)
        assertEquals(listOf(ev(0, CONNECTED), ev(30, NO_DATA), ev(330, RECOVERING)), log)

        val line = ConnectionLog.timeline(log, t0, t0 + 340 * min)
        assertEquals(
            listOf(seg(CONNECTED, 0, 30), seg(NO_DATA, 30, 330), seg(RECOVERING, 330, 340, openEnd = true)),
            line.segments
        )
        // Half an hour connected, not five and a half.
        assertEquals(30 * min, line.summary.connectedMs)
        assertEquals(1, line.summary.interruptions)
    }

    @Test
    fun gapIsFoundByAHeartbeatToo() {
        val log = mutableListOf(ev(0, CONNECTED))
        log.observe(null, t0 + 90 * min, lastAlive = t0 + 20 * min, first = true)
        assertEquals(listOf(ev(0, CONNECTED), ev(20, NO_DATA)), log)
    }

    @Test
    fun noGapAfterAStopOrWithinOneProcess() {
        // The service said it stopped: the next process has nothing to repair.
        val stopped = mutableListOf(ev(0, CONNECTED), ev(10, STOPPED))
        stopped.observe(RECOVERING, t0 + 500 * min, lastAlive = t0 + 10 * min, first = true)
        assertEquals(listOf(ev(0, CONNECTED), ev(10, STOPPED), ev(500, RECOVERING)), stopped)

        // Six steady hours in one process, heartbeats delayed by a sleeping device: still connected.
        val steady = mutableListOf(ev(0, CONNECTED))
        steady.observe(TROUBLE, t0 + 360 * min, lastAlive = t0 + 5 * min, first = false)
        assertEquals(listOf(ev(0, CONNECTED), ev(360, TROUBLE)), steady)
    }

    @Test
    fun gapNeverStartsBeforeTheStateItCloses() {
        // A last-alive time older than the last event (a clock set back) must not reorder the list.
        val log = mutableListOf(ev(10, CONNECTED))
        log.observe(RECOVERING, t0 + 60 * min, lastAlive = t0 + 2 * min, first = true)
        assertEquals(listOf(ev(10, CONNECTED), ev(10, NO_DATA), ev(60, RECOVERING)), log)
    }

    // --- Keeping ------------------------------------------------------------------------------

    @Test
    fun pruneKeepsTheStateAtTheCutoff() {
        val now = t0 + 100 * hour
        val old = ConnectionEvent(now - 40 * hour, RECOVERING)
        val atCutoff = ConnectionEvent(now - 30 * hour, CONNECTED)
        val inside = ConnectionEvent(now - 2 * hour, TROUBLE)
        val kept = ConnectionLog.pruned(listOf(old, atCutoff, inside), now)
        assertEquals(listOf(atCutoff, inside), kept)
        // The 24-hour window still knows it was connected at its left edge.
        val line = ConnectionLog.timeline(kept, now - 24 * hour, now)
        assertEquals(CONNECTED, line.segments.first().kind)
        assertTrue(line.segments.first().openStart)
    }

    @Test
    fun pruneOfAllOldEventsKeepsTheLast() {
        val now = t0 + 100 * hour
        val events = listOf(ConnectionEvent(now - 60 * hour, CONNECTED), ConnectionEvent(now - 50 * hour, STOPPED))
        assertEquals(events.takeLast(1), ConnectionLog.pruned(events, now))
        assertTrue(ConnectionLog.pruned(emptyList(), now).isEmpty())
    }

    @Test
    fun pruneLeavesARecentHistoryAlone() {
        val events = listOf(ev(0, CONNECTED), ev(5, TROUBLE))
        assertEquals(events, ConnectionLog.pruned(events, t0 + hour))
    }

    @Test
    fun aFlappingNetworkIsCapped() {
        val now = t0 + hour
        val flapping = List(ConnectionLog.MAX_EVENTS * 3) { i ->
            ConnectionEvent(t0 + i, if (i % 2 == 0) CONNECTED else WAITING_FOR_NETWORK)
        }
        val kept = ConnectionLog.pruned(flapping, now)
        assertEquals(ConnectionLog.MAX_EVENTS, kept.size)
        assertEquals(flapping.last(), kept.last())
        // What was dropped is the oldest; the timeline begins later, with no data before it.
        assertEquals(NO_DATA, ConnectionLog.timeline(kept, t0, now).segments.first().kind)
    }

    // --- The file -----------------------------------------------------------------------------

    @Test
    fun fileReadsBackWhatWasWritten() {
        val events = listOf(ev(0, RECOVERING), ev(1, CONNECTED), ev(9, STOPPED))
        val read = ConnectionLog.decode(ConnectionLog.encode(events, t0 + 20 * min))
        assertEquals(events, read.events)
        assertEquals(t0 + 20 * min, read.lastAlive)
        assertEquals(4, read.lines)
    }

    @Test
    fun lastAliveIsTheLatestLineOfAnyKind() {
        val text = ConnectionLog.eventLine(ev(0, CONNECTED)) + ConnectionLog.aliveLine(t0 + 5 * min) +
            ConnectionLog.aliveLine(t0 + 6 * min) + ConnectionLog.eventLine(ev(8, TROUBLE))
        assertEquals(t0 + 8 * min, ConnectionLog.decode(text).lastAlive)
    }

    @Test
    fun aTornOrForeignLineCostsOnlyItself() {
        val text = ConnectionLog.eventLine(ev(0, CONNECTED)) +
            "garbage\n" + "e notanumber C\n" + "e ${t0 + min} ?\n" + "a\n" +
            // Out of order: skipped rather than let it bend the timeline.
            "e ${t0 - hour} S\n" +
            ConnectionLog.eventLine(ev(2, TROUBLE)) +
            // The process died in the middle of this one.
            "e ${t0 + 3 * min}"
        val read = ConnectionLog.decode(text)
        assertEquals(listOf(ev(0, CONNECTED), ev(2, TROUBLE)), read.events)
        assertTrue(ConnectionLog.decode("").events.isEmpty())
    }

    // --- Reading a window -----------------------------------------------------------------------

    @Test
    fun segmentsAreCutAtTheWindowEdges() {
        val events = listOf(ev(-30, CONNECTED), ev(10, WAITING_FOR_NETWORK), ev(17, CONNECTED), ev(50, TROUBLE), ev(90, CONNECTED))
        val line = ConnectionLog.timeline(events, t0, t0 + 60 * min)
        assertEquals(
            listOf(
                seg(CONNECTED, 0, 10, openStart = true),
                seg(WAITING_FOR_NETWORK, 10, 17),
                seg(CONNECTED, 17, 50),
                // The event at minute 90 is past the window: this one runs on beyond its edge.
                seg(TROUBLE, 50, 60, openEnd = true),
            ),
            line.segments
        )
        // The segments fill the window with no hole and no overlap.
        assertEquals(60 * min, line.segments.sumOf { it.durationMs })
    }

    @Test
    fun beforeTheFirstEventThereIsNoData() {
        val line = ConnectionLog.timeline(listOf(ev(20, CONNECTED)), t0, t0 + 60 * min)
        assertEquals(listOf(seg(NO_DATA, 0, 20, openStart = true), seg(CONNECTED, 20, 60, openEnd = true)), line.segments)
        assertEquals(40 * min, line.summary.onMs)
        assertEquals(1f, line.summary.connectedFraction!!, 0f)
        // The leading no-data is not an interruption: nothing was connected before it.
        assertEquals(0, line.summary.interruptions)
    }

    @Test
    fun anEmptyHistoryIsOneStretchOfNoData() {
        val line = ConnectionLog.timeline(emptyList(), t0, t0 + hour)
        assertEquals(listOf(seg(NO_DATA, 0, 60, openStart = true, openEnd = true)), line.segments)
        assertFalse(line.summary.hasData)
        assertNull(line.summary.connectedFraction)
    }

    @Test
    fun theOpenSegmentGrowsWithTheClock() {
        val events = listOf(ev(0, CONNECTED))
        assertEquals(listOf(seg(CONNECTED, 0, 5, openEnd = true)), ConnectionLog.timeline(events, t0, t0 + 5 * min).segments)
        assertEquals(listOf(seg(CONNECTED, 0, 45, openEnd = true)), ConnectionLog.timeline(events, t0, t0 + 45 * min).segments)
    }

    @Test
    fun anOpenSegmentOfADeadProcessEndsWhereItWasLastAlive() {
        // The interface opens in a new process; the service has not started, so nothing was written yet.
        val events = listOf(ev(0, CONNECTED))
        val line = ConnectionLog.timeline(events, t0, t0 + 120 * min, aliveUntil = t0 + 40 * min)
        assertEquals(listOf(seg(CONNECTED, 0, 40), seg(NO_DATA, 40, 120, openEnd = true)), line.segments)
        assertEquals(40 * min, line.summary.connectedMs)
        assertEquals(1, line.summary.interruptions)
    }

    @Test
    fun aStopLastsWhoeverIsAlive() {
        val events = listOf(ev(0, CONNECTED), ev(10, STOPPED))
        val line = ConnectionLog.timeline(events, t0, t0 + 120 * min, aliveUntil = t0 + 10 * min)
        assertEquals(listOf(seg(CONNECTED, 0, 10), seg(STOPPED, 10, 120, openEnd = true)), line.segments)
        // Stopping is the user's own doing, not an interruption.
        assertEquals(0, line.summary.interruptions)
    }

    @Test
    fun eventsAtOneInstantLeaveNoEmptySegment() {
        val events = listOf(ev(0, CONNECTED), ev(10, NO_DATA), ev(10, RECOVERING), ev(11, CONNECTED))
        val line = ConnectionLog.timeline(events, t0, t0 + 20 * min)
        assertEquals(listOf(seg(CONNECTED, 0, 10), seg(RECOVERING, 10, 11), seg(CONNECTED, 11, 20, openEnd = true)), line.segments)
    }

    @Test
    fun neighboursOfOneKindAreOneSegment() {
        // A zero-length gap between two connected events must not split the bar.
        val events = listOf(ev(0, CONNECTED), ev(10, NO_DATA), ev(10, CONNECTED))
        val line = ConnectionLog.timeline(events, t0, t0 + 20 * min)
        assertEquals(listOf(seg(CONNECTED, 0, 20, openEnd = true)), line.segments)
        assertEquals(0, line.summary.interruptions)
    }

    @Test
    fun anEmptyOrBackwardWindowIsEmpty() {
        val events = listOf(ev(0, CONNECTED))
        assertTrue(ConnectionLog.timeline(events, t0 + min, t0 + min).segments.isEmpty())
        assertTrue(ConnectionLog.timeline(events, t0 + min, t0).segments.isEmpty())
    }

    // --- The summary ------------------------------------------------------------------------------

    @Test
    fun summaryCountsConnectedTimeOfTheTimeItWasOn() {
        // Six hours: off for the first three, then on, with two interruptions of 6 and 3 minutes.
        val events = listOf(
            ev(-60, STOPPED),
            ev(180, RECOVERING), ev(181, CONNECTED),
            ev(240, WAITING_FOR_NETWORK), ev(246, CONNECTED),
            ev(300, TROUBLE), ev(302, RECOVERING), ev(303, CONNECTED),
        )
        val s = ConnectionLog.timeline(events, t0, t0 + 360 * min).summary
        assertEquals(360 * min, s.windowMs)
        assertEquals(180 * min, s.onMs)
        assertEquals((59 + 54 + 57) * min, s.connectedMs)
        assertEquals(170f / 180f, s.connectedFraction!!, 1e-6f)
        assertEquals(170f / 360f, s.connectedOfWindow, 1e-6f)
        // Trouble followed by recovering is one interruption, not two.
        assertEquals(2, s.interruptions)
        assertTrue(s.hasData)
    }

    @Test
    fun aWindowSpentStoppedWasNeverOn() {
        val s = ConnectionLog.timeline(listOf(ev(-10, STOPPED)), t0, t0 + hour).summary
        assertEquals(0L, s.onMs)
        assertNull(s.connectedFraction)
        assertTrue(s.hasData)
    }

    @Test
    fun waitingForTheUserCountsAsOnButNotConnected() {
        val events = listOf(ev(0, NEEDS_YOU), ev(30, CONNECTED))
        val s = ConnectionLog.timeline(events, t0, t0 + hour).summary
        assertEquals(0.5f, s.connectedFraction!!, 1e-6f)
        assertEquals(0, s.interruptions)
    }

    @Test
    fun anInterruptionStillGoingOnIsCounted() {
        val events = listOf(ev(0, CONNECTED), ev(50, WAITING_FOR_NETWORK))
        assertEquals(1, ConnectionLog.timeline(events, t0, t0 + hour).summary.interruptions)
    }

    @Test
    fun anInterruptionBeforeTheWindowIsNot() {
        val events = listOf(ev(-50, CONNECTED), ev(-40, TROUBLE), ev(-30, CONNECTED))
        val s = ConnectionLog.timeline(events, t0, t0 + hour).summary
        assertEquals(0, s.interruptions)
        assertEquals(1f, s.connectedFraction!!, 0f)
    }
}
