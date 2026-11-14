package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.ExitNodeSuggestion.Outcome
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.PeerLocation

// The exit-node picker's map view, without its sheet: a Mullvad-like spread of cities, two
// of a person's own nodes guessed at their relay, and one node nothing places. The preview
// has no daemon, so the relays' coordinates are handed in.

private var nextIp = 10

private fun mullvad(cc: String, country: String, code: String, city: String, lat: Double, lon: Double, count: Int = 1) =
    (1..count).map { n ->
        val name = "${cc.lowercase()}-${code.lowercase()}-wg-00$n"
        PeerData(
            id = "m-$name", hostName = name, dnsName = "$name.mullvad.ts.net.", os = "linux",
            tailscaleIPs = listOf("100.64.1.${nextIp++}"), online = true, exitNodeOption = true,
            location = PeerLocation(country, cc, city, code, lat, lon)
        )
    }

private fun own(id: String, host: String, ip: String, relay: String) = PeerData(
    id = id, hostName = host, dnsName = "$host.tail4a2c9.ts.net.", os = "linux",
    tailscaleIPs = listOf(ip), online = true, exitNodeOption = true, relay = relay
)

private val mapNodes: List<PeerData> by lazy {
    mullvad("DE", "Germany", "FRA", "Frankfurt", 50.11, 8.68, 3) +
        mullvad("NL", "Netherlands", "AMS", "Amsterdam", 52.37, 4.90, 2) +
        mullvad("BE", "Belgium", "BRU", "Brussels", 50.85, 4.35) +
        mullvad("FR", "France", "PAR", "Paris", 48.86, 2.35, 2) +
        mullvad("GB", "UK", "LON", "London", 51.51, -0.13, 3) +
        mullvad("SE", "Sweden", "STO", "Stockholm", 59.33, 18.07, 2) +
        mullvad("FI", "Finland", "HEL", "Helsinki", 60.17, 24.94) +
        mullvad("US", "USA", "NYC", "New York, NY", 40.71, -74.01, 3) +
        mullvad("US", "USA", "LAX", "Los Angeles, CA", 34.05, -118.24, 2) +
        mullvad("BR", "Brazil", "SAO", "Sao Paulo", -23.55, -46.63) +
        mullvad("ZA", "South Africa", "JNB", "Johannesburg", -26.20, 28.05) +
        mullvad("JP", "Japan", "TYO", "Tokyo", 35.68, 139.69, 2) +
        mullvad("SG", "Singapore", "SIN", "Singapore", 1.35, 103.82) +
        mullvad("AU", "Australia", "SYD", "Sydney", -33.87, 151.21) +
        listOf(
            own("o1", "vps-relay", "100.94.210.8", "fra"),
            own("o2", "homelab-nas", "100.72.5.101", "fra"),
            own("o3", "office-router", "100.71.3.9", "hsl"),
        )
}

/** "hsl" is deliberately not here: a relay of a self-hosted server, which says nothing about where it is. */
private val relays = mapOf("fra" to DerpPlace("Frankfurt", 50.1109, 8.6821))

@Composable
private fun MapPicker(selectedIp: String, nodes: List<PeerData> = mapNodes, view: String = ExitNodePickerView.MAP) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.BottomCenter) {
            ExitNodePicker(
                nodes = nodes,
                loading = false,
                selectedIp = selectedIp,
                pings = emptyMap(),
                pingingAll = false,
                best = Outcome.Suggested("o1", "100.94.210.8", "vps-relay", null),
                strings = exitNodePickerStrings(),
                onPingAll = {},
                onPing = {},
                onRetryBest = {},
                onChoose = { _, _ -> },
                onClose = {},
                modifier = Modifier.fillMaxWidth(),
                derpPlaces = relays,
                initialView = view,
            )
        }
    }
}

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "2-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class MapSizes

/** The world, opened on a person's own node: the hollow dot at Frankfurt, its two nodes listed. */
@PreviewTest @MapSizes @Composable
fun ExitNodeMapWorld() = AdaptiveShowcase { MapPicker(selectedIp = "100.94.210.8") }

/** Nothing chosen yet: the hint under the map. */
@PreviewTest @MapSizes @Composable
fun ExitNodeMapNothingChosen() = AdaptiveShowcase { MapPicker(selectedIp = "") }

/** A tailnet whose exit nodes are a few cities of Europe opens on Europe, not on the world. */
@PreviewTest @MapSizes @Composable
fun ExitNodeMapEurope() = AdaptiveShowcase {
    MapPicker(selectedIp = "", nodes = mapNodes.filter { it.location?.countryCode in setOf("DE", "NL", "SE", "FI", "FR") })
}

/** The other themes the outline has to survive: light, and dark without AMOLED black. */
@PreviewTest
@Preview(name = "light", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun ExitNodeMapLight() = io.github.bropines.tailscaled.ui.theme.TailSocksTheme(
    appTheme = "light", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = false
) { MapPicker(selectedIp = "100.64.1.10") }

/**
 * The list of a tailnet with Mullvad (#27): the tailnet's own nodes, then the countries, the one
 * in use opened — two cities, each with its nodes, and the line that picks the country's best.
 */
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Composable
fun ExitNodeListByCountry() = AdaptiveShowcase { MapPicker(selectedIp = "100.64.1.24", view = ExitNodePickerView.LIST) }

/** Only Mullvad's nodes, the country in use opened: its best, then each city with its own. */
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Composable
fun ExitNodeListCountryOpen() = AdaptiveShowcase {
    MapPicker(
        selectedIp = "100.64.1.24",
        nodes = mapNodes.filter { it.location?.countryCode in setOf("US", "GB", "SE", "NL") },
        view = ExitNodePickerView.LIST,
    )
}
