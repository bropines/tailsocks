package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.models.PeerData
import kotlin.math.ceil
import kotlin.math.max

/*
 * The tailnet map's other pictures, as arithmetic like TailnetMapLayout.kt and on top of it:
 * which peers the reader asked to see (the filter), what they are grouped by, and where each
 * scheme puts a node. No Compose here; the screen draws whatever [MapScene] it is handed, so
 * a scheme is one function from peers and a window to a scene. All lengths are dp.
 *
 *  - Rings is TailnetMapLayout.kt's plan, unchanged: everybody around this device.
 *  - Routes puts what carries the traffic between this device and a peer on the picture: a
 *    hub for every DERP region and every peer relay in use on the inner ring, the peers
 *    reached through it on the arc behind it, the direct ones on an arc of their own.
 *  - Tree is the whole tailnet as an outline that grows down the window: this device, under
 *    it a row per group, the group's devices along the row. It is the one scheme that may be
 *    larger than the window, which is what the screen's pan and zoom are for.
 */

/** The key this device's own node goes by; no node id looks like it. */
internal const val MAP_SELF_KEY = "\u0000self"

/** What a hub's key starts with, so a selection can tell a hub from a peer. */
internal const val MAP_HUB_PREFIX = "\u0000hub:"

internal enum class MapScheme { RINGS, ROUTES, TREE }

/** What the tree's second level is. */
internal enum class TreeGrouping { ROUTE, OWNER, OS, STATE }

/** What a link is drawn as: one per kind of [PeerPath], and the plain line of a group that is not a path. */
internal enum class MapLinkKind {
    DIRECT, DERP, PEER_RELAY, IDLE, OFFLINE, GROUP;

    companion object {
        fun of(path: PeerPath): MapLinkKind = when (path) {
            is PeerPath.Direct -> DIRECT
            is PeerPath.Derp -> DERP
            is PeerPath.PeerRelay -> PEER_RELAY
            is PeerPath.NotYet, is PeerPath.Self -> IDLE
            is PeerPath.Offline -> OFFLINE
        }
    }
}

/** What a group of peers has in common, in the order groups are drawn. [relay] is true for the two that stand between devices. */
internal enum class MapGroupKind(val link: MapLinkKind, val relay: Boolean = false) {
    /** A path is in use, whichever: the tree's grouping by state. */
    LINKED(MapLinkKind.GROUP),
    DIRECT(MapLinkKind.DIRECT),
    PEER_RELAY(MapLinkKind.PEER_RELAY, relay = true),
    DERP(MapLinkKind.DERP, relay = true),
    IDLE(MapLinkKind.IDLE),
    OFFLINE(MapLinkKind.OFFLINE),
    USER(MapLinkKind.GROUP),
    TAG(MapLinkKind.GROUP),
    OS(MapLinkKind.GROUP),
}

/**
 * Peers with one thing in common, in place order. [name] is what the data calls the group — a
 * DERP region's code, a relay node, a user, a tag, an OS family — and null where only the
 * screen has a word for it ("direct", "offline"); [detail] is a DERP region's city.
 */
internal class MapGroup(
    val kind: MapGroupKind,
    val name: String?,
    val detail: String?,
    val members: List<MapPeer>,
    /** What tells the group from another of its kind: its name, or a user's id — two users may share a name. */
    id: String = name.orEmpty(),
) {
    val key: String = MAP_HUB_PREFIX + kind.name + ":" + id
    val rate: TrafficRate = TrafficRate(members.sumOf { it.rate.rxBps }, members.sumOf { it.rate.txBps })
    val linked: Boolean = members.any { it.tier == MapTier.LINKED }
}

/** The OS families the map tells apart, as [osFamily] names them. */
internal const val OS_OTHER = "other"
internal val OS_FAMILIES = listOf("android", "ios", "windows", "macos", "linux", "tvos", OS_OTHER)

/** [peer]'s OS as one of [OS_FAMILIES], read the way its icon is chosen. */
internal fun osFamily(peer: PeerData): String {
    val os = peer.iconOs()?.lowercase().orEmpty()
    return when {
        "android" in os -> "android"
        "tvos" in os -> "tvos"
        "ios" in os || "ipados" in os -> "ios"
        "windows" in os -> "windows"
        "macos" in os || "darwin" in os -> "macos"
        "linux" in os -> "linux"
        else -> OS_OTHER
    }
}

