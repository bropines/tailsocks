package io.github.bropines.tailscaled.admin.logs

import io.github.bropines.tailscaled.admin.api.AuditLogFilters
import io.github.bropines.tailscaled.admin.api.Rfc3339

/** How far back the tailnet's audit log is read. Always bounded: the API keeps 90 days. */
enum class LogWindow(val hours: Int) {
    HOUR(1), DAY(24), WEEK(24 * 7), MONTH(24 * 30);

    val millis: Long get() = hours * 3_600_000L
}

/**
 * What the tailnet's audit log is asked for: a window and the server's own filters. [actor]
 * and [target] are what was typed; [event] is one `TARGET.ACTION[.PROPERTY]` name or none.
 */
data class AuditLogQuery(
    val window: LogWindow = LogWindow.WEEK,
    val actor: String = "",
    val target: String = "",
    val event: String? = null,
) {
    val hasFilters: Boolean get() = actor.isNotBlank() || target.isNotBlank() || event != null

    /** The window ending at [now], as the API's `start` and `end`. */
    fun range(now: Long): Pair<String, String> = Rfc3339.format(now - window.millis) to Rfc3339.format(now)

    fun filters(): AuditLogFilters = AuditLogFilters(
        actors = listOfNotNull(actor.takeIf { it.isNotBlank() }?.let(AuditLogFilters::actor)),
        targets = listOfNotNull(target.trim().takeIf { it.isNotEmpty() }),
        events = listOfNotNull(event),
    )

    fun cleared(): AuditLogQuery = AuditLogQuery(window = window)
}
