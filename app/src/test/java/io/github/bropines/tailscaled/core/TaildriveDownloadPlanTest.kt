package io.github.bropines.tailscaled.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downloading from a share: what a selection comes to, the question a selection with folders asks
 * and its three answers, what the walk of the folders counts, the names things get where they land,
 * and when the folder is asked for.
 */
class TaildriveDownloadPlanTest {

    private val movies = DriveLocation(listOf("tail.ts.net", "nas", "Movies"))

    private fun file(name: String, size: Long = 0) = DriveEntry(name, DriveMime.of(name).kind, size)
    private fun folder(name: String) = DriveEntry(name, DriveKind.FOLDER)

    // --- the question ----------------------------------------------------------------------------

    @Test
    fun filesAloneStartAtOnceAndAFolderAsksFirst() {
        assertFalse(DownloadSelection.asks(listOf(file("a.mkv"), file("b.srt"))))
        assertFalse(DownloadSelection.asks(listOf(file("a.mkv"))))
        assertTrue(DownloadSelection.asks(listOf(file("a.mkv"), folder("Extras"))))
        assertTrue(DownloadSelection.asks(listOf(folder("Extras"))))
    }

    @Test
    fun theThreeAnswers() {
        val chosen = listOf(file("a.mkv"), folder("Extras"), file("b.srt"), folder("Season 2"))
        assertEquals(chosen, DownloadSelection.after(chosen, FolderChoice.ALL))
        assertEquals(listOf(file("a.mkv"), file("b.srt")), DownloadSelection.after(chosen, FolderChoice.FILES_ONLY))
        assertNull(DownloadSelection.after(chosen, FolderChoice.CANCEL))
    }

    @Test
    fun filesOnlyIsNoChoiceWithoutFiles() {
        val folders = listOf(folder("Extras"), folder("Season 2"))
        assertFalse(DownloadSelection.hasFiles(folders))
        assertNull(DownloadSelection.after(folders, FolderChoice.FILES_ONLY))
        assertTrue(DownloadSelection.hasFiles(folders + file("a.mkv")))
    }

    // --- the walk --------------------------------------------------------------------------------

    /** A share as a map of folder path to its rows; a path missing from it fails to list. */
    private val tree: Map<String, List<DriveEntry>> = mapOf(
        "/tail.ts.net/nas/Movies/Season 2" to listOf(folder("Extras"), file("e01.mkv", 1_000), file("e02.mkv", 2_000)),
        "/tail.ts.net/nas/Movies/Season 2/Extras" to listOf(file("trailer.mp4", 300), folder("Empty")),
        "/tail.ts.net/nas/Movies/Season 2/Extras/Empty" to emptyList(),
        "/tail.ts.net/nas/Movies/Docs" to listOf(file("notes.txt", 5), folder("Locked")),
    )

    private val asked = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun list(at: DriveLocation): DriveResult<List<DriveEntry>> {
        asked += at.davPath
        return tree[at.davPath]?.let { DriveResult.Ok(it) } ?: DriveResult.Err(DriveError.AccessDenied)
    }

    @Test
    fun theWalkFindsEveryFolderAndFileWithTheirPaths() = runBlocking {
        val walked = DriveWalk.collect(movies, listOf("Season 2"), ::list)
        val files = walked.items.filterIsInstance<WalkItem.File>().map { it.path.joinToString("/") to it.size }.toSet()
        assertEquals(
            setOf("Season 2/e01.mkv" to 1_000L, "Season 2/e02.mkv" to 2_000L, "Season 2/Extras/trailer.mp4" to 300L),
            files
        )
        val folders = walked.items.filterIsInstance<WalkItem.Folder>().map { it.path.joinToString("/") }.toSet()
        // The empty folder too: the structure is kept, not only what has files in it.
        assertEquals(setOf("Season 2", "Season 2/Extras", "Season 2/Extras/Empty"), folders)
        assertEquals(WalkTotals(folders = 3, files = 3, bytes = 3_300, unlisted = 0, done = true), walked.totals)
    }

    @Test
    fun aFolderComesBeforeAnythingInIt() = runBlocking {
        val walked = DriveWalk.collect(movies, listOf("Season 2", "Docs"), ::list, concurrency = 3)
        walked.items.forEachIndexed { i, item ->
            val parent = item.path.dropLast(1)
            if (parent.isNotEmpty()) {
                val at = walked.items.indexOfFirst { it is WalkItem.Folder && it.path == parent }
                assertTrue("${item.path} before its folder", at in 0 until i)
            }
        }
    }

    @Test
    fun aFolderThatCannotBeListedIsCountedAsSuchAndTheRestGoesOn() = runBlocking {
        val walked = DriveWalk.collect(movies, listOf("Docs", "Season 2"), ::list)
        assertEquals(listOf(listOf("Docs", "Locked")), walked.unlisted)
        assertEquals(4, walked.totals.files)
        assertEquals(3_305L, walked.totals.bytes)
        assertEquals(1, walked.totals.unlisted)
        // Files are listed, never read: one request a folder.
        assertEquals(asked.size, asked.toSet().size)
    }

