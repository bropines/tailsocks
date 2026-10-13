package io.github.bropines.tailscaled.core

import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the documents provider says about a file of a share, and how it opens one. The cursor
 * and the network are the provider's; the answer is decided here.
 */
class TaildriveDocumentsTest {
    private val mimeOf: (String) -> String = DriveMime::guess

    private val video = DriveDocId("default", "/home.tailnet/laptop/downloads/film.mp4")

    private fun file(path: String, size: Long = 38_189_343, modified: Long = 1_700_000_000_000) =
        WebDavFile(path, isDirectory = false, size = size, lastModified = modified)

    private fun folder(path: String) = WebDavFile("$path/", isDirectory = true, size = 0, lastModified = 0)

    // --- The id -----------------------------------------------------------------------------------

    @Test
    fun anIdIsTheProfileAndThePathAndThePathKeepsItsColons() {
        val id = DriveDocId.parse("drive_path:default:/home.tailnet/laptop/downloads/a:b/film.mp4")!!
        assertEquals("default", id.accountId)
        assertEquals("/home.tailnet/laptop/downloads/a:b/film.mp4", id.path)
        assertEquals("film.mp4", id.name)
        assertEquals(5, id.depth)
        assertEquals("drive_path:default:/home.tailnet/laptop/downloads/a:b/film.mp4", id.toString())
    }

    @Test
    fun whatIsNotADriveIdIsNoId() {
        for (bad in listOf(null, "", "root", "drive_root", "drive_root:default", "drive_account:default", "drive_path:", "drive_path:default", "drive_path::/a", "file:default/a")) {
            assertNull(bad, DriveDocId.parse(bad))
        }
    }

    @Test
    fun theNameAndTheDepthOfAFolderIgnoreATrailingSlash() {
        val share = DriveDocId("default", "/home.tailnet/laptop/downloads/")
        assertEquals("downloads", share.name)
        assertEquals(3, share.depth)
        assertEquals(0, DriveDocId("default", "/").depth)
    }

    // --- What the server said ---------------------------------------------------------------------

    @Test
    fun aFileIsWhatTheServerSaidItIs() {
        val doc = DriveDocuments.fromListing(video, listOf(file(video.path)), mimeOf)!!
        assertEquals("film.mp4", doc.name)
        assertEquals("video/mp4", doc.mime)
        assertFalse(doc.isDirectory)
        assertEquals(38_189_343L, doc.size)
        assertEquals(1_700_000_000_000L, doc.modified)
        assertEquals(DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE, doc.flags)
    }

    @Test
    fun aFolderIsAFolderOfNoSizeThatCanTakeNewFiles() {
        val path = "/home.tailnet/laptop/downloads"
        val doc = DriveDocuments.fromListing(DriveDocId("default", path), listOf(folder(path), file("$path/a.mp4")), mimeOf)!!
        assertEquals("downloads", doc.name)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, doc.mime)
        assertTrue(doc.isDirectory)
        assertEquals(0L, doc.size)
        assertTrue(doc.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
    }

    @Test
    fun theEntryForThePathItselfIsTheOneUsedWhereverItIs() {
        val path = "/home.tailnet/laptop/downloads"
        val listing = listOf(file("$path/other.mkv", size = 5), folder(path))
        val doc = DriveDocuments.fromListing(DriveDocId("default", path), listing, mimeOf)!!
        assertEquals("downloads", doc.name)
        assertTrue(doc.isDirectory)
        // None of them is the path: the first, as the provider always took it.
        assertEquals("other.mkv", DriveDocuments.fromListing(video, listing, mimeOf)!!.name)
    }

    @Test
    fun anEmptyListingIsNoDocument() {
        assertNull(DriveDocuments.fromListing(video, emptyList(), mimeOf))
        try {
            DriveDocuments.resolve(video, { emptyList() }, mimeOf)
            fail("expected FileNotFoundException")
        } catch (e: FileNotFoundException) {
        }
    }

    // --- What a failed request says ---------------------------------------------------------------

    @Test
    fun aFileThatIsNotThereOrNotOursIsNotFound() {
        for (code in listOf(404, 410, 401, 403, 407)) {
            try {
                DriveDocuments.fromFailure(video, WebDavHttpException("PROPFIND", code, ""), mimeOf)
                fail("HTTP $code should be FileNotFoundException")
            } catch (e: FileNotFoundException) {
                assertFalse("the message names no file: ${e.message}", e.message.orEmpty().contains("film"))
            }
        }
    }

