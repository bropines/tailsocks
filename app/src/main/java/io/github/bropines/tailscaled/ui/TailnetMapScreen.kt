package io.github.bropines.tailscaled.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The tailnet as a picture: this device in the centre, its peers around it, and between them
 * the thing a list cannot show at a glance — how each peer is reached right now and how much
 * is moving. A link's line is the kind of path (solid for direct, dashed for a DERP relay,
 * dash-dot for a peer relay; the colours repeat it), its thickness is the traffic, and a bead
 * runs along it in the direction the bytes go.
 *
 * Three parts. The arithmetic of where a node stands is TailnetMapLayout.kt; the data is the
 * shared poller of LiveTraffic.kt, with the paths read by PeerPath.kt exactly as the Peers
 * list reads them; this file draws. The links are one Canvas; the nodes are real composables
 * laid over it, so each is a touch target, a stop for a television's remote and an entry for
 * a screen reader without any of that being reimplemented on a canvas.
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

/** The key this device's own node is selected by; no node id looks like it. */
private const val SELF_KEY = "\u0000self"

private val NODE_SIZE = 40.dp
private val SELF_SIZE = 52.dp

/** Between a node's circle and the edge of its touch target, where the focus ring is drawn. */
private val NODE_PAD = 4.dp

/** The room kept under the rings for the "+N more" button when there is one. */
private const val MORE_ROOM_DP = 52f

/**
 * The map. It reads the live traffic itself ([rememberLiveTraffic]) and needs nothing but a
 * way back. A tap on a node opens a short sheet about it; [onOpenPeer], when given, puts a
 * button there that leads to the peer's full details. [initialPeerId] opens the screen with
 * that node's sheet up.
 */
@Composable
fun TailnetMapScreen(onBack: () -> Unit, onOpenPeer: ((PeerData) -> Unit)? = null, initialPeerId: String? = null) {
    val state = rememberLiveTraffic()
    val pathNames = rememberPeerPathNames(state.peers)
    val words = peerPathWords()
    val peers = remember(state.peers, state.traffic, pathNames) { mapPeers(state.peers, state.traffic, pathNames) }
    var selectedKey by rememberSaveable { mutableStateOf(initialPeerId) }
    // Not saved: the list it shows is not, and a sheet of nothing is worse than no sheet.
    var hiddenShown by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptyList<MapPeer>()) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tailnet_map_title),
                subtitle = if (state.running && state.loaded && state.peers.isNotEmpty()) {
                    stringResource(R.string.tailnet_map_online_format, state.peers.count { it.online == true }, state.peers.size)
                } else null,
                onBack = onBack,
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                // The poller notices the daemon by itself; there is nothing to reload.
                !state.running -> DaemonStoppedState(onStarted = {})
                else -> Column(Modifier.fillMaxSize()) {
                    MapHeader(state.traffic.total, words)
                    TailnetMap(
                        self = state.self,
                        selfPath = state.self?.let { pathNames.pathOf(it, isSelf = true) },
                        peers = peers,
                        words = words,
                        onSelect = { selectedKey = it },
                        onShowHidden = { hidden = it; hiddenShown = true },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }

    val self = state.self
    val selected = selectedKey
    if (selected == SELF_KEY && self != null) {
        MapDetailSheet(
            peer = self,
            path = pathNames.pathOf(self, isSelf = true),
            rate = state.traffic.total,
            // This device has no session with itself: its figures are everybody's together.
            rxBytes = state.peers.sumOf { it.rxBytes ?: 0L },
            txBytes = state.peers.sumOf { it.txBytes ?: 0L },
            words = words,
            isSelf = true,
            onOpen = null,
            onDismiss = { selectedKey = null }
        )
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
                words = words,
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
            words = words,
            onSelect = { selectedKey = it },
            onDismiss = { hiddenShown = false }
        )
    }
}

/** The tailnet's rate now, and what the three kinds of line mean. Wraps instead of cutting at a large font. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapHeader(total: TrafficRate, words: PeerPathWords) {
    val colors = MaterialTheme.colorScheme
    val formatRate = rememberRateFormatter()
    val spoken = stringResource(R.string.speed_graph_spoken_format, formatRate(total.rxBps), formatRate(total.txBps))
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.tailnet_map_rate_format, formatRate(total.rxBps), formatRate(total.txBps)),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.semantics { contentDescription = spoken }
        )
        val effects = rememberLinkEffects()
        for (kind in LinkKind.entries.filter { it.inLegend }) {
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
                        LinkKind.DIRECT -> words.direct
                        LinkKind.DERP -> words.relay
                        else -> words.peerRelay
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** What a link is drawn as. One per kind of [PeerPath]; the legend explains the first three. */
private enum class LinkKind(val inLegend: Boolean) {
    DIRECT(true), DERP(true), PEER_RELAY(true), IDLE(false), OFFLINE(false);

