package io.github.bropines.tailscaled.core

import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/*
 * Downloading from the other devices' shares (Taildrive), the rules without Android: what a
 * selection comes to, the question a selection with folders asks first, the walk that finds what
 * is inside those folders, the names things get where they land, and whether the folder they land
 * in is asked for. The running of a download is core/TaildriveDownloads.kt, where it is written
 * core/TaildriveDownloadSink.kt, the screens ui/TaildriveDownloadUi.kt.
 */

// ---------------------------------------------------------------------------------------------
// The selection
// ---------------------------------------------------------------------------------------------

/** The answer to the question a selection with folders asks ([DownloadSelection.asks]). */
enum class FolderChoice {
    /** The folders with everything inside them, and the files. */
    ALL,

    /** The selected files alone; the folders stay where they are. */
    FILES_ONLY,

    CANCEL,
}

object DownloadSelection {
    /**
     * Whether a download of [entries] asks first: a folder among them, which brings everything
     * inside it. A selection of files starts at once, as the button on a file does.
     */
    fun asks(entries: List<DriveEntry>): Boolean = entries.any { it.isFolder }

    /** Whether "files only" is a choice at all: a file among [entries]. */
    fun hasFiles(entries: List<DriveEntry>): Boolean = entries.any { !it.isFolder }

    /** What is downloaded once [choice] is made: null when nothing is. */
    fun after(entries: List<DriveEntry>, choice: FolderChoice): List<DriveEntry>? = when (choice) {
        FolderChoice.ALL -> entries
        FolderChoice.FILES_ONLY -> entries.filter { !it.isFolder }
        FolderChoice.CANCEL -> emptyList()
    }.ifEmpty { null }
}

// ---------------------------------------------------------------------------------------------
// The walk
// ---------------------------------------------------------------------------------------------

/**
 * What a download is made of: a folder to make, or a file to fetch, by its names under the folder
 * the selection was made in — `["Season 2", "Extras", "trailer.mp4"]`. A folder comes before
 * anything inside it, so the structure is there however a walk interleaves.
 */
sealed interface WalkItem {
    val path: List<String>

    data class Folder(override val path: List<String>) : WalkItem

    /** [size] as the listing gave it; 0 when the server did not say. */
    data class File(override val path: List<String>, val size: Long) : WalkItem
}

/**
 * What a walk has counted so far. [folders] are the folders found inside the selected ones, the
 * selected themselves included; [unlisted] those that could not be listed, and so are missing
 * from the rest. [done] once nothing is left to list.
 */
data class WalkTotals(
    val folders: Int = 0,
    val files: Int = 0,
    val bytes: Long = 0,
    val unlisted: Int = 0,
    val done: Boolean = false,
) {
    operator fun plus(item: WalkItem): WalkTotals = when (item) {
        is WalkItem.Folder -> copy(folders = folders + 1)
        is WalkItem.File -> copy(files = files + 1, bytes = bytes + item.size.coerceAtLeast(0))
    }
}

/** A finished walk: everything it found, and the folders it could not list (by their paths). */
data class Walked(val items: List<WalkItem>, val unlisted: List<List<String>>) {
    val totals: WalkTotals
        get() = items.fold(WalkTotals()) { t, item -> t + item }.copy(unlisted = unlisted.size, done = true)
}

/**
 * The folders of a selection, walked through the listing the browser itself uses
 * (TaildriveBrowse.list): one PROPFIND a folder, never a byte of a file. Depth first, with at most
 * [CONCURRENCY] listings in flight, so a tree of many small folders does not take one round trip
 * over the tailnet per folder in turn, and one of many does not flood the other device.
 */
object DriveWalk {
    const val CONCURRENCY = 4

    /**
     * Walks [folders], the names of folders in [parent], and hands [found] every folder (itself
     * first) and every file under them as it is listed. [found] is called by one walker at a time.
     * [list] blocks; it runs on the IO dispatcher and is interrupted when the walk is cancelled.
     * Returns the folders that could not be listed: their contents are missing, the rest goes on.
     */
    suspend fun walk(
        parent: DriveLocation,
        folders: List<String>,
        list: (DriveLocation) -> DriveResult<List<DriveEntry>>,
        concurrency: Int = CONCURRENCY,
        found: suspend (WalkItem) -> Unit,
    ): List<List<String>> {
        val gate = Semaphore(concurrency)
        val one = Mutex()
        val unlisted = ConcurrentLinkedQueue<List<String>>()
        suspend fun emit(item: WalkItem) = one.withLock { found(item) }

        suspend fun visit(path: List<String>) {
            emit(WalkItem.Folder(path))
            val at = DriveLocation(parent.segments + path)
            val listed = gate.withPermit { runInterruptible(Dispatchers.IO) { list(at) } }
            val entries = when (listed) {
                is DriveResult.Ok -> listed.value
                is DriveResult.Err -> {
                    unlisted += path
                    return
                }
            }
            // The files of a folder before its folders: what is here is known before what is deeper.
            for (entry in entries) if (!entry.isFolder) emit(WalkItem.File(path + entry.name, entry.size))
            coroutineScope {
                for (entry in entries) if (entry.isFolder) launch { visit(path + entry.name) }
            }
        }

        coroutineScope {
            for (name in folders) launch { visit(listOf(name)) }
        }
        return unlisted.toList()
    }

    /** [walk] to the end, everything it found in one list: what the question counts and a download can reuse. */
    suspend fun collect(
        parent: DriveLocation,
        folders: List<String>,
        list: (DriveLocation) -> DriveResult<List<DriveEntry>>,
        concurrency: Int = CONCURRENCY,
        onProgress: (WalkTotals) -> Unit = {},
    ): Walked {
        val items = ArrayList<WalkItem>()
        var totals = WalkTotals()
        val unlisted = walk(parent, folders, list, concurrency) { item ->
            items += item
            totals += item
            onProgress(totals)
        }
        return Walked(items, unlisted)
    }

    /** The selected files as the items of a download, ahead of whatever the folders hold. */
    fun filesOf(entries: List<DriveEntry>): List<WalkItem.File> =
        entries.filter { !it.isFolder }.map { WalkItem.File(listOf(it.name), it.size) }
}

// ---------------------------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------------------------

object DownloadNames {
    /**
     * A file's name in a folder that holds [taken]: [name], or "name (1).ext", "name (2).ext"… —
     * the rule Taildrop saves by ([TaildropSave.freeName]), case left aside as shared storage does.
     */
    fun freeFile(name: String, taken: Set<String>): String = TaildropSave.freeName(name, taken)

    /**
     * A folder's name: [name], or "name (1)", "name (2)"… A folder has no extension to keep, so
     * "Season 1.5" becomes "Season 1.5 (1)", not "Season 1 (1).5". Compared without case.
     */
    fun freeFolder(name: String, taken: Set<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (name.lowercase(Locale.ROOT) !in used) return name
        var n = 1
        while (true) {
            val candidate = "$name ($n)"
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
            n++
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Where it goes
// ---------------------------------------------------------------------------------------------

/** Where a download goes: the folder of Settings, or one asked for now. */
sealed interface DownloadDestination {
    data class Default(val folder: ReceiveFolder) : DownloadDestination

    /** Asked for now. [lost]: there is a default and it cannot be written any more — say so. */
    data class Ask(val lost: Boolean = false) : DownloadDestination

    companion object {
        /** "Ask every time" when no folder is set ([default] null); the folder when it can be written; asked otherwise. */
        fun of(default: ReceiveFolder?, writable: Boolean): DownloadDestination = when {
            default == null -> Ask()
            writable -> Default(default)
            else -> Ask(lost = true)
        }
    }
}
