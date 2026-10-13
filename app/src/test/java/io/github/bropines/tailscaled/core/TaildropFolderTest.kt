package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaildropFolderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTaildrop"
    private val default = File("/storage/emulated/0/Download/Taildrop")

    // --- what an install keeps and what a new one starts with -------------------------------

    /** A phone that chose a SAF folder before paths existed: nothing else is stored, and nothing changes. */
    @Test
    fun anExistingSafFolderStaysTheFolder() {
        assertEquals(ReceiveFolder.Tree(tree), TaildropFolder.resolve(tree, null, DeviceClass.HANDHELD, null))
        assertEquals(ReceiveFolder.Tree(tree), TaildropFolder.resolve(tree, "", DeviceClass.HANDHELD, default))
    }

    /** Not a television's default either: the interface switched to TV by hand on that phone. */
    @Test
    fun aSafFolderIsNotReplacedByATelevisionsDefault() {
        assertEquals(ReceiveFolder.Tree(tree), TaildropFolder.resolve(tree, null, DeviceClass.TV, default))
    }

    /** Both stored can only come from outside (set() forgets the other kind): the SAF grant of this install wins. */
    @Test
    fun aSafFolderWinsOverAStoredPath() {
        assertEquals(ReceiveFolder.Tree(tree), TaildropFolder.resolve(tree, "/storage/1234-ABCD/Movies", DeviceClass.TV, default))
    }

    @Test
    fun aChosenPathIsTheFolderOnAnyDevice() {
        val chosen = ReceiveFolder.Dir(File("/storage/1234-ABCD/Movies"))
        assertEquals(chosen, TaildropFolder.resolve(null, "/storage/1234-ABCD/Movies", DeviceClass.TV, default))
        assertEquals(chosen, TaildropFolder.resolve("", "/storage/1234-ABCD/Movies", DeviceClass.HANDHELD, null))
        assertFalse(chosen.isDefault)
    }

    @Test
    fun aStoredPathIsCleaned() {
        assertEquals(
            ReceiveFolder.Dir(File("/storage/emulated/0/Download")),
            TaildropFolder.resolve(null, "/storage/emulated/0/Download/Taildrop/../", DeviceClass.TV, default)
        )
    }

    /** A phone or a tablet with nothing chosen: received files wait in the inbox, as before. */
    @Test
    fun aHandheldWithNothingChosenHasNoFolder() {
        assertNull(TaildropFolder.resolve(null, null, DeviceClass.HANDHELD, default))
        assertNull(TaildropFolder.resolve("", "", DeviceClass.HANDHELD, default))
    }

    @Test
    fun aTelevisionWithNothingChosenStartsWithTheDefault() {
        assertEquals(ReceiveFolder.Dir(default, isDefault = true), TaildropFolder.resolve(null, null, DeviceClass.TV, default))
        assertEquals(ReceiveFolder.Dir(default, isDefault = true), TaildropFolder.resolve("", "", DeviceClass.TV, default))
        // No shared storage to put it on: nothing, not a crash.
        assertNull(TaildropFolder.resolve(null, null, DeviceClass.TV, null))
    }

    /** The default chosen by hand is a choice: it stays when the device stops being taken for a television. */
    @Test
    fun theDefaultChosenByHandIsAChoice() {
        val chosen = TaildropFolder.resolve(null, default.path, DeviceClass.HANDHELD, null)
        assertEquals(ReceiveFolder.Dir(default), chosen)
    }

    // --- the folder's name ----------------------------------------------------------------

    @Test
    fun aDirectoryIsNamedByItsPathUnderTheStorage() {
        assertEquals("Download/Taildrop", TaildropFolder.pathLabel(File("/storage/emulated/0/Download/Taildrop")))
        assertEquals("Download/Taildrop", TaildropFolder.pathLabel(File("/storage/emulated/10/Download/Taildrop/")))
        assertEquals("Movies/From phone", TaildropFolder.pathLabel(File("/storage/1234-ABCD/Movies/From phone")))
        assertEquals("Download", TaildropFolder.pathLabel(File("/sdcard/Download")))
    }

    @Test
    fun theTopOfAStorageAndAPathOnNoneKeepTheWholePath() {
        assertEquals("/storage/emulated/0", TaildropFolder.pathLabel(File("/storage/emulated/0")))
        assertEquals("/storage/1234-ABCD", TaildropFolder.pathLabel(File("/storage/1234-ABCD/")))
        assertEquals("/data/local/tmp/in", TaildropFolder.pathLabel(File("/data/local/tmp/in")))
        assertEquals("/mnt/media_rw/1234-ABCD/x", TaildropFolder.pathLabel(File("/mnt/media_rw/1234-ABCD/x")))
    }

    // --- whether files can go there ---------------------------------------------------------

    @Test
    fun aWritableDirectoryIsReadyWhateverIsGranted() {
        assertEquals(TaildropFolder.State.READY, TaildropFolder.stateOf(true, StorageAccess.GRANTED))
        // Downloads on Android 11 and later: the app's own files need no grant.
        assertEquals(TaildropFolder.State.READY, TaildropFolder.stateOf(true, StorageAccess.NEEDS_ALL_FILES))
    }

    @Test
    fun anUnwritableDirectoryAsksForTheAccessThatIsMissing() {
        assertEquals(TaildropFolder.State.NEEDS_ACCESS, TaildropFolder.stateOf(false, StorageAccess.NEEDS_ALL_FILES))
        assertEquals(TaildropFolder.State.NEEDS_ACCESS, TaildropFolder.stateOf(false, StorageAccess.NEEDS_PERMISSION))
    }

    /** Everything granted and still no way to write: the drive is gone, and no grant brings it back. */
    @Test
    fun anUnwritableDirectoryWithEverythingGrantedIsUnavailable() {
        assertEquals(TaildropFolder.State.UNAVAILABLE, TaildropFolder.stateOf(false, StorageAccess.GRANTED))
    }

    @Test
    fun aFileCanBeCreatedInADirectoryThatIsMadeForIt() {
        val dir = File(tmp.root, "Download/Taildrop")
        assertTrue(TaildropFolder.canCreateIn(dir))
        assertTrue(dir.isDirectory)
        // The try leaves nothing behind.
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertTrue(TaildropFolder.canCreateIn(dir))
    }

    @Test
    fun nothingCanBeCreatedWhereAFileStandsInTheWay() {
        val file = tmp.newFile("not-a-folder")
        assertFalse(TaildropFolder.canCreateIn(file))
        assertFalse(TaildropFolder.canCreateIn(File(file, "inside")))
    }
}
