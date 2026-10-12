package io.github.bropines.tailscaled.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.InetAddress

/**
 * Which program carries TUN mode's packets. Kept apart from [TunVpnService] so
 * the choice can be tested without the Android classes behind the service.
 */
object TunEngines {
    /** tailscaled owns the VpnService device (--tun=android-vpn, patches 17–19). */
    const val NATIVE = "native"
    /** hev-socks5-tunnel pushes every packet into the daemon's SOCKS5 port. */
    const val HEV = "hev"

    /** What TUN runs on for anyone who has not picked an engine. */
    const val DEFAULT = NATIVE

    /**
     * The engine for a stored `tun_engine` value.
     *
     * The key is written in exactly one place, the engine picker on the TUN
     * settings page (and by a settings import, which carries only keys that
     * were stored on the exporting device). So an absent key means "never
     * chose" and follows [DEFAULT]: new installs and everyone who stayed on the
     * old default move to the native engine, while a stored "hev" — someone who
     * switched to native and back, or picked hev outright — stays hev.
     * Anything unrecognised is treated as unset.
     */
    fun resolve(stored: String?): String = when (stored) {
        HEV -> HEV
        NATIVE -> NATIVE
        else -> DEFAULT
    }
}

/**
 * The accepted subnet routes a VpnService device has to carry.
 *
 * Without an exit node the device routes only the tailnet's own ranges
 * (100.64.0.0/10, fd7a:115c:a1e0::/48), so a subnet a peer advertises would
 * leave through the real network. The daemon knows which prefixes it wants in
 * the OS route table (`nodeBackend.osRoutes`), but on the native engine its
 * router is a no-op and on hev it is not involved at all, so the app rebuilds
 * the set from LocalAPI's status the way the route manager does: every peer's
 * AllowedIPs except its own addresses and the exit routes, which the device
 * handles on its own.
 */
object TunRoutes {

    /** A parsed prefix: network bytes with the host bits cleared, and its length. */
    data class Cidr(val bytes: ByteArray, val bits: Int) {
        val isV6: Boolean get() = bytes.size == 16

        /** Whether [other] lies inside this prefix. */
        fun contains(other: Cidr): Boolean =
            other.bytes.size == bytes.size && other.bits >= bits && masked(other.bytes, bits).contentEquals(bytes)

        /** Whether the two prefixes share any address. */
        fun overlaps(other: Cidr): Boolean = contains(other) || other.contains(this)

        /** The address part, in the form VpnService.Builder.addRoute parses. */
        fun address(): String = InetAddress.getByAddress(bytes).hostAddress ?: ""

        override fun toString(): String = "${address()}/$bits"
        override fun equals(other: Any?): Boolean = other is Cidr && other.bits == bits && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + bits
    }

    private val TAILNET_V4 = parse("100.64.0.0/10")!!
    private val TAILNET_V6 = parse("fd7a:115c:a1e0::/48")!!

