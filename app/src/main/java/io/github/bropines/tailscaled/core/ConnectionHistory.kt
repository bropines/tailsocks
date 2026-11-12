package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/*
 * The connection's last day, for the timeline on the main screen (ui/ConnectionTimeline.kt).
 *
 * The service tells [ConnectionHistory] what its card says whenever that changes; the thirteen
 * card states fold into the few [ConnectionKind]s a person can tell apart on a bar, and only a
 * change of kind is written down. [ConnectionLog] is everything that can be decided without
 * Android — what a new observation adds, what is dropped, how the file reads, and how events
 * and a window become segments and a summary — and is what the JVM tests run.
 */

/** What the connection was doing, as the timeline shows it. [code] is what the file holds: never reuse one. */
enum class ConnectionKind(val code: Char) {
    CONNECTED('C'),
    /** On its way up or back: connecting, starting, restarting, reconnecting. */
    RECOVERING('R'),
    /** Up but impaired, or down for a reason of its own: a degrading warning, a lost daemon, a failed root start. */
    TROUBLE('T'),
    WAITING_FOR_NETWORK('W'),
    /** Nothing moves until the user does something: sign in, approve the device, answer an OS refusal. */
    NEEDS_YOU('N'),
    STOPPED('S'),
    /** Nobody was recording: before the history begins, or while the process was dead. */
    NO_DATA('U');

    /** The service was up and reporting. An open segment of a live kind is only as long as the process lived. */
    val live: Boolean get() = this != STOPPED && this != NO_DATA

