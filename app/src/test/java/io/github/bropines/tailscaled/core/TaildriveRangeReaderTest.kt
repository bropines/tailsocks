package io.github.bropines.tailscaled.core

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The reader behind a share's seekable descriptor: against a server that is an array of
 * bytes, and, for the HTTP half, against one that listens on the loopback.
 */
class TaildriveRangeReaderTest {
    private val block = 1024

    private fun bytes(size: Int, seed: Long = 7): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    /** Small blocks, so that a test reads hundreds of them in no time. */
    private fun file(upstream: RangeUpstream, cacheBlocks: Int = 32, maxReadAhead: Int = 8) =
        RangedFile(upstream, block, cacheBlocks, maxReadAhead)

    /** A server holding [data]: answers ranges, counts them, and misbehaves on request. */
    private class FakeUpstream(private val data: ByteArray) : RangeUpstream {
        /** Every range asked for, as offset and length. */
        val requests = CopyOnWriteArrayList<Pair<Long, Int>>()
        private val number = AtomicInteger()

        /** The request with this number (from 0) fails with this, once. */
        val failures = ConcurrentHashMap<Int, RangeException>()

        /** Replaces the honest answer. */
        @Volatile var answer: ((offset: Long, length: Int) -> RangeChunk)? = null

        /** Every answer takes a moment, so that readers pile up behind a request. */
        @Volatile var slow = false

        /** A fetch waits here until the upstream is closed. */
        @Volatile var hang = false
        val hanging = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val closes = AtomicInteger()

        override fun fetch(offset: Long, length: Int): RangeChunk {
            val n = number.getAndIncrement()
            requests.add(offset to length)
            if (closes.get() > 0) throw RangeIoException("Canceled")
            if (hang) {
                hanging.countDown()
                released.await()
                throw RangeIoException("Canceled")
            }
            failures.remove(n)?.let { throw it }
            if (slow) Thread.sleep(2)
            answer?.let { return it(offset, length) }
            if (offset >= data.size) throw RangePastEndException(data.size.toLong())
            val end = minOf(offset + length, data.size.toLong()).toInt()
            return RangeChunk(data.copyOfRange(offset.toInt(), end), data.size.toLong())
        }

        override fun close() {
            closes.incrementAndGet()
            released.countDown()
        }

        /** How many times each block was asked for. */
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

    private fun RangedFile.readAt(offset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val n = read(offset, buffer, 0, length)
        return buffer.copyOf(n)
    }

    // --- Reading ---------------------------------------------------------------------------------

    @Test
    fun aWholeFileReadInOddPiecesIsTheFile() {
        val data = bytes(40 * block + 333)
        val file = file(FakeUpstream(data))
        assertEquals(data.size.toLong(), file.size)

        val pieces = intArrayOf(1, 7, 1023, 1025, 4099, 13, 2048, 6001)
        val out = ByteArray(data.size + 100)
        var at = 0
        var turn = 0
        while (true) {
            // Into the middle of a larger buffer: the offset in it is honoured.
            val n = file.read(at.toLong(), out, at, minOf(pieces[turn++ % pieces.size], out.size - at))
            if (n == 0) break
            at += n
        }
        assertEquals(data.size, at)
        assertArrayEquals(data, out.copyOf(at))
    }

    @Test
    fun theRealBlockSizeReadsARealFile() {
        val data = bytes(3 * RangedFile.BLOCK_SIZE + 12345)
        val upstream = FakeUpstream(data)
        val file = RangedFile(upstream)
        val out = ByteArray(data.size)
        var at = 0
        while (at < out.size) {
            val n = file.read(at.toLong(), out, at, minOf(128 * 1024, out.size - at))
            assertTrue(n > 0)
            at += n
        }
        assertArrayEquals(data, out)
        // The first block alone, then two, then the short tail.
        assertEquals(
            listOf(0L to RangedFile.BLOCK_SIZE, 262144L to 2 * RangedFile.BLOCK_SIZE, 786432L to 12345),
            upstream.requests.toList()
        )
    }

    @Test
    fun readingOnAsksForSeveralBlocksAtOnce() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        var at = 0L
        while (at < data.size) at += file.readAt(at, 500).size

