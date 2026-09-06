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
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.FileObserver
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

        /** What `tailscale up --advertise-exit-node` puts into AdvertiseRoutes. */
        private val EXIT_NODE_ROUTES = listOf("0.0.0.0/0", "::/0")

        /**
         * Upstream resolvers used when the preference holds nothing usable.
         * Must stay the same list the daemon falls back to, or its own bootstrap
         * queries are redirected back into MagicDNS.
         */
        private val DEFAULT_DNS_FALLBACKS = listOf("1.1.1.1", "8.8.8.8")

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
    /** ByeDPI run state: written from buildStartOptions() on the start and the
     *  APPLY_SETTINGS threads, from shutdownDaemon() on the teardown thread and
     *  from onDestroy() on main, so every reader needs the latest value. */
    @Volatile private var byedpiProxyAddress: Pair<String, Int>? = null
    @Volatile private var lastStartedFlags: String? = null
    @Volatile private var lastStartedIpv6Disabled: Boolean? = null
    @Volatile private var rootRoutingApplied = false
    /** Consecutive non-Running ticks; routing is only torn down after a couple of
     *  them so a brief state flap does not thrash iptables through `su`. */
    @Volatile private var rootNotRunningTicks = 0
    /** Consecutive failed routing attempts; retrying forever through `su` is noise. */
    @Volatile private var rootRoutingFailures = 0
    private val maxRootRoutingAttempts = 3
    /** An apply is on a `su` thread right now; a second one must not overlap it. */
    @Volatile private var rootRoutingInFlight = false
    /**
     * The inputs the currently installed ruleset was built from.
     *
     * An apply tears the system-wide DNS chain down before rebuilding it, so
     * re-running it for an unchanged ruleset is a device-wide DNS gap for
     * nothing. Cleared whenever the rules are removed.
     */
    @Volatile private var lastRootRoutingSignature: String? = null

    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var refreshTickRunning = false

    /** Delayed "network is back" notification refresh. Held as a named Runnable
     *  posted on refreshHandler so onDestroy can cancel it — a throwaway Handler's
     *  callback could otherwise fire after the service was torn down. */
    private val networkNotifyRunnable = Runnable {
        if (Appctr.isRunning()) updateNotification("Active")
    }

    /** Coalescing window for routing re-checks; see scheduleRootRoutingReapply. */
    private val rootRoutingReapplyDelayMs = 800L

    /** Deferred routing re-check. Posted on refreshHandler like its sibling
     *  above, so onDestroy cancels it, and it does its work on a thread of its
     *  own — the check talks to the daemon over its socket. */
    private val rootRoutingReapplyRunnable = Runnable {
        Thread { reevaluateRootRouting() }.start()
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

        // First Running state of this run: the netmap (and with it drive:share)
        // is in, so register Taildrive shares now if the start deferred it.
        if (isRunning && backendState == "Running" && !taildriveAppliedWhileRunning) {
            taildriveAppliedWhileRunning = true
            applyTaildrive(this@TailscaledService)
        }

        if (GlobalSettings.isRootModeEnabled(this@TailscaledService) && GlobalSettings.isRootTunEnabled(this@TailscaledService)) {
            // The VPN-slot callback is registered in onCreate and may have been
            // refused; the tick is the backstop that keeps the answer current.
            if (!vpnCallbackRegistered) refreshForeignVpnFromScan()
            pollForeignVpnProbe()
            if (isRunning && backendState == "Running") {
                rootNotRunningTicks = 0
                applyRootRoutingIfNeeded("daemon is Running")
                syncTailnetHosts()
            } else {
                if (rootRoutingApplied) {
                    rootNotRunningTicks++
                    if (rootNotRunningTicks >= 2) {
                        Log.i(TAG, "Daemon is not Running ($backendState). Cleaning up tailscale0 routing.")
                        rootRoutingApplied = false
                        rootNotRunningTicks = 0
                        lastRootRoutingSignature = null
                        Thread {
                            RootUtils.cleanupTailscale0Routing()
                            GlobalSettings.setRootRoutingInstalled(this@TailscaledService, false)
                            GlobalSettings.setRootRoutingYielded(this@TailscaledService, false)
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
     * Installs the Root Mode ruleset, unless what is already on the device was
     * built from the same inputs.
     *
     * An apply tears the system-wide DNS chain down before it rebuilds it, so
     * repeating one that would produce an identical ruleset is a device-wide DNS
     * gap for nothing. Every input the script depends on is folded into a
     * signature and the `su` work is skipped while it is unchanged, which is
     * what lets the re-apply triggers (another VPN coming or going, a settings
     * apply, a network change) fire freely.
     *
     * Yielding the device-wide tiers to another VPN is not a failure: the
     * tailnet tier is installed either way, so the attempt budget is untouched
     * and the installed marker is still written — without it the rules that did
     * go in could never be found and removed again.
     */
    private fun applyRootRoutingIfNeeded(reason: String) {
        if (rootRoutingApplied || rootRoutingInFlight) return
        if (rootRoutingFailures >= maxRootRoutingAttempts) return
        rootRoutingApplied = true
        rootRoutingInFlight = true

        val dnsRedirect = GlobalSettings.getBoolean(this, "accept_dns", true) &&
            GlobalSettings.isRootDnsRedirectEnabled(this)
        val bypass = upstreamDnsAddresses()
        val takeDeviceAnyway = GlobalSettings.isRootTakeDeviceAnyway(this)
        // Read here rather than inside the signature so the values that decide
        // the ruleset and the values it is built from are the same ones.
        val excludedApps = GlobalSettings.getTunExcludedApps(this).sorted().joinToString(",")
        val excludedCidrs = GlobalSettings.getTunExcludedCIDRs(this)
        val vpnSlotTaken = foreignVpnPresent

        Thread {
            try {
                // ConnectivityManager only reports a tunnel this app is a member
                // of, so the root-side probe still has to run when it says no.
                // That probe is the expensive half, hence the short circuit.
                val probeResult = RootUtils.detectForeignVpn()
                val foreignVpn = vpnSlotTaken || probeResult.present
                // The foreign tunnel's membership belongs in here: "a VPN is
                // present" is unchanged when its owner moves an app in or out of
                // its bypass list, but the ruleset we build from it is not.
                val foreignShape = RootUtils.foreignRoutingShape(probeResult)
                val signature = listOf(
                    dnsRedirect.toString(),
                    bypass.joinToString(","),
                    excludedApps,
                    excludedCidrs,
                    takeDeviceAnyway.toString(),
                    foreignVpn.toString(),
                    foreignShape
                ).joinToString("|")

                if (signature == lastRootRoutingSignature) {
                    Log.i(TAG, "Root routing re-check ($reason): inputs unchanged, leaving the rules alone")
                    return@Thread
                }

                Log.i(TAG, "Applying Root tailscale0 routing ($reason)")
                // Pass the Context so the SO_MARK check reads the daemon log
                // by its canonical path. Without it the check falls back to
                // the log of a daemon this process launched, which is unset
                // when the app attached to one the boot script had already
                // started — and the exit-node catch-all would then never be
                // installed after a reboot.
                val ok = RootUtils.applyTailscale0Routing(
                    dnsRedirect,
                    bypass,
                    this@TailscaledService,
                    foreignVpn,
                    takeDeviceAnyway,
                    probeResult
                )
                if (ok) {
                    rootRoutingFailures = 0
                    lastRootRoutingSignature = signature
                    // Persist the fact that system rules now exist, so they
                    // can be removed even if the app is killed or Root Mode
                    // is switched off before the next stop.
                    GlobalSettings.setRootRoutingInstalled(this@TailscaledService, true)
                    // Says only whether the device-wide tiers were left to the
                    // other tunnel; tailnet reachability is installed regardless.
                    GlobalSettings.setRootRoutingYielded(
                        this@TailscaledService,
                        foreignVpn && !takeDeviceAnyway
                    )
                } else {
                    rootRoutingApplied = false
                    lastRootRoutingSignature = null
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
            } finally {
                rootRoutingInFlight = false
            }
        }.start()
    }

    /**
     * Queues a re-evaluation of the Root Mode ruleset.
     *
     * Every apply spawns a `su` shell and re-scans the daemon log, and the
     * events that call for one arrive in bursts (a tunnel coming up moves the
     * default network too), so they are coalesced into a single deferred check.
     */
    private fun scheduleRootRoutingReapply(reason: String) {
        if (teardownStarted) return
        if (!GlobalSettings.isRootModeEnabled(this) || !GlobalSettings.isRootTunEnabled(this)) return
        Log.i(TAG, "Root routing re-check queued: $reason")
        refreshHandler.removeCallbacks(rootRoutingReapplyRunnable)
        refreshHandler.postDelayed(rootRoutingReapplyRunnable, rootRoutingReapplyDelayMs)
    }

    /**
     * Re-arms the one-shot apply latch and re-applies.
     *
     * The failure counter has to be cleared with it: a run that already spent
     * its three attempts would otherwise ignore every re-apply for the rest of
     * the session. The apply itself decides whether anything actually changed.
     */
    private fun reevaluateRootRouting() {
        if (teardownStarted) return
        if (!GlobalSettings.isRootModeEnabled(this) || !GlobalSettings.isRootTunEnabled(this)) return
        // Only re-arm the latch. Whether the daemon is up is the refresh tick's
        // question, and the tick is the one place that answers it correctly for
        // a daemon this process did not launch; asking Appctr here left the
        // re-check silently dead in exactly the Root Mode case it exists for.
        // The signature guard in the apply keeps the extra pass cheap.
        rootRoutingApplied = false
        rootRoutingFailures = 0
        Log.i(TAG, "Root routing latch re-armed; running a refresh pass now")
        // Run a pass right away rather than waiting out the refresh interval,
        // which is 15 s by default: the user is watching the main screen when
        // the other tunnel goes up or down, and a routing change that lands
        // half a minute later reads as "it did not work".
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.post(refreshRunnable)
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

    /** Ticks spent without reaching a connected state while the user wants one.
     *  Touched from a fresh refresh Thread per tick, like the sibling counters. */
    @Volatile private var unhealthyTicks = 0
    @Volatile private var autoRestartsDone = 0
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

        val gen = lifecycleGeneration.get()
        // Published as the in-flight teardown so a START arriving meanwhile joins
        // it and starts afterwards (see the START path) instead of doing nothing
        // because the daemon still looked alive.
        shutdownInFlight = Thread {
            try {
                stopTunMode()
                shutdownDaemon()
                Thread.sleep(1500)
                if (gen != lifecycleGeneration.get() || !ProxyState.isUserLetRunning(this)) {
                    // A manual stop, restart or start landed during the pause; a stop
                    // must not be undone, and a start owns the service now.
                    Log.i(TAG, "Auto-reconnect abandoned: superseded by a stop, restart or start")
                    return@Thread
                }
                teardownStarted = false
                startTailscale()
            } finally {
                autoRestartInFlight = false
            }
        }.also { it.start() }
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
            // The daemon substitutes its own defaults when this list is empty, so
            // an empty list here means no exclusion is written for the servers it
            // then queries — and its bootstrap lookups are redirected back into
            // MagicDNS, which is not answering yet.
            .ifEmpty { DEFAULT_DNS_FALLBACKS }
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
            scheduleRootRoutingReapply("the default network changed")
        }
        override fun onLost(network: Network) {
            Log.d(TAG, "Network Lost")
            injectIfNeeded()
            if (Appctr.isRunning()) updateNotification("Waiting for network...")
            scheduleRootRoutingReapply("the default network went away")
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
     * Whether another app holds Android's VPN slot right now.
     *
     * Android hands the slot to one app at a time and does not name the holder,
     * so this only answers whether it is taken. Root Mode's device-wide tiers
     * (the default-route capture and the system-wide DNS redirect) are yielded
     * while it is: their rules sit below netd's own VPN rules and would take the
     * other tunnel's apps away from it.
     */
    @Volatile private var foreignVpnPresent = false
    /** Written from binder callback threads and read from the refresh tick. */
    private val foreignVpnNetworks = java.util.Collections.synchronizedSet(HashSet<Network>())
    @Volatile private var vpnCallbackRegistered = false
    private var interfaceWatch: FileObserver? = null
    /** Last answer of the cheap tunnel-interface check; a change re-arms the ruleset. */
    @Volatile private var foreignVpnProbeSeen = false

    /**
     * A subscription of its own, because registerDefaultNetworkCallback only
     * reports the network *this app* routes over: a VPN that excludes TailSocks
     * from its tunnel never touches our default network while it captures every
     * other app. The default request also implicitly demands NET_CAPABILITY_NOT_VPN
     * and would never match a VPN at all.
     */
    private val vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            note(network, runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull())
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            note(network, caps)
        }

        override fun onLost(network: Network) {
            if (foreignVpnNetworks.remove(network)) publishForeignVpnState("a VPN went away")
        }

        private fun note(network: Network, caps: NetworkCapabilities?) {
            val foreign = caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !isOwnVpnNetwork(caps)
            val changed = if (foreign) foreignVpnNetworks.add(network) else foreignVpnNetworks.remove(network)
            if (changed) {
                publishForeignVpnState(if (foreign) "a VPN came up" else "a VPN went away")
            } else if (foreign) {
                // Its membership is part of its capabilities, so editing which
                // apps it carries lands here and nowhere else — the interface
                // does not necessarily come and go for that. Which apps it
                // bypasses is exactly what our scoped rules are built from, so
                // re-read; the apply's signature drops the pass if nothing moved.
                scheduleRootRoutingReapply("another VPN changed which apps it carries")
            }
        }
    }

    /**
     * Our own TUN mode is a VpnService like any other and must not make the app
     * yield to itself. The owner uid is only readable by the app that owns the
     * network, so it identifies ours exactly where the platform exposes it;
     * below that, and where it is withheld, the TUN service's own run flag
     * answers the same question. In Root Mode that service never runs, so
     * nothing is filtered out there.
     */
    private fun isOwnVpnNetwork(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val owner = caps.ownerUid
            if (owner != android.os.Process.INVALID_UID) return owner == android.os.Process.myUid()
        }
        return TunVpnService.isRunning
    }

    private fun publishForeignVpnState(reason: String) {
        setForeignVpnPresent(foreignVpnNetworks.isNotEmpty(), reason)
    }

    private fun setForeignVpnPresent(present: Boolean, reason: String) {
        if (present == foreignVpnPresent) return
        foreignVpnPresent = present
        Log.i(TAG, "VPN slot is ${if (present) "held by another app" else "free"} ($reason)")
        scheduleRootRoutingReapply(
            if (present) "another VPN took the slot" else "the other VPN released the slot"
        )
    }

    /**
     * Backstop for a callback registration the system refused — both of ours are
     * registered inside a catch that swallows the failure, so nothing else would
     * notice. One cheap ConnectivityManager scan per refresh tick.
     */
    @Suppress("DEPRECATION")
    /**
     * Notices another tunnel coming or going, without polling anything expensive.
     *
     * Two reasons this exists rather than leaning on the ConnectivityManager
     * callback alone: a VPN that excludes TailSocks from its tunnel is not
     * reported to us at all, so the slot can be taken and handed back without a
     * single callback firing; and the callback is the only other thing that
     * would re-arm the ruleset, which left the device yielded for the rest of
     * the session after the other tunnel had already gone.
     *
     * The check is a plain interface enumeration — no root shell, no wakeup of
     * its own. It rides the refresh tick that already runs, costs microseconds,
     * and only a *change* in its answer spends anything: the expensive root
     * probe runs inside the apply, and only then.
     */
    private fun pollForeignVpnProbe() {
        if (teardownStarted) return
        val seen = foreignTunnelInterfacePresent() ?: return
        if (seen == foreignVpnProbeSeen) return
        foreignVpnProbeSeen = seen
        scheduleRootRoutingReapply(
            if (seen) "a tunnel interface appeared" else "the tunnel interface went away"
        )
    }

    /**
     * True when an interface that belongs to somebody else's tunnel is up.
     *
     * Only `tun`/`ppp`/`wg`/`ipsec` names count and `tailscale0` is ours, so we
     * can never see ourselves here. Returns null when the enumeration fails, so
     * a transient error is not read as "the tunnel went away".
     */
    private fun foreignTunnelInterfacePresent(): Boolean? = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
        for (ni in java.util.Collections.list(ifaces)) {
            val name = ni.name ?: continue
            if (name == "tailscale0") continue
            val looksLikeTunnel = name.startsWith("tun") || name.startsWith("ppp") ||
                name.startsWith("wg") || name.startsWith("ipsec")
            // Deliberately not gated on isUp(): Java reports an interface as up
            // only with IFF_RUNNING too, which a tunnel briefly lacks, and this
            // check is a change signal — the root probe is what decides. A
            // leftover interface at worst costs one probe that says "no".
            if (looksLikeTunnel) return@runCatching true
        }
        false
    }.getOrNull()

    /**
     * Watches the kernel's own list of network interfaces, so another tunnel
     * coming up or going down is noticed at once instead of on the next tick.
     *
     * This is inotify on a sysfs directory: it costs nothing while nothing
     * happens, and it is an accelerator, not a dependency — the tick still
     * checks, so a kernel that declines to report sysfs directory events only
     * costs latency, never correctness.
     */
    private fun startInterfaceWatch() {
        if (interfaceWatch != null) return
        val watch = object : FileObserver(
            "/sys/class/net",
            FileObserver.CREATE or FileObserver.DELETE or
                FileObserver.MOVED_TO or FileObserver.MOVED_FROM
        ) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                if (name == "tailscale0") return
                val looksLikeTunnel = name.startsWith("tun") || name.startsWith("ppp") ||
                    name.startsWith("wg") || name.startsWith("ipsec")
                if (!looksLikeTunnel) return
                refreshHandler.post { pollForeignVpnProbe() }
            }
        }
        if (runCatching { watch.startWatching() }.isSuccess) {
            interfaceWatch = watch
            Log.i(TAG, "Watching /sys/class/net for other tunnels")
        }
    }

    private fun refreshForeignVpnFromScan() {
        val present = runCatching {
            connectivityManager.allNetworks.any { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !isOwnVpnNetwork(caps)
            }
        }.getOrDefault(foreignVpnPresent)
        setForeignVpnPresent(present, "network scan")
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
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                vpnNetworkCallback
            )
            vpnCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to watch the VPN slot: ${e.message}")
        }
        startInterfaceWatch()
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
                if (!ProxyState.isUserLetRunning(this)) {
                    // The root daemon was deliberately left alive by a manual
                    // Stop (root_kill_daemon_on_stop=false). A settings change
                    // is not a start request: do not attach and, above all, do
                    // not flip desired_running back on.
                    Log.i(TAG, "Apply requested while detached and not wanted; standing down")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
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
                // The DNS, per-app and coexistence settings all change what the
                // Root Mode ruleset should look like, and this path never used to
                // touch it. The re-check compares the inputs and does nothing when
                // the change was not one of them.
                scheduleRootRoutingReapply("settings were applied")
            }.start()
            return START_STICKY
        }
        
        if (action == "RESTART_ACTION") {
            enterForeground("Restarting...")
            // A restart must not run through stopMe(): that calls stopSelf(), and
            // the daemon was then started again on a service the system was already
            // tearing down. Shut the daemon down in place and bring it back up.
            ProxyState.setUserState(this, true)
            // The restart owns the service now. A stop whose teardown is still
            // running must not stop the service this restart brings back up, and
            // a Stop that lands during the restart's own teardown must not be
            // swallowed by stopMe()'s teardownStarted guard.
            val gen = lifecycleGeneration.incrementAndGet()
            teardownStarted = false
            ServiceWatchdog.schedule(this)
            refreshHandler.removeCallbacks(refreshRunnable)
            // Published as the in-flight teardown so a START arriving meanwhile
            // joins it and starts afterwards instead of seeing a daemon that is
            // about to be killed and doing nothing.
            shutdownInFlight = Thread {
                stopTunMode()
                shutdownDaemon()
                Thread.sleep(1000)
                if (gen != lifecycleGeneration.get()) {
                    // A stop or a fresh START landed during the teardown; the
                    // START path joins this thread and starts itself, a stop wins.
                    Log.i(TAG, "Restart abandoned: superseded by a stop or start")
                    return@Thread
                }
                teardownStarted = false
                startTailscale()
                refreshHandler.post {
                    refreshHandler.removeCallbacks(refreshRunnable)
                    refreshHandler.postDelayed(refreshRunnable, 1000)
                }
            }.also { it.start() }
            return START_STICKY
        }

        ProxyState.setUserState(this, true)
        // A previous stop may have marked this instance as torn down; a fresh start
        // command revives it, so the guard has to be cleared.
        teardownStarted = false
        // Supersede the deferred completion of any stop still in flight.
        lifecycleGeneration.incrementAndGet()
        ServiceWatchdog.schedule(this)
        // Whatever the system refused earlier, it let us through now; take the
        // "could not restart" notification down.
        ServiceWatchdog.clearRevivalRefused(this)
        updateTile()
        val pendingStop = shutdownInFlight
        if (pendingStop != null && pendingStop.isAlive) {
            // The previous stop is still tearing the daemon down. Appctr.isRunning()
            // is still true at this point, so the old code just showed "Active" and
            // started nothing — and the teardown then killed the daemon under it.
            // Let the teardown finish (it no longer stops the service, see stopMe)
            // and start fresh afterwards.
            enterForeground("Restarting...")
            Thread {
                try { pendingStop.join(15_000) } catch (_: InterruptedException) {}
                startTailscale()
            }.start()
        } else if (!Appctr.isRunning()) {
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
        taildriveAppliedWhileRunning = false
        // Only a stop may abandon a start. teardownStarted is set by stopMe() and
        // cleared synchronously by START/RESTART/auto-reconnect before they start,
        // so it means "the most recent lifecycle command was a stop". The
        // generation counter is bumped by START as well and must not be used
        // here: a second START_ACTION during an in-flight start (tile double-tap,
        // swipe from Recents, watchdog, Tasker) would make the first start kill
        // the daemon it had just launched while the service kept showing Active.
        fun stale() = teardownStarted

        Thread {
            // buildStartOptions does file IO, preference writes, a ServerSocket
            // bind (ByeDPI) and a JNI call — all off the main thread.
            val options = buildStartOptions()
            try {
                if (stale()) {
                    // buildStartOptions may already have started ByeDPI; the stop
                    // that superseded us ran before that, so clean up here.
                    Log.i(TAG, "Start abandoned: stopped before the daemon was launched")
                    shutdownDaemon()
                    return@Thread
                }
                applicationContext.sendBroadcast(Intent("STARTING").setPackage(packageName))
                if (GlobalSettings.isRootModeEnabled(this@TailscaledService)) {
                    val socketFile = java.io.File(options.socketPath)
                    // A socket file left behind by a killed daemon still exists, so
                    // liveness is decided by an actual connect before we query it.
                    // allowSocketConnect is isDaemonAlive plus one repair: if the
                    // connect is refused by SELinux rather than by a missing
                    // daemon, it adds the one allow rule and retries. Without it
                    // a healthy daemon started by the boot script looks dead and
                    // gets killed and restarted below.
                    val daemonAlive = RootUtils.allowSocketConnect(options.socketPath)
                    val statusJson = if (daemonAlive) {
                        kotlinx.coroutines.runBlocking { LocalApiClient { options.socketPath }.getStatus().getOrNull() }
                    } else null

                    val isRunningValid = statusJson != null && !statusJson.contains("\"BackendState\":\"NoState\"")

                    if (isRunningValid) {
                        if (stale()) {
                            Log.i(TAG, "Start abandoned before attaching to the root daemon")
                            return@Thread
                        }
                        Log.i(TAG, "Root daemon is already running. Attaching to existing socket with full options.")
                        Appctr.attachExternal(options)
                    } else {
                        if (socketFile.exists()) {
                            Log.w(TAG, "Root daemon socket is stale or unconfigured. Stopping leftover daemon and restarting.")
                            RootUtils.stopRootDaemon(options.socketPath)
                            RootUtils.handStateBackToApp(this@TailscaledService)
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
                        if (stale()) {
                            // A stop landed while su was bringing the daemon up; its
                            // teardown ran before the daemon existed, so take it down here.
                            Log.i(TAG, "Start abandoned after the root daemon launched, shutting it down again")
                            shutdownDaemon()
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
                    if (stale()) {
                        Log.i(TAG, "Start abandoned before launching the daemon")
                        shutdownDaemon()
                        return@Thread
                    }
                    Appctr.setExternalSocketPath("")
                    Appctr.start(options)
                }
                if (stale()) {
                    // A stop landed while the daemon was starting; its teardown may
                    // have run before the daemon existed. The Go side spawns the
                    // process asynchronously, so give it a moment to appear before
                    // taking it down, or it would be left running under no service.
                    Log.i(TAG, "Start abandoned after the daemon launched, shutting it down again")
                    var waited = 0
                    while (!Appctr.isRunning() && waited < 3000) { Thread.sleep(100); waited += 100 }
                    shutdownDaemon()
                    return@Thread
                }
                updateNotification("Active")
                applicationContext.sendBroadcast(Intent("START").setPackage(packageName))
                forceAppWidgetUpdate(this@TailscaledService)
                if (waitForDaemonReady()) {
                    if (stale()) {
                        Log.i(TAG, "Start abandoned after the daemon became ready, shutting it down again")
                        shutdownDaemon()
                        return@Thread
                    }
                    Log.d(TAG, "Daemon readiness checkpoint reached. Launching auxiliary modules...")
                    applyTagsAndRoutes(this@TailscaledService)
                    // Share registration needs the node's capabilities (drive:share),
                    // which arrive with the netmap. Right after the socket appears the
                    // backend is still Starting and the daemon answers 403 "sharing not
                    // enabled" — a race, not a policy problem. Apply now only if already
                    // Running (re-attach); otherwise the refresh tick does it once the
                    // state flips, see taildriveAppliedWhileRunning.
                    if (runCatching { Appctr.getBackendState() }.getOrDefault("") == "Running") {
                        taildriveAppliedWhileRunning = true
                        applyTaildrive(this@TailscaledService)
                    } else {
                        Log.i(TAG, "Taildrive: deferring share registration until the backend is Running")
                    }

                    if (GlobalSettings.isTunModeEnabled(this@TailscaledService) && !GlobalSettings.isRootModeEnabled(this@TailscaledService)) {
                        startTunMode()
                    }
                } else {
                    Log.w(TAG, "Daemon readiness checkpoint timed out.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                // A failure of a superseded start must not stop whatever superseded it.
                if (!stale()) stopMe()
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
            
            // Not under stateDir: the FileProvider has to be able to hand a
            // received file to another app, and that root must not also cover
            // the node keys. See TaildropPaths / res/xml/file_paths.xml.
            taildropDir = TaildropPaths.ensureDir(this@TailscaledService, activeAccount.id).absolutePath
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

            // The bridge parses extraUpArgs as `tailscale up`-style flags and folds
            // the result into the same prefs PATCH syncSettings sends, after the
            // app's own keys (appctr/extraargs.go). Only the user's own text goes
            // here: everything the app owns already travels as a StartOptions
            // field, and re-emitting those as flags would let the app's own
            // --advertise-exit-node overwrite AdvertiseRoutes with just the two
            // default routes, dropping the subnet routes applyTagsAndRoutes owns.
            extraUpArgs = GlobalSettings.getString(this@TailscaledService, "extra_args_raw", "").trim()

            val detailedLogs = GlobalSettings.getBoolean(this@TailscaledService, "detailed_logs", false)
            Appctr.setLogLevel(if (detailedLogs) 0 else 1)
        }
    }

    /** Guards against running the daemon teardown twice (stopMe then onDestroy). */
    @Volatile private var teardownStarted = false

    /**
     * Bumped by every stop and every start. The deferred completion of a stop
     * (stopSelf and friends) only runs if no start superseded it meanwhile,
     * and a start thread abandons its work once a stop superseded it. Without
     * this, STOP followed by a quick START left the service dead with
     * desired_running=true (so the watchdog "revived it by itself" later), and
     * STOP during a slow start left a daemon running under no service.
     */
    private val lifecycleGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Teardown thread of the most recent stop, while it is still running. */
    @Volatile private var shutdownInFlight: Thread? = null

    /** Taildrive shares were registered after this run's backend reached Running. */
    @Volatile private var taildriveAppliedWhileRunning = false

    /**
     * Set when a TUN start was requested, cleared once ACTION_STOP has been sent.
     * TunVpnService.isRunning only turns true at the end of its own start
     * sequence (VPN permission dialog, establish(), JNI), so without this a stop
     * landing in that window skipped the TUN stop and left the tunnel — routing
     * the whole device when an exit node is set — up under a stopped service.
     */
    @Volatile private var tunRequested = false

    private fun stopMe() {
        if (teardownStarted) return
        teardownStarted = true

        // Record the user's decision before anything that can fail. If the
        // teardown below crashes the process, desired_running must already be
        // false and the watchdog alarm gone, otherwise the sticky restart and the
        // 15-minute watchdog bring the service back after a manual stop.
        ProxyState.setUserState(this, false)
        ServiceWatchdog.cancel(this)
        // The user asked for this one, so there is no outage left to report.
        ServiceWatchdog.clearRevivalRefused(this)
        refreshHandler.removeCallbacks(refreshRunnable)

        try {
            stopTunMode()
        } catch (t: Throwable) {
            // stopTunMode() catches Exceptions; this covers LinkageErrors from
            // touching TunVpnService when its native library cannot be loaded.
            Log.e(TAG, "stopTunMode failed during stop, continuing teardown", t)
        }
        updateNotification("Stopping...")

        // Root teardown talks to `su` and can take seconds; doing that on the
        // caller's thread froze the UI whenever the tile or the notification
        // triggered a stop. The service stays in the foreground until it is done
        // so the process is not killed mid-cleanup.
        val gen = lifecycleGeneration.incrementAndGet()
        shutdownInFlight = Thread {
            try {
                shutdownDaemon()
            } finally {
                refreshHandler.post {
                    if (gen != lifecycleGeneration.get()) {
                        // A START arrived while the daemon was shutting down. That
                        // start owns the service now; stopping it here would leave
                        // desired_running=true with no service behind it.
                        Log.i(TAG, "Stop completion superseded by a newer start")
                        return@post
                    }
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    updateTile()
                    applicationContext.sendBroadcast(Intent("STOP").setPackage(packageName))
                    sendStatusBroadcast(this, "STOPPED")
                }
            }
        }.also { it.start() }
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
        lastRootRoutingSignature = null
        refreshHandler.removeCallbacks(rootRoutingReapplyRunnable)
        GlobalSettings.setRootRoutingYielded(this, false)

        if (installed) {
            RootUtils.cleanupTailscale0Routing()
            GlobalSettings.setRootRoutingInstalled(this, false)
        }
        if (killDaemon) {
            RootUtils.stopRootDaemon("${filesDir.absolutePath}/tailscaled.sock")
            RootUtils.handStateBackToApp(this@TailscaledService)
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
        val routes = routesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        // Being an exit node IS advertising the two default routes — there is no
        // separate pref for it. This is the only place that writes AdvertiseRoutes,
        // so the "Run as exit node" switch has to be folded in here or the write
        // below would drop the routes again on the next apply. The key is only
        // consulted when it exists: a device made an exit node from the CLI or the
        // admin console is left alone until the switch is touched once.
        if (profilePrefs.contains("advertise_exit_node") &&
            profilePrefs.getBoolean("advertise_exit_node", false)
        ) {
            for (r in EXIT_NODE_ROUTES) if (r !in routes) routes.add(r)
        }

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
            tunRequested = true
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
            if (!TunVpnService.isRunning && !tunRequested) {
                // Nothing to stop. Starting the VpnService only to stop it again
                // used to leave its foreground notification behind; also drop any
                // such leftover from earlier builds.
                (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                    ?.cancel(TunVpnService.NOTIF_ID)
                return
            }
            tunRequested = false
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

                // Every name published here resolves device-wide for every app, so
                // the tailnet zone is the only namespace a node may claim. The
                // control-assigned DNSName must sit inside the MagicDNS suffix, and
                // the self-reported HostName is accepted only as a bare label: a peer
                // calling itself "accounts.google.com" used to hijack that domain.
                val zone = status.magicDnsSuffix?.trim()?.removeSuffix(".")?.lowercase()
                if (zone.isNullOrEmpty()) {
                    Log.w(TAG, "hosts-sync: MagicDNS suffix unknown, not publishing tailnet names")
                    return@Thread
                }
                val bareLabel = Regex("^[A-Za-z0-9-]{1,63}$")
                val claimed = mutableSetOf<String>()
                val hostsMap = mutableMapOf<String, String>()

                fun addNode(dnsRaw: String?, hostRaw: String?, ips: List<String>?) {
                    val dnsName = dnsRaw?.removeSuffix(".")?.lowercase() ?: return
                    if (ips == null || dnsName.isEmpty() || !dnsName.endsWith(".$zone")) return
                    val shortName = dnsName.substringBefore('.')
                    val hostName = hostRaw?.trim()?.lowercase()
                        ?.takeIf { bareLabel.matches(it) && it != dnsName && it != shortName }
                    // First claim wins (self is added first), so no peer can shadow
                    // another node's name or this device's own.
                    val aliases = listOfNotNull(dnsName, shortName.takeIf { it != dnsName }, hostName)
                        .filter { claimed.add(it) }
                    if (aliases.isEmpty()) return
                    for (ip in ips) {
                        if (ip.isEmpty()) continue
                        hostsMap[ip] = aliases.joinToString(" ")
                    }
                }

                status.self?.let { addNode(it.dnsName, it.hostName, it.tailscaleIPs) }
                for ((_, p) in peers) addNode(p.dnsName, p.hostName, p.tailscaleIPs)
                
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
        refreshHandler.removeCallbacks(rootRoutingReapplyRunnable)
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
        try { connectivityManager.unregisterNetworkCallback(vpnNetworkCallback) } catch (e: Exception) {}
        vpnCallbackRegistered = false
        runCatching { interfaceWatch?.stopWatching() }
        interfaceWatch = null
        try { unregisterReceiver(idleModeReceiver) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
