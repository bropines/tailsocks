package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.PeerLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exit-node list by country (#27): what goes where, in what order, and what a search finds. */
class ExitNodeGroupsTest {
    private var ip = 0

    private fun node(name: String, cc: String? = null, country: String? = null, city: String? = null, priority: Int? = null, online: Boolean = true) =
        PeerData(
            id = "n-$name", hostName = name, dnsName = "$name.example.ts.net.", online = online,
            tailscaleIPs = listOf("100.64.0.${++ip}"),
            location = if (cc == null && country == null) null else PeerLocation(country, cc, city, null, null, null, priority),
        )

    private fun names(nodes: List<PeerData>) = nodes.map { it.hostName }

    @Test
    fun theTailnetsOwnStayAListAndTheLocatedGoByCountryAndCity() {
        val home = node("home-exit")
        val nodes = listOf(
            node("de-fra-2", "DE", "Germany", "Frankfurt", priority = 10),
            home,
            node("de-ber-1", "DE", "Germany", "Berlin", priority = 50),
            node("de-fra-1", "DE", "Germany", "Frankfurt", priority = 90),
            node("at-vie-1", "AT", "Austria", "Vienna"),
        )
        val groups = groupExitNodes(nodes)
        assertEquals(listOf(home), groups.own)
        assertEquals(listOf("Austria", "Germany"), groups.countries.map { it.name })
        val germany = groups.countries[1]
        assertEquals(listOf("Berlin", "Frankfurt"), germany.cities.map { it.name })
        // In a city the operator's favourite first.
        assertEquals(listOf("de-fra-1", "de-fra-2"), names(germany.cities[1].nodes))
        assertEquals("de-fra-1", germany.best?.hostName)
    }

    @Test
    fun theBestOfACountryIsOneThatIsOnline() {
        val country = groupExitNodes(listOf(
            node("a", "SE", "Sweden", "Stockholm", priority = 99, online = false),
            node("b", "SE", "Sweden", "Stockholm", priority = 5),
        )).countries.single()
        assertEquals("b", country.best?.hostName)
        // None online: still something to choose.
        val offline = groupExitNodes(listOf(node("c", "SE", "Sweden", "Malmö", priority = 1, online = false))).countries.single()
        assertEquals("c", offline.best?.hostName)
    }

    @Test
    fun aCountryWithoutACodeIsKeptByItsName() {
        val groups = groupExitNodes(listOf(node("x", country = "Atlantis", city = "Poseidonia"), node("y", "de", null, null)))
        assertEquals(listOf("Atlantis", "DE"), groups.countries.map { it.name })
        assertEquals("DE", groups.countries[1].code)
    }

    @Test
    fun theLinesShowOnlyTheOpenCountries() {
        val groups = groupExitNodes(listOf(
            node("own"),
            node("de-fra-1", "DE", "Germany", "Frankfurt"),
            node("de-ber-1", "DE", "Germany", "Berlin"),
            node("nl-ams-1", "NL", "Netherlands", "Amsterdam"),
        ))
        val closed = exitNodeEntries(groups, emptySet())
        assertEquals(listOf("n:n-own", "locations", "c:DE", "c:NL"), closed.map { it.key })
        val de = exitNodeEntries(groups, setOf("DE"))
        assertEquals(
            listOf("n:n-own", "locations", "c:DE", "b:DE", "t:DE:Berlin", "n:n-de-ber-1", "t:DE:Frankfurt", "n:n-de-fra-1", "c:NL"),
            de.map { it.key },
        )
        // One city, one node: no city line and no "best".
        val nl = exitNodeEntries(groups, setOf("NL"))
        assertEquals(listOf("c:NL", "n:n-nl-ams-1"), nl.map { it.key }.takeLast(2))
        // A search opens everything it found.
        assertTrue(exitNodeEntries(groups, null).any { it.key == "n:n-nl-ams-1" })
    }

    @Test
    fun theCountryInUseIsTheOneToOpen() {
        val nodes = listOf(node("own"), node("de-1", "DE", "Germany", "Frankfurt"))
        val groups = groupExitNodes(nodes)
        assertEquals("DE", countryOf(groups, nodes[1].getPrimaryIp()))
        assertNull(countryOf(groups, nodes[0].getPrimaryIp()))
        assertNull(countryOf(groups, ""))
    }

    @Test
    fun aSearchFindsByNameCountryAndCityInAnyCase() {
        val nodes = listOf(
            node("de-fra-wg-001", "DE", "Germany", "Frankfurt"),
            node("se-sto-wg-002", "SE", "Sweden", "Stockholm"),
            node("home-router"),
        )
        assertEquals(listOf("de-fra-wg-001"), names(searchExitNodes(nodes, "germ")))
        assertEquals(listOf("se-sto-wg-002"), names(searchExitNodes(nodes, "STOCKHOLM")))
        assertEquals(listOf("se-sto-wg-002"), names(searchExitNodes(nodes, "wg sweden")))
        assertEquals(listOf("home-router"), names(searchExitNodes(nodes, "home")))
        assertEquals(nodes, searchExitNodes(nodes, "  "))
    }

    @Test
    fun aFlagIsTwoRegionalIndicators() {
        assertEquals("🇩🇪", flagOf("de"))
        assertNull(flagOf("DEU"))
        assertNull(flagOf("1A"))
    }
}
