package io.github.bropines.tailscaled.core

import android.content.Context
import appctr.Appctr
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The daemon's saved copy of the network map (tailscaled 1.104+).
 *
 * tailscaled keeps the last network map under its state directory, in
 * `profile-data/<profile>/netmap-cache/` — `--statedir` is our per-account
 * `files/states/<id>`, in every mode, Root Mode and the boot script included —
 * and on the next start brings the tailnet up from it before the coordination
 * server has answered. Behind a blocked or slow control plane that is the
 * difference between peers at once and peers whenever control gets through.
 * Whether a node may keep one is the coordination server's call: the
 * `cache-network-maps` node attribute, which a tailnet policy can withhold with
 * `disable-cache-network-maps`. Without it nothing is written or read and an
 * existing copy is deleted (ipn/ipnlocal/diskcache.go, local.go).
 *
 * The switch is upstream's local override, `TS_USE_CACHED_NETMAP`: off, the
 * daemon does not read the copy at start and deletes it with the next network
 * map from control. Upstream's own Android app compiles the feature out until
 * it has "UI affordances for debugging it" — the switch, the server's verdict
 * and [clear] are ours. The daemon reads the variable at start, so a change
 * restarts it; Root Mode carries it in the env file, so the boot script starts
 * the daemon the same way.
 */
object NetmapCache {
    const val KEY = "netmap_cache"
    const val ENV = "TS_USE_CACHED_NETMAP"

    /** The cache's directory under a profile's data directory (ipn/ipnlocal/diskcache.go). */
    private const val DIR_NAME = "netmap-cache"
    private const val PROFILE_DATA = "profile-data"

    fun isEnabled(context: Context): Boolean = GlobalSettings.getBoolean(context, KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) = GlobalSettings.setBoolean(context, KEY, enabled)

    /** What the running daemon says about the cache (appctr GetNetmapCacheJSON). */
    @Serializable
    data class Report(
        /** False while there is no daemon or no network map: nothing to say yet. */
        val known: Boolean = false,
        /** The server grants `cache-network-maps` to this node. */
        val granted: Boolean = false,
        /** A network map but no map poll: started from the saved map, or control lost since. */
        val withoutControl: Boolean = false,
    )

    /** Blocking (a LocalAPI round trip); null when the daemon cannot be asked. */
    fun report(): Report? {
        val json = runCatching { Appctr.getNetmapCacheJSON() }.getOrNull()
        if (json.isNullOrEmpty()) return null
        return runCatching { AppJson.decodeFromString(Report.serializer(), json) }.getOrNull()
    }

    /**
     * Deletes the saved maps of every account. The running daemon clears its
     * own through the LocalAPI, which also drops its in-memory handle (in Root
     * Mode its files are root's, so that is the only way in); the accounts
     * without a daemon are plain files of the app's — a root daemon's state is
     * handed back to the app when it stops. Blocking. Returns the daemon's
     * error, or null.
     */
    fun clear(context: Context): String? {
        var error: String? = null
        val running = runCatching { Appctr.isRunning() }.getOrDefault(false)
        val activeId = AccountManager.getActiveAccount(context).id
        if (running) {
            error = runCatching { Appctr.clearNetmapCache() }.exceptionOrNull()?.let { it.message ?: it.toString() }
        }
        File(context.filesDir, "states").listFiles()?.forEach { account ->
            if (running && account.name == activeId) return@forEach
            File(account, PROFILE_DATA).listFiles()?.forEach { profile ->
                File(profile, DIR_NAME).takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
        return error
    }

    /**
     * Whether [relative], a path under `files/states`, lies inside a daemon's
     * netmap cache (`<account>/profile-data/<profile>/netmap-cache/…`). Backups
     * leave it out: it is a copy of what control sends, rebuilt on the first
     * connection, and it grows with the tailnet.
     */
    fun isCachePath(relative: String): Boolean {
        val parts = relative.replace('\\', '/').split('/')
        val i = parts.indexOf(DIR_NAME)
        return i >= 2 && i < parts.lastIndex && parts[i - 2] == PROFILE_DATA
    }
}
