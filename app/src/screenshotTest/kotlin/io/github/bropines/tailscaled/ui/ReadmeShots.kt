package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The screenshots in the README and the store listings, rendered from the
 * made-up tailnet in Showcase.kt, once in English and once in Russian.
 * docs/screenshots/ holds the copies that are published; regenerate with
 * ./gradlew :app:updateDebugScreenshotTest and scripts/readme-shots.py.
 */

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"
private const val PHONE_LANDSCAPE = "spec:width=891dp,height=411dp,dpi=420"
private const val TABLET = "spec:width=1280dp,height=800dp,dpi=240"
private const val FOLD = "spec:width=673dp,height=841dp,dpi=420"

private val relayProblem = """
    [{"Code": "no-derp-connection", "Title": "No DERP connection",
      "Text": "Tailscale could not connect to any relay server. Check your internet connection.",
      "Severity": "medium", "ImpactsConnectivity": true, "BrokenSinceMs": 0}]
""".trimIndent()

@Composable
private fun Shot(data: DemoData = DemoTailnet.data, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
        CompositionLocalProvider(LocalDemo provides data) { content() }
    }
}

@Composable
private fun Main() = MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeMain() = Shot { Main() }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeMainProblem() = Shot(DemoTailnet.data.copy(healthJson = relayProblem)) { Main() }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmePeers() = Shot { PeersScreen(onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeNetcheck() = Shot(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy)) { NetcheckScreen(onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE_LANDSCAPE, locale = "en")
@Preview(name = "ru", device = PHONE_LANDSCAPE, locale = "ru")
@Composable
fun ReadmeSettingsWide() = Shot {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {}
    )
}

@PreviewTest
@Preview(name = "en", device = TABLET, locale = "en")
@Preview(name = "ru", device = TABLET, locale = "ru")
@Composable
fun ReadmeTablet() = Shot { Main() }

@PreviewTest
@Preview(name = "en", device = FOLD, locale = "en")
@Preview(name = "ru", device = FOLD, locale = "ru")
@Composable
fun ReadmeFold() = Shot {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { Main() }
}
