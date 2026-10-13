package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import appctr.Appctr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's end of a setup session (appctr/setup.go) on the device that takes
 * the key: the bridge's calls, one at a time and off the main thread, and what
 * becomes of a key the person confirmed.
 *
 * A confirmed key goes the road a typed one goes (the main screen's "Use a
 * key"): into the active profile's `authkey`, the control server beside it if
 * the bundle named one, then a stop and a start of the service, which reads
 * both as it builds the daemon's options. What a typed key does not get is the
 * end of that road: this one is taken out of the settings again once the
 * device is signed in, or once it is clear it will not be — a key that came
 * for one sign-in is not left where Settings shows it. And a control server
 * that came with a key which signed nothing in goes with it: the device is
 * not left pointing at a server the sender chose.
 *
 * Process-wide, not a screen's: the person may leave the screen between the
 * yes and the sign-in, and the key has to go all the same. A process that dies
 * in between leaves a mark, and [sweep] clears the key when the app next
 * starts. Nothing here logs a key, a check code or a server address.
 */
object KeyHandover {
    private const val TAG = "KeyHandover"

    /** In the global preferences: the profile whose `authkey` came by a setup session and is still to be cleared. */
    private const val KEY_PENDING_PROFILE = "setup_key_profile"

    /** Beside it, when the bundle changed the profile's control server: the server it set, and what to put back. */
    private const val KEY_PENDING_SERVER = "setup_key_server"
    private const val KEY_PENDING_OLD_SERVER = "setup_key_old_server"
    private const val KEY_PENDING_WAS_LOGGED_IN = "setup_key_was_logged_in"

    /** How long a key gets to sign the device in before it is given up and cleared. */
    private const val SIGN_IN_WAIT_MS = 120_000L
    private const val LOOK_EVERY_MS = 1_000L

    /** Between the stop and the start, as the typed key's path has it. */
    private const val RESTART_GAP_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One call into the session at a time, in the order they were asked for. */
    private val bridge = Dispatchers.IO.limitedParallelism(1)

    private val _phase = MutableStateFlow(HandoverPhase.IDLE)

    /** Where the confirmed key stands. It outlives the screen that confirmed it. */
    val phase: StateFlow<HandoverPhase> = _phase.asStateFlow()

    private var watcher: Job? = null

    /**
     * The session as it stands, opened if there is none — unless a key is on
     * its way, when the next session waits for that to end.
     */
    suspend fun receive(): SetupReceiveStatus = withContext(bridge) {
        val status = SetupReceiveStatus.parse(runCatching { Appctr.setupReceiveStatus() }.getOrNull())
        if (status.vacant && _phase.value == HandoverPhase.IDLE) {
            SetupReceiveStatus.parse(runCatching { Appctr.setupReceiveStart() }.getOrNull())
        } else status
    }

    /** A new code in place of whatever there was; the outcome of an earlier key is forgotten with it. */
    suspend fun renew(): SetupReceiveStatus = withContext(bridge) {
        forgetOutcome()
        runCatching { Appctr.setupReceiveCancel() }
        SetupReceiveStatus.parse(runCatching { Appctr.setupReceiveStart() }.getOrNull())
    }

    /** The person said no. */
    suspend fun reject(): SetupReceiveStatus = withContext(bridge) {
        SetupReceiveStatus.parse(runCatching { Appctr.setupReceiveReject() }.getOrNull())
    }

    /**
     * The person said yes: the key is taken from the bridge — once, it keeps no
     * copy — written where a typed key goes, and the service restarted. Not
     * tied to the caller: a screen that goes away after the yes does not leave
     * a confirmed key behind in the bridge.
     */
    fun confirm(context: Context) {
        val app = context.applicationContext
        scope.launch(bridge) {
            val status = SetupReceiveStatus.parse(runCatching { Appctr.setupReceiveConfirm() }.getOrNull())
            if (status.state != "confirmed") return@launch
            val key = runCatching { Appctr.setupReceiveTakeKey() }.getOrNull().orEmpty()
            // Too late, by the bridge's clock: the session's status says so.
            if (key.isEmpty()) return@launch
            begin(app, key, status.loginServer)
        }
    }

    /** The screen that drew the code is going away. A confirmed key is left alone (see the bridge). */
    fun close() {
        scope.launch(bridge) { runCatching { Appctr.setupReceiveCancel() } }
    }

    /** A finished handover's outcome has been seen, or is too old to matter. One in progress stays. */
    fun forgetOutcome() {
        _phase.compareAndSet(HandoverPhase.SIGNED_IN, HandoverPhase.IDLE)
        _phase.compareAndSet(HandoverPhase.FAILED, HandoverPhase.IDLE)
    }

    /**
     * Clears a key whose handover this process did not live to finish. Called
     * as the app starts: whether that key signed the device in is no longer
     * known, and it is not left in the settings to find out.
     */
    fun sweep(context: Context) {
        val profile = global(context).getString(KEY_PENDING_PROFILE, null) ?: return
        Log.w(TAG, "A key from a setup session was still stored; clearing it")
        settle(context, profile, key = null, signedIn = false)
    }

    private fun global(context: Context) = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)

    private fun profile(context: Context, id: String) = context.getSharedPreferences("appctr_$id", Context.MODE_PRIVATE)

    private fun begin(app: Context, key: String, loginServer: String) {
        val account = AccountManager.getActiveAccount(app)
        val prefs = profile(app, account.id)
        val oldServer = prefs.getString("login_server", "").orEmpty()
        val changesServer = loginServer.isNotEmpty() && loginServer != oldServer
        // The mark goes to disk before the key does: a process killed between
        // the two has nothing to clear, never a key nobody knows about.
        global(app).edit(commit = true) {
            putString(KEY_PENDING_PROFILE, account.id)
            if (changesServer) {
                putString(KEY_PENDING_SERVER, loginServer)
                putString(KEY_PENDING_OLD_SERVER, oldServer)
                putBoolean(KEY_PENDING_WAS_LOGGED_IN, prefs.getBoolean("was_logged_in", false))
            }
        }
        prefs.edit(commit = true) {
            if (changesServer) {
                // As Settings does when the server is changed by hand.
                putBoolean("was_logged_in", false)
                putString("login_server", loginServer)
            }
            putString("authkey", key)
        }
        _phase.value = HandoverPhase.SIGNING_IN
        restartService(app)
        watcher?.cancel()
        watcher = scope.launch { watch(app, account.id, key) }
        Log.i(TAG, "A key from a setup session is in place; the service restarts with it")
    }

    /** Stop, then start: the way the typed key's Submit restarts the service. */
    private fun restartService(app: Context) {
        runCatching { app.startService(Intent(app, TailscaledService::class.java).apply { action = "STOP_ACTION" }) }
            .onFailure { Log.w(TAG, "Could not stop the service: ${it.javaClass.simpleName}") }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, TailscaledService::class.java).apply { action = "START_ACTION" })
            }.onFailure { Log.w(TAG, "Could not start the service: ${it.javaClass.simpleName}") }
        }, RESTART_GAP_MS)
    }

    /** Looks at the daemon until the key has signed the device in or its time is up, then clears it. */
    private suspend fun watch(app: Context, profileId: String, key: String) {
        var armed = false
        var signedIn = false
        val deadline = System.currentTimeMillis() + SIGN_IN_WAIT_MS
        while (!signedIn && System.currentTimeMillis() < deadline) {
            delay(LOOK_EVERY_MS)
            val alive = runCatching { Appctr.isRunning() }.getOrDefault(false)
            val state = if (alive) runCatching { Appctr.getBackendState() }.getOrDefault("") else ""
            val look = handoverLook(armed, alive, state)
            armed = look.armed
            signedIn = look.signedIn
        }
        withContext(Dispatchers.IO) { settle(app, profileId, key, signedIn) }
        Log.i(TAG, if (signedIn) "Signed in; the key is cleared from the settings" else "Not signed in in time; the key is cleared from the settings")
        _phase.value = if (signedIn) HandoverPhase.SIGNED_IN else HandoverPhase.FAILED
        // The daemon's options still hold the key, and the server if that was
        // put back; applying the settings is how they let go — what erasing the
        // key in Settings does.
        runCatching { app.startService(Intent(app, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" }) }
    }

    /**
     * Takes the key out of [profileId]'s settings, and the mark with it. With
     * [key] given, only that key: one the person typed meanwhile is theirs.
     * Not [signedIn], the server the bundle set goes too and the one before it
     * comes back — unless that was changed again meanwhile.
     */
    private fun settle(context: Context, profileId: String, key: String?, signedIn: Boolean) {
        val global = global(context)
        val prefs = profile(context, profileId)
        val server = global.getString(KEY_PENDING_SERVER, null)
        prefs.edit(commit = true) {
            if (key == null || prefs.getString("authkey", "") == key) remove("authkey")
            if (!signedIn && server != null && prefs.getString("login_server", "") == server) {
                putString("login_server", global.getString(KEY_PENDING_OLD_SERVER, "").orEmpty())
                putBoolean("was_logged_in", global.getBoolean(KEY_PENDING_WAS_LOGGED_IN, false))
            }
        }
        global.edit(commit = true) {
            remove(KEY_PENDING_PROFILE)
            remove(KEY_PENDING_SERVER)
            remove(KEY_PENDING_OLD_SERVER)
            remove(KEY_PENDING_WAS_LOGGED_IN)
        }
    }
}
