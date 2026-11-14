package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A key repeated in one LazyColumn crashes it: the keys of every keyed list come from here. */
class LazyKeysTest {

    @Test
    fun distinctBasesAreTheKeys() {
        assertEquals(listOf("a", "b", "c"), uniqueKeys(listOf("a", "b", "c")) { it })
    }

    @Test
    fun repeatsGetASuffix() {
        assertEquals(listOf("a", "a#2", "a#3"), uniqueKeys(listOf("a", "a", "a")) { it })
    }

    @Test
    fun aSuffixNeverTakesAnotherItemsOwnBase() {
        // The second "a" must not become "a#2": a later item is "a#2" itself.
        val keys = uniqueKeys(listOf("a", "a", "a#2")) { it }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("a#2", keys[2])
    }

    @Test
    fun emptyListHasNoKeys() {
        assertEquals(emptyList<String>(), uniqueKeys(emptyList<String>()) { it })
    }

    @Test
    fun peerKeyIsTheStableIdWhenThereIsOne() {
        assertEquals("id:n123", peerRowKey(PeerData(id = "n123", tailscaleIPs = listOf("100.64.0.1"))))
    }

    @Test
    fun peerWithoutIdFallsBackToAComposite() {
        val a = PeerData(publicKey = "nodekey:a", tailscaleIPs = listOf("100.64.0.1"), hostName = "a")
        val b = PeerData(publicKey = "nodekey:b", tailscaleIPs = listOf("100.64.0.1"), hostName = "a")
        assertNotEquals(peerRowKey(a), peerRowKey(b))
        // An empty ID is no ID.
        assertEquals(peerRowKey(a), peerRowKey(a.copy(id = "")))
    }

    @Test
    fun twoIdenticalPeersWithoutIdStillGetDistinctKeys() {
        val ghost = PeerData()
        val keys = uniqueKeys(listOf(ghost, ghost, PeerData(id = "x")), ::peerRowKey)
        assertEquals(3, keys.toSet().size)
        assertEquals(false, SELF_ROW_KEY in keys)
    }
}
