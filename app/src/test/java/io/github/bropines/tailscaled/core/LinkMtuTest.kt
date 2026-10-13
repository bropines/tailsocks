package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a network's MTU means for direct connections, and which interface is the one to read. */
class LinkMtuTest {

    private fun net(
        iface: String,
        vpn: Boolean = false,
        internet: Boolean = true,
        v4: Boolean = true,
        v6: Boolean = false,
        validated: Boolean = true,
        active: Boolean = false,
        propsMtu: Int = 0,
    ) = LinkCandidate(iface, vpn, internet, v4, v6, validated, active, propsMtu)

    /** A source with fixed answers; [failing] makes the named call throw. */
    private class Fake(
        val nets: List<LinkCandidate>,
        val mtus: Map<String, Int> = emptyMap(),
        val failList: Boolean = false,
        val failMtu: Boolean = false,
    ) : LinkSource {
        override fun candidates(): List<LinkCandidate> {
            if (failList) throw SecurityException("no permission")
            return nets
        }

        override fun interfaceMtu(iface: String): Int {
            if (failMtu) throw java.net.SocketException("ioctl failed")
            return mtus[iface] ?: 0
        }
    }

    // ---- the thresholds ----

    @Test
    fun thresholdsAreTheTunnelPlusWireGuardAndTheHeaders() {
        assertEquals(1340, LinkMtu.NEEDED_IPV4)
        assertEquals(1360, LinkMtu.NEEDED_IPV6)
    }

    // ---- the verdict ----

    @Test
    fun anOrdinaryLinkIsFine() {
        assertEquals(MtuVerdict.Fine, LinkMtu.verdict(1500))
        assertEquals(MtuVerdict.Fine, LinkMtu.verdict(1500, ipv6Underlay = true))
        assertEquals(MtuVerdict.Fine, LinkMtu.verdict(1500, ipv6Underlay = false))
    }

    @Test
    fun theTunnelsOwnMtuIsNotEnoughForADirectConnection() {
        // WSA's eth0 as measured: the tunnel's 1280 is what the link has, the packets need 80 more.
        assertEquals(MtuVerdict.TooSmall(1280, 1360), LinkMtu.verdict(1280))
        assertEquals(MtuVerdict.TooSmall(1280, 1340), LinkMtu.verdict(1280, ipv6Underlay = false))
    }

    @Test
    fun theBoundaryIsExact() {
        assertEquals(MtuVerdict.Fine, LinkMtu.verdict(1360))
        assertEquals(MtuVerdict.TooSmall(1359, 1360), LinkMtu.verdict(1359))
        assertEquals(MtuVerdict.Fine, LinkMtu.verdict(1340, ipv6Underlay = false))
        assertEquals(MtuVerdict.TooSmall(1339, 1340), LinkMtu.verdict(1339, ipv6Underlay = false))
    }

    @Test
    fun anUnknownFamilyIsJudgedByTheStricterNumber() {
        // 1350 carries IPv4 whole but not IPv6; with no word on the family the stricter one counts.
        assertEquals(MtuVerdict.TooSmall(1350, 1360), LinkMtu.verdict(1350, ipv6Underlay = null))
        assertEquals(MtuVerdict.TooSmall(1350, 1360), LinkMtu.verdict(1350, ipv6Underlay = true))
        assertEquals(MtuVerdict.Fine, LinkMtu.verdict(1350, ipv6Underlay = false))
    }

    @Test
    fun noNumberIsNoVerdict() {
        assertEquals(MtuVerdict.Unknown, LinkMtu.verdict(null))
        assertEquals(MtuVerdict.Unknown, LinkMtu.verdict(0))
        assertEquals(MtuVerdict.Unknown, LinkMtu.verdict(-1))
    }

    // ---- choosing the interface ----

    @Test
    fun theActiveNetworkIsTheOneWhenItIsNotAVpn() {
        val picked = LinkMtu.pickUnderlay(listOf(net("rmnet_data0"), net("wlan0", active = true)))
        assertEquals("wlan0", picked?.iface)
    }

    @Test
    fun aVpnThatIsActiveGivesWayToTheNetworkUnderIt() {
        // TUN mode: the active network is the app's own device, the packets leave by wlan0.
        val picked = LinkMtu.pickUnderlay(listOf(net("tun0", vpn = true, active = true), net("wlan0")))
        assertEquals("wlan0", picked?.iface)
    }

    @Test
    fun aForeignVpnIsNotTheLinkEither() {
        val picked = LinkMtu.pickUnderlay(listOf(net("wg0", vpn = true, active = true), net("eth0")))
        assertEquals("eth0", picked?.iface)
    }

    @Test
    fun theAppsOwnTunnelIsNeverChosenWhateverItIsFlaggedAs() {
        assertNull(LinkMtu.pickUnderlay(listOf(net("tun0"), net("tailscale0", active = true))))
    }

    @Test
    fun aValidatedNetworkBeatsOneAndroidDoesNotTrust() {
        val picked = LinkMtu.pickUnderlay(listOf(net("wlan1", validated = false), net("rmnet_data1")))
        assertEquals("rmnet_data1", picked?.iface)
    }

