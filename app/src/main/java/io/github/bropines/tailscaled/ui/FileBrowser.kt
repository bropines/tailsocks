package io.github.bropines.tailscaled.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.BrowseLocation
import io.github.bropines.tailscaled.core.BrowseRoot
import io.github.bropines.tailscaled.core.FileBrowse
import io.github.bropines.tailscaled.core.FileEntry
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.Listing
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.core.StorageAccess
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * A file and folder chooser of the app's own, for the devices that have no system picker — an
 * Android TV often does, and asking for one is a crash. A page, not an Activity: the caller
 * shows it where it likes and takes the answer from the callbacks. The rules of the road are in
 * core/FileBrowse.kt; this file is what is drawn and how the remote drives it.
 *
 * The remote drives it with nothing but the D-pad, OK and Back: every row is one focus stop and
 * OK does the row's single thing — enter a folder, check a file, go up. Back goes up a level and
 * cancels from the list of places. A window wide enough for two panes (a TV, a tablet) puts the
 * actions in a panel to the right of the list, one D-pad press away from any row; a phone puts
 * them in a bar under it.
 */

/** What the browser is asked to pick. */
sealed interface FileBrowserMode {
    /**
     * Files. Checked with OK and confirmed together when [multiple]; otherwise OK on a file
     * returns it at once.
     */
    data class Files(val multiple: Boolean = true) : FileBrowserMode

    /**
     * A folder: the one the browser is in when the confirm button is pressed. With [writable] a
     * folder the app cannot write to cannot be confirmed — for a place files are saved into.
     */
    data class Folder(val writable: Boolean = false) : FileBrowserMode
}

/** What the browser hands back; the same shape as the mode that was asked for. */
sealed interface FileBrowserResult {
    /** Existing files, in the order they were checked. */
    data class Files(val files: List<File>) : FileBrowserResult

    /** An existing folder. */
    data class Folder(val dir: File) : FileBrowserResult
}

/**
 * [FileBrowserScreen] for files: [onPicked] gets at least one existing file. [confirmLabel] is
 * the button's text for the number of files checked ("Send 3 files"); the default is "Choose N files".
 */
