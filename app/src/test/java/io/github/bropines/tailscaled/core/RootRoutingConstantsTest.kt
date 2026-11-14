package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kernel-side names of the Root Mode ruleset ([RootRouting]). They are
 * shared with the boot script (assets/scripts/tailscaled.sh), with patch 16
 * (netns_android.go TailsocksBypassMark) and with every ruleset an earlier
 * version left on a device, which cleanup must still recognise — so none of
 * them may change as a side effect of an edit. A deliberate change updates
 * this test, the boot script and the docs together.
 */
class RootRoutingConstantsTest {

    @Test
    fun `tables`() {
        assertEquals("53", RootRouting.ROUTE_TABLE)
        assertEquals("52", RootRouting.DAEMON_TABLE)
        assertEquals("1099", RootRouting.LEGACY_ROUTE_TABLE)
    }

    @Test
    fun `rule priorities`() {
        assertEquals("150", RootRouting.LAN_RETURN_PRIO)
        assertEquals("190", RootRouting.EXCLUDE_PRIO)
        assertEquals("200", RootRouting.CATCH_ALL_PRIO)
        assertEquals("10000", RootRouting.NETD_RULE_MIN)
        assertEquals("32000", RootRouting.NETD_RULE_MAX)
        // The tailnet rules carry their priority in the script text rather than in a constant.
        val tailnet = RootUtils.tailnetRulesV4()
        assertTrue(tailnet, tailnet.contains("table ${RootRouting.ROUTE_TABLE} priority 100\n"))
    }

    @Test
    fun `fwmarks`() {
        assertEquals("0x1000000", RootRouting.MARK_BIT)
        assertEquals("0x1000000", RootRouting.MARK_MASK)
        assertEquals("1099", RootRouting.LEGACY_MARK)
        assertEquals("0x2000000", RootRouting.BYPASS_MARK)
        assertEquals("0x2020000", RootRouting.CATCH_ALL_MASK)
        assertEquals("0x20000", RootRouting.PROTECT_MARK)
        assertEquals(listOf("0x2020000", "0x2000000"), RootUtils.DAEMON_SOCKET_MARKS)
        assertEquals("netns: SO_MARK 0x2000000 set on tailscaled sockets", RootUtils.SO_MARK_OK)
    }

    @Test
    fun `chains and addresses`() {
        assertEquals("TAILSOCKS_MARK", RootRouting.CHAIN_MARK)
        assertEquals("TAILSOCKS_DNS", RootRouting.CHAIN_DNS)
        assertEquals("TAILSOCKS_BYPASS", RootRouting.CHAIN_BYPASS)
        assertEquals("100.64.0.0/10", RootRouting.CGNAT_V4)
        assertEquals("fd7a:115c:a1e0::/48", RootRouting.TAILNET_V6)
        assertEquals("100.100.100.100", RootRouting.MAGIC_DNS)
        assertEquals(RootPaths.RP_FILTER_ORIG, RootRouting.RP_FILTER_ORIG)
    }
}
