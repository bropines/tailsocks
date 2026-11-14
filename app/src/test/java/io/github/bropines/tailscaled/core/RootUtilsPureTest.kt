package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of RootUtils that are plain functions: quoting, validation, prefix arithmetic, the probe parser, gap arithmetic. */
class RootUtilsPureTest {

    // --- the injection boundary (agents.md: untrusted text before a root shell) ---

    @Test
    fun `shQuote keeps a value one literal word`() {
        assertEquals("'plain'", RootUtils.shQuote("plain"))
        assertEquals("'it'\\''s'", RootUtils.shQuote("it's"))
        assertEquals("'\$(reboot)`id`'", RootUtils.shQuote("\$(reboot)`id`"))
        // Line breaks would end the line in the env file the boot script parses.
        assertEquals("'ab'", RootUtils.shQuote("a\r\nb"))
    }

    @Test
    fun `only real addresses pass as hosts entries`() {
        assertTrue(RootUtils.isIpLiteral("100.64.0.1"))
        assertTrue(RootUtils.isIpLiteral("fd7a:115c:a1e0::1"))
        assertFalse(RootUtils.isIpLiteral("256.1.1.1"))
        assertFalse(RootUtils.isIpLiteral("1.2.3"))
        assertFalse(RootUtils.isIpLiteral("1.2.3.4; reboot"))
        assertFalse(RootUtils.isIpLiteral("abcdef"))
    }

    // --- Excluded IP ranges ---

    @Test
    fun `exclusions are split by family, link-local added, junk dropped`() {
        val raw = "192.168.0.0/16, 10.0.0.0/8;fd00::/8\n  garbage 1.2.3.4 999.1.1.1/99"
        val (v4, droppedV4) = RootUtils.filterCidrs(raw, v6 = false)
        assertEquals(listOf("192.168.0.0/16", "10.0.0.0/8", "999.1.1.1/99", "169.254.0.0/16"), v4)
        // The regex is shape-only, as before: 999.1.1.1/99 passes it and `ip` refuses it on the device.
        assertEquals(listOf("garbage", "1.2.3.4"), droppedV4)
        val (v6, droppedV6) = RootUtils.filterCidrs(raw, v6 = true)
        assertEquals(listOf("fd00::/8", "fe80::/10"), v6)
        assertTrue(droppedV6.isEmpty())
    }

    @Test
    fun `an empty setting leaves link-local alone`() {
        assertEquals(listOf("169.254.0.0/16"), RootUtils.filterCidrs("", v6 = false).first)
        assertEquals(listOf("fe80::/10"), RootUtils.filterCidrs("", v6 = true).first)
    }

    @Test
    fun `duplicates are written once`() {
        assertEquals(listOf("10.0.0.0/8", "169.254.0.0/16"), RootUtils.filterCidrs("10.0.0.0/8 10.0.0.0/8", v6 = false).first)
    }

    // --- prefix arithmetic shared by ownLanPrefixes and the CGNAT guard ---

    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test
    fun `an address is masked to its network`() {
        assertEquals("192.168.1.0/24", RootUtils.ipv4Network(v4(192, 168, 1, 94), 24))
        assertEquals("10.0.0.0/8", RootUtils.ipv4Network(v4(10, 200, 3, 4), 8))
        assertEquals("100.96.0.0/11", RootUtils.ipv4Network(v4(100, 100, 7, 1), 11))
        assertEquals("172.16.5.4/32", RootUtils.ipv4Network(v4(172, 16, 5, 4), 32))
        assertEquals("128.0.0.0/1", RootUtils.ipv4Network(v4(200, 1, 1, 1), 1))
    }

    @Test
    fun `the value of an address is unsigned`() {
        assertEquals(0xc0a8015eL, RootUtils.ipv4Value(v4(192, 168, 1, 94)))
        assertEquals(0xffffffffL, RootUtils.ipv4Value(v4(255, 255, 255, 255)))
        assertTrue(RootUtils.ipv4Value(v4(100, 64, 0, 1)) in 0x64400000L..0x647fffffL)
        assertFalse(RootUtils.ipv4Value(v4(100, 128, 0, 1)) in 0x64400000L..0x647fffffL)
    }

