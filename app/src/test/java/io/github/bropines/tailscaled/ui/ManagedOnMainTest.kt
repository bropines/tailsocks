package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import io.github.bropines.tailscaled.admin.secure.KeyValueStore
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import io.github.bropines.tailscaled.core.RemotePairingRecord
import io.github.bropines.tailscaled.core.RemoteTokenStore
import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The devices this one manages, as the main screen has them (ui/ManagedOnMain.kt): which
 * pairings it shows, what it says of each from the peer list it already holds, and how many get
 * an entry of their own.
 */
class ManagedOnMainTest {

    private companion object {
        const val LIVING = "nTVliving"
        const val BEDROOM = "nTVbedroom"
        const val TABLET = "nTablet"
    }

    /** Counts the reads of a sealed token: the main screen must not open what it has no use for. */
    private class CountingStore(private val inner: KeyValueStore = MemoryKeyValueStore()) : KeyValueStore by inner {
        var opened = 0
        override fun getString(key: String): String? {
            opened++
            return inner.getString(key)
        }
    }

    private val store = CountingStore()
    private val tokens = RemoteTokenStore(store, SoftwareSecretBox())

    private fun pair(id: String, name: String, profile: String = "default", tv: Boolean = true, owner: Boolean = true) =
        assertTrue(tokens.put(profile, id, RemotePairingRecord(token = "t-$id", name = name, owner = owner, tv = tv)))

    private fun peer(id: String, name: String, online: Boolean) = PeerData(id = id, hostName = name, online = online)

    /** What the main screen draws for [profile] with [peers] in hand and [places] to put it in. */
    private fun shown(peers: List<PeerData>?, places: Int = 3, profile: String = "default"): ManagedOnMain =
        managedOnMain(managedNow(storedPairings(tokens, profile), peers), places)

    @Test
    fun noPairingIsNothingAndOpensNothing() {
        val nothing = shown(listOf(peer(LIVING, "living-room-tv", true)))
        assertTrue(nothing.shown.isEmpty())
        assertEquals(0, nothing.total)
        assertFalse(nothing.more)
        // A device that never paired reads the names of the keys and no key.
        assertEquals(0, store.opened)
        // Another profile's pairing is not this one's.
        pair(LIVING, "living-room-tv", profile = "work")
        assertEquals(0, shown(null).total)
    }

    @Test
    fun oneDeviceIsItsOwnEntry() {
        pair(LIVING, "living-room-tv")
        val one = shown(listOf(peer(LIVING, "living-room-tv", true)))
        assertEquals(listOf("living-room-tv"), one.shown.map { it.name })
        assertEquals(RemotePresence.ONLINE, one.shown.single().presence)
        assertTrue(one.shown.single().tv)
        assertFalse(one.more)
        // However little room there is, one device needs no step to "all".
        assertFalse(shown(null, places = 1).more)
    }

    @Test
    fun severalHaveAnEntryEachWhileTheyFit() {
        pair(LIVING, "living-room-tv")
        pair(BEDROOM, "bedroom-tv")
        pair(TABLET, "ann-tablet", tv = false, owner = false)
        val peers = listOf(peer(LIVING, "living-room-tv", true), peer(BEDROOM, "bedroom-tv", true), peer(TABLET, "ann-tablet", true))

        val three = shown(peers, places = 3)
        assertEquals(listOf("ann-tablet", "bedroom-tv", "living-room-tv"), three.shown.map { it.name })
        assertFalse(three.more)

        // Two places: one device and the way to all three.
        val two = shown(peers, places = 2)
        assertEquals(listOf("ann-tablet"), two.shown.map { it.name })
        assertEquals(3, two.total)
        assertTrue(two.more)

        // One place: only the way to all.
        val one = shown(peers, places = 1)
        assertTrue(one.shown.isEmpty())
        assertTrue(one.more)

        // More than fit anywhere: the last place leads to all of them.
        pair("nBox", "tv-box")
        pair("nShield", "shield")
        val five = shown(peers, places = 3)
        assertEquals(2, five.shown.size)
        assertEquals(5, five.total)
        assertTrue(five.more)
    }

