package io.github.bropines.tailscaled.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.CompactSearchBar
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.IncomingPhase
import io.github.bropines.tailscaled.core.IncomingTransfer
import io.github.bropines.tailscaled.core.TaildropFolder
import io.github.bropines.tailscaled.core.TaildropInbox
import io.github.bropines.tailscaled.core.formatFileSize
import io.github.bropines.tailscaled.core.incomingDetail
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import io.github.bropines.tailscaled.models.TaildropRoute
import io.github.bropines.tailscaled.models.TaildropSource
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/*
 * The Taildrop page of the Files screen: one vertical list — the devices a file can go to,
 * the inbox, the history — so a sideways swipe always belongs to the TailDrive|TailDrop
 * pager above it. It used to be a pager of its own, which took the drag until it ran out
 * of pages and made the way back to TailDrive feel stuck.
 *
 * The devices come first: sending is what the page is opened for. The inbox is folded to
 * its heading with a count and opens on a tap; only what is arriving right now shows
 * without asking, above everything, for as long as it arrives. On a window with two panes
 * the inbox has a pane of its own and stays open there.
 */

/** Rows the Send section shows before "All devices". */
private const val SEND_ROWS = 6

/** Rows the History section shows before "All history". */
private const val HISTORY_ROWS = 5

/** A device the Send section offers, with the daemon's verdict (Available or Offline). */
@Immutable
internal data class TaildropTarget(val peer: PeerData, val status: TaildropStatus)

/**
 * The Send section's devices. [all] holds every device a file can go to, in the order the
 * section lists them; [refused] the ones the daemon will not send to — name and reason —
 * which are listed nowhere and only counted under the section.
 */
@Immutable
internal data class TaildropTargets(
    val all: List<TaildropTarget>,
    val refused: List<Pair<String, String>>
) {
    companion object {
        val EMPTY = TaildropTargets(emptyList(), emptyList())
    }
}

/**
 * Splits the picker's peers ([taildropPickerPeers]: no self, no sharee or Funnel nodes)
 * into the ones a file can go to and the refused ones, and orders the first: the devices
 * this one last sent a file to (by node ID where the entry has one, else by name), then
 * the online ones, then the rest, by name within each.
 */
internal fun taildropTargets(
    pickerPeers: List<PeerData>,
    strings: TaildropReasonStrings,
    history: List<TaildropHistoryEntry>
): TaildropTargets {
    val judged = pickerPeers.map { TaildropTarget(it, taildropStatusOf(it, strings)) }
    val sendable = judged.filter { it.status !is TaildropStatus.Blocked }
    val refused = judged.mapNotNull { t -> (t.status as? TaildropStatus.Blocked)?.let { t.peer.getDisplayName() to it.reason } }

    val recent = mutableListOf<TaildropTarget>()
    for (e in history) {
        if (recent.size >= SEND_ROWS) break
        // Arrived sends only: a device a file never reached is not one it was "sent to".
        if (e.direction != TaildropDirection.SENT || !e.ok) continue
        val hit = sendable.firstOrNull { t ->
            if (e.peerId != null) t.peer.id == e.peerId else t.peer.getDisplayName().equals(e.peerName, ignoreCase = true)
        } ?: continue
        if (recent.none { it === hit }) recent += hit
    }
    val rest = sendable.filter { t -> recent.none { it === t } }.sortedWith(
        compareByDescending<TaildropTarget> { it.peer.online == true }
            .thenBy { taildropPickerRank(it.status) }
            .thenBy { it.peer.getDisplayName().lowercase() }
    )
    return TaildropTargets(recent + rest, refused)
}

/**
 * The receive folder's line over the inbox, for a folder that is a directory chosen in the
 * app's own browser (a television's): where files go and the way to change that; when the
 * folder cannot be written, why, and — while it is the storage access that is missing —
 * the way to allow it. [grantNote] is what to do where the device has no page for that.
 */
@Immutable
internal class TaildropFolderLine(
    val label: String,
    val state: TaildropFolder.State,
    val onChange: () -> Unit,
    val onAllow: () -> Unit,
    val grantNote: String? = null
)

