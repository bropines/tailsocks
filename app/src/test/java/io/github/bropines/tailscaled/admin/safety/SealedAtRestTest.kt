package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.policy.PolicyStore
import io.github.bropines.tailscaled.admin.policy.StoredPolicy
import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import io.github.bropines.tailscaled.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The policy kept for a revert and the local change log: sealed on disk, older clear copies sealed on first read. */
class SealedAtRestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val policy = StoredPolicy("""{"groups":{"group:admins":["alice@example.com"]}}""", 1_000, "\"e1\"")

    private fun record(id: String, profile: String = "p1") = AuditRecord(
        time = 1, profileId = profile, kind = ChangeKind.entries.first(), changeClass = ChangeClass.LOW,
        targetType = TargetType.entries.first(), targetId = id, targetName = "router-$id", effect = "renamed to router-$id",
        result = AuditResult.VERIFIED,
    )

    @Test
    fun aPolicyCopyIsSealedAndBoundToItsProfile() {
        val box = SoftwareSecretBox()
        val store = PolicyStore(tmp.root, box)
        store.save("p1", policy)
        val onDisk = File(tmp.root, "admin/policy/p1.sealed").readText()
        assertFalse(onDisk.contains("alice"))
        assertEquals(policy, store.load("p1"))
        File(tmp.root, "admin/policy/p1.sealed").copyTo(File(tmp.root, "admin/policy/p2.sealed"))
        assertNull("another profile's copy does not open", store.load("p2"))
        store.clear("p1")
        assertNull(store.load("p1"))
    }

    @Test
    fun aClearPolicyCopyFromAnEarlierBuildIsSealed() {
        val legacy = File(tmp.root, "admin/policy/p1.json").apply { parentFile!!.mkdirs(); writeText(AppJson.encodeToString(StoredPolicy.serializer(), policy)) }
        val store = PolicyStore(tmp.root, SoftwareSecretBox())
        assertEquals(policy, store.load("p1"))
        assertFalse(legacy.exists())
        assertTrue(File(tmp.root, "admin/policy/p1.sealed").exists())
        assertEquals(policy, store.load("p1"))
    }

    @Test
    fun theChangeLogIsSealedLineByLine() {
        val file = File(tmp.root, "admin/audit.jsonl")
        val log = AdminAuditLog(file, box = SoftwareSecretBox())
        log.append(record("a"))
        log.append(record("b", profile = "p2"))
        assertFalse(file.readText().contains("router"))
        assertEquals(listOf("b", "a"), log.records().map { it.targetId })
        assertEquals(listOf("a"), log.records("p1").map { it.targetId })
        log.clear("p2")
        assertEquals(listOf("a"), log.records().map { it.targetId })
        assertFalse(file.readText().contains("router"))
    }

    @Test
    fun clearLinesFromAnEarlierBuildAreSealedOnFirstRead() {
        val file = File(tmp.root, "admin/audit.jsonl")
        AdminAuditLog(file).append(record("old"))
        assertTrue(file.readText().contains("router-old"))
        val log = AdminAuditLog(file, box = SoftwareSecretBox())
        log.append(record("new"))
        assertEquals(listOf("new", "old"), log.records().map { it.targetId })
        assertFalse(file.readText().contains("router"))
        assertEquals(listOf("new", "old"), log.records().map { it.targetId })
    }
}
