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
        val isMagicDnsEnabled = prefs.getBoolean("magic_dns", true)
        val isAllowLanAccess = prefs.getBoolean("allow_lan_access", true)

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
        if (exitNodeIp.isNotEmpty()) {
            val activeAccount = AccountManager.getActiveAccount(context)
            val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            prefs.edit().putString("exit_node_ip", exitNodeIp.trim()).apply()
        }

        val intent = Intent(context, TailscaledService::class.java).apply {
            action = "START_ACTION"
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
        ProxyState.setUserState(context, true)

        return ConnectionResult(
            success = true,
            isConnected = true,
            message = if (exitNodeIp.isNotEmpty()) "Connected to TailSocks with Exit Node $exitNodeIp" else "Connected to TailSocks"
        )
    }

    /**
     * Disconnects or stops TailSocks service.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun disconnect(appFunctionContext: AppFunctionContext): ConnectionResult {
        val context = appFunctionContext.context
        val intent = Intent(context, TailscaledService::class.java).apply {
            action = "STOP_ACTION"
        }
        context.startService(intent)
        ProxyState.setUserState(context, false)

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
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val ipToSet = if (exitNodeIp.equals("off", ignoreCase = true) || exitNodeIp.equals("none", ignoreCase = true)) "" else exitNodeIp.trim()

        prefs.edit().putString("exit_node_ip", ipToSet).apply()

        if (ProxyState.isActualRunning(context)) {
            val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                action = TailscaledService.ACTION_APPLY_SETTINGS
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }

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
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_lan_access", enabled).apply()

        if (ProxyState.isActualRunning(context)) {
            val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                action = TailscaledService.ACTION_APPLY_SETTINGS
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
        return SettingsResult(success = true, settingName = "Allow LAN Access", enabled = enabled, message = "Allow LAN Access ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Enables or disables MagicDNS resolution.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setMagicDns(appFunctionContext: AppFunctionContext, enabled: Boolean): SettingsResult {
        val context = appFunctionContext.context
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("magic_dns", enabled).apply()

        if (ProxyState.isActualRunning(context)) {
            val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                action = TailscaledService.ACTION_APPLY_SETTINGS
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
        return SettingsResult(success = true, settingName = "MagicDNS", enabled = enabled, message = "MagicDNS ${if (enabled) "enabled" else "disabled"}")
    }
}
