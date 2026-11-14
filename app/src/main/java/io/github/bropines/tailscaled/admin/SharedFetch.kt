package io.github.bropines.tailscaled.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One value per [K], fetched at most once per TTL, with one fetch in flight that every caller
 * awaits. The lock is held only to look at the cache and to start or join the fetch — never
 * across the network: a slow or dead API parks no caller behind another's monitor, and a caller
 * that is cancelled stops waiting without cancelling the fetch the others wait on.
 *
 * [fetch] answers null for a failure; a failure stands for [failedTtlMs] (so a retry storm
 * cannot follow a refresh storm) and is [failed] to the caller, a value for [okTtlMs].
 */
internal class SharedFetch<K, V>(
    private val okTtlMs: Long,
    private val failedTtlMs: Long,
    private val failed: V,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (K) -> V?,
) {
    private class Entry<K, V>(val key: K, val value: V, val expiresAt: Long)
    private class InFlight<K, V>(val key: K, val result: Deferred<V>)

    private val mutex = Mutex()
    private var entry: Entry<K, V>? = null
    private var inFlight: InFlight<K, V>? = null

    suspend fun get(key: K): V {
        val pending = mutex.withLock {
            entry?.let { if (it.key == key && clock() < it.expiresAt) return it.value }
            inFlight?.takeIf { it.key == key && it.result.isActive }?.result
                ?: scope.async { load(key) }.also { inFlight = InFlight(key, it) }
        }
        return pending.await()
    }

    private suspend fun load(key: K): V {
        val got = try {
            fetch(key)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        val now = clock()
        val e = Entry(key, got ?: failed, now + if (got != null) okTtlMs else failedTtlMs)
        mutex.withLock {
            entry = e
            if (inFlight?.key == key) inFlight = null
        }
        return e.value
    }
}
