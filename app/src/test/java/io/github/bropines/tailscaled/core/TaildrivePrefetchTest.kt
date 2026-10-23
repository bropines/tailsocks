package io.github.bropines.tailscaled.core

import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background read-ahead of a [RangedFile] opened to be read through. */
class TaildrivePrefetchTest {
    private val block = 1024

    private fun bytes(size: Int): ByteArray = ByteArray(size).also { Random(11).nextBytes(it) }

    /** Answers ranges of [data], records them, and fails or dawdles on request. */
    private class Upstream(private val data: ByteArray) : RangeUpstream {
        val requests = CopyOnWriteArrayList<Pair<Long, Int>>()
        val fetching = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        @Volatile var delayMs = 0L
        @Volatile var failNext = 0
        @Volatile var closed = false

        override fun fetch(offset: Long, length: Int): RangeChunk {
            val now = fetching.incrementAndGet()
            mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                requests.add(offset to length)
                if (closed) throw RangeIoException("Canceled")
                if (failNext > 0) {
                    failNext--
                    throw RangeIoException("HTTP 502", 502)
                }
                if (delayMs > 0) Thread.sleep(delayMs)
                val end = minOf(offset + length, data.size.toLong()).toInt()
                return RangeChunk(data.copyOfRange(offset.toInt(), end), data.size.toLong())
            } finally {
                fetching.decrementAndGet()
            }
        }

        override fun close() {
            closed = true
        }

        fun blockCounts(block: Int): Map<Long, Int> {
            val counts = HashMap<Long, Int>()
            for ((offset, length) in requests) {
                var index = offset / block
                while (index * block < offset + length) {
                    counts[index] = (counts[index] ?: 0) + 1
                    index++
                }
            }
            return counts
        }
    }

    /** Runs a prefetch at once, on the reading thread: the order of requests is fixed. */
    private val inline = Executor { it.run() }

    private fun RangedFile.readAt(offset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        return buffer.copyOf(read(offset, buffer, 0, length))
    }

    @Test
    fun aReaderReadingOnFindsTheNextBlocksAlreadyFetched() {
        val data = bytes(block * 40)
        val upstream = Upstream(data)
        val file = RangedFile.open(upstream, block, 32, 8, inline)!!
        assertEquals(listOf(0L to block), upstream.requests)

        // The first read: block 0 is here; blocks 1-2 are fetched behind it.
        assertArrayEquals(data.copyOfRange(0, block), file.readAt(0, block))
        assertEquals(0L + block to 2 * block, upstream.requests.last())

        // Reading on: every block is in memory before it is read, the runs double up to 8.
        var asked = upstream.requests.size
        for (i in 1 until 40) {
            val before = upstream.requests.size
            assertArrayEquals(data.copyOfRange(i * block, (i + 1) * block), file.readAt(i.toLong() * block, block))
            // Whatever this read asked for is ahead of it, never the block it read.
            upstream.requests.drop(before).forEach { (offset, _) -> assertTrue("block $i waited for itself", offset > i.toLong() * block) }
            asked = upstream.requests.size
        }
        assertTrue(upstream.requests.drop(1).map { it.second }.contains(8 * block))
        assertTrue(upstream.blockCounts(block).values.all { it == 1 })
        assertTrue(asked < 40)
    }

    @Test
    fun aSeekStartsNoPrefetch() {
        val data = bytes(block * 64)
        val upstream = Upstream(data)
        val file = RangedFile.open(upstream, block, 32, 8, inline)!!
        file.readAt(30L * block, 10)
        file.readAt(50L * block, 10)
        file.readAt(10L * block, 10)
        assertEquals(listOf(0L to block, 30L * block to block, 50L * block to block, 10L * block to block), upstream.requests)
    }

    @Test
    fun theLastBlocksAreNotAskedForTwiceOrPastTheEnd() {
        val data = bytes(block * 5 + 100)
        val upstream = Upstream(data)
        val file = RangedFile.open(upstream, block, 32, 8, inline)!!
        val out = java.io.ByteArrayOutputStream()
        var at = 0L
        while (true) {
            val chunk = file.readAt(at, 300)
            if (chunk.isEmpty()) break
            out.write(chunk)
            at += chunk.size
        }
        assertArrayEquals(data, out.toByteArray())
        upstream.requests.forEach { (offset, length) -> assertTrue(offset + length <= data.size) }
        assertTrue(upstream.blockCounts(block).values.all { it == 1 })
    }

    @Test
    fun aFailedPrefetchLeavesTheBlocksToTheRead() {
        val data = bytes(block * 20)
        val upstream = Upstream(data)
        val file = RangedFile.open(upstream, block, 32, 8, inline)!!
        upstream.failNext = 1
        // The prefetch behind this read fails, quietly.
        assertArrayEquals(data.copyOfRange(0, block), file.readAt(0, block))
        // The next read asks for its block itself, and gets it.
        assertArrayEquals(data.copyOfRange(block, 2 * block), file.readAt(block.toLong(), block))
    }

    @Test
    fun withARealPrefetcherTheFileIsRightAndEachBlockFetchedOnce() {
        val data = bytes(block * 300 + 17)
        val upstream = Upstream(data).apply { delayMs = 2 }
        val pool = Executors.newCachedThreadPool()
        try {
            val file = RangedFile.open(upstream, block, 32, 8, pool)!!
            val out = java.io.ByteArrayOutputStream()
            var at = 0L
            while (true) {
                val chunk = file.readAt(at, 700)
                if (chunk.isEmpty()) break
                out.write(chunk)
                at += chunk.size
            }
            assertArrayEquals(data, out.toByteArray())
            assertTrue(upstream.blockCounts(block).values.all { it == 1 })
            assertEquals("never two requests at once", 1, upstream.mostAtOnce.get())
            file.close()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun closingStopsAPrefetchAndReadsAfterIt() {
        val data = bytes(block * 50)
        val upstream = Upstream(data)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val slowStart = Executor { task -> pool.execute { started.countDown(); gate.await(5, TimeUnit.SECONDS); task.run() } }
            val file = RangedFile.open(upstream, block, 32, 8, slowStart)!!
            file.readAt(0, block)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            file.close()
            gate.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            // The prefetch found the file closed and asked for nothing.
            assertEquals(1, upstream.requests.size)
            try {
                file.readAt(block.toLong(), block)
                throw AssertionError("a closed file read")
            } catch (_: RangeClosedException) {
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
