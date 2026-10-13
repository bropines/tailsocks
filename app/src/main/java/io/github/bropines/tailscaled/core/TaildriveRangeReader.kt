package io.github.bropines.tailscaled.core

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/*
 * A file on another device's Taildrive share, readable at any offset: what stands between
 * a descriptor a player seeks in (TaildriveDescriptor) and a server that answers HTTP byte
 * ranges (TaildriveClient.rangeUpstream). No android.* here, so all of it runs in a unit
 * test.
 *
 * A request costs a trip through the daemon's SOCKS5 port and over the tailnet, and a
 * player probing a container asks for a few bytes here and a few there. So the file is cut
 * into fixed blocks, no request is smaller than a block, the blocks read last stay in
 * memory, and a read that continues the previous one asks for several blocks at once.
 *
 * Nothing here is told the file's name or address, and no message carries one.
 */

// ---------------------------------------------------------------------------------------------
// The server's side
// ---------------------------------------------------------------------------------------------

/** The bytes of one answer and the file's length as that answer stated it; -1 when it did not. */
class RangeChunk(val bytes: ByteArray, val total: Long)

/**
 * Where the bytes come from. [RangedFile] asks for one range at a time; [close] may arrive
 * from any thread while a [fetch] is waiting.
 */
interface RangeUpstream : Closeable {
    /**
     * Blocks for [length] bytes starting at [offset]. Fewer come back only when the file
     * ends first.
     */
    @Throws(RangeException::class)
    fun fetch(offset: Long, length: Int): RangeChunk

    /** Aborts a fetch in flight and fails every later one. Safe to call twice. */
    override fun close()
}

/** Why a range could not be read. The messages are fixed words and a status, never a path. */
sealed class RangeException(message: String) : IOException(message)

/** The server sent the whole file instead of the range, or did not say how long the file is. */
class RangeNotSupportedException : RangeException("byte ranges are not supported")

/** The range starts at or after the end; [total] is the length the server named, -1 if none. */
class RangePastEndException(val total: Long) : RangeException("the range starts past the end")

class RangeNotFoundException : RangeException("no such file")

class RangeForbiddenException : RangeException("access denied")

class RangeTimeoutException : RangeException("timed out")

/** Everything else: [detail] is an HTTP status or the class of what was thrown; [status] is the HTTP status, 0 when there was none. */
class RangeIoException(detail: String, val status: Int = 0) : RangeException(detail)

/** The file was closed, before the read or under it. */
class RangeClosedException : RangeException("closed")

/**
 * A `Content-Range` header, `bytes 0-262143/70881983`. The length is an asterisk when the
 * server keeps it to itself, and the range is one on a 416; -1 stands for either.
 */
data class ContentRange(val first: Long, val last: Long, val total: Long) {
    companion object {
        /** Null for anything that is not a byte range a client can rely on. */
        fun parse(header: String?): ContentRange? {
            val value = header?.trim() ?: return null
            if (!value.startsWith("bytes", ignoreCase = true)) return null
            val spec = value.substring(5)
            if (spec.isEmpty() || !spec[0].isWhitespace()) return null
            val slash = spec.indexOf('/')
            if (slash < 0) return null
            val range = spec.substring(0, slash).trim()
            val length = spec.substring(slash + 1).trim()
            val total = if (length == "*") -1L else length.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (range == "*") return ContentRange(-1, -1, total)
            val dash = range.indexOf('-')
            if (dash <= 0) return null
            val first = range.substring(0, dash).trim().toLongOrNull() ?: return null
            val last = range.substring(dash + 1).trim().toLongOrNull() ?: return null
            if (first < 0 || last < first || (total >= 0 && last >= total)) return null
            return ContentRange(first, last, total)
        }
    }
}

/**
 * What an HTTP status other than 206 means for a ranged GET. 200 is a server that ignored
 * the `Range` header; 416 names the real length in [contentRange].
 */
fun rangeFailure(status: Int, contentRange: String? = null): RangeException = when (status) {
    200 -> RangeNotSupportedException()
    416 -> RangePastEndException(ContentRange.parse(contentRange)?.total ?: -1)
    401, 403 -> RangeForbiddenException()
    404, 410 -> RangeNotFoundException()
    408, 504 -> RangeTimeoutException()
    else -> RangeIoException("HTTP $status", status)
}

