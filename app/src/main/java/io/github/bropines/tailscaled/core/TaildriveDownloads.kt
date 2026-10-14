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
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.TaildriveActivity
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.exp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Taildrive downloads as they run: one at a time, in the order they were asked for, off the main
 * thread and outside any screen, with a notification that shows how far each has got and cancels it.
 *
 * Where they run. Not in a service of their own: Taildrive is there only while the node runs, and
 * the node is TailscaledService, a foreground service of this process — so the process a download
 * runs in is kept whichever screen is open, or none. A Stop ends Taildrive and so the download;
 * it says so, and what was saved stays.
 *
 * What is read. The file goes the way it goes to a player that opens it (TailsocksFileProvider):
 * HTTP byte ranges over the daemon's SOCKS5 port (RangedFile), a few megabytes at a time, or the
 * provider's plain stream when the server keeps to whole files. Nothing is held whole in memory,
 * and a read that fails is asked for again before the file is given up.
 *
 * Nothing here logs a name or an address of a file.
 */

/** What [TaildriveDownloads.start] is asked. */
class DownloadRequest(
    val accountId: String,
    /** The folder the selection was made in. */
    val parent: DriveLocation,
    /** The selection: files, folders, or both. */
    val entries: List<DriveEntry>,
    val folder: ReceiveFolder,
    /** Chosen for this download rather than taken from Settings: its end offers to keep it. */
    val pickedNow: Boolean,
    /** The selected folders as the question before the download walked them to the end; null to walk them now. */
    val walked: Walked? = null,
)

enum class DownloadStage {
    /** Behind another download. */
    WAITING,
    RUNNING,
    DONE,
    CANCELLED,

    /** Taildrive went away under it: the node was stopped. */
    STOPPED;

    val active: Boolean get() = this == WAITING || this == RUNNING
}

/** Why a file did not arrive, as the page and the notification name it. */
enum class DownloadFailReason { NOT_FOUND, DENIED, TIMEOUT, NETWORK, INCOMPLETE, WRITE, UNLISTED }

/** A file — or, [DownloadFailReason.UNLISTED], a folder — that is missing from what was saved, by its path under the selection. */
data class DownloadFailure(val path: String, val reason: DownloadFailReason)

/** A download as the browser and the notification show it. */
@Immutable
data class DownloadState(
    val id: Int,
    /** The folder as the person knows it: "Download/Movies". */
    val folderLabel: String,
    val folder: ReceiveFolder,
    val pickedNow: Boolean,
    val stage: DownloadStage = DownloadStage.WAITING,
    /** The folders are still being walked: [files] and [bytes] may grow. */
    val counting: Boolean = false,
    val files: Int = 0,
    val bytes: Long = 0,
    /** Files that are over, saved or not. */
    val filesDone: Int = 0,
    val bytesDone: Long = 0,
    /** The file being written, by its name. */
    val current: String? = null,
    val currentDone: Long = 0,
    /** 0 when the server did not say. */
    val currentSize: Long = 0,
    val saved: Int = 0,
    val failures: List<DownloadFailure> = emptyList(),
    /** Smoothed; null until it has been measured, and while nothing arrives. */
    val bytesPerSecond: Long? = null,
) {
    /** 0..1 of the whole; null while the total is not known yet. */
    val fraction: Float?
        get() = if (!counting && bytes > 0) (bytesDone.toDouble() / bytes).coerceIn(0.0, 1.0).toFloat() else null

    /** 0..1 of the file being written; null for a file whose size is not known. */
    val currentFraction: Float?
        get() = if (currentSize > 0) (currentDone.toDouble() / currentSize).coerceIn(0.0, 1.0).toFloat() else null

    /** The number of the file being written, 1-based, for "file 3 of 12". */
    val currentNumber: Int get() = (filesDone + 1).coerceAtMost(files.coerceAtLeast(1))
}

/** The listing of the browser as a walk takes it: the entries of [at], never another place. */
internal fun TaildriveBrowse.listEntries(at: DriveLocation): DriveResult<List<DriveEntry>> = when (val listed = list(at)) {
    is DriveResult.Ok -> DriveResult.Ok(listed.value.entries)
    is DriveResult.Err -> listed
}

/** A file of a share, read once from its start to its end. */
internal interface DriveFileReader : Closeable {
    /** The file's length as the server gave it; -1 when it did not. */
    val size: Long

    /** As [InputStream.read]: -1 at the end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

object TaildriveDownloads {
    private const val TAG = "TaildriveDownloads"
    private const val CHANNEL_ID = "tailsocks_taildrive_downloads"
    /** Above Taildrop's ids (0x7CFF and 0x7D00 + 0..0xFF). */
    private const val PROGRESS_ID = 0x7E00
    private const val DONE_BASE = 0x7E01
    private const val ACTION_CANCEL = BuildConfig.APPLICATION_ID + ".TAILDRIVE_DOWNLOAD_CANCEL"
    private const val EXTRA_ID = "download_id"

