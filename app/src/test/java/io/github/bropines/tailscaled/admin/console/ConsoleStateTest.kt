package io.github.bropines.tailscaled.admin.console

import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.secure.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleStateTest {

    private val tailscale = Capabilities(BackendKind.TAILSCALE, CredentialKind.API_TOKEN, TailscaleBackend.FEATURES)

    @Test
    fun aBackendWithoutAFeatureLosesItsTab() {
        assertEquals(ConsoleTab.entries, ConsoleState(caps = tailscale).tabs)
        assertEquals("before the capabilities are known, everything shows", ConsoleTab.entries, ConsoleState().tabs)
        val headscale = Capabilities(
            BackendKind.HEADSCALE_V2, CredentialKind.HEADSCALE_API_KEY,
            setOf(BackendFeature.DEVICES, BackendFeature.USERS, BackendFeature.KEYS, BackendFeature.POLICY),
        )
        assertEquals(listOf(ConsoleTab.DEVICES, ConsoleTab.USERS), ConsoleState(caps = headscale).tabs)
    }

    @Test
    fun writesAreOffForTheWholeProfileOrPerArea() {
        val profile = AdminProfile("p", "P")
        val open = ConsoleState(active = profile, caps = tailscale)
        assertNull(open.writeBlock)
        assertTrue(open.canWrite(AdminArea.DEVICES))
        assertEquals(WriteBlock.READ_ONLY_PROFILE, open.copy(active = profile.copy(readOnly = true)).writeBlock)
        assertEquals(WriteBlock.NO_SCREEN_LOCK, open.copy(lockState = LockState.NO_SCREEN_LOCK).writeBlock)
        assertFalse(open.copy(lockState = LockState.NO_SCREEN_LOCK).canWrite(AdminArea.DEVICES))
        val dnsReadOnly = open.copy(caps = tailscale.copy(access = mapOf(AdminArea.DNS to Access.READ)))
        assertFalse(dnsReadOnly.canWrite(AdminArea.DNS))
        assertTrue(dnsReadOnly.canRead(AdminArea.DNS))
        assertTrue(dnsReadOnly.canWrite(AdminArea.USERS))
    }

    @Test
    fun thePhoneIsInTheTailnetOnlyWhenTheListSaysSo() {
        val s = ConsoleState(self = SelfIdentity("nSELF", "me@example.com"))
        assertFalse(s.phoneInTailnet)
        assertFalse(s.copy(devices = Loadable(listOf(ApiDevice(nodeId = "nOTHER")))).phoneInTailnet)
        assertTrue(s.copy(devices = Loadable(listOf(ApiDevice(nodeId = "nSELF")))).phoneInTailnet)
    }

    @Test
    fun devicesSortOnlineFirstThenLastSeen() {
        val list = listOf(
            ApiDevice(nodeId = "a", name = "a.t.ts.net", connectedToControl = false, lastSeen = "2026-10-01T00:00:00Z"),
            ApiDevice(nodeId = "b", name = "b.t.ts.net", connectedToControl = true),
            ApiDevice(nodeId = "c", name = "c.t.ts.net", connectedToControl = false, lastSeen = "2026-10-05T00:00:00Z"),
        )
        assertEquals(listOf("b", "c", "a"), io.github.bropines.tailscaled.admin.sortDevices(list, "last_seen").map { it.nodeId })
        assertEquals(listOf("a", "b", "c"), io.github.bropines.tailscaled.admin.sortDevices(list, "name").map { it.nodeId })
    }
}
