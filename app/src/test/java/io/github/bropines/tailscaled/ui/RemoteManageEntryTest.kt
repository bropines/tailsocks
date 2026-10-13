package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which peers' details look into remote management, and which of those are asked over the network. */
class RemoteManageEntryTest {
    private fun peer(id: String? = "nABC123CNTRL", os: String? = "linux", online: Boolean? = true) =
        PeerData(id = id, os = os, online = online)

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
    fun onlyAPeerThatCouldBeATailSocksIsAsked() {
        // Android when it says what it is, Linux by default, nothing at all from an old daemon.
        for (os in listOf("android", "Android", "linux", "", null)) assertTrue("$os", remoteManageAsks(peer(os = os), isSelf = false))
        for (os in listOf("windows", "macOS", "iOS", "tvOS")) assertFalse(os, remoteManageAsks(peer(os = os), isSelf = false))
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
