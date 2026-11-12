package io.github.bropines.tailscaled.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The exit-node picker as a map: the world's outline, a dot per place that has exit nodes,
 * and under it the nodes of the place that was tapped — drawn by the picker's own row, so
 * choosing one here is the list's choosing and nothing else. Where a node goes, the zoom
 * arithmetic and the clustering are ui/ExitNodePlaces.kt; this file draws and listens.
 *
 * It is a view for a finger. A television keeps the list (ExitNodePicker never offers the
 * switch there): a map of free-floating dots has no order a remote could walk.
 */

/** Which of the picker's two views was used last, in the global preferences. */
internal object ExitNodePickerView {
    const val KEY = "exit_node_picker_view"
    const val LIST = "list"
    const val MAP = "map"
}

/** The map's words, resolved by the screen with the rest of the picker's — see wrapContextWithLocale(). */
internal data class ExitNodeMapStrings(
    val showMap: String,
    val showList: String,
    val reset: String,
    val hint: String,
    val nearRelayFormat: String,
    val approximateNote: String,
    val unplacedFormat: String,
    val markerFormat: String,
    val areaFormat: String,
)

@Composable
internal fun exitNodeMapStrings() = ExitNodeMapStrings(
    showMap = stringResource(R.string.exit_map_show_map),
    showList = stringResource(R.string.exit_map_show_list),
    reset = stringResource(R.string.exit_map_reset),
    hint = stringResource(R.string.exit_map_hint),
    nearRelayFormat = stringResource(R.string.exit_map_near_relay),
    approximateNote = stringResource(R.string.exit_map_approximate_note),
    unplacedFormat = stringResource(R.string.exit_map_unplaced),
    markerFormat = stringResource(R.string.exit_map_marker_cd),
    areaFormat = stringResource(R.string.exit_map_area_cd),
)

/**
 * Where the DERP regions are, for the nodes that have to be guessed at their relay. The
 * daemon's own map (/localapi/v0/derpmap, through the bridge call the peer list already
 * uses for region names) carries each region's coordinates. It changes about never: the
 * last answer is shown at once and asked again only after ten minutes.
 */
internal object ExitNodeDerpSource {
    private const val TAG = "ExitNodeDerpSource"
    private const val TTL_MS = 10 * 60_000L

    @Volatile private var cache: Pair<Long, Map<String, DerpPlace>>? = null

    fun cached(): Map<String, DerpPlace> = cache?.second.orEmpty()

    /** Blocking; call it off the main thread. A daemon that does not answer leaves the last answer. */
    fun load(): Map<String, DerpPlace> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { (at, places) -> if (now - at < TTL_MS) return places }
        val places = try {
            derpPlacesOf(Appctr.getDERPMapJSON())
        } catch (e: Exception) {
            Log.w(TAG, "DERP map: ${e.javaClass.simpleName}")
            emptyMap()
        }
        if (places.isEmpty()) return cached()
        cache = now to places
        return places
    }
}

/** [ExitNodeDerpSource] for a composable: what is known now, then what the daemon says. Nothing when [enabled] is off or in a preview. */
@Composable
internal fun rememberDerpPlaces(enabled: Boolean): Map<String, DerpPlace> {
    if (!enabled || LocalInspectionMode.current) return emptyMap()
    val places by produceState(ExitNodeDerpSource.cached()) {
        value = withContext(Dispatchers.IO) { ExitNodeDerpSource.load() }
    }
    return places
}

/**
 * The map and, under it, the exit nodes of the place last tapped. [placement] is
 * [placeExitNodes]; [selectedIp] the node in use, empty for none. [nodeRow] draws one node
 * and is where it is chosen. [onShowList] is the way to the nodes the map cannot place.
 */
