package io.github.bropines.tailscaled.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.ui.MainActivity

/**
 * Watches the selected exit node and says so when it drops off the tailnet.
 *
 * An exit node that goes offline takes the device's internet with it — the
 * daemon keeps routing into it, which is the point of an exit node and looks
 * exactly like a dead network. The service's tick already runs, so it asks here
 * whether the node is still online — as the coordination server reports it,
 * and only while this device is itself in touch with that server; after
 * [OFFLINE_GRACE_MS] of "no" a
 * notification names the node and offers two ways out:
 *
 * - **Go direct until it's back** — the exit node is taken off and remembered
 *   as *paused*. The tick keeps looking for it and selects it again the moment
 *   the coordination server reports it online, with nothing for the person to
 *   remember.
 * - **Turn off exit node** — an ordinary "None".
 *
 * Nothing is done without that tap: going direct shows the device's own
 * address to the internet, which is not this code's call to make for someone
 * who picked an exit node to hide it. Settings → Account has a switch that
 * gives the tap in advance, off by default.
 *
 * The pause is the active profile's and survives a restart; any exit-node
 * choice made anywhere else ([ExitNodeApply.select]) ends it.
 */
object ExitNodeGuard {
    private const val TAG = "ExitNodeGuard"
    private const val CHANNEL = "exit_node_guard"
    private const val NOTIF_ID = 0x7E60

    const val ACTION_DIRECT = "io.github.bropines.tailscaled.EXIT_GUARD_DIRECT"
    const val ACTION_OFF = "io.github.bropines.tailscaled.EXIT_GUARD_OFF"
    const val ACTION_FORGET = "io.github.bropines.tailscaled.EXIT_GUARD_FORGET"

    internal const val KEY_PAUSED_ID = "exit_node_paused_id"
    internal const val KEY_PAUSED_IP = "exit_node_paused_ip"
    internal const val KEY_PAUSED_NAME = "exit_node_paused_name"

    /** How long the node must stay offline before anyone is told: the coordination
     *  server flips the flag on a reconnect too, and a blip is not an outage. */
    internal const val OFFLINE_GRACE_MS = 20_000L

    enum class Step {
        /** No exit node and none paused, or the node is online: nothing to say. */
        QUIET,
        /** Offline, but not for long enough yet. */
        WAIT,
        /** Offline past the grace: tell the person. */
        WARN,
        /** A paused node is still away. */
        STAY_PAUSED,
        /** A paused node is online again: select it. */
        RESTORE,
    }

    /**
     * What a tick does, from what it read. [online] is the flag of the node in
     * question — the selected one, or the paused one while nothing is selected —
     * and null when the netmap does not list it at all, which is as offline as
     * a node gets. [offlineForMs] is how long the selected node has been seen
     * offline, this tick included.
     */
    internal fun step(selectedId: String, pausedId: String, online: Boolean?, offlineForMs: Long): Step = when {
        selectedId.isNotEmpty() -> when {
            online == true -> Step.QUIET
            offlineForMs >= OFFLINE_GRACE_MS -> Step.WARN
            else -> Step.WAIT
        }
        pausedId.isNotEmpty() -> if (online == true) Step.RESTORE else Step.STAY_PAUSED
        else -> Step.QUIET
    }

    /** The name a notification calls a node by: its host name, else the first label of its DNS name. */
    internal fun displayName(peer: PeerData?, fallback: String): String =
        peer?.hostName?.takeIf { it.isNotBlank() }
            ?: peer?.dnsName?.substringBefore('.')?.takeIf { it.isNotBlank() }
            ?: fallback

    /** When the selected node was first seen offline in this outage; 0 while it is not. */
    @Volatile private var offlineSince = 0L
    /** The node the offline warning was posted for, so one outage is one notification. */
    @Volatile private var warnedFor = ""
    /** The node the "going direct" notification is up for. */
    @Volatile private var pausedShownFor = ""
    /** The profile all of the above is about; another one starts from nothing. */
    @Volatile private var watchedProfile = ""
    @Volatile private var lastReadAt = 0L

    /** The status with every peer is a LocalAPI round trip and a decode of the whole
     *  tailnet — hundreds of peers with Mullvad — so it is not read on every tick. */
    internal const val READ_INTERVAL_MS = 20_000L

    /** The tick and the notification's buttons both change the choice; one at a time. */
    private val lock = Any()

