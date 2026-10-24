package io.github.bropines.tailscaled.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/*
 * A file on another device's Taildrive share, readable at any offset: what stands between
 * a descriptor a player seeks in (TaildriveDescriptor) and a server that answers HTTP byte
 * ranges (TaildriveClient.rangeUpstream, BridgeRangeUpstream). No android.* here, so all of it
 * runs in a unit test.
 *
 * A request costs a trip through the daemon and over the tailnet, and a
 * player probing a container asks for a few bytes here and a few there. So the file is cut
 * into fixed blocks, no request is smaller than a block, the blocks read last stay in
 * memory, and a read that continues the previous one asks for several blocks at once. A
 * file opened to be read through ([openRangedFile]) also fetches the blocks a reader that
 * reads on will want next while it is still busy with the ones it has.
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
    prefetcher: Executor? = RangedFile.PREFETCH,
): RangedFile? {
    try {
        return RangedFile.open(newUpstream(), prefetcher = prefetcher)
    } catch (e: RangeException) {
        if (!isStaleStart(e)) throw e
    }
    try {
        pause(retryDelayMs)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw RangeClosedException()
    }
    return RangedFile.open(newUpstream(), prefetcher = prefetcher)
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
 *
 * With a [prefetcher], a read that reads on also starts, on the prefetcher, the request the
 * next read would make — the blocks after the ones in memory, up to [maxReadAhead] beyond the
 * one just read — so the network is busy while the reader copies, rather than after it. The
 * reader that gets there first waits for that request as for any other. A seek starts
 * nothing, and a prefetch that fails leaves the blocks to the read that wants them.
 */
class RangedFile(
    private val upstream: RangeUpstream,
    private val blockSize: Int = BLOCK_SIZE,
    private val cacheBlocks: Int = CACHE_BLOCKS,
    private val maxReadAhead: Int = MAX_READ_AHEAD,
    private val prefetcher: Executor? = null,
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

    /** A prefetch is queued or running: there is never more than one. */
    private val prefetching = AtomicBoolean(false)

    /** The last block a read ended in: where a prefetch has to be ahead of to be worth its request. */
    @Volatile
    private var readerAt = -1L

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
        readerAt = lastBlock
        if (prefetcher != null) prefetchAfter(lastBlock, fileSize)
        return (end - offset).toInt()
    }

    /**
     * After a read that ended in block [last]: when the reader is reading on — the block before
     * it is in memory too, or it is the first — the first block after [last] that is not in
     * memory, if it is no further than [maxReadAhead] blocks away, is loaded on the prefetcher.
     */
    private fun prefetchAfter(last: Long, fileSize: Long) {
        val lastIndex = (fileSize - 1) / blockSize
        val next = lock.withLock {
            if (closed.get() || !cache.containsKey(last)) return
            if (last > 0 && !cache.containsKey(last - 1)) return
            var i = last + 1
            while (i <= lastIndex && i <= last + maxReadAhead && cache.containsKey(i)) i++
            if (i > lastIndex || i > last + maxReadAhead) return
            i
        }
        if (!prefetching.compareAndSet(false, true)) return
        try {
            prefetcher!!.execute {
                // Never queued behind the reader's own request: the lock is not fair, the reader
                // takes it again and again, and a prefetch that got it at last asked for a block
                // the reader had long passed — and kept every later prefetch waiting meanwhile. A
                // reader that is fetching brings what it needs; the next read starts the next one.
                if (!fetchLock.tryLock()) {
                    prefetching.set(false)
                    return@execute
                }
                try {
                    val ahead = lock.withLock { next > readerAt && next <= readerAt + maxReadAhead && !cache.containsKey(next) }
                    if (!closed.get() && ahead) load(next, next)
                } catch (_: Exception) {
                    // The read that wants these blocks asks again and is told what went wrong.
                } finally {
                    fetchLock.unlock()
                    prefetching.set(false)
                }
            }
        } catch (_: Exception) {
            prefetching.set(false)
        }
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
         * Where every file opened to be read through runs its prefetches: threads made as they
         * are needed and let go after a minute idle, which never keep the process alive. At the
         * highest priority a thread can be given: another app on screen waits on these reads,
         * and TailSocks itself is in the background then (on Android, nice -8).
         */
        val PREFETCH: Executor by lazy {
            val number = AtomicInteger()
            Executors.newCachedThreadPool { task ->
                Thread(task, "taildrive-prefetch-${number.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.MAX_PRIORITY
                }
            }
        }

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
            prefetcher: Executor? = null,
        ): RangedFile? {
            val file = RangedFile(upstream, blockSize, cacheBlocks, maxReadAhead, prefetcher)
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