private const val OWNER_TAG = "tag:"
private const val OWNER_USER = "user:"

/**
 * Who a device belongs to, as filter keys: its tags when it has any — a tagged device belongs
 * to nobody, whatever user the status files it under — else its user.
 */
internal fun ownerKeys(peer: PeerData): List<String> {
    val tags = peer.tags.orEmpty().filter { it.isNotBlank() }
    return if (tags.isNotEmpty()) tags.map { if (it.startsWith(OWNER_TAG)) it else OWNER_TAG + it }
    else listOf(OWNER_USER + (peer.userID ?: 0L))
}

internal fun isTagOwner(key: String): Boolean = key.startsWith(OWNER_TAG)

/** The user id inside a user's owner key; null for a tag's. */
internal fun ownerUserId(key: String): Long? = key.removePrefix(OWNER_USER).takeIf { key.startsWith(OWNER_USER) }?.toLongOrNull()

/**
 * Whether [peer] routes a subnet: it is allowed addresses that are neither its own nor the
 * whole internet (which is an exit node, and has its own flag).
 */
internal fun isSubnetRouter(peer: PeerData): Boolean {
    val own = peer.tailscaleIPs.orEmpty().toSet()
    return peer.allowedIPs.orEmpty().any { route ->
        val address = route.substringBefore('/')
        route.isNotBlank() && address !in own && route != "0.0.0.0/0" && route != "::/0"
    }
}

internal fun isExitNode(peer: PeerData): Boolean = peer.exitNodeOption == true || peer.exitNode == true

/** The paths the filter can ask for. An offline peer has none of them. */
internal enum class MapPathFilter { DIRECT, DERP, PEER_RELAY, NOT_YET }

/**
 * What the reader asked to see. Every part that is set narrows the map; inside a part the
 * choices add up — "direct or DERP", "Android or Linux". [query] is looked for in a device's
 * names, addresses and OS.
 */
internal data class MapFilter(
    val query: String = "",
    val onlineOnly: Boolean = false,
    val withTraffic: Boolean = false,
    val paths: Set<MapPathFilter> = emptySet(),
    val os: Set<String> = emptySet(),
    val owners: Set<String> = emptySet(),
    val exitNodes: Boolean = false,
    val subnetRouters: Boolean = false,
) {
    /** How many filters are on, each chip counted. */
    val count: Int
        get() = (if (query.isBlank()) 0 else 1) + (if (onlineOnly) 1 else 0) + (if (withTraffic) 1 else 0) +
            paths.size + os.size + owners.size + (if (exitNodes) 1 else 0) + (if (subnetRouters) 1 else 0)

    val isEmpty: Boolean get() = count == 0

    fun matches(p: MapPeer): Boolean {
        val peer = p.peer
        if (onlineOnly && p.tier == MapTier.OFFLINE) return false
        if (withTraffic && p.rate.totalBps <= 0) return false
        if (paths.isNotEmpty()) {
            val kind = when (p.path) {
                is PeerPath.Direct -> MapPathFilter.DIRECT
                is PeerPath.Derp -> MapPathFilter.DERP
                is PeerPath.PeerRelay -> MapPathFilter.PEER_RELAY
                is PeerPath.NotYet -> MapPathFilter.NOT_YET
                is PeerPath.Offline, is PeerPath.Self -> null
            }
            if (kind !in paths) return false
        }
        if (os.isNotEmpty() && osFamily(peer) !in os) return false
        if (owners.isNotEmpty() && ownerKeys(peer).none { it in owners }) return false
        if ((exitNodes || subnetRouters) && !((exitNodes && isExitNode(peer)) || (subnetRouters && isSubnetRouter(peer)))) return false
        val q = query.trim()
        if (q.isNotEmpty()) {
            val found = p.name.contains(q, ignoreCase = true) ||
                peer.hostName?.contains(q, ignoreCase = true) == true ||
                peer.dnsName?.contains(q, ignoreCase = true) == true ||
                peer.os?.contains(q, ignoreCase = true) == true ||
                peer.tailscaleIPs.orEmpty().any { it.contains(q, ignoreCase = true) }
            if (!found) return false
        }
        return true
    }
}

