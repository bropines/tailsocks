package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

// The renderer cannot report a hinge, so these hand one in through
// LocalPreviewFold and draw it as a dark band: what matters is whether the
// layout's seam lands on the band and nothing important sits under it.
// Sizes are a Pixel Fold-class inner display; the hinge is a 13dp strip.

private val bookFold = Fold.Vertical(start = 330.dp, end = 343.dp)
private val tabletopFold = Fold.Horizontal(top = 330.dp, bottom = 343.dp)

@Composable
private fun WithFold(fold: Fold, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPreviewFold provides fold) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            val band = Color.Black.copy(alpha = 0.35f)
            when (fold) {
                is Fold.Vertical -> Box(
                    Modifier.offset(x = fold.start).width(fold.end - fold.start).fillMaxHeight().background(band)
                )
                is Fold.Horizontal -> Box(
                    Modifier.offset(y = fold.top).height(fold.bottom - fold.top).fillMaxWidth().background(band)
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun MainScreenBookPreview() = TailSocksTheme {
    WithFold(bookFold) { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }
}

@PreviewTest
@Preview(name = "fold-tabletop", device = "spec:width=841dp,height=673dp,dpi=420")
@Composable
fun MainScreenTabletopPreview() = TailSocksTheme {
    WithFold(tabletopFold) { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }
}

@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun SettingsScreenBookPreview() = TailSocksTheme {
    WithFold(bookFold) {
        SettingsScreen(
            onBack = {},
            currentTheme = "system", onThemeChange = {},
            currentPreset = "default", onPresetChange = {},
            currentDynamicColor = false, onDynamicColorChange = {},
            currentAmoledMode = false, onAmoledModeChange = {}
        )
    }
}
