package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure rules of the peer screens that had no test: the daemon's RFC 3339 stamps
 * ([parseRfc3339Millis]), the Peers tabs ([peerTabRows], [visiblePeerTabs]) and the Taildrop
 * verdicts that decide who a file may be sent to on three screens ([taildropStatusOf],
 * [taildropPickerPeers], [withTaildropOwnership]).
 */
class PeerRulesTest {

    // ---------------------------------------------------------------- RFC 3339

    @Test
    fun utcStampWithoutFraction() {
        assertEquals(1_700_000_000_000L, parseRfc3339Millis("2023-11-14T22:13:20Z"))
    }

    @Test
    fun fractionalSecondsAreMillisecondsTruncated() {
        assertEquals(1_700_000_000_123L, parseRfc3339Millis("2023-11-14T22:13:20.123456789Z"))
        assertEquals(1_700_000_000_500L, parseRfc3339Millis("2023-11-14T22:13:20.5Z"))
    }

    @Test
    fun offsetsAreTakenOff() {
        assertEquals(1_700_000_000_000L, parseRfc3339Millis("2023-11-15T01:13:20+03:00"))
        assertEquals(1_700_000_000_000L, parseRfc3339Millis("2023-11-14T17:43:20-04:30"))
    }

    @Test
    fun goZeroTimeAndJunkAreNull() {
        assertNull(parseRfc3339Millis("0001-01-01T00:00:00Z"))
        assertNull(parseRfc3339Millis(null))
        assertNull(parseRfc3339Millis(""))
        assertNull(parseRfc3339Millis("yesterday"))
        assertNull(parseRfc3339Millis("2023-11-14 22:13:20"))
    }

    // ---------------------------------------------------------------- Peers tabs

    private val self = PeerData(id = "self", userID = 1, hostName = "phone", app = "tailsocks", tailscaleIPs = listOf("100.64.0.1"))
    private val laptop = PeerData(id = "a", userID = 1, hostName = "laptop", online = true)
    private val server = PeerData(id = "b", userID = 2, hostName = "server", tags = listOf("tag:srv"), online = false, exitNodeOption = true)
    private val friend = PeerData(id = "c", userID = 3, hostName = "friend", online = true)

    @Test
    fun eachTabTakesItsPeers() {
        val rows = peerTabRows(self, listOf(laptop, server, friend), "")
        assertEquals(listOf("a", "b", "c"), rows.getValue(PeerTab.ALL).peers.map { it.id })
        assertEquals(listOf("a", "c"), rows.getValue(PeerTab.ONLINE).peers.map { it.id })
        assertEquals(listOf("b"), rows.getValue(PeerTab.OFFLINE).peers.map { it.id })
        assertEquals(listOf("a"), rows.getValue(PeerTab.MINE).peers.map { it.id })
        assertEquals(listOf("b"), rows.getValue(PeerTab.TAGGED).peers.map { it.id })
        assertEquals(listOf("b"), rows.getValue(PeerTab.EXIT_NODES).peers.map { it.id })
        // This device: online, TailSocks, never offline and never its own exit node.
        assertEquals(self, rows.getValue(PeerTab.ONLINE).self)
        assertEquals(self, rows.getValue(PeerTab.TAILSOCKS).self)
        assertNull(rows.getValue(PeerTab.OFFLINE).self)
        assertNull(rows.getValue(PeerTab.EXIT_NODES).self)
    }

    @Test
    fun searchNarrowsEveryTabAndCanDropThisDevice() {
        val rows = peerTabRows(self, listOf(laptop, server, friend), "serv")
        assertEquals(listOf("b"), rows.getValue(PeerTab.ALL).peers.map { it.id })
        assertNull(rows.getValue(PeerTab.ALL).self)
        assertEquals(0, rows.getValue(PeerTab.ONLINE).size)
    }

