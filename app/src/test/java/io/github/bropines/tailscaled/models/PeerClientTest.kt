package io.github.bropines.tailscaled.models

import io.github.bropines.tailscaled.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerClientTest {
    @Test
    fun theStatusFieldIsRead() {
        val peer = AppJson.decodeFromString(PeerData.serializer(), """{"HostName":"poco","OS":"android","App":"tailsocks"}""")
        assertEquals("tailsocks", peer.app)
        assertTrue(peer.isTailSocks)
    }

    @Test
    fun tailSocksInEitherReport() {
        val honest = PeerData(os = "android", app = "tailsocks")
        val masked = PeerData(os = "linux", app = "tailsocks")
        for (peer in listOf(honest, masked)) {
            assertTrue(peer.isTailSocks)
            assertEquals("TailSocks", peer.client())
            assertEquals("android", peer.iconOs())
        }
    }

    // 5.3 and earlier: the masquerade said tailscale-cli, which no upstream build says.
    @Test
    fun theOldMasqueradeIsTailSocks() {
        val peer = PeerData(os = "linux", app = "tailscale-cli")
        assertTrue(peer.isTailSocks)
        assertEquals("android", peer.iconOs())
    }

    @Test
    fun othersAreWhatTheySay() {
        val official = PeerData(os = "android")
        assertFalse(official.isTailSocks)
        assertEquals("Tailscale", official.client())
        assertEquals("android", official.iconOs())

        val server = PeerData(os = "linux", app = "tsidp")
        assertFalse(server.isTailSocks)
        assertEquals("tsidp", server.client())
        assertEquals("linux", server.iconOs())

        // tailscale-cli means the masquerade only beside its OS.
        assertFalse(PeerData(os = "windows", app = "tailscale-cli").isTailSocks)
    }

    @Test
    fun theClientRowSitsUnderTheOs() {
        val rows = PeerData(os = "android", app = "tailsocks").getDetailsList().map { it.id }
        assertEquals(rows.indexOf(PeerDetailId.OS) + 1, rows.indexOf(PeerDetailId.CLIENT))
    }
}
