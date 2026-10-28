package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which peers' details look into remote management, and which of those are asked over the network. */
class RemoteManageEntryTest {
    private fun peer(id: String? = "nABC123CNTRL", os: String? = "linux", online: Boolean? = true, app: String? = "tailsocks") =
        PeerData(id = id, os = os, online = online, app = app)

    @Test
    fun thisDeviceIsNeverAskedNorLookedUp() {
        assertFalse(remoteManageConcerns(peer(), isSelf = true))
        assertFalse(remoteManageAsks(peer(), isSelf = true))
    }

    @Test
    fun anOfflinePeerIsLookedUpButNotAsked() {
        // A device this one is paired with can be forgotten while it is away: its token is on disk.
        assertTrue(remoteManageConcerns(peer(online = false), isSelf = false))
        assertFalse(remoteManageAsks(peer(online = false), isSelf = false))
        assertFalse(remoteManageAsks(peer(online = null), isSelf = false))
    }

    @Test
    fun onlyAPeerThatRunsTailSocksIsAsked() {
        for (os in listOf("android", "linux")) assertTrue(os, remoteManageAsks(peer(os = os), isSelf = false))
        // 5.3 and older in the masquerade: Linux that says tailscale-cli.
        assertTrue(remoteManageAsks(peer(os = "linux", app = "tailscale-cli"), isSelf = false))
        // The official clients, servers and other programs are left alone.
        for (os in listOf("android", "linux", "windows", "macOS", "")) assertFalse(os, remoteManageAsks(peer(os = os, app = null), isSelf = false))
        assertFalse(remoteManageAsks(peer(os = "linux", app = "tsidp"), isSelf = false))
    }

    @Test
    fun aPeerWithoutAnIdTheBridgeWouldTakeIsLeftAlone() {
        for (id in listOf(null, "", "has space", "a/b")) {
            assertFalse("$id", remoteManageConcerns(peer(id = id), isSelf = false))
            assertFalse("$id", remoteManageAsks(peer(id = id), isSelf = false))
        }
        assertFalse(remoteManageConcerns(null, isSelf = false))
    }
}
