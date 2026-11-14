package io.github.bropines.tailscaled.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.bropines.tailscaled.ui.FilesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/*
 * What the three transfers have in common — a file coming in over Taildrop (TaildropProgress),
 * files going out (TaildropOutbox) and Taildrive downloads (TaildriveDownloads): how a rate is
 * smoothed, how a progress notification is built, how a channel is made, and the tap that opens
 * the Files page on Taildrop. One copy each, so the three cannot drift apart again.
 */

/** The moving average the transfers measure their speed with. Pure. */
object TransferRate {
    /** Time constant of the average: a change of speed shows within a few seconds, a stutter does not. */
    const val TAU_MS = 3_000.0

    /** Gaps shorter than this between two reports (another transfer's tick) are not measured. */
    const val MIN_SAMPLE_MS = 700L

    /**
     * [previous] moved towards [sample] (bytes per second, measured over [dtMs]) by the weight a
     * gap of that length earns: an exponential average over time, not over reports, so a report
     * that comes late counts for more. The first sample is taken as it is.
     */
    fun smoothed(previous: Double?, sample: Double, dtMs: Long, tauMs: Double = TAU_MS): Double {
        if (previous == null) return sample
        val weight = 1 - exp(-dtMs / tauMs)
        return previous + weight * (sample - previous)
    }
}

/**
 * Runs [step] every [periodMs] while [busy] says there is something to do, and stops by itself
 * when there is not; [start] starts it again. A start is never lost: the loop decides to stop
 * and clears itself under the same lock [start] takes, so a start that finds it still set is
 * one the loop will still see — checking "is the job still active" instead let a start land in
 * the gap between the loop's last look and its end, and the work then ran with no ticks.
 */
internal class BusyTicker(
    private val scope: CoroutineScope,
    private val periodMs: Long,
    private val busy: () -> Boolean,
    private val step: () -> Unit
) {
    private var job: Job? = null

    fun start() = synchronized(this) {
        if (job != null) return@synchronized
        job = scope.launch {
            try {
                while (true) {
                    step()
                    synchronized(this@BusyTicker) {
                        if (!busy()) {
                            job = null
                            return@launch
                        }
                    }
                    delay(periodMs)
                }
            } finally {
                // A step that threw ends the loop too; the next start begins a new one.
                val self = coroutineContext[Job]
                synchronized(this@BusyTicker) { if (job === self) job = null }
            }
        }
    }
}

object TransferNotifications {
    /** The name each channel was last made with: made once, and again only when its name changes (the app's language). */
    private val channels = ConcurrentHashMap<String, String>()

    /**
     * Makes channel [id] called [name]. Asking the system each time cost a binder call per
     * redraw; a channel made again with another name is renamed, which is what a change of the
     * app's language needs, so that is the one case it is asked again.
     */
    fun ensureChannel(nm: NotificationManager, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (channels[id] == name) return
        nm.createNotificationChannel(NotificationChannel(id, name, importance))
        channels[id] = name
    }

    /**
     * An ongoing progress notification: a plain progress bar before Android 16, a Live Update on
     * it — the status bar chip, HyperOS's island — granted by POST_PROMOTED_NOTIFICATIONS in the
     * manifest. [percent] is 0..100, or null for an indeterminate bar. [action] is the one button
     * (Cancel) when there is one. The caller builds it, so it may still add to it.
     */
    fun progress(
        context: Context,
        channel: String,
        icon: Int,
        title: String,
        text: String,
        sub: String?,
        percent: Int?,
        tap: PendingIntent,
        action: Pair<String, PendingIntent>? = null
    ): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setStyle(NotificationCompat.ProgressStyle().setProgress(percent ?: 0).setProgressIndeterminate(percent == null))
            .setRequestPromotedOngoing(true)
        action?.let { (label, intent) -> b.addAction(0, label, intent) }
        if (percent != null) b.setShortCriticalText("$percent%")
        return b
    }

    /** The Files page, opened on Taildrop. */
    fun taildropFilesIntent(context: Context): Intent =
        Intent(context, FilesActivity::class.java)
            .putExtra(FilesActivity.EXTRA_OPEN_TAILDROP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** A tap that opens [taildropFilesIntent]; [requestCode] keeps one notification's tap apart from another's. */
    fun taildropFilesTap(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, taildropFilesIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