        // 64 blocks in 11 requests: one, then twice as many each time, up to eight.
        assertEquals(11, upstream.requests.size)
        assertEquals(
            listOf(0L to block, 1024L to 2 * block, 3072L to 4 * block, 7168L to 8 * block, 15360L to 8 * block),
            upstream.requests.take(5)
        )
        assertTrue(upstream.blockCounts(block).values.all { it == 1 })
    }

    @Test
    fun aSeekAsksForOneBlockAndThenTheReadAheadStartsOver() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        var at = 0L
        while (at < 20L * block) at += file.readAt(at, 700).size
        upstream.requests.clear()

        // A jump to the end, as a player does for an index kept there.
        assertArrayEquals(data.copyOfRange(60 * block + 10, 60 * block + 110), file.readAt(60L * block + 10, 100))
        assertEquals(listOf(60L * block to block), upstream.requests.toList())

        // Reading on from there is sequential again: two blocks, then the last one.
        at = 60L * block + 110
        while (at < data.size) at += file.readAt(at, 700).size
        assertEquals(
            listOf(60L * block to block, 61L * block to 2 * block, 63L * block to block),
            upstream.requests.toList()
        )
    }

    /** Sound and picture kept apart in the file: every other read is far from the one before it. */
    @Test
    fun twoPlacesReadInTurnAreBothReadAhead() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        var one = 0L
        var other = 32L * block
        repeat(40) {
            val a = file.readAt(one, 600)
            assertArrayEquals(data.copyOfRange(one.toInt(), one.toInt() + 600), a)
            one += a.size
            val b = file.readAt(other, 600)
            assertArrayEquals(data.copyOfRange(other.toInt(), other.toInt() + 600), b)
            other += b.size
        }
        // 24 blocks from each place, in five requests each.
        assertEquals(10, upstream.requests.size)
        assertTrue(upstream.blockCounts(block).values.all { it == 1 })
    }

    @Test
    fun aReadAcrossBlocksAfterASeekIsOneRequest() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        file.size
        upstream.requests.clear()

        val from = 30 * block + 900
        assertArrayEquals(data.copyOfRange(from, from + 2 * block), file.readAt(from.toLong(), 2 * block))
        assertEquals(listOf(30L * block to 3 * block), upstream.requests.toList())
    }

    @Test
    fun aReadLargerThanTheReadAheadIsSplit() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream, cacheBlocks = 8, maxReadAhead = 4)
        // Larger than the cache as well: every block is copied out before it can be dropped.
        assertArrayEquals(data.copyOfRange(block, 41 * block), file.readAt(block.toLong(), 40 * block))
        assertTrue(upstream.requests.all { it.second <= 4 * block })
    }

    @Test
    fun aSeekBackIntoTheCacheCostsNothing() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        var at = 0L
        while (at < 16L * block) at += file.readAt(at, 4000).size
        val before = upstream.requests.size

        assertArrayEquals(data.copyOfRange(2 * block + 5, 4 * block + 5), file.readAt(2L * block + 5, 2 * block))
        assertArrayEquals(data.copyOfRange(0, 100), file.readAt(0, 100))
        assertEquals(before, upstream.requests.size)
    }

    @Test
    fun aSeekBackPastTheCacheAsksAgain() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream, cacheBlocks = 8, maxReadAhead = 4)
        var at = 0L
        while (at < data.size) at += file.readAt(at, 4000).size
        val before = upstream.requests.size

        // The last eight blocks are kept, the first ones are long gone.
        assertArrayEquals(data.copyOfRange(60 * block, 61 * block), file.readAt(60L * block, block))
        assertEquals(before, upstream.requests.size)
        assertArrayEquals(data.copyOfRange(3, 103), file.readAt(3, 100))
        assertEquals(before + 1, upstream.requests.size)
        assertEquals(0L to block, upstream.requests.last())
    }

    @Test
    fun readAheadStopsAtABlockThatIsAlreadyHere() {
        val data = bytes(64 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        file.readAt(5L * block, 10)
        upstream.requests.clear()

        // The second request would be four blocks; the one already here cuts it to two.
        var at = 0L
        while (at < 5L * block) at += file.readAt(at, 512).size
        assertEquals(listOf(1L * block to 2 * block, 3L * block to 2 * block), upstream.requests.toList())
        assertTrue(upstream.blockCounts(block).values.all { it == 1 })
    }

    // --- The end of the file ---------------------------------------------------------------------

    @Test
    fun atAndPastTheEndIsZeroWithoutARequest() {
        val data = bytes(3 * block + 100)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        assertEquals(data.size.toLong(), file.size)
        assertEquals(1, upstream.requests.size)

        val buffer = ByteArray(64)
        assertEquals(0, file.read(data.size.toLong(), buffer, 0, 64))
        assertEquals(0, file.read(data.size + 1L, buffer, 0, 64))
        assertEquals(0, file.read(Long.MAX_VALUE - 10, buffer, 0, 64))
        assertEquals(0, file.read(0, buffer, 0, 0))
        assertEquals(1, upstream.requests.size)
    }

    @Test
    fun theLastBlockIsShort() {
        val data = bytes(3 * block + 100)
        val upstream = FakeUpstream(data)
        val file = file(upstream)

        // Asked for more than is left: what is left comes back.
        assertArrayEquals(data.copyOfRange(3 * block + 40, data.size), file.readAt(3L * block + 40, 500))
        // The request stops at the end of the file too.
        assertEquals(3L * block to 100, upstream.requests.last())
        assertArrayEquals(data.copyOfRange(2 * block + 1000, data.size), file.readAt(2L * block + 1000, 5000))
    }

    @Test
    fun aFileSmallerThanABlock() {
        val data = bytes(100)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        assertEquals(100L, file.size)
        assertArrayEquals(data, file.readAt(0, block))
        assertArrayEquals(data.copyOfRange(60, 100), file.readAt(60, 4000))
        assertEquals(0, file.readAt(100, 10).size)
        assertEquals(1, upstream.requests.size)
    }

    @Test
    fun aFileOfExactlyOneBlock() {
        val data = bytes(block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        assertArrayEquals(data, file.readAt(0, 2 * block))
        assertEquals(0, file.readAt(block.toLong(), 10).size)
        assertEquals(1, upstream.requests.size)
    }

    /** A 416 naming a length of nothing: how a strict server answers any range of an empty file. */
    @Test
    fun anEmptyFile() {
        val upstream = FakeUpstream(ByteArray(0))
        val file = file(upstream)
        assertEquals(0L, file.size)
        assertEquals(0, file.read(0, ByteArray(10), 0, 10))
        assertEquals(0, file.read(500, ByteArray(10), 0, 10))
        assertEquals(1, upstream.requests.size)
        assertNotNull(RangedFile.open(FakeUpstream(ByteArray(0)), block))
    }

    /** Go's file server answers a range of an empty file with 200 and no body; the client reports it as this. */
    @Test
    fun anEmptyFileFromAServerThatAnswersWithNoBody() {
        val upstream = FakeUpstream(ByteArray(0))
        upstream.answer = { _, _ -> RangeChunk(ByteArray(0), 0) }
        val file = file(upstream)
        assertEquals(0L, file.size)
        assertEquals(0, file.read(0, ByteArray(10), 0, 10))
        assertEquals(1, upstream.requests.size)
    }

    // --- Servers that cannot ---------------------------------------------------------------------

    @Test
    fun aServerWithoutRangesIsNoFile() {
        val upstream = FakeUpstream(bytes(5000))
        upstream.failures[0] = RangeNotSupportedException()
        assertNull(RangedFile.open(upstream, block))
        assertEquals(1, upstream.closes.get())

        val again = FakeUpstream(bytes(5000))
        again.failures[0] = RangeNotSupportedException()
        val file = file(again)
        try {
            file.size
            fail("expected RangeNotSupportedException")
        } catch (e: RangeNotSupportedException) {
        }
    }

    @Test
    fun aLengthTheServerKeepsToItselfIsNoFile() {
        val data = bytes(5000)
        val upstream = FakeUpstream(data)
        upstream.answer = { offset, length -> RangeChunk(data.copyOfRange(offset.toInt(), offset.toInt() + length), -1) }
        assertNull(RangedFile.open(upstream, block))
        assertEquals(1, upstream.closes.get())
    }

    @Test
    fun openingFetchesTheFirstBlockAndNothingElse() {
        val data = bytes(10 * block)
        val upstream = FakeUpstream(data)
        val file = RangedFile.open(upstream, block)!!
        assertEquals(listOf(0L to block), upstream.requests.toList())
        assertEquals(data.size.toLong(), file.size)
        assertArrayEquals(data.copyOf(block), file.readAt(0, block))
        assertEquals(1, upstream.requests.size)
        assertEquals(0, upstream.closes.get())
    }

    @Test
    fun aRefusalAtOpeningIsThrownAndTheUpstreamClosed() {
        for (failure in listOf(RangeForbiddenException(), RangeNotFoundException(), RangeTimeoutException(), RangeIoException("HTTP 500"))) {
            val upstream = FakeUpstream(bytes(5000))
            upstream.failures[0] = failure
            try {
                RangedFile.open(upstream, block)
                fail("expected ${failure.javaClass.simpleName}")
            } catch (e: RangeException) {
                assertEquals(failure.javaClass, e.javaClass)
            }
            assertEquals(1, upstream.closes.get())
        }
    }

    @Test
    fun aFailedFirstAnswerIsAskedForAgain() {
        val data = bytes(5000)
        val upstream = FakeUpstream(data)
        upstream.failures[0] = RangeTimeoutException()
        val file = file(upstream)
        try {
            file.readAt(0, 10)
            fail("expected RangeTimeoutException")
        } catch (e: RangeTimeoutException) {
        }
        assertEquals(5000L, file.size)
        assertArrayEquals(data.copyOf(10), file.readAt(0, 10))
    }

    // --- Failures in the middle ------------------------------------------------------------------

    @Test
    fun anErrorInTheMiddleFailsThatReadAndTheNextOneAsksAgain() {
        val data = bytes(32 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        assertArrayEquals(data.copyOf(block), file.readAt(0, block))

        upstream.failures[1] = RangeTimeoutException()
        val buffer = ByteArray(3 * block)
        try {
            file.read(block.toLong(), buffer, 0, buffer.size)
            fail("expected RangeTimeoutException")
        } catch (e: RangeTimeoutException) {
        }

        assertEquals(buffer.size, file.read(block.toLong(), buffer, 0, buffer.size))
        assertArrayEquals(data.copyOfRange(block, 4 * block), buffer)
        // Before the failure and after the retry: nothing was lost.
        var at = 0L
        val out = ByteArray(data.size)
        while (at < data.size) at += file.read(at, out, at.toInt(), minOf(3000, data.size - at.toInt()))
        assertArrayEquals(data, out)
    }

    @Test
    fun aShortAnswerKeepsItsWholeBlocksOnly() {
        val data = bytes(32 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        file.size
        // The server hands out a block and a half whatever it is asked for.
        upstream.answer = { offset, length ->
            val n = minOf(length, block + block / 2)
            RangeChunk(data.copyOfRange(offset.toInt(), offset.toInt() + n), data.size.toLong())
        }
        assertArrayEquals(data.copyOfRange(block, 6 * block), file.readAt(block.toLong(), 5 * block))

        // Less than a block is no block: an error, not a hole in the file.
        upstream.answer = { offset, _ -> RangeChunk(data.copyOfRange(offset.toInt(), offset.toInt() + 10), data.size.toLong()) }
        try {
            file.readAt(20L * block, 100)
            fail("expected RangeIoException")
        } catch (e: RangeIoException) {
        }
        upstream.answer = null
        assertArrayEquals(data.copyOfRange(20 * block, 20 * block + 100), file.readAt(20L * block, 100))
    }

    @Test
    fun whatTheUpstreamThrowsUntypedIsAnIoError() {
        val data = bytes(8 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        file.size
        upstream.answer = { _, _ -> throw IllegalStateException("http://10.0.0.1/secret") }
        try {
            file.readAt(4L * block, 10)
            fail("expected RangeIoException")
        } catch (e: RangeIoException) {
            // The class, never the message: it may name an address.
            assertEquals("IllegalStateException", e.message)
        }
    }

    // --- Threads ---------------------------------------------------------------------------------

    @Test
    fun readsFromSeveralThreadsAreRightAndFetchEachBlockOnce() {
        val data = bytes(48 * block + 77)
        val upstream = FakeUpstream(data)
        // A cache larger than the file: a second request for a block would be a bug, not an eviction.
        val file = file(upstream, cacheBlocks = 64)
        upstream.slow = true
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val wrong = AtomicReference<String>(null)
        try {
            val jobs = (0 until threads).map { t ->
                pool.submit {
                    val random = Random(100L + t)
                    start.await()
                    try {
                        repeat(400) {
                            // Half of the threads walk the file, the other half jump around it.
                            val offset = if (t % 2 == 0) (it * 997L) % data.size else random.nextInt(data.size + 500).toLong()
                            val length = 1 + random.nextInt(3 * block)
                            val got = file.readAt(offset, length)
                            val end = minOf(offset + length, data.size.toLong()).coerceAtLeast(offset)
                            val want = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset.toInt(), end.toInt())
                            if (!got.contentEquals(want)) wrong.compareAndSet(null, "thread $t at $offset+$length")
                        }
                    } catch (e: Throwable) {
                        wrong.compareAndSet(null, "thread $t: $e")
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertNull(wrong.get())
        val counts = upstream.blockCounts(block)
        assertEquals((0L..48L).toSet(), counts.keys)
        assertTrue("a block was fetched twice: $counts", counts.values.all { it == 1 })
    }

    @Test
    fun closingDuringAFetchCancelsIt() {
        val data = bytes(16 * block)
        val upstream = FakeUpstream(data)
        val file = file(upstream)
        file.size
        upstream.hang = true

        val thrown = AtomicReference<Throwable>(null)
        val done = CountDownLatch(1)
        val reader = Thread {
            try {
                file.readAt(8L * block, 100)
            } catch (e: Throwable) {
                thrown.set(e)
            }
            done.countDown()
        }
        reader.start()
        assertTrue(upstream.hanging.await(10, TimeUnit.SECONDS))

        // Does not wait for the request it cuts short.
        file.close()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue("${thrown.get()}", thrown.get() is RangeClosedException)
        assertEquals(1, upstream.closes.get())

        // Closed stays closed, also for what was in the cache, and asks the server nothing.
        val before = upstream.requests.size
        for (offset in listOf(0L, 8L * block)) {
            try {
                file.readAt(offset, 10)
                fail("expected RangeClosedException")
            } catch (e: RangeClosedException) {
            }
        }
        assertEquals(before, upstream.requests.size)
        file.close()
        assertEquals(1, upstream.closes.get())
    }

    @Test
    fun aReaderWaitingForAnotherOnesFetchIsReleasedByClose() {
        val upstream = FakeUpstream(bytes(16 * block))
        val file = file(upstream)
        file.size
        upstream.hang = true

        val failures = CopyOnWriteArrayList<Throwable>()
        val done = CountDownLatch(2)
        repeat(2) { i ->
            Thread {
                try {
                    file.readAt((4L + 4 * i) * block, 100)
                } catch (e: Throwable) {
                    failures.add(e)
                }
                done.countDown()
            }.start()
        }
        assertTrue(upstream.hanging.await(10, TimeUnit.SECONDS))
        file.close()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(2, failures.size)
        assertTrue(failures.all { it is RangeClosedException })
        // The one that waited never reached the server.
        assertEquals(2, upstream.requests.size)
    }

    // --- Over HTTP -------------------------------------------------------------------------------

    /** What a request said; the header names in lower case. */
    private class Asked(val line: String, val headers: Map<String, String>) {
        /** The two numbers of `Range: bytes=a-b`. */
        val range: Pair<Long, Long>
            get() = headers.getValue("range").removePrefix("bytes=").split("-").let { it[0].toLong() to it[1].toLong() }
    }

    /** An HTTP server on the loopback, one request to a connection; [handle] writes the whole answer. */
    private class TinyServer(private val handle: TinyServer.(Asked, OutputStream) -> Unit) : Closeable {
        val asked = CopyOnWriteArrayList<Asked>()
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()

        /** Opened by [close]: a handler that waits here is a server that never answers. */
        val never = CountDownLatch(1)

        init {
            Thread {
                try {
                    while (true) {
                        val client = listener.accept()
                        clients.add(client)
                        Thread { serve(client) }.apply { isDaemon = true }.start()
                    }
                } catch (e: IOException) {
                    // Closed: the test is over.
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: Socket) {
            try {
                client.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
                    val line = reader.readLine() ?: return
                    val headers = HashMap<String, String>()
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                        headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
                    }
                    val request = Asked(line, headers)
                    asked.add(request)
                    handle(request, it.getOutputStream())
                }
            } catch (e: IOException) {
                // The client went away first.
            }
        }

        /** A path like a real one: a tailnet, a device, a share, and a name with a space in it. */
        val url: URL
            get() = URL("http://127.0.0.1:${listener.localPort}/example.ts.net/nas/Films/Secret%20Film.mkv")

        fun upstream(readTimeoutMs: Int = 5000) =
            HttpRangeUpstream(5000, readTimeoutMs) { url.openConnection(Proxy.NO_PROXY) as HttpURLConnection }

        override fun close() {
            never.countDown()
            listener.close()
            clients.forEach { it.close() }
        }
    }

    /** [declared] is the Content-Length; it differs from the body only for a server that lies. */
    private fun OutputStream.answer(
        status: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
        declared: Int = body.size,
    ) {
        val head = StringBuilder("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: $declared\r\n")
        for ((name, value) in headers) head.append("$name: $value\r\n")
        write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
        write(body)
        flush()
    }

    /** Ranges the way Go's file server, which is what a share is served by, answers them. */
    private fun rangeServer(data: ByteArray) = TinyServer { asked, out ->
        val (first, last) = asked.range
        if (data.isEmpty()) {
            out.answer("200 OK")
        } else if (first >= data.size) {
            out.answer("416 Requested Range Not Satisfiable", mapOf("Content-Range" to "bytes */${data.size}"))
        } else {
            val end = minOf(last, data.size - 1L)
            out.answer(
                "206 Partial Content",
                mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes $first-$end/${data.size}"),
                data.copyOfRange(first.toInt(), end.toInt() + 1)
            )
        }
    }

    private inline fun <reified T : RangeException> expect(body: () -> Unit): T {
        try {
            body()
        } catch (e: RangeException) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got ${e.javaClass.simpleName} (${e.message})")
        }
        throw AssertionError("expected ${T::class.java.simpleName}, got an answer")
    }

    @Test
    fun overHttpARangeIsAskedForByItsBytes() {
        val data = bytes(70_000)
        rangeServer(data).use { server ->
            val upstream = server.upstream()
            val middle = upstream.fetch(30_000, 1000)
            assertArrayEquals(data.copyOfRange(30_000, 31_000), middle.bytes)
            assertEquals(70_000L, middle.total)

            val asked = server.asked.single()
            assertTrue(asked.line, asked.line.startsWith("GET /example.ts.net/nas/Films/Secret%20Film.mkv "))
            assertEquals("bytes=30000-30999", asked.headers["range"])
            // Not gzip: a range of a compressed stream is not a range of the file.
            assertEquals("identity", asked.headers["accept-encoding"])

            // The file ends before the range does.
            val tail = upstream.fetch(69_900, 1000)
            assertArrayEquals(data.copyOfRange(69_900, 70_000), tail.bytes)
            assertEquals(70_000L, tail.total)
        }
    }

    @Test
    fun overHttpAFileIsReadAndSoughtIn() {
        val data = bytes(40 * block + 333)
        rangeServer(data).use { server ->
            val file = RangedFile.open(server.upstream(), block)!!
            assertEquals(data.size.toLong(), file.size)
            // The end first, as for a container with its index there, then all of it.
            assertArrayEquals(data.copyOfRange(data.size - 100, data.size), file.readAt(data.size - 100L, 500))
            val out = ByteArray(data.size)
            var at = 0
            while (at < out.size) at += file.read(at.toLong(), out, at, minOf(3000, out.size - at))
            assertArrayEquals(data, out)
            // 41 blocks: the first, the last, and seven requests of growing size for the rest.
            assertEquals(9, server.asked.size)
            file.close()
        }
    }

    @Test
    fun overHttpAServerThatSendsTheWholeFileIsNoFile() {
        val data = bytes(5000)
        TinyServer { _, out -> out.answer("200 OK", body = data) }.use { server ->
            expect<RangeNotSupportedException> { server.upstream().fetch(0, block) }
            assertNull(RangedFile.open(server.upstream(), block))
        }
    }

    @Test
    fun overHttpAnEmptyFileIsAFile() {
        // Go's: 200 and no body, whatever range was asked for.
        rangeServer(ByteArray(0)).use { server ->
            val file = RangedFile.open(server.upstream(), block)!!
            assertEquals(0L, file.size)
            assertEquals(0, file.read(0, ByteArray(10), 0, 10))
            assertEquals(1, server.asked.size)
        }
        // A stricter server's: no such range in a file of no bytes.
        TinyServer { _, out -> out.answer("416 Requested Range Not Satisfiable", mapOf("Content-Range" to "bytes */0")) }.use { server ->
            assertEquals(0L, RangedFile.open(server.upstream(), block)!!.size)
        }
    }

    @Test
    fun overHttpAStatusIsATypedFailure() {
        val statuses = listOf(
            "403 Forbidden" to RangeForbiddenException::class.java,
            "404 Not Found" to RangeNotFoundException::class.java,
            "410 Gone" to RangeNotFoundException::class.java,
            "504 Gateway Timeout" to RangeTimeoutException::class.java,
            "500 Internal Server Error" to RangeIoException::class.java,
            "502 Bad Gateway" to RangeIoException::class.java,
        )
        for ((status, type) in statuses) {
            // The body of an error may name a path on the sharing device; it is not read.
            TinyServer { _, out -> out.answer(status, body = "open /srv/films: permission denied".toByteArray()) }.use { server ->
                val e = expect<RangeException> { server.upstream().fetch(0, block) }
                assertEquals(status, type, e.javaClass)
                assertFalse(e.message!!.contains("films"))
            }
        }
        rangeServer(bytes(5000)).use { server ->
            assertEquals(5000L, expect<RangePastEndException> { server.upstream().fetch(9000, 100) }.total)
        }
    }

    @Test
    fun overHttpAServerThatNeverAnswersIsATimeout() {
        TinyServer { _, _ -> never.await() }.use { server ->
            expect<RangeTimeoutException> { server.upstream(readTimeoutMs = 200).fetch(0, block) }
        }
    }

    @Test
    fun overHttpClosingCutsAFetchShort() {
        TinyServer { _, _ -> never.await() }.use { server ->
            val upstream = server.upstream(readTimeoutMs = 60_000)
            val thrown = AtomicReference<Throwable>(null)
            val done = CountDownLatch(1)
            Thread {
                try {
                    upstream.fetch(0, block)
                } catch (e: Throwable) {
                    thrown.set(e)
                }
                done.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (server.asked.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, server.asked.size)

            // The request is with the server, which says nothing; a minute of patience is cut short.
            upstream.close()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue("${thrown.get()}", thrown.get() is RangeClosedException)

            expect<RangeClosedException> { upstream.fetch(0, block) }
            assertEquals(1, server.asked.size)
            upstream.close()
        }
    }

    @Test
    fun overHttpABodyCutShortIsAnError() {
        val data = bytes(5000)
        val lying = TinyServer { _, out ->
            out.answer("206 Partial Content", mapOf("Content-Range" to "bytes 0-999/5000"), data.copyOf(300), declared = 1000)
        }
        lying.use { server ->
            expect<RangeIoException> { server.upstream().fetch(0, 1000) }
        }
    }

    @Test
    fun overHttpAnAnswerThatIsNotTheRangeAskedForIsNotTrusted() {
        val data = bytes(5000)
        // The start of the file, whatever was asked for.
        TinyServer { _, out -> out.answer("206 Partial Content", mapOf("Content-Range" to "bytes 0-99/5000"), data.copyOf(100)) }.use { server ->
            expect<RangeNotSupportedException> { server.upstream().fetch(1000, 100) }
        }
        // A part of something, not saying which.
        TinyServer { _, out -> out.answer("206 Partial Content", body = data.copyOf(100)) }.use { server ->
            expect<RangeNotSupportedException> { server.upstream().fetch(0, 100) }
        }
        // The right bytes of a file whose length is kept back: an answer, but no file to seek in.
        val coy = TinyServer { asked, out ->
            val (first, last) = asked.range
            out.answer("206 Partial Content", mapOf("Content-Range" to "bytes $first-$last/*"), data.copyOfRange(first.toInt(), last.toInt() + 1))
        }
        coy.use { server ->
            val chunk = server.upstream().fetch(100, 50)
            assertArrayEquals(data.copyOfRange(100, 150), chunk.bytes)
            assertEquals(-1L, chunk.total)
            assertNull(RangedFile.open(server.upstream(), block))
        }
    }

    @Test
    fun overHttpNoFailureNamesTheFileOrTheAddress() {
        // Nobody listens there: the platform's own message names the host and the port.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val refused = HttpRangeUpstream(2000, 2000) {
            URL("http://127.0.0.1:$port/example.ts.net/nas/Films/Secret%20Film.mkv").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        }
        val e = expect<RangeIoException> { refused.fetch(0, block) }
        assertEquals("ConnectException", e.message)
        assertNull(e.cause)

        val unopened = HttpRangeUpstream { throw MalformedURLException("no protocol: /example.ts.net/nas/Films/Secret Film.mkv") }
        val malformed = expect<RangeIoException> { unopened.fetch(0, block) }
        assertEquals("MalformedURLException", malformed.message)
        assertNull(malformed.cause)
    }

    // --- What the answers mean -------------------------------------------------------------------

    @Test
    fun contentRange() {
        assertEquals(ContentRange(0, 262143, 70881983), ContentRange.parse("bytes 0-262143/70881983"))
        assertEquals(ContentRange(30000000, 30262143, 70881983), ContentRange.parse(" Bytes 30000000-30262143/70881983 "))
        assertEquals(ContentRange(70881883, 70881982, 70881983), ContentRange.parse("bytes 70881883-70881982/70881983"))
        // A length the server keeps to itself, and a 416's "no such range of N bytes".
        assertEquals(ContentRange(0, 99, -1), ContentRange.parse("bytes 0-99/*"))
        assertEquals(ContentRange(-1, -1, 70881983), ContentRange.parse("bytes */70881983"))
        assertEquals(ContentRange(-1, -1, 0), ContentRange.parse("bytes */0"))

        for (bad in listOf(null, "", "bytes", "bytes 0-99", "items 0-99/100", "bytes=0-99/100", "bytes 99-0/100", "bytes 0-100/100", "bytes a-b/c", "bytes -5/100", "bytes 0-99/-1")) {
            assertNull(bad, ContentRange.parse(bad))
        }
    }

    @Test
    fun whatAStatusMeans() {
        assertTrue(rangeFailure(200) is RangeNotSupportedException)
        assertEquals(70881983L, (rangeFailure(416, "bytes */70881983") as RangePastEndException).total)
        assertEquals(-1L, (rangeFailure(416) as RangePastEndException).total)
        assertTrue(rangeFailure(401) is RangeForbiddenException)
        assertTrue(rangeFailure(403) is RangeForbiddenException)
        assertTrue(rangeFailure(404) is RangeNotFoundException)
        assertTrue(rangeFailure(410) is RangeNotFoundException)
        assertTrue(rangeFailure(408) is RangeTimeoutException)
        assertTrue(rangeFailure(504) is RangeTimeoutException)
        for (status in listOf(204, 301, 400, 500, 502, 503)) {
            val e = rangeFailure(status)
            assertTrue(e is RangeIoException)
            assertEquals("HTTP $status", e.message)
        }
    }

    @Test
    fun theErrnoAPlayerIsGiven() {
        assertEquals(RangeErrno.EACCES, rangeErrno(RangeForbiddenException()))
        assertEquals(RangeErrno.ENOENT, rangeErrno(RangeNotFoundException()))
        assertEquals(RangeErrno.ETIMEDOUT, rangeErrno(RangeTimeoutException()))
        assertEquals(RangeErrno.EIO, rangeErrno(RangeIoException("HTTP 500")))
        assertEquals(RangeErrno.EIO, rangeErrno(RangeNotSupportedException()))
        assertEquals(RangeErrno.EIO, rangeErrno(RangePastEndException(10)))
        assertEquals(RangeErrno.EIO, rangeErrno(RangeClosedException()))
        assertEquals(RangeErrno.EIO, rangeErrno(IllegalArgumentException("offset")))
    }
}