    companion object {
        fun ofCode(code: Char): ConnectionKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The kind a card state is drawn as. STOPPING is already "stopped": it lasts a moment and the
 * service is gone after it, so the stop begins where the user asked for it.
 */
internal fun connectionKindOf(state: CardState): ConnectionKind = when (state) {
    CardState.CONNECTED -> ConnectionKind.CONNECTED
    CardState.CONNECTING, CardState.STARTING, CardState.RESTARTING, CardState.RECONNECTING -> ConnectionKind.RECOVERING
    CardState.DEGRADED, CardState.CONNECTION_LOST, CardState.ROOT_FAILED -> ConnectionKind.TROUBLE
    CardState.WAITING_FOR_NETWORK -> ConnectionKind.WAITING_FOR_NETWORK
    CardState.NEEDS_LOGIN, CardState.AWAITING_APPROVAL, CardState.OS_REFUSED -> ConnectionKind.NEEDS_YOU
    CardState.STOPPING -> ConnectionKind.STOPPED
}

/** From [at] (epoch ms) on, the connection was [kind] — until the next event. */
data class ConnectionEvent(val at: Long, val kind: ConnectionKind)

/**
 * A stretch of one kind inside a window. [openStart]: it began before the window did (or
 * before the history does), so [start] is the window's edge, not when it began. [openEnd]: it
 * was still going on at the window's end.
 */
data class ConnectionSegment(
    val kind: ConnectionKind,
    val start: Long,
    val end: Long,
    val openStart: Boolean = false,
    val openEnd: Boolean = false,
) {
    val durationMs: Long get() = end - start
}

/**
 * A window in numbers. [onMs] is the time the service was up and reporting — everything but
 * STOPPED and NO_DATA — and is what [connectedFraction] is a share of: a tunnel the user turned
 * off for the afternoon was not down for the afternoon.
 */
data class ConnectionSummary(
    val windowMs: Long,
    val connectedMs: Long,
    val onMs: Long,
    /** Times a connected stretch ended in anything but a stop: trouble, a lost network, a dead process. */
    val interruptions: Int,
    /** False when the whole window is NO_DATA. */
    val hasData: Boolean,
) {
    /** Connected time as a share of the time it was on, 0…1; null when it was never on. */
    val connectedFraction: Float? get() = if (onMs > 0) connectedMs.toFloat() / onMs else null

    /** Connected time as a share of the whole window, 0…1. */
    val connectedOfWindow: Float get() = if (windowMs > 0) connectedMs.toFloat() / windowMs else 0f
}

data class ConnectionTimeline(val segments: List<ConnectionSegment>, val summary: ConnectionSummary)

/** The history as the interface reads it: the events, and how far the last of them can be trusted (see [ConnectionLog.timeline]). */
data class ConnectionSnapshot(val events: List<ConnectionEvent>, val aliveUntil: Long)

/** The rules of the history, free of Android and of the clock. */
object ConnectionLog {
    /** A day for the widest window, and an hour so that its left edge still has an event behind it. */
    const val RETENTION_MS = 25 * 60 * 60 * 1000L

    /**
     * The most events kept. A network that flaps every few seconds would otherwise write
     * without end; past this the oldest go, and the timeline simply begins later.
     */
    const val MAX_EVENTS = 1000

    /** The file is rewritten once it holds this many lines, heartbeats included (one a minute is 1440 a day). */
    const val COMPACT_AT_LINES = 2 * MAX_EVENTS

    /** The last-alive time is written at most this often; it is how early a dead process's last segment may end. */
    const val HEARTBEAT_EVERY_MS = 60_000L

    /**
     * What an observation adds to [events]: nothing, the new kind, a gap, or a gap and the kind.
     * [kind] is null for a heartbeat, which observes only that the process is alive.
     *
     * Only a change of kind is an event, so a card that goes CONNECTING → STARTING → RECONNECTING
     * writes once. A clock set back does not reorder the list: the event lands on the last one's
     * time, and whatever lay between them becomes a segment of no length that nobody sees.
     *
     * [firstWriteOfProcess] is where a dead process is found out. The service says when it stops;
     * if the list ends in a live kind and a new process is writing, the old one never said so —
     * it was killed, or the device restarted. All that is known is that it lived to [lastAlive],
     * so the open segment is closed there with NO_DATA rather than painted "connected" up to now.
     */
    fun appended(
        events: List<ConnectionEvent>,
        lastAlive: Long,
        kind: ConnectionKind?,
        at: Long,
        firstWriteOfProcess: Boolean,
    ): List<ConnectionEvent> {
        var last = events.lastOrNull()
        val time = maxOf(at, last?.at ?: at)
        val out = ArrayList<ConnectionEvent>(2)
        if (firstWriteOfProcess && last != null && last.kind.live) {
            val gap = ConnectionEvent(lastAlive.coerceIn(last.at, time), ConnectionKind.NO_DATA)
            out += gap
            last = gap
        }
        if (kind != null && last?.kind != kind) out += ConnectionEvent(time, kind)
        return out
    }

    /**
     * [events] without what no window can show any more: everything older than [RETENTION_MS]
     * except the newest of those, which says what the connection was at the cutoff; then the
     * newest [MAX_EVENTS] of what is left.
     */
    fun pruned(events: List<ConnectionEvent>, now: Long): List<ConnectionEvent> {
        val cutoff = now - RETENTION_MS
        val firstInside = events.indexOfFirst { it.at >= cutoff }
        val kept = when {
            firstInside < 0 -> events.takeLast(1)
            firstInside == 0 -> events
            else -> events.subList(firstInside - 1, events.size)
        }
        return if (kept.size > MAX_EVENTS) kept.subList(kept.size - MAX_EVENTS, kept.size).toList() else kept.toList()
    }

    // ---------------------------------------------------------------------------------------
    // The file: one line an event ("e <ms> <code>"), one line a heartbeat ("a <ms>").

    fun eventLine(event: ConnectionEvent) = "e ${event.at} ${event.kind.code}\n"
    fun aliveLine(at: Long) = "a $at\n"

    fun encode(events: List<ConnectionEvent>, lastAlive: Long): String = buildString {
        for (e in events) append(eventLine(e))
        if (lastAlive > 0) append(aliveLine(lastAlive))
    }

    class Decoded(val events: List<ConnectionEvent>, val lastAlive: Long, val lines: Int)

    /**
     * Reads the file. A line that does not parse is skipped — the last one may be half written
     * if the process died in the middle of it — and so is an event out of order. The last-alive
     * time is the latest moment anything was written at all, events included.
     */
    fun decode(text: String): Decoded {
        val events = ArrayList<ConnectionEvent>()
        var alive = 0L
        var lines = 0
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            lines++
            val parts = line.split(' ')
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            when {
                parts[0] == "a" && parts.size == 2 -> alive = maxOf(alive, at)
                parts[0] == "e" && parts.size == 3 && parts[2].length == 1 -> {
                    val kind = ConnectionKind.ofCode(parts[2][0]) ?: continue
                    if (events.isNotEmpty() && at < events.last().at) continue
                    events += ConnectionEvent(at, kind)
                    alive = maxOf(alive, at)
                }
            }
        }
        return Decoded(events, alive, lines)
    }

    // ---------------------------------------------------------------------------------------
    // Reading a window.

    /**
     * [events] as the segments that fill [from]…[to], and their summary.
     *
     * The window is always covered whole: what lies before the first event is NO_DATA, and so is
     * the end of an open live segment past [aliveUntil] — the last moment the recording process
     * is known to have lived. A caller in the recording process passes [to]; a process that has
     * recorded nothing itself passes the stored last-alive time, because the live state it reads
     * was left behind by a process that died (see [appended]). A stop lasts until the next
     * start whoever is alive, so [aliveUntil] never shortens STOPPED.
     */
    fun timeline(events: List<ConnectionEvent>, from: Long, to: Long, aliveUntil: Long = to): ConnectionTimeline {
        if (to <= from) return ConnectionTimeline(emptyList(), ConnectionSummary(0, 0, 0, 0, false))

        // Unclipped spans first, so that "began before the window" is known when they are cut.
        val spans = ArrayList<ConnectionSegment>(events.size + 2)
        val first = events.firstOrNull()
        if (first == null || first.at > from) spans += ConnectionSegment(ConnectionKind.NO_DATA, Long.MIN_VALUE, first?.at ?: Long.MAX_VALUE)
        for (i in events.indices) {
            val e = events[i]
            val next = events.getOrNull(i + 1)
            if (next != null) {
                spans += ConnectionSegment(e.kind, e.at, next.at)
            } else if (e.kind.live && aliveUntil < to) {
                val died = maxOf(aliveUntil, e.at)
                spans += ConnectionSegment(e.kind, e.at, died)
                spans += ConnectionSegment(ConnectionKind.NO_DATA, died, Long.MAX_VALUE)
            } else {
                spans += ConnectionSegment(e.kind, e.at, Long.MAX_VALUE)
            }
        }

        val segments = ArrayList<ConnectionSegment>()
        for (span in spans) {
            val start = maxOf(span.start, from)
            val end = minOf(span.end, to)
            if (end <= start) continue
            val openStart = span.start < from
            val openEnd = span.end > to
            val previous = segments.lastOrNull()
            if (previous != null && previous.kind == span.kind) {
                segments[segments.size - 1] = previous.copy(end = end, openEnd = openEnd)
            } else {
                segments += ConnectionSegment(span.kind, start, end, openStart, openEnd)
            }
        }

        var connected = 0L
        var on = 0L
        var interruptions = 0
        for (i in segments.indices) {
            val s = segments[i]
            if (s.kind.live) on += s.durationMs
            if (s.kind != ConnectionKind.CONNECTED) continue
            connected += s.durationMs
            // A stop is the user's own doing. Anything else that ends a connected stretch is not.
            val next = segments.getOrNull(i + 1)
            if (next != null && next.kind != ConnectionKind.STOPPED) interruptions++
        }
        val hasData = segments.any { it.kind != ConnectionKind.NO_DATA }
        return ConnectionTimeline(segments, ConnectionSummary(to - from, connected, on, interruptions, hasData))
    }
}

/**
 * The history on disk, and the one place the service reports to.
 *
 * Storage is one small text file in the app's files directory, appended to a line at a time:
 * an event is some twenty bytes and nothing is rewritten to add one, a line cut short by a
 * dying process costs that line only, and the file stays out of the shared preferences, which
 * are loaded whole on the main thread at start and travel in backups to other devices — this
 * is one device's own past. It is not kept per profile either: the timeline is of the service,
 * whichever account it ran.
 *
 * A process that dies cannot say so, so it leaves a trail instead: [heartbeat] appends the time
 * about once a minute while the service is up, and the next process closes the open segment at
 * the last such time ([ConnectionLog.appended]). The alternative — treating any long silence
 * between two events as a gap — cannot tell a dead process from a connection that was simply
 * steady for six hours, which is the case the timeline exists to show.
 *
 * Every call is a few in-memory steps and at most one short append under one lock, safe from
 * any thread, and nothing thrown here reaches the caller.
 */
object ConnectionHistory {
    private const val TAG = "ConnectionHistory"
    private const val FILE_NAME = "connection_history.log"

