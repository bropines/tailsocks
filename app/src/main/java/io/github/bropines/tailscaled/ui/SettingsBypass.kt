package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the Censorship bypass page shares with the screen around it: the control proxy's
 * dialog is drawn by the screen ([SettingsControlProxyDialog], over every page), opened
 * from this page's row, and the row shows what the dialog applied.
 */
@Stable
internal class SettingsBypassState(context: Context) {
    var showProxyDialog by mutableStateOf(false)
    var isProxyEnabled by mutableStateOf(GlobalSettings.isCPProxyEnabled(context))
}

// E. Censorship bypass
@Composable
internal fun SettingsBypassPage(context: Context, state: SettingsBypassState) {
    with(state) {
        var byedpiEnabled by remember { mutableStateOf(GlobalSettings.isCPByeDpiEnabled(context)) }
        var byedpiFlags by remember { mutableStateOf(GlobalSettings.getCPByeDpiFlags(context)) }
        var byedpiIpv6Disabled by remember { mutableStateOf(GlobalSettings.isCPByeDpiIpv6Disabled(context)) }
        // Observed, not read once: the proxy starts after the switch is drawn,
        // and a start that fails does so a moment later still.
        val bypassStatus by ByeDpiProxy.status.collectAsState()

        SettingsCard(title = stringResource(R.string.settings_sect_control_proxy)) {
            val isByeDpi = GlobalSettings.isCPByeDpiEnabled(context)
            val statusText = if (isProxyEnabled) {
                if (isByeDpi) "ByeDPI (DPI Bypass)" else stringResource(R.string.settings_control_proxy_enabled_format, GlobalSettings.getCPField(context, "type"))
            } else {
                stringResource(R.string.settings_control_proxy_disabled)
            }
            SettingsClickableItem(
                stringResource(R.string.settings_control_proxy_title), 
                statusText,
                Icons.Default.Shield
            ) { showProxyDialog = true }
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_tab_byedpi)) {
            HelpText(
                stringResource(R.string.settings_byedpi_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_byedpi_warning_override),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            SettingsSwitchItem(
                title = stringResource(R.string.settings_byedpi_enable),
                // The proxy as it is, not as the switch stands: it starts with
                // the service, and automation can turn it on behind this screen.
                subtitle = when (val status = bypassStatus) {
                    is ByeDpiStatus.Active -> {
                        val address = "${status.address.first}:${status.address.second}"
                        // What runs is not always what the field below holds.
                        when {
                            status.ignored.isEmpty() -> stringResource(R.string.settings_byedpi_status_active, address)
                            status.builtIn -> stringResource(R.string.settings_byedpi_status_builtin, address)
                            else -> stringResource(R.string.settings_byedpi_status_ignored, address, status.ignored.joinToString(" "))
                        }
                    }
                    is ByeDpiStatus.Failed -> stringResource(R.string.settings_byedpi_status_failed, status.code)
                    ByeDpiStatus.Stopped -> stringResource(R.string.settings_byedpi_status_stopped)
                },
                icon = Icons.Default.Shield,
                checked = byedpiEnabled
            ) {
                byedpiEnabled = it
                GlobalSettings.setCPByeDpiEnabled(context, it)
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
            }

            Spacer(Modifier.height(12.dp))

            SettingsEditItem(
                title = stringResource(R.string.settings_byedpi_flags),
                value = byedpiFlags,
                icon = Icons.Default.Code,
                // What an empty field runs.
                placeholder = ByeDpiFlags.DEFAULT
            ) {
                byedpiFlags = it
                GlobalSettings.setCPByeDpiFlags(context, it)
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
            }

            Spacer(Modifier.height(12.dp))

            SettingsSwitchItem(
                title = stringResource(R.string.settings_byedpi_ipv6_disabled),
                subtitle = stringResource(R.string.settings_byedpi_ipv6_disabled_desc),
                icon = Icons.Default.Dns,
                checked = byedpiIpv6Disabled
            ) {
                byedpiIpv6Disabled = it
                GlobalSettings.setCPByeDpiIpv6Disabled(context, it)
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
            }
        }

        SettingsCardGap()

        // Which port the control connection uses (ControlPortCard.kt).
        ControlPortCard()

        SettingsCardGap()

        // What gets the tailnet up while control is out of reach (NetmapCacheCard.kt).
        NetmapCacheCard()
    }
}

/** The control proxy's dialog, raised by the row of [SettingsBypassPage]. */
@Composable
internal fun SettingsControlProxyDialog(context: Context, state: SettingsBypassState) {
    with(state) {
        if (showProxyDialog) {
            ControlProxyDialog(
                onDismiss = { showProxyDialog = false },
                onApply = {
                    isProxyEnabled = GlobalSettings.isCPProxyEnabled(context)
                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
                }
            )
        }
    }
}
