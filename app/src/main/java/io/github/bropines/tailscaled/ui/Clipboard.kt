package io.github.bropines.tailscaled.ui

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.CoroutineScope
import io.github.bropines.tailscaled.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.launch

/*
 * Clipboard helpers shared by every screen that copies or pastes text.
 */

/**
 * Puts [text] on the clipboard from a callback: LocalClipboard, which
 * replaced LocalClipboardManager, is a suspend API.
 */
fun Clipboard.copyText(scope: CoroutineScope, text: String) {
    scope.launch { setClipEntry(ClipEntry(ClipData.newPlainText("TailSocks", text))) }
}

/**
 * Puts [text] on the clipboard under [label] and says so with [confirmation] in a toast
 * (a long one when [longToast]): the copy buttons whose text is built off the screen's
 * composition — a diagnostics report, the whole log — and that confirm in words of their own.
 * Call it on the main thread. Whether a screen offers copying at all (a television has no
 * clipboard to speak of) is `Feature.CLIPBOARD_ACTIONS`, decided by the caller.
 *
 * A clip goes to the system through a binder transaction, and a large one (the Logs copy is
 * capped near that limit, `buildClipboardLogString`) can still be refused with an exception:
 * that is an error toast and false, never a crash.
 */
fun copyToClipboard(context: Context, label: String, text: String, confirmation: String, longToast: Boolean = false): Boolean =
    try {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, confirmation, if (longToast) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
        false
    }

/** The clipboard's text, or null when it holds none. */
suspend fun Clipboard.readText(context: Context): String? =
    getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
