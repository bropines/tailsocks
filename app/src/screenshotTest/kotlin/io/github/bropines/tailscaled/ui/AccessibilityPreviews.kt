package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

// Large system font: fixed heights and single-line labels are where layouts
// break first, and nobody tests at 200% by hand.

@PreviewTest
@Preview(name = "font150", device = "spec:width=411dp,height=891dp,dpi=420", fontScale = 1.5f)
@Preview(name = "font200", device = "spec:width=411dp,height=891dp,dpi=420", fontScale = 2f)
@Preview(name = "font200-ru", device = "spec:width=411dp,height=891dp,dpi=420", fontScale = 2f, locale = "ru")
@Composable
fun MainLargeFontPreview() = TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
    CompositionLocalProvider(LocalDemo provides DemoTailnet.data) {
        MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
    }
}
