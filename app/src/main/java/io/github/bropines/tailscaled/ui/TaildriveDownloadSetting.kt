package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DownloadMode
import io.github.bropines.tailscaled.core.DownloadSlots
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.ReceiveFolder
import io.github.bropines.tailscaled.core.TaildriveDownloadFolder
import io.github.bropines.tailscaled.core.TaildriveDownloads
import io.github.bropines.tailscaled.core.TaildropFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Sharing & access → Storage: where Taildrive downloads go (core/TaildriveDownloadFolder.kt).
 * Three choices in a dialog: the Taildrop folder, the default, which needs no picker and nothing
 * to allow; ask every time; or a folder chosen once. Choosing the last opens the picker, and only a
 * folder actually picked changes the setting. [taildropFolder] is Taildrop's own setting, as the
 * page holds it.
 */
@Composable
internal fun TaildriveDownloadFolderRow(taildropFolder: ReceiveFolder?) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(TaildriveDownloadFolder.mode(context)) }
    var chosen by remember { mutableStateOf(TaildriveDownloadFolder.chosen(context)) }
    // Taildrop's folder as the row above has it now: changed there, it changes here.
    val taildrop = remember(taildropFolder) { TaildriveDownloadFolder.sameAsTaildrop(taildropFolder) }
    // Both folders by the names the user knows them, as the Taildrop row above names its own.
    val chosenLabel by produceState(chosen?.let(TaildropFolder::quickLabel), chosen) {
        value = chosen?.let { TaildropFolder.quickLabel(it) ?: withContext(Dispatchers.IO) { TaildropFolder.label(context, it) } }
    }
    val taildropLabel by produceState(taildrop?.let(TaildropFolder::quickLabel), taildrop) {
        value = taildrop?.let { TaildropFolder.quickLabel(it) ?: withContext(Dispatchers.IO) { TaildropFolder.label(context, it) } }
    }
    var asking by remember { mutableStateOf(false) }
    val picker = rememberDownloadFolderAsk()
    val pick = {
        picker.ask { picked ->
            TaildriveDownloadFolder.set(context, picked)
            chosen = picked
            mode = DownloadMode.FOLDER
        }
    }
    SettingsClickableItem(
        stringResource(R.string.taildrive_download_folder_title),
        when (mode) {
            DownloadMode.SAME_AS_TAILDROP -> stringResource(R.string.taildrive_download_folder_taildrop_format, taildropLabel ?: "…")
            DownloadMode.ASK -> stringResource(R.string.taildrive_download_folder_ask)
            DownloadMode.FOLDER -> stringResource(R.string.taildrive_download_folder_set_format, chosenLabel ?: "…")
        },
        Icons.Default.Download
    ) { asking = true }
    if (asking) {
        // A dialog is a window of its own: the words are resolved here, in the app's language.
        val title = stringResource(R.string.taildrive_download_folder_title)
        val ask = stringResource(R.string.taildrive_download_folder_reset)
        val choose = stringResource(R.string.taildrive_download_mode_choose)
        val same = stringResource(R.string.taildrive_download_mode_taildrop)
        val askNote = stringResource(R.string.taildrive_download_mode_ask_note)
        val chooseNote = chosenLabel?.takeIf { mode == DownloadMode.FOLDER } ?: stringResource(R.string.taildrive_download_mode_choose_note)
        val pickerHelp = stringResource(R.string.taildrive_download_mode_picker_help)
        val cancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeRow(same, taildropLabel ?: "…", mode == DownloadMode.SAME_AS_TAILDROP) {
                        asking = false
                        TaildriveDownloadFolder.setMode(context, DownloadMode.SAME_AS_TAILDROP)
                        mode = DownloadMode.SAME_AS_TAILDROP
                    }
                    ModeRow(ask, askNote, mode == DownloadMode.ASK) {
                        asking = false
                        TaildriveDownloadFolder.setMode(context, DownloadMode.ASK)
                        mode = DownloadMode.ASK
                    }
                    ModeRow(choose, chooseNote, mode == DownloadMode.FOLDER) {
                        asking = false
                        pick()
                    }
                    // What the system's picker refuses, said once under the two choices that open it.
                    HelpText(pickerHelp, Modifier.padding(start = 48.dp, top = 4.dp))
                }
            },
            confirmButton = { TextButton(onClick = { asking = false }) { Text(cancel) } }
        )
    }
}

/**
 * Settings → Sharing & access → Storage, under the folder: how many files a download reads at once
 * ([DownloadSlots], core/TaildriveDownloadQueue.kt), across every download queued. A choice of four
 * in a sheet; a change frees or takes slots as soon as a file ends, and a raise starts files at once.
 */
@Composable
internal fun TaildriveParallelRow() {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(GlobalSettings.getTaildriveParallelDownloads(context)) }
    var picking by remember { mutableStateOf(false) }
    SettingsClickableItem(
        stringResource(R.string.taildrive_parallel_title),
        pluralStringResource(R.plurals.taildrive_parallel_desc, count, count),
        Icons.Default.Layers
    ) { picking = true }
    if (picking) {
        // Resolved here: the sheet is a window of its own and would look its words up in the
        // system language — see wrapContextWithLocale().
        val notes = mapOf(
            1 to stringResource(R.string.taildrive_parallel_one),
            2 to stringResource(R.string.taildrive_parallel_two),
            3 to stringResource(R.string.taildrive_parallel_three),
            4 to stringResource(R.string.taildrive_parallel_four),
        )
        PickerSheet(
            title = stringResource(R.string.taildrive_parallel_title),
            options = DownloadSlots.CHOICES.map { PickerOption(it, it.toString(), supporting = notes[it]) },
            selected = count,
            onPick = { picked ->
                if (picked != count) {
                    count = picked
                    GlobalSettings.setTaildriveParallelDownloads(context, picked)
                    TaildriveDownloads.slotsChanged()
                }
            },
            onDismiss = { picking = false }
        )
    }
}

/** One choice of the dialog: the remote starts on the one in force. */
@Composable
private fun ModeRow(title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.tvInitialFocus() else Modifier)
            .tvFocusRing(source, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