@Composable
internal fun ExitNodeWorldMap(
    placement: ExitNodePlacement,
    selectedIp: String,
    strings: ExitNodeMapStrings,
    onShowList: () -> Unit,
    modifier: Modifier = Modifier,
    nodeRow: @Composable (PeerData) -> Unit,
) {
    val places = placement.places
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // A phone on its side gives the sheet some 250 dp of height: map above list would
        // leave neither enough, so there the nodes are listed beside the map.
        val sideBySide = constraints.hasBoundedHeight && maxHeight < 400.dp && maxWidth >= 560.dp
        val mapWidth = (if (sideBySide) maxWidth * 0.55f else maxWidth) - MAP_MARGIN * 2
        // Taller than the world's own shape, so that a zoomed-in map has room; but never
        // so tall that the nodes of the tapped place have nowhere to be listed.
        val mapHeight = minOf(
            mapWidth / 1.6f,
            320.dp,
            when {
                !constraints.hasBoundedHeight -> 320.dp
                sideBySide -> maxHeight - 16.dp
                else -> maxHeight * 0.55f
            }
        )
        val frame = remember(mapWidth, mapHeight, density) {
            with(density) { MapFrame(mapWidth.toPx().coerceAtLeast(1f), mapHeight.toPx().coerceAtLeast(1f)) }
        }
        val mergePx = with(density) { MERGE_DISTANCE.toPx() }
        val touchHalfPx = with(density) { MARKER_TOUCH.toPx() / 2 }

        // The view the map opens with and returns to: every placed node, not the whole
        // world — a tailnet whose exit nodes are three cities of Europe opens on Europe.
        val home = remember(frame, places) { frame.fit(places, with(density) { FIT_PADDING.toPx() }, HOME_MAX_SCALE) }
        // Null until the user moves the map, so that "home" keeps following the nodes as
        // they arrive. Read through currentView() in the layout and draw phases only: a
        // pinch then moves pixels without recomposing the markers every frame.
        var moved by remember(frame) { mutableStateOf<MapView?>(null) }
        // Through a state: the gesture below outlives a recomposition and must not keep an old home.
        val latestHome by rememberUpdatedState(home)
        val currentView = { moved ?: latestHome }
        val scale by remember(frame, home) { derivedStateOf { currentView().scale } }
        val atHome by remember(frame, home) { derivedStateOf { currentView() == home } }

        val scope = rememberCoroutineScope()
        val flight = remember { arrayOfNulls<Job>(1) }
        fun flyTo(target: MapView?) {
            flight[0]?.cancel()
            val from = currentView()
            val to = target ?: latestHome
            flight[0] = scope.launch {
                animate(0f, 1f, animationSpec = tween(350)) { t, _ -> moved = from.lerp(to, t) }
                moved = target
            }
        }

        val clusters = remember(places, scale, frame) {
            clusterPlaces(places, mergePx / (frame.width * scale))
                // The one that holds the node in use is drawn last, on top of its neighbours.
                .sortedBy { c -> c.places.any { p -> p.holds(selectedIp) } }
        }
        // The places whose nodes are listed under the map; it opens on the node in use.
        var opened by remember { mutableStateOf(setOfNotNull(places.firstOrNull { it.holds(selectedIp) }?.key)) }
        val openedPlaces = places.filter { it.key in opened }

        fun onMarker(cluster: ExitNodeCluster) {
            // A circle with a number is places too close to tap apart. Zooming in parts
            // them — unless they are one spot (a person's node guessed at the Frankfurt
            // relay, and Mullvad's Frankfurt), which no zoom parts: those are listed.
            val parts = cluster.places.size > 1 && scale < MapFrame.MAX_SCALE * 0.98f &&
                clusterPlaces(cluster.places, mergePx / (frame.width * MapFrame.MAX_SCALE)).size > 1
            if (parts) flyTo(frame.fit(cluster.places, touchHalfPx * 2, MapFrame.MAX_SCALE))
            else opened = cluster.places.mapTo(HashSet()) { it.key }
        }

        val scheme = MaterialTheme.colorScheme
        val backdrop = scheme.surfaceContainerLow
        val map: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .padding(horizontal = MAP_MARGIN, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(mapHeight)
                    .clip(MaterialTheme.shapes.large)
                    .background(backdrop)
                    .border(BorderStroke(1.dp, scheme.onSurface.copy(alpha = 0.08f)), MaterialTheme.shapes.large)
                    .pointerInput(frame) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var travelled = Offset.Zero
                            var spread = 1f
                            var moving = false
                            do {
                                val event = awaitPointerEvent()
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                if (!moving) {
                                    travelled += pan
                                    spread *= zoom
                                    val past = travelled.getDistance() > viewConfiguration.touchSlop ||
                                        abs(1 - spread) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
                                    // One finger on a map that shows the whole world has
                                    // nothing to pan: that drag is left to the sheet, which
                                    // closes on it, instead of being swallowed here.
                                    val fingers = event.changes.count { it.pressed }
                                    if (past && (fingers > 1 || currentView().scale > frame.minScale * 1.01f)) {
                                        moving = true
                                        flight[0]?.cancel()
                                    }
                                }
                                if (moving) {
                                    val at = event.calculateCentroid(useCurrent = true)
                                    if (at.isSpecified) moved = frame.transform(currentView(), at.x, at.y, pan.x, pan.y, zoom)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                WorldLandCanvas(frame, currentView, scheme.onSurface.copy(alpha = 0.10f))
                clusters.forEach { cluster ->
                    key(cluster.key) {
                        val single = cluster.places.singleOrNull()
                        MapMarker(
                            cluster = cluster,
                            selected = cluster.places.any { it.holds(selectedIp) },
                            opened = cluster.places.all { it.key in opened },
                            backdrop = backdrop,
                            description = if (single != null) strings.markerFormat.format(single.title(strings), single.nodes.size)
                            else strings.areaFormat.format(cluster.nodeCount),
                            onClick = { onMarker(cluster) },
                            modifier = Modifier.offset {
                                val view = currentView()
                                IntOffset(
                                    (frame.screenX(view, cluster.x) - touchHalfPx).roundToInt(),
                                    (frame.screenY(view, cluster.y) - touchHalfPx).roundToInt()
                                )
                            }
                        )
                    }
                }
                if (!atHome) {
                    FilledTonalIconButton(
                        onClick = { flyTo(null) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    ) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = strings.reset)
                    }
                }
            }
        }
        val details: @Composable ColumnScope.() -> Unit = {
            if (places.any { it.approximate }) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MapDot(approximate = true, color = scheme.onSurfaceVariant, backdrop = Color.Transparent, radius = 4.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.approximateNote, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }

            if (openedPlaces.isEmpty()) {
                Text(
                    strings.hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    openedPlaces.forEach { place ->
                        item(key = place.key) {
                            Row(
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MapDot(place.approximate, scheme.onSurfaceVariant, Color.Transparent, radius = 4.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    place.title(strings),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                        items(place.nodes, key = { place.key + "/" + (it.id ?: it.getPrimaryIp()) }) { nodeRow(it) }
                    }
                }
            }

            if (placement.unplaced.isNotEmpty()) {
                TextButton(onClick = onShowList, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(strings.unplacedFormat.format(placement.unplaced.size))
                }
            }
        }
        if (sideBySide) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(mapWidth + MAP_MARGIN * 2)) { map() }
                Column(Modifier.weight(1f)) { details() }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                map()
                details()
            }
        }
    }
}

