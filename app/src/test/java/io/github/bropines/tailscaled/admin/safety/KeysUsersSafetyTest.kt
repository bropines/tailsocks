package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.DeviceInviteRequest
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.WriteGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The classes of the Keys and Users tabs' own changes, and the guards they meet. */
class KeysUsersClassifierTest {

    @Test
    fun invitesAndClientsByKind() {
        assertEquals(ChangeClass.LOW, ChangeClassifier.classify(ChangeKind.USER_INVITE_RESEND))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.OAUTH_CLIENT_CREATE))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.USER_INVITE_CREATE))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.DEVICE_INVITE_CREATE))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.USER_INVITE_DELETE))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.DEVICE_INVITE_DELETE))
        assertEquals("giving a suspended user access back is as weighty as taking it", ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.USER_RESTORE))
    }

    @Test
    fun theirAreasAreTheScopesThatGovernThem() {
        assertEquals(AdminArea.OAUTH_KEYS, ChangeKind.OAUTH_CLIENT_CREATE.area)
        assertEquals(AdminArea.USERS, ChangeKind.USER_INVITE_CREATE.area)
        assertEquals(AdminArea.DEVICE_INVITES, ChangeKind.DEVICE_INVITE_CREATE.area)
        assertEquals(AdminArea.DEVICE_INVITES, ChangeKind.DEVICE_INVITE_DELETE.area)
    }

    @Test
    fun aClientThatCanMintCredentialsOrRewriteThePolicyIsHigh() {
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.oauthClient(listOf("all:read")))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.oauthClient(listOf("devices:core", "auth_keys", "dns")))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.oauthClient(listOf("oauth_keys:read", "policy_file:read")))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.oauthClient(listOf("all")))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.oauthClient(listOf("dns:read", "oauth_keys")))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.oauthClient(listOf("policy_file")))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.oauthClient(listOf(" users ")))
    }

    @Test
    fun anInviteAboveMemberIsHigh() {
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.userInvite(UserRole.MEMBER))
        listOf(UserRole.ADMIN, UserRole.NETWORK_ADMIN, UserRole.IT_ADMIN, UserRole.BILLING_ADMIN, UserRole.AUDITOR)
            .forEach { assertEquals(it.name, ChangeClass.HIGH, ChangeClassifier.userInvite(it)) }
    }

    @Test
    fun aMultiUseShareIsHigh() {
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.deviceInvite(DeviceInviteRequest().multiUse))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.deviceInvite(true))
    }
}

class KeysUsersGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun runner(ctx: SafetyContext, t: FakeTransport = FakeTransport()) =
        SafeChangeRunner(testBackend(t), AdminAuditLog(java.io.File(tmp.newFolder(), "audit.jsonl")), { ctx }, clock = { 1_500L })

    private fun change(kind: ChangeKind, target: ChangeTarget, cls: ChangeClass = ChangeClassifier.classify(kind), area: AdminArea = kind.area) =
        AdminChange(kind, cls, target, title = kind.name, effect = "e", area = area)

    private fun all(name: String) = GateEvidence(true, name, WriteGrant(1_000L, LockState.CRYPTO_PER_USE))

    @Test
    fun aSharedInDeviceIsNotSharedOnward() = runBlocking {
        val r = runner(SafetyContext("p1"))
        val share = PlannedChange(
            change(ChangeKind.DEVICE_INVITE_CREATE, ChangeTarget(TargetType.DEVICE, "nS", "family-nas", shared = true)),
            { it.createDeviceInvite("nS", DeviceInviteRequest()) },
        )
        assertEquals(ChangeOutcome.Refused(Refusal.SHARED_DEVICE), r.run(share, all("family-nas")))
    }

    @Test
    fun revokingAClientNeedsTheOauthKeysScope() = runBlocking {
        val t = FakeTransport().ok("DELETE", "/keys/kCLIENT", "{}")
        val r = runner(SafetyContext("p1", canWrite = { it == AdminArea.AUTH_KEYS }), t)
        val revoke = PlannedChange(
            change(ChangeKind.KEY_REVOKE, ChangeTarget(TargetType.KEY, "kCLIENT", "grafana"), area = AdminArea.OAUTH_KEYS),
            { it.deleteKey("kCLIENT") },
        )
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_ALLOWED), r.run(revoke, all("grafana")))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun deletingAnInviteTakesItsNameTypedBack() = runBlocking {
        val t = FakeTransport().ok("DELETE", "/user-invites/29214", "{}")
        val r = runner(SafetyContext("p1"), t)
        val delete = PlannedChange(
            change(ChangeKind.USER_INVITE_DELETE, ChangeTarget(TargetType.INVITE, "29214", "user@example.com")),
            { it.deleteUserInvite("29214") },
        )
        assertEquals(ChangeOutcome.Refused(Refusal.TYPED_MISMATCH), r.run(delete, all("user@")))
        assertTrue(r.run(delete, all("user@example.com")) is ChangeOutcome.Applied)
        assertEquals(1, t.requestsTo("DELETE", "/user-invites/29214").size)
    }

    @Test
    fun aResendNeedsOnlyTheTap() = runBlocking {
        val t = FakeTransport().ok("POST", "/user-invites/29214/resend", "{}")
        val resend = PlannedChange(
            change(ChangeKind.USER_INVITE_RESEND, ChangeTarget(TargetType.INVITE, "29214", "user@example.com")),
            { it.resendUserInvite("29214") },
        )
        assertTrue(runner(SafetyContext("p1"), t).run(resend, GateEvidence(confirmed = true)) is ChangeOutcome.Applied)
    }
}
