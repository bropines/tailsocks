package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*

/*
 * The main screen's arrangement (core/MainLayout.kt) and the windows it is drawn in: one flow
 * — a phone held upright, and every window while the screen is being arranged —, a foldable
 * half open like a laptop, and two panes beside each other. The fourth, a dashboard for a
 * tablet or a desktop window, is MainDashboard.kt. Which one a window gets is decided by the
 * screen (ui/MainActivity.kt); the cells drawn in them are its lambdas.
 */

/** What is on the screen and where, and the screen being its own editor. */
@Stable
internal class MainLayoutState(
    layout: MutableState<MainLayout>,
    editing: MutableState<Boolean>,
    paletteOpen: MutableState<Boolean>,
    confirmLayoutReset: MutableState<Boolean>,
    private val context: Context,
    /** For previews: the arrangement drawn instead of the stored one; nothing is stored then. */
    private val previewLayout: MainLayout?,
) {
    var layout by layout
    // The screen as its own editor: a long press on it, or the row in Settings → Appearance.
    var editing by editing
    var paletteOpen by paletteOpen
    var confirmLayoutReset by confirmLayoutReset

    // Every change is stored as it is made: there is nothing to confirm, and Done only leaves.
    fun changeLayout(next: MainLayout) {
        if (next == layout) return
        layout = next
        if (previewLayout == null) MainLayoutStore.save(context, next)
    }
}

/**
 * Until the person arranges it, the arrangement is read off the switches the screen had
 * before, so it is the screen they know. The screen reads it again when it comes back to the
 * front: Settings can change it too.
 */
@Composable
internal fun rememberMainLayoutState(
    previewLayout: MainLayout?,
    previewEditing: Boolean,
    remoteTileOffered: Boolean,
    arrangeable: Boolean,
): MainLayoutState {
    val context = LocalContext.current
    val layout = remember { mutableStateOf(previewLayout ?: MainLayoutStore.load(context, remoteTileOffered, arrangeable)) }
    val editing = rememberSaveable { mutableStateOf(previewEditing) }
    val paletteOpen = remember { mutableStateOf(false) }
    val confirmLayoutReset = remember { mutableStateOf(false) }
    return remember { MainLayoutState(layout, editing, paletteOpen, confirmLayoutReset, context, previewLayout) }
}

/** The editor's palette and its reset confirmation, over the screen while asked for. */
@Composable
internal fun MainLayoutOverlays(
    state: MainLayoutState,
    itemOffered: (MainItem) -> Boolean,
    previewLayout: MainLayout?,
    remoteTileOffered: Boolean,
    arrangeable: Boolean,
): Unit = with(state) {
    val context = LocalContext.current
    if (paletteOpen) {
        MainPaletteSheet(
            items = layout.addable(itemOffered),
            onAdd = { changeLayout(layout.add(it)) },
            onDismiss = { paletteOpen = false },
        )
    }

    if (confirmLayoutReset) {
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_layout_reset_title)
        val dlgText = stringResource(R.string.main_layout_reset_text)
        val dlgReset = stringResource(R.string.main_layout_reset)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { confirmLayoutReset = false },
            icon = { Icon(Icons.Default.RestartAlt, null) },
            title = { Text(dlgTitle) },
            text = { Text(dlgText) },
            confirmButton = {
                Button(onClick = {
                    confirmLayoutReset = false
                    if (previewLayout == null) {
                        MainLayoutStore.reset(context)
                        layout = MainLayoutStore.load(context, remoteTileOffered, arrangeable)
                    }
                }) { Text(dlgReset) }
            },
            dismissButton = { TextButton(onClick = { confirmLayoutReset = false }) { Text(dlgCancel) } }
        )
    }
}

/**
 * One flow, top to bottom: a phone held upright — and every window while the screen is being
 * arranged, because the flow is the arrangement itself. [flowWindow] says which of the two it
 * is; [placed] is what the device is offered of the arrangement.
 */