@Composable
fun PickFilesScreen(
    source: FileSource,
    onPicked: (List<File>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    start: File? = null,
    multiple: Boolean = true,
    title: String? = null,
    confirmLabel: (@Composable (count: Int) -> String)? = null,
    onGrantAccess: (() -> Unit)? = null,
) = FileBrowserScreen(
    mode = FileBrowserMode.Files(multiple),
    start = start,
    source = source,
    onResult = { if (it is FileBrowserResult.Files) onPicked(it.files) },
    onCancel = onCancel,
    modifier = modifier,
    title = title,
    confirmLabel = confirmLabel,
    onGrantAccess = onGrantAccess,
)

/** [FileBrowserScreen] for a folder: [onPicked] gets an existing folder. [confirmLabel] replaces "Choose this folder". */
@Composable
fun PickFolderScreen(
    source: FileSource,
    onPicked: (File) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    start: File? = null,
    writable: Boolean = false,
    title: String? = null,
    confirmLabel: String? = null,
    onGrantAccess: (() -> Unit)? = null,
) = FileBrowserScreen(
    mode = FileBrowserMode.Folder(writable),
    start = start,
    source = source,
    onResult = { if (it is FileBrowserResult.Folder) onPicked(it.dir) },
    onCancel = onCancel,
    modifier = modifier,
    title = title,
    confirmLabel = confirmLabel?.let { label -> { _: Int -> label } },
    onGrantAccess = onGrantAccess,
)

/**
 * The browser, full screen. It opens at [start] (or the nearest folder above it that still
 * exists; the list of places when there is none) and offers [start] as a place of its own.
 * [onResult] is called once with what was chosen; [onCancel] when Back is pressed from the
 * list of places or Cancel is pressed. Back and the Up row go one level up.
 *
 * [title] and [confirmLabel] replace the defaults ("Choose files", "Choose N files"; for a
 * folder "Choose this folder", where the count is 1). [showHidden] is where the toggle for
 * hidden files starts. [onGrantAccess] replaces the built-in grant — the Storage permission
 * dialog below Android 11, the All files access page from it, and when the device has no such
 * page the command to run from a computer.
 *
 * Reads happen off the main thread; the state (place, checked files, hidden toggle) survives
 * rotation. The page does not take the Back key until it is shown, so a caller that wants
 * Back to leave it elsewhere must not compose it.
 */
@Composable
fun FileBrowserScreen(
    mode: FileBrowserMode,
    start: File?,
    source: FileSource,
    onResult: (FileBrowserResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmLabel: (@Composable (count: Int) -> String)? = null,
    showHidden: Boolean = false,
    onGrantAccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val scope = rememberCoroutineScope()

    // Settings and the permission dialog are other screens: coming back from one, the grant, the
    // volumes and the listing are read again.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) {
                paused = false
                refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val access = remember(refresh) { source.access }

    var roots by remember { mutableStateOf<List<BrowseRoot>?>(if (inspection) source.roots(start) else null) }
    LaunchedEffect(source, start, refresh) { roots = withContext(Dispatchers.IO) { source.roots(start) } }

    // Where it is: "" the list of places, null not decided yet (the places have not arrived).
    fun opensAt(places: List<BrowseRoot>): String =
        FileBrowse.initialDir(start?.let(source::canonical), places) { source.describe(it)?.isDirectory == true }?.path ?: ""
    var path by rememberSaveable { mutableStateOf<String?>(if (inspection) roots?.let(::opensAt) else null) }
    LaunchedEffect(roots) {
        val places = roots
        if (path == null && places != null) path = withContext(Dispatchers.IO) { opensAt(places) }
    }
    var cameFrom by remember { mutableStateOf<File?>(null) }
    var hidden by rememberSaveable { mutableStateOf(showHidden) }
    val selected = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf() }

    val location: BrowseLocation? = path?.let { if (it.isEmpty()) BrowseLocation.Roots else BrowseLocation.Dir(File(it)) }
    val dir = (location as? BrowseLocation.Dir)?.dir
    var loaded by remember { mutableStateOf<Listing?>(if (inspection && dir != null) source.list(dir, hidden) else null) }
    LaunchedEffect(dir, hidden, refresh) { loaded = if (dir == null) null else withContext(Dispatchers.IO) { source.list(dir, hidden) } }
    val listing = loaded?.takeIf { it.dir == dir }

    fun go(to: BrowseLocation, from: File?) {
        cameFrom = from
        path = (to as? BrowseLocation.Dir)?.dir?.path ?: ""
    }

    fun up() {
        val places = roots
        val here = location
        if (places == null || here == null) return onCancel()
        val above = FileBrowse.up(here, places)
        if (above == null) onCancel() else go(above, (here as? BrowseLocation.Dir)?.dir)
    }
    if (!inspection) BackHandler { up() }

    // The answer is checked against the disk once more: a stick may have been pulled meanwhile.
    var finishing by remember { mutableStateOf(false) }
    fun deliver(build: () -> FileBrowserResult?) {
        if (finishing) return
        finishing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { build() }
            finishing = false
            if (result != null) onResult(result) else refresh++
        }
    }

    var grantUnavailable by remember { mutableStateOf(false) }
    val grant = onGrantAccess ?: rememberStorageGrant(access) { grantUnavailable = true }

    val places = roots
    if (places == null || location == null) {
        LoadingPage(title ?: stringResource(modeTitle(mode)), onBack = onCancel, modifier = modifier)
        return
    }
    val ui = browserUi(mode, title, confirmLabel, location, places, listing, selected.map(::File), hidden, access, cameFrom)
    val actions = BrowserActions(
        onBack = ::up,
        onUp = ::up,
        onOpen = { go(BrowseLocation.Dir(it), null) },
        onToggle = { file -> if (!selected.remove(file.path)) selected.add(file.path) },
        onPick = { file -> deliver { file.takeIf { source.describe(it)?.isDirectory == false }?.let { FileBrowserResult.Files(listOf(it)) } } },
        onConfirm = {
            when (mode) {
                is FileBrowserMode.Files -> deliver {
                    val files = selected.map(::File).filter { source.describe(it)?.isDirectory == false }
                    if (files.isEmpty()) null else FileBrowserResult.Files(files)
                }
                is FileBrowserMode.Folder -> if (dir != null) deliver {
                    if (source.describe(dir)?.isDirectory == true) FileBrowserResult.Folder(dir) else null
                }
            }
        },
        onCancel = onCancel,
        onToggleHidden = { hidden = !hidden },
        onGrant = grant,
    )
    FileBrowserContent(ui.copy(grantUnavailable = grantUnavailable, adbCommand = FileBrowse.adbGrantCommand(context.packageName)), actions, modifier)
}

