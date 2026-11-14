package io.github.bropines.tailscaled.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refresh tick's gate: one tick at a time, and a request made meanwhile runs right after. */
class TickGateTest {

    @Test
    fun `a quiet tick ends without asking for another`() {
        val gate = TickGate()
        assertTrue(gate.tryBegin())
        assertFalse(gate.end())
    }

    @Test
    fun `a request during a tick is refused now and honoured when it ends`() {
        val gate = TickGate()
        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertTrue(gate.end())
        // The follow-up tick starts clean.
        assertTrue(gate.tryBegin())
        assertFalse(gate.end())
    }

    @Test
    fun `a burst of requests folds into one extra tick`() {
        val gate = TickGate()
        assertTrue(gate.tryBegin())
        repeat(5) { assertFalse(gate.tryBegin()) }
        assertTrue(gate.end())
        assertTrue(gate.tryBegin())
        assertFalse(gate.end())
    }
}
