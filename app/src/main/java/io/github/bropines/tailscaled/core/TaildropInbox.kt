package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import appctr.Appctr
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * The Taildrop inbox without a screen: what it holds, and what can be done with a received
 * file — save, delete, hand to a viewer. The Files screen draws this and calls these; none
 * of it needs an Activity, a composition or a person in a system dialog, so anything else
 * that has a Context can list and manage received files the same way. Blocking I/O
 * throughout — never on the main thread.
 */
object TaildropInbox {
    private const val TAG = "TaildropInbox"

    /** Saved files the inbox shows besides the waiting ones, newest first. */
    private const val SAVED_ROWS = 10

    /** When the inbox was last looked at, epoch milliseconds; install-local, not exported. */
    private const val KEY_SEEN_AT = "taildrop_inbox_seen_at"

    /**
     * The inbox at one moment: the [files] still waiting in the app, each with a date — null
     * when the bridge's list could not be read; the [history] they are matched against; and
     * the receive [folder] with its [folderState], both null when there is none.
     */
    data class Snapshot(
        val files: List<TaildropFile>?,
        val history: List<TaildropHistoryEntry>,
        val folder: ReceiveFolder?,
        val folderState: TaildropFolder.State?,
    )

    /** Reads the inbox of the account whose received files are in [dir] ([TaildropPaths.ensureDir]). */
    fun list(context: Context, dir: File): Snapshot {
        val history = TaildropHistory.read(context)
        val files = try {
            // A development build asks the bridge for the directory of the daemon's current
            // run, a release one names the account's directory itself.
            val json = if (BuildConfig.IS_DEV) Appctr.getTaildropFilesFromAPI() else Appctr.getWaitingFiles(dir.absolutePath)
            withReceivedTimes(decodeFiles(json), history)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load waiting files", e)
            null
        }
        val folder = TaildropFolder.get(context)
        return Snapshot(files, history, folder, folder?.let { TaildropFolder.state(context, it) })
    }

    /**
     * When the inbox was last looked at; 0 before the first time, so that a file that came
     * before anybody opened the inbox is new. What arrived later is what a screen without
     * notifications sets apart ([markSeen] is called as the inbox leaves the screen).
     */
    fun seenAt(context: Context): Long = GlobalSettings.getLong(context, KEY_SEEN_AT, 0)

    fun markSeen(context: Context) = GlobalSettings.setLong(context, KEY_SEEN_AT, System.currentTimeMillis())

    /** The bridge's waiting-files JSON; an empty list for nothing or anything unreadable. */
    fun decodeFiles(json: String?): List<TaildropFile> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<TaildropFile>>(json) }.getOrDefault(emptyList())

    /**
     * The received entry a file in the inbox came with, for its sender line: by path, else by
     * name — but not one the default folder took, whose file left the inbox under that name.
     */
    fun receivedEntryOf(file: TaildropFile, history: List<TaildropHistoryEntry>): TaildropHistoryEntry? {
        val live = history.filter { it.direction == TaildropDirection.RECEIVED && it.deletedAt == null }
        return live.firstOrNull { it.path == file.Path } ?: live.firstOrNull { it.name == file.Name && it.savedUri == null }
    }

    /**
     * The inbox's files with a date each. The bridge's list carried no ModTime up to 4.7.4, so
     * every card read "1 Jan, 03:00"; where it is missing, the arrival logged in the history
     * stands in, then the file's own modification time. Reads the disk.
     */
    fun withReceivedTimes(files: List<TaildropFile>, history: List<TaildropHistoryEntry>): List<TaildropFile> =
        files.map { f ->
            if (f.ModTime > 0) f
            else {
                val millis = receivedEntryOf(f, history)?.timestamp ?: File(f.Path).lastModified()
                if (millis > 0) f.copy(ModTime = millis / 1000) else f
            }
        }

    /**
     * Received files the default folder took, for the inbox: saved there (they have a document
     * or a path to open), not hidden or deleted, and not still waiting in the app — a file the
     * folder took whose original could not be removed is listed once, as a waiting file.
     */
    fun savedEntries(files: List<TaildropFile>, history: List<TaildropHistoryEntry>): List<TaildropHistoryEntry> {
        val waiting = files.mapNotNullTo(HashSet()) { receivedEntryOf(it, history) }
        return history.asSequence()
            .filter {
                it.direction == TaildropDirection.RECEIVED && it.savedUri != null &&
                    it.dismissedAt == null && it.deletedAt == null && it !in waiting
            }
            .take(SAVED_ROWS)
            .toList()
    }

    /** When a waiting file arrived, in milliseconds; 0 when nothing says. */
    fun arrivedAt(file: TaildropFile): Long = file.ModTime * 1000

    /**
     * Moves a waiting file into the receive folder, as an arrival does; see
     * [TaildropSave.Result]. [TaildropSave.copyToDir] and [TaildropSave.copyToDocument] save
     * it somewhere else and leave it in the inbox.
     */
    fun save(context: Context, file: TaildropFile): TaildropSave.Result = TaildropSave.moveToFolder(context, file)

    /**
     * Deletes a waiting file and notes it in the history; false when it is still there. The
     * bridge takes a plain file name only and removes it from the running daemon's directory.
     */
    fun delete(context: Context, file: TaildropFile): Boolean {
        if (!Appctr.deleteTaildropFileFromAPI(file.Name)) return false
        TaildropHistory.markDeleted(context, file)
        return true
    }

    /**
     * VIEW of a file waiting in the app, through the FileProvider's "taildrop" root, with
     * the read grant. Throws when the file is not under that root.
     */
    fun viewIntent(context: Context, file: TaildropFile): Intent {
        var f = File(file.Path)
        // The FileProvider only covers files/taildrop/. A Root Mode daemon started by the
        // boot script keeps writing into the pre-4.0 directory until the app hands it the
        // new one, so a path from outside is migrated and re-resolved instead of failing.
        val root = File(context.filesDir, "taildrop").absolutePath + File.separator
        if (!f.absolutePath.startsWith(root)) {
            val accountId = AccountManager.getActiveAccount(context).id
            TaildropPaths.migrate(context, accountId)
            val moved = File(TaildropPaths.dir(context, accountId), f.name)
            if (moved.exists()) f = moved
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** VIEW of a received file the folder took; null for an entry that has no saved copy. Throws like [TaildropSave.viewIntent]. */
    fun viewIntent(context: Context, entry: TaildropHistoryEntry): Intent? {
        val uri = entry.savedUri?.let(Uri::parse) ?: return null
        return TaildropSave.viewIntent(context, uri, entry.mime ?: mimeTypeOf(entry.savedTo ?: entry.name))
    }
}
