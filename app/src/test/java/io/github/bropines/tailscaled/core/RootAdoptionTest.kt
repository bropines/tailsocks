package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.RootAdoption.Probe
import io.github.bropines.tailscaled.core.RootAdoption.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A start that finds a root daemon: adopt it, wait for it, start one, or replace it. */
class RootAdoptionTest {

    @Test
    fun `a daemon that answers is adopted at once`() {
        assertEquals(Step.ADOPT, RootAdoption.next(Probe.READY, processAlive = true, elapsedMs = 0))
        // The answer is what counts: the process check may simply not have run yet.
        assertEquals(Step.ADOPT, RootAdoption.next(Probe.READY, processAlive = false, elapsedMs = 0))
    }

    @Test
    fun `no daemon process means a normal start, without waiting`() {
        assertEquals(Step.START_FRESH, RootAdoption.next(Probe.ABSENT, processAlive = false, elapsedMs = 0))
        // A socket file a killed daemon left behind accepts nothing and has no process.
        assertEquals(Step.START_FRESH, RootAdoption.next(Probe.STARTING, processAlive = false, elapsedMs = 0))
    }

    @Test
    fun `a daemon that is still coming up is waited for, not killed`() {
        // Before it listens: no socket, or a stale one that refuses.
        assertEquals(Step.WAIT, RootAdoption.next(Probe.ABSENT, processAlive = true, elapsedMs = 0))
        // Listening, but LocalAPI holds the request until the backend exists.
        assertEquals(Step.WAIT, RootAdoption.next(Probe.STARTING, processAlive = true, elapsedMs = 5_000))
    }

    @Test
    fun `a daemon that answers within the budget is adopted`() {
        assertEquals(Step.ADOPT, RootAdoption.next(Probe.READY, processAlive = true, elapsedMs = RootAdoption.BUDGET_MS - 1))
    }

    @Test
    fun `a daemon that dies while it is waited for is replaced by a fresh start`() {
        assertEquals(Step.START_FRESH, RootAdoption.next(Probe.ABSENT, processAlive = false, elapsedMs = 3_000))
    }

    @Test
    fun `a daemon that never answers is replaced once the budget is spent`() {
        assertEquals(Step.GIVE_UP, RootAdoption.next(Probe.STARTING, processAlive = true, elapsedMs = RootAdoption.BUDGET_MS))
        assertEquals(Step.GIVE_UP, RootAdoption.next(Probe.ABSENT, processAlive = true, elapsedMs = RootAdoption.BUDGET_MS + 1))
    }

    @Test
    fun `the wait is bounded and checks often enough to be cheap and quick`() {
        assertTrue(RootAdoption.PROBE_INTERVAL_MS in 100..1_000)
        assertTrue(RootAdoption.PROBE_TIMEOUT_MS < RootAdoption.BUDGET_MS)
        assertTrue(RootAdoption.PROCESS_RECHECK_MS < RootAdoption.BUDGET_MS)
    }

    @Test
    fun `a status with a real backend state is ready`() {
        assertEquals(Probe.READY, RootAdoption.classify("""{"Version":"1.104.1","BackendState":"Running","Self":{}}"""))
        assertEquals(Probe.READY, RootAdoption.classify("""{"BackendState": "NeedsLogin"}"""))
        assertEquals(Probe.READY, RootAdoption.classify("""{"BackendState":"Starting"}"""))
        assertEquals(Probe.READY, RootAdoption.classify("""{"BackendState":"Stopped"}"""))
    }

    @Test
    fun `NoState, an error text or no answer is a daemon that is not ready yet`() {
        assertEquals(Probe.STARTING, RootAdoption.classify("""{"BackendState":"NoState"}"""))
        assertEquals(Probe.STARTING, RootAdoption.classify("no backend\n"))
        assertEquals(Probe.STARTING, RootAdoption.classify(""))
        assertEquals(Probe.STARTING, RootAdoption.classify(null))
        assertEquals(Probe.STARTING, RootAdoption.classify("""{"BackendState":""}"""))
    }
}
