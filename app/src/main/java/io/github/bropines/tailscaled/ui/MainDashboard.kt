package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/*
 * The home screen of a window that is not a phone. MainScreen decides which windows those
 * are — a phone, upright or turned, and a foldable keep the layouts they had — and lays the
 * dashboard out with the two pieces in this file: columns that end on the window's bottom
 * line, and the card of the tailnet's devices that is what makes the height worth filling.
 */

/**
 * The home screen of a window that is not a phone and has no fold to lay out around: a
 * tablet either way up, a desktop window. [width] and [height] are the space under the top
 * bar; [status] is the status column of the phone layouts and [menu] their grid of
 * destinations, so the dashboard shows the same things and adds what a phone has no room
 * for: the tailnet's devices ([peers], see [DevicesGlance]) and the live state of the
 * service, cards that run to the bottom of the window.
 *
 * Lying on its side (or wide enough either way) the window gets two columns: the status
 * with the live state under it — the service now, and in detail — and the destinations
 * with the devices under them. Upright, the status goes across the top, and under it the
 * menu with the live state beside a column of devices; on a window too narrow for two
 * columns of cards, the menu across and the devices under it.
 */
@Composable
internal fun MainDashboard(
    window: WindowLayout,
    width: Dp,
    height: Dp,
    status: @Composable ColumnScope.() -> Unit,
    menu: @Composable (columns: Int, cardHeight: Dp) -> Unit,
    peers: List<PeerData>?,
    connected: Boolean,
    onOpenPeers: (peerId: String?) -> Unit,
) {
    val margin = window.margin
    val inner = width - margin * 2
    // On a window that is not tall, two rows of shorter cards leave the devices more room.
    val cardHeight = if (window.heightClass == WindowHeightClass.EXPANDED) 96.dp else 88.dp
    val pings = rememberGlancePings(peers)
    val nowMillis = rememberGlanceNow(peers)
    val devices: @Composable () -> Unit = {
        DevicesGlance(peers = peers, connected = connected, pings = pings, nowMillis = nowMillis, onOpenPeers = onOpenPeers)
    }
    val live: @Composable () -> Unit = { LiveDiagnosticsCard(fillHeight = true) }
    val statusColumn: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = status)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = margin)
    ) {
        Spacer(Modifier.height(DASHBOARD_EDGE))
        val minHeight = height - DASHBOARD_EDGE * 2
        when {
            window.widthClass >= WindowWidthClass.EXPANDED || width > height -> {
                // The phone-landscape layout's proportion: a share of the width, never more
                // than a column of cards needs.
                val statusWidth = (width * 0.38f).coerceIn(280.dp, 400.dp)
                DashboardColumns(
                    minHeight = minHeight,
                    widths = listOf(statusWidth, null),
                    tops = listOf(statusColumn, { menu(dashboardMenuColumns(inner - statusWidth - DASHBOARD_GAP), cardHeight) }),
                    fills = listOf(live, devices),
                    columnGap = DASHBOARD_GAP,
                )
            }
            // Two columns of cards at least as wide as a phone's: the menu in the phone's two
            // columns, the live state under it, and the devices beside both — the longest list
            // gets the longest column. The menu's gap between the columns, so the devices' edge
            // lines up with the cards'.
            columnsFor(inner, 320.dp, MENU_GAP, maxColumns = 2) > 1 -> DashboardColumns(
                minHeight = minHeight,
                header = statusColumn,
                widths = listOf(null, null),
                tops = listOf({ menu(2, cardHeight) }, {}),
                fills = listOf(live, devices),
                columnGap = MENU_GAP,
            )
            else -> DashboardColumns(
                minHeight = minHeight,
                header = statusColumn,
                widths = listOf(null),
                tops = listOf({ menu(menuColumnsFor(inner), cardHeight) }),
                fills = listOf(devices),
            )
        }
        Spacer(Modifier.height(DASHBOARD_EDGE))
    }
}