    private val lock = Any()
    private var loaded = false
    private var events = ArrayList<ConnectionEvent>()
    private var lastAlive = 0L
    private var lines = 0

    /** Nothing was written by this process yet: its first write checks for a process that died before it. */
    private var firstWrite = true

    private val changeCount = MutableStateFlow(0)

    /** Goes up whenever an event is added; the timeline reads the history again when it does. */
    val changes: StateFlow<Int> get() = changeCount

    /** The card now says [state]. Call on every change of the shown card; a repeat of the same kind is ignored. */
    internal fun record(context: Context, state: CardState, at: Long = System.currentTimeMillis()) =
        write(context, connectionKindOf(state), at)

    /** The service is going down: the timeline shows "stopped" from here until the next [record]. */
    fun recordStopped(context: Context, at: Long = System.currentTimeMillis()) =
        write(context, ConnectionKind.STOPPED, at)

    /**
     * The service is still up. Call from its refresh tick; it writes at most once in
     * [ConnectionLog.HEARTBEAT_EVERY_MS] and only while the last event is a live one, so a
     * call costs nothing most of the time.
     */
    fun heartbeat(context: Context, at: Long = System.currentTimeMillis()) = write(context, null, at)

    /**
     * The history for the interface. Reads the file the first time, so call it off the main
     * thread. [now] is the caller's clock, for the moment the last segment is known to reach.
     */
    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): ConnectionSnapshot = synchronized(lock) {
        runCatching { load(context) }.onFailure { Log.w(TAG, "History unreadable: ${it.message}") }
        // A live state this process has not written itself was left by one that died.
        ConnectionSnapshot(events.toList(), if (firstWrite) lastAlive else now)
    }

