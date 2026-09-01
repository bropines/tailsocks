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
        const val ACTION_GET_STATUS = "io.github.bropines.tailscaled.action.GET_STATUS"
        const val ACTION_SET_EXIT_NODE = "io.github.bropines.tailscaled.action.SET_EXIT_NODE"
        const val ACTION_SWITCH_ACCOUNT = "io.github.bropines.tailscaled.action.SWITCH_ACCOUNT"
        const val ACTION_SET_BYEDPI = "io.github.bropines.tailscaled.action.SET_BYEDPI"
        const val ACTION_SET_TUN = "io.github.bropines.tailscaled.action.SET_TUN"

        // Short aliases
        const val ALIAS_START = "io.github.bropines.tailscaled.START"
        const val ALIAS_STOP = "io.github.bropines.tailscaled.STOP"
        const val ALIAS_TOGGLE = "io.github.bropines.tailscaled.TOGGLE"
        const val ALIAS_RESTART = "io.github.bropines.tailscaled.RESTART"
        const val ALIAS_GET_STATUS = "io.github.bropines.tailscaled.GET_STATUS"
        const val ALIAS_SET_EXIT_NODE = "io.github.bropines.tailscaled.SET_EXIT_NODE"
        const val ALIAS_SWITCH_ACCOUNT = "io.github.bropines.tailscaled.SWITCH_ACCOUNT"
        const val ALIAS_SET_BYEDPI = "io.github.bropines.tailscaled.SET_BYEDPI"
        const val ALIAS_SET_TUN = "io.github.bropines.tailscaled.SET_TUN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast action: $action")

        // 1. Verify Automation Security Enabled
        if (!GlobalSettings.isAutomationEnabled(context)) {
            Log.w(TAG, "Automation is disabled in TailSocks settings. Ignoring intent: $action")
            return
        }

        // 2. Require a secret token. This receiver is exported with no
        //    permission, so an empty token used to leave it open to every app on
        //    the device — any of them could disable the VPN or reroute traffic.
        //    Nothing is honoured until the user sets a token in settings.
        val requiredSecret = GlobalSettings.getAutomationSecret(context)
        if (requiredSecret.isEmpty()) {
            Log.e(TAG, "Automation intent rejected: no secret token configured. Set one in Settings.")
            return
        }
        val providedSecret = intent.getStringExtra("secret")
            ?: intent.getStringExtra("token")
            ?: intent.getStringExtra("key")
            ?: ""
        if (!constantTimeEquals(providedSecret, requiredSecret)) {
            Log.e(TAG, "Unauthorized automation intent rejected! Invalid or missing secret token.")
            return
        }

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
            ACTION_GET_STATUS, ALIAS_GET_STATUS -> {
                TailscaledService.sendStatusBroadcast(context)
            }
            ACTION_SET_EXIT_NODE, ALIAS_SET_EXIT_NODE -> {
                val exitNodeIp = intent.getStringExtra("exit_node")
                    ?: intent.getStringExtra("exit_node_ip")
                    ?: ""
                handleSetExitNode(context, exitNodeIp)
            }
            ACTION_SWITCH_ACCOUNT, ALIAS_SWITCH_ACCOUNT -> {
                val targetAccount = intent.getStringExtra("account")
                    ?: intent.getStringExtra("account_id")
                    ?: intent.getStringExtra("account_name")
                    ?: ""
                handleSwitchAccount(context, targetAccount)
            }
            ACTION_SET_BYEDPI, ALIAS_SET_BYEDPI -> {
                if (intent.hasExtra("enabled")) {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    GlobalSettings.setCPByeDpiEnabled(context, enabled)
                }
                val flags = intent.getStringExtra("flags")
                if (!flags.isNullOrBlank()) {
                    GlobalSettings.setCPByeDpiFlags(context, flags)
                }
                serviceIntent.action = TailscaledService.ACTION_APPLY_SETTINGS
                startServiceSafely(context, serviceIntent)
            }
            ACTION_SET_TUN, ALIAS_SET_TUN -> {
                if (intent.hasExtra("enabled")) {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    GlobalSettings.setTunModeEnabled(context, enabled)
                    serviceIntent.action = TailscaledService.ACTION_APPLY_SETTINGS
                    startServiceSafely(context, serviceIntent)
                }
            }
            else -> {
                Log.w(TAG, "Unknown action received: $action")
            }
        }
    }

    private fun handleSetExitNode(context: Context, rawExitNode: String) {
        try {
            val activeAccount = AccountManager.getActiveAccount(context)
            val profilePrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val exitNodeIp = if (rawExitNode.equals("none", ignoreCase = true) || rawExitNode.equals("disabled", ignoreCase = true) || rawExitNode.equals("off", ignoreCase = true)) "" else rawExitNode.trim()

            profilePrefs.edit().putString("exit_node_ip", exitNodeIp).apply()
            Log.d(TAG, "Updated Exit Node IP for account ${activeAccount.name} to '$exitNodeIp'")

            if (ProxyState.isActualRunning()) {
                val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                    action = TailscaledService.ACTION_APPLY_SETTINGS
                }
                startServiceSafely(context, serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update exit node via intent: ${e.message}", e)
        }
    }

    private fun handleSwitchAccount(context: Context, targetAccountQuery: String) {
        if (targetAccountQuery.isBlank()) return
        try {
            val accounts = AccountManager.getAccounts(context)
            val foundAccount = accounts.find {
                it.id.equals(targetAccountQuery, ignoreCase = true) || it.name.equals(targetAccountQuery, ignoreCase = true)
            }

            if (foundAccount != null) {
                val currentAccount = AccountManager.getActiveAccount(context)
                if (foundAccount.id != currentAccount.id) {
                    Log.d(TAG, "Switching active account to: ${foundAccount.name} (${foundAccount.id})")
                    AccountManager.setActiveAccount(context, foundAccount.id)

                    if (ProxyState.isActualRunning()) {
                        val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                            action = "RESTART_ACTION"
                        }
                        startServiceSafely(context, serviceIntent)
                    }
                }
            } else {
                Log.w(TAG, "Account matching query '$targetAccountQuery' not found.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch account via intent: ${e.message}", e)
        }
    }

    /** Compares two secrets without leaking their length or content via timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun startServiceSafely(context: Context, serviceIntent: Intent) {
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TailscaledService via broadcast: ${e.message}", e)
        }
    }
}