@Composable
internal fun TaildropPage(
    incoming: List<IncomingTransfer>,
    files: List<TaildropFile>,
    history: List<TaildropHistoryEntry>,
    targets: TaildropTargets,
    loaded: Boolean,
    onOpenFile: (TaildropFile) -> Unit,
    onSaveFile: (TaildropFile) -> Unit,
    onDeleteFile: (TaildropFile) -> Unit,
    onOpenSaved: (TaildropHistoryEntry) -> Unit,
    /** Null where the device has no file manager to show a folder in: the card has no such button. */
    onShowSavedInFolder: ((TaildropHistoryEntry) -> Unit)?,
    /** Null where the device has no clipboard a user can reach: the long press does nothing. */
    onCopySavedFolderPath: ((TaildropHistoryEntry) -> Unit)?,
    onHideSaved: (TaildropHistoryEntry) -> Unit,
    showFolderHint: Boolean,
    onChooseFolder: () -> Unit,
    onDismissFolderHint: () -> Unit,
    onSendTo: (PeerData) -> Unit,
    onAllDevices: () -> Unit,
    onAllHistory: () -> Unit,
    onEntry: (TaildropHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    window: WindowLayout = rememberWindowLayout(),
    openEntry: TaildropHistoryEntry? = null,
    onCloseEntry: () -> Unit = {},
    onDeleteEntry: (TaildropHistoryEntry) -> Unit = {},
    /** Whether the folded inbox starts open: a preview's way to show it; the app starts folded. */
    inboxOpenAtStart: Boolean = false,
    folderLine: TaildropFolderLine? = null,
    /**
     * Where the newest files are to stand out — a television, which has no notification to
     * say one came: what arrived after this moment is listed first under "New", and the
     * whole inbox by arrival, newest first. Null leaves the inbox as it always was.
     */
    newSince: Long? = null
) {
    val saved = remember(files, history) { TaildropInbox.savedEntries(files, history) }
    var inboxOpen by rememberSaveable { mutableStateOf(inboxOpenAtStart) }
    val inbox = InboxContent(
        incoming, files, history, saved, loaded, onOpenFile, onSaveFile, onDeleteFile,
        onOpenSaved, onShowSavedInFolder, onCopySavedFolderPath, onHideSaved,
        showFolderHint, onChooseFolder, onDismissFolderHint, folderLine, newSince,
        open = inboxOpen, onToggle = { inboxOpen = !inboxOpen }
    )
    when {
        // The inbox down one side, sending and the history on the other, both in view at once;
        // an entry of the history opens in that pane in place of them. See ListDetailLayout.
        window.listDetail -> ListDetailLayout(
            window = window,
            twoPane = true,
            // A television's window is expanded, never large, and it is read in any language.
            listWidth = if (window.widthClass >= WindowWidthClass.LARGE || window.tv) TaildropInboxPaneWidth else window.listPaneWidth,
            modifier = modifier.fillMaxSize(),
            list = {
                val paneState = rememberLazyListState()
                var remoteInPane by remember { mutableStateOf(false) }
                // A file that has just come heads the pane: the top is brought into view —
                // unless the remote is in the pane, which is not pulled from under it.
                val newest = inbox.cards.firstOrNull()?.takeIf { inbox.fresh > 0 }?.key
                LaunchedEffect(newest) { if (newest != null && !remoteInPane) paneState.animateScrollToItem(0) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().onFocusChanged { remoteInPane = it.hasFocus }.tvFocusGroup(),
                    state = paneState,
                    contentPadding = PaddingValues(start = window.margin, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { inboxPaneItems(inbox) }
            },
            detail = {
                // On a television the entry whose details were open: when its pane closes, the
                // remote is put back on the row it came from.
                var lastOpened by remember { mutableStateOf<TaildropHistoryEntry?>(null) }
                if (openEntry != null) {
                    if (window.tv) SideEffect { lastOpened = openEntry }
                    TaildropEntrySheet(openEntry, onDismiss = onCloseEntry, onDelete = { onDeleteEntry(openEntry) })
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .tvFocusGroup()
                            .verticalScroll(rememberScrollState())
                            // The bottom leaves the last row clear of the send button.
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp)
                    ) {
                        TaildropSendAndHistory(
                            targets, history, loaded, nowMillis, onSendTo, onAllDevices, onAllHistory, onEntry,
                            returnFocusTo = lastOpened, onFocusReturned = { lastOpened = null }
                        )
                    }
                }
            }
        )
        // A window wider than a phone but not wide enough for two panes: sending and the history
        // side by side, the inbox's cards under them in as many columns as fit once it is opened.
        window.multiColumn -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(320.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = window.margin, end = window.margin, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 8.dp
        ) {
            items(incoming, key = { "incoming:" + it.key }) { TaildropIncomingCard(it) }
            item(key = "send-history", span = StaggeredGridItemSpan.FullLine) {
                TaildropSendAndHistory(targets, history, loaded, nowMillis, onSendTo, onAllDevices, onAllHistory, onEntry)
            }
            inboxGridItems(inbox)
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            // The bottom leaves the last row clear of the send button.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Arriving now: on top while it arrives. Each makes way for its file once it is in.
            items(incoming, key = { "incoming:" + it.key }) { TaildropIncomingCard(it) }

            item(key = "send-head") { TaildropSectionHeading(stringResource(R.string.taildrop_section_send), null) }
            if (targets.all.isEmpty()) {
                if (loaded) item(key = "send-empty") { TaildropMutedLine(stringResource(R.string.taildrop_send_empty), Icons.Default.Devices) }
            } else {
                item(key = "send-rows") { TaildropSendGroup(targets, onSendTo, onAllDevices) }
            }
            if (targets.refused.isNotEmpty()) item(key = "send-refused") { TaildropRefusedNote(targets.refused) }

            item(key = "inbox-head") { inbox.Heading() }
            if (inbox.folded == 0) {
                // Not while something is arriving: "nothing here" would be untrue for a moment.
                if (loaded && incoming.isEmpty()) item(key = "inbox-empty") { TaildropMutedLine(stringResource(R.string.files_empty_inbox), Icons.Default.Inbox) }
            } else if (inbox.open) {
                items(inbox.cards, key = { it.key }) { inbox.Card(it) }
            }
            if (folderLine != null && loaded) item(key = "inbox-folder-line") { TaildropFolderLineCard(folderLine) }
            if (showFolderHint && loaded) item(key = "inbox-folder-hint") { TaildropFolderHint(onChooseFolder, onDismissFolderHint) }

            item(key = "history-head") { TaildropSectionHeading(stringResource(R.string.taildrop_section_history), null) }
            if (history.isEmpty()) {
                if (loaded) item(key = "history-empty") { TaildropMutedLine(stringResource(R.string.files_empty_history), Icons.Default.History) }
            } else {
                item(key = "history-rows") { TaildropHistoryGroup(history, nowMillis, onEntry, onAllHistory) }
            }
        }
    }
}

/**
 * The inbox pane's width on a large window and on a television: wide enough for a card's
 * three buttons in any language. At the 360dp an expanded window gives a list, "Скрыть",
 * "Показать в папке" and "Открыть" do not fit a card.
 */
private val TaildropInboxPaneWidth = 440.dp

/** One card of the inbox: a file waiting in the app, or one the folder took. [at] is when it came. */
private sealed interface InboxCard {
    val key: String
    val at: Long

    data class Waiting(val file: TaildropFile) : InboxCard {
        override val key get() = "file:" + file.Path + file.Name
        override val at get() = TaildropInbox.arrivedAt(file)
    }

    // Keyed by position too: on the device's storage a document id is a path, so a copy
    // saved under the name of one deleted earlier gets the same URI again.
    data class Saved(val index: Int, val entry: TaildropHistoryEntry) : InboxCard {
        override val key get() = "saved:$index:" + entry.savedUri
        override val at get() = entry.timestamp
    }
}

/**
 * What the inbox lists and does, gathered once so that the phone's list, the grid and the
 * pane draw the same cards from it.
 */
private class InboxContent(
    val incoming: List<IncomingTransfer>,
    val files: List<TaildropFile>,
    val history: List<TaildropHistoryEntry>,
    val saved: List<TaildropHistoryEntry>,
    val loaded: Boolean,
    val onOpenFile: (TaildropFile) -> Unit,
    val onSaveFile: (TaildropFile) -> Unit,
    val onDeleteFile: (TaildropFile) -> Unit,
    val onOpenSaved: (TaildropHistoryEntry) -> Unit,
    val onShowSavedInFolder: ((TaildropHistoryEntry) -> Unit)?,
    val onCopySavedFolderPath: ((TaildropHistoryEntry) -> Unit)?,
    val onHideSaved: (TaildropHistoryEntry) -> Unit,
    val showFolderHint: Boolean,
    val onChooseFolder: () -> Unit,
    val onDismissFolderHint: () -> Unit,
    val folderLine: TaildropFolderLine?,
    newSince: Long?,
    /** Whether the folded inbox is open; only the list and the grid fold it. */
    val open: Boolean = true,
    val onToggle: () -> Unit = {}
) {
    val count: Int get() = incoming.size + files.size + saved.size

    /** What the fold hides: everything but what is arriving, which shows without asking. */
    val folded: Int get() = files.size + saved.size

    /**
     * The cards in order. Waiting first: they are the ones asking for something — or, where
     * the newest are to stand out, all of them by arrival, newest first.
     */
    val cards: List<InboxCard> = (files.map(InboxCard::Waiting) + saved.mapIndexed(InboxCard::Saved))
        .let { all -> if (newSince == null) all else all.sortedByDescending { it.at } }

    /** How many of [cards], from the first, are new. */
    val fresh: Int = if (newSince == null) 0 else cards.count { it.at > newSince }

    @Composable
    fun Card(card: InboxCard) = when (card) {
        is InboxCard.Waiting -> WaitingCard(card.file)
        is InboxCard.Saved -> SavedCard(card.entry)
    }

    /** The inbox's heading as the fold's handle; a plain heading while there is nothing to open. */
    @Composable
    fun Heading() {
        if (folded == 0) TaildropSectionHeading(stringResource(R.string.taildrop_section_inbox), null)
        else TaildropFoldHeading(stringResource(R.string.taildrop_section_inbox), folded, open, onToggle)
    }

    /** A file waiting in the app, with who sent it and what became of a save, from the history. */
    @Composable
    fun WaitingCard(f: TaildropFile) {
        val entry = remember(f, history) { TaildropInbox.receivedEntryOf(f, history) }
        val note = when {
            entry?.savedTo != null -> stringResource(R.string.taildrop_saved_to_format, entry.savedTo)
            entry?.saveError != null -> stringResource(R.string.taildrop_save_failed)
            else -> null
        }
        FileCard(
            f, { onOpenFile(f) }, { onSaveFile(f) }, { onDeleteFile(f) },
            sender = entry?.peerName?.takeIf { it.isNotEmpty() },
            note = note,
            noteIsError = entry?.savedTo == null && entry?.saveError != null
        )
    }

    @Composable
    fun SavedCard(e: TaildropHistoryEntry) {
        TaildropSavedCard(
            e,
            onOpen = { onOpenSaved(e) },
            onShowInFolder = onShowSavedInFolder?.let { show -> { show(e) } },
            onCopyFolderPath = onCopySavedFolderPath?.let { copy -> { copy(e) } },
            onHide = { onHideSaved(e) }
        )
    }
}

/**
 * The inbox as a pane of its own. An empty one is a pane's worth of empty space, so it says
 * so in the middle of the pane rather than in one line at the top, the folder hint over it.
 * The receive folder's line, where there is one, comes right under the heading: it is where
 * every card below went or failed to go.
 */
private fun LazyListScope.inboxPaneItems(inbox: InboxContent) {
    val hint = inbox.showFolderHint && inbox.loaded
    item(key = "inbox-head") { TaildropSectionHeading(stringResource(R.string.taildrop_section_inbox), inbox.count.takeIf { it > 0 }) }
    if (inbox.folderLine != null && inbox.loaded) item(key = "inbox-folder-line") { TaildropFolderLineCard(inbox.folderLine) }
    if (inbox.count == 0) {
        if (hint) item(key = "inbox-folder-hint") { TaildropFolderHint(inbox.onChooseFolder, inbox.onDismissFolderHint) }
        if (inbox.loaded) item(key = "inbox-empty") {
            EmptyState(
                icon = Icons.Default.Inbox,
                text = stringResource(R.string.files_empty_inbox),
                modifier = Modifier.fillParentMaxWidth().fillParentMaxHeight(0.6f)
            )
        }
    } else {
        items(inbox.incoming, key = { "incoming:" + it.key }) { TaildropIncomingCard(it) }
        val fresh = inbox.fresh
        if (fresh > 0) item(key = "inbox-new") { TaildropNewLabel() }
        items(inbox.cards.take(fresh), key = { it.key }) { inbox.Card(it) }
        if (fresh > 0 && fresh < inbox.cards.size) item(key = "inbox-earlier") { TaildropEarlierLabel() }
        items(inbox.cards.drop(fresh), key = { it.key }) { inbox.Card(it) }
        if (hint) item(key = "inbox-folder-hint") { TaildropFolderHint(inbox.onChooseFolder, inbox.onDismissFolderHint) }
    }
}

/** The folded inbox in the medium window's grid; the heading and the hint span its width. */
private fun LazyStaggeredGridScope.inboxGridItems(inbox: InboxContent) {
    item(key = "inbox-head", span = StaggeredGridItemSpan.FullLine) { inbox.Heading() }
    if (inbox.folderLine != null && inbox.loaded) item(key = "inbox-folder-line", span = StaggeredGridItemSpan.FullLine) {
        TaildropFolderLineCard(inbox.folderLine)
    }
    if (inbox.folded == 0) {
        if (inbox.loaded && inbox.incoming.isEmpty()) item(key = "inbox-empty", span = StaggeredGridItemSpan.FullLine) {
            TaildropMutedLine(stringResource(R.string.files_empty_inbox), Icons.Default.Inbox)
        }
    } else if (inbox.open) {
        val fresh = inbox.fresh
        if (fresh > 0) item(key = "inbox-new", span = StaggeredGridItemSpan.FullLine) { TaildropNewLabel() }
        items(inbox.cards.take(fresh), key = { it.key }) { inbox.Card(it) }
        if (fresh > 0 && fresh < inbox.cards.size) item(key = "inbox-earlier", span = StaggeredGridItemSpan.FullLine) { TaildropEarlierLabel() }
        items(inbox.cards.drop(fresh), key = { it.key }) { inbox.Card(it) }
    }
    if (inbox.showFolderHint && inbox.loaded) item(key = "inbox-folder-hint", span = StaggeredGridItemSpan.FullLine) {
        TaildropFolderHint(inbox.onChooseFolder, inbox.onDismissFolderHint)
    }
}

/** Over the cards that came since the inbox was last looked at. */
@Composable
private fun TaildropNewLabel() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    ) {
        Text(
            stringResource(R.string.taildrop_inbox_new),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Over the rest, once there is a "New" to tell them from. */
@Composable
private fun TaildropEarlierLabel() {
    Text(
        stringResource(R.string.taildrop_inbox_earlier),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

/** The receive folder in one line with Change — or, when files cannot go there, why and what to do. */
@Composable
private fun TaildropFolderLineCard(line: TaildropFolderLine) {
    val ready = line.state == TaildropFolder.State.READY
    val container = if (ready) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.tertiaryContainer
    val onColor = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(shape = MaterialTheme.shapes.large, color = container, modifier = Modifier.fillMaxWidth()) {
        if (ready) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.taildrop_folder_line_format, line.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = onColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                )
                TextButton(onClick = line.onChange) { Text(stringResource(R.string.taildrop_folder_change)) }
            }
        } else {
            val needsAccess = line.state == TaildropFolder.State.NEEDS_ACCESS
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = onColor)
                    Spacer(Modifier.width(12.dp))
                    HelpText(
                        stringResource(
                            if (needsAccess) R.string.taildrop_folder_needs_access_format else R.string.taildrop_folder_unwritable_format,
                            line.label
                        ) + line.grantNote?.let { " $it" }.orEmpty(),
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        color = onColor
                    )
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = line.onChange) { Text(stringResource(R.string.taildrop_folder_change)) }
                    if (needsAccess) {
                        Button(onClick = line.onAllow, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.taildrop_folder_allow)) }
                    }
                }
            }
        }
    }
}

