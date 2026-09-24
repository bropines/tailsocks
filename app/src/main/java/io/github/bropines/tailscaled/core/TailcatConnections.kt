package io.github.bropines.tailscaled.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID

/** One tailcat server and the ports forwarded to it: one `tailcat forward` process. */
@Serializable
data class TailcatConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val ports: String = "",
)

/**
 * The configured connections, as one JSON list in the global preferences.
 * Each runs as its own process, so several servers can be reached at once;
 * they share the client key, which a server lists in `serve --allow`.
 */
object TailcatConnections {
    private const val KEY = "tailcat_connections"

    // The single connection the first version kept, folded into the list once.
    private const val LEGACY_ADDRESS = "tailcat_address"
    private const val LEGACY_PORTS = "tailcat_ports"

    private val IPV6_LITERAL = Regex("""^[0-9A-Fa-f:.]+$""")

    fun load(context: Context): List<TailcatConnection> {
        val json = GlobalSettings.getString(context, KEY, "")
        if (json.isNotEmpty()) {
            return runCatching { AppJson.decodeFromString<List<TailcatConnection>>(json) }.getOrDefault(emptyList())
        }
        val legacyAddress = GlobalSettings.getString(context, LEGACY_ADDRESS, "")
        if (legacyAddress.isEmpty()) return emptyList()
        val migrated = listOf(
            TailcatConnection(
                name = "tailcat",
                address = legacyAddress,
                ports = GlobalSettings.getString(context, LEGACY_PORTS, "")
            )
        )
        save(context, migrated)
        GlobalSettings.remove(context, LEGACY_ADDRESS)
        GlobalSettings.remove(context, LEGACY_PORTS)
        return migrated
    }

    fun save(context: Context, connections: List<TailcatConnection>) {
        GlobalSettings.setString(context, KEY, AppJson.encodeToString(connections))
    }

    fun get(context: Context, id: String): TailcatConnection? = load(context).firstOrNull { it.id == id }

    fun isValidAddress(address: String) = address.isNotBlank() && address.trim().none { it.isWhitespace() }

    /** Splits a ports field into mappings; null when any of them is malformed. */
    fun parsePorts(text: String): List<String>? {
        val specs = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return specs.takeIf { it.isNotEmpty() && it.all(::isForwardSpec) }
    }

    /**
     * The local ports a valid ports field listens on. A local port 0 asks the
     * OS for a free one, so it cannot clash and is left out.
     */
    fun localPorts(text: String): Set<Int> =
        parsePorts(text).orEmpty().map { it.substringBefore(':').toInt() }.filter { it != 0 }.toSet()

    /**
     * The connections, other than [except], that already listen on one of
     * [ports]' local ports — only one process can hold 127.0.0.1:<port>.
     */
    fun clashes(context: Context, ports: String, except: String?): List<Pair<Int, TailcatConnection>> {
        val wanted = localPorts(ports)
        return load(context).filter { it.id != except }.flatMap { other ->
            localPorts(other.ports).intersect(wanted).map { it to other }
        }
    }

    /** "127.0.0.1:2283, 127.0.0.1:8080", what the connection listens on. */
    fun summary(ports: String): String = parsePorts(ports).orEmpty().joinToString(", ") {
        val local = it.substringBefore(':')
        "127.0.0.1:" + if (local == "0") "auto" else local
    }

    /**
     * The mappings `tailcat forward` accepts (parseForwardSpec in its
     * cmd/tailcat/forward.go), checked here so a typo never reaches Start:
     * `8080`; `18080:8080`, with local port 0 for one the OS picks; and
     * `3001:192.168.1.5:3001` or `5555:[fd7a::1]:5555` through an exit-node
     * server. The remote host must be an IP literal — tailcat resolves no
     * names here and exits on one.
     */
    private fun isForwardSpec(spec: String): Boolean {
        val colon = spec.indexOf(':')
        if (colon < 0) return isPort(spec)
        val local = spec.substring(0, colon)
        val target = spec.substring(colon + 1)
        if (local != "0" && !isPort(local)) return false
        if (isPort(target)) return true
        val portSep = target.lastIndexOf(':')
        if (portSep <= 0 || !isPort(target.substring(portSep + 1))) return false
        val host = target.substring(0, portSep)
        return if (host.startsWith('[') && host.endsWith(']')) {
            host.length > 2 && ':' in host && IPV6_LITERAL.matches(host.substring(1, host.length - 1))
        } else {
            isIpv4(host)
        }
    }

    private fun isPort(s: String) = s.isNotEmpty() && s.all(Char::isDigit) && s.length <= 5 && s.toInt() in 1..65535

    // Four decimal octets without leading zeros, as Go's netip requires.
    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                (it == "0" || !it.startsWith('0')) && it.toInt() <= 255
        }
    }
}