/**
 * Whether [e], the answer to the first request of an open, is what a connection that died while
 * it sat idle gives: our own daemon's reverse proxy answering 500, 502 or 503 for it, as the
 * listing has long known (`TaildriveBrowse.isStale`). Measured with the sharing device's daemon
 * restarted a moment before: the first request got 500, and so did the pipe's, a tenth of a
 * second later; the one after a pause worked. A player that is handed the pipe then fails on its
 * first open and plays on the second.
 */
fun isStaleStart(e: RangeException): Boolean = e is RangeIoException && e.status in STALE_STATUSES

private val STALE_STATUSES = setOf(500, 502, 503)

/** How long the quiet second ask of [openRangedFile] waits: the listing's own pause (`TaildriveBrowse.RETRY_DELAY_MS`). */
const val STALE_RETRY_DELAY_MS = 1_200L

/**
 * [RangedFile.open] over an upstream from [newUpstream], and over a second one, after
 * [retryDelayMs], when the first answer was [isStaleStart]. Nothing else is asked twice: a
 * server that has no ranges, a file that is not there, a refusal and a timeout (which has
 * already waited long enough) are the first answer's. Whenever no file comes back, every
 * upstream made here has been closed.
 */
@Throws(RangeException::class)
fun openRangedFile(
    newUpstream: () -> RangeUpstream,
    retryDelayMs: Long = STALE_RETRY_DELAY_MS,
    pause: (Long) -> Unit = { Thread.sleep(it) },
): RangedFile? {
    try {
        return RangedFile.open(newUpstream())
    } catch (e: RangeException) {
        if (!isStaleStart(e)) throw e
    }
    try {
        pause(retryDelayMs)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw RangeClosedException()
    }
    return RangedFile.open(newUpstream())
}

/**
 * A server reached over HTTP: one GET with a `Range` header per fetch, on a connection
 * that [connect] opens to the file, through whatever proxy leads there, and has not sent
 * yet.
 *
 * The URL names somebody's file and the platform's messages name hosts and ports, so what
 * is thrown from here carries an HTTP status or the class of the failure, never its
 * message and never its cause.
 */
class HttpRangeUpstream(
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 15000,
    private val connect: () -> HttpURLConnection,
) : RangeUpstream {
    private val inFlight = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    @Volatile
    private var closed = false

    override fun fetch(offset: Long, length: Int): RangeChunk {
        var conn: HttpURLConnection? = null
        try {
            if (closed) throw RangeClosedException()
            conn = connect()
            inFlight.add(conn)
            // close() may have looked through inFlight just before this connection was in it.
            if (closed) throw RangeClosedException()
            conn.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            // Left alone, Android asks for gzip and inflates the answer itself; a range of
            // a compressed stream is not a range of the file.
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            val status = conn.responseCode
            if (closed) throw RangeClosedException()
            if (status != 206) {
                // Go's file server answers a range of an empty file with 200 and no body:
                // that is the whole file, not a server without ranges.
                if (status == 200 && conn.getHeaderField("Content-Length")?.trim() == "0") {
                    return RangeChunk(ByteArray(0), 0)
                }
                throw rangeFailure(status, conn.getHeaderField("Content-Range"))
            }
            val range = ContentRange.parse(conn.getHeaderField("Content-Range"))
            // A 206 for another range, or for one it does not name: not a server to seek on.
            if (range == null || range.first != offset) throw RangeNotSupportedException()
            val bytes = ByteArray(min(length.toLong(), range.last - range.first + 1).toInt())
            conn.inputStream.use { input ->
                var done = 0
                while (done < bytes.size) {
                    val n = input.read(bytes, done, bytes.size - done)
                    if (n < 0) throw RangeIoException("short body")
                    done += n
                }
            }
            return RangeChunk(bytes, range.total)
        } catch (e: RangeException) {
            throw e
        } catch (e: Exception) {
            if (closed) throw RangeClosedException()
            throw if (e is SocketTimeoutException) RangeTimeoutException() else RangeIoException(e.javaClass.simpleName)
        } finally {
            if (conn != null) {
                inFlight.remove(conn)
                // After a body read to its end this leaves the socket to the platform's pool.
                conn.disconnect()
            }
        }
    }

    /** Disconnecting is what makes a fetch that waits on its socket throw. */
    override fun close() {
        closed = true
        inFlight.forEach { it.disconnect() }
    }
}

