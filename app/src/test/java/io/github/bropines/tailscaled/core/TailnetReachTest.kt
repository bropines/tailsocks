package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list the daemon's loopback guard reads (core/TailnetReach.kt, patch 23's
 * cmd/tailscaled/android_loopback.go): what goes into it for which switches and addresses.
 */
class TailnetReachTest {

    private val defaults = mapOf(
        TailnetListener.SOCKS5 to "127.0.0.1:48115",
        TailnetListener.HTTP_PROXY to "",
        TailnetListener.DNS_PROXY to "127.0.0.1:1053",
        TailnetListener.TAILDRIVE_PROXY to "",
    )

    @Test
    fun nothingIsListedUntilOpened() {
        assertEquals(emptyList<String>(), TailnetReach.lines(defaults, emptySet()))
    }

    @Test
    fun anOpenedListenerIsItsPortInItsProtocol() {
        assertEquals(listOf("tcp 48115"), TailnetReach.lines(defaults, setOf(TailnetListener.SOCKS5)))
        // The DNS proxy answers UDP only.
        assertEquals(listOf("udp 1053"), TailnetReach.lines(defaults, setOf(TailnetListener.DNS_PROXY)))
        val all = defaults + mapOf(TailnetListener.HTTP_PROXY to "127.0.0.1:8118", TailnetListener.TAILDRIVE_PROXY to "127.0.0.1:33445")
        assertEquals(
            listOf("tcp 48115", "tcp 8118", "udp 1053", "tcp 33445"),
            TailnetReach.lines(all, TailnetListener.entries.toSet()),
        )
    }

    @Test
    fun aListenerOnEveryAddressIsReachedThroughLoopbackToo() {
        val lan = defaults + (TailnetListener.SOCKS5 to "0.0.0.0:48115")
        assertEquals(listOf("tcp 48115"), TailnetReach.lines(lan, setOf(TailnetListener.SOCKS5)))
    }

    @Test
    fun whatTheDaemonCannotReachOnLoopbackIsNotListed() {
        // Off: nothing listens.
        assertEquals(emptyList<String>(), TailnetReach.lines(defaults, setOf(TailnetListener.HTTP_PROXY, TailnetListener.TAILDRIVE_PROXY)))
        // A loopback alias or an interface's own address: the forward to 127.0.0.1 would find nothing there.
        val alias = defaults + (TailnetListener.SOCKS5 to "127.42.7.1:48115")
        assertEquals(emptyList<String>(), TailnetReach.lines(alias, setOf(TailnetListener.SOCKS5)))
        val lanOnly = defaults + (TailnetListener.SOCKS5 to "192.168.1.20:48115")
        assertEquals(emptyList<String>(), TailnetReach.lines(lanOnly, setOf(TailnetListener.SOCKS5)))
        // A malformed address opens nothing.
        val broken = defaults + (TailnetListener.SOCKS5 to "127.0.0.1")
        assertEquals(emptyList<String>(), TailnetReach.lines(broken, setOf(TailnetListener.SOCKS5)))
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
