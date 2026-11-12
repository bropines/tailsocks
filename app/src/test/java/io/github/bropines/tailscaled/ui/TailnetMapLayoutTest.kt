package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.core.TrafficSnapshot
import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TailnetMapLayoutTest {
    private val m = MapMetrics()

    private fun peer(
        name: String,
        path: PeerPath = PeerPath.Direct("192.168.1.2:41641"),
        rate: TrafficRate = TrafficRate.ZERO,
    ) = MapPeer(name, PeerData(id = name, hostName = name), path, rate)

    private fun idle(name: String) = peer(name, PeerPath.NotYet("fra", null))
    private fun offline(name: String) = peer(name, PeerPath.Offline(null))

    private fun names(nodes: List<MapNode>) = nodes.map { it.peer.name }

    /** A phone held upright, a tablet on its side, a television inside its safe area. */
    private val windows = listOf(411f to 640f, 1280f to 640f, 864f to 400f, 320f to 300f)

    @Test
    fun pointsOnAnEllipseAreEvenlySpacedAlongIt() {
        val pts = ellipsePoints(300f, 120f, 12)
        assertEquals(12, pts.size)
        // The first one is the top.
        assertEquals(0f, pts[0].first, 0.5f)
        assertEquals(-120f, pts[0].second, 0.5f)
        // Every point is on the curve.
        for ((x, y) in pts) assertEquals(1f, (x / 300f) * (x / 300f) + (y / 120f) * (y / 120f), 0.01f)
        // Neighbours along a flat ellipse are a similar distance apart; at equal angles the
        // ones at the ends of the long axis would be a third as far apart as the ones on top.
        val gaps = pts.indices.map { i ->
            val (ax, ay) = pts[i]
            val (bx, by) = pts[(i + 1) % pts.size]
            hypot(bx - ax, by - ay)
        }
        assertTrue("gaps $gaps", gaps.min() > gaps.max() * 0.7f)
    }

    @Test
    fun aCircleGivesTheAnglesItShould() {
        val pts = ellipsePoints(100f, 100f, 4)
        val expected = listOf(0f to -100f, 100f to 0f, 0f to 100f, -100f to 0f)
        for (i in 0..3) {
            assertEquals(expected[i].first, pts[i].first, 0.5f)
            assertEquals(expected[i].second, pts[i].second, 0.5f)
        }
        // Half a step on.
        val shifted = ellipsePoints(100f, 100f, 4, phase = 0.5f)
        assertEquals(70.7f, shifted[0].first, 0.5f)
        assertEquals(-70.7f, shifted[0].second, 0.5f)
        assertEquals(628.3f, ellipsePerimeter(100f, 100f), 0.5f)
    }

    @Test
    fun ringsStayInsideTheWindowAndApartFromEachOther() {
        for ((w, h) in windows) {
            val rings = mapRings(w, h, m)
            assertTrue(rings.isNotEmpty())
            val outer = rings.last()
            assertEquals(w / 2 - m.marginX, outer.rx, 0.01f)
            assertEquals((h - m.marginTop - m.marginBottom) / 2, outer.ry, 0.01f)
            rings.zipWithNext { a, b ->
                assertTrue("$w×$h", minOf(b.rx - a.rx, b.ry - a.ry) >= m.ringGap - 0.01f)
            }
            if (rings.size > 1) assertEquals(m.firstRing, minOf(rings.first().rx, rings.first().ry), 0.01f)
            for (r in rings) assertEquals((ellipsePerimeter(r.rx, r.ry) / m.slot).toInt(), r.capacity)
        }
    }

    @Test
    fun aPhoneHasTwoRingsAndATabletMore() {
        assertEquals(2, mapRings(411f, 640f, m).size)
        assertTrue(mapRings(1280f, 640f, m).size >= 3)
        assertEquals(1, mapRings(320f, 300f, m).size)
    }

    @Test
    fun onePeerStandsAboveTheCentreOnTheMiddleRing() {
        val plan = planTailnetMap(listOf(peer("only")), 1280f, 640f, m)
        val rings = mapRings(1280f, 640f, m)
        val node = plan.nodes.single()
        assertEquals(rings.size / 2, node.spot.ring)
        assertEquals(plan.centerX, node.spot.x, 0.5f)
        assertEquals(plan.centerY - rings[node.spot.ring].ry, node.spot.y, 0.5f)
        assertTrue(plan.hidden.isEmpty())
        assertEquals(640f, plan.centerX, 0.01f)
    }

    @Test
    fun noPeersIsAnEmptyMap() {
        val plan = planTailnetMap(emptyList(), 411f, 640f, m)
        assertTrue(plan.nodes.isEmpty())
        assertTrue(plan.hidden.isEmpty())
    }

    @Test
    fun theFewestRingsFromTheMiddleAreUsed() {
        assertEquals(1..1, chooseRings(listOf(8, 16, 24), 10))
        // No middle for two of three: the outer pair.
        assertEquals(1..2, chooseRings(listOf(8, 16, 24), 20))
        assertEquals(0..2, chooseRings(listOf(8, 16, 24), 45))
        assertEquals(0..2, chooseRings(listOf(8, 16, 24), 500))
        assertEquals(1..1, chooseRings(listOf(8, 16), 3))
        assertEquals(0..1, chooseRings(listOf(8, 16), 17))
        assertEquals(0..0, chooseRings(listOf(8), 0))
    }

    @Test
    fun ringsFillInProportionAndNeverOverflow() {
        assertEquals(listOf(3, 6), spreadOverRings(listOf(8, 16), 9))
        assertEquals(listOf(8, 16), spreadOverRings(listOf(8, 16), 24))
        assertEquals(listOf(8, 16), spreadOverRings(listOf(8, 16), 300))
        assertEquals(listOf(1, 0), spreadOverRings(listOf(8, 16), 1))
        for (n in 0..48) {
            val shares = spreadOverRings(listOf(8, 16, 24), n)
            assertEquals(n, shares.sum())
            assertTrue(shares[0] <= 8 && shares[1] <= 16 && shares[2] <= 24)
        }
    }

    @Test
    fun linkedPeersTakeTheInnerRingThenIdleThenOffline() {
        val peers = listOf(offline("z-off"), idle("b-idle"), peer("y-direct"), peer("a-derp", PeerPath.Derp("fra", null))) +
            (1..20).map { offline("off$it") }
        val plan = planTailnetMap(peers, 411f, 640f, m)
        assertTrue(plan.hidden.isEmpty())
        assertEquals(listOf("a-derp", "y-direct", "b-idle"), names(plan.nodes).take(3))
        assertTrue(plan.nodes.drop(3).all { it.peer.tier == MapTier.OFFLINE })
        // Inner ring first: the ring number never goes down along the order.
        plan.nodes.zipWithNext { a, b -> assertTrue(a.spot.ring <= b.spot.ring) }
        assertEquals(0, plan.nodes.first().spot.ring)
        assertEquals(1, plan.nodes.last().spot.ring)
    }

    @Test
    fun aRateDoesNotMoveAnybodyWhileEverybodyFits() {
        val calm = (1..10).map { peer("p$it") }
        val busy = calm.map { if (it.name == "p7") peer("p7", rate = TrafficRate(5_000_000, 100)) else it }
        val a = planTailnetMap(calm, 411f, 640f, m)
        val b = planTailnetMap(busy, 411f, 640f, m)
        assertEquals(a.nodes.map { it.peer.name to it.spot }, b.nodes.map { it.peer.name to it.spot })
    }

    @Test
    fun threeHundredPeersAreCappedWithTheBusyAndTheLinkedKept() {
        val peers = (1..280).map { idle("mullvad-%03d".format(it)) } +
            (1..15).map { offline("old-$it") } +
            listOf(peer("nas"), peer("desktop", PeerPath.Derp("fra", "Frankfurt")), peer("pi", PeerPath.PeerRelay("203.0.113.9:7777", "nas")))
        // Traffic through an exit node that the alphabet alone would have left out.
        val busyIdle = peers.map { if (it.name == "mullvad-250") peer("mullvad-250", rate = TrafficRate(900_000, 40_000)) else it }

        for ((w, h) in windows) {
            val room = mapRings(w, h, m.copy(marginBottom = m.marginBottom + 52f)).sumOf { it.capacity }
            val plan = planTailnetMap(busyIdle, w, h, m, moreRoom = 52f)
            assertEquals(room, plan.nodes.size)
            assertEquals(busyIdle.size - room, plan.hidden.size)
            assertTrue("$w×$h holds $room", room in 4..80)
            val shown = names(plan.nodes).toSet()
            assertTrue(shown.containsAll(listOf("mullvad-250", "nas", "desktop", "pi")))
            assertTrue(plan.hidden.none { it.name in shown })
            assertTrue(plan.nodes.none { it.peer.tier == MapTier.OFFLINE })
            // Nobody is drawn twice, and the two lists are the whole tailnet.
            assertEquals(busyIdle.size, (shown + plan.hidden.map { it.name }).size)
        }
    }

    @Test
    fun nodesKeepTheirDistanceAndStayInTheWindow() {
        val peers = (1..300).map { idle("n%03d".format(it)) }
        for ((w, h) in windows) {
            val plan = planTailnetMap(peers, w, h, m, moreRoom = 52f)
            val spots = plan.nodes.map { it.spot }
            for (s in spots) {
                assertTrue("$w×$h $s", s.x >= m.marginX - 0.5f && s.x <= w - m.marginX + 0.5f)
                assertTrue("$w×$h $s", s.y >= m.marginTop - 0.5f && s.y <= h - m.marginBottom - 52f + 0.5f)
            }
            // A full ring is the tightest case: neighbours are a slot apart along the curve,
            // a little less as the crow flies where the curve bends.
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                val d = hypot(spots[i].x - spots[j].x, spots[i].y - spots[j].y)
                assertTrue("$w×$h: $d between ${spots[i]} and ${spots[j]}", d >= m.slot * 0.8f)
            }
            // And none stands on this device.
            if (mapRings(w, h, m).size > 1) for (s in spots) {
                assertTrue(hypot(s.x - plan.centerX, s.y - plan.centerY) >= m.firstRing - 0.5f)
            }
        }
    }

    @Test
    fun theRoomForTheMoreButtonIsTakenOnlyWhenSomebodyIsLeftOut() {
        val room = mapRings(411f, 640f, m).sumOf { it.capacity }
        val fits = planTailnetMap((1..room).map { idle("n%03d".format(it)) }, 411f, 640f, m, moreRoom = 52f)
        assertTrue(fits.hidden.isEmpty())
        assertEquals(room, fits.nodes.size)
        val over = planTailnetMap((1..room + 1).map { idle("n%03d".format(it)) }, 411f, 640f, m, moreRoom = 52f)
        assertTrue(over.hidden.isNotEmpty())
        assertTrue(over.centerY < fits.centerY)
        assertTrue(over.nodes.maxOf { it.spot.y } <= 640f - m.marginBottom - 52f + 0.5f)
    }

    @Test
    fun pathsDecideTheTier() {
        val names = PeerPathNames.NONE
        val peers = listOf(
            PeerData(id = "d", hostName = "d", online = true, curAddr = "192.168.1.9:41641", relay = "fra"),
            PeerData(id = "r", hostName = "r", online = true, relay = "fra", lastWrite = "2026-10-01T10:00:00Z"),
            PeerData(id = "n", hostName = "n", online = true, relay = "fra", lastWrite = "0001-01-01T00:00:00Z"),
            PeerData(id = "o", hostName = "o", online = false, relay = "fra"),
        )
        val traffic = TrafficSnapshot(measured = true, peers = mapOf("r" to TrafficRate(10, 20)))
        val mapped = mapPeers(peers, traffic, names)
        assertEquals(listOf(MapTier.LINKED, MapTier.LINKED, MapTier.IDLE, MapTier.OFFLINE), mapped.map { it.tier })
        assertEquals(listOf("d", "r", "n", "o"), mapped.map { it.key })
        assertEquals(TrafficRate(10, 20), mapped[1].rate)
        assertEquals(TrafficRate.ZERO, mapped[0].rate)
    }

    @Test
    fun aLinkGetsHeavierWithTheLogarithmOfItsRate() {
        assertEquals(0f, linkWeight(0), 0f)
        assertEquals(0f, linkWeight(900), 0f)
        assertEquals(1f, linkWeight(10_000_000), 0.001f)
        assertEquals(1f, linkWeight(900_000_000), 0f)
        assertEquals(0.5f, linkWeight(100_000), 0.001f)
        val steps = listOf(2_000L, 20_000L, 200_000L, 2_000_000L).map(::linkWeight)
        steps.zipWithNext { a, b -> assertTrue(abs((b - a) - 0.25f) < 0.001f) }
    }
}
