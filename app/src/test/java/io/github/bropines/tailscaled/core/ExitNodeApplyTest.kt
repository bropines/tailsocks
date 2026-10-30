package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What can be selected as an exit node, and which peers are offered as one. */
class ExitNodeApplyTest {

    @Test
    fun onlyNoneOrBothHalvesAreSelectable() {
        assertTrue(ExitNodeApply.isSelectable("", ""))
        assertTrue(ExitNodeApply.isSelectable("nStable", "100.64.0.1"))
        // An address alone would be stored as routed while the daemon is told to clear it.
        assertFalse(ExitNodeApply.isSelectable("", "100.64.0.1"))
        assertFalse(ExitNodeApply.isSelectable("nStable", ""))
    }

    private fun peer(id: String?, ips: List<String>?, offers: Boolean?, dns: String = "") =
        PeerData(id = id, tailscaleIPs = ips, exitNodeOption = offers, dnsName = dns)

    @Test
    fun liveExitNodesNeedAnOfferAnIdAndAnAddress() {
        val status = StatusResponse(
            peers = mapOf(
                "k1" to peer("nB", listOf("100.64.0.2", "fd7a::2"), true, "bravo.tail.ts.net."),
                "k2" to peer("nA", listOf("100.64.0.1"), true, "alpha.tail.ts.net."),
                "k3" to peer("nC", listOf("100.64.0.3"), false, "charlie.tail.ts.net."),
                "k4" to peer(null, listOf("100.64.0.4"), true),
                "k5" to peer("nE", null, true),
            )
        )
        assertEquals(
            listOf(ExitNodeOption("nA", "100.64.0.1", "alpha"), ExitNodeOption("nB", "100.64.0.2", "bravo")),
            liveExitNodes(status)
        )
    }

    @Test
    fun noPeersNoExitNodes() {
        assertEquals(emptyList<ExitNodeOption>(), liveExitNodes(StatusResponse()))
    }
}
