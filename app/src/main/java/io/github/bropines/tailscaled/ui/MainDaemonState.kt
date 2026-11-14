package io.github.bropines.tailscaled.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

/*
 * What the main screen (ui/MainActivity.kt) knows about the daemon and the connection, and
 * what keeps it current: the refresh loop driven by the daemon's change events
 * (core/DaemonEvents.kt; its decisions are the pure functions in MainStatusLogic.kt), the
 * login-tab watcher, the service's broadcasts, and the mirrors of the preferences the status
 * card reads.
 */

/**
 * The daemon as the main screen shows it. Written by [MainDaemonEffects] and by the screen's
 * own start, stop and account switch ([isProcessing]); read by the status card, its lines,
 * the summary and the devices.
 */
@Stable
internal class MainDaemonState(
    proxyState: MutableState<String>,
    daemonBackend: MutableState<String>,
    shownWarnings: MutableState<List<HealthWarning>>,
    connectionSummary: MutableState<ConnectionSummary?>,
    tailnetPeers: MutableState<List<PeerData>?>,
    managedPeers: MutableState<List<PeerData>?>,
    isProcessing: MutableState<Boolean>,
    loginUrl: MutableState<String?>,
    show410Warning: MutableState<Boolean>,
) {
    /** The screen's state of the service: STOPPED, STARTING, ACTIVE, LOGGED_OUT, CONNECTION_ISSUE (nextMainState). */
    var proxyState by proxyState
    // What the daemon says about itself beyond "the service is running": its
    // backend state, the health warnings worth showing, and a summary of the
    // tailnet. The service state above cannot tell "connected" from "stuck
    // starting" or "up, but no relay reachable" — these can.
    var daemonBackend by daemonBackend
    var shownWarnings by shownWarnings
    var connectionSummary by connectionSummary
    // The other devices, from the same status read as the summary: what a large window's
    // devices card lists (MainDashboard.kt). A phone has no such card and shows none of it.
    var tailnetPeers by tailnetPeers
    // The same list for the managed devices, which the screen's own scope reads: replaced only
    // when what they take from it changes (managedPresence). tailnetPeers is new on every read —
    // traffic counters, handshakes — and read there, it re-ran all of MainScreen every 10 s;
    // it is now read only by what draws it, a wide Peers tile and a large window's devices card.
    var managedPeers by managedPeers
    /** A start, stop, login or account switch is under way; the start watchdog runs meanwhile. */
    var isProcessing by isProcessing
    var loginUrl by loginUrl
    // Saveable: the bridge's last error is cleared by the read that found it (getLastError), so
    // a rotation or a recreate() for a language or scale change lost the banner for good.
    var show410Warning by show410Warning
}

/**
 * The daemon's state as the screen starts: the service's own word for whether it runs, or
 * what a preview hands in ([demo]; see LocalDemo). [inPreview] is the preview renderer, which
 * has no daemon and no native bridge.
 */
@Composable
internal fun rememberMainDaemonState(demo: DemoData?, inPreview: Boolean): MainDaemonState {
    val context = LocalContext.current
    val proxyState = remember {
        mutableStateOf(
            when {
                demo != null -> if (demo.running) "ACTIVE" else "STOPPED"
                !inPreview && ProxyState.isActualRunning(context) -> "ACTIVE"
                else -> "STOPPED"
            }
        )
    }
    val daemonBackend = remember { mutableStateOf(demo?.backendState ?: "") }
    val shownWarnings = remember {
        mutableStateOf(visibleWarnings(parseHealthWarnings(demo?.healthJson), System.currentTimeMillis()))
    }
    val connectionSummary = remember {
        mutableStateOf(
            demo?.statusJson?.let { json ->
                runCatching { summarize(AppJson.decodeFromString<StatusResponse>(json), demo.exitNodeIp ?: "") }.getOrNull()
            }
        )
    }
    val demoPeers = remember {
        demo?.statusJson?.let { json ->
            runCatching { listablePeers(AppJson.decodeFromString<StatusResponse>(json)) }.getOrNull()
        }
    }
    val tailnetPeers = remember { mutableStateOf(demoPeers) }
    val managedPeers = remember { mutableStateOf(demoPeers) }
    val isProcessing = remember { mutableStateOf(false) }
    val loginUrl = remember { mutableStateOf<String?>(null) }
    val show410Warning = rememberSaveable { mutableStateOf(false) }
    return remember {
        MainDaemonState(
            proxyState, daemonBackend, shownWarnings, connectionSummary, tailnetPeers, managedPeers,
            isProcessing, loginUrl, show410Warning,
        )
    }
}

