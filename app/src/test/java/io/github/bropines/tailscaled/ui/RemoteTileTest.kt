package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.Availability
import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DeviceProfile
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.RemoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the home screen's tile for remote management says, and who is given one. */
class RemoteTileTest {
    private val ready = RemoteState(available = true, enabled = true, listening = true, port = 8777, inbound = RemoteState.INBOUND_NETSTACK)

    @Test
    fun offIsOffWhateverTheBridgeStillSays() {
        assertEquals(RemoteTile.Kind.OFF, RemoteTile.of(false, RemoteState()).kind)
        // The switch is off and the endpoint has not closed yet: still off, to someone reading a tile.
        assertEquals(RemoteTile.Kind.OFF, RemoteTile.of(false, ready).kind)
    }

    @Test
    fun onAndReachableWaitsForAFirstPairing() {
        val t = RemoteTile.of(true, ready)
        assertEquals(RemoteTile.Kind.WAITING, t.kind)
        assertTrue(t.ready)
    }

    @Test
    fun pairedDevicesAreCounted() {
        val t = RemoteTile.of(true, ready.copy(paired = 3))
        assertEquals(RemoteTile(RemoteTile.Kind.PAIRED, 3), t)
        assertTrue(t.ready)
    }

    /**
     * The limit the page is honest about, and no more than the bridge says of it: where it
     * reports no way in the tile does not look ready; where it reports one, whatever the mode, it does.
     */
    @Test
    fun theBridgeDecidesWhetherAModeCanReceiveConnections() {
        val none = ready.copy(listening = false, inbound = RemoteState.INBOUND_NONE, paired = 2)
        val t = RemoteTile.of(true, none)
        assertEquals(RemoteTile.Kind.UNAVAILABLE_IN_MODE, t.kind)
        assertFalse(t.ready)
        assertTrue(t.blocked)
        // The same device once the bridge reports a way in.
        assertEquals(RemoteTile.Kind.PAIRED, RemoteTile.of(true, none.copy(listening = true, inbound = RemoteState.INBOUND_NETSTACK)).kind)
    }

    @Test
    fun aStoppedDaemonAndABusyPortAreSaidApart() {
        assertEquals(RemoteTile.Kind.NOT_RUNNING, RemoteTile.of(true, RemoteState()).kind)
        val busy = ready.copy(listening = false, error = "address already in use")
        assertEquals(RemoteTile.Kind.PORT_BUSY, RemoteTile.of(true, busy).kind)
        assertFalse(RemoteTile.of(true, busy).ready)
        assertFalse(RemoteTile.of(true, RemoteState()).ready)
        // A daemon that is not running yet is no fault: only a busy port is set apart.
        assertTrue(RemoteTile.of(true, busy).blocked)
        assertFalse(RemoteTile.of(true, RemoteState()).blocked)
        // The endpoint is opening: neither busy nor ready yet, and waiting.
        assertEquals(RemoteTile.Kind.WAITING, RemoteTile.of(true, ready.copy(listening = false)).kind)
    }

    /** A television has the tile; a phone and a tablet keep the page in the settings and nothing else. */
    @Test
    fun onlyATelevisionIsGivenTheTile() {
        for (caps in listOf(Capabilities.ALL, Capabilities.NONE)) {
            assertEquals(
                Availability.OFFERED,
                DeviceProfile.availability(Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS, DeviceClass.HANDHELD, caps)
            )
            assertNotEquals(
                Availability.OFFERED,
                DeviceProfile.availability(Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS, DeviceClass.TV, caps)
            )
        }
    }
}