    @Test
    fun aRequestThatFailedStillNamesTheFileAndNeverMakesItAFolderOfNoContent() {
        val failures = listOf(
            WebDavHttpException("PROPFIND", 500, ""),
            WebDavHttpException("PROPFIND", 502, ""),
            WebDavHttpException("PROPFIND", 503, ""),
            ConnectException("Connection refused"),
            SocketTimeoutException("timeout"),
            Exception("SOCKS5 proxy is not running"),
            java.io.IOException("unexpected end of stream"),
        )
        for (failure in failures) {
            val doc = DriveDocuments.fromFailure(video, failure, mimeOf)
            assertEquals("film.mp4", doc.name)
            assertEquals("video/mp4", doc.mime)
            assertFalse(failure.toString(), doc.isDirectory)
            // Not known is not 0: a player told 0 bytes takes the file for empty.
            assertNull(failure.toString(), doc.size)
            assertNull(doc.modified)
            assertEquals("nothing is allowed on what was not seen", 0, doc.flags)
        }
    }

    @Test
    fun aPlaceDownToAShareIsAFolderWhateverItIsCalled() {
        for (path in listOf("/home.tailnet", "/home.tailnet/laptop", "/home.tailnet/laptop/films.mp4")) {
            val doc = DriveDocuments.fromFailure(DriveDocId("default", path), ConnectException(), mimeOf)
            assertTrue(path, doc.isDirectory)
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, doc.mime)
            assertEquals(0L, doc.size)
        }
    }

    @Test
    fun belowAShareAFailedNameIsAFolderOnlyWhenItLooksLikeOne() {
        fun failed(name: String) = DriveDocuments.fromFailure(DriveDocId("default", "/t/d/share/sub/$name"), ConnectException(), mimeOf)
        assertTrue(failed("photos").isDirectory)
        assertTrue(failed(".hidden").isDirectory)
        assertFalse(failed("notes.txt").isDirectory)
        assertEquals("text/plain", failed("notes.txt").mime)
        assertFalse(failed("thing.xyzzy").isDirectory)
        assertEquals(DriveMime.FALLBACK, failed("thing.xyzzy").mime)
        assertFalse(failed("song.flac").isDirectory)
    }

    // --- Asked on a thread of ours ----------------------------------------------------------------

    @Test
    fun resolveGivesTheRealAnswerWhenTheServerAnswers() {
        val doc = DriveDocuments.resolve(video, { path ->
            assertEquals(video.path, path)
            listOf(file(path))
        }, mimeOf)
        assertEquals("video/mp4", doc.mime)
        assertEquals(38_189_343L, doc.size)
    }

    @Test
    fun resolveTurnsAFailureIntoTheFailureAnswer() {
        val doc = DriveDocuments.resolve(video, { throw WebDavHttpException("PROPFIND", 502, "") }, mimeOf)
        assertNull(doc.size)
        assertFalse(doc.isDirectory)
        try {
            DriveDocuments.resolve(video, { throw WebDavHttpException("PROPFIND", 404, "") }, mimeOf)
            fail("expected FileNotFoundException")
        } catch (e: FileNotFoundException) {
        }
    }

    /**
     * The regression for the report. Asked on the binder thread of a caller that is on its main
     * thread, the network threw, the provider's catch-all answered, and the player was told
     * a folder of size 0 for its 36 MB video. Asked on a thread of ours, as the provider now asks,
     * the same call is the file.
     */
    @Test
    fun aVideoIsNotAnEmptyFolderBecauseTheCallerIsOnItsMainThread() {
        val binderThread = Thread.currentThread()
        val list: (String) -> List<WebDavFile> = { path ->
            if (Thread.currentThread() === binderThread) throw android.os.NetworkOnMainThreadException()
            listOf(file(path))
        }

        // What it used to be: the calling thread asks, and the answer is wrong without a word.
        val before = DriveDocuments.resolve(video, list, mimeOf)
        assertNull("the answer for the exception is not the file", before.size)

        // What it is: the same request, handed to the worker.
        val after = ProviderWorker.run { DriveDocuments.resolve(video, list, mimeOf) }
        assertEquals("video/mp4", after.mime)
        assertFalse(after.isDirectory)
        assertEquals(38_189_343L, after.size)
        assertNotNull(after.modified)
    }

    // --- Modes ------------------------------------------------------------------------------------

    @Test
    fun aModeWritesWhenItHasAWInIt() {
        for (mode in listOf("w", "wt", "wa", "rw", "rwt", "rwx")) assertTrue(mode, OpenModes.writes(mode))
        for (mode in listOf(null, "", "r", "x", "read")) assertFalse(mode.toString(), OpenModes.writes(mode))
    }

    @Test
    fun onlyTheModesTheFrameworkParsesAreStandard() {
        for (mode in listOf("r", "w", "wt", "wa", "rw", "rwt")) assertTrue(mode, OpenModes.isStandard(mode))
        for (mode in listOf("", "rwx", "x", "read", "rt", "wr", "R")) assertFalse(mode, OpenModes.isStandard(mode))
    }
}
