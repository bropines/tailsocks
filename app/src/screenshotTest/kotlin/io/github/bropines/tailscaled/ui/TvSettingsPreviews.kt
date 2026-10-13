package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass

/*
 * Settings as a television shows them (SettingsActivity.kt, ExplainedRow.kt): the list of
 * sections ending in Advanced, the Advanced page with what left the hub and the main
 * screen, the pages behind it, and the sections that stayed. In English and in Russian.
 *
 * A render cannot press a key, so nothing here has the focus and no explanation is
 * unfolded by it; TvSettingsRowRevealed draws a row as it is once the remote has rested
 * on it, by handing it the answer the focus would have given.
 */

/** [TvSizes], and the same television in Russian. */
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class TvLanguages

@Composable
private fun TvSettings(section: String? = null) = AdaptiveShowcase(device = DeviceClass.TV) {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {},
        initialSection = section,
    )
}

/** The list of sections, Advanced last, beside the first of them. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsHub() = TvSettings()

/** Advanced: the pages that left the hub and the screens that left the main screen. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsAdvanced() = TvSettings("advanced")

/** Advanced → Theme and icon: what Appearance does not carry on a television. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsTheme() = TvSettings("theme")

/** Advanced → Root mode: Tunnel mode with Root among the modes. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsRoot() = TvSettings("root")

/** Advanced → Backup & restore. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsBackup() = TvSettings("backup")

/** Advanced → Diagnostics, without the links to Logs and Console that Advanced carries. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsDiagnostics() = TvSettings("diagnostics")

/** Tunnel mode in the hub: Proxy and VPN. */
@PreviewTest @TvLanguages @Composable
fun TvSettingsTunnel() = TvSettings("tunnel")

/** Sharing without the link to Serve, DNS without the link to its screen. */
@PreviewTest @TvSizes @Composable
fun TvSettingsSharing() = TvSettings("sharing")

@PreviewTest @TvSizes @Composable
fun TvSettingsDns() = TvSettings("dns")

@PreviewTest @TvSizes @Composable
fun TvSettingsAccount() = TvSettings("account")

/** A television that has a file picker and a browser changes nothing in this structure. */
@PreviewTest @TvSizes @Composable
fun TvSettingsHubWithPicker() = CompositionLocalProvider(LocalCapabilities provides Capabilities.ALL) { TvSettings() }

/**
 * Two rows as the remote finds them and as they are once it has rested on one: under a
 * value that is set, the description a handheld shows on a long press. The folded
 * explanation of the switch row unfolds the same way, a frame too late for a render:
 * it has to be laid out once to know that it is cut.
 */
@PreviewTest @TvSizes @Composable
fun TvSettingsRowRevealed() = AdaptiveShowcase(device = DeviceClass.TV) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        for (revealed in listOf(false, true)) {
            CompositionLocalProvider(LocalRowHelpRevealed provides revealed) {
                SettingsSwitchItem(
                    title = "Report the real OS",
                    subtitle = "The daemon tells the coordination server what this device is: Android, its model and version, the way the official client does. Off, it says Linux. A node is bound to the OS it registered with, so a registered profile is offered a new one instead of a change.",
                    icon = Icons.Default.Shield,
                    checked = false,
                    onCheckedChange = {}
                )
                SettingsEditItem(
                    title = "Advertised routes",
                    value = "192.168.1.0/24",
                    icon = Icons.Default.VpnKey,
                    description = "Subnets this device routes for the tailnet, comma-separated. They take effect once approved in the admin console.",
                    onSave = {}
                )
            }
        }
    }
}
