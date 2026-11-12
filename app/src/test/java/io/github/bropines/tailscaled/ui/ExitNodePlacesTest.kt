package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.PeerLocation
import io.github.bropines.tailscaled.models.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitNodePlacesTest {
    private fun located(id: String, cc: String, code: String, city: String, lat: Double, lon: Double) = PeerData(
        id = id, hostName = id, tailscaleIPs = listOf("100.64.0.${id.hashCode() and 0xFF}"), exitNodeOption = true,
        location = PeerLocation(country = cc, countryCode = cc, city = city, cityCode = code, latitude = lat, longitude = lon)
    )

    private fun own(id: String, relay: String?) =
        PeerData(id = id, hostName = id, tailscaleIPs = listOf("100.70.0.${id.hashCode() and 0xFF}"), exitNodeOption = true, relay = relay)

    private val derp = mapOf(
        "fra" to DerpPlace("Frankfurt", 50.1109, 8.6821),
        "nyc" to DerpPlace("New York City", 40.7128, -74.006),
    )

    private val fra1 = located("fra1", "DE", "FRA", "Frankfurt", 50.11, 8.68)
    private val fra2 = located("fra2", "DE", "FRA", "Frankfurt", 50.11, 8.68)
    private val ams = located("ams", "NL", "AMS", "Amsterdam", 52.37, 4.90)
    private val bru = located("bru", "BE", "BRU", "Brussels", 50.85, 4.35)
    private val syd = located("syd", "AU", "SYD", "Sydney", -33.87, 151.21)

    // --- the DERP map ---------------------------------------------------------------------

    @Test
    fun derpPlacesComeByRegionCodeAndOnlyWithCoordinates() {
        val json = """
            {"Regions": {
              "4": {"RegionID": 4, "RegionCode": "fra", "RegionName": "Frankfurt", "Latitude": 50.1109, "Longitude": 8.6821, "Nodes": [{"Name": "4a"}]},
              "900": {"RegionID": 900, "RegionCode": "home", "RegionName": "Embedded"},
              "901": {"RegionID": 901, "RegionCode": "", "RegionName": "Nameless", "Latitude": 1.0, "Longitude": 1.0},
              "902": {"RegionID": 902, "RegionCode": "odd", "Latitude": 95.0, "Longitude": 1.0}
            }}
        """.trimIndent()
        assertEquals(mapOf("fra" to DerpPlace("Frankfurt", 50.1109, 8.6821)), derpPlacesOf(json))
    }

    @Test
    fun anAnswerThatIsNotAMapGivesNothing() {
        assertTrue(derpPlacesOf("Error: daemon not running").isEmpty())
        assertTrue(derpPlacesOf("").isEmpty())
        assertTrue(derpPlacesOf("{}").isEmpty())
    }

    // --- the status ------------------------------------------------------------------------

    @Test
    fun aPeersLocationIsReadFromTheStatus() {
        val status = AppJson.decodeFromString<StatusResponse>(
            """
            {"Peer": {
              "a": {"ID": "a", "ExitNodeOption": true, "Location": {"Country": "Sweden", "CountryCode": "SE",
                    "City": "Stockholm", "CityCode": "STO", "Latitude": 59.3293, "Longitude": 18.0686, "Priority": 100}},
              "b": {"ID": "b", "ExitNodeOption": true, "Relay": "fra"}
            }}
            """.trimIndent()
        )
        val a = status.peers!!.getValue("a").location!!
        assertEquals("Stockholm", a.city)
        assertEquals("SE", a.countryCode)
        assertEquals(59.3293, a.latitude!!, 1e-9)
        assertEquals(18.0686, a.longitude!!, 1e-9)
        assertNull(status.peers!!.getValue("b").location)
    }

    // --- placing ---------------------------------------------------------------------------

    @Test
    fun nodesOfOneCityShareAPlace() {
        val placement = placeExitNodes(listOf(ams, fra1, fra2), derp)
        assertTrue(placement.unplaced.isEmpty())
        // Largest first.
        assertEquals(listOf("Frankfurt", "Amsterdam"), placement.places.map { it.city })
        assertEquals(listOf(fra1, fra2), placement.places[0].nodes)
        assertFalse(placement.places[0].approximate)
        assertEquals("DE", placement.places[0].country)
    }

    @Test
    fun aNodeWithoutALocationIsGuessedAtItsRelay() {
        val a = own("a", "fra")
        val b = own("b", "fra")
        val c = own("c", "nyc")
        val placement = placeExitNodes(listOf(a, b, c), derp)
        assertEquals(2, placement.places.size)
        val fra = placement.places[0]
        assertTrue(fra.approximate)
        assertEquals("Frankfurt", fra.relay)
        // Two devices behind one relay are one dot that lists both.
        assertEquals(listOf(a, b), fra.nodes)
        assertEquals(50.1109, fra.latitude, 1e-9)
        assertEquals("New York City", placement.places[1].relay)
    }

    @Test
    fun aGuessIsNeverMergedIntoTheCityItFallsOn() {
        val placement = placeExitNodes(listOf(fra1, own("a", "fra")), derp)
        assertEquals(2, placement.places.size)
        assertEquals(setOf(true, false), placement.places.map { it.approximate }.toSet())
    }

    @Test
    fun whatCanBePlacedNeitherWayIsLeftForTheList() {
        val noRelay = own("a", null)
        val blankRelay = own("b", "")
        val unknownRelay = own("c", "home")
        // A location with no coordinates is no place; the relay is the next thing to try.
        val named = PeerData(id = "d", relay = "fra", location = PeerLocation(country = "Sweden", city = "Stockholm"))
        val zero = PeerData(id = "e", location = PeerLocation(city = "Null Island", latitude = 0.0, longitude = 0.0))
        val placement = placeExitNodes(listOf(noRelay, blankRelay, unknownRelay, named, zero), derp)
        assertEquals(listOf(noRelay, blankRelay, unknownRelay, zero), placement.unplaced)
        assertEquals(listOf(named), placement.places.single().nodes)
        assertTrue(placement.places.single().approximate)
        assertTrue(placeExitNodes(emptyList(), derp).places.isEmpty())
    }

    @Test
    fun citiesWithoutACodeAreToldApartByWhereTheyAre() {
        val a = PeerData(id = "a", location = PeerLocation(city = "Springfield", latitude = 39.80, longitude = -89.64))
        val b = PeerData(id = "b", location = PeerLocation(city = "Springfield", latitude = 37.21, longitude = -93.29))
        val c = PeerData(id = "c", location = PeerLocation(city = "Springfield", latitude = 39.80, longitude = -89.64))
        val placement = placeExitNodes(listOf(a, b, c), emptyMap())
        assertEquals(listOf(listOf(a, c), listOf(b)), placement.places.map { it.nodes })
    }

    // --- projection ------------------------------------------------------------------------

    @Test
    fun theProjectionSpansTheCroppedWorld() {
        assertEquals(0f, MapProjection.x(-180.0), 1e-6f)
        assertEquals(0.5f, MapProjection.x(0.0), 1e-6f)
        assertEquals(1f, MapProjection.x(180.0), 1e-6f)
        assertEquals(0f, MapProjection.y(MapProjection.LAT_TOP), 1e-6f)
        assertEquals(MapProjection.HEIGHT, MapProjection.y(MapProjection.LAT_BOTTOM), 1e-6f)
        // Beyond the crop is its edge, not off the map.
        assertEquals(0f, MapProjection.y(89.0), 1e-6f)
        assertEquals(MapProjection.HEIGHT, MapProjection.y(-75.0), 1e-6f)
        // North is up, all the way.
        var last = -1f
        for (lat in 80 downTo -58) {
            val y = MapProjection.y(lat.toDouble())
            assertTrue(y > last)
            last = y
        }
        // Miller: about 2.1 : 1 for this crop, and a degree is taller in Europe than at the equator.
        assertEquals(0.474f, MapProjection.HEIGHT, 0.005f)
        val atEquator = MapProjection.y(0.0) - MapProjection.y(1.0)
        val inEurope = MapProjection.y(50.0) - MapProjection.y(51.0)
        assertTrue(inEurope > atEquator * 1.25f)
    }

    // --- the view --------------------------------------------------------------------------

    private val frame = MapFrame(1000f, 600f)

    @Test
    fun theWholeWorldFitsAndIsCentred() {
        val whole = frame.whole()
        assertEquals(1f, whole.scale, 1e-6f)
        assertEquals(0f, frame.screenX(whole, 0f), 1e-3f)
        assertEquals(1000f, frame.screenX(whole, 1f), 1e-3f)
        // Shorter than the frame, so it sits in the middle of it.
        assertEquals(300f, frame.screenY(whole, MapProjection.HEIGHT / 2), 1e-3f)

        // A frame flatter than the world shows it whole by height instead.
        val flat = MapFrame(1000f, 237f)
        assertEquals(0.5f, flat.minScale, 0.01f)
        assertEquals(0f, flat.screenY(flat.whole(), 0f), 0.5f)
        assertEquals(237f, flat.screenY(flat.whole(), MapProjection.HEIGHT), 0.5f)
    }

    @Test
    fun theViewStaysInsideTheWorldAndItsZoomLimits() {
        assertEquals(1f, frame.clamp(MapView(0.2f, 0.9f, 0.1f)).scale, 1e-6f)
        assertEquals(MapFrame.MAX_SCALE, frame.clamp(MapView(500f, 0.5f, 0.2f)).scale, 1e-6f)
        // Zoomed in four times, panned far past the north-west corner: the corner is the limit.
        val corner = frame.clamp(MapView(4f, -3f, -3f))
        assertEquals(0f, frame.screenX(corner, 0f), 1e-3f)
        assertEquals(0f, frame.screenY(corner, 0f), 1e-3f)
        val opposite = frame.clamp(MapView(4f, 9f, 9f))
        assertEquals(1000f, frame.screenX(opposite, 1f), 1e-2f)
        assertEquals(600f, frame.screenY(opposite, MapProjection.HEIGHT), 1e-2f)
    }

    @Test
    fun aPinchKeepsThePointUnderTheFingers() {
        val start = frame.clamp(MapView(3f, 0.52f, 0.15f))
        val wx = frame.worldX(start, 400f)
        val wy = frame.worldY(start, 250f)
        // The fingers spread to twice the distance while their centre moves from (400, 250) to (430, 240).
        val after = frame.transform(start, focusX = 430f, focusY = 240f, panX = 30f, panY = -10f, zoom = 2f)
        assertEquals(6f, after.scale, 1e-4f)
        assertEquals(430f, frame.screenX(after, wx), 0.05f)
        assertEquals(240f, frame.screenY(after, wy), 0.05f)
        // Round trip of the two conversions.
        assertEquals(wx, frame.worldX(after, frame.screenX(after, wx)), 1e-5f)
    }

    @Test
    fun aPinchStopsAtTheLimits() {
        val out = frame.transform(frame.whole(), 500f, 300f, 0f, 0f, 0.1f)
        assertEquals(frame.whole(), out)
        val closest = frame.transform(MapView(30f, 0.5f, 0.2f), 500f, 300f, 0f, 0f, 4f)
        assertEquals(MapFrame.MAX_SCALE, closest.scale, 1e-6f)
    }

    @Test
    fun fittingHoldsEveryPlaceInsideThePadding() {
        val places = placeExitNodes(listOf(fra1, ams, bru, syd), derp).places
        val view = frame.fit(places, padding = 50f, maxScale = MapFrame.MAX_SCALE)
        for (p in places) {
            assertTrue(frame.screenX(view, p.x) in 49.5f..950.5f)
            assertTrue(frame.screenY(view, p.y) in 49.5f..550.5f)
        }
        // Europe alone is fitted far closer than Europe with Sydney.
        val europe = frame.fit(places.filter { it.city != "Sydney" }, 50f, MapFrame.MAX_SCALE)
        assertTrue(europe.scale > view.scale * 5)
        // One place is a point: the limit decides, and the place is in the middle.
        val one = frame.fit(places.filter { it.city == "Amsterdam" }, 50f, maxScale = 5f)
        assertEquals(5f, one.scale, 1e-6f)
        assertEquals(500f, frame.screenX(one, places.first { it.city == "Amsterdam" }.x), 0.05f)
        assertEquals(frame.whole(), frame.fit(emptyList(), 50f, 5f))
    }

    @Test
    fun aViewBetweenTwoIsBetweenThem() {
        val a = MapView(1f, 0.2f, 0.1f)
        val b = MapView(16f, 0.6f, 0.3f)
        assertEquals(a.scale, a.lerp(b, 0f).scale, 1e-5f)
        assertEquals(b.scale, a.lerp(b, 1f).scale, 1e-3f)
        // Geometric: half way from 1x to 16x is 4x.
        assertEquals(4f, a.lerp(b, 0.5f).scale, 1e-3f)
        assertEquals(0.4f, a.lerp(b, 0.5f).cx, 1e-5f)
    }

    // --- clustering ------------------------------------------------------------------------

    /** The merge distance of a 44-pixel marker in [frame] at [scale]. */
    private fun distance(scale: Float) = 44f / (frame.width * scale)

    @Test
    fun neighboursMergeWhenZoomedOutAndPartWhenZoomedIn() {
        val places = placeExitNodes(listOf(fra1, fra2, ams, bru, syd), derp).places
        val far = clusterPlaces(places, distance(1f))
        // Europe is one marker, Sydney another; the number is of nodes, not of cities.
        assertEquals(2, far.size)
        assertEquals(listOf(4, 1), far.map { it.nodeCount })
        assertEquals(setOf("Frankfurt", "Amsterdam", "Brussels"), far[0].places.map { it.city }.toSet())

        val near = clusterPlaces(places, distance(MapFrame.MAX_SCALE))
        assertEquals(4, near.size)
        assertTrue(near.all { it.places.size == 1 })
    }

    @Test
    fun aGuessOnTopOfACityNeverParts() {
        val places = placeExitNodes(listOf(fra1, own("a", "fra")), derp).places
        val closest = clusterPlaces(places, distance(MapFrame.MAX_SCALE))
        assertEquals(1, closest.size)
        assertEquals(2, closest[0].nodeCount)
    }

    @Test
    fun aClusterSitsAmongItsPlacesAndKeepsItsName() {
        val places = placeExitNodes(listOf(ams, bru), derp).places
        val one = clusterPlaces(places, 1f).single()
        assertEquals((places[0].x + places[1].x) / 2, one.x, 1e-6f)
        assertEquals((places[0].y + places[1].y) / 2, one.y, 1e-6f)
        assertEquals(one.key, clusterPlaces(places, 1f).single().key)
        assertTrue(clusterPlaces(emptyList(), 1f).isEmpty())
    }

    @Test
    fun aPlaceJoinsTheNearestSeedNotTheFirstInReach() {
        // On the equator, ten degrees apart, and a third six degrees from the first.
        val a1 = located("a1", "AA", "AAA", "A", 0.5, 0.0)
        val a2 = located("a2", "AA", "AAA", "A", 0.5, 0.0)
        val b1 = located("b1", "BB", "BBB", "B", 0.5, 10.0)
        val b2 = located("b2", "BB", "BBB", "B", 0.5, 10.0)
        val c = located("c", "CC", "CCC", "C", 0.5, 6.0)
        val clusters = clusterPlaces(placeExitNodes(listOf(c, b1, a1, b2, a2), emptyMap()).places, 0.02f)
        assertEquals(listOf(listOf("A"), listOf("B", "C")), clusters.map { cl -> cl.places.map { it.city } })
    }

    // --- clipping --------------------------------------------------------------------------

    private fun area(ring: FloatArray): Float {
        var sum = 0f
        for (i in ring.indices step 2) {
            val j = (i + 2) % ring.size
            sum += ring[i] * ring[j + 1] - ring[j] * ring[i + 1]
        }
        return kotlin.math.abs(sum) / 2
    }

    @Test
    fun clippingKeepsWhatIsInsideTheWindow() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        // Entirely inside: untouched.
        assertEquals(100f, area(clipRing(square, -5f, -5f, 20f, 20f)), 1e-4f)
        // The window cuts a quarter out of it.
        val quarter = clipRing(square, 5f, 5f, 20f, 20f)
        assertEquals(25f, area(quarter), 1e-4f)
        for (i in quarter.indices step 2) {
            assertTrue(quarter[i] in 5f..10f)
            assertTrue(quarter[i + 1] in 5f..10f)
        }
        // The window inside the ring: the window itself.
        assertEquals(4f, area(clipRing(square, 4f, 4f, 6f, 6f)), 1e-4f)
        // Apart: nothing.
        assertEquals(0, clipRing(square, 11f, 0f, 20f, 10f).size)
        // A triangle cut by one edge.
        val triangle = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
        assertEquals(50f - 12.5f, area(clipRing(triangle, -1f, -1f, 5f, 20f)), 1e-4f)
    }
}
