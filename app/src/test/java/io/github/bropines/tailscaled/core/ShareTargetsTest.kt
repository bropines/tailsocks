package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which devices the system share sheet offers, in what order, and whose they are. */
class ShareTargetsTest {

    private fun c(id: String, available: Boolean = true) = ShareCandidate(id, id, ShareTargetOs.OTHER, available = available)

    @Test
    fun recentFirstThenPickerOrder() {
        val picked = pickShareTargets(listOf(c("a"), c("b"), c("c")), recentPeerIds = listOf("c", "b"), limit = 8)
        assertEquals(listOf("c", "b", "a"), picked.map { it.peerId })
    }

    @Test
    fun offlineOnlyWhenSentToBeforeAndAfterTheOnlineOnes() {
        val picked = pickShareTargets(
            listOf(c("off-old", available = false), c("on"), c("off-new", available = false)),
            recentPeerIds = listOf("off-old"),
            limit = 8
        )
        assertEquals(listOf("on", "off-old"), picked.map { it.peerId })
    }

    @Test
    fun limitCutsTheTail() {
        val picked = pickShareTargets(listOf(c("a"), c("b"), c("c")), emptyList(), limit = 2)
        assertEquals(listOf("a", "b"), picked.map { it.peerId })
        assertEquals(emptyList<ShareCandidate>(), pickShareTargets(listOf(c("a")), emptyList(), limit = -1))
    }

    @Test
    fun pinnedComeFirstEvenOfflineAndHiddenNeverShow() {
        val picks = ShareTargetPicks(pinned = setOf("pin-off", "pin-on"), hidden = setOf("hid"))
        val picked = pickShareTargets(
            listOf(c("a"), c("hid"), c("pin-off", available = false), c("pin-on"), c("off", available = false)),
            recentPeerIds = listOf("hid", "a"),
            limit = 8,
            picks = picks,
        )
        // Pinned: online before offline; then the automatic ones; the offline one never sent to stays out.
        assertEquals(listOf("pin-on", "pin-off", "a"), picked.map { it.peerId })
    }

    @Test
    fun pinnedTakeTheLimitFirst() {
        val picked = pickShareTargets(listOf(c("a"), c("b"), c("c")), recentPeerIds = listOf("a"), limit = 2, picks = ShareTargetPicks(pinned = setOf("c", "b")))
        assertEquals(listOf("b", "c"), picked.map { it.peerId })
    }

    @Test
    fun picksSwitchModes() {
        var p = ShareTargetPicks()
        assertEquals(ShareTargetMode.AUTO, p.modeOf("x"))
        p = p.with("x", ShareTargetMode.PINNED)
        assertEquals(ShareTargetMode.PINNED, p.modeOf("x"))
        p = p.with("x", ShareTargetMode.HIDDEN)
        assertEquals(ShareTargetMode.HIDDEN, p.modeOf("x"))
        assertEquals(emptySet<String>(), p.pinned)
        p = p.with("x", ShareTargetMode.AUTO)
        assertEquals(true, p.isEmpty)
    }

    @Test
    fun recentRecipientsAreSuccessfulSendsNewestFirst() {
        fun e(peer: String?, ok: Boolean = true, dir: TaildropDirection = TaildropDirection.SENT) =
            TaildropHistoryEntry(name = "f", peerName = "p", timestamp = 0, direction = dir, ok = ok, peerId = peer)
        val history = listOf(e("x"), e("y", ok = false), e("z", dir = TaildropDirection.RECEIVED), e(null), e("w"), e("x"))
        assertEquals(listOf("x", "w"), recentRecipients(history))
    }

    @Test
    fun shortcutIdIsScopedToItsAccount() {
        val id = ShareTargets.shortcutId("acc1", "nNode123CNTRL")
        assertEquals("nNode123CNTRL", ShareTargets.peerIdFor(id, "acc1"))
        assertNull(ShareTargets.peerIdFor(id, "acc2"))
        assertNull(ShareTargets.peerIdFor(id, "acc"))
        assertNull(ShareTargets.peerIdFor("toggle", "acc1"))
        assertNull(ShareTargets.peerIdFor(null, "acc1"))
    }

    @Test
    fun iconFollowsTheDeviceListsOs() {
        assertEquals(ShareTargetOs.ANDROID, shareTargetOsOf("android"))
        assertEquals(ShareTargetOs.WINDOWS, shareTargetOsOf("windows"))
        assertEquals(ShareTargetOs.LINUX, shareTargetOsOf("linux"))
        assertEquals(ShareTargetOs.MACOS, shareTargetOsOf("macOS"))
        assertEquals(ShareTargetOs.MACOS, shareTargetOsOf("darwin"))
        assertEquals(ShareTargetOs.IOS, shareTargetOsOf("iOS"))
        assertEquals(ShareTargetOs.OTHER, shareTargetOsOf("freebsd"))
        assertEquals(ShareTargetOs.OTHER, shareTargetOsOf(null))
    }

    @Test
    fun labelsDropThePrefixSimilarNamesShare() {
        assertEquals(
            listOf("pc", "macbook-air", "23030rac7y"),
            shareTargetLabels(listOf("personal-pinus-pc", "personal-pinus-macbook-air", "23030rac7y")),
        )
        // The longest shared prefix, cut at a hyphen, per name.
        assertEquals(
            listOf("pc", "macbook-air", "laptop"),
            shareTargetLabels(listOf("home-pinus-pc", "home-pinus-macbook-air", "home-laptop")),
        )
        assertEquals(listOf("8", "8-1"), shareTargetLabels(listOf("pixel-8", "pixel-8-1")))
    }

    @Test
    fun labelsStayWholeWhenCuttingWouldConfuse() {
        // Nothing shared, a single device; a trailing hyphen is no cut that leaves nothing.
        assertEquals(listOf("nas", "desktop"), shareTargetLabels(listOf("nas", "desktop")))
        assertEquals(listOf("personal-pinus-pc"), shareTargetLabels(listOf("personal-pinus-pc")))
        assertEquals(listOf("pc-", "2"), shareTargetLabels(listOf("pc-", "pc-2")))
        // Two names that would be cut to the same label keep their full names.
        assertEquals(
            listOf("a-c", "a-b-c", "d"),
            shareTargetLabels(listOf("a-c", "a-b-c", "a-b-d")),
        )
        assertEquals(emptyList<String>(), shareTargetLabels(emptyList()))
    }
}
