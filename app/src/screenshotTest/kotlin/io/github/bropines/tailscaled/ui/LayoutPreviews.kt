package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/**
 * The geometries every layout is checked in. Rendered on the build machine by
 * the Compose preview screenshot plugin; no device involved.
 */
@Preview(name = "phone", device = "spec:width=393dp,height=852dp,dpi=420")
@Preview(name = "phone-landscape", device = "spec:width=852dp,height=393dp,dpi=420")
@Preview(name = "foldable-open", device = "spec:width=673dp,height=841dp,dpi=420")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
// The author's Lenovo tablet, both ways: 800x1280 px at 213 dpi.
@Preview(name = "tablet-small-portrait", device = "spec:width=600dp,height=960dp,dpi=213")
@Preview(name = "tablet-small-landscape", device = "spec:width=960dp,height=600dp,dpi=213")
annotation class Geometries

private val sampleMenu = listOf(
    MenuEntry("Console", Icons.Default.PlayArrow) {},
    MenuEntry("Peers", Icons.Default.Share) {},
    MenuEntry("Logs", Icons.AutoMirrored.Filled.List) {},
    MenuEntry("Files", Icons.Default.Folder) {},
    MenuEntry("DNS", Icons.Default.Language) {},
    MenuEntry("Netcheck", Icons.Default.Refresh) {},
    MenuEntry("Settings", Icons.Default.Settings) {},
    MenuEntry("Serve", Icons.Default.Public) {},
)

@PreviewTest
@Geometries
@Composable
fun MenuGridPreview() {
    TailSocksTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MenuGrid(columns = 2, entries = sampleMenu, modifier = Modifier.padding(24.dp))
        }
    }
}
