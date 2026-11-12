package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/*
 * Where the exit nodes go on the map, and the map's arithmetic: placing, projection, the
 * view (zoom and pan) and clustering. Nothing here draws or asks the daemon, so all of it is
 * tested on the JVM (ExitNodePlacesTest); the drawing is ui/ExitNodeWorldMap.kt.
 *
 * A node is placed by what it says about itself (tailcfg.Location — Mullvad's nodes carry
 * one) or, failing that, at its home DERP relay. The second is a guess: a device is usually
 * within a few hundred kilometres of the relay it prefers, not at it, and the place says so.
 */

/** A DERP region as far as the map needs it: its city and where that is. */
internal data class DerpPlace(val name: String, val latitude: Double, val longitude: Double)

@Serializable
private class DerpGeoMap(@SerialName("Regions") val regions: Map<String, DerpGeoRegion> = emptyMap())

@Serializable
private class DerpGeoRegion(
    @SerialName("RegionCode") val code: String = "",
    @SerialName("RegionName") val name: String = "",
    @SerialName("Latitude") val latitude: Double = 0.0,
    @SerialName("Longitude") val longitude: Double = 0.0,
)

/**
 * The regions of a DERP map (/localapi/v0/derpmap) that say where they are, by region code —
 * what a peer's `Relay` holds. A region without coordinates is left out: a custom relay of a
 * self-hosted control server usually has none, and (0, 0) is a point in the Gulf of Guinea.
 * Text that is not a DERP map gives nothing.
 */
internal fun derpPlacesOf(derpMapJson: String): Map<String, DerpPlace> = try {
    AppJson.decodeFromString<DerpGeoMap>(derpMapJson).regions.values
        .filter { it.code.isNotBlank() && isPlace(it.latitude, it.longitude) }
        .associate { it.code to DerpPlace(it.name.ifBlank { it.code }, it.latitude, it.longitude) }
} catch (e: Exception) {
    emptyMap()
}

