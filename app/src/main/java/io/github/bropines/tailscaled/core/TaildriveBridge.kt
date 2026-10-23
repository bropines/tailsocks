package io.github.bropines.tailscaled.core

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/*
 * Taildrive as calls into the bridge (appctr/drive_client.go): the bridge makes each WebDAV
 * request over the daemon's Taildrive socket with one pool of kept-alive connections, and the
 * app speaks no HTTP — no port, no proxy, no password in between. What the bridge answers is
 * mapped onto the errors the rest of the app already tells apart: a refused connection for no
 * daemon, a timeout, an HTTP status.
 *
 * The calls sit behind [DriveCalls] so that everything but the bridge itself runs in a unit
 * test. Nothing here puts a path into a message.
 */

/** What one request answered: a status, or [fail] — one of the bridge's words — when none came. */
class DriveAnswer(
    val status: Int,
    val fail: String = "",
    val detail: String = "",
    val body: ByteArray = ByteArray(0),
    val contentRange: String? = null,
    val contentLength: String? = null,
) {
    /** The first line of the body, for an error message: short, and never a path of ours. */
    fun firstLine(): String = body.toString(Charsets.UTF_8).lineSequence()
        .firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""

    /** Throws what the app already makes of such a failure; returns for an answer. */
    fun throwIfFailed() {
        when (fail) {
            "" -> return
            FAIL_TIMEOUT -> throw SocketTimeoutException("Taildrive: timed out")
            // What the SOCKS5 port gave for a daemon that was not running; TaildriveBrowse shows "not connected".
            FAIL_NO_DAEMON -> throw ConnectException("Taildrive: the daemon is not running")
            FAIL_CANCELED -> throw InterruptedIOException("Taildrive: canceled")
            else -> throw IOException("Taildrive: ${detail.ifEmpty { fail }}")
        }
    }

    companion object {
        /** The words of DriveResult.Fail in appctr/drive_client.go. */
        const val FAIL_TIMEOUT = "timeout"
        const val FAIL_NO_DAEMON = "no-daemon"
        const val FAIL_CANCELED = "canceled"
    }
}

/** The bridge's calls, one [Call] per request or stream. */
interface DriveCalls {
    fun newCall(): Call

    interface Call {
        /** One request with no body, its answer read whole (PROPFIND, OPTIONS, DELETE, MKCOL, MOVE, an empty PUT). */
        fun request(method: String, path: String, depth: String, destination: String, timeoutMs: Int): DriveAnswer

        /** [length] bytes of [path] from [offset]; the bytes are the body of a 206. */
        fun range(path: String, offset: Long, length: Int, timeoutMs: Int): DriveAnswer

        /** A GET of the whole of [path], to read a piece at a time. */
        fun open(path: String, timeoutMs: Int): Stream

        /** A PUT of [path], its body written a piece at a time. */
        fun upload(path: String): Upload

        /** Ends the request in flight and every later one, from any thread. */
        fun cancel()
    }

    interface Stream {
        val answer: DriveAnswer

        /** Up to [max] bytes; empty at the end. */
        fun read(max: Int): ByteArray
        fun close()
    }

    interface Upload {
        fun write(bytes: ByteArray)
        fun finish(timeoutMs: Int): DriveAnswer
        fun abort()
    }
}

/** The real calls: appctr.DriveCall in the bridge. */
object GoDriveCalls : DriveCalls {
    override fun newCall(): DriveCalls.Call = GoCall(appctr.Appctr.newDriveCall())

    private fun answerOf(r: appctr.DriveResult): DriveAnswer = DriveAnswer(
        status = r.status.toInt(),
        fail = r.fail.orEmpty(),
        detail = r.detail.orEmpty(),
        body = r.body ?: ByteArray(0),
        contentRange = r.contentRange?.ifEmpty { null },
        contentLength = r.contentLength?.ifEmpty { null },
    )

    private class GoCall(private val call: appctr.DriveCall) : DriveCalls.Call {
        override fun request(method: String, path: String, depth: String, destination: String, timeoutMs: Int) =
            answerOf(call.request(method, path, depth, destination, timeoutMs.toLong()))

        override fun range(path: String, offset: Long, length: Int, timeoutMs: Int) =
            answerOf(call.range(path, offset, length.toLong(), timeoutMs.toLong()))

        override fun open(path: String, timeoutMs: Int): DriveCalls.Stream {
            val stream = call.open(path, timeoutMs.toLong())
            return object : DriveCalls.Stream {
                override val answer = answerOf(stream.result)
                override fun read(max: Int): ByteArray = try {
                    stream.read(max.toLong()) ?: ByteArray(0)
                } catch (e: Exception) {
                    throw IOException(e.message ?: "Taildrive: read failed")
                }
                override fun close() = stream.close()
            }
        }