@Composable
internal fun MainFlowWindow(
    state: MainLayoutState,
    maxWidth: Dp,
    cardHeight: Dp,
    flowWindow: Boolean,
    placed: List<MainLayout.Placed>,
    itemOffered: (MainItem) -> Boolean,
    tv: Boolean,
    remoteTileOffered: Boolean,
    menuStart: FocusRequester,
    blockShows: (MainItem) -> Boolean,
    bannersBefore: (List<MainItem>) -> MainItem,
    banners: @Composable () -> Unit,
    block: @Composable ColumnScope.(MainItem, Modifier) -> Unit,
    entryOf: @Composable (MainLayout.Placed) -> MenuEntry,
    columnsOr: (Int) -> Int,
): Unit = with(state) {
    val columnWidth = minOf(maxWidth, ReadableContentWidth)
    val scroll = rememberScrollState()
    // Arranging, everything on the screen is there to be moved, with or without
    // something to show; in use, only what has something to show.
    val cells = if (editing) placed else placed.filter { it.item.isTile || blockShows(it.item) }
    val drawn = cells.map { it.item }
    val inserts = listOf(MainCanvasInsert(bannersBefore(drawn)) { banners() })
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.width(columnWidth).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            if (editing && !flowWindow) {
                Text(
                    stringResource(R.string.main_layout_panes_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            MainCanvas(
                cells = cells,
                columns = columnsOr(menuColumnsFor(columnWidth - 48.dp, cells.count { !it.full })),
                cardHeight = cardHeight,
                editing = editing,
                scroll = scroll,
                gestures = !tv,
                inserts = inserts,
                title = { stringResource(it.spec(tv).title) },
                // The card keeps its own long press — the line it shows for a while.
                // Anything else held down opens the editor, and is already being dragged.
                onLongPress = { item -> (item != MainItem.STATUS).also { if (it) editing = true } },
                onMove = { item, target -> changeLayout(layout.move(item, target)) },
                onStep = { item, by -> changeLayout(layout.step(item, by, itemOffered)) },
                onRemove = { changeLayout(layout.remove(it)) },
                onSetWide = { item, wide -> changeLayout(layout.setWide(item, wide)) },
            ) { cell ->
                val item = cell.item
                when {
                    item.isTile -> {
                        val entry = entryOf(cell)
                        // The remote's way into the menu from the card, and back onto the tile whose page it left.
                        val focus = Modifier
                            .then(if (remoteTileOffered && item == drawn.firstOrNull { it.isTile }) Modifier.focusRequester(menuStart) else Modifier)
                            .then(if (entry.focusOnShow) Modifier.tvInitialFocus() else Modifier)
                        if (cell.wide) WideMenuCard(entry, focus)
                        else MenuCard(title = entry.title, icon = entry.icon, icon2 = entry.icon2, modifier = focus, onClick = entry.onClick)
                    }
                    blockShows(item) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { block(item, Modifier) }
                    else -> MainBlockPlaceholder(
                        item,
                        note = if (item == MainItem.MANAGED) R.string.main_layout_when_managing else R.string.main_layout_when_connected,
                    )
                }
            }
            AnimatedVisibility(visible = editing && layout.addable(itemOffered).isNotEmpty()) {
                MainAddCell(onClick = { paletteOpen = true }, modifier = Modifier.padding(top = 16.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * A foldable half open like a laptop: the upright half shows the status and the half lying
 * flat holds the menu, under the thumbs.
 */
@Composable
internal fun MainTabletopWindow(
    fold: Fold.Horizontal,
    maxWidth: Dp,
    insetTop: Dp,
    cardHeight: Dp,
    cardCount: Int,
    managed: ManagedDevices,
    statusPane: @Composable ColumnScope.(fillHeight: Boolean) -> Unit,
    menuPane: @Composable (Int, Dp) -> Unit,
) {
    val menuColumns = menuColumnsFor(maxWidth - 48.dp, cardCount)
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((fold.top - insetTop).coerceAtLeast(0.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            statusPane(false)
        }
        Spacer(modifier = Modifier.height(fold.bottom - fold.top))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            menuPane(menuColumns, cardHeight)
            ManagedPills(managed, Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * The status and the menu side by side: a window with the width for it, or a foldable open
 * like a book ([fold] is then the hinge, each half a pane).
 */
@Composable
internal fun MainTwoPaneWindow(
    fold: Fold?,
    maxWidth: Dp,
    maxHeight: Dp,
    insetStart: Dp,
    cardHeight: Dp,
    paneArrangement: Arrangement.Vertical,
    cardCount: Int,
    managed: ManagedDevices,
    statusPane: @Composable ColumnScope.(fillHeight: Boolean) -> Unit,
    menuPane: @Composable (Int, Dp) -> Unit,
) {
    val statusWidth = if (fold is Fold.Vertical) (fold.start - insetStart - 36.dp).coerceAtLeast(220.dp)
                      else (maxWidth * 0.38f).coerceIn(280.dp, 400.dp)
    val paneGap = if (fold is Fold.Vertical) (fold.end - fold.start) + 24.dp else 24.dp
    // Columns follow the width the menu actually gets, not the
    // width of the window: the same phone in landscape and a
    // tablet in portrait can have the same window and very
    // different room left over here.
    val menuWidth = maxWidth - statusWidth - paneGap - 48.dp
    val menuColumns = menuColumnsFor(menuWidth, cardCount)
    // One row for the pair, as tall as the taller of the two. The
    // card takes up whatever the status column has left, so beside
    // a taller grid the column ends where the grid does — with the
    // exit-node line above the card or without it — and a column
    // that is already taller (banners, the summary) is left alone.
    // Intrinsic heights, not a measurement: the preview renders
    // one frame, and a measured height lands only on the second.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 24.dp),
        verticalArrangement = paneArrangement
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(
                modifier = Modifier.width(statusWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                statusPane(true)
            }
            Spacer(modifier = Modifier.width(paneGap))
            Column(modifier = Modifier.weight(1f)) {
                menuPane(menuColumns, cardHeight)
                ManagedPills(managed, Modifier.padding(top = 10.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
