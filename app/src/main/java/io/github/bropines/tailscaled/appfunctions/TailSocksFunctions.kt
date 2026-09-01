package io.github.bropines.tailscaled.appfunctions

import android.content.Context
import android.content.Intent
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionSerializable
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.TailscaledService
import appctr.Appctr
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AppFunctionSerializable
data class TailSocksStatus(
    val isConnected: Boolean,
    val activeAccountName: String,
    val activeExitNodeIp: String,
    val isTunMode: Boolean,
    val isByeDpiEnabled: Boolean,
    val isMagicDnsEnabled: Boolean,
    val isAllowLanAccess: Boolean
)

@AppFunctionSerializable
data class ExitNodeItem(
    val name: String,
    val ip: String,
    val isOnline: Boolean,
    val isActive: Boolean
)

@AppFunctionSerializable
data class ExitNodesResult(
    val count: Int,
    val activeExitNodeIp: String,
    val exitNodes: List<ExitNodeItem>
)

@AppFunctionSerializable
data class PeerItem(
    val name: String,
    val ip: String,
    val os: String,
    val isOnline: Boolean
)

@AppFunctionSerializable
data class PeersResult(
    val count: Int,
    val peers: List<PeerItem>
)

@AppFunctionSerializable
data class AccountItem(
    val id: String,
    val name: String,
    val isActive: Boolean
)

@AppFunctionSerializable
data class AccountsResult(
    val count: Int,
    val activeAccountId: String,
    val accounts: List<AccountItem>
)

@AppFunctionSerializable
data class ConnectionResult(
    val success: Boolean,
    val isConnected: Boolean,
    val message: String
)

@AppFunctionSerializable
data class AccountSwitchResult(
    val success: Boolean,
    val activeAccountId: String,
    val activeAccountName: String,
    val message: String
)

@AppFunctionSerializable
data class SettingsResult(
    val success: Boolean,
    val settingName: String,
    val enabled: Boolean,
    val message: String
)

class TailSocksFunctions {

    /** True when the on-device automation surface is turned off in settings. */
    private fun automationOff(context: Context): Boolean = !GlobalSettings.isAutomationEnabled(context)

    private fun blockedConnection() = ConnectionResult(
        success = false, isConnected = false,
        message = "External automation is disabled in TailSocks settings."
    )

    private fun blockedSettings(name: String) = SettingsResult(
        success = false, settingName = name, enabled = false,
        message = "External automation is disabled in TailSocks settings."
    )

