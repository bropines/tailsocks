package io.github.bropines.tailscaled.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Https
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ControlPort
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.TailscaledService

/**
 * Settings → Censorship bypass: the port of the daemon's control connection
 * (core/ControlPort).
 */
@Composable
fun ControlPortCard() {
    val context = LocalContext.current
    var tlsOnly by remember { mutableStateOf(ControlPort.isTlsOnly(context)) }
    ControlPortCardContent(
        tlsOnly = tlsOnly,
        onToggle = { on ->
            tlsOnly = on
            ControlPort.setTlsOnly(context, on)
            // The daemon reads it at start, hence RESTART rather than APPLY.
            if (ProxyState.isUserLetRunning(context)) {
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
            }
        },
    )
}

/** The card itself, state in and events out, for previews. */
@Composable
internal fun ControlPortCardContent(tlsOnly: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsCard(title = stringResource(R.string.core_control_port_section)) {
        HelpText(
            stringResource(R.string.core_control_port_help),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsSwitchItem(
            title = stringResource(R.string.core_control_port_title),
            subtitle = stringResource(R.string.core_control_port_desc),
            icon = Icons.Default.Https,
            checked = tlsOnly,
            onCheckedChange = onToggle
        )
    }
}
