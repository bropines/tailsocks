package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import appctr.Appctr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Account page's state: the open profile's stored values and the app-wide "accept
 * routes", read once when Settings opens, the tags the daemon reports (filled when the page
 * opens), and the page's two dialogs. [honest] is the profile's Android/Linux choice, which
 * the screen keeps keyed on the account (ProfileHostinfo) and hands in.
 */
@Stable
internal class SettingsAccountState(context: Context, profilePrefs: SharedPreferences, honest: MutableState<Boolean>) {
    var loginServer by mutableStateOf(profilePrefs.getString("login_server", "") ?: "")
    var acceptRoutes by mutableStateOf(GlobalSettings.getBoolean(context, "accept_routes", false))
    var authKey by mutableStateOf(profilePrefs.getString("authkey", "") ?: "")
    var hostname by mutableStateOf(profilePrefs.getString("hostname", "") ?: "")
    var exitNodeIp by mutableStateOf(profilePrefs.getString("exit_node_ip", "") ?: "")
    var exitNodeId by mutableStateOf(profilePrefs.getString("exit_node_id", "") ?: "")
    var advertiseTags by mutableStateOf(profilePrefs.getString("advertise_tags", "") ?: "")
    var advertiseRoutes by mutableStateOf(profilePrefs.getString("advertise_routes", "") ?: "")
    var advertiseExitNode by mutableStateOf(profilePrefs.getBoolean("advertise_exit_node", false))
    var honestHostinfo by honest
    var showHonestHostinfoDialog by mutableStateOf(false)
    var honestHostinfoTarget by mutableStateOf(false)
    var appliedTags by mutableStateOf<List<String>>(emptyList())
    var availableNetworkTags by mutableStateOf<List<String>>(emptyList())
    // "Log out and reset": its dialog is drawn by the screen, over every page (SettingsLogoutDialog).
    var showResetDialog by mutableStateOf(false)
}

/** The dialog of the Account page's "Log out and reset" row. */
@Composable
internal fun SettingsLogoutDialog(env: SettingsPageEnv, state: SettingsAccountState) {
    with(env) {
    with(state) {
        if (showResetDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsLogoutTitle = stringResource(R.string.settings_logout_title)
            val strSettingsLogoutText = stringResource(R.string.settings_logout_text)
            val strSettingsLogoutConfirm = stringResource(R.string.settings_logout_confirm)
            val strActionCancel = stringResource(R.string.action_cancel)
            AppAlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(strSettingsLogoutTitle) },
                text = { Text(strSettingsLogoutText) },
                confirmButton = {
                    Button(onClick = { 
                        scope.launch(Dispatchers.IO) {
                            Appctr.logout()
                            profilePrefs.edit().putBoolean("was_logged_in", false).apply()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.settings_logged_out), Toast.LENGTH_SHORT).show()
                            }
                        }
                        showResetDialog = false 
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(strSettingsLogoutConfirm) }
                },
                dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(strActionCancel) } }
            )
        }
    }
    }
}