/** The errno a failed read becomes; [TaildriveDescriptor] supplies the platform's numbers. */
enum class RangeErrno { EACCES, ENOENT, ETIMEDOUT, EIO }

/** What a player is told when a read fails: it can tell a refusal from a missing file from a slow link. */
fun rangeErrno(e: Throwable): RangeErrno = when (e) {
    is RangeForbiddenException -> RangeErrno.EACCES
    is RangeNotFoundException -> RangeErrno.ENOENT
    is RangeTimeoutException -> RangeErrno.ETIMEDOUT
    else -> RangeErrno.EIO
}

// ---------------------------------------------------------------------------------------------
// The file
// ---------------------------------------------------------------------------------------------

/**
 * [upstream] as a file of a known [size] that [read] takes from at any offset.
 *
 * Blocks of [blockSize] bytes, the last [cacheBlocks] of them in memory, least recently
 * read out first. A request that starts right after a block in memory is reading on, and
 * asks for twice as many blocks as the request before it, up to [maxReadAhead]. Any other
 * request is a seek: it asks for the blocks its read touches and no more, and the doubling
 * starts over. So a player that jumps around a container pays a block per jump, and one
 * that plays on pays a request per [maxReadAhead] blocks. Going by the block before, and
 * not by where the previous read ended, keeps this true for a player that takes its sound
 * and its picture from two places in turn.
 *
 * One request runs at a time: a second reader waits for it and then looks in the cache
 * again, so no block is asked for twice while it is cached. [close] does not wait.
 */
