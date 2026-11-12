package io.github.bropines.tailscaled.ui

import android.text.format.Formatter
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.models.PeerData

/*
 * What the tailnet map (TailnetMapScreen.kt) opens over itself: a device's sheet, a hub's, the
 * list of the peers a window had no room for, and the filters. Each sheet's content is a
 * composable of its own under the sheet, which is what the previews draw.
 */

/** The icon and the sentence for [path] in a sheet, with the endpoint apart where there is one. */
private class RouteLine(val icon: ImageVector, val text: String, val address: String? = null)

private fun routeLine(path: PeerPath, w: PeerPathWords): RouteLine = when (path) {
    is PeerPath.Direct -> RouteLine(Icons.Default.Lan, if (path.local) w.routeDirectLocal else w.routeDirectInternet, path.endpoint)
    is PeerPath.PeerRelay -> RouteLine(
        Icons.Default.DeviceHub,
        path.node?.let { w.routePeerRelayFormat.format(it) } ?: w.routePeerRelay,
        path.address
    )
    is PeerPath.Derp -> RouteLine(Icons.Default.Router, w.routeDerpFormat.format(w.region(path.region, path.city)))
    is PeerPath.NotYet -> RouteLine(
        Icons.Default.Schedule,
        path.home?.let { w.routeNotYetFormat.format(w.region(it, path.homeCity)) } ?: w.routeNotYet
    )
    is PeerPath.Offline -> RouteLine(Icons.Default.CloudOff, w.routeOffline)
    is PeerPath.Self -> RouteLine(
        Icons.Default.Router,
        path.home?.let { w.routeHomeFormat.format(w.region(it, path.homeCity)) } ?: w.noHome
    )
}

