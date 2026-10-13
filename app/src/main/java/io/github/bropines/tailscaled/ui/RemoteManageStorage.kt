package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.BrowseRoot
import io.github.bropines.tailscaled.core.FileEntry
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.Listing
import io.github.bropines.tailscaled.core.ManagedRefusal
import io.github.bropines.tailscaled.core.ManagedResult
import io.github.bropines.tailscaled.core.ManagedStorageEntry
import io.github.bropines.tailscaled.core.ManagedStorageList
import io.github.bropines.tailscaled.core.ManagedStorageRoot
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.core.StorageAccess
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A managed device's storage as the app's file browser reads a disk (core/FileBrowse.kt): the
 * places are the device's roots, a folder's listing is one request to it. No file's bytes come
 * this way — the browser only ever learns names, sizes and dates (contract §10).
 *
 * [list] is the request (RemoteClient.storageList), an empty path for the roots, an offset for
 * the next part of a folder longer than one answer; its failures are kept apart by what the
 * browser can say of them. A folder that is gone or refused is the
 * browser's own notice. Anything that is not about the folder — the device went away, Full
 * control was switched off meanwhile — goes to [onFailure], and the page says it: the browser
 * has no words for that.
 *
 * Paths are the device's, carried in [File] only because that is what the browser passes
 * around; nothing here touches this device's disk.
 */
internal class RemoteFileSource(
    private val list: (path: String, offset: Int) -> ManagedResult<ManagedStorageList>,
    private val onFailure: (ManagedResult.Failed) -> Unit,
) : FileSource {

    /** What the listings have shown so far: the browser asks about one path again before it answers. */
    private val seen = ConcurrentHashMap<String, FileEntry>()

    override fun roots(start: File?): List<BrowseRoot> = when (val answer = list("", 0)) {
        is ManagedResult.Failed -> {
            onFailure(answer)
            emptyList()
        }
        is ManagedResult.Ok -> answer.value.roots.orEmpty().filter { it.path.isNotEmpty() }.map { root ->
            val dir = File(root.path)
            seen[dir.path] = FileEntry(dir, isDirectory = true)
            // The device's own storage is named here, in this device's language; a card or a stick
            // by the name the managed device's system gives it, as that device shows it.
            if (root.kind == ManagedStorageRoot.PRIMARY) BrowseRoot(RootKind.PRIMARY, dir)
            else BrowseRoot(RootKind.VOLUME, dir, label = root.name.ifEmpty { dir.name }.ifEmpty { null }, removable = root.kind == ManagedStorageRoot.REMOVABLE)
        }
    }

    /**
     * A folder whole: the device answers 500 entries at a time, and the parts are appended as
     * they come — they are in its own browser's order already, folders first. A folder beyond
     * [MAX_PARTS] answers is cut there; nobody picks the ten-thousandth file by scrolling to it.
     */
    private fun whole(path: String): ManagedResult<ManagedStorageList> {
        var answer = list(path, 0)
        val first = answer.valueOrNull ?: return answer
        val entries = ArrayList<ManagedStorageEntry>(first.entries)
        var last = first
        var parts = 1
        while (last.truncated && last.entries.isNotEmpty() && parts < MAX_PARTS) {
            answer = list(path, entries.size)
            // A part that did not come leaves what did: the folder is shown as far as it was read.
            last = answer.valueOrNull ?: break
            entries += last.entries
            parts++
        }
        return ManagedResult.Ok(first.copy(entries = entries, truncated = last.truncated))
    }

    override fun list(dir: File, showHidden: Boolean): Listing = when (val answer = whole(dir.path)) {
        is ManagedResult.Ok -> {
            val entries = answer.value.entries.filter { it.name.isNotEmpty() }.map { entry ->
                FileEntry(File(dir, entry.name), isDirectory = entry.dir, size = entry.size, modified = entry.time * 1000)
                    .also { seen[it.file.path] = it }
            }
            // Hidden files the device never lists.
            Listing.Entries(dir, entries, limited = answer.value.limited, writable = true)
        }
        is ManagedResult.Failed -> when (answer.refusal) {
            ManagedRefusal.NOT_FOUND -> Listing.Unreadable(dir, Listing.Reason.NOT_FOUND)
            // Outside the roots, or the system refuses it there whatever is granted.
            ManagedRefusal.FORBIDDEN, ManagedRefusal.REFUSED -> Listing.Unreadable(dir, Listing.Reason.DENIED)
            else -> {
                onFailure(answer)
                Listing.Unreadable(dir, Listing.Reason.DENIED)
            }
        }
    }

    override fun describe(file: File): FileEntry? = seen[file.path]

    /** The access that matters is the managed device's own, and its listings say when it is missing. */
    override val access: StorageAccess get() = StorageAccess.GRANTED

    private companion object {
        /** How many answers of 500 entries one folder is read to. */
        const val MAX_PARTS = 20
    }
}
