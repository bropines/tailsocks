package io.github.bropines.tailscaled.core

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.DeepLinks
import io.github.bropines.tailscaled.ui.MainActivity
import io.github.bropines.tailscaled.ui.RemoteElevateActivity
import kotlinx.serialization.Serializable
import java.io.File
import java.text.DateFormat
import java.util.Date

/*
 * Remote management, full rights for a guest for a while (appctr/remote_elevate.go has the
 * model and its reasons): the switch on the managed device's page that allows them, the
 * bridge's calls that give and take them, and what the device says while they last — a
 * notification that names who has them and until when.
 *
 * Nothing here gives anything on its own: RemoteElevateActivity asks the device's lock first,
 * or, where the device has none, a person in front of it, and only then calls [RemoteElevate.grant].
 */

/** One guest's full rights in force: RemotePairedJSON's "elevated", and the bridge's "elevations" request. */
@Serializable
data class RemoteElevation(
    /** The guest's node and name; empty in the paired list, where the row has them. */
    val nodeId: String = "",
    val name: String = "",
    /** Seconds they have left; [until] the same moment as Unix seconds by this device's clock; [seconds] the length given. */
    val left: Long = 0,
    val until: Long = 0,
    val seconds: Long = 0,
    /** The full-control tier is part of them. */
    val full: Boolean = false,
)

/** RemoteElevate and RemoteElevateEnd. */
@Serializable
data class RemoteElevateAnswer(
    val ok: Boolean = false,
    /** not_paired, not_guest, off, no_guests, bad_request, not_elevated, not_saved; bad_answer from this side. */
    val error: String = "",
    val left: Long = 0,
    val until: Long = 0,
    val seconds: Long = 0,
    val full: Boolean = false,
) {
    companion object {
        const val NOT_SAVED = "not_saved"
        const val NOT_ELEVATED = "not_elevated"
    }
}

/** The lengths full rights are given and asked for, in seconds, as the bridge takes them (remoteElevateLengths). */
object RemoteElevateLengths {
    /** "Until the guest's pairing ends": a day at most. */
    const val UNTIL_PAIRING_ENDS = 0L
    const val MAX = 86_400L
    val ALL = listOf(15 * 60L, 60 * 60L, 8 * 60 * 60L, UNTIL_PAIRING_ENDS)

    /** What is offered first: long enough to set a device up, short enough to be forgotten safely. */
    const val DEFAULT = 60 * 60L

    /** What a [choice] gives a pairing with [pairingLeft] seconds left (-1: until revoked): the bridge's own rule. */
    fun effective(choice: Long, pairingLeft: Long): Long {
        var length = if (choice in 1 until MAX) choice else MAX
        if (pairingLeft in 0 until length) length = pairingLeft
        return length
    }

    /**
     * The choices worth offering a pairing with [pairingLeft] seconds left: one it does not have
     * is "until it ends" by another name, and is left out.
     */
    fun offered(pairingLeft: Long): List<Long> = ALL.filter { it == UNTIL_PAIRING_ENDS || pairingLeft < 0 || it < pairingLeft }

    /** [choice] if it is one of the lengths, else null. */
    fun of(choice: Long): Long? = choice.takeIf { it in ALL }

    /**
     * A length in one unit, as it was chosen: whole hours as hours — an hour is "1 h", a day
     * "24 h" — and anything else as [remoteDuration] rounds it.
     */
    fun unit(seconds: Long): Pair<Long, RemoteDurationUnit> =
        if (seconds > 0 && seconds % 3_600 == 0L) seconds / 3_600 to RemoteDurationUnit.HOURS else remoteDuration(seconds)
}

/**
 * The switch "let guests get full rights for a while" and the bridge's two calls. The switch is a
 * file under files/remote/ like the page's other switches (RemoteLevel): out of every backup, on
 * unless a person turned it off.
 */
object RemoteElevate {
    private const val TAG = "RemoteElevate"
    private const val OFF_FILE = "elevate.off"

    private fun file(context: Context) = File(File(context.filesDir, "remote"), OFF_FILE)

    fun isAllowed(context: Context): Boolean = !file(context).isFile

    /**
     * Turns the switch and tells the bridge at once — off ends the rights in force — and a running
     * service with the next settings it applies. False when the file could not be written.
     */
    fun setAllowed(context: Context, allowed: Boolean): Boolean {
        val file = file(context)
        val done = try {
            if (!allowed) {
                file.parentFile?.mkdirs()
                file.createNewFile() || file.isFile
            } else {
                !file.exists() || file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "The switch could not be written: ${e.message}")
            false
        }
        if (!done) return false
        runCatching { Appctr.remoteAllowElevation(allowed) }
        if (ProxyState.isActualRunning(context)) TailscaledService.requestApplySettings(context)
        RemoteElevationNotice.refresh(context)
        return true
    }

    /** Gives the guest paired as [nodeId] full rights. Only after the device's lock, or a person at a device without one. Blocking. */
    fun grant(context: Context, nodeId: String, seconds: Long, full: Boolean): RemoteElevateAnswer {
        val answer = RemoteJson.decode<RemoteElevateAnswer>(Appctr.remoteElevate(nodeId, seconds, full))
            ?: RemoteElevateAnswer(error = "bad_answer")
        RemoteElevationNotice.refresh(context)
        return answer
    }

