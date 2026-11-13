package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminApiActivity
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.MainItem

/** How an item of the main screen is named and drawn: on its tile, and in the list of what can be added. */
internal data class MainItemSpec(
    @param:StringRes val title: Int,
    /** One line for the list of what can be added. */
    @param:StringRes val desc: Int,
    val icon: ImageVector,
    /** Beside [icon], for a screen of two parts. */
    val icon2: ImageVector? = null,
)

/**
 * [tv]: remote management is named there by what it is to a television — the way to put the
 * remote down — and by its own name on a device that has it in the settings.
 */
internal fun MainItem.spec(tv: Boolean = false): MainItemSpec = when (this) {
    MainItem.STATUS -> MainItemSpec(R.string.main_block_status, R.string.main_block_status_desc, Icons.Default.PowerSettingsNew)
    MainItem.EXIT_NODE -> MainItemSpec(R.string.main_block_exit_node, R.string.main_block_exit_node_desc, Icons.Default.Public)
    MainItem.SUMMARY -> MainItemSpec(R.string.settings_connection_summary_title, R.string.main_block_summary_desc, Icons.Default.Summarize)
    MainItem.MANAGED -> MainItemSpec(R.string.main_block_managed, R.string.main_block_managed_desc, Icons.Default.SettingsRemote)
    MainItem.SPEED_GRAPH -> MainItemSpec(R.string.main_block_speed_graph, R.string.main_block_speed_graph_desc, Icons.AutoMirrored.Filled.ShowChart)
    MainItem.TIMELINE -> MainItemSpec(R.string.main_block_timeline, R.string.main_block_timeline_desc, Icons.Default.Timeline)

    // Icons say what is behind them: a terminal, devices, a DNS record, a network check.
    MainItem.CONSOLE -> MainItemSpec(R.string.menu_console, R.string.tile_console_desc, Icons.Default.Terminal)
    MainItem.PEERS -> MainItemSpec(R.string.menu_peers, R.string.tile_peers_desc, Icons.Default.Devices)
    MainItem.LOGS -> MainItemSpec(R.string.menu_logs, R.string.tile_logs_desc, Icons.AutoMirrored.Filled.List)
    MainItem.FILES -> MainItemSpec(R.string.menu_files, R.string.tile_files_desc, Icons.Default.Folder)
    MainItem.DNS -> MainItemSpec(R.string.menu_dns, R.string.tile_dns_desc, Icons.Default.Dns)
    MainItem.NETCHECK -> MainItemSpec(R.string.menu_netcheck, R.string.tile_netcheck_desc, Icons.Default.NetworkCheck)
    MainItem.SETTINGS -> MainItemSpec(R.string.menu_settings, R.string.tile_settings_desc, Icons.Default.Settings)
    MainItem.SERVE -> MainItemSpec(R.string.menu_serve, R.string.tile_serve_desc, Icons.Default.Public, Icons.Default.Pets)
    MainItem.TAILNET_MAP -> MainItemSpec(R.string.tailnet_map_title, R.string.main_block_tailnet_map_desc, Icons.Default.Hub)
    MainItem.TAILCAT -> MainItemSpec(R.string.tailcat_title, R.string.tile_tailcat_desc, Icons.Default.Pets)
    MainItem.TAILDRIVE -> MainItemSpec(R.string.tile_taildrive, R.string.tile_taildrive_desc, Icons.Default.FolderShared)
    MainItem.ADMIN -> MainItemSpec(R.string.tile_admin, R.string.tile_admin_desc, Icons.Default.AdminPanelSettings)

    MainItem.EXIT_PICKER -> MainItemSpec(R.string.tile_exit_picker, R.string.tile_exit_picker_desc, Icons.Default.VpnLock)
    MainItem.SCAN -> MainItemSpec(R.string.tile_scan, R.string.tile_scan_desc, Icons.Default.QrCodeScanner)
    MainItem.ACCOUNTS -> MainItemSpec(R.string.accounts_sheet_title, R.string.tile_accounts_desc, Icons.Default.SwitchAccount)

    MainItem.REMOTE -> MainItemSpec(
        if (tv) R.string.tile_remote_title else R.string.tile_remote, R.string.tile_remote_desc, Icons.Default.SettingsRemote
    )
    MainItem.APP_SPLIT -> MainItemSpec(R.string.title_activity_tun_excluded_apps, R.string.tile_app_split_desc, Icons.Default.Apps)
    MainItem.DNS_SETTINGS -> MainItemSpec(R.string.tile_dns_settings, R.string.tile_dns_settings_desc, Icons.Default.SettingsEthernet)
    MainItem.ROOT -> MainItemSpec(R.string.tile_root, R.string.tile_root_desc, Icons.Default.Tag)
    MainItem.PERMISSIONS -> MainItemSpec(R.string.settings_permissions_title, R.string.tile_permissions_desc, Icons.Default.VerifiedUser)
    MainItem.TAILNET_LOCK -> MainItemSpec(R.string.tile_tailnet_lock, R.string.tile_tailnet_lock_desc, Icons.Default.Lock)
}

@StringRes
internal fun MainItem.Group.title(): Int = when (this) {
    MainItem.Group.BLOCK -> R.string.main_layout_group_blocks
    MainItem.Group.SCREEN -> R.string.main_layout_group_screens
    MainItem.Group.SHORTCUT -> R.string.main_layout_group_shortcuts
    MainItem.Group.SETTING -> R.string.main_layout_group_settings
}

/**
 * The screen a tile opens, for the tiles that open one. The others act on the main screen
 * itself — its exit-node list, its scanner, its accounts, the page of remote management — and
 * MainScreen answers for those.
 */
internal fun MainItem.screen(context: Context): Intent? = when (this) {
    MainItem.CONSOLE -> Intent(context, ConsoleActivity::class.java)
    MainItem.PEERS -> Intent(context, PeersActivity::class.java)
    MainItem.LOGS -> Intent(context, LogsActivity::class.java)
    MainItem.FILES -> Intent(context, FilesActivity::class.java)
    MainItem.DNS -> Intent(context, DnsActivity::class.java)
    MainItem.NETCHECK -> Intent(context, NetcheckActivity::class.java)
    MainItem.SETTINGS -> Intent(context, SettingsActivity::class.java)
    MainItem.SERVE -> Intent(context, ServeActivity::class.java)
    MainItem.TAILNET_MAP -> Intent(context, TailnetMapActivity::class.java)
    MainItem.TAILCAT -> ServeActivity.tailcatIntent(context)
    MainItem.TAILDRIVE -> Intent(context, TaildriveActivity::class.java)
    MainItem.ADMIN -> Intent(context, AdminApiActivity::class.java)
    MainItem.APP_SPLIT -> Intent(context, TunExcludedAppsActivity::class.java)
    MainItem.DNS_SETTINGS -> Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "dns")
    MainItem.ROOT -> Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "root")
    MainItem.PERMISSIONS -> Intent(context, PermissionsActivity::class.java)
    MainItem.TAILNET_LOCK -> Intent(context, TailnetLockActivity::class.java)
    else -> null
}

/**
 * What this device is offered of the main screen's items: a tile for a screen it does not have
 * in its usual place — a television's console, its admin console — is neither drawn nor addable.
 */
@Composable
internal fun rememberMainItemsOffered(): (MainItem) -> Boolean {
    val offered = Feature.entries.filterTo(mutableSetOf()) { it.offered() }
    return remember(offered) { { item: MainItem -> item.feature == null || item.feature in offered } }
}