/**
 * What a tailnet gives the filter to choose from: only the OS families, owners and roles it
 * actually has, so the sheet offers nothing that would empty the map by construction.
 */
internal class MapFilterOptions(
    val os: List<String>,
    /** Owner keys ([ownerKeys]): users first, then tags, each sorted. */
    val owners: List<String>,
    val exitNodes: Boolean,
    val subnetRouters: Boolean,
)

internal fun mapFilterOptions(peers: List<MapPeer>): MapFilterOptions {
    val families = peers.mapTo(HashSet()) { osFamily(it.peer) }
    val owners = peers.flatMapTo(HashSet()) { ownerKeys(it.peer) }
    return MapFilterOptions(
        os = OS_FAMILIES.filter { it in families },
        owners = owners.sortedWith(compareBy({ isTagOwner(it) }, { it })),
        exitNodes = peers.any { isExitNode(it.peer) },
        subnetRouters = peers.any { isSubnetRouter(it.peer) },
    )
}

/**
 * [peers] in groups, the groups in the order they are drawn and each one's members in place
 * order. [users] names the users of the status by id; [selfUser], this device's own, goes
 * first among them. A device with several tags stands under the first: a tree has one parent
 * for everybody.
 */
internal fun groupPeers(
    peers: List<MapPeer>,
    by: TreeGrouping,
    users: Map<Long, String> = emptyMap(),
    selfUser: Long? = null,
): List<MapGroup> {
    class Slot(val kind: MapGroupKind, val id: String, val name: String?, val detail: String?, val order: String = id.lowercase())

    fun slot(p: MapPeer): Slot = when (by) {
        TreeGrouping.ROUTE -> when (val path = p.path) {
            is PeerPath.Direct -> Slot(MapGroupKind.DIRECT, "", null, null)
            is PeerPath.PeerRelay -> (path.node ?: endpointHost(path.address)).let { Slot(MapGroupKind.PEER_RELAY, it, it, null) }
            is PeerPath.Derp -> Slot(MapGroupKind.DERP, path.region, path.region, path.city)
            is PeerPath.NotYet, is PeerPath.Self -> Slot(MapGroupKind.IDLE, "", null, null)
            is PeerPath.Offline -> Slot(MapGroupKind.OFFLINE, "", null, null)
        }
        TreeGrouping.OWNER -> ownerKeys(p.peer).first().let { key ->
            val user = ownerUserId(key)
            if (user == null) Slot(MapGroupKind.TAG, key, key, null)
            // This device's own user first, then by name; one the status does not name goes last.
            else Slot(
                MapGroupKind.USER, user.toString(), users[user], null,
                order = (if (user == selfUser) "0" else "1") + (users[user]?.lowercase() ?: "\uFFFF") + user
            )
        }
        TreeGrouping.OS -> osFamily(p.peer).let { Slot(MapGroupKind.OS, it, it, null, order = OS_FAMILIES.indexOf(it).toString().padStart(2, '0')) }
        TreeGrouping.STATE -> when (p.tier) {
            MapTier.LINKED -> Slot(MapGroupKind.LINKED, "", null, null)
            MapTier.IDLE -> Slot(MapGroupKind.IDLE, "", null, null)
            MapTier.OFFLINE -> Slot(MapGroupKind.OFFLINE, "", null, null)
        }
    }

    val slots = LinkedHashMap<String, Pair<Slot, MutableList<MapPeer>>>()
    for (p in peers.sortedWith(PLACE_ORDER)) {
        val s = slot(p)
        slots.getOrPut(s.kind.name + ":" + s.id) { s to ArrayList() }.second += p
    }
    return slots.values
        .sortedWith(compareBy({ it.first.kind }, { it.first.order }))
        .map { (s, members) -> MapGroup(s.kind, s.name, s.detail, members, s.id) }
}

/** A node of a scene that is a device. [parent] is the key of what it hangs off: a hub, or this device. */
internal class ScenePeer(val peer: MapPeer, val x: Float, val y: Float, val parent: String)

