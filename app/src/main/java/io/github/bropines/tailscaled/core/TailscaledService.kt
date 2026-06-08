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
import appctr.Appctr
import appctr.Closer
import appctr.StartOptions
import com.google.gson.Gson

class TailscaledService : Service() {
    companion object {
        const val ACTION_APPLY_SETTINGS = "APPLY_SETTINGS"
    }
    private val TAG = "TailscaledService"
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var wakeLock: PowerManager.WakeLock? = null
    private var byedpiProxyAddress: Pair<String, Int>? = null
    
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            // Больше не нужно вручную проверять и сбрасывать Exit Nodes здесь,
            // так как LocalAPI синхронизация в ApplySettings позаботится о профиле-зависимых настройках.
            val activeAccount = AccountManager.getActiveAccount(this@TailscaledService)
            val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val interval = profilePrefs.getString("refresh_interval", "15000")?.toLongOrNull() ?: 15000L
            
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Appctr.isRunning() && powerManager.isInteractive) {
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
                Appctr.start(options)
                updateNotification("Active")
                applicationContext.sendBroadcast(Intent("START"))
                
                try { Thread.sleep(2500) } catch (e: Exception) {}
                applyTagsAndRoutes(this@TailscaledService)
                applyTaildrive(this@TailscaledService)
                
                if (GlobalSettings.isTunModeEnabled(this@TailscaledService)) {
                    startTunMode()
                }
            } catch (e: Exception) { 
                Log.e(TAG, "Start failed", e)
                stopMe() 
            }
        }.start()
    }

    private fun buildStartOptions(): StartOptions {
        val activeAccount = AccountManager.getActiveAccount(this)
        val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val stateDir = "${filesDir.absolutePath}/states/${activeAccount.id}"
        java.io.File(stateDir).mkdirs()

        val accRoutes = GlobalSettings.getBoolean(this@TailscaledService, "accept_routes", false)
        val accDNS = GlobalSettings.getBoolean(this@TailscaledService, "accept_dns", true)
        val host = profilePrefs.getString("hostname", "") ?: ""

        val byedpiEnabled = GlobalSettings.isCPByeDpiEnabled(this@TailscaledService)
        if (byedpiEnabled) {
            if (byedpiProxyAddress == null) {
                val flags = GlobalSettings.getCPByeDpiFlags(this@TailscaledService)
                byedpiProxyAddress = ByeDpiProxy.start(flags, this@TailscaledService)
            }
        } else {
            ByeDpiProxy.stop()
            byedpiProxyAddress = null
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

            // Передаем флаги напрямую для LocalAPI синхронизации в Go
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
            
            // Exit Nodes теперь управляются динамически через LocalAPI (Appctr.setPrefs)
            // в SettingsActivity. Мы больше не передаем их в 'up', чтобы не перезапускать 
            // конфигурацию без необходимости.

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
        Appctr.stop()
        try { ByeDpiProxy.stop() } catch (e: Exception) {}
        byedpiProxyAddress = null
        try { Runtime.getRuntime().exec("killall tailscaled") } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        updateTile()
        applicationContext.sendBroadcast(Intent("STOP"))
    }
    
    private fun updateTile() {
        TileService.requestListeningState(this, ComponentName(this, ProxyTileService::class.java))
        updateAllWidgets(this@TailscaledService)
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
        val taildriveEnabled = profilePrefs.getBoolean("taildrive_enabled", false)
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
            val intent = Intent(this, TunPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TunPermissionActivity", e)
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

    override fun onDestroy() {
        Appctr.stop()
        try { ByeDpiProxy.stop() } catch (e: Exception) {}
        byedpiProxyAddress = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
