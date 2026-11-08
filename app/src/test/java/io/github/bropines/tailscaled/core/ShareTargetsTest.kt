package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which devices the system share sheet offers, in what order, and whose they are. */
class ShareTargetsTest {

    private fun c(id: String, available: Boolean = true) = ShareCandidate(id, id, phone = false, available = available)

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
}
