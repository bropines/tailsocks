package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// "Receive beta versions" under the update check, in both languages. A window as wide as a
// tablet on its side: there the Personalization card is two columns and its last rows are
// in the frame, which on a phone they are not until the page is scrolled.

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
@Composable
fun UpdateChannelRow() = AdaptiveShowcase {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {},
        initialSection = "appearance",
    )
}
