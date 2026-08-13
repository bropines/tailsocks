package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import appctr.Appctr
import appctr.Closer
import appctr.StartOptions
import com.google.gson.Gson

class TailscaledService : Service() {
    companion object {
        const val ACTION_APPLY_SETTINGS = "APPLY_SETTINGS"
        const val ACTION_STATUS_CHANGED = "io.github.bropines.tailscaled.STATUS_CHANGED"
        const val ALIAS_STATUS_CHANGED = "io.github.bropines.tailscaled.STATUS"

        fun sendStatusBroadcast(context: Context, statusOverride: String? = null) {
            try {
                val isRunning = Appctr.isRunning()
                val activeAccount = AccountManager.getActiveAccount(context)
                val profilePrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
                val exitNodeIp = profilePrefs.getString("exit_node_ip", "") ?: ""
                val statusText = statusOverride ?: if (isRunning) "ACTIVE" else "STOPPED"

                val intent = Intent(ACTION_STATUS_CHANGED).apply {
                    setPackage(context.packageName)
                    putExtra("running", isRunning)
                    putExtra("status", statusText)
                    putExtra("account", activeAccount.name)
                    putExtra("account_id", activeAccount.id)
                    putExtra("exit_node", exitNodeIp)
                    putExtra("tun_enabled", GlobalSettings.isTunModeEnabled(context))
                    putExtra("byedpi_enabled", GlobalSettings.isCPByeDpiEnabled(context))
                }
                context.sendBroadcast(intent)

                val aliasIntent = Intent(ALIAS_STATUS_CHANGED).apply {
                    setPackage(context.packageName)
                    putExtra("running", isRunning)
                    putExtra("status", statusText)
                    putExtra("account", activeAccount.name)
                    putExtra("account_id", activeAccount.id)
                    putExtra("exit_node", exitNodeIp)
                    putExtra("tun_enabled", GlobalSettings.isTunModeEnabled(context))
                    putExtra("byedpi_enabled", GlobalSettings.isCPByeDpiEnabled(context))
                }
                context.sendBroadcast(aliasIntent)

                updateAllWidgets(context)
                forceAppWidgetUpdate(context)
            } catch (e: Exception) {
                Log.e("TailscaledService", "Failed to send status broadcast: ${e.message}")
            }
        }
    }
    private val TAG = "TailscaledService"
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var wakeLock: PowerManager.WakeLock? = null
    private var byedpiProxyAddress: Pair<String, Int>? = null
    private var lastStartedFlags: String? = null
    private var lastStartedIpv6Disabled: Boolean? = null
    private var dnsRedirectApplied = false
    
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            // No longer need to manually check and reset Exit Nodes here,
            // as LocalAPI synchronization in ApplySettings handles profile-dependent settings.
            val activeAccount = AccountManager.getActiveAccount(this@TailscaledService)
            val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val defaultInterval = profilePrefs.getString("refresh_interval", "15000")?.toLongOrNull() ?: 15000L
            var interval = defaultInterval

            val isRunning = Appctr.isRunning()
            val backendState = if (isRunning) {
                try { Appctr.getBackendState() } catch (e: Exception) { "" }
            } else ""

            if (GlobalSettings.isRootModeEnabled(this@TailscaledService) && GlobalSettings.isRootTunEnabled(this@TailscaledService)) {
                if (isRunning && backendState == "Running") {
                    if (!dnsRedirectApplied) {
                        Log.i(TAG, "Daemon is Running. Applying Root tailscale0 routing.")
                        RootUtils.applyTailscale0Routing()
                        dnsRedirectApplied = true
                    }
                    syncTailnetHosts()
                } else {
                    if (dnsRedirectApplied) {
                        Log.i(TAG, "Daemon is not Running ($backendState). Cleaning up tailscale0 routing.")
                        RootUtils.cleanupTailscale0Routing()
                        dnsRedirectApplied = false
                    }
                    if (isRunning && (backendState == "NeedsLogin" || backendState == "Starting" || backendState == "NoState")) {
                        interval = 2000L
                    }
                }
            }
            
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (isRunning && powerManager.isInteractive) {
                updateAllWidgets(this@TailscaledService)
            }
            
