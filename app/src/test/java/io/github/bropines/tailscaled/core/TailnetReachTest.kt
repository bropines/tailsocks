package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy the daemon's loopback guard reads (core/TailnetReach.kt, patch 23's
 * cmd/tailscaled/android_loopback.go): what goes into it for which switches, addresses and mode,
 * and how the field of ports left open in strict mode is read.
 */
class TailnetReachTest {

    private val defaults = mapOf(
        TailnetListener.SOCKS5 to "127.0.0.1:48115",
        TailnetListener.HTTP_PROXY to "",
        TailnetListener.DNS_PROXY to "127.0.0.1:1053",
        TailnetListener.TAILDRIVE_PROXY to "",
    )

    private val all = defaults + mapOf(TailnetListener.HTTP_PROXY to "127.0.0.1:8118", TailnetListener.TAILDRIVE_PROXY to "127.0.0.1:33445")

    @Test
    fun byDefaultTheAppsOwnListenersAreClosedAndNothingElse() {
        // A fresh install: SOCKS5 and the DNS proxy, which answers UDP only.
        assertEquals(listOf("mode default", "close tcp 48115", "close udp 1053"), TailnetReach.lines(defaults, emptySet()))
        assertEquals(
            listOf("mode default", "close tcp 48115", "close tcp 8118", "close udp 1053", "close tcp 33445"),
            TailnetReach.lines(all, emptySet()),
        )
        // The Web UI and TailCat's forwards are the app's too, and are never opened.
        assertEquals(
            listOf("mode default", "close tcp 48115", "close udp 1053", "close tcp 8080", "close tcp 2222"),
            TailnetReach.lines(defaults, emptySet(), others = listOf("127.0.0.1:8080", "127.0.0.1:2222")),
        )
    }

    @Test
    fun anOpenedListenerIsNotClosed() {
        assertEquals(listOf("mode default", "close udp 1053"), TailnetReach.lines(defaults, setOf(TailnetListener.SOCKS5)))
        assertEquals(listOf("mode default"), TailnetReach.lines(all, TailnetListener.entries.toSet()))
    }

    @Test
    fun strictOpensWhatIsOpenedAndTheFieldsPorts() {
        assertEquals(listOf("mode strict"), TailnetReach.lines(defaults, emptySet(), strict = true))
        assertEquals(listOf("mode strict", "open tcp 48115"), TailnetReach.lines(defaults, setOf(TailnetListener.SOCKS5), strict = true))
        val extra = listOf(ReachPort(5555), ReachPort(8022), ReachPort(1234, udp = true))
        assertEquals(
            listOf("mode strict", "open udp 1053", "open tcp 5555", "open tcp 8022", "open udp 1234"),
            TailnetReach.lines(defaults, setOf(TailnetListener.DNS_PROXY), strict = true, extra = extra),
        )
        // The field's ports mean nothing in default mode: everything not closed is open there.
        assertEquals(listOf("mode default", "close tcp 48115", "close udp 1053"), TailnetReach.lines(defaults, emptySet(), extra = extra))
    }

    @Test
    fun theFieldCannotOpenTheAppsOwnPorts() {
        // A closed proxy, the Web UI, remote management: each has its own switch.
        val extra = listOf(ReachPort(48115), ReachPort(8080), ReachPort(Exposure.REMOTE_PORT), ReachPort(5555))
        assertEquals(
            listOf("mode strict", "open tcp 5555"),
            TailnetReach.lines(defaults, emptySet(), others = listOf("127.0.0.1:8080"), strict = true, extra = extra),
        )
        // The same number in the other protocol is not the app's.
        assertEquals(
            listOf("mode strict", "open udp 48115"),
            TailnetReach.lines(defaults, emptySet(), strict = true, extra = listOf(ReachPort(48115, udp = true))),
        )
    }

    @Test
    fun aListenerOnEveryAddressIsReachedThroughLoopbackToo() {
        val lan = defaults + (TailnetListener.SOCKS5 to "0.0.0.0:48115")
        assertEquals(listOf("mode default", "close tcp 48115", "close udp 1053"), TailnetReach.lines(lan, emptySet()))
        assertEquals(listOf("mode strict", "open tcp 48115"), TailnetReach.lines(lan, setOf(TailnetListener.SOCKS5), strict = true))
    }