private fun ExitNodePlace.holds(ip: String): Boolean = ip.isNotEmpty() && nodes.any { it.getPrimaryIp() == ip }

/** What a place is called: its city and country, or — for a guess — the relay it was guessed from, said as such. */
private fun ExitNodePlace.title(strings: ExitNodeMapStrings): String =
    if (relay != null) strings.nearRelayFormat.format(relay)
    else listOfNotNull(city, country).joinToString(", ").ifEmpty { nodes.first().getDisplayName() }

/** The land of [WorldLand] in world coordinates, with the box it fills — to skip or clip a ring by. */
private class LandRing(val xy: FloatArray) {
    val left: Float
    val right: Float
    val top: Float
    val bottom: Float

    init {
        var l = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in xy.indices step 2) {
            l = minOf(l, xy[i]); r = maxOf(r, xy[i])
            t = minOf(t, xy[i + 1]); b = maxOf(b, xy[i + 1])
        }
        left = l; right = r; top = t; bottom = b
    }
}

/** The outline under the dots. [view] is read while drawing, so a pan repaints and nothing more. */
@Composable
private fun WorldLandCanvas(frame: MapFrame, view: () -> MapView, color: Color) {
    val context = LocalContext.current
    val land = remember(context) {
        WorldLand.rings(context).map { ring ->
            LandRing(FloatArray(ring.size) { i ->
                if (i % 2 == 0) MapProjection.x(ring[i].toDouble()) else MapProjection.y(ring[i].toDouble())
            })
        }
    }
    val path = remember { Path() }
    Canvas(Modifier.fillMaxSize()) {
        val v = view()
        // A little past the frame, so the edge a clipped coast gets is never on screen.
        val margin = 4f / (frame.width * v.scale)
        val left = frame.worldX(v, 0f) - margin
        val right = frame.worldX(v, frame.width) + margin
        val top = frame.worldY(v, 0f) - margin
        val bottom = frame.worldY(v, frame.height) + margin
        path.rewind()
        for (ring in land) {
            if (ring.right < left || ring.left > right || ring.bottom < top || ring.top > bottom) continue
            val whole = ring.left >= left && ring.right <= right && ring.top >= top && ring.bottom <= bottom
            val pts = if (whole) ring.xy else clipRing(ring.xy, left, top, right, bottom)
            if (pts.isEmpty()) continue
            path.moveTo(frame.screenX(v, pts[0]), frame.screenY(v, pts[1]))
            for (i in 2 until pts.size step 2) path.lineTo(frame.screenX(v, pts[i]), frame.screenY(v, pts[i + 1]))
            path.close()
        }
        drawPath(path, color)
    }
}

