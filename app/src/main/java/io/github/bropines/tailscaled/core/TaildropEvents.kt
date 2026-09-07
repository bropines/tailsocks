package io.github.bropines.tailscaled.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import appctr.Appctr
import appctr.TaildropListener
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.ui.FilesActivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * What the app does when the daemon finishes receiving a Taildrop file.
 *
 * The daemon runs in direct mode (TS_TAILDROP_DIR is set), so it writes the file
 * itself and there is no inbox to poll — `/localapi/v0/files/` is `null` forever.
 * The one thing that announces an arrival is `Notify.IncomingFiles` on the IPN
 * bus, which the Go bridge already streams and forwards here through
 * [TaildropListener]: one callback per notification (about one a second per
 * transfer in flight) and a final one where the finished file carries
 * `Done=true` and `FinalPath`. The daemon drops the transfer right after that
 * notification, so the Done entry is seen exactly once; this object turns it
 * into a broadcast (the Files hub re-reads the directory) and a notification.
 *
 * The listener is installed once by the service and survives daemon restarts on
 * the Go side (it is not part of the bridge's per-daemon resources).
 */
object TaildropEvents {
    private const val TAG = "TaildropEvents"

    /** In-app broadcast: a received file is complete. Extras: [EXTRA_NAME], [EXTRA_PATH]. */
    const val ACTION_RECEIVED = "io.github.bropines.tailscaled.TAILDROP_RECEIVED"
    const val EXTRA_NAME = "name"
    const val EXTRA_PATH = "path"

    private const val CHANNEL_ID = "tailsocks_taildrop"
    private const val NOTIF_BASE = 0x7D00

    /** One element of the bridge's IncomingFiles JSON (appctr.BusPartialFile, mirroring ipn.PartialFile). */
    @Serializable
    data class IncomingFile(
        val Name: String = "",
        val Started: String? = null,
        val DeclaredSize: Long = -1,
        val Received: Long = 0,
        val PartialPath: String? = null,
        val FinalPath: String? = null,
        val Done: Boolean = false
    )

    private val lock = Any()
    /**
     * Transfers already announced, keyed on FinalPath + Started. The daemon reports the
     * Done entry in exactly one Notify, so this guards only the corner where another
     * transfer's 1/s tick lands between the rename and the daemon forgetting the finished
     * one. It must NOT be keyed on FinalPath alone: when the user deletes a received
     * file and the same name is sent again, the daemon reuses the bare name
     * (feature/taildrop/ext.go: no collision, no " (1)"), so FinalPath repeats and the
     * second arrival would be swallowed until this map evicted the first.
     */
    private val announced = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 64
    }

    /**
     * True while FilesActivity is resumed on the Taildrop Inbox page (set by FilesScreen).
     * The in-app broadcast still fires so the list refreshes; only the heads-up
     * notification is skipped, since the user is already looking at the new file.
     */
    @Volatile var inboxVisible: Boolean = false

    fun attach(context: Context) {
        val app = context.applicationContext
        Appctr.setTaildropListener(TaildropListener { json -> onIncomingFiles(app, json) })
    }

    fun detach() {
        try { Appctr.setTaildropListener(null) } catch (e: Exception) { Log.w(TAG, "detach: ${e.message}") }
    }

    /** Runs on the bridge's bus goroutine: decode, pick out the finished files, leave. */
    private fun onIncomingFiles(context: Context, json: String) {
        if (json.isBlank() || json == "[]") return
        val files = runCatching { AppJson.decodeFromString<List<IncomingFile>>(json) }
            .getOrElse { Log.w(TAG, "IncomingFiles unreadable: ${it.message}"); return }
        val done = files.filter { it.Done && !it.FinalPath.isNullOrEmpty() }
        if (done.isEmpty()) return
        val fresh = synchronized(lock) { done.filter { announced.put(it.FinalPath!! + "|" + (it.Started ?: ""), true) == null } }
        if (fresh.isEmpty()) return
        Thread({ fresh.forEach { announce(context, it) } }, "taildrop-received").start()
    }

    private fun announce(context: Context, f: IncomingFile) {
        val finalPath = f.FinalPath ?: return
        // The daemon may have renamed on collision ("photo (1).jpg"); the path is the truth.
        val name = File(finalPath).name
        val sender = senderName(f.PartialPath)
        Log.i(TAG, "Received $name" + (sender?.let { " from $it" } ?: ""))
        context.sendBroadcast(
            Intent(ACTION_RECEIVED).setPackage(context.packageName)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PATH, finalPath)
        )
        if (inboxVisible) {
            Log.d(TAG, "Inbox on screen; skipping the notification for $name")
            return
        }
        postNotification(context, name, finalPath, sender)
    }

    /**
     * Who sent it, from the one place the bus states it: in direct mode the partial is
     * named `<name>.<sender StableNodeID>.partial` (feature/taildrop/send.go). Resolved to
     * a display name through /status; null when anything along the way is missing.
     */
    private fun senderName(partialPath: String?): String? {
        val base = partialPath?.let { File(it).name } ?: return null
        if (!base.endsWith(".partial")) return null
        val id = base.removeSuffix(".partial").substringAfterLast('.', "")
        if (id.isEmpty()) return null
        return runCatching {
            val json = Appctr.getStatusFromAPI()
            if (json.isBlank() || json.startsWith("Error")) null
            else AppJson.decodeFromString<StatusResponse>(json).peers?.values?.firstOrNull { it.id == id }?.getDisplayName()
        }.getOrNull()
    }

    private fun postNotification(context: Context, name: String, path: String, sender: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.taildrop_notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val id = NOTIF_BASE + (path.hashCode() and 0xFF)
        val open = Intent(context, FilesActivity::class.java)
            .putExtra(FilesActivity.EXTRA_OPEN_TAILDROP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (sender != null) context.getString(R.string.taildrop_received_from_format, name, sender) else name
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.taildrop_received_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // Without POST_NOTIFICATIONS on 13+ the system drops it silently; nothing to do here.
        try { nm.notify(id, notification) } catch (e: Exception) { Log.w(TAG, "notify: ${e.message}") }
    }
}
