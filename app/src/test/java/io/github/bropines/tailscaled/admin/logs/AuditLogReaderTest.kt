package io.github.bropines.tailscaled.admin.logs

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.Rfc3339
import io.github.bropines.tailscaled.admin.api.recorded
import io.github.bropines.tailscaled.admin.api.status
import io.github.bropines.tailscaled.admin.api.testBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The audit log read a day at a time: the spans, the merge, and a failed day. */
class AuditLogReaderTest {

    private val now = Rfc3339.parse("2026-10-09T12:00:00Z")!!

    @Test
    fun spansCoverTheWindowWithoutGaps() {
        val week = AuditLogReader.spans(now - LogWindow.WEEK.millis, now)
        assertEquals(7, week.size)
        assertEquals(now, week.first().second)
        assertEquals(now - LogWindow.WEEK.millis, week.last().first)
        week.zipWithNext().forEach { (newer, older) -> assertEquals(newer.first, older.second) }
        assertEquals(listOf(now - LogWindow.HOUR.millis to now), AuditLogReader.spans(now - LogWindow.HOUR.millis, now))
        // A window that is not a whole number of days ends with a short span.
        val odd = AuditLogReader.spans(now - 30 * 3_600_000L, now)
        assertEquals(listOf(24 * 3_600_000L, 6 * 3_600_000L), odd.map { it.second - it.first })
    }

    @Test
    fun aWeekIsSevenRequestsMergedOnce() = runBlocking {
        val t = FakeTransport().ok("GET", "/logging/configuration", recorded("audit_log.json"))
        val single = testBackend(FakeTransport().ok("GET", "/logging/configuration", recorded("audit_log.json")))
            .auditLog("a", "b", AuditLogQuery().filters()).items
        val log = AuditLogReader.read(testBackend(t), AuditLogQuery(LogWindow.WEEK, event = "NODE.DELETE"), now)
        assertEquals(7, t.requests.size)
        val starts = t.requests.map { it.url.substringAfter("start=").substringBefore('&') }.toSet()
        assertEquals(7, starts.size)
        assertTrue(t.requests.all { it.url.contains("event=NODE.DELETE") || it.url.contains("events=NODE.DELETE") })
        // Every day answered the same entries here: they are one set, not seven.
        assertEquals(single, log.items)
    }

    @Test
    fun aDayThatFailsFailsTheWindow() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/logging/configuration", "start=2026-10-07", true, status(500, """{"message":"boom"}"""))
            .ok("GET", "/logging/configuration", recorded("audit_log.json"))
        try {
            AuditLogReader.read(testBackend(t), AuditLogQuery(LogWindow.WEEK), now)
            fail("a log with a day missing would read as a quiet day")
        } catch (_: AdminApiException.Server) {
        }
    }
}
