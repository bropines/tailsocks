package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.WriteGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The classes of the configuration tabs' changes, and the policy gate in the runner. */
class ConfigSafetyTest {

    private fun s(key: TailnetSettingKey, before: Any?, after: Any?) = ChangeClassifier.setting(key, before, after)

    @Test
    fun settingsThatOpenOrCollectAreHighTheWayBackMedium() {
        // Opening the tailnet.
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.DEVICES_APPROVAL, true, false))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.USERS_APPROVAL, true, false))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.DEVICES_KEY_DURATION, 90, 180))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.USERS_EXTERNAL_ROLE, "none", "member"))
        // Collecting and publishing.
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.NETWORK_FLOW_LOGGING, false, true))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.POSTURE_IDENTITY, false, true))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.POSTURE_IDENTITY, null, true))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.HTTPS, false, true))
        assertEquals(ChangeClass.HIGH, s(TailnetSettingKey.ACLS_EXTERNALLY_MANAGED, false, true))
        // The way back.
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.DEVICES_APPROVAL, false, true))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.USERS_APPROVAL, false, true))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.DEVICES_KEY_DURATION, 180, 30))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.USERS_EXTERNAL_ROLE, "member", "admin"))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.NETWORK_FLOW_LOGGING, true, false))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.POSTURE_IDENTITY, true, false))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.HTTPS, true, false))
        // Neither way.
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.DEVICES_AUTO_UPDATES, false, true))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.ROUTE_SELECTION, "active-passive-failover", "regional-routing"))
        assertEquals(ChangeClass.MEDIUM, s(TailnetSettingKey.ACLS_EXTERNAL_LINK, null, "https://github.com/example/policy"))
    }

    @Test
    fun dnsByWhatItTakesAway() {
        assertEquals(ChangeClass.HIGH, ChangeClassifier.magicDns(on = false))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.magicDns(on = true))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.nameservers(listOf("1.1.1.1"), emptyList(), magicDnsOn = false, overrideLocalDns = true))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.nameservers(listOf("1.1.1.1"), emptyList(), magicDnsOn = false, overrideLocalDns = false))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.DNS_OVERRIDE_LOCAL))
    }

    @Test
    fun webhooks() {
        assertEquals(ChangeClass.LOW, ChangeClassifier.classify(ChangeKind.WEBHOOK_TEST))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.WEBHOOK_CREATE))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.WEBHOOK_UPDATE))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.WEBHOOK_ROTATE))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.WEBHOOK_DELETE))
    }

    private val evidence = PolicyEvidence(
        candidateSha256 = PolicyGate.sha256("{}"), baseEtag = "\"e1\"", localOk = true, serverValid = true,
        linesAdded = 1, linesRemoved = 0, riskFindings = 0, accessChecked = true, accessLost = 0,
    )

    private fun policy(e: PolicyEvidence?) = AdminChange(
        ChangeKind.POLICY_FILE, ChangeClass.POLICY, ChangeTarget(TargetType.TAILNET, "-", "tail4a2c9.ts.net"),
        "Apply", "effect", confirmPhrase = "apply policy", policy = e,
    )

    @Test
    fun aPolicyChangePassesOnlyWithWholeEvidence() {
        assertTrue(PolicyGate.passed(policy(evidence)))
        listOf(
            null,
            evidence.copy(localOk = false),
            evidence.copy(serverValid = false),
            evidence.copy(baseEtag = null),
            evidence.copy(baseEtag = " "),
            evidence.copy(linesAdded = 0, linesRemoved = 0),
        ).forEach { assertEquals(it.toString(), false, PolicyGate.passed(policy(it))) }
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", PolicyGate.sha256("{}"))
    }

    @Test
    fun theOtherGuardsStillHoldForAPolicyChange() = runBlocking {
        val t = FakeTransport()
        val change = policy(evidence)
        fun runner(ctx: SafetyContext) = SafeChangeRunner(testBackend(t), null, { ctx })
        assertNull(runner(SafetyContext("p")).blockedBy(change))
        assertEquals(Refusal.READ_ONLY_PROFILE, runner(SafetyContext("p", readOnlyProfile = true)).blockedBy(change))
        assertEquals(Refusal.NOT_ALLOWED, runner(SafetyContext("p", canWrite = { it != AdminArea.POLICY })).blockedBy(change))
        assertEquals(Refusal.NO_SCREEN_LOCK, runner(SafetyContext("p", lockState = LockState.NO_SCREEN_LOCK)).blockedBy(change))
        val grant = WriteGrant(System.currentTimeMillis(), LockState.CRYPTO_PER_USE)
        val typedName = runner(SafetyContext("p")).run(PlannedChange(change, { }), GateEvidence(true, "tail4a2c9.ts.net", grant))
        assertEquals("the phrase, not the tailnet's name, is typed", ChangeOutcome.Refused(Refusal.TYPED_MISMATCH), typedName)
        assertTrue(t.requests.isEmpty())
    }
}