/** A node of a scene that is a group: a relay between devices, or a branch of the tree. */
internal class SceneHub(val group: MapGroup, val x: Float, val y: Float)

/**
 * A line between two nodes, by their keys. [linked] is a path in use, drawn in full and as
 * heavy as [rate]. A link with a [drop] is drawn square instead of straight: down from its
 * start by [drop], across to [inset] short of its end, down to the end's height and in — the
 * branches of the tree; `drop = 0, inset = 0` is a plain corner.
 */
internal class SceneLink(
    val from: String,
    val to: String,
    val kind: MapLinkKind,
    val rate: TrafficRate,
    val linked: Boolean,
    val drop: Float = Float.NaN,
    val inset: Float = 0f,
) {
    val square: Boolean get() = !drop.isNaN()
}

/**
 * One picture of the tailnet: this device at ([selfX], [selfY]) in a [width] × [height] box
 * that is the window or, for a tree that outgrew it, larger; the nodes in the order a
 * keyboard walks them; and the peers that got no node.
 */
internal class MapScene(
    val scheme: MapScheme,
    val width: Float,
    val height: Float,
    val selfX: Float,
    val selfY: Float,
    val hubs: List<SceneHub>,
    val peers: List<ScenePeer>,
    val links: List<SceneLink>,
    val hidden: List<MapPeer>,
)

/** The scene of [scheme] for [peers] in a [width] × [height] window. See [planTailnetMap] for [moreRoom]. */
internal fun planMapScene(
    scheme: MapScheme,
    grouping: TreeGrouping,
    peers: List<MapPeer>,
    width: Float,
    height: Float,
    users: Map<Long, String> = emptyMap(),
    selfUser: Long? = null,
    m: MapMetrics = MapMetrics(),
    moreRoom: Float = 0f,
): MapScene = when (scheme) {
    MapScheme.RINGS -> ringsScene(peers, width, height, m, moreRoom)
    MapScheme.ROUTES -> routesScene(peers, width, height, m, moreRoom)
    MapScheme.TREE -> treeScene(groupPeers(peers, grouping, users, selfUser), width, height, byRoute = grouping == TreeGrouping.ROUTE)
}

private fun selfLink(p: MapPeer) =
    SceneLink(MAP_SELF_KEY, p.key, MapLinkKind.of(p.path), p.rate, linked = p.tier == MapTier.LINKED)

/** [planTailnetMap] as a scene: every peer linked straight to this device. */
internal fun ringsScene(peers: List<MapPeer>, width: Float, height: Float, m: MapMetrics = MapMetrics(), moreRoom: Float = 0f): MapScene {
    val plan = planTailnetMap(peers, width, height, m, moreRoom)
    return MapScene(
        MapScheme.RINGS, width, height, plan.centerX, plan.centerY,
        hubs = emptyList(),
        peers = plan.nodes.map { ScenePeer(it.peer, it.spot.x, it.spot.y, MAP_SELF_KEY) },
        links = plan.nodes.map { selfLink(it.peer) },
        hidden = plan.hidden,
    )
}

/** Which hubs get a place when the inner ring cannot hold them all: the ones carrying traffic, then the fullest. */
private val HUB_KEEP_ORDER = compareByDescending<MapGroup> { g -> g.members.any { it.busy } }.thenByDescending { it.members.size }.thenBy { it.key }

/**
 * The relays as points on the way. The window's inner ring ([mapRings]) is the hubs', the
 * rings outside it the peers'; the peers stand group after group round them — the direct ones
 * first, centred above this device, then each relay's, then the ones with no path — so every
 * group is one arc, and its hub stands a ring nearer this device in the middle of that arc. Nothing
 * crosses: a hub's links fan outwards inside its own arc.
 *
 * A window with room for one ring only — a phone on its side — has no inner ring to give: a
 * hub there stands on the ring itself, in the middle of its peers ([flatRoutes]).
 *
 * Like the rings, a place depends on who is on the map and how each is reached, never on a
 * rate; and a window too small for everybody keeps the busy and the linked ([KEEP_ORDER]).
 */