    private const val BUFFER_BYTES = 256 * 1024
    /** How often the page is told how far a file has got. */
    private const val PUBLISH_MS = 200L
    /** How often the notification is redrawn and the rate measured. */
    private const val TICK_MS = 1_000L
    private const val RATE_TAU_MS = 3_000.0
    /** A failed read is asked for again this many times, after a pause that grows, before the file is given up. */
    private const val READ_RETRIES = 3
    private const val RETRY_PAUSE_MS = 1_000L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "download failed: ${e.javaClass.simpleName}") }
    )
    /** One download writes at a time; the others wait their turn in the order asked. */
    private val turn = Mutex()
    private val ids = AtomicInteger(0)
    private val jobs = ConcurrentHashMap<Int, Job>()
    /** The file each download is reading: a cancel closes it, which aborts the request in flight. */
    private val readers = ConcurrentHashMap<Int, DriveFileReader>()
    private val state = MutableStateFlow<List<DownloadState>>(emptyList())

    /** Every download that is running, waiting, or over and not yet put away from the page. */
    val downloads: StateFlow<List<DownloadState>> = state.asStateFlow()

    @Volatile
    private var receiverRegistered = false
    private var ticker: Job? = null

    /** Queues [request]; returns its id. Returns at once. */
    fun start(context: Context, request: DownloadRequest): Int {
        val app = context.applicationContext
        val id = ids.incrementAndGet()
        val label = TaildropFolder.quickLabel(request.folder) ?: "…"
        state.update { it + DownloadState(id, label, request.folder, request.pickedNow) }
        registerCancel(app)
        val job = scope.launch {
            try {
                turn.withLock { run(app, id, request) }
            } catch (e: CancellationException) {
                edit(id) { if (it.stage.active) it.copy(stage = DownloadStage.CANCELLED, current = null, counting = false, bytesPerSecond = null) else it }
            } finally {
                render(app)
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
        startTicker(app)
        return id
    }

    /** Stops download [id]: the file being written is taken away, what was saved stays. */
    fun cancel(id: Int) {
        jobs[id]?.cancel()
        readers[id]?.close()
    }

    /** Takes a finished download off the page. */
    fun dismiss(id: Int) {
        state.update { list -> list.filterNot { it.id == id && !it.stage.active } }
    }

    private fun edit(id: Int, change: (DownloadState) -> DownloadState) {
        state.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    // ---------------------------------------------------------------------------------------------
    // One download
    // ---------------------------------------------------------------------------------------------

    /** The node stopped under the download: nothing more can be read. */
    private class Stopped : Exception()

    /** Writing into the folder failed, as against reading from the share. */
    private class WriteFailed : IOException()

    /** Fewer bytes came than the server said the file has. */
    private class Incomplete : IOException()

    /** What a running download keeps count of; one writer, the download's own coroutine and its walk under [lock]. */
    private class Tally {
        val lock = Any()
        var files = 0
        var bytes = 0L
        var counting = true
        var filesDone = 0
        var bytesDone = 0L
        var current: String? = null
        var currentDone = 0L
        var currentSize = 0L
        var saved = 0
        val failures = ArrayList<DownloadFailure>()
    }

    private suspend fun run(context: Context, id: Int, request: DownloadRequest) {
        val label = runCatching { TaildropFolder.label(context, request.folder) }.getOrNull()
        val folders = request.entries.filter { it.isFolder }.map { it.name }
        edit(id) { it.copy(stage = DownloadStage.RUNNING, folderLabel = label ?: it.folderLabel, counting = folders.isNotEmpty()) }
        log("INFO", "Taildrive: download of ${request.entries.size} selected into ${label ?: "a folder"} started")

        val tally = Tally()
        var lastPublished = 0L
        fun publish(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastPublished < PUBLISH_MS) return
            lastPublished = now
            val snapshot = synchronized(tally.lock) {
                { s: DownloadState ->
                    s.copy(
                        counting = tally.counting, files = tally.files, bytes = tally.bytes,
                        filesDone = tally.filesDone, bytesDone = tally.bytesDone,
                        current = tally.current, currentDone = tally.currentDone, currentSize = tally.currentSize,
                        saved = tally.saved, failures = tally.failures.toList()
                    )
                }
            }
            edit(id, snapshot)
        }

        val sink = DownloadSink.of(context, request.folder)
        val client = TaildriveClient(context, request.accountId)
        val browse = TaildriveBrowse.forAccount(context, request.accountId)
        // The folders made so far, by their path under the selection; the download folder itself is the empty path.
        val made = HashMap<List<String>, SinkNode>()
        fun folderAt(path: List<String>): SinkNode? {
            if (path.isEmpty()) return null
            made[path]?.let { return it }
            val parent = folderAt(path.dropLast(1))
            return sink.newFolder(parent, path.last()).also { made[path] = it }
        }

        var stopped = false
        try {
            coroutineScope {
                val items = Channel<WalkItem>(Channel.UNLIMITED)
                launch {
                    try {
                        fun counted(item: WalkItem) = synchronized(tally.lock) {
                            if (item is WalkItem.File) {
                                tally.files++
                                tally.bytes += item.size.coerceAtLeast(0)
                            }
                        }
                        for (file in DriveWalk.filesOf(request.entries)) {
                            counted(file)
                            items.send(file)
                        }
                        val unlisted = request.walked?.let { walked ->
                            walked.items.forEach { counted(it); items.send(it) }
                            walked.unlisted
                        } ?: DriveWalk.walk(request.parent, folders, { browse.listEntries(it) }) { item ->
                            counted(item)
                            items.send(item)
                        }
                        synchronized(tally.lock) {
                            unlisted.forEach { tally.failures += DownloadFailure(it.joinToString("/"), DownloadFailReason.UNLISTED) }
                        }
                    } finally {
                        synchronized(tally.lock) { tally.counting = false }
                        items.close()
                        publish(force = true)
                    }
                }
                for (item in items) {
                    ensureActive()
                    when (item) {
                        is WalkItem.Folder -> runCatching { folderAt(item.path) }.onFailure {
                            if (it is CancellationException) throw it
                            synchronized(tally.lock) { tally.failures += DownloadFailure(item.path.joinToString("/"), DownloadFailReason.WRITE) }
                        }
                        // A read in flight is aborted by closing its file; this is where cancel() finds it.
                        is WalkItem.File -> fetch(context, request, item, tally, sink, client, { folderAt(it) }, { r -> if (r != null) readers[id] = r else readers.remove(id) }, ::publish)
                    }
                    publish()
                }
            }
        } catch (e: Stopped) {
            stopped = true
        }
        val outcome = synchronized(tally.lock) { tally.saved to tally.failures.size }
        log("INFO", "Taildrive: download ${if (stopped) "stopped by the node" else "finished"}: ${outcome.first} saved, ${outcome.second} missing")
        publish(force = true)
        edit(id) { it.copy(stage = if (stopped) DownloadStage.STOPPED else DownloadStage.DONE, current = null, counting = false, bytesPerSecond = null) }
        state.value.firstOrNull { it.id == id }?.let { postFinished(context, it) }
    }

    private suspend fun fetch(
        context: Context,
        request: DownloadRequest,
        item: WalkItem.File,
        tally: Tally,
        sink: DownloadSink,
        client: TaildriveClient,
        folderAt: (List<String>) -> SinkNode?,
        reading: (DriveFileReader?) -> Unit,
        publish: (Boolean) -> Unit,
    ) {
        val name = item.path.last()
        synchronized(tally.lock) {
            tally.current = name
            tally.currentDone = 0
            tally.currentSize = item.size.coerceAtLeast(0)
        }
        publish(true)
        var file: SinkNode? = null
        var copied = 0L
        try {
            file = try {
                sink.newFile(folderAt(item.path.dropLast(1)), name)
            } catch (e: IOException) {
                throw WriteFailed()
            }
            val reader = openReader(client, DriveLocation(request.parent.segments + item.path).davPath)
            reading(reader)
            reader.use {
                val expected = reader.size.takeIf { it >= 0 } ?: item.size.takeIf { it > 0 }
                if (expected != null) synchronized(tally.lock) { tally.currentSize = expected }
                val out = try {
                    sink.write(file)
                } catch (e: IOException) {
                    throw WriteFailed()
                }
                out.use { stream ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = reader.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        try {
                            stream.write(buffer, 0, n)
                        } catch (e: IOException) {
                            throw WriteFailed()
                        }
                        copied += n
                        synchronized(tally.lock) {
                            tally.currentDone = copied
                            tally.bytesDone += n
                        }
                        publish(false)
                    }
                }
                if (expected != null && copied != expected) throw Incomplete()
            }
            synchronized(tally.lock) { tally.saved++ }
        } catch (e: CancellationException) {
            file?.let(sink::discard)
            throw e
        } catch (e: Exception) {
            file?.let(sink::discard)
            val reason = failReason(e)
            Log.w(TAG, "a file was not saved: ${e.javaClass.simpleName} -> $reason")
            synchronized(tally.lock) { tally.failures += DownloadFailure(item.path.joinToString("/"), reason) }
            // A connection that failed because the node is gone: nothing after it can be read either.
            if (reason == DownloadFailReason.NETWORK || reason == DownloadFailReason.TIMEOUT) {
                if (!ProxyState.isActualRunning(context)) throw Stopped()
            }
        } finally {
            reading(null)
            synchronized(tally.lock) {
                tally.filesDone++
                // A file that ended short is over all the same: the whole moves on by what it had left.
                tally.bytesDone += (item.size - copied).coerceAtLeast(0)
                tally.current = null
            }
        }
    }

    /** The file at [path] the way a player gets it: ranges when the server has them, the plain stream otherwise. */
    private fun openReader(client: TaildriveClient, path: String): DriveFileReader {
        val ranged = openRangedFile({ client.rangeUpstream(path) })
        return if (ranged != null) RangedReader(ranged) else StreamReader(client.getDownloadStream(path))
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

    /** Why [e] ended a file. */
    internal fun failReason(e: Throwable): DownloadFailReason = when (e) {
        is WriteFailed -> DownloadFailReason.WRITE
        is Incomplete -> DownloadFailReason.INCOMPLETE
        is RangeNotFoundException -> DownloadFailReason.NOT_FOUND
        is RangeForbiddenException -> DownloadFailReason.DENIED
        is RangeTimeoutException, is SocketTimeoutException -> DownloadFailReason.TIMEOUT
        // The plain stream's refusal: "WebDAV GET failed: HTTP 404".
        else -> when (HTTP_STATUS.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()) {
            404, 410 -> DownloadFailReason.NOT_FOUND
            401, 403 -> DownloadFailReason.DENIED
            else -> DownloadFailReason.NETWORK
        }
    }

    private val HTTP_STATUS = Regex("""HTTP (\d{3})""")

    // ---------------------------------------------------------------------------------------------
    // The notification
    // ---------------------------------------------------------------------------------------------

    private var rate: Double? = null
    private var rateBytes = 0L
    private var rateAt = 0L
    private var rateId = 0

    /** Once a second while anything runs: the rate, and the progress notification. */
    private fun startTicker(context: Context) = synchronized(this) {
        if (ticker?.isActive == true) return@synchronized
        ticker = scope.launch {
            while (true) {
                measure()
                render(context)
                if (state.value.none { it.stage.active }) break
                delay(TICK_MS)
            }
        }
    }

    private fun measure() {
        val run = state.value.firstOrNull { it.stage == DownloadStage.RUNNING } ?: return
        val now = SystemClock.elapsedRealtime()
        if (run.id != rateId) {
            rateId = run.id
            rate = null
            rateBytes = run.bytesDone
            rateAt = now
            return
        }
        val dt = now - rateAt
        if (dt <= 0) return
        val sample = (run.bytesDone - rateBytes) * 1000.0 / dt
        val weight = 1 - exp(-dt / RATE_TAU_MS)
        val smoothed = rate?.let { it + weight * (sample - it) } ?: sample
        rate = smoothed
        rateBytes = run.bytesDone
        rateAt = now
        edit(run.id) { if (it.stage == DownloadStage.RUNNING) it.copy(bytesPerSecond = smoothed.toLong().takeIf { s -> s > 0 }) else it }
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

    /** The download that is running, as one notification; none once nothing runs. */
    private fun render(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val run = state.value.firstOrNull { it.stage == DownloadStage.RUNNING }
        if (run == null) {
            if (state.value.none { it.stage.active }) nm.cancel(PROGRESS_ID)
            return
        }
        channel(context, nm)
        val res = context.resources
        val title = if (run.files <= 1 && !run.counting) run.current ?: run.folderLabel
        else res.getString(
            if (run.counting) R.string.taildrive_download_file_of_counting else R.string.taildrive_download_file_of,
            run.currentNumber, run.files
        )
        val sizes = if (run.counting || run.bytes <= 0) formatFileSize(run.bytesDone)
        else res.getString(R.string.taildrop_progress_of_format, formatFileSize(run.bytesDone), formatFileSize(run.bytes))
        val text = listOfNotNull(sizes, run.bytesPerSecond?.let { res.getString(R.string.taildrop_speed_format, formatFileSize(it)) }).joinToString(" · ")
        val fraction = run.fraction
        val percent = ((fraction ?: 0f) * 100).toInt()
        val cancel = PendingIntent.getBroadcast(
            context, run.id,
            Intent(ACTION_CANCEL).setPackage(context.packageName).putExtra(EXTRA_ID, run.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (run.files > 1 || run.counting) run.current else run.folderLabel)
            .setContentIntent(browseTap(context, PROGRESS_ID))
            .addAction(0, res.getString(R.string.action_cancel), cancel)
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
     * sends in its creator's name), so a receiver nobody else can reach takes it.
     */
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            if (id > 0) cancel(id)
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