    companion object {
        fun of(path: PeerPath): LinkKind = when (path) {
            is PeerPath.Direct -> DIRECT
            is PeerPath.Derp -> DERP
            is PeerPath.PeerRelay -> PEER_RELAY
            is PeerPath.NotYet, is PeerPath.Self -> IDLE
            is PeerPath.Offline -> OFFLINE
        }
    }
}

/**
 * The theme's colour for a kind of link. Direct is the primary — the good case, and most of
 * a healthy tailnet; the two relays take the other two accents; a peer nothing has been sent
 * to and an offline one are outlines, not colours.
 */
@Composable
private fun LinkKind.color(): Color = when (this) {
    LinkKind.DIRECT -> MaterialTheme.colorScheme.primary
    LinkKind.DERP -> MaterialTheme.colorScheme.tertiary
    LinkKind.PEER_RELAY -> MaterialTheme.colorScheme.secondary
    LinkKind.IDLE -> MaterialTheme.colorScheme.outline
    LinkKind.OFFLINE -> MaterialTheme.colorScheme.outlineVariant
}

/**
 * The dashes of each kind, so the kinds are told apart without telling colours apart: direct
 * is unbroken, a DERP relay is dashed, a peer relay is dash-dot, a peer not yet talked to is
 * dotted. In pixels, hence remembered per density rather than built on every frame.
 */
@Composable
private fun rememberLinkEffects(): Map<LinkKind, PathEffect?> {
    val density = LocalDensity.current
    return remember(density) {
        fun dashes(vararg dp: Float) = PathEffect.dashPathEffect(FloatArray(dp.size) { with(density) { dp[it].dp.toPx() } })
        mapOf(
            LinkKind.DIRECT to null,
            LinkKind.DERP to dashes(7f, 6f),
            LinkKind.PEER_RELAY to dashes(10f, 5f, 1f, 5f),
            LinkKind.IDLE to dashes(1f, 6f),
            LinkKind.OFFLINE to null,
        )
    }
}

/**
 * Whether to animate at all: not when the system's "remove animations" is on — which reaches
 * an app as an animator scale of zero; Compose has no flag of its own for it — and not in a
 * preview, whose one frame would catch the beads wherever they happened to be.
 */
