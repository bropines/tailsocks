package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The television interface as far as a render shows it (core/DeviceProfile.kt,
 * ui/DeviceClass.kt, ui/Focus.kt): the class handed in through AdaptiveShowcase, so the
 * theme insets the screen by the safe area, the top bar has no arrow and a 540dp-high
 * window is not a phone.
 *
 * A render cannot press a key, so nothing in it is ever focused. The ring is drawn by
 * hand — focusRing(focused = true), the same modifier a television runs — over one
 * element of each screen, at the place that element has in this window, and on the
 * app's own containers in every preset (TvFocusRing*): what has to be seen from a sofa.
 */

/**
 * [content] with the focus ring drawn over it, [width] × [height] at ([x], [y]) from the
 * corner of the safe area: one element as the remote would mark it.
 */
@Composable
private fun Focused(x: Dp, y: Dp, width: Dp, height: Dp, shape: Shape, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        Box(Modifier.offset(x, y).size(width, height).focusRing(true, shape))
    }
}

@Composable
private fun TvSettings(section: String) = SettingsScreen(
    onBack = {},
    currentTheme = "dark", onThemeChange = {},
    currentPreset = "emerald", onPresetChange = {},
    currentDynamicColor = false, onDynamicColorChange = {},
    currentAmoledMode = true, onAmoledModeChange = {},
    initialSection = section,
)

/** The dashboard, the remote on the status card — where it starts. */
@PreviewTest @TvSizes @Composable
fun TvDashboard() = AdaptiveShowcase(device = DeviceClass.TV) {
    Focused(24.dp, 164.dp, 328.dp, 130.dp, MaterialTheme.shapes.extraLarge) {
        MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
    }
}

/** Settings → Appearance with the Interface row, the remote on the first category. */
@PreviewTest @TvSizes @Composable
fun TvSettingsAppearance() = AdaptiveShowcase(device = DeviceClass.TV) {
    Focused(16.dp, 84.dp, 308.dp, 72.dp, MaterialTheme.shapes.medium) {
        TvSettings("appearance")
    }
}

private val presets = listOf("default", "lavender", "emerald", "sapphire", "amber", "monochrome", "tokionight")

/**
 * The ring on the four containers it has to show on: a card on the background, a
 * settings row, the chosen chip of a segmented row and a filled button — the last
 * two are where its outer line is lost and the inner one carries it.
 */
@Composable
private fun RingSpecimens(appTheme: String) {
    Column(Modifier.fillMaxSize()) {
        for (preset in presets) {
            TailSocksTheme(appTheme = appTheme, themePreset = preset, dynamicColorEnabled = false, amoledModeEnabled = false) {
                Row(
                    Modifier.fillMaxWidth().height(77.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(preset, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.width(110.dp))
                    MenuCard("Devices", Icons.Default.Devices, Modifier.width(150.dp).focusRing(true, MaterialTheme.shapes.large)) {}
                    // A settings row's own container (SettingsSwitchItem), which takes no modifier.
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.weight(1f).focusRing(true)
                    ) {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.NewReleases, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = true, onCheckedChange = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        ) { Text("Show what's new") }
                    }
                    Box(
                        Modifier.width(120.dp).height(38.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                            .focusRing(true),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Dark", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Button(onClick = {}, modifier = Modifier.focusRing(true, ButtonDefaults.shape)) { Text("Connect") }
                }
            }
        }
    }
}

@PreviewTest @TvSizes @Composable
fun TvFocusRingDark() = RingSpecimens("dark")

@PreviewTest @TvSizes @Composable
fun TvFocusRingLight() = RingSpecimens("light")
