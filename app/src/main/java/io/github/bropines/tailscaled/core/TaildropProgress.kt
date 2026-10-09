package io.github.bropines.tailscaled.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.FilesActivity
import io.github.bropines.tailscaled.ui.parseRfc3339Millis
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp

/** Where a file being received stands, in the order a notification for several picks from. */
enum class IncomingPhase {
    /** Bytes are arriving. */
    RECEIVING,
    /** Nothing has arrived for a few seconds; the daemon still holds the transfer. */
    STALLED,
    /** Every byte is in; the daemon is closing and renaming the file, or it is being logged. */
    FINISHING,
    /** Done, and being copied into the default folder. */
    SAVING,
    /** Dropped by the daemon without Done, or silent too long. */
    INTERRUPTED
}

/** One file being received, as the Files page's Incoming card and the progress notification show it. */
@Immutable
data class IncomingTransfer(
    /** The daemon's partial path (or the name) and its Started: one upload of one file. */
    val key: String,
    val name: String,
    /** The sender's name, once /status has said who it is. */
    val sender: String? = null,
    /** What the sender declared; -1 when it did not say. A resumed upload declares what is left. */
    val size: Long = -1,
    val received: Long = 0,
    /** Smoothed receive rate; null until two reports have measured it, and while nothing arrives. */
    val bytesPerSecond: Long? = null,
    val phase: IncomingPhase = IncomingPhase.RECEIVING
) {
    /** 0..1 for a bar; null for an indeterminate one — the size unknown, or the bytes in and the file being finished. */
    val fraction: Float?
        get() = when {
            phase == IncomingPhase.FINISHING || phase == IncomingPhase.SAVING -> null
            size > 0 -> (received.toDouble() / size).coerceIn(0.0, 1.0).toFloat()
            else -> null
        }

    /** Seconds to go at the current rate, rounded up; null when the size or the rate is unknown. */
    val secondsLeft: Long?
        get() {
            val rate = bytesPerSecond?.takeIf { it > 0 } ?: return null
            if (size <= 0 || phase != IncomingPhase.RECEIVING) return null
            return ((size - received).coerceAtLeast(0) + rate - 1) / rate
        }
}

/** "45.2 MB of 104.9 MB", or just what has arrived when the size is unknown. */
fun incomingSizes(res: Resources, t: IncomingTransfer): String =
    if (t.size > 0) res.getString(R.string.taildrop_progress_of_format, formatFileSize(t.received), formatFileSize(t.size))
    else formatFileSize(t.received)

/**
 * The numbers of a transfer in one line: "45.2 MB of 104.9 MB · 5.2 MB/s · 12 s left" while
 * it runs, the state's word first otherwise ("Interrupted · 45.2 MB of 104.9 MB").
 */
fun incomingDetail(res: Resources, t: IncomingTransfer): String = when (t.phase) {
    IncomingPhase.RECEIVING -> listOfNotNull(
        incomingSizes(res, t),
        t.bytesPerSecond?.let { res.getString(R.string.taildrop_speed_format, formatFileSize(it)) },
        t.secondsLeft?.let { timeLeft(res, it) }
    )
    IncomingPhase.STALLED -> listOf(res.getString(R.string.taildrop_progress_stalled), incomingSizes(res, t))
    IncomingPhase.FINISHING -> listOf(res.getString(R.string.taildrop_progress_finishing), formatFileSize(maxOf(t.size, t.received)))
    IncomingPhase.SAVING -> listOf(res.getString(R.string.taildrop_progress_saving), formatFileSize(maxOf(t.size, t.received)))
    IncomingPhase.INTERRUPTED -> listOf(res.getString(R.string.taildrop_progress_interrupted), incomingSizes(res, t))
}.joinToString(" · ")

