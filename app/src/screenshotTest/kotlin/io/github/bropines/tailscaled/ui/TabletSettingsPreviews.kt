package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

// Settings in every window size (AdaptivePreviews.kt); the screens around it are in
// TabletOnboardingPreviews.kt and LicensesPreviews.kt. The Phone* ones are what a layout
// change must leave as it was: compare them before and after.

@Composable
private fun Settings(section: String? = null) = SettingsScreen(
    onBack = {},
    currentTheme = "dark", onThemeChange = {},
    currentPreset = "emerald", onPresetChange = {},
    currentDynamicColor = false, onDynamicColorChange = {},
    currentAmoledMode = true, onAmoledModeChange = {},
    initialSection = section,
)

@PreviewTest @WindowSizes @Composable
fun TabletSettings() = AdaptiveShowcase { Settings() }

@PreviewTest @WindowSizes @Composable
fun TabletSettingsTunnel() = AdaptiveShowcase { Settings("tunnel") }

@PreviewTest @WindowSizes @Composable
fun TabletSettingsAccount() = AdaptiveShowcase { Settings("account") }

@PreviewTest @WindowSizes @Composable
fun TabletSettingsProxies() = AdaptiveShowcase { Settings("proxies") }

@PreviewTest @WindowSizes @Composable
fun TabletSettingsDiagnostics() = AdaptiveShowcase { Settings("diagnostics") }

/** The other sections where a tablet shows them: upright and on its side. */
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class TabletSizes

@PreviewTest @TabletSizes @Composable
fun TabletSettingsDns() = AdaptiveShowcase { Settings("dns") }

@PreviewTest @TabletSizes @Composable
fun TabletSettingsSharing() = AdaptiveShowcase { Settings("sharing") }

@PreviewTest @TabletSizes @Composable
fun TabletSettingsBackground() = AdaptiveShowcase { Settings("background") }

@PreviewTest @TabletSizes @Composable
fun TabletSettingsBackup() = AdaptiveShowcase { Settings("backup") }

@PreviewTest @TabletSizes @Composable
fun TabletSettingsAutomation() = AdaptiveShowcase { Settings("automation") }

/** A foldable open like a book: the categories on one half, a section on the other. */
@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun FoldSettingsBook() = AdaptiveShowcase {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { Settings("account") }
}

// Every section on a phone, upright and on its side (where Settings is two panes already).
// Not Censorship bypass: the renderer fails on that section (an NPE in its composition tree).

@PreviewTest @PhoneSizes @Composable
fun PhoneSettings() = AdaptiveShowcase { Settings() }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsAppearance() = AdaptiveShowcase { Settings("appearance") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsAccount() = AdaptiveShowcase { Settings("account") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsTunnel() = AdaptiveShowcase { Settings("tunnel") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsProxies() = AdaptiveShowcase { Settings("proxies") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsDns() = AdaptiveShowcase { Settings("dns") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsSharing() = AdaptiveShowcase { Settings("sharing") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsBackground() = AdaptiveShowcase { Settings("background") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsBackup() = AdaptiveShowcase { Settings("backup") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsAutomation() = AdaptiveShowcase { Settings("automation") }

@PreviewTest @PhoneSizes @Composable
fun PhoneSettingsDiagnostics() = AdaptiveShowcase { Settings("diagnostics") }