/**
 * The menu's columns beside the status: [menuColumnsFor]'s two to four, and all eight in one
 * row once they fit at its smallest card — a desktop-sized window, where two rows of four
 * would be eight wide slabs.
 */
private fun dashboardMenuColumns(width: Dp): Int =
    if (width + MENU_GAP >= (MENU_MIN_CARD + MENU_GAP) * 8) 8 else menuColumnsFor(width)

/** menuColumnsFor's narrowest card. */
private val MENU_MIN_CARD = 100.dp

/** Above and below the dashboard, as the phone layouts leave under the top bar. */
private val DASHBOARD_EDGE = 12.dp

/** Between the status column and the menu, and under the status where it runs across. */
private val DASHBOARD_GAP = 24.dp

/** MenuGrid's own gap between two cards. */
private val MENU_GAP = 16.dp

/**
 * What "last seen" on the devices card counts from: the clock at each new list. A demo
 * carries no clock, so there time stands at the newest stamp it holds, as on the Peers
 * screen — a render made next month reads as one made today.
 */
@Composable
private fun rememberGlanceNow(peers: List<PeerData>?): Long {
    val demo = LocalDemo.current
    return remember(peers) {
        if (demo == null) System.currentTimeMillis()
        else peers.orEmpty().mapNotNull { parseRfc3339Millis(it.lastSeen) }.maxOrNull() ?: System.currentTimeMillis()
    }
}

/**
 * The dashboard's columns, side by side under an optional [header] that spans them. Each
 * column holds what it holds at its own height ([tops]) and, under that, one card ([fills])
 * stretched so that every column ends on one line: the bottom of [minHeight] — the window —
 * or lower when a column needs more, and then the page scrolls. A column of banners next to
 * a short menu does not leave a hole under the menu, and neither leaves the bottom of the
 * window empty.
 *
 * [widths] gives each column a width, or null for a share of what the fixed ones leave. A
 * fill card is never made shorter than [fillMinHeight]: squeezed to a strip it would say
 * nothing, and scrolling down to it is better. A fill is laid out at exactly its size, so
 * its root should fill what it is given.
 *
 * Measured in one pass, from the slots' own heights: the preview renderer draws the first
 * frame only, and a layout that settles on the second would never be seen there.
 */
@Composable
internal fun DashboardColumns(
    minHeight: Dp,
    widths: List<Dp?>,
    tops: List<@Composable () -> Unit>,
    fills: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    headerGap: Dp = DASHBOARD_GAP,
    columnGap: Dp = DASHBOARD_GAP,
    fillGap: Dp = MENU_GAP,
    fillMinHeight: Dp = 280.dp,
) {
    require(widths.size == tops.size && tops.size == fills.size) { "a width, a top and a fill per column" }
    Layout(contents = listOf(header) + tops + fills, modifier = modifier) { slots, constraints ->
        val n = widths.size
        val gap = columnGap.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val fixed = widths.sumOf { it?.roundToPx() ?: 0 }
        val shared = widths.count { it == null }
        val share = if (shared == 0) 0 else ((width - fixed - gap * (n - 1)) / shared).coerceAtLeast(0)
        val columnWidths = widths.map { it?.roundToPx() ?: share }

        val headerPlaceables = slots[0].map { it.measure(Constraints.fixedWidth(width)) }
        val columnsTop = headerPlaceables.sumOf { it.height } + if (headerPlaceables.isEmpty()) 0 else headerGap.roundToPx()
        val topPlaceables = List(n) { i -> slots[1 + i].map { it.measure(Constraints.fixedWidth(columnWidths[i])) } }
        val fillOffsets = List(n) { i ->
            columnsTop + topPlaceables[i].sumOf { it.height } + if (topPlaceables[i].isEmpty()) 0 else fillGap.roundToPx()
        }
        val bottom = maxOf(
            minHeight.roundToPx().coerceAtLeast(constraints.minHeight),
            fillOffsets.maxOf { it + fillMinHeight.roundToPx() },
        )
        val fillPlaceables = List(n) { i ->
            slots[1 + n + i].map { it.measure(Constraints.fixed(columnWidths[i], (bottom - fillOffsets[i]).coerceAtLeast(0))) }
        }
        layout(width, bottom) {
            var y = 0
            headerPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            var x = 0
            for (i in 0 until n) {
                y = columnsTop
                topPlaceables[i].forEach { it.placeRelative(x, y); y += it.height }
                fillPlaceables[i].forEach { it.placeRelative(x, fillOffsets[i]) }
                x += columnWidths[i] + gap
            }
        }
    }
}