/**
 * The preferences the status card and its lines read, mirrored as state: a listener on the
 * global store keeps the ones the service writes in step, and the screen's resume reads them
 * all again ([MainDaemonEffects]).
 */
@Stable
internal class MainConnectionFlags(
    isTunEnabled: MutableState<Boolean>,
    isTunDisplaced: MutableState<Boolean>,
    isRootEnabled: MutableState<Boolean>,
    dnsHasFallback: MutableState<Boolean>,
    isRootYielded: MutableState<Boolean>,
    isRootShared: MutableState<Boolean>,
    isBatteryOptimizationsIgnored: MutableState<Boolean>,
) {
    var isTunEnabled by isTunEnabled
    // TUN mode is on, but another VPN app took the slot: the card must not say
    // "+ TUN" over a phone that has no TailSocks VPN (TunVpnService.isDisplaced).
    var isTunDisplaced by isTunDisplaced
    // Root Mode routes through the kernel interface, not the VpnService, so the
    // card has to name it: "Active" alone reads as plain proxy mode.
    var isRootEnabled by isRootEnabled
    // Whether a failing Tailscale resolver is a notice or a connection problem.
    var dnsHasFallback by dnsHasFallback
    // Root Mode leaves the default route and the device's DNS to another VPN
    // when one holds the phone. The service records that; the card and the exit
    // node row read it here, so neither promises a tunnel that is not installed.
    var isRootYielded by isRootYielded
    // The yield has two shapes and they mean opposite things to the user. When
    // the other client bypasses some apps we take the default route for exactly
    // those, so the exit node is carrying traffic — for them alone. Read as a
    // pair: this flag decides, and the yield flag only speaks when it is off.
    var isRootShared by isRootShared
    // Read when the screen is resumed (the observer in MainDaemonEffects): it changes only in
    // the system's settings, and coming back from there resumes this screen. It was a binder
    // call every 2 s.
    var isBatteryOptimizationsIgnored by isBatteryOptimizationsIgnored

    val globalPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "tun_mode_enabled") {
            this.isTunEnabled = sharedPreferences.getBoolean("tun_mode_enabled", false)
        }
        if (key == TunVpnService.DISPLACED_PREF) {
            this.isTunDisplaced = sharedPreferences.getBoolean(TunVpnService.DISPLACED_PREF, false)
        }
        if (key == "root_routing_yielded") {
            this.isRootYielded = sharedPreferences.getBoolean("root_routing_yielded", false)
        }
        if (key == "root_routing_shared") {
            this.isRootShared = sharedPreferences.getBoolean("root_routing_shared", false)
        }
    }
}

@Composable
internal fun rememberMainConnectionFlags(): MainConnectionFlags {
    val context = LocalContext.current
    val isBatteryOptimizationsIgnored = remember { mutableStateOf(true) }
    val isTunEnabled = remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    val isTunDisplaced = remember { mutableStateOf(TunVpnService.isDisplaced(context)) }
    val isRootEnabled = remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
    val dnsHasFallback = remember { mutableStateOf(GlobalSettings.dnsHasFallback(context)) }
    val isRootYielded = remember { mutableStateOf(GlobalSettings.isRootRoutingYielded(context)) }
    val isRootShared = remember { mutableStateOf(GlobalSettings.isRootRoutingShared(context)) }
    return remember {
        MainConnectionFlags(isTunEnabled, isTunDisplaced, isRootEnabled, dnsHasFallback, isRootYielded, isRootShared, isBatteryOptimizationsIgnored)
    }
}

/**
 * Everything that writes [daemon], [flags] and the exit node's mirror, in the order it always
 * ran: the refresh loop, the login-tab watcher, the two preference listeners, the resume
 * observer and the service's broadcasts. Moved here as one block from the screen; the nested
 * `with`s let its text stay what was verified on a device. [prefs] is the active profile's
 * store and [globalPrefs] the global one; [onResumed] runs first on every resume (the screen
 * reloads its arrangement there).
 */
