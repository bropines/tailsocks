package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.Diagnostics
import io.github.bropines.tailscaled.models.parseHealthWarnings
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The live state card of Settings → Diagnostics, fed invented states. Health
 * codes are the daemon's real ones; addresses are loopback or from the
 * documentation ranges, and the log lines are made up in the daemon's style.
 */

object DemoLive {
    private val now = System.currentTimeMillis()

    /** Everything as it should be: TUN on hev, both proxies up, one live relay. */
    val healthy = Diagnostics.Live(
        atMs = now,
        rootMode = false,
        alive = true,
        attached = true,
        sinceMs = now - (2 * 3600 + 14 * 60) * 1000L,
        wanted = true,
        backend = "Running",
        health = emptyList(),
        engine = Diagnostics.Engine(rxBytes = 289_283_940, txBytes = 56_415_306, livePeers = 6, liveRelays = 1),
        relayKickMs = 0,
        network = Diagnostics.Net("wlan0", "wifi", validated = true),
        socks5 = "127.0.0.1:48115",
        socksProbe = Diagnostics.Probe(listening = true, atMs = now),
        http = "127.0.0.1:8118",
        tunMode = true,
        tunEngine = "hev",
        vpnUp = true,
        rootTun = false,
        errors = emptyList(),
    )

    /**
     * A carrier network that lost its internet: no validation, no relay although the
     * recovery just reconnected them, the daemon complaining — and the SOCKS5 port
     * taken by something else, which the last log line says in so many words.
     */
    val troubled = healthy.copy(
        health = parseHealthWarnings(
            """
            [{"Code": "no-derp-connection", "Severity": "medium", "ImpactsConnectivity": true, "BrokenSinceMs": ${now - 4 * 60_000}},
             {"Code": "update-available", "Severity": "low", "ImpactsConnectivity": false, "BrokenSinceMs": 0}]
            """.trimIndent()
        ),
        engine = Diagnostics.Engine(rxBytes = 1_904_113, txBytes = 822_590, livePeers = 1, liveRelays = 0),
        relayKickMs = now - 38_000,
        network = Diagnostics.Net("rmnet_data1", "cellular", validated = false),
        socksProbe = Diagnostics.Probe(listening = false, atMs = now - 25_000),
        http = "",
        errors = listOf(
            Diagnostics.ErrorLine("08:13:30", "TAILSCALE", "SOCKS5 listener failed: listen tcp 127.0.0.1:48115: bind: address already in use"),
            Diagnostics.ErrorLine("08:12:57", "TAILSCALE", "magicsock: derp-4 connection failed: context deadline exceeded"),
            Diagnostics.ErrorLine("08:12:41", "TAILSCALE", "derphttp.Client.Recv: connect to region 4 (fra) failed: dial tcp 203.0.113.9:443: i/o timeout"),
            Diagnostics.ErrorLine("08:12:40", "ERROR", "Relay recovery: reconnecting the relays failed: context deadline exceeded"),
        ),
    )
}

@Composable
private fun CardOnScreen(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@PreviewTest
@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun LiveDiagnosticsShowcase() = Showcase { CardOnScreen { LiveDiagnosticsCard(DemoLive.healthy) } }

/** Also in Russian, whose labels and values are the longer ones. */
@PreviewTest
@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "showcase-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Composable
fun LiveDiagnosticsTroubledShowcase() = Showcase { CardOnScreen { LiveDiagnosticsCard(DemoLive.troubled) } }

/** The light default theme, where the error and tertiary roles are the hardest to read. */
@PreviewTest
@Preview(name = "light-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun LiveDiagnosticsTroubledLightPreview() = TailSocksTheme(
    appTheme = "light", themePreset = "default", dynamicColorEnabled = false, amoledModeEnabled = false
) {
    CardOnScreen { LiveDiagnosticsCard(DemoLive.troubled) }
}

/** The page it lives on, opened the way a health banner opens it; the card reads DemoTailnet. */
@PreviewTest
@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun SettingsDiagnosticsShowcase() = Showcase {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {},
        initialSection = "diagnostics"
    )
}