    @Test
    fun theRunningCountGrowsToTheTotal() = runBlocking {
        val seen = ArrayList<WalkTotals>()
        val walked = DriveWalk.collect(movies, listOf("Season 2"), ::list) { seen += it }
        assertTrue(seen.zipWithNext().all { (a, b) -> b.files >= a.files && b.bytes >= a.bytes })
        assertEquals(walked.totals.files, seen.last().files)
        assertFalse(seen.last().done)
        assertTrue(walked.totals.done)
    }

    @Test
    fun selectedFilesComeAsTheyAre() {
        assertEquals(
            listOf(WalkItem.File(listOf("a.mkv"), 10)),
            DriveWalk.filesOf(listOf(file("a.mkv", 10), folder("Extras")))
        )
    }

    // --- names -----------------------------------------------------------------------------------

    @Test
    fun aNameClashBecomesANumberedName() {
        assertEquals("film.mkv", DownloadNames.freeFile("film.mkv", setOf("other.mkv")))
        assertEquals("film (1).mkv", DownloadNames.freeFile("film.mkv", setOf("film.mkv")))
        assertEquals("film (2).mkv", DownloadNames.freeFile("film.mkv", setOf("FILM.mkv", "film (1).mkv")))
        assertEquals("README (1)", DownloadNames.freeFile("README", setOf("readme")))
    }

    @Test
    fun aFolderKeepsItsDotsWhenNumbered() {
        assertEquals("Season 1.5", DownloadNames.freeFolder("Season 1.5", emptySet()))
        assertEquals("Season 1.5 (1)", DownloadNames.freeFolder("Season 1.5", setOf("season 1.5")))
        assertEquals("Movies (2)", DownloadNames.freeFolder("Movies", setOf("Movies", "Movies (1)")))
    }

    private val temp: File = Files.createTempDirectory("sink").toFile()

    @After
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun aDirectoryNeverHasAnythingWrittenOver() {
        File(temp, "film.mkv").writeText("theirs")
        File(temp, "Season 2").mkdir()
        val sink = DirSink(temp)
        val file = sink.newFile(null, "film.mkv")
        assertEquals("film (1).mkv", file.name)
        sink.write(file).use { it.write("ours".toByteArray()) }
        assertEquals("theirs", File(temp, "film.mkv").readText())
        assertEquals("ours", File(temp, "film (1).mkv").readText())

        val season = sink.newFolder(null, "Season 2")
        assertEquals("Season 2 (1)", season.name)
        val inside = sink.newFolder(season, "Extras")
        val trailer = sink.newFile(inside, "trailer.mp4")
        assertTrue(File(temp, "Season 2 (1)/Extras/trailer.mp4").isFile)
        assertEquals("trailer.mp4", trailer.name)
    }

    @Test
    fun aDirectoryThatIsMissingIsMadeAndANameItCannotHoldIsMadeStorable() {
        val sink = DirSink(File(temp, "Download/Taildrive"))
        val file = sink.newFile(null, "what?.txt")
        assertEquals("what_.txt", file.name)
        assertTrue(File(temp, "Download/Taildrive/what_.txt").isFile)
    }

    @Test
    fun anUnfinishedFileIsTakenAway() {
        val sink = DirSink(temp)
        val file = sink.newFile(null, "half.bin")
        sink.discard(file)
        assertFalse(File(temp, "half.bin").exists())
    }

    // --- where it goes ---------------------------------------------------------------------------

    @Test
    fun anInstallThatNeverChoseGoesWhereTaildropGoes() {
        assertEquals(DownloadMode.SAME_AS_TAILDROP, DownloadMode.of(null, hasChosen = false))
        assertEquals(DownloadMode.SAME_AS_TAILDROP, DownloadMode.of("", hasChosen = false))
        // One that chose a folder before there were three choices keeps it.
        assertEquals(DownloadMode.FOLDER, DownloadMode.of("", hasChosen = true))
        assertEquals(DownloadMode.ASK, DownloadMode.of("ask", hasChosen = true))
        assertEquals(DownloadMode.SAME_AS_TAILDROP, DownloadMode.of("taildrop", hasChosen = true))
        assertEquals(DownloadMode.FOLDER, DownloadMode.of("folder", hasChosen = false))
    }

    private val taildropDefault = File("/storage/emulated/0/Download/Taildrop")
    private val fallback = ReceiveFolder.Dir(taildropDefault, isDefault = true)

    /** Taildrop's folder as Settings resolves it from what is stored, on [device]. */
    private fun taildrop(tree: String?, path: String?, device: DeviceClass = DeviceClass.HANDHELD) =
        TaildropFolder.resolve(tree, path, device, if (device == DeviceClass.TV) taildropDefault else null)