    /** Takes them back at once. Blocking. */
    fun takeBack(context: Context, nodeId: String): RemoteElevateAnswer {
        val answer = RemoteJson.decode<RemoteElevateAnswer>(Appctr.remoteElevateEnd(nodeId))
            ?: RemoteElevateAnswer(error = "bad_answer")
        RemoteElevationNotice.refresh(context)
        return answer
    }

    /** The rights in force, each with its guest, from the bridge's list. Blocking. */
    fun inForce(): List<RemoteElevation> = elevations(RemoteJson.paired(Appctr.remotePairedJSON()))

    /** The rights in [devices], each with its guest. */
    fun elevations(devices: List<RemotePairedDevice>): List<RemoteElevation> =
        devices.mapNotNull { d -> d.elevated?.copy(nodeId = d.nodeId, name = d.name) }

    /** Whether the device has a lock to confirm with: a PIN, a pattern or a password. */
    fun deviceSecure(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)
}

/**
 * While a guest has full rights, the notification says who and until when — one for all of
 * them — and goes by itself when the last of them ends. Quiet: it is a state, not news; the news
 * was the request, and that had its own (RemoteDevicePrompts). Tapping it opens remote
 * management in Settings; with one guest it has "Take back" as well.
 */
object RemoteElevationNotice {
    private const val TAG = "RemoteElevationNotice"
    private const val CHANNEL_ID = "tailsocks_remote_rights"
    private const val NOTIF_ID = 0x7E50

    /** What was put up last, to leave an unchanged notification alone. Guarded by itself. */
    private val shown = arrayOfNulls<List<RemoteElevation>>(1)

    /** Reads the bridge and shows what it says. Blocking. */
    fun refresh(context: Context) {
        val list = runCatching { RemoteElevate.inForce() }.getOrNull() ?: return
        show(context, list)
    }

    /**
     * Shows [list], or takes the notification down when it is empty. The times of a list are
     * by this device's clock; two lists that differ only in seconds left are the same one.
     */
    fun show(context: Context, list: List<RemoteElevation>) {
        val app = context.applicationContext
        val key = list.map { it.copy(left = 0) }
        synchronized(shown) {
            if (shown[0] == key) return
            shown[0] = key
        }
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (list.isEmpty()) {
            runCatching { manager.cancel(NOTIF_ID) }
            return
        }
        // In the language picked in Settings: the application's own context is in the system's.
        // Called on the bridge's threads as well, so the prompts' way and not the activities'.
        val res = RemoteDevicePrompts.inAppLanguage(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, res.getString(R.string.remote_elevate_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val lines = list.map { e -> RemoteElevateText.holds(res, e) }
        val title = if (list.size == 1) res.getString(R.string.remote_elevate_notif_one, list[0].name)
        else res.resources.getQuantityString(R.plurals.remote_elevate_notif_many, list.size, list.size)
        val text = lines.joinToString("\n")
        val open = PendingIntent.getActivity(
            app, NOTIF_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("${DeepLinks.SCHEME}://settings/advanced/remote_management"), app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(title)
            .setContentText(if (list.size == 1) RemoteElevateText.until(res, list[0]) else text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            // It goes with the last of them, whether or not anyone tells it.
            .setTimeoutAfter(list.maxOf { it.left }.coerceAtLeast(1) * 1000)
        if (list.size == 1) {
            val takeBack = PendingIntent.getActivity(
                app, NOTIF_ID + 1, RemoteElevateActivity.takeBackIntent(app, list[0].nodeId, list[0].name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, res.getString(R.string.remote_elevate_take_back), takeBack)
        }
        // Without POST_NOTIFICATIONS on 13+ the system drops it: the page and the log still say it.
        if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            try { manager.notify(NOTIF_ID, builder.build()) } catch (e: Exception) { Log.w(TAG, "notify: ${e.message}") }
        }
    }
}

/** How full rights are said, the same in the notification, the bar and the dialog. */
object RemoteElevateText {
    /** "until 18:30", by this device's clock and in its way of writing a time. */
    fun clock(context: Context, unixSeconds: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(unixSeconds * 1000))

    fun until(context: Context, e: RemoteElevation): String {
        val time = clock(context, e.until)
        return context.getString(if (e.full) R.string.remote_elevate_until_full else R.string.remote_elevate_until, time)
    }

    /** "ann-iphone: until 18:30, with full control". */
    fun holds(context: Context, e: RemoteElevation): String = context.getString(R.string.remote_elevate_holds, e.name, until(context, e))

    /** A length in words: "15 min", "1 h", "until the pairing ends". */
    fun length(context: Context, seconds: Long): String {
        if (seconds == RemoteElevateLengths.UNTIL_PAIRING_ENDS) return context.getString(R.string.remote_elevate_len_pairing)
        val (count, unit) = RemoteElevateLengths.unit(seconds)
        return context.getString(
            when (unit) {
                RemoteDurationUnit.DAYS -> R.string.remote_dur_days
                RemoteDurationUnit.HOURS -> R.string.remote_dur_hours
                RemoteDurationUnit.MINUTES -> R.string.remote_dur_minutes
            },
            count.toInt()
        )
    }
}
