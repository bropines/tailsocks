package io.github.bropines.tailscaled.admin.devices

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQueryTest {

    /** 2026-10-09 12:00 UTC. */
    private val now = 1_791_547_200_000L
    private val day = DeviceQueries.DAY_MS

    private fun dev(
        id: String,
        user: String? = "alex@example.com",
        online: Boolean = true,
        lastSeen: String? = null,
        created: String? = "2026-01-01T00:00:00Z",
        tags: List<String> = emptyList(),
        authorized: Boolean? = true,
        expires: String? = "2027-01-01T00:00:00Z",
        expiryOff: Boolean? = false,
        update: Boolean? = false,
        external: Boolean? = false,
        v4: String = "100.64.0.1",
        host: String = id,
    ) = ApiDevice(
        id = id, nodeId = "n$id", name = "$id.tail1.ts.net", hostname = host, addresses = listOf(v4, "fd7a::1"),
        user = user, connectedToControl = online, lastSeen = lastSeen, created = created, tags = tags,
        authorized = authorized, expires = expires, keyExpiryDisabled = expiryOff, updateAvailable = update, isExternal = external,
    )

    private val laptop = dev("laptop", lastSeen = null)
    private val nas = dev("nas", tags = listOf("tag:server"), v4 = "100.72.5.101", host = "Homelab-NAS")
    private val pending = dev("newbox", user = "jordan@example.com", authorized = false, online = false, lastSeen = null, created = "2026-10-09T09:00:00Z")
    private val stale = dev("oldvm", online = false, lastSeen = "2026-07-30T10:00:00Z", expires = "2026-10-12T00:00:00Z")
    private val shared = dev("family", user = "robin@example.net", external = true, expires = null)
    private val old = dev("ipad", online = false, lastSeen = "2026-10-06T12:00:00Z", update = true, expires = "2026-10-01T00:00:00Z")
    private val all = listOf(laptop, nas, pending, stale, shared, old)

    private val routes = mapOf(
        "nas" to DeviceRoutes(advertisedRoutes = listOf("192.168.1.0/24"), enabledRoutes = listOf("192.168.1.0/24")),
        "laptop" to DeviceRoutes(advertisedRoutes = listOf("0.0.0.0/0", "::/0"), enabledRoutes = emptyList()),
        "oldvm" to DeviceRoutes(),
    )

    private fun run(q: DeviceQuery) = DeviceQueries.apply(all, q, now) { routes[it.id] }.map { it.id }

    @Test
    fun searchMatchesNameHostnameAddressTagAndUser() {
        assertEquals(listOf("nas"), run(DeviceQuery(text = "homelab")))
        assertEquals(listOf("nas"), run(DeviceQuery(text = "100.72.5")))
        assertEquals("with or without the tag: prefix", listOf("nas"), run(DeviceQuery(text = "server")))
        assertEquals(listOf("nas"), run(DeviceQuery(text = "tag:server")))
        assertEquals(listOf("newbox"), run(DeviceQuery(text = "JORDAN")))
        assertEquals("every word must match", listOf("family"), run(DeviceQuery(text = "robin family")))
        assertEquals(emptyList<String>(), run(DeviceQuery(text = "robin laptop")))
        assertEquals(all.size, run(DeviceQuery(text = "   ")).size)
    }

    @Test
    fun ownerIsAnExactLogin() {
        assertEquals(listOf("family"), run(DeviceQuery(owner = "robin@example.net")))
        assertEquals(listOf("ipad", "laptop", "nas", "oldvm"), run(DeviceQuery(owner = "ALEX@example.com")))
        assertEquals(emptyList<String>(), run(DeviceQuery(owner = "robin")))
    }

    @Test
    fun eachChipNarrowsAndTheyCombine() {
        fun f(vararg chips: DeviceFilter, days: Int = 30, tag: String? = null) = run(DeviceQuery(filters = chips.toSet(), offlineDays = days, tag = tag))
        assertEquals(listOf("newbox"), f(DeviceFilter.NEEDS_APPROVAL))
        assertEquals("expiring within 7 days, not already expired", listOf("oldvm"), f(DeviceFilter.EXPIRING))
        assertEquals(listOf("oldvm"), f(DeviceFilter.OFFLINE, days = 30))
        assertEquals("never seen counts from when it was added", listOf("ipad", "oldvm"), f(DeviceFilter.OFFLINE, days = 1))
        assertEquals("routes unknown are not routers; an empty route set is not one", listOf("laptop", "nas"), f(DeviceFilter.ROUTERS))
        assertEquals(listOf("ipad"), f(DeviceFilter.UPDATE))
        assertEquals(listOf("nas"), f(DeviceFilter.TAGGED))
        assertEquals(listOf("nas"), f(DeviceFilter.TAGGED, tag = "tag:server"))
        assertEquals(emptyList<String>(), f(DeviceFilter.TAGGED, tag = "tag:web"))
        assertEquals(listOf("family"), f(DeviceFilter.SHARED))
        assertEquals(listOf("oldvm"), f(DeviceFilter.EXPIRING, DeviceFilter.OFFLINE))
        assertEquals(emptyList<String>(), f(DeviceFilter.SHARED, DeviceFilter.NEEDS_APPROVAL))
    }

    @Test
    fun sortsByNameLastSeenOnlineFirstAndNewestFirst() {
        assertEquals(listOf("family", "ipad", "laptop", "nas", "newbox", "oldvm"), run(DeviceQuery(sort = DeviceSort.NAME)))
        assertEquals(
            "online ones first, then the most recently seen; never seen last",
            listOf("family", "laptop", "nas", "ipad", "oldvm", "newbox"),
            run(DeviceQuery(sort = DeviceSort.LAST_SEEN)),
        )
        assertEquals(listOf("newbox", "family", "ipad", "laptop", "nas", "oldvm"), run(DeviceQuery(sort = DeviceSort.CREATED)))
    }

    @Test
    fun onlyTopLevelExactFieldsGoToTheServer() {
        assertEquals(emptyList<Pair<String, String>>(), DeviceQueries.serverFilters(DeviceQuery(text = "nas", filters = setOf(DeviceFilter.EXPIRING, DeviceFilter.OFFLINE, DeviceFilter.ROUTERS))))
        val q = DeviceQuery(
            owner = "alex@example.com",
            filters = setOf(DeviceFilter.NEEDS_APPROVAL, DeviceFilter.UPDATE, DeviceFilter.SHARED, DeviceFilter.TAGGED),
            tag = "tag:server",
        )
        assertEquals(
            listOf("user" to "alex@example.com", "authorized" to "false", "updateAvailable" to "true", "isExternal" to "true", "tags" to "tag:server"),
            DeviceQueries.serverFilters(q),
        )
        assertTrue("any tag is decided on the phone", DeviceQueries.serverFilters(DeviceQuery(filters = setOf(DeviceFilter.TAGGED))).isEmpty())
        assertTrue(DeviceQueries.serverFilters(q).none { it.first == "fields" })
    }

    @Test
    fun turningTaggedOffForgetsTheTag() {
        val q = DeviceQuery(filters = setOf(DeviceFilter.TAGGED), tag = "tag:server").with(DeviceFilter.TAGGED, false)
        assertNull(q.tag)
        assertFalse(q.narrowed)
        assertEquals(DeviceSort.CREATED, DeviceQuery(text = "x", sort = DeviceSort.CREATED, owner = "a").cleared().sort)
        assertFalse(DeviceQuery(text = "x", owner = "a").cleared().narrowed)
    }

    @Test
    fun keyExpiryStates() {
        assertEquals(KeyExpiry.Disabled, DeviceQueries.keyExpiry(dev("a", expiryOff = true, expires = "2026-10-10T00:00:00Z"), now))
        assertEquals(KeyExpiry.Unknown, DeviceQueries.keyExpiry(dev("a", expires = "0001-01-01T00:00:00Z"), now))
        assertTrue(DeviceQueries.keyExpiry(dev("a", expires = "2026-10-08T00:00:00Z"), now) is KeyExpiry.Expired)
        assertEquals(2, (DeviceQueries.keyExpiry(dev("a", expires = "2026-10-12T00:00:00Z"), now) as KeyExpiry.Expiring).days)
        assertEquals(0, (DeviceQueries.keyExpiry(dev("a", expires = "2026-10-09T20:00:00Z"), now) as KeyExpiry.Expiring).days)
        assertTrue(DeviceQueries.keyExpiry(dev("a", expires = "2026-10-17T00:00:00Z"), now) is KeyExpiry.Valid)
    }

    @Test
    fun badgesSayWhatNeedsAttention() {
        fun kinds(d: ApiDevice, r: DeviceRoutes? = null, self: String? = null) = DeviceQueries.badges(d, now, r, self).map { it.kind }
        assertEquals(listOf(BadgeKind.THIS_PHONE), kinds(laptop, self = "nlaptop"))
        assertEquals(listOf(BadgeKind.NEEDS_APPROVAL), kinds(pending))
        assertEquals(listOf(BadgeKind.KEY_EXPIRED, BadgeKind.UPDATE), kinds(old))
        assertEquals(listOf(BadgeKind.SHARED), kinds(shared))
        assertEquals(listOf(BadgeKind.ROUTES_PENDING), kinds(laptop, routes["laptop"]))
        assertEquals(listOf(BadgeKind.SUBNET_ROUTER), kinds(nas, routes["nas"]))
        assertEquals(
            listOf(BadgeKind.EXIT_NODE, BadgeKind.SUBNET_ROUTER),
            kinds(nas, DeviceRoutes(listOf("0.0.0.0/0", "::/0", "10.0.0.0/8"), listOf("0.0.0.0/0", "::/0", "10.0.0.0/8"))),
        )
        val troubled = laptop.copy(tailnetLockError = "not signed", multipleConnections = true)
        assertEquals(listOf(BadgeKind.LOCK_ERROR, BadgeKind.MULTIPLE_CONNECTIONS), kinds(troubled))
        assertEquals(DeviceBadge(BadgeKind.KEY_EXPIRING, 2), DeviceQueries.badges(stale, now, null, null).single())
    }

    @Test
    fun routesFromAFullReadCountWhenNoneWereLoaded() {
        val full = nas.copy(advertisedRoutes = listOf("10.1.0.0/16"), enabledRoutes = emptyList())
        assertEquals(DeviceRoutes(listOf("10.1.0.0/16"), emptyList()), DeviceQueries.knownRoutes(full, null))
        assertNull(DeviceQueries.knownRoutes(nas, null))
        val loaded = DeviceRoutes(listOf("10.2.0.0/16"), listOf("10.2.0.0/16"))
        assertEquals(loaded, DeviceQueries.knownRoutes(full, loaded))
    }

    @Test
    fun timestampsWithFractionsAndOffsets() {
        assertEquals(now, DeviceQueries.instant("2026-10-09T12:00:00Z"))
        assertEquals(now + 250, DeviceQueries.instant("2026-10-09T12:00:00.25Z"))
        assertEquals(now, DeviceQueries.instant("2026-10-09T15:00:00+03:00"))
        assertNull(DeviceQueries.instant("0001-01-01T00:00:00Z"))
        assertNull(DeviceQueries.instant("yesterday"))
        assertNull(DeviceQueries.instant(null))
        assertEquals(3 * day, DeviceQueries.offlineFor(dev("x", online = false, lastSeen = "2026-10-06T12:00:00Z"), now))
        assertNull(DeviceQueries.offlineFor(laptop, now))
    }

    @Test
    fun versionsAndSystemsAsPeopleWriteThem() {
        assertEquals("1.104.0", DeviceQueries.shortVersion("v1.104.0"))
        assertEquals("1.96.2", DeviceQueries.shortVersion("1.96.2-t1a2b3c4d-g5e6f7a8b9"))
        assertNull(DeviceQueries.shortVersion(""))
        assertEquals("macOS", DeviceQueries.osName("macOS"))
        assertEquals("iOS", DeviceQueries.osName("ios"))
        assertEquals("plan9", DeviceQueries.osName("plan9"))
        assertNull(DeviceQueries.osName(null))
    }
}

