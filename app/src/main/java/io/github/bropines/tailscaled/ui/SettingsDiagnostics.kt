package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Diagnostics page's stored values, read once when Settings opens. [extraArgs] is read
 * by the Account page as well — a flag there overrides the node's own switches — so it
 * lives here, with the page that changes it, and the Account page is handed it.
 */
@Stable
internal class SettingsDiagnosticsState(context: Context) {
    var detailedLogs by mutableStateOf(GlobalSettings.getBoolean(context, "detailed_logs", false))
    var extraArgs by mutableStateOf(GlobalSettings.getString(context, "extra_args_raw", ""))
}

// K. Diagnostics & developer
@Composable
internal fun SettingsDiagnosticsPage(env: SettingsPageEnv, state: SettingsDiagnosticsState) {
    with(env) {
    with(state) {
        // Root Mode is read here, not held: the switch that changes it lives in
        // section B, and leaving that page re-composes this one.
        val rootModeActive = GlobalSettings.isRootModeEnabled(context)
        val rootTunActive = GlobalSettings.isRootTunEnabled(context)

        // First, because it is what this page is opened for when something is wrong:
        // the state right now, before the switches that might explain it.
        LiveDiagnosticsCard(Modifier.settingAnchor(stringResource(R.string.diag_live_title)))
        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_flags_logs)) {
            SettingsSwitchItem(stringResource(R.string.settings_detailed_logs_title), stringResource(R.string.settings_detailed_logs_desc), Icons.Default.BugReport, detailedLogs) { detailedLogs = it; saveGlobalPref("detailed_logs", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(
                stringResource(R.string.settings_extra_args_title),
                extraArgs,
                Icons.Default.Code,
                placeholder = stringResource(R.string.settings_extra_args_placeholder),
                description = stringResource(R.string.extra_args_desc)
            ) { extraArgs = it; saveGlobalPref("extra_args_raw", it) }
        }

        if (rootModeActive) {
            SettingsCardGap()
            SettingsCard(title = stringResource(R.string.settings_root_info_title)) {
                val socketPath = "${context.filesDir.absolutePath}/tailscaled.sock"
                val logsDir = File(context.filesDir.parentFile ?: context.filesDir, "logs").absolutePath
                val logPath = "$logsDir/tailscaled.log"
                val serviceScriptPath = RootPaths.current.serviceScript
                var daemonAlive by remember { mutableStateOf(false) }

                // Poll real socket liveness every 3s instead of just File.exists()
                LaunchedEffect(Unit) {
                    while (true) {
                        daemonAlive = withContext(Dispatchers.IO) {
                            RootUtils.isDaemonAlive(socketPath)
                        }
                        kotlinx.coroutines.delay(3_000)
                    }
                }

                CopyablePathItem(
                    label = stringResource(R.string.settings_diag_socket_path),
                    path = socketPath,
                    context = context
                )

                Spacer(Modifier.height(8.dp))
                CopyablePathItem(
                    label = stringResource(R.string.settings_diag_log_file),
                    path = logPath,
                    context = context
                )

                Spacer(Modifier.height(8.dp))
                CopyablePathItem(
                    label = stringResource(R.string.settings_diag_service_script),
                    path = serviceScriptPath,
                    context = context
                )

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_root_daemon_status_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(if (daemonAlive) R.string.settings_root_daemon_status_running else R.string.settings_root_daemon_status_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (daemonAlive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                if (rootTunActive) {
                    var routingDump by remember { mutableStateOf<String?>(null) }
                    var isDumping by remember { mutableStateOf(false) }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            isDumping = true
                            scope.launch {
                                val dump = withContext(Dispatchers.IO) { RootUtils.dumpRoutingState(context) }
                                routingDump = dump.ifBlank { context.getString(R.string.settings_diag_no_output) }
                                isDumping = false
                            }
                        },
                        enabled = !isDumping,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.settings_root_routing_check),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    routingDump?.let { dump ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = dump,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_tools)) {
            if (place(Feature.LOGS) == Availability.OFFERED) {
                SettingsClickableItem(
                    stringResource(R.string.logs_title),
                    stringResource(R.string.settings_link_logs_desc),
                    Icons.AutoMirrored.Filled.List
                ) { context.startActivity(Intent(context, LogsActivity::class.java)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (place(Feature.CONSOLE) == Availability.OFFERED) {
                SettingsClickableItem(
                    stringResource(R.string.console_title),
                    stringResource(R.string.settings_link_console_desc),
                    Icons.Default.Terminal
                ) { context.startActivity(Intent(context, ConsoleActivity::class.java)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            SettingsClickableItem(
                stringResource(R.string.netcheck_title),
                stringResource(R.string.settings_link_netcheck_desc),
                Icons.Default.NetworkCheck
            ) { context.startActivity(Intent(context, NetcheckActivity::class.java)) }
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_troubleshooting)) {
            SettingsClickableItem(
                 stringResource(R.string.settings_show_onboarding),
                 stringResource(R.string.settings_show_onboarding_desc),
                 Icons.Default.Info
            ) {
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("first_start_done", false)
                    .apply()
                context.startActivity(Intent(context, FirstStartActivity::class.java))
                context.findActivity()?.finish()
            }
        }
    }
    }
}
