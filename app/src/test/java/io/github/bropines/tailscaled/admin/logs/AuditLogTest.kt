package io.github.bropines.tailscaled.admin.logs

import io.github.bropines.tailscaled.admin.api.AuditLogFilters
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.Rfc3339
import io.github.bropines.tailscaled.admin.api.recorded
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The audit log's query, its before → after diff, and its days. */
class AuditLogTest {

    private fun json(s: String) = AppJson.parseToJsonElement(s)

    // ------------------------------------------------------------------ query

    @Test
    fun theWindowIsAlwaysBounded() {
        val now = Rfc3339.parse("2026-10-09T12:00:00Z")!!
        assertEquals("2026-10-09T11:00:00Z" to "2026-10-09T12:00:00Z", AuditLogQuery(LogWindow.HOUR).range(now))
        assertEquals("2026-10-08T12:00:00Z", AuditLogQuery(LogWindow.DAY).range(now).first)
        assertEquals("2026-10-02T12:00:00Z", AuditLogQuery().range(now).first)
        assertEquals("2026-09-09T12:00:00Z", AuditLogQuery(LogWindow.MONTH).range(now).first)
    }

    @Test
    fun filtersBecomeTheServersParameters() {
        assertEquals(emptyList<Pair<String, String>>(), AuditLogQuery().filters().query())
        val q = AuditLogQuery(actor = "bob", target = " raspberry ", event = "NODE.UPDATE.MACHINE_NAME")
        assertEquals(
            listOf("actor" to "~bob", "target" to "raspberry", "event" to "NODE.UPDATE.MACHINE_NAME"),
            q.filters().query(),
        )
        assertEquals("an id is sent as it is", "uZKk3KSfrH11CNTRL", AuditLogFilters.actor("uZKk3KSfrH11CNTRL"))
        assertEquals("~bob", AuditLogFilters.actor("~bob"))
        assertEquals(AuditLogQuery(LogWindow.HOUR), q.copy(window = LogWindow.HOUR).cleared())
    }

    @Test
    fun theTailscaleBackendSendsThemRepeated() = runBlocking {
        val t = FakeTransport().ok("GET", "/logging/configuration", recorded("audit_log.json"))
        testBackend(t).auditLog("2026-10-01T00:00:00Z", "2026-10-09T00:00:00Z", AuditLogFilters(actors = listOf("~a", "~b"), events = listOf("NODE.DELETE")))
        val url = t.requests.single().url
        assertTrue(url, url.contains("start=2026-10-01T00%3A00%3A00Z"))
        assertTrue(url, url.contains("actor=%7Ea&actor=%7Eb"))
        assertTrue(url, url.contains("event=NODE.DELETE"))
    }

    // ------------------------------------------------------------------ diff

    @Test
    fun scalarsAndFlags() {
        assertEquals(listOf(AuditChange.Changed("", "raspberry", "raspberry-pi")), AuditDiff.of(JsonPrimitive("raspberry"), JsonPrimitive("raspberry-pi")))
        assertEquals(listOf(AuditChange.Changed("", "true", "false")), AuditDiff.of(JsonPrimitive(true), JsonPrimitive(false)))
        assertEquals(listOf(AuditChange.Added("", "90")), AuditDiff.of(null, JsonPrimitive(90)))
        assertEquals(listOf(AuditChange.Removed("", "x")), AuditDiff.of(JsonPrimitive("x"), JsonNull))
        assertEquals(emptyList<AuditChange>(), AuditDiff.of(JsonPrimitive("same"), JsonPrimitive("same")))
        assertEquals(emptyList<AuditChange>(), AuditDiff.of(null, null))
    }

    @Test
    fun listsAreSets() {
        val changes = AuditDiff.of(json("""["tag:a","tag:b"]"""), json("""["tag:b","tag:c"]"""))
        assertEquals(listOf(AuditChange.Removed("", "tag:a"), AuditChange.Added("", "tag:c")), changes)
        assertEquals("order alone is no change", emptyList<AuditChange>(), AuditDiff.of(json("""["a","b"]"""), json("""["b","a"]""")))
    }

