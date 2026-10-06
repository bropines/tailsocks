package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
