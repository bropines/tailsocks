package io.github.bropines.tailscaled.core

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** An exit node as the widget lists it and the cache keeps it: StableID, address, name. */
@Serializable
data class ExitNodeOption(
    val id: String = "",
    val ip: String = "",
    val name: String = ""
)

/**
 * The profile's last known exit nodes (`cached_exit_nodes_json` in `appctr_<id>`), so the
 * widget can list them, and an address be matched to its StableID, while no daemon answers.
 *
 * The cache is a copy of the last live list, never a union of every list seen: a node
 * removed from the tailnet, or that stopped offering itself, leaves with the next live read.
 */
object ExitNodeCache {
    private const val KEY = "cached_exit_nodes_json"

    fun read(prefs: SharedPreferences): List<ExitNodeOption> {
        val json = prefs.getString(KEY, "").orEmpty()
        if (json.isEmpty()) return emptyList()
        return runCatching { AppJson.decodeFromString<List<ExitNodeOption>>(json) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotEmpty() && it.ip.isNotEmpty() }
    }

    /** Stores [live] as the cache; a list equal to the stored one writes nothing. */
    fun replace(prefs: SharedPreferences, live: List<ExitNodeOption>) {
        if (read(prefs) == live) return
        prefs.edit { putString(KEY, AppJson.encodeToString(live)) }
    }

    /** The StableID the cache knows for [ip], or null. */
    fun idFor(prefs: SharedPreferences, ip: String): String? =
        read(prefs).firstOrNull { it.ip == ip }?.id
}

/**
 * The exit nodes in a daemon status: peers that offer themselves as one and have both a
 * StableID and an address. A node without its ID cannot be selected — the daemon routes by
 * the ID, and an empty one clears the exit node — so it is left out rather than shown.
 */
internal fun liveExitNodes(status: StatusResponse): List<ExitNodeOption> =
    status.peers?.values.orEmpty()
        .filter { it.exitNodeOption == true }
        .mapNotNull { peer ->
            val id = peer.id.orEmpty()
            val ip = peer.tailscaleIPs?.firstOrNull().orEmpty()
            if (id.isEmpty() || ip.isEmpty()) null else ExitNodeOption(id, ip, peer.getDisplayName())
        }
        .sortedBy { it.id }