    // --- coexistence: the probe output and the gaps ---

    @Test
    fun `a tunnel that is up and has member rules is another VPN, with its ranges and netId`() {
        val out = """
            FOREIGN_IF tun0
            FOREIGN_UIDS tun0 0-10354
            FOREIGN_MARK tun0 0x10084/0x1ffff
            FOREIGN_RULE 12000 tun0
            FOREIGN_UIDS tun0 10356-99999
            FOREIGN_RULE 12000 tun0
            FOREIGN_UIDS wlan0_local 0-99999
            FOREIGN_MARK tun0 0x0/0x1ffff
        """.trimIndent()
        val v = RootUtils.parseForeignVpnProbe(out)
        assertTrue(v.reason, v.present)
        assertEquals(listOf(0L..10354L, 10356L..99999L), v.memberRanges)
        assertEquals(listOf(0x84), v.netIds)
    }

    @Test
    fun `an interface without rules, or rules without an interface, is not another VPN`() {
        assertFalse(RootUtils.parseForeignVpnProbe("FOREIGN_IF tun0\n").present)
        assertFalse(RootUtils.parseForeignVpnProbe("FOREIGN_RULE 12000 tun0\n").present)
        assertFalse(RootUtils.parseForeignVpnProbe("").present)
    }

    @Test
    fun `a malformed range is dropped rather than guessed at`() {
        val out = "FOREIGN_IF tun0\nFOREIGN_UIDS tun0 10-5\nFOREIGN_UIDS tun0 abc\nFOREIGN_UIDS tun0 7-9\nFOREIGN_RULE 13000 tun0\n"
        assertEquals(listOf(7L..9L), RootUtils.parseForeignVpnProbe(out).memberRanges)
    }

    @Test
    fun `the gaps are what the other tunnel does not claim, up to the last app uid`() {
        assertEquals(emptyList<LongRange>(), RootUtils.foreignBypassGaps(emptyList()))
        assertEquals(listOf(10355L..10355L), RootUtils.foreignBypassGaps(listOf(0L..10354L, 10356L..99999L)))
        assertEquals(
            listOf(0L..9999L, 10501L..99999L),
            RootUtils.foreignBypassGaps(listOf(10000L..10500L))
        )
        // Overlapping and unsorted ranges.
        assertEquals(listOf(10L..19L), RootUtils.foreignBypassGaps(listOf(30L..99999L, 0L..9L, 20L..35L)))
    }

    @Test
    fun `a range is split around its holes`() {
        assertEquals(listOf(10L..12L, 14L..20L), RootUtils.splitAround(10L..20L, listOf(13L, 50L)))
        assertEquals(listOf(11L..19L), RootUtils.splitAround(10L..20L, listOf(10L, 20L)))
        assertEquals(emptyList<LongRange>(), RootUtils.splitAround(5L..5L, listOf(5L)))
        assertEquals(listOf(1L..3L), RootUtils.splitAround(1L..3L, emptyList()))
    }

    // --- the DNS health gate's outside probe ---

    @Test
    fun `the upstream probe asks a new name under the stable zone every time`() {
        val names = (1..50).map { RootUtils.upstreamProbeName() }
        assertEquals(50, names.toSet().size)
        for (n in names) {
            assertTrue(n, n.endsWith("." + RootUtils.UPSTREAM_PROBE_ZONE))
            assertTrue(n, Regex("^tsprobe-[0-9a-f]{16}\\.").containsMatchIn(n))
        }
        // A seeded source gives the same label: the randomness is the only input.
        assertEquals(RootUtils.upstreamProbeName(java.util.Random(7)), RootUtils.upstreamProbeName(java.util.Random(7)))
    }

    @Test
    fun `NOERROR and NXDOMAIN are healthy, every other answer code is not`() {
        assertTrue(RootUtils.upstreamProbeHealthy(0))
        assertTrue(RootUtils.upstreamProbeHealthy(3))
        for (rcode in listOf(1, 2, 4, 5, 9, -1)) assertFalse("rcode $rcode", RootUtils.upstreamProbeHealthy(rcode))
    }
}
