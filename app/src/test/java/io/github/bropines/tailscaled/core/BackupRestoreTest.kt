package io.github.bropines.tailscaled.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRestoreTest {
    private lateinit var root: File
    private lateinit var prefs: File
    private lateinit var states: File
    private lateinit var files: File

    @Before fun setUp() {
        root = Files.createTempDirectory("restore").toFile().canonicalFile
        prefs = File(root, "shared_prefs").apply { mkdirs() }
        files = File(root, "files").apply { mkdirs() }
        states = File(files, "states").apply { mkdirs() }
    }

    @After fun tearDown() {
        root.deleteRecursively()
        BackupRestore.PasswordHold.clear()
    }

    private fun target(name: String) = BackupRestore.restoreTarget(name, prefs, states, files)

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // --- restoreTarget: the allow-list between a crafted archive and a file written as the app

    @Test fun aRestartAfterARestoreComesBackDisconnected() {
        val xml = """<map>
    <boolean name="desired_running" value="true" />
    <boolean name="other_flag" value="true" />
</map>"""
        val out = BackupRestore.PendingRestart.withoutDesiredRunning(xml)
        assertTrue(out.contains("""name="desired_running" value="false""""))
        assertTrue(out.contains("""name="other_flag" value="true""""))
        assertEquals(out, BackupRestore.PendingRestart.withoutDesiredRunning(out))
    }

    @Test fun whatABackupContainsIsRestored() {
        assertEquals(File(prefs, "tailsocks_global.xml"), target("shared_prefs/tailsocks_global.xml"))
        assertEquals(File(files, "sent_history.json"), target("files/sent_history.json"))
        assertEquals(File(states, "acc1/tailscaled.state"), target("files/states/acc1/tailscaled.state"))
    }

    @Test fun escapesAreRefused() {
        assertNull(target("files/states/../control_proxy.env"))
        assertNull(target("shared_prefs/../files/x.xml"))
        assertNull(target("../outside.xml"))
        assertNull(target("files/states/a/../../../x"))
    }

    @Test fun absolutePathsAreRefused() {
        assertNull(target("/data/data/x/shared_prefs/a.xml"))
        assertNull(target("/files/sent_history.json"))
    }

    @Test fun emptySegmentsAreRefused() {
        assertNull(target("shared_prefs//a.xml"))
        assertNull(target("files/states//x"))
        assertNull(target("files/states/"))
        assertNull(target(""))
    }

    @Test fun preferenceFilesAreFlatXmlOnly() {
        assertNull(target("shared_prefs/a/b.xml"))
        assertNull(target("shared_prefs/a.txt"))
        assertNull(target("shared_prefs/"))
    }

    @Test fun otherFilesAreRefused() {
        // The Root Mode boot script sourced this one as root.
        assertNull(target("files/control_proxy.env"))
        assertNull(target("files/libtailscale.so"))
        assertNull(target("databases/x.db"))
        assertNull(target("files/sent_history.json.bak"))
    }

    // --- restoreTargets / writeRestore

    @Test fun anEntryOutsideTheSetRefusesTheWholeArchive() {
        val archive = zip(
            "shared_prefs/a.xml" to "<map/>",
            "files/control_proxy.env" to "evil",
        )
        try {
            BackupRestore.restoreTargets(archive, prefs, states, files)
            fail("expected a refusal")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("files/control_proxy.env"))
        }
        assertFalse(File(prefs, "a.xml").exists())
    }

    @Test fun theManifestIsNotATarget() {
        val archive = zip(
            BackupFormat.MANIFEST_ENTRY to "{}",
            "shared_prefs/a.xml" to "<map/>",
        )
        assertEquals(setOf("shared_prefs/a.xml"), BackupRestore.restoreTargets(archive, prefs, states, files).keys)
    }

    @Test fun writeRestoreWritesEntriesAndLeavesTheNetmapCacheOut() {
        val archive = zip(
            "shared_prefs/a.xml" to "<map>restored</map>",
            "files/states/acc1/tailscaled.state" to "state",
            "files/states/acc1/profile-data/p1/netmap-cache/x" to "old map",
        )
        val targets = BackupRestore.restoreTargets(archive, prefs, states, files)
        BackupRestore.writeRestore(archive, targets)
        assertEquals("<map>restored</map>", File(prefs, "a.xml").readText())
        assertEquals("state", File(states, "acc1/tailscaled.state").readText())
        assertFalse(File(states, "acc1/profile-data/p1/netmap-cache/x").exists())
    }

    @Test fun writeRestoreTwiceLeavesTheRestoredFiles() {
        // The restart writes the files again over whatever the stop wrote meanwhile.
        val archive = zip("shared_prefs/a.xml" to "<map>restored</map>")
        val targets = BackupRestore.restoreTargets(archive, prefs, states, files)
        BackupRestore.writeRestore(archive, targets)
        File(prefs, "a.xml").writeText("<map>old in-memory values</map>")
        BackupRestore.writeRestore(archive, targets)
        assertEquals("<map>restored</map>", File(prefs, "a.xml").readText())
    }

    // --- prefsToJson / jsonToPrefValues

    @Test fun prefsToJsonKeepsTypes() {
        val json = BackupRestore.prefsToJson(
            mapOf("s" to "x", "b" to true, "i" to 3, "l" to 4L, "f" to 1.5f, "set" to setOf("a", "b"), "n" to null)
        )
        assertEquals(JsonPrimitive("x"), json["s"])
        assertEquals(JsonPrimitive(true), json["b"])
        assertEquals(JsonPrimitive(3), json["i"])
        assertEquals(JsonPrimitive(4L), json["l"])
        assertEquals(JsonPrimitive(1.5f), json["f"])
        assertEquals(listOf("a", "b"), json["set"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("a null value is left out", json.containsKey("n"))
    }

    @Test fun aStringThatReadsLikeANumberOrBoolStaysAString() {
        val back = BackupRestore.jsonToPrefValues(
            BackupRestore.prefsToJson(mapOf("port" to "1080", "flag" to "true", "on" to false))
        )
        assertEquals("1080", back["port"])
        assertEquals("true", back["flag"])
        assertEquals(false, back["on"])
    }

    @Test fun jsonToPrefValuesSkipsWhatItDidNotWrite() {
        val back = BackupRestore.jsonToPrefValues(buildJsonObject {
            put("n", 3)
            put("s", "x")
        })
        assertEquals(mapOf<String, Any>("s" to "x"), back)
    }

    // --- BackupCrypto

    @Test fun cryptoRoundTrip() {
        val data = "secret archive".toByteArray()
        val sealed = BackupCrypto.encrypt(data, "pw".toCharArray())
        assertFalse(sealed.contentEquals(data))
        assertArrayEquals(data, BackupCrypto.decrypt(sealed, "pw".toCharArray()))
    }

    @Test fun cryptoWrongPasswordFails() {
        val sealed = BackupCrypto.encrypt("x".toByteArray(), "right".toCharArray())
        try {
            BackupCrypto.decrypt(sealed, "wrong".toCharArray())
            fail("a wrong password must not decrypt")
        } catch (e: java.security.GeneralSecurityException) {
        }
    }

    @Test fun cryptoTamperedDataFails() {
        val sealed = BackupCrypto.encrypt("some archive bytes".toByteArray(), "pw".toCharArray())
        sealed[sealed.size - 3] = (sealed[sealed.size - 3].toInt() xor 1).toByte()
        try {
            BackupCrypto.decrypt(sealed, "pw".toCharArray())
            fail("tampered data must not decrypt")
        } catch (e: java.security.GeneralSecurityException) {
        }
    }

    @Test fun cryptoTruncatedDataFails() {
        val sealed = BackupCrypto.encrypt("some archive bytes".toByteArray(), "pw".toCharArray())
        try {
            BackupCrypto.decrypt(sealed.copyOf(10), "pw".toCharArray())
            fail("too short")
        } catch (e: IllegalArgumentException) {
        }
        try {
            BackupCrypto.decrypt(sealed.copyOf(sealed.size - 1), "pw".toCharArray())
            fail("truncated data must not decrypt")
        } catch (e: java.security.GeneralSecurityException) {
        }
    }

    @Test fun cryptoRefusesAnEmptyPassword() {
        try {
            BackupCrypto.encrypt("x".toByteArray(), CharArray(0))
            fail("an empty password must be refused")
        } catch (e: IllegalArgumentException) {
        }
    }

    // --- PasswordHold

    @Test fun theHeldPasswordIsHandedOverOnce() {
        BackupRestore.PasswordHold.put("pw".toCharArray())
        assertArrayEquals("pw".toCharArray(), BackupRestore.PasswordHold.take())
        assertNull(BackupRestore.PasswordHold.take())
    }

    @Test fun anEmptyOrClearedPasswordIsNone() {
        BackupRestore.PasswordHold.put(CharArray(0))
        assertNull(BackupRestore.PasswordHold.take())
        val pw = "pw".toCharArray()
        BackupRestore.PasswordHold.put(pw)
        BackupRestore.PasswordHold.clear()
        assertNull(BackupRestore.PasswordHold.take())
        assertTrue("cleared from memory", pw.all { it == '\u0000' })
    }
}
