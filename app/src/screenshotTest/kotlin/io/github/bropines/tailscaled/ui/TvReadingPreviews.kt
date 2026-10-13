package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass

/*
 * The reading screens on a television (ui/TvReading.kt): the logs, the console, the netcheck
 * report, the licences, the live state card, the peers and their details, DNS — at 960 × 540 dp,
 * in English and Russian. A render cannot press a key, so the ring a remote draws over the pane
 * it is on is drawn by hand, as in TvFoundationPreviews.
 */

/** A television in both of the app's languages. */
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class TvSizesEnRu

/** [content] with the ring over a [width] × [height] element at ([x], [y]) from the corner of the safe area. */
@Composable
private fun Ringed(x: Dp, y: Dp, width: Dp, height: Dp, shape: Shape, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        Box(Modifier.offset(x, y).size(width, height).focusRing(true, shape))
    }
}

private val tvLogLines = listOf(
    "14:02:11 [INFO] [CORE] Service started: proxy mode, SOCKS5 127.0.0.1:1055",
    "14:02:12 [INFO] [TAILSCALE] control: netmap: 9 peers, 6 online",
    "14:02:14 [WARN] [TAILSCALE] magicsock: derp-4: connection lost, reconnecting",
    "14:02:19 [ERROR] [CORE] Taildrop: send of holiday-video.mp4 to ipad failed: HTTP 502: no answer from the peer",
    "14:02:20 [INFO] [OTHER] DPI bypass: split the first TLS record for 3 hosts",
    "14:02:22 [INFO] [TAILSCALE] netcheck: report: udp=true v6=false v4a=203.0.113.24:41641 derp=12",
    "14:02:25 [INFO] [CORE] SOCKS5 127.0.0.1:1055: connect 100.72.5.101:445 (homelab-nas) ok in 14ms",
    "14:02:28 [INFO] [TAILSCALE] magicsock: disco: node [n2Pq9] d:a0c3 now using 192.168.1.5:41641 mtu=1360",
    "14:02:40 [WARN] [CORE] Exit node exit-frankfurt: 41ms over DERP fra, no direct path yet",
    "14:02:44 [INFO] [CORE] Exit node exit-frankfurt: direct, 18ms",
    "14:03:10 [INFO] [TAILSCALE] taildrop: received IMG_20261007_143205.jpg (3.3 MB) from macbook-air",
    "14:03:41 [INFO] [TAILSCALE] health(warnable=update-available): ok",
    "14:04:30 [ERROR] [TAILSCALE] magicsock: ReceiveIPv6: udp6 socket closed: network is unreachable",
    "14:05:00 [INFO] [TAILSCALE] magicsock: derp-4 connected; connGen=1",
    "14:05:40 [INFO] [TAILSCALE] control: netmap: 9 peers, 6 online",
)

@PreviewTest @TvSizesEnRu @Composable
fun TvLogs() = AdaptiveShowcase(DemoTailnet.data.copy(logLines = tvLogLines), device = DeviceClass.TV) {
    // The log pane: where the remote starts on this screen.
    Ringed(4.dp, 121.dp, 773.dp, 364.dp, MaterialTheme.shapes.medium) { LogsScreen(onBack = {}) }
}

private val tvConsole = ConsoleDemo(
    scrollback = buildString {
        append("$ tailscale status\n")
        append("100.101.34.12  pixel-9-pro     bropines@  android  -\n")
        append("100.88.12.4    desktop-home    bropines@  windows  active; direct 192.168.1.20:41641\n")
        append("100.72.5.101   homelab-nas     bropines@  linux    active; direct 192.168.1.5:41641\n")
        append("100.94.210.8   exit-frankfurt  tagged-devices linux active; offers exit node\n")
        append("$ tailscale ping 100.88.12.4\n")
        append("pong from desktop-home (100.88.12.4) via 192.168.1.20:41641 in 8ms\n")
        append("$ ")
    },
    history = listOf("netcheck", "ping 100.88.12.4", "status"),
    presets = listOf("exit-node list"),
)

