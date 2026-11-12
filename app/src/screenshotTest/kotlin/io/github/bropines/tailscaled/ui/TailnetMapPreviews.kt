package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.TrafficSnapshot
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The tailnet map and the main screen's speed graph, on the made-up tailnet: three peers with
 * traffic (LiveTraffic.kt's demo rates), one behind a DERP relay, one behind a peer relay, one
 * nothing has talked to yet, three offline — and on a tailnet of three hundred nodes, most of
 * them exit nodes nobody uses, where the window holds a few dozen and the rest are "+N more".
 */

/** DemoTailnet with raspberry-pi behind a peer relay and exit-helsinki not yet talked to. */
private val mapData = DemoTailnet.data.copy(
    statusJson = DemoTailnet.statusJson
        .replace("\"CurAddr\": \"192.168.1.31:41641\"", "\"CurAddr\": \"\", \"PeerRelay\": \"203.0.113.9:7777:vni:5\"")
        .replace("\"CurAddr\": \"198.51.100.7:41641\"", "\"CurAddr\": \"\", \"LastWrite\": \"0001-01-01T00:00:00Z\""),
)

private val mapFacts = PeerPathFacts(
    derpCities = mapOf("fra" to "Frankfurt", "ams" to "Amsterdam", "hel" to "Helsinki"),
    relayServers = setOf("100.72.5.101"),
)

/** The made-up tailnet with 290 Mullvad exit nodes added: online, never talked to. */
private val largeData = mapData.copy(
    statusJson = mapData.statusJson!!.replace(
        "\"Peer\": {",
        "\"Peer\": {" + (1..290).joinToString("") { i ->
            val name = "%s-%s-wg-%03d".format(listOf("se", "de", "us", "jp", "nl")[i % 5], listOf("sto", "fra", "nyc", "tyo", "ams")[i % 5], i)
            """
            "m$i": {
              "ID": "m$i", "UserID": 2, "HostName": "$name", "DNSName": "$name.mullvad.ts.net.", "OS": "linux",
              "TailscaleIPs": ["100.${64 + i / 250}.${i % 250}.1"], "CurAddr": "", "Relay": "",
              "Online": true, "Active": false, "ExitNodeOption": true, "LastWrite": "0001-01-01T00:00:00Z",
              "RxBytes": 0, "TxBytes": 0
            },"""
        }
    )
)

@Composable
private fun Map(data: DemoData = mapData, device: DeviceClass? = null, content: @Composable () -> Unit) =
    AdaptiveShowcase(data, device) {
        CompositionLocalProvider(LocalDemoPeerPathFacts provides mapFacts) { content() }
    }

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "1-phone-small", device = "spec:width=360dp,height=640dp,dpi=420")
@Preview(name = "1-phone-large-font", device = "spec:width=411dp,height=891dp,dpi=420", fontScale = 1.5f)
@Composable
fun TailnetMapPhone() = Map { TailnetMapScreen(onBack = {}) }

@PreviewTest
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TailnetMapTablet() = Map { TailnetMapScreen(onBack = {}) }

@PreviewTest @TvBothLanguages @Composable
fun TailnetMapTv() = Map(device = DeviceClass.TV) { TailnetMapScreen(onBack = {}) }

// Three hundred peers: the linked ones and the busy ones drawn, the rest behind the button.
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TailnetMapLarge() = Map(largeData) { TailnetMapScreen(onBack = {}) }

@PreviewTest @TvBothLanguages @Composable
fun TailnetMapLargeTv() = Map(largeData, DeviceClass.TV) { TailnetMapScreen(onBack = {}) }

// One peer, and nobody at all.
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun TailnetMapOnePeer() = Map(
    mapData.copy(statusJson = mapData.statusJson!!.substringBefore("\"n2\":").trimEnd().trimEnd(',') + "}}")
) { TailnetMapScreen(onBack = {}) }

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun TailnetMapStopped() = Map(mapData.copy(running = false)) { TailnetMapScreen(onBack = {}) }

// The light theme with another preset: every colour on the map is the theme's.
@PreviewTest
@Preview(name = "1-phone-light", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun TailnetMapLight() = TailSocksTheme(appTheme = "light", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = false) {
    CompositionLocalProvider(LocalDemo provides mapData, LocalDemoPeerPathFacts provides mapFacts) {
        TailnetMapScreen(onBack = {})
    }
}

// The speed graph as the main screen has it: busy, connected but idle, and with the service stopped.
@Composable
private fun Graphs() = Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {
    val busy = demoLiveTraffic(mapData)
    SpeedGraphCard(busy, Modifier.fillMaxWidth())
    SpeedGraphCard(busy.copy(traffic = TrafficSnapshot(atMs = busy.traffic.atMs, measured = true)), Modifier.fillMaxWidth())
    SpeedGraphCard(demoLiveTraffic(mapData.copy(running = false)), Modifier.fillMaxWidth())
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=520dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=520dp,dpi=420", locale = "ru")
@Preview(name = "1-phone-small-large-font", device = "spec:width=360dp,height=640dp,dpi=420", fontScale = 1.5f)
@Composable
fun SpeedGraphPhone() = Map { Graphs() }

@PreviewTest
@Preview(name = "1-phone-light", device = "spec:width=411dp,height=520dp,dpi=420")
@Composable
fun SpeedGraphLight() = TailSocksTheme(appTheme = "light", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = false) {
    Graphs()
}
