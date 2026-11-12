package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.core.TrafficSnapshot
import io.github.bropines.tailscaled.core.trafficKey
import io.github.bropines.tailscaled.models.PeerData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/*
 * Where the tailnet map (TailnetMapScreen.kt) puts things, as arithmetic: which peers get a
 * node, in what order, and where each node stands. No Compose in it, so the tests can lay out
 * one peer or three hundred in any window and look at the numbers. All lengths are dp.
 *
 * This device is the centre. The peers stand on concentric ellipses around it — ellipses,
 * not circles, so a tall phone and a wide television both use their long side — evenly
 * spaced along each one. The peers this device is talking to take the inner rings, idle
 * ones come after, offline ones last. A window holds only so many nodes that stay readable;
 * the rest are handed back as [MapPlan.hidden] for the screen's "+N more".
 */

/** How much a peer matters to the map, most first. */
internal enum class MapTier {
    /** A path is in use: direct, through a peer relay, or through DERP. */
    LINKED,

    /** Online, but nothing has been sent to it yet. */
    IDLE,

    OFFLINE,
}

/** A peer as the map draws it: its [path] ([PeerPathNames.pathOf]) and its traffic right now. */
internal class MapPeer(val key: String, val peer: PeerData, val path: PeerPath, val rate: TrafficRate) {
    val tier: MapTier = when (path) {
        is PeerPath.Direct, is PeerPath.PeerRelay, is PeerPath.Derp -> MapTier.LINKED
        is PeerPath.NotYet, is PeerPath.Self -> MapTier.IDLE
        is PeerPath.Offline -> MapTier.OFFLINE
    }
    val name: String = peer.getDisplayName()
}

/** [peers] as the map draws them: each with its path, read the one way the app reads paths, and its rate. */
internal fun mapPeers(peers: List<PeerData>, traffic: TrafficSnapshot, names: PeerPathNames): List<MapPeer> =
    peers.map { p -> MapPeer(p.trafficKey() ?: p.getPrimaryIp(), p, names.pathOf(p, isSelf = false), traffic.rateOf(p)) }

/** The measures the layout keeps between nodes. */
internal data class MapMetrics(
    /** Room for one node along a ring: its circle and the name under it. */
    val slot: Float = 84f,
    /** Between two rings, measured along the window's short side. */
    val ringGap: Float = 72f,
    /** The first ring's distance from the centre: clear of this device's node and its name. */
    val firstRing: Float = 84f,
    /** Kept free at the left and right edge: half a node's slot. */
    val marginX: Float = 44f,
    /** Kept free above the outer ring: the upper half of a node. */
    val marginTop: Float = 28f,
    /** Kept free below it: the lower half of a node and its two lines of text. */
    val marginBottom: Float = 60f,
)

/** One ring: its radii and how many nodes it holds [MapMetrics.slot] apart. */
internal data class MapRing(val rx: Float, val ry: Float, val capacity: Int)

/** A node's place: its centre in the map's own box, and the ring it stands on (0 is the inner one). */
internal data class MapSpot(val x: Float, val y: Float, val ring: Int)

internal class MapNode(val peer: MapPeer, val spot: MapSpot)

/**
 * The map of one window: this device at ([centerX], [centerY]), the [nodes] that got a place —
 * most important first — and the peers that did not, in the order they would have been next.
 */
internal class MapPlan(val centerX: Float, val centerY: Float, val nodes: List<MapNode>, val hidden: List<MapPeer>)

/**
 * The rings a [width] × [height] window has room for, inner first. The outer one touches the
 * margins; the others divide what is between it and [MapMetrics.firstRing] so that no two
 * are closer than [MapMetrics.ringGap] on the short side. A window too small for two rings
 * has the one.
 */
internal fun mapRings(width: Float, height: Float, m: MapMetrics = MapMetrics()): List<MapRing> {
    val rxMax = (width / 2 - m.marginX).coerceAtLeast(1f)
    val ryMax = ((height - m.marginTop - m.marginBottom) / 2).coerceAtLeast(1f)
    val short = min(rxMax, ryMax)
    val count = if (short < m.firstRing + m.ringGap) 1 else ((short - m.firstRing) / m.ringGap).toInt() + 1
    return List(count) { i ->
        val scale = if (count == 1) 1f else (m.firstRing + i * (short - m.firstRing) / (count - 1)) / short
        val rx = rxMax * scale
        val ry = ryMax * scale
        MapRing(rx, ry, (ellipsePerimeter(rx, ry) / m.slot).toInt().coerceAtLeast(1))
    }
}

/** How finely an ellipse is walked to measure it; a third of a degree. */
private const val ELLIPSE_STEPS = 1080

private fun ellipseArcs(rx: Float, ry: Float): FloatArray {
    val arcs = FloatArray(ELLIPSE_STEPS + 1)
    var px = 0f
    var py = -ry
    for (i in 1..ELLIPSE_STEPS) {
        val a = -PI / 2 + 2 * PI * i / ELLIPSE_STEPS
        val x = (rx * cos(a)).toFloat()
        val y = (ry * sin(a)).toFloat()
        arcs[i] = arcs[i - 1] + hypot(x - px, y - py)
        px = x
        py = y
    }
    return arcs
}

internal fun ellipsePerimeter(rx: Float, ry: Float): Float = ellipseArcs(rx, ry)[ELLIPSE_STEPS]

/**
 * [n] points on an ellipse, the same distance apart *along the curve*, clockwise from the
 * top, relative to its centre. Equal angles would crowd the nodes at the ends of the long
 * axis and leave the flat sides bare. [phase] shifts them all by that share of one step, so
 * the nodes of neighbouring rings do not line up into spokes.
 */
