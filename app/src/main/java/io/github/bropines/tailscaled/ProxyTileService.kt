package io.github.bropines.tailscaled

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class ProxyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        
        val isRunning = ProxyState.isActualRunning()
        val intent = Intent(this, TailscaledService::class.java).apply {
            action = if (isRunning) "STOP_ACTION" else "START_ACTION"
        }
        
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Optimistic update
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = ProxyState.isUserLetRunning(this)
        
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "TailSocks"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val activeAccount = AccountManager.getActiveAccount(this)
                val prefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
                val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
                
                if (isRunning && exitNodeIp.isNotEmpty()) {
                    tile.subtitle = "${activeAccount.name} ($exitNodeIp)"
                } else {
                    tile.subtitle = activeAccount.name
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        tile.updateTile()
    }
}