internal fun routesScene(peers: List<MapPeer>, width: Float, height: Float, m: MapMetrics = MapMetrics(), moreRoom: Float = 0f): MapScene {
    val groups = groupPeers(peers, TreeGrouping.ROUTE)
    val relays = groups.count { it.kind.relay }
    val fits = mapRings(width, height, m).let { rings ->
        if (rings.size == 1) peers.size + relays <= rings.single().capacity
        else peers.size <= rings.drop(1).sumOf { it.capacity } && relays <= rings.first().capacity
    }
    val metrics = if (fits || moreRoom <= 0f) m else m.copy(marginBottom = m.marginBottom + moreRoom)
    val rings = mapRings(width, height, metrics)
    val centerX = width / 2
    val centerY = metrics.marginTop + (height - metrics.marginTop - metrics.marginBottom).coerceAtLeast(2f) / 2
    if (rings.size == 1) return flatRoutes(peers, groups, rings.single(), width, height, centerX, centerY)
    val hubRing = rings.first()
    val peerRings = rings.drop(1)

    val dropped = groups.filter { it.kind.relay }.sortedWith(HUB_KEEP_ORDER).drop(hubRing.capacity)
        .flatMapTo(HashSet()) { g -> g.members.map { it.key } }
    val capacities = peerRings.map { it.capacity }
    val kept = peers.filter { it.key !in dropped }.sortedWith(KEEP_ORDER).take(capacities.sum()).mapTo(HashSet()) { it.key }
    val hidden = peers.filter { it.key !in kept }.sortedWith(PLACE_ORDER)
    val shown = groups.mapNotNull { g -> g.members.filter { it.key in kept }.takeIf { it.isNotEmpty() }?.let { g to it } }
    val n = kept.size
    if (n == 0) return MapScene(MapScheme.ROUTES, width, height, centerX, centerY, emptyList(), emptyList(), emptyList(), hidden)

    val used = chooseRings(capacities, n)
    val shares = spreadOverRings(capacities.slice(used), n)

    // How many of each group stand on each ring: in proportion, ring by ring, so that every
    // group's arc covers the same angle on all of them. The last ring takes what is left.
    val left = IntArray(shown.size) { shown[it].second.size }
    val counts = shares.mapIndexed { ri, share ->
        val take = IntArray(left.size)
        if (ri == shares.lastIndex) {
            left.copyInto(take)
        } else {
            val total = left.sum().coerceAtLeast(1)
            for (g in left.indices) take[g] = (share.toLong() * left[g] / total).toInt()
            var spare = share - take.sum()
            val byRemainder = left.indices.sortedByDescending { g -> (share.toLong() * left[g]) % total }
            for (g in byRemainder) if (spare > 0 && take[g] < left[g]) { take[g]++; spare-- }
        }
        for (g in left.indices) left[g] -= take[g]
        take
    }

    val scenePeers = ArrayList<ScenePeer>(n)
    val arcSum = FloatArray(shown.size)
    val placed = IntArray(shown.size)
    used.forEachIndexed { ri, ringIndex ->
        val ring = peerRings[ringIndex]
        val onRing = shares[ri]
        if (onRing == 0) return@forEachIndexed
        // The first group's arc is centred on the top.
        val lead = (counts[ri][0] - 1).coerceAtLeast(0) / 2f
        val fractions = FloatArray(onRing) { (it - lead) / onRing }
        val points = ellipsePointsAt(ring.rx, ring.ry, fractions)
        var slot = 0
        for (g in shown.indices) {
            val group = shown[g].first
            repeat(counts[ri][g]) {
                val (x, y) = points[slot]
                scenePeers += ScenePeer(shown[g].second[placed[g]++], centerX + x, centerY + y, if (group.kind.relay) group.key else MAP_SELF_KEY)
                arcSum[g] += fractions[slot]
                slot++
            }
        }
    }

    // A hub belongs in the middle of its peers' arc; two hubs of small groups would then stand
    // closer than their names allow, so neighbours are pushed apart to a slot's distance.
    val hubIndexes = shown.indices.filter { shown[it].first.kind.relay }
    val at = FloatArray(hubIndexes.size) { arcSum[hubIndexes[it]] / shown[hubIndexes[it]].second.size }
    // The hubs' ring is the one just inside their peers': on a tablet, where the peers may
    // stand three rings out, a hub beside this device would be a point on nobody's way.
    val between = rings[used.first]
    spreadOnRing(at, minOf(1f / hubIndexes.size.coerceAtLeast(1), metrics.slot / ellipsePerimeter(between.rx, between.ry)))
    val hubPoints = ellipsePointsAt(between.rx, between.ry, at)
    val hubs = hubIndexes.mapIndexed { i, g -> SceneHub(shown[g].first, centerX + hubPoints[i].first, centerY + hubPoints[i].second) }

    return MapScene(MapScheme.ROUTES, width, height, centerX, centerY, hubs, scenePeers, routeLinks(hubs, scenePeers), hidden)
}