/** No screen shows more rows than this; past it the card is a list nobody reads at a glance. */
private const val GLANCE_MAX_ROWS = 24

/** A row narrower than this cuts the name short; past two of these, the rows stand in columns. */
private val GLANCE_MIN_CELL = 300.dp

/**
 * The tailnet at a glance, on a large window's dashboard: the other devices, online ones
 * first as on the Peers screen, each with its address, how it is reached and its latency,
 * the exit node in use marked. As many as the card has room for, and the way to all of them.
 *
 * [peers] null is nothing to list yet: [connected] tells "still coming up" from "not
 * running". [pings] holds the daemon's raw answers by address, as the Peers screen keeps
 * them; [nowMillis] is what "last seen" counts from.
 */
@Composable
internal fun DevicesGlance(
    peers: List<PeerData>?,
    connected: Boolean,
    pings: Map<String, String>,
    nowMillis: Long,
    /** Null for the list as a whole, a node's id for its details. */
    onOpenPeers: (peerId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The live state card's look: the two stand side by side on the dashboard.
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.peers_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (peers.isNullOrEmpty()) ""
                    else stringResource(R.string.summary_online, peers.count { it.online == true }, peers.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onOpenPeers(null) }) {
                    Text(stringResource(R.string.tablet_main_peers_all), maxLines = 1)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            val body = Modifier.fillMaxWidth().weight(1f)
            when {
                peers == null -> GlanceMessage(
                    stringResource(if (connected) R.string.tablet_main_peers_waiting else R.string.tablet_main_peers_offline),
                    body
                )
                peers.isEmpty() -> GlanceMessage(stringResource(R.string.tablet_main_peers_none), body)
                else -> {
                    val selectedLabel = stringResource(R.string.peer_exit_node_selected)
                    val offeredLabel = stringResource(R.string.peer_exit_node_offered)
                    FittingGrid(modifier = body.padding(end = 12.dp, top = 4.dp)) {
                        peers.take(GLANCE_MAX_ROWS).forEach { peer ->
                            GlanceRow(
                                peer = peer,
                                ping = pingStateOf(pings[peer.getPrimaryIp()]),
                                nowMillis = nowMillis,
                                selectedLabel = selectedLabel,
                                offeredLabel = offeredLabel,
                                onClick = { onOpenPeers(peer.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The card with no rows: the app's empty state, in the middle of the space they would take. */
@Composable
private fun GlanceMessage(text: String, modifier: Modifier) {
    EmptyState(icon = Icons.Default.Devices, text = text, modifier = modifier.padding(end = 12.dp))
}

/**
 * Rows in as many columns as [GLANCE_MIN_CELL] allows, in reading order, and only the ones
 * that fit the height whole: a row cut in half at the bottom of the card would look like a
 * list to scroll, and this is not one — the Peers screen is. Unbounded, everything is placed.
 */
@Composable
private fun FittingGrid(
    modifier: Modifier = Modifier,
    columnGap: Dp = 16.dp,
    rowGap: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hGap = columnGap.roundToPx()
        val vGap = rowGap.roundToPx()
        val width = constraints.maxWidth
        val columns = columnsFor(width.toDp(), GLANCE_MIN_CELL, columnGap, maxColumns = 3)
        val cell = ((width - hGap * (columns - 1)) / columns).coerceAtLeast(0)
        val placed = mutableListOf<Triple<Placeable, Int, Int>>()
        var y = 0
        for (row in measurables.chunked(columns)) {
            val cells = row.map { it.measure(Constraints.fixedWidth(cell)) }
            val height = cells.maxOf { it.height }
            if (constraints.hasBoundedHeight && y + height > constraints.maxHeight) break
            cells.forEachIndexed { i, p -> placed += Triple(p, i * (cell + hGap), y) }
            y += height + vGap
        }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else (y - vGap).coerceAtLeast(0)
        layout(width, height) { placed.forEach { (p, x, top) -> p.placeRelative(x, top) } }
    }
}

/**
 * One device: its OS, its name, and under it the address with how it is reached now and
 * the last round trip — or, offline, how long ago it was seen. The online dot repeats in
 * colour what the second line says in words.
 */
@Composable
private fun GlanceRow(
    peer: PeerData,
    ping: PeerPingState,
    nowMillis: Long,
    selectedLabel: String,
    offeredLabel: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val online = peer.online == true
    val ip = peer.getPrimaryIp()
    val path = when {
        !online -> parseRfc3339Millis(peer.lastSeen)?.let { agoText(context, it, nowMillis) }
        !peer.curAddr.isNullOrEmpty() -> stringResource(R.string.peer_path_direct)
        !peer.peerRelay.isNullOrEmpty() -> stringResource(R.string.peer_path_peer_relay)
        !peer.relay.isNullOrEmpty() -> stringResource(R.string.peer_state_relay_format, peer.relay)
        else -> null
    }
    // A figure measured before the device went away is not its latency now.
    val latency = if (online) (ping as? PeerPingState.Measured)?.latency else null
    val stateWord = stringResource(if (online) R.string.peer_status_online else R.string.peer_status_offline)
    val (osIcon, osColor) = getOsVisuals(peer.os)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = listOfNotNull(stateWord, path, latency).joinToString(", ") }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(osColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(osIcon, null, Modifier.size(18.dp), tint = osColor)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.getDisplayName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The latency stands outside the part that is cut short, as on the Peers screen:
            // a narrow row loses the path before the figure.
            Row {
                Text(
                    listOfNotNull(ip, path).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (latency != null) {
                    Text(
                        buildAnnotatedString {
                            append(" · ")
                            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(latency) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        ExitNodeBadge(
            peer = peer,
            selectedLabel = selectedLabel,
            offeredLabel = offeredLabel,
            modifier = Modifier.padding(start = 8.dp)
        )
        if (online) {
            Box(Modifier.padding(start = 10.dp).size(8.dp).clip(CircleShape).background(PEER_ONLINE_GREEN))
        } else {
            Spacer(Modifier.width(18.dp))
        }
    }
}

/**
 * Round trips to the online devices the card lists, measured when it first shows them and
 * again every minute while the screen is in front; a new figure replaces the old one only
 * when it lands, so the rows never blink empty. Nothing is measured in a preview, which
 * carries its figures in [LocalDemo] — and nothing at all on a phone, which has no card.
 */
@Composable
internal fun rememberGlancePings(peers: List<PeerData>?): Map<String, String> {
    val demo = LocalDemo.current
    val pings = remember { mutableStateMapOf<String, String>().apply { demo?.pings?.let { putAll(it) } } }
    if (demo != null || LocalInspectionMode.current) return pings
    val ips = remember(peers) {
        peers.orEmpty().filter { it.online == true }.take(GLANCE_MAX_ROWS).map { it.getPrimaryIp() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(ips, lifecycle) {
        if (ips.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // A few at a time, as the Peers screen's "Ping all" does: forty disco pings at
                // once would queue in the daemon and time out there, not on the network.
                for (batch in ips.chunked(4)) {
                    coroutineScope { batch.map { ip -> async { pings[ip] = pingPeer(ip) } }.awaitAll() }
                }
                delay(GLANCE_PING_EVERY_MS)
            }
        }
    }
    return pings
}

private const val GLANCE_PING_EVERY_MS = 60_000L