private fun isPlace(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && !(latitude == 0.0 && longitude == 0.0) &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

/**
 * One dot's worth of exit nodes: a city, or a relay. [relay] is the relay's city when the
 * place is the guess described above and null when the nodes named the place themselves.
 */
internal data class ExitNodePlace(
    val key: String,
    val latitude: Double,
    val longitude: Double,
    val city: String?,
    val country: String?,
    val relay: String?,
    val nodes: List<PeerData>,
) {
    val approximate: Boolean get() = relay != null
    val x: Float = MapProjection.x(longitude)
    val y: Float = MapProjection.y(latitude)
}

/** [places] largest first; [unplaced] are the nodes the map cannot show, in the list's order. */
internal data class ExitNodePlacement(val places: List<ExitNodePlace>, val unplaced: List<PeerData>)

/**
 * Sorts [nodes] onto the map. [derp] is [derpPlacesOf] the daemon's DERP map. Nodes of one
 * city share a place, and so do nodes guessed at one relay — several of a person's own
 * devices behind "fra" are one dot that lists them, not dots hiding each other.
 */
internal fun placeExitNodes(nodes: List<PeerData>, derp: Map<String, DerpPlace>): ExitNodePlacement {
    val places = LinkedHashMap<String, ExitNodePlace>()
    val unplaced = ArrayList<PeerData>()
    for (node in nodes) {
        val loc = node.location
        val relay = node.relay?.takeIf { it.isNotBlank() }?.let { code -> derp[code] }
        val place = when {
            loc != null && isPlace(loc.latitude, loc.longitude) -> {
                // The city code is what tells two cities of one name apart; without it the
                // coordinates do, rounded so that a provider's rounding noise is one city.
                val key = if (!loc.cityCode.isNullOrBlank()) "loc:${loc.countryCode.orEmpty()}/${loc.cityCode}"
                else "loc:%.1f,%.1f".format(java.util.Locale.ROOT, loc.latitude, loc.longitude)
                places[key] ?: ExitNodePlace(
                    key, loc.latitude!!, loc.longitude!!,
                    city = loc.city?.takeIf { it.isNotBlank() },
                    country = (loc.country ?: loc.countryCode)?.takeIf { it.isNotBlank() },
                    relay = null, nodes = emptyList()
                )
            }
            relay != null -> {
                val key = "derp:${node.relay}"
                places[key] ?: ExitNodePlace(key, relay.latitude, relay.longitude, null, null, relay.name, emptyList())
            }
            else -> null
        }
        if (place == null) unplaced.add(node) else places[place.key] = place.copy(nodes = place.nodes + node)
    }
    // Largest first: clustering seeds from the front, so a hub keeps its neighbours and the
    // picture does not depend on the order the daemon listed the peers in.
    return ExitNodePlacement(places.values.sortedWith(compareBy({ -it.nodes.size }, { it.key })), unplaced)
}

/**
 * Miller's cylindrical projection, cropped to the latitudes that have land worth showing. It
 * is chosen over the plain equirectangular grid because it gives the high latitudes more
 * height — Europe, where a provider's cities sit a degree apart, comes out about a third
 * taller — without Mercator's runaway near the poles, and its shapes are the familiar ones.
 *
 * World coordinates: x runs 0…1 across 360° of longitude, y runs 0…[HEIGHT] downwards in
 * the same unit, so a world unit is as long on screen in both directions.
 */
internal object MapProjection {
    const val LAT_TOP = 80.0
    const val LAT_BOTTOM = -58.0

    private fun miller(latitude: Double): Double = 1.25 * ln(tan(PI / 4 + 0.4 * Math.toRadians(latitude)))

    private val top = miller(LAT_TOP)

    /** The cropped world's height in world units; its width is 1. */
    val HEIGHT: Float = ((top - miller(LAT_BOTTOM)) / (2 * PI)).toFloat()

    fun x(longitude: Double): Float = ((longitude + 180.0) / 360.0).toFloat()

    /** Latitudes beyond the crop land on its edge: a node at 82°N is still a dot. */
    fun y(latitude: Double): Float =
        ((top - miller(latitude.coerceIn(LAT_BOTTOM, LAT_TOP))) / (2 * PI)).toFloat()
}

/** What the map shows: [scale] 1 is the world as wide as the frame; ([cx], [cy]) is the world point at its centre. */
internal data class MapView(val scale: Float, val cx: Float, val cy: Float) {
    /** Between this view and [to]. The zoom goes geometrically, which is what looks even. */
    fun lerp(to: MapView, t: Float): MapView = MapView(
        scale = exp(ln(scale) + (ln(to.scale) - ln(scale)) * t),
        cx = cx + (to.cx - cx) * t,
        cy = cy + (to.cy - cy) * t,
    )
}

/** The map's frame on screen, in pixels, and everything that depends on its size. */
internal class MapFrame(val width: Float, val height: Float) {
    /** The whole world in the frame: by width, or by height in a frame flatter than the world. */
    val minScale: Float = min(1f, height / (width * MapProjection.HEIGHT))

    /** Pixels per world unit. */
    private fun unit(view: MapView) = width * view.scale

    fun screenX(view: MapView, x: Float): Float = (x - view.cx) * unit(view) + width / 2
    fun screenY(view: MapView, y: Float): Float = (y - view.cy) * unit(view) + height / 2
    fun worldX(view: MapView, screenX: Float): Float = view.cx + (screenX - width / 2) / unit(view)
    fun worldY(view: MapView, screenY: Float): Float = view.cy + (screenY - height / 2) / unit(view)

    /**
     * [view] within its limits: no closer than [MAX_SCALE], no further than the whole world,
     * and never panned past the world's edge — an axis the world does not fill is centred.
     */
    fun clamp(view: MapView): MapView {
        val scale = view.scale.coerceIn(minScale, MAX_SCALE)
        val halfX = width / 2 / (width * scale)
        val halfY = height / 2 / (width * scale)
        return MapView(
            scale,
            if (halfX * 2 >= 1f) 0.5f else view.cx.coerceIn(halfX, 1f - halfX),
            if (halfY * 2 >= MapProjection.HEIGHT) MapProjection.HEIGHT / 2 else view.cy.coerceIn(halfY, MapProjection.HEIGHT - halfY),
        )
    }

    /** The whole world. */
    fun whole(): MapView = clamp(MapView(minScale, 0.5f, MapProjection.HEIGHT / 2))

    /**
     * [view] after one step of a pinch: the fingers' centre is at ([focusX], [focusY]) having
     * moved by ([panX], [panY]) and spread by [zoom]. The world point that was under the
     * fingers stays under them — that is what makes a pinch feel attached to the map.
     */
    fun transform(view: MapView, focusX: Float, focusY: Float, panX: Float, panY: Float, zoom: Float): MapView {
        val wx = worldX(view, focusX - panX)
        val wy = worldY(view, focusY - panY)
        val scale = (view.scale * zoom).coerceIn(minScale, MAX_SCALE)
        return clamp(MapView(scale, wx - (focusX - width / 2) / (width * scale), wy - (focusY - height / 2) / (width * scale)))
    }

    /**
     * The closest view that holds all of [places] with [padding] pixels around them, no
     * closer than [maxScale]: one lone node would otherwise be magnified until no coast is
     * left to tell where it is.
     */
    fun fit(places: List<ExitNodePlace>, padding: Float, maxScale: Float): MapView {
        if (places.isEmpty()) return whole()
        val left = places.minOf { it.x }
        val right = places.maxOf { it.x }
        val top = places.minOf { it.y }
        val bottom = places.maxOf { it.y }
        val roomX = max(width - 2 * padding, 1f)
        val roomY = max(height - 2 * padding, 1f)
        // A span of nothing (one place) asks for an infinite zoom, which maxScale answers.
        val scale = min(roomX / (width * max(right - left, 1e-6f)), roomY / (width * max(bottom - top, 1e-6f)))
        return clamp(MapView(min(scale, maxScale), (left + right) / 2, (top + bottom) / 2))
    }

    companion object {
        /** Close enough to part neighbouring cities (Amsterdam and Brussels, a degree apart). */
        const val MAX_SCALE = 32f
    }
}

/** What one marker stands for: a place, or several too close to tap apart at this zoom. */
internal data class ExitNodeCluster(val places: List<ExitNodePlace>) {
    val x: Float = places.sumOf { it.x.toDouble() }.toFloat() / places.size
    val y: Float = places.sumOf { it.y.toDouble() }.toFloat() / places.size
    val nodeCount: Int get() = places.sumOf { it.nodes.size }
    /** Stable while the cluster's membership is: what the UI keys a marker by. */
    val key: String get() = places.first().key + "+" + (places.size - 1)
}

/**
 * Groups [places] whose markers would overlap: each joins the nearest group whose first
 * member is within [mergeDistance] (world units — the marker's size divided by the zoom),
 * or starts one. Greedy and order-dependent on purpose: with [placeExitNodes]' largest-first
 * order the hubs are the seeds, and the same input always gives the same picture.
 */
internal fun clusterPlaces(places: List<ExitNodePlace>, mergeDistance: Float): List<ExitNodeCluster> {
    val groups = ArrayList<MutableList<ExitNodePlace>>()
    val limit = mergeDistance * mergeDistance
    for (place in places) {
        var nearest: MutableList<ExitNodePlace>? = null
        var best = limit
        for (group in groups) {
            val seed = group[0]
            val dx = seed.x - place.x
            val dy = seed.y - place.y
            val d = dx * dx + dy * dy
            if (d <= best) {
                best = d
                nearest = group
            }
        }
        if (nearest != null) nearest.add(place) else groups.add(mutableListOf(place))
    }
    return groups.map { ExitNodeCluster(it) }
}

/**
 * Clips a closed ring `[x0, y0, x1, y1, …]` to a rectangle (Sutherland–Hodgman). The map
 * draws only the visible part of a coastline: zoomed in, Eurasia is tens of thousands of
 * pixels wide, and Android before 9 rasterises a path into a texture of its full bounds and
 * silently draws nothing once that exceeds the GPU's limit.
 */
internal fun clipRing(ring: FloatArray, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
    var pts = ring
    for (edge in 0 until 4) {
        if (pts.size < 6) return FloatArray(0)
        // Each vertex gives at most itself and one crossing.
        val out = FloatArray(pts.size * 2)
        var n = 0
        fun inside(x: Float, y: Float) = when (edge) {
            0 -> x >= left
            1 -> x <= right
            2 -> y >= top
            else -> y <= bottom
        }
        var px = pts[pts.size - 2]
        var py = pts[pts.size - 1]
        var pIn = inside(px, py)
        var i = 0
        while (i < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            val isIn = inside(x, y)
            if (isIn != pIn) {
                if (edge < 2) {
                    val bound = if (edge == 0) left else right
                    out[n++] = bound
                    out[n++] = py + (bound - px) / (x - px) * (y - py)
                } else {
                    val bound = if (edge == 2) top else bottom
                    out[n++] = px + (bound - py) / (y - py) * (x - px)
                    out[n++] = bound
                }
            }
            if (isIn) {
                out[n++] = x
                out[n++] = y
            }
            px = x
            py = y
            pIn = isIn
            i += 2
        }
        pts = out.copyOf(n)
    }
    return if (pts.size < 6) FloatArray(0) else pts
}