internal fun ellipsePoints(rx: Float, ry: Float, n: Int, phase: Float = 0f): List<Pair<Float, Float>> {
    if (n <= 0) return emptyList()
    val arcs = ellipseArcs(rx, ry)
    val total = arcs[ELLIPSE_STEPS]
    var step = 0
    return List(n) { j ->
        val target = (j + phase) / n * total
        while (step < ELLIPSE_STEPS - 1 && arcs[step + 1] < target) step++
        val span = arcs[step + 1] - arcs[step]
        val within = if (span > 0f) ((target - arcs[step]) / span).coerceIn(0f, 1f) else 0f
        val a = -PI / 2 + 2 * PI * (step + within) / ELLIPSE_STEPS
        (rx * cos(a)).toFloat() to (ry * sin(a)).toFloat()
    }
}

/**
 * Which of the rings [n] nodes stand on: the fewest neighbouring rings that hold them all,
 * taken from the middle of what the window has — one peer on a tablet belongs neither next
 * to this device nor out at the edge — and, where there is no middle, from the outer side:
 * a phone's ten peers read better spread over its outer ring than huddled on the inner one
 * with the screen empty around them. All of the rings when even that is not enough.
 */
internal fun chooseRings(capacities: List<Int>, n: Int): IntRange {
    for (used in 1..capacities.size) {
        val first = (capacities.size - used + 1) / 2
        if (capacities.subList(first, first + used).sum() >= n) return first until first + used
    }
    return capacities.indices
}

/**
 * How many of [n] nodes each ring takes: in proportion to what it holds, so the rings look
 * equally full instead of the inner one packed and the outer one with a single straggler.
 * Never more than a ring's capacity; what the rounding leaves over goes to the inner rings.
 */
internal fun spreadOverRings(capacities: List<Int>, n: Int): List<Int> {
    val room = capacities.sum()
    if (n >= room) return capacities
    val shares = capacities.map { (n.toLong() * it / room).toInt() }.toMutableList()
    var left = n - shares.sum()
    while (left > 0) {
        for (i in shares.indices) {
            if (left > 0 && shares[i] < capacities[i]) {
                shares[i]++
                left--
            }
        }
    }
    return shares
}

/** Where a peer stands among its equals: by tier, then by name, then by key so that no two tie. */
private val PLACE_ORDER = compareBy<MapPeer>({ it.tier }, { it.name.lowercase() }, { it.key })

/**
 * Who gets a node when not everybody can: a peer that is moving traffic right now before any
 * that is not, then [PLACE_ORDER]. Whether traffic flows decides only *who* is drawn — where
 * they are drawn never depends on a rate, or the nodes would trade places every time one
 * changed.
 */
private val KEEP_ORDER = compareByDescending<MapPeer> { it.rate.totalBps > 0 }.then(PLACE_ORDER)

/**
 * Lays [peers] out in a [width] × [height] box. While everybody fits, a peer's place depends
 * only on the set of peers and their paths, so the map holds still between two readings; the
 * busiest peers displace idle ones only on a tailnet too large to draw whole.
 *
 * [moreRoom] is the height of the screen's "+N more" button. It is taken off the bottom only
 * when somebody is left out — and decided on the box *without* it, so a tailnet that just
 * fits is not pushed over the edge by the room for a button it would not have needed.
 */
internal fun planTailnetMap(
    peers: List<MapPeer>,
    width: Float,
    height: Float,
    m: MapMetrics = MapMetrics(),
    moreRoom: Float = 0f,
): MapPlan {
    val fits = peers.size <= mapRings(width, height, m).sumOf { it.capacity }
    val metrics = if (fits || moreRoom <= 0f) m else m.copy(marginBottom = m.marginBottom + moreRoom)
    val rings = mapRings(width, height, metrics)
    val centerX = width / 2
    val centerY = metrics.marginTop + (height - metrics.marginTop - metrics.marginBottom).coerceAtLeast(2f) / 2
    val capacities = rings.map { it.capacity }
    val room = capacities.sum()

    val kept = peers.sortedWith(KEEP_ORDER)
    val shown = kept.take(room).sortedWith(PLACE_ORDER)
    val hidden = kept.drop(room).sortedWith(PLACE_ORDER)

    val used = chooseRings(capacities, shown.size)
    val shares = spreadOverRings(capacities.slice(used), shown.size)
    val nodes = ArrayList<MapNode>(shown.size)
    var next = 0
    used.forEachIndexed { i, ringIndex ->
        val ring = rings[ringIndex]
        // Every other ring starts half a step on, so the rings do not form spokes. Counted
        // from the first ring in use, which therefore always starts at the top: a lone peer
        // stands above this device, not wherever its ring's turn happened to put it.
        ellipsePoints(ring.rx, ring.ry, shares[i], phase = if (i % 2 == 0) 0f else 0.5f).forEach { (x, y) ->
            nodes += MapNode(shown[next++], MapSpot(centerX + x, centerY + y, ringIndex))
        }
    }
    return MapPlan(centerX, centerY, nodes, hidden)
}

/** The rate at which a link is as thick as it gets: 10 MB/s. */
private const val FULL_WEIGHT_BPS = 10_000_000.0

/** Below this a link is at its thinnest: a keepalive is not traffic. */
private const val NO_WEIGHT_BPS = 1_000.0

/**
 * How heavy a link is drawn for [bps], from 0 (idle) to 1. Logarithmic: rates span five
 * orders of magnitude, and on a linear scale everything short of a download would be a
 * hairline.
 */
internal fun linkWeight(bps: Long): Float {
    if (bps <= NO_WEIGHT_BPS) return 0f
    return (ln(bps / NO_WEIGHT_BPS) / ln(FULL_WEIGHT_BPS / NO_WEIGHT_BPS)).toFloat().coerceIn(0f, 1f)
}