// A. Account & connection
//
// extraArgs: the Diagnostics page's extra arguments, whose flags override the node's switches.
@Composable
internal fun SettingsAccountPage(env: SettingsPageEnv, state: SettingsAccountState, extraArgs: String) {
    with(env) {
    with(state) {
        // The node's own switches (NodeSettings.kt): stored per profile, applied with the
        // other settings, overridden by a flag in the extra arguments.
        val nodeSwitches = rememberNodeSwitches(profilePrefs, extraArgs)
        val saveNodeSwitch: (String, Any) -> Unit = { key, value -> saveProfilePref(key, value) }
        // The tag suggestions and the "applied tags" line are the only readers
        // of this query, so it runs when the page opens instead of on every
        // Settings open.
        LaunchedEffect(activeAccount.id) {
            scope.launch(Dispatchers.IO) {
                try {
                    val status = DaemonStatus.readOrNull()
                    if (status != null) {
                        val selfTags = status.self?.tags ?: emptyList()
                        withContext(Dispatchers.Main) { appliedTags = selfTags }

                        val netTags = mutableSetOf<String>()
                        selfTags.forEach { netTags.add(it) }
                        status.peers?.values?.forEach { peer ->
                            peer.tags?.forEach { netTags.add(it) }
                        }
                        val sortedTags = netTags.toList().sorted()
                        withContext(Dispatchers.Main) { availableNetworkTags = sortedTags }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        SettingsCard(title = stringResource(R.string.settings_sect_account_format, activeAccount.name)) {
            SettingsEditItem(stringResource(R.string.settings_login_server_title), loginServer, Icons.Default.Cloud, placeholder = stringResource(R.string.settings_login_server_placeholder)) { 
                if (loginServer != it) {
                    loginServer = it
                    saveProfilePref("was_logged_in", false, triggerService = false)
                    saveProfilePref("login_server", it)
                }
            }
            SettingsEditItem(stringResource(R.string.settings_auth_key_title), authKey, Icons.Default.VpnKey) { authKey = it; saveProfilePref("authkey", it) }
            SettingsEditItem(
                stringResource(R.string.settings_device_name_title),
                hostname,
                Icons.Default.Badge,
                onAction = { sanitizeHostnameInput(android.os.Build.MODEL) },
                actionIcon = Icons.Default.AutoFixHigh
            ) {
                // The device name becomes a DNS label on the tailnet, so
                // whitespace and stray characters are dropped before it is
                // stored rather than being sent to the control plane.
                val clean = sanitizeHostnameInput(it)
                hostname = clean
                saveProfilePref("hostname", clean)
            }
             @Suppress("UNCHECKED_CAST")
             SettingsExitNodeItem(stringResource(R.string.settings_exit_node_title), exitNodeId, exitNodeIp, Icons.AutoMirrored.Filled.Input) { id, ip ->
                 exitNodeId = id
                exitNodeIp = ip
                saveProfilePref("exit_node_ip", ip, triggerService = false)
                saveProfilePref("exit_node_id", id, triggerService = false)
            }
            ExitNodeLanRow(nodeSwitches, saveNodeSwitch)
            var exitNodeAutoDirect by remember { mutableStateOf(GlobalSettings.isExitNodeAutoDirect(context)) }
            SettingsSwitchItem(
                stringResource(R.string.settings_exit_guard_auto_title),
                stringResource(R.string.settings_exit_guard_auto_desc),
                Icons.Default.AltRoute,
                exitNodeAutoDirect
            ) {
                GlobalSettings.setExitNodeAutoDirect(context, it)
                exitNodeAutoDirect = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(stringResource(R.string.settings_accept_subnet_routes_title), stringResource(R.string.settings_accept_routes_desc), Icons.Default.Map, acceptRoutes) { acceptRoutes = it; saveGlobalPref("accept_routes", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(
                stringResource(R.string.settings_advertise_exit_node_title),
                stringResource(R.string.settings_advertise_exit_node_desc),
                Icons.Default.Public,
                advertiseExitNode
            ) {
                advertiseExitNode = it
                saveProfilePref("advertise_exit_node", it)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            // A property of the profile (ProfileHostinfo): the coordination server
            // binds a node to the OS it registered with and refuses it if that
            // changes — measured 2026-09-07 ("node OS changed since last connection",
            // no netmap). A profile that has not logged in yet can still choose; a
            // registered one is offered a new profile instead. The daemon reads the
            // value from its environment at start, hence RESTART rather than APPLY.
            SettingsSwitchItem(
                title = stringResource(R.string.settings_honest_hostinfo_title),
                subtitle = stringResource(R.string.settings_honest_hostinfo_desc),
                icon = Icons.Default.Android,
                checked = honestHostinfo
            ) { enabled ->
                if (ProfileHostinfo.isRegistered(context, activeAccount.id)) {
                    honestHostinfoTarget = enabled
                    showHonestHostinfoDialog = true
                } else {
                    honestHostinfo = enabled
                    ProfileHostinfo.setHonest(context, activeAccount.id, enabled)
                    if (ProxyState.isUserLetRunning(context)) {
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                        context.startService(intent)
                    }
                }
            }
        }

        if (showHonestHostinfoDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTitle = stringResource(R.string.settings_honest_hostinfo_dialog_title)
            val strRegisteredAs = stringResource(
                if (honestHostinfo) R.string.honest_hostinfo_os_android else R.string.honest_hostinfo_os_linux
            )
            val strBody = stringResource(R.string.settings_honest_hostinfo_dialog_body, strRegisteredAs)
            val strConfirm = stringResource(R.string.settings_honest_hostinfo_create_profile)
            val strCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showHonestHostinfoDialog = false },
                icon = { Icon(Icons.Default.Android, contentDescription = null) },
                title = { Text(strTitle) },
                text = { Text(text = strBody, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(onClick = {
                        showHonestHostinfoDialog = false
                        // The dashboard owns profile creation; it opens "Add account"
                        // with the checkbox preset to what the user asked for here.
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(MAIN_EXTRA_ADD_ACCOUNT, true)
                            putExtra(MAIN_EXTRA_ADD_ACCOUNT_HONEST, honestHostinfoTarget)
                        })
                    }) { Text(strConfirm) }
                },
                dismissButton = {
                    TextButton(onClick = { showHonestHostinfoDialog = false }) { Text(strCancel) }
                }
            )
        }

        SettingsCardGap()

        NodeSecurityCard(nodeSwitches, saveNodeSwitch) {
            context.startActivity(Intent(context, TailnetLockActivity::class.java))
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_service_ad)) {
            SettingsEditItem(
                title = stringResource(R.string.settings_ad_routes_title),
                value = advertiseRoutes,
                icon = Icons.Default.Map,
                placeholder = stringResource(R.string.settings_ad_routes_placeholder),
                description = stringResource(R.string.settings_ad_routes_desc)
            ) { 
                advertiseRoutes = it
                saveProfilePref("advertise_routes", it) 
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(
                title = stringResource(R.string.settings_ad_tags_title),
                value = advertiseTags,
                icon = Icons.AutoMirrored.Filled.Label,
                placeholder = stringResource(R.string.settings_ad_tags_placeholder),
                description = stringResource(R.string.settings_ad_tags_desc),
                suggestions = availableNetworkTags
            ) { 
                advertiseTags = it
                saveProfilePref("advertise_tags", it) 
            }
            if (appliedTags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_applied_tags_label, appliedTags.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            NodeServiceRows(nodeSwitches, saveNodeSwitch)
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_adv_profile)) {
            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth().settingAnchor(stringResource(R.string.settings_logout_reset_title)),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_logout_reset_title))
            }
        }
    }
    }
}
