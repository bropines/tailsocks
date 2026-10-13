package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * What a network's link MTU means for Tailscale's direct connections.
 *
 * The tunnel's MTU is 1280, so a full packet from inside it leaves wrapped by WireGuard:
 * the outer IP header, the UDP header, WireGuard's 16-byte data header and its 16-byte
 * authentication tag. Over IPv6 that is 1280 + 40 + 8 + 32 = 1360 bytes on the wire, over
 * IPv4 1280 + 20 + 8 + 32 = 1340. On a link whose MTU is lower the kernel fragments those
 * packets, and a network that drops fragments loses them without a word: small requests
 * work, every large answer over a direct path vanishes, while relayed traffic (DERP, TCP)
 * is unaffected. Peers do not probe for this themselves on Android (the daemon's path-MTU
 * discovery is compiled out), so the app looks: it reads the MTU, says so, and leaves the
 * setting to whoever owns the network.
 */
object LinkMtu {
    /** The tunnel's own MTU, set by the daemon. */
    const val TUNNEL_MTU = 1280

    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    /** WireGuard's data message: 16 bytes of header and a 16-byte Poly1305 tag. */
    private const val WIREGUARD_FRAMING = 16 + 16

    /** The smallest link MTU that carries a full packet over IPv4 whole: 1280 + 20 + 8 + 32 = 1340. */
    const val NEEDED_IPV4 = TUNNEL_MTU + IPV4_HEADER + UDP_HEADER + WIREGUARD_FRAMING

    /** The same over IPv6: 1280 + 40 + 8 + 32 = 1360. Also what is assumed when the family is not known. */
    const val NEEDED_IPV6 = TUNNEL_MTU + IPV6_HEADER + UDP_HEADER + WIREGUARD_FRAMING

    /**
     * Whether a link of [mtu] bytes carries a full packet whole. [ipv6Underlay] is whether
     * the direct connections may run over IPv6; null (not known) is treated as yes, the
     * stricter of the two. An MTU that is missing or not positive says nothing.
     */
    fun verdict(mtu: Int?, ipv6Underlay: Boolean? = null): MtuVerdict {
        if (mtu == null || mtu <= 0) return MtuVerdict.Unknown
        val needed = if (ipv6Underlay == false) NEEDED_IPV4 else NEEDED_IPV6
        return if (mtu >= needed) MtuVerdict.Fine else MtuVerdict.TooSmall(mtu, needed)
    }

    /** An interface the app or its daemon owns: the VPN device, Root Mode's kernel tunnel. Never the underlay. */
    private fun isOwnTunnel(iface: String) = iface.startsWith("tun") || iface.startsWith("tailscale")

    /**
     * The network the daemon's direct packets leave by, among [candidates]. A VPN is never
     * it — in TUN mode the active network is our own tunnel, and a foreign VPN's device is
     * not the physical link — nor is a network without internet or without a default
     * route. The active network wins, then one Android has validated, then the first.
     */
    fun pickUnderlay(candidates: List<LinkCandidate>): LinkCandidate? = candidates
        .filter { c ->
            !c.vpn && c.internet && (c.defaultV4 || c.defaultV6) &&
                c.iface.isNotBlank() && !isOwnTunnel(c.iface)
        }
        // sortedWith is stable: equals keep the order the platform listed them in.
        .sortedWith(compareByDescending<LinkCandidate> { it.active }.thenByDescending { it.validated })
        .firstOrNull()

    /**
     * The MTU of the network the daemon's packets leave by, with the verdict; null when
     * there is no such network or anything refused to be read. The interface's own MTU
     * wins, since that is what the kernel fragments by; what Android's link properties
     * carry (often nothing: 0) is the fallback.
     */
    fun read(source: LinkSource): LinkMtuReading? = try {
        pickUnderlay(source.candidates())?.let { c ->
            val mtu = runCatching { source.interfaceMtu(c.iface) }.getOrDefault(0)
                .takeIf { it > 0 } ?: c.linkPropertiesMtu.takeIf { it > 0 }
            LinkMtuReading(c.iface, mtu, verdict(mtu, c.defaultV6))
        }
    } catch (e: Exception) {
        // A network list the platform refuses to give is "unknown", never a crash on a screen
        // that only wanted to be helpful.
        null
    }

    /** [read] on this device. */
    fun read(context: Context): LinkMtuReading? = read(AndroidLinkSource(context))

    /** The reading as the diagnostics report's one line. */
    fun reportLine(reading: LinkMtuReading?): String = "Link MTU: " + when {
        reading == null -> "unknown"
        reading.mtu == null -> "${reading.iface} unknown"
        else -> "${reading.iface} ${reading.mtu} (" + when (val v = reading.verdict) {
            MtuVerdict.Fine -> "fine"
            is MtuVerdict.TooSmall -> "too small: direct connections need ${v.needed}"
            MtuVerdict.Unknown -> "unknown"
        } + ")"
    }
}

/** The answer to "can this link carry a full packet from the tunnel whole?". */
sealed interface MtuVerdict {
    data object Fine : MtuVerdict
    /** [mtu] is below the [needed] bytes. */
    data class TooSmall(val mtu: Int, val needed: Int) : MtuVerdict
    /** Nothing was readable, or what was read makes no sense. */
    data object Unknown : MtuVerdict
}

/** The interface the packets leave by, its MTU when it could be read, and what that means. */
data class LinkMtuReading(val iface: String, val mtu: Int?, val verdict: MtuVerdict)

/** One of the device's networks, reduced to what choosing the underlay needs. */
data class LinkCandidate(
    val iface: String,
    val vpn: Boolean,
    val internet: Boolean,
    val defaultV4: Boolean,
    val defaultV6: Boolean,
    val validated: Boolean,
    val active: Boolean,
    /** What Android's link properties say (API 29+); 0 when unset, which is common. */
    val linkPropertiesMtu: Int = 0,
)

/** Where [LinkMtu.read] gets its facts; the device's in [AndroidLinkSource]. May throw. */
interface LinkSource {
    fun candidates(): List<LinkCandidate>
    /** The kernel's MTU of [iface]; 0 when it has none to give. */
    fun interfaceMtu(iface: String): Int
}

/** The platform's answers: ConnectivityManager for the networks, the interface itself for the MTU. */
class AndroidLinkSource(context: Context) : LinkSource {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun candidates(): List<LinkCandidate> {
        val cm = cm ?: return emptyList()
        val active = cm.activeNetwork
        // allNetworks is deprecated from 31 for lack of a better one-shot list; it still
        // answers, and the active network alone is a poor stand-in when that is a VPN.
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return (listOfNotNull(active) + all).distinct().mapNotNull { network ->
            val props = cm.getLinkProperties(network) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(network)
            val defaults = props.routes.filter { it.isDefaultRoute }
            LinkCandidate(
                iface = props.interfaceName.orEmpty(),
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                defaultV4 = defaults.any { it.destination.address is Inet4Address },
                defaultV6 = defaults.any { it.destination.address is Inet6Address },
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                active = network == active,
                linkPropertiesMtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) props.mtu else 0,
            )
        }
    }

    override fun interfaceMtu(iface: String): Int = NetworkInterface.getByName(iface)?.mtu ?: 0
}
