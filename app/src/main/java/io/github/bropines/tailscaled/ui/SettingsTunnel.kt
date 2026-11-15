package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import appctr.Appctr

/**
 * The Tunnel page's VPN (TUN) values, read once when Settings opens. Root Mode's own state
 * is the page's, read each time it opens; the list of excluded apps is chosen on a screen
 * of its own, whose launcher the screen registers and hands to the page.
 */
@Stable
internal class SettingsTunnelState(context: Context) {
    var tunModeEnabled by mutableStateOf(GlobalSettings.isTunModeEnabled(context))
    var tunEngine by mutableStateOf(GlobalSettings.getTunEngine(context))
    var tunExcludedCIDRs by mutableStateOf(GlobalSettings.getTunExcludedCIDRs(context))
    var tunExcludedApps by mutableStateOf(GlobalSettings.getTunExcludedApps(context))
    var tunAddress by mutableStateOf(GlobalSettings.getTunAddress(context))
    var tunIpv6Enabled by mutableStateOf(GlobalSettings.isTunIpv6Enabled(context))
    var tunSwapInPlace by mutableStateOf(GlobalSettings.isTunSwapInPlace(context))
    var tunTransparent by mutableStateOf(GlobalSettings.getTunTransparent(context))
}

// B. Tunnel mode
//
// One three-way selector owns both mode keys. Proxy / VPN (TUN) / Root are
// mutually exclusive here: `tun_mode_enabled` and `root_mode_enabled` are
// never written true together. Root keeps the old carry-over — enabling it
// while TUN was on forces `root_tun_enabled`, so a user who had a tunnel
// keeps one, now the native kernel interface. Only the rows of the selected
// mode are composed, which is why the TUN rows no longer carry the
// "unavailable in Root mode" notes: in Root mode they are not there at all.
//
// withRoot: whether Root is among the modes to pick. It is wherever Root Mode is
// offered in its usual place; a device that keeps it for the advanced settings reaches
// this same page with it through Advanced → Root mode. A device that runs in Root Mode
// sees it either way: the mode in use is never hidden.
//
// socks5: the Local proxies page's SOCKS5 address, which the VPN rows name.

/** The one command a switch between the tunnel modes sends to [TailscaledService]. */
internal enum class ModeSwitchCommand { NONE, APPLY_SETTINGS, RESTART }

/**
 * Which command carries a switch from mode [from] to mode [to] (0 Proxy, 1 VPN, 2 Root).
 *
 * Exactly one per switch. A switch into or out of Root restarts the connection (only
 * while the user wants one — the caller checks that when it sends): the root daemon is
 * a separate process the bridge cannot reconfigure in place. Proxy <-> VPN is a settings
 * apply, which relaunches the daemon on or off the VpnService device.
 *
 * Root -> VPN used to send both: the restart from the Root teardown and an
 * APPLY_SETTINGS from writing the TUN key. The apply ran against the root daemon being
 * killed, then started the TUN; the restart's teardown stopped it a moment later and its
 * start established the tunnel again — two native TUN starts about a second apart, each
 * a VpnService establish and a daemon hand-over, and the two commands raced.
 */
internal fun modeSwitchCommand(from: Int, to: Int): ModeSwitchCommand = when {
    from == to -> ModeSwitchCommand.NONE
    from == 2 || to == 2 -> ModeSwitchCommand.RESTART
    else -> ModeSwitchCommand.APPLY_SETTINGS
}

