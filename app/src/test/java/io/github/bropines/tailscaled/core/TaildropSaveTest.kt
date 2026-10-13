package io.github.bropines.tailscaled.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Saving a received file into a folder that is a directory by its path (a television's receive folder). */
class TaildropSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun received(name: String, bytes: ByteArray = name.toByteArray()): File =
        File(tmp.newFolder(), name).apply { writeBytes(bytes) }

    @Test
    fun aFileIsCopiedUnderItsOwnNameAndTheOriginalIsLeftToTheCaller() {
        val dir = tmp.newFolder("Taildrop")
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val source = received("holiday.mp4", bytes)

        val copy = TaildropSave.copyIntoDir(dir, source, source.name)

        assertEquals(File(dir, "holiday.mp4"), copy)
        assertArrayEquals(bytes, copy.readBytes())
        assertTrue(source.exists())
    }

    @Test
    fun aMissingFolderIsMade() {
        val dir = File(tmp.root, "Download/Taildrop")
        val copy = TaildropSave.copyIntoDir(dir, received("a.txt"), "a.txt")
        assertEquals(File(dir, "a.txt"), copy)
    }

    @Test
    fun aTakenNameGetsANumberBeforeTheExtension() {
        val dir = tmp.newFolder("Taildrop")
        val source = received("photo.jpg")
        assertEquals("photo.jpg", TaildropSave.copyIntoDir(dir, source, "photo.jpg").name)
        assertEquals("photo (1).jpg", TaildropSave.copyIntoDir(dir, source, "photo.jpg").name)
        assertEquals("photo (2).jpg", TaildropSave.copyIntoDir(dir, source, "photo.jpg").name)
        assertEquals(setOf("photo.jpg", "photo (1).jpg", "photo (2).jpg"), dir.list()!!.toSet())
    }

    /** Shared storage and memory cards do not tell "A.jpg" from "a.jpg"; nothing is ever overwritten. */
    @Test
    fun aNameTakenInAnotherCaseIsTakenAndNothingIsOverwritten() {
        val dir = tmp.newFolder("Taildrop")
        File(dir, "Report.PDF").writeText("the one that was there")
        val copy = TaildropSave.copyIntoDir(dir, received("report.pdf"), "report.pdf")
        assertEquals("report (1).pdf", copy.name)
        assertEquals("the one that was there", File(dir, "Report.PDF").readText())
    }

    @Test
    fun aNameWithoutAnExtensionIsNumberedAtTheEnd() {
        assertEquals("README (1)", TaildropSave.freeName("README", setOf("README")))
        assertEquals(".profile (1)", TaildropSave.freeName(".profile", setOf(".profile")))
        assertEquals("a.tar (1).gz", TaildropSave.freeName("a.tar.gz", setOf("a.tar.gz")))
    }

    /** What a sender on Linux may call a file and a FAT stick or shared storage refuses. */
    @Test
    fun charactersStorageRefusesBecomeUnderscores() {
        assertEquals("what_ 3_4.txt", TaildropSave.storableName("what? 3:4.txt"))
        assertEquals("a_b_c_d_e_f_g", TaildropSave.storableName("a\"b*c<d>e|f\\g"))
        assertEquals("tab_here", TaildropSave.storableName("tab\there"))
        assertEquals("Отчёт — итог (2).pdf", TaildropSave.storableName("Отчёт — итог (2).pdf"))
        assertEquals("_", TaildropSave.storableName(".."))
        assertEquals("_", TaildropSave.storableName(""))

        val dir = tmp.newFolder("Taildrop")
        assertEquals("what_.txt", TaildropSave.copyIntoDir(dir, received("src"), "what?.txt").name)
    }

    @Test
    fun aReceivedFileThatIsGoneIsAnErrorAndLeavesNothing() {
        val dir = tmp.newFolder("Taildrop")
        assertThrows(IOException::class.java) { TaildropSave.copyIntoDir(dir, File(tmp.root, "gone.bin"), "gone.bin") }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    /** A drive pulled out, a folder deleted and a file put in its place: the file stays in the inbox. */
    @Test
    fun aFolderThatCannotBeMadeIsAnError() {
        val blocker = tmp.newFile("Taildrop")
        assertThrows(IOException::class.java) { TaildropSave.copyIntoDir(blocker, received("a.txt"), "a.txt") }
        assertThrows(IOException::class.java) { TaildropSave.copyIntoDir(File(blocker, "inside"), received("b.txt"), "b.txt") }
        assertFalse(blocker.isDirectory)
    }
}