    @Test
    fun anActiveNetworkBeatsAValidatedOne() {
        val picked = LinkMtu.pickUnderlay(listOf(net("rmnet_data1"), net("wlan0", validated = false, active = true)))
        assertEquals("wlan0", picked?.iface)
    }

    @Test
    fun withNothingToTellThemApartTheFirstListedWins() {
        val picked = LinkMtu.pickUnderlay(listOf(net("eth1"), net("eth0")))
        assertEquals("eth1", picked?.iface)
    }

    @Test
    fun aNetworkWithoutInternetOrWithoutADefaultRouteIsNotTheLink() {
        val ims = net("rmnet_ims0", internet = false)
        val local = net("wlan1", v4 = false, v6 = false)
        val unnamed = net("")
        assertNull(LinkMtu.pickUnderlay(listOf(ims, local, unnamed)))
    }

    @Test
    fun noNetworkAtAllIsNoAnswer() {
        assertNull(LinkMtu.pickUnderlay(emptyList()))
        assertNull(LinkMtu.read(Fake(emptyList())))
    }

    // ---- reading ----

    @Test
    fun theInterfacesOwnMtuIsWhatIsReadAndJudged() {
        val reading = LinkMtu.read(Fake(listOf(net("eth0", v6 = true, active = true)), mapOf("eth0" to 1280)))
        assertEquals(LinkMtuReading("eth0", 1280, MtuVerdict.TooSmall(1280, 1360)), reading)
    }

    @Test
    fun anIpv4OnlyNetworkIsJudgedByTheIpv4Number() {
        val reading = LinkMtu.read(Fake(listOf(net("wlan0", v6 = false, active = true)), mapOf("wlan0" to 1350)))
        assertEquals(LinkMtuReading("wlan0", 1350, MtuVerdict.Fine), reading)
    }

    @Test
    fun anIpv6OnlyNetworkIsJudgedByTheIpv6Number() {
        val reading = LinkMtu.read(Fake(listOf(net("rmnet_data0", v4 = false, v6 = true, active = true)), mapOf("rmnet_data0" to 1350)))
        assertEquals(LinkMtuReading("rmnet_data0", 1350, MtuVerdict.TooSmall(1350, 1360)), reading)
    }

    @Test
    fun withAVpnActiveTheUnderlyingInterfaceIsRead() {
        val nets = listOf(net("tun0", vpn = true, active = true), net("wlan0", v6 = true))
        val reading = LinkMtu.read(Fake(nets, mapOf("tun0" to 1280, "wlan0" to 1500)))
        assertEquals(LinkMtuReading("wlan0", 1500, MtuVerdict.Fine), reading)
    }

    @Test
    fun anMtuOfZeroFromTheLinkPropertiesFallsBackToTheInterface() {
        val reading = LinkMtu.read(Fake(listOf(net("wlan0", propsMtu = 0, active = true)), mapOf("wlan0" to 1500)))
        assertEquals(1500, reading?.mtu)
    }

    @Test
    fun theLinkPropertiesAnswerWhenTheInterfaceHasNone() {
        val reading = LinkMtu.read(Fake(listOf(net("wlan0", propsMtu = 1280, active = true)), mapOf("wlan0" to 0)))
        assertEquals(LinkMtuReading("wlan0", 1280, MtuVerdict.TooSmall(1280, 1340)), reading)
    }

    @Test
    fun theLinkPropertiesAnswerWhenReadingTheInterfaceThrows() {
        val reading = LinkMtu.read(Fake(listOf(net("wlan0", v6 = true, propsMtu = 1500, active = true)), failMtu = true))
        assertEquals(LinkMtuReading("wlan0", 1500, MtuVerdict.Fine), reading)
    }

    @Test
    fun theInterfaceWinsWhenTheTwoDisagree() {
        val reading = LinkMtu.read(Fake(listOf(net("eth0", v6 = true, propsMtu = 1500, active = true)), mapOf("eth0" to 1280)))
        assertEquals(1280, reading?.mtu)
        assertEquals(MtuVerdict.TooSmall(1280, 1360), reading?.verdict)
    }

    @Test
    fun anInterfaceWithNoMtuAnywhereIsNamedAndUnknown() {
        val reading = LinkMtu.read(Fake(listOf(net("wlan0", active = true)), failMtu = true))
        assertEquals(LinkMtuReading("wlan0", null, MtuVerdict.Unknown), reading)
    }

    @Test
    fun anExceptionListingTheNetworksIsUnknownAndNothingMore() {
        assertNull(LinkMtu.read(Fake(emptyList(), failList = true)))
    }

    // ---- the report line ----

    @Test
    fun theReportLineSaysTheInterfaceTheNumberAndTheVerdict() {
        assertEquals(
            "Link MTU: wlan0 1500 (fine)",
            LinkMtu.reportLine(LinkMtuReading("wlan0", 1500, MtuVerdict.Fine))
        )
        assertEquals(
            "Link MTU: eth0 1280 (too small: direct connections need 1360)",
            LinkMtu.reportLine(LinkMtuReading("eth0", 1280, MtuVerdict.TooSmall(1280, 1360)))
        )
        assertEquals(
            "Link MTU: wlan0 unknown",
            LinkMtu.reportLine(LinkMtuReading("wlan0", null, MtuVerdict.Unknown))
        )
        assertEquals("Link MTU: unknown", LinkMtu.reportLine(null))
    }
}
