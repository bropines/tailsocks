package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The Background page's stored values, read once when Settings opens, as they always were. */
@Stable
internal class SettingsBackgroundState(context: Context) {
    var autoStart by mutableStateOf(GlobalSettings.isAutoStartEnabled(context))
    var hideFromRecents by mutableStateOf(GlobalSettings.isHideFromRecents(context))
    var autoReconnect by mutableStateOf(GlobalSettings.isAutoReconnectEnabled(context))
    var autoReconnectAttempts by mutableStateOf(GlobalSettings.getAutoReconnectAttempts(context).toString())
    var serviceWatchdog by mutableStateOf(GlobalSettings.isServiceWatchdogEnabled(context))
    var sleepMode by mutableStateOf(SleepMode.of(context))
}

// G. Background & permissions
@Composable
internal fun SettingsBackgroundPage(env: SettingsPageEnv, state: SettingsBackgroundState) {
    with(env) {
    with(state) {
        SettingsCard(title = stringResource(R.string.settings_sect_service_perms)) {
            SettingsClickableItem(stringResource(R.string.settings_permissions_title), stringResource(R.string.settings_permissions_desc), Icons.Default.VerifiedUser) {
                context.startActivity(Intent(context, PermissionsActivity::class.java))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(stringResource(R.string.settings_autostart_title), stringResource(R.string.settings_autostart_desc), Icons.Default.PowerSettingsNew, autoStart) {
                GlobalSettings.setAutoStartEnabled(context, it)
                autoStart = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(stringResource(R.string.settings_hide_recents_title), stringResource(R.string.settings_hide_recents_desc), Icons.Default.VisibilityOff, hideFromRecents) {
                GlobalSettings.setHideFromRecents(context, it)
                hideFromRecents = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(
                stringResource(R.string.settings_auto_reconnect_title),
                stringResource(R.string.settings_auto_reconnect_desc),
                Icons.Default.Autorenew,
                autoReconnect
            ) {
                GlobalSettings.setAutoReconnectEnabled(context, it)
                autoReconnect = it
            }
            if (autoReconnect) {
                SettingsEditItem(
                    title = stringResource(R.string.settings_auto_reconnect_attempts_title),
                    value = autoReconnectAttempts,
                    icon = Icons.Default.Repeat
                ) {
                    autoReconnectAttempts = it.filter { ch -> ch.isDigit() }.take(2)
                    saveGlobalPref("auto_reconnect_attempts", autoReconnectAttempts)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(
                stringResource(R.string.settings_watchdog_title),
                stringResource(R.string.settings_watchdog_desc),
                Icons.Default.MonitorHeart,
                serviceWatchdog
            ) {
                GlobalSettings.setServiceWatchdogEnabled(context, it)
                serviceWatchdog = it
                if (it) {
                    ServiceWatchdog.schedule(context)
                    // Without "Alarms & reminders" the check still runs, only
                    // later and without the exemption that lets it start the
                    // service from the background. Ask exactly here, where the
                    // user just said they want the feature.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        !ServiceWatchdog.canScheduleExact(context)
                    ) {
                        val page = Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        // A device without the page, or with a placeholder in its place, is told so.
                        if (!(context.canLaunch(page) && context.tryStartActivity(page))) {
                            Toast.makeText(context, context.getString(R.string.settings_watchdog_exact_error), Toast.LENGTH_LONG).show()
                        }
                    }
                } else ServiceWatchdog.cancel(context)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            // What the connection does while the phone sleeps (core/SleepMode.kt).
            var pickingSleep by remember { mutableStateOf(false) }
            val sleepLabels = mapOf(
                SleepMode.OFF to stringResource(R.string.settings_sleep_off),
                SleepMode.PERIODIC to stringResource(R.string.settings_sleep_periodic),
                SleepMode.ALWAYS to stringResource(R.string.settings_sleep_always),
            )
            val sleepDescs = mapOf(
                SleepMode.OFF to stringResource(R.string.settings_sleep_off_desc),
                SleepMode.PERIODIC to stringResource(R.string.settings_sleep_periodic_desc),
                SleepMode.ALWAYS to stringResource(R.string.settings_force_bg_desc),
            )
            SettingsClickableItem(
                stringResource(R.string.settings_keep_awake_title),
                "${sleepLabels.getValue(sleepMode)} — ${sleepDescs.getValue(sleepMode)}",
                Icons.Default.BatteryFull
            ) { pickingSleep = true }
            if (pickingSleep) {
                PickerSheet(
                    title = stringResource(R.string.settings_keep_awake_title),
                    options = SleepMode.entries.map { PickerOption(it, sleepLabels.getValue(it), supporting = sleepDescs.getValue(it)) },
                    selected = sleepMode,
                    onPick = { SleepMode.set(context, it); sleepMode = it },
                    onDismiss = { pickingSleep = false },
                )
            }
        }
    }
    }
}
