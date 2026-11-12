package io.github.bropines.tailscaled.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.TaildropSource
import io.github.bropines.tailscaled.ui.FilesActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

/** Where a file being sent stands, in the order a notification for several picks from. */
enum class OutgoingPhase {
    /** Bytes are leaving. */
    SENDING,
    /** Every byte has left; the other device is closing the file. */
    FINISHING,
    /** Being read through and copied for the daemon, which takes a path. */
    PREPARING,
    /** Behind another file. */
    QUEUED,
    /** Refused, lost or cancelled; [OutgoingTransfer.error] says which. */
    FAILED
}

/** One file being sent, as the Files page's card and the progress notification show it. */
@Immutable
data class OutgoingTransfer(
    val key: String,
    val name: String,
    /** The device it goes to. */
    val peer: String,
    /** -1 until the file has been read through. */
    val size: Long = -1,
    val sent: Long = 0,
    val bytesPerSecond: Long? = null,
    val phase: OutgoingPhase = OutgoingPhase.QUEUED,
    val error: String? = null
) {
    /** 0..1 for a bar; null for an indeterminate one. */
    val fraction: Float?
        get() = when {
            phase == OutgoingPhase.SENDING && size > 0 -> (sent.toDouble() / size).coerceIn(0.0, 1.0).toFloat()
            phase == OutgoingPhase.FAILED -> if (size > 0) (sent.toDouble() / size).coerceIn(0.0, 1.0).toFloat() else 0f
            else -> null
        }

    val secondsLeft: Long?
        get() {
            val rate = bytesPerSecond?.takeIf { it > 0 } ?: return null
            if (size <= 0 || phase != OutgoingPhase.SENDING) return null
            return ((size - sent).coerceAtLeast(0) + rate - 1) / rate
        }
}

private fun outgoingSizes(res: Resources, t: OutgoingTransfer): String =
    if (t.size > 0) res.getString(R.string.taildrop_progress_of_format, formatFileSize(t.sent), formatFileSize(t.size))
    else formatFileSize(t.sent)

/** The numbers of a send in one line, the way incomingDetail gives a receive's. */
fun outgoingDetail(res: Resources, t: OutgoingTransfer): String = when (t.phase) {
    OutgoingPhase.SENDING -> listOfNotNull(
        outgoingSizes(res, t),
        t.bytesPerSecond?.let { res.getString(R.string.taildrop_speed_format, formatFileSize(it)) },
        t.secondsLeft?.let { taildropTimeLeft(res, it) }
    )
    OutgoingPhase.FINISHING -> listOf(res.getString(R.string.taildrop_progress_finishing), formatFileSize(t.size))
    OutgoingPhase.PREPARING -> listOf(res.getString(R.string.taildrop_out_preparing))
    OutgoingPhase.QUEUED -> listOf(res.getString(R.string.taildrop_out_queued))
    OutgoingPhase.FAILED -> listOfNotNull(t.error ?: res.getString(R.string.taildrop_out_failed_title))
}.joinToString(" · ")

/**
 * The files on their way out: sent one after another in the background, whoever asked — the
 * Share sheet, the Files page, a peer's sheet — so the screen that asked can close at once.
 * [outgoing] feeds the Files page; one notification carries the progress of all of them,
 * with Cancel, and another says how it ended.
 *
 * The process stays up for it the way it does for everything else: sending needs the daemon,
 * and TailscaledService is in the foreground while the daemon runs.
 *
 * A file shared from another app comes with a grant that lasts as long as the activity that
 * received it, so [send] opens every file before it returns and the copy is made from that
 * stream. The daemon reports nothing about a file going out; the bytes are counted where the
 * bridge hands them to it (appctr/taildrop_send.go).
 */
object TaildropOutbox {
    private const val TAG = "TaildropOutbox"
    private const val NOTIF_ID = 0x7CFE
    private const val RESULT_NOTIF_ID = 0x7CFD
    const val ACTION_CANCEL = "io.github.bropines.tailscaled.TAILDROP_CANCEL_SEND"

    private const val TICK_MS = 500L
    private const val FAILED_KEEP_MS = 30_000L
    private const val MIN_SAMPLE_MS = 700L
    private const val RATE_TAU_MS = 3_000.0

    private class Batch(val peerName: String, var left: Int) {
        val sent = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        var cancelled = false
    }