class RangedFile(
    private val upstream: RangeUpstream,
    private val blockSize: Int = BLOCK_SIZE,
    private val cacheBlocks: Int = CACHE_BLOCKS,
    private val maxReadAhead: Int = MAX_READ_AHEAD,
) : Closeable {

    init {
        require(blockSize > 0) { "blockSize" }
        require(maxReadAhead in 1..cacheBlocks) { "maxReadAhead" }
    }

    /** Guards everything below it; held for a lookup or a copy of references, never for a request. */
    private val lock = ReentrantLock()

    /** Held across a request. Taken before [lock], never under it. */
    private val fetchLock = ReentrantLock()

    private val closed = AtomicBoolean(false)

    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            this.size > cacheBlocks
    }

    /** -1 until the first answer names it. */
    @Volatile
    private var total = -1L

    /** Blocks the last request asked for on account of reading on; 1 after a seek. */
    private var ahead = 1

    /**
     * The file's length, from the first answer's `Content-Range`. Costs the first block's
     * request when no read has made it yet, and throws what that request threw:
     * [RangeNotSupportedException] is the caller's cue to read the file the plain way.
     */
    @get:Throws(RangeException::class)
    val size: Long
        get() {
            val known = total
            return if (known >= 0) known else learnSize()
        }

    /**
     * Copies up to [length] bytes from [offset] into [buffer] at [bufferOffset]. Returns
     * fewer only where the file ends, and 0 at or after the end, without a request. A
     * failure belongs to this read alone: nothing is cached from it, and the next read asks
     * again.
     */
    @Throws(RangeException::class)
    fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        require(offset >= 0) { "offset" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset <= buffer.size - length) { "buffer" }
        if (closed.get()) throw RangeClosedException()
        val fileSize = size
        if (length == 0 || offset >= fileSize) return 0

        val end = min(offset + length, fileSize)
        val lastBlock = (end - 1) / blockSize
        var position = offset
        while (position < end) {
            val index = position / blockSize
            val block = lock.withLock { cache[index] } ?: load(index, lastBlock)
            val from = (position - index * blockSize).toInt()
            val count = min((block.size - from).toLong(), end - position).toInt()
            System.arraycopy(block, from, buffer, bufferOffset + (position - offset).toInt(), count)
            position += count
        }
        return (end - offset).toInt()
    }

    /** Drops the cache and aborts a request in flight; reads from now on throw [RangeClosedException]. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        upstream.close()
        lock.withLock { cache.clear() }
    }

    /** The first block, asked for on its own: its answer is where the length comes from. */
    private fun learnSize(): Long = fetchLock.withLock {
        val known = total
        if (known >= 0) return known
        val chunk = try {
            fetch(0, blockSize)
        } catch (e: RangePastEndException) {
            // No byte 0: the file is empty, whatever length the answer named.
            total = 0
            return 0
        }
        val length = chunk.total
        if (length < 0) throw RangeNotSupportedException()
        val first = min(blockSize.toLong(), length).toInt()
        if (chunk.bytes.size < first) throw RangeIoException("short answer")
        lock.withLock {
            if (closed.get()) throw RangeClosedException()
            total = length
            if (first > 0) cache[0L] = if (chunk.bytes.size == first) chunk.bytes else chunk.bytes.copyOf(first)
        }
        length
    }

    /**
     * Block [index] from the server, with as many of the following ones as the read is
     * owed: up to [lastBlock], the last one it touches, and further when it is reading on.
     */
    private fun load(index: Long, lastBlock: Long): ByteArray = fetchLock.withLock {
        val fileSize = total
        var run = 1
        val count = lock.withLock {
            if (closed.get()) throw RangeClosedException()
            // Another reader's request brought it while this one waited.
            cache[index]?.let { return it }
            // containsKey: a look, which unlike a read does not keep the block before in memory longer.
            if (cache.containsKey(index - 1)) run = min(ahead * 2, maxReadAhead)
            val wanted = min(max(lastBlock - index + 1, run.toLong()), maxReadAhead.toLong())
            val left = (fileSize - index * blockSize + blockSize - 1) / blockSize
            // Up to the first block that is already here: never ask for the same bytes twice.
            var n = 1
            while (n < min(wanted, left) && !cache.containsKey(index + n)) n++
            n
        }
        val start = index * blockSize
        val asked = min(count.toLong() * blockSize, fileSize - start).toInt()
        val bytes = fetch(start, asked).bytes

        lock.withLock {
            if (closed.get()) throw RangeClosedException()
            var first: ByteArray? = null
            var at = 0
            var i = index
            while (at < asked) {
                val want = min(blockSize.toLong(), fileSize - i * blockSize).toInt()
                // A short answer: keep the whole blocks of it, never a part of one.
                if (bytes.size - at < want) break
                val block = if (at == 0 && bytes.size == want) bytes else bytes.copyOfRange(at, at + want)
                cache[i] = block
                if (first == null) first = block
                at += want
                i++
            }
            val block = first ?: throw RangeIoException("short answer")
            ahead = run
            block
        }
    }

    /** One request; whatever it throws once the file is closed is the closing, not an error of its own. */
    private fun fetch(offset: Long, length: Int): RangeChunk {
        if (closed.get()) throw RangeClosedException()
        val chunk = try {
            upstream.fetch(offset, length)
        } catch (e: Exception) {
            if (closed.get()) throw RangeClosedException()
            throw e as? RangeException ?: RangeIoException(e.javaClass.simpleName)
        }
        if (closed.get()) throw RangeClosedException()
        return chunk
    }

    companion object {
        /** A block: the smallest request and the unit of the cache. */
        const val BLOCK_SIZE = 256 * 1024

        /** Blocks kept for one open file: 8 MiB. */
        const val CACHE_BLOCKS = 32

        /** The most blocks one request asks for: 2 MiB. */
        const val MAX_READ_AHEAD = 8

        /**
         * [upstream] as a file whose first block is already here: that answer says how long
         * the file is and proves the server honours ranges. Null when it does not, or keeps
         * the length to itself; the caller then reads the file the plain way. Whenever no
         * file comes back, [upstream] has been closed.
         */
        @Throws(RangeException::class)
        fun open(
            upstream: RangeUpstream,
            blockSize: Int = BLOCK_SIZE,
            cacheBlocks: Int = CACHE_BLOCKS,
            maxReadAhead: Int = MAX_READ_AHEAD,
        ): RangedFile? {
            val file = RangedFile(upstream, blockSize, cacheBlocks, maxReadAhead)
            try {
                file.learnSize()
            } catch (e: RangeNotSupportedException) {
                file.close()
                return null
            } catch (e: RangeException) {
                file.close()
                throw e
            }
            return file
        }
    }
}