@PreviewTest @TvSizesEnRu @Composable
fun TvConsole() = AdaptiveShowcase(device = DeviceClass.TV) {
    CompositionLocalProvider(LocalConsoleDemo provides tvConsole) { ConsoleScreen(initialCmd = "", onBack = {}) }
}

@PreviewTest @TvSizesEnRu @Composable
fun TvNetcheck() = AdaptiveShowcase(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy), device = DeviceClass.TV) {
    NetcheckScreen(onBack = {})
}

@PreviewTest @TvSizesEnRu @Composable
fun TvLicenses() = AdaptiveShowcase(device = DeviceClass.TV) {
    LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}, initialOpen = "go/github.com/aws/aws-sdk-go-v2")
}

@PreviewTest @TvSizesEnRu @Composable
fun TvPeers() = AdaptiveShowcase(device = DeviceClass.TV) { PeersScreen(onBack = {}) }

/** A peer opened: its details in the pane. */
@PreviewTest @TvSizesEnRu @Composable
fun TvPeersPicked() = AdaptiveShowcase(device = DeviceClass.TV) { PeersScreen(onBack = {}, initialPeerId = "n3") }

/** The device's own search is not offered on a television: a query in the intent is ignored. */
@PreviewTest @TvSizesEnRu @Composable
fun TvPeersNoSearch() = AdaptiveShowcase(device = DeviceClass.TV) { PeersScreen(onBack = {}, initialQuery = "exit") }

/** MagicDNS on, three split routes (one tested fine, one timing out), a lookup made. */
private val tvDns = DemoDns(
    statusJson = """
    {
      "TailscaleDNS": true,
      "CurrentTailnet": {"MagicDNSEnabled": true, "MagicDNSSuffix": "tail4a2c9.ts.net", "SelfDNSName": "tv-living-room.tail4a2c9.ts.net"},
      "SplitDNSRoutes": {
        "tail4a2c9.ts.net.": [{"Addr": "100.100.100.100"}],
        "home.arpa.": [{"Addr": "192.168.1.1"}, {"Addr": "100.72.5.101"}],
        "corp.example.com.": [{"Addr": "10.20.0.53"}]
      }
    }
    """.trimIndent(),
    lookupDomain = "homelab-nas.tail4a2c9.ts.net",
    lookupResult = "100.72.5.101",
    localTest = "Success via Tailscale daemon (latency: 14 ms)\n100.72.5.101",
    routeTests = mapOf(
        "home.arpa._192.168.1.1" to "Success (reply: 61 bytes, latency: 3 ms)",
        "corp.example.com._10.20.0.53" to "Failed: timeout",
    ),
)

/** No copy buttons, bigger values and test buttons; the lookup is a button that opens its field. */
@PreviewTest @TvSizesEnRu @Composable
fun TvDns() = AdaptiveShowcase(device = DeviceClass.TV) {
    CompositionLocalProvider(LocalDemoDns provides tvDns) { DnsScreen(onBack = {}) }
}

/** The live state card as the dashboard shows it: filling the height it is given, which it scrolls. */
@PreviewTest @TvSizesEnRu @Composable
fun TvLiveDiagnostics() = AdaptiveShowcase(device = DeviceClass.TV) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 48.dp, vertical = 27.dp)) {
            LiveDiagnosticsCard(DemoLive.troubled, modifier = Modifier.size(420.dp, 300.dp), fillHeight = true)
        }
    }
}

/** What a peer's "Show as…" holds: the forms to read, the value large and its code. */
@PreviewTest @TvSizesEnRu @Composable
fun TvValueDialog() = AdaptiveShowcase(device = DeviceClass.TV) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        ValueDialogContent(
            title = "homelab-nas",
            values = listOf(
                ShownValue("MagicDNS name", "homelab-nas", qr = false),
                ShownValue("Full MagicDNS name", "homelab-nas.tail4a2c9.ts.net", qr = true),
                ShownValue("IPv4 address", "100.72.5.101", qr = true),
                ShownValue("SSH", "ssh homelab-nas.tail4a2c9.ts.net", qr = false),
            ),
            selected = 2,
            onSelect = {},
            strings = rememberShownValueStrings(),
            onDismiss = {}
        )
    }
}