    /** Resolves a Tailscale IP to its StableID, which is what the daemon routes by. */
    private fun resolveExitNodeId(context: Context, ip: String): String {
        if (ip.isEmpty()) return ""
        return try {
            val statusJson = Appctr.getStatusJSON(true)
            if (statusJson.isNullOrEmpty()) return ""
            val root: Map<String, Any> = Gson().fromJson(statusJson, object : TypeToken<Map<String, Any>>() {}.type)
            val peers = root["Peer"] as? Map<String, Any> ?: emptyMap()
            for ((_, peerData) in peers) {
                val p = peerData as? Map<String, Any> ?: continue
                val ips = (p["TailscaleIPs"] as? List<*>)?.map { it.toString() } ?: emptyList()
                if (ips.contains(ip)) return (p["ID"] as? String) ?: ""
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Applies an exit node selection the same way the widgets do: both prefs and a live push. */
    private fun applyExitNode(context: Context, ip: String) {
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val id = if (ip.isEmpty()) "" else resolveExitNodeId(context, ip)
        prefs.edit().putString("exit_node_ip", ip).putString("exit_node_id", id).apply()
        if (Appctr.isRunning()) {
            try { Appctr.setPrefs("{\"ExitNodeID\": \"$id\", \"ExitNodeIDSet\": true}") } catch (e: Exception) {}
        } else if (ProxyState.isActualRunning(context)) {
            TailscaledService.requestApplySettings(context)
        }
    }

    /**
     * Returns current status of TailSocks VPN/proxy service, active account name, active exit node, and enabled features.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getStatus(appFunctionContext: AppFunctionContext): TailSocksStatus {
        val context = appFunctionContext.context
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        
        val isConnected = ProxyState.isActualRunning(context)
        val activeExitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        val isTunMode = GlobalSettings.isTunModeEnabled(context)
        val isByeDpiEnabled = GlobalSettings.isCPByeDpiEnabled(context)
        // Read the settings the daemon actually uses, not orphan per-profile keys.
        val isMagicDnsEnabled = GlobalSettings.getBoolean(context, "accept_dns", true)
        val isAllowLanAccess = GlobalSettings.isLanAccessEnabled(context)

        return TailSocksStatus(
            isConnected = isConnected,
            activeAccountName = activeAccount.name,
            activeExitNodeIp = activeExitNodeIp,
            isTunMode = isTunMode,
            isByeDpiEnabled = isByeDpiEnabled,
            isMagicDnsEnabled = isMagicDnsEnabled,
            isAllowLanAccess = isAllowLanAccess
        )
    }

    /**
     * Returns list of available Exit Nodes in the tailnet.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getAvailableExitNodes(appFunctionContext: AppFunctionContext): ExitNodesResult {
        val context = appFunctionContext.context
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val activeExitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        
        val exitNodeItems = mutableListOf<ExitNodeItem>()
        try {
            val statusJson = Appctr.getStatusJSON(true)
            if (!statusJson.isNullOrEmpty()) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val root: Map<String, Any> = gson.fromJson(statusJson, mapType)
                val peers = root["Peer"] as? Map<String, Any> ?: emptyMap()
                
                for ((_, peerData) in peers) {
                    val p = peerData as? Map<String, Any> ?: continue
                    val isExitNode = p["ExitNode"] as? Boolean ?: false
                    val exitNodeOption = p["ExitNodeOption"] as? Boolean ?: false
                    if (isExitNode || exitNodeOption) {
                        val name = (p["HostName"] as? String) ?: (p["DNSName"] as? String) ?: "Exit Node"
                        val ips = p["TailscaleIPs"] as? List<*>
                        val ip = ips?.firstOrNull()?.toString() ?: ""
                        val isOnline = p["Online"] as? Boolean ?: false
                        exitNodeItems.add(
                            ExitNodeItem(
                                name = name.removeSuffix("."),
                                ip = ip,
                                isOnline = isOnline,
                                isActive = ip == activeExitNodeIp
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ExitNodesResult(
            count = exitNodeItems.size,
            activeExitNodeIp = activeExitNodeIp,
            exitNodes = exitNodeItems
        )
    }

    /**
     * Returns list of all tailnet peer devices.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getTailnetPeers(appFunctionContext: AppFunctionContext): PeersResult {
        val peerItems = mutableListOf<PeerItem>()
        try {
            val statusJson = Appctr.getStatusJSON(true)
            if (!statusJson.isNullOrEmpty()) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val root: Map<String, Any> = gson.fromJson(statusJson, mapType)
                val peers = root["Peer"] as? Map<String, Any> ?: emptyMap()
                
                for ((_, peerData) in peers) {
                    val p = peerData as? Map<String, Any> ?: continue
                    val name = (p["HostName"] as? String) ?: (p["DNSName"] as? String) ?: "Peer"
                    val ips = p["TailscaleIPs"] as? List<*>
                    val ip = ips?.firstOrNull()?.toString() ?: ""
                    val os = p["OS"] as? String ?: "unknown"
                    val isOnline = p["Online"] as? Boolean ?: false
                    peerItems.add(
                        PeerItem(
                            name = name.removeSuffix("."),
                            ip = ip,
                            os = os,
                            isOnline = isOnline
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return PeersResult(count = peerItems.size, peers = peerItems)
    }

    /**
     * Returns list of configured accounts/profiles.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getAccounts(appFunctionContext: AppFunctionContext): AccountsResult {
        val context = appFunctionContext.context
        val accounts = AccountManager.getAccounts(context)
        val activeAccount = AccountManager.getActiveAccount(context)
        val items = accounts.map {
            AccountItem(id = it.id, name = it.name, isActive = it.id == activeAccount.id)
        }
        return AccountsResult(count = items.size, activeAccountId = activeAccount.id, accounts = items)
    }

    /**
     * Connects or starts TailSocks service. Optionally selects an exit node IP.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun connect(appFunctionContext: AppFunctionContext, exitNodeIp: String): ConnectionResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedConnection()

        if (exitNodeIp.isNotEmpty()) {
            applyExitNode(context, exitNodeIp.trim())
        }

        ProxyState.setUserState(context, true)
        val intent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)

        // Report the observed state rather than an optimistic "connected".
        val connected = withContext(Dispatchers.IO) {
            var ok = false
            for (i in 0 until 15) {
                if (Appctr.isRunning() && runCatching { Appctr.getBackendState() }.getOrDefault("") == "Running") { ok = true; break }
                try { Thread.sleep(400) } catch (e: Exception) {}
            }
            ok || ProxyState.isActualRunning(context)
        }
        return ConnectionResult(
            success = true,
            isConnected = connected,
            message = when {
                !connected -> "TailSocks is starting; not connected yet."
                exitNodeIp.isNotEmpty() -> "Connected to TailSocks with Exit Node $exitNodeIp"
                else -> "Connected to TailSocks"
            }
        )
    }

    /**
     * Disconnects or stops TailSocks service.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun disconnect(appFunctionContext: AppFunctionContext): ConnectionResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedConnection()
        ProxyState.setUserState(context, false)
        val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
        return ConnectionResult(
            success = true,
            isConnected = false,
            message = "Disconnected TailSocks"
        )
    }

    /**
     * Toggles TailSocks service on or off.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun toggle(appFunctionContext: AppFunctionContext): ConnectionResult {
        val context = appFunctionContext.context
        val currentlyRunning = ProxyState.isActualRunning(context)
        return if (currentlyRunning) disconnect(appFunctionContext) else connect(appFunctionContext, "")
    }

    /**
     * Selects active exit node IP or clears it if "off" or empty string is passed.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun selectExitNode(appFunctionContext: AppFunctionContext, exitNodeIp: String): ConnectionResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedConnection()
        val ipToSet = if (exitNodeIp.equals("off", ignoreCase = true) || exitNodeIp.equals("none", ignoreCase = true)) "" else exitNodeIp.trim()

        // Writes both exit_node_ip and the StableID the daemon routes by, and
        // pushes it live — writing only the IP changed nothing.
        applyExitNode(context, ipToSet)

        return ConnectionResult(
            success = true,
            isConnected = ProxyState.isActualRunning(context),
            message = if (ipToSet.isEmpty()) "Exit Node cleared" else "Exit Node set to $ipToSet"
        )
    }

    /**
     * Clears current active exit node (routes traffic directly).
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun clearExitNode(appFunctionContext: AppFunctionContext): ConnectionResult = selectExitNode(appFunctionContext, "")

    /**
     * Switches active account profile by name or ID.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun switchAccount(appFunctionContext: AppFunctionContext, accountNameOrId: String): AccountSwitchResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return AccountSwitchResult(false, AccountManager.getActiveAccount(context).id, AccountManager.getActiveAccount(context).name, "External automation is disabled in TailSocks settings.")
        val accounts = AccountManager.getAccounts(context)
        val found = accounts.find {
            it.id.equals(accountNameOrId, ignoreCase = true) || it.name.equals(accountNameOrId, ignoreCase = true)
        }

        if (found == null) {
            return AccountSwitchResult(
                success = false,
                activeAccountId = AccountManager.getActiveAccount(context).id,
                activeAccountName = AccountManager.getActiveAccount(context).name,
                message = "Account '$accountNameOrId' not found"
            )
        }

        val wasRunning = ProxyState.isActualRunning(context)
        if (wasRunning) {
            val stopIntent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
            context.startService(stopIntent)
        }

        AccountManager.setActiveAccount(context, found.id)

        if (wasRunning) {
            val startIntent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
            androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
        }

        return AccountSwitchResult(
            success = true,
            activeAccountId = found.id,
            activeAccountName = found.name,
            message = "Switched active account to ${found.name}"
        )
    }

    /**
     * Enables or disables ByeDPI DPI bypass.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setByeDpi(appFunctionContext: AppFunctionContext, enabled: Boolean, flags: String): SettingsResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedSettings("ByeDPI")
        GlobalSettings.setCPByeDpiEnabled(context, enabled)
        if (flags.isNotBlank()) {
            GlobalSettings.setCPByeDpiFlags(context, flags)
        }
        if (ProxyState.isActualRunning(context)) {
            val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                action = TailscaledService.ACTION_APPLY_SETTINGS
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
        return SettingsResult(success = true, settingName = "ByeDPI", enabled = enabled, message = "ByeDPI ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Enables or disables transparent TUN mode.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setTunMode(appFunctionContext: AppFunctionContext, enabled: Boolean): SettingsResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedSettings("TUN Mode")
        GlobalSettings.setTunModeEnabled(context, enabled)
        if (ProxyState.isActualRunning(context)) {
            val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                action = TailscaledService.ACTION_APPLY_SETTINGS
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
        return SettingsResult(success = true, settingName = "TUN Mode", enabled = enabled, message = "TUN Mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Enables or disables local network access (allow LAN).
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setAllowLanAccess(appFunctionContext: AppFunctionContext, enabled: Boolean): SettingsResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedSettings("Allow LAN Access")
        GlobalSettings.setLanAccessEnabled(context, enabled)
        if (ProxyState.isActualRunning(context)) {
            TailscaledService.requestApplySettings(context)
        }
        return SettingsResult(success = true, settingName = "Allow LAN Access", enabled = enabled, message = "Allow LAN Access ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Enables or disables MagicDNS resolution.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setMagicDns(appFunctionContext: AppFunctionContext, enabled: Boolean): SettingsResult {
        val context = appFunctionContext.context
        if (automationOff(context)) return blockedSettings("MagicDNS")
        GlobalSettings.setBoolean(context, "accept_dns", enabled)
        if (ProxyState.isActualRunning(context)) {
            TailscaledService.requestApplySettings(context)
        }
        return SettingsResult(success = true, settingName = "MagicDNS", enabled = enabled, message = "MagicDNS ${if (enabled) "enabled" else "disabled"}")
    }
}
