package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppIcons

/*
 * Screens for the release notes on GitHub, in the look of the README shots (Showcase):
 * the ones the other preview files do not already draw that way. Each in English and
 * Russian, for the two halves of the notes.
 */

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseLicenses() = Showcase { LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}) }

@PreviewTest
@Preview(name = "en", device = "spec:width=411dp,height=1100dp,dpi=420", locale = "en")
@Preview(name = "ru", device = "spec:width=411dp,height=1100dp,dpi=420", locale = "ru")
@Composable
fun ReleaseAppIcons() = Showcase {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        AppIconSheetContent(selected = AppIcons.DEFAULT, onPick = {})
    }
}

/** The interface scale (Settings → Appearance) as it applies: the whole screen at [scale]. */
@Composable
private fun Scaled(scale: Float) = Showcase {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
        MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
    }
}

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseScale80() = Scaled(0.8f)

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseScale120() = Scaled(1.2f)
