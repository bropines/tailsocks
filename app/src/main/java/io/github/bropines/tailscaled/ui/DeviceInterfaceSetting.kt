package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceOverride
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.SlidingSegmentedChips

/**
 * Settings → Appearance → Interface: the interface this device gets — the one
 * it asks for, a handheld's, or a television's (core/DeviceProfile.kt).
 *
 * The television's can be turned on by hand anywhere, which is how it is tried
 * on a phone, and turned off on a television that would rather be a tablet.
 * The choice is applied as it is made: the theme of every open screen listens
 * for it (rememberDeviceClass) and rebuilds the screen for the other class, so
 * no activity is restarted — and Settings comes back at its list of sections.
 * Wherever the appearance settings end up on a television, this row has to
 * stay within reach — it is the way back.
 *
 * Laid out like the rows above it in the Appearance card, its own gap included.
 */
@Composable
fun DeviceInterfaceSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(GlobalSettings.getDeviceOverride(context)) }
    val options = listOf(
        DeviceOverride.AUTO to stringResource(R.string.tv_interface_auto),
        DeviceOverride.HANDHELD to stringResource(R.string.tv_interface_handheld),
        DeviceOverride.TV to stringResource(R.string.tv_interface_tv),
    )
    Column(modifier.padding(top = 12.dp, bottom = 8.dp).settingAnchor(stringResource(R.string.tv_interface_title), outset = 6.dp)) {
        Text(
            stringResource(R.string.tv_interface_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HelpText(stringResource(R.string.tv_interface_desc))
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            options = options.map { it.second },
            selectedIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0),
            onOptionSelected = { idx ->
                val picked = options[idx].first
                if (picked != current) {
                    current = picked
                    GlobalSettings.setDeviceOverride(context, picked)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            height = 38.dp
        )
    }
}
