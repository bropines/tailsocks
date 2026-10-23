package io.github.bropines.tailscaled.core

import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Where the documents provider does its waiting. A call from another app is served on one of
 * this process's binder threads, and for the length of the call that thread carries the
 * caller's StrictMode policy. A caller on its main thread — a player asking for a file's name
 * and size before it plays — has death-on-network there, so the first socket the binder thread
 * opens throws NetworkOnMainThreadException, however well the network is. Measured on API 36
 * with a stand-in player: the same query answered the real file from a background thread, and
 * a directory of size 0 from the main thread, because the provider's catch-all answered for
 * the exception.
 *
 * So whatever may touch the network is run here, on a thread of ours, and the binder thread
 * only waits.
 */

internal object ProviderWorker {
    /** Threads for the provider's requests and the first block of each open: short-lived, none of them the descriptor's reading thread. */
    private val workers: ExecutorService by lazy {
        // Another app waits on each of these: TailSocks is in the background then, so they get
        // the highest priority a thread can have (on Android, nice -8).
        Executors.newCachedThreadPool { r ->
            Thread(r, "taildrive-provider").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    }

    /**
     * [body] on a worker thread, waited for here; what it throws is thrown as it was, on this
     * thread. An interrupt of the waiting thread cancels the work and is an [InterruptedIOException].
     */
    @Throws(InterruptedIOException::class)
    fun <T> run(body: () -> T): T {
        val task = workers.submit(Callable { body() })
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted")
        }
    }
}
