package io.github.bropines.tailscaled.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TunEngines
import io.github.bropines.tailscaled.core.TunTransparent

/**
 * The settings of the VPN (TUN) mode, shown under its option in Settings →
 * Tunnel mode.
 *
 * The engine is the first row: native is the default, hev-socks5-tunnel the
 * compatibility choice. The rows that only hev reads — the SOCKS5 proxy it
 * forwards into and its private gateway address — are there only while hev is
 * picked; the native device carries the node's own addresses, and only the
 * native engine moves a running daemon onto a rebuilt device (the swap row).
 * Transparent mode under Android's lockdown is both engines'.
 */
@Composable
fun TunSettingsCard(
    engine: String,
    onEngineChange: (String) -> Unit,
    socks5: String,
    onOpenProxies: () -> Unit,
    ipv6: Boolean,
    onIpv6Change: (Boolean) -> Unit,
    tunAddress: String,
    onTunAddressChange: (String) -> Unit,
    excludedCidrs: String,
    onExcludedCidrsChange: (String) -> Unit,
    excludedAppsCount: Int,
    onOpenExcludedApps: () -> Unit,
    swapInPlace: Boolean,
    onSwapInPlaceChange: (Boolean) -> Unit,
    transparent: String,
    onTransparentChange: (String) -> Unit,
) {
    val hev = engine == TunEngines.HEV
    var picking by remember { mutableStateOf(false) }
    var pickingTransparent by remember { mutableStateOf(false) }

    SettingsCard(title = stringResource(R.string.settings_sect_tun_mode)) {
        SettingsClickableItem(
            title = stringResource(R.string.tun_engine_title),
            subtitle = stringResource(if (hev) R.string.tun_engine_hev_desc else R.string.tun_engine_native_desc),
            icon = Icons.Default.Memory
        ) { picking = true }

        // Only the native engine hands a rebuilt device to the running daemon.
        AnimatedVisibility(visible = !hev) {
            Column {
                TunDivider()
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_tun_swap_title),
                    subtitle = stringResource(R.string.settings_tun_swap_desc),
                    icon = Icons.Default.Autorenew,
                    checked = swapInPlace,
                    onCheckedChange = onSwapInPlaceChange
                )
            }
        }

        // Only hev forwards into the proxy and has a gateway address of its own.
        AnimatedVisibility(visible = hev) {
            Column {
                TunDivider()
                // The dependency is real — the tunnel forwards every packet into
                // this local proxy — but the proxy is not TUN's: proxy mode is
                // nothing else, and in Root mode Taildrive and the admin API
                // still dial it. So it is named here and edited in its own
                // category.
                SettingsClickableItem(
                    title = stringResource(R.string.settings_tun_uses_socks_title),
                    subtitle = stringResource(R.string.settings_tun_uses_socks_desc, socks5.ifBlank { "127.0.0.1:48115" }),
                    icon = Icons.Default.SwapHoriz,
                    onClick = onOpenProxies
                )
            }
        }

        TunDivider()
        SettingsSwitchItem(
            title = stringResource(R.string.settings_tun_ipv6_title),
            subtitle = stringResource(R.string.settings_tun_ipv6_desc),
            icon = Icons.Default.Language,
            checked = ipv6,
            onCheckedChange = onIpv6Change
        )

        TunDivider()
        SettingsClickableItem(
            title = stringResource(R.string.settings_tun_transparent_title),
            subtitle = stringResource(
                when (transparent) {
                    TunTransparent.OFF -> R.string.settings_tun_transparent_off_desc
                    TunTransparent.ALWAYS -> R.string.settings_tun_transparent_always_desc
                    else -> R.string.settings_tun_transparent_lockdown_desc
                }
            ),
            icon = Icons.Default.Lock
        ) { pickingTransparent = true }

        AnimatedVisibility(visible = hev) {
            Column {
                TunDivider()
                SettingsEditItem(
                    title = stringResource(R.string.settings_tun_address_title),
                    value = tunAddress,
                    icon = Icons.Default.Settings,
                    placeholder = "10.0.0.1/8",
                    description = stringResource(R.string.settings_tun_address_desc),
                    onSave = onTunAddressChange
                )
            }
        }

        TunDivider()
        SettingsEditItem(
            title = stringResource(R.string.settings_tun_excluded_cidrs_title),
            value = excludedCidrs,
            icon = Icons.Default.Block,
            placeholder = "192.168.0.0/16, 10.0.0.0/8",
            description = stringResource(R.string.settings_tun_excluded_cidrs_desc),
            onSave = onExcludedCidrsChange
        )

        TunDivider()
        SettingsClickableItem(
            title = stringResource(R.string.settings_tun_excluded_apps_title),
            subtitle = stringResource(R.string.settings_tun_excluded_apps_desc, excludedAppsCount),
            icon = Icons.Default.Apps,
            onClick = onOpenExcludedApps
        )
    }

    if (pickingTransparent) {
        // Resolved here, as the engine's below: the sheet is a window of its own.
        PickerSheet(
            title = stringResource(R.string.settings_tun_transparent_title),
            options = tunTransparentOptions(),
            selected = transparent,
            onPick = { if (it != transparent) onTransparentChange(it) },
            onDismiss = { pickingTransparent = false }
        )
    }

    if (picking) {
        // Resolved here: the sheet is a window of its own and would look its
        // words up in the system language — see wrapContextWithLocale().
        PickerSheet(
            title = stringResource(R.string.tun_engine_title),
            options = tunEngineOptions(),
            selected = engine,
            onPick = { if (it != engine) onEngineChange(it) },
            onDismiss = { picking = false }
        )
    }
}

/** The engine picker's rows, in the composition's language. */
@Composable
internal fun tunEngineOptions(): List<PickerOption<String>> = listOf(
    PickerOption(
        value = TunEngines.NATIVE,
        label = stringResource(R.string.tun_engine_native),
        icon = Icons.Default.Bolt,
        supporting = stringResource(R.string.tun_engine_native_short)
    ),
    PickerOption(
        value = TunEngines.HEV,
        label = stringResource(R.string.tun_engine_hev),
        icon = Icons.Default.SwapHoriz,
        supporting = stringResource(R.string.tun_engine_hev_short)
    ),
)

/** Transparent mode's choices, in the composition's language. */
@Composable
internal fun tunTransparentOptions(): List<PickerOption<String>> = listOf(
    PickerOption(
        value = TunTransparent.OFF,
        label = stringResource(R.string.settings_tun_transparent_off),
        icon = Icons.Default.Block,
        supporting = stringResource(R.string.settings_tun_transparent_off_short)
    ),
    PickerOption(
        value = TunTransparent.LOCKDOWN,
        label = stringResource(R.string.settings_tun_transparent_lockdown),
        icon = Icons.Default.Lock,
        supporting = stringResource(R.string.settings_tun_transparent_lockdown_short)
    ),
    PickerOption(
        value = TunTransparent.ALWAYS,
        label = stringResource(R.string.settings_tun_transparent_always),
        icon = Icons.Default.AllInclusive,
        supporting = stringResource(R.string.settings_tun_transparent_always_short)
    ),
)

@Composable
private fun TunDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}
