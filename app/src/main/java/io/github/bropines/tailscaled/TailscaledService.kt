package io.github.bropines.tailscaled

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
    private val TAG = "TailscaledService"
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val activeAccount = AccountManager.getActiveAccount(this@TailscaledService)
            val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            
            if (Appctr.isRunning()) {
                val exitNodeIp = profilePrefs.getString("exit_node_ip", "")
                if (!exitNodeIp.isNullOrEmpty()) {
                    try {
                        val pJson = Appctr.getStatusFromAPI()
                        if (!pJson.startsWith("Error") && pJson.contains("\"Self\"")) {
                            val status = Gson().fromJson(pJson, StatusResponse::class.java)
                            var found = false
                            if (status.self?.tailscaleIPs?.contains(exitNodeIp) == true) found = true
                            status.peers?.values?.forEach { peer ->
                                if (peer.tailscaleIPs?.contains(exitNodeIp) == true) found = true
                            }
                            
                            if (!found && (status.self != null || !status.peers.isNullOrEmpty())) {
                                Log.w(TAG, "Exit node $exitNodeIp not found in netmap. Auto-clearing it.")
                                profilePrefs.edit().remove("exit_node_ip").apply()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(this@TailscaledService, "Invalid Exit Node ($exitNodeIp) was automatically disabled.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            
            val interval = profilePrefs.getString("refresh_interval", "15000")?.toLongOrNull() ?: 15000L
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
        
        if (action == "STOP_ACTION") { stopMe(); return START_NOT_STICKY }
        if (action == "REFRESH_ACTION" || action == "APPLY_SETTINGS") {
            Thread { Appctr.applySettings(buildStartOptions()) }.start()
            return START_STICKY
        }
        if (action == "RESTART_ACTION") {
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
            startForeground(1, buildNotification("Starting..."))
            startTailscale()
        } else updateNotification("Active")
        
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

        return StartOptions().apply {
            socks5Server = GlobalSettings.getString(this@TailscaledService, "socks5", "127.0.0.1:48115")
            socks5User   = GlobalSettings.getString(this@TailscaledService, "socks5_user", "")
            socks5Pass   = GlobalSettings.getString(this@TailscaledService, "socks5_pass", "")
            httpProxy    = GlobalSettings.getString(this@TailscaledService, "httpproxy", "")
            controlProxy = GlobalSettings.getControlProxyUrl(this@TailscaledService)
            dnsProxy     = GlobalSettings.getString(this@TailscaledService, "dns_proxy", "127.0.0.1:1053")
            dnsFallbacks = GlobalSettings.getString(this@TailscaledService, "dns_fallbacks", "8.8.8.8:53,1.1.1.1:53")
            dohFallback  = GlobalSettings.getString(this@TailscaledService, "doh_url", "https://1.1.1.1/dns-query")
            
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

            val argsBuilder = StringBuilder()
            val hostname = profilePrefs.getString("hostname", "")
            if (!hostname.isNullOrEmpty()) argsBuilder.append("--hostname=$hostname ")
            
            val loginServer = profilePrefs.getString("login_server", "")
            if (!loginServer.isNullOrEmpty()) argsBuilder.append("--login-server=$loginServer ")
            
            if (GlobalSettings.getBoolean(this@TailscaledService, "accept_routes", false)) {
                argsBuilder.append("--accept-routes=true ")
            } else {
                argsBuilder.append("--accept-routes=false ")
            }
            
            if (GlobalSettings.getBoolean(this@TailscaledService, "accept_dns", true)) {
                argsBuilder.append("--accept-dns=true ")
            } else {
                argsBuilder.append("--accept-dns=false ")
            }
            
            val exitNodeIp = profilePrefs.getString("exit_node_ip", "")
            if (!exitNodeIp.isNullOrEmpty()) {
                argsBuilder.append("--exit-node=$exitNodeIp ")
                if (profilePrefs.getBoolean("exit_node_allow_lan", false)) {
                    argsBuilder.append("--exit-node-allow-lan-access=true ")
                } else {
                    argsBuilder.append("--exit-node-allow-lan-access=false ")
                }
            } else {
                argsBuilder.append("--exit-node= ")
            }
            
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
        ProxyState.setUserState(this, false)
        refreshHandler.removeCallbacks(refreshRunnable)
        Appctr.stop()
        try { Runtime.getRuntime().exec("killall tailscaled") } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        updateTile()
        applicationContext.sendBroadcast(Intent("STOP"))
    }
    
    private fun updateTile() = TileService.requestListeningState(this, ComponentName(this, ProxyTileService::class.java))
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

    override fun onDestroy() {
        Appctr.stop()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
