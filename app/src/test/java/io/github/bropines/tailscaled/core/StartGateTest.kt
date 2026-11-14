package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One start sequence in flight per service; a START meanwhile joins it, a STOP supersedes it. */
class StartGateTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val gate = StartGate(executor)
    private val owner = Any()

    private fun drain() {
        // A no-op behind everything queued: once it has run, so has the rest.
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `two STARTs while one is in flight run one sequence`() {
        val release = CountDownLatch(1)
        val runs = Collections.synchronizedList(mutableListOf<String>())
        val admitted = mutableListOf<String>()
        assertTrue(gate.request(owner, 0, onAdmitted = { admitted += "first" }) { release.await(); runs += "first" })
        assertTrue(gate.inFlight)
        assertFalse(gate.request(owner, 0, onAdmitted = { admitted += "second" }) { runs += "second" })
        assertFalse(gate.request(owner, 0) { runs += "third" })
        release.countDown()
        drain()
        assertEquals(listOf("first"), runs)
        assertEquals(listOf("first"), admitted)
        assertFalse(gate.inFlight)
    }

    @Test
    fun `a START after a STOP is a new sequence, and it runs after the superseded one`() {
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        var stopGeneration = 0
        val firstGen = stopGeneration
        assertTrue(gate.request(owner, firstGen) {
            release.await()
            // The start asks whether a stop came since it began, as the service's does.
            order += if (firstGen != stopGeneration) "first abandoned" else "first started"
        })
        stopGeneration++ // STOP
        assertTrue(gate.request(owner, stopGeneration) { order += "second started" })
        // A further START in the same generation joins the queued one.
        assertFalse(gate.request(owner, stopGeneration) { order += "third started" })
        release.countDown()
        drain()
        assertEquals(listOf("first abandoned", "second started"), order)
        assertFalse(gate.inFlight)
    }

    @Test
    fun `a START after the sequence has ended starts again`() {
        val runs = Collections.synchronizedList(mutableListOf<Int>())
        assertTrue(gate.request(owner, 0) { runs += 1 })
        drain()
        assertFalse(gate.inFlight)
        assertTrue(gate.request(owner, 0) { runs += 2 })
        drain()
        assertEquals(listOf(1, 2), runs)
    }

    @Test
    fun `a new service instance queues behind the previous one's sequence`() {
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        assertTrue(gate.request(Any(), 0) { release.await(); order += "old" })
        // Same generation number, different instance: not the same sequence.
        assertTrue(gate.request(owner, 0) { order += "new" })
        release.countDown()
        drain()
        assertEquals(listOf("old", "new"), order)
    }

    @Test
    fun `a sequence that throws still opens the gate`() {
        val failing = Executors.newSingleThreadExecutor { r -> Thread(r).apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> } } }
        val g = StartGate(failing)
        assertTrue(g.request(owner, 0) { throw IllegalStateException("boom") })
        val done = CountDownLatch(1)
        failing.execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(g.inFlight)
        assertTrue(g.request(owner, 0) {})
    }
}
