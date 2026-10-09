package io.github.bropines.tailscaled.admin.logs

import java.util.Calendar
import java.util.TimeZone

/** Entries of one local calendar day; [dayStart] is that day's midnight, null for entries without a time. */
data class DayGroup<T>(val dayStart: Long?, val items: List<T>)

/** Log entries by day, newest day first and newest entry first within a day. */
object LogDays {

    fun dayStart(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).run {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    fun <T> group(items: List<T>, time: (T) -> Long?, zone: TimeZone = TimeZone.getDefault()): List<DayGroup<T>> {
        val timed = items.map { it to time(it) }
        val days = timed.filter { it.second != null }
            .sortedByDescending { it.second }
            .groupBy { dayStart(it.second!!, zone) }
            .map { (day, list) -> DayGroup(day, list.map { it.first }) }
        val untimed = timed.filter { it.second == null }.map { it.first }
        return if (untimed.isEmpty()) days else days + DayGroup(null, untimed)
    }

    /** 0 for today, 1 for yesterday, and so on, by calendar day in [zone]; negative for the future. */
    fun daysAgo(dayStart: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val today = Calendar.getInstance(zone).apply { timeInMillis = dayStart(now, zone) }
        val day = Calendar.getInstance(zone).apply { timeInMillis = dayStart }
        var n = 0
        // Calendar days, not 24-hour spans: a DST change makes one day 23 or 25 hours long.
        while (day.before(today) && n < 400) {
            day.add(Calendar.DAY_OF_YEAR, 1)
            n++
        }
        return if (day.after(today) && n == 0) -1 else n
    }
}
