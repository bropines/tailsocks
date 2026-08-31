package io.github.bropines.tailscaled.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Periodic revival check for [TailscaledService].
 *
 * `START_STICKY` covers the case where Android reclaims memory, but aggressive
 * OEM task killers stop the service outright and never bring it back. An alarm
 * outlives the process, so it can notice that the user still wants a connection
 * and start the service again.
 *
 * This is a safety net, not a guarantee: on skins that block background starts
 * the revival is refused, and only the OEM's own autostart permission helps.
 */
object ServiceWatchdog {
    private const val TAG = "ServiceWatchdog"
    private const val REQUEST_CODE = 4711

    /** How often to check. Inexact, so the system may batch it. */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    const val ACTION_CHECK = "io.github.bropines.tailscaled.WATCHDOG_CHECK"

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).apply { action = ACTION_CHECK }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context) {
        if (!GlobalSettings.isServiceWatchdogEnabled(context)) {
            cancel(context)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            // Inexact on purpose: an exact alarm would need a permission that a
            // background keep-alive has no business asking for.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule watchdog: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel watchdog: ${e.message}")
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServiceWatchdog.ACTION_CHECK) return

        val wantsRunning = ProxyState.isUserLetRunning(context)
        if (wantsRunning && !ProxyState.isActualRunning(context)) {
            Log.w("ServiceWatchdog", "Service is gone while the user wants it running, reviving")
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                )
                appctr.Appctr.logAndroid("WARN", "CORE", "Service was killed in the background and has been restarted")
            } catch (e: Exception) {
                // Android 12+ can refuse a background foreground-service start.
                Log.e("ServiceWatchdog", "Revival refused by the system: ${e.message}")
                appctr.Appctr.logAndroid(
                    "ERROR", "CORE",
                    "Service was killed and the system refused to restart it in the background. " +
                        "Grant the app autostart permission in the system settings."
                )
            }
        }

        // Alarms are one-shot; queue the next check.
        if (wantsRunning) ServiceWatchdog.schedule(context) else ServiceWatchdog.cancel(context)
    }
}
