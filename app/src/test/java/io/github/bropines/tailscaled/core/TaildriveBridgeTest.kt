package io.github.bropines.tailscaled.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The app's Taildrive client over the bridge's calls, against a bridge that is a fake. */
class TaildriveBridgeTest {
    private val block = 1024

    private fun bytes(size: Int): ByteArray = ByteArray(size).also { Random(5).nextBytes(it) }

    /** What a call was asked. */
    private data class Asked(val what: String, val path: String, val a: String = "", val b: String = "")

    /**
     * A bridge whose daemon serves [data] at every path the way Go's file server does, unless
     * [answer] says otherwise; it records what was asked.
     */
    private class FakeBridge(private val data: ByteArray = ByteArray(0)) : DriveCalls {
        val asked = CopyOnWriteArrayList<Asked>()
        @Volatile var answer: ((Asked) -> DriveAnswer?)? = null
        /** A range waits here until it is canceled. */
        @Volatile var hang = false
        val hanging = CountDownLatch(1)
        val uploaded = ByteArrayOutputStream()
        @Volatile var uploadStatus = 201
        @Volatile var aborted = false

        override fun newCall(): DriveCalls.Call = object : DriveCalls.Call {
            private val canceled = CountDownLatch(1)

            override fun request(method: String, path: String, depth: String, destination: String, timeoutMs: Int): DriveAnswer {
                val a = Asked(method, path, depth, destination)
                asked.add(a)
                return answer?.invoke(a) ?: DriveAnswer(if (method == "PROPFIND") 207 else 201, body = LISTING.toByteArray())
            }

            override fun range(path: String, offset: Long, length: Int, timeoutMs: Int): DriveAnswer {
                val a = Asked("RANGE", path, offset.toString(), length.toString())
                asked.add(a)
                if (hang) {
                    hanging.countDown()
                    canceled.await(10, TimeUnit.SECONDS)
                    return DriveAnswer(0, fail = DriveAnswer.FAIL_CANCELED)
                }
                answer?.invoke(a)?.let { return it }
                if (data.isEmpty()) return DriveAnswer(200, contentLength = "0")
                if (offset >= data.size) return DriveAnswer(416, contentRange = "bytes */${data.size}")
                val end = minOf(offset + length, data.size.toLong()) - 1
                return DriveAnswer(206, body = data.copyOfRange(offset.toInt(), end.toInt() + 1), contentRange = "bytes $offset-$end/${data.size}")
            }

            override fun open(path: String, timeoutMs: Int): DriveCalls.Stream {
                asked.add(Asked("GET", path))
                var at = 0
                return object : DriveCalls.Stream {
                    override val answer = DriveAnswer(200, contentLength = data.size.toString())
                    override fun read(max: Int): ByteArray {
                        val n = minOf(max, 7000, data.size - at)
                        return data.copyOfRange(at, at + n).also { at += n }
                    }
                    override fun close() {}
                }
            }

            override fun upload(path: String): DriveCalls.Upload {
                asked.add(Asked("PUT", path))
                return object : DriveCalls.Upload {
                    override fun write(bytes: ByteArray) = uploaded.write(bytes)
                    override fun finish(timeoutMs: Int) = DriveAnswer(uploadStatus)
                    override fun abort() {
                        aborted = true
                    }
                }
            }

            override fun cancel() = canceled.countDown()
        }

