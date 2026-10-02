package io.github.bropines.tailscaled.core

import android.content.Context
import appctr.Appctr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/** One tailcat server and the local ports forwarded to it. */
@Serializable
data class TailcatConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val ports: String = "",
)

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

    /** The connections other than [except] that listen on one of [ports]' local ports. */
    fun clashes(context: Context, ports: String, except: String?): List<Pair<Int, TailcatConnection>> {
        val wanted = localPorts(ports)
        return load(context).filter { it.id != except }.flatMap { other ->
            localPorts(other.ports).intersect(wanted).map { it to other }
        }
    }

    /**
     * Why a connection could not start, or null. The address and the mappings
     * are checked by the bridge, the same parser that starts them.
     */
    fun problem(context: Context, address: String, ports: String, except: String?, running: Set<String>): Problem? {
        val addr = runCatching { Appctr.tailcatCheckAddress(address.trim()) }.getOrDefault("?")
        if (address.isBlank() || addr.isNotEmpty()) return Problem.Address(addr)
        val mappings = runCatching { Appctr.tailcatCheckMappings(ports) }.getOrDefault("?")
        if (mappings.isNotEmpty()) return Problem.Ports(mappings)
        val clash = clashes(context, ports, except).firstOrNull { (_, other) -> other.id in running }
        if (clash != null) return Problem.Clash(clash.first, clash.second.name)
        return null
    }

    sealed interface Problem {
        data class Address(val detail: String) : Problem
        data class Ports(val detail: String) : Problem
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