/** This device to every hub with all that goes through it, a hub to each of its peers, this device to the rest. */
private fun routeLinks(hubs: List<SceneHub>, peers: List<ScenePeer>): List<SceneLink> {
    val links = ArrayList<SceneLink>(peers.size + hubs.size)
    for (hub in hubs) links += SceneLink(MAP_SELF_KEY, hub.group.key, hub.group.kind.link, hub.group.rate, linked = true)
    for (p in peers) {
        links += if (p.parent == MAP_SELF_KEY) selfLink(p.peer)
        else SceneLink(p.parent, p.peer.key, MapLinkKind.of(p.peer.path), p.peer.rate, linked = true)
    }
    return links
}

/**
 * The routes on one [ring]: the peers group after group round it, and each relay's hub on the
 * ring among them, in the middle of its own peers, taking a place like they do. Half the way
 * to this device — where a window with more room has its hubs — would be on top of this
 * device's name in a window this low.
 */
private fun flatRoutes(
    peers: List<MapPeer>,
    groups: List<MapGroup>,
    ring: MapRing,
    width: Float,
    height: Float,
    centerX: Float,
    centerY: Float,
): MapScene {
    val groupOf = HashMap<String, MapGroup>()
    for (g in groups) for (p in g.members) groupOf[p.key] = g
    // A relay's first peer costs two places, its hub's and its own; whoever does not fit is left out.
    val kept = HashSet<String>()
    val hubsIn = HashSet<String>()
    for (p in peers.sortedWith(KEEP_ORDER)) {
        val group = groupOf.getValue(p.key)
        val cost = if (group.kind.relay && group.key !in hubsIn) 2 else 1
        if (kept.size + hubsIn.size + cost > ring.capacity) continue
        kept += p.key
        if (group.kind.relay) hubsIn += group.key
    }
    val hidden = peers.filter { it.key !in kept }.sortedWith(PLACE_ORDER)
    val shown = groups.mapNotNull { g -> g.members.filter { it.key in kept }.takeIf { it.isNotEmpty() }?.let { g to it } }
    if (shown.isEmpty()) return MapScene(MapScheme.ROUTES, width, height, centerX, centerY, emptyList(), emptyList(), emptyList(), hidden)

    // Null is where a group's hub stands among its peers.
    val places = shown.flatMap { (g, members) ->
        if (!g.kind.relay) members.map { g to it } else members.map<MapPeer, Pair<MapGroup, MapPeer?>> { g to it }.toMutableList().apply { add(members.size / 2, g to null) }
    }
    val first = places.count { it.first === shown.first().first }
    val points = ellipsePointsAt(ring.rx, ring.ry, FloatArray(places.size) { (it - (first - 1) / 2f) / places.size })
    val hubs = ArrayList<SceneHub>()
    val scenePeers = ArrayList<ScenePeer>()
    places.forEachIndexed { i, (group, peer) ->
        val x = centerX + points[i].first
        val y = centerY + points[i].second
        if (peer == null) hubs += SceneHub(group, x, y)
        else scenePeers += ScenePeer(peer, x, y, if (group.kind.relay) group.key else MAP_SELF_KEY)
    }
    return MapScene(MapScheme.ROUTES, width, height, centerX, centerY, hubs, scenePeers, routeLinks(hubs, scenePeers), hidden)
}

/**
 * Moves [at] — places round a ring as fractions of it, in ascending order — apart until no
 * two neighbours are closer than [gap], each pair giving way equally. The caller makes sure
 * they can all fit.
 */
