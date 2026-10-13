package io.github.bropines.tailscaled.ui

import android.text.format.Formatter
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ManagedCaps
import io.github.bropines.tailscaled.core.ManagedDenial
import io.github.bropines.tailscaled.core.ManagedDeviceInfo
import io.github.bropines.tailscaled.core.ManagedExitNode
import io.github.bropines.tailscaled.core.ManagedFetch
import io.github.bropines.tailscaled.core.ManagedKeys
import io.github.bropines.tailscaled.core.ManagedLevel
import io.github.bropines.tailscaled.core.ManagedLock
import io.github.bropines.tailscaled.core.ManagedOp
import io.github.bropines.tailscaled.core.ManagedPermission
import io.github.bropines.tailscaled.core.ManagedReceive
import io.github.bropines.tailscaled.core.ManagedSetting
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.clearOnly
import io.github.bropines.tailscaled.core.isOlderAppVersion
import io.github.bropines.tailscaled.core.lock
import io.github.bropines.tailscaled.core.setting
import io.github.bropines.tailscaled.core.switches
import java.text.SimpleDateFormat
import java.util.Date

/*
 * The groups of the managing page (ui/RemoteManagePage.kt), each as the line it shows folded
 * and the rows it shows open. A group is drawn only when the device has it (ManagedAccess);
 * what the device has and this caller may not use stands disabled under one reason.
 */

/** What a group asks the page to open: a question before something is done, a picker, a field. */
internal sealed interface ManageAsk {
    data class DeleteFile(val name: String) : ManageAsk
    data class RemoveShare(val name: String) : ManageAsk
    data object FullControlOff : ManageAsk
    data object Forget : ManageAsk
    data object Routes : ManageAsk
    /** The device's storage, for a file to be sent here. */
    data object BrowseFiles : ManageAsk
    /** The device's storage, for a folder to share. */
    data object BrowseFolder : ManageAsk
}

internal fun ManageGroup.icon(): ImageVector = when (this) {
    ManageGroup.CONNECTION -> Icons.Default.Hub
    ManageGroup.FILES -> Icons.AutoMirrored.Filled.InsertDriveFile
    ManageGroup.SHARES -> Icons.Default.FolderShared
    ManageGroup.PERMISSIONS -> Icons.Default.VerifiedUser
    ManageGroup.DEVICE -> Icons.Default.Devices
    ManageGroup.DIAGNOSTICS -> Icons.Default.Troubleshoot
    ManageGroup.PAIRING -> Icons.Default.Link
}

@Composable
internal fun ManageGroup.title(): String = stringResource(
    when (this) {
        ManageGroup.CONNECTION -> R.string.rmanage_group_connection
        ManageGroup.FILES -> R.string.rmanage_group_files
        ManageGroup.SHARES -> R.string.rmanage_group_shares
        ManageGroup.PERMISSIONS -> R.string.rmanage_group_permissions
        ManageGroup.DEVICE -> R.string.rmanage_group_device
        ManageGroup.DIAGNOSTICS -> R.string.rmanage_group_diag
        ManageGroup.PAIRING -> R.string.rmanage_group_pairing
    }
)

/** The groups a device has, in order. The first build has no report and no diagnostics to show. */
internal fun groupsOf(ui: RemoteManageUi.Managing): List<ManageGroup> {
    val access = ui.access
    return ManageGroup.entries.filter { group ->
        when (group) {
            ManageGroup.SHARES -> access.has(ManagedCaps.REPORT) || access.has(ManagedCaps.SHARES)
            ManageGroup.PERMISSIONS -> access.has(ManagedCaps.REPORT) && ui.app != null
            ManageGroup.DIAGNOSTICS -> access.has(ManagedCaps.DIAG)
            else -> true
        }
    }
}

/** A group's one line, and whether something in it wants looking at. */
internal data class GroupSummary(val text: String, val attention: Boolean = false)

