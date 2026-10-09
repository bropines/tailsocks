package io.github.bropines.tailscaled.admin.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadingIndicatorCompat
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiClientConnectivity
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.safety.ReadOnlyBanner
import io.github.bropines.tailscaled.admin.ConsoleLinks
import io.github.bropines.tailscaled.ui.HelpText
import kotlin.math.roundToInt

/** What the sheet's controls do; the defaults do nothing, for previews. */
class DeviceActions(
    val onRename: () -> Unit = {},
    val onTags: () -> Unit = {},
    val onSetIp: () -> Unit = {},
    val onKeyExpiryDisabled: (Boolean) -> Unit = {},
    val onAuthorize: (Boolean) -> Unit = {},
    val onExpire: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSetRoutes: (before: List<String>, after: List<String>) -> Unit = { _, _ -> },
    val onRetryRoutes: () -> Unit = {},
    val onRetryDetail: () -> Unit = {},
)

/** The list's device with what only a full read carries: routes, connectivity, SSH, distro. */
internal fun withDetail(listed: ApiDevice, full: ApiDevice?): ApiDevice = if (full == null) listed else listed.copy(
    advertisedRoutes = full.advertisedRoutes ?: listed.advertisedRoutes,
    enabledRoutes = full.enabledRoutes ?: listed.enabledRoutes,
    clientConnectivity = full.clientConnectivity ?: listed.clientConnectivity,
    sshEnabled = full.sshEnabled ?: listed.sshEnabled,
    multipleConnections = full.multipleConnections ?: listed.multipleConnections,
    distro = full.distro ?: listed.distro,
)

/**
 * One device in full, and every change offered on it. Each button hands its change to the
 * safety pipeline; this sheet asks nothing itself. A device shared in from another tailnet
 * shows, explains why, and offers no change.
 */
@Composable
fun DeviceDetailSheet(device: ApiDevice, state: ConsoleState, vm: AdminConsoleViewModel?, selfNodeId: String?, onDismiss: () -> Unit) {
    val dvm: DevicesViewModel? = if (vm != null) viewModel() else null
    val full = dvm?.ui?.collectAsState()?.value?.detail?.get(device.pathId)
    val routes = state.routes[device.pathId]
    val now = if (LocalInspectionMode.current) PREVIEW_NOW else remember(state.devices.loadedAt) { System.currentTimeMillis() }
    // Read again after every reload of the list: a change that applied reloads it.
    LaunchedEffect(device.pathId, state.devices.loadedAt) { if (vm != null) dvm?.loadDetail(vm, device.pathId) }
    LaunchedEffect(device.pathId) { if (!device.isShared && routes == null) vm?.loadRoutes(device) }

    var dialog by rememberSaveable(device.pathId) { mutableStateOf<String?>(null) }
    val self = state.self.nodeId

    DeviceSheet(onDismiss) {
        DeviceDetailContent(
            device = withDetail(device, full?.value),
            full = full,
            routes = routes,
            now = now,
            isThisPhone = selfNodeId != null && device.nodeId == selfNodeId,
            canWriteDevices = state.canWrite(AdminArea.DEVICES),
            canWriteRoutes = state.canWrite(AdminArea.ROUTES),
            canSetIp = state.caps?.has(BackendFeature.DEVICE_IPV4) != false,
            actions = DeviceActions(
                onRename = { dialog = DIALOG_RENAME },
                onTags = { dialog = DIALOG_TAGS },
                onSetIp = { dialog = DIALOG_IP },
                onKeyExpiryDisabled = { vm?.setKeyExpiryDisabled(device, it) },
                onAuthorize = { vm?.setDeviceAuthorized(device, it) },
                onExpire = { vm?.expireDevice(device) },
                onDelete = { vm?.deleteDevice(device) },
                onSetRoutes = { before, after ->
                    vm?.let { v -> v.propose(DeviceChanges.setRoutes(v.text, device, before, after, self), after = { v.loadRoutes(device) }) }
                },
                onRetryRoutes = { vm?.loadRoutes(device) },
                onRetryDetail = { if (vm != null) dvm?.loadDetail(vm, device.pathId) },
            ),
        )
    }

    // In the parent composition, after the sheet: a dialog window over it, with this screen's language.
    when (dialog) {
        DIALOG_RENAME -> RenameDialog(
            device = device,
            onDismiss = { dialog = null },
            onRename = { name ->
                dialog = null
                vm?.renameDevice(device, name)
            },
            onReset = {
                dialog = null
                vm?.let { it.propose(DeviceChanges.resetName(it.text, device, self)) }
            },
        )
        DIALOG_TAGS -> TagsDialog(
            device = device,
            policyTags = state.policyTags,
            onDismiss = { dialog = null },
            onSave = { tags ->
                dialog = null
                vm?.let { it.propose(DeviceChanges.setTags(it.text, device, tags, self)) }
            },
        )
        DIALOG_IP -> Ipv4Dialog(
            device = device,
            taken = Ipv4Rules.holders(state.devices.value.orEmpty(), device) +
                state.services.value.orEmpty().flatMap { s -> s.addrs.filter { ':' !in it }.map { it to s.name } },
            onDismiss = { dialog = null },
            onSet = { ip ->
                dialog = null
                vm?.let { it.propose(DeviceChanges.setIpv4(it.text, device, ip, self)) }
            },
        )
    }
}

