package io.github.bropines.tailscaled.core

import java.net.Inet4Address
import java.net.NetworkInterface

/*
 * The Taildrive proxy's address as a person, or another device, is given it. A phone has the
 * clipboard for that; a television has none, so the address is read from the screen and scanned
 * from a code, and what the code may hold has to be decided here, apart from the drawing:
 *
 *  - the login never goes into the address that is shown, and goes into the code only when the
 *    user has asked to see it;
 *  - a proxy that listens on loopback can be reached by this device alone, so a code of its
 *    address would lead a phone to its own loopback — there is no code then;
 *  - a proxy that listens on every interface has no address of its own: the one another device
 *    uses is this device's address on the local network.
 */

/** What the address card shows. [toString] names no password. */
class DriveAddress internal constructor(
    /** `http://host:port`, never with a login: what is read off the screen. */
    val url: String,
    private val urlWithLogin: String?,
    /** The proxy's login, when it asks for one. */
    val login: DriveProxyAuth?,
    /** Only this device can reach it: the proxy listens on loopback, or on every interface with no network address to name. */
    val deviceOnly: Boolean,
) {
    /** What a code for another device holds, or null when there is nothing for another device to open. */
    fun qrText(withLogin: Boolean): String? = when {
        deviceOnly -> null
        withLogin && urlWithLogin != null -> urlWithLogin
        else -> url
    }

    override fun toString(): String = "DriveAddress($url, login=${login != null}, deviceOnly=$deviceOnly)"
}

object DriveAddresses {
    /**
     * The address of the proxy bound to [bind] and [port] as the stored strings have them.
     * [lan] is this device's address on the local network, for a proxy that listens on every
     * interface; null when it has none or is not known. The login counts as the proxy counts it:
     * the switch is on and both fields are filled.
     */
    fun of(bind: String, port: String, authEnabled: Boolean, user: String, password: String, lan: String? = null): DriveAddress {
        val stored = DriveProxy.of(enabled = true, bind = bind, port = port, authEnabled = authEnabled, user = user, password = password)
        val named = bind.trim().removePrefix("[").removeSuffix("]")
        val host: String
        val deviceOnly: Boolean
        if (isWildcard(named)) {
            host = lan ?: stored.host
            deviceOnly = lan == null
        } else {
            host = named
            deviceOnly = isLoopback(named)
        }
        val proxy = DriveProxy(enabled = true, host = host, port = stored.port, auth = stored.auth)
        return DriveAddress(
            url = proxy.baseUrl,
            urlWithLogin = stored.auth?.let { proxy.urlFor(DriveLocation.ROOT, withCredentials = true).removeSuffix("/") },
            login = stored.auth,
            deviceOnly = deviceOnly,
        )
    }

    /** An address that means every interface, which no other device can connect to. */
    fun isWildcard(host: String): Boolean = host.isEmpty() || host == "0.0.0.0" || host == "::"

    /** An address only this device answers on. */
    fun isLoopback(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host == "::1" || host == "0:0:0:0:0:0:0:1" || host.startsWith("127.")

    /**
     * The address another device on the local network would use, out of [candidates] (dotted IPv4
     * addresses, in the order the system gave them): a private address (192.168/16, then 10/8,
     * then 172.16/12). The tailnet's own 100.64/10, a link-local 169.254/16 and loopback are
     * addresses of other networks. Null when there is none.
     */
    fun pickLan(candidates: List<String>): String? {
        fun rank(address: String): Int {
            val octets = address.split('.').map { it.toIntOrNull() ?: return -1 }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return -1
            return when {
                octets[0] == 192 && octets[1] == 168 -> 0
                octets[0] == 10 -> 1
                octets[0] == 172 && octets[1] in 16..31 -> 2
                else -> -1
            }
        }
        return candidates.filter { rank(it) >= 0 }.minByOrNull { rank(it) }
    }

    /** Interfaces that carry a tunnel or a point-to-point link, whose addresses are not the local network's. */
    private val NOT_LAN = Regex("^(lo|tun|tap|wg|ppp|ipsec|tailscale|dummy|rmnet|ccmni|v4-|clat|p2p).*")

    /** Whether an interface called [name] can be the local network. */
    fun isLanInterface(name: String): Boolean = !NOT_LAN.matches(name.lowercase())

    /**
     * This device's address on the local network, or null: the private address of an interface
     * that is up and is not a tunnel. Cheap, and it reads no network state that needs a permission.
     */
    fun lanAddress(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && isLanInterface(it.name) }
            .flatMap { nif -> nif.inetAddresses.toList().mapNotNull { (it as? Inet4Address)?.hostAddress } }
        pickLan(candidates)
    }.getOrNull()
}
