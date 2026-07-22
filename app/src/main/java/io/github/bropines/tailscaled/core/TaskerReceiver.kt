package io.github.bropines.tailscaled.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class TaskerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TaskerReceiver"

        const val ACTION_CONNECT = "io.github.bropines.tailscaled.action.CONNECT"
        const val ACTION_DISCONNECT = "io.github.bropines.tailscaled.action.DISCONNECT"
        const val ACTION_TOGGLE = "io.github.bropines.tailscaled.action.TOGGLE"
        const val ACTION_RESTART = "io.github.bropines.tailscaled.action.RESTART"

        // Short aliases
        const val ALIAS_START = "io.github.bropines.tailscaled.START"
        const val ALIAS_STOP = "io.github.bropines.tailscaled.STOP"
        const val ALIAS_TOGGLE = "io.github.bropines.tailscaled.TOGGLE"
        const val ALIAS_RESTART = "io.github.bropines.tailscaled.RESTART"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast action: $action")

        val serviceIntent = Intent(context, TailscaledService::class.java)

        when (action) {
            ACTION_CONNECT, ALIAS_START -> {
                serviceIntent.action = "START_ACTION"
                startServiceSafely(context, serviceIntent)
            }
            ACTION_DISCONNECT, ALIAS_STOP -> {
                serviceIntent.action = "STOP_ACTION"
                startServiceSafely(context, serviceIntent)
            }
            ACTION_TOGGLE, ALIAS_TOGGLE -> {
                val isRunning = ProxyState.isActualRunning()
                serviceIntent.action = if (isRunning) "STOP_ACTION" else "START_ACTION"
                startServiceSafely(context, serviceIntent)
            }
            ACTION_RESTART, ALIAS_RESTART -> {
                serviceIntent.action = "RESTART_ACTION"
                startServiceSafely(context, serviceIntent)
            }
            else -> {
                Log.w(TAG, "Unknown action received: $action")
            }
        }
    }

    private fun startServiceSafely(context: Context, serviceIntent: Intent) {
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TailscaledService via broadcast: ${e.message}", e)
        }
    }
}
