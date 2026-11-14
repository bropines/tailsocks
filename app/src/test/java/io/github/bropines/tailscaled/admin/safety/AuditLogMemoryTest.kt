package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import io.github.bropines.tailscaled.admin.secure.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The sealed change log opens each line once per instance, and its copy in memory never lies. */
class AuditLogMemoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Counts the Keystore's work: every open is a round trip on a phone. */
    private class CountingBox(private val inner: SecretBox = SoftwareSecretBox()) : SecretBox {
        var opens = 0
        override fun seal(plaintext: String, aad: String) = inner.seal(plaintext, aad)
        override fun open(blob: String, aad: String): String {
            opens++
            return inner.open(blob, aad)
        }
    }

    private fun record(id: String, profile: String = "p1", time: Long = 1_000L) = AuditRecord(
        time = time, profileId = profile, kind = ChangeKind.entries.first(), changeClass = ChangeClass.LOW,
        targetType = TargetType.entries.first(), targetId = id, targetName = "router-$id", effect = "renamed", result = AuditResult.VERIFIED,
    )

    @Test
    fun appendsAndRepeatedReadsOpenEachLineOnce() {
        val box = CountingBox()
        val log = AdminAuditLog(File(tmp.root, "admin/audit.jsonl"), box = box)
        repeat(100) { log.append(record("d$it", time = it.toLong())) }
        assertEquals("appending opens nothing", 0, box.opens)
        repeat(3) { assertEquals(100, log.records().size) }
        assertTrue("${box.opens} opens for 100 lines read three times", box.opens <= 100)
        // A write after the read is added to the copy: still no line opened again.
        log.append(record("late", time = 500))
        val before = box.opens
        assertEquals("late", log.records().first().targetId)
        assertEquals(before, box.opens)
    }

    @Test
    fun aLineAnotherInstanceWroteIsSeen() {
        val file = File(tmp.root, "admin/audit.jsonl")
        val box = SoftwareSecretBox()
        val console = AdminAuditLog(file, box = box)
        console.append(record("a"))
        assertEquals(listOf("a"), console.records().map { it.targetId })
        // A Serve publish or a notification's action writes through its own instance.
        AdminAuditLog(file, box = box).append(record("b"))
        assertEquals(listOf("b", "a"), console.records().map { it.targetId })
        console.append(record("c"))
        assertEquals(listOf("c", "b", "a"), console.records().map { it.targetId })
        assertEquals(listOf("c", "b", "a"), AdminAuditLog(file, box = box).records().map { it.targetId })
    }

    @Test
    fun theTrimAndAClearKeepTheCopyEqualToTheFile() {
        val file = File(tmp.root, "admin/audit.jsonl")
        val box = SoftwareSecretBox()
        val log = AdminAuditLog(file, maxEntries = 10, box = box)
        log.records()
        repeat(25) { log.append(record("d$it", profile = if (it % 2 == 0) "p1" else "p2", time = it.toLong())) }
        val fresh = { AdminAuditLog(file, maxEntries = 10, box = box).records() }
        assertEquals(fresh(), log.records())
        assertTrue(file.readLines().size <= 12)
        log.clear("p2")
        assertEquals(fresh(), log.records())
        assertTrue(log.records().all { it.profileId == "p1" })
        log.clear()
        assertEquals(emptyList<AuditRecord>(), log.records())
        log.append(record("again"))
        assertEquals(listOf("again"), log.records().map { it.targetId })
        assertFalse(file.readText().contains("router"))
    }

    @Test
    fun theListHandedOutDoesNotChangeUnderItsReader() {
        val log = AdminAuditLog(File(tmp.root, "admin/audit.jsonl"), box = SoftwareSecretBox())
        log.append(record("a"))
        val shown = log.records()
        log.append(record("b"))
        assertEquals(listOf("a"), shown.map { it.targetId })
    }
}
