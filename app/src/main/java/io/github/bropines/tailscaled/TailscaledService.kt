package io.github.bropines.tailscaled

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import appctr.Appctr
import appctr.Closer
import appctr.StartOptions

class TailscaledService : Service() {
    private val TAG = "TailscaledService"
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network Available")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (Appctr.isRunning()) {
                    updateNotification("Active")
                }
            }, 1500)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network Lost")
            if (Appctr.isRunning()) {
                updateNotification("Waiting for network...")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        try {
            android.system.Os.setenv("TZ", java.util.TimeZone.getDefault().id, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set TZ env", e)
        }

        prefs = getSharedPreferences("appctr", Context.MODE_PRIVATE)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::WakeLock").apply {
            acquire(10*60*1000L) 
        }

        if (ProxyState.isUserLetRunning(this) && !Appctr.isRunning()) {
             if (prefs.getBoolean("force_bg", false)) {
                 startTailscale()
             } else {
                 ProxyState.setUserState(this, false)
             }
        }
        
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_ACTION") {
            stopMe()
            return START_NOT_STICKY
        }

        ProxyState.setUserState(this, true)
        updateTile()
        
        if (!Appctr.isRunning()) {
            startForeground(1, buildNotification("Starting..."))
            startTailscale()
        } else {
            updateNotification("Active")
        }
        
        return START_STICKY
    }

    private fun startTailscale() {
        val argsBuilder = StringBuilder()

        val hostname = prefs.getString("hostname", "")
        if (!hostname.isNullOrEmpty()) argsBuilder.append("--hostname=$hostname ")

        val loginServer = prefs.getString("login_server", "")
        if (!loginServer.isNullOrEmpty()) argsBuilder.append("--login-server=$loginServer ")

        if (prefs.getBoolean("accept_routes", false)) argsBuilder.append("--accept-routes ")
        if (!prefs.getBoolean("accept_dns", true)) argsBuilder.append("--accept-dns=false ")

        val exitNodeIp = prefs.getString("exit_node_ip", "")
        if (!exitNodeIp.isNullOrEmpty()) {
            argsBuilder.append("--exit-node=$exitNodeIp ")
            if (prefs.getBoolean("exit_node_allow_lan", false)) {
                argsBuilder.append("--exit-node-allow-lan-access ")
            }
        }

        if (prefs.getBoolean("advertise_exit_node", false)) {
            argsBuilder.append("--advertise-exit-node ")
        }

        val rawArgs = prefs.getString("extra_args_raw", "")
        if (!rawArgs.isNullOrEmpty()) argsBuilder.append("$rawArgs")

        val options = StartOptions().apply {
            socks5Server = prefs.getString("socks5", "127.0.0.1:1055")
            httpProxy = prefs.getString("httpproxy", "127.0.0.1:1057") // Настраиваемый HTTP порт
            sshServer = prefs.getString("sshserver", "127.0.0.1:1056")
            authKey = prefs.getString("authkey", "")
            extraUpArgs = argsBuilder.toString()
            execPath = "${applicationInfo.nativeLibraryDir}/libtailscaled.so"
            socketPath = "${applicationInfo.dataDir}/tailscaled.sock"
            statePath = "${applicationInfo.dataDir}/state"
            closeCallBack = Closer { stopMe() }
        }

        Thread {
            try {
                applicationContext.sendBroadcast(Intent("STARTING"))
                updateNotification("Starting daemon...")

                try {
                    val process = Runtime.getRuntime().exec("killall tailscaled")
                    process.waitFor()
                    Log.d(TAG, "Killall tailscaled executed")
                } catch (e: Exception) {
                    Log.w(TAG, "killall failed (maybe no processes found)")
                }

                Thread.sleep(1000)

                Appctr.start(options)
                
                updateNotification("Active")
                applicationContext.sendBroadcast(Intent("START"))
            } catch (e: Exception) {
                Log.e(TAG, "Start error: ${e.message}")
                stopMe()
            }
        }.start()
    }

    private fun stopMe() {
        ProxyState.setUserState(this, false)
        Appctr.stop()
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        updateTile()
        applicationContext.sendBroadcast(Intent("STOP"))
    }
    
    private fun updateTile() {
        TileService.requestListeningState(this, ComponentName(this, ProxyTileService::class.java))
    }

    private fun updateNotification(status: String) {
        notificationManager.notify(1, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val channelId = "tailscaled_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Tailscale Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TailscaledService::class.java).apply { action = "STOP_ACTION" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TailSocks")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_secure) 
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {}
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}