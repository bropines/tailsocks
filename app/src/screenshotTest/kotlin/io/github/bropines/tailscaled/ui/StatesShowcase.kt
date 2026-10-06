package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminApiMainScreen
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The states a screen is in besides "showing its data": the service stopped, a
 * list with nothing in it, a search that matches nothing — and the peer rows'
 * path and last-seen reading. Same look as Showcase.kt: dark, emerald, AMOLED.
 */

@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
annotation class StatesPhone

@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "showcase-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class StatesPhoneBothLanguages

/**
 * DemoTailnet with one more kind of path in it: raspberry-pi reached through a
 * peer relay instead of directly, so a single screen shows all three — direct
 * (desktop-home), DERP (macbook-air, "ams") and the peer relay.
 */
private val pathsTailnet = DemoTailnet.data.copy(
    statusJson = DemoTailnet.statusJson.replace(
        "\"CurAddr\": \"192.168.1.31:41641\"",
        "\"CurAddr\": \"\", \"PeerRelay\": \"100.72.5.101:40000\""
    )
)

/** A tailnet of one: this device and nobody else. */
private val aloneTailnet = DemoData(
    statusJson = """
    {
      "BackendState": "Running",
      "MagicDNSSuffix": "tail4a2c9.ts.net",
      "Self": {
        "ID": "nSELF7", "UserID": 1, "HostName": "pixel-9-pro", "DNSName": "pixel-9-pro.tail4a2c9.ts.net.",
        "OS": "android", "TailscaleIPs": ["100.101.34.12", "fd7a:115c:a1e0::6a01:220c"],
        "Online": true, "Active": true, "Relay": "fra"
      },
      "Peer": {}
    }
    """.trimIndent()
)

private val stopped = DemoTailnet.data.copy(running = false, exitNodeIp = null)

@Composable
private fun States(data: DemoData, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
        CompositionLocalProvider(LocalDemo provides data) { content() }
    }
}

// Peers: every row now says how it is reached, or when it was last seen.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersPathsShowcase() = States(pathsTailnet) { PeersScreen(onBack = {}) }

// A search that keeps a mix: this device, a direct peer, the peer relay, two offline.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersLastSeenShowcase() = States(pathsTailnet) { PeersScreen(onBack = {}, initialQuery = "p") }

@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersNoMatchShowcase() = States(DemoTailnet.data) { PeersScreen(onBack = {}, initialQuery = "printer") }

@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersAloneShowcase() = States(aloneTailnet) { PeersScreen(onBack = {}) }

// The tabs, and the search a pull at the top brings out, working within the tab: Online,
// searched by OS. The counts on the tabs are the search's hits in each.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersTabsSearchShowcase() = States(pathsTailnet) {
    PeersScreen(onBack = {}, initialQuery = "linux", initialTab = PeerTab.ONLINE)
}

// A tab the search leaves empty keeps its place, with its 0 and the way back.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersTabNoMatchShowcase() = States(DemoTailnet.data) {
    PeersScreen(onBack = {}, initialQuery = "exit", initialTab = PeerTab.OFFLINE)
}

// Without a search the field is folded away; the tab row stays.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersExitTabShowcase() = States(DemoTailnet.data) {
    PeersScreen(onBack = {}, initialTab = PeerTab.EXIT_NODES)
}

// Stopped: one line and a Start button, the same on every screen that needs the daemon.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeersStoppedShowcase() = States(stopped) { PeersScreen(onBack = {}) }

@PreviewTest @StatesPhone @Composable
fun DnsStoppedShowcase() = States(stopped) { DnsScreen(onBack = {}) }

@PreviewTest @StatesPhone @Composable
fun ServeStoppedShowcase() = States(stopped) { ServeScreen(onBack = {}) }

// With no tailnet known yet: Start, or type the name in.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun AdminStoppedShowcase() = States(stopped) { AdminApiMainScreen(onBack = {}) }

@PreviewTest @StatesPhoneBothLanguages @Composable
fun LogsEmptyShowcase() = States(DemoTailnet.data) { LogsScreen(onBack = {}) }

// Logs has no demo feed, so the filtered-empty state is drawn on its own.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun LogsCategoryEmptyShowcase() = States(DemoTailnet.data) {
    Surface(Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Default.FilterAltOff,
            text = stringResource(R.string.state_logs_category_empty, "ERROR"),
            actionLabel = stringResource(R.string.state_show_all),
            onAction = {}
        )
    }
}
