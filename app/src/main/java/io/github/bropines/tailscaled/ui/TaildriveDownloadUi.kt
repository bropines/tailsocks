package io.github.bropines.tailscaled.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AskReason
import io.github.bropines.tailscaled.core.DownloadFailReason
import io.github.bropines.tailscaled.core.DownloadQueueSummary
import io.github.bropines.tailscaled.core.DownloadStage
import io.github.bropines.tailscaled.core.DownloadState
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.FileProgress
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
 * asked for, the question a selection with folders asks, and the panel that follows the queue of
 * downloads to their ends. The rules are core/TaildriveDownloadPlan.kt, the queue
 * core/TaildriveDownloadQueue.kt, the running on the device core/TaildriveDownloads.kt.
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
    val onClearFinished: () -> Unit = {},
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

/** Where the remote goes once the button it pressed has gone: a download's dismiss, or the panel's first stop. */
private sealed interface PanelAim {
    data class Close(val id: Int) : PanelAim

    data object Panel : PanelAim
}

/**
 * The downloads under the list, folded or not.
 *
 * The head speaks for the whole queue in one place: what is being written (a file's name, a
 * folder's, or how many files at once), one line of sizes, files, the queue and the speed, and one
 * bar. Once nothing runs it is the download that ended last: what was saved where, the way to the
 * folder, the offer to keep it.
 *
 * Unfolded: one row per download, each said once — a file as its name, percentage, bar and one line
 * of numbers; a folder the same with the files in flight under it as plain lines; a waiting one by
 * its place; a finished one by how it ended. Each has a × of its own; Cancel all and Clear finished
 * close the list. The destination is not repeated on every row: it is said where a download ends.
 *
 * [defaultFolder]: where downloads go without asking now, so a download into it has no offer.
 */
@Composable
internal fun DownloadPanel(
    downloads: List<DownloadState>,
    actions: DownloadPanelActions,
    modifier: Modifier = Modifier,
    defaultFolder: ReceiveFolder? = null,
    /** Something on the device opens the folder. */
    canOpen: (ReceiveFolder) -> Boolean = { actions.onOpenFolder != null },
    startExpanded: Boolean = false,
) {
    if (downloads.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val active = downloads.filter { it.stage.active }
    val finished = downloads.filter { !it.stage.active }
    val lastEnded = finished.maxByOrNull { it.ended }
    val summary = DownloadQueueSummary.of(downloads)
    // Something to unfold: another download, or the files of one that has several. Not the
    // number of files in flight, which comes and goes between two files.
    val expandable = downloads.size > 1 || active.any { it.stage == DownloadStage.RUNNING && isFolderLike(it) }
    var unfolded by rememberSaveable { mutableStateOf(startExpanded) }
    val expanded = unfolded && expandable

    // A button pressed here that goes away with what it did — a ×, the offer to keep the folder —
    // hands the remote on inside the panel, rather than let it fall to the first stop of the
    // screen: to the × of the download it ended, or to the panel's first stop.
    val toggleFocus = remember { FocusRequester() }
    val closeFocus = remember { HashMap<Int, FocusRequester>() }
    val closeOf = { id: Int -> closeFocus.getOrPut(id) { FocusRequester() } }
    var aim by remember { mutableStateOf<PanelAim?>(null) }
    val input = LocalInputModeManager.current
    val target = aim
    if (target != null && !LocalInspectionMode.current) LaunchedEffect(target, downloads, expanded) {
        // A cancelled download ends a moment later, and its × comes with that.
        if (target is PanelAim.Close && downloads.any { it.id == target.id && it.stage.active }) return@LaunchedEffect
        withFrameNanos { }
        if (input.inputMode == InputMode.Keyboard) {
            fun ask(requester: FocusRequester) = runCatching { requester.requestFocus() }.getOrDefault(false)
            val landed = target is PanelAim.Close && ask(closeOf(target.id))
            if (!landed && !(expandable && ask(toggleFocus))) lastEnded?.let { ask(closeOf(it.id)) }
        }
        aim = null
    }
    val cancel = { state: DownloadState ->
        aim = if (state.stage == DownloadStage.RUNNING) PanelAim.Close(state.id) else PanelAim.Panel
        actions.onCancel(state.id)
    }
    val dismiss = { id: Int ->
        aim = PanelAim.Panel
        actions.onClose(id)
    }
    val ending = DownloadPanelActions(
        onClose = dismiss,
        onOpenFolder = actions.onOpenFolder,
        onKeep = { aim = PanelAim.Close(it.id); actions.onKeep(it) },
    )
    val toggle: @Composable () -> Unit = {
        if (expandable) IconButton(onClick = { unfolded = !unfolded }, modifier = Modifier.focusRequester(toggleFocus)) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = stringResource(if (expanded) R.string.taildrive_queue_hide else R.string.taildrive_queue_show)
            )
        }
    }
    // The share list stays in sight above it, a television's too.
    val listHeight = rememberWindowLayout().height * 0.35f

    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                active.isNotEmpty() -> QueueHead(active, summary, toggle, onCancel = active.singleOrNull()?.let { one -> { cancel(one) } })
                finished.size == 1 && lastEnded != null -> Over(
                    lastEnded, lastEnded.folder == defaultFolder, canOpen, ending, closeOf(lastEnded.id), trailing = toggle
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Text(
                        pluralStringResource(R.plurals.taildrive_queue_finished, finished.size, finished.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    toggle()
                }
            }
            if (expanded) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                // The one download the head already speaks for shows only its files here.
                val lone = active.singleOrNull()?.takeIf { it.stage == DownloadStage.RUNNING && downloads.size == 1 }
                Column(
                    Modifier.heightIn(max = listHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (lone != null) {
                        InFlight(lone.writing)
                    } else {
                        var place = 0
                        for (state in downloads) key(state.id) {
                            when (state.stage) {
                                DownloadStage.RUNNING -> RunningRow(state) { cancel(state) }
                                DownloadStage.WAITING -> WaitingRow(state, ++place) { cancel(state) }
                                else -> EndedRow(state, closeOf(state.id)) { dismiss(state.id) }
                            }
                        }
                    }
                }
                if (active.size > 1 || finished.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (finished.isNotEmpty()) TextButton(onClick = { aim = PanelAim.Panel; actions.onClearFinished() }) {
                        Text(stringResource(R.string.taildrive_queue_clear))
                    }
                    if (active.size > 1) TextButton(onClick = { aim = PanelAim.Panel; active.forEach { actions.onCancel(it.id) } }) {
                        Text(stringResource(R.string.taildrive_queue_cancel_all))
                    }
                }
            }
        }
    }
}

