package io.github.bropines.tailscaled.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

/** The rate average the transfers share, and the ticker the downloads run on. */
class TransferPlumbingTest {

    @Test
    fun theFirstSampleIsTakenAsItIsAndLaterOnesByTheWeightOfTheirGap() {
        assertEquals(500.0, TransferRate.smoothed(null, 500.0, 1_000), 0.0)
        val w = 1 - exp(-1_000 / TransferRate.TAU_MS)
        assertEquals(100.0 + w * 400.0, TransferRate.smoothed(100.0, 500.0, 1_000), 1e-9)
        // A longer gap moves it further.
        assertTrue(TransferRate.smoothed(100.0, 500.0, 6_000) > TransferRate.smoothed(100.0, 500.0, 1_000))
    }

    /**
     * The download ticker's race, a thousand times: work arrives again just as the ticker decides
     * there is none. Every start must be followed by a step, or that download runs with no rate
     * and no notification.
     */
    @Test
    fun aStartThatLandsAsTheTickerEndsIsNeverLost() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val busy = AtomicBoolean(false)
            val steps = AtomicInteger()
            val ticker = BusyTicker(scope, periodMs = 0, busy = { busy.get() }) { steps.incrementAndGet() }
            repeat(1_000) { round ->
                val before = steps.get()
                busy.set(true)
                ticker.start()
                val deadline = System.currentTimeMillis() + 5_000
                while (steps.get() == before) {
                    check(System.currentTimeMillis() < deadline) { "round $round: a start was lost" }
                    Thread.yield()
                }
                // Work done: the ticker is now about to end, and the next round starts at once.
                busy.set(false)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aStepThatThrowsDoesNotKeepTheTickerFromStartingAgain() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
        try {
            val steps = AtomicInteger()
            val ticker = BusyTicker(scope, periodMs = 0, busy = { true }) {
                if (steps.incrementAndGet() == 1) throw IllegalStateException("first step fails")
                throw kotlinx.coroutines.CancellationException("stop")
            }
            ticker.start()
            val deadline = System.currentTimeMillis() + 5_000
            while (steps.get() < 1) { check(System.currentTimeMillis() < deadline); Thread.yield() }
            Thread.sleep(50)
            ticker.start()
            while (steps.get() < 2) { check(System.currentTimeMillis() < deadline) { "not restarted" }; Thread.yield() }
        } finally {
            scope.cancel()
        }
    }
}
