package io.github.bropines.tailscaled.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AskReason
import io.github.bropines.tailscaled.core.DownloadFailReason
import io.github.bropines.tailscaled.core.DownloadStage
import io.github.bropines.tailscaled.core.DownloadState
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.PickerKind
import io.github.bropines.tailscaled.core.RealFileSource
import io.github.bropines.tailscaled.core.ReceiveFolder
import io.github.bropines.tailscaled.core.TaildriveDownloadFolder
import io.github.bropines.tailscaled.core.TaildropFolder
import io.github.bropines.tailscaled.core.WalkTotals
import io.github.bropines.tailscaled.core.choosePicker
import io.github.bropines.tailscaled.core.formatFileSize
import io.github.bropines.tailscaled.core.tryLaunch

/*
 * The share browser's downloads, the parts a page draws: the folder a download goes to when it is
 * asked for, the question a selection with folders asks, and the panel that follows a download to
 * its end. The rules are core/TaildriveDownloadPlan.kt, the running core/TaildriveDownloads.kt.
 */

// ---------------------------------------------------------------------------------------------
// Where it goes
// ---------------------------------------------------------------------------------------------

/** Asks where a download goes; see [rememberDownloadFolderAsk]. */
@Stable
class DownloadFolderAsk internal constructor(private val onAsk: ((ReceiveFolder) -> Unit) -> Unit) {
    /** Opens the picker; [then] gets the folder, and is not called at all when nothing was chosen. */
    fun ask(then: (ReceiveFolder) -> Unit) = onAsk(then)
}

/**
 * The folder a download goes to, asked for now: the system's folder picker, whose grant is kept
 * for good — the download outlives the screen, and the folder may become the default — or, where
 * the system has none (a television), the app's own browser, writable folders only. The answer to
 * a request is not kept across a recreation: nobody would remember asking.
 */
@Composable
fun rememberDownloadFolderAsk(): DownloadFolderAsk {
    val context = LocalContext.current
    val offered = Feature.SYSTEM_FILE_PICKER.offered()
    var pending by remember { mutableStateOf<((ReceiveFolder) -> Unit)?>(null) }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val then = pending
        pending = null
        if (uri != null && then != null) {
            TaildriveDownloadFolder.persistGrant(context, uri)
            then(ReceiveFolder.Tree(uri.toString()))
        }
    }
    var browsing by remember { mutableStateOf(false) }
    if (browsing) {
        val source = remember { RealFileSource(context) }
        // Where downloads went last time they were not asked for, or Downloads, which the app may write with no grant.
        val start = remember { (TaildriveDownloadFolder.chosen(context) as? ReceiveFolder.Dir)?.dir ?: TaildropFolder.defaultDir()?.parentFile }
        val cancel = { browsing = false; pending = null }
        PickerWindow(onDismiss = cancel) {
            PickFolderScreen(
                source = source,
                onPicked = { dir ->
                    val then = pending
                    browsing = false
                    pending = null
                    then?.invoke(ReceiveFolder.Dir(dir))
                },
                onCancel = cancel,
                start = start,
                writable = true,
                title = stringResource(R.string.taildrive_download_pick_title),
                confirmLabel = stringResource(R.string.taildrive_download_pick_confirm),
            )
        }
    }
    return remember(offered, system) {
        DownloadFolderAsk { then ->
            pending = then
            if (choosePicker(offered) { system.tryLaunch(null) } == PickerKind.IN_APP) browsing = true
        }
    }
}

