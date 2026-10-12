package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPathTest {
    private fun peer(
        id: String,
        host: String,
        ip: String,
        online: Boolean = true,
        curAddr: String? = null,
        relay: String? = "fra",
        peerRelay: String? = null,
        lastWrite: String? = null,
        lastSeen: String? = null,
    ) = PeerData(
        id = id, hostName = host, dnsName = "$host.tail4a2c9.ts.net.", tailscaleIPs = listOf(ip),
        online = online, curAddr = curAddr, relay = relay, peerRelay = peerRelay,
        lastWrite = lastWrite, lastSeen = lastSeen
    )

    private val nas = peer("n2", "homelab-nas", "100.72.5.101")
    private val vps = peer("n3", "vps-relay", "100.94.210.8")
    private val pi = peer("n6", "raspberry-pi", "100.71.3.9")

    @Test
    fun peerRelayAddressDropsTheVni() {
        assertEquals("203.0.113.9:7777", peerRelayAddress("203.0.113.9:7777:vni:5"))
        assertEquals("[2001:db8::9]:7777", peerRelayAddress("[2001:db8::9]:7777:vni:12"))
        // What older demos wrote, without the suffix, is taken as it is.
        assertEquals("100.72.5.101:40000", peerRelayAddress("100.72.5.101:40000"))
    }

    @Test
    fun endpointHostReadsBothFamilies() {
        assertEquals("192.168.1.20", endpointHost("192.168.1.20:41641"))
        assertEquals("2001:db8::1", endpointHost("[2001:db8::1]:41641"))
        assertEquals("2001:db8::1", endpointHost("2001:db8::1"))
        assertEquals("198.51.100.7", endpointHost("198.51.100.7"))
    }

    @Test
    fun localAddressesArePrivateOnly() {
        for (h in listOf("10.0.0.5", "172.16.1.1", "172.31.255.1", "192.168.1.20", "169.254.3.4", "127.0.0.1", "fd7a:115c::1", "fe80::1"))
            assertTrue(h, isLocalAddress(h))
        for (h in listOf("203.0.113.24", "172.32.0.1", "100.64.0.1", "2001:db8::1", "8.8.8.8", "not-an-ip"))
            assertFalse(h, isLocalAddress(h))
        assertTrue(PeerPath.Direct("192.168.1.20:41641").local)
        assertFalse(PeerPath.Direct("[2001:db8::1]:41641").local)
    }

    @Test
    fun relayIsNamedByItsReportedEndpoint() {
        val hosts = listOf(
            RelayHost("homelab-nas", setOf("100.72.5.101"), setOf("203.0.113.9", "192.168.1.5")),
            RelayHost("raspberry-pi", setOf("100.71.3.9"), setOf("198.51.100.20")),
        )
        assertEquals("homelab-nas", relayNodeFor("203.0.113.9", hosts, servers = emptySet()))
        assertEquals("raspberry-pi", relayNodeFor("198.51.100.20", hosts, servers = emptySet()))
    }

    @Test
    fun twoDevicesBehindOneNatAreToldApartByTheRelayList() {
        val hosts = listOf(
            RelayHost("homelab-nas", setOf("100.72.5.101"), setOf("203.0.113.9")),
            RelayHost("desktop-home", setOf("100.88.12.4"), setOf("203.0.113.9")),
        )
        assertEquals("homelab-nas", relayNodeFor("203.0.113.9", hosts, setOf("100.72.5.101")))
        // Neither is a relay this device may use: nothing honest to say.
        assertNull(relayNodeFor("203.0.113.9", hosts, emptySet()))
    }

    @Test
    fun theOnlyRelayIsNamedWhenNothingContradictsIt() {
        val unknown = listOf(
            RelayHost("homelab-nas", setOf("100.72.5.101"), null),
            RelayHost("raspberry-pi", setOf("100.71.3.9"), null),
        )
        assertEquals("homelab-nas", relayNodeFor("203.0.113.9", unknown, setOf("100.72.5.101")))
        // Two relays and no endpoints to choose by.
        assertNull(relayNodeFor("203.0.113.9", unknown, setOf("100.72.5.101", "100.71.3.9")))
        // The one relay reported other endpoints: the far side's relay, not ours.
        val known = listOf(RelayHost("homelab-nas", setOf("100.72.5.101"), setOf("198.51.100.1")))
        assertNull(relayNodeFor("203.0.113.9", known, setOf("100.72.5.101")))
        // A relay known to be elsewhere does not hide the one nobody reported on.
        val mixed = known + RelayHost("vps-relay", setOf("100.94.210.8"), null)
        assertEquals("vps-relay", relayNodeFor("203.0.113.9", mixed, setOf("100.72.5.101", "100.94.210.8")))
    }

    @Test
    fun pathsReadLikeTailscaleStatus() {
        val names = PeerPathNames(
            listOf(nas, vps, pi),
            PeerPathFacts(derpCities = mapOf("fra" to "Frankfurt"), relayServers = setOf("100.94.210.8"))
        )
        // A direct endpoint wins over the home region the daemon always fills in.
        assertEquals(PeerPath.Direct("192.168.1.20:41641"), names.pathOf(peer("a", "a", "100.1.1.1", curAddr = "192.168.1.20:41641"), false))
        // A peer relay, named after the one relay this device may use.
        assertEquals(
            PeerPath.PeerRelay("203.0.113.9:7777", "vps-relay"),
            names.pathOf(peer("b", "b", "100.1.1.2", peerRelay = "203.0.113.9:7777:vni:5"), false)
        )
        // DERP, with the region's city.
        assertEquals(PeerPath.Derp("fra", "Frankfurt"), names.pathOf(peer("c", "c", "100.1.1.3", lastWrite = "2026-10-12T05:00:00Z"), false))
        // A daemon that did not say when it last wrote gets the benefit of the doubt.
        assertEquals(PeerPath.Derp("fra", "Frankfurt"), names.pathOf(peer("d", "d", "100.1.1.4"), false))
        // Nothing ever sent: no path yet, only where it would be reached.
        val idle = peer("e", "e", "100.1.1.5", lastWrite = "0001-01-01T00:00:00Z")
        assertEquals(PeerPath.NotYet("fra", "Frankfurt"), names.pathOf(idle, false))
        assertEquals(PeerPath.Offline(null), names.pathOf(peer("f", "f", "100.1.1.6", online = false, curAddr = "1.2.3.4:5"), false))
        assertEquals(PeerPath.Self("fra", "Frankfurt"), names.pathOf(peer("g", "g", "100.1.1.7"), true))
    }

    @Test
    fun aPingFillsInOnlyAPeerWithNoPathYet() {
        val names = PeerPathNames(emptyList(), PeerPathFacts(derpCities = mapOf("ams" to "Amsterdam")))
        val idle = peer("e", "e", "100.1.1.5", lastWrite = "0001-01-01T00:00:00Z")
        val viaDerp = pingStateOf("""{"LatencySeconds": 0.096, "DERPRegionID": 4, "DERPRegionCode": "ams", "Endpoint": "", "PeerRelay": ""}""")
        assertEquals(PeerPingState.Measured("96 ms", PeerPath.Derp("ams", null)), viaDerp)
        assertEquals(PeerPath.Derp("ams", "Amsterdam"), names.pathOf(idle, false, viaDerp))
        // A peer the status already reads keeps that reading.
        val direct = peer("a", "a", "100.1.1.1", curAddr = "192.168.1.20:41641")
        assertEquals(PeerPath.Direct("192.168.1.20:41641"), names.pathOf(direct, false, viaDerp))
    }

    @Test
    fun pingAnswersSayWhichWayTheyCame() {
        assertEquals(
            PeerPath.Direct("192.168.1.20:41641"),
            (pingStateOf("""{"LatencySeconds": 0.008, "Endpoint": "192.168.1.20:41641"}""") as PeerPingState.Measured).via
        )
        assertEquals(
            PeerPath.PeerRelay("203.0.113.9:7777", null),
            (pingStateOf("""{"LatencySeconds": 0.02, "Endpoint": "", "PeerRelay": "203.0.113.9:7777:vni:5"}""") as PeerPingState.Measured).via
        )
        // The plain line of `tailscale ping` has only its figure.
        assertEquals(PeerPingState.Measured("8ms", null), pingStateOf("pong from desktop (100.88.12.4) via 192.168.1.20:41641 in 8ms"))
        assertEquals(PeerPingState.Failed, pingStateOf("""{"Err": "timeout"}"""))
    }
}
