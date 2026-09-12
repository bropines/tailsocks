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
import appctr.Appctr
import io.github.bropines.tailscaled.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

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
        const val TUN_ADDR_V6  = "fd00::1"
        const val TUN_PREFIX_V6 = 64
        const val TUN_MTU      = 1500

        /** Native engine: tailscaled owns the device; Tailscale's default TUN MTU. */
        const val TUN_MTU_NATIVE = 1280
        const val TUN_DNS_IP_V6  = "fd7a:115c:a1e0::53"
        const val ENGINE_HEV     = "hev"
        const val ENGINE_NATIVE  = "native"

        private const val NOTIF_CHANNEL = "tailsocks_tun"
        internal const val NOTIF_ID     = 2
        private const val TAG           = "TunVpnService"

        /**
         * True while the hev tunnel is up. Lets TailscaledService skip
         * ACTION_STOP when there is nothing to stop: starting this VpnService
         * just to stop it again posted a foreground notification that stayed
         * behind, and did that on every stop for users who never use TUN.
         */
        @Volatile var isRunning = false
            private set

        private const val SELF_IPS_PREF = "native_tun_self_ips"

        /** The node's addresses from the last native start, per profile; lets the VPN come up before the daemon. */
        fun cachedSelfIps(context: Context): List<String> {
            val account = AccountManager.getActiveAccount(context)
            return context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
                .getString(SELF_IPS_PREF, "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        // JNI interface (hev-socks5-tunnel)
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray

        var nativeLoaded = false
            private set

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                nativeLoaded = true
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError when the .so is missing, but also
                // NoSuchMethodError when RegisterNatives in JNI_OnLoad cannot
                // find one of the Kotlin externals (an R8 keep-rule mismatch).
                // Either way TUN mode is unavailable; the whole app must not
                // die just because this class was touched during a stop.
                Log.e(TAG, "libhev-socks5-tunnel failed to load, TUN mode unavailable: $e")
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var tunFd: ParcelFileDescriptor? = null
    private var currentExitNodeId: String? = null
    /** Whether the device carries the default route; the only exit-node fact the Builder knows. */
    private var currentFullTunnel: Boolean? = null
    private var currentEngine: String? = null
    /** Everything else the Builder was given (exclusions, IPv6, address); a change means a rebuild. */
    private var currentBuilderInputs: String? = null

    /** The Builder's inputs beyond engine and default route, as one comparable string. */
    private fun builderInputs(): String = listOf(
        GlobalSettings.getTunExcludedApps(this).sorted().joinToString(","),
        GlobalSettings.getTunExcludedCIDRs(this),
        GlobalSettings.isTunIpv6Enabled(this).toString(),
        GlobalSettings.getTunAddress(this)
    ).joinToString("|")
    /** Addresses the native device was established with; a restart reuses them, the daemon is down then. */
    private var nativeSelfIps: List<String> = emptyList()

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // One card for the whole app: this service goes foreground on the main
        // service's notification id, so the two do not stack. On stop it detaches
        // instead of removing, leaving the card to the main service.
        val notification = TailscaledService.buildStatusNotification(this, TailscaledService.statusText(this, "Active"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TailscaledService.MAIN_NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(TailscaledService.MAIN_NOTIF_ID, notification)
        }
        // Drop the separate TUN card older builds left behind.
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)

        val action = intent?.action

        if (!nativeLoaded) {
            Log.w(TAG, "Native library not loaded, cannot process action=$action. Stopping.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        executor.submit {
            try {
                if (action == ACTION_STOP) {
                    stopTunInternal()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        stopSelf()
                    }
                } else {
                    startTunInternal()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing action $action in background", e)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by system")
        executor.submit {
            stopTunInternal()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                stopSelf()
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        executor.submit {
            stopTunInternal()
        }
        executor.shutdown()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Start / Stop Internal (Runs on executor thread)
    // -------------------------------------------------------------------------

    private fun startTunInternal() {
        val activeAccount = AccountManager.getActiveAccount(this)
        val profilePrefs = getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeId = profilePrefs.getString("exit_node_id", "") ?: ""

        val engine = GlobalSettings.getTunEngine(this)
        val wantFullTunnel = exitNodeId.isNotEmpty()
        val wantInputs = builderInputs()
        var knownIps = nativeSelfIps
        if (tunFd != null) {
            // Android fixes a VPN's routes at establish(): the device must be
            // rebuilt when the default route comes or goes (exit node on/off), when
            // the engine changes, or when the daemon's netmap disagrees with the
            // addresses the device was established from. Switching from one exit
            // node to another changes none of that — the daemon re-points WireGuard
            // itself, the tunnel stays up.
            val liveIps = if (currentEngine == ENGINE_NATIVE) selfIpsNow() else emptyList()
            val ipsChanged = liveIps.isNotEmpty() && liveIps.toSet() != nativeSelfIps.toSet()
            val inputsChanged = currentBuilderInputs != wantInputs
            if (currentFullTunnel == wantFullTunnel && currentEngine == engine && !ipsChanged && !inputsChanged) {
                if (currentExitNodeId != exitNodeId) Log.i(TAG, "Exit node '$currentExitNodeId' -> '$exitNodeId': routes unchanged, keeping the device")
                currentExitNodeId = exitNodeId
                return
            }
            if (ipsChanged) knownIps = liveIps
            Log.i(TAG, "TUN parameters changed (default route $currentFullTunnel -> $wantFullTunnel, engine '$currentEngine' -> '$engine', addresses ${if (ipsChanged) "changed" else "same"}, exclusions/IPv6/address ${if (inputsChanged) "changed" else "same"}), restarting TUN interface...")
            stopTunInternal(nativeStartFollows = engine == ENGINE_NATIVE)
        }
        currentExitNodeId = exitNodeId
        currentFullTunnel = wantFullTunnel
        currentEngine = engine
        currentBuilderInputs = wantInputs

        if (engine == ENGINE_NATIVE) {
            if (!startNativeTunInternal(exitNodeId, knownIps)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
            }
            return
        }

        val socksAddr = GlobalSettings.getString(this, "socks5", "127.0.0.1:48115")
        // The tunnel dials the proxy locally; a wildcard bind is reached via loopback.
        val socksHost = NetAddr.dialableHost(socksAddr)
        val socksPort = NetAddr.port(socksAddr) ?: 48115
        val mtu       = TUN_MTU
        val fullTunnel = exitNodeId.isNotEmpty()
        val excludedApps = GlobalSettings.getTunExcludedApps(this)
        val excludedCIDRs = GlobalSettings.getTunExcludedCIDRs(this)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val tunAddrRaw = GlobalSettings.getTunAddress(this)
        var tunIp = TUN_ADDR_V4
        var tunPrefix = TUN_PREFIX
        try {
            if (tunAddrRaw.contains("/")) {
                val parts = tunAddrRaw.split("/")
                if (parts.size == 2) {
                    tunIp = parts[0].trim()
                    tunPrefix = parts[1].trim().toIntOrNull() ?: TUN_PREFIX
                }
            } else if (tunAddrRaw.isNotEmpty()) {
                tunIp = tunAddrRaw.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom TUN address: $tunAddrRaw, using default", e)
        }

        // The tailnet's own IPv6 always rides the tunnel: the daemon carries it to
        // peers and it works. The world's IPv6 is a different promise — handing
        // Android a global route we cannot serve makes every AAAA-capable site
        // hang until it times out instead of quietly falling back to IPv4, which
        // is what happened on WSA behind an exit node. So ::/0 goes in only when
        // the user asks for it.
        val ipv6Enabled = true
        val ipv6DefaultRoute = GlobalSettings.isTunIpv6Enabled(this)

        // Build VPN interface.
        val builder = Builder()
            .setSession("TailSocks TUN")
            .setMtu(mtu)
            .addAddress(tunIp, tunPrefix)
        
        if (ipv6Enabled) {
            builder.addAddress(TUN_ADDR_V6, TUN_PREFIX_V6)
        }

        builder.addDnsServer(TUN_DNS_IP)
            .addRoute(TUN_DNS_IP, 32)  // route fake DNS IP through VPN

        // Routing mode.
        if (fullTunnel) {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6DefaultRoute) {
                builder.addRoute("::", 0)
            }
            // Tailnet IPv6 still goes through us even when the world's does not.
            builder.addRoute("fd7a:115c:a1e0::", 48)
        } else {
            // Tailscale IPv4 space
            builder.addRoute("100.64.0.0", 10)
            if (ipv6Enabled) {
                // Tailscale IPv6 space
                builder.addRoute("fd7a:115c:a1e0::", 48)
            }
        }

        applyAppExclusions(builder, excludedApps)
        applyRouteExclusions(builder, excludedCIDRs, fullTunnel)

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                stopSelf()
            }
            return
        }
        tunFd = fd

        // Write hev-socks5-tunnel YAML config.
        val configFile = File(cacheDir, "tun.conf")
        val socksUser = GlobalSettings.getString(this, "socks5_user", "")
        val socksPass = GlobalSettings.getString(this, "socks5_pass", "")
        if (!writeHevConfig(configFile, socksHost, socksPort, mtu, socksUser, socksPass)) {
            try { fd.close() } catch (_: Exception) {}
            tunFd = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                stopSelf()
            }
            return
        }

        // Native-TUN experiment 1: can a child process of ours ioctl the VPN fd?
        // A duplicate goes to a short-lived tailscaled that reports the interface
        // name and MTU; hev gets the original below, so traffic is untouched.
        try {
            val dup = fd.dup()
            val report = Appctr.probeTunFd(dup.detachFd())
            Log.i(TAG, "TUN fd probe:\n$report")
        } catch (e: Exception) {
            Log.w(TAG, "TUN fd probe failed: $e")
        }

        // Start hev tunnel (JNI).
        Log.i(TAG, "Calling TProxyStartService JNI...")
        TProxyStartService(configFile.absolutePath, fd.fd)
        isRunning = true
        Log.i(TAG, "TUN started: socks=$socksAddr mtu=$mtu full=$fullTunnel")
    }

    /** Our own packages always bypass the tunnel (routing loop), then the user's list. */
    private fun applyAppExclusions(builder: Builder, excludedApps: Set<String>) {
        // Always exclude all TailSocks packages to avoid routing loops.
        val pm = packageManager
        try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
                if (info.packageName.startsWith("io.github.bropines.tailscaled")) {
                    try {
                        builder.addDisallowedApplication(info.packageName)
                        Log.i(TAG, "Automatically excluded TailSocks package: ${info.packageName}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to exclude package ${info.packageName}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query installed apps for auto-exclusion: ${e.message}")
            // Fallback to current packageName
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }

        // User-defined app exclusions.
        for (pkg in excludedApps) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "Excluded app not found: $pkg") }
        }
    }

    /**
     * The "Excluded IP ranges" setting: subnets that keep going out through the
     * real network. Android can carve them out of the tunnel only since 13
     * (Builder.excludeRoute), and only when a route covers them — with the
     * default route behind an exit node. Without an exit node the tunnel only
     * takes the tailnet ranges, so private subnets already bypass it.
     */
    private fun applyRouteExclusions(builder: Builder, cidrs: List<String>, fullTunnel: Boolean) {
        if (cidrs.isEmpty() || !fullTunnel) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(TAG, "Excluded IP ranges need Android 13+, ignoring: $cidrs")
            return
        }
        for (cidr in cidrs) {
            try {
                val (addr, len) = cidr.split("/").let {
                    android.net.InetAddresses.parseNumericAddress(it[0].trim()) to
                        (it.getOrNull(1)?.trim()?.toInt() ?: if (it[0].contains(':')) 128 else 32)
                }
                builder.excludeRoute(android.net.IpPrefix(addr, len))
                Log.i(TAG, "Excluded IP range from the tunnel: $cidr")
            } catch (e: Exception) {
                Log.w(TAG, "Excluded IP range ignored: $cidr (${e.message})")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Native engine: tailscaled owns the device
    // -------------------------------------------------------------------------

    /**
     * Establishes the VPN with what the daemon's router would otherwise install
     * — the node's own addresses, the tailnet ranges, the default route behind
     * an exit node, MagicDNS at 100.100.100.100 — and hands a duplicate of the
     * fd to the bridge, which relaunches tailscaled on it (--tun=android-vpn).
     * hev is not started. Returns false when nothing was established.
     */
    private fun startNativeTunInternal(exitNodeId: String, knownIps: List<String>): Boolean {
        val selfIps = knownIps.ifEmpty { waitForSelfIps() }
        if (selfIps.isEmpty()) {
            Log.e(TAG, "Native TUN: the node's addresses are unknown (no netmap yet), not establishing")
            Appctr.logAndroid("ERROR", "CORE", "Native TUN: node addresses unknown, the daemon has no netmap yet")
            return false
        }
        val fullTunnel = exitNodeId.isNotEmpty()
        val ipv6DefaultRoute = GlobalSettings.isTunIpv6Enabled(this)
        val hasV6 = selfIps.any { ':' in it }

        val builder = Builder()
            .setSession("TailSocks TUN (native)")
            .setMtu(TUN_MTU_NATIVE)
        for (ip in selfIps) {
            builder.addAddress(ip, if (':' in ip) 128 else 32)
        }
        builder.addDnsServer(TUN_DNS_IP)
        if (hasV6) builder.addDnsServer(TUN_DNS_IP_V6)
        if (fullTunnel) {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6DefaultRoute) builder.addRoute("::", 0)
            builder.addRoute("fd7a:115c:a1e0::", 48)
        } else {
            builder.addRoute("100.64.0.0", 10)
            builder.addRoute("fd7a:115c:a1e0::", 48)
        }
        applyAppExclusions(builder, GlobalSettings.getTunExcludedApps(this))
        applyRouteExclusions(builder, GlobalSettings.getTunExcludedCIDRs(this)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }, fullTunnel)

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "Native TUN: establish() returned null (another VPN active?)")
            return false
        }
        tunFd = fd
        val err = try {
            val dup = fd.dup()
            Appctr.setNativeTun(dup.detachFd())
        } catch (e: Exception) {
            "hand-over failed: $e"
        }
        if (err.isNotEmpty()) {
            Log.e(TAG, "Native TUN: the bridge refused the device: $err")
            Appctr.logAndroid("ERROR", "CORE", "Native TUN: $err")
            try { fd.close() } catch (_: Exception) {}
            tunFd = null
            return false
        }
        isRunning = true
        nativeSelfIps = selfIps
        val account = AccountManager.getActiveAccount(this)
        getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE).edit()
            .putString(SELF_IPS_PREF, selfIps.joinToString(",")).apply()
        Log.i(TAG, "Native TUN started: addrs=$selfIps full=$fullTunnel mtu=$TUN_MTU_NATIVE")
        return true
    }

    /** What the bridge knows right now: the bus snapshot, LocalAPI, or its last-known copy. */
    private fun selfIpsNow(): List<String> =
        (try { Appctr.getSelfIPs() } catch (e: Exception) { "" })
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * With a daemon up, the netmap brings the addresses within moments — wait for
     * it. Without one (the VPN is being established before the first daemon
     * start) fall back to the addresses cached from the last native run.
     */
    private fun waitForSelfIps(timeoutMs: Long = 8000L): List<String> {
        val daemonUp = try { Appctr.isRunning() } catch (e: Exception) { false }
        if (!daemonUp) {
            val ips = selfIpsNow().ifEmpty { cachedSelfIps(this) }
            if (ips.isNotEmpty()) Log.i(TAG, "Native TUN: no daemon yet, using the cached addresses $ips")
            return ips
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ips = selfIpsNow()
            if (ips.isNotEmpty() || System.currentTimeMillis() >= deadline) return ips
            try { Thread.sleep(250) } catch (_: InterruptedException) { return emptyList() }
        }
    }

    /**
     * nativeStartFollows: this stop is the first half of a restart into the
     * native engine. The daemon is then stopped once and started once, on the
     * new device, by the SetNativeTun that follows — instead of a relaunch in
     * userspace mode in between and a second one right after.
     */
    private fun stopTunInternal(nativeStartFollows: Boolean = false) {
        Log.i(TAG, "Stopping TUN...")
        isRunning = false

        // Stop the native tunnel first, then close the fd. The lwip task keeps
        // read()/write()ing the raw fd number until TProxyStopService joins its
        // thread; closing the fd before that frees the number for immediate reuse
        // by another thread (the Go daemon opens sockets constantly), so the
        // tunnel would read and write an unrelated socket.
        if (currentEngine == ENGINE_NATIVE) {
            // tailscaled holds its own copy of the device. For a restart into
            // the native engine the bridge only stops the daemon; otherwise it
            // relaunches it in userspace mode unless the whole connection is
            // going down.
            try {
                if (nativeStartFollows) Appctr.releaseNativeTunForSwap()
                else Appctr.clearNativeTun(ProxyState.isUserLetRunning(this))
            } catch (e: Exception) {
                Log.w(TAG, "Native TUN: releasing the device failed: $e")
            }
        } else if (nativeLoaded) {
            try {
                Log.d(TAG, "Calling TProxyStopService JNI...")
                TProxyStopService()
                Log.i(TAG, "TProxyStopService completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error in TProxyStopService: ${e.message}")
            }
        }

        try {
            tunFd?.close()
            Log.d(TAG, "tunFd closed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close tunFd: ${e.message}")
        }
        tunFd = null

        stopForeground(STOP_FOREGROUND_DETACH)
        Log.i(TAG, "TUN service stop sequence completed")
    }

    // -------------------------------------------------------------------------
    // hev config writer
    // -------------------------------------------------------------------------

    /**
     * Writes a minimal hev-socks5-tunnel YAML config.
     * We exclude mapdns section so DNS queries on 100.100.100.100:53 are routed 
     * natively through SOCKS5 UDP association to Tailscale daemon, returning 
     * real IPs and supporting Split Tunnel seamlessly.
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