@Composable
internal fun summaryOf(group: ManageGroup, ui: RemoteManageUi.Managing): GroupSummary = when (group) {
    ManageGroup.CONNECTION -> {
        val node = ui.exitNode
        GroupSummary(if (node == null) stringResource(R.string.rmanage_sum_no_exit) else stringResource(R.string.rmanage_sum_exit, exitNodeText(node)))
    }
    ManageGroup.FILES -> {
        val waiting = ui.files?.size ?: 0
        val receive = ui.app?.receive
        when {
            ui.status?.running == false -> GroupSummary(stringResource(R.string.rmanage_sum_not_connected), attention = true)
            waiting > 0 -> GroupSummary(pluralStringResource(R.plurals.rmanage_sum_waiting, waiting, waiting))
            receive?.state == ManagedReceive.READY -> GroupSummary(stringResource(R.string.rmanage_sum_saves_to, receive.folder))
            receive?.state == ManagedReceive.INBOX -> GroupSummary(stringResource(R.string.rmanage_sum_inbox))
            else -> GroupSummary(stringResource(R.string.remote_files_empty))
        }
    }
    ManageGroup.SHARES -> {
        val off = ui.settings.setting(ManagedKeys.TAILDRIVE, ManagedSetting.BOOL)?.takeIf { it.error.isEmpty() }?.on == false
        val count = ui.app?.folders?.size ?: ui.app?.folderCount ?: 0
        GroupSummary(
            when {
                off -> stringResource(R.string.rmanage_sum_shares_off)
                count == 0 -> stringResource(R.string.rmanage_shares_none)
                else -> pluralStringResource(R.plurals.rmanage_shares_count, count, count)
            }
        )
    }
    ManageGroup.PERMISSIONS -> {
        val missing = shownPermissions(ui).count { it.state == ManagedPermission.MISSING }
        if (missing == 0) GroupSummary(stringResource(R.string.rmanage_sum_perm_ok))
        else GroupSummary(pluralStringResource(R.plurals.rmanage_sum_perm_missing, missing, missing), attention = true)
    }
    ManageGroup.DEVICE -> {
        val status = ui.status
        val warnings = status?.health?.size ?: 0
        val android = status?.device?.android.orEmpty()
        val version = status?.appVersion.orEmpty()
        when {
            warnings > 0 -> GroupSummary(pluralStringResource(R.plurals.rmanage_warnings, warnings, warnings), attention = true)
            version.isEmpty() -> GroupSummary(stringResource(R.string.remote_status_version, status?.version.orEmpty()))
            android.isEmpty() -> GroupSummary(stringResource(R.string.rmanage_sum_device, version))
            else -> GroupSummary(stringResource(R.string.rmanage_sum_device_android, version, android))
        }
    }
    ManageGroup.DIAGNOSTICS -> {
        val denial = ui.access.gate(ManagedOp.DIAG_LOG).denial
        GroupSummary(if (denial != null) denialShort(denial) else stringResource(R.string.rmanage_sum_diag))
    }
    ManageGroup.PAIRING -> GroupSummary(roleText(ui) + " · " + validText(ui))
}

@Composable
internal fun GroupBody(group: ManageGroup, ui: RemoteManageUi.Managing, actions: RemoteManageActions, hooks: RemoteManageHooks, ask: (ManageAsk) -> Unit) {
    when (group) {
        ManageGroup.CONNECTION -> ConnectionGroup(ui, actions)
        ManageGroup.FILES -> FilesGroup(ui, actions, hooks, ask)
        ManageGroup.SHARES -> SharesGroup(ui, actions, ask)
        ManageGroup.PERMISSIONS -> PermissionsGroup(ui, actions)
        ManageGroup.DEVICE -> DeviceGroup(ui, actions, ask)
        ManageGroup.DIAGNOSTICS -> DiagnosticsGroup(ui, actions)
        ManageGroup.PAIRING -> PairingGroup(ui, ask)
    }
}

// ─── Connection ──────────────────────────────────────────────────────────────────────────────

@Composable
internal fun exitNodeText(node: ManagedExitNode): String =
    if (node.online) node.label else stringResource(R.string.remote_exit_offline, node.label)

@Composable
private fun settingTitle(key: String): String = when (key) {
    ManagedKeys.ACCEPT_DNS -> stringResource(R.string.remote_setting_accept_dns)
    ManagedKeys.ACCEPT_ROUTES -> stringResource(R.string.remote_setting_accept_routes)
    ManagedKeys.EXIT_NODE_LAN -> stringResource(R.string.remote_setting_lan)
    ManagedKeys.TUN_MODE -> stringResource(R.string.rmanage_setting_tun)
    ManagedKeys.AUTO_RECONNECT -> stringResource(R.string.settings_auto_reconnect_title)
    ManagedKeys.KEEP_AWAKE -> stringResource(R.string.settings_keep_awake_title)
    ManagedKeys.ADVERTISE_EXIT_NODE -> stringResource(R.string.settings_advertise_exit_node_title)
    ManagedKeys.ADVERTISE_ROUTES -> stringResource(R.string.rmanage_routes)
    ManagedKeys.TAILDRIVE -> stringResource(R.string.rmanage_setting_taildrive)
    ManagedKeys.TAILDRIVE_PROXY -> stringResource(R.string.rmanage_setting_taildrive_proxy)
    ManagedKeys.FULL_CONTROL -> stringResource(R.string.rmanage_level_full)
    ManagedKeys.EXIT_NODE -> stringResource(R.string.remote_exit_node)
    else -> key
}