/**
 * The Send section and the History section side by side where two columns fit, one over
 * the other where they do not — the same sections the phone's list holds under its inbox.
 * The history shows as many rows as the devices, so that side by side the two cards end
 * on one line.
 */
@Composable
private fun TaildropSendAndHistory(
    targets: TaildropTargets,
    history: List<TaildropHistoryEntry>,
    loaded: Boolean,
    nowMillis: Long,
    onSendTo: (PeerData) -> Unit,
    onAllDevices: () -> Unit,
    onAllHistory: () -> Unit,
    onEntry: (TaildropHistoryEntry) -> Unit,
    /** The entry whose pane has just closed: its row takes the remote's focus back, once ([onFocusReturned]). */
    returnFocusTo: TaildropHistoryEntry? = null,
    onFocusReturned: () -> Unit = {}
) {
    CardColumns(minColumnWidth = 320.dp, maxColumns = 2, spacing = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TaildropSectionHeading(stringResource(R.string.taildrop_section_send), null)
            if (targets.all.isEmpty()) {
                if (loaded) TaildropMutedLine(stringResource(R.string.taildrop_send_empty), Icons.Default.Devices)
            } else {
                TaildropSendGroup(targets, onSendTo, onAllDevices)
            }
            if (targets.refused.isNotEmpty()) TaildropRefusedNote(targets.refused)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TaildropSectionHeading(stringResource(R.string.taildrop_section_history), null)
            if (history.isEmpty()) {
                if (loaded) TaildropMutedLine(stringResource(R.string.files_empty_history), Icons.Default.History)
            } else {
                TaildropHistoryGroup(history, nowMillis, onEntry, onAllHistory, rows = SEND_ROWS, returnFocusTo, onFocusReturned)
            }
        }
    }
}