private const val DIALOG_RENAME = "rename"
private const val DIALOG_TAGS = "tags"
private const val DIALOG_IP = "ip"

/** The sheet's body, apart so a preview can draw it without the sheet's window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceDetailContent(
    device: ApiDevice,
    full: Loadable<ApiDevice>?,
    routes: Loadable<DeviceRoutes>?,
    now: Long,
    isThisPhone: Boolean,
    canWriteDevices: Boolean,
    canWriteRoutes: Boolean,
    canSetIp: Boolean,
    actions: DeviceActions,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String, String) -> Unit = { label, text -> copyToClipboard(ctx, clipboard, scope, label, text) }
    val shared = device.isShared
    val writable = canWriteDevices && !shared
    val knownRoutes = DeviceQueries.knownRoutes(device, routes?.value)
    val badges = DeviceQueries.badges(device, now, knownRoutes, if (isThisPhone) device.nodeId else null)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OsAvatar(device.os, 56.dp)
            Text(device.shortName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            PresenceLine(device, now, systemLine(device))
            if (badges.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    badges.forEach { BadgeChip(it) }
                }
            }
        }

        if (shared) {
            ReadOnlyBanner(
                ctx.getString(R.string.admin_dev_shared_title), ctx.getString(R.string.admin_dev_shared_desc),
                Modifier.padding(horizontal = 16.dp), Icons.Default.Share,
            )
        }
        if (device.authorized == false && !shared) ApprovalCard(writable, actions)
        Problems(device)

        Section(ctx.getString(R.string.admin_dev_section_addresses)) {
            device.addresses.forEach { a ->
                val label = ctx.getString(if (':' in a) R.string.admin_dev_ipv6 else R.string.admin_dev_ipv4)
                InfoRow(label, a, mono = true, onCopy = copy)
            }
            device.name.trimEnd('.').takeIf { it.isNotBlank() }?.let {
                InfoRow(ctx.getString(R.string.admin_dev_magicdns), it, mono = true, onCopy = copy)
            }
        }

        Section(ctx.getString(R.string.admin_dev_section_details)) { Details(device, now, copy) }

        Section(ctx.getString(R.string.admin_dev_section_connectivity)) {
            Connectivity(device.clientConnectivity, full, actions.onRetryDetail, copy)
        }

        if (!shared) {
            Section(ctx.getString(R.string.admin_dev_routes_title)) {
                Routes(routes, knownRoutes, canWriteRoutes, actions)
            }
            Actions(device, now, writable, canSetIp, isThisPhone, actions)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

/** A label over its value; with [onCopy], tapping the row copies the value. */
@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, onCopy: ((String, String) -> Unit)? = null) {
    val ctx = LocalContext.current
    val tap = if (onCopy != null) Modifier.clickable(onClickLabel = ctx.getString(R.string.admin_dev_copy, label)) { onCopy(label, value) } else Modifier
    Row(Modifier.fillMaxWidth().then(tap).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
        }
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            CopyMark()
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** Copied node state and a Tailnet Lock signature problem: things to act on, said first. */
@Composable
private fun Problems(device: ApiDevice) {
    val ctx = LocalContext.current
    val lockError = device.tailnetLockError?.takeIf { it.isNotBlank() }
    if (device.multipleConnections != true && lockError == null) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (device.multipleConnections == true) WarningLine(ctx.getString(R.string.admin2_device_multiple_connections))
            lockError?.let { WarningLine(ctx.getString(R.string.admin2_device_lock_error, it)) }
        }
    }
}