    @Test
    fun whatTheDaemonCannotReachOnLoopbackIsNotNamed() {
        // Off: nothing listens.
        assertEquals(listOf("mode strict"), TailnetReach.lines(defaults, setOf(TailnetListener.HTTP_PROXY, TailnetListener.TAILDRIVE_PROXY), strict = true))
        // A loopback alias or an interface's own address: the forward to 127.0.0.1 would find
        // nothing of ours there, so another app's 127.0.0.1 listener on the port stays its own.
        for (bind in listOf("127.42.7.1:48115", "192.168.1.20:48115", "127.0.0.1")) {
            val moved = defaults + (TailnetListener.SOCKS5 to bind)
            assertEquals(bind, listOf("mode default", "close udp 1053"), TailnetReach.lines(moved, emptySet()))
            assertEquals(bind, listOf("mode strict"), TailnetReach.lines(moved, setOf(TailnetListener.SOCKS5), strict = true))
        }
    }

    @Test
    fun twoListenersOnOnePortAreClosed() {
        // The HTTP proxy opened and the Web UI on its port (both default to 8080): whichever is
        // bound, the one that is not opened might be.
        val http = defaults + (TailnetListener.HTTP_PROXY to "127.0.0.1:8080")
        val web = listOf("127.0.0.1:8080")
        assertEquals(listOf("mode default", "close tcp 48115", "close udp 1053", "close tcp 8080"), TailnetReach.lines(http, setOf(TailnetListener.HTTP_PROXY), web))
        assertEquals(listOf("mode strict"), TailnetReach.lines(http, setOf(TailnetListener.HTTP_PROXY), web, strict = true))
    }

    @Test
    fun theFieldIsReadAsPortsTcpUnlessSaidOtherwise() {
        val read = TailnetReach.parseExtra("5555, 8022 udp:1234,TCP:2222;5555,, ")
        assertEquals(listOf(ReachPort(5555), ReachPort(8022), ReachPort(1234, udp = true), ReachPort(2222)), read.ports)
        assertTrue(read.valid)
        assertEquals(ExtraPorts(emptyList()), TailnetReach.parseExtra(""))

        val bad = TailnetReach.parseExtra("0, 65536, abc, sctp:80, udp:, :80, 1:2:3, 22")
        assertEquals(listOf(ReachPort(22)), bad.ports)
        assertEquals(listOf("0", "65536", "abc", "sctp:80", "udp:", ":80", "1:2:3"), bad.bad)
        assertFalse(bad.valid)

        val own = TailnetReach.parseExtra("48115, udp:48115, 28115, 5555", setOf(ReachPort(48115), ReachPort(Exposure.REMOTE_PORT)))
        assertEquals(listOf(ReachPort(48115, udp = true), ReachPort(5555)), own.ports)
        assertEquals(listOf("48115", "28115"), own.own)
        assertFalse(own.valid)

        assertEquals("5555, 8022, udp:1234", TailnetReach.normalExtra(" 5555 ,8022, UDP:1234, 5555"))
    }

    @Test
    fun theAppsOwnPortsAreWhatTheForwardReachesAndRemoteManagement() {
        assertEquals(
            setOf(ReachPort(48115), ReachPort(1053, udp = true), ReachPort(8080), ReachPort(Exposure.REMOTE_PORT)),
            TailnetReach.ownPorts(defaults, listOf("127.0.0.1:8080", "127.9.9.9:9999")),
        )
    }

    @Test
    fun loopbackAndEveryAddressAreWhatTheForwardReaches() {
        for (bind in listOf("127.0.0.1:1", "localhost:80", "0.0.0.0:48115", "[::]:48115", ":33445")) {
            assertTrue(bind, TailnetReach.reachableThroughLoopback(bind))
        }
        for (bind in listOf("127.0.0.2:1", "[::1]:53", "192.168.1.20:8080", "100.64.0.5:80", "", "127.0.0.1")) {
            assertFalse(bind, TailnetReach.reachableThroughLoopback(bind))
        }
    }

    @Test
    fun eachListenerIsAnExposureRow() {
        for (listener in TailnetListener.entries) assertEquals(listener, TailnetListener.of(listener.item))
        assertEquals(null, TailnetListener.of(ExposureItem.WEB_UI))
    }
}
