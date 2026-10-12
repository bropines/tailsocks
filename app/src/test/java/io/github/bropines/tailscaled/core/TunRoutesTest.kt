package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunRoutesTest {

    @Test
    fun theEngineIsNativeUnlessHevWasChosen() {
        assertEquals(TunEngines.NATIVE, TunEngines.resolve(null))
        assertEquals(TunEngines.NATIVE, TunEngines.resolve(""))
        assertEquals(TunEngines.NATIVE, TunEngines.resolve("something-else"))
        assertEquals(TunEngines.NATIVE, TunEngines.resolve("native"))
        // Stored only by the picker: switching to native and back is a choice too.
        assertEquals(TunEngines.HEV, TunEngines.resolve("hev"))
    }

    @Test
    fun prefixesAreParsedNumericallyWithHostBitsCleared() {
        assertEquals("192.168.1.0/24", TunRoutes.parse("192.168.1.5/24").toString())
        assertEquals("10.0.0.0/8", TunRoutes.parse(" 10.0.0.0/8 ").toString())
        assertEquals("1.2.3.4/32", TunRoutes.parse("1.2.3.4").toString())
        assertEquals("0.0.0.0/0", TunRoutes.parse("0.0.0.0/0").toString())
        assertEquals("2001:db8:0:0:0:0:0:0/64", TunRoutes.parse("2001:db8::1/64").toString())
        assertEquals("fd7a:115c:a1e0:0:0:0:0:0/48", TunRoutes.parse("fd7a:115c:a1e0::/48").toString())
        assertTrue(TunRoutes.parse("2001:db8::/32")!!.isV6)
        for (bad in listOf("", "300.1.1.1/8", "1.2.3/8", "1.2.3.4/33", "1.2.3.4/x", "example.com/24", "fe80::1%wlan0/64", "::ffff:1.2.3.4/120")) {
            assertNull(bad, TunRoutes.parse(bad))
        }
    }

    @Test
    fun containmentAndOverlap() {
        val wide = TunRoutes.parse("192.168.0.0/16")!!
        val lan = TunRoutes.parse("192.168.50.0/24")!!
        val other = TunRoutes.parse("10.0.0.0/8")!!
        assertTrue(wide.contains(lan))
        assertTrue(!lan.contains(wide))
        assertTrue(lan.overlaps(wide) && wide.overlaps(lan))
        assertTrue(!other.overlaps(lan))
        assertTrue(!TunRoutes.parse("::/0")!!.contains(lan))
    }

    private val status = """
        {
          "BackendState": "Running",
          "Self": {"TailscaleIPs": ["100.64.0.1", "fd7a:115c:a1e0::1"]},
          "Peer": {
            "nodekey:a": {
              "TailscaleIPs": ["100.64.0.2", "fd7a:115c:a1e0::2"],
              "AllowedIPs": ["100.64.0.2/32", "fd7a:115c:a1e0::2/128", "192.168.50.0/24", "2001:db8:1::/64", "0.0.0.0/0", "::/0"]
            },
            "nodekey:b": {
              "TailscaleIPs": ["100.64.0.3"],
              "AllowedIPs": ["100.64.0.3/32", "192.168.50.0/24", "10.20.0.0/16", "100.100.10.5/32", "fd7a:115c:a1e0:b1a:0:1::/96"]
            },
            "nodekey:c": {
              "TailscaleIPs": ["100.64.0.4"],
              "AllowedIPs": ["100.64.0.4/32", "172.30.0.0/16"],
              "Expired": true
            },
            "nodekey:d": {
              "TailscaleIPs": ["100.64.0.5"],
              "AllowedIPs": ["100.64.0.5/32", "203.0.113.7/32"],
              "SomethingNew": {"x": 1}
            }
          }
        }
    """.trimIndent()

    @Test
    fun offeredRoutesFollowTheRouteManagersSplit() {
        val routes = TunRoutes.offeredRoutes(status)!!.map { it.toString() }
        // Own addresses, exit routes, anything inside the tailnet ranges (a VIP,
        // a 4via6 prefix) and the expired peer's route are not subnet routes; a
        // route two routers offer is listed once; IPv4 sorts first.
        assertEquals(
            listOf("10.20.0.0/16", "192.168.50.0/24", "203.0.113.7/32", "2001:db8:1:0:0:0:0:0/64"),
            routes
        )
    }

    @Test
    fun anAnswerThatCannotTellIsNotAnEmptyRouteSet() {
        assertNull(TunRoutes.offeredRoutes("""{"Error": "daemon is not running"}"""))
        assertNull(TunRoutes.offeredRoutes(status.replace("\"Running\"", "\"Starting\"")))
        assertNull(TunRoutes.offeredRoutes("not json"))
        assertEquals(emptyList<TunRoutes.Cidr>(), TunRoutes.offeredRoutes("""{"BackendState": "Running"}"""))
    }

    @Test
    fun routesTheDefaultRouteCoversAreLeftOutUnlessExcluded() {
        val offered = TunRoutes.offeredRoutes(status)!!
        val none = emptyList<TunRoutes.Cidr>()
        // Split tunnel: every subnet route has to be on the device.
        assertEquals(offered, TunRoutes.forDevice(offered, defaultRouteV4 = false, defaultRouteV6 = false, appliedExclusions = none))
        // Exit node with "Route all IPv6": both default routes carry everything.
        assertEquals(none, TunRoutes.forDevice(offered, defaultRouteV4 = true, defaultRouteV6 = true, appliedExclusions = none))
        // Exit node without it: IPv6 has no default route, so the v6 subnet stays listed.
        assertEquals(
            listOf("2001:db8:1:0:0:0:0:0/64"),
            TunRoutes.forDevice(offered, defaultRouteV4 = true, defaultRouteV6 = false, appliedExclusions = none).map { it.toString() }
        )
        // The default "Excluded IP Ranges": the private subnets punch back through.
        val lanRanges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12").map { TunRoutes.parse(it)!! }
        assertEquals(
            listOf("10.20.0.0/16", "192.168.50.0/24"),
            TunRoutes.forDevice(offered, defaultRouteV4 = true, defaultRouteV6 = true, appliedExclusions = lanRanges).map { it.toString() }
        )
        // A route wider than the exclusion still overlaps it.
        val narrow = listOf(TunRoutes.parse("10.20.30.0/24")!!)
        assertEquals(listOf("10.20.0.0/16"), TunRoutes.forDevice(offered, true, true, narrow).map { it.toString() })
    }

    @Test
    fun routeSetsSurviveTheirCacheForm() {
        val offered = TunRoutes.offeredRoutes(status)!!
        val text = TunRoutes.encode(offered)
        assertEquals(offered, TunRoutes.decode(text))
        assertEquals(emptyList<TunRoutes.Cidr>(), TunRoutes.decode(""))
        assertEquals(emptyList<TunRoutes.Cidr>(), TunRoutes.decode(null))
        assertEquals("", TunRoutes.encode(emptyList()))
    }

    @Test
    fun daemonPrefsNeedBothFields() {
        assertEquals(true to false, TunRoutes.daemonPrefs("""{"RouteAll": true, "CorpDNS": false, "ExitNodeID": ""}"""))
        assertEquals(false to true, TunRoutes.daemonPrefs("""{"RouteAll": false, "CorpDNS": true}"""))
        assertNull(TunRoutes.daemonPrefs("""{"Error": "nope"}"""))
        assertNull(TunRoutes.daemonPrefs("""{"RouteAll": true}"""))
        assertNull(TunRoutes.daemonPrefs(""))
    }
}
