package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The revision bookkeeping behind DaemonEvents: what a collector that was away learns. */
class DaemonEventsTest {

    @Test
    fun `the wire kinds are the bridge's`() {
        assertEquals(DaemonEventKind.STATE, DaemonEventKind.fromWire("state"))
        assertEquals(DaemonEventKind.HEALTH, DaemonEventKind.fromWire("health"))
        assertEquals(DaemonEventKind.PREFS, DaemonEventKind.fromWire("prefs"))
        assertEquals(DaemonEventKind.NETMAP, DaemonEventKind.fromWire("netmap"))
        // A kind a newer bridge might add, and engine statistics, which are no event.
        assertNull(DaemonEventKind.fromWire("engine"))
    }

    @Test
    fun `an event records its seq under its kind`() {
        val r = Revisions().with(DaemonEvent(DaemonEventKind.NETMAP, 7))
        assertEquals(Revisions(netmap = 7), r)
        assertEquals(Revisions(state = 8, netmap = 7), r.with(DaemonEvent(DaemonEventKind.STATE, 8)))
    }

    @Test
    fun `an older or repeated event changes nothing`() {
        val r = Revisions(state = 10)
        assertSame(r, r.with(DaemonEvent(DaemonEventKind.STATE, 9)))
        assertSame(r, r.with(DaemonEvent(DaemonEventKind.STATE, 10)))
    }

    @Test
    fun `a collector that was away learns which kinds changed meanwhile`() {
        val left = Revisions(state = 3, health = 2, prefs = 0, netmap = 4)
        var now = left
        for ((k, seq) in listOf(DaemonEventKind.NETMAP to 5L, DaemonEventKind.NETMAP to 6L, DaemonEventKind.HEALTH to 7L)) {
            now = now.with(DaemonEvent(k, seq))
        }
        assertEquals(setOf(DaemonEventKind.NETMAP, DaemonEventKind.HEALTH), now.changedSince(left))
        assertTrue(now.changedSince(now).isEmpty())
    }

    @Test
    fun `deliver feeds the revisions, and ignores kinds it does not know`() {
        val before = DaemonEvents.revision.value
        val seq = maxOf(before.state, before.health, before.prefs, before.netmap) + 1
        DaemonEvents.deliver("prefs", seq)
        DaemonEvents.deliver("engine", seq + 1)
        val after = DaemonEvents.revision.value
        assertEquals(setOf(DaemonEventKind.PREFS), after.changedSince(before))
        assertEquals(seq, after.prefs)
    }
}
