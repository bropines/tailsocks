package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.getOsVisuals
import io.github.bropines.tailscaled.ui.rememberFullSheetState

private const val EXIT_V4 = "0.0.0.0/0"
private const val EXIT_V6 = "::/0"

/** Online ones first, then by when they were last seen: lastSeen is absent while connected. */
internal fun sortDevices(devices: List<ApiDevice>, sortBy: String): List<ApiDevice> = when (sortBy) {
    "name_desc" -> devices.sortedByDescending { it.shortName.lowercase() }
    "last_seen" -> devices.sortedWith(compareByDescending<ApiDevice> { it.isOnline }.thenByDescending { it.lastSeen.orEmpty() })
    "update" -> devices.sortedWith(compareByDescending<ApiDevice> { it.updateAvailable == true }.thenBy { it.shortName.lowercase() })
    else -> devices.sortedBy { it.shortName.lowercase() }
}

@Composable
fun DevicesTabContent(
    state: Loadable<List<ApiDevice>>,
    selfNodeId: String?,
    onRetry: () -> Unit,
    onDeviceClick: (ApiDevice) -> Unit,
) {
    val ctx = LocalContext.current
    var sortBy by rememberSaveable { mutableStateOf("name") }
    val devices = state.value.orEmpty()
    val sorted = remember(devices, sortBy) { sortDevices(devices, sortBy) }

    var sortSheet by remember { mutableStateOf(false) }
    val strSortTitle = ctx.getString(R.string.admin_devices_cd_sort)
    if (sortSheet) {
        PickerSheet(
            title = strSortTitle,
            options = listOf(
                PickerOption("name", ctx.getString(R.string.pickers_sort_name_az), Icons.Default.SortByAlpha),
                PickerOption("name_desc", ctx.getString(R.string.pickers_sort_name_za), Icons.Default.SortByAlpha),
                PickerOption("last_seen", ctx.getString(R.string.pickers_sort_last_seen), Icons.Default.Schedule),
                PickerOption("update", ctx.getString(R.string.pickers_sort_update), Icons.Default.SystemUpdate),
            ),
            selected = sortBy,
            onPick = { sortBy = it },
            onDismiss = { sortSheet = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ctx.resources.getQuantityString(R.plurals.admin2_devices_count, devices.size, devices.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            IconButton(onClick = { sortSheet = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = strSortTitle)
            }
        }
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when {
            sorted.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            sorted.isEmpty() -> EmptyState(Icons.Default.Devices, ctx.getString(R.string.admin_devices_no_devices))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sorted, key = { it.pathId }) { device ->
                    DeviceRow(device = device, isThisPhone = selfNodeId != null && device.nodeId == selfNodeId, onClick = { onDeviceClick(device) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingIndicatorCompat(modifier: Modifier = Modifier) = LoadingIndicator(modifier)

/** A small label with an icon: a status that does not depend on its colour to be read. */
@Composable
internal fun StatusTag(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceRow(device: ApiDevice, isThisPhone: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val (osIcon, osColor) = getOsVisuals(device.os)
    val expired = device.expires != null && isTimeExpired(device.expires) && device.keyExpiryDisabled != true
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(osIcon, null, tint = osColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(device.shortName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.ipv4 ?: device.addresses.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        Icons.Default.Circle, null, Modifier.size(8.dp),
                        tint = if (device.isOnline) scheme.primary else scheme.outline,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            device.isOnline -> ctx.getString(R.string.admin2_device_online)
                            device.lastSeen != null -> ctx.getString(R.string.admin2_device_last_seen, formatExpires(device.lastSeen))
                            else -> ctx.getString(R.string.admin2_device_offline)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (device.tags.isNotEmpty()) {
                    Text(
                        device.tags.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 1) {
                if (isThisPhone) StatusTag(ctx.getString(R.string.admin2_device_this_phone), scheme.primaryContainer, scheme.onPrimaryContainer)
                if (device.isShared) StatusTag(ctx.getString(R.string.admin2_device_shared), scheme.secondaryContainer, scheme.onSecondaryContainer)
                if (expired) StatusTag(ctx.getString(R.string.admin_devices_status_expired), scheme.errorContainer, scheme.onErrorContainer, Icons.Default.TimerOff)
                else if (device.authorized == false) StatusTag(ctx.getString(R.string.admin_devices_status_pending), scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Default.Schedule)
                if (device.updateAvailable == true) StatusTag(ctx.getString(R.string.admin_device_update_available), scheme.surfaceContainerHighest, scheme.onSurface, Icons.Default.SystemUpdate)
                if (device.multipleConnections == true || !device.tailnetLockError.isNullOrBlank()) {
                    Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = scheme.error)
                }
            }
        }
    }
}

/**
 * One device: its details, and the changes offered on it. Every button hands its change to
 * the safety pipeline, which asks for confirmation — this sheet has no confirm dialogs of its
 * own any more. A device shared in from another tailnet shows, and changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceDetailBottomSheet(
    device: ApiDevice,
    routes: Loadable<DeviceRoutes>?,
    allTailnetTags: List<String>,
    isThisPhone: Boolean,
    canWriteDevices: Boolean,
    canWriteRoutes: Boolean,
    onDismiss: () -> Unit,
    onLoadRoutes: () -> Unit,
    onRename: (String) -> Unit,
    onAuthorize: (Boolean) -> Unit,
    onExpire: () -> Unit,
    onDelete: () -> Unit,
    onUpdateTags: (List<String>) -> Unit,
    onToggleKeyExpiryDisabled: (Boolean) -> Unit,
    onSetRoutes: (before: List<String>, after: List<String>) -> Unit,
) {
    val sheetState = rememberFullSheetState()
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp
    // The sheet is a window of its own whose context ignores the app locale; strings come from
    // the parent's (see wrapContextWithLocale).
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val writable = canWriteDevices && !device.isShared
    val routesWritable = canWriteRoutes && !device.isShared

    var showRename by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }

    LaunchedEffect(device.pathId) { if (routes == null && !device.isShared) onLoadRoutes() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val (osIcon, osColor) = getOsVisuals(device.os)
            Box(Modifier.size(56.dp).clip(CircleShape).background(osColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(osIcon, null, tint = osColor, modifier = Modifier.size(28.dp))
            }
            Text(
                device.shortName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                StatusTag(
                    ctx.getString(if (device.isOnline) R.string.admin2_device_online else R.string.admin2_device_offline),
                    if (device.isOnline) scheme.primaryContainer else scheme.surfaceContainerHighest,
                    if (device.isOnline) scheme.onPrimaryContainer else scheme.onSurface,
                    Icons.Default.Circle,
                )
                if (isThisPhone) StatusTag(ctx.getString(R.string.admin2_device_this_phone), scheme.primaryContainer, scheme.onPrimaryContainer)
                if (device.isShared) StatusTag(ctx.getString(R.string.admin2_device_shared), scheme.secondaryContainer, scheme.onSecondaryContainer)
            }

            if (device.isShared) {
                HelpText(ctx.getString(R.string.admin2_device_shared_desc), modifier = Modifier.padding(horizontal = 24.dp))
            } else if (writable) {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showRename = true }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(ctx.getString(R.string.admin_device_btn_rename), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = { showTags = true }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.AutoMirrored.Filled.Label, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(ctx.getString(R.string.admin_device_btn_tags), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (device.multipleConnections == true || !device.tailnetLockError.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (device.multipleConnections == true) Text(ctx.getString(R.string.admin2_device_multiple_connections), style = MaterialTheme.typography.bodySmall)
                        device.tailnetLockError?.takeIf { it.isNotBlank() }?.let {
                            Text(ctx.getString(R.string.admin2_device_lock_error, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyableDetailBlock(ctx.getString(R.string.admin_device_detail_full_name), device.name.ifBlank { device.shortName })
                device.ipv4?.let { CopyableDetailBlock(ctx.getString(R.string.admin_device_detail_ip), it) }
                device.ipv6?.let { CopyableDetailBlock("IPv6", it) }
                CopyableDetailBlock(
                    ctx.getString(R.string.admin_device_detail_os),
                    listOfNotNull(device.os, device.distro?.name, device.distro?.version).joinToString(" ").ifBlank { ctx.getString(R.string.admin_device_detail_os_unknown) }
                        + (device.clientVersion?.let { " · $it" } ?: ""),
                )
                CopyableDetailBlock(ctx.getString(R.string.admin_device_detail_owner), device.user ?: ctx.getString(R.string.admin_device_detail_owner_na))
                if (!device.isOnline && device.lastSeen != null) {
                    CopyableDetailBlock(ctx.getString(R.string.admin2_device_offline), ctx.getString(R.string.admin2_device_last_seen, formatExpires(device.lastSeen)))
                }
                CopyableDetailBlock(
                    ctx.getString(R.string.admin_device_detail_key_expiry),
                    if (device.keyExpiryDisabled == true) ctx.getString(R.string.admin_device_detail_key_expiry_disabled) else formatExpires(device.expires),
                )
                CopyableDetailBlock(
                    ctx.getString(R.string.admin_device_detail_authorization),
                    ctx.getString(if (device.authorized == true) R.string.admin_device_detail_authorization_approved else R.string.admin_device_detail_authorization_required),
                )
                if (device.tags.isNotEmpty()) CopyableDetailBlock(ctx.getString(R.string.admin_device_detail_tags), device.tags.joinToString(", "))
            }

            if (!device.isShared) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
                ) {
                    val disabled = device.keyExpiryDisabled == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = disabled, enabled = writable, role = Role.Switch) { onToggleKeyExpiryDisabled(it) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ctx.getString(R.string.admin_device_disable_key_expiry), style = MaterialTheme.typography.titleSmall)
                            HelpText(ctx.getString(R.string.admin_device_disable_key_expiry_desc), inClickableRow = true)
                        }
                        Switch(checked = disabled, onCheckedChange = null, enabled = writable)
                    }
                }

                RoutesCard(routes, routesWritable, onLoadRoutes, onSetRoutes)
            }

            if (writable) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (device.authorized == false) {
                        Button(onClick = { onAuthorize(true) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.CheckCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_device_authorize))
                        }
                    } else {
                        OutlinedButton(onClick = { onAuthorize(false) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Cancel, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_device_deauthorize))
                        }
                    }
                    val expired = device.expires != null && isTimeExpired(device.expires) && device.keyExpiryDisabled != true
                    if (!expired && device.keyExpiryDisabled != true) {
                        OutlinedButton(onClick = onExpire, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.TimerOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_device_expire_key))
                        }
                    }
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.admin_device_delete))
                    }
                }
            }
        }
    }

    if (showRename) RenameDeviceDialog(device, onDismiss = { showRename = false }) { showRename = false; onRename(it) }
    if (showTags) TagsDialog(device, allTailnetTags, onDismiss = { showTags = false }) { showTags = false; onUpdateTags(it) }
}

@Composable
private fun RoutesCard(
    routes: Loadable<DeviceRoutes>?,
    writable: Boolean,
    onRetry: () -> Unit,
    onSetRoutes: (List<String>, List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ctx.getString(R.string.admin_device_routing_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val value = routes?.value
            when {
                value == null && routes?.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ctx.getString(R.string.admin2_device_routes_unreadable), style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text(ctx.getString(R.string.admin2_retry)) }
                }
                value == null -> Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicatorCompat(Modifier.size(24.dp))
                }
                value.advertisedRoutes.isEmpty() && value.enabledRoutes.isEmpty() ->
                    Text(ctx.getString(R.string.admin_device_no_routes), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                else -> {
                    val advertised = value.advertisedRoutes
                    val enabled = value.enabledRoutes
                    if (EXIT_V4 in advertised || EXIT_V6 in advertised) {
                        val on = EXIT_V4 in enabled
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = on, enabled = writable, role = Role.Switch) { use ->
                                    val next = enabled.filterNot { it == EXIT_V4 || it == EXIT_V6 } +
                                        if (use) advertised.filter { it == EXIT_V4 || it == EXIT_V6 } else emptyList()
                                    onSetRoutes(enabled, next)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(ctx.getString(R.string.admin_device_exit_node_label), style = MaterialTheme.typography.bodyLarge)
                                HelpText(ctx.getString(R.string.admin_device_exit_node_desc), inClickableRow = true)
                            }
                            Switch(checked = on, onCheckedChange = null, enabled = writable)
                        }
                        HorizontalDivider(color = scheme.outlineVariant)
                    }
                    val subnets = advertised.filter { it != EXIT_V4 && it != EXIT_V6 }
                    if (subnets.isEmpty()) {
                        if (EXIT_V4 !in advertised) Text(ctx.getString(R.string.admin_device_no_subnet_routes), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                    } else {
                        Text(ctx.getString(R.string.admin_device_advertised_subnets), style = MaterialTheme.typography.labelLarge)
                        subnets.forEach { route ->
                            val on = route in enabled
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = on, enabled = writable, role = Role.Switch) { enable ->
                                        onSetRoutes(enabled, if (enable) enabled + route else enabled - route)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // A long IPv6 CIDR would otherwise push the switch off the row.
                                Text(route, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = on, onCheckedChange = null, enabled = writable)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The device's own label only, pre-filled with it — never "host.tail1234", which made a
 * rename into a dotted name — and never blank, which the API would take as "back to the
 * hostname" without a word.
 */
@Composable
private fun RenameDeviceDialog(device: ApiDevice, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val ctx = LocalContext.current
    var name by rememberSaveable(device.pathId) { mutableStateOf(device.shortName) }
    val trimmed = name.trim()
    val ok = trimmed.isNotEmpty() && trimmed != device.shortName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_device_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace(" ", "-") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    label = { Text(ctx.getString(R.string.admin_device_rename_label)) },
                    isError = trimmed.isEmpty(),
                    supportingText = {
                        Text(
                            if (trimmed.isEmpty()) ctx.getString(R.string.admin2_device_rename_blank)
                            else ctx.getString(R.string.admin2_device_rename_help, device.dnsSuffix ?: "ts.net")
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onRename(trimmed) }, enabled = ok) { Text(ctx.getString(R.string.action_rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/**
 * Tags as chips: the policy's and the device's own, each on or off, plus a field that adds a
 * new chip. The field used to hold the current tags as text, so a chip switched off came back
 * from it on save; it is empty now and only ever adds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsDialog(device: ApiDevice, allTags: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val selected = remember(device.pathId) { mutableStateListOf<String>().apply { addAll(device.tags) } }
    val offered = remember(device.pathId) { mutableStateListOf<String>().apply { addAll((allTags + device.tags).distinct().sorted()) } }
    var newTag by remember { mutableStateOf("") }
    val candidate = newTag.trim().let { if (it.isEmpty() || it.startsWith("tag:")) it else "tag:$it" }
    val candidateOk = candidate.length > 4 && candidate.drop(4).all { it.isLetterOrDigit() || it == '-' || it == '_' }
    val changed = selected.toSet() != device.tags.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_device_tags_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (device.tags.isEmpty() && !device.user.isNullOrBlank()) {
                    HelpText(ctx.getString(R.string.admin2_device_tags_owner_warning, device.user), color = MaterialTheme.colorScheme.error)
                }
                if (offered.isEmpty()) {
                    Text(ctx.getString(R.string.admin2_device_tags_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        offered.forEach { tag ->
                            val on = tag in selected
                            FilterChip(
                                selected = on,
                                onClick = { if (on) selected.remove(tag) else selected.add(tag) },
                                label = { Text(tag.removePrefix("tag:")) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it.replace(" ", "") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        label = { Text(ctx.getString(R.string.admin2_device_tags_new)) },
                        placeholder = { Text("tag:server") },
                        isError = newTag.isNotBlank() && !candidateOk,
                        supportingText = { Text(ctx.getString(R.string.admin2_device_tags_invalid)) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = candidateOk,
                        onClick = {
                            if (candidate !in offered) offered.add(candidate)
                            if (candidate !in selected) selected.add(candidate)
                            newTag = ""
                        },
                    ) { Text(ctx.getString(R.string.action_add)) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(selected.toList()) }, enabled = changed) { Text(ctx.getString(R.string.admin_device_tags_update)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}
