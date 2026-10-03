package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

// Whole screens, rendered as they are. The renderer has no daemon and no
// native bridge, so each shows its empty or not-running state — which is
// exactly the state whose layout is easiest to get wrong and hardest to reach
// on a device where the service is up.

@PreviewTest @Geometries @Composable
fun MainScreenPreview() = TailSocksTheme { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }

@PreviewTest @Geometries @Composable
fun PeersScreenPreview() = TailSocksTheme { PeersScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun NetcheckScreenPreview() = TailSocksTheme { NetcheckScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun DnsScreenPreview() = TailSocksTheme { DnsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun LogsScreenPreview() = TailSocksTheme { LogsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun ConsoleScreenPreview() = TailSocksTheme { ConsoleScreen(initialCmd = "", onBack = {}) }

@PreviewTest @Geometries @Composable
fun FilesScreenPreview() = TailSocksTheme { FilesScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun ServeScreenPreview() = TailSocksTheme { ServeHost(startTab = 0, onBack = {}) }

@PreviewTest @Geometries @Composable
fun TailcatScreenPreview() = TailSocksTheme { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @Geometries @Composable
fun TaildriveScreenPreview() = TailSocksTheme { TaildriveScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun PermissionsScreenPreview() = TailSocksTheme { PermissionsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun TunExcludedAppsScreenPreview() = TailSocksTheme { TunExcludedAppsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun SettingsScreenPreview() = TailSocksTheme {
    SettingsScreen(
        onBack = {},
        currentTheme = "system", onThemeChange = {},
        currentPreset = "default", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = false, onAmoledModeChange = {}
    )
}

@PreviewTest @Geometries @Composable
fun AdminApiScreenPreview() = TailSocksTheme { io.github.bropines.tailscaled.admin.AdminApiMainScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun FirstStartScreenPreview() = TailSocksTheme { FirstStartScreen(onFinished = {}) }