@Composable
internal fun SettingsTunnelPage(
    env: SettingsPageEnv,
    state: SettingsTunnelState,
    socks5: String,
    excludedAppsLauncher: ActivityResultLauncher<Intent>,
    withRoot: Boolean,
) {
    with(env) {
    with(state) {
        var rootModeEnabled by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
        var rootTunEnabled by remember { mutableStateOf(GlobalSettings.isRootTunEnabled(context)) }
        var rootYieldLan by remember { mutableStateOf(GlobalSettings.isRootYieldLanEnabled(context)) }
        var serviceScriptInstalled by remember { mutableStateOf(false) }
        // Whether the installed copy is the one this build writes (RootBootScript).
        var serviceScript by remember { mutableStateOf(RootBootScript.State(RootBootScript.Status.ABSENT)) }
        var cliInstalled by remember { mutableStateOf(false) }
        var killDaemonOnStop by remember { mutableStateOf(GlobalSettings.shouldKillRootDaemonOnStop(context)) }
        var rootVpnBypass by remember { mutableStateOf(GlobalSettings.isRootVpnBypassEnabled(context)) }
        var rootTakeDevice by remember { mutableStateOf(GlobalSettings.isRootTakeDeviceAnyway(context)) }
        // What the service actually installed last time. It changes while this
        // screen is open — another VPN starts, ours yields — so it is watched
        // rather than read once. Shared is the middle case: the other client
        // bypasses some apps and we took the default route for those alone.
        var rootRoutingYielded by remember { mutableStateOf(GlobalSettings.isRootRoutingYielded(context)) }
        var rootRoutingShared by remember { mutableStateOf(GlobalSettings.isRootRoutingShared(context)) }
        var showRootWarningDialog by remember { mutableStateOf(false) }
        var showTunWarningDialog by remember { mutableStateOf(false) }
        var showTakeDeviceDialog by remember { mutableStateOf(false) }
        // Android hands the VPN slot to one app at a time and revokes it from
        // whoever held it, without asking that app. It does not say who that is,
        // so we can only report that the slot is taken — by AdGuard, a private
        // DNS app, another tunnel — and let the user decide.
        // Read afresh each time the dialog is raised, not once per composition.
        val foreignVpnActive = remember(showTunWarningDialog) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                // A one-off look when the dialog opens; allNetworks is deprecated
                // only in favour of callbacks, which a dialog does not need.
                @Suppress("DEPRECATION")
                cm.allNetworks.any { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                } && !TunVpnService.isRunning
            }.getOrDefault(false)
        }
        var isRootBusy by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val prefs = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
                if (key == "root_routing_yielded") {
                    rootRoutingYielded = store.getBoolean("root_routing_yielded", false)
                }
                if (key == "root_routing_shared") {
                    rootRoutingShared = store.getBoolean("root_routing_shared", false)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            rootRoutingYielded = prefs.getBoolean("root_routing_yielded", false)
            rootRoutingShared = prefs.getBoolean("root_routing_shared", false)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // Root wins the display if an older install left both keys set; from the
        // first tap on, the selector keeps them exclusive.
        val selectedMode = if (rootModeEnabled) 2 else if (tunModeEnabled) 1 else 0

        // Probing these spawns a root shell, which must never run on the
        // composition thread — it blocks the UI until su answers.
        LaunchedEffect(rootModeEnabled) {
            if (!rootModeEnabled) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val script = RootUtils.serviceScriptState(context)
                val cli = RootUtils.isTailscaleCliInstalled()
                withContext(Dispatchers.Main) {
                    serviceScript = script
                    serviceScriptInstalled = script.installed
                    cliInstalled = cli
                }
            }
        }

        // Exactly what the old "Enable Native Root Mode" switch ran when it was
        // switched off. The selector calls it for both of the other two modes, so
        // the teardown lives in one place instead of being duplicated per branch.
        //
        // thenTun: the switch is Root -> VPN. The TUN key is written here, after the
        // Root key and before the restart below is sent, and without an APPLY_SETTINGS
        // of its own: the restart starts the connection in VPN mode (modeSwitchCommand).
        fun disableRootMode(thenTun: Boolean = false) {
            rootModeEnabled = false
            val hadRouting = GlobalSettings.isRootRoutingInstalled(context)
            GlobalSettings.setRootModeEnabled(context, false)
            if (thenTun) {
                tunModeEnabled = true
                GlobalSettings.setTunModeEnabled(context, true)
            }
            val hadScript = serviceScriptInstalled
            serviceScriptInstalled = false
            Toast.makeText(context, context.getString(R.string.settings_root_mode_disabled_toast), Toast.LENGTH_SHORT).show()
            // The application context: the work outlives this screen (settingsWork).
            val app = context.applicationContext
            settingsWork.launch {
                if (hadScript) RootUtils.setServiceScriptInstalled(app, false)
                // Take the system rules and the daemon down here rather
                // than hoping a later stop does it: nothing else knows
                // Root Mode was ever on once the setting is cleared.
                // One root shell removes the routing, kills the daemon and
                // hands its state back to the app (it used to be three).
                RootUtils.stopRootDaemon("${app.filesDir.absolutePath}/tailscaled.sock", handBackTo = app)
                if (hadRouting) {
                    GlobalSettings.setRootRoutingInstalled(app, false)
                    GlobalSettings.setRootRoutingYielded(app, false)
                    GlobalSettings.setRootRoutingShared(app, false)
                }
                // Whether the user wants a connection, not whether this process
                // happens to own a daemon: in Root Mode the daemon is a separate
                // root process the app only attaches to, so Appctr.isRunning()
                // reported false here and switching modes quietly did nothing
                // until the service was toggled by hand. startService needs no
                // main thread, and no suspension point is left to cancel it.
                if (ProxyState.isUserLetRunning(app)) {
                    app.startService(Intent(app, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                }
            }
        }

        if (showTunWarningDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsTunWarningTitle = stringResource(R.string.settings_tun_warning_title)
            val strSettingsTunWarningBodyBusy = stringResource(R.string.settings_tun_warning_body_busy)
            val strSettingsTunWarningBody = stringResource(R.string.settings_tun_warning_body)
            val strSettingsTunWarningConfirm = stringResource(R.string.settings_tun_warning_confirm)
            val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showTunWarningDialog = false },
                icon = { Icon(Icons.Default.VpnLock, contentDescription = null) },
                title = { Text(strSettingsTunWarningTitle) },
                text = {
                    Text(
                        text = if (foreignVpnActive) strSettingsTunWarningBodyBusy
                               else strSettingsTunWarningBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showTunWarningDialog = false
                        // Root down first, then the TUN key, so the two are never
                        // set together. One command carries the switch: from Root
                        // the restart disableRootMode sends, otherwise the apply.
                        when (modeSwitchCommand(selectedMode, 1)) {
                            ModeSwitchCommand.RESTART -> disableRootMode(thenTun = true)
                            ModeSwitchCommand.APPLY_SETTINGS -> if (!tunModeEnabled) {
                                tunModeEnabled = true
                                saveGlobalPref("tun_mode_enabled", true)
                            }
                            ModeSwitchCommand.NONE -> {}
                        }
                    }) {
                        Text(strSettingsTunWarningConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTunWarningDialog = false }) {
                        Text(strSettingsRootWarningDialogCancel)
                    }
                }
            )
        }

        if (showRootWarningDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsRootWarningDialogTitle = stringResource(R.string.settings_root_warning_dialog_title)
            val strSettingsRootWarningDialogBody = stringResource(R.string.settings_root_warning_dialog_body)
            val strSettingsRootWarningDialogConfirm = stringResource(R.string.settings_root_warning_dialog_confirm)
            val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showRootWarningDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strSettingsRootWarningDialogTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = strSettingsRootWarningDialogBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRootWarningDialog = false
                            isRootBusy = true
                            scope.launch {
                                val granted = withContext(Dispatchers.IO) { RootUtils.isRootAvailable() }
                                isRootBusy = false
                                if (granted) {
                                    rootModeEnabled = true
                                    GlobalSettings.setRootModeEnabled(context, true)
                                    if (GlobalSettings.isTunModeEnabled(context)) {
                                        GlobalSettings.setRootTunEnabled(context, true)
                                        rootTunEnabled = true
                                        // Root takes the tunnel over, so the
                                        // Android VpnService flag goes off with
                                        // it — the two mode keys are never both
                                        // true. The RESTART_ACTION below stops
                                        // the running VpnService.
                                        GlobalSettings.setTunModeEnabled(context, false)
                                        tunModeEnabled = false
                                    }
                                    Toast.makeText(context, context.getString(R.string.settings_root_mode_enabled_toast), Toast.LENGTH_SHORT).show()
                                    if (ProxyState.isUserLetRunning(context)) {
                                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                        context.startService(intent)
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.settings_root_access_unavailable), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strSettingsRootWarningDialogConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRootWarningDialog = false }) {
                        Text(strSettingsRootWarningDialogCancel)
                    }
                }
            )
        }

        if (showTakeDeviceDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTakeDeviceTitle = stringResource(R.string.settings_root_take_device_dialog_title)
            val strTakeDeviceBody = stringResource(R.string.settings_root_take_device_dialog_body)
            val strTakeDeviceConfirm = stringResource(R.string.settings_root_take_device_confirm)
            val strTakeDeviceCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showTakeDeviceDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strTakeDeviceTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = strTakeDeviceBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showTakeDeviceDialog = false
                            rootTakeDevice = true
                            GlobalSettings.setRootTakeDeviceAnyway(context, true)
                            // The decision is read when routing is applied, and
                            // that only happens on a fresh run — APPLY_SETTINGS
                            // never reinstalls the rules.
                            if (ProxyState.isUserLetRunning(context)) {
                                val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                context.startService(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strTakeDeviceConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTakeDeviceDialog = false }) {
                        Text(strTakeDeviceCancel)
                    }
                }
            )
        }

        // A mode is a commitment — it decides how every packet leaves the phone —
        // so it is picked from a list that states what each one does, not from a
        // tab strip that looks like a view switcher. The chosen one expands with
        // its own settings underneath.
        fun selectMode(index: Int) {
            if (index == selectedMode) return
            if (index == 2) {
                // Root is never enabled straight from a tap: the warning dialog
                // owns the su probe and the writes that follow it.
                showRootWarningDialog = true
                return
            }
            if (index == 1) {
                // Nor is TUN: taking the VPN slot kicks out whatever holds it,
                // and the user finds out when their ad blocker goes dark.
                showTunWarningDialog = true
                return
            }
            // Root goes down first, the TUN key is written second — the same
            // order the two old switches produced, so no APPLY_SETTINGS ever
            // lands with Root Mode still on and a TUN start pending.
            if (rootModeEnabled) disableRootMode()
            val wantTun = index == 1
            if (tunModeEnabled != wantTun) {
                tunModeEnabled = wantTun
                saveGlobalPref("tun_mode_enabled", wantTun)
            }
        }

        @Composable
        fun ModeOption(index: Int, icon: ImageVector, title: String, desc: String, danger: Boolean = false) {
            val selected = index == selectedMode
            val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val help = remember(desc) { mutableStateOf(false) }
            ExplainedRow(
                onClick = { selectMode(index) },
                help = help,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .settingAnchor(title, shape = MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                color = if (selected) accent.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.45f)) else null,
                clipped = false,
                onLongClick = { help.value = !help.value }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        HelpText(desc, expanded = help, inClickableRow = true)
                    }
                    RadioButton(
                        selected = selected,
                        onClick = { selectMode(index) },
                        // The card picks the mode: for a remote the button is no stop of its own.
                        modifier = Modifier.tvNoFocus(),
                        colors = RadioButtonDefaults.colors(selectedColor = accent)
                    )
                }
            }
        }

        ModeOption(
            index = 0,
            icon = Icons.Default.SwapHoriz,
            title = stringResource(R.string.settings_tunnel_mode_proxy),
            desc = stringResource(R.string.settings_tunnel_mode_proxy_desc)
        )
        ModeOption(
            index = 1,
            icon = Icons.Default.VpnLock,
            title = stringResource(R.string.settings_tunnel_mode_vpn),
            desc = stringResource(R.string.settings_tunnel_mode_vpn_desc)
        )

        AnimatedVisibility(visible = selectedMode == 1) {
            TunSettingsCard(
                engine = tunEngine,
                onEngineChange = {
                    tunEngine = it
                    saveGlobalPref("tun_engine", it)
                },
                socks5 = socks5,
                onOpenProxies = { open("proxies") },
                ipv6 = tunIpv6Enabled,
                onIpv6Change = {
                    tunIpv6Enabled = it
                    saveGlobalPref("tun_ipv6_enabled", it)
                },
                tunAddress = tunAddress,
                onTunAddressChange = {
                    tunAddress = it
                    saveGlobalPref("tun_address", it)
                },
                excludedCidrs = tunExcludedCIDRs,
                onExcludedCidrsChange = {
                    tunExcludedCIDRs = it
                    saveGlobalPref("tun_excluded_cidrs", it)
                },
                excludedAppsCount = tunExcludedApps.size,
                onOpenExcludedApps = {
                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                },
                swapInPlace = tunSwapInPlace,
                onSwapInPlaceChange = {
                    tunSwapInPlace = it
                    saveGlobalPref("tun_swap_in_place", it)
                },
                transparent = tunTransparent,
                onTransparentChange = {
                    tunTransparent = it
                    saveGlobalPref("tun_transparent", it)
                }
            )
        }

        if (withRoot || rootModeEnabled) {
            ModeOption(
                index = 2,
                icon = Icons.Default.Security,
                title = stringResource(R.string.settings_tunnel_mode_root),
                desc = stringResource(R.string.settings_tunnel_mode_root_desc),
                danger = true
            )
        }

        if (selectedMode == 2) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                HelpText(
                    stringResource(R.string.settings_root_banner_warning),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            SettingsCard(title = stringResource(R.string.settings_root_sect_title)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_tun_title),
                    subtitle = if (rootTunEnabled)
                        stringResource(R.string.settings_root_tun_desc_native)
                        else stringResource(R.string.settings_root_tun_desc_proxy),
                    icon = Icons.Default.VpnLock,
                    checked = rootTunEnabled
                ) { enabled ->
                    rootTunEnabled = enabled
                    val hadRouting = GlobalSettings.isRootRoutingInstalled(context)
                    GlobalSettings.setRootTunEnabled(context, enabled)
                    // Finishes even if Settings is left meanwhile (settingsWork).
                    val app = context.applicationContext
                    settingsWork.launch {
                        // Leaving native TUN drops tailscale0, so its policy
                        // routing has to go with it — the refresh loop no longer
                        // looks at Root Mode routing once this is off.
                        if (!enabled && hadRouting) {
                            RootUtils.cleanupTailscale0Routing()
                            GlobalSettings.setRootRoutingInstalled(app, false)
                            GlobalSettings.setRootRoutingYielded(app, false)
                            GlobalSettings.setRootRoutingShared(app, false)
                        }
                        if (ProxyState.isUserLetRunning(app)) {
                            app.startService(Intent(app, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                // The same list TUN mode uses: in Root Mode it takes the app's
                // uid out of the device-wide DNS redirect and off the exit node.
                SettingsClickableItem(
                    title = stringResource(R.string.settings_tun_excluded_apps_title),
                    subtitle = stringResource(R.string.settings_tun_excluded_apps_desc, tunExcludedApps.size),
                    icon = Icons.Default.Apps
                ) {
                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                }

                // Two ways the LAN stays on the real network while an exit node is
                // on. The switch: the prefixes this device sits on become rules
                // that hand them back to the platform (RootUtils.localReturnRulesInstall).
                // The list, the same one TUN mode uses: throw routes in the daemon's
                // table (RootUtils.localBypassRoutes), where a more specific accepted
                // route still wins. The service watches both keys and re-applies.
                AnimatedVisibility(visible = rootTunEnabled) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        SettingsSwitchItem(
                            title = stringResource(R.string.settings_root_yield_lan_title),
                            subtitle = stringResource(R.string.settings_root_yield_lan_desc),
                            icon = Icons.Default.Router,
                            checked = rootYieldLan
                        ) { enabled ->
                            rootYieldLan = enabled
                            saveGlobalPref("root_yield_lan", enabled)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        SettingsEditItem(
                            title = stringResource(R.string.settings_tun_excluded_cidrs_title),
                            value = tunExcludedCIDRs,
                            icon = Icons.Default.Block,
                            placeholder = "192.168.0.0/16, 10.0.0.0/8",
                            description = stringResource(R.string.settings_root_excluded_cidrs_desc),
                            onSave = {
                                tunExcludedCIDRs = it
                                saveGlobalPref("tun_excluded_cidrs", it)
                            }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_service_title),
                    subtitle = stringResource(R.string.settings_root_service_desc),
                    icon = Icons.Default.Build,
                    checked = serviceScriptInstalled,
                    enabled = !isRootBusy
                ) { install ->
                    isRootBusy = true
                    scope.launch {
                        // Read back rather than assumed: the state shown is the file's.
                        val (success, state) = withContext(Dispatchers.IO) {
                            val ok = RootUtils.setServiceScriptInstalled(context, install)
                            ok to RootUtils.serviceScriptState(context)
                        }
                        isRootBusy = false
                        serviceScript = state
                        if (success) {
                            serviceScriptInstalled = install
                            Toast.makeText(context, context.getString(if (install) R.string.settings_root_service_script_installed else R.string.settings_root_service_script_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_service_script_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (serviceScriptInstalled) {
                    val coroutineScope = rememberCoroutineScope()
                    val msgOk = stringResource(R.string.settings_root_script_reinstalled)
                    val msgFail = stringResource(R.string.error_generic, "reinstall failed")
                    var reinstallStatus by remember { mutableStateOf<String?>(null) }
                    var isReinstallOk by remember { mutableStateOf(true) }

                    BootScriptReinstallRow(
                        script = serviceScript,
                        reinstallStatus = reinstallStatus,
                        reinstallOk = isReinstallOk,
                        onReinstall = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val success = RootUtils.setServiceScriptInstalled(context, true)
                                val state = RootUtils.serviceScriptState(context)
                                withContext(Dispatchers.Main) {
                                    serviceScript = state
                                    isReinstallOk = success
                                    reinstallStatus = if (success) msgOk else msgFail
                                    Toast.makeText(context, if (success) msgOk else msgFail, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_cli_title),
                    subtitle = stringResource(R.string.settings_root_cli_desc),
                    icon = Icons.Default.Terminal,
                    checked = cliInstalled,
                    enabled = !isRootBusy
                ) { install ->
                    isRootBusy = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            RootUtils.setTailscaleCliInstalled(context, install)
                        }
                        isRootBusy = false
                        if (success) {
                            cliInstalled = install
                            val message = if (install) {
                                context.getString(R.string.settings_root_cli_installed, RootPaths.current.systemCli)
                            } else {
                                context.getString(R.string.settings_root_cli_removed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_cli_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_vpn_bypass_title),
                    subtitle = stringResource(R.string.settings_root_vpn_bypass_desc),
                    icon = Icons.Default.Shield,
                    checked = rootVpnBypass
                ) { enabled ->
                    rootVpnBypass = enabled
                    GlobalSettings.setRootVpnBypassEnabled(context, enabled)
                    if (ProxyState.isUserLetRunning(context)) {
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                        context.startService(intent)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                // Taking the device breaks another app's tunnel, so switching it
                // on asks first; giving the device back needs no confirmation.
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_take_device_title),
                    subtitle = stringResource(R.string.settings_root_take_device_desc),
                    icon = Icons.Default.PriorityHigh,
                    checked = rootTakeDevice
                ) { enabled ->
                    if (enabled) {
                        showTakeDeviceDialog = true
                    } else {
                        rootTakeDevice = false
                        GlobalSettings.setRootTakeDeviceAnyway(context, false)
                        if (ProxyState.isUserLetRunning(context)) {
                            val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                            context.startService(intent)
                        }
                    }
                }

                // Three outcomes, and the override only silences the two that
                // say we stepped aside. Shared answers first: it is the one the
                // yield flag also claims, and it claims less than the truth.
                if ((rootRoutingYielded || rootRoutingShared) && !rootTakeDevice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        HelpText(
                            if (rootRoutingShared) stringResource(R.string.settings_root_shared_banner)
                            else stringResource(R.string.settings_root_yielded_banner)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_kill_daemon_title),
                    subtitle = stringResource(R.string.settings_root_kill_daemon_desc),
                    icon = Icons.Default.Dangerous,
                    checked = killDaemonOnStop
                ) {
                    killDaemonOnStop = it
                    GlobalSettings.setKillRootDaemonOnStop(context, it)
                }
            }
        }
    }
    }
}