/**
 * What a tap on a node shows: who it is, how it is reached, and its traffic — the rate now
 * and the total of the session, each way. Short on purpose; [onOpen] leads to the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapDetailSheet(
    peer: PeerData,
    path: PeerPath,
    rate: TrafficRate,
    rxBytes: Long,
    txBytes: Long,
    words: PeerPathWords,
    isSelf: Boolean,
    onOpen: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val formatRate = rememberRateFormatter()
    val route = routeLine(path, words)
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (isSelf) colors.primary else colors.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        getOsVisuals(peer.iconOs()).first, contentDescription = null,
                        tint = if (isSelf) colors.onPrimary else colors.onSecondaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer.getDisplayName(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            if (isSelf) stringResource(R.string.peer_ping_self) else null,
                            peer.os?.takeIf { it.isNotBlank() } ?: stringResource(R.string.peer_status_os_unknown),
                            peer.getPrimaryIp()
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(route.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(route.text, style = MaterialTheme.typography.bodyMedium)
                    if (route.address != null) {
                        Text(
                            route.address,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant
                        )
                    }
                    if (peer.exitNode == true) {
                        Text(
                            stringResource(R.string.peer_exit_node_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrafficTile(
                    Icons.Default.ArrowDownward, stringResource(R.string.tailnet_map_received), colors.primary,
                    formatRate(rate.rxBps),
                    stringResource(R.string.tailnet_map_total_format, Formatter.formatShortFileSize(context, rxBytes)),
                    Modifier.weight(1f)
                )
                TrafficTile(
                    Icons.Default.ArrowUpward, stringResource(R.string.tailnet_map_sent), colors.tertiary,
                    formatRate(rate.txBps),
                    stringResource(R.string.tailnet_map_total_format, Formatter.formatShortFileSize(context, txBytes)),
                    Modifier.weight(1f)
                )
            }
            if (isSelf) {
                Text(
                    stringResource(R.string.tailnet_map_self_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            // A television's sheet needs a stop for the remote to land on, and has no handle
            // to drag it away by.
            if (onOpen != null || isTv) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (isTv) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    if (onOpen != null) {
                        FilledTonalButton(onClick = { onDismiss(); onOpen() }) { Text(stringResource(R.string.tailnet_map_open_peer)) }
                    }
                }
            }
        }
    }
}

/** One direction in a sheet: the rate now as the figure, the session's total under it. */
@Composable
private fun TrafficTile(icon: ImageVector, title: String, tint: Color, rate: String, total: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(rate, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(total, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A peer as a row of a sheet's list: what its node would say — the path, the rate — and a tap that opens its own sheet. */
@Composable
private fun MapPeerRow(node: MapPeer, words: PeerPathWords, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val formatRate = rememberRateFormatter()
    val offline = node.tier == MapTier.OFFLINE
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRing(source)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            getOsVisuals(node.peer.iconOs()).first, contentDescription = null,
            tint = if (offline) colors.outline else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (offline) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                routeLine(node.path, words).text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (node.rate.totalBps > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatRate(node.rate.totalBps),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * The peers the window had no room to draw, as a list: on a tailnet with hundreds of nodes
 * this is where most of them are, so each row says what its node would have — the path, the
 * rate — and opens the same sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenPeersSheet(peers: List<MapPeer>, words: PeerPathWords, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.tailnet_map_more_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Text(
                stringResource(R.string.tailnet_map_more_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(peers, key = { it.key }) { node ->
                    MapPeerRow(node, words) {
                        onDismiss()
                        onSelect(node.key)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * What a tap on a hub shows: what it is, everything going through it — the rate now and the
 * session's totals, summed over its peers — and those peers, each a tap from its own sheet.
 * This is the answer the Routes scheme exists for: who is behind which relay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapHubSheet(group: MapGroup, words: MapWords, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AppModalSheet(onDismissRequest = onDismiss) { MapHubPanel(group, words, onSelect, onDismiss) }
}

/** [MapHubSheet]'s content, apart from the sheet so a preview can draw it. */
@Composable
internal fun MapHubPanel(group: MapGroup, words: MapWords, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val formatRate = rememberRateFormatter()
    val (fill, onFill) = hubColors(group.kind)
    val count = pluralStringResource(R.plurals.tailnet_map_devices, group.members.size, group.members.size)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(fill), contentAlignment = Alignment.Center) {
                Icon(hubIcon(group), contentDescription = null, tint = onFill, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(words.title(group), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        words.kind(group).takeIf { group.kind.relay },
                        // The city is the title; the code is what `tailscale status` and the logs call the region.
                        group.name.takeIf { group.kind == MapGroupKind.DERP && group.detail != null },
                        count
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrafficTile(
                Icons.Default.ArrowDownward, stringResource(R.string.tailnet_map_received), colors.primary,
                formatRate(group.rate.rxBps),
                stringResource(R.string.tailnet_map_total_format, Formatter.formatShortFileSize(context, group.members.sumOf { it.peer.rxBytes ?: 0L })),
                Modifier.weight(1f)
            )
            TrafficTile(
                Icons.Default.ArrowUpward, stringResource(R.string.tailnet_map_sent), colors.tertiary,
                formatRate(group.rate.txBps),
                stringResource(R.string.tailnet_map_total_format, Formatter.formatShortFileSize(context, group.members.sumOf { it.peer.txBytes ?: 0L })),
                Modifier.weight(1f)
            )
        }
        Column {
            Text(
                stringResource(if (group.kind.relay) R.string.tailnet_map_hub_through else R.string.tailnet_map_hub_members),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(group.members, key = { it.key }) { node -> MapPeerRow(node, words.path) { onSelect(node.key) } }
            }
        }
        if (isTv) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

/** The filters, as a sheet over the map: every change is on the map behind it at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapFilterSheet(
    filter: MapFilter,
    options: MapFilterOptions,
    users: Map<Long, String>,
    shown: Int,
    total: Int,
    words: MapWords,
    onFilter: (MapFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalSheet(onDismissRequest = onDismiss) { MapFilterPanel(filter, options, users, shown, total, words, onFilter, onDismiss) }
}

/**
 * [MapFilterSheet]'s content: a search, then a section of chips for every way the tailnet can
 * be narrowed — and only those it offers a choice in ([MapFilterOptions]). Under them, how
 * many devices are left, which is the thing to watch while tapping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapFilterPanel(
    filter: MapFilter,
    options: MapFilterOptions,
    users: Map<Long, String>,
    shown: Int,
    total: Int,
    words: MapWords,
    onFilter: (MapFilter) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tailnet_map_filters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (!filter.isEmpty) {
                TextButton(onClick = { onFilter(MapFilter()) }) { Text(stringResource(R.string.tailnet_map_filter_reset)) }
            }
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { onFilter(filter.copy(query = it)) },
                placeholder = { Text(stringResource(R.string.tailnet_map_filter_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(onClick = { onFilter(filter.copy(query = "")) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            FilterSection(R.string.tailnet_map_filter_section_state) {
                FilterToggle(stringResource(R.string.tailnet_map_filter_online), filter.onlineOnly) { onFilter(filter.copy(onlineOnly = it)) }
                FilterToggle(stringResource(R.string.tailnet_map_filter_traffic), filter.withTraffic) { onFilter(filter.copy(withTraffic = it)) }
            }
            FilterSection(R.string.tailnet_map_filter_section_path) {
                for (path in MapPathFilter.entries) {
                    FilterToggle(stringResource(path.label), path in filter.paths) {
                        onFilter(filter.copy(paths = if (it) filter.paths + path else filter.paths - path))
                    }
                }
            }
            // One system or one owner is no choice. A filter already on stays reachable all the same.
            val systems = (options.os + filter.os).distinct()
            if (systems.size > 1 || filter.os.isNotEmpty()) {
                FilterSection(R.string.tailnet_map_filter_section_os) {
                    for (os in systems) {
                        FilterToggle(words.os(os), os in filter.os) { onFilter(filter.copy(os = if (it) filter.os + os else filter.os - os)) }
                    }
                }
            }
            val owners = (options.owners + filter.owners).distinct()
            if (owners.size > 1 || filter.owners.isNotEmpty()) {
                FilterSection(R.string.tailnet_map_filter_section_owner) {
                    for (owner in owners) {
                        FilterToggle(words.owner(owner, users), owner in filter.owners) {
                            onFilter(filter.copy(owners = if (it) filter.owners + owner else filter.owners - owner))
                        }
                    }
                }
            }
            if (options.exitNodes || options.subnetRouters || filter.exitNodes || filter.subnetRouters) {
                FilterSection(R.string.tailnet_map_filter_section_role) {
                    if (options.exitNodes || filter.exitNodes) {
                        FilterToggle(stringResource(R.string.peers_tab_exit_nodes), filter.exitNodes) { onFilter(filter.copy(exitNodes = it)) }
                    }
                    if (options.subnetRouters || filter.subnetRouters) {
                        FilterToggle(stringResource(R.string.tailnet_map_filter_subnet_routers), filter.subnetRouters) {
                            onFilter(filter.copy(subnetRouters = it))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.tailnet_map_filter_shown_format, shown, total),
                style = MaterialTheme.typography.bodyMedium,
                color = if (shown == 0) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = onDone) { Text(stringResource(R.string.tailnet_map_filter_done)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: Int, chips: @Composable () -> Unit) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun FilterToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (on) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}
