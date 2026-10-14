package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Where files downloaded from Taildrive shares go: Settings → Sharing & access → Storage, "Taildrive
 * downloads", three choices ([DownloadMode]).
 *
 * - **Same as Taildrop**, the default, for every install that never chose: the Taildrop folder as
 *   it is at the moment of the download ([TaildropFolder.get]), followed when it changes, written
 *   the way received files are — a SAF tree through its grant, a directory with java.io.File. A
 *   phone that keeps received files in the app has no Taildrop folder: then the one a television
 *   starts with, Download/Taildrop ([TaildropFolder.defaultDir]), where from Android 11 an app may
 *   create its own files with no grant at all. No picker, nothing to allow — and the system's
 *   picker refuses Download itself and the storage root, which is where people go. Nothing of
 *   Taildrop's sees the download: its inbox and its notifications come from the daemon's own
 *   transfers and its history, which a download never touches.
 * - **Ask every time**: a folder for each download.
 * - **A folder chosen once**: the same two kinds of folder as Taildrop's ([ReceiveFolder]) — a SAF
 *   tree granted through the system's picker, or a directory chosen in the app's own browser where
 *   the system has none.
 *
 * Its own keys, apart from Taildrop's, none of them exported with the settings: they name places on
 * this device.
 */
object TaildriveDownloadFolder {
    private const val KEY_MODE = "taildrive_download_mode"
    private const val KEY_TREE = "taildrive_download_tree"
    private const val KEY_PATH = "taildrive_download_path"

    /** The stored strings as a chosen folder; null when neither is stored. Pure. */
    fun resolve(tree: String?, path: String?): ReceiveFolder? = when {
        !tree.isNullOrEmpty() -> ReceiveFolder.Tree(tree)
        !path.isNullOrEmpty() -> ReceiveFolder.Dir(FileBrowse.clean(File(path)))
        else -> null
    }

    /** The folder chosen for [DownloadMode.FOLDER], whatever the mode is now; null when none was. */
    fun chosen(context: Context): ReceiveFolder? =
        resolve(GlobalSettings.getString(context, KEY_TREE, ""), GlobalSettings.getString(context, KEY_PATH, ""))

    /** The setting; [DownloadMode.SAME_AS_TAILDROP] for an install that never chose ([DownloadMode.of]). */
    fun mode(context: Context): DownloadMode =
        DownloadMode.of(GlobalSettings.getString(context, KEY_MODE, ""), chosen(context) != null)

    /** [DownloadMode.SAME_AS_TAILDROP] or [DownloadMode.ASK]; a chosen folder is kept for when it is chosen again. */
    fun setMode(context: Context, mode: DownloadMode) {
        GlobalSettings.setString(context, KEY_MODE, mode.id)
    }

    /** Makes [folder] the download folder: [DownloadMode.FOLDER], with that folder. */
    fun set(context: Context, folder: ReceiveFolder) {
        when (folder) {
            is ReceiveFolder.Tree -> {
                persistGrant(context, folder.treeUri)
                GlobalSettings.setString(context, KEY_PATH, "")
                GlobalSettings.setString(context, KEY_TREE, folder.uri)
            }
            is ReceiveFolder.Dir -> {
                GlobalSettings.setString(context, KEY_TREE, "")
                GlobalSettings.setString(context, KEY_PATH, FileBrowse.clean(folder.dir).path)
            }
        }
        GlobalSettings.setString(context, KEY_MODE, DownloadMode.FOLDER.id)
    }

    /** The folder [mode] writes to without asking ([DownloadPlace]): Taildrop's now, the chosen one, or none. */
    fun target(context: Context, mode: DownloadMode = mode(context)): ReceiveFolder? = when (mode) {
        DownloadMode.SAME_AS_TAILDROP -> sameAsTaildrop(TaildropFolder.get(context))
        else -> DownloadPlace.of(mode, null, null, chosen(context))
    }

    /** Where "same as Taildrop" writes while Taildrop's folder is [taildrop]: it, or the one a television starts with. */
    fun sameAsTaildrop(taildrop: ReceiveFolder?): ReceiveFolder? =
        DownloadPlace.of(DownloadMode.SAME_AS_TAILDROP, taildrop, taildropDefault(), null)

    /** Download/Taildrop; null where the platform names no storage — the preview renderer has none to name. */
    private fun taildropDefault(): ReceiveFolder? =
        runCatching { TaildropFolder.defaultDir() }.getOrNull()?.let { ReceiveFolder.Dir(FileBrowse.clean(it), isDefault = true) }

    /**
     * Takes the system picker's grant on [tree] for good: a download outlives the screen that
     * asked for it, and the folder may become the default at its end.
     */
    fun persistGrant(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /**
     * Where the next download goes. Blocking: a directory is tried by making a file in it — before
     * Android 11 Download/Taildrop needs the Storage permission, and without it the download asks
     * instead (that picker still takes Download itself there); a SAF folder by its grant.
     */
    fun destination(context: Context): DownloadDestination {
        val mode = mode(context)
        val folder = target(context, mode)
        val writable = when (folder) {
            null -> false
            is ReceiveFolder.Tree -> TaildropSave.holdsGrant(context, folder.treeUri)
            is ReceiveFolder.Dir -> TaildropFolder.canCreateIn(folder.dir)
        }
        return DownloadDestination.of(mode, folder, writable)
    }

    /**
     * VIEW of [folder] for the system's file manager, which takes a directory document: a SAF
     * tree's own folder, or a directory on the device's storage by the document its provider names
     * it with. Null where nothing on the device would open it — a television has no file manager.
     */
    fun viewIntent(context: Context, folder: ReceiveFolder): Intent? {
        val document = when (folder) {
            is ReceiveFolder.Tree -> runCatching {
                DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, DocumentsContract.getTreeDocumentId(folder.treeUri))
            }.getOrNull()
            is ReceiveFolder.Dir -> storageDocumentId(folder.dir)?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, it) }
        } ?: return null
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent.takeIf { context.canLaunch(it) }
    }

    /** "primary:Download/Movies" for /storage/emulated/0/Download/Movies, as the device's storage provider names it. Pure. */
    fun storageDocumentId(dir: File): String? {
        val seg = FileBrowse.segments(dir)
        return when {
            seg.size >= 3 && seg[0] == "storage" && seg[1] == "emulated" && seg[2] == "0" -> "primary:" + seg.drop(3).joinToString("/")
            seg.size >= 1 && seg[0] == "sdcard" -> "primary:" + seg.drop(1).joinToString("/")
            seg.size >= 2 && seg[0] == "storage" && seg[1] != "emulated" && seg[1] != "self" -> seg[1] + ":" + seg.drop(2).joinToString("/")
            else -> null
        }
    }

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
}
