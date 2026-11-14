package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The DNS page's stored values, read once when Settings opens, as they always were. */
@Stable
internal class SettingsDnsState(context: Context) {
    var dnsProxy by mutableStateOf(GlobalSettings.getString(context, "dns_proxy", "127.0.0.1:1053"))
    var dnsFallbacks by mutableStateOf(GlobalSettings.getString(context, "dns_fallbacks", "8.8.8.8:53,1.1.1.1:53"))
    var dohUrl by mutableStateOf(GlobalSettings.getString(context, "doh_url", "https://1.1.1.1/dns-query"))
    var acceptDns by mutableStateOf(GlobalSettings.getBoolean(context, "accept_dns", true))
}

// D. DNS
@Composable
internal fun SettingsDnsPage(env: SettingsPageEnv, state: SettingsDnsState) {
    with(env) {
    with(state) {
        // Root Mode owns the resolver itself, so both gates below read the
        // stored value the same way the old TS-Core tab did.
        val isRootModeActive = GlobalSettings.isRootModeEnabled(context)
        val isRootTunActive = GlobalSettings.isRootTunEnabled(context)
        var rootDnsRedirect by remember { mutableStateOf(GlobalSettings.isRootDnsRedirectEnabled(context)) }
        // The switch can be on while the redirect is not installed: another VPN
        // owns the resolver and Root Mode yielded it. Say so instead of letting
        // the row claim something that is not on the device. Shared is the third
        // case — the redirect is installed, minus that client's own queries.
        val rootDnsShared = GlobalSettings.isRootRoutingShared(context)
        val rootDnsYielded = GlobalSettings.isRootRoutingYielded(context) && !rootDnsShared

        SettingsCard(title = stringResource(R.string.settings_sect_resolver)) {
            SettingsSwitchItem(stringResource(R.string.settings_magicdns_title), stringResource(R.string.settings_accept_dns_desc), Icons.Default.Dns, acceptDns) { acceptDns = it; saveGlobalPref("accept_dns", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(
                title = stringResource(R.string.settings_dns_proxy_address_title),
                value = dnsProxy,
                icon = Icons.Default.Toll,
                enabled = !isRootModeActive,
                description = if (isRootModeActive) stringResource(R.string.settings_root_disabled_general_note) else ""
            ) { dnsProxy = it; saveGlobalPref("dns_proxy", it) }

            if (isRootModeActive && isRootTunActive) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_dns_redirect_title),
                    subtitle = when {
                        rootDnsRedirect && rootDnsYielded -> stringResource(R.string.settings_root_yielded_banner)
                        rootDnsRedirect && rootDnsShared -> stringResource(R.string.settings_root_dns_shared_desc)
                        else -> stringResource(R.string.settings_root_dns_redirect_desc)
                    },
                    icon = Icons.Default.Dns,
                    checked = rootDnsRedirect
                ) { enabled ->
                    rootDnsRedirect = enabled
                    GlobalSettings.setRootDnsRedirectEnabled(context, enabled)
                    if (ProxyState.isUserLetRunning(context)) {
                        val intent = Intent(context, TailscaledService::class.java).apply {
                            action = "RESTART_ACTION"
                        }
                        context.startService(intent)
                    }
                }
            }
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_fallback_dns)) {
            SettingsEditItem(stringResource(R.string.settings_dns_fallbacks_title), dnsFallbacks, Icons.AutoMirrored.Filled.List, placeholder = stringResource(R.string.settings_dns_fallbacks_placeholder)) { dnsFallbacks = it; saveGlobalPref("dns_fallbacks", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(stringResource(R.string.settings_doh_fallback_title), dohUrl, Icons.Default.Link, placeholder = stringResource(R.string.settings_doh_fallback_placeholder)) { dohUrl = it; saveGlobalPref("doh_url", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            var dnsRescue by remember { mutableStateOf(GlobalSettings.isDnsRescueEnabled(context)) }
            SettingsSwitchItem(
                stringResource(R.string.settings_dns_rescue_title),
                stringResource(R.string.settings_dns_rescue_desc),
                Icons.Default.HealthAndSafety,
                dnsRescue
            ) {
                dnsRescue = it
                GlobalSettings.setDnsRescueEnabled(context, it)
                // The daemon reads it at start, hence RESTART rather than APPLY.
                if (ProxyState.isUserLetRunning(context)) {
                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                }
            }
        }

        if (place(Feature.DNS_SCREEN) == Availability.OFFERED) {
            SettingsCardGap()

            SettingsCard(title = stringResource(R.string.settings_sect_tools)) {
                SettingsClickableItem(
                    stringResource(R.string.dns_title),
                    stringResource(R.string.settings_link_dns_desc),
                    Icons.Default.Troubleshoot
                ) { context.startActivity(Intent(context, DnsActivity::class.java)) }
            }
        }
    }
    }
}
