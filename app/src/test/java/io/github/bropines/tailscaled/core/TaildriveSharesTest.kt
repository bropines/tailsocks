package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaildriveSharesTest {
    private val none = emptyList<String>()

    @Test
    fun aShareIsNamedAfterItsFolder() {
        assertEquals("Download", suggestShareName("/storage/emulated/0/Download", none))
        assertEquals("Movies", suggestShareName("/storage/emulated/0/Movies/", none))
        assertEquals("Camera", suggestShareName("/storage/emulated/0/DCIM/Camera", none))
    }

    /** What Taildrive does not take is left out, as the field does when it is typed. */
    @Test
    fun onlyLettersDigitsAndUnderscoreStay() {
        assertEquals("MyMovies2", suggestShareName("/storage/emulated/0/My Movies (2)", none))
        assertEquals("a_b", suggestShareName("/x/a_b", none))
        // A removable drive's root is its volume label, with the dash gone.
        assertEquals("ABCD1234", suggestShareName("/storage/ABCD-1234", none))
    }

    /** A folder named in Cyrillic has no letters left: a name that can be accepted as it is. */
    @Test
    fun aNameWithNothingLeftIsShare() {
        assertEquals("share", suggestShareName("/storage/emulated/0/Фильмы", none))
        assertEquals("share_2", suggestShareName("/storage/emulated/0/Музыка", listOf("share")))
    }

    @Test
    fun theWholeStorageIsTheOneTheSwitchShares() {
        assertEquals("sdcard", suggestShareName("/storage/emulated/0", none))
        assertEquals("sdcard", suggestShareName("/storage/emulated/0/", none))
        assertEquals("sdcard_2", suggestShareName("/storage/emulated/0", listOf("sdcard")))
    }

    /** `Music` and `music` are one share to Taildrive, as the screen's own duplicate check has it. */
    @Test
    fun aTakenNameGetsANumberWhateverItsCase() {
        assertEquals("Music_2", suggestShareName("/storage/emulated/0/Music", listOf("Music")))
        assertEquals("Music_2", suggestShareName("/storage/emulated/0/Music", listOf("music")))
        assertEquals("Music_3", suggestShareName("/storage/emulated/0/Music", listOf("Music", "MUSIC_2")))
        assertEquals("Music", suggestShareName("/storage/emulated/0/Music", listOf("Music_2", "Movies")))
    }

    @Test
    fun aLongNameStaysAName() {
        val name = suggestShareName("/storage/emulated/0/" + "a".repeat(200), none)
        assertEquals(40, name.length)
    }

    @Test
    fun theFullStoragePathIsRecognisedWithAndWithoutTheSlash() {
        assertTrue(isFullStoragePath("/storage/emulated/0"))
        assertTrue(isFullStoragePath("/storage/emulated/0/"))
        assertFalse(isFullStoragePath("/storage/emulated/0/Download"))
        assertFalse(isFullStoragePath("/storage/emulated/10"))
    }

    // What to run from a computer ---------------------------------------------------------------

    @Test
    fun fromAndroid11ItIsTheAllFilesAppOp() {
        assertEquals(
            "adb shell appops set io.github.bropines.tailscaled MANAGE_EXTERNAL_STORAGE allow",
            storageGrantCommand(30, "io.github.bropines.tailscaled")
        )
        assertEquals(storageGrantCommand(30, "p"), storageGrantCommand(36, "p"))
    }

    @Test
    fun beforeThatItIsTheStoragePermission() {
        val command = storageGrantCommand(29, "io.github.bropines.tailscaled")
        assertTrue(command.contains("pm grant io.github.bropines.tailscaled android.permission.READ_EXTERNAL_STORAGE"))
        assertTrue(command.contains("pm grant io.github.bropines.tailscaled android.permission.WRITE_EXTERNAL_STORAGE"))
        assertFalse(command.contains("appops"))
    }
}
