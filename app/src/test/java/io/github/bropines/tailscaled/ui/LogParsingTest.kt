package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The Logs screen's parsers every bug report passes through: the Root Mode daemon log's
 * reader ([RootDaemonLog]), the clipboard's tail cut ([cutToTail]) and the day dividers
 * ([withDayDividers]).
 */
class LogParsingTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun goLogLinesBecomeEntriesWithTheirLocalTime() {
        val entries = RootDaemonLog.parse("2026/11/14 12:00:01 wgengine: started\n2026/11/14 12:00:02 dial failed\n")
        assertEquals(2, entries.size)
        assertEquals("12:00:01", entries[0].timestamp)
        assertEquals("wgengine: started", entries[0].message)
        assertEquals("TAILSCALE", entries[0].category)
        assertEquals("INFO", entries[0].level)
        assertEquals("ERROR", entries[1].level)
        val expected = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).parse("2026/11/14 12:00:01")!!.time
        assertEquals(expected, entries[0].unix)
    }

    @Test
    fun continuationLinesJoinThePreviousEntry() {
        val entries = RootDaemonLog.parse("2026/11/14 12:00:01 netmap: self: [B06o]\n  peer a\n  peer b\n2026/11/14 12:00:02 warn: slow\n")
        assertEquals(2, entries.size)
        assertEquals("netmap: self: [B06o]\n  peer a\n  peer b", entries[0].message)
        assertEquals("WARN", entries[1].level)
    }

    @Test
    fun textBeforeTheFirstStampIsAnEntryOfItsOwn() {
        val entries = RootDaemonLog.parse("orphan line\n\n2026/11/14 12:00:01 ok\n")
        assertEquals(listOf("orphan line", "ok"), entries.map { it.message })
        assertEquals(0L, entries[0].unix)
        assertEquals("", entries[0].timestamp)
    }

    @Test
    fun readTailStartsOnALineBoundary() {
        val f = tmp.newFile("tailscaled.log").apply { writeText("first line\nsecond line\nthird\n") }
        // The whole file when it fits.
        assertEquals("first line\nsecond line\nthird\n", RootDaemonLog.readTail(f, 1000))
        // A cut in the middle of "second line" drops that partial line.
        assertEquals("third\n", RootDaemonLog.readTail(f, 10))
        // A cut that takes the newline before a line keeps that line whole.
        assertEquals("third\n", RootDaemonLog.readTail(f, 7))
        assertEquals("", RootDaemonLog.readTail(f, 0))
    }

    @Test
    fun cutToTailKeepsWholeLines() {
        assertEquals("short", cutToTail("short", 100))
        assertEquals("ccc\n", cutToTail("aaa\nbbb\nccc\n", 6))
        // No newline after the cut: the plain tail.
        assertEquals("efgh", cutToTail("abcdefgh", 4))
        assertTrue(cutToTail("aaa\nbbb\nccc\n", 6).length <= 6)
    }

    @Test
    fun oneDayHasNoDividers() {
        val now = System.currentTimeMillis()
        val rows = withDayDividers(listOf(LogEntry(unix = now), LogEntry(unix = now - 1000), LogEntry(unix = 0)), "Today", "Yesterday")
        assertTrue(rows.all { it is LogRow.Entry })
        assertEquals(3, rows.size)
    }

    @Test
    fun aDividerOpensEachDay() {
        val now = System.currentTimeMillis()
        val old = now - 3 * 86_400_000L
        val rows = withDayDividers(
            listOf(LogEntry(unix = old, message = "a"), LogEntry(unix = 0, message = "b"), LogEntry(unix = now, message = "c")),
            "Today", "Yesterday"
        )
        // Day, a, b (no stamp: no divider of its own), Day "Today", c.
        assertEquals(5, rows.size)
        assertTrue(rows[0] is LogRow.Day)
        assertEquals("b", (rows[2] as LogRow.Entry).log.message)
        assertEquals(LogRow.Day("Today"), rows[3])
        assertEquals("c", (rows[4] as LogRow.Entry).log.message)
    }

    @Test
    fun noEntriesNoRows() {
        assertEquals(emptyList<LogRow>(), withDayDividers(emptyList(), "Today", "Yesterday"))
    }
}