    private class Item(
        val key: String,
        val uri: Uri,
        val name: String,
        val mime: String?,
        val peer: PeerData,
        val source: TaildropSource,
        val stream: InputStream?,
        val batch: Batch
    ) {
        var size = -1L
        var sent = 0L
        var rate: Double? = null
        var sampleAt = 0L
        var sampleBytes = 0L
        var phase = OutgoingPhase.QUEUED
        var error: String? = null
        var endedAt = 0L
        var cancelled = false
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "outbox failed", e) }
    )
    private val ids = AtomicLong(SystemClock.elapsedRealtime())
    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()
    private val queue = Channel<Item>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var ticker: Job? = null
    private var appContext: Context? = null
    private var shown: Card? = null

    private val state = MutableStateFlow<List<OutgoingTransfer>>(emptyList())
    val outgoing: StateFlow<List<OutgoingTransfer>> = state.asStateFlow()

    /**
     * Queues [uris] for [peer] and returns once each is open. A file that cannot be opened is
     * still queued, to fail in its turn with the reason and a line in the history.
     */
    fun send(context: Context, uris: List<Uri>, peer: PeerData, source: TaildropSource) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        // The app's own language, which the application context does not carry.
        appContext = wrapContextWithLocale(app)
        val batch = Batch(peer.getDisplayName(), uris.size)
        val added = uris.map { uri ->
            Item(
                key = "out-" + ids.incrementAndGet(),
                uri = uri,
                name = displayNameOf(app, uri),
                mime = runCatching { app.contentResolver.getType(uri) }.getOrNull(),
                peer = peer,
                source = source,
                stream = runCatching { app.contentResolver.openInputStream(uri) }.getOrNull(),
                batch = batch
            )
        }
        synchronized(lock) {
            added.forEach { items[it.key] = it }
            if (worker == null) worker = scope.launch { for (item in queue) run(app, item) }
        }
        added.forEach { queue.trySend(it) }
        publish()
        startTicker()
    }

    /** Stops [key], or everything when it is null. A file already there stays there. */
    fun cancel(key: String? = null) {
        val hit = synchronized(lock) {
            items.values.filter { (key == null || it.key == key) && it.phase != OutgoingPhase.FAILED }.onEach {
                it.cancelled = true
                it.batch.cancelled = true
            }
        }
        hit.forEach {
            // Whichever it is at: the bridge's request, or the copy reading this stream.
            Appctr.cancelSendFile(it.key)
            runCatching { it.stream?.close() }
        }
    }

    private fun run(context: Context, item: Item) {
        val outcome = if (synchronized(lock) { item.cancelled }) {
            runCatching { item.stream?.close() }
            null
        } else {
            synchronized(lock) { item.phase = OutgoingPhase.PREPARING }
            publish()
            sendTaildropFile(
                context, item.uri, item.peer, item.source, item.name,
                opened = item.stream, mime = item.mime, sendId = item.key,
                onSize = { size ->
                    synchronized(lock) {
                        item.size = size
                        item.phase = OutgoingPhase.SENDING
                        item.sampleAt = SystemClock.elapsedRealtime()
                    }
                    publish()
                }
            )
        }
        val now = SystemClock.elapsedRealtime()
        val done = synchronized(lock) {
            val batch = item.batch
            when {
                // Cancelled: gone at once, with nothing to tell.
                outcome == null || item.cancelled -> items.remove(item.key)
                outcome.error == null -> { batch.sent += item.name; items.remove(item.key) }
                else -> {
                    batch.failed += item.name to outcome.error
                    item.phase = OutgoingPhase.FAILED
                    item.error = outcome.error
                    item.endedAt = now
                    item.rate = null
                }
            }
            batch.left--
            batch.takeIf { it.left == 0 }
        }
        publish()
        render()
        done?.let { announce(appContext ?: context, it) }
    }

    private fun publish() {
        state.value = synchronized(lock) {
            items.values.map { i ->
                val phase = if (i.phase == OutgoingPhase.SENDING && i.size > 0 && i.sent >= i.size) OutgoingPhase.FINISHING else i.phase
                OutgoingTransfer(
                    key = i.key,
                    name = i.name,
                    peer = i.batch.peerName,
                    size = i.size,
                    sent = i.sent,
                    bytesPerSecond = if (phase == OutgoingPhase.SENDING) i.rate?.toLong() else null,
                    phase = phase,
                    error = i.error
                )
            }
        }
    }

    /** While anything is listed: read how far the running send is, drop old failures, redraw. */
    private fun startTicker() {
        synchronized(lock) {
            if (ticker != null) return
            ticker = scope.launch {
                var tick = 0
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val running = synchronized(lock) { items.values.firstOrNull { it.phase == OutgoingPhase.SENDING } }
                    if (running != null) {
                        val bytes = Appctr.sendFileProgress(running.key)
                        if (bytes >= 0) synchronized(lock) { sample(running, bytes, now) }
                    }
                    val empty = synchronized(lock) {
                        items.values.removeAll { it.phase == OutgoingPhase.FAILED && now - it.endedAt > FAILED_KEEP_MS }
                        if (items.isEmpty()) ticker = null
                        items.isEmpty()
                    }
                    publish()
                    // The notification at most once a second; the page twice.
                    if (empty || tick++ % 2 == 0) render()
                    if (empty) break
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun sample(i: Item, bytes: Long, now: Long) {
        val dt = now - i.sampleAt
        if (bytes > i.sampleBytes && dt >= MIN_SAMPLE_MS) {
            val s = (bytes - i.sampleBytes) * 1000.0 / dt
            val weight = 1 - exp(-dt / RATE_TAU_MS)
            i.rate = i.rate?.let { it + weight * (s - it) } ?: s
            i.sampleAt = now
            i.sampleBytes = bytes
        }
        i.sent = bytes
    }

    // --- the notifications ---

    private data class Card(val title: String, val text: String, val peer: String?, val percent: Int, val indeterminate: Boolean)

    /** One notification while anything is going or waiting; a failed file is the result notification's to tell. */
    private fun render() {
        val context = appContext ?: return
        val res = context.resources
        val active = state.value.filter { it.phase != OutgoingPhase.FAILED }
        val card = if (active.isEmpty()) null else {
            val current = active.firstOrNull { it.phase == OutgoingPhase.SENDING || it.phase == OutgoingPhase.FINISHING } ?: active.first()
            val known = active.all { it.size > 0 }
            val fraction = when {
                active.size == 1 -> current.fraction
                known -> (active.sumOf { it.sent }.toDouble() / active.sumOf { it.size }).coerceIn(0.0, 1.0).toFloat()
                else -> null
            }
            Card(
                title = if (active.size == 1) current.name
                else res.getQuantityString(R.plurals.taildrop_out_sending_files, active.size, active.size),
                text = if (active.size == 1) outgoingDetail(res, current) else current.name + " · " + outgoingDetail(res, current),
                peer = active.map { it.peer }.distinct().singleOrNull(),
                percent = ((fraction ?: 0f) * 100).toInt(),
                indeterminate = fraction == null
            )
        }
        synchronized(this) {
            if (card == shown) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (card == null) {
                nm.cancel(NOTIF_ID)
                shown = null
                return
            }
            try {
                nm.notify(NOTIF_ID, progressNotification(context, nm, card))
                shown = card
            } catch (e: Exception) {
                Log.w(TAG, "notify: ${e.message}")
            }
        }
    }

    private fun channel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(TAILDROP_PROGRESS_CHANNEL, context.getString(R.string.taildrop_progress_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openFiles(context: Context, requestCode: Int): PendingIntent {
        val files = Intent(context, FilesActivity::class.java)
            .putExtra(FilesActivity.EXTRA_OPEN_TAILDROP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, files, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun progressNotification(context: Context, nm: NotificationManager, card: Card): Notification {
        channel(context, nm)
        val cancel = PendingIntent.getBroadcast(
            context, NOTIF_ID,
            Intent(context, TaildropOutboxReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, TAILDROP_PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setSubText(card.peer?.let { context.getString(R.string.taildrop_out_to_format, it) })
            .setContentIntent(openFiles(context, NOTIF_ID))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // As for a file coming in (TaildropProgress): a bar before Android 16, a Live Update on it.
            .setStyle(NotificationCompat.ProgressStyle().setProgress(card.percent).setProgressIndeterminate(card.indeterminate))
            .setRequestPromotedOngoing(true)
            .addAction(0, context.getString(R.string.action_cancel), cancel)
        if (!card.indeterminate) b.setShortCriticalText("${card.percent}%")
        return b.build()
    }

    /** How a batch ended: a notification, or a toast where notifications are off. Nothing for one the user cancelled whole. */
    private fun announce(context: Context, batch: Batch) {
        if (batch.sent.isEmpty() && batch.failed.isEmpty()) return
        val res = context.resources
        val to = res.getString(R.string.taildrop_out_to_format, batch.peerName)
        val ok = batch.failed.isEmpty()
        val title = when {
            !ok && batch.sent.isEmpty() -> res.getString(R.string.taildrop_out_failed_title)
            !ok -> res.getString(R.string.taildrop_out_partly_title, batch.sent.size, batch.sent.size + batch.failed.size)
            else -> res.getQuantityString(R.plurals.taildrop_out_sent_files, batch.sent.size, batch.sent.size)
        }
        val text = if (ok) batch.sent.singleOrNull() ?: batch.sent.joinToString(", ")
        else batch.failed.joinToString("\n") { (name, why) -> res.getString(R.string.share_failed_format, name, why) }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, if (ok) "$title · $to" else text, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(context, nm)
        val b = NotificationCompat.Builder(context, TAILDROP_PROGRESS_CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(to)
            .setContentIntent(openFiles(context, RESULT_NOTIF_ID))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(if (ok) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
        try {
            nm.notify(RESULT_NOTIF_ID, b.build())
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }
}

/** Cancel on the sending notification. */
class TaildropOutboxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TaildropOutbox.ACTION_CANCEL) TaildropOutbox.cancel()
    }
}
