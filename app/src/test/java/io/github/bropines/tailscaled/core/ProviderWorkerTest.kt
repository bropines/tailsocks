package io.github.bropines.tailscaled.core

import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Where the documents provider does its waiting: a thread of its own, so that the binder
 * thread, which carries the caller's StrictMode policy, never touches the network.
 */
class ProviderWorkerTest {
    @Test
    fun theBodyRunsOnAThreadOfItsOwnAndTheAnswerComesBack() {
        val ranOn = AtomicReference<Thread>()
        val answer = ProviderWorker.run {
            ranOn.set(Thread.currentThread())
            42
        }
        assertEquals(42, answer)
        assertNotSame(Thread.currentThread(), ranOn.get())
        assertEquals("taildrive-provider", ranOn.get().name)
        assertTrue(ranOn.get().isDaemon)
    }

    @Test
    fun whatTheBodyThrowsIsThrownOnTheCallingThreadAsItWas() {
        val thrown = listOf(
            FileNotFoundException("no such document"),
            IllegalStateException("state"),
            WebDavHttpException("PROPFIND", 502, ""),
            RangeTimeoutException(),
        )
        for (failure in thrown) {
            try {
                ProviderWorker.run<Unit> { throw failure }
                fail("expected ${failure.javaClass.simpleName}")
            } catch (e: Exception) {
                assertSame(failure, e)
            }
        }
    }

    /**
     * The cause of the report, as far as a unit test can have it. A binder thread serving a
     * caller on its main thread forbids the network, and the first socket throws; the same
     * request on a thread of ours does not. The "network" here is a function that throws on the
     * one thread that is forbidden it, as StrictMode's death-on-network does on the thread
     * it was handed to.
     */
    @Test
    fun aCallerWhoseThreadForbidsTheNetworkStillGetsTheAnswer() {
        val forbidden = Thread.currentThread()
        fun network(): String {
            if (Thread.currentThread() === forbidden) throw android.os.NetworkOnMainThreadException()
            return "207 Multi-Status"
        }
        try {
            network()
            fail("the calling thread was meant to be refused")
        } catch (e: android.os.NetworkOnMainThreadException) {
        }
        assertEquals("207 Multi-Status", ProviderWorker.run { network() })
    }

    @Test
    fun anInterruptedWaitCancelsTheWorkAndIsAnInterruptedIoException() {
        val started = CountDownLatch(1)
        val bodyInterrupted = CountDownLatch(1)
        val outcome = AtomicReference<Throwable>()
        val flagKept = AtomicReference<Boolean>()
        val waiter = Thread {
            try {
                ProviderWorker.run {
                    started.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (e: InterruptedException) {
                        bodyInterrupted.countDown()
                        throw e
                    }
                }
            } catch (e: Throwable) {
                outcome.set(e)
                flagKept.set(Thread.currentThread().isInterrupted)
            }
        }
        waiter.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        waiter.interrupt()
        waiter.join(5000)
        assertNotNull(outcome.get())
        assertTrue(outcome.get() is InterruptedIOException)
        assertEquals(true, flagKept.get())
        assertTrue("the work was cancelled", bodyInterrupted.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun severalCallsWaitAtOnceWithoutHoldingEachOther() {
        val inside = CountDownLatch(3)
        val release = CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val callers = (1..3).map { n ->
            Thread {
                results.add(ProviderWorker.run {
                    inside.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    n
                })
            }.also { it.start() }
        }
        assertTrue("all three are in the pool at once", inside.await(5, TimeUnit.SECONDS))
        release.countDown()
        callers.forEach { it.join(5000) }
        assertEquals(setOf(1, 2, 3), results.toSet())
    }
}