internal fun spreadOnRing(at: FloatArray, gap: Float) {
    val n = at.size
    if (n < 2) return
    repeat(400) {
        var moved = false
        for (i in 0 until n) {
            val j = (i + 1) % n
            // Two places have one gap each way round; with more, the last one's neighbour is the first.
            val between = if (j == 0) at[0] + 1f - at[i] else at[j] - at[i]
            if (between < gap - 1e-5f) {
                val push = (gap - between) / 2
                at[i] -= push
                at[j] += push
                moved = true
            }
        }
        if (!moved) return
    }
}

/** The measures of the tree. */
internal data class TreeMetrics(
    /** Between two devices along a row: a node's slot and a breath. */
    val column: Float = 88f,
    /** Between two rows: a node, its two lines of text, and room for the branch to show. */
    val row: Float = 86f,
    /** From a node's centre to the branch that runs down beside it. */
    val stem: Float = 46f,
    /** From the box's left edge to the trunk, and from the last column to its right edge. */
    val marginX: Float = 10f,
    val marginTop: Float = 34f,
    /** Under the last row: the lower half of a node, and the screen's "+N more" over it. */
    val marginBottom: Float = 108f,
    /** Between this device and the first group. */
    val rootGap: Float = 92f,
    /** Extra between two groups, so that a group's rows read as one. */
    val groupGap: Float = 10f,
    /** How many devices are drawn at all; a tailnet beyond it keeps the busy and the linked. */
    val maxPeers: Int = 120,
)

/**
 * The tree: this device top left, the trunk down the left edge, every group a row hanging off
 * it — the group's node in the first column, its devices along the row and on to further rows
 * when the window is not wide enough. So it is never wider than the window, however large the
 * tailnet, and grows downwards, where a finger scrolls anyway. A tree narrower than the window
 * stands in its middle, and always starts at the top, like the list it nearly is.
 *
 * [byRoute] says the groups are paths, so a device's branch is drawn as its group's kind of
 * line; otherwise branches are plain, and only one carrying traffic takes its path's colour.
 */
internal fun treeScene(groups: List<MapGroup>, width: Float, height: Float, t: TreeMetrics = TreeMetrics(), byRoute: Boolean = true): MapScene {
    val all = groups.flatMap { it.members }
    val kept = all.sortedWith(KEEP_ORDER).take(t.maxPeers).mapTo(HashSet()) { it.key }
    val hidden = all.filter { it.key !in kept }.sortedWith(PLACE_ORDER)
    val shown = groups.mapNotNull { g -> g.members.filter { it.key in kept }.takeIf { it.isNotEmpty() }?.let { g to it } }

    // Column 0 is this device and the groups; the devices take the columns after it.
    val half = t.column / 2
    val room = ((width - 2 * t.marginX - t.stem - half) / t.column).toInt().coerceAtLeast(2)
    val columns = minOf(room, shown.maxOfOrNull { it.second.size } ?: 1)
    val rows = shown.sumOf { ceil(it.second.size / columns.toFloat()).toInt() }
    val contentWidth = t.marginX + t.stem + columns * t.column + half + t.marginX
    val contentHeight = t.marginTop + (if (shown.isEmpty()) 0f else t.rootGap + (rows - 1) * t.row + (shown.size - 1) * t.groupGap) + t.marginBottom
    val sceneWidth = max(width, contentWidth)
    val sceneHeight = max(height, contentHeight)
    val x0 = (sceneWidth - contentWidth) / 2 + t.marginX + t.stem
    val selfY = t.marginTop

    val hubs = ArrayList<SceneHub>(shown.size)
    val peers = ArrayList<ScenePeer>(kept.size)
    val links = ArrayList<SceneLink>(kept.size + shown.size)
    var y = selfY + t.rootGap
    for ((group, members) in shown) {
        hubs += SceneHub(group, x0, y)
        links += SceneLink(MAP_SELF_KEY, group.key, group.kind.link, group.rate, group.linked, drop = 0f, inset = t.stem)
        members.forEachIndexed { i, p ->
            val column = 1 + i % columns
            peers += ScenePeer(p, x0 + column * t.column, y + (i / columns) * t.row, group.key)
            val kind = if (byRoute || p.rate.totalBps > 0) MapLinkKind.of(p.path) else MapLinkKind.GROUP
            // Every branch of a group leaves it by the one stem, beside the first column of devices.
            links += SceneLink(group.key, p.key, kind, p.rate, p.tier == MapTier.LINKED, drop = 0f, inset = (column - 1) * t.column + t.stem)
        }
        y += ceil(members.size / columns.toFloat()).toInt() * t.row + t.groupGap
    }
    return MapScene(MapScheme.TREE, sceneWidth, sceneHeight, x0, selfY, hubs, peers, links, hidden)
}

