package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TailnetMapSchemesTest {
    private val m = MapMetrics()
    private val t = TreeMetrics()

    private fun peer(
        name: String,
        path: PeerPath = PeerPath.Direct("192.168.1.2:41641"),
        rate: TrafficRate = TrafficRate.ZERO,
        data: PeerData = PeerData(id = name, hostName = name),
    ) = MapPeer(name, data, path, rate)

    private fun derp(name: String, region: String, rate: TrafficRate = TrafficRate.ZERO) = peer(name, PeerPath.Derp(region, null), rate)
    private fun relayed(name: String, node: String, rate: TrafficRate = TrafficRate.ZERO) =
        peer(name, PeerPath.PeerRelay("203.0.113.9:7777", node), rate)
    private fun idle(name: String) = peer(name, PeerPath.NotYet("fra", null))
    private fun offline(name: String) = peer(name, PeerPath.Offline(null))

    /** The made-up tailnet of the previews: four direct, one behind DERP, one behind a peer relay, one idle, three offline. */
    private val home = listOf(
        peer("desktop", rate = TrafficRate(2_400_000, 180_000)), peer("nas"), peer("exit-fra"), peer("pixel"),
        derp("macbook", "ams", TrafficRate(40_000, 8_000)), relayed("pi", "nas", TrafficRate(1_000, 2_000)),
        idle("exit-hel"), offline("ipad"), offline("deck"), offline("laptop"),
    )

    /** Three hundred: mostly exit nodes nobody uses, a few offline, and the home tailnet's paths. */
    private val large = (1..280).map { idle("mullvad-%03d".format(it)) } + (1..12).map { offline("old-$it") } +
        home.filter { it.tier == MapTier.LINKED } + listOf(derp("vps", "fra"), derp("office", "fra", TrafficRate(900, 300)))

    /** A phone held upright, a tablet on its side, a television inside its safe area, a split window. */
    private val windows = listOf(411f to 640f, 1280f to 640f, 864f to 400f, 320f to 300f)

    private fun MapScene.spots(): List<Pair<Float, Float>> = peers.map { it.x to it.y } + hubs.map { it.x to it.y }

    private fun assertApart(scene: MapScene, least: Float, where: String) {
        val spots = scene.spots()
        for (i in spots.indices) for (j in i + 1 until spots.size) {
            val d = hypot(spots[i].first - spots[j].first, spots[i].second - spots[j].second)
            assertTrue("$where: $d between ${spots[i]} and ${spots[j]}", d >= least)
        }
    }

    private fun assertWhole(scene: MapScene, all: List<MapPeer>) {
        val shown = scene.peers.map { it.peer.key }
        assertEquals("nobody is drawn twice", shown.size, shown.toSet().size)
        assertEquals(all.map { it.key }.toSet(), shown.toSet() + scene.hidden.map { it.key })
        assertEquals(all.size, shown.size + scene.hidden.size)
    }

    // ---- Rings

    @Test
    fun ringsAsASceneAreThePlan() {
        for ((w, h) in windows) {
            val plan = planTailnetMap(large, w, h, m, moreRoom = 52f)
            val scene = ringsScene(large, w, h, m, moreRoom = 52f)
            assertEquals(plan.nodes.map { Triple(it.peer.key, it.spot.x, it.spot.y) }, scene.peers.map { Triple(it.peer.key, it.x, it.y) })
            assertEquals(plan.hidden.map { it.key }, scene.hidden.map { it.key })
            assertTrue(scene.hubs.isEmpty())
            assertTrue(scene.links.all { it.from == MAP_SELF_KEY && !it.square })
            assertEquals(w, scene.width, 0f)
            assertEquals(h, scene.height, 0f)
        }
    }

    // ---- Routes

    @Test
    fun routesPutAHubBetweenThisDeviceAndWhatARelayCarries() {
        val scene = routesScene(home, 411f, 640f, m)
        assertTrue(scene.hidden.isEmpty())
        assertEquals(listOf(MapGroupKind.PEER_RELAY to "nas", MapGroupKind.DERP to "ams"), scene.hubs.map { it.group.kind to it.group.name })
        val parent = scene.peers.associate { it.peer.name to it.parent }
        val (relay, ams) = scene.hubs.map { it.group.key }
        assertEquals(relay, parent["pi"])
        assertEquals(ams, parent["macbook"])
        for (name in listOf("desktop", "nas", "exit-fra", "pixel", "exit-hel", "ipad")) assertEquals(MAP_SELF_KEY, parent[name])

        // This device → hub carries everything behind the hub; hub → peer carries that peer's own.
        val toAms = scene.links.single { it.from == MAP_SELF_KEY && it.to == ams }
        assertEquals(TrafficRate(40_000, 8_000), toAms.rate)
        assertEquals(MapLinkKind.DERP, toAms.kind)
        val fromAms = scene.links.single { it.from == ams }
        assertEquals("macbook", fromAms.to)
        assertTrue(fromAms.linked)
        assertEquals(MapLinkKind.PEER_RELAY, scene.links.single { it.from == relay }.kind)
        // Direct, idle and offline peers hang off this device, each by its own kind of line.
        assertEquals(MapLinkKind.DIRECT, scene.links.single { it.to == "desktop" }.kind)
        assertEquals(MapLinkKind.IDLE, scene.links.single { it.to == "exit-hel" }.kind)
        assertFalse(scene.links.single { it.to == "ipad" }.linked)
        assertEquals(home.size + 2, scene.links.size)
    }

    @Test
    fun aHubStandsInsideItsPeersAndTheDirectOnesAreCentredOnTheTop() {
        val scene = routesScene(home, 411f, 640f, m)
        val direct = scene.peers.filter { it.peer.path is PeerPath.Direct }
        assertEquals(scene.selfX, direct.map { it.x }.average().toFloat(), 1f)
        assertTrue(direct.all { it.y < scene.selfY })
        for (hub in scene.hubs) {
            val behind = scene.peers.filter { it.parent == hub.group.key }
            val centre = hypot(hub.x - scene.selfX, hub.y - scene.selfY)
            // Nearer to this device than any of its peers, and on their side of it.
            for (p in behind) {
                assertTrue(hypot(p.x - scene.selfX, p.y - scene.selfY) > centre)
                assertTrue((p.x - scene.selfX) * (hub.x - scene.selfX) + (p.y - scene.selfY) * (hub.y - scene.selfY) > 0)
            }
        }
    }

    @Test
    fun routesKeepTheirDistanceAndStayInTheWindow() {
        val manyHubs = (1..14).map { derp("via-$it", "r%02d".format(it)) } + home
        for (tailnet in listOf(home, large, manyHubs, listOf(derp("only", "fra")), listOf(peer("only")))) {
            for ((w, h) in windows) {
                val where = "${tailnet.size} peers in $w×$h"
                val scene = routesScene(tailnet, w, h, m, moreRoom = 52f)
                assertWhole(scene, tailnet)
                val bottom = h - m.marginBottom - (if (scene.hidden.isEmpty()) 0f else 52f)
                for ((x, y) in scene.spots()) {
                    assertTrue("$where: $x", x >= m.marginX - 0.5f && x <= w - m.marginX + 0.5f)
                    assertTrue("$where: $y", y >= m.marginTop - 0.5f && y <= bottom + 0.5f)
                }
                // A full ring is the tightest case, as in the rings: a slot along the curve.
                assertApart(scene, m.slot * 0.8f, where)
                // A hub is never on this device.
                for (hub in scene.hubs) assertTrue(where, hypot(hub.x - scene.selfX, hub.y - scene.selfY) >= 40f)
                for (hub in scene.hubs) assertTrue(where, scene.peers.any { it.parent == hub.group.key })
                assertTrue(where, scene.peers.all { p -> p.parent == MAP_SELF_KEY || scene.hubs.any { it.group.key == p.parent } })
            }
        }
    }

    @Test
    fun threeHundredPeersKeepTheLinkedAndTheirHubs() {
        for ((w, h) in windows) {
            val scene = routesScene(large, w, h, m, moreRoom = 52f)
            assertTrue("$w×$h", scene.hidden.isNotEmpty())
            val shown = scene.peers.map { it.peer.name }.toSet()
            assertTrue("$w×$h $shown", shown.containsAll(listOf("desktop", "macbook", "pi", "office")))
            assertTrue(scene.peers.none { it.peer.tier == MapTier.OFFLINE })
            // A hub says what goes through it, drawn or not: fra carries two peers' traffic.
            val fra = scene.hubs.single { it.group.name == "fra" }
            assertEquals(2, fra.group.members.size)
            assertEquals(TrafficRate(900, 300), fra.group.rate)
        }
    }

    @Test
    fun moreRelaysThanTheInnerRingHoldsLeaveTheQuietOnesOut() {
        val busy = derp("busy", "zzz", TrafficRate(5_000, 5_000))
        val tailnet = (1..30).map { derp("via-$it", "r%02d".format(it)) } + busy
        val scene = routesScene(tailnet, 411f, 640f, m, moreRoom = 52f)
        assertTrue(scene.hubs.size in 2..12)
        assertTrue(scene.hubs.any { it.group.name == "zzz" })
        assertEquals(tailnet.size - scene.peers.size, scene.hidden.size)
        assertEquals(scene.hubs.size, scene.peers.size)
    }

    @Test
    fun aWindowWithOneRingHasItsHubsOnThatRingAmongTheirPeers() {
        // A phone on its side: the map's box is wide and low.
        val scene = routesScene(home + derp("vps", "ams"), 860f, 280f, m)
        assertEquals(1, mapRings(860f, 280f, m).size)
        assertTrue(scene.hidden.isEmpty())
        assertEquals(2, scene.hubs.size)
        val ring = mapRings(860f, 280f, m).single()
        for (hub in scene.hubs) {
            val dx = (hub.x - scene.selfX) / ring.rx
            val dy = (hub.y - scene.selfY) / ring.ry
            assertEquals(1f, dx * dx + dy * dy, 0.02f)
        }
        // The ams hub stands between its two peers.
        val ams = scene.hubs.single { it.group.name == "ams" }
        val behind = scene.peers.filter { it.parent == ams.group.key }
        assertEquals(2, behind.size)
        assertEquals(behind.map { it.peer.key }.toSet(), scene.peers.sortedBy { hypot(it.x - ams.x, it.y - ams.y) }.take(2).map { it.peer.key }.toSet())
        // And a window of one ring that cannot hold everybody leaves out whole places, hubs counted.
        val tight = routesScene(large, 320f, 300f, m, moreRoom = 52f)
        val room = mapRings(320f, 300f, m.copy(marginBottom = m.marginBottom + 52f)).single().capacity
        assertTrue(tight.peers.size + tight.hubs.size <= room)
        assertTrue(tight.hubs.isNotEmpty())
    }

    @Test
    fun aRateMovesNobodyOnTheRoutes() {
        val calm = home.map { peer(it.name, it.path) }
        val a = routesScene(calm, 411f, 640f, m)
        val b = routesScene(home, 411f, 640f, m)
        assertEquals(a.peers.map { Triple(it.peer.name, it.x, it.y) }, b.peers.map { Triple(it.peer.name, it.x, it.y) })
        assertEquals(a.hubs.map { Triple(it.group.key, it.x, it.y) }, b.hubs.map { Triple(it.group.key, it.x, it.y) })
    }

    @Test
    fun routesOfNobodyAreThisDeviceAlone() {
        val scene = routesScene(emptyList(), 411f, 640f, m)
        assertTrue(scene.peers.isEmpty() && scene.hubs.isEmpty() && scene.links.isEmpty() && scene.hidden.isEmpty())
        assertEquals(205.5f, scene.selfX, 0.01f)
    }

    @Test
    fun placesOnARingArePushedApartAndNoFurther() {
        val at = floatArrayOf(0.10f, 0.11f, 0.12f, 0.60f)
        spreadOnRing(at, 0.1f)
        for (i in 0 until at.size - 1) assertTrue(at[i + 1] - at[i] >= 0.1f - 1e-3f)
        assertEquals(0.11f, at[1], 1e-3f)
        assertEquals(0.60f, at[3], 1e-3f)
        // Round the end of the ring as well.
        val ends = floatArrayOf(0.02f, 0.98f)
        spreadOnRing(ends, 0.2f)
        assertEquals(0.2f, ends[0] + 1f - ends[1], 1e-3f)
        val alone = floatArrayOf(0.3f)
        spreadOnRing(alone, 0.5f)
        assertEquals(0.3f, alone[0], 0f)
    }

    @Test
    fun pointsRoundAnEllipseAreTakenByAnyFraction() {
        val pts = ellipsePointsAt(100f, 100f, floatArrayOf(0f, 0.25f, -0.25f, 1.5f))
        val expected = listOf(0f to -100f, 100f to 0f, -100f to 0f, 0f to 100f)
        for (i in expected.indices) {
            assertEquals(expected[i].first, pts[i].first, 0.5f)
            assertEquals(expected[i].second, pts[i].second, 0.5f)
        }
        assertEquals(ellipsePoints(300f, 120f, 12)[5].first, ellipsePointsAt(300f, 120f, floatArrayOf(5f / 12))[0].first, 0.5f)
    }

    // ---- Tree

    @Test
    fun theTreeIsThisDeviceThenGroupsThenTheirDevices() {
        val scene = treeScene(groupPeers(home, TreeGrouping.ROUTE), 411f, 640f, t)
        assertEquals(
            listOf(MapGroupKind.DIRECT, MapGroupKind.PEER_RELAY, MapGroupKind.DERP, MapGroupKind.IDLE, MapGroupKind.OFFLINE),
            scene.hubs.map { it.group.kind }
        )
        assertTrue(scene.hidden.isEmpty())
        // The groups stand under this device, in one column, top to bottom.
        assertTrue(scene.hubs.all { it.x == scene.selfX })
        assertTrue(scene.hubs.first().y > scene.selfY)
        scene.hubs.zipWithNext { a, b -> assertTrue(b.y - a.y >= t.row) }
        // A group's devices start on its own row, to its right.
        for (hub in scene.hubs) {
            val row = scene.peers.filter { it.parent == hub.group.key }
            assertEquals(hub.group.members.map { it.key }, row.map { it.peer.key })
            assertEquals(hub.y, row.first().y, 0f)
            assertEquals(hub.x + t.column, row.first().x, 0.01f)
        }
        // Every node has one link into it, all of them square.
        assertEquals(scene.hubs.size + scene.peers.size, scene.links.size)
        assertTrue(scene.links.all { it.square })
        assertEquals(scene.hubs.map { it.group.key }, scene.links.filter { it.from == MAP_SELF_KEY }.map { it.to })
    }

    @Test
    fun theTreeIsNeverWiderThanTheWindowAndNothingOverlaps() {
        for (tailnet in listOf(listOf(peer("only")), home, large)) for (by in TreeGrouping.entries) for ((w, h) in windows) {
            val where = "${tailnet.size} peers by $by in $w×$h"
            val scene = treeScene(groupPeers(tailnet, by), w, h, t)
            assertWhole(scene, tailnet)
            assertEquals(where, w, scene.width, 0f)
            assertTrue(where, scene.height >= h)
            val half = m.slot / 2
            for ((x, y) in scene.spots() + (scene.selfX to scene.selfY)) {
                assertTrue("$where: $x", x - half >= 0f && x + half <= scene.width)
                assertTrue("$where: $y", y - 30f >= 0f && y + 60f <= scene.height)
            }
            // A grid: any two nodes are a column apart or a row apart.
            val spots = scene.spots() + (scene.selfX to scene.selfY)
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                val dx = abs(spots[i].first - spots[j].first)
                val dy = abs(spots[i].second - spots[j].second)
                assertTrue("$where: ${spots[i]} and ${spots[j]}", dx >= m.slot - 0.01f || dy >= t.row - 0.01f)
            }
            // The trunk and every stem are inside the box too.
            assertTrue(where, scene.selfX - t.stem >= 0f)
        }
    }

    @Test
    fun aSmallTreeStandsInTheMiddleAndALargeOneGrowsDownwards() {
        val small = treeScene(groupPeers(home, TreeGrouping.ROUTE), 1280f, 800f, t)
        assertEquals(1280f, small.width, 0f)
        assertEquals(800f, small.height, 0f)
        val xs = small.spots().map { it.first } + small.selfX
        assertEquals(1280f / 2, (xs.min() - t.stem - t.marginX + xs.max() + t.column / 2 + t.marginX) / 2, 0.5f)

        val tall = treeScene(groupPeers(large, TreeGrouping.ROUTE), 411f, 640f, t)
        assertTrue(tall.height > 640f * 3)
        assertEquals(t.maxPeers, tall.peers.size)
        assertEquals(large.size - t.maxPeers, tall.hidden.size)
        // The linked and the busy are among the drawn, as in every scheme.
        assertTrue(tall.peers.map { it.peer.name }.containsAll(listOf("desktop", "macbook", "pi", "office", "vps")))
        // A phone has room for three devices beside the group's own column; a tablet for many.
        assertEquals(3, tall.peers.map { it.x }.distinct().size)
        assertTrue(treeScene(groupPeers(large, TreeGrouping.ROUTE), 1280f, 640f, t).peers.map { it.x }.distinct().size >= 12)
    }

    @Test
    fun aBranchIsItsGroupsLineByRouteAndPlainOtherwiseUnlessItCarriesTraffic() {
        val byRoute = treeScene(groupPeers(home, TreeGrouping.ROUTE), 411f, 640f, t, byRoute = true)
        assertEquals(MapLinkKind.DIRECT, byRoute.links.single { it.to == "nas" }.kind)
        assertEquals(MapLinkKind.OFFLINE, byRoute.links.single { it.to == "ipad" }.kind)
        val byOs = treeScene(groupPeers(home, TreeGrouping.OS), 411f, 640f, t, byRoute = false)
        assertEquals(MapLinkKind.GROUP, byOs.links.single { it.to == "nas" }.kind)
        assertEquals(MapLinkKind.DERP, byOs.links.single { it.to == "macbook" }.kind)
        assertTrue(byOs.links.filter { it.from == MAP_SELF_KEY }.all { it.kind == MapLinkKind.GROUP })
    }

    @Test
    fun aSquareLinkTurnsAtItsStem() {
        val straight = SceneLink("a", "b", MapLinkKind.DIRECT, TrafficRate.ZERO, linked = true)
        assertEquals(listOf(1f, 2f, 30f, 40f), linkPoints(straight, 1f, 2f, 30f, 40f).toList())
        val branch = SceneLink("a", "b", MapLinkKind.GROUP, TrafficRate.ZERO, linked = false, drop = 0f, inset = 46f)
        assertEquals(listOf(10f, 20f, 10f, 20f, 254f, 20f, 254f, 106f, 300f, 106f), linkPoints(branch, 10f, 20f, 300f, 106f).toList())
    }

    // ---- Groups

    private fun device(id: String, os: String, user: Long = 1, tags: List<String> = emptyList()) =
        peer(id, data = PeerData(id = id, hostName = id, os = os, userID = user, tags = tags))

    @Test
    fun groupsByRouteStandInTheOrderTheyAreDrawn() {
        val groups = groupPeers(home + derp("vps", "fra"), TreeGrouping.ROUTE)
        assertEquals(listOf(null, "nas", "ams", "fra", null, null), groups.map { it.name })
        assertEquals(listOf(4, 1, 1, 1, 1, 3), groups.map { it.members.size })
        assertEquals(listOf("desktop", "exit-fra", "nas", "pixel"), groups.first().members.map { it.name })
        assertEquals(TrafficRate(2_400_000, 180_000), groups.first().rate)
        assertEquals(groups.size, groups.map { it.key }.toSet().size)
        assertTrue(groups.all { it.key.startsWith(MAP_HUB_PREFIX) })
        // A relay nobody could name goes by its address.
        assertEquals("203.0.113.9", groupPeers(listOf(peer("x", PeerPath.PeerRelay("203.0.113.9:7777", null))), TreeGrouping.ROUTE).single().name)
    }

    @Test
    fun groupsByOwnerPutThisDevicesUserFirstAndATaggedDeviceUnderItsTag() {
        val tailnet = listOf(
            device("a", "linux", user = 2), device("b", "android", user = 1), device("c", "linux", user = 9),
            device("d", "linux", user = 1, tags = listOf("tag:server", "tag:exit")), device("e", "linux", tags = listOf("tag:exit")),
        )
        val groups = groupPeers(tailnet, TreeGrouping.OWNER, users = mapOf(1L to "Zoe", 2L to "Alex"), selfUser = 1)
        assertEquals(listOf("Zoe", "Alex", null, "tag:exit", "tag:server"), groups.map { it.name })
        assertEquals(listOf(MapGroupKind.USER, MapGroupKind.USER, MapGroupKind.USER, MapGroupKind.TAG, MapGroupKind.TAG), groups.map { it.kind })
        assertEquals(listOf("b"), groups[0].members.map { it.key })
        assertEquals(listOf("d"), groups[4].members.map { it.key })
        // Two users of one name are two groups.
        val twins = groupPeers(tailnet.take(2), TreeGrouping.OWNER, users = mapOf(1L to "Sam", 2L to "Sam"))
        assertEquals(2, twins.size)
        assertEquals(2, twins.map { it.key }.toSet().size)
    }

    @Test
    fun groupsByOsAndByState() {
        val tailnet = listOf(
            device("a", "linux"), device("b", "android"), device("c", "iOS"), device("d", "macOS"), device("e", "windows"),
            device("f", "freebsd"), device("g", "tvOS"),
            // TailSocks reports linux and is a phone all the same.
            peer("h", data = PeerData(id = "h", hostName = "h", os = "linux", app = "tailsocks")),
        )
        val byOs = groupPeers(tailnet, TreeGrouping.OS)
        assertEquals(listOf("android", "ios", "windows", "macos", "linux", "tvos", OS_OTHER), byOs.map { it.name })
        assertEquals(listOf("b", "h"), byOs.first().members.map { it.key })

        val byState = groupPeers(home, TreeGrouping.STATE)
        assertEquals(listOf(MapGroupKind.LINKED, MapGroupKind.IDLE, MapGroupKind.OFFLINE), byState.map { it.kind })
        assertEquals(listOf(6, 1, 3), byState.map { it.members.size })
    }

    // ---- Filters

    @Test
    fun anEmptyFilterShowsEverybodyAndCountsNothing() {
        val f = MapFilter()
        assertTrue(f.isEmpty)
        assertEquals(0, f.count)
        assertTrue(home.all(f::matches))
        assertTrue(MapFilter(query = "  ").isEmpty)
    }

    @Test
    fun filtersNarrowAndTheChoicesInsideOneAddUp() {
        fun names(f: MapFilter) = home.filter(f::matches).map { it.name }
        assertEquals(7, names(MapFilter(onlineOnly = true)).size)
        assertEquals(listOf("desktop", "macbook", "pi"), names(MapFilter(withTraffic = true)))
        assertEquals(listOf("macbook"), names(MapFilter(paths = setOf(MapPathFilter.DERP))))
        assertEquals(listOf("macbook", "pi"), names(MapFilter(paths = setOf(MapPathFilter.DERP, MapPathFilter.PEER_RELAY))))
        assertEquals(listOf("exit-hel"), names(MapFilter(paths = setOf(MapPathFilter.NOT_YET))))
        // Two filters at once: both must hold.
        assertEquals(listOf("desktop"), names(MapFilter(withTraffic = true, paths = setOf(MapPathFilter.DIRECT))))
        // An offline peer has no path, so asking for one leaves it out.
        assertTrue(names(MapFilter(paths = MapPathFilter.entries.toSet())).none { it in listOf("ipad", "deck", "laptop") })
        assertEquals(listOf("exit-fra", "exit-hel"), names(MapFilter(query = "EXIT")))
        assertTrue(names(MapFilter(query = "nobody")).isEmpty())
        assertEquals(5, MapFilter(query = "x", onlineOnly = true, paths = setOf(MapPathFilter.DERP, MapPathFilter.DIRECT), exitNodes = true).count)
    }

    @Test
    fun filtersByWhatADeviceIs() {
        val tailnet = listOf(
            peer("phone", data = PeerData(id = "phone", hostName = "phone", os = "android", userID = 1, tailscaleIPs = listOf("100.64.0.1"))),
            peer("vps", data = PeerData(id = "vps", hostName = "vps", os = "linux", userID = 1, tags = listOf("tag:exit"), exitNodeOption = true,
                tailscaleIPs = listOf("100.64.0.2", "fd7a::2"), allowedIPs = listOf("100.64.0.2/32", "fd7a::2/128", "0.0.0.0/0", "::/0"))),
            peer("router", data = PeerData(id = "router", hostName = "router", os = "linux", userID = 2,
                tailscaleIPs = listOf("100.64.0.3"), allowedIPs = listOf("100.64.0.3/32", "192.168.1.0/24"))),
        )
        fun names(f: MapFilter) = tailnet.filter(f::matches).map { it.name }
        assertEquals(listOf("vps", "router"), names(MapFilter(os = setOf("linux"))))
        assertEquals(listOf("phone", "vps", "router"), names(MapFilter(os = setOf("linux", "android"))))
        assertEquals(listOf("vps"), names(MapFilter(exitNodes = true)))
        assertEquals(listOf("router"), names(MapFilter(subnetRouters = true)))
        assertEquals(listOf("vps", "router"), names(MapFilter(exitNodes = true, subnetRouters = true)))
        // A tagged device is its tag's, not its user's.
        assertEquals(listOf("phone"), names(MapFilter(owners = setOf("user:1"))))
        assertEquals(listOf("vps"), names(MapFilter(owners = setOf("tag:exit"))))
        assertEquals(listOf("router"), names(MapFilter(query = "100.64.0.3")))

        val options = mapFilterOptions(tailnet)
        assertEquals(listOf("android", "linux"), options.os)
        assertEquals(listOf("user:1", "user:2", "tag:exit"), options.owners)
        assertTrue(options.exitNodes && options.subnetRouters)
        assertFalse(mapFilterOptions(home).exitNodes)
        assertEquals(1L, ownerUserId("user:1"))
        assertNull(ownerUserId("tag:exit"))
        assertNotNull(options.owners.firstOrNull(::isTagOwner))
    }

    // ---- The view

    @Test
    fun aSceneTheSizeOfTheWindowOpensWholeAndOnlyZoomsIn() {
        val scene = routesScene(home, 411f, 640f, m)
        assertEquals(SceneView(), startView(scene, 411f, 640f))
        assertEquals(SceneView(), fitView(scene, 411f, 640f))
        // A drag alone goes nowhere; a pinch out stops at the whole map.
        assertEquals(SceneView(), zoomView(SceneView(), 1f, 100f, 100f, 60f, -40f, scene, 411f, 640f))
        assertEquals(SceneView(), zoomView(SceneView(), 0.5f, 100f, 100f, 0f, 0f, scene, 411f, 640f))
        val zoomed = zoomView(SceneView(), 2f, 100f, 200f, 0f, 0f, scene, 411f, 640f)
        assertEquals(SceneView(2f, -100f, -200f), zoomed)
        assertEquals(MAP_MAX_SCALE, zoomView(zoomed, 9f, 0f, 0f, 0f, 0f, scene, 411f, 640f).scale, 0f)
        // Zoomed in, it can be dragged — but not off the window.
        assertEquals(SceneView(2f, 0f, 0f), zoomView(zoomed, 1f, 0f, 0f, 5_000f, 5_000f, scene, 411f, 640f))
        assertEquals(SceneView(2f, -411f, -640f), zoomView(zoomed, 1f, 0f, 0f, -5_000f, -5_000f, scene, 411f, 640f))
    }

    @Test
    fun aTreeTallerThanTheWindowOpensAtItsRootAndFitsOnRequest() {
        val scene = treeScene(groupPeers(large, TreeGrouping.ROUTE), 411f, 640f, t)
        assertEquals(SceneView(), startView(scene, 411f, 640f))
        val fit = fitView(scene, 411f, 640f)
        assertEquals(640f / scene.height, fit.scale, 1e-4f)
        assertEquals(0f, fit.y, 0.01f)
        assertEquals((411f - 411f * fit.scale) / 2, fit.x, 0.01f)
        // Dragged up, it stops at its last row.
        val bottom = zoomView(SceneView(), 1f, 0f, 0f, 0f, -100_000f, scene, 411f, 640f)
        assertEquals(640f - scene.height, bottom.y, 0.01f)
        assertEquals(0f, bottom.x, 0f)
    }

    @Test
    fun aNodeOutOfSightIsBroughtIn() {
        val scene = treeScene(groupPeers(large, TreeGrouping.ROUTE), 411f, 640f, t)
        val last = scene.peers.last()
        val view = revealView(SceneView(), last.x, last.y, scene, 411f, 640f)
        val onScreen = view.y + last.y
        assertTrue(onScreen in 0f..640f)
        // One already in sight moves nothing.
        val first = scene.hubs.first()
        assertEquals(SceneView(), revealView(SceneView(), first.x, first.y, scene, 411f, 640f))
        assertEquals(view, revealView(view, last.x, last.y, scene, 411f, 640f))
    }

    @Test
    fun theSchemeIsOneSwitch() {
        for (scheme in MapScheme.entries) {
            val scene = planMapScene(scheme, TreeGrouping.OWNER, home, 411f, 640f, users = mapOf(0L to "Alex"))
            assertEquals(scheme, scene.scheme)
            assertEquals(home.size, scene.peers.size)
        }
        assertEquals(1, planMapScene(MapScheme.TREE, TreeGrouping.OWNER, home, 411f, 640f).hubs.size)
        assertEquals(2, planMapScene(MapScheme.ROUTES, TreeGrouping.OWNER, home, 411f, 640f).hubs.size)
    }
}
