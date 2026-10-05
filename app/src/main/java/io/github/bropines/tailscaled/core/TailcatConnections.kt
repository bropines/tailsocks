package io.github.bropines.tailscaled.core

import android.content.Context
import appctr.Appctr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/** One tailcat server, the local ports forwarded to it, and its SOCKS5 proxy. */
@Serializable
data class TailcatConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val ports: String = "",
    /** A SOCKS5 proxy on 127.0.0.1 that dials through the server; 0 for none. */
    val socks: Int = 0,
    /**
     * The proxy's credentials, made with it: any app on the device can find an
     * open proxy on localhost, as with the main SOCKS5.
     */
    val socksUser: String = "",
    val socksPass: String = "",
    /**
     * The card's switch, remembered: an enabled connection comes back up when
     * the core starts (TailscaledService), and stops with it.
     */
    val enabled: Boolean = false,
) {
    /** What an app is given for the proxy, or null without one. */
    fun socksUrl(): String? = if (socks == 0) null else "socks5://$socksUser:$socksPass@127.0.0.1:$socks"

    /**
     * This connection for another device, in the form a server's card shares:
     * a computer runs it, a connection's editor imports it. The proxy goes
     * without its credentials, which belong to this device.
     */
    fun command(): String = TailcatConnections.command(address, TailcatConnections.specs(ports), socks.takeIf { it > 0 })
}

/** What a pasted address or tailcat command says about a connection; see [TailcatConnections.parseImport]. */
data class TailcatImport(val address: String, val ports: String, val socks: Int?)

/**
 * The configured tailcat connections, one JSON list in the global preferences.
 * Several run at once, one per server; they share the client key.
 *
 * Kept out of the settings export: a server's address is what lets a client
 * in when the server allows anyone.
 */
object TailcatConnections {
    private const val KEY = "tailcat_connections"

    fun load(context: Context): List<TailcatConnection> {
        val json = GlobalSettings.getString(context, KEY, "")
        if (json.isEmpty()) return emptyList()
        return runCatching { AppJson.decodeFromString<List<TailcatConnection>>(json) }.getOrDefault(emptyList())
    }

    fun save(context: Context, connections: List<TailcatConnection>) =
        GlobalSettings.setString(context, KEY, AppJson.encodeToString(connections))

    fun get(context: Context, id: String): TailcatConnection? = load(context).firstOrNull { it.id == id }

    /** Adds [conn], or replaces the one with its id. */
    fun put(context: Context, conn: TailcatConnection) {
        val list = load(context)
        save(context, if (list.any { it.id == conn.id }) list.map { if (it.id == conn.id) conn else it } else list + conn)
    }

