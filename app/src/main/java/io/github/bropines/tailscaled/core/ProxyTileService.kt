package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.MainActivity

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile.
 *
 * Control centres differ a lot between OEM skins (stock, MIUI/HyperOS, One UI),
 * so the tile keeps to the contract every one of them honours: state and label
 * are refreshed on every [onStartListening], the icon is set explicitly instead
 * of relying on the manifest default, and the tile keeps updating while the
 * panel is open by listening for the service's status broadcast.
 */
class ProxyTileService : TileService() {

    private companion object {
        const val TAG = "ProxyTileService"
    }

    /** Set on click so a slow start/stop does not flip the tile back visually. */
    private var pendingState: Boolean? = null
    private var statusReceiver: BroadcastReceiver? = null

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        registerStatusReceiver()
        updateTile()
    }

    override fun onStopListening() {
        unregisterStatusReceiver()
        pendingState = null
        super.onStopListening()
    }

    override fun onDestroy() {
        unregisterStatusReceiver()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        // On a locked device with a secure keyguard the panel cannot start our
        // foreground service; ask the system to unlock first.
        if (isLocked && isSecure) {
            unlockAndRun { toggle() }
            return
        }
        toggle()
    }

    private fun toggle() {
        val isRunning = ProxyState.isActualRunning(this)
        val target = !isRunning

        pendingState = target
        renderTile(target, transitioning = true)

        val intent = Intent(this, TailscaledService::class.java).apply {
            action = if (isRunning) "STOP_ACTION" else "START_ACTION"
        }

        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Android 12+ can refuse a background foreground-service start.
            // Falling back to the app keeps the tile functional instead of
            // silently doing nothing.
            Log.w(TAG, "Foreground service start refused from tile: ${e.message}")
            pendingState = null
            openApp()
        }
    }

    /** Opens the dashboard and collapses the panel, across API level differences. */
    private fun openApp() {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this, 0, activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(activityIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app from tile: ${e.message}")
        }
    }

    private fun registerStatusReceiver() {
        if (statusReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pendingState = null
                updateTile()
            }
        }
        val filter = IntentFilter().apply {
            addAction(TailscaledService.ACTION_STATUS_CHANGED)
            addAction("START")
            addAction("STOP")
        }
        try {
            ContextCompat.registerReceiver(
                this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            statusReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register status receiver: ${e.message}")
        }
    }

    private fun unregisterStatusReceiver() {
        statusReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { /* already gone */ }
        }
        statusReceiver = null
    }

    private fun updateTile() {
        val running = ProxyState.isActualRunning(this)
        val pending = pendingState

        // Drop the optimistic state once reality has caught up with it.
        if (pending != null && pending == running) {
            pendingState = null
        }
        renderTile(pendingState ?: running, transitioning = pendingState != null)
    }

    private fun renderTile(active: Boolean, transitioning: Boolean) {
        val tile = qsTile ?: return

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)

        // Some skins (MIUI/HyperOS) do not pick up the manifest icon reliably
        // after a theme change, so it is applied on every render.
        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set tile icon: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = buildSubtitle(active, transitioning)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = buildSubtitle(active, transitioning)
        }

        tile.updateTile()
    }

    private fun buildSubtitle(active: Boolean, transitioning: Boolean): String {
        if (transitioning) {
            return getString(if (active) R.string.tile_state_connecting else R.string.tile_state_disconnecting)
        }
        return try {
            val activeAccount = AccountManager.getActiveAccount(this)
            val prefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
            if (active && exitNodeIp.isNotEmpty()) {
                "${activeAccount.name} ($exitNodeIp)"
            } else {
                activeAccount.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build tile subtitle: ${e.message}")
            getString(R.string.app_name)
        }
    }
}
