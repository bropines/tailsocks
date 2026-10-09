package io.github.bropines.tailscaled.admin.api

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RFC 3339 timestamps as the APIs write them — any fraction ("…:51.493647586Z"), "Z" or an
 * offset — without java.time, which API 24–25 do not have.
 */
object Rfc3339 {
    private val SHAPE = Regex("^(\\d{4}-\\d{2}-\\d{2})[Tt ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?([Zz]|[+-]\\d{2}:\\d{2})$")

    /** Epoch milliseconds, or null for anything else (Go's zero time included). */
    fun parse(text: String?): Long? {
        val m = SHAPE.matchEntire(text?.trim() ?: return null) ?: return null
        val (date, time, fraction, zone) = m.destructured
        if (date.startsWith("0001-01-01")) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { isLenient = false }
        val base = runCatching { fmt.parse("${date}T$time${if (zone.equals("z", true)) "+00:00" else zone}") }.getOrNull() ?: return null
        val ms = fraction.take(3).padEnd(3, '0').toIntOrNull() ?: 0
        return base.time + ms
    }

    /** UTC, whole seconds: "2026-10-09T17:08:51Z". */
    fun format(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
