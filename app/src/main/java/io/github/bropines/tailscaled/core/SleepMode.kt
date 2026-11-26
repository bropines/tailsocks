package io.github.bropines.tailscaled.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import appctr.Appctr

/**
 * What the connection does while the phone sleeps (Settings → Background → Keep the
 * connection awake).
 *
 *  - [OFF] — the way the official Tailscale app works: no wake lock at all. In Doze the
 *    daemon freezes with the CPU; the foreground service keeps the process, so nothing
 *    dies, and leaving Doze or the screen coming on nudges the daemon to re-check its
 *    network and relays at once.
 *  - [PERIODIC] — as [OFF], and the phone is woken every [SleepWakeups.INTERVAL_MS] for
 *    [SleepWakeups.WAKE_MS] so the daemon can answer its relay and keepalives: the device
 *    stays reachable from the tailnet more often, at a fraction of the cost of [ALWAYS].
 *  - [ALWAYS] — a wake lock for the whole session; the CPU never sleeps.
 *
 * Stored as two switches, so the remote-management toggle and old backups, which know only
 * `force_bg`, keep working: `force_bg` is [ALWAYS] whatever the other says.
 */
enum class SleepMode {
    OFF, PERIODIC, ALWAYS;

    companion object {
        const val KEY_ALWAYS = "force_bg"
        const val KEY_PERIODIC = "sleep_wakeups"

        fun from(always: Boolean, periodic: Boolean) = when {
            always -> ALWAYS
            periodic -> PERIODIC
            else -> OFF
        }

        fun of(context: Context) = from(
            GlobalSettings.getBoolean(context, KEY_ALWAYS, false),
            GlobalSettings.getBoolean(context, KEY_PERIODIC, false),
        )

        fun set(context: Context, mode: SleepMode) {
            context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ALWAYS, mode == ALWAYS)
                .putBoolean(KEY_PERIODIC, mode == PERIODIC)
                .apply()
        }
    }
}

/**
 * The short wake-ups of [SleepMode.PERIODIC]: an alarm allowed in Doze, a wake lock that
 * lets itself go after [WAKE_MS], and a nudge to the daemon. Android lets an app's
 * Doze-time alarms through about once in nine minutes, so a shorter interval would only
 * be batched.
 */
object SleepWakeups {
    private const val TAG = "SleepWakeups"
    private const val REQUEST_CODE = 4713
    const val ACTION = "io.github.bropines.tailscaled.SLEEP_WAKEUP"
    const val INTERVAL_MS = 10 * 60 * 1000L
    const val WAKE_MS = 20_000L

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context, SleepWakeupReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (ServiceWatchdog.canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sleep wake-up not scheduled: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    /**
     * Wakes the daemon's network monitor, and reconnects the relays if it says they are
     * unreachable (appctr NetworkBecameUsable). Off the caller's thread; never blocks it.
     */
    fun nudgeDaemon(context: Context, why: String) {
        Thread {
            if (GlobalSettings.isRootModeEnabled(context)) {
                runCatching { RootUtils.wakeRootDaemon(java.io.File(context.filesDir, "tailscaled.sock").absolutePath) }
            }
            runCatching { Appctr.networkBecameUsable() }
            Log.i(TAG, "Daemon nudged: $why")
        }.start()
    }
}

/** One [SleepWakeups] wake-up: the CPU for a few seconds, the daemon nudged, the next one set. */
class SleepWakeupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SleepWakeups.ACTION) return
        if (SleepMode.of(context) != SleepMode.PERIODIC || !ProxyState.isUserLetRunning(context)) {
            SleepWakeups.cancel(context)
            return
        }
        SleepWakeups.schedule(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Awake by itself only while the screen is off; a lit screen keeps the CPU up anyway.
        if (!pm.isInteractive) {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::SleepWakeup").apply {
                setReferenceCounted(false)
                acquire(SleepWakeups.WAKE_MS)
            }
        }
        NetTrace.event(context, "sleep wake-up (idle=${pm.isDeviceIdleMode})")
        SleepWakeups.nudgeDaemon(context, "sleep wake-up")
    }
}