@Composable
internal fun MainDaemonEffects(
    daemon: MainDaemonState,
    acc: MainAccountsState,
    exitNode: MainExitNodeState,
    flags: MainConnectionFlags,
    prefs: SharedPreferences,
    globalPrefs: SharedPreferences,
    demo: DemoData?,
    inPreview: Boolean,
    onResumed: () -> Unit,
) {
    val context = LocalContext.current
    with(daemon) { with(acc) { with(exitNode) { with(flags) {
        // The watchdog lives as long as the screen, and an account switch replaces the profile's
        // store and its exit node without restarting it (the activity is not recreated): it reads
        // them through these, or it kept the first account's for good — `was_logged_in` written
        // into the wrong profile, the new profile's exit node looked up by the old one's address.
        val currentPrefs by rememberUpdatedState(prefs)
        val currentExitNodeIp by rememberUpdatedState(exitNodeIp)
        val pollLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

        // The refresh loop: keeps the card, its warnings, the login link and the summary in step with
        // the daemon.
        //
        // Told, not asking (core/DaemonEvents.kt, appctr/events.go): the bridge says when the backend
        // state, the health warnings, the prefs or the peers changed, and only then is anything read —
        // a state, health or prefs event re-reads the bridge's cheap state, a netmap or prefs event the
        // full status. It used to be a 2-second loop that read the bridge 30 times a minute and the
        // full status, every peer included, 6 times a minute whether or not anything had changed.
        // Now an idle, connected screen reads nothing but the safety net's cheap read every
        // MAIN_SAFETY_MS (events can be missed across a daemon restart or a bridge re-attach; the
        // screen must never sit on a stale state for good), and wakes sooner only while something is
        // time-bound (mainWakeDelayMs, MainStatusLogic.kt): the start watchdog between a start, stop
        // or login pressed and its outcome, the logged-out grace period, a young health warning.
        //
        // Only while the screen is at least STARTED. It is the task's singleTask root, so it stays
        // composed under Settings, Peers and every other screen, and in the background for as long
        // as the service keeps the process. The counters stay outside the block, so a stop and a
        // start carry on where they were; every start reads the cheap state and the full status at
        // once, so what comes back is current within one round trip, whatever happened meanwhile.
        LaunchedEffect(pollLifecycle) {
            // The preview renderer has no bridge, and a preview's state is handed in (LocalDemo).
            if (inPreview || demo != null) return@LaunchedEffect
            var urlDetected = false
            var lastAvatarSync = 0L
            var loggedOutSince = 0L
            pollLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // What wakes the loop before its timer: an event, a start or stop pressed, the exit
                // node changed here. Conflated: however many arrive while it reads, it reads once.
                val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
                // Back in front: everything is read at once.
                var stateWanted = true
                var statusWanted = true
                var lastReadAt = 0L
                var processingSince = 0L
                var backendRunning = false
                var settlesAt = 0L
                // The status read runs beside the loop — a large tailnet's status takes a while — and
                // a slow one is not stacked on: a netmap event during it asks for one more after it.
                var statusRead: kotlinx.coroutines.Job? = null

                // The bridge's events, as revisions: a conflated StateFlow never loses "something of
                // this kind changed", even when the event stream dropped entries.
                launch {
                    var seen = DaemonEvents.revision.value
                    DaemonEvents.revision.collect { rev ->
                        val changed = rev.changedSince(seen)
                        seen = rev
                        if (changed.isEmpty()) return@collect
                        if (mainWantsStateRead(changed)) stateWanted = true
                        if (mainWantsStatusRead(changed)) statusWanted = true
                        wake.trySend(Unit)
                    }
                }
                // A start, stop or login pressed starts the start watchdog, and its end is read at once.
                launch {
                    snapshotFlow { isProcessing }.drop(1).collect { stateWanted = true; wake.trySend(Unit) }
                }
                // The summary names the exit node by the address chosen here: a new one is read at once.
                launch {
                    snapshotFlow { currentExitNodeIp }.drop(1).collect { statusWanted = true; wake.trySend(Unit) }
                }

                while (true) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (stateWanted) {
                        stateWanted = false
                        lastReadAt = now
                        // Each of these can be a LocalAPI round trip, and LocalAPI holds a request
                        // while the daemon has no backend yet: read on this thread, a daemon that was
                        // slow to start froze the screen it was starting under.
                        val isProcessAlive = withContext(Dispatchers.IO) { try { appctr.Appctr.isRunning() } catch (e: Exception) { false } }
                        val backendState = if (isProcessAlive) {
                            withContext(Dispatchers.IO) { try { appctr.Appctr.getBackendState() } catch (e: Exception) { "Error" } }
                        } else "Stopped"
                        daemonBackend = backendState
                        val allWarnings = if (isProcessAlive) {
                            parseHealthWarnings(withContext(Dispatchers.IO) { runCatching { appctr.Appctr.getHealthWarningsJSON() }.getOrNull() })
                        } else emptyList()
                        val wallNow = System.currentTimeMillis()
                        shownWarnings = visibleWarnings(allWarnings, wallNow)
                        // A warning still too young to show: look again when it is not.
                        settlesAt = warningSettlesAtMs(allWarnings, wallNow).let { if (it == 0L) 0L else now + (it - wallNow) }

                        // Into Running: the summary and the devices are read at once.
                        val running = isProcessAlive && backendState == "Running"
                        if (running && !backendRunning) statusWanted = true
                        backendRunning = running

                        if (running) {
                            // A login opened in a Custom Tab is done: back over the tab.
                            context.findActivity()?.let { LoginTab.finished(it) }
                            val wasLoggedIn = currentPrefs.getBoolean("was_logged_in", false)
                            if (!wasLoggedIn) {
                                currentPrefs.edit().putBoolean("was_logged_in", true).apply()
                            }
                            // A running backend means any start/login transition is over. If
                            // isProcessing was left set (no START broadcast reached this
                            // screen, e.g. a slow browser login), the sync below never ran and
                            // the "connection issue" card stayed up over a working tailnet.
                            if (isProcessing && (proxyState == "STARTING" || proxyState == "LOGGED_OUT" || proxyState == "CONNECTION_ISSUE")) {
                                isProcessing = false
                            }
                        }

                        // Sync state if not explicitly in transition (nextMainState, MainStatusLogic.kt).
                        if (!isProcessing) {
                            val tick = nextMainState(
                                previous = proxyState,
                                alive = isProcessAlive,
                                backend = backendState,
                                loggedOutSinceMs = loggedOutSince,
                                nowMs = now,
                                wasLoggedIn = currentPrefs.getBoolean("was_logged_in", false),
                                hasLoginUrl = !loginUrl.isNullOrBlank(),
                            )
                            proxyState = tick.state
                            loggedOutSince = tick.loggedOutSinceMs
                        }

                        if (isProcessAlive) {
                            val url = withContext(Dispatchers.IO) { try { appctr.Appctr.getLoginURL() } catch (e: Exception) { "" } }
                            loginUrl = if (url.isNullOrBlank()) null else url

                            if (loginUrl != null) urlDetected = true
                            else if (urlDetected) {
                                show410Warning = false
                                urlDetected = false
                            }

                            // Set by a console or login command the daemon answered with 410; the
                            // bridge sends a state event when it records one.
                            val lastErr = withContext(Dispatchers.IO) { try { appctr.Appctr.getLastError() } catch (e: Exception) { "" } }
                            if (lastErr == "410_GONE") show410Warning = true

                            if (!running) {
                                connectionSummary = null
                                tailnetPeers = null
                                managedPeers = null
                            }
                        } else {
                            loginUrl = null
                            show410Warning = false
                            urlDetected = false
                        }
                    }

                    // The summary under the card and the devices, from one status read, off the main
                    // thread, only while the backend is actually running — and the account's picture
                    // from that same read, at most every 30 s (avatarDue).
                    if (statusWanted && !backendRunning) statusWanted = false
                    if (statusWanted && statusRead?.isActive != true) {
                        statusWanted = false
                        val avatar = avatarDue(now, lastAvatarSync)
                        if (avatar) lastAvatarSync = now
                        val ipForExit = currentExitNodeIp
                        val account = activeAccount
                        statusRead = launch(Dispatchers.IO) {
                            val status = DaemonStatus.readOrNull()
                            val built = status?.let { runCatching { summarize(it, ipForExit) }.getOrNull() }
                            val peers = status?.let { runCatching { listablePeers(it) }.getOrNull() }
                            withContext(Dispatchers.Main) {
                                connectionSummary = built
                                tailnetPeers = peers
                                if (managedPresence(peers) != managedPresence(managedPeers)) managedPeers = peers
                            }
                            // Background avatar sync, off the status just read.
                            val picUrl = if (avatar && status != null) avatarToSync(status, account.avatarUrl) else null
                            if (picUrl != null) {
                                try {
                                    downloadAndCacheAvatar(context, account.id, picUrl)
                                    AccountManager.updateAccountAvatar(context, account.id, picUrl)
                                    withContext(Dispatchers.Main) {
                                        accounts.value = AccountManager.getAccounts(context)
                                        activeAccount = AccountManager.getActiveAccount(context)
                                    }
                                } catch (e: Exception) {}
                            }
                            // Asked for again while this one ran: go round once more.
                            wake.trySend(Unit)
                        }
                    }

                    processingSince = if (!isProcessing) 0L else if (processingSince == 0L) now else processingSince
                    val sleepMs = mainWakeDelayMs(now, lastReadAt, processingSince, loggedOutSince, settlesAt)
                    // No event in time: a read is due, by the safety net or a pending condition.
                    if (kotlinx.coroutines.withTimeoutOrNull(sleepMs) { wake.receive() } == null) stateWanted = true
                }
            }
        }

        // The one question that goes on being asked while the screen is stopped: a login opened in
        // a Custom Tab stops this screen, the tab being on top of it, and the loop above with it —
        // yet it is the loop's Running that closes the tab (LoginTab.finished). So while the screen
        // is stopped with a login link on it, the backend alone is checked on every state event (and
        // by the safety net), until it runs or for as long as the tab would still be closed; nothing
        // at all otherwise. Back in front, the loop takes over.
        LaunchedEffect(pollLifecycle) {
            if (inPreview || demo != null) return@LaunchedEffect
            pollLifecycle.currentStateFlow.collectLatest { state ->
                if (state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) || loginUrl == null) return@collectLatest
                val until = android.os.SystemClock.elapsedRealtime() + MAIN_LOGIN_WATCH_MS
                var seen = DaemonEvents.revision.value
                while (true) {
                    val left = until - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    kotlinx.coroutines.withTimeoutOrNull(minOf(MAIN_SAFETY_MS, left)) {
                        DaemonEvents.revision.first { DaemonEventKind.STATE in it.changedSince(seen) }
                    }
                    seen = DaemonEvents.revision.value
                    val running = withContext(Dispatchers.IO) {
                        runCatching { appctr.Appctr.isRunning() && appctr.Appctr.getBackendState() == "Running" }.getOrDefault(false)
                    }
                    if (running) {
                        context.findActivity()?.let { LoginTab.finished(it) }
                        break
                    }
                }
            }
        }

        DisposableEffect(prefs) {
            prefs.registerOnSharedPreferenceChangeListener(profilePrefsListener)
            // Runs as the effect is applied, before a preview is captured — it
            // would overwrite the exit node a demo handed in.
            if (demo == null) exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
            onDispose {
                prefs.unregisterOnSharedPreferenceChangeListener(profilePrefsListener)
            }
        }

        DisposableEffect(globalPrefs) {
            globalPrefs.registerOnSharedPreferenceChangeListener(globalPrefsListener)
            isTunEnabled = globalPrefs.getBoolean("tun_mode_enabled", false)
            isTunDisplaced = globalPrefs.getBoolean(TunVpnService.DISPLACED_PREF, false)
            isRootYielded = globalPrefs.getBoolean("root_routing_yielded", false)
            isRootShared = globalPrefs.getBoolean("root_routing_shared", false)
            onDispose {
                globalPrefs.unregisterOnSharedPreferenceChangeListener(globalPrefsListener)
            }
        }

        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    onResumed()
                    isTunEnabled = GlobalSettings.isTunModeEnabled(context)
                    isTunDisplaced = TunVpnService.isDisplaced(context)
                    isRootEnabled = GlobalSettings.isRootModeEnabled(context)
                    dnsHasFallback = GlobalSettings.dnsHasFallback(context)
                    isRootYielded = GlobalSettings.isRootRoutingYielded(context)
                    isRootShared = GlobalSettings.isRootRoutingShared(context)
                    // Check battery optimization status (a binder call; not in a preview, which has none).
                    if (!inPreview && demo == null) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        isBatteryOptimizationsIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            pm.isIgnoringBatteryOptimizations(context.packageName)
                        } else {
                            true
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Keyed on the profile's store too: the receiver writes that profile's exit node into the
        // state that holds it, and both are replaced on an account switch (see the watchdog above).
        // The swap is two back-to-back calls on the main thread, once per switch.
        DisposableEffect(context, prefs) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        "STARTING" -> { proxyState = "STARTING"; isProcessing = true }
                        "START" -> {
                            proxyState = "ACTIVE"
                            isProcessing = false
                            exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
                            show410Warning = false
                        }
                        "STOP" -> { proxyState = "STOPPED"; isProcessing = false }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("STARTING")
                addAction("START")
                addAction("STOP")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            onDispose {
                try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            }
        }
    } } } }
}
