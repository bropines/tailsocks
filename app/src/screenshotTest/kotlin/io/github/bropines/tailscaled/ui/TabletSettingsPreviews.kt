package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.android.tools.screenshot.PreviewTest

// Settings and the forms around it in every window size (AdaptivePreviews.kt).

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
fun TabletPermissions() = AdaptiveShowcase { PermissionsScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletTunExcludedApps() = AdaptiveShowcase { TunExcludedAppsScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletFirstStart() = AdaptiveShowcase { FirstStartScreen(onFinished = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletLicenses() = AdaptiveShowcase { LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}) }