    private fun write(context: Context, kind: ConnectionKind?, at: Long) {
        try {
            val added = synchronized(lock) {
                load(context)
                val added = ConnectionLog.appended(events, lastAlive, kind, at, firstWrite)
                firstWrite = false
                val dueAlive = events.lastOrNull()?.kind?.live == true && at - lastAlive >= ConnectionLog.HEARTBEAT_EVERY_MS
                if (added.isEmpty() && !dueAlive) return
                events.addAll(added)
                lastAlive = maxOf(lastAlive, at)
                val pruned = ConnectionLog.pruned(events, at)
                if (pruned.size != events.size || lines >= ConnectionLog.COMPACT_AT_LINES) {
                    events = ArrayList(pruned)
                    rewrite(context)
                } else {
                    val text = if (added.isEmpty()) ConnectionLog.aliveLine(at) else added.joinToString("") { ConnectionLog.eventLine(it) }
                    file(context).appendText(text)
                    lines += maxOf(added.size, 1)
                }
                added
            }
            if (added.isNotEmpty()) changeCount.value++
        } catch (e: Exception) {
            Log.w(TAG, "History not written: ${e.message}")
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context) {
        if (loaded) return
        // Marked first: a file that cannot be read is an empty history, not one to retry on every call.
        loaded = true
        val file = file(context)
        if (!file.exists()) return
        val decoded = ConnectionLog.decode(file.readText())
        events = ArrayList(decoded.events)
        lastAlive = decoded.lastAlive
        lines = decoded.lines
    }

    /** The whole file again, through a temporary one and a rename: a reader of the next start never sees half of it. */
    private fun rewrite(context: Context) {
        val file = file(context)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        val text = ConnectionLog.encode(events, lastAlive)
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
        lines = events.size + 1
    }
}
