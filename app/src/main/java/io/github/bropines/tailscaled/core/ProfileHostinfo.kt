package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * What a profile's node tells the coordination server about this device.
 *
 * A property of the profile, not of the app: the coordination server binds a
 * node to the OS it registered with and refuses the node if that changes
 * («node OS changed since last connection, was node state copied between
 * devices?» — measured 2026-09-07). So a profile registered as Linux stays
 * Linux, one registered as Android stays Android, and the way to switch is a
 * new profile. Off (the default) keeps the masquerade daemon patch 06 has
 * applied since 2026-05-07: OS `linux`, App `tailscale-cli`, DeviceModel
 * `Tailsocks`. On, the daemon reports OS `android` with the real model,
 * Android version and install source, the way the official client does; the
 * coordinator then names the machine after the model.
 */
object ProfileHostinfo {
    const val KEY = "honest_hostinfo"

    private fun prefs(context: Context, accountId: String) =
        context.getSharedPreferences("appctr_$accountId", Context.MODE_PRIVATE)

    fun isHonest(context: Context, accountId: String): Boolean =
        prefs(context, accountId).getBoolean(KEY, false)

    fun setHonest(context: Context, accountId: String, honest: Boolean) =
        prefs(context, accountId).edit().putBoolean(KEY, honest).apply()

    /**
     * Whether this profile's node is already known to the coordination server,
     * i.e. whether flipping [KEY] would be refused. Two witnesses, either one
     * suffices: the dashboard's `was_logged_in` mark, and a node ID inside the
     * daemon's state file. The state file is a JSON object whose values are
     * base64; control assigns `NodeID` only once it has registered the node.
     * In Root Mode the file is root-owned while the daemon runs and cannot be
     * read from here, which is what the first witness is for.
     */
    fun isRegistered(context: Context, accountId: String): Boolean {
        if (prefs(context, accountId).getBoolean("was_logged_in", false)) return true
        val state = File(context.filesDir, "states/$accountId/tailscaled.state")
        if (!state.isFile) return false
        return try {
            val root = AppJson.parseToJsonElement(state.readText()).jsonObject
            root.values.any { v ->
                val encoded = (v as? JsonPrimitive)?.content ?: return@any false
                val decoded = runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
                    ?: return@any false
                NODE_ID.containsMatchIn(decoded)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The development builds of 2026-09-07 (before 4.1.1) kept this key in the global
     * preferences for a few hours; the one device that ran them carries it to
     * its active profile here, once, so the node it registered as Android keeps
     * saying Android.
     */
    fun migrateGlobalKey(context: Context) {
        val global = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)
        if (!global.contains(KEY)) return
        val honest = global.getBoolean(KEY, false)
        global.edit().remove(KEY).apply()
        if (honest) setHonest(context, AccountManager.getActiveAccount(context).id, true)
    }

    private val NODE_ID = Regex("\"NodeID\":\"[^\"]+\"")
}