class Ipv4RulesTest {

    private val taken = mapOf("100.72.5.101" to "homelab-nas", "100.100.100.1" to "svc:web")

    private fun problem(ip: String, current: String? = "100.64.0.9") = Ipv4Rules.check(ip, current, taken).problem

    @Test
    fun onlyDottedQuadsInsideTheTailnetRange() {
        assertNull(problem("100.80.0.1"))
        assertNull(problem(" 100.127.255.254 "))
        assertNull(problem("100.64.0.1"))
        assertEquals(Ipv4Problem.MALFORMED, problem("100.80.0"))
        assertEquals(Ipv4Problem.MALFORMED, problem("100.80.0.256"))
        assertEquals("no octal-looking octets", Ipv4Problem.MALFORMED, problem("100.080.0.1"))
        assertEquals(Ipv4Problem.MALFORMED, problem("100.80.0.1/32"))
        assertEquals(Ipv4Problem.MALFORMED, problem("fd7a::1"))
        assertEquals(Ipv4Problem.OUTSIDE_RANGE, problem("100.63.255.255"))
        assertEquals(Ipv4Problem.OUTSIDE_RANGE, problem("100.128.0.1"))
        assertEquals(Ipv4Problem.OUTSIDE_RANGE, problem("192.168.1.10"))
    }