/** A download of folders, or of several files: its files have rows of their own. */
private fun isFolderLike(state: DownloadState): Boolean = state.files > 1 || state.counting

/**
 * The queue in its head: what is being written — one file's name, one folder's, or how many files at
 * once — one line of numbers and one bar. [onCancel]: there is one download, and this is its ×.
 */
@Composable
private fun QueueHead(active: List<DownloadState>, summary: DownloadQueueSummary, toggle: @Composable () -> Unit, onCancel: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val one = active.singleOrNull()
    val inFlight = active.sumOf { it.writing.size }
    val title = when {
        one != null && !isFolderLike(one) -> one.writing.singleOrNull()?.name ?: selectionName(one)
        one != null -> selectionName(one)
        inFlight > 0 -> pluralStringResource(R.plurals.taildrive_queue_notify_files, inFlight, inFlight)
        else -> stringResource(R.string.taildrive_queue_downloading)
    }
    // Files are counted where a folder is being downloaded; for files that are each a download of
    // their own the title has said how many.
    val folders = active.any(::isFolderLike)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            NumbersLine(
                numbers(
                    summary.bytesDone, summary.bytes, summary.filesDone, if (folders) summary.files else 1, summary.counting && folders,
                    waiting = summary.waiting
                ),
                summary.bytesPerSecond
            )
        }
        if (onCancel != null) CancelButton(onCancel)
        toggle()
    }
    Bar(summary.fraction)
}

