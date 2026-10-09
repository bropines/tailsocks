package io.github.bropines.tailscaled.admin.logs

import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry
import io.github.bropines.tailscaled.admin.api.Listing
import io.github.bropines.tailscaled.admin.api.Rfc3339
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The tailnet's audit log for a window, read a day at a time and several days at once. The
 * server's time grows with what it returns (every policy edit carries both versions of the
 * file): on a real tailnet a week took ten seconds in one piece and a month was cut off after
 * thirty, while each day answers in about two.
 */
object AuditLogReader {
    const val SPAN_MS = 24 * 3_600_000L
    const val PARALLEL = 6

    /** [start, end) as spans of at most [spanMs], newest first; they meet without a gap. */
    fun spans(start: Long, end: Long, spanMs: Long = SPAN_MS): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var hi = end
        while (hi > start) {
            val lo = maxOf(start, hi - spanMs)
            out += lo to hi
            hi = lo
        }
        return out
    }

    /** The whole window or a failure: a log with a day missing would read as a quiet day. */
    suspend fun read(backend: AdminBackend, query: AuditLogQuery, now: Long, parallel: Int = PARALLEL): Listing<ApiAuditLogEntry> {
        val filters = query.filters()
        val gate = Semaphore(parallel)
        val parts = coroutineScope {
            spans(now - query.window.millis, now).map { (lo, hi) ->
                async { gate.withPermit { backend.auditLog(Rfc3339.format(lo), Rfc3339.format(hi), filters) } }
            }.awaitAll()
        }
        // Spans meet at a whole second, which the server may count on both sides.
        return Listing(parts.flatMap { it.items }.distinct(), parts.flatMap { it.issues })
    }
}
