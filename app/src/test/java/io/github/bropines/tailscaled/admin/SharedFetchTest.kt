package io.github.bropines.tailscaled.admin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** PeerVersionSource's cache: one fetch for callers that arrive together, nobody parked behind a lock. */
class SharedFetchTest {

    @Test
    fun callersThatArriveDuringAFetchWaitForThatOne() = runBlocking {
        val fetches = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val f = SharedFetch<String, List<String>>(okTtlMs = 60_000, failedTtlMs = 1_000, failed = emptyList()) {
            fetches.incrementAndGet()
            release.await() // a slow API
            listOf("$it-device")
        }
        val a = async(Dispatchers.IO) { f.get("p1") }
        val b = async(Dispatchers.IO) { f.get("p1") }
        // While the network is silent, another key is not stuck behind the first fetch.
        while (fetches.get() == 0) yield()
        val other = SharedFetch<String, String>(60_000, 1_000, "failed") { "x" }
        assertEquals("x", withTimeout(5_000) { other.get("p2") })
        release.complete(Unit)
        assertEquals(listOf("p1-device"), a.await())
        assertEquals(listOf("p1-device"), b.await())
        assertEquals("two callers, one request", 1, fetches.get())
        assertEquals("then from memory", listOf("p1-device"), f.get("p1"))
        assertEquals(1, fetches.get())
    }

    @Test
    fun aFailureStandsForItsOwnTimeAndAnotherKeyAsksAgain() = runBlocking {
        var now = 0L
        val fetches = AtomicInteger()
        var answer: String? = null
        val f = SharedFetch<String, String>(okTtlMs = 300_000, failedTtlMs = 60_000, failed = "none", clock = { now }) {
            fetches.incrementAndGet()
            answer
        }
        assertEquals("none", f.get("p1"))
        answer = "v1"
        now = 59_000
        assertEquals("a failure is remembered for a minute", "none", f.get("p1"))
        assertEquals(1, fetches.get())
        now = 61_000
        assertEquals("v1", f.get("p1"))
        assertEquals("an edited profile is another key", "v1", f.get("p1-edited"))
        assertEquals(3, fetches.get())
    }

    @Test
    fun aThrowingFetchIsAFailureNotAnError() = runBlocking {
        val f = SharedFetch<String, Int>(60_000, 1_000, failed = -1) { throw java.io.IOException("unreachable") }
        assertEquals(-1, f.get("p"))
    }
}
