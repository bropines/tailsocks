package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.NetmapCache

// Settings → Censorship bypass → Start without the server, in each thing the daemon can say.
// The whole section does not render (see TabletSettingsPreviews.kt), so the card stands alone.

private val verdicts = listOf(
    true to NetmapCache.Report(known = true, granted = true),
    true to NetmapCache.Report(known = true, granted = true, withoutControl = true),
    true to NetmapCache.Report(known = true, granted = false),
    false to null,
)

@Composable
private fun Cards(modifier: Modifier = Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    verdicts.forEach { (on, report) -> NetmapCacheCardContent(on, report, clearing = false, onToggle = {}, onClear = {}) }
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=1500dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=1500dp,dpi=420", locale = "ru")
@Composable
fun NetmapCacheCardPhone() = AdaptiveShowcase {
    Cards(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(16.dp)
    )
}

@PreviewTest
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
@Composable
fun NetmapCacheCardTablet() = AdaptiveShowcase {
    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            verdicts.take(2).forEach { (on, r) -> NetmapCacheCardContent(on, r, clearing = false, onToggle = {}, onClear = {}) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            verdicts.drop(2).forEach { (on, r) -> NetmapCacheCardContent(on, r, clearing = false, onToggle = {}, onClear = {}) }
        }
    }
}
