package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.status
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.WriteGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChangeClassifierTest {

    @Test
    fun theTableAsTheWorkPlanHasIt() {
        val low = setOf(ChangeKind.DEVICE_RENAME, ChangeKind.WEBHOOK_TEST)
        val medium = setOf(
            ChangeKind.DEVICE_TAGS, ChangeKind.DEVICE_ROUTES, ChangeKind.DEVICE_AUTHORIZE, ChangeKind.DEVICE_KEY_EXPIRY,
            ChangeKind.DNS_MAGIC_DNS, ChangeKind.DNS_SPLIT, ChangeKind.KEY_CREATE, ChangeKind.USER_APPROVE,
        )
        val high = setOf(
            ChangeKind.DEVICE_DELETE, ChangeKind.DEVICE_EXPIRE, ChangeKind.KEY_REVOKE, ChangeKind.DEVICE_DEAUTHORIZE,
            ChangeKind.USER_ROLE, ChangeKind.USER_SUSPEND, ChangeKind.USER_DELETE, ChangeKind.WEBHOOK_DELETE, ChangeKind.SERVICE_DELETE,
        )
        low.forEach { assertEquals(it.name, ChangeClass.LOW, ChangeClassifier.classify(it)) }
        medium.forEach { assertEquals(it.name, ChangeClass.MEDIUM, ChangeClassifier.classify(it)) }
        high.forEach { assertEquals(it.name, ChangeClass.HIGH, ChangeClassifier.classify(it)) }
        assertEquals(ChangeClass.POLICY, ChangeClassifier.classify(ChangeKind.POLICY_FILE))
    }

    @Test
    fun settingsThatOpenTheTailnetAreHigh() {
        fun c(k: TailnetSettingKey, b: Any?, a: Any?) = ChangeClassifier.setting(k, b, a)
        assertEquals(ChangeClass.HIGH, c(TailnetSettingKey.DEVICES_APPROVAL, true, false))
        assertEquals(ChangeClass.MEDIUM, c(TailnetSettingKey.DEVICES_APPROVAL, false, true))
        assertEquals(ChangeClass.HIGH, c(TailnetSettingKey.USERS_APPROVAL, true, false))
        assertEquals(ChangeClass.HIGH, c(TailnetSettingKey.DEVICES_KEY_DURATION, 30, 180))
        assertEquals(ChangeClass.MEDIUM, c(TailnetSettingKey.DEVICES_KEY_DURATION, 180, 30))
        assertEquals(ChangeClass.HIGH, c(TailnetSettingKey.USERS_EXTERNAL_ROLE, "admin", "member"))
        assertEquals(ChangeClass.HIGH, c(TailnetSettingKey.USERS_EXTERNAL_ROLE, "none", "admin"))
        assertEquals(ChangeClass.MEDIUM, c(TailnetSettingKey.USERS_EXTERNAL_ROLE, "member", "none"))
        assertEquals(ChangeClass.MEDIUM, c(TailnetSettingKey.HTTPS, true, false))
        assertEquals(ChangeClass.MEDIUM, c(TailnetSettingKey.ROUTE_SELECTION, "regional-routing", "active-passive-failover"))
    }

    @Test
    fun dnsListsByWhatTheyRemove() {
        assertEquals(ChangeClass.HIGH, ChangeClassifier.nameservers(listOf("1.1.1.1"), emptyList(), magicDnsOn = true))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.nameservers(listOf("1.1.1.1"), emptyList(), magicDnsOn = false))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.nameservers(listOf("1.1.1.1"), listOf("8.8.8.8"), magicDnsOn = true))
        assertEquals(ChangeClass.LOW, ChangeClassifier.searchPaths(listOf("a.example"), listOf("a.example", "b.example")))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.searchPaths(listOf("a.example", "b.example"), listOf("a.example")))
    }
}

class SafeChangeRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val device = ChangeTarget(TargetType.DEVICE, "n1", "pixel-9")

    private fun change(kind: ChangeKind, target: ChangeTarget = device) =
        AdminChange(kind, ChangeClassifier.classify(kind), target, title = kind.name, effect = "effect of ${kind.name}")

    private fun grant(now: Long = 1_000L) = WriteGrant(now, LockState.CRYPTO_PER_USE)

    private class Fixture(val runner: SafeChangeRunner, val transport: FakeTransport, val audit: AdminAuditLog)

    private fun fixture(ctx: SafetyContext = SafetyContext("p1", "Home"), transport: FakeTransport = FakeTransport()): Fixture {
        val audit = AdminAuditLog(java.io.File(tmp.newFolder(), "audit.jsonl"))
        return Fixture(SafeChangeRunner(testBackend(transport), audit, { ctx }, clock = { 1_500L }), transport, audit)
    }

    private fun deleteDevice(target: ChangeTarget = device) = PlannedChange(change(ChangeKind.DEVICE_DELETE, target), { it.deleteDevice(target.id) })

    @Test
    fun aHighChangeNeedsEveryGate() = runBlocking {
        val f = fixture(transport = FakeTransport().ok("DELETE", "/device/n1", "{}"))
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_CONFIRMED), f.runner.run(deleteDevice(), GateEvidence(confirmed = false, "pixel-9", grant())))
        assertEquals(ChangeOutcome.Refused(Refusal.TYPED_MISMATCH), f.runner.run(deleteDevice(), GateEvidence(true, "pixel", grant())))
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_UNLOCKED), f.runner.run(deleteDevice(), GateEvidence(true, "pixel-9", null)))
        assertTrue("nothing went out", f.transport.requests.isEmpty())
        val out = f.runner.run(deleteDevice(), GateEvidence(true, " pixel-9 ", grant()))
        assertTrue(out is ChangeOutcome.Applied)
        assertEquals(1, f.transport.requests.size)
        val log = f.audit.records()
        assertEquals(AuditResult.APPLIED, log.first().result)
        assertEquals(listOf("req-ok"), log.first().requestIds)
        assertEquals(3, log.count { it.result == AuditResult.REFUSED })
    }

    @Test
    fun aGrantOpensOneChangeOnly() = runBlocking {
        val f = fixture(transport = FakeTransport().ok("POST", "/device/n1/tags", "{}"))
        val g = grant()
        val tags = PlannedChange(change(ChangeKind.DEVICE_TAGS), { it.setDeviceTags("n1", listOf("tag:a")) })
        assertTrue(f.runner.run(tags, GateEvidence(true, grant = g)) is ChangeOutcome.Applied)
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_UNLOCKED), f.runner.run(tags, GateEvidence(true, grant = g)))
        assertEquals(1, f.transport.requests.size)
    }

    @Test
    fun aStaleGrantIsRefused() = runBlocking {
        val f = fixture(transport = FakeTransport().ok("POST", "/device/n1/tags", "{}"))
        val stale = grant(now = 1_500L - WriteGrant.VALID_MS - 1)
        val tags = PlannedChange(change(ChangeKind.DEVICE_TAGS), { it.setDeviceTags("n1", emptyList()) })
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_UNLOCKED), f.runner.run(tags, GateEvidence(true, grant = stale)))
    }

    @Test
    fun aLowChangeNeedsOnlyTheConfirm() = runBlocking {
        val f = fixture(transport = FakeTransport().ok("POST", "/device/n1/name", "{}"))
        val rename = PlannedChange(change(ChangeKind.DEVICE_RENAME), { it.renameDevice("n1", "pixel-10") })
        assertTrue(f.runner.run(rename, GateEvidence(confirmed = true)) is ChangeOutcome.Applied)
    }

    @Test
    fun guardsHoldWhateverTheGates() = runBlocking {
        val ctx = SafetyContext("p1", ownKeyId = "kOWN", ownUserId = "uME", ownLoginName = "me@example.com")
        val f = fixture(ctx)
        val all = GateEvidence(true, "x", grant())
        val ownKey = PlannedChange(change(ChangeKind.KEY_REVOKE, ChangeTarget(TargetType.KEY, "kOWN", "x")), { it.deleteKey("kOWN") })
        assertEquals(ChangeOutcome.Refused(Refusal.OWN_CREDENTIAL), f.runner.run(ownKey, all))
        val me = PlannedChange(change(ChangeKind.USER_SUSPEND, ChangeTarget(TargetType.USER, "uME", "x")), { it.suspendUser("uME") })
        assertEquals(ChangeOutcome.Refused(Refusal.OWN_USER), f.runner.run(me, all))
        val meByLogin = PlannedChange(
            change(ChangeKind.USER_ROLE, ChangeTarget(TargetType.USER, "uOTHERID", "x", loginName = "ME@example.com")), { }
        )
        assertEquals(ChangeOutcome.Refused(Refusal.OWN_USER), f.runner.run(meByLogin, all))
        val shared = deleteDevice(ChangeTarget(TargetType.DEVICE, "nS", "x", shared = true))
        assertEquals(ChangeOutcome.Refused(Refusal.SHARED_DEVICE), f.runner.run(shared, all))
        assertTrue(f.transport.requests.isEmpty())
    }

    @Test
    fun readOnlyNoLockAndScopesRefuse() = runBlocking {
        val all = GateEvidence(true, "pixel-9", grant())
        assertEquals(
            ChangeOutcome.Refused(Refusal.READ_ONLY_PROFILE),
            fixture(SafetyContext("p1", readOnlyProfile = true)).runner.run(deleteDevice(), all)
        )
        assertEquals(
            ChangeOutcome.Refused(Refusal.NO_SCREEN_LOCK),
            fixture(SafetyContext("p1", lockState = LockState.NO_SCREEN_LOCK)).runner.run(deleteDevice(), all)
        )
        assertEquals(
            ChangeOutcome.Refused(Refusal.NOT_ALLOWED),
            fixture(SafetyContext("p1", canWrite = { it != AdminArea.DEVICES })).runner.run(deleteDevice(), all)
        )
        val policy = PlannedChange(change(ChangeKind.POLICY_FILE, ChangeTarget(TargetType.TAILNET, "-", "tailnet")), { })
        assertEquals(ChangeOutcome.Refused(Refusal.POLICY_PIPELINE), fixture().runner.run(policy, all))
    }

    @Test
    fun verificationAndFailuresAreRecorded() = runBlocking {
        val t = FakeTransport()
            .ok("POST", "/device/n1/authorized", "{}")
            .on("POST", "/device/n2/authorized", null, true, status(500, """{"message":"boom"}"""))
        val f = fixture(transport = t)
        val ok = PlannedChange(
            change(ChangeKind.DEVICE_AUTHORIZE), { it.setDeviceAuthorized("n1", true) }, verify = { true }
        )
        assertEquals(ChangeOutcome.Applied(true, listOf("req-ok")), f.runner.run(ok, GateEvidence(true, grant = grant())))
        val mismatch = PlannedChange(change(ChangeKind.DEVICE_AUTHORIZE), { it.setDeviceAuthorized("n1", true) }, verify = { false })
        assertEquals(ChangeOutcome.Applied(false, listOf("req-ok")), f.runner.run(mismatch, GateEvidence(true, grant = grant())))
        val failing = PlannedChange(change(ChangeKind.DEVICE_AUTHORIZE, ChangeTarget(TargetType.DEVICE, "n2", "b")), { it.setDeviceAuthorized("n2", true) })
        val out = f.runner.run(failing, GateEvidence(true, grant = grant()))
        assertTrue(out is ChangeOutcome.Failed)
        assertEquals("a write is sent once", 1, t.requestsTo("POST", "/device/n2").size)
        val log = f.audit.records("p1")
        assertEquals(listOf(AuditResult.FAILED, AuditResult.MISMATCH, AuditResult.VERIFIED), log.map { it.result })
        assertTrue(log.first().detail!!.contains("boom"))
        assertEquals(listOf("req-500"), log.first().requestIds)
    }

    @Test
    fun blockedByIsWhatTheUiDisablesOn() {
        val f = fixture(SafetyContext("p1", ownKeyId = "kOWN"))
        assertEquals(Refusal.OWN_CREDENTIAL, f.runner.blockedBy(change(ChangeKind.KEY_REVOKE, ChangeTarget(TargetType.KEY, "kOWN", "k"))))
        assertNull(f.runner.blockedBy(change(ChangeKind.KEY_REVOKE, ChangeTarget(TargetType.KEY, "kOTHER", "k"))))
    }
}

class AdminAuditLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rec(i: Int, profile: String = "p1") = AuditRecord(
        time = i.toLong(), profileId = profile, kind = ChangeKind.DEVICE_RENAME, changeClass = ChangeClass.LOW,
        targetType = TargetType.DEVICE, targetId = "n$i", targetName = "d$i", effect = "e", result = AuditResult.APPLIED,
    )

    @Test
    fun newestFirstPerProfileAndBounded() {
        val log = AdminAuditLog(java.io.File(tmp.root, "admin/audit.jsonl"), maxEntries = 10)
        (1..30).forEach { log.append(rec(it, if (it % 2 == 0) "p2" else "p1")) }
        val all = log.records()
        assertEquals(10, all.size)
        assertEquals(30L, all.first().time)
        assertTrue(log.records("p1").all { it.profileId == "p1" })
        log.clear("p2")
        assertFalse(log.records().any { it.profileId == "p2" })
        log.clear()
        assertTrue(log.records().isEmpty())
    }

    @Test
    fun aDamagedLineIsSkipped() {
        val file = java.io.File(tmp.root, "audit.jsonl")
        val log = AdminAuditLog(file)
        log.append(rec(1))
        file.appendText("{not json\n")
        log.append(rec(2))
        assertEquals(listOf(2L, 1L), log.records().map { it.time })
    }
}
