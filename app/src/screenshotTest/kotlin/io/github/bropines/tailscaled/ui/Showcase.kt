package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * Showcase renders: the real screens fed a made-up tailnet through LocalDemo,
 * for the README and the store listings. Nothing here is anyone's real
 * network — names, addresses and keys are invented.
 */

/** A small, plausible tailnet: home machines, two exit nodes, some offline. */
object DemoTailnet {
    private fun peer(
        id: String, host: String, os: String, v4: String, v6: String,
        online: Boolean, relay: String = "fra", curAddr: String = "",
        exitNode: Boolean = false, exitOption: Boolean = false,
        lastSeen: String = "2026-09-30T08:12:00Z", tags: List<String> = emptyList(),
    ) = """
        "$id": {
          "ID": "$id", "UserID": 1, "HostName": "$host", "DNSName": "$host.tail4a2c9.ts.net.",
          "OS": "$os", "TailscaleIPs": ["$v4", "$v6"], "AllowedIPs": ["$v4/32", "$v6/128"],
          "CurAddr": "$curAddr", "Relay": "$relay", "Online": $online, "Active": $online,
          "ExitNode": $exitNode, "ExitNodeOption": $exitOption, "LastSeen": "$lastSeen",
          "RxBytes": ${if (online) 48_213_990 else 0}, "TxBytes": ${if (online) 9_402_551 else 0},
          "InNetworkMap": true, "InMagicSock": true, "InEngine": true,
          "TaildropTarget": ${if (online) 3 else 0},
          "Tags": [${tags.joinToString { "\"$it\"" }}]
        }
    """.trimIndent()

    val statusJson: String = """
    {
      "BackendState": "Running",
      "MagicDNSSuffix": "tail4a2c9.ts.net",
      "Self": {
        "ID": "nSELF7", "UserID": 1, "HostName": "pixel-9-pro", "DNSName": "pixel-9-pro.tail4a2c9.ts.net.",
        "OS": "android", "TailscaleIPs": ["100.101.34.12", "fd7a:115c:a1e0::6a01:220c"],
        "Online": true, "Active": true, "Relay": "fra"
      },
      "User": { "1": { "ID": 1, "LoginName": "alex@example.com", "DisplayName": "Alex" } },
      "Peer": {
        ${peer("n1", "desktop-home", "windows", "100.88.12.4", "fd7a:115c:a1e0::5801:c04", true, curAddr = "192.168.1.20:41641")},
        ${peer("n2", "homelab-nas", "linux", "100.72.5.101", "fd7a:115c:a1e0::4801:565", true, curAddr = "192.168.1.5:41641", exitOption = true)},
        ${peer("n3", "exit-frankfurt", "linux", "100.94.210.8", "fd7a:115c:a1e0::5e01:d208", true, curAddr = "203.0.113.24:41641", exitNode = true, exitOption = true, tags = listOf("tag:exit"))},
        ${peer("n4", "exit-helsinki", "linux", "100.66.18.77", "fd7a:115c:a1e0::4201:124d", true, relay = "hel", curAddr = "198.51.100.7:41641", exitOption = true, tags = listOf("tag:exit"))},
        ${peer("n5", "macbook-air", "macOS", "100.110.9.33", "fd7a:115c:a1e0::6e01:921", true, relay = "ams")},
        ${peer("n6", "raspberry-pi", "linux", "100.71.3.9", "fd7a:115c:a1e0::4701:309", true, curAddr = "192.168.1.31:41641", tags = listOf("tag:iot"))},
        ${peer("n7", "ipad", "iOS", "100.83.44.2", "fd7a:115c:a1e0::5301:2c02", false, lastSeen = "2026-09-29T21:40:00Z")},
        ${peer("n8", "steam-deck", "linux", "100.99.201.50", "fd7a:115c:a1e0::6301:c932", false, lastSeen = "2026-09-27T18:05:00Z")},
        ${peer("n9", "work-laptop", "windows", "100.77.160.14", "fd7a:115c:a1e0::4d01:a00e", false, lastSeen = "2026-09-26T17:30:00Z")}
      }
    }
    """.trimIndent()

    private fun ping(ms: Int) = """{"LatencySeconds": ${ms / 1000.0}}"""

    val pings = mapOf(
        "100.88.12.4" to ping(8),
        "100.72.5.101" to ping(12),
        "100.94.210.8" to ping(41),
        "100.66.18.77" to ping(58),
        "100.110.9.33" to ping(96),
        "100.71.3.9" to ping(15),
    )

    val data = DemoData(
        statusJson = statusJson,
        pings = pings,
        running = true,
        exitNodeName = "exit-frankfurt",
        exitNodeIp = "100.94.210.8",
    )
}

/** The look the screenshots are taken in: dark, the emerald preset, AMOLED black. */
@Composable
fun Showcase(content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
        CompositionLocalProvider(LocalDemo provides DemoTailnet.data) { content() }
    }
}

@PreviewTest
@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun PeersShowcase() = Showcase { PeersScreen(onBack = {}) }

@PreviewTest
@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "showcase-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
@Preview(name = "showcase-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun MainShowcase() = Showcase {
    MainScreen(showAccountSwitcher = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) })
}

@PreviewTest
@Preview(name = "showcase-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
@Preview(name = "showcase-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun SettingsShowcase() = Showcase {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {}
    )
}
