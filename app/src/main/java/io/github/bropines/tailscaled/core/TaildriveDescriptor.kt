package io.github.bropines.tailscaled.core

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.FileNotFoundException

/*
 * A share's file as a descriptor another app can seek in: the platform's proxy descriptor
 * (API 26) over RangedFile. A pipe, which is what the provider hands out otherwise, can
 * only be read from start to end, so a player could neither jump in a video nor open an
 * MP4 whose index is at its end.
 *
 * The reading, the cache and the read-ahead are in TaildriveRangeReader.kt and are tested
 * there; this file is the part that needs a device.
 */

private const val TAG = "TaildriveDescriptor"

@RequiresApi(26)
object TaildriveDescriptor {
    /**
     * The one thread every open descriptor is read on: the platform delivers a read here
     * and the reader waits here for the network. Started by the first descriptor.
     */
    private val handler: Handler by lazy {
        val thread = HandlerThread("taildrive-fd")
        thread.start()
        Handler(thread.looper)
    }

    /**
     * A read-only, seekable descriptor over [upstream], or null when there cannot be one —
     * the server ignores ranges or keeps the file's length to itself, the first block did
     * not arrive, the platform refused — and the caller hands out its pipe instead.
     *
     * Waits for the first block on the calling thread; that answer is where the length
     * comes from, and what tells a server with ranges from one without. A file the server
     * says is not there, or not ours to read, is refused here rather than at the first read.
     * [upstream] is closed whenever no descriptor comes back, and with the descriptor
     * otherwise.
     */
    @Throws(FileNotFoundException::class)
    fun open(context: Context, upstream: RangeUpstream): ParcelFileDescriptor? {
        val file = try {
            RangedFile.open(upstream) ?: return null
        } catch (e: RangeNotFoundException) {
            throw FileNotFoundException("Taildrive: no such file")
        } catch (e: RangeForbiddenException) {
            throw FileNotFoundException("Taildrive: access denied")
        } catch (e: RangeException) {
            // The pipe meets the same failure and reports it the way it always has.
            Log.w(TAG, "first block: ${describe(e)}")
            return null
        }
        return try {
            val storage = context.getSystemService(StorageManager::class.java)
                ?: throw IllegalStateException("no StorageManager")
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Callback(file), handler)
        } catch (e: Exception) {
            Log.w(TAG, "no proxy descriptor: ${e.javaClass.simpleName}")
            file.close()
            null
        }
    }

    private class Callback(private val file: RangedFile) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = orErrno("size") { file.size }

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
            orErrno("read") { file.read(offset, data, 0, size.coerceIn(0, data.size)) }

        override fun onRelease() = file.close()
    }

    /** A failure as the errno the reading app gets from `read`. */
    private inline fun <T> orErrno(what: String, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        Log.w(TAG, "$what: ${describe(e)}")
        throw ErrnoException(what, rangeErrno(e).number)
    }

    private val RangeErrno.number: Int
        get() = when (this) {
            RangeErrno.EACCES -> OsConstants.EACCES
            RangeErrno.ENOENT -> OsConstants.ENOENT
            RangeErrno.ETIMEDOUT -> OsConstants.ETIMEDOUT
            RangeErrno.EIO -> OsConstants.EIO
        }

    /** For the log: the class, and the message only where it is ours and so names no file and no address. */
    private fun describe(e: Throwable): String =
        if (e is RangeIoException) "${e.javaClass.simpleName} (${e.message})" else e.javaClass.simpleName
}