/** The corners of [link] between ([fx], [fy]) and ([tx], [ty]), ends included: two points for a straight one. */
internal fun linkPoints(link: SceneLink, fx: Float, fy: Float, tx: Float, ty: Float): FloatArray {
    if (!link.square) return floatArrayOf(fx, fy, tx, ty)
    val bus = fy + link.drop
    val stem = tx - link.inset
    return floatArrayOf(fx, fy, fx, bus, stem, bus, stem, ty, tx, ty)
}

/**
 * How the screen looks at a scene: [scale], and where the scene's top left corner is in the
 * window ([x], [y], in the window's dp). The plain view is the scene as it was planned.
 */
internal data class SceneView(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

/** As far in as a pinch goes. */
internal const val MAP_MAX_SCALE = 2.5f

/** The scale at which all of [scene] is inside a [width] × [height] window; never above 1. */
internal fun fitScale(scene: MapScene, width: Float, height: Float): Float =
    minOf(1f, width / scene.width, height / scene.height)

/**
 * [view] kept sane: its scale between the fit and the maximum, and the scene not dragged off —
 * along a side it is longer than the window it covers the window, along one it is shorter it
 * stands in the middle.
 */
internal fun clampView(view: SceneView, scene: MapScene, width: Float, height: Float): SceneView {
    val scale = view.scale.coerceIn(fitScale(scene, width, height), MAP_MAX_SCALE)
    fun along(at: Float, length: Float, window: Float): Float {
        val shown = length * scale
        return if (shown <= window) (window - shown) / 2 else at.coerceIn(window - shown, 0f)
    }
    return SceneView(scale, along(view.x, scene.width, width), along(view.y, scene.height, height))
}

/** The view a scene opens in: full size, from its top left corner, where the tree's root is. */
internal fun startView(scene: MapScene, width: Float, height: Float): SceneView = clampView(SceneView(), scene, width, height)

/** The view with the whole scene in the window. */
internal fun fitView(scene: MapScene, width: Float, height: Float): SceneView =
    clampView(SceneView(fitScale(scene, width, height)), scene, width, height)

/** [view] after a pinch by [zoom] around ([cx], [cy]) and a drag by ([panX], [panY]): the point under the fingers stays under them. */
internal fun zoomView(view: SceneView, zoom: Float, cx: Float, cy: Float, panX: Float, panY: Float, scene: MapScene, width: Float, height: Float): SceneView {
    val scale = (view.scale * zoom).coerceIn(fitScale(scene, width, height), MAP_MAX_SCALE)
    val ratio = scale / view.scale
    return clampView(SceneView(scale, cx - (cx - view.x) * ratio + panX, cy - (cy - view.y) * ratio + panY), scene, width, height)
}

/**
 * [view] moved as little as it takes to have the scene's point ([x], [y]) inside the window
 * with [margin] around it: what brings the node a remote has moved onto into sight.
 */
internal fun revealView(view: SceneView, x: Float, y: Float, scene: MapScene, width: Float, height: Float, margin: Float = 72f): SceneView {
    fun along(at: Float, point: Float, window: Float): Float {
        val onScreen = at + point * view.scale
        val pad = minOf(margin, window / 2)
        return when {
            onScreen < pad -> at + (pad - onScreen)
            onScreen > window - pad -> at - (onScreen - (window - pad))
            else -> at
        }
    }
    return clampView(SceneView(view.scale, along(view.x, x, width), along(view.y, y, height)), scene, width, height)
}
