package io.github.bropines.tailscaled.core

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import appctr.Appctr
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * TUN mode: the Android VpnService behind "VPN (TUN)", with two engines.
 *
 * Native (the default, [TunEngines]): the Builder gets what the daemon's router
 * would install — the node's own addresses, the tailnet ranges, accepted subnet
 * routes, the default route behind an exit node, MagicDNS — and a duplicate of
 * the fd goes to the bridge, which relaunches tailscaled with
 * `--tun=android-vpn` on it (patches 17–19). The daemon reads and writes the
 * device itself; DNS, split DNS and the exit node are its own.
 *
 * hev (the compatibility engine): the Builder gets a private gateway address and
 * the same routes, hev-socks5-tunnel reads the device and pushes every packet
 * into the daemon's SOCKS5 port, DNS at 100.100.100.100 included.
 *
 * Lifecycle:
 *  1. TailscaledService sends ACTION_START once the daemon is up (or before its
 *     first launch, on the native engine with cached addresses); the system
 *     sends [VpnService.SERVICE_INTERFACE] when TailSocks is the always-on VPN.
 *  2. Android fixes a VPN's routes at establish(), so a change in what the
 *     device must route rebuilds it; on the native engine that relaunches the
 *     daemon. [rebuildReason] is how the main service's tick notices changes
 *     that come from the tailnet rather than from a setting.
 *  3. ACTION_STOP or onRevoke() takes the device down.
 */
class TunVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.bropines.tailscaled.TUN_START"
        const val ACTION_STOP  = "io.github.bropines.tailscaled.TUN_STOP"

        /** MagicDNS; hev carries it to the daemon over SOCKS5 UDP, the native engine answers it itself. */
        const val TUN_DNS_IP   = "100.100.100.100"

        /** hev's gateway / device address. */
        const val TUN_ADDR_V4  = "10.0.0.1"
        const val TUN_PREFIX   = 8
        const val TUN_ADDR_V6  = "fd00::1"
        const val TUN_PREFIX_V6 = 64
        const val TUN_MTU      = 1500

        /** Native engine: tailscaled owns the device; Tailscale's default TUN MTU. */
        const val TUN_MTU_NATIVE = 1280
        const val TUN_DNS_IP_V6  = "fd7a:115c:a1e0::53"
        const val ENGINE_HEV     = TunEngines.HEV
        const val ENGINE_NATIVE  = TunEngines.NATIVE

        internal const val NOTIF_ID     = 2
        private const val TAG           = "TunVpnService"

        /**
         * True while a device is up. Lets TailscaledService skip ACTION_STOP
         * when there is nothing to stop: starting this VpnService just to stop
         * it again posted a foreground notification that stayed behind, and
         * did that on every stop for users who never use TUN.
         */
        @Volatile var isRunning = false
            private set

        /**
         * The native engine was asked to start before the node had addresses —
         * a first connect, a fresh login — and established nothing. The main
         * service's tick starts it again once the netmap is in; hev never
         * waits, it needs no addresses of the node.
         */
        @Volatile var waitingForAddresses = false
            private set

        // What the running device was built from, for [rebuildReason].
        @Volatile private var builtEngine: String? = null
        @Volatile private var builtSelfIps: List<String> = emptyList()
        @Volatile private var builtFullTunnel = false
        @Volatile private var builtExclusions: List<TunRoutes.Cidr> = emptyList()
        @Volatile private var builtRoutes = ""
        @Volatile private var lastRouteCheckAt = 0L
        @Volatile private var lastRouteRebuildAt = 0L

        /** A full LocalAPI status read; the tick asks for routes no more often than this. */
        private const val ROUTE_CHECK_INTERVAL_MS = 60_000L

        /**
         * At most one route-driven rebuild per this long. Each one relaunches the
         * daemon on the native engine, and an app connector can change its
         * advertised routes every few minutes. A settings change is not held
         * back by this: it sends its own start.
         */
        private const val ROUTE_REBUILD_MIN_GAP_MS = 5 * 60_000L

        private const val SELF_IPS_PREF = "native_tun_self_ips"
        private const val ROUTES_PREF = "tun_offered_routes"

        private fun profilePrefs(context: Context) =
            context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)

        /** The node's addresses from the last native start, per profile; lets the VPN come up before the daemon. */
        fun cachedSelfIps(context: Context): List<String> =
            profilePrefs(context).getString(SELF_IPS_PREF, "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        /** The subnet routes the tailnet offered when last asked, per profile; for a device built before the daemon answers. */
        private fun cachedOfferedRoutes(context: Context): List<TunRoutes.Cidr> =
            TunRoutes.decode(profilePrefs(context).getString(ROUTES_PREF, ""))

        private fun cacheOfferedRoutes(context: Context, routes: List<TunRoutes.Cidr>) {
            val encoded = TunRoutes.encode(routes)
            val prefs = profilePrefs(context)
            if (prefs.getString(ROUTES_PREF, "") != encoded) prefs.edit().putString(ROUTES_PREF, encoded).apply()
        }

        /**
         * Accept routes and use Tailscale DNS: the running daemon's prefs, which
         * the extra arguments may have changed, else the app's own settings.
         */
        private fun routePrefs(context: Context): Pair<Boolean, Boolean> {
            val live = if (Appctr.isRunning()) {
                runCatching { TunRoutes.daemonPrefs(Appctr.getPrefsJSON()) }.getOrNull()
            } else null
            return live ?: (GlobalSettings.getBoolean(context, "accept_routes", false) to
                GlobalSettings.getBoolean(context, "accept_dns", true))
        }

        /**
         * The subnet routes the tailnet offers this node, or none while it does
         * not accept them. Live from the daemon when it can answer, cached per
         * profile otherwise.
         */
        private fun offeredRoutesNow(context: Context, routeAll: Boolean): List<TunRoutes.Cidr> {
            if (!routeAll) return emptyList()
            val live = if (Appctr.isRunning()) {
                runCatching { TunRoutes.offeredRoutes(Appctr.getStatusFromAPI()) }.getOrNull()
            } else null
            if (live != null) {
                cacheOfferedRoutes(context, live)
                return live
            }
            return cachedOfferedRoutes(context)
        }

        /** "Excluded IP ranges" that Android will actually apply: behind an exit node, on 13+. */
        private fun appliedExclusions(context: Context, fullTunnel: Boolean): List<TunRoutes.Cidr> {
            if (!fullTunnel || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
            return GlobalSettings.getTunExcludedCIDRs(context).split(",").mapNotNull { TunRoutes.parse(it) }
        }

        /** What the bridge knows right now: the bus snapshot, LocalAPI, or its last-known copy. */
        private fun selfIpsNow(): List<String> =
            (try { Appctr.getSelfIPs() } catch (e: Exception) { "" })
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * Why the device should be built again now, or null. Called from the
         * main service's refresh tick while the backend is Running; the caller
         * then sends ACTION_START, and the start compares everything again.
         *
         *  - a native start that waited for the node's addresses can go ahead;
         *  - the node's addresses are no longer the ones the device carries (a
         *    new login on the same profile);
         *  - the tailnet's accepted subnet routes changed — a router came or
         *    went, the user accepted routes — throttled, see the constants.
         *
         * Never while the VPN permission is missing: the prompt belongs to a
         * start the user made, not to a background tick.
         */
        fun rebuildReason(context: Context): String? {
            if (!GlobalSettings.isTunModeEnabled(context) || GlobalSettings.isRootModeEnabled(context)) return null
            if (!ProxyState.isUserLetRunning(context)) return null
            if (runCatching { VpnService.prepare(context) }.getOrNull() != null) return null
            if (waitingForAddresses) {
                return if (selfIpsNow().isNotEmpty()) "the node's addresses are known now" else null
            }
            if (!isRunning) return null
            if (builtEngine == ENGINE_NATIVE) {
                val live = selfIpsNow()
                if (live.isNotEmpty() && live.toSet() != builtSelfIps.toSet()) return "the node's addresses changed"
            }
            val now = SystemClock.elapsedRealtime()
            if (lastRouteCheckAt != 0L && now - lastRouteCheckAt < ROUTE_CHECK_INTERVAL_MS) return null
            lastRouteCheckAt = now
            val routeAll = routePrefs(context).first
            if (!routeAll && builtRoutes.isEmpty()) return null
            val offered = if (routeAll) {
                runCatching { TunRoutes.offeredRoutes(Appctr.getStatusFromAPI()) }.getOrNull() ?: return null
            } else emptyList()
            cacheOfferedRoutes(context, offered)
            val want = TunRoutes.encode(TunRoutes.forDevice(offered, builtFullTunnel, builtExclusions))
            if (want == builtRoutes) return null
            if (lastRouteRebuildAt != 0L && now - lastRouteRebuildAt < ROUTE_REBUILD_MIN_GAP_MS) {
                Log.i(TAG, "Accepted subnet routes changed; rebuilding after the minimum gap")
                return null
            }
            lastRouteRebuildAt = now
            return "the accepted subnet routes changed ([$builtRoutes] -> [$want])"
        }

        // JNI interface (hev-socks5-tunnel)
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray

        /** Whether libhev-socks5-tunnel loaded. Only the hev engine needs it. */
        var hevLoaded = false
            private set

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                hevLoaded = true
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError when the .so is missing, but also
                // NoSuchMethodError when RegisterNatives in JNI_OnLoad cannot
                // find one of the Kotlin externals (an R8 keep-rule mismatch).
                // Either way the hev engine is unavailable; the native engine
                // does not use the library, and the whole app must not die just
                // because this class was touched during a stop.
                Log.e(TAG, "libhev-socks5-tunnel failed to load, the hev engine is unavailable: $e")
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var tunFd: ParcelFileDescriptor? = null
    private var currentExitNodeId: String? = null
    /** Whether the device carries the default route; the only exit-node fact the Builder knows. */
    private var currentFullTunnel: Boolean? = null
    private var currentEngine: String? = null
    /** Everything else the Builder was given (exclusions, IPv6, address, routes, DNS); a change means a rebuild. */
    private var currentBuilderInputs: String? = null
    /** Addresses the native device was established with; a restart reuses them, the daemon is down then. */
    private var nativeSelfIps: List<String> = emptyList()

    /** What a start has decided the device must look like. */
    private class Plan(
        val engine: String,
        val exitNodeId: String,
        val routes: List<TunRoutes.Cidr>,
        val exclusions: List<TunRoutes.Cidr>,
        /** Native only: whether the device's DNS is MagicDNS, see [startNativeTunInternal]. */
        val tailscaleDns: Boolean,
    ) {
        val fullTunnel get() = exitNodeId.isNotEmpty()
    }

    /** The Builder's inputs beyond engine and default route, as one comparable string. */
    private fun builderInputs(plan: Plan): String = listOf(
        GlobalSettings.getTunExcludedApps(this).sorted().joinToString(","),
        GlobalSettings.getTunExcludedCIDRs(this),
        GlobalSettings.isTunIpv6Enabled(this).toString(),
        GlobalSettings.getTunAddress(this),
        TunRoutes.encode(plan.routes),
        plan.tailscaleDns.toString()
    ).joinToString("|")

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

        if (action != ACTION_STOP) {
            val refusal = startRefusal(action)
            if (refusal != null) {
                Log.w(TAG, "Not establishing the tunnel (${action ?: "restart"}): $refusal")
                if (action == VpnService.SERVICE_INTERFACE) {
                    runCatching { Appctr.logAndroid("WARN", "CORE", "Always-on VPN start ignored: $refusal") }
                }
                // The card is the main service's whenever it runs.
                stopForeground(if (Appctr.isRunning()) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            if (action == VpnService.SERVICE_INTERFACE) startCoreForAlwaysOn()
        }

        executor.submit {
            try {
                if (action == ACTION_STOP) {
                    waitingForAddresses = false
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

    /**
     * Why a start must not establish anything, or null when it may.
     *
     * A start from inside the app or a sticky restart needs the user to want
     * the connection: picking an exit node or editing the excluded apps sends
     * ACTION_START whenever TUN mode is on, and with the connection stopped
     * that raised a tunnel no daemon served — behind an exit node, a device-wide
     * black hole. The always-on start is the system acting on the user's own
     * standing order, so it counts as one (see [startCoreForAlwaysOn]); a mode
     * other than TUN is still not switched behind the user's back.
     */
    private fun startRefusal(action: String?): String? {
        if (!GlobalSettings.isTunModeEnabled(this)) return "TUN mode is off (proxy mode)"
        if (GlobalSettings.isRootModeEnabled(this)) return "Root mode routes the device instead"
        if (GlobalSettings.getTunEngine(this) == ENGINE_HEV && !hevLoaded) return "the hev engine's library did not load"
        if (action == VpnService.SERVICE_INTERFACE) return null
        return if (ProxyState.isUserLetRunning(this)) null else "the connection is stopped"
    }

    /**
     * Always-on VPN: Android starts this service at boot and whenever the user
     * picks TailSocks as the always-on VPN, without the main service. A device
     * with no daemon behind it carries nothing — with "Block connections without
     * VPN" it is the only network the phone has — so the connection is started
     * as if the user had tapped Connect. Repeating START on a running service is
     * harmless; it only refreshes the card.
     */
    private fun startCoreForAlwaysOn() {
        Log.i(TAG, "Started as the always-on VPN; starting the connection")
        ProxyState.setUserState(this, true)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TailscaledService::class.java).apply { action = "START_ACTION" }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Always-on: starting the main service failed: ${e.message}")
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by system")
        executor.submit {
            waitingForAddresses = false
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
        val (routeAll, corpDns) = routePrefs(this)
        val exclusions = appliedExclusions(this, wantFullTunnel)
        val plan = Plan(
            engine = engine,
            exitNodeId = exitNodeId,
            routes = TunRoutes.forDevice(offeredRoutesNow(this, routeAll), wantFullTunnel, exclusions),
            exclusions = exclusions,
            tailscaleDns = engine != ENGINE_NATIVE || corpDns,
        )
        val wantInputs = builderInputs(plan)
        var knownIps = nativeSelfIps
        if (tunFd != null) {
            // Android fixes a VPN's routes at establish(): the device must be
            // rebuilt when the default route comes or goes (exit node on/off), when
            // the engine changes, when the routes or the DNS it was given change,
            // or when the daemon's netmap disagrees with the addresses the device
            // was established from. Switching from one exit node to another
            // changes none of that — the daemon re-points WireGuard itself, the
            // tunnel stays up.
            val liveIps = if (currentEngine == ENGINE_NATIVE) selfIpsNow() else emptyList()
            val ipsChanged = liveIps.isNotEmpty() && liveIps.toSet() != nativeSelfIps.toSet()
            val inputsChanged = currentBuilderInputs != wantInputs
            if (currentFullTunnel == wantFullTunnel && currentEngine == engine && !ipsChanged && !inputsChanged) {
                if (currentExitNodeId != exitNodeId) Log.i(TAG, "Exit node '$currentExitNodeId' -> '$exitNodeId': routes unchanged, keeping the device")
                currentExitNodeId = exitNodeId
                return
            }
            if (ipsChanged) knownIps = liveIps
            Log.i(TAG, "TUN parameters changed (default route $currentFullTunnel -> $wantFullTunnel, engine '$currentEngine' -> '$engine', addresses ${if (ipsChanged) "changed" else "same"}, exclusions/IPv6/address/routes/DNS ${if (inputsChanged) "changed" else "same"}), restarting TUN interface...")
            stopTunInternal(nativeStartFollows = engine == ENGINE_NATIVE)
        }
        currentExitNodeId = exitNodeId
        currentFullTunnel = wantFullTunnel
        currentEngine = engine
        currentBuilderInputs = wantInputs

        val started = if (engine == ENGINE_NATIVE) startNativeTunInternal(plan, knownIps) else startHevTunInternal(plan)
        if (!started) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
            return
        }
        builtEngine = engine
        builtSelfIps = if (engine == ENGINE_NATIVE) nativeSelfIps else emptyList()
        builtFullTunnel = wantFullTunnel
        builtExclusions = exclusions
        builtRoutes = TunRoutes.encode(plan.routes)
    }

    /** hev-socks5-tunnel on a device with a private gateway address. Returns false when nothing was established. */
    private fun startHevTunInternal(plan: Plan): Boolean {
        val socksAddr = GlobalSettings.getString(this, "socks5", "127.0.0.1:48115")
        // The tunnel dials the proxy locally; a wildcard bind is reached via loopback.
        val socksHost = NetAddr.dialableHost(socksAddr)
        val socksPort = NetAddr.port(socksAddr) ?: 48115
        val mtu       = TUN_MTU
        val fullTunnel = plan.fullTunnel
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
        applySubnetRoutes(builder, plan.routes)

        applyCommon(builder)
        applyAppExclusions(builder, excludedApps)
        applyRouteExclusions(builder, excludedCIDRs, fullTunnel)

        val fd = establishOrNull(builder) ?: return false
        tunFd = fd

        // Write hev-socks5-tunnel YAML config.
        val configFile = File(cacheDir, "tun.conf")
        val socksUser = GlobalSettings.getString(this, "socks5_user", "")
        val socksPass = GlobalSettings.getString(this, "socks5_pass", "")
        if (!writeHevConfig(configFile, socksHost, socksPort, mtu, socksUser, socksPass)) {
            try { fd.close() } catch (_: Exception) {}
            tunFd = null
            return false
        }

        // The probe from before the native engine existed: can a child process
        // of ours ioctl the VPN fd? A duplicate goes to a short-lived tailscaled
        // that reports the interface name and MTU; hev gets the original below,
        // so traffic is untouched. Kept on the compatibility engine because its
        // log line says whether the native engine could run on this device.
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
        waitingForAddresses = false
        Log.i(TAG, "TUN started (hev): socks=$socksAddr mtu=$mtu full=$fullTunnel subnets=${plan.routes.size}")
        return true
    }

    /**
     * What every device gets, whichever engine reads it.
     *
     * Not metered: a VPN of an app that targets Android 10+ is metered unless it
     * says otherwise, and with the device up every app on Wi-Fi would have been
     * told its network costs money — downloads held for "Wi-Fi only", sync
     * deferred. Unmetered here means "as metered as the networks underneath".
     */
    private fun applyCommon(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
    }

    /** establish(), with the refusals it can throw for a bad route or address turned into null. */
    private fun establishOrNull(builder: Builder): ParcelFileDescriptor? {
        val fd = try {
            builder.establish()
        } catch (e: RuntimeException) {
            Log.e(TAG, "establish() refused the device: $e")
            null
        }
        if (fd == null) Log.e(TAG, "Failed to establish VPN interface (no permission, or another VPN took it)")
        return fd
    }

    /**
     * Accepted subnet routes (see [TunRoutes]). An entry Android refuses is
     * skipped rather than costing the whole device.
     */
    private fun applySubnetRoutes(builder: Builder, routes: List<TunRoutes.Cidr>) {
        for (route in routes) {
            try {
                builder.addRoute(route.address(), route.bits)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Subnet route ignored: $route (${e.message})")
            }
        }
        if (routes.isNotEmpty()) Log.i(TAG, "Accepted subnet routes on the device: ${routes.joinToString(" ")}")
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
     * takes the tailnet ranges and accepted subnet routes, so private subnets
     * already bypass it. An accepted subnet route inside a range is more
     * specific and still goes to the tailnet.
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
     * — the node's own addresses, the tailnet ranges, accepted subnet routes,
     * the default route behind an exit node, MagicDNS at 100.100.100.100 — and
     * hands a duplicate of the fd to the bridge, which relaunches tailscaled on
     * it (--tun=android-vpn). hev is not started. Returns false when nothing was
     * established.
     *
     * DNS: with "Use Tailscale DNS" off the daemon's resolver forwards nothing
     * (no routes, so every name outside the tailnet is SERVFAIL), so the device
     * then names no DNS server at all and Android resolves through the
     * underlying network, the way it does for any VPN without DNS.
     */
    private fun startNativeTunInternal(plan: Plan, knownIps: List<String>): Boolean {
        val selfIps = knownIps.ifEmpty { waitForSelfIps() }
        if (selfIps.isEmpty()) {
            waitingForAddresses = true
            Log.w(TAG, "Native TUN: the node's addresses are unknown (no netmap yet); starting once it has them")
            Appctr.logAndroid("WARN", "CORE", "Native TUN: waiting for the node's addresses (not signed in, or no netmap yet)")
            return false
        }
        val fullTunnel = plan.fullTunnel
        val ipv6DefaultRoute = GlobalSettings.isTunIpv6Enabled(this)
        val hasV6 = selfIps.any { ':' in it }

        val builder = Builder()
            .setSession("TailSocks TUN (native)")
            .setMtu(TUN_MTU_NATIVE)
        for (ip in selfIps) {
            builder.addAddress(ip, if (':' in ip) 128 else 32)
        }
        if (plan.tailscaleDns) {
            builder.addDnsServer(TUN_DNS_IP)
            if (hasV6) builder.addDnsServer(TUN_DNS_IP_V6)
        }
        if (fullTunnel) {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6DefaultRoute) builder.addRoute("::", 0)
            builder.addRoute("fd7a:115c:a1e0::", 48)
        } else {
            builder.addRoute("100.64.0.0", 10)
            builder.addRoute("fd7a:115c:a1e0::", 48)
        }
        applySubnetRoutes(builder, plan.routes)
        applyCommon(builder)
        applyAppExclusions(builder, GlobalSettings.getTunExcludedApps(this))
        applyRouteExclusions(builder, GlobalSettings.getTunExcludedCIDRs(this)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }, fullTunnel)

        val fd = establishOrNull(builder) ?: return false
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
        waitingForAddresses = false
        nativeSelfIps = selfIps
        profilePrefs(this).edit().putString(SELF_IPS_PREF, selfIps.joinToString(",")).apply()
        Log.i(TAG, "Native TUN started: addrs=$selfIps full=$fullTunnel subnets=${plan.routes.size} dns=${plan.tailscaleDns} mtu=$TUN_MTU_NATIVE")
        return true
    }

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
        builtEngine = null
        builtSelfIps = emptyList()
        builtRoutes = ""

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
        } else if (hevLoaded && currentEngine == ENGINE_HEV && tunFd != null) {
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
}
