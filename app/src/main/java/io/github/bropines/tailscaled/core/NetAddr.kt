package io.github.bropines.tailscaled.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Helpers for the `host:port` listen addresses used by the SOCKS5 proxy, the
 * outbound HTTP proxy, the local DNS proxy and the Taildrive proxy.
 *
 * A listen address and a dial address are not the same thing: a proxy bound to
 * the wildcard address `0.0.0.0` so that other LAN devices can reach it must
 * still be dialed over loopback from inside the app.
 */
object NetAddr {

    const val WILDCARD_V4 = "0.0.0.0"
    const val LOOPBACK_V4 = "127.0.0.1"

    /** Host part of a `host:port` pair, or an empty string when malformed. */
    fun host(addr: String): String =
        addr.substringBeforeLast(":", "").trim().removeSurrounding("[", "]")

    /** Port part of a `host:port` pair, or null when malformed. */
    fun port(addr: String): Int? = addr.substringAfterLast(":", "").trim().toIntOrNull()

    /** True when the address is bound to every interface, i.e. reachable from the LAN. */
    fun isWildcard(addr: String): Boolean = when (host(addr)) {
        WILDCARD_V4, "::", "" -> true
        else -> false
    }

    /**
     * Rewrites a listen address into one that can be dialed from this device.
     * Wildcard binds collapse to loopback; everything else is returned as-is.
     */
    fun dialable(addr: String): String {
        val p = port(addr) ?: return addr
        return when (host(addr)) {
            WILDCARD_V4, "" -> "$LOOPBACK_V4:$p"
            "::" -> "[::1]:$p"
            else -> addr
        }
    }

    /** Host of [dialable], suitable for APIs that take host and port separately. */
    fun dialableHost(addr: String): String = host(dialable(addr)).ifEmpty { LOOPBACK_V4 }

    /**
     * Rebinds an address to the wildcard or the loopback interface, keeping the
     * port. A non-loopback, non-wildcard host chosen by the user is preserved.
     */
    fun rebind(addr: String, lanAccess: Boolean): String {
        val p = port(addr) ?: return addr
        val h = host(addr)
        return when {
            lanAccess && (h == LOOPBACK_V4 || h.startsWith("127.") || h.isEmpty()) -> "$WILDCARD_V4:$p"
            !lanAccess && isWildcard(addr) -> "$LOOPBACK_V4:$p"
            else -> addr
        }
    }

    /**
     * First non-loopback IPv4 address of this device, used to tell the user
     * where a LAN-exposed proxy can actually be reached.
     */
    fun lanIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