    fun delete(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })

    /**
     * Reads a tc… address out of pasted text and, when the text is a
     * `tailcat forward` or `tailcat socks` command — what a server's card
     * copies, one per line — the port mappings and the proxy's port. Null
     * without a usable address.
     */
    fun parseImport(text: String): TailcatImport? {
        var address: String? = null
        val ports = mutableListOf<String>()
        var socks: Int? = null
        for (line in text.lines()) {
            val tokens = line.trim().split(Regex("\\s+")).map { it.trim('"', '\'') }.filter { it.isNotEmpty() }
            val here = tokens.firstOrNull { it.startsWith("tc") && runCatching { Appctr.tailcatCheckAddress(it) }.getOrDefault("?").isEmpty() }
                ?: continue
            if (address == null) address = here
            if (here != address) continue
            when {
                "forward" in tokens -> tokens.dropWhile { it != here }.drop(1).filter { MAPPING.matches(it) }.forEach { ports += it }
                "socks" in tokens -> {
                    val i = tokens.indexOfFirst { it == "--listen" || it.startsWith("--listen=") }
                    val listen = when {
                        i < 0 -> null
                        tokens[i].startsWith("--listen=") -> tokens[i].substringAfter('=')
                        else -> tokens.getOrNull(i + 1)
                    }
                    // No --listen, or port 0, is "any free port" to the CLI; a card needs one.
                    socks = listen?.substringAfterLast(':')?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 1080
                }
            }
        }
        return address?.let { TailcatImport(it, ports.distinct().joinToString(", "), socks) }
    }

    /**
     * What a computer runs to reach [address], one command a line:
     * `tailcat forward` with [mappings], `tailcat socks` listening on [socks].
     * [parseImport] reads the same lines back; with neither, the bare address.
     */
    fun command(address: String, mappings: List<String>, socks: Int?): String = buildList {
        if (mappings.isNotEmpty()) add("tailcat forward $address ${mappings.joinToString(" ")}")
        if (socks != null) add("tailcat socks --listen=127.0.0.1:$socks $address")
    }.joinToString("\n").ifEmpty { address }

    /** A port mapping as `tailcat forward` takes it: port, local:remote, local:ip:port. */
    private val MAPPING = Regex("""^\d+(:\d+)?$|^\d+:(\[[0-9a-fA-F:.]+]|[0-9.]+):\d+$""")

    fun setEnabled(context: Context, id: String, on: Boolean) =
        save(context, load(context).map { if (it.id == id) it.copy(enabled = on) else it })

    fun disableAll(context: Context) = save(context, load(context).map { it.copy(enabled = false) })

    /** The mapping specs in a ports field. */
    fun specs(ports: String): List<String> =
        ports.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    /** The local ports a ports field listens on; 0, a port the system picks, cannot clash and is left out. */
    fun localPorts(ports: String): Set<Int> = specs(ports).mapNotNull { spec ->
        val local = if (':' in spec) spec.substringBefore(':') else spec
        local.toIntOrNull()?.takeIf { it in 1..65535 }
    }.toSet()

    /** Every local port a connection listens on: its mappings' and its proxy's. */
    fun localPorts(conn: TailcatConnection): Set<Int> =
        localPorts(conn.ports) + listOfNotNull(conn.socks.takeIf { it in 1..65535 })

    /** The connections other than [conn] that listen on one of its local ports. */
    fun clashes(context: Context, conn: TailcatConnection): List<Pair<Int, TailcatConnection>> {
        val wanted = localPorts(conn)
        return load(context).filter { it.id != conn.id }.flatMap { other ->
            localPorts(other).intersect(wanted).map { it to other }
        }
    }

    /**
     * Why a connection could not start, or null; [running] limits the clash
     * check to the connections that hold their ports now. The address and the
     * mappings are checked by the bridge, the same parser that starts them.
     */
    fun problem(context: Context, conn: TailcatConnection, running: Set<String>? = null): Problem? {
        val addr = runCatching { Appctr.tailcatCheckAddress(conn.address.trim()) }.getOrDefault("?")
        if (conn.address.isBlank() || addr.isNotEmpty()) return Problem.Address(addr)
        // A proxy alone is a connection too; with neither, the bridge says "no port mappings".
        if (conn.ports.isNotBlank() || conn.socks == 0) {
            val mappings = runCatching { Appctr.tailcatCheckMappings(conn.ports) }.getOrDefault("?")
            if (mappings.isNotEmpty()) return Problem.Ports(mappings)
        }
        if (conn.socks !in 0..65535) return Problem.Socks
        if (conn.socks != 0 && conn.socks in localPorts(conn.ports)) return Problem.Clash(conn.socks, conn.name)
        val clash = clashes(context, conn).firstOrNull { (_, other) -> running == null || other.id in running }
        if (clash != null) return Problem.Clash(clash.first, clash.second.name)
        return null
    }

    sealed interface Problem {
        data class Address(val detail: String) : Problem
        data class Ports(val detail: String) : Problem
        data object Socks : Problem
        data class Clash(val port: Int, val name: String) : Problem
    }
}