/** Why the picker opens instead of the folder of Settings, when that is not simply the setting. */
internal fun sayWhyAsked(context: android.content.Context, reason: AskReason) {
    val text = when (reason) {
        AskReason.SETTING -> return
        AskReason.FOLDER_GONE -> R.string.taildrive_download_folder_lost
        AskReason.TAILDROP_UNWRITABLE -> R.string.taildrive_download_taildrop_unwritable
    }
    Toast.makeText(context, context.getString(text), Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------------------------------------
// The question
// ---------------------------------------------------------------------------------------------

/**
 * "They download with everything inside: 3 folders — 240 files, 3.2 GB." The numbers are the
 * walk's so far; "counting…" until it has found a file, and a running count after that.
 */
@Composable
internal fun folderQuestionBody(folders: Int, totals: WalkTotals): String {
    val what = pluralStringResource(R.plurals.taildrive_download_folders, folders, folders)
    val found = stringResource(
        R.string.taildrive_download_files_size,
        pluralStringResource(R.plurals.taildrive_download_files, totals.files, totals.files),
        formatFileSize(totals.bytes)
    )
    return when {
        totals.done -> stringResource(R.string.taildrive_download_folders_body, what, found)
        totals.files == 0 -> stringResource(R.string.taildrive_download_folders_body_counting, what, stringResource(R.string.taildrive_download_counting))
        else -> stringResource(R.string.taildrive_download_folders_body_counting, what, stringResource(R.string.taildrive_download_counting_so_far, found))
    }
}

/**
 * Asked before a download that holds folders: everything in them comes too. Every answer works at
 * once, the count or no count; [onFilesOnly] is null where the selection has no files.
 */
@Composable
internal fun FolderQuestionDialog(
    folders: Int,
    totals: WalkTotals,
    onAll: () -> Unit,
    onFilesOnly: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    // A dialog is a window of its own: the words are resolved here, in the app's language.
    val title = stringResource(R.string.taildrive_download_folders_title)
    val body = folderQuestionBody(folders, totals)
    val all = stringResource(R.string.taildrive_download_all)
    val filesOnly = stringResource(R.string.taildrive_download_files_only)
    val cancel = stringResource(R.string.action_cancel)
    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Row {
                if (onFilesOnly != null) TextButton(onClick = onFilesOnly) { Text(filesOnly) }
                TextButton(onClick = onAll) { Text(all) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancel) } }
    )
}

// ---------------------------------------------------------------------------------------------
// The panel
// ---------------------------------------------------------------------------------------------

/** What the panel's buttons do; the previews pass nothing. */
internal class DownloadPanelActions(
    val onCancel: (Int) -> Unit = {},
    val onClose: (Int) -> Unit = {},
    /** Null where nothing on the device opens a folder. */
    val onOpenFolder: ((DownloadState) -> Unit)? = null,
    val onKeep: (DownloadState) -> Unit = {},
)

@Composable
internal fun failureReason(reason: DownloadFailReason): String = stringResource(
    when (reason) {
        DownloadFailReason.NOT_FOUND -> R.string.taildrive_download_reason_not_found
        DownloadFailReason.DENIED -> R.string.taildrive_download_reason_denied
        DownloadFailReason.TIMEOUT -> R.string.taildrive_download_reason_timeout
        DownloadFailReason.NETWORK -> R.string.taildrive_download_reason_network
        DownloadFailReason.INCOMPLETE -> R.string.taildrive_download_reason_incomplete
        DownloadFailReason.WRITE -> R.string.taildrive_download_reason_write
        DownloadFailReason.UNLISTED -> R.string.taildrive_download_reason_unlisted
    }
)

/**
 * A download under the list: while it runs, the file it is on and the whole, each with its bar,
 * and Cancel; once over, what was saved where, what was not, and the way to the folder — and, for
 * a folder chosen for this download, the offer to keep it. [waiting] is how many more are queued.
 * [kept]: that offer was taken, or the folder is the default already.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadPanel(
    state: DownloadState,
    waiting: Int,
    kept: Boolean,
    actions: DownloadPanelActions,
    modifier: Modifier = Modifier,
    /** Something on the device opens the folder. */
    openable: Boolean = actions.onOpenFolder != null,
) {
    val colors = MaterialTheme.colorScheme
    // A button pressed here that goes away with what it did — Cancel, the offer to keep the folder —
    // hands the remote to Close, rather than let it fall to the first stop of the screen.
    var handOver by remember(state.id) { mutableStateOf(false) }
    val close = remember { FocusRequester() }
    if (!state.stage.active && handOver && !LocalInspectionMode.current) LaunchedEffect(state.stage, kept) {
        withFrameNanos { }
        runCatching { close.requestFocus() }
        handOver = false
    }
    val panelActions = DownloadPanelActions(
        onCancel = { handOver = true; actions.onCancel(it) },
        onClose = actions.onClose,
        onOpenFolder = actions.onOpenFolder.takeIf { openable },
        onKeep = { handOver = true; actions.onKeep(it) },
    )
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.stage.active) Running(state, waiting, panelActions) else Over(state, kept, panelActions, close)
        }
    }
}