/** A device waiting for approval: why it cannot reach the tailnet, and the button that lets it. */
@Composable
private fun ApprovalCard(writable: Boolean, actions: DeviceActions) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HowToReg, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                ctx.getString(R.string.admin_dev_needs_approval_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (writable) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = { actions.onAuthorize(true) }, shape = MaterialTheme.shapes.medium) {
                    Text(ctx.getString(R.string.admin_dev_action_authorize))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(device: ApiDevice, now: Long, copy: (String, String) -> Unit) {
    val ctx = LocalContext.current
    DeviceQueries.instant(device.created)?.let { InfoRow(ctx.getString(R.string.admin_dev_created), whenText(ctx, it, now)) }
    InfoRow(
        ctx.getString(R.string.admin_dev_last_seen),
        when {
            device.isOnline -> ctx.getString(R.string.admin_dev_online_now)
            else -> DeviceQueries.instant(device.lastSeen)?.let { whenText(ctx, it, now) } ?: ctx.getString(R.string.admin_dev_never_seen)
        },
    )
    val system = listOfNotNull(
        DeviceQueries.osName(device.os),
        listOfNotNull(device.distro?.name, device.distro?.version).joinToString(" ").ifBlank { null },
    ).joinToString(" · ")
    if (system.isNotBlank()) InfoRow(ctx.getString(R.string.admin_dev_system), system)
    device.clientVersion?.takeIf { it.isNotBlank() }?.let { v ->
        InfoRow(
            ctx.getString(R.string.admin_dev_version),
            if (device.updateAvailable == true) ctx.getString(R.string.admin_dev_version_update, v) else v,
            mono = false,
        )
    }
    // The API cannot start a client update; the console's own page for the machine can.
    if (device.updateAvailable == true && !device.isShared) ConsoleLinks.machine(device)?.let { url ->
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            TextButton(onClick = { ConsoleLinks.open(ctx, url) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_update_in_console))
            }
            HelpText(ctx.getString(R.string.admin_update_help))
        }
    }
    val user = device.user?.takeIf { it.isNotBlank() }
    when {
        device.isTagged -> InfoRow(ctx.getString(R.string.admin_dev_owner), user?.let { ctx.getString(R.string.admin_dev_owner_tags, it) } ?: ctx.getString(R.string.admin_dev_tags))
        user != null -> InfoRow(ctx.getString(R.string.admin_dev_owner), user)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(ctx.getString(R.string.admin_dev_tags), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (device.tags.isEmpty()) Text(ctx.getString(R.string.admin2_device_tags_none), style = MaterialTheme.typography.bodyMedium)
        else FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            device.tags.forEach { TagChip(it) }
        }
    }
    InfoRow(
        ctx.getString(R.string.admin_dev_key_expiry),
        when (val k = DeviceQueries.keyExpiry(device, now)) {
            KeyExpiry.Disabled -> ctx.getString(R.string.admin_dev_key_disabled)
            KeyExpiry.Unknown -> ctx.getString(R.string.admin_dev_not_reported)
            is KeyExpiry.Expired -> ctx.getString(R.string.admin_dev_key_expired, dateText(ctx, k.at))
            is KeyExpiry.Expiring -> ctx.getString(R.string.admin_dev_key_expires, dateText(ctx, k.at))
            is KeyExpiry.Valid -> ctx.getString(R.string.admin_dev_key_expires, dateText(ctx, k.at))
        },
    )
    InfoRow(ctx.getString(R.string.admin_dev_ssh), ConsoleText.onOff(ctx, device.sshEnabled).takeIf { device.sshEnabled != null } ?: ctx.getString(R.string.admin_dev_not_reported))
    if (device.isEphemeral == true) InfoRow(ctx.getString(R.string.admin_dev_ephemeral), ctx.getString(R.string.admin_dev_ephemeral_value))
    if (device.blocksIncomingConnections == true) InfoRow(ctx.getString(R.string.admin_dev_incoming), ctx.getString(R.string.admin_dev_incoming_blocked))
    device.tailnetLockKey?.takeIf { it.isNotBlank() }?.let { InfoRow(ctx.getString(R.string.admin_dev_lock_key), it, mono = true, onCopy = copy) }
    device.nodeId.takeIf { it.isNotBlank() }?.let { InfoRow(ctx.getString(R.string.admin_dev_node_id), it, mono = true, onCopy = copy) }
}

