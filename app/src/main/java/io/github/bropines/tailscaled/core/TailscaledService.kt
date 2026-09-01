package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.app.*
import android.content.BroadcastReceiver
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

class TailscaledService : Service() {
    companion object {
        const val ACTION_APPLY_SETTINGS = "APPLY_SETTINGS"
        const val ACTION_STATUS_CHANGED = "io.github.bropines.tailscaled.STATUS_CHANGED"
        const val ALIAS_STATUS_CHANGED = "io.github.bropines.tailscaled.STATUS"

        /**
         * Asks the service to push current preferences to the daemon.
         *
         * Callers outside the service (widgets, automation) must not talk to the
         * bridge directly: in Root Mode the daemon can be alive while this process
         * is not attached to it, and only the service knows how to attach.
         */
        fun requestApplySettings(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TailscaledService::class.java).apply { action = ACTION_APPLY_SETTINGS }
                )
            } catch (e: Exception) {
                Log.w("TailscaledService", "Failed to request settings apply: ${e.message}")
            }
        }

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
    @Volatile private var rootRoutingApplied = false
    /** Consecutive non-Running ticks; routing is only torn down after a couple of
     *  them so a brief state flap does not thrash iptables through `su`. */
    @Volatile private var rootNotRunningTicks = 0
    /** Consecutive failed routing attempts; retrying forever through `su` is noise. */
    @Volatile private var rootRoutingFailures = 0
    private val maxRootRoutingAttempts = 3

    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var refreshTickRunning = false

    /** Delayed "network is back" notification refresh. Held as a named Runnable
     *  posted on refreshHandler so onDestroy can cancel it — a throwaway Handler's
     *  callback could otherwise fire after the service was torn down. */
    private val networkNotifyRunnable = Runnable {
        if (Appctr.isRunning()) updateNotification("Active")
    }

    /**
     * The tick queries the daemon over its socket, so it runs off the main
     * thread and only re-schedules itself once it is done.
     */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshTickRunning) return
            refreshTickRunning = true
            Thread {
                val interval = try {
                    runRefreshTick()
                } catch (e: Exception) {
                    Log.w(TAG, "Refresh tick failed: ${e.message}")
                    15000L
                } finally {
                    refreshTickRunning = false
                }
                refreshHandler.post {
                    if (!teardownStarted) {
                        refreshHandler.removeCallbacks(this)
                        refreshHandler.postDelayed(this, interval)
                    }
                }
            }.start()
        }
    }

    private fun runRefreshTick(): Long {
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
                rootNotRunningTicks = 0
                if (!rootRoutingApplied && rootRoutingFailures < maxRootRoutingAttempts) {
                    rootRoutingApplied = true
                    Log.i(TAG, "Daemon is Running. Applying Root tailscale0 routing.")
                    val dnsRedirect = GlobalSettings.getBoolean(this@TailscaledService, "accept_dns", true) &&
                        GlobalSettings.isRootDnsRedirectEnabled(this@TailscaledService)
                    val bypass = upstreamDnsAddresses()
                    Thread {
                        if (RootUtils.applyTailscale0Routing(dnsRedirect, bypass)) {
                            rootRoutingFailures = 0
                            // Persist the fact that system rules now exist, so they
                            // can be removed even if the app is killed or Root Mode
                            // is switched off before the next stop.
                            GlobalSettings.setRootRoutingInstalled(this@TailscaledService, true)
                        } else {
                            rootRoutingApplied = false
                            rootRoutingFailures++
                            if (rootRoutingFailures >= maxRootRoutingAttempts) {
                                Log.e(TAG, "Giving up on tailscale0 routing after $rootRoutingFailures attempts")
                                Appctr.logAndroid(
                                    "ERROR", "ROOT",
                                    "Routing setup failed $rootRoutingFailures times — giving up. " +
                                        "Use Settings → Root Mode → Check Routing for details."
                                )
                            }
                        }
                    }.start()
                }
                syncTailnetHosts()
            } else {
                if (rootRoutingApplied) {
                    rootNotRunningTicks++
                    if (rootNotRunningTicks >= 2) {
                        Log.i(TAG, "Daemon is not Running ($backendState). Cleaning up tailscale0 routing.")
                        rootRoutingApplied = false
                        rootNotRunningTicks = 0
                        Thread {
                            RootUtils.cleanupTailscale0Routing()
                            GlobalSettings.setRootRoutingInstalled(this@TailscaledService, false)
                        }.start()
                    }
                }
                if (isRunning && (backendState == "NeedsLogin" || backendState == "Starting" || backendState == "NoState")) {
                    interval = 2000L
                }
            }
        }

        if (checkConnectionHealth(isRunning, backendState)) {
            interval = 5000L
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (isRunning && powerManager.isInteractive) {
            updateAllWidgets(this@TailscaledService)
        }

        return interval
    }

    /**
     * Holds the CPU awake for as long as the connection is wanted.
     *
     * The setting is stored globally but used to be read from the per-profile
     * store, so the lock was never taken; and even when it was, it expired after
     * ten minutes. Without it the CPU sleeps in Doze, the daemon stops servicing
     * its DERP/WireGuard keepalives, and the connection is dead by morning.
     */
    private fun acquireKeepAliveLock() {
        if (!GlobalSettings.getBoolean(this, "force_bg", false)) return
        try {
            if (wakeLock?.isHeld != true) {
                @Suppress("WakelockTimeout")
                wakeLock?.acquire()
                Log.i(TAG, "Keep-alive wake lock acquired for the session")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    /** Ticks spent without reaching a connected state while the user wants one. */
    private var unhealthyTicks = 0
    private var autoRestartsDone = 0
    @Volatile private var autoRestartInFlight = false

    /**
     * Watches for a connection that never came up, or one that died on its own,
     * and restarts the daemon when the user asked for that.
     *
     * A daemon waiting for the user to log in is not unhealthy, so states that
     * need human action are left alone — restarting them would only throw the
     * pending login away.
     *
     * @return true while a recovery is pending, so the caller polls faster.
     */
    private fun checkConnectionHealth(isRunning: Boolean, backendState: String): Boolean {
        if (!ProxyState.isUserLetRunning(this) || teardownStarted || autoRestartInFlight) {
            return false
        }
        if (!GlobalSettings.isAutoReconnectEnabled(this)) {
            unhealthyTicks = 0
            return false
        }

        val awaitingUser = backendState == "NeedsLogin" ||
            backendState == "NoState" ||
            (isRunning && runCatching { Appctr.getLoginURL().isNotEmpty() }.getOrDefault(false))
        if (awaitingUser) {
            unhealthyTicks = 0
            return false
        }

        val healthy = isRunning && (backendState == "Running" || backendState == "Starting")
        if (healthy) {
            unhealthyTicks = 0
            autoRestartsDone = 0
            return false
        }

        unhealthyTicks++
        // Three consecutive unhealthy ticks before acting, so a momentary
        // reconnect is not answered with a full daemon restart.
        if (unhealthyTicks < 3) return true

        val limit = GlobalSettings.getAutoReconnectAttempts(this)
        if (limit != 0 && autoRestartsDone >= limit) {
            return false
        }

        unhealthyTicks = 0
        autoRestartsDone++
        autoRestartInFlight = true
        Log.w(TAG, "Connection is not coming up (state=$backendState), restarting daemon (attempt $autoRestartsDone)")
        Appctr.logAndroid(
            "WARN", "CORE",
            "Connection did not come up (state: ${backendState.ifEmpty { "stopped" }}), restarting the daemon (attempt $autoRestartsDone)"
        )
        updateNotification("Reconnecting...")

        Thread {
            try {
                stopTunMode()
                shutdownDaemon()
                Thread.sleep(1500)
                teardownStarted = false
                startTailscale()
            } finally {
                autoRestartInFlight = false
            }
        }.start()
        return true
    }

    /**
     * Upstream resolver IPs that must keep reaching port 53 directly.
     *
     * The system-wide DNS redirect sends every port-53 packet to MagicDNS. The
     * daemon's own upstream queries and our local DNS proxy's fallbacks would be
     * caught by that rule too and bounce straight back into MagicDNS, so their
     * destinations are excluded from the redirect.
     */
    /**
     * Reduces a user-entered device name to what a DNS label may contain.
     * Trailing newlines and spaces used to be sent verbatim to the control plane.
     */
    private fun sanitizeHostname(raw: String): String =
        raw.trim()
            .replace(" ", "-")
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "")
            .trim('-')
            .take(63)

    private fun upstreamDnsAddresses(): List<String> {
        val raw = GlobalSettings.getString(this, "dns_fallbacks", "8.8.8.8:53,1.1.1.1:53")
        // The redirect chain is IPv4-only, so only IPv4 literals are usable here.
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return raw.split(",")
            .map { it.trim().substringBefore(":") }
            .filter { ipv4.matches(it) }
            .distinct()
    }

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Read, compared and written from a fresh Thread per network event
        // (injectIfNeeded), so the dedup check races across concurrent events.
        // @Volatile at least makes each event's write visible to the next.
        @Volatile private var lastStateJson = ""

        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network Available")
            injectIfNeeded()
            // Post through refreshHandler (not a throwaway Handler) so onDestroy
            // cancels this and it cannot fire after the service is gone; remove
            // any pending one first so rapid onAvailable events do not stack.
            refreshHandler.removeCallbacks(networkNotifyRunnable)
            refreshHandler.postDelayed(networkNotifyRunnable, 1500)
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
                    val arr = kotlinx.serialization.json.buildJsonArray {
                        if (interfaces != null) {
                            for (iface in interfaces) {
                                if (!iface.isUp || iface.isLoopback) continue
                                val addrs = iface.inetAddresses?.toList()?.filter { !it.isLoopbackAddress }?.map { it.hostAddress ?: "" } ?: emptyList()
                                if (addrs.isEmpty()) continue
                                addJsonObject {
                                    put("name", iface.name)
                                    putJsonArray("addresses") { addrs.forEach { add(it) } }
                                    put("up", iface.isUp)
                                    put("mtu", iface.mtu)
                                }
                            }
                        }
                    }
                    val json = arr.toString()
                    if (json != lastStateJson) {
                        lastStateJson = json
                        Appctr.injectNetworkState(json)
                        Log.d(TAG, "Network state changed and injected")
                    }
                } catch (e: Exception) { Log.e(TAG, "Inject failed: ${e.message}") }
            }.start()
        }
    }

    /**
     * Doze suspends the CPU and defers work; a connection that died while the
     * device was idle is only noticed on the next refresh tick, which can be
     * minutes later. Leaving idle is the moment to check and recover.
     */
    private val idleModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isDeviceIdleMode) {
                Log.d(TAG, "Device entered Doze")
                return
            }
            Log.i(TAG, "Device left Doze, re-checking connection")
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.post(refreshRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { android.system.Os.setenv("TZ", java.util.TimeZone.getDefault().id, true) } catch (e: Exception) {}
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::WakeLock").apply {
            setReferenceCounted(false)
        }
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback) } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ContextCompat.registerReceiver(
                    this,
                    idleModeReceiver,
                    android.content.IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register Doze receiver: ${e.message}")
            }
        }
    }

    /**
     * Enters the foreground within the FGS start window. Android 12+ kills the
     * process if a foreground-service start does not call startForeground within
     * a few seconds, so every onStartCommand path goes through this before any
     * branching or teardown — several branches used to reach stopMe(), whose
     * root teardown can take seconds, without ever calling it.
     */
    private fun enterForeground(status: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                buildNotification(status),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, buildNotification(status))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (intent == null && !ProxyState.isUserLetRunning(this)) {
            enterForeground("Stopping...")
            stopMe()
            return START_NOT_STICKY
        }

        if (action == "STOP_ACTION") {
            enterForeground("Stopping...")
            stopMe()
            return START_NOT_STICKY
        }

        if (action == "REFRESH_ACTION" || action == "APPLY_SETTINGS" || action == ACTION_APPLY_SETTINGS) {
            enterForeground("Active")
            if (!ProxyState.isActualRunning(this)) {
                // Nothing is running and the daemon is genuinely gone. Do not run
                // the full stopMe() teardown here — it clears the user's
                // desired-running state and kills the root daemon. Just leave the
                // foreground quietly.
                Log.i(TAG, "Apply/refresh with no running daemon; standing down without teardown")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            if (!Appctr.isRunning()) {
                // The daemon is alive but this process is not attached to it —
                // Root Mode after the app was killed. Attach through the normal
                // start path, which applies the settings on the way.
                Log.i(TAG, "Apply requested while detached, attaching to running daemon first")
                ProxyState.setUserState(this, true)
                startTailscale()
                refreshHandler.removeCallbacks(refreshRunnable)
                refreshHandler.postDelayed(refreshRunnable, 1000)
                return START_STICKY
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
            enterForeground("Restarting...")
            // A restart must not run through stopMe(): that calls stopSelf(), and
            // the daemon was then started again on a service the system was already
            // tearing down. Shut the daemon down in place and bring it back up.
            ProxyState.setUserState(this, true)
            refreshHandler.removeCallbacks(refreshRunnable)
            Thread {
                stopTunMode()
                shutdownDaemon()
                Thread.sleep(1000)
                teardownStarted = false
                startTailscale()
                refreshHandler.post {
                    refreshHandler.removeCallbacks(refreshRunnable)
                    refreshHandler.postDelayed(refreshRunnable, 1000)
                }
            }.start()
            return START_STICKY
        }

        ProxyState.setUserState(this, true)
        // A previous stop may have marked this instance as torn down; a fresh start
        // command revives it, so the guard has to be cleared.
        teardownStarted = false
        ServiceWatchdog.schedule(this)
        updateTile()
        if (!Appctr.isRunning()) {
            enterForeground("Starting...")
            startTailscale()
        } else {
            enterForeground("Active")
            updateNotification("Active")
        }
        
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 1000)
        return START_STICKY
    }

    private fun startTailscale() {
        acquireKeepAliveLock()

        Thread {
            // buildStartOptions does file IO, preference writes, a ServerSocket
            // bind (ByeDPI) and a JNI call — all off the main thread.
            val options = buildStartOptions()
            try {
                applicationContext.sendBroadcast(Intent("STARTING").setPackage(packageName))
                if (GlobalSettings.isRootModeEnabled(this@TailscaledService)) {
                    val socketFile = java.io.File(options.socketPath)
                    // A socket file left behind by a killed daemon still exists, so
                    // liveness is decided by an actual connect before we query it.
                    val daemonAlive = RootUtils.isDaemonAlive(options.socketPath)
                    val statusJson = if (daemonAlive) {
                        kotlinx.coroutines.runBlocking { LocalApiClient { options.socketPath }.getStatus().getOrNull() }
                    } else null

                    val isRunningValid = statusJson != null && !statusJson.contains("\"BackendState\":\"NoState\"")

                    if (isRunningValid) {
                        Log.i(TAG, "Root daemon is already running. Attaching to existing socket with full options.")
                        Appctr.attachExternal(options)
                    } else {
                        if (socketFile.exists()) {
                            Log.w(TAG, "Root daemon socket is stale or unconfigured. Stopping leftover daemon and restarting.")
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
                            tunMode = GlobalSettings.isRootTunEnabled(this@TailscaledService),
                            dnsFallbacks = upstreamDnsAddresses()
                        )

                        if (!ok) {
                            // Do not report "Active" for a daemon that never came up.
                            Log.e(TAG, "Root daemon failed to start, aborting service start")
                            updateNotification("Root daemon failed to start")
                            stopMe()
                            return@Thread
                        }
                        Appctr.attachExternal(options)
                    }
                } else {
                    // Starting in userspace mode while system rules from a previous
                    // Root Mode session are still installed would leave the device
                    // routing tailnet traffic into an interface nobody manages.
                    if (GlobalSettings.isRootRoutingInstalled(this@TailscaledService)) {
                        Log.i(TAG, "Removing leftover Root Mode routing before userspace start")
                        removeRootArtifacts(killDaemon = true)
                    }
                    Appctr.setExternalSocketPath("")
                    Appctr.start(options)
                }
                updateNotification("Active")
                applicationContext.sendBroadcast(Intent("START").setPackage(packageName))
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
        // Whitespace and stray characters make it all the way to the control
        // plane as part of the node name, so the stored value is sanitised here
        // and written back to repair profiles that already hold a broken one.
        var host = sanitizeHostname(profilePrefs.getString("hostname", "") ?: "")
        if (host != (profilePrefs.getString("hostname", "") ?: "")) {
            profilePrefs.edit().putString("hostname", host).apply()
        }
        if (host.isBlank()) {
            val defaultHost = sanitizeHostname(android.os.Build.MODEL)
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
            socks5Server = GlobalSettings.getSocks5BindAddr(this@TailscaledService)
            socks5User   = GlobalSettings.getString(this@TailscaledService, "socks5_user", "")
            socks5Pass   = GlobalSettings.getString(this@TailscaledService, "socks5_pass", "")
            httpProxy    = GlobalSettings.getHttpProxyBindAddr(this@TailscaledService)
            controlProxy = if (byedpiEnabled && byedpiProxyAddress != null) {
                "socks5://${byedpiProxyAddress!!.first}:${byedpiProxyAddress!!.second}"
            } else {
                GlobalSettings.getControlProxyUrl(this@TailscaledService)
            }
            dnsProxy     = GlobalSettings.getDnsProxyBindAddr(this@TailscaledService)
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

            // Only emit the flag when the app actually owns this preference.
            // Nothing writes it (there is no UI), so unconditionally appending
            // --advertise-exit-node=false un-advertised a device that had been
            // made an exit node from the CLI or the admin console on every start.
            if (profilePrefs.contains("advertise_exit_node")) {
                argsBuilder.append("--advertise-exit-node=${profilePrefs.getBoolean("advertise_exit_node", false)} ")
            }

            val extraArgs = GlobalSettings.getString(this@TailscaledService, "extra_args_raw", "")
            if (extraArgs.isNotEmpty()) argsBuilder.append("$extraArgs ")

            extraUpArgs = argsBuilder.toString()
            
            val detailedLogs = GlobalSettings.getBoolean(this@TailscaledService, "detailed_logs", false)
            Appctr.setLogLevel(if (detailedLogs) 0 else 1)
        }
    }

    /** Guards against running the daemon teardown twice (stopMe then onDestroy). */
    @Volatile private var teardownStarted = false

    private fun stopMe() {
        if (teardownStarted) return
        teardownStarted = true

        stopTunMode()
        ProxyState.setUserState(this, false)
        ServiceWatchdog.cancel(this)
        refreshHandler.removeCallbacks(refreshRunnable)
        updateNotification("Stopping...")

        // Root teardown talks to `su` and can take seconds; doing that on the
        // caller's thread froze the UI whenever the tile or the notification
        // triggered a stop. The service stays in the foreground until it is done
        // so the process is not killed mid-cleanup.
        Thread {
            try {
                shutdownDaemon()
            } finally {
                refreshHandler.post {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    updateTile()
                    applicationContext.sendBroadcast(Intent("STOP").setPackage(packageName))
                    sendStatusBroadcast(this, "STOPPED")
                }
            }
        }.start()
    }

    /** Blocking teardown of the daemon and everything attached to it. */
    private fun shutdownDaemon() {
        try { Appctr.stopDriveServer() } catch (e: Exception) {}
        try { Appctr.stopDriveProxy() } catch (e: Exception) {}

        val rootMode = GlobalSettings.isRootModeEnabled(this)
        if (rootMode) {
            // Release the bridge first: the IPN bus, the DNS proxy and the Taildrop
            // collector must stop talking to a daemon that is about to disappear.
            Appctr.detachExternal()
        } else {
            Appctr.stop()
        }

        // Rule removal is deliberately not tied to Root Mode still being on.
        // Turning the mode off flips the setting before the service is asked to
        // stop, which used to send this down the non-root path and leave the
        // firewall rules, the routing table and the hosts bind-mount behind.
        removeRootArtifacts(killDaemon = !rootMode || GlobalSettings.shouldKillRootDaemonOnStop(this))

        try { ByeDpiProxy.stop() } catch (e: Exception) {}
        byedpiProxyAddress = null
        lastStartedFlags = null
        lastStartedIpv6Disabled = null
    }

    /**
     * Removes everything Root Mode installs on the system, if anything is
     * recorded as installed. Safe to call when nothing is.
     *
     * @param killDaemon also terminate the root daemon. When Root Mode is being
     *   switched off the daemon must go regardless of the keep-alive preference,
     *   since nothing will manage it any more.
     */
    private fun removeRootArtifacts(killDaemon: Boolean) {
        val installed = GlobalSettings.isRootRoutingInstalled(this)
        val rootMode = GlobalSettings.isRootModeEnabled(this)
        if (!installed && !rootMode) return

        rootRoutingApplied = false
        rootRoutingFailures = 0
        rootNotRunningTicks = 0

        if (installed) {
            RootUtils.cleanupTailscale0Routing()
            GlobalSettings.setRootRoutingInstalled(this, false)
        }
        if (killDaemon) {
            RootUtils.stopRootDaemon("${filesDir.absolutePath}/tailscaled.sock")
        }
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

        val json = kotlinx.serialization.json.buildJsonObject {
            putJsonArray("AdvertiseTags") { tags.forEach { add(it) } }
            put("AdvertiseTagsSet", true)
            putJsonArray("AdvertiseRoutes") { routes.forEach { add(it) } }
            put("AdvertiseRoutesSet", true)
        }.toString()
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

    @Volatile private var lastHostsHash: Int = 0
    @Volatile private var lastHostsSyncAt: Long = 0
    @Volatile private var hostsSyncInFlight = false

    /** Minimum gap between full peer-status fetches for the /etc/hosts sync. */
    private val hostsSyncIntervalMs = 60_000L

    private fun syncTailnetHosts() {
        if (!GlobalSettings.isRootModeEnabled(this) || !GlobalSettings.isRootTunEnabled(this)) return

        // The peer list changes rarely; pulling the full status on every refresh
        // tick is the polling this architecture deliberately moved away from.
        val now = System.currentTimeMillis()
        if (hostsSyncInFlight || now - lastHostsSyncAt < hostsSyncIntervalMs) return
        lastHostsSyncAt = now
        hostsSyncInFlight = true

        Thread {
            try {
                val statusJson = Appctr.getStatusJSON(true)
                if (statusJson.isNullOrBlank()) return@Thread

                val status = runCatching { AppJson.decodeFromString<StatusResponse>(statusJson) }.getOrNull() ?: return@Thread
                val peers = status.peers ?: emptyMap()

                val hostsMap = mutableMapOf<String, String>()

                // Add self
                val self = status.self
                if (self != null) {
                    val dnsName = self.dnsName?.removeSuffix(".")
                    val hostName = self.hostName?.trim()
                    val ips = self.tailscaleIPs
                    if (!dnsName.isNullOrEmpty() && ips != null) {
                        val shortName = dnsName.substringBefore('.')
                        val aliases = listOfNotNull(dnsName, shortName.takeIf { it != dnsName }, hostName.takeIf { it != dnsName && it != shortName }).joinToString(" ")
                        for (ip in ips) {
                            if (ip.isEmpty()) continue
                            hostsMap[ip] = aliases
                        }
                    }
                }

                // Add peers
                for ((_, p) in peers) {
                    val dnsName = p.dnsName?.removeSuffix(".")
                    val hostName = p.hostName?.trim()
                    val ips = p.tailscaleIPs
                    if (!dnsName.isNullOrEmpty() && ips != null) {
                        val shortName = dnsName.substringBefore('.')
                        val aliases = listOfNotNull(dnsName, shortName.takeIf { it != dnsName }, hostName.takeIf { it != dnsName && it != shortName }).joinToString(" ")
                        for (ip in ips) {
                            if (ip.isEmpty()) continue
                            hostsMap[ip] = aliases
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
            } finally {
                hostsSyncInFlight = false
            }
        }.start()
    }

    /**
     * Swiping the app out of Recents destroys the task but must not take the
     * connection with it, so the service asks to be started again.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ProxyState.isUserLetRunning(this) && !teardownStarted) {
            Log.i(TAG, "Task removed while running, requesting restart")
            ServiceWatchdog.schedule(this)
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, TailscaledService::class.java).apply { action = "START_ACTION" }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not re-request service start after task removal: ${e.message}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.removeCallbacks(networkNotifyRunnable)
        // stopMe() already ran the teardown (or is running it); only handle the
        // case where the system tore the service down without going through it.
        if (!teardownStarted) {
            teardownStarted = true
            // The system destroyed us without stopMe(): the TUN tunnel would
            // otherwise stay up, forwarding into a SOCKS proxy that is gone.
            stopTunMode()
            val rootMode = GlobalSettings.isRootModeEnabled(this)
            Thread {
                if (rootMode) {
                    Appctr.detachExternal()
                } else {
                    Appctr.stop()
                }
                // Autostart installed means the daemon is expected to outlive the
                // app, so it is left alone — but its rules are still ours to drop
                // if the daemon is going away with us.
                removeRootArtifacts(killDaemon = !RootUtils.isServiceScriptInstalled())
                try { ByeDpiProxy.stop() } catch (e: Exception) {}
            }.start()
            byedpiProxyAddress = null
            lastStartedFlags = null
            lastStartedIpv6Disabled = null
        }
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        try { unregisterReceiver(idleModeReceiver) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