    @Test
    fun theDefaultIsTheTaildropFolderAndFollowsIt() {
        val same = DownloadMode.SAME_AS_TAILDROP
        // A television that never chose: Download/Taildrop, as Taildrop has it.
        assertEquals(fallback, DownloadPlace.of(same, taildrop(null, null, DeviceClass.TV), fallback, null))
        // Taildrop's folder changed to a directory, then to a SAF tree: the downloads follow, each time.
        val movies = ReceiveFolder.Dir(File("/storage/emulated/0/Movies"))
        assertEquals(movies, DownloadPlace.of(same, taildrop(null, "/storage/emulated/0/Movies/"), fallback, null))
        val tree = ReceiveFolder.Tree("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTaildrop")
        assertEquals(tree, DownloadPlace.of(same, taildrop(tree.uri, "/storage/emulated/0/Movies"), fallback, null))
        // A phone that keeps received files in the app: the folder a television would start with.
        assertEquals(fallback, DownloadPlace.of(same, taildrop(null, null), fallback, null))
        // A folder chosen for the downloads is no part of it.
        assertEquals(movies, DownloadPlace.of(same, movies, fallback, ReceiveFolder.Dir(File("/sdcard/Other"))))
    }

    @Test
    fun theOtherChoicesTakeTheirOwnFolder() {
        val chosen = ReceiveFolder.Dir(File("/storage/emulated/0/Download/Films"))
        assertEquals(chosen, DownloadPlace.of(DownloadMode.FOLDER, fallback, fallback, chosen))
        assertNull(DownloadPlace.of(DownloadMode.FOLDER, fallback, fallback, null))
        assertNull(DownloadPlace.of(DownloadMode.ASK, fallback, fallback, chosen))
    }

    @Test
    fun askOrGoStraightThere() {
        val dir = ReceiveFolder.Dir(File("/storage/emulated/0/Download/Taildrop"))
        assertEquals(DownloadDestination.Default(dir), DownloadDestination.of(DownloadMode.SAME_AS_TAILDROP, dir, writable = true))
        assertEquals(DownloadDestination.Default(dir), DownloadDestination.of(DownloadMode.FOLDER, dir, writable = true))
        // Ask every time asks, whatever folder there is.
        assertEquals(DownloadDestination.Ask(AskReason.SETTING), DownloadDestination.of(DownloadMode.ASK, dir, writable = true))
        // A folder that cannot be written: asked for, and said why.
        assertEquals(DownloadDestination.Ask(AskReason.TAILDROP_UNWRITABLE), DownloadDestination.of(DownloadMode.SAME_AS_TAILDROP, dir, writable = false))
        assertEquals(DownloadDestination.Ask(AskReason.TAILDROP_UNWRITABLE), DownloadDestination.of(DownloadMode.SAME_AS_TAILDROP, null, writable = false))
        assertEquals(DownloadDestination.Ask(AskReason.FOLDER_GONE), DownloadDestination.of(DownloadMode.FOLDER, dir, writable = false))
        assertEquals(DownloadDestination.Ask(AskReason.FOLDER_GONE), DownloadDestination.of(DownloadMode.FOLDER, null, writable = false))
    }

    @Test
    fun theStoredFolder() {
        assertNull(TaildriveDownloadFolder.resolve(null, null))
        assertNull(TaildriveDownloadFolder.resolve("", ""))
        assertEquals(ReceiveFolder.Tree("content://x/tree/1"), TaildriveDownloadFolder.resolve("content://x/tree/1", "/sdcard/Download"))
        assertEquals(ReceiveFolder.Dir(File("/storage/emulated/0/Download")), TaildriveDownloadFolder.resolve("", "/storage/emulated/0/Download/"))
    }

    @Test
    fun aDirectoryAsTheStorageProviderNamesIt() {
        assertEquals("primary:Download/Movies", TaildriveDownloadFolder.storageDocumentId(File("/storage/emulated/0/Download/Movies")))
        assertEquals("primary:", TaildriveDownloadFolder.storageDocumentId(File("/storage/emulated/0")))
        assertEquals("1234-ABCD:Films", TaildriveDownloadFolder.storageDocumentId(File("/storage/1234-ABCD/Films")))
        assertNull(TaildriveDownloadFolder.storageDocumentId(File("/data/local/tmp")))
    }

    @Test
    fun aFailureIsNamedByWhatWentWrong() {
        assertEquals(DownloadFailReason.NOT_FOUND, TaildriveDownloads.failReason(RangeNotFoundException()))
        assertEquals(DownloadFailReason.DENIED, TaildriveDownloads.failReason(RangeForbiddenException()))
        assertEquals(DownloadFailReason.TIMEOUT, TaildriveDownloads.failReason(RangeTimeoutException()))
        assertEquals(DownloadFailReason.NETWORK, TaildriveDownloads.failReason(RangeIoException("HTTP 502", 502)))
        assertEquals(DownloadFailReason.NOT_FOUND, TaildriveDownloads.failReason(Exception("WebDAV GET failed: HTTP 404")))
    }
}