    @Test
    fun addressesTailscaleKeepsAndOnesInUse() {
        assertEquals(Ipv4Problem.RESERVED, problem("100.100.100.100"))
        assertEquals("the ChromeOS VM range", Ipv4Problem.RESERVED, problem("100.115.92.7"))
        assertEquals(Ipv4Problem.RESERVED, problem("100.115.93.255"))
        assertNull(problem("100.115.94.1"))
        assertEquals(Ipv4Problem.RESERVED, problem("100.64.0.0"))
        assertEquals(Ipv4Problem.RESERVED, problem("100.127.255.255"))
        val inUse = Ipv4Rules.check("100.72.5.101", "100.64.0.9", taken)
        assertEquals(Ipv4Problem.TAKEN, inUse.problem)
        assertEquals("homelab-nas", inUse.takenBy)
        assertEquals("svc:web", Ipv4Rules.check("100.100.100.1", null, taken).takenBy)
        assertEquals(Ipv4Problem.UNCHANGED, problem("100.64.0.9"))
    }

    @Test
    fun holdersLeaveOutTheDeviceItself() {
        val a = ApiDevice(nodeId = "a", name = "a.t.ts.net", addresses = listOf("100.64.0.1", "fd7a::1"))
        val b = ApiDevice(nodeId = "b", name = "b.t.ts.net", addresses = listOf("100.64.0.2", "fd7a::2"))
        assertEquals(mapOf("100.64.0.2" to "b"), Ipv4Rules.holders(listOf(a, b), a))
    }
}

class DeviceNamesTest {
    @Test
    fun labelsAndHostnames() {
        assertTrue(DeviceNames.isValid("pixel-9-pro"))
        assertTrue(DeviceNames.isValid("a"))
        assertFalse(DeviceNames.isValid("-edge"))
        assertFalse(DeviceNames.isValid("edge-"))
        assertFalse(DeviceNames.isValid("dotted.name"))
        assertFalse(DeviceNames.isValid("a".repeat(64)))
        assertEquals("alexs-macbook-pro", DeviceNames.fromHostname("Alex's MacBook Pro"))
        assertEquals("desktop-home", DeviceNames.fromHostname("DESKTOP-HOME.local"))
        assertNull(DeviceNames.fromHostname("..."))
    }

    @Test
    fun renameMayResetOrTakeANewValidName() {
        val d = ApiDevice(nodeId = "n", name = "pixel.t.ts.net", hostname = "Pixel")
        assertTrue("blank resets to the hostname", DeviceNames.renameAllowed(d, ""))
        assertTrue(DeviceNames.renameAllowed(d, "phone"))
        assertFalse("the same name is no change", DeviceNames.renameAllowed(d, "PIXEL"))
        assertFalse(DeviceNames.renameAllowed(d, "my phone"))
    }
}