/**
 * Takes the remote's focus once, as the element appears: for the row an entry's pane was
 * opened from, when the pane closes and the list is back — without it the focus is nowhere,
 * and the next key starts from the top of the other pane.
 */
@Composable
private fun Modifier.takeFocusOnce(onTaken: () -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        runCatching { requester.requestFocus() }
        onTaken()
    }
    return focusRequester(requester)
}

/** The Send section's card: the first devices, then "All devices" when there are more. */
@Composable
private fun TaildropSendGroup(targets: TaildropTargets, onSendTo: (PeerData) -> Unit, onAllDevices: () -> Unit) {
    TaildropGroup {
        targets.all.take(SEND_ROWS).forEachIndexed { i, t ->
            if (i > 0) TaildropRowDivider()
            TaildropDeviceRow(t) { onSendTo(t.peer) }
        }
        if (targets.all.size > SEND_ROWS) {
            TaildropRowDivider(inset = false)
            TaildropMoreRow(stringResource(R.string.taildrop_all_devices_format, targets.all.size), onAllDevices)
        }
    }
}

/** The History section's card: the latest entries and "All history". */
@Composable
private fun TaildropHistoryGroup(
    history: List<TaildropHistoryEntry>,
    nowMillis: Long,
    onEntry: (TaildropHistoryEntry) -> Unit,
    onAllHistory: () -> Unit,
    rows: Int = HISTORY_ROWS,
    returnFocusTo: TaildropHistoryEntry? = null,
    onFocusReturned: () -> Unit = {}
) {
    val shown = history.take(rows)
    TaildropGroup {
        shown.forEachIndexed { i, e ->
            if (i > 0) TaildropRowDivider()
            TaildropHistoryRow(e, nowMillis, if (e == returnFocusTo) Modifier.takeFocusOnce(onFocusReturned) else Modifier) { onEntry(e) }
        }
        // Always there, not only past five: it is also the way to search,
        // export and clear.
        TaildropRowDivider(inset = false)
        TaildropMoreRow(
            stringResource(R.string.taildrop_all_history_format, history.size),
            onAllHistory,
            // An entry opened from the whole history, or deleted since, has no row here.
            if (returnFocusTo != null && returnFocusTo !in shown) Modifier.takeFocusOnce(onFocusReturned) else Modifier
        )
    }
}

