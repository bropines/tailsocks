package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.headscale.v2Backend
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Headscale changes' classes, the console's own API key, and which tabs a Headscale server gets. */
class HeadscaleSafetyTest {

    @Test
    fun headscaleChangesAreClassed() {
        listOf(ChangeKind.NODE_REGISTER, ChangeKind.NODE_REGISTRATION_REJECT, ChangeKind.USER_CREATE)
            .forEach { assertEquals(it.name, ChangeClass.MEDIUM, ChangeClassifier.classify(it)) }
        listOf(ChangeKind.USER_RENAME, ChangeKind.API_KEY_CREATE, ChangeKind.API_KEY_EXPIRE)
            .forEach { assertEquals(it.name, ChangeClass.HIGH, ChangeClassifier.classify(it)) }
    }

    @Test
    fun theConsolesOwnApiKeyCannotBeExpiredFromHere() {
        val own = "hskey-api-AbCd-EfGh123-***"
        val runner = SafeChangeRunner(testBackend(FakeTransport()), null, { SafetyContext("p", ownKeyId = own) })
        fun expire(id: String) = AdminChange(
            ChangeKind.API_KEY_EXPIRE, ChangeClass.HIGH, ChangeTarget(TargetType.KEY, id, id.removePrefix("hskey-api-").removeSuffix("-***")), "t", "e",
        )
        assertEquals(Refusal.OWN_CREDENTIAL, runner.blockedBy(expire(own)))
        assertNull(runner.blockedBy(expire("hskey-api-ZyXwVuTs9876-***")))
    }

    @Test
    fun aHeadscaleServerGetsItsOwnTabAndLosesTailscalesOnes() {
        val caps = v2Backend(FakeTransport()).capabilities.value
        assertEquals(listOf(ConsoleTab.DEVICES, ConsoleTab.USERS, ConsoleTab.SERVER), ConsoleState(caps = caps).tabs)
        assertTrue(caps.has(BackendFeature.AUTH_KEY_OWNER))
        assertFalse(caps.has(BackendFeature.SETTINGS))
        val tailscale = Capabilities(BackendKind.TAILSCALE, CredentialKind.API_TOKEN, TailscaleBackend.FEATURES)
        assertFalse("Tailscale has no Headscale tab", ConsoleTab.SERVER in ConsoleState(caps = tailscale).tabs)
        assertTrue(tailscale.has(BackendFeature.DEVICE_DEAUTHORIZE) && tailscale.has(BackendFeature.DEVICE_UNTAG))
        assertFalse(tailscale.has(BackendFeature.HEADSCALE_ADMIN) || tailscale.has(BackendFeature.AUTH_KEY_OWNER))
    }
}
