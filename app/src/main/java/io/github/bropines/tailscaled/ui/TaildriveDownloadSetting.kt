package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TaildriveDownloadFolder
import io.github.bropines.tailscaled.core.TaildropFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Sharing & access → Storage: where Taildrive downloads go (core/TaildriveDownloadFolder.kt).
 * "Ask every time" until a folder is chosen; pressed then, the row opens the picker at once — there
 * is nothing else to do. With a folder, it asks first: another folder, or back to asking every time.
 */
@Composable
internal fun TaildriveDownloadFolderRow() {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(TaildriveDownloadFolder.get(context)) }
    val chosen = folder
    // The folder by the name the user knows it, as the Taildrop row above names its own.
    val label by produceState(chosen?.let(TaildropFolder::quickLabel), chosen) {
        value = chosen?.let { TaildropFolder.quickLabel(it) ?: withContext(Dispatchers.IO) { TaildropFolder.label(context, it) } }
    }
    var asking by remember { mutableStateOf(false) }
    val picker = rememberDownloadFolderAsk()
    val change = {
        picker.ask { picked ->
            TaildriveDownloadFolder.set(context, picked)
            folder = picked
        }
    }
    SettingsClickableItem(
        stringResource(R.string.taildrive_download_folder_title),
        if (chosen == null) stringResource(R.string.taildrive_download_folder_ask)
        else stringResource(R.string.taildrive_download_folder_set_format, label ?: "…"),
        Icons.Default.Download
    ) { if (chosen == null) change() else asking = true }
    if (asking && chosen != null) {
        // A dialog is a window of its own: the words are resolved here, in the app's language.
        val title = stringResource(R.string.taildrive_download_folder_title)
        val body = stringResource(R.string.taildrive_download_folder_dialog_body, label ?: "…")
        val changeLabel = stringResource(R.string.taildrive_download_folder_change)
        val reset = stringResource(R.string.taildrive_download_folder_reset)
        val cancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        asking = false
                        TaildriveDownloadFolder.reset(context)
                        folder = null
                    }) { Text(reset) }
                    TextButton(onClick = {
                        asking = false
                        change()
                    }) { Text(changeLabel) }
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(cancel) } }
        )
    }
}