    @Test
    fun objectsByKeyWithPaths() {
        val old = json("""{"magicDNS":true,"nameservers":["1.1.1.1"],"split":{"corp":["10.0.0.53"]}}""")
        val new = json("""{"magicDNS":false,"nameservers":["1.1.1.1","9.9.9.9"],"split":{"corp":["10.0.0.54"]},"search":["corp"]}""")
        assertEquals(
            listOf(
                AuditChange.Changed("magicDNS", "true", "false"),
                AuditChange.Added("nameservers", "9.9.9.9"),
                AuditChange.Removed("split.corp", "10.0.0.53"),
                AuditChange.Added("split.corp", "10.0.0.54"),
                AuditChange.Added("search", "corp"),
            ),
            AuditDiff.of(old, new),
        )
        val objs = AuditDiff.of(json("""[{"a":1},{"a":2}]"""), json("""[{"a":1},{"a":3}]"""))
        assertEquals(listOf(AuditChange.Changed("[1].a", "2", "3")), objs)
    }

    @Test
    fun aPolicyFileIsDiffedByLineWithContext() {
        val before = (1..20).joinToString("\n") { "line $it" }
        val after = before.replace("line 10", "line ten").replace("line 20", "line 20\nline 21")
        val text = AuditDiff.of(JsonPrimitive(before), JsonPrimitive(after)).single() as AuditChange.Text
        val kinds = text.lines.map { it.kind to (if (it.kind == TextLine.Kind.GAP) it.folded.toString() else it.text) }
        assertEquals(
            listOf(
                TextLine.Kind.GAP to "7",
                TextLine.Kind.SAME to "line 8", TextLine.Kind.SAME to "line 9",
                TextLine.Kind.REMOVED to "line 10", TextLine.Kind.ADDED to "line ten",
                TextLine.Kind.SAME to "line 11", TextLine.Kind.SAME to "line 12",
                TextLine.Kind.GAP to "6",
                TextLine.Kind.SAME to "line 19", TextLine.Kind.SAME to "line 20",
                TextLine.Kind.ADDED to "line 21",
            ),
            kinds,
        )
    }

    @Test
    fun longValuesAreCut() {
        val long = "x".repeat(1000)
        val c = AuditDiff.of(JsonPrimitive("a"), json("""{"k":"$long"}""")).single() as AuditChange.Changed
        assertTrue(c.after.length <= 240)
        assertTrue(c.after.endsWith("…"))
    }

    // ------------------------------------------------------------------ days

    @Test
    fun entriesGroupByLocalDayNewestFirst() {
        val utc = TimeZone.getTimeZone("UTC")
        data class E(val t: String?)
        val items = listOf(E("2026-10-08T23:59:00Z"), E("2026-10-09T08:00:00Z"), E(null), E("2026-10-09T10:00:00Z"), E("2026-10-07T01:00:00Z"))
        val days = LogDays.group(items, { Rfc3339.parse(it.t) }, utc)
        assertEquals(listOf("2026-10-09T10:00:00Z", "2026-10-09T08:00:00Z"), days[0].items.map { it.t })
        assertEquals(listOf("2026-10-08T23:59:00Z"), days[1].items.map { it.t })
        assertEquals(listOf("2026-10-07T01:00:00Z"), days[2].items.map { it.t })
        assertNull("entries without a time come last", days[3].dayStart)
        val now = Rfc3339.parse("2026-10-09T12:00:00Z")!!
        assertEquals(listOf(0, 1, 2), days.take(3).map { LogDays.daysAgo(it.dayStart!!, now, utc) })
        // In Moscow, 23:59 UTC on the 8th is already the 9th.
        val msk = TimeZone.getTimeZone("Europe/Moscow")
        assertEquals(2, LogDays.group(items.take(2), { Rfc3339.parse(it.t) }, msk).first().items.size)
    }

    @Test
    fun timestampsInEveryShapeTheApisUse() {
        assertEquals(Rfc3339.parse("2026-10-09T17:08:51.493Z"), Rfc3339.parse("2026-10-09T17:08:51.493647586Z"))
        assertEquals(Rfc3339.parse("2026-10-09T17:08:51Z"), Rfc3339.parse("2026-10-09T20:08:51+03:00"))
        assertNull(Rfc3339.parse("0001-01-01T00:00:00Z"))
        assertNull(Rfc3339.parse("yesterday"))
        assertEquals("2026-10-09T17:08:51Z", Rfc3339.format(Rfc3339.parse("2026-10-09T17:08:51.9Z")!! - 900))
    }
}