/** This device as a tailcat server (appctr/tailcat_server.go): what it serves, and to whom. */
@Serializable
data class TailcatServerConfig(
    /** The card's switch, remembered, as [TailcatConnection.enabled]. */
    val enabled: Boolean = false,
    /** Ports on this device that clients reach, by commas. */
    val ports: String = "",
    /** Clients may go out through this device's network to any address. */
    val exitNode: Boolean = false,
    /** Anyone holding the address may connect, not only [allowed]. */
    val allowAll: Boolean = false,
    /** Client public keys ("nodekey:…"), one per line: the server's --allow. */
    val allowed: String = "",
) {
    fun allowedKeys(): List<String> = allowed.split(',', ' ', '\n', '\t', '\r').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * The server's settings, a JSON object in the global preferences, and its
 * identity: a key file in private storage whose relay region is fixed when
 * it is made, so the address survives restarts. Neither is in the settings
 * export, as with the connections.
 */
object TailcatServer {
    private const val KEY = "tailcat_server"

    fun load(context: Context): TailcatServerConfig {
        val json = GlobalSettings.getString(context, KEY, "")
        if (json.isEmpty()) return TailcatServerConfig()
        return runCatching { AppJson.decodeFromString<TailcatServerConfig>(json) }.getOrDefault(TailcatServerConfig())
    }

    fun save(context: Context, config: TailcatServerConfig) =
        GlobalSettings.setString(context, KEY, AppJson.encodeToString(config))

    fun keyFile(context: Context): File = File(File(context.filesDir, "tailcat").apply { mkdirs() }, "server.json")

    /** The server's tc… address, or null before it has an identity. */
    fun address(context: Context): String? =
        runCatching { Appctr.tailcatServerAddress(keyFile(context).absolutePath) }.getOrDefault("").ifEmpty { null }

    /**
     * Makes an identity, replacing any, and returns its address. Picks the
     * nearest relay, so it needs the network; blocking, call off the main thread.
     */
    fun create(context: Context): String = Appctr.tailcatServerCreateKey(keyFile(context).absolutePath)

    /**
     * What a computer runs to use this server, one command a line:
     * `tailcat forward` for its ports, `tailcat socks` for its exit node. The
     * import in a connection's editor reads the same lines back.
     */
    fun clientCommand(address: String, config: TailcatServerConfig): String =
        TailcatConnections.command(address, TailcatConnections.specs(config.ports), if (config.exitNode) 1080 else null)

    /** Why the server could not start with [config], or null. Checked by the bridge's parsers. */
    fun problem(config: TailcatServerConfig): Problem? {
        val ports = runCatching { Appctr.tailcatCheckServerPorts(config.ports) }.getOrDefault("?")
        if (ports.isNotEmpty()) return Problem.Ports(ports)
        val keys = runCatching { Appctr.tailcatCheckClientKeys(config.allowed) }.getOrDefault("?")
        if (keys.isNotEmpty()) return Problem.Keys(keys)
        if (config.ports.isBlank() && !config.exitNode) return Problem.Nothing
        return null
    }

    sealed interface Problem {
        data class Ports(val detail: String) : Problem
        data class Keys(val detail: String) : Problem
        data object Nothing : Problem
    }
}

/**
 * This device's tailcat identity. A server started with
 * `tailcat serve --allow=<public key>` lets in only the clients it lists;
 * without a key each run is a throwaway identity anyone may use.
 *
 * The private half is a file in the app's private storage, not a preference,
 * so a settings export never carries it.
 */
object TailcatKey {
    private fun file(context: Context) = File(File(context.filesDir, "tailcat").apply { mkdirs() }, "client.key")

    fun private(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    /** "nodekey:…", or null without a key. */
    fun public(context: Context): String? =
        private(context)?.let { runCatching { Appctr.tailcatPublicKey(it) }.getOrDefault("").ifEmpty { null } }

    /** Creates a key, replacing any; returns its public half. */
    fun create(context: Context): String? {
        val priv = Appctr.tailcatGenerateClientKey()
        val f = file(context)
        f.writeText(priv)
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
        return public(context)
    }
}
