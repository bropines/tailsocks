package io.github.bropines.tailscaled.ui

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.PeerVersionSource
import io.github.bropines.tailscaled.admin.api.ApiDerpMap
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * How each peer is reached right now, in one reading shared by the Peers list, the peer's
 * details and the large window's device rows — so the three cannot tell the same peer's
 * path three different ways.
 *
 * The daemon's status (ipnstate.PeerStatus, filled by magicsock's populatePeerStatus) says it
 * in three fields: CurAddr while UDP goes straight to the peer, PeerRelay ("ip:port:vni:N")
 * while another node's peer relay carries it, and Relay — the peer's home DERP region —
 * always, whether anything goes through it or not. `tailscale status` reads them in that
 * order and so does this. What the status does not say is fetched beside it: which city a
 * DERP region code is (the daemon's DERP map), and which node a peer relay's address
 * belongs to (PeerPathNames).
 */

/** How traffic reaches a peer, as the daemon last chose it. */
@Immutable
sealed interface PeerPath {
    /** UDP straight to the peer, at [endpoint] ("ip:port"). */
    data class Direct(val endpoint: String) : PeerPath {
        /** The endpoint is a private address: both devices are on one network. */
        val local: Boolean get() = isLocalAddress(endpointHost(endpoint))
    }

    /** Through another node's peer relay at [address] ("ip:port"); [node] is that node's name
     *  when it can be told (see [relayNodeFor]). */
    data class PeerRelay(val address: String, val node: String?) : PeerPath

    /** Through Tailscale's DERP relay in [region] ("fra"); [city] when the DERP map names it. */
    data class Derp(val region: String, val city: String?) : PeerPath

    /** Online, but nothing has been sent to it yet, so no path has been chosen. [home] is its
     *  home DERP region, which is all the daemon reports for it until then. */
    data class NotYet(val home: String?, val homeCity: String?) : PeerPath

    /** The control plane has it offline. [lastSeenMillis] is null when it never said when. */
    data class Offline(val lastSeenMillis: Long?) : PeerPath

    /** This device: no path to itself, only the DERP region it is reachable through. */
    data class Self(val home: String?, val homeCity: String?) : PeerPath
}

/** "203.0.113.9:7777:vni:5" → "203.0.113.9:7777": the address the daemon's PeerRelay field
 *  carries, without the virtual network id that tells sessions on one relay apart. A value
 *  without the suffix is taken as it is. */
internal fun peerRelayAddress(raw: String): String = raw.trim().replace(VNI_SUFFIX, "")

private val VNI_SUFFIX = Regex(""":vni:\d+$""")

/** The host of "ip:port" or "[v6]:port"; a bare address comes back as it is. */
internal fun endpointHost(endpoint: String): String {
    val s = endpoint.trim()
    if (s.startsWith("[")) return s.substringAfter("[").substringBefore("]")
    // One colon is IPv4 with a port; more than one is an IPv6 address without one.
    return if (s.count { it == ':' } == 1) s.substringBefore(":") else s
}

/**
 * A private address: RFC 1918, link-local, loopback, IPv6 unique-local or link-local. Not
 * 100.64.0.0/10 — a direct endpoint there is a carrier's NAT as often as anybody's LAN.
 */
internal fun isLocalAddress(host: String): Boolean {
    val h = host.lowercase()
    if (':' in h) {
        return h.startsWith("fc") || h.startsWith("fd") ||
            h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
    }
    val p = h.split('.').map { it.toIntOrNull() ?: return false }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) ||
        (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
}

/** A node a peer relay's address may belong to: its name, its tailnet addresses, and the
 *  hosts of the endpoints it last reported (null when nobody said). */
internal class RelayHost(val name: String, val tailnetIps: Set<String>, val endpointHosts: Set<String>?)

/**
 * Which node a peer relay at [relayHost] (the address without its port) is, or null when that
 * cannot be told honestly. The status names the relay only by the address it answers on, and
 * that address is on the internet or the LAN, not in the tailnet. Two witnesses:
 *
 *  - The endpoints a device last reported to the Admin API (when one is set up): a relay
 *    answers on the same public or LAN address as the device's own WireGuard port, on another
 *    port. One device there is the answer; several behind one NAT are told apart by the next.
 *  - The peer relays this device may use ([servers], their tailnet addresses, from the
 *    daemon's peer-relay-servers list). A tailnet with one relay — the usual case — has it
 *    there. Its own reported endpoints, when known, must not contradict it; the relay may also
 *    be one only the far side may use, and then nothing here names it.
 */
internal fun relayNodeFor(relayHost: String, hosts: List<RelayHost>, servers: Set<String>): String? {
    val isServer = { h: RelayHost -> h.tailnetIps.any { it in servers } }
    val byEndpoint = hosts.filter { it.endpointHosts?.contains(relayHost) == true }
    if (byEndpoint.size == 1) return byEndpoint.single().name
    if (byEndpoint.size > 1) return byEndpoint.filter(isServer).singleOrNull()?.name
    return hosts.filter { isServer(it) && it.endpointHosts == null }.singleOrNull()?.name
}

/**
 * What the status leaves out of a path, fetched beside it: the DERP regions' cities by code,
 * the peer relays this device may use (by tailnet address), and the endpoints each device last
 * reported to the Admin API, by node id — empty when no admin profile serves this tailnet.
 */
@Immutable
data class PeerPathFacts(
    val derpCities: Map<String, String> = emptyMap(),
    val relayServers: Set<String> = emptySet(),
    val endpoints: Map<String, List<String>> = emptyMap(),
)

/** Facts a preview hands the screens instead of asking the daemon. Null in the app. */
val LocalDemoPeerPathFacts = staticCompositionLocalOf<PeerPathFacts?> { null }

/**
 * The paths of one list of peers, named: [peers] are the nodes a peer relay may turn out to
 * be (this device never is one — the app's daemon is built without the relay server).
 */
@Immutable
internal class PeerPathNames(peers: Collection<PeerData>, private val facts: PeerPathFacts) {
    private val hosts = peers.map { p ->
        RelayHost(
            name = p.getDisplayName(),
            tailnetIps = p.tailscaleIPs.orEmpty().toSet(),
            endpointHosts = p.id?.let { facts.endpoints[it] }?.map(::endpointHost)?.toSet()
        )
    }

    fun city(region: String?): String? = region?.let { facts.derpCities[it] }

    fun relayNode(address: String): String? = relayNodeFor(endpointHost(address), hosts, facts.relayServers)

    /** [path] with what this list knows filled in: a DERP region's city, a relay's node. */
    fun named(path: PeerPath): PeerPath = when (path) {
        is PeerPath.PeerRelay -> if (path.node != null) path else path.copy(node = relayNode(path.address))
        is PeerPath.Derp -> if (path.city != null) path else path.copy(city = city(path.region))
        else -> path
    }

    /**
     * [peer]'s path. A direct endpoint wins over a relay, as in `tailscale status`: the daemon
     * keeps Relay filled with the home region even while traffic goes direct. A peer nothing
     * has been sent to (LastWrite is Go's zero time) has no path yet — its Relay is only where
     * it would be reached — unless [ping] went somewhere: a disco ping chooses no path of its
     * own, but it says which way the answer came.
     */
    fun pathOf(peer: PeerData, isSelf: Boolean, ping: PeerPingState = PeerPingState.Idle): PeerPath {
        val relay = peer.relay?.takeIf { it.isNotBlank() }
        val status = when {
            isSelf -> PeerPath.Self(relay, city(relay))
            peer.online != true -> PeerPath.Offline(parseRfc3339Millis(peer.lastSeen))
            !peer.curAddr.isNullOrBlank() -> PeerPath.Direct(peer.curAddr)
            !peer.peerRelay.isNullOrBlank() -> named(PeerPath.PeerRelay(peerRelayAddress(peer.peerRelay), null))
            relay == null -> PeerPath.NotYet(null, null)
            // Absent is not "never": a daemon that did not say gets the benefit of the doubt.
            peer.lastWrite?.startsWith("0001-01-01") == true -> PeerPath.NotYet(relay, city(relay))
            else -> PeerPath.Derp(relay, city(relay))
        }
        if (status is PeerPath.NotYet) (ping as? PeerPingState.Measured)?.via?.let { return named(it) }
        return status
    }

    companion object {
        val NONE = PeerPathNames(emptyList(), PeerPathFacts())
    }
}

/** The names the Peers screen reads its paths with; its rows and its details sheet share them. */
internal val LocalPeerPathNames = compositionLocalOf { PeerPathNames.NONE }

/**
 * [peers]' paths with names, fetched off the main thread whenever what they need changes —
 * the DERP regions in use, the peer relay addresses — and not on every refresh of a list that
 * reads the same. Until the answer lands the paths read without names: a region code, a
 * relay's address. A preview gets [LocalDemoPeerPathFacts] and asks nothing.
 */
@Composable
internal fun rememberPeerPathNames(peers: List<PeerData>?): PeerPathNames {
    val context = LocalContext.current
    val demoFacts = LocalDemoPeerPathFacts.current
    val live = LocalDemo.current == null && !LocalInspectionMode.current
    var facts by remember { mutableStateOf(demoFacts ?: PeerPathFacts()) }
    if (live) {
        val needs = remember(peers) {
            peers.orEmpty().mapNotNull { it.relay?.takeIf(String::isNotBlank) }.toSortedSet() to
                peers.orEmpty().mapNotNull { it.peerRelay?.takeIf(String::isNotBlank)?.let(::peerRelayAddress) }.toSortedSet()
        }
        val current by rememberUpdatedState(peers)
        LaunchedEffect(needs) {
            if (needs.first.isEmpty() && needs.second.isEmpty()) return@LaunchedEffect
            facts = withContext(Dispatchers.IO) { PeerPathSource.load(context, current.orEmpty()) }
        }
    }
    return remember(peers, facts) { PeerPathNames(peers.orEmpty(), facts) }
}

/**
 * Where [PeerPathFacts] come from. The DERP map is the daemon's own (/localapi/v0/derpmap) and
 * changes about never, so it is kept for ten minutes. The peer relays this device may use come
 * from the daemon's debug action "peer-relay-servers" — a list of tailnet addresses — and the
 * devices' endpoints from the Admin API's device list, the one PeerVersionSource already keeps;
 * both only while a peer relay is actually carrying something.
 */
internal object PeerPathSource {
    private const val TAG = "PeerPathSource"
    private const val DERP_TTL_MS = 10 * 60_000L

    @Volatile private var derp: Pair<Long, Map<String, String>>? = null

    /** Blocking; call it off the main thread. Nothing it fails to learn is an error. */
    fun load(context: Context, peers: Collection<PeerData>): PeerPathFacts {
        val relayed = peers.any { !it.peerRelay.isNullOrBlank() }
        return PeerPathFacts(
            derpCities = derpCities(),
            relayServers = if (relayed) relayServers() else emptySet(),
            endpoints = if (relayed) PeerVersionSource.endpointsFor(context, peers) else emptyMap(),
        )
    }

    private fun derpCities(): Map<String, String> {
        val now = System.currentTimeMillis()
        derp?.let { (at, cities) -> if (now - at < DERP_TTL_MS && cities.isNotEmpty()) return cities }
        val cities = try {
            AppJson.decodeFromString<ApiDerpMap>(Appctr.getDERPMapJSON()).regions.values
                .filter { it.code.isNotBlank() && it.name.isNotBlank() }
                .associate { it.code to it.name }
        } catch (e: Exception) {
            Log.w(TAG, "DERP map: ${e.javaClass.simpleName}")
            return derp?.second.orEmpty()
        }
        derp = now to cities
        return cities
    }

    private fun relayServers(): Set<String> = try {
        val raw = Appctr.doLocalAPIRequest("POST", "/localapi/v0/debug?action=peer-relay-servers", "")
        if (raw.startsWith("Error")) emptySet() else AppJson.decodeFromString<List<String>>(raw).toSet()
    } catch (e: Exception) {
        Log.w(TAG, "Peer relay servers: ${e.javaClass.simpleName}")
        emptySet()
    }
}

/**
 * Every word a path is told in, resolved in the parent composition — the details sheet's
 * window does not follow the app's language (see wrapContextWithLocale).
 */
@Immutable
data class PeerPathWords(
    /** Row words: the kind of path, before what it goes through. */
    val direct: String,
    val peerRelay: String,
    val relay: String,
    /** "via": before a relay node's name where the row has no room for "peer relay" too. */
    val via: String,
    /** As a screen reader states a row: "via relay %1$s", "via peer relay (%1$s)". */
    val viaRelayFormat: String,
    val viaPeerRelay: String,
    val viaPeerRelayFormat: String,
    val lastSeenFormat: String,
    /** The details' status chips. */
    val chipDirect: String,
    val chipRelayFormat: String,
    val chipPeerRelay: String,
    val chipPeerRelayFormat: String,
    val chipHomeFormat: String,
    /** The route card's captions. */
    val routeDirectLocal: String,
    val routeDirectInternet: String,
    val routeDerp: String,
    val routeDerpFormat: String,
    val routePeerRelay: String,
    val routePeerRelayFormat: String,
    val routeHome: String,
    val routeHomeFormat: String,
    /** This device has no home relay: only direct connections reach it. */
    val noHome: String,
    val routeNotYet: String,
    val routeNotYetFormat: String,
    val routeOffline: String,
    /** "Frankfurt (fra)". */
    val cityCodeFormat: String,
    /** DERP against a peer relay, once, folded. */
    val help: String,
)

@Composable
internal fun peerPathWords(): PeerPathWords = PeerPathWords(
    direct = stringResource(R.string.peer_path_direct),
    peerRelay = stringResource(R.string.peer_path_peer_relay),
    relay = stringResource(R.string.peer_path_relay),
    via = stringResource(R.string.peer_path_via),
    viaRelayFormat = stringResource(R.string.peer_state_relay_format),
    viaPeerRelay = stringResource(R.string.peer_state_peer_relay),
    viaPeerRelayFormat = stringResource(R.string.peer_state_peer_relay_format),
    lastSeenFormat = stringResource(R.string.peer_state_last_seen_format),
    chipDirect = stringResource(R.string.peer_status_direct),
    chipRelayFormat = stringResource(R.string.peer_status_relay_format),
    chipPeerRelay = stringResource(R.string.peer_via_peer_relay),
    chipPeerRelayFormat = stringResource(R.string.peer_status_peer_relay_format),
    chipHomeFormat = stringResource(R.string.peer_status_home_relay_format),
    routeDirectLocal = stringResource(R.string.peer_route_direct_local),
    routeDirectInternet = stringResource(R.string.peer_route_direct_internet),
    routeDerp = stringResource(R.string.peer_route_relay),
    routeDerpFormat = stringResource(R.string.peer_route_relay_format),
    routePeerRelay = stringResource(R.string.peer_via_peer_relay),
    routePeerRelayFormat = stringResource(R.string.peer_route_peer_relay_format),
    routeHome = stringResource(R.string.peer_route_home),
    routeHomeFormat = stringResource(R.string.peer_route_home_format),
    noHome = stringResource(R.string.netcheck_no_home_derp),
    routeNotYet = stringResource(R.string.peer_route_not_yet),
    routeNotYetFormat = stringResource(R.string.peer_route_not_yet_format),
    routeOffline = stringResource(R.string.peer_status_offline),
    cityCodeFormat = stringResource(R.string.peer_path_city_code),
    help = stringResource(R.string.peer_path_help),
)

/** A region as people say it: the city with its code, or the code alone. */
internal fun PeerPathWords.region(code: String, city: String?): String =
    if (city != null) cityCodeFormat.format(city, code) else code

/**
 * A path on one line after an address: the icon, the kind in words, then what it goes
 * through — the endpoint, the relay node, the city. [word] is empty where the icon has to say
 * the kind alone; [detail] is null where there is nothing to add; [detailIsAddress] asks for
 * the address face.
 */
internal class PathLine(val icon: ImageVector, val word: String, val detail: String?, val detailIsAddress: Boolean)

/**
 * [path] as a row tells it, from the longest line to the shortest: a row shows the first that
 * fits ([FirstThatFits]). The long ones add what the path goes through — the endpoint, the
 * relay node, the city; a peer relay's node outlasts the words "peer relay", since which node
 * carries the traffic is the thing worth the width (the row's spoken state still says both);
 * a DERP relay's short line has the region code where the long one has the city; the last
 * resort is the icon alone, which beats a word cut to "di…". [ago] is how long ago an offline
 * peer was seen, already in words. Empty for a path with nothing to say on a row: this device,
 * a peer nothing has talked to yet.
 */
internal fun PeerPathWords.lines(path: PeerPath, ago: String?): List<PathLine> = when (path) {
    is PeerPath.Direct -> listOf(
        PathLine(Icons.Default.Lan, direct, path.endpoint, true),
        PathLine(Icons.Default.Lan, direct, null, false),
        PathLine(Icons.Default.Lan, "", null, false),
    )
    is PeerPath.PeerRelay -> listOfNotNull(
        PathLine(Icons.Default.DeviceHub, peerRelay, path.node ?: path.address, detailIsAddress = path.node == null),
        path.node?.let { PathLine(Icons.Default.DeviceHub, via, it, false) },
        // The node alone, after the icon only peer relays carry: «через» is longer than "via".
        path.node?.let { PathLine(Icons.Default.DeviceHub, "", it, false) },
        PathLine(Icons.Default.DeviceHub, peerRelay, null, false),
        PathLine(Icons.Default.DeviceHub, "", null, false),
    )
    is PeerPath.Derp -> listOfNotNull(
        path.city?.let { PathLine(Icons.Default.Router, relay, it, false) },
        PathLine(Icons.Default.Router, relay, path.region, false),
        PathLine(Icons.Default.Router, "", path.region, false),
        PathLine(Icons.Default.Router, "", null, false),
    )
    is PeerPath.Offline -> listOfNotNull(ago?.let { PathLine(Icons.Default.Schedule, it, null, false) })
    is PeerPath.NotYet, is PeerPath.Self -> emptyList()
}

/** [path] as a screen reader states it after "online": "direct", "via relay Frankfurt". */
internal fun PeerPathWords.spoken(path: PeerPath, ago: String?): String? = when (path) {
    is PeerPath.Direct -> direct
    is PeerPath.PeerRelay -> path.node?.let { viaPeerRelayFormat.format(it) } ?: viaPeerRelay
    is PeerPath.Derp -> viaRelayFormat.format(path.city ?: path.region)
    is PeerPath.Offline -> ago?.let { lastSeenFormat.format(it) }
    is PeerPath.NotYet, is PeerPath.Self -> null
}

/** The details' status chip for [path], or null for a path with no chip. */
internal fun PeerPathWords.chip(path: PeerPath): Pair<String, ImageVector>? = when (path) {
    is PeerPath.Direct -> chipDirect to Icons.Default.Lan
    is PeerPath.PeerRelay -> (path.node?.let { chipPeerRelayFormat.format(it) } ?: chipPeerRelay) to Icons.Default.DeviceHub
    is PeerPath.Derp -> chipRelayFormat.format(path.city ?: path.region) to Icons.Default.Router
    is PeerPath.Self -> path.home?.let { chipHomeFormat.format(path.homeCity ?: it) to Icons.Default.Router }
    is PeerPath.NotYet, is PeerPath.Offline -> null
}

/**
 * Lays out the first of its children that fits the width whole, or the last one cut short when
 * none does: a row offers its long line and its short one, and the screen picks. Decided in
 * one pass from the children's intrinsic widths, so the first frame — all a preview renders —
 * is already the right one. Only the chosen child is placed.
 */
@Composable
internal fun FirstThatFits(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val pick = measurables.firstOrNull { it.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth }
            ?: measurables.last()
        val placeable = pick.measure(constraints.copy(minWidth = 0))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}