    /**
     * One look, from the service's refresh tick while the backend is Running
     * and a network is up. Free while no exit node is selected or paused;
     * otherwise one status read every [READ_INTERVAL_MS]. Blocking — call it
     * off the main thread.
     */
    fun onTick(context: Context) {
        val app = context.applicationContext
        val profile = AccountManager.getActiveAccount(app).id
        if (profile != watchedProfile) synchronized(lock) {
            // Another profile's outage, pause and notification are not this one's.
            watchedProfile = profile
            offlineSince = 0L
            lastReadAt = 0L
            dismiss(app)
        }
        val prefs = profilePrefs(app)
        if (prefs.getString("exit_node_id", "").isNullOrEmpty() && prefs.getString(KEY_PAUSED_ID, "").isNullOrEmpty()) {
            synchronized(lock) {
                if (warnedFor.isNotEmpty() || pausedShownFor.isNotEmpty()) dismiss(app)
                offlineSince = 0L
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastReadAt < READ_INTERVAL_MS) return
        lastReadAt = now
        val status = runCatching {
            AppJson.decodeFromString<StatusResponse>(Appctr.getStatusFromAPI())
        }.getOrNull()
        val peers = status?.peers
        // No netmap yet, or no status at all: that says nothing about the node.
        if (peers.isNullOrEmpty()) return
        // "Online" is what the coordination server last said. A daemon that
        // started from its cached netmap, or lost the server on the way, holds
        // yesterday's answer: a node that was offline then would be reported
        // dead over a working tunnel. Judge only while this node hears the server.
        if (status.self?.online != true) {
            offlineSince = 0L
            return
        }

        synchronized(lock) {
            // Read again: a button may have changed the choice while the status was fetched.
            val selectedId = prefs.getString("exit_node_id", "").orEmpty()
            val pausedId = prefs.getString(KEY_PAUSED_ID, "").orEmpty()
            val watchedId = selectedId.ifEmpty { pausedId }
            val peer = peers.values.firstOrNull { it.id == watchedId }
            if (selectedId.isNotEmpty() && peer?.online != true) {
                if (offlineSince == 0L) offlineSince = now
            } else {
                offlineSince = 0L
            }
            val offlineFor = if (offlineSince == 0L) 0L else now - offlineSince

            when (step(selectedId, pausedId, peer?.online, offlineFor)) {
                Step.QUIET -> if (warnedFor.isNotEmpty() || pausedShownFor.isNotEmpty()) dismiss(app)
                Step.WAIT -> Unit
                Step.WARN -> if (warnedFor != selectedId) {
                    val ip = prefs.getString("exit_node_ip", "").orEmpty()
                    val name = displayName(peer, ip)
                    Log.i(TAG, "Exit node $name has been offline for ${offlineFor / 1000} s")
                    Appctr.logAndroid("WARN", "CORE", "Exit node $name is offline — this device has no internet until it returns or another one is chosen")
                    warnedFor = selectedId
                    // Asked for in Settings: the tap was given in advance.
                    if (GlobalSettings.isExitNodeAutoDirect(app)) pause(app) else postOffline(app, name)
                }
                Step.STAY_PAUSED -> if (pausedShownFor != pausedId) {
                    pausedShownFor = pausedId
                    postPaused(app, prefs.getString(KEY_PAUSED_NAME, "").orEmpty())
                }
                Step.RESTORE -> restore(app, prefs, pausedId, peer)
            }
        }
    }

    /** The service stopped: nothing is watched, so nothing is claimed. A pause stays stored. */
    fun onStopped(context: Context) = synchronized(lock) {
        offlineSince = 0L
        lastReadAt = 0L
        dismiss(context.applicationContext)
    }

    /** "Go direct until it's back": the exit node comes off and is remembered. */
    internal fun pause(context: Context): Unit = synchronized(lock) {
        val app = context.applicationContext
        val prefs = profilePrefs(app)
        val id = prefs.getString("exit_node_id", "").orEmpty()
        val ip = prefs.getString("exit_node_ip", "").orEmpty()
        if (!ExitNodeApply.isSelectable(id, ip) || id.isEmpty()) {
            dismiss(app)
            return@synchronized
        }
        val name = runCatching {
            val peers = AppJson.decodeFromString<StatusResponse>(Appctr.getStatusFromAPI()).peers
            displayName(peers?.values?.firstOrNull { it.id == id }, ip)
        }.getOrDefault(ip)
        ExitNodeApply.select(app, "", "")
        // After the select, which ends any pause: this one is the new one.
        prefs.edit {
            putString(KEY_PAUSED_ID, id)
            putString(KEY_PAUSED_IP, ip)
            putString(KEY_PAUSED_NAME, name)
        }
        offlineSince = 0L
        warnedFor = ""
        pausedShownFor = id
        Appctr.logAndroid("INFO", "CORE", "Exit node $name is offline: going direct until it is back, as asked")
        postPaused(app, name)
    }

    /** "Turn off exit node": a plain None, and the warning goes. */
    internal fun turnOff(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        ExitNodeApply.select(app, "", "")
        offlineSince = 0L
        dismiss(app)
    }

    /** "Don't restore": the device stays direct and the node is no longer waited for. */
    internal fun forget(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        profilePrefs(app).edit { clearPause() }
        dismiss(app)
    }

    /** Removes a pause as part of someone else's edit; see [ExitNodeApply.select]. */
    internal fun SharedPreferences.Editor.clearPause() {
        remove(KEY_PAUSED_ID)
        remove(KEY_PAUSED_IP)
        remove(KEY_PAUSED_NAME)
    }

    private fun restore(app: Context, prefs: SharedPreferences, id: String, peer: PeerData?) {
        val ip = prefs.getString(KEY_PAUSED_IP, "").orEmpty()
        val name = displayName(peer, prefs.getString(KEY_PAUSED_NAME, "").orEmpty().ifEmpty { ip })
        // Asked first: a refusal leaves the pause standing, and the next tick tries again.
        when (val result = ExitNodeApply.select(app, id, ip, pushFirst = true)) {
            ExitNodeApply.Result.Applied -> {
                pausedShownFor = ""
                Log.i(TAG, "Exit node $name is back online; selected again")
                Appctr.logAndroid("INFO", "CORE", "Exit node $name is back online — traffic goes through it again")
                postRestored(app, name)
            }
            ExitNodeApply.Result.Incomplete -> {
                // A pause without its address cannot be put back; do not wait for ever.
                prefs.edit { clearPause() }
                dismiss(app)
            }
            is ExitNodeApply.Result.Refused ->
                Log.w(TAG, "The daemon refused to take exit node $name back: ${result.message}")
        }
    }

    private fun dismiss(app: Context) {
        warnedFor = ""
        pausedShownFor = ""
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIF_ID) }
    }

    private fun postOffline(app: Context, name: String) {
        val res = TailscaledService.localized(app)
        val text = res.getString(R.string.exitguard_offline_text, name)
        post(app) {
            setContentTitle(res.getString(R.string.exitguard_offline_title, name))
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setPriority(NotificationCompat.PRIORITY_HIGH)
            addAction(0, res.getString(R.string.exitguard_action_direct), action(app, ACTION_DIRECT))
            addAction(0, res.getString(R.string.exitguard_action_off), action(app, ACTION_OFF))
        }
    }

    private fun postPaused(app: Context, name: String) {
        val res = TailscaledService.localized(app)
        val text = res.getString(R.string.exitguard_paused_text, name)
        post(app) {
            setContentTitle(res.getString(R.string.exitguard_paused_title, name))
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setOnlyAlertOnce(true)
            setSilent(true)
            // A state, not an event: while it stands, the node will come back by itself.
            setOngoing(true)
            addAction(0, res.getString(R.string.exitguard_action_forget), action(app, ACTION_FORGET))
        }
    }

    private fun postRestored(app: Context, name: String) {
        val res = TailscaledService.localized(app)
        post(app) {
            setContentTitle(res.getString(R.string.exitguard_restored_title, name))
            setContentText(res.getString(R.string.exitguard_restored_text))
            setSilent(true)
            setAutoCancel(true)
            setTimeoutAfter(60_000L)
        }
    }

    private fun post(app: Context, build: NotificationCompat.Builder.() -> Unit) {
        try {
            val nm = NotificationManagerCompat.from(app)
            if (!nm.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        TailscaledService.localized(app).getString(R.string.exitguard_channel),
                        // The one alert here that is worth a banner: the phone just lost its internet.
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val tap = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setContentIntent(
                    PendingIntent.getActivity(app, NOTIF_ID, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
                .apply(build)
                .build()
            nm.notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS can be denied; the log line above still says it.
            Log.w(TAG, "Exit-node notification not posted: ${e.message}")
        }
    }

    private fun action(app: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            app, action.hashCode(),
            Intent(app, ExitNodeGuardReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun profilePrefs(context: Context) =
        context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)
}

/** The notification's buttons. Each one talks to the daemon, so the work leaves the main thread. */
class ExitNodeGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ExitNodeGuard.ACTION_DIRECT -> ExitNodeGuard.pause(context)
                    ExitNodeGuard.ACTION_OFF -> ExitNodeGuard.turnOff(context)
                    ExitNodeGuard.ACTION_FORGET -> ExitNodeGuard.forget(context)
                }
            } catch (e: Exception) {
                Log.w("ExitNodeGuard", "${intent.action} failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
