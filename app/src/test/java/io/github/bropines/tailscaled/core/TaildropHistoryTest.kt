package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Taildrop history's rules — attempts, received entries, order, CSV — and its file with the list kept in memory. */
class TaildropHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sent(name: String, at: Long, peerId: String? = "n1", peer: String = "laptop", duration: Long? = null, attempt: Int? = null) =
        TaildropHistoryEntry(name = name, peerName = peer, timestamp = at, direction = TaildropDirection.SENT, peerId = peerId, durationMs = duration, attempt = attempt)

    private fun received(name: String, at: Long, path: String? = null, savedUri: String? = null, deletedAt: Long? = null) =
        TaildropHistoryEntry(name = name, peerName = "phone", timestamp = at, direction = TaildropDirection.RECEIVED, path = path, savedUri = savedUri, deletedAt = deletedAt)

    // --- Attempts ----------------------------------------------------------------------------------

    @Test
    fun aResendToTheSameDeviceWithinAMinuteIsTheNextAttempt() {
        val list = mutableListOf<TaildropHistoryEntry>()
        TaildropHistory.recordInto(list, sent("a.jpg", at = 10_000))
        TaildropHistory.recordInto(list, sent("a.jpg", at = 40_000, duration = 5_000))
        TaildropHistory.recordInto(list, sent("a.jpg", at = 70_000))
        assertEquals(listOf(3, 2, null), list.map { it.attempt })
    }

    @Test
    fun anotherFileAnotherDeviceOrALaterResendStartsAgain() {
        val list = mutableListOf(sent("a.jpg", at = 10_000))
        TaildropHistory.recordInto(list, sent("b.jpg", at = 20_000))
        TaildropHistory.recordInto(list, sent("a.jpg", at = 30_000, peerId = "n2"))
        TaildropHistory.recordInto(list, sent("a.jpg", at = 10_000 + 61_000, peerId = "n1").copy(timestamp = 200_000))
        assertTrue(list.all { it.attempt == null })
    }

    @Test
    fun withoutIdsTheDevicesNameDecides() {
        val list = mutableListOf(sent("a.jpg", at = 10_000, peerId = null, peer = "tv"))
        TaildropHistory.recordInto(list, sent("a.jpg", at = 20_000, peerId = null, peer = "tv"))
        assertEquals(2, list.first().attempt)
    }

    @Test
    fun aReceivedFileGetsNoAttempt() {
        val list = mutableListOf(received("a.jpg", at = 10_000))
        TaildropHistory.recordInto(list, received("a.jpg", at = 20_000))
        assertTrue(list.all { it.attempt == null })
    }

    // --- Order -------------------------------------------------------------------------------------

    @Test
    fun theListStaysNewestFirstWhateverOrderEntriesCome() {
        val list = mutableListOf<TaildropHistoryEntry>()
        for (at in listOf(5L, 9L, 1L, 7L, 9L)) TaildropHistory.insertByTime(list, sent("f$at", at))
        assertEquals(listOf(9L, 9L, 7L, 5L, 1L), list.map { it.timestamp })
        // The later of two at the same time goes first.
        assertEquals("f9", list[0].name)
    }

    // --- Received entries --------------------------------------------------------------------------

    @Test
    fun aReceivedFileIsFoundByPathThenByName() {
        val list = mutableListOf(
            received("photo.jpg", at = 3, path = "/inbox/photo.jpg"),
            received("old.jpg", at = 2, path = "/before-4.0/old.jpg"),
        )
        TaildropHistory.updateReceived(list, TaildropFile(Name = "photo.jpg", Path = "/inbox/photo.jpg")) { it.copy(savedTo = "by path") }
        TaildropHistory.updateReceived(list, TaildropFile(Name = "old.jpg", Path = "/inbox/old.jpg")) { it.copy(savedTo = "by name") }
        assertEquals(listOf("by path", "by name"), list.map { it.savedTo })
    }

    @Test
    fun aDeletedEntryIsNotTheFilesAndAFileWithoutOneGetsOne() {
        val list = mutableListOf(received("README", at = 5, path = "/inbox/README", deletedAt = 6))
        TaildropHistory.updateReceived(list, TaildropFile(Name = "README", Size = 12, ModTime = 7, Path = "/inbox/README")) { it.copy(savedTo = "x") }
        assertEquals(2, list.size)
        val made = list.first()
        assertEquals("x", made.savedTo)
        assertEquals(7_000L, made.timestamp)
        assertEquals(12L, made.size)
        assertEquals(TaildropDirection.RECEIVED, made.direction)
        assertNull("the deleted one is left alone", list[1].savedTo)
    }

    // --- CSV ---------------------------------------------------------------------------------------

    @Test
    fun aCsvCellIsQuotedWhenItMustBeAndAFormulaIsDefused() {
        assertEquals("", TaildropHistory.csvCell(null))
        assertEquals("", TaildropHistory.csvCell(""))
        assertEquals("plain.txt", TaildropHistory.csvCell("plain.txt"))
        assertEquals("\"a,b\"", TaildropHistory.csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", TaildropHistory.csvCell("say \"hi\""))
        assertEquals("\"two\nlines\"", TaildropHistory.csvCell("two\nlines"))
        for (formula in listOf("=SUM(A1)", "+1", "-1", "@x")) assertEquals("'$formula", TaildropHistory.csvCell(formula))
        assertEquals("\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\"", TaildropHistory.csvCell("=HYPERLINK(\"x\",\"y\")"))
    }

    // --- The file and the list kept in memory -----------------------------------------------------

    private fun json(vararg entries: TaildropHistoryEntry) =
        io.github.bropines.tailscaled.core.AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(TaildropHistoryEntry.serializer()), entries.toList())

    @Test
    fun aSecondReadIsTheListInMemory() {
        val file = File(tmp.root, TaildropHistory.FILE_NAME).apply { writeText(json(sent("a.jpg", 1))) }
        val store = HistoryFile(file)
        val first = store.read()
        assertSame(first, store.read())
    }

    @Test
    fun aFileReplacedFromOutsideIsReadAgain() {
        val file = File(tmp.root, TaildropHistory.FILE_NAME).apply { writeText(json(sent("a.jpg", 1))) }
        val store = HistoryFile(file)
        assertEquals(listOf("a.jpg"), store.read().map { it.name })
        // A backup restored over it: other content, another length.
        file.writeText(json(sent("restored-1.jpg", 2), sent("restored-2.jpg", 1)))
        file.setLastModified(file.lastModified() + 2_000)
        assertEquals(listOf("restored-1.jpg", "restored-2.jpg"), store.read().map { it.name })
    }

    @Test
    fun anEditIsWrittenAndSeenWithoutReadingTheFileAgain() {
        val file = File(tmp.root, TaildropHistory.FILE_NAME)
        val store = HistoryFile(file)
        assertTrue(store.read().isEmpty())
        store.edit { TaildropHistory.recordInto(it, sent("a.jpg", 1)) }
        store.edit { TaildropHistory.recordInto(it, sent("b.jpg", 2)) }
        assertEquals(listOf("b.jpg", "a.jpg"), store.read().map { it.name })
        // What is on disk is the same list.
        assertEquals(listOf("b.jpg", "a.jpg"), HistoryFile(file).read().map { it.name })
        assertTrue(!File(tmp.root, TaildropHistory.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun theListKeepsItsLimit() {
        val store = HistoryFile(File(tmp.root, TaildropHistory.FILE_NAME))
        store.edit { list -> repeat(TaildropHistory.MAX_ENTRIES + 20) { TaildropHistory.insertByTime(list, sent("f$it", it.toLong())) } }
        val read = store.read()
        assertEquals(TaildropHistory.MAX_ENTRIES, read.size)
        assertEquals("the oldest went", "f${TaildropHistory.MAX_ENTRIES + 19}", read.first().name)
    }

    @Test
    fun aDeletedFileIsAnEmptyHistory() {
        val file = File(tmp.root, TaildropHistory.FILE_NAME).apply { writeText(json(sent("a.jpg", 1))) }
        val store = HistoryFile(file)
        assertEquals(1, store.read().size)
        file.delete()
        assertTrue(store.read().isEmpty())
    }

    // --- The inbox's matching ----------------------------------------------------------------------

    @Test
    fun theInboxIndexFindsWhatASearchFromTheTopWould() {
        val history = listOf(
            received("a.jpg", at = 9, path = "/inbox/a.jpg"),
            received("a.jpg", at = 8, path = "/inbox/a.jpg"),
            received("b.jpg", at = 7, path = "/old/b.jpg", savedUri = "content://saved"),
            received("b.jpg", at = 6, path = "/old2/b.jpg"),
            received("c.jpg", at = 5, path = "/inbox/c.jpg", deletedAt = 6),
        )
        val index = TaildropInbox.ReceivedIndex(history)
        for (f in listOf(
            TaildropFile(Name = "a.jpg", Path = "/inbox/a.jpg"),
            TaildropFile(Name = "b.jpg", Path = "/inbox/b.jpg"),
            TaildropFile(Name = "c.jpg", Path = "/inbox/c.jpg"),
            TaildropFile(Name = "x.jpg", Path = "/inbox/x.jpg"),
        )) {
            val live = history.filter { it.direction == TaildropDirection.RECEIVED && it.deletedAt == null }
            val bySearch = live.firstOrNull { it.path == f.Path } ?: live.firstOrNull { it.name == f.Name && it.savedUri == null }
            assertSame(f.Name, bySearch, index.of(f))
        }
        assertEquals(6L, index.of(TaildropFile(Name = "b.jpg", Path = "/inbox/b.jpg"))!!.timestamp)
    }
}