@Composable
private fun settingHelp(key: String): String = stringResource(
    when (key) {
        ManagedKeys.ACCEPT_DNS -> R.string.rmanage_setting_accept_dns_help
        ManagedKeys.ACCEPT_ROUTES -> R.string.rmanage_setting_accept_routes_help
        ManagedKeys.EXIT_NODE_LAN -> R.string.rmanage_setting_lan_help
        ManagedKeys.TUN_MODE -> R.string.rmanage_setting_tun_help
        ManagedKeys.AUTO_RECONNECT -> R.string.rmanage_setting_auto_reconnect_help
        ManagedKeys.KEEP_AWAKE -> R.string.rmanage_setting_keep_awake_help
        ManagedKeys.ADVERTISE_EXIT_NODE -> R.string.rmanage_setting_advertise_exit_help
        ManagedKeys.TAILDRIVE -> R.string.rmanage_setting_taildrive_help
        else -> R.string.rmanage_setting_taildrive_proxy_help
    }
)

private fun settingIcon(key: String): ImageVector = when (key) {
    ManagedKeys.ACCEPT_DNS -> Icons.Default.Dns
    ManagedKeys.ACCEPT_ROUTES -> Icons.Default.Map
    ManagedKeys.EXIT_NODE_LAN -> Icons.Default.Router
    ManagedKeys.TUN_MODE -> Icons.Default.VpnLock
    ManagedKeys.AUTO_RECONNECT -> Icons.Default.Autorenew
    ManagedKeys.KEEP_AWAKE -> Icons.Default.BatteryFull
    ManagedKeys.ADVERTISE_EXIT_NODE -> Icons.Default.CellTower
    ManagedKeys.TAILDRIVE -> Icons.Default.FolderShared
    else -> Icons.Default.Lan
}

/** What a setting's change is called in the sentence about its guard, and its value as the device has it now. */
@Composable
internal fun guardedValue(key: String, ui: RemoteManageUi.Managing): Pair<String, String> {
    val title = settingTitle(key)
    if (key == ManagedKeys.EXIT_NODE) return title to (ui.exitNode?.let { exitNodeText(it) } ?: stringResource(R.string.remote_exit_none))
    val row = ui.settings.firstOrNull { it.key == key }
    val value = when {
        row == null || row.error.isNotEmpty() -> stringResource(R.string.remote_setting_unknown)
        row.kind == ManagedSetting.BOOL -> stringResource(if (row.on) R.string.rmanage_on else R.string.rmanage_off)
        else -> row.value
    }
    return title to value
}

