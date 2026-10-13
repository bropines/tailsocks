package io.github.bropines.tailscaled.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TaildropInbox
import io.github.bropines.tailscaled.core.TaildropSave
import io.github.bropines.tailscaled.core.canLaunch
import io.github.bropines.tailscaled.core.tryStartActivity
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry

/*
 * Received files handed to other apps: a viewer for a file, the file manager for the folder
 * it is in. Each says what happened when nothing took it — a television has a viewer for
 * few types and a file manager for none.
 */

/**
 * Hands [view] — a VIEW with its URI, type and read grant — to a viewer. A phone or a
 * tablet gets the system's chooser, as it always has. A television ([tv]) is asked first
 * whether anything real takes the type: its chooser would list a placeholder that answers
 * "you don't have an app that can do this", or nothing, and the app can say so itself, by
 * the type of [name].
 */
private fun openInViewer(context: Context, view: Intent, name: String, tv: Boolean) {
    val started = (!tv || context.canLaunch(view)) &&
        context.tryStartActivity(Intent.createChooser(view, context.getString(R.string.files_open_file_chooser)))
    if (!started) {
        val type = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }
        val text = if (type != null) context.getString(R.string.taildrop_no_viewer_format, ".$type") else context.getString(R.string.taildrop_no_viewer)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

private fun cantOpen(context: Context, e: Exception) =
    Toast.makeText(context, context.getString(R.string.files_error_cant_open, e.message), Toast.LENGTH_SHORT).show()

/** A file waiting in the inbox, in a viewer. */
internal fun openWaitingTaildropFile(context: Context, file: TaildropFile, tv: Boolean) {
    try {
        openInViewer(context, TaildropInbox.viewIntent(context, file), file.Name, tv)
    } catch (e: Exception) {
        cantOpen(context, e)
    }
}

/** A received file the default folder took, in a viewer — the app's grant on the folder passed on. */
internal fun openSavedTaildropFile(context: Context, entry: TaildropHistoryEntry, tv: Boolean) {
    val name = entry.savedTo?.substringAfterLast('/') ?: entry.name
    try {
        // A copy in a directory can be checked for, which beats a viewer opening on nothing.
        val gone = entry.savedUri?.let { TaildropSave.pathOf(Uri.parse(it)) }?.takeIf { !it.exists() }
        if (gone != null) {
            val folder = entry.savedTo?.substringBeforeLast('/', "")?.ifEmpty { null } ?: gone.parent.orEmpty()
            Toast.makeText(context, context.getString(R.string.taildrop_saved_gone_format, folder), Toast.LENGTH_LONG).show()
            return
        }
        val view = TaildropInbox.viewIntent(context, entry) ?: return
        openInViewer(context, view, name, tv)
    } catch (e: Exception) {
        cantOpen(context, e)
    }
}

/**
 * The folder a saved file is in, in the system's file manager. Where nothing takes a folder,
 * the toast at least says where it is.
 */
internal fun showSavedTaildropFolder(context: Context, entry: TaildropHistoryEntry) {
    val intent = entry.savedUri?.let { TaildropSave.folderIntent(Uri.parse(it)) }
    if (intent == null || !runCatching { context.tryStartActivity(intent) }.getOrDefault(false)) {
        val folder = entry.savedTo?.substringBeforeLast('/', "")?.ifEmpty { null } ?: entry.savedTo.orEmpty()
        Toast.makeText(context, context.getString(R.string.taildrop_folder_unavailable_format, folder), Toast.LENGTH_LONG).show()
    }
}

/**
 * Copies the path of the folder a saved file sits in, to paste into a file manager that
 * cannot be handed a folder; the label shown on the card when the path is not known.
 */
internal fun copySavedTaildropFolderPath(context: Context, entry: TaildropHistoryEntry) {
    val path = entry.savedUri?.let { TaildropSave.folderPath(Uri.parse(it)) }
        ?: entry.savedTo?.substringBeforeLast('/', "")?.ifEmpty { null }
        ?: return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(path, path))
    Toast.makeText(context, context.getString(R.string.taildrop_folder_path_copied_format, path), Toast.LENGTH_SHORT).show()
}
