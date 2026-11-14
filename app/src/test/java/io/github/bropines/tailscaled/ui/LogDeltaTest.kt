package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LogDeltaTest {
    private fun e(seq: Long) = LogEntry(seq = seq, unix = seq * 1000, timestamp = "", level = "INFO", category = "CORE", message = "line $seq")
    private fun seqs(list: List<LogEntry>) = list.map { it.seq }

    @Test
    fun firstReadTakesTheWholeBuffer() {
        val d = LogDelta(gen = 0, reset = true, first = 1, last = 3, entries = listOf(e(1), e(2), e(3)))
        assertEquals(listOf(1L, 2L, 3L), seqs(mergeLogDelta(listOf(e(99)), d)))
    }

    @Test
    fun newEntriesAreAppended() {
        val held = listOf(e(1), e(2))
        val d = LogDelta(gen = 0, first = 1, last = 4, entries = listOf(e(3), e(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs(mergeLogDelta(held, d)))
    }

    @Test
    fun nothingNewIsTheSameList() {
        val held = listOf(e(1), e(2))
        assertSame(held, mergeLogDelta(held, LogDelta(gen = 0, first = 1, last = 2)))
    }

    /** The buffer drops its older half when full; the screen drops it too, as it did by re-reading. */
    @Test
    fun whatTheBufferTrimmedIsDropped() {
        val held = (1L..10L).map(::e)
        val d = LogDelta(gen = 0, first = 6, last = 11, entries = listOf(e(11)))
        assertEquals((6L..11L).toList(), seqs(mergeLogDelta(held, d)))
    }

    @Test
    fun aClearReplacesEverything() {
        val held = (1L..5L).map(::e)
        val d = LogDelta(gen = 1, reset = true, first = 2, last = 5, entries = listOf(e(2), e(4)))
        assertEquals(listOf(2L, 4L), seqs(mergeLogDelta(held, d)))
        assertEquals(emptyList<Long>(), seqs(mergeLogDelta(held, LogDelta(gen = 2, reset = true, last = 5))))
    }

    /** The shape appctr's GetLogsJSONSince writes. */
    @Test
    fun decodesTheBridgesAnswer() {
        val json = """{"gen":2,"reset":false,"first":6,"last":7,"entries":[{"seq":7,"unix":1763121600000,"timestamp":"12:00:00","level":"INFO","category":"TAILSCALE","message":"m"}]}"""
        val d = AppJson.decodeFromString<LogDelta>(json)
        assertEquals(2L, d.gen)
        assertEquals(6L, d.first)
        assertEquals(7L, d.last)
        assertEquals(LogEntry(seq = 7, unix = 1763121600000, timestamp = "12:00:00", level = "INFO", category = "TAILSCALE", message = "m"), d.entries.single())
    }
}