// --- pieces of the page ---

/** Same heading as the Serve and Tailcat screens, with the section's count after it. */
@Composable
private fun TaildropSectionHeading(text: String, count: Int?) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (count != null) {
            Text(
                " · $count",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A section's heading that opens and folds it: the same words and count, a chevron at the
 * end, the whole line the tap target. A screen reader hears a button and its state.
 */
@Composable
private fun TaildropFoldHeading(text: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (open) R.string.taildrop_fold_open else R.string.taildrop_fold_closed)
    val action = stringResource(if (open) R.string.taildrop_fold_hide else R.string.taildrop_fold_show)
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            // Before the clip, which would cut the corners off the remote's ring.
            .tvFocusRing(source, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(source, LocalIndication.current, role = Role.Button, onClickLabel = action, onClick = onToggle)
            .semantics { stateDescription = state }
            .heightIn(min = 48.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(" · $count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(20.dp)
        )
    }
}

/** What an empty section says: one quiet line, not a screen-sized empty state. */
@Composable
private fun TaildropMutedLine(text: String, icon: ImageVector?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/** A section's rows on one card; the card clips them, so a row's ripple keeps its corners. */
@Composable
private fun TaildropGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) { Column(content = content) }
}

/** Between two rows; inset to the text, past the icon, as list dividers are. */
@Composable
private fun TaildropRowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 66.dp else 0.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

/** "All devices · 14": the door to the rest, last on the card. */
@Composable
private fun TaildropMoreRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A file being received: its name, the sender, a bar and the numbers (incomingDetail). No
 * buttons — there is nothing to do with half a file, and the daemon offers no way to refuse
 * one. The bar runs indeterminate while the size is unknown and while the file is finished
 * and saved; an interrupted one keeps how far it got, in the error colour.
 */
@Composable
internal fun TaildropIncomingCard(transfer: IncomingTransfer) {
    val interrupted = transfer.phase == IncomingPhase.INTERRUPTED
    val accent = if (interrupted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val detail = incomingDetail(LocalResources.current, transfer)
    val fraction = transfer.fraction ?: if (interrupted) 0f else null
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(transfer.name.substringAfterLast('.', "").lowercase())
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(transfer.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                transfer.sender?.let {
                    Text(
                        stringResource(R.string.taildrop_from_format, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val bar = Modifier.fillMaxWidth().padding(top = 10.dp)
                if (fraction != null) {
                    // Reports come a second apart; the bar slides between them instead of jumping.
                    val shown by animateFloatAsState(fraction, label = "incoming")
                    LinearProgressIndicator(progress = { shown }, modifier = bar, color = accent)
                } else {
                    LinearProgressIndicator(modifier = bar, color = accent)
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (interrupted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * A received file the default folder took: the name it was saved under, when it came and
 * from whom, and where it is now. Open and Show in folder go to the saved copy — a long
 * press on Show in folder copies the folder's path instead, for a file manager that takes
 * no folder; Hide takes the card off the inbox and leaves the file alone. Without
 * [onShowInFolder] — a device with no file manager to show a folder in — the button is not
 * there; without [onCopyFolderPath] — one with no clipboard — it is a plain button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TaildropSavedCard(
    entry: TaildropHistoryEntry,
    onOpen: () -> Unit,
    onShowInFolder: (() -> Unit)?,
    onCopyFolderPath: (() -> Unit)?,
    onHide: () -> Unit
) {
    val savedTo = entry.savedTo.orEmpty()
    // savedTo is "<folder>/<name>"; the name may have been made free with " (1)".
    val name = savedTo.substringAfterLast('/').ifEmpty { entry.name }
    val folder = savedTo.substringBeforeLast('/', "").ifEmpty { savedTo }
    val locale = LocalConfiguration.current.locales[0]
    // The waiting cards' format, so the two kinds read alike.
    val date = remember(entry.timestamp, locale) { SimpleDateFormat("d MMM, HH:mm", locale).format(Date(entry.timestamp)) }
    val from = entry.peerName.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.taildrop_from_format, it) }

    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(name.substringAfterLast('.', "").lowercase())
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(date, entry.size?.let(::formatFileSize), from).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.taildrop_saved_to_format, folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onHide, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Text(stringResource(R.string.taildrop_hide))
            }
            if (onShowInFolder != null && onCopyFolderPath == null) {
                TextButton(onClick = onShowInFolder) { Text(stringResource(R.string.taildrop_show_in_folder)) }
            } else if (onShowInFolder != null) {
                // A TextButton in looks and size — the 48dp it reserves to be touched, too, or
                // the row sets it higher than its neighbours — with a long press it cannot take.
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(ButtonDefaults.textShape)
                        .combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = stringResource(R.string.taildrop_copy_folder_path),
                            onLongClick = onCopyFolderPath,
                            onClick = onShowInFolder
                        )
                        .defaultMinSize(minHeight = ButtonDefaults.MinHeight)
                        .padding(ButtonDefaults.TextButtonContentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.taildrop_show_in_folder),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Button(onClick = onOpen, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.action_open)) }
        }
    }
}

/**
 * While no default folder is chosen: that there could be one, and the picker for it. The
 * explanation folds to two lines; the cross hides the hint for good.
 */
@Composable
private fun TaildropFolderHint(onChoose: () -> Unit, onDismiss: () -> Unit) {
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CreateNewFolder, null, Modifier.size(20.dp), tint = onColor)
                Spacer(Modifier.width(12.dp))
                HelpText(
                    stringResource(R.string.taildrop_folder_hint),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    color = onColor
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.taildrop_folder_hint_dismiss), Modifier.size(18.dp), tint = onColor)
                }
            }
            TextButton(onClick = onChoose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.taildrop_folder_hint_choose))
            }
        }
    }
}

