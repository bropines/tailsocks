package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

// Settings → Censorship bypass → Control connection, on and off.
// The whole section does not render (see TabletSettingsPreviews.kt), so the card stands alone.

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=700dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=700dp,dpi=420", locale = "ru")
@Composable
fun ControlPortCardPhone() = AdaptiveShowcase {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ControlPortCardContent(tlsOnly = true, onToggle = {})
        ControlPortCardContent(tlsOnly = false, onToggle = {})
    }
}
