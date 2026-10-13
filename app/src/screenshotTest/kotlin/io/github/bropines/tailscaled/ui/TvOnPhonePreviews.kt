package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass

/*
 * A phone with the TV interface switched on by hand (Settings → Appearance → Interface → TV),
 * upright and on its side, which is how it is tried out: the frame and the remote's behaviour
 * of a television in a window that is not one. The safe area is 5% of the window, so a phone
 * does not lose a sixth of its width to it; what must hold is that nothing clips or overlaps.
 */

@Preview(name = "1-phone-as-tv", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-landscape-as-tv", device = "spec:width=891dp,height=411dp,dpi=420")
annotation class PhoneAsTvSizes

@Composable
private fun TvOnPhone(content: @Composable () -> Unit) = AdaptiveShowcase(device = DeviceClass.TV, content = content)

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvHome() = TvOnPhone { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvSettingsHub() = TvOnPhone { PhoneAsTvSettings() }

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvSettingsAccount() = TvOnPhone { PhoneAsTvSettings("account") }

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvSettingsAdvanced() = TvOnPhone { PhoneAsTvSettings("advanced") }

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvPeers() = TvOnPhone { PeersScreen(onBack = {}) }

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvFiles() = TvOnPhone { FilesScreen(onBack = {}, openTaildrop = true) }

@PreviewTest @PhoneAsTvSizes @Composable
fun PhoneAsTvSignIn() = TvOnPhone {
    TvSignInScreen(account = "Default", url = "https://login.tailscale.com/a/1a2b3c4d5e6f7", slow = false, renewing = false, error = null, onNewCode = {}, onClose = {})
}

@Composable
private fun PhoneAsTvSettings(section: String? = null) = SettingsScreen(
    onBack = {},
    currentTheme = "dark", onThemeChange = {},
    currentPreset = "emerald", onPresetChange = {},
    currentDynamicColor = false, onDynamicColorChange = {},
    currentAmoledMode = true, onAmoledModeChange = {},
    initialSection = section,
)