        override fun upload(path: String): DriveCalls.Upload {
            val upload = call.upload(path)
            return object : DriveCalls.Upload {
                override fun write(bytes: ByteArray) = try {
                    upload.write(bytes)
                } catch (e: Exception) {
                    throw IOException(e.message ?: "Taildrive: write failed")
                }
                override fun finish(timeoutMs: Int) = answerOf(upload.finish(timeoutMs.toLong()))
                override fun abort() = upload.abort()
            }
        }

        override fun cancel() = call.cancel()
    }
}

// ---------------------------------------------------------------------------------------------
// Ranges
// ---------------------------------------------------------------------------------------------

/**
 * [answer], to a request for [length] bytes from [offset], as [RangeUpstream.fetch] returns or
 * throws it: the bytes of a 206 that is the range asked for, an empty file, or the typed failure.
 */
fun rangeChunkOf(answer: DriveAnswer, offset: Long, length: Int): RangeChunk {
    when (answer.fail) {
        "" -> Unit
        DriveAnswer.FAIL_TIMEOUT -> throw RangeTimeoutException()
        DriveAnswer.FAIL_CANCELED -> throw RangeClosedException()
        DriveAnswer.FAIL_NO_DAEMON -> throw RangeIoException("no daemon")
        else -> throw RangeIoException(answer.detail.ifEmpty { answer.fail })
    }
    if (answer.status != 206) {
        // Go's file server answers a range of an empty file with 200 and no body: that is the
        // whole file, not a server without ranges.
        if (answer.status == 200 && answer.contentLength?.trim() == "0") return RangeChunk(ByteArray(0), 0)
        throw rangeFailure(answer.status, answer.contentRange)
    }
    val range = ContentRange.parse(answer.contentRange)
    // A 206 for another range, or for one it does not name: not a server to seek on.
    if (range == null || range.first != offset) throw RangeNotSupportedException()
    val expected = min(length.toLong(), range.last - range.first + 1).toInt()
    if (answer.body.size < expected) throw RangeIoException("short body")
    val bytes = if (answer.body.size == expected) answer.body else answer.body.copyOf(expected)
    return RangeChunk(bytes, range.total)
}

/**
 * [path] read a range at a time through [calls]. [close] cancels the requests in flight, so a
 * fetch waiting on the network returns at once, and fails every later one.
 */
class BridgeRangeUpstream(
    private val calls: DriveCalls,
    private val path: String,
    private val timeoutMs: Int = RANGE_TIMEOUT_MS,
) : RangeUpstream {
    private val inFlight = ConcurrentHashMap.newKeySet<DriveCalls.Call>()

    @Volatile
    private var closed = false

    override fun fetch(offset: Long, length: Int): RangeChunk {
        if (closed) throw RangeClosedException()
        val call = try {
            calls.newCall()
        } catch (e: Throwable) {
            // No bridge to call: a build without it, or a JVM test.
            throw RangeIoException(e.javaClass.simpleName)
        }
        inFlight.add(call)
        try {
            // close() may have looked through inFlight just before this call was in it.
            if (closed) throw RangeClosedException()
            val answer = call.range(path, offset, length, timeoutMs)
            if (closed) throw RangeClosedException()
            return rangeChunkOf(answer, offset, length)
        } finally {
            inFlight.remove(call)
        }
    }

    override fun close() {
        closed = true
        inFlight.forEach { it.cancel() }
    }

    companion object {
        /** A range is at most a few megabytes; a minute covers one over a slow relay. */
        const val RANGE_TIMEOUT_MS = 60_000
    }
}

// ---------------------------------------------------------------------------------------------
// Streams
// ---------------------------------------------------------------------------------------------

/** A whole file's body as an InputStream. */
class BridgeInputStream(private val stream: DriveCalls.Stream) : InputStream() {
    private var piece = ByteArray(0)
    private var at = 0
    private var ended = false

    private fun fill(): Boolean {
        if (at < piece.size) return true
        if (ended) return false
        piece = stream.read(PIECE)
        at = 0
        if (piece.isEmpty()) ended = true
        return !ended
    }

    override fun read(): Int = if (fill()) piece[at++].toInt() and 0xff else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = min(len, piece.size - at)
        System.arraycopy(piece, at, b, off, n)
        at += n
        return n
    }

    override fun close() = stream.close()

    private companion object {
        const val PIECE = 256 * 1024
    }
}

/**
 * The body of a PUT as an OutputStream. [close] ends it and waits for the answer: a refusal is
 * thrown there, where the copy that wrote the file can still report it.
 */
class BridgeOutputStream(private val upload: DriveCalls.Upload, private val timeoutMs: Int = 120_000) : OutputStream() {
    private var done = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (done) throw IOException("Taildrive: the upload is over")
        if (len > 0) upload.write(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len))
    }

    override fun close() {
        if (done) return
        done = true
        val answer = upload.finish(timeoutMs)
        answer.throwIfFailed()
        if (answer.status != 200 && answer.status != 201 && answer.status != 204) {
            throw WebDavHttpException("PUT", answer.status, answer.firstLine())
        }
    }

    /** Gives the upload up without finishing it. */
    fun abort() {
        if (done) return
        done = true
        upload.abort()
    }
}