@Composable
private fun Running(state: DownloadState, waiting: Int, actions: DownloadPanelActions) {
    val colors = MaterialTheme.colorScheme
    val single = state.files <= 1 && !state.counting
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                stringResource(R.string.taildrive_download_to, state.folderLabel),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    // One file: its name says more than "file 1 of 1".
                    single -> state.current ?: ""
                    state.files > 0 -> stringResource(
                        if (state.counting) R.string.taildrive_download_file_of_counting else R.string.taildrive_download_file_of,
                        state.currentNumber, state.files
                    )
                    else -> stringResource(R.string.taildrive_download_counting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = { actions.onCancel(state.id) }) { Text(stringResource(R.string.action_cancel)) }
    }
    // The whole: its bar, how much of it, how fast.
    Bar(state.fraction)
    Row {
        Text(
            if (state.counting || state.bytes <= 0) formatFileSize(state.bytesDone)
            else stringResource(R.string.taildrop_progress_of_format, formatFileSize(state.bytesDone), formatFileSize(state.bytes)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        state.bytesPerSecond?.let {
            Text(stringResource(R.string.taildrop_speed_format, formatFileSize(it)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    // The file it is on, and its own bar; for a single file the whole is the file.
    if (!single) state.current?.let { name ->
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Bar(state.currentFraction, secondary = true)
    }
    if (waiting > 0) {
        Text(pluralStringResource(R.plurals.taildrive_download_waiting, waiting, waiting), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** A determinate bar for a known fraction, a moving one otherwise. */
@Composable
private fun Bar(fraction: Float?, secondary: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val color = if (secondary) colors.tertiary else colors.primary
    val modifier = Modifier.fillMaxWidth()
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = modifier, color = color)
    else LinearProgressIndicator(modifier = modifier, color = color)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Over(state: DownloadState, kept: Boolean, actions: DownloadPanelActions, close: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    val good = state.stage == DownloadStage.DONE && state.saved > 0
    val title = when {
        state.stage == DownloadStage.CANCELLED -> stringResource(R.string.taildrive_download_cancelled)
        state.saved > 0 -> pluralStringResource(R.plurals.taildrive_download_saved, state.saved, state.saved, state.folderLabel)
        else -> stringResource(R.string.taildrive_download_nothing_saved)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (good) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (good) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    // A cancelled download keeps what it saved: say how much, and where.
    if (state.stage == DownloadStage.CANCELLED && state.saved > 0) {
        Text(
            pluralStringResource(R.plurals.taildrive_download_saved, state.saved, state.saved, state.folderLabel),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
    if (state.stage == DownloadStage.STOPPED) {
        Text(stringResource(R.string.taildrive_download_stopped), style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
    if (state.failures.isNotEmpty()) {
        val head = pluralStringResource(R.plurals.taildrive_download_missing, state.failures.size, state.failures.size)
        val lines = state.failures.map { stringResource(R.string.taildrive_download_failure, it.path, failureReason(it.reason)) }
        HelpText((listOf(head) + lines).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
    val offer = state.pickedNow && !kept && state.saved > 0
    if (offer) HelpText(stringResource(R.string.taildrive_download_keep_help), style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (offer) TextButton(onClick = { actions.onKeep(state) }) { Text(stringResource(R.string.taildrive_download_keep)) }
        if (state.saved > 0) actions.onOpenFolder?.let { open ->
            TextButton(onClick = { open(state) }) { Text(stringResource(R.string.taildrive_download_open_folder)) }
        }
        TextButton(onClick = { actions.onClose(state.id) }, modifier = Modifier.focusRequester(close)) { Text(stringResource(R.string.action_close)) }
    }
}

/** The panel's download: the one running, or else the last that is over and still on the page; and how many wait behind it. */
internal fun panelOf(downloads: List<DownloadState>): Pair<DownloadState, Int>? {
    val running = downloads.firstOrNull { it.stage == DownloadStage.RUNNING }
    val waiting = downloads.count { it.stage == DownloadStage.WAITING }
    val shown = running ?: downloads.lastOrNull { !it.stage.active } ?: downloads.firstOrNull { it.stage == DownloadStage.WAITING } ?: return null
    return shown to if (shown.stage == DownloadStage.WAITING) waiting - 1 else waiting
}

/** Keeps the folder of [state] for every download from now on; says so. */
internal fun keepFolder(context: android.content.Context, state: DownloadState) {
    TaildriveDownloadFolder.set(context, state.folder)
    Toast.makeText(context, context.getString(R.string.taildrive_download_kept, state.folderLabel), Toast.LENGTH_SHORT).show()
}

/** Whether downloads go to [state]'s folder without asking already. */
internal fun isDefaultFolder(context: android.content.Context, state: DownloadState): Boolean =
    TaildriveDownloadFolder.target(context) == state.folder
