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
) {
    /** What an app is given for the proxy, or null without one. */
    fun socksUrl(): String? = if (socks == 0) null else "socks5://$socksUser:$socksPass@127.0.0.1:$socks"
}

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