/** The icon of a list row: a tinted circle, the size the peer rows use. */
@Composable
private fun TaildropRowIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
}

/**
 * A device a file can go to. The second line is its address and OS, or — for one the
 * control plane marks offline — the daemon's note, since that is what decides whether to
 * try. A tap picks the files for it.
 */
@Composable
internal fun TaildropDeviceRow(target: TaildropTarget, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val peer = target.peer
    val (osIcon, osColor) = getOsVisuals(peer.os)
    val note = (target.status as? TaildropStatus.Offline)?.reason
    val online = peer.online == true
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_send), onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaildropRowIcon(osIcon, osColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.getDisplayName(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                note ?: listOfNotNull(peer.tailscaleIPs?.firstOrNull(), peer.os?.takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (online) {
            Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(PEER_ONLINE_GREEN))
        }
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.padding(start = 12.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The devices left off the list because the daemon refuses them: their number in one line,
 * and — folded until asked for — who they are and the daemon's reason for each.
 */
@Composable
private fun TaildropRefusedNote(refused: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val head = pluralStringResource(R.plurals.taildrop_refused_more, refused.size, refused.size)
    val reasons = refused.groupBy({ it.second }, { it.first })
        .entries.joinToString("\n") { (reason, names) -> names.joinToString(", ") + " — " + reason }
    HelpText(
        text = head + "\n" + reasons,
        lines = 1,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)
    )
}

/** The icon and colour of an entry: up for sent, down for received, a warning for a failed send. */
@Composable
private fun entryLook(entry: TaildropHistoryEntry): Pair<ImageVector, Color> = when {
    entry.failed -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    entry.direction == TaildropDirection.RECEIVED -> Icons.Default.FileDownload to MaterialTheme.colorScheme.tertiary
    else -> Icons.Default.FileUpload to MaterialTheme.colorScheme.primary
}

@Composable
private fun entryPeerLine(entry: TaildropHistoryEntry): String {
    val who = entry.peerName.ifEmpty { stringResource(R.string.taildrop_unknown_device) }
    return stringResource(
        if (entry.direction == TaildropDirection.RECEIVED) R.string.taildrop_from_format else R.string.taildrop_to_format,
        who
    )
}

/** One transfer: the file, who it went to or came from, when; the size at the end. */
@Composable
internal fun TaildropHistoryRow(entry: TaildropHistoryEntry, nowMillis: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (icon, tint) = entryLook(entry)
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(entry.timestamp, nowMillis, locale) { shortTime(entry.timestamp, nowMillis, locale) }
    val line = listOfNotNull(
        if (entry.failed) stringResource(R.string.taildrop_result_failed) else null,
        entryPeerLine(entry),
        time
    ).joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaildropRowIcon(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        entry.size?.let {
            Text(
                formatFileSize(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** "14:32" today, "6 Oct, 14:32" this year, "6 Oct 2025" before. */
private fun shortTime(millis: Long, nowMillis: Long, locale: Locale): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val pattern = when {
        sameYear && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
        sameYear -> "d MMM, HH:mm"
        else -> "d MMM yyyy"
    }
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

// --- sheets ---

/**
 * A full-height sheet whose content reads strings in the activity's language: the parent's
 * context, configuration and resources, all three, provided again inside — the sheet's own
 * window answers in the system language (see wrapContextWithLocale()). [content] gets a
 * `hide` that slides the sheet away and then calls [onDismiss].
 *
 * On a television the sheet has no handle — there is nothing to drag it with, and the handle
 * was the first thing the remote stopped on; Back closes it — and it ends above the part of
 * the screen a set may cut off, which the theme keeps clear for a screen and not for a
 * window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaildropSheet(onDismiss: () -> Unit, content: @Composable (hide: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val sheetState = rememberFullSheetState()
    val scope = rememberCoroutineScope()
    val tv = isTv
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = if (tv) null else ({ BottomSheetDefaults.DragHandle() })
    ) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides resources
        ) {
            val body: @Composable () -> Unit = {
                content { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
            }
            if (tv) Box(Modifier.padding(top = 16.dp, bottom = TvSafeArea.calculateBottomPadding())) { body() } else body()
        }
    }
}

/**
 * Every device a file can go to, under [title]: the Send section's "All devices", and the
 * picker the send button opens once the files are chosen. Searchable once the list is
 * longer than the section shows. Holds the refused-devices note at the end too.
 */
@Composable
internal fun TaildropDevicesSheetContent(
    title: String,
    targets: TaildropTargets,
    emptyText: String = stringResource(R.string.taildrop_send_empty),
    onPick: (PeerData) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val q = query.trim()
    val shown = remember(targets, q) {
        if (q.isEmpty()) targets.all
        else targets.all.filter { t ->
            val p = t.peer
            p.getDisplayName().contains(q, ignoreCase = true) ||
                p.tailscaleIPs.orEmpty().any { it.contains(q, ignoreCase = true) } ||
                p.os.orEmpty().contains(q, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
        )
        if (targets.all.size > SEND_ROWS && Feature.SEARCH.offered()) {
            CompactSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholderText = stringResource(R.string.peers_search_placeholder),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (shown.isEmpty()) item {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    TaildropMutedLine(if (q.isEmpty()) emptyText else stringResource(R.string.taildrop_devices_no_match), null)
                }
            }
            items(shown, key = { it.peer.id ?: it.peer.getDisplayName() }) { t ->
                TaildropDeviceRow(t, Modifier.clip(MaterialTheme.shapes.medium)) { onPick(t.peer) }
            }
            if (targets.refused.isNotEmpty() && q.isEmpty()) item {
                TaildropRefusedNote(targets.refused, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/** The History sheet's filter chips. Sent and Failed split the sends, so the counts add up. */
internal enum class TaildropHistoryFilter(val label: Int) {
    ALL(R.string.taildrop_filter_all),
    SENT(R.string.taildrop_filter_sent),
    RECEIVED(R.string.taildrop_filter_received),
    FAILED(R.string.taildrop_filter_failed);

    fun matches(e: TaildropHistoryEntry): Boolean = when (this) {
        ALL -> true
        SENT -> e.direction == TaildropDirection.SENT && e.ok
        RECEIVED -> e.direction == TaildropDirection.RECEIVED
        FAILED -> e.failed
    }
}

/**
 * The whole history: a search over file and device, the four filters, export and clear.
 * Without [onExport] — a device with no app to hand the export to — there is no such button.
 */
@Composable
internal fun TaildropHistorySheetContent(
    history: List<TaildropHistoryEntry>,
    onEntry: (TaildropHistoryEntry) -> Unit,
    onExport: (() -> Unit)?,
    onClear: () -> Unit,
    nowMillis: Long = System.currentTimeMillis(),
    initialFilter: TaildropHistoryFilter = TaildropHistoryFilter.ALL
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val q = query.trim()
    val searched = remember(history, q) {
        if (q.isEmpty()) history
        else history.filter {
            it.name.contains(q, ignoreCase = true) || it.peerName.contains(q, ignoreCase = true) ||
                it.peerIp.orEmpty().contains(q, ignoreCase = true)
        }
    }
    val shown = remember(searched, filter) { searched.filter(filter::matches) }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.taildrop_section_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (onExport != null) {
                IconButton(onClick = onExport, enabled = history.isNotEmpty()) {
                    Icon(Icons.Default.Share, stringResource(R.string.taildrop_history_export))
                }
            }
            IconButton(onClick = onClear, enabled = history.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, stringResource(R.string.taildrop_history_clear), tint = MaterialTheme.colorScheme.error.copy(alpha = if (history.isNotEmpty()) 1f else 0.38f))
            }
        }
        IfOffered(Feature.SEARCH) {
            CompactSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholderText = stringResource(R.string.taildrop_history_search),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TaildropHistoryFilter.entries.forEach { f ->
                val count = searched.count(f::matches)
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(stringResource(f.label) + " · " + count) }
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (shown.isEmpty()) item {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    TaildropMutedLine(stringResource(if (history.isEmpty()) R.string.files_empty_history else R.string.taildrop_history_no_match), null)
                }
            }
            items(shown) { e ->
                TaildropHistoryRow(e, nowMillis, Modifier.clip(MaterialTheme.shapes.medium)) { onEntry(e) }
            }
        }
    }
}

/**
 * One entry of the history in full: a sheet over the page on a phone, the pane beside the
 * inbox on a two-pane window ([LocalInPane]) — where there is no swipe to dismiss it, so
 * it has a close button, and Delete closes it as the sheet slides away. [onDelete] removes
 * the entry once the details are going.
 */
@Composable
internal fun TaildropEntrySheet(entry: TaildropHistoryEntry, onDismiss: () -> Unit, onDelete: () -> Unit) {
    if (LocalInPane.current) {
        TaildropEntryDetails(entry, onClose = onDismiss) { onDismiss(); onDelete() }
    } else {
        TaildropSheet(onDismiss = onDismiss) { hide ->
            TaildropEntryDetails(entry) { hide(); onDelete() }
        }
    }
}

/** One label and its value in the details sheet. */
private data class DetailRow(val label: String, val value: String, val monospace: Boolean = false, val isError: Boolean = false)

/**
 * Everything the history knows about one transfer, grouped: the file, the device, the
 * transfer, and — for a received file — what became of it here. A field nobody recorded
 * has no row, and a group with no rows is not drawn: an entry from before the history
 * grew is a name, a device and a date, and shows as just that.
 */
@Composable
internal fun TaildropEntryDetails(entry: TaildropHistoryEntry, onClose: (() -> Unit)? = null, onDelete: () -> Unit) {
    // In a pane (onClose given) nothing sits over it, and the send button sits in its corner.
    val inPane = onClose != null
    val locale = LocalConfiguration.current.locales[0]
    val (icon, tint) = entryLook(entry)
    val full = remember(locale) { SimpleDateFormat("d MMMM yyyy, HH:mm:ss", locale) }
    val short = remember(locale) { SimpleDateFormat("d MMM yyyy, HH:mm", locale) }
    val result = stringResource(
        when {
            entry.failed -> R.string.taildrop_result_failed
            entry.direction == TaildropDirection.RECEIVED -> R.string.taildrop_result_received
            else -> R.string.taildrop_result_sent
        }
    )

    val fileRows = listOfNotNull(
        entry.size?.let {
            // The exact count too: a size is what one compares with the other side's.
            val bytes = if (it >= 1024) " (" + NumberFormat.getIntegerInstance(locale).format(it) + " B)" else ""
            DetailRow(stringResource(R.string.taildrop_details_size), formatFileSize(it) + bytes)
        },
        entry.mime?.let { DetailRow(stringResource(R.string.taildrop_details_type), it) },
        entry.sha256?.let { DetailRow(stringResource(R.string.taildrop_details_sha256), it, monospace = true) }
    )
    val deviceRows = listOfNotNull(
        entry.peerName.takeIf { it.isNotEmpty() }?.let { DetailRow(stringResource(R.string.taildrop_details_name), it) },
        entry.peerIp?.let { DetailRow(stringResource(R.string.taildrop_details_ip), it, monospace = true) },
        entry.peerOs?.let { DetailRow(stringResource(R.string.taildrop_details_os), it) },
        entry.peerId?.let { DetailRow(stringResource(R.string.taildrop_details_node_id), it, monospace = true) }
    )
    val speed = if (entry.size != null && entry.durationMs != null && entry.durationMs > 0 && entry.ok) {
        stringResource(R.string.taildrop_speed_format, formatFileSize(entry.size * 1000 / entry.durationMs))
    } else null
    val transferRows = listOfNotNull(
        DetailRow(stringResource(R.string.taildrop_details_time), full.format(Date(entry.timestamp))),
        entry.durationMs?.let { DetailRow(stringResource(R.string.taildrop_details_duration), durationText(it)) },
        speed?.let { DetailRow(stringResource(R.string.taildrop_details_speed), it) },
        entry.route?.let { r ->
            val at = entry.routeAddress.orEmpty()
            DetailRow(
                stringResource(R.string.taildrop_details_route),
                stringResource(
                    when (r) {
                        TaildropRoute.DIRECT -> R.string.taildrop_route_direct_format
                        TaildropRoute.PEER_RELAY -> R.string.taildrop_route_peer_relay_format
                        TaildropRoute.DERP -> R.string.taildrop_route_derp_format
                    },
                    at
                ).removeSuffix(" · ")
            )
        },
        entry.attempt?.let { DetailRow(stringResource(R.string.taildrop_details_attempt), it.toString()) },
        entry.httpStatus?.let { DetailRow(stringResource(R.string.taildrop_details_http), it.toString(), isError = true) },
        entry.error?.let { DetailRow(stringResource(R.string.taildrop_details_error), it, isError = true) },
        entry.source?.let {
            DetailRow(
                stringResource(R.string.taildrop_details_source),
                stringResource(
                    when (it) {
                        TaildropSource.FILES -> R.string.taildrop_source_files
                        TaildropSource.SHARE -> R.string.taildrop_source_share
                        TaildropSource.PEERS -> R.string.taildrop_source_peers
                    }
                )
            )
        }
    )
    val phoneRows = listOfNotNull(
        entry.savedTo?.let { to ->
            DetailRow(stringResource(R.string.taildrop_details_saved_to), listOfNotNull(to, entry.savedAt?.let { short.format(Date(it)) }).joinToString(" · "))
        },
        entry.saveError?.let { DetailRow(stringResource(R.string.taildrop_details_save_error), it, isError = true) },
        entry.deletedAt?.let { DetailRow(stringResource(R.string.taildrop_details_deleted), short.format(Date(it))) }
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = if (inPane) 16.dp else 0.dp, bottom = if (inPane) 88.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, Modifier.size(22.dp), tint = tint) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    result + " · " + entryPeerLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onClose != null) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
            }
        }
        // Selectable: a hash, an address or an error is what one comes here to copy.
        SelectionContainer {
            val groups: @Composable () -> Unit = {
                DetailsGroup(stringResource(R.string.taildrop_details_transfer), transferRows, startsFocus = true)
                DetailsGroup(stringResource(R.string.taildrop_details_device), deviceRows)
                DetailsGroup(stringResource(R.string.taildrop_details_file), fileRows)
                DetailsGroup(stringResource(R.string.taildrop_details_on_phone), phoneRows)
            }
            // A pane on a large window is twice a sheet's width: the groups take two columns
            // there, rather than rows with the value a hand's width from its label.
            if (inPane) CardColumns(minColumnWidth = 320.dp, maxColumns = 2, spacing = 12.dp) { groups() }
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { groups() }
        }
        OutlinedButton(
            onClick = onDelete,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.taildrop_entry_delete))
        }
    }
}

/**
 * A stop for the remote on something that is only read. The details are taller than a
 * television's screen and have a button at each end; with the groups between them as stops
 * the pane scrolls to whatever the remote is on, and nothing is acted on out of sight.
 */
@Composable
private fun Modifier.tvReadStop(shape: Shape): Modifier {
    if (!isTv) return this
    val source = remember { MutableInteractionSource() }
    return tvFocusRing(source, shape).focusable(interactionSource = source)
}

/** [startsFocus]: where the remote lands when the details open — in them, not on whatever was left behind. */
@Composable
private fun DetailsGroup(title: String, rows: List<DetailRow>, startsFocus: Boolean = false) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        val start = if (startsFocus) Modifier.tvInitialFocus() else Modifier
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().then(start).tvReadStop(MaterialTheme.shapes.medium)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { r ->
                    Row {
                        Text(
                            r.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.38f).padding(end = 8.dp, top = 1.dp)
                        )
                        Text(
                            r.value,
                            style = if (r.monospace) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            fontFamily = if (r.monospace) FontFamily.Monospace else null,
                            color = if (r.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(0.62f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun durationText(ms: Long): String =
    if (ms < 60_000) stringResource(R.string.taildrop_duration_seconds_format, ms / 1000f)
    else stringResource(R.string.taildrop_duration_minutes_format, (ms / 60_000).toInt(), ((ms / 1000) % 60).toInt())