@Composable
private fun Switches(keys: List<String>, ui: RemoteManageUi.Managing, actions: RemoteManageActions, note: @Composable (String) -> String? = { null }) {
    ui.settings.switches(keys, ui.access).forEach { row ->
        ManageSwitch(
            setting = row,
            title = settingTitle(row.key),
            help = settingHelp(row.key),
            icon = settingIcon(row.key),
            owner = ui.owner,
            usable = ui.usable,
            onChange = { actions.setSwitch(row.key, it) },
            note = note(row.key),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionGroup(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    val access = ui.access
    val node = ui.exitNode
    val value = when {
        node == null -> stringResource(R.string.remote_exit_none)
        node.place.isEmpty() -> exitNodeText(node)
        else -> exitNodeText(node) + " · " + node.place
    }
    ManageOpenRow(
        icon = Icons.Default.Public,
        title = stringResource(R.string.remote_exit_node),
        value = value,
        enabled = ui.usable && access.gate(ManagedOp.EXIT_NODE_SET).open,
        onClick = actions::openExitNodes
    )
    Switches(ManagedKeys.CONNECTION, ui, actions)

    // The two ways to shake the connection, as rows like the rest: each says what it costs.
    val act = access.gate(ManagedOp.ACTION)
    ManageRow(
        icon = Icons.Default.Autorenew,
        title = stringResource(R.string.remote_reconnect),
        enabled = ui.usable && act.open,
        onClick = actions::reconnect,
        help = stringResource(R.string.rmanage_reconnect_help),
        // While it restarts: said in the row that was pressed, in place of an error.
        reason = if (ui.reconnecting) stringResource(R.string.remote_reconnecting, ui.name) else null,
    ) {
        if (ui.reconnecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    if (access.has(ManagedCaps.RELAYS)) {
        ManageRow(
            icon = Icons.Default.CellTower,
            title = stringResource(R.string.rmanage_relays),
            enabled = ui.usable && act.open,
            onClick = { actions.act(ManagedKeys.ACTION_RELAYS) },
            help = stringResource(R.string.rmanage_relays_help),
        ) {}
    }
    if (!ui.owner && ui.status != null) ManageLine(stringResource(R.string.rmanage_guest_note), Modifier.padding(top = 4.dp), icon = Icons.Default.Lock)
}

// ─── Files ───────────────────────────────────────────────────────────────────────────────────

/** A file's date in the app's own language, which need not be the system's. */
@Composable
private fun fileDate(unixSeconds: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(unixSeconds, locale) { SimpleDateFormat("d MMM, HH:mm", locale).format(Date(unixSeconds * 1000)) }
}

@Composable
private fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** One file of a list: its name, a line of facts, and [trailing] when something can be done with it. */
@Composable
private fun FileRow(name: String, facts: String, focusStop: Boolean, trailing: (@Composable () -> Unit)? = null) {
    // A row nobody may act on has nothing for a remote to stop on, and a list of them could
    // not be read to its end: on a television such a row is a stop itself.
    val source = remember { MutableInteractionSource() }
    val stop = if (isTv && focusStop) Modifier.tvFocusRing(source, MaterialTheme.shapes.medium).focusable(interactionSource = source) else Modifier
    Row(stop.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** How many files of a list the page draws; the rest are counted. */
private const val FILES_SHOWN = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilesGroup(ui: RemoteManageUi.Managing, actions: RemoteManageActions, hooks: RemoteManageHooks, ask: (ManageAsk) -> Unit) {
    val access = ui.access
    val status = ui.status
    val send = ui.send
    val running = status?.running != false

    // Said before a file is picked, not after: whether it can arrive at all.
    val blocked = when {
        !running -> stringResource(R.string.rmanage_cannot_receive, ui.name)
        ui.sendBlocked != null -> stringResource(R.string.rmanage_cannot_send, ui.name, ui.sendBlocked)
        else -> null
    }
    ManageButton(
        stringResource(R.string.rmanage_send),
        onClick = { hooks.sendFile?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.AutoMirrored.Filled.Send,
        enabled = blocked == null && send?.progress == null && ui.problem?.stale != true && ui.problem?.final != true && status != null,
        primary = true,
        working = send?.progress != null,
    )
    when {
        blocked != null -> ManageLine(blocked, tone = ManageTone.WARN)
        send?.progress != null -> ManageLine(stringResource(R.string.rmanage_send_progress, send.progress.replace('\n', ' ')))
        send != null -> {
            if (send.sent.size == 1) ManageLine(stringResource(R.string.rmanage_send_done_one, send.sent.first()), tone = ManageTone.GOOD)
            else if (send.sent.size > 1) ManageLine(pluralStringResource(R.plurals.rmanage_send_done, send.sent.size, send.sent.size), tone = ManageTone.GOOD)
            send.failed.forEach { (name, why) -> ManageLine(stringResource(R.string.rmanage_send_failed, name, why), tone = ManageTone.WARN) }
        }
    }

    // Where what is sent ends up, as the device's app says it.
    ui.app?.receive?.let { receive ->
        val text = when (receive.state) {
            ManagedReceive.READY ->
                if (receive.free >= 0) stringResource(R.string.rmanage_receive_ready_free, receive.folder, sizeText(receive.free))
                else stringResource(R.string.rmanage_receive_ready, receive.folder)
            ManagedReceive.NEEDS_ACCESS -> stringResource(R.string.rmanage_receive_needs_access, receive.folder)
            ManagedReceive.UNAVAILABLE -> stringResource(R.string.rmanage_receive_unavailable, receive.folder)
            ManagedReceive.INBOX -> stringResource(R.string.rmanage_receive_inbox)
            else -> null
        }
        if (text != null) {
            val wrong = receive.state == ManagedReceive.NEEDS_ACCESS || receive.state == ManagedReceive.UNAVAILABLE
            ManageLine(text, tone = if (wrong) ManageTone.WARN else ManageTone.PLAIN, icon = if (wrong) null else Icons.Default.Folder)
        }
        val canAsk = receive.state == ManagedReceive.NEEDS_ACCESS &&
            ui.app?.permissions?.firstOrNull { it.id == ManagedPermission.STORAGE }?.ask == true &&
            access.gate(ManagedOp.PERMISSION_REQUEST).open
        if (canAsk || receive.reset) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canAsk) ManageButton(stringResource(R.string.rmanage_ask_storage), onClick = { actions.askPermission(ManagedPermission.STORAGE) }, enabled = ui.usable)
                if (receive.reset) ManageButton(stringResource(R.string.rmanage_receive_reset), onClick = { actions.act(ManagedKeys.ACTION_RECEIVE_DEFAULT) }, enabled = ui.usable)
            }
        }
        ui.asked[ManagedPermission.STORAGE]?.let { ManageLine(askedText(it, ui.name)) }
    }

    // A file asked of the device: on its way here, here, or not sent.
    ui.fetch?.let { fetch ->
        when (fetch.state) {
            ManagedFetch.SENDING -> ManageLine(stringResource(R.string.rmanage_fetch_sending, fetch.name, sizeText(fetch.size)))
            ManagedFetch.SENT -> {
                ManageLine(
                    if (fetch.age >= 60) stringResource(R.string.rmanage_fetch_sent_ago, fetch.name, remoteDurationText(fetch.age))
                    else stringResource(R.string.rmanage_fetch_sent, fetch.name),
                    tone = ManageTone.GOOD
                )
                hooks.openInbox?.let { open -> TextButton(onClick = open) { Text(stringResource(R.string.rmanage_fetch_open)) } }
            }
            ManagedFetch.FAILED -> ManageLine(
                if (fetch.error.isEmpty()) stringResource(R.string.rmanage_fetch_failed_plain, fetch.name)
                else stringResource(R.string.rmanage_fetch_failed, fetch.name, fetch.error),
                tone = ManageTone.WARN
            )
        }
    }

    val waiting = ui.files
    val saved = ui.app?.saved.orEmpty()
    val delete = access.gate(ManagedOp.FILES_DELETE)
    // The first build has one list, and it is all of "Received files".
    val twoLists = access.has(ManagedCaps.REPORT)
    if (waiting != null) {
        if (!twoLists || waiting.isNotEmpty()) {
            ManageSubtitle(stringResource(if (twoLists) R.string.rmanage_files_waiting else R.string.remote_files_title))
        }
        if (waiting.isEmpty() && (!twoLists || saved.isEmpty())) {
            Text(stringResource(R.string.remote_files_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        waiting.take(FILES_SHOWN).forEach { file ->
            FileRow(file.name, sizeText(file.size) + " · " + fileDate(file.modTime), focusStop = !delete.open) {
                if (delete.open) {
                    IconButton(onClick = { ask(ManageAsk.DeleteFile(file.name)) }, enabled = ui.usable) {
                        Icon(Icons.Default.Delete, stringResource(R.string.remote_file_delete, file.name))
                    }
                }
            }
        }
        val more = waiting.size - FILES_SHOWN
        if (more > 0) {
            Text(pluralStringResource(R.plurals.remote_files_more, more, more), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Offered by the device's own count of what waits, and only into a folder that works.
        val movable = (ui.app?.receive?.waiting ?: 0) > 0 && ui.app?.receive?.state == ManagedReceive.READY
        if (movable && access.has(ManagedCaps.SAVE) && access.gate(ManagedOp.ACTION).open) {
            ManageButton(stringResource(R.string.rmanage_save_received), onClick = { actions.act(ManagedKeys.ACTION_SAVE_RECEIVED) }, enabled = ui.usable)
        }
    }
    if (twoLists && saved.isNotEmpty()) {
        ManageSubtitle(stringResource(R.string.rmanage_files_saved))
        saved.forEach { file ->
            FileRow(
                file.name,
                listOf(sizeText(file.size), fileDate(file.time), file.folder).filter { it.isNotEmpty() }.joinToString(" · "),
                focusStop = true,
            )
        }
        HelpText(stringResource(R.string.rmanage_files_saved_help), Modifier.padding(top = 4.dp))
    }

    val browse = access.gate(ManagedOp.STORAGE_LIST)
    if (browse.offered) {
        ManageGap()
        ManageOpenRow(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.rmanage_browse),
            help = stringResource(R.string.rmanage_browse_help),
            reason = browse.denial?.let { denialRow(it) },
            enabled = ui.usable && status?.fetch?.state != ManagedFetch.SENDING,
            onClick = { ask(ManageAsk.BrowseFiles) }
        )
        FullControlNote(browse.denial == ManagedDenial.NEEDS_FULL, ui.name)
    }
}

// ─── Shared folders ──────────────────────────────────────────────────────────────────────────

@Composable
private fun SharesGroup(ui: RemoteManageUi.Managing, actions: RemoteManageActions, ask: (ManageAsk) -> Unit) {
    val access = ui.access
    Switches(ManagedKeys.SHARING, ui, actions)

    val folders = ui.app?.folders
    val remove = access.gate(ManagedOp.SHARES_REMOVE)
    when {
        // A guest is told how many, never which.
        folders == null -> {
            val count = ui.app?.folderCount ?: 0
            Text(
                if (count == 0) stringResource(R.string.rmanage_shares_none) else pluralStringResource(R.plurals.rmanage_shares_guest, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        folders.isEmpty() -> Text(
            stringResource(R.string.rmanage_shares_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        else -> folders.forEach { folder ->
            val source = remember { MutableInteractionSource() }
            val stop = if (isTv && !remove.open) Modifier.tvFocusRing(source, MaterialTheme.shapes.medium).focusable(interactionSource = source) else Modifier
            Row(stop.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(folder.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                if (remove.open) {
                    IconButton(onClick = { ask(ManageAsk.RemoveShare(folder.name)) }, enabled = ui.usable) {
                        Icon(Icons.Default.Delete, stringResource(R.string.rmanage_share_remove, folder.name))
                    }
                }
            }
        }
    }

    val add = access.gate(ManagedOp.SHARES_ADD)
    if (add.offered) {
        ManageOpenRow(
            icon = Icons.Default.CreateNewFolder,
            title = stringResource(R.string.rmanage_share_add),
            help = stringResource(R.string.rmanage_share_add_help),
            reason = add.denial?.let { denialRow(it) },
            enabled = ui.usable,
            onClick = { ask(ManageAsk.BrowseFolder) }
        )
    }
    // Said once for the card: where the thing its rows wait for is switched on.
    FullControlNote(add.denial == ManagedDenial.NEEDS_FULL || needsFull(ManagedKeys.SHARING, ui), ui.name)
}

// ─── Permissions ─────────────────────────────────────────────────────────────────────────────

/** The permissions worth a row: the ones this build has words for, without those the device has no use for. */
internal fun shownPermissions(ui: RemoteManageUi.Managing): List<ManagedPermission> =
    ui.app?.permissions.orEmpty().filter { it.id in ManagedPermission.KNOWN && it.state != ManagedPermission.NA }

@Composable
private fun permissionTitle(id: String): String = stringResource(
    when (id) {
        ManagedPermission.STORAGE -> R.string.rmanage_perm_storage
        "notifications" -> R.string.rmanage_perm_notifications
        "battery" -> R.string.rmanage_perm_battery
        "vpn" -> R.string.rmanage_perm_vpn
        "alarms" -> R.string.rmanage_perm_alarms
        "install" -> R.string.rmanage_perm_install
        else -> R.string.rmanage_perm_autostart
    }
)

/** What became of a request to show a permission's page, as the contract words it. */
@Composable
internal fun askedText(shown: ManagedShown, name: String): String = stringResource(
    when (shown) {
        ManagedShown.SHOWN -> R.string.rmanage_asked_shown
        ManagedShown.NOTIFIED -> R.string.rmanage_asked_notified
        ManagedShown.WAITING -> R.string.rmanage_asked_waiting
    },
    name
)

@Composable
private fun PermissionsGroup(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    val gate = ui.access.gate(ManagedOp.PERMISSION_REQUEST)
    val waiting = ui.app?.asked
    val rows = shownPermissions(ui)
    rows.forEach { permission ->
        val title = permissionTitle(permission.id)
        val (icon, tint, state) = when (permission.state) {
            ManagedPermission.GRANTED -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.rmanage_perm_granted))
            ManagedPermission.MISSING -> Triple(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.tertiary, stringResource(R.string.rmanage_perm_missing))
            else -> Triple(Icons.Default.HelpOutline, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.rmanage_perm_unknown))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(state, style = MaterialTheme.typography.bodySmall, color = tint)
            }
            // The device's own flag decides whether it can be asked; this caller's class is in it.
            if (permission.ask && gate.open) {
                val label = stringResource(R.string.rmanage_perm_ask_named, title)
                OutlinedButton(
                    onClick = { actions.askPermission(permission.id) },
                    enabled = ui.usable,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(start = 32.dp, top = 4.dp).semantics { contentDescription = label }
                ) { Text(stringResource(R.string.rmanage_perm_ask)) }
            } else if (permission.id == "vpn" && permission.state == ManagedPermission.MISSING) {
                Text(
                    stringResource(R.string.rmanage_perm_vpn_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }
            // What this page was answered, or what still waits there from before it was opened.
            val outcome = ui.asked[permission.id] ?: waiting?.takeIf { it.id == permission.id }?.let { ManagedShown.of(it.result) }
            if (outcome != null && permission.state != ManagedPermission.GRANTED) {
                ManageLine(askedText(outcome, ui.name), Modifier.padding(start = 32.dp))
            }
        }
    }
    if (rows.any { it.state == ManagedPermission.MISSING }) DeniedNote(gate, ui.name)
    HelpText(stringResource(R.string.rmanage_permissions_help), Modifier.padding(top = 6.dp))
}

// ─── Device ──────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun backendStateText(state: String): String = when (state) {
    "NeedsLogin" -> stringResource(R.string.remote_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.remote_state_needs_approval)
    "Starting" -> stringResource(R.string.remote_state_starting)
    "Stopped" -> stringResource(R.string.remote_state_stopped)
    else -> state.ifEmpty { stringResource(R.string.remote_status_not_running) }
}

@Composable
private fun modeText(device: ManagedDeviceInfo): String? = when (device.mode) {
    "proxy" -> stringResource(R.string.rmanage_mode_proxy)
    "root" -> stringResource(R.string.rmanage_mode_root)
    "tun" -> stringResource(
        when (device.engine) {
            "native" -> R.string.rmanage_mode_tun_native
            "hev" -> R.string.rmanage_mode_tun_hev
            else -> R.string.rmanage_mode_tun
        }
    )
    else -> null
}

@Composable
private fun DeviceGroup(ui: RemoteManageUi.Managing, actions: RemoteManageActions, ask: (ManageAsk) -> Unit) {
    val status = ui.status ?: return
    val access = ui.access
    val device = status.device?.takeIf { access.has(ManagedCaps.DEVICE) }

    if (!status.running) ManageFact(stringResource(R.string.rmanage_fact_state), backendStateText(status.backendState), tone = ManageTone.WARN)
    if (status.appVersion.isNotEmpty()) {
        val older = ui.appVersion.isNotEmpty() && isOlderAppVersion(status.appVersion, ui.appVersion)
        ManageFact(
            stringResource(R.string.rmanage_fact_app),
            if (older) stringResource(R.string.rmanage_fact_app_older, status.appVersion, ui.appVersion) else status.appVersion,
        )
    }
    if (status.version.isNotEmpty()) ManageFact(stringResource(R.string.rmanage_fact_daemon), status.version)
    if (device != null) {
        if (device.android.isNotEmpty()) {
            ManageFact(
                stringResource(R.string.rmanage_fact_android),
                if (device.sdk > 0) stringResource(R.string.rmanage_fact_android_value, device.android, device.sdk) else device.android,
            )
        }
        if (device.model.isNotEmpty()) ManageFact(stringResource(R.string.rmanage_fact_model), device.model)
        modeText(device)?.let { ManageFact(stringResource(R.string.rmanage_fact_mode), it) }
        val left = device.keyExpiry - ui.now / 1000
        ManageFact(
            stringResource(R.string.rmanage_fact_key),
            when {
                device.keyExpiry <= 0 -> stringResource(R.string.rmanage_key_never)
                left <= 0 -> stringResource(R.string.rmanage_key_expired)
                else -> stringResource(R.string.rmanage_key_in, remoteDurationText(left))
            },
            tone = if (device.keyExpiry > 0 && left < 7 * 86_400) ManageTone.WARN else ManageTone.PLAIN,
        )
        if (device.build.isNotEmpty()) ManageFact(stringResource(R.string.rmanage_fact_build), device.build)
    }

    if (status.health.isEmpty()) ManageLine(stringResource(R.string.remote_status_health_ok), tone = ManageTone.GOOD)
    else {
        ManageSubtitle(stringResource(R.string.rmanage_health_title))
        status.health.forEach { ManageLine(it, tone = ManageTone.WARN) }
    }

    val offer = status.advertise?.takeIf { access.has(ManagedCaps.ADVERTISE) }
    val waitingApproval = stringResource(R.string.rmanage_not_approved)
    val rows = ui.settings.switches(ManagedKeys.DEVICE, access)
    if (rows.isNotEmpty()) ManageGap()
    Switches(ManagedKeys.DEVICE, ui, actions) { key ->
        if (key == ManagedKeys.ADVERTISE_EXIT_NODE && offer?.exitNode?.let { it.on && !it.approved } == true) waitingApproval else null
    }

    val routes = ui.settings.setting(ManagedKeys.ADVERTISE_ROUTES, ManagedSetting.CIDRS)?.takeIf { access.has(ManagedCaps.ADVERTISE) }
    if (routes != null) {
        val approved = offer?.routes.orEmpty().associate { it.cidr to it.approved }
        val list = routes.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val pending = stringResource(R.string.rmanage_routes_pending, "%s")
        val value = if (list.isEmpty()) stringResource(R.string.rmanage_routes_none)
        else list.joinToString(", ") { if (approved[it] == false) pending.format(it) else it }
        val lock = routes.lock(ui.owner)
        val locked = if (lock == ManagedLock.UNKNOWN || lock == ManagedLock.READ_ONLY) lockText(lock) else null
        ManageOpenRow(
            icon = Icons.Default.AltRoute,
            title = stringResource(R.string.rmanage_routes),
            value = value,
            // Nothing to withdraw, and a list cannot be written at this level.
            reason = locked ?: if (routes.clearOnly && list.isEmpty()) denialRow(ManagedDenial.NEEDS_FULL) else null,
            enabled = ui.usable && routes.writable,
            onClick = { ask(ManageAsk.Routes) }
        )
    }
    FullControlNote(ui.owner && (routes?.clearOnly == true || needsFull(ManagedKeys.DEVICE, ui)), ui.name)
    if (!ui.owner && (rows.isNotEmpty() || routes != null)) ManageLine(stringResource(R.string.rmanage_deny_owner), icon = Icons.Default.Lock)
    if (access.firstBuild) HelpText(stringResource(R.string.rmanage_first_build, ui.name), Modifier.padding(top = 8.dp))
}

// ─── Diagnostics ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DiagnosticsGroup(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    val log = ui.access.gate(ManagedOp.DIAG_LOG)
    val report = ui.access.gate(ManagedOp.DIAG_REPORT)
    // The two take the same class and level: one reason covers both.
    ManageOpenRow(
        icon = Icons.Default.Subject,
        title = stringResource(R.string.rmanage_diag_log),
        help = stringResource(R.string.rmanage_diag_log_help),
        reason = log.denial?.let { denialRow(it) },
        enabled = ui.usable,
        onClick = { actions.openDiag(log = true) }
    )
    ManageOpenRow(
        icon = Icons.Default.Article,
        title = stringResource(R.string.rmanage_diag_report),
        help = stringResource(R.string.rmanage_diag_report_help),
        reason = report.denial?.let { denialRow(it) },
        enabled = ui.usable,
        onClick = { actions.openDiag(log = false) }
    )
    FullControlNote(log.denial == ManagedDenial.NEEDS_FULL || report.denial == ManagedDenial.NEEDS_FULL, ui.name)
}

/** Whether a switch of [keys] waits for Full control: its row says so in two words, the card once in full. */
private fun needsFull(keys: List<String>, ui: RemoteManageUi.Managing): Boolean =
    ui.settings.switches(keys, ui.access).any { it.lock(ui.owner) == ManagedLock.NEEDS_FULL }

/** Where Full control is switched on, said once under the rows that wait for it. Nothing when [shown] is false. */
@Composable
private fun FullControlNote(shown: Boolean, name: String) {
    if (shown) ManageLine(denialText(ManagedDenial.NEEDS_FULL, name), Modifier.padding(top = 4.dp), icon = Icons.Default.Lock)
}

// ─── Pairing ─────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun roleText(ui: RemoteManageUi.Managing): String =
    stringResource(if (ui.owner) R.string.remote_role_owner else R.string.remote_role_guest)

@Composable
internal fun validText(ui: RemoteManageUi.Managing): String =
    if (ui.left < 0) stringResource(R.string.rmanage_until_revoked) else stringResource(R.string.rmanage_left, remoteDurationText(ui.left))

@Composable
internal fun levelText(level: String): String =
    stringResource(if (level == ManagedLevel.FULL) R.string.rmanage_level_full else R.string.rmanage_level_basic)

@Composable
private fun PairingGroup(ui: RemoteManageUi.Managing, ask: (ManageAsk) -> Unit) {
    val access = ui.access
    ManageFact(stringResource(R.string.rmanage_fact_class), roleText(ui))
    HelpText(stringResource(R.string.rmanage_class_help))
    if (access.levelKnown) {
        ManageFact(stringResource(R.string.rmanage_fact_level), levelText(access.level))
        HelpText(stringResource(R.string.rmanage_level_help))
    }
    ManageFact(stringResource(R.string.rmanage_fact_valid), validText(ui))

    // Full control can be given up from afar, never taken.
    val full = ui.settings.setting(ManagedKeys.FULL_CONTROL, ManagedSetting.BOOL)
    val canDrop = full != null && full.on && full.lock(ui.owner) == ManagedLock.NONE && access.levelKnown
    ManageGap()
    if (canDrop) {
        ManageButton(stringResource(R.string.rmanage_full_off), onClick = { ask(ManageAsk.FullControlOff) }, enabled = ui.usable)
    }
    TextButton(
        onClick = { ask(ManageAsk.Forget) },
        enabled = !ui.busy,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) { Text(stringResource(R.string.remote_forget)) }
}