/** The Storage permission dialog below Android 11, Settings' All files access page from it; [onUnavailable] when there is no such page. */
@Composable
private fun rememberStorageGrant(access: StorageAccess, onUnavailable: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val legacy = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    val asker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { !it }) {
            val activity = context.findActivity()
            // No dialog is shown for a permission refused for good: only the app's own settings page gives it back.
            if (activity == null || legacy.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
                if (!startFirst(context, FileBrowse.grantIntents(context))) onUnavailable()
            }
        }
    }
    return {
        if (access == StorageAccess.NEEDS_PERMISSION) asker.launch(legacy)
        else if (!startFirst(context, FileBrowse.grantIntents(context))) onUnavailable()
    }
}

/** Starts the first of [intents] the device has a page for; false when it has none. */
private fun startFirst(context: Context, intents: List<Intent>): Boolean {
    for (intent in intents) {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

// What is on the screen -------------------------------------------------------------------------

/** The browser's state, ready to draw; [browserUi] makes it, previews make it from a fake source. */
internal data class BrowserUi(
    val mode: FileBrowserMode,
    val title: String,
    val subtitle: String?,
    val location: BrowseLocation,
    val roots: List<BrowseRoot>,
    val listing: Listing?,
    val selected: List<File>,
    val showHidden: Boolean,
    val access: StorageAccess,
    val confirm: Confirm?,
    /** The row to put the remote's focus on when the page is entered or a folder opened. */
    val focusKey: String?,
    val grantUnavailable: Boolean = false,
    val adbCommand: String = "",
)

/** The button that answers: its text, whether it can be pressed, and why not. */
internal data class Confirm(val label: String, val enabled: Boolean = true, val note: String? = null)

internal class BrowserActions(
    val onBack: () -> Unit,
    val onUp: () -> Unit,
    val onOpen: (File) -> Unit,
    val onToggle: (File) -> Unit,
    val onPick: (File) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onToggleHidden: () -> Unit,
    val onGrant: () -> Unit,
) {
    companion object {
        val None = BrowserActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

private fun modeTitle(mode: FileBrowserMode): Int = when (mode) {
    is FileBrowserMode.Files -> if (mode.multiple) R.string.file_browser_title_files else R.string.file_browser_title_file
    is FileBrowserMode.Folder -> R.string.file_browser_title_folder
}

// Keys of the rows that are not files or places; the real ones are paths. NOTICE_KEY is the
// notice that stands in a folder's place, whose button is where the remote starts there.
private const val UP_KEY = "\u0000up"
private const val BANNER_KEY = "\u0000banner"
private const val NOTICE_KEY = "\u0000notice"

@Composable
internal fun browserUi(
    mode: FileBrowserMode,
    title: String?,
    confirmLabel: (@Composable (count: Int) -> String)?,
    location: BrowseLocation,
    roots: List<BrowseRoot>,
    listing: Listing?,
    selected: List<File>,
    showHidden: Boolean,
    access: StorageAccess,
    cameFrom: File? = null,
): BrowserUi {
    val count = selected.size
    val confirm = when {
        mode is FileBrowserMode.Files && mode.multiple && count > 0 ->
            Confirm(confirmLabel?.invoke(count) ?: pluralStringResource(R.plurals.file_browser_choose_files, count, count))
        mode is FileBrowserMode.Folder && location is BrowseLocation.Dir -> {
            val readOnly = mode.writable && listing is Listing.Entries && !listing.writable
            Confirm(
                confirmLabel?.invoke(1) ?: stringResource(R.string.file_browser_choose_folder),
                enabled = listing is Listing.Entries && !readOnly,
                note = if (readOnly) stringResource(R.string.file_browser_read_only) else null,
            )
        }
        else -> null
    }
    val subtitle = if (mode is FileBrowserMode.Files && mode.multiple && count > 0) {
        pluralStringResource(R.plurals.file_browser_selected_format, count, count)
    } else null
    return BrowserUi(
        mode, title ?: stringResource(modeTitle(mode)), subtitle, location, roots, listing, selected, showHidden, access,
        confirm, focusKeyOf(mode, location, roots, listing, cameFrom)
    )
}

/** Entering a page or a folder, the focus goes to the row one would most likely press, or back to the one just left. */
private fun focusKeyOf(mode: FileBrowserMode, location: BrowseLocation, roots: List<BrowseRoot>, listing: Listing?, cameFrom: File?): String? =
    when (location) {
        BrowseLocation.Roots ->
            (roots.firstOrNull { cameFrom != null && FileBrowse.same(it.dir, cameFrom) } ?: roots.firstOrNull())?.dir?.path
        is BrowseLocation.Dir -> when (listing) {
            null -> null
            is Listing.Unreadable -> NOTICE_KEY
            is Listing.Entries -> {
                // In a folder chooser the files are only shown; the remote skips them.
                val pressable = listing.entries.filter { it.isDirectory || mode is FileBrowserMode.Files }
                when {
                    listing.entries.isEmpty() -> NOTICE_KEY
                    cameFrom != null && pressable.any { it.file == cameFrom } -> cameFrom.path
                    pressable.isNotEmpty() -> pressable.first().file.path
                    else -> UP_KEY
                }
            }
        }
    }

/** What fills the list instead of rows when a folder has nothing to show. */
private sealed interface Notice {
    data class Empty(val hiddenOff: Boolean) : Notice
    data class Unreadable(val reason: Listing.Reason) : Notice
    data object NoAccess : Notice
}

private sealed interface BrowserItem {
    val key: String

    data object Banner : BrowserItem { override val key = BANNER_KEY }
    data class Up(val toPlaces: Boolean, val parent: File?) : BrowserItem { override val key = UP_KEY }
    data class Place(val root: BrowseRoot) : BrowserItem { override val key get() = root.dir.path }
    data class Entry(val entry: FileEntry) : BrowserItem { override val key get() = entry.file.path }
    data class Message(val notice: Notice) : BrowserItem { override val key = NOTICE_KEY }
}

private fun itemsOf(ui: BrowserUi): List<BrowserItem> = buildList {
    when (val at = ui.location) {
        BrowseLocation.Roots -> {
            if (ui.access != StorageAccess.GRANTED) add(BrowserItem.Banner)
            ui.roots.forEach { add(BrowserItem.Place(it)) }
        }
        is BrowseLocation.Dir -> when (val l = ui.listing) {
            null -> Unit
            is Listing.Unreadable ->
                add(BrowserItem.Message(if (l.reason == Listing.Reason.NO_ACCESS) Notice.NoAccess else Notice.Unreadable(l.reason)))
            is Listing.Entries -> when {
                // Nothing listed where the system hides files proves nothing: ask for the access instead.
                l.entries.isEmpty() && l.limited -> add(BrowserItem.Message(Notice.NoAccess))
                l.entries.isEmpty() -> add(BrowserItem.Message(Notice.Empty(hiddenOff = !ui.showHidden)))
                else -> {
                    if (l.limited && ui.access != StorageAccess.GRANTED) add(BrowserItem.Banner)
                    add(BrowserItem.Up(toPlaces = FileBrowse.up(at, ui.roots) == BrowseLocation.Roots, parent = FileBrowse.parentOf(at.dir)))
                    l.entries.forEach { add(BrowserItem.Entry(it)) }
                }
            }
        }
    }
}

// The page ---------------------------------------------------------------------------------------

@Composable
private fun LoadingPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    if (!LocalInspectionMode.current) BackHandler(onBack = onBack)
    Scaffold(modifier, topBar = { AppTopBar(title, onBack = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
internal fun FileBrowserContent(ui: BrowserUi, actions: BrowserActions, modifier: Modifier = Modifier) {
    val layout = rememberWindowLayout()
    val twoPanes = layout.listDetail
    val margin = layout.margin
    // Right from any row of a wide window goes to the panel's main button, not to whichever is nearest.
    val panelEntry = remember { FocusRequester() }
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(ui.title, subtitle = ui.subtitle, onBack = actions.onBack) {
                val shown = ui.showHidden
                IconButton(onClick = actions.onToggleHidden) {
                    Icon(
                        if (shown) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(if (shown) R.string.file_browser_hidden_hide else R.string.file_browser_hidden_show)
                    )
                }
            }
        },
        bottomBar = { if (!twoPanes && ui.confirm != null) PhoneBar(ui.confirm, actions) },
    ) { padding ->
        if (twoPanes) {
            Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = margin)) {
                Column(Modifier.weight(1f)) {
                    PathLine(ui, tv = true)
                    BrowserList(
                        ui, actions, tv = true, sidePadding = 0.dp,
                        modifier = Modifier.weight(1f).focusProperties { right = panelEntry }.focusRestorer()
                    )
                }
                Spacer(Modifier.width(24.dp))
                SidePanel(ui, actions, panelEntry, Modifier.width(320.dp).fillMaxHeight().padding(vertical = 8.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                PathLine(ui, tv = false, sidePadding = margin)
                BrowserList(ui, actions, tv = false, sidePadding = margin, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Where one is, in words and without typing: "Internal storage › Download › Taildrop". */
@Composable
private fun PathLine(ui: BrowserUi, tv: Boolean, modifier: Modifier = Modifier, sidePadding: Dp = 0.dp) {
    val places = stringResource(R.string.file_browser_places)
    val labels = ui.roots.associate { it.dir.path to rootLabel(it) }
    val text = when (val at = ui.location) {
        BrowseLocation.Roots -> places
        is BrowseLocation.Dir -> {
            val crumb = FileBrowse.breadcrumb(at.dir, ui.roots)
            (listOfNotNull(crumb.root?.let { labels[it.dir.path] }) + crumb.trail).joinToString("  ›  ")
        }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Folder, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (tv) 20.dp else 18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = if (tv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // The end of the path is what tells folders apart, so a long one loses its beginning.
            overflow = TextOverflow.StartEllipsis,
        )
    }
}

@Composable
private fun BrowserList(ui: BrowserUi, actions: BrowserActions, tv: Boolean, sidePadding: Dp, modifier: Modifier = Modifier) {
    val rows = itemsOf(ui)
    val index = rows.indexOfFirst { it.key == ui.focusKey }

    // The remote has no pointer to touch a row with: when a page or a folder opens, the focus is
    // put on the row to press. A touch screen is left alone, or a row would light up unasked.
    // The row asks as it is placed (see ListStart); a new start for every page and every read.
    val start = rememberListStart(Triple(ui.location, ui.focusKey, rows.size))
    // The list opens with that row in view, so it is composed, placed and asking in the first
    // frame the rows are there, not after an effect has scrolled to it.
    val listState = rememberSaveable(ui.location.toString(), ui.focusKey, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = if (start.allowed) index.coerceAtLeast(0) else 0)
    }
    // The second try, for a row that is composed only once the list has been scrolled to it.
    LaunchedEffect(start) {
        if (index < 0 || !start.allowed) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
            withFrameNanos { }
        }
        start.ask()
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 4.dp),
    ) {
        items(rows, key = { it.key }) { item ->
            val target = if (item.key == ui.focusKey) Modifier.tvListStart(start) else Modifier
            when (item) {
                BrowserItem.Banner -> BrowserRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(grantLabel(ui.access)),
                    supporting = stringResource(R.string.file_browser_limited),
                    onClick = actions.onGrant,
                    tv = tv,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    accent = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = target,
                )
                is BrowserItem.Up -> BrowserRow(
                    icon = Icons.Filled.ArrowUpward,
                    title = stringResource(R.string.file_browser_up),
                    supporting = if (item.toPlaces) stringResource(R.string.file_browser_places) else item.parent?.let { dirLabel(it, ui.roots) },
                    onClick = actions.onUp,
                    tv = tv,
                    modifier = target,
                )
                is BrowserItem.Place -> BrowserRow(
                    icon = rootIcon(item.root),
                    title = rootLabel(item.root),
                    supporting = item.root.dir.path,
                    onClick = { actions.onOpen(item.root.dir) },
                    tv = tv,
                    modifier = target,
                )
                is BrowserItem.Entry -> EntryRow(item.entry, ui, actions, tv, target)
                is BrowserItem.Message -> NoticeCard(
                    item.notice, ui, actions, tv,
                    Modifier.fillParentMaxSize().padding(horizontal = 16.dp), target
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FileEntry, ui: BrowserUi, actions: BrowserActions, tv: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val modified = if (entry.modified > 0) {
        DateUtils.formatDateTime(
            context, entry.modified,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
    } else null
    if (entry.isDirectory) {
        BrowserRow(
            icon = Icons.Filled.Folder,
            title = entry.name,
            supporting = modified,
            onClick = { actions.onOpen(entry.file) },
            tv = tv,
            modifier = modifier,
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        )
        return
    }
    val details = listOfNotNull(Formatter.formatShortFileSize(context, entry.size), modified)
        .let { if (it.size == 2) stringResource(R.string.file_browser_file_format, it[0], it[1]) else it.joinToString() }
    when (val mode = ui.mode) {
        is FileBrowserMode.Files -> {
            val checked = entry.file in ui.selected
            BrowserRow(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = entry.name,
                supporting = details,
                onClick = { if (mode.multiple) actions.onToggle(entry.file) else actions.onPick(entry.file) },
                tv = tv,
                modifier = modifier,
                checked = checked.takeIf { mode.multiple },
                container = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                trailing = if (mode.multiple) ({ Checkbox(checked = checked, onCheckedChange = null) }) else null,
            )
        }
        // A folder chooser shows the files so that a folder can be recognised, but they cannot be pressed.
        is FileBrowserMode.Folder -> BrowserRow(
            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            title = entry.name,
            supporting = details,
            onClick = null,
            tv = tv,
            modifier = modifier,
            dim = true,
        )
    }
}

/**
 * One row, one focus stop: pressing it does its one thing. [onClick] null makes it a label;
 * [checked] non-null makes it a check box row, whose box is drawn by [trailing] and is not a
 * stop of its own.
 */
@Composable
private fun BrowserRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    onClick: (() -> Unit)?,
    tv: Boolean,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    accent: Color = MaterialTheme.colorScheme.primary,
    checked: Boolean? = null,
    dim: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (tv) 64.dp else 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                modifier = Modifier.size(if (tv) 32.dp else 24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
    val shape = MaterialTheme.shapes.medium
    when {
        onClick == null -> Surface(modifier, shape = shape, color = container, content = content)
        checked != null -> Surface(
            checked = checked,
            onCheckedChange = { onClick() },
            modifier = modifier.semantics { role = Role.Checkbox },
            shape = shape,
            color = container,
            content = content,
        )
        else -> Surface(onClick = onClick, modifier = modifier, shape = shape, color = container, content = content)
    }
}

/** A folder with nothing to show, or that cannot be shown: what it is, and the one thing to do about it. */
@Composable
private fun NoticeCard(notice: Notice, ui: BrowserUi, actions: BrowserActions, tv: Boolean, modifier: Modifier, button: Modifier) {
    val title: String
    val body: String
    val icon: ImageVector
    val action: String
    val onAction: () -> Unit
    when (notice) {
        is Notice.Empty -> {
            icon = Icons.Filled.FolderOpen
            title = stringResource(R.string.file_browser_empty_title)
            body = stringResource(R.string.file_browser_empty_body) +
                if (notice.hiddenOff) " " + stringResource(R.string.file_browser_empty_hidden) else ""
            action = stringResource(R.string.file_browser_go_up)
            onAction = actions.onUp
        }
        is Notice.Unreadable -> {
            icon = Icons.Filled.Warning
            title = stringResource(R.string.file_browser_unreadable_title)
            body = stringResource(
                if (notice.reason == Listing.Reason.NOT_FOUND) R.string.file_browser_unreadable_gone else R.string.file_browser_unreadable_denied
            )
            action = stringResource(R.string.file_browser_go_up)
            onAction = actions.onUp
        }
        Notice.NoAccess -> {
            icon = Icons.Filled.Lock
            title = stringResource(R.string.file_browser_no_access_title)
            val why = stringResource(
                if (ui.access == StorageAccess.NEEDS_PERMISSION) R.string.file_browser_no_access_storage else R.string.file_browser_no_access_all_files
            )
            // A TV often has no Settings page for the grant: say what to run from a computer instead.
            body = if (ui.grantUnavailable && ui.access == StorageAccess.NEEDS_ALL_FILES) {
                why + " " + stringResource(R.string.file_browser_grant_unavailable_format, ui.adbCommand)
            } else why
            action = stringResource(grantLabel(ui.access))
            onAction = actions.onGrant
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (tv) 56.dp else 48.dp))
            Text(
                title,
                style = if (tv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            HelpText(
                body,
                textAlign = TextAlign.Center,
                style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onAction, modifier = button.heightIn(min = if (tv) 56.dp else 48.dp)) {
                Text(action, style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun grantLabel(access: StorageAccess): Int =
    if (access == StorageAccess.NEEDS_PERMISSION) R.string.file_browser_grant_storage else R.string.file_browser_grant_all_files

/** A folder as the user names it: a place by its label ("Internal storage", not "0"), any other by its name. */
@Composable
private fun dirLabel(dir: File, roots: List<BrowseRoot>): String =
    roots.firstOrNull { FileBrowse.same(it.dir, dir) }?.let { rootLabel(it) } ?: dir.name.ifEmpty { dir.path }

@Composable
private fun rootLabel(root: BrowseRoot): String = when (root.kind) {
    RootKind.PRIMARY -> stringResource(R.string.file_browser_internal)
    RootKind.DOWNLOADS -> stringResource(R.string.file_browser_downloads)
    RootKind.VOLUME -> root.label ?: stringResource(R.string.file_browser_removable)
    RootKind.START -> root.dir.name.ifEmpty { root.dir.path }
}

private fun rootIcon(root: BrowseRoot): ImageVector = when (root.kind) {
    RootKind.PRIMARY -> Icons.Filled.Storage
    RootKind.DOWNLOADS -> Icons.Filled.Download
    RootKind.VOLUME -> if (root.removable) Icons.Filled.Usb else Icons.Filled.Storage
    RootKind.START -> Icons.Filled.Folder
}

/** The confirm and cancel buttons of a narrow window, under the list. */
@Composable
private fun PhoneBar(confirm: Confirm, actions: BrowserActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (confirm.note != null) {
                Text(
                    confirm.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = actions.onCancel) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = actions.onConfirm, enabled = confirm.enabled, modifier = Modifier.weight(1f)) {
                    Text(confirm.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A wide window's actions, to the right of the list: where one is, what is checked, the confirm
 * and cancel buttons. The remote reaches it with Right from any row.
 */
@Composable
private fun SidePanel(ui: BrowserUi, actions: BrowserActions, entry: FocusRequester, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val at = ui.location
            if (at is BrowseLocation.Dir) {
                Text(
                    stringResource(R.string.file_browser_current_folder),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    dirLabel(at.dir, ui.roots),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    at.dir.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val mode = ui.mode
            val hint = when {
                at == BrowseLocation.Roots -> R.string.file_browser_hint_places
                mode is FileBrowserMode.Folder -> R.string.file_browser_hint_folder
                mode is FileBrowserMode.Files && mode.multiple -> R.string.file_browser_hint_files
                else -> R.string.file_browser_hint_file
            }
            if (ui.subtitle != null) {
                Text(ui.subtitle, style = MaterialTheme.typography.titleMedium)
            } else {
                HelpText(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            val canConfirm = ui.confirm?.enabled == true
            if (ui.confirm != null) {
                if (ui.confirm.note != null) {
                    Text(ui.confirm.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = actions.onConfirm,
                    enabled = ui.confirm.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (canConfirm) Modifier.focusRequester(entry) else Modifier),
                ) {
                    Text(ui.confirm.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = actions.onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (canConfirm) Modifier else Modifier.focusRequester(entry)),
            ) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
