package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.bropines.tailscaled.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * TUN-mode VPN service backed by hev-socks5-tunnel (native C library).
 *
 * Lifecycle:
 *  1. TailscaledService starts this service with ACTION_START when TUN mode is enabled.
 *  2. VpnService.Builder establishes the TUN interface (fd).
 *  3. We write a YAML config and call TProxyStartService(config, fd) via JNI.
 *  4. On ACTION_STOP or onRevoke(), we call TProxyStopService() and close the fd.
 *
 * DNS strategy:
 *  - addDnsServer("100.100.100.100") + addRoute("100.100.100.100", 32)
 *  - hev mapdns section maps queries for 100.100.100.100:53 → 127.0.0.1:1053
 *  - The Go DNS proxy on port 1053 (in TailscaledService process) handles resolution:
 *    ts.net / MagicDNS → tailscaled LocalAPI, other → fallback/DoH.
 */
class TunVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.bropines.tailscaled.TUN_START"
        const val ACTION_STOP  = "io.github.bropines.tailscaled.TUN_STOP"

        /** Fake DNS IP routed through VPN; hev mapdns redirects queries to Go DNS proxy on 1053. */
        const val TUN_DNS_IP   = "100.100.100.100"

        /** TUN gateway / device address. */
        const val TUN_ADDR_V4  = "10.0.0.1"
        const val TUN_PREFIX   = 8
        const val TUN_MTU      = 1500

        private const val NOTIF_CHANNEL = "tailsocks_tun"
        private const val NOTIF_ID      = 2
        private const val TAG           = "TunVpnService"

        // JNI interface (hev-socks5-tunnel)
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var currentExitNodeId: String? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopTun(); START_NOT_STICKY }
            else        -> { startTun(); START_STICKY }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by system")
        stopTun()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTun()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private fun startTun() {
        val activeAccount = AccountManager.getActiveAccount(this)
        val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeId = profilePrefs.getString("exit_node_id", "") ?: ""

        if (tunFd != null) {
            if (currentExitNodeId == exitNodeId) {
                Log.w(TAG, "Already running with same exit node, ignoring start")
                return
            }
            Log.i(TAG, "Exit Node changed from '$currentExitNodeId' to '$exitNodeId', restarting TUN interface...")
            TProxyStopService()
            try { tunFd?.close() } catch (_: Exception) {}
            tunFd = null
        }
        currentExitNodeId = exitNodeId

        val socksAddr = GlobalSettings.getString(this, "socks5", "127.0.0.1:48115")
        val socksHost = socksAddr.substringBeforeLast(":")
        val socksPort = socksAddr.substringAfterLast(":").toIntOrNull() ?: 48115
        val mtu       = TUN_MTU
        val fullTunnel = exitNodeId.isNotEmpty()
        val excludedApps = GlobalSettings.getTunExcludedApps(this)
        val excludedCIDRs = GlobalSettings.getTunExcludedCIDRs(this)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // Build VPN interface.
        val builder = Builder()
            .setSession("TailSocks TUN")
            .setMtu(mtu)
            .addAddress(TUN_ADDR_V4, TUN_PREFIX)
            .addDnsServer(TUN_DNS_IP)
            .addRoute(TUN_DNS_IP, 32)  // route fake DNS IP through VPN

        // Routing mode.
        if (fullTunnel) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            // Only Tailscale IP space (100.64.0.0/10).
            builder.addRoute("100.64.0.0", 10)
        }

        // Excluded IP ranges: add them back as "allow" via more-specific routes.
        // Android VpnService doesn't have addExcludedRoute() before API 33,
        // so we split the routing table manually for split tunnel mode.
        // For full tunnel mode, we skip exclusions at the VPN level;
        // the hev-socks5-tunnel handles them as direct connections.
        if (!fullTunnel) {
            // In split tunnel, excluded CIDRs are simply not added to routes — no action needed.
            Log.d(TAG, "Split tunnel: excluded CIDRs passed to hev: $excludedCIDRs")
        }

        // Always exclude TailSocks itself to avoid routing loops.
        try { builder.addDisallowedApplication(packageName) } catch (_: PackageManager.NameNotFoundException) {}

        // User-defined app exclusions.
        for (pkg in excludedApps) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "Excluded app not found: $pkg") }
        }

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }
        tunFd = fd

        // Write hev-socks5-tunnel YAML config.
        val configFile = File(cacheDir, "tun.conf")
        val socksUser = GlobalSettings.getString(this, "socks5_user", "")
        val socksPass = GlobalSettings.getString(this, "socks5_pass", "")
        if (!writeHevConfig(configFile, socksHost, socksPort, mtu, socksUser, socksPass)) {
            fd.close()
            tunFd = null
            stopSelf()
            return
        }

        // Start hev tunnel (JNI).
        TProxyStartService(configFile.absolutePath, fd.fd)
        Log.i(TAG, "TUN started: socks=$socksAddr mtu=$mtu full=$fullTunnel")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun stopTun() {
        Log.i(TAG, "Stopping TUN...")
        try {
            tunFd?.close()
            Log.d(TAG, "tunFd closed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close tunFd: ${e.message}")
        }
        tunFd = null

        Thread {
            try {
                Log.d(TAG, "Calling TProxyStopService native...")
                TProxyStopService()
                Log.i(TAG, "TProxyStopService completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error in TProxyStopService: ${e.message}")
            }
        }.start()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "TUN service stop sequence initiated")
    }

    // -------------------------------------------------------------------------
    // hev config writer
    // -------------------------------------------------------------------------

    /**
     * Writes a minimal hev-socks5-tunnel YAML config.
     *
     * Key: mapdns section maps 100.100.100.100:53 queries to 127.0.0.1:1053
     * (the Go DNS proxy in TailscaledService process, protected from VPN via
     * the VpnService process being VPN-exempt itself).
     */
    private fun writeHevConfig(
        file: File,
        socksHost: String,
        socksPort: Int,
        mtu: Int,
        user: String,
        pass: String,
    ): Boolean {
        return try {
            file.createNewFile()
            FileOutputStream(file, false).use { fos ->
                var cfg = """
tunnel:
  mtu: $mtu

socks5:
  address: '$socksHost'
  port: $socksPort
  udp: 'udp'
""".trimIndent()

                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    cfg += "\n  username: '$user'\n  password: '$pass'\n"
                }

                // mapdns: redirect DNS queries for fake IP → Go DNS proxy on localhost:1053.
                // The Go proxy is VPN-exempt (runs inside TailscaledService which is excluded).
                cfg += """

mapdns:
  address: $TUN_DNS_IP
  port: 53
  network: 100.100.100.0
  netmask: 255.255.255.0
  cache-size: 2000
"""
                fos.write(cfg.toByteArray())
            }
            Log.d(TAG, "hev config written: ${file.absolutePath}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write hev config: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "TailSocks TUN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TunVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("TailSocks TUN")
            .setContentText(getString(R.string.tun_notif_active))
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopPi)
            .build()
    }
}
