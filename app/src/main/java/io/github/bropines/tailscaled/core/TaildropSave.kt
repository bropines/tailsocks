package io.github.bropines.tailscaled.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import appctr.Appctr
import io.github.bropines.tailscaled.models.TaildropFile
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Received files and the default Taildrop folder (Settings → Sharing & access → Storage).
 *
 * With a folder chosen, a received file does not wait in the app: [moveToFolder] copies it
 * there under a free name, removes it from the daemon's directory and notes in the history
 * where it went — on arrival (TaildropEvents), and from the inbox's Save for a file that
 * came in before the folder was chosen or could not be saved then. Without a folder the
 * file stays in the app's storage until the user saves or deletes it.
 *
 * The folder is a SAF tree the user granted once, and files go into its top level. The
 * grant can be revoked and the folder deleted behind the app's back, so every save checks,
 * and a failed one leaves the file in the inbox exactly as it was. Blocking I/O throughout.
 */
object TaildropSave {
    private const val TAG = "TaildropSave"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    sealed interface Result {
        /** No default folder: the file stays in the inbox. */
        data object NoFolder : Result
        /** The copy, a document of the folder's tree; the folder in the user's words and the copy's name. */
        data class Saved(val uri: Uri, val folder: String, val name: String) : Result
        /** The file stayed in the inbox; [reason], in English, is for the log and the history. */
        data class Failed(val folder: String, val reason: String) : Result
    }

    /**
     * One move at a time — the receive thread and the inbox's Save alike — so two files
     * cannot both pick the same free name.
     */
    private val lock = Any()

    /** Moves a received file into the default folder; see [Result]. */
    fun moveToFolder(context: Context, file: TaildropFile): Result = synchronized(lock) {
        val tree = GlobalSettings.getTaildropRootUri(context) ?: return Result.NoFolder
        val folder = folderLabel(context, tree)
        val copy = try {
            if (!holdsGrant(context, tree)) throw IOException("no access to the folder any more, choose it again in Settings")
            copyInto(context, tree, File(file.Path), file.Name)
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            log("WARN", "Taildrop: ${file.Name} not saved to $folder ($reason), it stays in the inbox")
            TaildropHistory.markSaveFailed(context, file, reason)
            return Result.Failed(folder, reason)
        }
        val name = displayName(context, copy) ?: file.Name
        // The same unlink the inbox's Delete makes through the bridge (os.Remove, in this
        // process). In Root Mode the file is root's, but the directory is the app's
        // (TaildropPaths.ensureDir), and the directory is what unlinking needs. Should it
        // fail anyway, the file stays listed in the inbox, its entry already marked saved.
        val original = File(file.Path)
        if (!original.delete() && original.exists()) {
            log("WARN", "Taildrop: ${file.Name} saved to $folder, but the original could not be removed")
        }
        TaildropHistory.markSaved(context, file, "$folder/$name", copy.toString())
        log("INFO", "Taildrop: ${file.Name} saved to $folder" + if (name != file.Name) " as $name" else "")
        return Result.Saved(copy, folder, name)
    }

    /** Whether the app still holds the write grant on [tree] it took when the folder was chosen. */
    fun holdsGrant(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /**
     * The folder as the user knows it: "Download/Taildrop" on the device's storage, else
     * the name its provider gives it. Asks the provider; see [quickFolderLabel].
     */
    fun folderLabel(context: Context, tree: Uri): String =
        quickFolderLabel(tree)
            ?: runCatching { displayName(context, folderDocument(tree)) }.getOrNull()
            ?: tree.lastPathSegment
            ?: tree.toString()

    /**
     * [folderLabel] from the URI alone, for the device's own storage, whose document ids are
     * paths ("primary:Download/Taildrop"). Null for any other provider and for a storage
     * volume's root, whose name only the provider knows.
     */
    fun quickFolderLabel(tree: Uri): String? {
        if (tree.authority != EXTERNAL_STORAGE) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        return id.substringAfter(':', "").trim('/').takeIf { it.isNotEmpty() }
    }

    /** VIEW of a saved copy, passing on the read grant the app holds through the folder. */
    fun viewIntent(saved: Uri, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(saved, mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * VIEW of the folder a saved copy sits in, for the system's file manager (DocumentsUI
     * takes a directory document); null when [saved] is not a document of a tree.
     */
    fun folderIntent(saved: Uri): Intent? = runCatching {
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(folderDocument(saved), DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull()

    /** The top folder of the tree [uri] belongs to — the default folder itself, for a saved copy. */
    private fun folderDocument(uri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))

    /** Copies [source] into the top folder of [tree] as [name], or as "name (1).ext" and on when that is taken. */
    private fun copyInto(context: Context, tree: Uri, source: File, name: String): Uri {
        if (!source.isFile) throw IOException("the received file is gone")
        val resolver = context.contentResolver
        val folderId = DocumentsContract.getTreeDocumentId(tree)
        val target = freeName(name, childNames(resolver, tree, folderId))
        // The real type, not "*/*": the device's storage provider fits the name to the type
        // and would otherwise keep ".jpg" as part of the base name ("a.jpg (1)").
        val mime = mimeTypeOf(target) ?: "application/octet-stream"
        val copy = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), mime, target)
            ?: throw IOException("the folder refused a new file")
        try {
            val out = resolver.openOutputStream(copy, "w") ?: throw IOException("the new file cannot be written")
            val copied = source.inputStream().use { input -> out.use { input.copyTo(it) } }
            if (copied != source.length()) throw IOException("copied $copied of ${source.length()} bytes")
        } catch (e: Exception) {
            // Half a file under the right name is worse than none.
            runCatching { DocumentsContract.deleteDocument(resolver, copy) }
            throw e
        }
        return copy
    }

    /** Names in the folder; the folder being gone or unreadable is an error, not an empty folder. */
    private fun childNames(resolver: ContentResolver, tree: Uri, folderId: String): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val names = HashSet<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let(names::add)
        } ?: throw IOException("the folder cannot be read")
        return names
    }

    /**
     * [name], or "name (1).ext", "name (2).ext"… — the first not in [taken]. Compared without
     * case: shared storage and memory cards do not tell "A.jpg" from "a.jpg".
     */
    internal fun freeName(name: String, taken: Set<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (name.lowercase(Locale.ROOT) !in used) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
            n++
        }
    }

    private fun displayName(context: Context, doc: Uri): String? = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** To the app's log (Logs → CORE) and logcat. */
    private fun log(level: String, message: String) {
        if (level == "INFO") Log.i(TAG, message) else Log.w(TAG, message)
        runCatching { Appctr.logAndroid(level, "CORE", message) }
    }
}