            refreshHandler.postDelayed(this, interval)
        }
    }

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastStateJson = ""
        
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network Available")
            injectIfNeeded()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (Appctr.isRunning()) updateNotification("Active")
            }, 1500)
        }
        override fun onLost(network: Network) {
            Log.d(TAG, "Network Lost")
            injectIfNeeded()
            if (Appctr.isRunning()) updateNotification("Waiting for network...")
        }

        private fun injectIfNeeded() {
            if (!Appctr.isRunning()) return
            Thread {
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    val list = mutableListOf<Map<String, Any>>()
                    if (interfaces != null) {
                        for (iface in interfaces) {
                            if (!iface.isUp || iface.isLoopback) continue
                            val addrs = iface.inetAddresses?.toList()?.filter { !it.isLoopbackAddress }?.map { it.hostAddress ?: "" } ?: emptyList()
                            if (addrs.isNotEmpty()) {
                                list.add(mapOf("name" to iface.name, "addresses" to addrs, "up" to iface.isUp, "mtu" to iface.mtu))
                            }
                        }
                    }
                    val json = Gson().toJson(list)
                    if (json != lastStateJson) {
                        lastStateJson = json
                        Appctr.injectNetworkState(json)
                        Log.d(TAG, "Network state changed and injected")
                    }
                } catch (e: Exception) { Log.e(TAG, "Inject failed: ${e.message}") }
            }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { android.system.Os.setenv("TZ", java.util.TimeZone.getDefault().id, true) } catch (e: Exception) {}
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::WakeLock")
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback) } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !ProxyState.isUserLetRunning(this)) {
            stopMe()
            return START_NOT_STICKY
        }
        val action = intent?.action
        
        if (action == "STOP_ACTION") {
            val notificationText = "Stopping..."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    buildNotification(notificationText),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, buildNotification(notificationText))
            }
            stopMe()
            return START_NOT_STICKY
        }
        
        if (action == "REFRESH_ACTION" || action == "APPLY_SETTINGS" || action == ACTION_APPLY_SETTINGS) {
            if (!Appctr.isRunning()) {
                stopMe()
                return START_NOT_STICKY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    buildNotification("Active"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, buildNotification("Active"))
            }
            Thread {
                Appctr.applySettings(buildStartOptions())
                try { Thread.sleep(1500) } catch (e: Exception) {}
                applyTagsAndRoutes(this@TailscaledService)
                applyTaildrive(this@TailscaledService)
                if (GlobalSettings.isTunModeEnabled(this@TailscaledService)) {
                    startTunMode()
                } else {
                    stopTunMode()
                }
            }.start()
            return START_STICKY
        }
        
        if (action == "RESTART_ACTION") {
            val notificationText = "Restarting..."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    buildNotification(notificationText),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, buildNotification(notificationText))
            }
            Thread {
                stopMe()
                Thread.sleep(1500)
                startTailscale()
            }.start()
            return START_STICKY
        }

        ProxyState.setUserState(this, true)
        updateTile()
        if (!Appctr.isRunning()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    buildNotification("Starting..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, buildNotification("Starting..."))
            }
            startTailscale()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    buildNotification("Active"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, buildNotification("Active"))
            }
            updateNotification("Active")
        }
        
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 1000)
        return START_STICKY
    }

    private fun startTailscale() {
        val options = buildStartOptions()
        val activeAccount = AccountManager.getActiveAccount(this)
        val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        
        if (profilePrefs.getBoolean("force_bg", false)) wakeLock?.acquire(10 * 60 * 1000L)
        
        Thread {
            try {
                applicationContext.sendBroadcast(Intent("STARTING"))
                if (GlobalSettings.isRootModeEnabled(this@TailscaledService)) {
                    val socketFile = java.io.File(options.socketPath)
                    val statusJson = if (socketFile.exists()) {
                        kotlinx.coroutines.runBlocking { LocalApiClient { options.socketPath }.getStatus().getOrNull() }
                    } else null

                    val isRunningValid = statusJson != null && !statusJson.contains("\"BackendState\":\"NoState\"")

                    if (isRunningValid) {
                        Log.i(TAG, "Root daemon is already running. Attaching to existing socket with full options.")
                        Appctr.attachExternal(options)
                    } else {
                        if (socketFile.exists()) {
                            Log.w(TAG, "Root daemon is in NoState or unconfigured. Stopping stale daemon and restarting.")
                            RootUtils.stopRootDaemon(options.socketPath)
                        }
                        val logsDir = java.io.File(filesDir.parentFile ?: filesDir, "logs").absolutePath
                        val logFile = "$logsDir/tailscaled.log"
                        val ok = RootUtils.startRootDaemon(
                            context = this@TailscaledService,
                            stateDir = options.statePath,
                            socketPath = options.socketPath,
                            logFilePath = logFile,
                            socksAddr = options.socks5Server,
                            httpAddr = options.httpProxy,
                            controlProxy = options.controlProxy,
                            taildropDir = options.taildropDir,
                            tunMode = GlobalSettings.isRootTunEnabled(this@TailscaledService)
                        )

                        if (ok) {
                            Appctr.attachExternal(options)
                        }
                    }
                } else {
                    Appctr.setExternalSocketPath("")
                    Appctr.start(options)
                }
                updateNotification("Active")
                applicationContext.sendBroadcast(Intent("START"))
                forceAppWidgetUpdate(this@TailscaledService)
                if (waitForDaemonReady()) {
                    Log.d(TAG, "Daemon readiness checkpoint reached. Launching auxiliary modules...")
                    applyTagsAndRoutes(this@TailscaledService)
                    applyTaildrive(this@TailscaledService)
                    
                    if (GlobalSettings.isTunModeEnabled(this@TailscaledService) && !GlobalSettings.isRootModeEnabled(this@TailscaledService)) {
                        startTunMode()
                    }
                } else {
                    Log.w(TAG, "Daemon readiness checkpoint timed out.")
                }
            } catch (e: Exception) { 
                Log.e(TAG, "Start failed", e)
                stopMe() 
            }
        }.start()
    }

    private fun waitForDaemonReady(timeoutMs: Long = 10000L): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val st = Appctr.getStatusJSON(false)
                if (st.isNotBlank() && st.contains("BackendState")) {
                    return true
                }
            } catch (e: Exception) {
                // Socket / daemon not responsive yet
            }
            try { Thread.sleep(300) } catch (e: Exception) {}
        }
        return false
    }

    private fun buildStartOptions(): StartOptions {
        val activeAccount = AccountManager.getActiveAccount(this)
        val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val stateDir = "${filesDir.absolutePath}/states/${activeAccount.id}"
        java.io.File(stateDir).mkdirs()

        val accRoutes = GlobalSettings.getBoolean(this@TailscaledService, "accept_routes", false)
        val accDNS = GlobalSettings.getBoolean(this@TailscaledService, "accept_dns", true)
        var host = profilePrefs.getString("hostname", "") ?: ""
        if (host.isBlank()) {
            val defaultHost = android.os.Build.MODEL.replace(" ", "-").lowercase().replace(Regex("[^a-z0-9-]"), "")
            if (defaultHost.isNotBlank()) {
                host = defaultHost
                profilePrefs.edit().putString("hostname", defaultHost).apply()
            }
        }

        val byedpiEnabled = GlobalSettings.isCPByeDpiEnabled(this@TailscaledService)
        val flags = GlobalSettings.getCPByeDpiFlags(this@TailscaledService)
        val ipv6Disabled = GlobalSettings.isCPByeDpiIpv6Disabled(this@TailscaledService)

        if (byedpiEnabled) {
            if (byedpiProxyAddress == null || flags != lastStartedFlags || ipv6Disabled != lastStartedIpv6Disabled) {
                if (byedpiProxyAddress != null) {
                    try { ByeDpiProxy.stop() } catch (e: Exception) {}
                    try { Thread.sleep(200) } catch (e: Exception) {}
                }
                byedpiProxyAddress = ByeDpiProxy.start(flags, this@TailscaledService)
                lastStartedFlags = flags
                lastStartedIpv6Disabled = ipv6Disabled
            }
        } else {
            ByeDpiProxy.stop()
            byedpiProxyAddress = null
            lastStartedFlags = null
            lastStartedIpv6Disabled = null
        }

        return StartOptions().apply {
            socks5Server = GlobalSettings.getString(this@TailscaledService, "socks5", "127.0.0.1:48115")
            socks5User   = GlobalSettings.getString(this@TailscaledService, "socks5_user", "")
            socks5Pass   = GlobalSettings.getString(this@TailscaledService, "socks5_pass", "")
            httpProxy    = GlobalSettings.getString(this@TailscaledService, "httpproxy", "")
            controlProxy = if (byedpiEnabled && byedpiProxyAddress != null) {
                "socks5://${byedpiProxyAddress!!.first}:${byedpiProxyAddress!!.second}"
            } else {
                GlobalSettings.getControlProxyUrl(this@TailscaledService)
            }
            dnsProxy     = GlobalSettings.getString(this@TailscaledService, "dns_proxy", "127.0.0.1:1053")
            dnsFallbacks = GlobalSettings.getString(this@TailscaledService, "dns_fallbacks", "8.8.8.8:53,1.1.1.1:53")
            dohFallback  = GlobalSettings.getString(this@TailscaledService, "doh_url", "https://1.1.1.1/dns-query")
            loginServer  = profilePrefs.getString("login_server", "") ?: ""
            
            authKey      = profilePrefs.getString("authkey", "")
            enableWebUI = profilePrefs.getBoolean("enable_webui", false)
            webUIAddr   = profilePrefs.getString("webui_addr", "127.0.0.1:8080")
            
            taildropDir = "$stateDir/taildrop"
            java.io.File(taildropDir).mkdirs()
            execPath     = "${applicationInfo.nativeLibraryDir}/libtailscale.so"
            socketPath   = "${filesDir.absolutePath}/tailscaled.sock"
            statePath    = stateDir
            closeCallBack = Closer { stopMe() }
            doReset      = profilePrefs.getBoolean("do_reset", false)
            if (doReset) profilePrefs.edit().putBoolean("do_reset", false).apply()

            // Pass flags directly for LocalAPI synchronization in Go
            hostname = host
            acceptRoutes = accRoutes
            acceptDNS = accDNS
            exitNodeID = profilePrefs.getString("exit_node_id", "") ?: ""

            val argsBuilder = StringBuilder()
            if (host.isNotEmpty()) argsBuilder.append("--hostname=$host ")
            
            val loginServer = profilePrefs.getString("login_server", "")
            if (!loginServer.isNullOrEmpty()) argsBuilder.append("--login-server=$loginServer ")
            
            argsBuilder.append("--accept-routes=$accRoutes ")
            argsBuilder.append("--accept-dns=$accDNS ")
            
            // Exit Nodes are now managed dynamically via LocalAPI (Appctr.setPrefs)
            // in SettingsActivity. We no longer pass them in 'up' to avoid restarting
            // configuration unnecessarily.

            if (profilePrefs.getBoolean("advertise_exit_node", false)) {
                argsBuilder.append("--advertise-exit-node=true ")
            } else {
                argsBuilder.append("--advertise-exit-node=false ")
            }

            val extraArgs = GlobalSettings.getString(this@TailscaledService, "extra_args_raw", "")
            if (extraArgs.isNotEmpty()) argsBuilder.append("$extraArgs ")

            extraUpArgs = argsBuilder.toString()
            
            val detailedLogs = GlobalSettings.getBoolean(this@TailscaledService, "detailed_logs", false)
            Appctr.setLogLevel(if (detailedLogs) 0 else 1)
        }
    }

    private fun stopMe() {
        stopTunMode()
        ProxyState.setUserState(this, false)
        refreshHandler.removeCallbacks(refreshRunnable)
        try { Appctr.stopDriveServer() } catch (e: Exception) {}
        try { Appctr.stopDriveProxy() } catch (e: Exception) {}
        if (GlobalSettings.isRootModeEnabled(this)) {
            val socketPath = "${filesDir.absolutePath}/tailscaled.sock"
            Appctr.setExternalSocketPath("")
            if (GlobalSettings.shouldKillRootDaemonOnStop(this)) {
                RootUtils.stopRootDaemon(socketPath)
            }
        } else {
            Appctr.stop()
        }
        try { ByeDpiProxy.stop() } catch (e: Exception) {}
        byedpiProxyAddress = null
        lastStartedFlags = null
        lastStartedIpv6Disabled = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        updateTile()
        applicationContext.sendBroadcast(Intent("STOP"))
        sendStatusBroadcast(this, "STOPPED")
    }
    
    private fun updateTile() {
        TileService.requestListeningState(this, ComponentName(this, ProxyTileService::class.java))
        updateAllWidgets(this@TailscaledService)
        forceAppWidgetUpdate(this@TailscaledService)
    }
    private fun updateNotification(status: String) = notificationManager.notify(1, buildNotification(status))

    private fun buildNotification(status: String): Notification {
        val channelId = "tailscaled_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Tailscale Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, TailscaledService::class.java).apply { action = "STOP_ACTION" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TailSocks").setContentText(status).setSmallIcon(android.R.drawable.ic_secure).setOngoing(true).setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent).build()
    }

    private fun applyTagsAndRoutes(context: Context) {
        val activeAccount = AccountManager.getActiveAccount(context)
        val profilePrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val tagsStr = profilePrefs.getString("advertise_tags", "") ?: ""
        val routesStr = profilePrefs.getString("advertise_routes", "") ?: ""

        val tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
            if (it.startsWith("tag:")) it else "tag:$it"
        }
        val routes = routesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val payload = mapOf(
            "AdvertiseTags" to tags,
            "AdvertiseTagsSet" to true,
            "AdvertiseRoutes" to routes,
            "AdvertiseRoutesSet" to true
        )
        val json = Gson().toJson(payload)
        Log.d(TAG, "Syncing tags & routes via LocalAPI: $json")
        val res = Appctr.setPrefs(json)
        if (res != "OK") {
            Log.e(TAG, "Failed to apply tags & routes: $res")
        }
    }

    private fun applyTaildrive(context: Context) {
        val activeAccount = AccountManager.getActiveAccount(context)
        val profilePrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        if (!profilePrefs.contains("taildrive_enabled")) {
            profilePrefs.edit()
                .putBoolean("taildrive_enabled", true)
                .putString("taildrive_shares", "[{\"name\":\"Downloads\",\"path\":\"/storage/emulated/0/Download\"}]")
                .apply()
        }

        val taildriveEnabled = profilePrefs.getBoolean("taildrive_enabled", true)
        val proxyEnabled = profilePrefs.getBoolean("taildrive_proxy_enabled", false)

        if (!Appctr.isRunning()) {
            Log.d(TAG, "Taildrive: Tailscaled is not running, skipping.")
            return
        }

        if (taildriveEnabled) {
            try {
                Log.d(TAG, "Taildrive: Enabling server...")
                val addr = Appctr.startDriveServer()
                Log.d(TAG, "Taildrive: Server started on $addr")
                val sharesJson = profilePrefs.getString("taildrive_shares", "[]") ?: "[]"
                Log.d(TAG, "Taildrive: Updating shares: $sharesJson")
                Appctr.updateDriveShares(sharesJson)
                Log.d(TAG, "Taildrive: Shares updated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Taildrive: Failed to start server or update shares", e)
            }
        } else {
            try {
                Log.d(TAG, "Taildrive: Disabling server...")
                Appctr.stopDriveServer()
                Log.d(TAG, "Taildrive: Server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Taildrive: Failed to stop server", e)
            }
        }

        if (proxyEnabled) {
            try {
                val ip = profilePrefs.getString("taildrive_proxy_ip", "127.0.0.1") ?: "127.0.0.1"
                val port = profilePrefs.getString("taildrive_proxy_port", "33445") ?: "33445"
                val authEnabled = profilePrefs.getBoolean("taildrive_proxy_auth_enabled", false)
                val user = if (authEnabled) (profilePrefs.getString("taildrive_proxy_username", "tailsocks") ?: "tailsocks") else ""
                val pass = if (authEnabled) (profilePrefs.getString("taildrive_proxy_password", "") ?: "") else ""
                val localAddr = "$ip:$port"

                Log.d(TAG, "Taildrive Proxy: Enabling proxy on $localAddr (auth=$authEnabled)...")
                Appctr.startDriveProxy(localAddr, user, pass)
                Log.d(TAG, "Taildrive Proxy: Proxy started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Taildrive Proxy: Failed to start proxy", e)
            }
        } else {
            try {
                Log.d(TAG, "Taildrive Proxy: Disabling proxy...")
                Appctr.stopDriveProxy()
                Log.d(TAG, "Taildrive Proxy: Proxy stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Taildrive Proxy: Failed to stop proxy", e)
            }
        }
    }

    private fun startTunMode() {
        try {
            val prepareIntent = android.net.VpnService.prepare(this)
            if (prepareIntent == null) {
                Log.d(TAG, "VPN permission already granted, starting TunVpnService directly")
                val tunIntent = Intent(this, TunVpnService::class.java).apply {
                    action = TunVpnService.ACTION_START
                }
                ContextCompat.startForegroundService(this, tunIntent)
            } else {
                Log.d(TAG, "VPN permission required, launching TunPermissionActivity")
                val intent = Intent(this, TunPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TUN mode", e)
        }
    }

    private fun stopTunMode() {
        try {
            if (!TunVpnService.nativeLoaded) {
                Log.d(TAG, "TUN native library not loaded, skipping stop")
                return
            }
            startService(Intent(this, TunVpnService::class.java).apply {
                action = TunVpnService.ACTION_STOP
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TunVpnService", e)
        }
    }

    private var lastHostsHash: Int = 0

    private fun syncTailnetHosts() {
        if (!GlobalSettings.isRootModeEnabled(this) || !GlobalSettings.isRootTunEnabled(this)) return
        
        Thread {
            try {
                val statusJson = Appctr.getStatusJSON(true)
                if (statusJson.isNullOrEmpty()) return@Thread
                
                val gson = Gson()
                val mapType = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                val root: Map<String, Any> = gson.fromJson(statusJson, mapType)
                val peers = root["Peer"] as? Map<String, Any> ?: emptyMap()
                
                val hostsMap = mutableMapOf<String, String>()
                
                // Add self
                val self = root["Self"] as? Map<String, Any>
                if (self != null) {
                    val dnsName = (self["DNSName"] as? String)?.removeSuffix(".")
                    val ips = self["TailscaleIPs"] as? List<*>
                    if (!dnsName.isNullOrEmpty() && ips != null) {
                        for (ip in ips) {
                            val ipStr = ip?.toString() ?: continue
                            hostsMap[ipStr] = dnsName
                        }
                    }
                }
                
                // Add peers
                for ((_, peerData) in peers) {
                    val p = peerData as? Map<String, Any> ?: continue
                    val dnsName = (p["DNSName"] as? String)?.removeSuffix(".")
                    val ips = p["TailscaleIPs"] as? List<*>
                    if (!dnsName.isNullOrEmpty() && ips != null) {
                        for (ip in ips) {
                            val ipStr = ip?.toString() ?: continue
                            hostsMap[ipStr] = dnsName
                        }
                    }
                }
                
                val currentHash = hostsMap.hashCode()
                if (currentHash != lastHostsHash && hostsMap.isNotEmpty()) {
                    Log.i(TAG, "Syncing ${hostsMap.size} tailnet hosts to /system/etc/hosts")
                    if (RootUtils.updateRootHosts(hostsMap)) {
                        lastHostsHash = currentHash
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync tailnet hosts: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        if (GlobalSettings.isRootModeEnabled(this)) {
            val socketPath = "${filesDir.absolutePath}/tailscaled.sock"
            Appctr.setExternalSocketPath("")
            if (!RootUtils.isServiceScriptInstalled()) {
                RootUtils.stopRootDaemon(socketPath)
            }
        } else {
            Appctr.stop()
        }
        try { ByeDpiProxy.stop() } catch (e: Exception) {}
        byedpiProxyAddress = null
        lastStartedFlags = null
        lastStartedIpv6Disabled = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