        companion object {
            const val LISTING = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/t/d/s/a%20b+c.txt</D:href>""" +
                """<D:propstat><D:prop><D:getcontentlength>12</D:getcontentlength></D:prop></D:propstat></D:response></D:multistatus>"""
        }
    }

    private inline fun <reified T : Throwable> expect(body: () -> Unit): T {
        try {
            body()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        fail("expected ${T::class.java.simpleName}, nothing was thrown")
        throw IllegalStateException()
    }

    // --- Ranges ----------------------------------------------------------------------------------

    @Test
    fun aRangeIsAskedForByItsBytes() {
        val data = bytes(70_000)
        val bridge = FakeBridge(data)
        val upstream = BridgeRangeUpstream(bridge, "/t/d/s/Secret Film.mkv")
        val middle = upstream.fetch(30_000, 1000)
        assertArrayEquals(data.copyOfRange(30_000, 31_000), middle.bytes)
        assertEquals(70_000L, middle.total)
        assertEquals(Asked("RANGE", "/t/d/s/Secret Film.mkv", "30000", "1000"), bridge.asked.single())
        // Where the file ends first: the rest of it.
        val tail = upstream.fetch(69_900, 1000)
        assertArrayEquals(data.copyOfRange(69_900, 70_000), tail.bytes)
    }

    @Test
    fun aFileIsReadAndSoughtInThroughTheBridge() {
        val data = bytes(block * 20 + 300)
        val file = RangedFile.open(BridgeRangeUpstream(FakeBridge(data), "/f"), block)!!
        assertEquals(data.size.toLong(), file.size)
        val buffer = ByteArray(500)
        val n = file.read(data.size - 100L, buffer, 0, 500)
        assertArrayEquals(data.copyOfRange(data.size - 100, data.size), buffer.copyOf(n))
        val out = ByteArrayOutputStream()
        var at = 0L
        while (true) {
            val m = file.read(at, buffer, 0, 333)
            if (m == 0) break
            out.write(buffer, 0, m)
            at += m
        }
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun aServerThatSendsTheWholeFileIsNoFile() {
        val bridge = FakeBridge(bytes(5000)).apply { answer = { DriveAnswer(200, contentLength = "5000") } }
        assertNull(RangedFile.open(BridgeRangeUpstream(bridge, "/f"), block))
    }

    @Test
    fun anEmptyFileIsAFile() {
        val file = RangedFile.open(BridgeRangeUpstream(FakeBridge(), "/f"), block)!!
        assertEquals(0L, file.size)
        assertEquals(0, file.read(0, ByteArray(10), 0, 10))
    }

    @Test
    fun aStatusIsATypedFailure() {
        val cases = mapOf(
            401 to RangeForbiddenException::class.java,
            403 to RangeForbiddenException::class.java,
            404 to RangeNotFoundException::class.java,
            504 to RangeTimeoutException::class.java,
            500 to RangeIoException::class.java,
            502 to RangeIoException::class.java,
        )
        for ((status, type) in cases) {
            val e = expect<RangeException> { rangeChunkOf(DriveAnswer(status), 0, 100) }
            assertEquals("HTTP $status", type, e.javaClass)
        }
        // The stale-start retry reads the status off the failure.
        assertTrue(isStaleStart(expect<RangeIoException> { rangeChunkOf(DriveAnswer(503), 0, 100) }))
        assertEquals(5000L, expect<RangePastEndException> { rangeChunkOf(DriveAnswer(416, contentRange = "bytes */5000"), 9000, 100) }.total)
    }

    @Test
    fun theBridgesFailuresAreTyped() {
        expect<RangeTimeoutException> { rangeChunkOf(DriveAnswer(0, fail = DriveAnswer.FAIL_TIMEOUT), 0, 1) }
        expect<RangeClosedException> { rangeChunkOf(DriveAnswer(0, fail = DriveAnswer.FAIL_CANCELED), 0, 1) }
        val noDaemon = expect<RangeIoException> { rangeChunkOf(DriveAnswer(0, fail = DriveAnswer.FAIL_NO_DAEMON), 0, 1) }
        assertFalse(isStaleStart(noDaemon))
        assertEquals("short body", expect<RangeIoException> { rangeChunkOf(DriveAnswer(0, fail = "io", detail = "short body"), 0, 1) }.message)
    }

    @Test
    fun aBodyShorterThanTheRangeItNamesIsAnError() {
        val answer = DriveAnswer(206, body = ByteArray(500), contentRange = "bytes 0-1023/5000")
        assertEquals("short body", expect<RangeIoException> { rangeChunkOf(answer, 0, 1024) }.message)
    }

    @Test
    fun anAnswerThatIsNotTheRangeAskedForIsNotTrusted() {
        expect<RangeNotSupportedException> { rangeChunkOf(DriveAnswer(206, body = ByteArray(10), contentRange = "bytes 0-9/100"), 50, 10) }
        expect<RangeNotSupportedException> { rangeChunkOf(DriveAnswer(206, body = ByteArray(10)), 0, 10) }
        // A length kept back is no length: the file cannot be opened.
        val chunk = rangeChunkOf(DriveAnswer(206, body = ByteArray(50), contentRange = "bytes 100-149/*"), 100, 50)
        assertEquals(-1L, chunk.total)
    }

    @Test
    fun closingCancelsAFetchInFlight() {
        val bridge = FakeBridge(bytes(5000)).apply { hang = true }
        val upstream = BridgeRangeUpstream(bridge, "/f")
        val thrown = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                upstream.fetch(0, 100)
            } catch (e: Throwable) {
                thrown.set(e)
            }
            done.countDown()
        }.start()
        assertTrue(bridge.hanging.await(5, TimeUnit.SECONDS))
        upstream.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("${thrown.get()}", thrown.get() is RangeClosedException)
        // And a later fetch asks nothing.
        expect<RangeClosedException> { upstream.fetch(0, 100) }
        assertEquals(1, bridge.asked.size)
    }

    @Test
    fun noFailureNamesTheFile() {
        val path = "/example.ts.net/nas/Films/Secret Film.mkv"
        for (answer in listOf(
            DriveAnswer(0, fail = "io", detail = "*net.OpError"),
            DriveAnswer(0, fail = DriveAnswer.FAIL_NO_DAEMON),
            DriveAnswer(500, body = "no file at $path".toByteArray()),
        )) {
            val e = expect<RangeException> { rangeChunkOf(answer, 0, 1) }
            assertFalse(e.message!!.contains("Secret"))
            assertNull(e.cause)
        }
    }

    // --- The client ------------------------------------------------------------------------------

    @Test
    fun aListingAsksForDepthOneAtASlashedPath() {
        val bridge = FakeBridge()
        // The listing's XML is read by Android's parser, which a JVM test does not have.
        runCatching { TaildriveClient(bridge).list("t/d/s") }
        assertEquals(Asked("PROPFIND", "/t/d/s", "1", ""), bridge.asked.single())
    }

    @Test
    fun theClientsFailuresAreWhatTheBrowserTellsApart() {
        val bridge = FakeBridge()
        val client = TaildriveClient(bridge)
        bridge.answer = { DriveAnswer(0, fail = DriveAnswer.FAIL_NO_DAEMON) }
        expect<ConnectException> { client.list("/") }
        bridge.answer = { DriveAnswer(0, fail = DriveAnswer.FAIL_TIMEOUT) }
        expect<SocketTimeoutException> { client.list("/") }
        bridge.answer = { DriveAnswer(0, fail = DriveAnswer.FAIL_CANCELED) }
        expect<InterruptedIOException> { client.list("/") }
        bridge.answer = { DriveAnswer(0, fail = "io", detail = "no answer") }
        assertEquals("Taildrive: no answer", expect<IOException> { client.list("/") }.message)
        bridge.answer = { DriveAnswer(502, body = "\n  unable to reach the device\nmore".toByteArray()) }
        val e = expect<WebDavHttpException> { client.list("/") }
        assertEquals(502, e.code)
        assertEquals("unable to reach the device", e.body)
        // What the browser makes of them.
        bridge.answer = { DriveAnswer(0, fail = DriveAnswer.FAIL_NO_DAEMON) }
        assertEquals(DriveError.NotConnected, DriveError.from(runCatching { client.list("/") }.exceptionOrNull()!!))
        bridge.answer = { DriveAnswer(0, fail = DriveAnswer.FAIL_TIMEOUT) }
        assertEquals(DriveError.Timeout, DriveError.from(runCatching { client.list("/") }.exceptionOrNull()!!))
    }

    @Test
    fun moveNamesItsDestination() {
        val bridge = FakeBridge()
        TaildriveClient(bridge).move("/t/d/s/old.txt", "t/d/s/new.txt")
        assertEquals(Asked("MOVE", "/t/d/s/old.txt", "", "/t/d/s/new.txt"), bridge.asked.single())
        bridge.answer = { DriveAnswer(403) }
        expect<Exception> { TaildriveClient(bridge).move("/a", "/b") }
    }

    @Test
    fun aWholeFileStreamsInPieces() {
        val data = bytes(50_000)
        val input = TaildriveClient(FakeBridge(data)).getDownloadStream("/f")
        assertArrayEquals(data, input.readBytes())
        assertEquals(-1, input.read())
    }

    @Test
    fun anUploadIsWrittenAndItsAnswerChecked() {
        val bridge = FakeBridge()
        val data = bytes(100_000)
        TaildriveClient(bridge).getUploadStream("/t/d/s/new.bin").use { out ->
            out.write(data, 0, 40_000)
            out.write(data, 40_000, 60_000)
        }
        assertArrayEquals(data, bridge.uploaded.toByteArray())
        bridge.uploadStatus = 507
        val refused = TaildriveClient(bridge).getUploadStream("/x")
        refused.write(byteArrayOf(1, 2, 3))
        assertEquals(507, expect<WebDavHttpException> { refused.close() }.code)
        // An upload given up is not finished.
        val given = TaildriveClient(bridge).getUploadStream("/y")
        given.abort()
        assertTrue(bridge.aborted)
        given.close()
    }
}