/** The device's network as it reported it — only a full read carries it, so it can lag or be missing. */
@Composable
private fun Connectivity(c: ApiClientConnectivity?, full: Loadable<ApiDevice>?, onRetry: () -> Unit, copy: (String, String) -> Unit) {
    val ctx = LocalContext.current
    if (c == null) {
        when {
            full?.loading == true || full == null -> Box(Modifier.fillMaxWidth().padding(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            full.error != null -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HelpText(
                    ctx.getString(R.string.admin_dev_detail_failed, ConsoleText.error(ctx, full.error)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(ctx.getString(R.string.admin2_retry)) }
            }
            else -> Text(
                ctx.getString(R.string.admin_dev_connectivity_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        return
    }
    val notReported = ctx.getString(R.string.admin_dev_not_reported)
    val relay = c.latency.entries.firstOrNull { it.value.preferred == true }
        ?: c.latency.entries.filter { it.value.latencyMs != null }.minByOrNull { it.value.latencyMs!! }
    InfoRow(
        ctx.getString(R.string.admin_dev_relay),
        relay?.let { (region, l) -> l.latencyMs?.let { ctx.getString(R.string.admin_dev_relay_value, region, it.roundToInt()) } ?: region } ?: notReported,
    )
    val supports = c.clientSupports
    InfoRow(ctx.getString(R.string.admin_dev_udp), supports?.udp?.let { ConsoleText.onOff(ctx, it) } ?: notReported)
    InfoRow(ctx.getString(R.string.admin_dev_ipv6_support), supports?.ipv6?.let { ConsoleText.onOff(ctx, it) } ?: notReported)
    InfoRow(
        ctx.getString(R.string.admin_dev_port_mapping),
        if (supports == null) notReported
        else listOfNotNull("UPnP".takeIf { supports.upnp == true }, "NAT-PMP".takeIf { supports.pmp == true }, "PCP".takeIf { supports.pcp == true })
            .joinToString(", ").ifBlank { ctx.getString(R.string.admin_dev_port_mapping_none) },
    )
    InfoRow(
        ctx.getString(R.string.admin_dev_nat),
        when (c.mappingVariesByDestIP) {
            true -> ctx.getString(R.string.admin_dev_nat_hard)
            false -> ctx.getString(R.string.admin_dev_nat_easy)
            null -> notReported
        },
    )
    if (c.endpoints.isNotEmpty()) InfoRow(ctx.getString(R.string.admin_dev_endpoints), c.endpoints.joinToString("\n"), mono = true, onCopy = copy)
}

/**
 * Advertised against approved: the exit node as one switch (both address families), each subnet
 * route with its state in words, and one button for every route still waiting. Turning an
 * approved exit node off is HIGH in the pipeline; the rest MEDIUM.
 */
@Composable
private fun Routes(routes: Loadable<DeviceRoutes>?, known: DeviceRoutes?, writable: Boolean, actions: DeviceActions) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    if (known == null) {
        if (routes?.error != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.getString(R.string.admin2_device_routes_unreadable), style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onRetryRoutes) { Text(ctx.getString(R.string.admin2_retry)) }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { LoadingIndicatorCompat(Modifier.size(32.dp)) }
        }
        return
    }
    val advertised = known.advertisedRoutes
    val enabled = known.enabledRoutes
    val exitAdvertised = advertised.any(DeviceQueries::isExitRoute)
    val exitEnabled = enabled.any(DeviceQueries::isExitRoute)
    val subnets = (advertised + enabled).filterNot(DeviceQueries::isExitRoute).distinct()
    if (!exitAdvertised && !exitEnabled && subnets.isEmpty()) {
        Text(
            ctx.getString(R.string.admin_dev_routes_none),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        return
    }
    fun stateOf(route: String, on: Boolean) = ctx.getString(
        when {
            on && route !in advertised -> R.string.admin_dev_route_stale
            on -> R.string.admin_dev_route_approved
            else -> R.string.admin_dev_route_pending
        }
    )
    if (exitAdvertised || exitEnabled) {
        val exitRoute = DeviceQueries.EXIT_V4
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = exitEnabled, enabled = writable && (exitAdvertised || exitEnabled), role = Role.Switch) { use ->
                    val next = enabled.filterNot(DeviceQueries::isExitRoute) + if (use) advertised.filter(DeviceQueries::isExitRoute) else emptyList()
                    actions.onSetRoutes(enabled, next)
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ctx.getString(R.string.admin_dev_exit_node), style = MaterialTheme.typography.bodyLarge)
                Text(stateOf(exitRoute, exitEnabled), style = MaterialTheme.typography.labelMedium, color = if (exitEnabled) scheme.primary else scheme.tertiary)
                HelpText(ctx.getString(R.string.admin_dev_exit_desc), inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = exitEnabled, onCheckedChange = null, enabled = writable)
        }
        if (subnets.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant)
    }
    subnets.forEach { route ->
        val on = route in enabled
        val canFlip = writable && (on || route in advertised)
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = on, enabled = canFlip, role = Role.Switch) { use ->
                    actions.onSetRoutes(enabled, if (use) enabled + route else enabled - route)
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // A long IPv6 prefix would push the switch off the row otherwise.
                Text(route, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateOf(route, on), style = MaterialTheme.typography.labelMedium, color = if (on) scheme.primary else scheme.tertiary)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = null, enabled = canFlip)
        }
    }
    val pending = advertised.filter { it !in enabled && !DeviceQueries.isExitRoute(it) }
    if (writable && pending.size >= 2) {
        TextButton(
            onClick = { actions.onSetRoutes(enabled, (enabled + pending).distinct()) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(ctx.resources.getQuantityString(R.plurals.admin_dev_routes_approve_all, pending.size, pending.size)) }
    }
}

@Composable
private fun Actions(device: ApiDevice, now: Long, writable: Boolean, canSetIp: Boolean, isThisPhone: Boolean, actions: DeviceActions) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    if (!writable) {
        Text(
            ctx.getString(R.string.admin_dev_cannot_write),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.outline,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(ctx.getString(R.string.admin_dev_action_rename), Icons.Default.Edit, actions.onRename, Modifier.weight(1f))
            ActionButton(ctx.getString(R.string.admin_dev_action_tags), Icons.AutoMirrored.Filled.Label, actions.onTags, Modifier.weight(1f))
            if (canSetIp) ActionButton(ctx.getString(R.string.admin_dev_action_ip), Icons.Default.Numbers, actions.onSetIp, Modifier.weight(1f))
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        ) {
            val disabled = device.keyExpiryDisabled == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = disabled, role = Role.Switch) { actions.onKeyExpiryDisabled(it) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_dev_key_expiry_switch), style = MaterialTheme.typography.titleSmall)
                    HelpText(ctx.getString(R.string.admin_dev_key_expiry_switch_desc), inClickableRow = true)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = disabled, onCheckedChange = null)
            }
        }
        // Said before the buttons that would cut this phone off, not after them.
        if (isThisPhone) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp).padding(top = 2.dp), tint = scheme.error)
                Spacer(Modifier.width(8.dp))
                HelpText(ctx.getString(R.string.admin_dev_this_phone_warning), color = scheme.error)
            }
        }
        if (device.authorized != false) {
            OutlinedButton(onClick = { actions.onAuthorize(false) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Cancel, null)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_dev_action_deauthorize))
            }
        } else {
            Button(onClick = { actions.onAuthorize(true) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_dev_action_authorize))
            }
        }
        val key = DeviceQueries.keyExpiry(device, now)
        if (key !is KeyExpiry.Expired && key != KeyExpiry.Disabled) {
            OutlinedButton(onClick = actions.onExpire, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.TimerOff, null)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_dev_action_expire))
            }
        }
        Button(
            onClick = actions.onDelete,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
        ) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text(ctx.getString(R.string.admin_dev_action_delete))
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        // Three in a row on a phone: the default 24dp sides would leave the label no room.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}
