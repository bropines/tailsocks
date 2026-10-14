package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Where files downloaded from Taildrive shares go: Settings → Sharing & access → Storage, "Taildrive
 * downloads". Nothing stored is "ask every time", the default: each download asks for a folder,
 * and the one picked then can be kept from the end of that download.
 *
 * The same two kinds of folder as Taildrop's ([ReceiveFolder]): a SAF tree granted through the
 * system's picker, or a directory chosen in the app's own browser where the system has none. Its
 * own two keys, apart from Taildrop's, and neither exported with the settings: both name a place
 * on this device.
 */
object TaildriveDownloadFolder {
    private const val KEY_TREE = "taildrive_download_tree"
    private const val KEY_PATH = "taildrive_download_path"

    /** The stored strings as a folder; null — ask every time — when neither is stored. Pure. */
    fun resolve(tree: String?, path: String?): ReceiveFolder? = when {
        !tree.isNullOrEmpty() -> ReceiveFolder.Tree(tree)
        !path.isNullOrEmpty() -> ReceiveFolder.Dir(FileBrowse.clean(File(path)))
        else -> null
    }

    /** The download folder, or null: ask every time. */
    fun get(context: Context): ReceiveFolder? =
        resolve(GlobalSettings.getString(context, KEY_TREE, ""), GlobalSettings.getString(context, KEY_PATH, ""))

    /** Makes [folder] the download folder; the user's last choice is the one stored. */
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
    }

    /** Back to "ask every time". The grant on a tree is kept: Taildrop may use the same folder. */
    fun reset(context: Context) {
        GlobalSettings.setString(context, KEY_TREE, "")
        GlobalSettings.setString(context, KEY_PATH, "")
    }

    /**
     * Takes the system picker's grant on [tree] for good: a download outlives the screen that
     * asked for it, and the folder may become the default at its end.
     */
    fun persistGrant(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** Where the next download goes: the default while it can be written, the person asked otherwise. Blocking. */
    fun destination(context: Context): DownloadDestination {
        val folder = get(context)
        return DownloadDestination.of(folder, folder != null && TaildropFolder.state(context, folder) == TaildropFolder.State.READY)
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