    /**
     * Parses "a.b.c.d/n" or an IPv6 literal with a prefix length; a bare address
     * is a single host. Host bits are cleared, because Builder.addRoute refuses
     * a route that has any. Null for anything that is not a numeric prefix — a
     * name is never looked up.
     */
    fun parse(text: String): Cidr? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val addrText = raw.substringBefore('/')
        val bytes = parseAddress(addrText) ?: return null
        val max = bytes.size * 8
        val bits = if ('/' in raw) raw.substringAfter('/').toIntOrNull() ?: return null else max
        if (bits !in 0..max) return null
        return Cidr(masked(bytes, bits), bits)
    }

    private fun parseAddress(text: String): ByteArray? {
        if (text.isEmpty()) return null
        if (':' !in text) {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }
        // Only a literal reaches InetAddress, so it never resolves a name.
        if (!text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        val addr = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null
        val b = addr.address
        // An IPv4-mapped literal comes back as 4 bytes; routes keep their family.
        return if (b.size == 16) b else null
    }

    private fun masked(bytes: ByteArray, bits: Int): ByteArray {
        val out = bytes.copyOf()
        for (i in out.indices) {
            val keep = (bits - i * 8).coerceIn(0, 8)
            out[i] = (out[i].toInt() and (0xFF shl (8 - keep)) and 0xFF).toByte()
        }
        return out
    }

    private fun isExitRoute(c: Cidr) = c.bits == 0

    /** Already on the device whatever else it carries: the tailnet's own ranges. */
    private fun coveredByTailnetRanges(c: Cidr) = TAILNET_V4.contains(c) || TAILNET_V6.contains(c)

    @Serializable
    internal data class Status(
        @SerialName("BackendState") val backendState: String? = null,
        @SerialName("Peer") val peers: Map<String, Peer>? = null,
    )

    @Serializable
    internal data class Peer(
        @SerialName("TailscaleIPs") val tailscaleIPs: List<String>? = null,
        @SerialName("AllowedIPs") val allowedIPs: List<String>? = null,
        @SerialName("Expired") val expired: Boolean = false,
    )

    /**
     * Every subnet route the tailnet offers, from a LocalAPI status answer, or
     * null when the answer cannot tell (an error, a daemon that is not Running
     * yet) — so a caller never mistakes "unknown" for "no routes" and tears
     * routes down. Sorted and de-duplicated, IPv4 first, so two equal sets
     * compare equal.
     *
     * Mirrors the route manager's split (net/routemanager peerViewOf + eligible):
     * an AllowedIP that is a single tailnet address or one of the peer's own
     * addresses is the peer itself; 0.0.0.0/0 and ::/0 belong to exit nodes,
     * which the device's default route serves; an expired peer contributes
     * nothing. Prefixes inside the tailnet ranges are left out as well — the
     * device routes those ranges whole.
     */
    fun offeredRoutes(statusJson: String): List<Cidr>? {
        val status = runCatching { AppJson.decodeFromString(Status.serializer(), statusJson) }.getOrNull() ?: return null
        if (status.backendState != "Running") return null
        val out = LinkedHashSet<Cidr>()
        for (peer in status.peers.orEmpty().values) {
            if (peer.expired) continue
            val own = peer.tailscaleIPs.orEmpty().mapNotNull { parse(it) }.toSet()
            for (text in peer.allowedIPs.orEmpty()) {
                val c = parse(text) ?: continue
                if (isExitRoute(c)) continue
                if (c in own) continue
                // Single tailnet addresses (the peer, a VIP service it hosts) and
                // anything else inside the tailnet ranges.
                if (coveredByTailnetRanges(c)) continue
                out += c
            }
        }
        return sorted(out)
    }

    /**
     * The subnet routes a device built now must carry.
     *
     * Without an exit node, all of [offered] — nothing else sends them into the
     * tunnel. Behind an exit node the default route already does, except where
     * an "Excluded IP ranges" entry carves a hole ([appliedExclusions], only the
     * ones Android actually applied): an accepted subnet inside such a hole
     * still belongs to the tailnet, and its more specific route wins over the
     * exclusion. Listing only those keeps a route change that the default route
     * covers from rebuilding the device, which on the native engine relaunches
     * the daemon.
     */
    fun forDevice(offered: List<Cidr>, fullTunnel: Boolean, appliedExclusions: List<Cidr>): List<Cidr> =
        if (!fullTunnel) offered
        else offered.filter { route -> appliedExclusions.any { it.overlaps(route) } }

    private fun sorted(routes: Collection<Cidr>): List<Cidr> = routes.sortedWith(
        compareBy<Cidr>({ it.bytes.size })
            .thenComparator { a, b -> compareBytes(a.bytes, b.bytes) }
            .thenBy { it.bits }
    )

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    @Serializable
    internal data class Prefs(
        @SerialName("RouteAll") val routeAll: Boolean? = null,
        @SerialName("CorpDNS") val corpDns: Boolean? = null,
    )

    /**
     * RouteAll ("accept routes") and CorpDNS ("use Tailscale DNS") as the
     * running daemon has them, from LocalAPI's /prefs — they include whatever
     * the extra arguments changed — or null when the answer lacks either.
     */
    fun daemonPrefs(prefsJson: String): Pair<Boolean, Boolean>? {
        val p = runCatching { AppJson.decodeFromString(Prefs.serializer(), prefsJson) }.getOrNull() ?: return null
        return (p.routeAll ?: return null) to (p.corpDns ?: return null)
    }

    /** One comparable line for a route set: what a rebuild check and the cache store. */
    fun encode(routes: List<Cidr>): String = routes.joinToString(",")

    /** The inverse of [encode]; unparsable entries are dropped. */
    fun decode(text: String?): List<Cidr> =
        text.orEmpty().split(',').mapNotNull { if (it.isBlank()) null else parse(it) }
}
