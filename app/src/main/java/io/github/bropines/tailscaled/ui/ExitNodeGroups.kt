package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData

/*
 * The exit-node list in groups, for a tailnet that has hundreds of them: Mullvad's nodes each
 * say where they are (tailcfg.Location), and a flat list of every one of them could not be
 * scrolled through (#27). The tailnet's own exit nodes, which say nothing of the kind, stay a
 * plain list above; the located ones go under their country, and in a country of more than
 * one city, under their city.
 *
 * Nothing here draws: ExitNodePicker lays the groups out, and all of this is tested on the JVM
 * (ExitNodeGroupsTest).
 */

/** The nodes of one city, the most preferable first. */
internal data class ExitNodeCity(val name: String, val nodes: List<PeerData>)

/** One country's nodes, by city. */
internal data class ExitNodeCountry(val code: String, val name: String, val cities: List<ExitNodeCity>) {
    val nodes: List<PeerData> get() = cities.flatMap { it.nodes }

    /**
     * The node to choose for the country as a whole: the one its operator ranks highest
     * (Location.Priority — Mullvad ranks its servers by load) among those online, and among
     * all of them while none says it is.
     */
    val best: PeerData? get() = nodes.filter { it.online != false }.ifEmpty { nodes }.maxByOrNull { it.location?.priority ?: 0 }
}

/** The tailnet's own exit nodes, and the located ones by country. */
internal data class ExitNodeGroups(val own: List<PeerData>, val countries: List<ExitNodeCountry>)

/**
 * [nodes] grouped: those without a country as they came, the others by country (by name), by
 * city (by name) and in each city the highest priority first, then by name — so the order does
 * not move each time the list is read again.
 */
internal fun groupExitNodes(nodes: List<PeerData>): ExitNodeGroups {
    val (located, own) = nodes.partition { countryKey(it) != null }
    val countries = located.groupBy { countryKey(it)!! }.map { (code, members) ->
        val name = members.firstNotNullOfOrNull { it.location?.country?.takeIf(String::isNotBlank) } ?: code
        val cities = members
            .groupBy { it.location?.city?.takeIf(String::isNotBlank) ?: "" }
            .map { (city, inCity) -> ExitNodeCity(city, inCity.sortedWith(byPreference)) }
            .sortedWith(compareBy<ExitNodeCity> { it.name.isEmpty() }.thenBy { it.name.lowercase() })
        ExitNodeCountry(code, name, cities)
    }.sortedBy { it.name.lowercase() }
    return ExitNodeGroups(own, countries)
}

private val byPreference = compareByDescending<PeerData> { it.location?.priority ?: 0 }.thenBy { it.getDisplayName().lowercase() }

/** A country's key: its code when it has one, its name otherwise; null for a node that names neither. */
private fun countryKey(node: PeerData): String? {
    val location = node.location ?: return null
    return location.countryCode?.takeIf { it.isNotBlank() }?.uppercase()
        ?: location.country?.takeIf { it.isNotBlank() }
}

/**
 * The nodes [query] finds: every word of it somewhere in the node's name, its DNS name, its
 * country, its city or their codes, in any case. A blank query finds them all.
 */
internal fun searchExitNodes(nodes: List<PeerData>, query: String): List<PeerData> {
    val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
    if (words.isEmpty()) return nodes
    return nodes.filter { node ->
        val haystack = listOfNotNull(
            node.getDisplayName(), node.hostName, node.dnsName,
            node.location?.country, node.location?.countryCode, node.location?.city, node.location?.cityCode,
        ).joinToString(" ").lowercase()
        words.all { it in haystack }
    }
}

/** The flag of a two-letter country code, as its pair of regional indicator symbols; null for anything else. */
internal fun flagOf(code: String): String? {
    if (code.length != 2 || !code.all { it.uppercaseChar() in 'A'..'Z' }) return null
    return code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** A line of the grouped list, in the order it is drawn. [key] is stable across recompositions. */
internal sealed interface ExitNodeEntry {
    val key: String

    data class Node(val node: PeerData) : ExitNodeEntry {
        override val key: String get() = "n:${node.id ?: node.getPrimaryIp()}"
    }

    /** The heading over the located nodes, under the tailnet's own. */
    data object Locations : ExitNodeEntry {
        override val key: String get() = "locations"
    }

    data class Country(val country: ExitNodeCountry, val open: Boolean) : ExitNodeEntry {
        override val key: String get() = "c:${country.code}"
    }

    /** "Best in <country>": the country's [ExitNodeCountry.best], one press. */
    data class Best(val country: ExitNodeCountry) : ExitNodeEntry {
        override val key: String get() = "b:${country.code}"
    }

    data class City(val country: String, val city: ExitNodeCity) : ExitNodeEntry {
        override val key: String get() = "t:$country:${city.name}"
    }
}

/**
 * [groups] as the lines of the list: the tailnet's own nodes, then each country — its nodes,
 * under their cities when it has more than one, only while it is in [open] (every country when
 * [open] is null: a search shows what it found). A country of more than one node starts with
 * the line that chooses its best.
 */
internal fun exitNodeEntries(groups: ExitNodeGroups, open: Set<String>?): List<ExitNodeEntry> = buildList {
    groups.own.forEach { add(ExitNodeEntry.Node(it)) }
    if (groups.countries.isNotEmpty() && groups.own.isNotEmpty()) add(ExitNodeEntry.Locations)
    for (country in groups.countries) {
        val isOpen = open == null || country.code in open
        add(ExitNodeEntry.Country(country, isOpen))
        if (!isOpen) continue
        if (country.nodes.size > 1) add(ExitNodeEntry.Best(country))
        val named = country.cities.size > 1
        for (city in country.cities) {
            if (named) add(ExitNodeEntry.City(country.code, city))
            city.nodes.forEach { add(ExitNodeEntry.Node(it)) }
        }
    }
}

/** The country [selectedIp] is in, by its key; null for none or a node of the tailnet's own. */
internal fun countryOf(groups: ExitNodeGroups, selectedIp: String): String? =
    if (selectedIp.isEmpty()) null
    else groups.countries.firstOrNull { c -> c.nodes.any { it.getPrimaryIp() == selectedIp } }?.code
