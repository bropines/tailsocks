package io.github.bropines.tailscaled.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.TaildriveActivity
import java.io.InputStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Taildrive downloads as they run on the device: the queue of core/TaildriveDownloadQueue.kt given
 * Android — the files read the way a player gets them, the folder written through its grant —
 * off the main thread and outside any screen, with a notification that shows the whole queue and
 * cancels it.
 *
 * Where they run. Not in a service of their own: Taildrive is there only while the node runs, and
 * the node is TailscaledService, a foreground service of this process — so the process a download
 * runs in is kept whichever screen is open, or none. A Stop ends Taildrive and so the downloads;
 * they say so, and what was saved stays.
 *
 * What is read. The file goes the way it goes to a player that opens it (TailsocksFileProvider):
 * byte ranges through the bridge to the daemon's Taildrive socket (RangedFile, read ahead while the
 * file is written), a few megabytes at a time, or the provider's plain stream when the server keeps
 * to whole files. Nothing is held whole in memory,
 * and a read that fails is asked for again before the file is given up.
 *
 * Nothing here logs a name or an address of a file.
 */

/** The listing of the browser as a walk takes it: the entries of [at], never another place. */
internal fun TaildriveBrowse.listEntries(at: DriveLocation): DriveResult<List<DriveEntry>> = when (val listed = list(at)) {
    is DriveResult.Ok -> DriveResult.Ok(listed.value.entries)
    is DriveResult.Err -> listed
}

object TaildriveDownloads {
    private const val TAG = "TaildriveDownloads"
    private const val CHANNEL_ID = "tailsocks_taildrive_downloads"
    /** Above Taildrop's ids (0x7CFF and 0x7D00 + 0..0xFF). */
    private const val PROGRESS_ID = 0x7E00
    private const val DONE_BASE = 0x7E01
    private const val ACTION_CANCEL = BuildConfig.APPLICATION_ID + ".TAILDRIVE_DOWNLOAD_CANCEL"
    private const val EXTRA_ID = "download_id"

