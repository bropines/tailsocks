package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.RootOrphanSweep.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** When the process-start sweep removes rules a dead daemon left behind. */
class RootOrphanSweepTest {

    private fun never(): Boolean = throw AssertionError("no root shell should be spent on this")

    @Test
    fun `nothing recorded, nothing done and no root shell asked`() {
        assertEquals(Action.NOTHING, RootOrphanSweep.decide(installed = false, desiredRunning = false, daemonProcessAlive = ::never))
    }

    @Test
    fun `a wanted connection is left to the start, which unhooks DNS itself`() {
        assertEquals(Action.NOTHING, RootOrphanSweep.decide(installed = true, desiredRunning = true, daemonProcessAlive = ::never))
    }

    @Test
    fun `a running daemon keeps its rules`() {
        assertEquals(Action.NOTHING, RootOrphanSweep.decide(installed = true, desiredRunning = false) { true })
    }

    @Test
    fun `rules with no daemon and no wish to run are removed`() {
        var asked = false
        assertEquals(Action.CLEANUP, RootOrphanSweep.decide(installed = true, desiredRunning = false) { asked = true; false })
        assertFalse(!asked)
    }
}