/** [text] and, on the right where it is never cut, the speed. */
@Composable
private fun NumbersLine(text: String, bytesPerSecond: Long?) {
    val colors = MaterialTheme.colorScheme
    Row {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        bytesPerSecond?.let {
            Text(
                " · " + stringResource(R.string.taildrop_speed_format, formatFileSize(it)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** "24 MB of 1.3 GB · 3 of 6 files · 2 in queue": the numbers there are, in one line; the speed goes beside it. */
@Composable
private fun numbers(
    bytesDone: Long,
    bytes: Long,
    filesDone: Int,
    files: Int,
    counting: Boolean,
    waiting: Int = 0,
): String {
    val sizes = if (counting || bytes <= 0) formatFileSize(bytesDone)
    else stringResource(R.string.taildrop_progress_of_format, formatFileSize(bytesDone), formatFileSize(bytes))
    // A single file says nothing more by "0 of 1 file".
    val count = if (files > 1 || counting) pluralStringResource(
        if (counting) R.plurals.taildrive_queue_files_done_counting else R.plurals.taildrive_queue_files_done, files, filesDone, files
    ) else null
    val queue = if (waiting > 0) pluralStringResource(R.plurals.taildrive_queue_in_queue, waiting, waiting) else null
    return listOfNotNull(sizes, count, queue).joinToString(" · ")
}

/** The files a download is writing, as plain lines: a name and how far it is. */
@Composable
private fun InFlight(files: List<FileProgress>) {
    if (files.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (file in files) Row {
            Text(
                file.name,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            file.fraction?.let {
                Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** "Season 2 and 3 more": a download by what was selected. */
@Composable
private fun selectionName(state: DownloadState): String =
    if (state.selected > 1) stringResource(R.string.taildrive_queue_and_more, state.what, state.selected - 1) else state.what

/**
 * A running download among others, said once: its name — the file's, or the folder's — and how far
 * it is, its ×, a bar and one line of numbers; a folder's files in flight under it.
 */
@Composable
private fun RunningRow(state: DownloadState, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val folder = isFolderLike(state)
    val name = if (folder) selectionName(state) else state.writing.singleOrNull()?.name ?: selectionName(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                state.fraction?.let {
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Bar(state.fraction)
            NumbersLine(numbers(state.bytesDone, state.bytes, state.filesDone, state.files, state.counting), state.bytesPerSecond)
            if (folder) InFlight(state.writing)
        }
        CancelButton(onCancel)
    }
}

/** A download that waits: its place in the queue, its name and its ×, which takes it off. */
@Composable
private fun WaitingRow(state: DownloadState, place: Int, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = colors.secondaryContainer, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$place", style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
            }
        }
        Text(
            selectionName(state),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
        CancelButton(onCancel)
    }
}

/** A download among others that is over: how it ended, in one line, and a × that puts it away. */
@Composable
private fun EndedRow(state: DownloadState, close: FocusRequester, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val good = state.stage == DownloadStage.DONE && state.saved > 0 && state.failures.isEmpty()
    val status = when {
        state.stage == DownloadStage.CANCELLED -> stringResource(R.string.taildrive_download_cancelled)
        state.stage == DownloadStage.STOPPED -> stringResource(R.string.taildrive_download_stopped)
        state.saved > 0 -> pluralStringResource(R.plurals.taildrive_download_saved, state.saved, state.saved, state.folderLabel)
        else -> stringResource(R.string.taildrive_download_nothing_saved)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    good -> Icons.Default.CheckCircle
                    state.stage == DownloadStage.CANCELLED -> Icons.Default.Block
                    else -> Icons.Default.ErrorOutline
                },
                contentDescription = null,
                tint = when {
                    good -> colors.primary
                    state.stage == DownloadStage.CANCELLED -> colors.onSurfaceVariant
                    else -> colors.error
                },
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(selectionName(state), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.focusRequester(close)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.taildrive_queue_dismiss_item))
            }
        }
        if (state.failures.isNotEmpty()) {
            val head = pluralStringResource(R.plurals.taildrive_download_missing, state.failures.size, state.failures.size)
            val lines = state.failures.map { stringResource(R.string.taildrive_download_failure, it.path, failureReason(it.reason)) }
            HelpText((listOf(head) + lines).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

/** A running or waiting download's ×. */
@Composable
private fun CancelButton(onCancel: () -> Unit) {
    IconButton(onClick = onCancel) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.taildrive_queue_cancel_item))
    }
}

/** A determinate bar for a known fraction, a moving one otherwise. */
@Composable
private fun Bar(fraction: Float?) {
    val colors = MaterialTheme.colorScheme
    val modifier = Modifier.fillMaxWidth()
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = modifier, color = colors.primary)
    else LinearProgressIndicator(modifier = modifier, color = colors.primary)
}

/**
 * A download that is over: what was saved where, what was not, the way to the folder and — for a
 * folder chosen for this download — the offer to keep it ([kept]: taken, or the folder is the
 * default already). [named]: among others, with what was selected under its title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Over(
    state: DownloadState,
    kept: Boolean,
    canOpen: (ReceiveFolder) -> Boolean,
    actions: DownloadPanelActions,
    close: FocusRequester,
    named: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val good = state.stage == DownloadStage.DONE && state.saved > 0
    val title = when {
        state.stage == DownloadStage.CANCELLED -> stringResource(R.string.taildrive_download_cancelled)
        state.saved > 0 -> pluralStringResource(R.plurals.taildrive_download_saved, state.saved, state.saved, state.folderLabel)
        else -> stringResource(R.string.taildrive_download_nothing_saved)
    }
    val openable = actions.onOpenFolder != null && remember(state.folder) { canOpen(state.folder) }
    val closeButton: @Composable () -> Unit = {
        TextButton(onClick = { actions.onClose(state.id) }, modifier = Modifier.focusRequester(close)) { Text(stringResource(R.string.action_close)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (good) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (good) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (named) Text(selectionName(state), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // In the list its Close stands where the others have their Cancel.
            if (named) closeButton()
            trailing()
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
        val open = actions.onOpenFolder?.takeIf { state.saved > 0 && openable }
        if (!named || offer || open != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (offer) TextButton(onClick = { actions.onKeep(state) }) { Text(stringResource(R.string.taildrive_download_keep)) }
            open?.let { TextButton(onClick = { it(state) }) { Text(stringResource(R.string.taildrive_download_open_folder)) } }
            if (!named) closeButton()
        }
    }
}

/** Keeps the folder of [state] for every download from now on; says so. */
internal fun keepFolder(context: android.content.Context, state: DownloadState) {
    TaildriveDownloadFolder.set(context, state.folder)
    Toast.makeText(context, context.getString(R.string.taildrive_download_kept, state.folderLabel), Toast.LENGTH_SHORT).show()
}