    @Test
    fun aDeviceThatIsAwaySaysSoAndComesAfterThoseThatAreThere() {
        pair(LIVING, "living-room-tv")
        pair(BEDROOM, "bedroom-tv")
        pair(TABLET, "ann-tablet", tv = false)
        // The living room's television is off, the tablet has left the tailnet.
        val now = shown(listOf(peer(LIVING, "living-room-tv", false), peer(BEDROOM, "bedroom-tv", true), peer("nOther", "desktop", true)))
        assertEquals(listOf("bedroom-tv", "living-room-tv", "ann-tablet"), now.shown.map { it.name })
        assertEquals(listOf(RemotePresence.ONLINE, RemotePresence.OFFLINE, RemotePresence.GONE), now.shown.map { it.presence })
        // With two places the one that is there keeps its entry.
        assertEquals(listOf("bedroom-tv"), managedOnMain(now.shown, 2).shown.map { it.name })
    }

    @Test
    fun withoutAPeerListTheNamesStandWithoutAState() {
        pair(LIVING, "living-room-tv")
        pair(BEDROOM, "bedroom-tv")
        // The daemon is stopped, or its first answer has not come: nothing is claimed of anyone.
        val waiting = shown(null)
        assertEquals(listOf("bedroom-tv", "living-room-tv"), waiting.shown.map { it.name })
        assertTrue(waiting.shown.all { it.presence == RemotePresence.UNKNOWN })
        // A list that came and went again takes the states back with it.
        val stored = storedPairings(tokens, "default")
        val seen = managedNow(stored, listOf(peer(LIVING, "living-room-tv", true)))
        assertTrue(managedNow(seen, null).all { it.presence == RemotePresence.UNKNOWN })
    }

    @Test
    fun theTailnetsNameForADeviceReplacesTheOneItWasPairedBy() {
        pair(LIVING, "living-room")
        val renamed = PeerData(id = LIVING, hostName = "living-room", dnsName = "big-tv.tail1234.ts.net.", online = true)
        assertEquals("big-tv", shown(listOf(renamed)).shown.single().name)
        // While it is away from the list, the name it was paired by.
        assertEquals("living-room", shown(null).shown.single().name)
    }

    @Test
    fun theListFollowsTheProfile() {
        pair(LIVING, "living-room-tv")
        pair("nOffice", "office-tv", profile = "work")
        val peers = listOf(peer(LIVING, "living-room-tv", true), peer("nOffice", "office-tv", true))
        assertEquals(listOf("living-room-tv"), shown(peers).shown.map { it.name })
        assertEquals(listOf("office-tv"), shown(peers, profile = "work").shown.map { it.name })
        assertTrue(shown(peers, profile = "guest").shown.isEmpty())
        // A profile that is deleted takes its devices with it and leaves the others'.
        tokens.removeProfile("work")
        assertEquals(0, shown(peers, profile = "work").total)
        assertEquals(1, shown(peers).total)
    }

    @Test
    fun aForgottenDeviceLeavesNoEntry() {
        pair(LIVING, "living-room-tv")
        pair(BEDROOM, "bedroom-tv")
        val peers = listOf(peer(LIVING, "living-room-tv", true), peer(BEDROOM, "bedroom-tv", false))
        assertEquals(2, shown(peers).total)

        // "Forget this device" in its page: the next read has one device, and it is the other.
        tokens.remove("default", LIVING)
        assertEquals(listOf("bedroom-tv"), shown(peers).shown.map { it.name })

        // The last one forgotten: nothing is left to draw, though the peer is still in the tailnet.
        tokens.remove("default", BEDROOM)
        val none = shown(peers)
        assertTrue(none.shown.isEmpty())
        assertFalse(none.more)

        // Paired again, it is back.
        pair(BEDROOM, "bedroom-tv")
        assertEquals(listOf("bedroom-tv"), shown(peers).shown.map { it.name })
    }
}
