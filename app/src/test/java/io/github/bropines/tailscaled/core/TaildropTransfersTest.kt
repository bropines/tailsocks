package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.TaildropEvents.IncomingFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The two transfer state machines (a file coming in, files going out), the send's copies, and safeFileName. */
class TaildropTransfersTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- Coming in ---------------------------------------------------------------------------------

    private val partial = "/inbox/film.mp4.nABC123.partial"
    private fun report(received: Long, size: Long = 1_000_000, done: Boolean = false, started: String = "s1", path: String = partial) =
        IncomingFile(Name = "film.mp4", Started = started, DeclaredSize = size, Received = received, PartialPath = path, Done = done)

    private fun book() = IncomingBook(startedAt = { null })

    @Test
    fun aTransferIsListedOnceBytesArriveAndItsSenderIsAskedFor() {
        val book = book()
        assertTrue(book.apply(listOf(report(0)), now = 0, wall = 0) { false }.isEmpty())
        assertTrue("no 0 B card", book.snapshot(0).isEmpty())
        assertEquals(setOf("nABC123"), book.apply(listOf(report(100)), now = 100, wall = 0) { false })
        val t = book.snapshot(100).single()
        assertEquals(IncomingPhase.RECEIVING, t.phase)
        assertEquals(100L, t.received)
        book.senderResolved("nABC123", "laptop")
        assertEquals("laptop", book.snapshot(100).single().sender)
    }

    @Test
    fun aKnownSenderIsNotAskedForAgain() {
        val book = book()
        book.apply(listOf(report(100)), 0, 0) { false }
        book.senderResolved("nABC123", "laptop")
        val asked = book.apply(listOf(report(100, started = "s2", path = "/inbox/b.bin.nABC123.partial").copy(Name = "b.bin")), 10, 0) { false }
        assertTrue(asked.isEmpty())
        assertTrue(book.snapshot(10).all { it.sender == "laptop" })
    }

    @Test
    fun theRateIsMeasuredAndStartsOverAfterAPause() {
        val book = book()
        book.apply(listOf(report(1_000)), 0, 0) { false }
        book.apply(listOf(report(101_000)), 1_000, 0) { false }
        assertEquals(100_000L, book.snapshot(1_000).single().bytesPerSecond)
        // Silent for longer than the stall limit: waiting, with no rate.
        assertEquals(IncomingPhase.STALLED, book.snapshot(1_000 + IncomingBook.STALL_AFTER_MS + 1).single().phase)
        assertNull(book.snapshot(1_000 + IncomingBook.STALL_AFTER_MS + 1).single().bytesPerSecond)
        // Bytes again: running, and the rate measured afresh rather than averaging the pause in.
        book.apply(listOf(report(201_000)), 10_000, 0) { false }
        assertEquals(IncomingPhase.RECEIVING, book.snapshot(10_000).single().phase)
        assertNull(book.snapshot(10_000).single().bytesPerSecond)
    }

    @Test
    fun reportsCloserThanTheSampleGapDoNotMoveTheRate() {
        val book = book()
        book.apply(listOf(report(1_000)), 0, 0) { false }
        book.apply(listOf(report(2_000)), 100, 0) { false }
        assertNull(book.snapshot(100).single().bytesPerSecond)
    }

    @Test
    fun silenceBecomesInterruptedAndIsDroppedLater() {
        val book = book()
        book.apply(listOf(report(100)), 0, 0) { false }
        book.tick(IncomingBook.INTERRUPT_AFTER_MS + 1)
        assertEquals(IncomingPhase.INTERRUPTED, book.snapshot(IncomingBook.INTERRUPT_AFTER_MS + 1).single().phase)
        book.tick(IncomingBook.INTERRUPT_AFTER_MS + 1 + IncomingBook.INTERRUPTED_KEEP_MS + 1)
        assertTrue(book.isEmpty)
    }

    @Test
    fun missingFromAReportWithoutDoneIsInterruptedAndBytesBringItBack() {
        val book = book()
        book.apply(listOf(report(100)), 0, 0) { false }
        book.apply(emptyList(), 500, 0) { false }
        assertEquals(IncomingPhase.INTERRUPTED, book.snapshot(500).single().phase)
        book.apply(listOf(report(200)), 1_000, 0) { false }
        assertEquals(IncomingPhase.RECEIVING, book.snapshot(1_000).single().phase)
    }

    @Test
    fun allBytesInIsFinishingAndDoneIsSavingWithAFolder() {
        for (folder in listOf(true, false)) {
            val book = book()
            book.apply(listOf(report(100)), 0, 0) { folder }
            book.apply(listOf(report(1_000_000)), 1_000, 0) { folder }
            assertEquals(IncomingPhase.FINISHING, book.snapshot(1_000).single().phase)
            book.apply(listOf(report(0, done = true)), 2_000, 0) { folder }
            val t = book.snapshot(2_000).single()
            assertEquals(if (folder) IncomingPhase.SAVING else IncomingPhase.FINISHING, t.phase)
            assertEquals("Done reports 0; what arrived stays", 1_000_000L, t.received)
            assertTrue(book.finished(IncomingBook.keyOf(report(0, done = true))))
            assertTrue(book.isEmpty)
        }
    }

    @Test
    fun aFileFirstSeenDoneGetsNoCardAndAnUnannouncedOneLeavesInTime() {
        val book = book()
        book.apply(listOf(report(5, done = true)), 0, 0) { false }
        assertTrue(book.isEmpty)
        book.apply(listOf(report(100)), 0, 0) { false }
        book.apply(listOf(report(100, done = true)), 10, 0) { false }
        book.tick(10 + IncomingBook.DONE_KEEP_MS + 1)
        assertTrue(book.isEmpty)
    }

    @Test
    fun aResendOfTheSameFileReplacesTheEarlierUpload() {
        val book = book()
        book.apply(listOf(report(100, started = "s1")), 0, 0) { false }
        book.apply(listOf(report(50, started = "s2")), 10, 0) { false }
        assertEquals(listOf(50L), book.snapshot(10).map { it.received })
    }

    @Test
    fun theDaemonsStartTimeGivesAFirstRate() {
        val book = IncomingBook(startedAt = { 0L })
        book.apply(listOf(report(200_000)), now = 0, wall = 2_000) { false }
        assertEquals(100_000L, book.snapshot(0).single().bytesPerSecond)
    }

    // --- Going out ---------------------------------------------------------------------------------

    private fun outgoing(vararg names: String): Pair<OutgoingBook, List<OutgoingBook.Item>> {
        val book = OutgoingBook()
        val batch = OutgoingBook.Batch("tv", names.size)
        val items = names.mapIndexed { i, n -> OutgoingBook.Item("out-$i", n, batch) }
        book.add(items)
        return book to items
    }

    @Test
    fun aFileGoesQueuedPreparingSendingFinishingAndLeaves() {
        val (book, items) = outgoing("a.jpg")
        val a = items.single()
        assertEquals(OutgoingPhase.QUEUED, book.snapshot().single().phase)
        assertTrue(book.preparing(a))
        assertEquals(OutgoingPhase.PREPARING, book.snapshot().single().phase)
        book.sized(a, 10_000, now = 0)
        assertSame(a, book.running())
        book.progress(a, 5_000, now = 1_000)
        val sending = book.snapshot().single()
        assertEquals(OutgoingPhase.SENDING, sending.phase)
        assertEquals(5_000L, sending.bytesPerSecond)
        assertEquals(1L, sending.secondsLeft)
        book.progress(a, 10_000, now = 2_000)
        assertEquals(OutgoingPhase.FINISHING, book.snapshot().single().phase)
        val batch = book.ended(a, error = null, skipped = false, now = 3_000)
        assertNotNull("the batch's last file ends it", batch)
        assertEquals(listOf("a.jpg"), batch!!.sent)
        assertTrue(book.isEmpty)
    }

    @Test
    fun aFailureStaysListedForAWhileAndTheBatchSaysWhy() {
        val (book, items) = outgoing("a.jpg", "b.jpg")
        assertNull(book.ended(items[0], error = null, skipped = false, now = 0))
        val batch = book.ended(items[1], error = "HTTP 404: node not found", skipped = false, now = 0)!!
        assertEquals(listOf("a.jpg"), batch.sent)
        assertEquals(listOf("b.jpg" to "HTTP 404: node not found"), batch.failed)
        val failed = book.snapshot().single()
        assertEquals(OutgoingPhase.FAILED, failed.phase)
        assertEquals("HTTP 404: node not found", failed.error)
        assertFalse(book.sweep(OutgoingBook.FAILED_KEEP_MS))
        assertTrue(book.sweep(OutgoingBook.FAILED_KEEP_MS + 1))
    }

    @Test
    fun aCancelledFileLeavesAtOnceWithNothingToTell() {
        val (book, items) = outgoing("a.jpg", "b.jpg")
        assertEquals(listOf(items[1]), book.cancel("out-1"))
        assertFalse("its turn finds it cancelled", book.preparing(items[1]))
        book.ended(items[0], error = null, skipped = false, now = 0)
        val batch = book.ended(items[1], error = null, skipped = true, now = 0)!!
        assertTrue(batch.cancelled)
        assertEquals(listOf("a.jpg"), batch.sent)
        assertTrue(batch.failed.isEmpty())
        assertTrue(book.isEmpty)
    }

    @Test
    fun cancelAllLeavesTheFailedAlone() {
        val (book, items) = outgoing("a.jpg", "b.jpg")
        book.ended(items[0], error = "refused", skipped = false, now = 0)
        assertEquals(listOf(items[1]), book.cancel(null))
    }

    // --- The send's copies -------------------------------------------------------------------------

    @Test
    fun twoCopiesOfSameNamedFilesNeverShareAPath() {
        val copies = TaildropCopies(File(tmp.root, TaildropCopies.DIR))
        val a = copies.newCopy("photo.jpg")
        val b = copies.newCopy("photo.jpg")
        assertNotEquals(a, b)
        assertEquals("the bridge names the file after the path", "photo.jpg", a.name)
        assertEquals("photo.jpg", b.name)
        a.writeText("a")
        TaildropCopies.discard(a)
        assertFalse(a.exists())
        assertFalse("its directory goes with it", a.parentFile!!.exists())
        assertTrue(b.parentFile!!.exists())
    }

    @Test
    fun theFirstCopyOfAProcessClearsWhatAnEarlierOneLeft() {
        val root = File(tmp.root, TaildropCopies.DIR)
        val left = File(root, "peers/film.mkv").apply { parentFile!!.mkdirs(); writeText("half a film") }
        val copies = TaildropCopies(root)
        val first = copies.newCopy("a.bin").apply { writeText("in flight") }
        assertFalse(left.exists())
        copies.newCopy("b.bin")
        assertTrue("later copies leave this process's own alone", first.exists())
    }

    // --- safeFileName ------------------------------------------------------------------------------

    @Test
    fun aDisplayNameFromAnotherAppIsReducedToAPlainName() {
        assertEquals("x.xml", safeFileName("../../shared_prefs/x.xml"))
        assertEquals("evil.txt", safeFileName("..\\..\\evil.txt"))
        assertEquals("a_b", safeFileName("a\u0000b"))
        assertEquals("name.jpg", safeFileName("  name.jpg  "))
        assertEquals("Отчёт.pdf", safeFileName("Отчёт.pdf"))
        for (bad in listOf("", "   ", ".", "..", "/", "a/", "dir/..", "\\..")) assertNull(bad, safeFileName(bad))
    }
}
