package io.github.bropines.tailscaled.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.SegmentedChipItem
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlin.math.abs
import kotlinx.coroutines.launch

/*
 * The tailnet as a picture: this device, its peers, and between them the thing a list cannot
 * show at a glance — how each peer is reached right now and how much is moving. A link's line
 * is the kind of path (solid for direct, dashed for a DERP relay, dash-dot for a peer relay;
 * the colours repeat it), its thickness is the traffic, and a bead runs along it in the
 * direction the bytes go.
 *
 * The picture comes in schemes — rings around this device, the routes with every relay as a
 * point on the way, the whole tailnet as a tree — and can be narrowed by filters. Where a node
 * stands is arithmetic: TailnetMapLayout.kt for the rings, TailnetMapSchemes.kt for the rest,
 * the filters and the view. The data is the shared poller of LiveTraffic.kt, with the paths
 * read by PeerPath.kt exactly as the Peers list reads them. This file draws; the sheets a tap
 * opens are TailnetMapSheets.kt. The links are one Canvas; the nodes are real composables laid
 * over it, so each is a touch target, a stop for a television's remote and an entry for a
 * screen reader without any of that being reimplemented on a canvas.
 */

class TailnetMapActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                TailnetMapScreen(
                    onBack = { finish() },
                    onOpenPeer = { peer ->
                        val id = peer.id ?: return@TailnetMapScreen
                        startActivity(Intent(this, PeersActivity::class.java).putExtra(PeersActivity.EXTRA_PEER_ID, id))
                    }
                )
            }
        }
    }
}

private val NODE_SIZE = 40.dp
private val SELF_SIZE = 52.dp
private val SELF_WIDTH = 112.dp

/** Between a node's circle and the edge of its touch target, where the focus ring is drawn. */
private val NODE_PAD = 4.dp

/** The room kept under the rings for the "+N more" button when there is one. */
private const val MORE_ROOM_DP = 52f

/** The scheme and the tree's grouping last picked by hand, for the next visit: per device, like the theme. */
/** How long after its last byte a peer still counts as busy for a place on a crowded map. */
private const val BUSY_HOLD_MS = 60_000L
private const val SCHEME_PREF = "tailnet_map_scheme"
private const val GROUPING_PREF = "tailnet_map_tree_grouping"

/**
 * What the screen opens with instead of what was stored: a preview's way to draw a scheme or
 * a filter without tapping. The app passes none of it.
 */
internal class MapScreenStart(
    val scheme: MapScheme? = null,
    val grouping: TreeGrouping? = null,
    val filter: MapFilter = MapFilter(),
)

/**
 * The map. It reads the live traffic itself ([rememberLiveTraffic]) and needs nothing but a
 * way back. A tap on a node opens a short sheet about it; [onOpenPeer], when given, puts a
 * button there that leads to the peer's full details. [initialPeerId] opens the screen with
 * that node's sheet up.
 */
@Composable
fun TailnetMapScreen(onBack: () -> Unit, onOpenPeer: ((PeerData) -> Unit)? = null, initialPeerId: String? = null) =
    TailnetMapScreen(MapScreenStart(), onBack, onOpenPeer, initialPeerId)

@Composable
internal fun TailnetMapScreen(
    start: MapScreenStart,
    onBack: () -> Unit,
    onOpenPeer: ((PeerData) -> Unit)? = null,
    initialPeerId: String? = null,
) {
    val context = LocalContext.current
    val state = rememberLiveTraffic()
    val pathNames = rememberPeerPathNames(state.peers)
    val words = mapWords()
    // Who moved traffic lately, and when last: a peer keeps its place on a crowded map for
    // BUSY_HOLD_MS after its last byte, instead of trading it away at every quiet reading.
    val lastBusy = remember { HashMap<String, Long>() }
    val peers = remember(state.peers, state.traffic, pathNames) {
        val now = android.os.SystemClock.elapsedRealtime()
        val fresh = mapPeers(state.peers, state.traffic, pathNames)
        fresh.forEach { if (it.rate.totalBps > 0) lastBusy[it.key] = now }
        lastBusy.values.removeAll { now - it > BUSY_HOLD_MS }
        if (lastBusy.isEmpty()) fresh else mapPeers(state.peers, state.traffic, pathNames, held = lastBusy.keys.toSet())
    }

    var scheme by remember {
        mutableStateOf(
            start.scheme
                ?: GlobalSettings.getString(context, SCHEME_PREF, "").let { name -> MapScheme.entries.firstOrNull { it.name == name } }
                ?: MapScheme.RINGS
        )
    }
    var grouping by remember {
        mutableStateOf(
            start.grouping
                ?: GlobalSettings.getString(context, GROUPING_PREF, "").let { name -> TreeGrouping.entries.firstOrNull { it.name == name } }
                ?: TreeGrouping.ROUTE
        )
    }
    // The filters live as long as the screen: a map reopened next week with a forgotten
    // "direct only" on it would look like a tailnet that lost half its devices.
    var filter by rememberSaveable(stateSaver = MapFilterSaver) { mutableStateOf(start.filter) }
    var filtersShown by remember { mutableStateOf(false) }
    val shown = remember(peers, filter) { if (filter.isEmpty) peers else peers.filter(filter::matches) }

    var selectedKey by rememberSaveable { mutableStateOf(initialPeerId) }
    // Not saved: the list it shows is not, and a sheet of nothing is worse than no sheet.
    var hiddenShown by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptyList<MapPeer>()) }
    val mapped = state.running && state.loaded

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tailnet_map_title),
                subtitle = when {
                    !mapped || state.peers.isEmpty() -> null
                    !filter.isEmpty -> stringResource(R.string.tailnet_map_filter_shown_format, shown.size, peers.size)
                    else -> stringResource(R.string.tailnet_map_online_format, state.peers.count { it.online == true }, state.peers.size)
                },
                onBack = onBack,
                actions = {
                    if (mapped && peers.isNotEmpty()) {
                        val label = if (filter.isEmpty) stringResource(R.string.tailnet_map_filters)
                        else stringResource(R.string.tailnet_map_filters_on_format, filter.count)
                        IconButton(onClick = { filtersShown = true }, modifier = Modifier.semantics { contentDescription = label }) {
                            BadgedBox(badge = { if (!filter.isEmpty) Badge { Text(filter.count.toString()) } }) {
                                Icon(Icons.Default.FilterList, contentDescription = null)
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                // The poller notices the daemon by itself; there is nothing to reload.
                !state.running -> DaemonStoppedState(onStarted = {})
                else -> Column(Modifier.fillMaxSize()) {
                    MapHeader(state.traffic.total, words.path)
                    if (peers.isNotEmpty()) {
                        MapControls(
                            scheme = scheme,
                            onScheme = { scheme = it; GlobalSettings.setString(context, SCHEME_PREF, it.name) },
                            grouping = grouping,
                            onGrouping = { grouping = it; GlobalSettings.setString(context, GROUPING_PREF, it.name) },
                            filter = filter,
                            onFilter = { filter = it },
                            users = state.users,
                            words = words,
                        )
                    }
                    if (peers.isNotEmpty() && shown.isEmpty()) {
                        FilteredOutState(peers.size, onReset = { filter = MapFilter() }, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        TailnetMap(
                            self = state.self,
                            selfPath = state.self?.let { pathNames.pathOf(it, isSelf = true) },
                            peers = shown,
                            scheme = scheme,
                            grouping = grouping,
                            users = state.users,
                            words = words,
                            onSelect = { selectedKey = it },
                            onShowHidden = { hidden = it; hiddenShown = true },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    val self = state.self
    val selected = selectedKey
    if (selected == MAP_SELF_KEY && self != null) {
        MapDetailSheet(
            peer = self,
            path = pathNames.pathOf(self, isSelf = true),
            rate = state.traffic.total,
            // This device has no session with itself: its figures are everybody's together.
            rxBytes = state.peers.sumOf { it.rxBytes ?: 0L },
            txBytes = state.peers.sumOf { it.txBytes ?: 0L },
            words = words.path,
            isSelf = true,
            onOpen = null,
            onDismiss = { selectedKey = null }
        )
    } else if (selected != null && selected.startsWith(MAP_HUB_PREFIX)) {
        // Grouped again on every reading, like a peer's sheet: its members and their rates
        // are the ones of now. A hub whose last peer found another path takes its sheet along.
        val group = remember(shown, scheme, grouping, state.users, self?.userID, selected) {
            groupPeers(shown, if (scheme == MapScheme.TREE) grouping else TreeGrouping.ROUTE, state.users, self?.userID)
                .firstOrNull { it.key == selected }
        }
        if (group != null) {
            MapHubSheet(group, words, onSelect = { selectedKey = it }, onDismiss = { selectedKey = null })
        }
    } else if (selected != null) {
        // Looked up on every reading, so the sheet's figures move while it is open. A peer
        // that has left the tailnet takes its sheet with it.
        peers.firstOrNull { it.key == selected }?.let { node ->
            MapDetailSheet(
                peer = node.peer,
                path = node.path,
                rate = node.rate,
                rxBytes = node.peer.rxBytes ?: 0L,
                txBytes = node.peer.txBytes ?: 0L,
                words = words.path,
                isSelf = false,
                onOpen = onOpenPeer?.let { open -> { open(node.peer) } },
                onDismiss = { selectedKey = null }
            )
        }
    } else if (hiddenShown) {
        // The list as it was when the button was pressed would go stale under the reader:
        // the same peers, as they are now.
        val keys = remember(hidden) { hidden.mapTo(HashSet()) { it.key } }
        HiddenPeersSheet(
            peers = peers.filter { it.key in keys },
            words = words.path,
            onSelect = { selectedKey = it },
            onDismiss = { hiddenShown = false }
        )
    } else if (filtersShown) {
        MapFilterSheet(
            filter = filter,
            options = remember(peers) { mapFilterOptions(peers) },
            users = state.users,
            shown = shown.size,
            total = peers.size,
            words = words,
            onFilter = { filter = it },
            onDismiss = { filtersShown = false }
        )
    }
}

/** A [MapFilter] across a rotation, as the flat list a bundle takes. */
private val MapFilterSaver = listSaver<MapFilter, Any>(
    save = { f ->
        listOf(
            f.query, f.onlineOnly, f.withTraffic, f.paths.map { it.name }, f.os.toList(), f.owners.toList(), f.exitNodes, f.subnetRouters
        )
    },
    restore = { v ->
        @Suppress("UNCHECKED_CAST")
        MapFilter(
            query = v[0] as String,
            onlineOnly = v[1] as Boolean,
            withTraffic = v[2] as Boolean,
            paths = (v[3] as List<String>).mapNotNullTo(HashSet()) { name -> MapPathFilter.entries.firstOrNull { it.name == name } },
            os = (v[4] as List<String>).toSet(),
            owners = (v[5] as List<String>).toSet(),
            exitNodes = v[6] as Boolean,
            subnetRouters = v[7] as Boolean,
        )
    }
)

/**
 * The words the map adds to a path's ([PeerPathWords]): what a group is called where the data
 * has no name for it. Resolved in the screen's composition, like the path's own.
 */
@Immutable
internal class MapWords(
    val path: PeerPathWords,
    val idle: String,
    val linked: String,
    val derp: String,
    val peerRelay: String,
    val group: String,
    val otherOs: String,
    val otherUser: String,
)

@Composable
internal fun mapWords(): MapWords = MapWords(
    path = peerPathWords(),
    idle = stringResource(R.string.tailnet_map_group_idle),
    linked = stringResource(R.string.tailnet_map_group_linked),
    derp = stringResource(R.string.tailnet_map_kind_derp),
    peerRelay = stringResource(R.string.tailnet_map_kind_peer_relay),
    group = stringResource(R.string.tailnet_map_kind_group),
    otherOs = stringResource(R.string.tailnet_map_os_other),
    otherUser = stringResource(R.string.tailnet_map_owner_unknown),
)

/** An OS family ([osFamily]) as its makers write it. */
internal fun MapWords.os(family: String?): String = when (family) {
    "android" -> "Android"
    "ios" -> "iOS"
    "windows" -> "Windows"
    "macos" -> "macOS"
    "linux" -> "Linux"
    "tvos" -> "tvOS"
    else -> otherOs
}

/** An owner key ([ownerKeys]) as a name: the user's, or the tag as it is written in the policy. */
internal fun MapWords.owner(key: String, users: Map<Long, String>): String =
    if (isTagOwner(key)) key else ownerUserId(key)?.let { users[it] } ?: otherUser

/** What a group is called on its node: the city of a DERP region, a relay's node, a user, or the word for its kind. */
internal fun MapWords.title(g: MapGroup): String = when (g.kind) {
    MapGroupKind.DIRECT -> path.chipDirect
    MapGroupKind.DERP -> g.detail ?: g.name.orEmpty()
    MapGroupKind.PEER_RELAY, MapGroupKind.TAG -> g.name.orEmpty()
    MapGroupKind.IDLE -> idle
    MapGroupKind.OFFLINE -> path.routeOffline
    MapGroupKind.LINKED -> linked
    MapGroupKind.USER -> g.name ?: otherUser
    MapGroupKind.OS -> os(g.name)
}

/** What kind of thing a group is, for its sheet and a screen reader. */
internal fun MapWords.kind(g: MapGroup): String = when (g.kind) {
    MapGroupKind.DERP -> derp
    MapGroupKind.PEER_RELAY -> peerRelay
    else -> group
}

internal fun hubIcon(g: MapGroup): ImageVector = when (g.kind) {
    MapGroupKind.DIRECT -> Icons.Default.Lan
    MapGroupKind.DERP -> Icons.Default.Router
    MapGroupKind.PEER_RELAY -> Icons.Default.DeviceHub
    MapGroupKind.IDLE -> Icons.Default.Schedule
    MapGroupKind.OFFLINE -> Icons.Default.CloudOff
    MapGroupKind.LINKED -> Icons.Default.SwapVert
    MapGroupKind.USER -> Icons.Default.Person
    MapGroupKind.TAG -> Icons.Default.Sell
    MapGroupKind.OS -> getOsVisuals(g.name).first
}

/**
 * A hub's fill and what is drawn on it. A relay is solid in its link's colour — the two
 * things that carry other devices' traffic are the two loudest squares on the map; a group
 * that is only a heading stays a quiet surface.
 */
@Composable
internal fun hubColors(kind: MapGroupKind): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (kind) {
        MapGroupKind.DERP -> colors.tertiary to colors.onTertiary
        MapGroupKind.PEER_RELAY -> colors.secondary to colors.onSecondary
        MapGroupKind.DIRECT, MapGroupKind.LINKED -> colors.primaryContainer to colors.onPrimaryContainer
        MapGroupKind.IDLE, MapGroupKind.OFFLINE -> colors.surfaceContainerHigh to colors.onSurfaceVariant
        MapGroupKind.USER, MapGroupKind.TAG, MapGroupKind.OS -> colors.surfaceContainerHighest to colors.onSurface
    }
}

/**
 * Under the header: the scheme, and — only when there is something to say — one row of what
 * shapes the picture besides it: the tree's grouping and every filter that is on, each chip a
 * tap away from being off. The row scrolls sideways rather than pushing the map down the screen.
 */
@Composable
private fun MapControls(
    scheme: MapScheme,
    onScheme: (MapScheme) -> Unit,
    grouping: TreeGrouping,
    onGrouping: (TreeGrouping) -> Unit,
    filter: MapFilter,
    onFilter: (MapFilter) -> Unit,
    users: Map<Long, String>,
    words: MapWords,
) {
    val schemeLabel = stringResource(R.string.tailnet_map_scheme_label)
    SlidingSegmentedChips(
        items = listOf(
            SegmentedChipItem(stringResource(R.string.tailnet_map_scheme_rings), Icons.Default.Adjust),
            SegmentedChipItem(stringResource(R.string.tailnet_map_scheme_routes), Icons.Default.Hub),
            SegmentedChipItem(stringResource(R.string.tailnet_map_scheme_tree), Icons.Default.AccountTree),
        ),
        selectedIndex = scheme.ordinal,
        onOptionSelected = { onScheme(MapScheme.entries[it]) },
        modifier = Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = schemeLabel },
        height = 36.dp
    )
    if (scheme != MapScheme.TREE && filter.isEmpty) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (scheme == MapScheme.TREE) {
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text(stringResource(R.string.tailnet_map_grouping_format, stringResource(grouping.label))) },
                    leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (option in TreeGrouping.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(option.label).replaceFirstChar { it.titlecase() }) },
                            onClick = { open = false; onGrouping(option) },
                            trailingIcon = { if (option == grouping) Icon(Icons.Default.Check, contentDescription = null) },
                        )
                    }
                }
            }
        }
        if (!filter.isEmpty) {
            AssistChip(
                onClick = { onFilter(MapFilter()) },
                label = { Text(stringResource(R.string.tailnet_map_filter_reset)) },
                leadingIcon = { Icon(Icons.Default.FilterListOff, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
            for ((label, without) in activeFilters(filter, users, words)) {
                InputChip(
                    selected = true,
                    onClick = { onFilter(without) },
                    label = { Text(label, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
        }
    }
}

internal val TreeGrouping.label: Int
    get() = when (this) {
        TreeGrouping.ROUTE -> R.string.tailnet_map_grouping_route
        TreeGrouping.OWNER -> R.string.tailnet_map_grouping_owner
        TreeGrouping.OS -> R.string.tailnet_map_grouping_os
        TreeGrouping.STATE -> R.string.tailnet_map_grouping_state
    }

internal val MapPathFilter.label: Int
    get() = when (this) {
        MapPathFilter.DIRECT -> R.string.peer_status_direct
        MapPathFilter.DERP -> R.string.tailnet_map_kind_derp
        MapPathFilter.PEER_RELAY -> R.string.tailnet_map_kind_peer_relay
        MapPathFilter.NOT_YET -> R.string.tailnet_map_filter_not_yet
    }

/** Every filter that is on, as its chip's words and the filter without it. */
@Composable
private fun activeFilters(f: MapFilter, users: Map<Long, String>, words: MapWords): List<Pair<String, MapFilter>> = buildList {
    if (f.query.isNotBlank()) add(stringResource(R.string.tailnet_map_filter_query_format, f.query.trim()) to f.copy(query = ""))
    if (f.onlineOnly) add(stringResource(R.string.tailnet_map_filter_online) to f.copy(onlineOnly = false))
    if (f.withTraffic) add(stringResource(R.string.tailnet_map_filter_traffic) to f.copy(withTraffic = false))
    for (p in MapPathFilter.entries) if (p in f.paths) add(stringResource(p.label) to f.copy(paths = f.paths - p))
    for (os in f.os.sorted()) add(words.os(os) to f.copy(os = f.os - os))
    for (owner in f.owners.sorted()) add(words.owner(owner, users) to f.copy(owners = f.owners - owner))
    if (f.exitNodes) add(stringResource(R.string.peers_tab_exit_nodes) to f.copy(exitNodes = false))
    if (f.subnetRouters) add(stringResource(R.string.tailnet_map_filter_subnet_routers) to f.copy(subnetRouters = false))
}

/** The filters left nobody: said in words, with the way out, instead of a map of one device. */
@Composable
private fun FilteredOutState(total: Int, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.FilterListOff, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.tailnet_map_filter_empty), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.tailnet_map_filter_empty_desc, total),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(onClick = onReset, modifier = Modifier.tvInitialFocus()) { Text(stringResource(R.string.tailnet_map_filter_reset_all)) }
    }
}

/** The kinds of link the legend explains. */
private val LEGEND = listOf(MapLinkKind.DIRECT, MapLinkKind.DERP, MapLinkKind.PEER_RELAY)

/** The tailnet's rate now, and what the three kinds of line mean. Wraps instead of cutting at a large font. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapHeader(total: TrafficRate, words: PeerPathWords) {
    val colors = MaterialTheme.colorScheme
    val formatRate = rememberRateFormatter()
    val spoken = stringResource(R.string.speed_graph_spoken_format, formatRate(total.rxBps), formatRate(total.txBps))
    // The rate has a line to itself: sharing one with the legend, a number that grew by a
    // digit wrapped the legend, and the map under it changed height at every reading.
    Text(
        stringResource(R.string.tailnet_map_rate_format, formatRate(total.rxBps), formatRate(total.txBps)),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).semantics { contentDescription = spoken }
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val effects = rememberLinkEffects()
        for (kind in LEGEND) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = kind.color()
                Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
                    drawLine(
                        color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = effects[kind]
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    when (kind) {
                        MapLinkKind.DIRECT -> words.direct
                        MapLinkKind.DERP -> words.relay
                        else -> words.peerRelay
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The theme's colour for a kind of link. Direct is the primary — the good case, and most of
 * a healthy tailnet; the two relays take the other two accents; a peer nothing has been sent
 * to, an offline one and a branch of the tree that is no path are outlines, not colours.
 */
@Composable
private fun MapLinkKind.color(): Color = when (this) {
    MapLinkKind.DIRECT -> MaterialTheme.colorScheme.primary
    MapLinkKind.DERP -> MaterialTheme.colorScheme.tertiary
    MapLinkKind.PEER_RELAY -> MaterialTheme.colorScheme.secondary
    MapLinkKind.IDLE, MapLinkKind.GROUP -> MaterialTheme.colorScheme.outline
    MapLinkKind.OFFLINE -> MaterialTheme.colorScheme.outlineVariant
}

/**
 * The dashes of each kind, so the kinds are told apart without telling colours apart: direct
 * is unbroken, a DERP relay is dashed, a peer relay is dash-dot, a peer not yet talked to is
 * dotted. In pixels, hence remembered per density rather than built on every frame.
 */
@Composable
private fun rememberLinkEffects(): Map<MapLinkKind, PathEffect?> {
    val density = LocalDensity.current
    return remember(density) {
        fun dashes(vararg dp: Float) = PathEffect.dashPathEffect(FloatArray(dp.size) { with(density) { dp[it].dp.toPx() } })
        mapOf(
            MapLinkKind.DIRECT to null,
            MapLinkKind.DERP to dashes(7f, 6f),
            MapLinkKind.PEER_RELAY to dashes(10f, 5f, 1f, 5f),
            MapLinkKind.IDLE to dashes(1f, 6f),
            MapLinkKind.OFFLINE to null,
            MapLinkKind.GROUP to null,
        )
    }
}

/**
 * Whether to animate at all: not when the system's "remove animations" is on — which reaches
 * an app as an animator scale of zero; Compose has no flag of its own for it — and not in a
 * preview, whose one frame would catch the beads wherever they happened to be.
 */
@Composable
internal fun rememberAnimationsOn(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled()
        else runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}

/** How long a bead takes from one end of a link to the other. */
private const val PULSE_MS = 1_800

/** How long the nodes take to their new places when the scheme or the filter changes. */
private const val MOVE_MS = 450

/** How long the view takes to where a button or a remote sends it. */
private const val VIEW_MS = 300

/**
 * Where every node is on the way from one scene to the next, in the scene's dp. One clock for
 * all of them, read where things are placed and drawn and nowhere in composition, so a move
 * costs a relayout and a redraw a frame, not a recomposition.
 */
private class MapMotion(private val from: Map<String, Offset>, val to: Map<String, Offset>, val progress: Animatable<Float, AnimationVector1D>) {
    fun at(key: String): Offset {
        val end = to[key] ?: return Offset.Unspecified
        val start = from[key] ?: return end
        val t = progress.value
        return if (t >= 1f) end else lerp(start, end, t)
    }
}

/**
 * The motion towards [scene]: every node from where it is right now — mid-move included — and
 * a node the last scene did not have from its parent, so a filter taken off grows its peers
 * out of their hub instead of dropping them from the sky. The same places are the same
 * motion: a reading that moved nobody starts nothing.
 */
@Composable
private fun rememberMapMotion(scene: MapScene, animate: Boolean): MapMotion {
    val targets = remember(scene) {
        HashMap<String, Offset>(scene.peers.size + scene.hubs.size + 1).apply {
            put(MAP_SELF_KEY, Offset(scene.selfX, scene.selfY))
            for (h in scene.hubs) put(h.group.key, Offset(h.x, h.y))
            for (p in scene.peers) put(p.peer.key, Offset(p.x, p.y))
        }
    }
    val parents = remember(scene) { scene.peers.associate { it.peer.key to it.parent } }
    val last = remember { arrayOfNulls<MapMotion>(1) }
    val motion = remember(targets) {
        val previous = last[0]
        val from = if (previous == null || !animate) emptyMap() else Snapshot.withoutReadObservation {
            val origin = previous.at(MAP_SELF_KEY)
            targets.mapValues { (key, _) ->
                previous.at(key).takeIf { it.isSpecified }
                    ?: parents[key]?.let { previous.at(it) }?.takeIf { it.isSpecified }
                    ?: origin
            }
        }
        MapMotion(from, targets, Animatable(if (from.isEmpty()) 1f else 0f)).also { last[0] = it }
    }
    LaunchedEffect(motion) { motion.progress.animateTo(1f, tween(MOVE_MS, easing = FastOutSlowInEasing)) }
    return motion
}

/** The view the reader has dragged or pinched the map to; null while it is the scene's own. */
@Stable
private class MapViewport {
    var user by mutableStateOf<SceneView?>(null)
}

/**
 * The map itself, in whatever box it is given: the scene of the scheme for that box, the
 * links under the nodes, the nodes, and over them the "+N more" button when the box could not
 * hold everybody and the button that fits the map to the window or brings it back.
 *
 * The scene is drawn in a layer of its own size that the view moves and scales, so a tree
 * taller than the window is dragged and pinched like any map; a scene that is the window —
 * the rings, the routes — only zooms in.
 */
@Composable
private fun TailnetMap(
    self: PeerData?,
    selfPath: PeerPath?,
    peers: List<MapPeer>,
    scheme: MapScheme,
    grouping: TreeGrouping,
    users: Map<Long, String>,
    words: MapWords,
    onSelect: (String) -> Unit,
    onShowHidden: (List<MapPeer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val formatRate = rememberRateFormatter()
    val effects = rememberLinkEffects()
    val linkColors = MapLinkKind.entries.associateWith { it.color() }
    val animate = rememberAnimationsOn()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier) {
        val width = maxWidth.value
        val height = maxHeight.value
        val scene = remember(peers, scheme, grouping, users, self?.userID, width, height) {
            planMapScene(scheme, grouping, peers, width, height, users, self?.userID, moreRoom = MORE_ROOM_DP)
        }
        val motion = rememberMapMotion(scene, animate)

        // A new scheme is a new picture: it opens in its own view, not in the last one's zoom.
        // Not a new window size: the box changes by a line whenever the header's text wraps,
        // and that must not throw away where the reader has zoomed to.
        val viewport = remember(scheme, grouping) { MapViewport() }
        val sceneNow by rememberUpdatedState(scene)
        val viewNow: () -> SceneView = remember(viewport, width, height) {
            { viewport.user?.let { clampView(it, sceneNow, width, height) } ?: startView(sceneNow, width, height) }
        }
        val moveTo: (SceneView) -> Unit = remember(viewport, viewNow) {
            { target ->
                val start = viewNow()
                if (!animate) viewport.user = target
                else scope.launch {
                    animate(0f, 1f, animationSpec = tween(VIEW_MS, easing = FastOutSlowInEasing)) { t, _ ->
                        viewport.user = SceneView(
                            start.scale + (target.scale - start.scale) * t,
                            start.x + (target.x - start.x) * t,
                            start.y + (target.y - start.y) * t,
                        )
                    }
                }
            }
        }
        // A remote has no finger to drag with: the node it moves onto is brought into sight.
        val reveal: (String) -> Unit = remember(motion, viewNow, moveTo) {
            { key ->
                val at = motion.to[key]
                val now = viewNow()
                if (at != null) revealView(now, at.x, at.y, sceneNow, width, height).let { if (it != now) moveTo(it) }
            }
        }

        // One clock for every bead, and only while something moves: an idle tailnet draws
        // its frame and stops. The value is read in the draw phase alone, so a tick redraws
        // the canvas and recomposes nothing.
        val moving = animate && scene.links.any { it.linked && it.rate.totalBps > 0 }
        val pulse: State<Float>? = if (moving) {
            rememberInfiniteTransition(label = "links")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)), label = "pulse")
        } else null

        // Unused first, then by weight, so that a link in use is never drawn under an idle one.
        val drawOrder = remember(scene) { scene.links.sortedWith(compareBy({ it.linked }, { it.rate.totalBps })) }
        val slot = MapMetrics().slot.dp

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(viewport, width, height) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val now = viewNow()
                        val next = zoomView(
                            now, zoom, centroid.x.toDp().value, centroid.y.toDp().value, pan.x.toDp().value, pan.y.toDp().value,
                            sceneNow, width, height
                        )
                        if (next != now) viewport.user = next
                    }
                }
        ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(scene.width.dp, scene.height.dp)
                    .graphicsLayer {
                        val view = viewNow()
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = view.scale
                        scaleY = view.scale
                        translationX = view.x.dp.toPx()
                        translationY = view.y.dp.toPx()
                    }
            ) {
                val path = remember { Path() }
                Canvas(Modifier.fillMaxSize()) {
                    val thin = 1.5.dp.toPx()
                    val extra = 6.dp.toPx()
                    val unit = 1.dp.toPx()
                    for (link in drawOrder) {
                        val a = motion.at(link.from)
                        val b = motion.at(link.to)
                        if (!a.isSpecified || !b.isSpecified) continue
                        val color = linkColors.getValue(link.kind)
                        // A branch that is no path has no traffic of its own to be heavy with.
                        val weight = if (link.kind == MapLinkKind.GROUP) 0f else linkWeight(link.rate.totalBps)
                        // A branch of the tree is drawn whole and opaque: branches share their
                        // stem, and a see-through line would darken wherever two of them overlap.
                        val stroke = if (link.linked || link.square) thin + extra * weight else thin * 0.75f
                        val alpha = if (link.linked || link.square) 1f else 0.7f
                        // Laid out in dp, like the scene and a branch's own measures, then taken to pixels.
                        val pts = linkPoints(link, a.x, a.y, b.x, b.y)
                        for (i in pts.indices) pts[i] *= unit
                        if (pts.size == 4) {
                            drawLine(
                                color.copy(alpha = alpha), Offset(pts[0], pts[1]), Offset(pts[2], pts[3]),
                                strokeWidth = stroke, cap = StrokeCap.Round, pathEffect = effects[link.kind]
                            )
                        } else {
                            path.rewind()
                            path.moveTo(pts[0], pts[1])
                            for (i in 2 until pts.size step 2) path.lineTo(pts[i], pts[i + 1])
                            drawPath(
                                path, color.copy(alpha = alpha),
                                style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effects[link.kind])
                            )
                        }
                        val t = pulse?.value ?: continue
                        if (!link.linked) continue
                        // Away from this device for what it sends, towards it for what it receives.
                        val bead = stroke / 2 + 2.5.dp.toPx()
                        if (link.rate.txBps > 0) drawCircle(color, bead, pointAlong(pts, t))
                        if (link.rate.rxBps > 0) drawCircle(color, bead, pointAlong(pts, 1f - t))
                    }
                }

                fun Modifier.at(key: String, size: Dp, width: Dp = slot): Modifier = this
                    .absoluteOffset {
                        val p = motion.at(key)
                        IntOffset((p.x.dp - width / 2).roundToPx(), (p.y.dp - size / 2 - NODE_PAD).roundToPx())
                    }
                    .width(width)

                if (self != null) {
                    MapNodeView(
                        name = self.getDisplayName(),
                        label = stringResource(R.string.peer_ping_self),
                        labelColor = colors.onSurfaceVariant,
                        icon = getOsVisuals(self.iconOs()).first,
                        fill = colors.primary,
                        onFill = colors.onPrimary,
                        ring = null,
                        nameColor = colors.onSurface,
                        size = SELF_SIZE,
                        shape = CircleShape,
                        exitNode = false,
                        spoken = listOfNotNull(
                            self.getDisplayName(), stringResource(R.string.peer_ping_self),
                            selfPath?.let { words.path.chip(it)?.first }
                        ).joinToString(", "),
                        onClick = { onSelect(MAP_SELF_KEY) },
                        onFocused = { reveal(MAP_SELF_KEY) },
                        modifier = Modifier
                            // Wider than a peer's slot: «Это устройство» is, and nothing stands that close to it.
                            .at(MAP_SELF_KEY, SELF_SIZE, SELF_WIDTH)
                            // A television needs one thing in focus; this device is the fair place to start.
                            .tvInitialFocus()
                    )
                }

                // Composed in the scene's order — a hub, then the peers behind it — which is the
                // order a keyboard's Tab walks; a remote's arrows go by where the nodes are.
                val peersByParent = remember(scene) { scene.peers.groupBy { it.parent } }
                val hubSize = NODE_SIZE
                for (owner in listOf(null) + scene.hubs) {
                    if (owner != null) key(owner.group.key) {
                        val group = owner.group
                        val (fill, onFill) = hubColors(group.kind)
                        val title = words.title(group)
                        val count = pluralStringResource(R.plurals.tailnet_map_devices, group.members.size, group.members.size)
                        val busy = group.rate.totalBps > 0
                        MapNodeView(
                            name = title,
                            label = if (busy) formatRate(group.rate.totalBps) else count,
                            labelColor = if (busy) linkColors.getValue(group.kind.link) else colors.onSurfaceVariant,
                            icon = hubIcon(group),
                            fill = fill,
                            onFill = onFill,
                            ring = null,
                            nameColor = colors.onSurface,
                            size = hubSize,
                            shape = MaterialTheme.shapes.medium,
                            exitNode = false,
                            strongName = true,
                            spoken = listOfNotNull(
                                title, words.kind(group), count,
                                if (busy) stringResource(
                                    R.string.tailnet_map_rate_spoken_format, formatRate(group.rate.rxBps), formatRate(group.rate.txBps)
                                ) else null
                            ).joinToString(", "),
                            onClick = { onSelect(group.key) },
                            onFocused = { reveal(group.key) },
                            modifier = Modifier.at(group.key, hubSize)
                        )
                    }
                    for (node in peersByParent[owner?.group?.key ?: MAP_SELF_KEY].orEmpty()) {
                        // Keyed by the peer: a node that moves when the tailnet changes keeps the remote's focus.
                        key(node.peer.key) {
                            val peer = node.peer
                            val kind = MapLinkKind.of(peer.path)
                            val offline = peer.tier == MapTier.OFFLINE
                            val busy = peer.rate.totalBps > 0
                            val rateWords = if (busy) {
                                stringResource(R.string.tailnet_map_rate_spoken_format, formatRate(peer.rate.rxBps), formatRate(peer.rate.txBps))
                            } else null
                            // Under a hub the path is the hub; what is left to say is how much. The
                            // rings keep the path under every node, as they always had it.
                            val underHub = owner != null && (scheme == MapScheme.ROUTES || grouping == TreeGrouping.ROUTE)
                            val label = when {
                                scheme != MapScheme.RINGS && busy -> formatRate(peer.rate.totalBps)
                                underHub -> null
                                else -> pathLabel(peer.path, words.path)
                            }
                            MapNodeView(
                                name = peer.name,
                                label = label,
                                labelColor = linkColors.getValue(kind),
                                icon = getOsVisuals(peer.peer.iconOs()).first,
                                fill = if (offline) colors.surfaceContainerHigh else colors.secondaryContainer,
                                onFill = if (offline) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.onSecondaryContainer,
                                ring = linkColors.getValue(kind).takeIf { peer.tier == MapTier.LINKED },
                                nameColor = if (offline) colors.onSurfaceVariant.copy(alpha = 0.7f) else colors.onSurface,
                                size = NODE_SIZE,
                                shape = CircleShape,
                                exitNode = peer.peer.exitNode == true,
                                spoken = listOfNotNull(
                                    peer.name,
                                    if (offline) words.path.routeOffline else words.path.spoken(peer.path, null),
                                    rateWords
                                ).joinToString(", "),
                                onClick = { onSelect(peer.key) },
                                onFocused = { reveal(peer.key) },
                                modifier = Modifier.at(peer.key, NODE_SIZE)
                            )
                        }
                    }
                }
            }
        }

        if (peers.isEmpty()) {
            Text(
                stringResource(R.string.tailnet_map_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 24.dp)
            )
        }
        if (scene.hidden.isNotEmpty()) {
            FilledTonalButton(
                onClick = { onShowHidden(scene.hidden) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
            ) {
                Text(stringResource(R.string.tailnet_map_more_format, scene.hidden.size))
            }
        }
        MapViewButton(scene, width, height, viewNow, moveTo, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp))
    }
}

/** Two views are the same to the eye: within a hundredth of the scale and half a dp. */
private fun SceneView.near(other: SceneView): Boolean =
    abs(scale - other.scale) < 0.01f && abs(x - other.x) < 0.5f && abs(y - other.y) < 0.5f

/**
 * The one button over the map's corner. While the view is not the whole map it fits the map
 * to the window; at the whole of a map that is too small to read that way it goes back to
 * full size; and where the two are the same view — the rings and the routes, untouched — it
 * is not there at all. A remote has no pinch, so this is its zoom.
 */
@Composable
private fun MapViewButton(scene: MapScene, width: Float, height: Float, viewNow: () -> SceneView, moveTo: (SceneView) -> Unit, modifier: Modifier = Modifier) {
    val now = viewNow()
    val fit = fitView(scene, width, height)
    val start = startView(scene, width, height)
    val target = when {
        !now.near(fit) -> fit
        !start.near(fit) -> start
        else -> return
    }
    val label = stringResource(if (target === fit) R.string.tailnet_map_fit else R.string.tailnet_map_actual_size)
    FilledTonalIconButton(onClick = { moveTo(target) }, modifier = modifier.semantics { contentDescription = label }) {
        Icon(if (target === fit) Icons.Default.FitScreen else Icons.Default.ZoomIn, contentDescription = null)
    }
}

/** The point [t] of the way along the line through [pts] (x, y pairs), by length. */
private fun pointAlong(pts: FloatArray, t: Float): Offset {
    if (pts.size == 4) return Offset(pts[0] + (pts[2] - pts[0]) * t, pts[1] + (pts[3] - pts[1]) * t)
    var total = 0f
    for (i in 2 until pts.size step 2) total += abs(pts[i] - pts[i - 2]) + abs(pts[i + 1] - pts[i - 1])
    var left = total * t
    for (i in 2 until pts.size step 2) {
        // Every stretch of a square link runs along one axis, so its length is the plain sum.
        val length = abs(pts[i] - pts[i - 2]) + abs(pts[i + 1] - pts[i - 1])
        if (left <= length && length > 0f) {
            val f = left / length
            return Offset(pts[i - 2] + (pts[i] - pts[i - 2]) * f, pts[i - 1] + (pts[i + 1] - pts[i - 1]) * f)
        }
        left -= length
    }
    return Offset(pts[pts.size - 2], pts[pts.size - 1])
}

/**
 * The line under a node's name: how it is reached, as short as it can be said — "direct", a
 * DERP region's code, the relaying node's name. Nothing for a peer with no path; an offline
 * one is told by its grey, and its sheet says when it was last seen.
 */
private fun pathLabel(path: PeerPath, words: PeerPathWords): String? = when (path) {
    is PeerPath.Direct -> words.direct
    is PeerPath.Derp -> path.region
    is PeerPath.PeerRelay -> path.node ?: words.peerRelay
    is PeerPath.NotYet, is PeerPath.Offline, is PeerPath.Self -> null
}

/**
 * One node: an icon on its [shape] — a circle for a device, a rounded square for a hub, so
 * the two are told apart before any colour is — its name, and [label] under it. The whole of
 * it is the target — a 40dp circle alone would be under the size a finger needs — and on a
 * television it is a stop with the app's ring ([tvFocusRing]). [ring] circles a peer that is
 * linked, in its link's colour; [exitNode] marks the exit node in use.
 */
@Composable
private fun MapNodeView(
    name: String,
    label: String?,
    labelColor: Color,
    icon: ImageVector,
    fill: Color,
    onFill: Color,
    ring: Color?,
    nameColor: Color,
    size: Dp,
    shape: Shape,
    exitNode: Boolean,
    spoken: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    strongName: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val target = MaterialTheme.shapes.medium
    val exitWords = stringResource(R.string.peer_exit_node_selected)
    Column(
        modifier
            .tvFocusRing(source, target)
            .clip(target)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            // One sentence per node; the pieces below are for the eye.
            .clearAndSetSemantics { contentDescription = if (exitNode) "$spoken, $exitWords" else spoken }
            .padding(NODE_PAD),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Box(
                Modifier
                    .size(size)
                    .clip(shape)
                    .background(fill)
                    .then(if (ring != null) Modifier.border(2.dp, ring, shape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = onFill, modifier = Modifier.size(size * 0.5f))
            }
            if (exitNode) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(size * 0.42f)
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(size * 0.3f))
                }
            }
        }
        // A veil of the screen's own colour, so a link passing behind the words does not cross them out.
        Column(
            Modifier.padding(top = 2.dp).background(colors.background.copy(alpha = 0.8f), MaterialTheme.shapes.small),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (strongName) FontWeight.SemiBold else null,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