@Composable
private fun rememberAnimationsOn(): Boolean {
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

/**
 * The map itself, in whatever box it is given: the plan for that box, the links under the
 * nodes, the nodes, and the "+N more" button when the box could not hold everybody.
 */
@Composable
private fun TailnetMap(
    self: PeerData?,
    selfPath: PeerPath?,
    peers: List<MapPeer>,
    words: PeerPathWords,
    onSelect: (String) -> Unit,
    onShowHidden: (List<MapPeer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val formatRate = rememberRateFormatter()
    val effects = rememberLinkEffects()
    val linkColors = LinkKind.entries.associateWith { it.color() }
    val animate = rememberAnimationsOn()

    BoxWithConstraints(modifier) {
        val width = maxWidth.value
        val height = maxHeight.value
        val plan = remember(peers, width, height) { planTailnetMap(peers, width, height, moreRoom = MORE_ROOM_DP) }

        // One clock for every bead, and only while something moves: an idle tailnet draws
        // its frame and stops. The value is read in the draw phase alone, so a tick redraws
        // the canvas and recomposes nothing.
        val moving = animate && plan.nodes.any { it.peer.rate.totalBps > 0 }
        val pulse: State<Float>? = if (moving) {
            rememberInfiniteTransition(label = "links")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)), label = "pulse")
        } else null

        // Offline first, so that a link in use is never drawn under an unused one.
        val drawOrder = remember(plan) { plan.nodes.sortedByDescending { it.peer.tier } }
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(plan.centerX.dp.toPx(), plan.centerY.dp.toPx())
            val thin = 1.5.dp.toPx()
            val extra = 6.dp.toPx()
            for (node in drawOrder) {
                val kind = LinkKind.of(node.peer.path)
                val color = linkColors.getValue(kind)
                val end = Offset(node.spot.x.dp.toPx(), node.spot.y.dp.toPx())
                val linked = node.peer.tier == MapTier.LINKED
                val stroke = if (linked) thin + extra * linkWeight(node.peer.rate.totalBps) else thin * 0.75f
                drawLine(
                    color.copy(alpha = if (linked) 1f else 0.7f), center, end,
                    strokeWidth = stroke, cap = StrokeCap.Round, pathEffect = effects[kind]
                )
                val t = pulse?.value ?: continue
                if (!linked) continue
                // Out from this device for what it sends, in towards it for what it receives.
                val bead = stroke / 2 + 2.5.dp.toPx()
                if (node.peer.rate.txBps > 0) drawCircle(color, bead, center + (end - center) * t)
                if (node.peer.rate.rxBps > 0) drawCircle(color, bead, end + (center - end) * t)
            }
        }

        val slot = MapMetrics().slot.dp
        for (node in plan.nodes) {
            // Keyed by the peer: a node that moves when the tailnet changes keeps the remote's focus.
            key(node.peer.key) {
                val peer = node.peer
                val kind = LinkKind.of(peer.path)
                val offline = peer.tier == MapTier.OFFLINE
                val label = pathLabel(peer.path, words)
                val rateWords = if (peer.rate.totalBps > 0) {
                    stringResource(R.string.tailnet_map_rate_spoken_format, formatRate(peer.rate.rxBps), formatRate(peer.rate.txBps))
                } else null
                MapNodeView(
                    name = peer.name,
                    label = label,
                    labelColor = linkColors.getValue(kind),
                    icon = getOsVisuals(peer.peer.iconOs()).first,
                    circle = if (offline) colors.surfaceContainerHigh else colors.secondaryContainer,
                    onCircle = if (offline) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.onSecondaryContainer,
                    ring = linkColors.getValue(kind).takeIf { peer.tier == MapTier.LINKED },
                    nameColor = if (offline) colors.onSurfaceVariant.copy(alpha = 0.7f) else colors.onSurface,
                    size = NODE_SIZE,
                    exitNode = peer.peer.exitNode == true,
                    spoken = listOfNotNull(
                        peer.name,
                        if (offline) words.routeOffline else words.spoken(peer.path, null),
                        rateWords
                    ).joinToString(", "),
                    onClick = { onSelect(peer.key) },
                    modifier = Modifier
                        .absoluteOffset(x = node.spot.x.dp - slot / 2, y = node.spot.y.dp - NODE_SIZE / 2 - NODE_PAD)
                        .width(slot)
                )
            }
        }

        if (self != null) {
            MapNodeView(
                name = self.getDisplayName(),
                label = stringResource(R.string.peer_ping_self),
                labelColor = colors.onSurfaceVariant,
                icon = getOsVisuals(self.iconOs()).first,
                circle = colors.primary,
                onCircle = colors.onPrimary,
                ring = null,
                nameColor = colors.onSurface,
                size = SELF_SIZE,
                exitNode = false,
                spoken = listOfNotNull(
                    self.getDisplayName(), stringResource(R.string.peer_ping_self),
                    selfPath?.let { words.chip(it)?.first }
                ).joinToString(", "),
                onClick = { onSelect(SELF_KEY) },
                modifier = Modifier
                    .absoluteOffset(x = plan.centerX.dp - slot / 2, y = plan.centerY.dp - SELF_SIZE / 2 - NODE_PAD)
                    .width(slot)
                    // A television needs one thing in focus; the centre is the fair place to start.
                    .tvInitialFocus()
            )
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
        if (plan.hidden.isNotEmpty()) {
            FilledTonalButton(
                onClick = { onShowHidden(plan.hidden) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
            ) {
                Text(stringResource(R.string.tailnet_map_more_format, plan.hidden.size))
            }
        }
    }
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
 * One node: the device's icon in a circle, its name, and [label] under it. The whole of it
 * is the target — a 40dp circle alone would be under the size a finger needs — and on a
 * television it is a stop with the app's ring ([tvFocusRing]). [ring] circles a peer that is
 * linked, in its link's colour; [exitNode] marks the exit node in use.
 */
@Composable
private fun MapNodeView(
    name: String,
    label: String?,
    labelColor: Color,
    icon: ImageVector,
    circle: Color,
    onCircle: Color,
    ring: Color?,
    nameColor: Color,
    size: Dp,
    exitNode: Boolean,
    spoken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    val exitWords = stringResource(R.string.peer_exit_node_selected)
    Column(
        modifier
            .tvFocusRing(source, shape)
            .clip(shape)
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
                    .clip(CircleShape)
                    .background(circle)
                    .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = onCircle, modifier = Modifier.size(size * 0.5f))
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
private fun MapDetailSheet(
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

/**
 * The peers the window had no room to draw, as a list: on a tailnet with hundreds of nodes
 * this is where most of them are, so each row says what its node would have — the path, the
 * rate — and opens the same sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenPeersSheet(peers: List<MapPeer>, words: PeerPathWords, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val formatRate = rememberRateFormatter()
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
                    val offline = node.tier == MapTier.OFFLINE
                    val source = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .tvFocusRing(source)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(interactionSource = source, indication = LocalIndication.current) {
                                onDismiss()
                                onSelect(node.key)
                            }
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
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