    @Test
    fun onlyTabsThatNarrowTheListAreShown() {
        // Everyone online and untagged, all one user's: Online, Mine and Tagged say nothing new.
        val mine = listOf(laptop, laptop.copy(id = "d", hostName = "tablet"))
        val tabs = visiblePeerTabs(self, mine)
        assertEquals(PeerTab.ALL, tabs.first())
        assertEquals(false, PeerTab.ONLINE in tabs)
        assertEquals(false, PeerTab.TAGGED in tabs)
        // A mixed tailnet offers the filters that split it.
        val mixed = visiblePeerTabs(self, listOf(laptop, server, friend))
        assertEquals(true, PeerTab.OFFLINE in mixed)
        assertEquals(true, PeerTab.TAGGED in mixed)
    }

    // ---------------------------------------------------------------- Taildrop

    private val words = TaildropReasonStrings(
        blocked = "blocked", noNetmap = "no netmap", notRunning = "not running", missingCap = "missing cap",
        offline = "offline", noPeerInfo = "no peer info", unsupportedOs = "unsupported os",
        noPeerApi = "no peer api", otherUser = "other user",
    )

    @Test
    fun daemonCodesBecomeVerdicts() {
        assertEquals(TaildropStatus.Available, taildropStatusOf(PeerData(taildropTarget = 1), words))
        assertEquals(TaildropStatus.Offline("offline"), taildropStatusOf(PeerData(taildropTarget = 5), words))
        assertEquals(TaildropStatus.Blocked("other user"), taildropStatusOf(PeerData(taildropTarget = 9), words))
        assertEquals(TaildropStatus.Blocked("unsupported os"), taildropStatusOf(PeerData(taildropTarget = 7), words))
    }

    @Test
    fun unknownCodeIsNoRefusalUnlessAReasonCameWithIt() {
        assertEquals(TaildropStatus.Available, taildropStatusOf(PeerData(), words))
        assertEquals(TaildropStatus.Available, taildropStatusOf(PeerData(taildropTarget = 0), words))
        assertEquals(TaildropStatus.Blocked("nope"), taildropStatusOf(PeerData(taildropTarget = 0, noFileSharingReason = "nope"), words))
        // A code from a newer daemon: its reason, or the generic refusal.
        assertEquals(TaildropStatus.Blocked("blocked"), taildropStatusOf(PeerData(taildropTarget = 42), words))
    }

    @Test
    fun anOfflineDeviceOfAnotherUserIsRefusedAsItWillBeOnline() {
        val theirs = PeerData(userID = 3, taildropTarget = 5)
        assertEquals(9, theirs.withTaildropOwnership(selfUserId = 1).taildropTarget)
        assertEquals(5, theirs.withTaildropOwnership(selfUserId = 3).taildropTarget)
        assertEquals(5, theirs.withTaildropOwnership(selfUserId = null).taildropTarget)
    }

    @Test
    fun pickerDropsNonTargetsAndPutsTheSendableFirst() {
        val status = StatusResponse(
            self = PeerData(id = "self", userID = 1, hostName = "phone"),
            peers = mapOf(
                "k0" to PeerData(id = "self", userID = 1, hostName = "phone-again"),
                "k1" to PeerData(id = "n1", userID = 1, hostName = "zeta", taildropTarget = 1, online = true),
                "k2" to PeerData(id = "n2", userID = 1, hostName = "alpha", taildropTarget = 1, online = true),
                "k3" to PeerData(id = "n3", userID = 3, hostName = "theirs", taildropTarget = 5),
                "k4" to PeerData(id = "n4", userID = 1, hostName = "sleepy", taildropTarget = 5),
                "k5" to PeerData(id = "n5", hostName = "funnel-ingress-node"),
                "k6" to PeerData(id = "n6", hostName = "sharee", shareeNode = true),
                "k7" to PeerData(id = "n7"),
            ),
        )
        val picked = taildropPickerPeers(status, words)
        // Sendable by name, then offline, then refused (another user's offline device).
        assertEquals(listOf("n2", "n1", "n4", "n3"), picked.map { it.id })
        assertEquals(9, picked.last().taildropTarget)
    }
}
