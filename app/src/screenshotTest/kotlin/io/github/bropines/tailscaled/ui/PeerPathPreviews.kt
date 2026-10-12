package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.models.StatusResponse

/*
 * How each peer is reached, in the made-up tailnet: direct on the LAN (desktop-home) and over
 * the internet (exit-frankfurt), a DERP relay (macbook-air, Amsterdam), a peer relay
 * (raspberry-pi, through homelab-nas) and a peer nothing has talked to yet (exit-helsinki) —
 * on the Peers list, in a peer's details and on the large window's device rows.
 */

/** DemoTailnet with raspberry-pi behind a peer relay and exit-helsinki not yet talked to. */
private val pathsData = DemoTailnet.data.copy(
    statusJson = DemoTailnet.statusJson
        .replace("\"CurAddr\": \"192.168.1.31:41641\"", "\"CurAddr\": \"\", \"PeerRelay\": \"203.0.113.9:7777:vni:5\"")
        .replace("\"CurAddr\": \"198.51.100.7:41641\"", "\"CurAddr\": \"\", \"LastWrite\": \"0001-01-01T00:00:00Z\""),
    // exit-helsinki's ping came back without saying which way; macbook-air's through Amsterdam.
    pings = DemoTailnet.pings + mapOf(
        "100.66.18.77" to """{"LatencySeconds": 0.058}""",
        "100.110.9.33" to """{"LatencySeconds": 0.096, "DERPRegionCode": "ams"}""",
    ),
)

/** The daemon's DERP map, and homelab-nas as the one peer relay this device may use. */
private val pathFacts = PeerPathFacts(
    derpCities = mapOf("fra" to "Frankfurt", "ams" to "Amsterdam", "hel" to "Helsinki"),
    relayServers = setOf("100.72.5.101"),
)

@Composable
private fun Paths(content: @Composable () -> Unit) = AdaptiveShowcase(pathsData) {
    CompositionLocalProvider(LocalDemoPeerPathFacts provides pathFacts) { content() }
}

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class PathPhones

@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class PathPanes

// The list: every row says how it is reached — the endpoint, the relay node, the city where
// the row has the width for it.
@PreviewTest @PathPhones @Composable
fun PeerPathsPhone() = Paths { PeersScreen(onBack = {}) }

@PreviewTest
@Preview(name = "1-phone-small", device = "spec:width=360dp,height=740dp,dpi=420")
@Preview(name = "1-phone-large-font", device = "spec:width=411dp,height=891dp,dpi=420", fontScale = 1.5f)
@Composable
fun PeerPathsNarrow() = Paths { PeersScreen(onBack = {}) }

// A peer's details beside the list, one per kind of path.
@PreviewTest @PathPanes @Composable
fun PeerPathPeerRelay() = Paths { PeersScreen(onBack = {}, initialPeerId = "n6") }

@PreviewTest @PathPanes @Composable
fun PeerPathDerp() = Paths { PeersScreen(onBack = {}, initialPeerId = "n5") }

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun PeerPathDirectLan() = Paths { PeersScreen(onBack = {}, initialPeerId = "n1") }

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun PeerPathNotYet() = Paths { PeersScreen(onBack = {}, initialPeerId = "n4") }

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun PeerPathSelf() = Paths { PeersScreen(onBack = {}, initialPeerId = "nSELF7") }

/** The details at a phone's width: what the sheet holds, drawn as a pane (the renderer does
 *  not draw a sheet). */
@PreviewTest @PathPhones @Composable
fun PeerPathDetailsPhone() = Paths {
    val status = remember { AppJson.decodeFromString<StatusResponse>(pathsData.statusJson!!) }
    val peers = remember { listablePeers(status) }
    val relayed = peers.first { it.id == "n6" }
    CompositionLocalProvider(LocalPeerPathNames provides PeerPathNames(peers, pathFacts)) {
        Surface(Modifier.fillMaxSize()) {
            PeerDetailsModal(
                peerAt = { if (it == 0) PeerPage(relayed, isSelf = false) else null },
                selfAddress = "100.101.34.12",
                onDismiss = {},
                inPane = true
            )
        }
    }
}

// The large window's device rows, with the same words and icons.
@PreviewTest
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
@Composable
fun PeerPathDashboard() = Paths { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }
