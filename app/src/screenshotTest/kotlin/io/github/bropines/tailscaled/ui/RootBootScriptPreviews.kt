package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.RootBootScript

// Settings → Tunnel mode → Root → "Autostart Magisk/KernelSU Service", in each state the
// installed copy can be in. The Root section only renders on a rooted device with Root Mode
// on, which a render is not, so the switch and its row stand alone here.

private val states = listOf(
    RootBootScript.State(RootBootScript.Status.CURRENT, "5.0.0") to null,
    RootBootScript.State(RootBootScript.Status.OUTDATED, "4.10.2") to null,
    RootBootScript.State(RootBootScript.Status.OUTDATED, null) to null,
    RootBootScript.State(RootBootScript.Status.CURRENT, "5.0.0") to R.string.settings_root_script_reinstalled,
)

@Composable
private fun Rows(modifier: Modifier = Modifier) = Column(modifier) {
    states.forEachIndexed { i, (state, status) ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        SettingsSwitchItem(
            title = stringResource(R.string.settings_root_service_title),
            subtitle = stringResource(R.string.settings_root_service_desc),
            icon = Icons.Default.Build,
            checked = true,
            onCheckedChange = {}
        )
        BootScriptReinstallRow(
            script = state,
            reinstallStatus = status?.let { stringResource(it) },
            reinstallOk = true,
            onReinstall = {}
        )
    }
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=900dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=900dp,dpi=420", locale = "ru")
@Composable
fun RootBootScriptRowPhone() = AdaptiveShowcase {
    Rows(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical = 16.dp))
}
