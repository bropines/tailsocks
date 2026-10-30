package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import appctr.Appctr
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.ui.WidgetUpdates
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Selecting the active profile's exit node — the one way the main screen, Settings, the
 * widgets, remote management, Tasker and the AppFunctions all do it.
 *
 * Both halves are stored: the address the app shows and compares, and the StableID the
 * daemon routes by (the settings sync sends only the ID). The choice then goes live —
 * straight to an attached daemon, or through the service when only it can reach the daemon
 * (Root Mode after the app was killed). A TUN device carries the default route only behind
 * an exit node and Android fixes its routes at establish(), so the VPN is told to look again;
 * it refuses on its own while the connection is stopped.
 *
 * Blocking (JNI, LocalAPI): call it off the main thread.
 */
object ExitNodeApply {
    private const val TAG = "ExitNodeApply"

    sealed interface Result {
        /** Stored, and sent to the daemon when one is up. */
        data object Applied : Result
        /** An address without a StableID, or the other way round: nothing was changed. */
        data object Incomplete : Result
        /** The daemon refused it with [message]. Stored all the same, unless pushed first. */
        data class Refused(val message: String) : Result
    }

    /**
     * Whether ([id], [ip]) can be selected: both empty (no exit node) or both known. An
     * address alone would be stored as routed while the empty ID sent with it clears the
     * exit node in the daemon.
     */
    fun isSelectable(id: String, ip: String): Boolean = id.isEmpty() == ip.isEmpty()

    /**
     * Makes ([id], [ip]) the exit node; two empty strings clear it. With [pushFirst] the
     * daemon is asked before anything is stored, so a refusal leaves the old choice in place.
     */
    fun select(context: Context, id: String, ip: String, pushFirst: Boolean = false): Result {
        if (!isSelectable(id, ip)) return Result.Incomplete
        val app = context.applicationContext
        val prefs = profilePrefs(app)

        var refusal: String? = null
        if (pushFirst) {
            refusal = pushToDaemon(app, id)
            if (refusal != null) return Result.Refused(refusal)
        }
        prefs.edit {
            val currentIp = prefs.getString("exit_node_ip", "").orEmpty()
            val currentId = prefs.getString("exit_node_id", "").orEmpty()
            putString("exit_node_ip", ip)
            putString("exit_node_id", id)
            // The last node in use, which the widget lists while it knows of no other.
            when {
                ip.isNotEmpty() -> { putString("last_exit_node_ip", ip); putString("last_exit_node_id", id) }
                currentIp.isNotEmpty() && currentId.isNotEmpty() -> {
                    putString("last_exit_node_ip", currentIp); putString("last_exit_node_id", currentId)
                }
            }
        }
        WidgetUpdates.request(app)
        if (!pushFirst) refusal = pushToDaemon(app, id)

        if (GlobalSettings.isTunModeEnabled(app)) {
            try {
                app.startService(Intent(app, TunVpnService::class.java).apply { action = TunVpnService.ACTION_START })
            } catch (e: Exception) {
                // A background start of a stopped service is refused; then there is no tunnel to rebuild.
                Log.w(TAG, "The tunnel was not told about the exit node: ${e.message}")
            }
        }
        return refusal?.let { Result.Refused(it) } ?: Result.Applied
    }

    /**
     * The StableID of the exit node at [ip]: from the daemon when this process is attached to
     * one, otherwise from the profile's cached exit nodes; "" when neither knows it.
     */
    fun resolveId(context: Context, ip: String): String {
        if (ip.isEmpty()) return ""
        if (Appctr.isRunning()) {
            runCatching {
                val status = AppJson.decodeFromString<StatusResponse>(Appctr.getStatusFromAPI())
                status.peers?.values?.firstOrNull { it.tailscaleIPs?.contains(ip) == true }?.id
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ExitNodeCache.idFor(profilePrefs(context.applicationContext), ip).orEmpty()
    }

    /** Sends the choice to the daemon; the refusal's text, or null when it took it or none is up. */
    private fun pushToDaemon(context: Context, id: String): String? {
        if (Appctr.isRunning()) {
            val patch = buildJsonObject { put("ExitNodeID", id); put("ExitNodeIDSet", true) }.toString()
            val res = try { Appctr.setPrefs(patch) } catch (e: Exception) { e.message ?: e.toString() }
            return if (res == "OK") null else res
        }
        if (ProxyState.isActualRunning(context)) TailscaledService.requestApplySettings(context)
        return null
    }

    private fun profilePrefs(context: Context) =
        context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)
}
