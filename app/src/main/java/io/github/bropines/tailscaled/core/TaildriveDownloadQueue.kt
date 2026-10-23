package io.github.bropines.tailscaled.core

import androidx.compose.runtime.Immutable
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.exp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Taildrive downloads as a queue, the rules without Android. Every download asked for takes its
 * place in the order asked, and up to [DownloadWorld.slots] files are read at the same time across
 * all of them: the files of one download side by side, and, when it has fewer left than there are
 * free slots, the next download's as well. A download begins — its folders walked, its files
 * counted — when a slot is free that the ones before it cannot fill, and at most as many run as
 * there are slots.
 *
 * What a file is read from and written to comes from the world (core/TaildriveDownloads.kt gives
 * Android's, the tests their own): nothing is held whole in memory, a file that fails is listed and
 * the rest go on, a cancel closes the files in flight and keeps what was saved, a connection that
 * broke because the node is gone ends every download. Names are claimed one at a time per folder
 * written to, so two files that want the same name never get it both.
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
    /** Behind other downloads, not begun. */
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

/** A file being written: its name, how much of it has come, and its size — 0 when the server did not say. */
@Immutable
data class FileProgress(val name: String, val done: Long = 0, val size: Long = 0) {
    /** 0..1; null for a file whose size is not known. */
    val fraction: Float?
        get() = if (size > 0) (done.toDouble() / size).coerceIn(0.0, 1.0).toFloat() else null
}

/** A download as the browser and the notification show it. */
@Immutable
data class DownloadState(
    val id: Int,
    /** The folder as the person knows it: "Download/Movies". */
    val folderLabel: String,
    val folder: ReceiveFolder,
    val pickedNow: Boolean,
    val stage: DownloadStage = DownloadStage.WAITING,
    /** The first name of the selection, and how many it holds: what tells one download from another. */
    val what: String = "",
    val selected: Int = 1,
    /** The folders are still being walked: [files] and [bytes] may grow. */
    val counting: Boolean = false,
    val files: Int = 0,
    val bytes: Long = 0,
    /** Files that are over, saved or not. */
    val filesDone: Int = 0,
    val bytesDone: Long = 0,
    /** The files being written now, in the order they started. */
    val writing: List<FileProgress> = emptyList(),
    val saved: Int = 0,
    val failures: List<DownloadFailure> = emptyList(),
    /** Smoothed; null until it has been measured, and while nothing arrives. */
    val bytesPerSecond: Long? = null,
    /** When it ended among the others, 1 for the first; 0 while it is active. */
    val ended: Int = 0,
) {
    /** 0..1 of the whole; null while the total is not known yet. */
    val fraction: Float?
        get() = if (!counting && bytes > 0) (bytesDone.toDouble() / bytes).coerceIn(0.0, 1.0).toFloat() else null
}

/** The queue in one line, as the collapsed panel and the notification say it: the running downloads summed. */
@Immutable
data class DownloadQueueSummary(
    val running: Int = 0,
    val waiting: Int = 0,
    val finished: Int = 0,
    /** Files being written now, across every download. */
    val writing: Int = 0,
    val files: Int = 0,
    val filesDone: Int = 0,
    val bytes: Long = 0,
    val bytesDone: Long = 0,
    /** A running download is still walking its folders: the totals may grow. */
    val counting: Boolean = false,
    val bytesPerSecond: Long? = null,
) {
    val active: Int get() = running + waiting

    /** 0..1 of what runs; null while a total is not known. */
    val fraction: Float?
        get() = if (!counting && bytes > 0) (bytesDone.toDouble() / bytes).coerceIn(0.0, 1.0).toFloat() else null

    companion object {
        fun of(downloads: List<DownloadState>): DownloadQueueSummary {
            val running = downloads.filter { it.stage == DownloadStage.RUNNING }
            val rates = running.mapNotNull { it.bytesPerSecond }
            return DownloadQueueSummary(
                running = running.size,
                waiting = downloads.count { it.stage == DownloadStage.WAITING },
                finished = downloads.count { !it.stage.active },
                writing = running.sumOf { it.writing.size },
                files = running.sumOf { it.files },
                filesDone = running.sumOf { it.filesDone },
                bytes = running.sumOf { it.bytes },
                bytesDone = running.sumOf { it.bytesDone },
                counting = running.any { it.counting },
                bytesPerSecond = rates.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }
}

/** The "Parallel downloads" setting: how many files are read at once. */
object DownloadSlots {
    const val DEFAULT = 3
    val CHOICES = 1..4

    /** The stored value, or [DEFAULT] for none or one out of [CHOICES]. Stored as a string, as the global settings are. */
    fun of(stored: String?): Int = stored?.toIntOrNull()?.takeIf { it in CHOICES } ?: DEFAULT
}

/** A file of a share, read once from its start to its end. */
internal interface DriveFileReader : Closeable {
    /** The file's length as the server gave it; -1 when it did not. */
    val size: Long

    /** As [InputStream.read]: -1 at the end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Where a download's files come from: the listing a walk takes, and a file read from its start. Both block. */
internal interface DownloadSource {
    fun list(at: DriveLocation): DriveResult<List<DriveEntry>>

    /** The file at [path], a WebDAV path. */
    fun open(path: String): DriveFileReader
}

/** What [DownloadQueue] needs from the world. */
internal interface DownloadWorld {
    /** How many files may be read at once, now: asked whenever a slot may be given. */
    fun slots(): Int

    /** A clock in milliseconds that only goes forward. */
    fun now(): Long

    /** The folder a download writes into. Cheap: nothing is asked of the storage yet. */
    fun sink(folder: ReceiveFolder): DownloadSink

    /** The folder by the name the person knows it; null when it cannot be had. Blocking. */
    fun label(folder: ReceiveFolder): String?

    /** The share [request] reads from. Cheap. */
    fun source(request: DownloadRequest): DownloadSource

    /** Whether the node runs: a connection that broke while it does not ends every download. Blocking. */
    fun nodeRunning(): Boolean

    /** A download began; once its folder has a name. */
    fun started(state: DownloadState) {}

    /** A download that began is over. Not said of one that never began. */
    fun ended(state: DownloadState) {}

    fun log(message: String) {}
}

/** Writing into the folder failed, as against reading from the share. */
internal class WriteFailed : IOException()

/** Fewer bytes came than the server said the file has. */
internal class Incomplete : IOException()

/**
 * The downloads, in the order asked, and the slots their files are read in. Every method returns
 * at once; the work runs in [scope], which blocking reads and writes are fine in.
 */
internal class DownloadQueue(private val world: DownloadWorld, private val scope: CoroutineScope) {

    /** Guards everything below and every [Run]'s fields; never held across a read, a write or a call that blocks. */
    private val lock = Any()
    private val runs = ArrayList<Run>()
    private var lastId = 0
    private var endings = 0
    /** Files being read now, across every download. */
    private var reading = 0
    /** One sink for each folder written to while a download into it runs, by [folderKey]. */
    private val sinks = HashMap<String, Lease>()
    /** What to do once the lock is let go: hand the world what happened, close readers. */
    private val after = ArrayList<() -> Unit>()

    private val state = MutableStateFlow<List<DownloadState>>(emptyList())
    private val lastPublished = AtomicLong(0)

    /** Every download that is running, waiting, or over and not yet put away from the page, in the order asked. */
    val downloads: StateFlow<List<DownloadState>> = state.asStateFlow()

    /** Queues [request]; returns its id. [label] names its folder until a better name is found. */
    fun add(request: DownloadRequest, label: String): Int = locked {
        val run = Run(++lastId, request, label)
        runs += run
        dispatch()
        run.id
    }

    /** Stops download [id]: one that waits is taken off the queue; one that runs loses the files in flight and keeps what it saved. */
    fun cancel(id: Int) = locked {
        val run = runs.firstOrNull { it.id == id } ?: return@locked
        when (run.stage) {
            DownloadStage.WAITING -> runs.remove(run)
            DownloadStage.RUNNING -> halt(run, stopped = false)
            else -> Unit
        }
        dispatch()
    }

    /** [cancel] of every download that is active. */
    fun cancelAll() = locked {
        runs.removeAll { it.stage == DownloadStage.WAITING }
        runs.filter { it.stage == DownloadStage.RUNNING }.forEach { halt(it, stopped = false) }
    }

    /** Takes a finished download off the page. */
    fun dismiss(id: Int) = locked {
        runs.removeAll { it.id == id && !it.stage.active }
    }

    /** Takes every finished download off the page. */
    fun dismissFinished() = locked {
        runs.removeAll { !it.stage.active }
    }

    /** The setting changed: more slots may be free now. */
    fun slotsChanged() = locked { dispatch() }

    /** Whether anything runs or waits. */
    val active: Boolean get() = synchronized(lock) { runs.any { it.stage.active } }

    /** Once a second while anything runs: each download's rate is measured, and the page told. */
    fun tick() = locked {
        val now = world.now()
        for (run in runs) if (run.stage == DownloadStage.RUNNING) run.measure(now)
    }

    // ---------------------------------------------------------------------------------------------
    // The queue
    // ---------------------------------------------------------------------------------------------

    private class Lease(val sink: DownloadSink) {
        var users = 0
    }

    private class Writing(val name: String, size: Long) {
        var done = 0L
        /** The size the totals count for this file: the listing's, then the server's. */
        var size = size
        /** Closed by a cancel, which aborts the request in flight. */
        var reader: DriveFileReader? = null
    }

    /** A download. Its fields are guarded by the queue's lock, [made] by itself. */
    private inner class Run(val id: Int, request: DownloadRequest, var label: String) {
        var request: DownloadRequest? = request
        val folder = request.folder
        val pickedNow = request.pickedNow
        val what = request.entries.firstOrNull()?.name.orEmpty()
        val selected = request.entries.size
        var stage = DownloadStage.WAITING
        var begun = false
        var walking = false
        var counting = false
        var files = 0
        var bytes = 0L
        var filesDone = 0
        var bytesDone = 0L
        var saved = 0
        var failures: List<DownloadFailure> = emptyList()
        val pending = ArrayDeque<WalkItem.File>()
        val writing = ArrayList<Writing>()
        var cancelled = false
        var stopped = false
        var ended = 0
        var job: Job? = null
        var source: DownloadSource? = null
        var sink: DownloadSink? = null
        /** The folders made so far, by their path under the selection. */
        val made = HashMap<List<String>, SinkNode>()

        // The rate, from the bytes read: a file that fails moves the whole on, not the rate.
        var received = 0L
        var rate: Double? = null
        var rateBytes = 0L
        /** -1 until the first tick has taken its measure. */
        var rateAt = -1L

        fun measure(now: Long) {
            if (rateAt < 0) {
                rateBytes = received
                rateAt = now
                return
            }
            val dt = now - rateAt
            if (dt <= 0) return
            val sample = (received - rateBytes) * 1000.0 / dt
            val weight = 1 - exp(-dt / RATE_TAU_MS)
            rate = rate?.let { it + weight * (sample - it) } ?: sample
            rateBytes = received
            rateAt = now
        }

        fun snapshot() = DownloadState(
            id = id, folderLabel = label, folder = folder, pickedNow = pickedNow, stage = stage,
            what = what, selected = selected, counting = counting,
            files = files, bytes = bytes, filesDone = filesDone, bytesDone = bytesDone,
            writing = writing.map { FileProgress(it.name, it.done, it.size) },
            saved = saved, failures = failures,
            bytesPerSecond = rate?.toLong()?.takeIf { stage == DownloadStage.RUNNING && it > 0 },
            ended = ended,
        )
    }

    /** Runs [block] under the lock, tells the page, then does what it left for after. */
    private inline fun <T> locked(block: () -> T): T {
        val actions: List<() -> Unit>
        val result = synchronized(lock) {
            val r = block()
            publishLocked()
            actions = after.toList()
            after.clear()
            r
        }
        for (action in actions) {
            try {
                action()
            } catch (e: Exception) {
                world.log("download queue: ${e.javaClass.simpleName}")
            }
        }
        return result
    }

    private fun publishLocked() {
        lastPublished.set(world.now())
        state.value = runs.map { it.snapshot() }
    }

    /** How far the files have got, at most every [PUBLISH_MS]. */
    private fun publish() {
        val now = world.now()
        val last = lastPublished.get()
        if (now - last < PUBLISH_MS || !lastPublished.compareAndSet(last, now)) return
        synchronized(lock) { state.value = runs.map { it.snapshot() } }
    }

    /**
     * Gives the free slots away: to the first download in the order asked that has a file ready,
     * and, when none has, to the next download in the queue, which begins — while fewer run than
     * there are slots. Under the lock.
     */
    private fun dispatch() {
        val slots = world.slots().coerceIn(DownloadSlots.CHOICES)
        while (reading < slots) {
            val ready = runs.firstOrNull { it.stage == DownloadStage.RUNNING && it.pending.isNotEmpty() }
            if (ready != null) {
                read(ready, ready.pending.removeFirst())
                continue
            }
            if (runs.count { it.stage == DownloadStage.RUNNING } >= slots) return
            begin(runs.firstOrNull { it.stage == DownloadStage.WAITING } ?: return)
        }
    }

    /** [run] begins: what it already knows is ready at once, its folders are walked beside. Under the lock. */
    private fun begin(run: Run) {
        // Only an ended download has no request; one that waits always has it. Ended, it cannot be begun again.
        val request = run.request ?: return end(run, DownloadStage.DONE)
        val folders = request.entries.filter { it.isFolder }.map { it.name }
        run.stage = DownloadStage.RUNNING
        run.begun = true
        run.source = world.source(request)
        // The selected files, and what is inside the folders when the question has walked them already.
        for (file in DriveWalk.filesOf(request.entries)) enqueue(run, file)
        request.walked?.items?.forEach { if (it is WalkItem.File) enqueue(run, it) }
        run.counting = folders.isNotEmpty() && request.walked == null
        run.walking = true
        val job = SupervisorJob(scope.coroutineContext[Job])
        run.job = job
        val walk = CoroutineScope(scope.coroutineContext + job).launch(start = CoroutineStart.LAZY) { walk(run, request, folders) }
        walk.invokeOnCompletion {
            locked {
                run.walking = false
                run.counting = false
                settle(run)
                dispatch()
            }
        }
        walk.start()
    }

    private fun enqueue(run: Run, file: WalkItem.File) {
        run.files++
        run.bytes += file.size.coerceAtLeast(0)
        run.pending.addLast(file)
    }

    /**
     * The folders of [run], walked while its files are read: each folder made as it is found (an
     * empty one too), each file queued. A walk the question finished has only its folders to make.
     */
    private suspend fun walk(run: Run, request: DownloadRequest, folders: List<String>) {
        val label = runCatching { world.label(run.folder) }.getOrNull()
        locked {
            if (label != null) run.label = label
            val begun = run.snapshot()
            after += { world.started(begun) }
        }
        val walked = request.walked
        val unlisted = when {
            walked != null -> {
                for (item in walked.items) if (item is WalkItem.Folder) {
                    currentCoroutineContext().ensureActive()
                    makeFolder(run, item.path)
                }
                walked.unlisted
            }
            folders.isEmpty() -> emptyList()
            else -> {
                val source = synchronized(lock) { run.source } ?: return
                DriveWalk.walk(request.parent, folders, { source.list(it) }) { item ->
                    when (item) {
                        is WalkItem.Folder -> makeFolder(run, item.path)
                        is WalkItem.File -> locked {
                            if (run.stage == DownloadStage.RUNNING && !run.cancelled && !run.stopped) {
                                enqueue(run, item)
                                dispatch()
                            }
                        }
                    }
                }
            }
        }
        if (unlisted.isNotEmpty()) locked {
            run.failures = run.failures + unlisted.map { DownloadFailure(it.joinToString("/"), DownloadFailReason.UNLISTED) }
        }
    }

    private fun makeFolder(run: Run, path: List<String>) {
        try {
            folderAt(run, path)
        } catch (e: Exception) {
            locked { run.failures = run.failures + DownloadFailure(path.joinToString("/"), DownloadFailReason.WRITE) }
        }
    }

    /** The folder at [path] under the download folder, made the first time it is asked for; null for the download folder itself. */
    private fun folderAt(run: Run, path: List<String>): SinkNode? {
        if (path.isEmpty()) return null
        synchronized(run.made) {
            run.made[path]?.let { return it }
            val parent = folderAt(run, path.dropLast(1))
            return sinkOf(run).newFolder(parent, path.last()).also { run.made[path] = it }
        }
    }

    /** The sink of [run]'s folder, shared with every other download into it while they run. */
    private fun sinkOf(run: Run): DownloadSink = synchronized(lock) {
        run.sink ?: sinks.getOrPut(folderKey(run.folder)) { Lease(ClaimsInTurn(world.sink(run.folder))) }
            .also { it.users++ }.sink.also { run.sink = it }
    }

    /** A slot for [file] of [run]. Under the lock. */
    private fun read(run: Run, file: WalkItem.File) {
        // Every running download has its job from [begin].
        val job = run.job ?: return run.pending.clear()
        val writing = Writing(file.path.last(), file.size.coerceAtLeast(0))
        run.writing += writing
        reading++
        val fetch = CoroutineScope(scope.coroutineContext + job).launch(start = CoroutineStart.LAZY) { fetch(run, file, writing) }
        fetch.invokeOnCompletion {
            locked {
                reading--
                run.writing.remove(writing)
                run.filesDone++
                // A file that ended short is over all the same: the whole moves on by what it had left.
                run.bytesDone += (writing.size - writing.done).coerceAtLeast(0)
                settle(run)
                dispatch()
            }
        }
        fetch.start()
    }

    private suspend fun fetch(run: Run, item: WalkItem.File, writing: Writing) {
        currentCoroutineContext().ensureActive()
        val (request, source) = synchronized(lock) { run.request to run.source }
        if (request == null || source == null) return
        var claimed: Pair<DownloadSink, SinkNode>? = null
        try {
            // Claimed first, as Taildrop's files are: a name that is taken gets a number, never written over.
            val (sink, file) = try {
                val sink = sinkOf(run)
                sink to sink.newFile(folderAt(run, item.path.dropLast(1)), writing.name)
            } catch (e: Exception) {
                throw WriteFailed()
            }
            claimed = sink to file
            val reader = source.open(DriveLocation(request.parent.segments + item.path).davPath)
            // Where a cancel finds it: closing it aborts the read in flight.
            synchronized(lock) { writing.reader = reader }
            reader.use {
                val expected = reader.size.takeIf { it >= 0 } ?: item.size.takeIf { it > 0 }
                if (expected != null) synchronized(lock) {
                    run.bytes += expected - writing.size
                    writing.size = expected
                }
                val out: OutputStream = try {
                    sink.write(file)
                } catch (e: Exception) {
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
                        synchronized(lock) {
                            writing.done += n
                            run.bytesDone += n
                            run.received += n
                            // A size nobody gave: the whole grows as it comes, and never runs past its end.
                            if (expected == null) run.bytes += n
                        }
                        publish()
                    }
                }
                if (expected != null && writing.done != expected) throw Incomplete()
            }
            locked { run.saved++ }
        } catch (e: CancellationException) {
            discard(claimed)
            throw e
        } catch (e: Exception) {
            discard(claimed)
            // A cancel closes the file under the read, which then fails like a broken connection: it is the cancel.
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
            val reason = failReason(e)
            world.log("a file was not saved: ${e.javaClass.simpleName} -> $reason")
            // A connection that failed because the node is gone: nothing after it can be read either.
            val gone = (reason == DownloadFailReason.NETWORK || reason == DownloadFailReason.TIMEOUT) && !world.nodeRunning()
            locked {
                run.failures = run.failures + DownloadFailure(item.path.joinToString("/"), reason)
                if (gone) stopAll()
            }
        } finally {
            synchronized(lock) { writing.reader = null }
        }
    }

    /** Takes away a file that was not finished: half a file under the right name is worse than none. */
    private fun discard(claimed: Pair<DownloadSink, SinkNode>?) {
        if (claimed != null) runCatching { claimed.first.discard(claimed.second) }
    }

    /** Ends [run] once nothing of it is left to read, walk or wait for. Under the lock. */
    private fun settle(run: Run) {
        if (run.stage != DownloadStage.RUNNING || run.walking || run.writing.isNotEmpty() || run.pending.isNotEmpty()) return
        end(
            run,
            when {
                run.stopped -> DownloadStage.STOPPED
                run.cancelled -> DownloadStage.CANCELLED
                else -> DownloadStage.DONE
            }
        )
    }

    private fun end(run: Run, stage: DownloadStage) {
        run.stage = stage
        run.counting = false
        run.rate = null
        run.ended = ++endings
        run.request = null
        run.source = null
        run.pending.clear()
        run.job?.cancel()
        run.sink?.let { sink ->
            val key = folderKey(run.folder)
            sinks[key]?.takeIf { it.sink === sink }?.let { lease -> if (--lease.users <= 0) sinks.remove(key) }
        }
        run.sink = null
        if (run.begun) {
            val over = run.snapshot()
            after += { world.ended(over) }
        }
    }

    /** Stops [run]: nothing more starts, the files in flight are closed, and it ends once they have. Under the lock. */
    private fun halt(run: Run, stopped: Boolean) {
        if (stopped) run.stopped = true else run.cancelled = true
        run.pending.clear()
        run.job?.cancel()
        val open = run.writing.mapNotNull { it.reader }
        if (open.isNotEmpty()) after += { open.forEach { runCatching { it.close() } } }
        settle(run)
    }

    /** The node is gone: every download stops, the waiting ones too — nothing can be read. Under the lock. */
    private fun stopAll() {
        for (run in runs.toList()) when (run.stage) {
            DownloadStage.WAITING -> {
                run.stopped = true
                end(run, DownloadStage.STOPPED)
            }
            DownloadStage.RUNNING -> halt(run, stopped = true)
            else -> Unit
        }
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        /** How often the page is told how far the files have got. */
        private const val PUBLISH_MS = 200L
        private const val RATE_TAU_MS = 3_000.0

        /** Downloads into the same folder share its sink: one cache of its names, one claim at a time. */
        private fun folderKey(folder: ReceiveFolder): String = when (folder) {
            is ReceiveFolder.Tree -> "tree:" + folder.uri
            is ReceiveFolder.Dir -> "dir:" + folder.dir.path
        }

        /** Why [e] ended a file. */
        fun failReason(e: Throwable): DownloadFailReason = when (e) {
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
    }
}