/**
 * One marker: a dot for a place, a numbered circle for places too close to tell apart. The
 * touch target is [MARKER_TOUCH] whatever is drawn in it. [selected] holds the exit node in
 * use; [opened] is listed under the map.
 */
@Composable
private fun MapMarker(
    cluster: ExitNodeCluster,
    selected: Boolean,
    opened: Boolean,
    backdrop: Color,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(MARKER_TOUCH)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        val single = cluster.places.singleOrNull()
        if (single == null) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                    .background(if (selected) scheme.primary else scheme.secondaryContainer, CircleShape)
                    .border(1.dp, if (opened) scheme.onSurface else backdrop, CircleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    cluster.nodeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) scheme.onPrimary else scheme.onSecondaryContainer,
                    maxLines = 1,
                    // The description already says the number.
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        } else {
            MapDot(
                approximate = single.approximate,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                backdrop = backdrop,
                // A city with several nodes is a little heavier than a lone one.
                radius = if (single.nodes.size > 1) 6.5.dp else 5.dp,
                halo = selected,
                ring = opened,
            )
        }
    }
}

/**
 * The dot itself. A place the nodes named is solid; a guess — a node drawn at its relay —
 * is a hollow ring, the same convention as an open circle on a graph: not exactly here.
 * [halo] marks the exit node in use, [ring] the place listed under the map.
 */
@Composable
private fun MapDot(
    approximate: Boolean,
    color: Color,
    backdrop: Color,
    radius: Dp,
    halo: Boolean = false,
    ring: Boolean = false,
) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size((radius + if (halo || ring) 6.dp else 1.dp) * 2)) {
        val r = radius.toPx()
        if (halo) drawCircle(color.copy(alpha = 0.25f), r + 5.dp.toPx())
        if (ring) drawCircle(outline, r + 4.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        // A rim of the map's own colour keeps a dot apart from the coast and its neighbours.
        drawCircle(backdrop, r + 1.dp.toPx())
        if (approximate) {
            val stroke = 2.dp.toPx()
            drawCircle(color, r - stroke / 2, style = Stroke(stroke))
        } else {
            drawCircle(color, r)
        }
    }
}

/** Markers nearer than this would be one tap target on top of another. */
private val MERGE_DISTANCE = 44.dp

/** The least a finger can be asked to hit. */
private val MARKER_TOUCH = 48.dp

private val MAP_MARGIN = 16.dp

/** Kept clear around the nodes the opening view is fitted to: a marker at the very edge is half a marker. */
private val FIT_PADDING = 40.dp

/** The closest the opening view goes: a few countries across, enough coast to recognise them by. */
private const val HOME_MAX_SCALE = 8f