private fun timeLeft(res: Resources, seconds: Long): String = when {
    seconds < 60 -> res.getString(R.string.taildrop_progress_left_seconds, seconds.toInt().coerceAtLeast(1))
    seconds < 3600 -> res.getString(R.string.taildrop_progress_left_minutes, ((seconds + 59) / 60).toInt())
    else -> res.getString(R.string.taildrop_progress_left_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
}

/**
 * Several transfers as one, for the notification: sizes and rates added up, the size unknown
 * if any one is, the phase of the one furthest from done, the sender if they share one.
 */
internal fun aggregateIncoming(list: List<IncomingTransfer>): IncomingTransfer {
    val phase = IncomingPhase.entries.firstOrNull { p -> list.any { it.phase == p } } ?: IncomingPhase.FINISHING
    val rates = list.mapNotNull { it.bytesPerSecond }
    return IncomingTransfer(
        key = "",
        name = "",
        sender = list.map { it.sender }.distinct().singleOrNull(),
        size = if (list.all { it.size > 0 }) list.sumOf { it.size } else -1,
        received = list.sumOf { it.received },
        bytesPerSecond = if (phase == IncomingPhase.RECEIVING && rates.isNotEmpty()) rates.sum() else null,
        phase = phase
    )
}

/**
 * The files being received right now, tracked from the daemon's reports: [incoming] for the
 * Files page, and one notification with a progress bar for all of them.
 *
 * Notify.IncomingFiles lists every transfer the daemon holds, with Received of DeclaredSize
 * (-1: not declared). With patch 03 (send.go) a transfer is reported when it writes and a second has
 * passed since its last report; upstream 1.84–1.104 copies past the counting writer and
 * reports only once, after the rename, with Done=true and Received=0. Done is reported once
 * and the transfer is forgotten. A failure is reported to nobody: PutFile returns and the
 * entry leaves the map, and no empty list follows. So a transfer missing from a later report
 * without Done has failed, and one that goes silent is only stalled until
 * [INTERRUPT_AFTER_MS] — the peer API has no read timeout, so a vanished sender can hold the
 * transfer open in the daemon for good. Received reaches the size a little before Done
 * (close, rename), and after Done the default folder may still be copying the file
 * (TaildropSave) until TaildropEvents calls [finished]: the card says so meanwhile.
 *
 * Everything runs on one confined dispatcher: the bus goroutine hands a report over and
 * returns. The rate is a moving average over the reports' arrival times. The notification
 * is redrawn at most once a second, by a ticker that runs while there is anything to show.
 */
object TaildropProgress {
    private const val TAG = "TaildropProgress"
    private const val CHANNEL_ID = "tailsocks_taildrop_progress"
    /** Just below the "File received" ids (TaildropEvents: 0x7D00 + 0..0xFF). */
    private const val NOTIF_ID = 0x7CFF

    private const val TICK_MS = 1_000L
    /** No new bytes for this long: shown as waiting, with no rate. */
    private const val STALL_AFTER_MS = 5_000L
    /** No new bytes for this long: shown as interrupted, until bytes come again. */
    private const val INTERRUPT_AFTER_MS = 15_000L
    /** How long an interrupted transfer stays on the Files page. */
    private const val INTERRUPTED_KEEP_MS = 30_000L
    /** A Done transfer whose announcement never finished leaves after this. */
    private const val DONE_KEEP_MS = 5 * 60_000L
    /** Shorter gaps between reports (another transfer's tick) are not measured. */
    private const val MIN_SAMPLE_MS = 700L
    /** Time constant of the rate's moving average. */
    private const val RATE_TAU_MS = 3_000.0

    private enum class Stage { RUNNING, DONE, FAILED }

    private class Entry(val key: String, val slot: String, val name: String, val senderId: String?, now: Long, bytes: Long) {
        var size = -1L
        var received = bytes
        var sender: String? = null
        var stage = Stage.RUNNING
        /** Done, with a default folder to copy into. */
        var saving = false
        var lastProgressAt = now
        var endedAt = 0L
        var sampleAt = now
        var sampleBytes = bytes
        var rate: Double? = null
    }

    private val confined = Dispatchers.Default.limitedParallelism(1)
    // A progress display must not take the service's process down with it.
    private val scope = CoroutineScope(
        SupervisorJob() + confined + CoroutineExceptionHandler { _, e -> Log.e(TAG, "progress tracking failed", e) }
    )

    // Confined state.
    private val entries = LinkedHashMap<String, Entry>()
    private val senderNames = HashMap<String, String>()
    private var ticker: Job? = null
    private var appContext: Context? = null
    private var shown: Card? = null

    private val state = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    val incoming: StateFlow<List<IncomingTransfer>> = state.asStateFlow()

    /** One upload of one file: the partial (name and sender) and when it started. */
    fun keyOf(f: TaildropEvents.IncomingFile): String =
        (f.PartialPath?.takeIf { it.isNotEmpty() } ?: f.Name) + "|" + (f.Started ?: "")

    /** One Notify.IncomingFiles report. Called on the bus goroutine; returns at once. */
    fun onReport(context: Context, files: List<TaildropEvents.IncomingFile>) {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val app = context.applicationContext
        scope.launch { apply(app, files, now, wall) }
    }

    /** [f]'s arrival has been announced, or needs no announcing: its card makes way for the inbox entry. */
    fun finished(f: TaildropEvents.IncomingFile) {
        val key = keyOf(f)
        scope.launch {
            if (entries.remove(key) == null) return@launch
            publish(SystemClock.elapsedRealtime())
            // "File received" is up by now; the progress notification goes without waiting for the tick.
            if (entries.isEmpty()) render()
        }
    }

    /**
     * The listener goes with the service, so nothing more will be heard of these — whether
     * they end or not (a boot-started daemon in Root Mode can outlive the service). Off the
     * page and out of the notification shade, rather than called interrupted.
     */
    fun stopped() {
        scope.launch {
            entries.clear()
            publish(SystemClock.elapsedRealtime())
            render()
        }
    }

    private fun apply(context: Context, files: List<TaildropEvents.IncomingFile>, now: Long, wall: Long) {
        appContext = context
        val seen = HashSet<String>()
        for (f in files) {
            val key = keyOf(f)
            seen += key
            val e = entries[key]
            when {
                // Until finished() the card stays, saying what is left: the default folder may
                // still be taking the file. One first seen as Done — any file, without patch
                // 22 — gets no card; TaildropEvents announces it.
                f.Done -> if (e != null && e.stage != Stage.DONE) {
                    e.received = maxOf(f.Received, e.size, e.received)
                    e.stage = Stage.DONE
                    e.saving = GlobalSettings.getTaildropRootUri(context) != null
                    e.endedAt = now
                    e.rate = null
                }
                // Nothing written yet — or a daemon that never counts: no "0 B" card.
                e == null -> if (f.Received > 0) add(f, key, now, wall)
                else -> progress(e, f, now)
            }
        }
        // Missing from a full report without Done: PutFile failed and dropped it.
        entries.values.forEach { if (it.key !in seen && it.stage == Stage.RUNNING) fail(it, now) }
        publish(now)
        startTicker()
    }

    private fun add(f: TaildropEvents.IncomingFile, key: String, now: Long, wall: Long) {
        val slot = f.PartialPath?.takeIf { it.isNotEmpty() } ?: f.Name
        // A new upload of the same file from the same sender (a resume, a resend) means the
        // earlier one is over: the daemon holds one per sender and name.
        entries.values.removeAll { it.slot == slot && it.stage != Stage.DONE }
        val e = Entry(key, slot, f.Name, TaildropEvents.senderId(f.PartialPath), now, f.Received)
        e.size = f.DeclaredSize
        // A first rate from the daemon's own start time; the next reports refine it.
        val elapsed = parseRfc3339Millis(f.Started)?.let { wall - it } ?: 0L
        if (elapsed >= MIN_SAMPLE_MS) e.rate = f.Received * 1000.0 / elapsed
        entries[key] = e
        e.senderId?.let { id -> senderNames[id]?.let { e.sender = it } ?: resolveSender(id) }
    }

    private fun progress(e: Entry, f: TaildropEvents.IncomingFile, now: Long) {
        if (e.stage == Stage.DONE) return
        e.size = f.DeclaredSize
        if (f.Received <= e.received) return
        if (e.stage == Stage.FAILED || now - e.lastProgressAt > STALL_AFTER_MS) {
            // Moving again: the rate starts over rather than averaging the pause in.
            e.stage = Stage.RUNNING
            e.rate = null
            e.sampleAt = now
            e.sampleBytes = f.Received
        } else {
            val dt = now - e.sampleAt
            if (dt >= MIN_SAMPLE_MS) {
                val sample = (f.Received - e.sampleBytes) * 1000.0 / dt
                val weight = 1 - exp(-dt / RATE_TAU_MS)
                e.rate = e.rate?.let { it + weight * (sample - it) } ?: sample
                e.sampleAt = now
                e.sampleBytes = f.Received
            }
        }
        e.received = f.Received
        e.lastProgressAt = now
    }

    private fun fail(e: Entry, now: Long) {
        e.stage = Stage.FAILED
        e.endedAt = now
        e.rate = null
    }

    /** Who sent it, from /status off the confined thread; remembered by node for the next file. */
    private fun resolveSender(id: String) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { TaildropEvents.senderPeer(id)?.getDisplayName() } ?: return@launch
            senderNames[id] = name
            entries.values.forEach { if (it.senderId == id) it.sender = name }
            publish(SystemClock.elapsedRealtime())
        }
    }

    private fun publish(now: Long) {
        state.value = entries.values.map { e ->
            val phase = when (e.stage) {
                Stage.DONE -> if (e.saving) IncomingPhase.SAVING else IncomingPhase.FINISHING
                Stage.FAILED -> IncomingPhase.INTERRUPTED
                Stage.RUNNING -> when {
                    e.size > 0 && e.received >= e.size -> IncomingPhase.FINISHING
                    now - e.lastProgressAt > STALL_AFTER_MS -> IncomingPhase.STALLED
                    else -> IncomingPhase.RECEIVING
                }
            }
            IncomingTransfer(
                key = e.key,
                name = e.name,
                sender = e.sender,
                size = e.size,
                received = e.received,
                bytesPerSecond = if (phase == IncomingPhase.RECEIVING) e.rate?.toLong() else null,
                phase = phase
            )
        }
    }

    /** Once a second while anything is listed: time out the silent, drop the old, redraw. */
    private fun startTicker() {
        if (ticker != null || entries.isEmpty()) return
        ticker = scope.launch {
            try {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    entries.values.forEach {
                        if (it.stage == Stage.RUNNING && now - it.lastProgressAt > INTERRUPT_AFTER_MS) fail(it, now)
                    }
                    entries.values.removeAll {
                        (it.stage == Stage.FAILED && now - it.endedAt > INTERRUPTED_KEEP_MS) ||
                            (it.stage == Stage.DONE && now - it.endedAt > DONE_KEEP_MS)
                    }
                    publish(now)
                    render()
                    if (entries.isEmpty()) break
                    delay(TICK_MS)
                }
            } finally {
                ticker = null
            }
        }
    }

    // --- the notification ---

    /** What the notification shows; it is reposted only when this changes. */
    private data class Card(
        val title: String,
        val text: String,
        val sender: String?,
        /** 0..100, or -1 for no bar. */
        val percent: Int,
        val indeterminate: Boolean,
        val ongoing: Boolean
    )

    /**
     * One notification for every file arriving: the progress while anything is, "interrupted"
     * once all that is left failed — that one stays until swiped away — and nothing after.
     */
    private fun render() {
        val context = appContext ?: return
        val res = context.resources
        val list = state.value
        val active = list.filter { it.phase != IncomingPhase.INTERRUPTED }
        val card = when {
            active.isNotEmpty() -> {
                val one = active.singleOrNull()
                val sum = one ?: aggregateIncoming(active)
                val fraction = sum.fraction
                Card(
                    title = one?.name ?: res.getQuantityString(R.plurals.taildrop_progress_receiving_files, active.size, active.size),
                    text = incomingDetail(res, sum),
                    sender = sum.sender,
                    percent = ((fraction ?: 0f) * 100).toInt(),
                    indeterminate = fraction == null,
                    ongoing = true
                )
            }
            list.isNotEmpty() -> {
                val one = list.singleOrNull()
                val sum = one ?: aggregateIncoming(list)
                val what = one?.name ?: res.getQuantityString(R.plurals.taildrop_progress_files, list.size, list.size)
                Card(
                    title = res.getString(R.string.taildrop_progress_interrupted_title),
                    text = what + " · " + incomingSizes(res, sum),
                    sender = sum.sender,
                    percent = -1,
                    indeterminate = false,
                    ongoing = false
                )
            }
            else -> null
        }
        if (card == shown) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (card == null) {
            // Over. An "interrupted" card is the user's to dismiss.
            if (shown?.ongoing == true) nm.cancel(NOTIF_ID)
            shown = null
            return
        }
        // Without POST_NOTIFICATIONS on 13+ the system drops it silently; the page still shows it.
        try {
            nm.notify(NOTIF_ID, build(context, nm, card))
            shown = card
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }

    private fun build(context: Context, nm: NotificationManager, card: Card): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.taildrop_progress_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val files = Intent(context, FilesActivity::class.java)
            .putExtra(FilesActivity.EXTRA_OPEN_TAILDROP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, NOTIF_ID, files, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setSubText(card.sender?.let { context.getString(R.string.taildrop_from_format, it) })
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (card.ongoing) {
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                // A plain progress bar before Android 16. On 16 a Live Update — the status bar
                // chip, HyperOS's island — once the manifest asks for POST_PROMOTED_NOTIFICATIONS;
                // without it the request is ignored.
                .setStyle(NotificationCompat.ProgressStyle().setProgress(card.percent).setProgressIndeterminate(card.indeterminate))
                .setRequestPromotedOngoing(true)
            if (!card.indeterminate) b.setShortCriticalText("${card.percent}%")
        } else {
            b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
        }
        return b.build()
    }
}
