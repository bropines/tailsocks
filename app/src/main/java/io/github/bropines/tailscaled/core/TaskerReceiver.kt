package io.github.bropines.tailscaled.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import appctr.Appctr
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                val exitNode = intent.getStringExtra("exit_node")
                    ?: intent.getStringExtra("exit_node_ip")
                    ?: ""
                // Resolving the node asks the daemon: off the main thread, with the
                // broadcast held open until the choice is written.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        handleSetExitNode(context, exitNode)
                    } finally {
                        pending.finish()
                    }
                }
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

    /**
     * Sets the active profile's exit node from a Tailscale IP, `best` (the node the daemon
     * recommends, see [ExitNodeSuggestion]) or `none` / `disabled` / `off`. Both halves are
     * written: the address the app shows and the StableID the daemon routes by — the settings
     * sync sends only the latter, so an address written alone changed the screen, not the route.
     */
    private fun handleSetExitNode(context: Context, rawExitNode: String) {
        try {
            val activeAccount = AccountManager.getActiveAccount(context)
            val profilePrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val value = rawExitNode.trim()
            val (exitNodeId, exitNodeIp) = when {
                value.isEmpty() || value.equals("none", ignoreCase = true) ||
                    value.equals("disabled", ignoreCase = true) || value.equals("off", ignoreCase = true) -> "" to ""
                value.equals("best", ignoreCase = true) -> when (val best = ExitNodeSuggestion.fetch()) {
                    is ExitNodeSuggestion.Outcome.Suggested -> best.id to best.ip
                    is ExitNodeSuggestion.Outcome.Unavailable -> {
                        Log.w(TAG, "No recommended exit node (${best.reason}); the exit node is left as it was")
                        return
                    }
                }
                else -> resolveExitNodeId(value) to value
            }
            if (exitNodeIp.isNotEmpty() && exitNodeId.isEmpty()) {
                Log.w(TAG, "No peer holds $exitNodeIp right now; it is stored, but the daemon gets no exit node")
            }

            profilePrefs.edit()
                .putString("exit_node_ip", exitNodeIp)
                .putString("exit_node_id", exitNodeId)
                .apply()
            Log.d(TAG, "Updated Exit Node for account ${activeAccount.name} to '$exitNodeIp' ($exitNodeId)")

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

    /** The StableID of the peer holding [ip], or "" when the daemon cannot say. */
    private fun resolveExitNodeId(ip: String): String = try {
        val status = AppJson.decodeFromString<StatusResponse>(Appctr.getStatusFromAPI())
        status.peers?.values?.firstOrNull { it.tailscaleIPs?.contains(ip) == true }?.id.orEmpty()
    } catch (e: Exception) {
        ""
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