    /** How often the notification is redrawn and the rates measured. */
    private const val TICK_MS = 1_000L
    /** A failed read is asked for again this many times, after a pause that grows, before the file is given up. */
    private const val READ_RETRIES = 3
    private const val RETRY_PAUSE_MS = 1_000L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "download failed: ${e.javaClass.simpleName}") }
    )

    /** Set by the first [start]: nothing of the world is asked for before a download is. */
    @Volatile
    private var app: Context? = null

    private fun context(): Context = app ?: error("no download was started")

    private val world = object : DownloadWorld {
        override fun slots(): Int = app?.let { GlobalSettings.getTaildriveParallelDownloads(it) } ?: DownloadSlots.DEFAULT
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun sink(folder: ReceiveFolder): DownloadSink = DownloadSink.of(context(), folder)
        override fun label(folder: ReceiveFolder): String? = runCatching { TaildropFolder.label(context(), folder) }.getOrNull()
        override fun source(request: DownloadRequest): DownloadSource = AccountSource(context(), request.accountId)
        override fun nodeRunning(): Boolean = ProxyState.isActualRunning(context())

        override fun started(state: DownloadState) =
            log("INFO", "Taildrive: download of ${state.selected} selected into ${state.folderLabel} started")

        override fun ended(state: DownloadState) {
            val how = if (state.stage == DownloadStage.STOPPED) "stopped by the node" else "finished"
            log("INFO", "Taildrive: download $how: ${state.saved} saved, ${state.failures.size} missing")
            app?.let {
                postFinished(it, state)
                render(it)
            }
        }

        override fun log(message: String) {
            Log.w(TAG, message)
        }
    }

    private val queue = DownloadQueue(world, scope)

    /** Every download that is running, waiting, or over and not yet put away from the page, in the order asked. */
    val downloads: StateFlow<List<DownloadState>> = queue.downloads

    @Volatile
    private var receiverRegistered = false
    private var ticker: Job? = null

    /** Queues [request]; returns its id. Returns at once. */
    fun start(context: Context, request: DownloadRequest): Int {
        val appContext = context.applicationContext
        app = appContext
        registerCancel(appContext)
        val id = queue.add(request, TaildropFolder.quickLabel(request.folder) ?: "…")
        startTicker(appContext)
        return id
    }

    /** Stops download [id]: a waiting one leaves the queue; a running one loses the files in flight, and what was saved stays. */
    fun cancel(id: Int) = queue.cancel(id)

    /** [cancel] of every download that runs or waits: the notification's Cancel. */
    fun cancelAll() = queue.cancelAll()

    /** Takes a finished download off the page. */
    fun dismiss(id: Int) = queue.dismiss(id)

    /** Takes every finished download off the page. */
    fun dismissFinished() = queue.dismissFinished()

    /** "Parallel downloads" changed: a slot it frees is given at once. */
    fun slotsChanged() = queue.slotsChanged()

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    /** One account's shares: the browser's listing, and a file the way a player gets it. */
    private class AccountSource(context: Context, accountId: String) : DownloadSource {
        private val client = TaildriveClient(context.applicationContext, accountId)
        private val browse = TaildriveBrowse.forAccount(context, accountId)

        override fun list(at: DriveLocation): DriveResult<List<DriveEntry>> = browse.listEntries(at)

        /** Ranges when the server has them, the plain stream otherwise. */
        override fun open(path: String): DriveFileReader {
            val ranged = openRangedFile({ client.rangeUpstream(path) })
            return if (ranged != null) RangedReader(ranged) else StreamReader(client.getDownloadStream(path))
        }
    }

    /** [RangedFile] read from the start; a failed read is asked for again a few times, as a player would. */
    private class RangedReader(private val file: RangedFile) : DriveFileReader {
        private var position = 0L
        override val size: Long = file.size

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            var tries = 0
            while (true) {
                try {
                    val n = file.read(position, buffer, offset, length)
                    if (n <= 0) return -1
                    position += n
                    return n
                } catch (e: RangeException) {
                    val again = e is RangeTimeoutException || e is RangeIoException
                    if (!again || ++tries > READ_RETRIES) throw e
                    try {
                        Thread.sleep(RETRY_PAUSE_MS * tries)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RangeClosedException()
                    }
                }
            }
        }

        override fun close() = file.close()
    }

    /** A server without ranges: the whole file in one answer, its length not known here. */
    private class StreamReader(private val input: InputStream) : DriveFileReader {
        override val size: Long = -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)
        override fun close() = input.close()
    }

    // ---------------------------------------------------------------------------------------------
    // The notification
    // ---------------------------------------------------------------------------------------------

    /** Once a second while anything runs or waits: the rates, and the progress notification. */
    private fun startTicker(context: Context) = synchronized(this) {
        if (ticker?.isActive == true) return@synchronized
        ticker = scope.launch {
            while (true) {
                queue.tick()
                render(context)
                if (!queue.active) break
                delay(TICK_MS)
            }
        }
    }

    private fun channel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.taildrive_download_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun browseTap(context: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        TaildriveActivity.browseIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * The queue as one notification: the files in flight, how far the running downloads have got
     * together and how fast, how many wait — and Cancel, which cancels them all. None once nothing
     * runs or waits.
     */
    private fun render(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val all = downloads.value
        val summary = DownloadQueueSummary.of(all)
        if (summary.running == 0) {
            if (summary.active == 0) nm.cancel(PROGRESS_ID)
            return
        }
        channel(context, nm)
        val res = context.resources
        val running = all.filter { it.stage == DownloadStage.RUNNING }
        val one = running.singleOrNull()
        val inFlight = running.flatMap { it.writing }
        val title = when {
            inFlight.size == 1 -> inFlight.single().name
            inFlight.size > 1 -> res.getQuantityString(R.plurals.taildrive_queue_notify_files, inFlight.size, inFlight.size)
            one != null -> res.getString(R.string.taildrive_download_to, one.folderLabel)
            else -> res.getQuantityString(R.plurals.taildrive_queue_downloads, summary.active, summary.active)
        }
        val sizes = if (summary.counting || summary.bytes <= 0) formatFileSize(summary.bytesDone)
        else res.getString(R.string.taildrop_progress_of_format, formatFileSize(summary.bytesDone), formatFileSize(summary.bytes))
        val text = listOfNotNull(sizes, summary.bytesPerSecond?.let { res.getString(R.string.taildrop_speed_format, formatFileSize(it)) }).joinToString(" · ")
        val sub = if (summary.waiting > 0) res.getQuantityString(R.plurals.taildrive_queue_in_queue, summary.waiting, summary.waiting)
        else one?.folderLabel
        val fraction = summary.fraction
        val percent = ((fraction ?: 0f) * 100).toInt()
        // No id: every download, the waiting ones too.
        val cancel = PendingIntent.getBroadcast(
            context, PROGRESS_ID,
            Intent(ACTION_CANCEL).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelLabel = res.getString(if (summary.active > 1) R.string.taildrive_queue_cancel_all else R.string.action_cancel)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setContentIntent(browseTap(context, PROGRESS_ID))
            .addAction(0, cancelLabel, cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // As Taildrop's: a plain bar before Android 16, a Live Update on it.
            .setStyle(NotificationCompat.ProgressStyle().setProgress(percent).setProgressIndeterminate(fraction == null))
            .setRequestPromotedOngoing(true)
        if (fraction != null) b.setShortCriticalText("$percent%")
        // Without POST_NOTIFICATIONS on 13+ the system drops it silently; the page still shows it.
        try {
            nm.notify(PROGRESS_ID, b.build())
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.javaClass.simpleName}")
        }
    }

    /** "Saved 12 files to Download/Movies", and what did not arrive; a tap opens the folder where something can. */
    private fun postFinished(context: Context, done: DownloadState) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(context, nm)
        val res = context.resources
        val title = when {
            done.saved > 0 -> res.getQuantityString(R.plurals.taildrive_download_saved, done.saved, done.saved, done.folderLabel)
            else -> res.getString(R.string.taildrive_download_nothing_saved)
        }
        val lines = listOfNotNull(
            res.getString(R.string.taildrive_download_stopped).takeIf { done.stage == DownloadStage.STOPPED },
            done.failures.takeIf { it.isNotEmpty() }?.let { res.getQuantityString(R.plurals.taildrive_download_missing, it.size, it.size) },
        )
        val id = DONE_BASE + (done.id and 0xFF)
        val folder = TaildriveDownloadFolder.viewIntent(context, done.folder)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = if (folder != null) PendingIntent.getActivity(context, id, folder, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else browseTap(context, id)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (done.saved > 0) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (lines.isNotEmpty()) {
            b.setContentText(lines.first())
            b.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        try {
            nm.notify(id, b.build())
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cancel from the notification
    // ---------------------------------------------------------------------------------------------

    /**
     * The notification's Cancel. Registered at run time, not in the manifest: it is needed exactly
     * while a download runs in this process, and the broadcast is the app's own (a PendingIntent
     * sends in its creator's name), so a receiver nobody else can reach takes it. Without an id it
     * is every download.
     */
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            if (id > 0) cancel(id) else cancelAll()
        }
    }

    private fun registerCancel(context: Context) = synchronized(this) {
        if (receiverRegistered) return@synchronized
        ContextCompat.registerReceiver(context, cancelReceiver, IntentFilter(ACTION_CANCEL), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    /** To the app's log (Logs → CORE) and logcat. */
    private fun log(level: String, message: String) {
        if (level == "INFO") Log.i(TAG, message) else Log.w(TAG, message)
        runCatching { appctr.Appctr.logAndroid(level, "CORE", message) }
    }
}
