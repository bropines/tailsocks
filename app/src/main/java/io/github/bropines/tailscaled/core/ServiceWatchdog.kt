package io.github.bropines.tailscaled.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.MainActivity

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

    /** Set while a background revival was refused and nothing has run since. */
    const val KEY_REVIVAL_REFUSED = "revival_refused"

    /** Extra on the MainActivity intent behind the "tap to reconnect" notification. */
    const val EXTRA_RESUME_SERVICE = "resume_service"

    private const val NOTIF_ID = 4712
    private const val NOTIF_CHANNEL = "tailsocks_alerts"

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
            // An exact alarm's broadcast is briefly exempt from the Android 12+
            // ban on starting a foreground service from the background, which is
            // the whole job of this alarm. The permission is optional: without it
            // the inexact alarm still fires, only later and without the exemption.
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
            } else {
                scheduleInexact(am, triggerAt, context)
            }
        } catch (e: Exception) {
            // Includes a SecurityException from the permission being revoked
            // between the check and the call.
            Log.w(TAG, "Failed to schedule watchdog: ${e.message}")
            try {
                scheduleInexact(am, triggerAt, context)
            } catch (e2: Exception) {
                Log.w(TAG, "Inexact fallback failed too: ${e2.message}")
            }
        }
    }

    private fun scheduleInexact(am: AlarmManager, triggerAt: Long, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
        }
    }

    /** Whether SCHEDULE_EXACT_ALARM is granted; always true before Android 12. */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return try { am.canScheduleExactAlarms() } catch (e: Exception) { false }
    }

    /**
     * Records that the system refused to start the service in the background and
     * offers the user the one start that is always allowed: a tap.
     *
     * Called once per outage — a refused start repeats every 15 minutes, and one
     * notification per tick would be a nag.
     */
    fun noteRevivalRefused(context: Context) {
        if (GlobalSettings.getBoolean(context, KEY_REVIVAL_REFUSED, false)) return
        GlobalSettings.setBoolean(context, KEY_REVIVAL_REFUSED, true)
        postReviveNotification(context)
    }

    /**
     * Clears the outage: the service runs again, or the user has seen the message.
     * Called on every start and stop, so it does nothing when there is no outage.
     */
    fun clearRevivalRefused(context: Context) {
        if (!GlobalSettings.getBoolean(context, KEY_REVIVAL_REFUSED, false)) return
        GlobalSettings.setBoolean(context, KEY_REVIVAL_REFUSED, false)
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel the revive notification: ${e.message}")
        }
    }

    private fun postReviveNotification(context: Context) {
        try {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(
                    NotificationChannel(
                        NOTIF_CHANNEL,
                        context.getString(R.string.notif_channel_alerts),
                        // The connection is already down; a heads-up banner on top
                        // of that would turn every OEM kill into an interruption.
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val tap = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_RESUME_SERVICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL)
                .setContentTitle(context.getString(R.string.revive_refused_title))
                .setContentText(context.getString(R.string.revive_refused_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.revive_refused_text)))
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, NOTIF_ID, tap,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            nm.notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS can be denied; the flag still survives and the
            // hint on the next app launch carries the same message.
            Log.w(TAG, "Failed to post the revive notification: ${e.message}")
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
                // That log line lives in a ring buffer nobody looks at while the
                // connection is quietly down. Put it on screen instead, with the
                // one start the system never refuses: a tap.
                ServiceWatchdog.noteRevivalRefused(context)
            }
        }

        // Alarms are one-shot; queue the next check.
        if (wantsRunning) ServiceWatchdog.schedule(context) else ServiceWatchdog.cancel(context)
    }
}
