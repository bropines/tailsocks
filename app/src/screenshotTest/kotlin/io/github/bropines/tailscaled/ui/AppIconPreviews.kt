package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppIcons
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The icon picker's content, without the sheet: every launcher icon drawn by
 * Android's own VectorDrawable renderer from the layers the app ships, which
 * is the check that the SVG → VectorDrawable conversion kept them intact.
 */

@Composable
private fun AppIconSample(dark: Boolean) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
            AppIconSheetContent(selected = AppIcons.DEFAULT, onPick = {})
        }
    }
}

@PreviewTest
@Preview(name = "icons-phone", device = "spec:width=393dp,height=1200dp,dpi=420")
@Preview(name = "icons-phone-ru", device = "spec:width=393dp,height=1200dp,dpi=420", locale = "ru")
@Composable
fun AppIconSheetLight() = AppIconSample(dark = false)

@PreviewTest
@Preview(name = "icons-phone-dark", device = "spec:width=393dp,height=1200dp,dpi=420")
@Composable
fun AppIconSheetDark() = AppIconSample(dark = true)

// Pickers stay bottom sheets on a tablet, held to Material's 640dp and 85% of the height;
// the renderer draws no sheet, so this is their content at that size, where the sheet sits.

@Composable
private fun AsTabletSheet(content: @Composable () -> Unit) = AdaptiveShowcase {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
            modifier = Modifier.width(640.dp)
        ) { content() }
    }
}

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletAppIconSheet() = AsTabletSheet { AppIconSheetContent(selected = AppIcons.DEFAULT, onPick = {}) }

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletPickerSheet() = AsTabletSheet {
    PickerSheetContent(
        title = "App language",
        options = listOf(
            PickerOption("sys", "System", Icons.Default.Settings, supporting = "Follows the device"),
            PickerOption("en", "English", Icons.Default.Language),
            PickerOption("ru", "Русский", Icons.Default.Translate),
        ),
        selected = "en",
        monospace = false,
        onRowClick = {}
    )
}
