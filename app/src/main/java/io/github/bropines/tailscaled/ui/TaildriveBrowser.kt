package io.github.bropines.tailscaled.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.TaildrivePolicyFix
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.DownloadDestination
import io.github.bropines.tailscaled.core.DownloadRequest
import io.github.bropines.tailscaled.core.DownloadSelection
import io.github.bropines.tailscaled.core.DownloadState
import io.github.bropines.tailscaled.core.DriveEntry
import io.github.bropines.tailscaled.core.DriveError
import io.github.bropines.tailscaled.core.DriveKind
import io.github.bropines.tailscaled.core.DriveLaunch
import io.github.bropines.tailscaled.core.DriveLevel
import io.github.bropines.tailscaled.core.DriveLocation
import io.github.bropines.tailscaled.core.DrivePathInput
import io.github.bropines.tailscaled.core.DriveProxy
import io.github.bropines.tailscaled.core.DriveResult
import io.github.bropines.tailscaled.core.DriveWalk
import io.github.bropines.tailscaled.core.LinkNotice
import io.github.bropines.tailscaled.core.NoAppAdvice
import io.github.bropines.tailscaled.core.PathFound
import io.github.bropines.tailscaled.core.ProxyCondition
import io.github.bropines.tailscaled.core.ReceiveFolder
import io.github.bropines.tailscaled.core.TaildriveBrowse
import io.github.bropines.tailscaled.core.TaildriveDownloadFolder
import io.github.bropines.tailscaled.core.TaildriveDownloads
import io.github.bropines.tailscaled.core.TaildriveLinkChoice
import io.github.bropines.tailscaled.core.TaildriveOpenPlan
import io.github.bropines.tailscaled.core.WalkTotals
import io.github.bropines.tailscaled.core.Walked
import io.github.bropines.tailscaled.core.formatFileSize
import io.github.bropines.tailscaled.core.listEntries
import io.github.bropines.tailscaled.core.pathLine
import io.github.bropines.tailscaled.core.tryStartActivity
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The other devices' Taildrive shares as a page to walk with a remote: devices, their
 * shares, folders, files. The list logic is core/TaildriveBrowse.kt; this file is the page and
 * the states it passes through. Downloading is ui/TaildriveDownloadUi.kt and the path under the
 * title ui/TaildrivePathBar.kt; this page puts them together.
 *
 * Built for a D-pad first. Every row is one focus stop and OK on it does the whole thing — enters a
 * folder, or hands a file to another app; Back climbs one level and leaves at the top. A file's row
 * has a second stop, its download button, one press to the right. Selecting several, which a touch
 * screen starts with a long press, has a button in the bar as well, so nothing is out of reach of
 * four arrows, OK and Back.
 */

/** How long a page that has just asked for the proxy waits for it to come up. */
private const val PROXY_WAIT_MS = 10_000L
private const val PROXY_POLL_MS = 500L

/** What the body of the page shows. */
internal sealed interface BrowserPhase {
    data object Loading : BrowserPhase

    /** The proxy was asked to start and has not answered yet. */
    data object StartingProxy : BrowserPhase

    data class Listed(val entries: List<DriveEntry>) : BrowserPhase

    /** Includes the proxy being off and the daemon being stopped: each has its own face. */
    data class Failed(val error: DriveError) : BrowserPhase
}

/** What is asked of the user after a file was handed over and nothing took it. */
internal sealed interface OpenNotice {
    /**
     * No installed app takes the file; [address] is where any Tailscale client can fetch it. With
     * [openInBrowser] set a web browser does take the link, and it is for the person to say whether that will do.
     */
    class NoApp(
        val name: String,
        val address: String,
        val openInBrowser: (() -> Unit)? = null,
        /** What to say of it: a player to install for media, for anything else that nothing here opens it. */
        val advice: NoAppAdvice = NoAppAdvice.INSTALL_PLAYER,
    ) : OpenNotice

    data class Failed(val name: String, val detail: String) : OpenNotice
}

/** What the page can be asked to do; the previews pass [None]. */
internal class DriveBrowserActions(
    val onOpen: (DriveEntry) -> Unit,
    val onUp: () -> Unit,
    val onRetry: () -> Unit,
    val onEnableProxy: () -> Unit,
    val onDaemonStarted: () -> Unit,
    val onDismissNotice: () -> Unit,
    /** The way into the policy editor, for a device the policy does not let in; null where it is not offered. */
    val onFixPolicy: (() -> Unit)? = null,
    /** Files open through the link on the local proxy before the document road: the person's switch, in the bar. */
    val viaLink: Boolean = false,
    /** Null where the switch is not shown (the previews of states that have no bar to show it in). */
    val onToggleLink: (() -> Unit)? = null,
    /** A file row's own button. */
    val onDownload: (DriveEntry) -> Unit = {},
    /** A long press on a row: selection mode, with that row in it. */
    val onLongPress: (DriveEntry) -> Unit = {},
    /** A row pressed in selection mode. */
    val onToggle: (DriveEntry) -> Unit = {},
    /** The bar's Select, for a device without a long press. */
    val onSelect: () -> Unit = {},
    /** Select all, or clear when all are. */
    val onSelectAll: () -> Unit = {},
    val onDownloadSelected: () -> Unit = {},
    val onLeaveSelection: () -> Unit = {},
    /** A name of the path pressed: that level. */
    val onGo: (DriveLocation) -> Unit = {},
    val onEditPath: () -> Unit = {},
    val onPathText: (String) -> Unit = {},
    val onPathGo: () -> Unit = {},
    val onPathClose: () -> Unit = {},
    val panel: DownloadPanelActions = DownloadPanelActions(),
) {
    companion object {
        val None = DriveBrowserActions({}, {}, {}, {}, {}, {})
    }
}

/** What the page shows besides the list; the previews pass what their state needs. */
internal class BrowserExtras(
    /** The names selected in this folder; null outside selection mode. */
    val selection: Set<String>? = null,
    /** How deep the list of devices is: 1 under a single tailnet. */
    val top: Int = 1,
    /** The path as a field; null while it is the path's names. */
    val pathEdit: PathEdit? = null,
    /** The download under the list and how many wait behind it. */
    val panel: Pair<DownloadState, Int>? = null,
    /** The panel's folder is the default already, or was just made it. */
    val panelKept: Boolean = false,
    val snackbar: SnackbarHostState? = null,
    /** The link switch is on and the proxy is not there. */
    val linkDown: Boolean = false,
    /** A new value puts the remote back on the list's row. */
    val refocus: Int = 0,
)

private val LocationSaver = Saver<DriveLocation, ArrayList<String>>(
    save = { ArrayList(it.segments) },
    restore = { DriveLocation(it) }
)

/** Selection mode across a rotation: the names, or nothing saved for "not selecting". */
private val SelectionSaver = Saver<Set<String>?, ArrayList<String>>(
    save = { it?.let(::ArrayList) },
    restore = { it.toSet() }
)

/** The question before a download with folders: what was chosen, and where. */
private class FolderQuestion(val parent: DriveLocation, val entries: List<DriveEntry>) {
    val folders: List<String> = entries.filter { it.isFolder }.map { it.name }
}

/**
 * The page. A full-screen composable, not an Activity: the host puts it where it likes and
 * supplies two callbacks.
 *
 * - [onClose] runs when Back (or the arrow) is used at the top level, the list of devices.
 *   Deeper, Back goes one level up and the callback is not called.
 * - [onEnableProxy] runs from the "Turn it on" button of the proxy-off state, and wherever the
 *   page offers the proxy for the link road. It should switch the Taildrive proxy on for the
 *   profile and tell the service to apply it (what Settings -> Taildrive does); the page then
 *   waits a few seconds for the proxy to answer and carries on by itself. Returning from another
 *   screen, a settings page say, also re-checks.
 *
 * [accountId] is the profile to browse as; the active one by default.
 */
@Composable
fun TaildriveBrowserPage(
    onClose: () -> Unit,
    onEnableProxy: () -> Unit,
    modifier: Modifier = Modifier,
    accountId: String? = null,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val account = remember(accountId) { accountId ?: AccountManager.getActiveAccount(appContext).id }
    val browse = remember(account) { TaildriveBrowse.forAccount(appContext, account) }
    val scope = rememberCoroutineScope()

    var location by rememberSaveable(stateSaver = LocationSaver) { mutableStateOf(DriveLocation.ROOT) }
    // How deep the list of devices is: 1 under a single tailnet, which is entered for the user.
    var top by rememberSaveable { mutableIntStateOf(1) }
    var phase by remember { mutableStateOf<BrowserPhase>(BrowserPhase.Loading) }
    var focusName by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<OpenNotice?>(null) }
    var loadKey by remember { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }
    // The person's own switch for the link road (see TaildriveOpenPlan); the document road is the default.
    var viaLink by remember { mutableStateOf(DriveProxy.openViaLink(appContext, account)) }
    // The proxy as last seen, for the switch's face; null until it has been looked at.
    var proxy by remember { mutableStateOf<ProxyCondition?>(null) }
    var askProxy by remember { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf<Set<String>?>(null) }
    var question by remember { mutableStateOf<FolderQuestion?>(null) }
    var pathEdit by remember { mutableStateOf<PathEdit?>(null) }
    var refocus by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val folderAsk = rememberDownloadFolderAsk()
    val downloads by TaildriveDownloads.downloads.collectAsState()

    // Every load: is the daemon up and is the policy against it, then the listing. A new key cancels
    // the load in flight, so a slow share never overwrites the folder entered after it. The proxy is
    // no part of it — a file reaches a player as a document of the app's own — except for a page that
    // has just asked for it, which waits until it answers so that the next file finds it.
    LaunchedEffect(loadKey) {
        val waiting = starting
        starting = false
        phase = if (waiting) BrowserPhase.StartingProxy else BrowserPhase.Loading
        val wanted = location
        val (next, shown) = withContext(Dispatchers.IO) {
            // The service applies the switch a moment after the callback has run.
            var waited = 0L
            while (waiting && waited < PROXY_WAIT_MS && !DriveProxy.read(appContext, account).let { it.enabled && it.isListening() }) {
                delay(PROXY_POLL_MS)
                waited += PROXY_POLL_MS
            }
            val blocked = TaildriveBrowse.listingBlock(appContext)
            if (blocked != null) return@withContext BrowserPhase.Failed(blocked) to wanted
            when (val result = browse.list(wanted)) {
                is DriveResult.Ok -> BrowserPhase.Listed(result.value.entries) to result.value.location
                is DriveResult.Err -> BrowserPhase.Failed(result.error) to wanted
            }
        }
        if (wanted.isRoot && next is BrowserPhase.Listed) top = shown.depth
        // The root was asked for and the tailnet's devices came back: that is where we are now.
        location = shown
        phase = next
    }
    // The proxy, for the link switch's face: looked at with every load, apart from it — a proxy that
    // is on and silent takes a moment to say so, and the listing does not wait for that.
    LaunchedEffect(loadKey) {
        proxy = withContext(Dispatchers.IO) { TaildriveBrowse.proxyCondition(DriveProxy.read(appContext, account)) }
    }

    fun go(to: DriveLocation, focus: String? = null) {
        notice = null
        selection = null
        pathEdit = null
        location = to
        focusName = focus
        loadKey++
    }

    val up: () -> Unit = {
        val parent = location.parent
        if (parent != null && location.depth > top) go(parent, focus = location.name) else onClose()
    }
    BackHandler(onBack = up)
    // Back in selection mode leaves it, before it climbs anywhere.
    BackHandler(enabled = selection != null) { selection = null }

    // Back from another app or a settings page: whatever was missing may be there now.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentPhase by rememberUpdatedState(phase)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val current = currentPhase
            if (current is BrowserPhase.Failed && current.error.isFixableOutside) loadKey++
            else scope.launch { proxy = withContext(Dispatchers.IO) { TaildriveBrowse.proxyCondition(DriveProxy.read(appContext, account)) } }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The way into the policy editor, looked up only when a refusal wants it: a read of preferences.
    var fix by remember { mutableStateOf<Intent?>(null) }
    val refused = (phase as? BrowserPhase.Failed)?.error == DriveError.NotPermitted
    LaunchedEffect(refused) {
        fix = if (refused) withContext(Dispatchers.IO) {
            TaildrivePolicyFix.profileFor(appContext)?.let { TaildrivePolicyFix.intent(appContext, it) }
        } else null
    }

    val enableProxy: () -> Unit = {
        starting = true
        onEnableProxy()
        loadKey++
    }

    /** A short line at the bottom; with [action], a button that does it. In the app's language: the page's context has it. */
    fun say(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = action, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun linkNotice(notice: LinkNotice) {
        val text = context.getString(
            when (notice) {
                LinkNotice.PROXY_OFF -> R.string.taildrive_link_opened_directly
                LinkNotice.PROXY_SILENT -> R.string.taildrive_link_opened_directly_silent
                LinkNotice.PROXY_OFF_NO_SEEK -> R.string.taildrive_link_opened_no_seek
                LinkNotice.PROXY_SILENT_NO_SEEK -> R.string.taildrive_link_opened_no_seek_silent
            }
        )
        if (notice.offersProxy) say(text, context.getString(R.string.taildrive_link_turn_on_proxy), enableProxy) else say(text)
    }

    fun setLink(on: Boolean) {
        viaLink = on
        DriveProxy.setOpenViaLink(appContext, account, on)
        say(context.getString(if (on) R.string.taildrive_link_on else R.string.taildrive_link_off))
    }

    // Downloads: where to, then off they go (core/TaildriveDownloads.kt). The page may be left at once.
    fun startDownload(parent: DriveLocation, entries: List<DriveEntry>, walked: Walked?) {
        scope.launch {
            val where = withContext(Dispatchers.IO) { TaildriveDownloadFolder.destination(appContext) }
            val begin = { folder: ReceiveFolder, picked: Boolean ->
                TaildriveDownloads.start(appContext, DownloadRequest(account, parent, entries, folder, picked, walked))
                Unit
            }
            when (where) {
                is DownloadDestination.Default -> begin(where.folder, false)
                is DownloadDestination.Ask -> {
                    if (where.lost) sayFolderLost(context)
                    folderAsk.ask { begin(it, true) }
                }
            }
        }
    }

    fun download(entries: List<DriveEntry>) {
        if (entries.isEmpty()) return
        if (selection != null) {
            selection = null
            // The bar's Download goes with selection mode: the remote goes back to the list.
            focusName = entries.first().name
            refocus++
        }
        if (DownloadSelection.asks(entries)) question = FolderQuestion(location, entries)
        else startDownload(location, entries, null)
    }

    // A typed path: looked for level by level (DrivePathInput), and gone to only when it is there.
    fun lookFor(text: String) {
        val tailnet = location.segments.firstOrNull()
        val base = if (tailnet != null) DriveLocation(listOf(tailnet)) else DriveLocation.ROOT
        val names = DrivePathInput.parse(text, tailnet)
        pathEdit = PathEdit(text, looking = true)
        scope.launch {
            val found = withContext(Dispatchers.IO) { DrivePathInput.descend(base, names) { browse.listEntries(it) } }
            if (pathEdit?.text != text) return@launch
            fun shown(at: DriveLocation) = DrivePathInput.format(at).ifEmpty { context.getString(R.string.taildrive_browser_level_devices) }
            when (found) {
                is PathFound.Folder -> go(found.location)
                is PathFound.File -> go(found.folder, focus = found.name)
                is PathFound.Missing -> pathEdit = PathEdit(
                    text,
                    problem = context.getString(R.string.taildrive_path_missing, found.missing, shown(found.deepest)),
                    deepest = found.deepest,
                    deepestLabel = shown(found.deepest)
                )
                is PathFound.Failed -> pathEdit = PathEdit(
                    text,
                    problem = context.getString(R.string.taildrive_path_failed, shown(found.at)),
                    deepest = found.at.parent?.takeIf { it.depth >= base.depth },
                    deepestLabel = found.at.parent?.takeIf { it.depth >= base.depth }?.let(::shown)
                )
            }
        }
    }

    val listed = (phase as? BrowserPhase.Listed)?.entries.orEmpty()
    val actions = DriveBrowserActions(
        onOpen = { entry ->
            if (entry.isFolder) go(location.child(entry.name))
            else scope.launch {
                val file = location.child(entry.name)
                val prepared = withContext(Dispatchers.IO) { TaildriveBrowse.prepareOpen(appContext, account, file) }
                val spec = (prepared as? DriveResult.Ok)?.value ?: return@launch
                proxy = spec.proxy
                // The switch asks for the link; without the proxy the file goes directly, and the page says why.
                val decision = TaildriveLinkChoice.decide(viaLink, spec.proxy, Build.VERSION.SDK_INT, TaildriveOpenPlan.isMedia(entry.kind))
                notice = when (val launched = spec.launch(context, decision.useLink)) {
                    DriveLaunch.Started -> {
                        decision.notice?.let(::linkNotice)
                        null
                    }
                    DriveLaunch.NoApp -> OpenNotice.NoApp(entry.name, spec.tailnetUrl, advice = spec.noAppAdvice)
                    // Nothing but a browser takes it: not opened until the person says so.
                    is DriveLaunch.OnlyBrowser -> OpenNotice.NoApp(entry.name, spec.tailnetUrl, openInBrowser = {
                        notice = null
                        (launched.openAnyway() as? DriveLaunch.Failed)?.let { notice = OpenNotice.Failed(entry.name, it.detail) }
                    })
                    is DriveLaunch.Failed -> OpenNotice.Failed(entry.name, launched.detail)
                }
            }
        },
        onUp = up,
        onRetry = { loadKey++ },
        onEnableProxy = enableProxy,
        onDaemonStarted = { loadKey++ },
        onDismissNotice = { notice = null },
        onFixPolicy = fix?.let { intent -> { context.tryStartActivity(intent); Unit } },
        viaLink = viaLink,
        onToggleLink = {
            val on = !viaLink
            // Turning it on with the proxy off asks to turn the proxy on: a link needs it.
            if (TaildriveLinkChoice.asksToEnable(on, if (DriveProxy.read(appContext, account).enabled) ProxyCondition.READY else ProxyCondition.OFF)) askProxy = true
            else setLink(on)
        },
        onDownload = { download(listOf(it)) },
        onLongPress = { entry -> selection = (selection ?: emptySet()) + entry.name },
        onToggle = { entry ->
            val now = selection ?: emptySet()
            selection = if (entry.name in now) now - entry.name else now + entry.name
        },
        onSelect = { selection = emptySet() },
        onSelectAll = {
            val all = listed.map { it.name }.toSet()
            selection = if (selection == all) emptySet() else all
        },
        onDownloadSelected = { download(listed.filter { it.name in selection.orEmpty() }) },
        onLeaveSelection = { selection = null },
        onGo = { if (it != location) go(it) },
        onEditPath = { pathEdit = PathEdit(DrivePathInput.format(location)) },
        onPathText = { pathEdit = PathEdit(it) },
        onPathGo = { pathEdit?.let { lookFor(it.text) } },
        onPathClose = { pathEdit = null },
        panel = DownloadPanelActions(
            onCancel = TaildriveDownloads::cancel,
            onClose = TaildriveDownloads::dismiss,
            onOpenFolder = { state -> TaildriveDownloadFolder.viewIntent(context, state.folder)?.let { context.tryStartActivity(it) } },
            onKeep = { state -> keepFolder(context, state); refocus++ },
        ),
    )
    val panel = panelOf(downloads)
    // Read with every recomposition the panel's state brings: a preference, in memory.
    val kept = panel?.let { remember(it.first.id, it.first.stage, refocus) { isDefaultFolder(appContext, it.first) } } ?: false
    val extras = BrowserExtras(
        selection = selection,
        top = top,
        pathEdit = pathEdit,
        panel = panel,
        panelKept = kept,
        snackbar = snackbar,
        linkDown = viaLink && proxy != null && proxy != ProxyCondition.READY,
        refocus = refocus,
    )
    TaildriveBrowserContent(location, phase, focusName, notice, actions, modifier, extras)

    question?.let { q ->
        var totals by remember(q) { mutableStateOf(WalkTotals()) }
        var walked by remember(q) { mutableStateOf<Walked?>(null) }
        // The folders are counted while the question is up; every answer works without waiting for it.
        LaunchedEffect(q) {
            val result = withContext(Dispatchers.IO) { DriveWalk.collect(q.parent, q.folders, { browse.listEntries(it) }) { totals = it } }
            walked = result
            totals = result.totals
        }
        FolderQuestionDialog(
            folders = q.folders.size,
            totals = totals,
            onAll = {
                question = null
                startDownload(q.parent, q.entries, walked)
            },
            onFilesOnly = if (DownloadSelection.hasFiles(q.entries)) ({
                question = null
                startDownload(q.parent, q.entries.filter { !it.isFolder }, null)
            }) else null,
            onCancel = { question = null }
        )
    }
    if (askProxy) {
        val title = stringResource(R.string.taildrive_open_via_link)
        val text = stringResource(R.string.taildrive_link_needs_proxy)
        val turnOn = stringResource(R.string.taildrive_link_turn_on)
        val cancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { askProxy = false },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    askProxy = false
                    setLink(true)
                    enableProxy()
                }) { Text(turnOn) }
            },
            dismissButton = { TextButton(onClick = { askProxy = false }) { Text(cancel) } }
        )
    }
}

/** Whether the user can put this right somewhere else and come back. */
private val DriveError.isFixableOutside: Boolean
    get() = this == DriveError.ProxyOff || this == DriveError.NotConnected || this == DriveError.ProxyNotListening ||
        this == DriveError.NotPermitted

/**
 * The page for a given state, with nothing live behind it: the previews draw this, and the
 * page above feeds it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaildriveBrowserContent(
    location: DriveLocation,
    phase: BrowserPhase,
    focusName: String?,
    notice: OpenNotice?,
    actions: DriveBrowserActions,
    modifier: Modifier = Modifier,
    extras: BrowserExtras = BrowserExtras(),
) {
    val layout = rememberWindowLayout()
    val selection = extras.selection
    val listed = (phase as? BrowserPhase.Listed)?.entries.orEmpty()
    // Selecting is for what is inside a share: files and folders, not devices or shares.
    val selectable = location.level == DriveLevel.FOLDER && listed.isNotEmpty()
    // Inside a device the line under the title is the path, every name of it a button.
    val crumbs = selection == null && location.depth > extras.top
    val subtitle = when {
        // The root is the list of devices of the one tailnet, not yet entered while the page loads
        // or fails; it is a list of tailnets only when it has been read and holds several.
        location.isRoot && phase is BrowserPhase.Listed -> stringResource(R.string.taildrive_browser_level_tailnets)
        location.depth <= 1 -> stringResource(R.string.taildrive_browser_level_devices)
        // The tailnet's own name is the same on every row and longer than the rest of the line.
        else -> pathLine(location.segments.drop(1), if (layout.isPhone) 34 else 80)
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                AppTopBar(
                    title = if (selection != null) pluralStringResource(R.plurals.taildrive_selected, selection.size, selection.size)
                    else stringResource(R.string.taildrive_browser_title),
                    subtitle = subtitle,
                    subtitleContent = if (crumbs) ({ PathCrumbs(location, extras.top, actions.onGo, actions.onEditPath) }) else null,
                    onBack = if (selection != null) actions.onLeaveSelection else actions.onUp,
                    actions = {
                        // One place for Select and Select all, so the remote that pressed the first is on the second.
                        if (selection != null || (selectable && isTv)) {
                            val all = selection != null && selection.size == listed.size
                            BarAction(
                                label = stringResource(
                                    when {
                                        selection == null -> R.string.taildrive_select
                                        all -> R.string.taildrive_select_none
                                        else -> R.string.taildrive_select_all
                                    }
                                ),
                                icon = when {
                                    selection == null -> Icons.Default.Checklist
                                    all -> Icons.Default.Deselect
                                    else -> Icons.Default.SelectAll
                                },
                                onClick = if (selection == null) actions.onSelect else actions.onSelectAll
                            )
                        }
                        if (selection != null) {
                            TextButton(onClick = actions.onDownloadSelected, enabled = selection.isNotEmpty()) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.taildrive_download))
                            }
                        } else {
                            IconButton(onClick = actions.onEditPath) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.taildrive_path_edit))
                            }
                            actions.onToggleLink?.let { toggle -> LinkSwitch(actions.viaLink, extras.linkDown, toggle) }
                        }
                    }
                )
                extras.pathEdit?.let { edit ->
                    PathEditor(
                        edit,
                        onText = actions.onPathText,
                        onGo = actions.onPathGo,
                        onDeepest = actions.onGo,
                        onClose = actions.onPathClose,
                        modifier = Modifier.readableWidth()
                    )
                }
            }
        },
        bottomBar = {
            extras.panel?.let { (state, waiting) ->
                DownloadPanel(
                    state, waiting, extras.panelKept, actions.panel,
                    Modifier.navigationBarsPadding().readableWidth().padding(horizontal = layout.margin, vertical = 8.dp)
                )
            }
        },
        snackbarHost = { extras.snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ReadableWidth {
                when (phase) {
                    BrowserPhase.Loading -> Busy(stringResource(R.string.taildrive_browser_loading))
                    BrowserPhase.StartingProxy -> Busy(stringResource(R.string.taildrive_browser_proxy_starting))
                    is BrowserPhase.Listed -> Entries(location, phase.entries, focusName, selection, extras.refocus, actions)
                    is BrowserPhase.Failed -> Failure(phase.error, actions)
                }
            }
        }
    }
    when (notice) {
        null -> Unit
        is OpenNotice.NoApp -> NoAppDialog(notice, actions.onDismissNotice)
        is OpenNotice.Failed -> FailedDialog(notice, actions.onDismissNotice)
    }
}

/** A word on a television, where a bare icon says little from the sofa; an icon elsewhere. */
@Composable
private fun BarAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    if (isTv) {
        TextButton(onClick = onClick) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(label)
        }
    } else {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

/**
 * The link switch, with its name on a long press. On while the proxy is not there ([down]) it
 * says so: a crossed link, not in the accent colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSwitch(on: Boolean, down: Boolean, onToggle: () -> Unit) {
    val name = stringResource(if (on && down) R.string.taildrive_link_proxy_off_desc else R.string.taildrive_open_via_link)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState()
    ) {
        IconToggleButton(checked = on, onCheckedChange = { onToggle() }) {
            Icon(
                if (on && down) Icons.Default.LinkOff else Icons.Default.Link,
                contentDescription = name,
                tint = if (on && !down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------------------------

@Composable
private fun Entries(
    location: DriveLocation,
    entries: List<DriveEntry>,
    focusName: String?,
    selection: Set<String>?,
    refocus: Int,
    actions: DriveBrowserActions,
) {
    if (entries.isEmpty()) {
        val devices = location.depth <= 1
        State(
            icon = if (devices) Icons.Default.Devices else Icons.Default.FolderOpen,
            title = stringResource(
                when {
                    devices -> R.string.taildrive_browser_empty_devices
                    location.depth == 2 -> R.string.taildrive_browser_empty_shares
                    else -> R.string.taildrive_browser_empty_folder
                }
            ),
            help = if (devices) stringResource(R.string.taildrive_browser_empty_devices_help) else null,
            // What a list of devices or of a device's shares holds changes on the other devices'
            // side: looking again is the one thing to do here. A folder's own emptiness is final.
            actionLabel = if (location.depth <= 2) stringResource(R.string.action_retry) else null,
            onAction = if (location.depth <= 2) actions.onRetry else null
        )
        return
    }

    val layout = rememberWindowLayout()
    // Files and folders inside a share can be downloaded; devices and shares are places.
    val inShare = location.level == DriveLevel.FOLDER
    // The folder just left when going up, so the list is where it was; the first row otherwise.
    val target = remember(entries, focusName) { entries.indexOfFirst { it.name == focusName }.coerceAtLeast(0) }
    // The row asks for the remote as it is placed (see ListStart); a touch screen has none to give.
    // A bar button that went away with selection mode asks again ([refocus]).
    val start = rememberListStart(Triple(entries, focusName, refocus))
    // The list opens with that row in view, so it is composed, placed and asking in the first
    // frame the rows are there, not after an effect has scrolled to it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (start.allowed) target else 0)
    // The second try, for a row that is composed only once the list has been scrolled to it.
    LaunchedEffect(start) {
        if (!start.allowed) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            listState.scrollToItem(target)
            withFrameNanos { }
        }
        start.ask()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = layout.margin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.name }) { entry ->
            val selecting = selection != null
            EntryRow(
                entry,
                selected = selection?.contains(entry.name),
                onClick = { if (selecting) actions.onToggle(entry) else actions.onOpen(entry) },
                onLongClick = if (inShare) ({ if (selecting) actions.onToggle(entry) else actions.onLongPress(entry) }) else null,
                onDownload = if (inShare && !selecting && !entry.isFolder) ({ actions.onDownload(entry) }) else null,
                modifier = if (entry.name == entries[target].name) Modifier.tvListStart(start) else Modifier
            )
        }
    }
}

/**
 * A row: its main part is one stop for the D-pad, and OK does what the row says — enters a
 * folder, opens a file, or in selection mode ([selected] not null) checks it. A file inside a
 * share has its download button at the end, a stop of its own one press to the right. A long
 * press ([onLongClick]) starts selection mode where there is one to start.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: DriveEntry,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val meta = remember(entry, locale) { entryMeta(entry, locale) }
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val source = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = if (selected == true) colors.secondaryContainer else colors.surfaceContainerHigh
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier
                    .weight(1f)
                    .tvFocusRing(source, shape)
                    .clip(shape)
                    .combinedClickable(
                        interactionSource = source,
                        indication = LocalIndication.current,
                        role = if (selected != null) Role.Checkbox else Role.Button,
                        onLongClick = onLongClick,
                        onClick = onClick
                    )
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = if (onDownload != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KindIcon(entry.kind)
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (meta != null) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when {
                    // The box shows the state; the row is what is pressed.
                    selected != null -> Checkbox(checked = selected, onCheckedChange = null)
                    entry.isFolder -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
                }
            }
            if (onDownload != null) {
                IconButton(onClick = onDownload, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.taildrive_download_file, entry.name), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Size and date of a file, the date of a folder; nothing for a device or a share, which have neither. */
private fun entryMeta(entry: DriveEntry, locale: Locale): String? {
    val date = entry.modified.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(it)) }
    return when (entry.kind) {
        DriveKind.TAILNET, DriveKind.DEVICE, DriveKind.SHARE -> null
        DriveKind.FOLDER -> date
        else -> listOfNotNull(entry.size.takeIf { it > 0 }?.let(::formatFileSize), date).joinToString(" • ").ifEmpty { null }
    }
}

@Composable
private fun KindIcon(kind: DriveKind) {
    val icon: ImageVector = when (kind) {
        DriveKind.TAILNET -> Icons.Default.Language
        DriveKind.DEVICE -> Icons.Default.Computer
        DriveKind.SHARE -> Icons.Default.FolderShared
        DriveKind.FOLDER -> Icons.Default.Folder
        DriveKind.VIDEO -> Icons.Default.Movie
        DriveKind.AUDIO -> Icons.Default.MusicNote
        DriveKind.IMAGE -> Icons.Default.Image
        DriveKind.SUBTITLES -> Icons.Default.Subtitles
        DriveKind.TEXT -> Icons.Default.Description
        DriveKind.PDF -> Icons.Default.PictureAsPdf
        DriveKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    // Places, what plays, and the rest: three colours, so a column of names reads at a glance.
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        DriveKind.TAILNET, DriveKind.DEVICE, DriveKind.SHARE, DriveKind.FOLDER -> colors.primaryContainer to colors.onPrimaryContainer
        DriveKind.VIDEO, DriveKind.AUDIO, DriveKind.IMAGE -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Box(Modifier.size(44.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// The other states
// ---------------------------------------------------------------------------------------------

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The error or the proxy-off face of the page, with the one thing to do about it. */
@Composable
private fun Failure(error: DriveError, actions: DriveBrowserActions) {
    when (error) {
        // The shared "daemon stopped" state starts the service itself and calls back when it answers.
        DriveError.NotConnected -> {
            // The shared state has a Start button and no way to ask for it the focus: a remote would
            // have nothing under it until a first arrow. Entering the page puts it on the button.
            val entry = remember { FocusRequester() }
            if (isTv && !LocalInspectionMode.current) LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { entry.requestFocus() }
            }
            Box(Modifier.fillMaxSize().focusRequester(entry).focusGroup()) {
                DaemonStoppedState(onStarted = actions.onDaemonStarted)
            }
        }
        DriveError.ProxyOff -> State(
            icon = Icons.Default.Lan,
            title = stringResource(R.string.taildrive_browser_proxy_off),
            help = stringResource(R.string.taildrive_browser_proxy_off_help),
            actionLabel = stringResource(R.string.taildrive_browser_proxy_turn_on),
            onAction = actions.onEnableProxy
        )
        // The policy: what to change and, where the console is, the way to change it; elsewhere, to look again.
        DriveError.NotPermitted -> State(
            icon = Icons.Default.Block,
            title = stringResource(R.string.taildrive_browser_error_not_permitted),
            help = stringResource(R.string.taildrive_browser_error_not_permitted_help),
            actionLabel = stringResource(if (actions.onFixPolicy != null) R.string.taildrive_fix_policy else R.string.action_retry),
            onAction = actions.onFixPolicy ?: actions.onRetry
        )
        else -> {
            val (title, help) = when (error) {
                DriveError.NoAnswer -> R.string.taildrive_browser_error_no_answer to R.string.taildrive_browser_error_no_answer_help
                DriveError.Unreachable -> R.string.taildrive_browser_error_unreachable to R.string.taildrive_browser_error_unreachable_help
                DriveError.AccessDenied -> R.string.taildrive_browser_error_denied to R.string.taildrive_browser_error_denied_help
                DriveError.NotFound -> R.string.taildrive_browser_error_not_found to R.string.taildrive_browser_error_not_found_help
                DriveError.Timeout -> R.string.taildrive_browser_error_timeout to R.string.taildrive_browser_error_timeout_help
                DriveError.ProxyNotListening -> R.string.taildrive_browser_error_proxy to R.string.taildrive_browser_error_proxy_help
                else -> R.string.taildrive_browser_error_failed to null
            }
            State(
                icon = Icons.Default.ErrorOutline,
                title = stringResource(title),
                help = help?.let { stringResource(it) } ?: (error as? DriveError.Failed)?.detail,
                actionLabel = stringResource(R.string.action_retry),
                onAction = actions.onRetry
            )
        }
    }
}

/**
 * An icon, one line, its explanation folded under it and at most one button, which takes the
 * focus: OK on a remote is then "do the thing", the same as on a row.
 */
@Composable
private fun State(
    icon: ImageVector,
    title: String,
    help: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val requester = remember { FocusRequester() }
    if (onAction != null && !LocalInspectionMode.current) LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (help != null) {
            Spacer(Modifier.height(8.dp))
            HelpText(help, Modifier.widthIn(max = 480.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.focusRequester(requester)) { Text(actionLabel) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// When nothing takes the file
// ---------------------------------------------------------------------------------------------

/**
 * Says plainly that no app takes the file, and on a second step shows the address it can be
 * fetched from. A dialog is a window with its own composition: the words are resolved here and
 * passed in, so they follow the app's language and not the system's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoAppDialog(notice: OpenNotice.NoApp, onDismiss: () -> Unit) {
    var showAddress by remember { mutableStateOf(false) }
    val browserOnly = notice.openInBrowser != null
    val labels = NoticeLabels(
        title = stringResource(
            when {
                showAddress -> R.string.taildrive_browser_address_title
                browserOnly -> R.string.taildrive_open_browseronly_title
                else -> R.string.taildrive_browser_noapp_title
            }
        ),
        body = stringResource(
            when {
                browserOnly -> R.string.taildrive_open_browseronly_body
                notice.advice == NoAppAdvice.NOTHING_OPENS -> R.string.taildrive_browser_noapp_other_body
                else -> R.string.taildrive_browser_noapp_body
            }
        ),
        openInBrowser = stringResource(R.string.taildrive_open_in_browser),
        addressHelp = stringResource(R.string.taildrive_browser_address_help),
        showAddress = stringResource(R.string.taildrive_browser_noapp_show_address),
        close = stringResource(R.string.action_close),
        qrDescription = stringResource(R.string.qr_image),
        qrTooLong = stringResource(R.string.qr_too_long),
    )
    // The platform's own width is a narrow column on a television: the address, its code and the
    // buttons under them are taller than the screen there, and the buttons were cut off. The
    // width Material gives a dialog puts the code beside the text, which fits.
    AppBasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !isTv)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            NoAppCard(
                notice.name, notice.address, showAddress, labels,
                onShowAddress = { showAddress = true },
                onClose = onDismiss,
                onOpenInBrowser = notice.openInBrowser
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FailedDialog(notice: OpenNotice.Failed, onDismiss: () -> Unit) {
    val title = stringResource(R.string.taildrive_browser_open_failed_title)
    val close = stringResource(R.string.action_close)
    AppBasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(notice.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(notice.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton(close, onDismiss, focused = true, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/** The words of [NoAppCard], already in the user's language. */
internal class NoticeLabels(
    val title: String,
    val body: String,
    val addressHelp: String,
    val showAddress: String,
    val close: String,
    val qrDescription: String,
    val qrTooLong: String,
    /** The button that hands the link to a browser anyway; the card has it only when it is given an action for it. */
    val openInBrowser: String = "",
)

/**
 * The inside of the dialog: the file's name and what to do about it, then, once asked, the
 * address as text and as a QR code. Apart from the dialog's window so the previews can draw it.
 */
@Composable
internal fun NoAppCard(
    name: String,
    address: String,
    showAddress: Boolean,
    labels: NoticeLabels,
    onShowAddress: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenInBrowser: (() -> Unit)? = null,
) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(labels.title, style = MaterialTheme.typography.headlineSmall)
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!showAddress) {
            Text(labels.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            BoxWithConstraints {
                val wide = maxWidth >= 480.dp
                val code: @Composable () -> Unit = {
                    QrCodeImage(address, Modifier.size(if (wide) 168.dp else 200.dp), contentDescription = labels.qrDescription, tooLong = labels.qrTooLong)
                }
                val text: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(labels.addressHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        code()
                        text(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        code()
                        text(Modifier)
                    }
                }
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // The safe choice takes the focus: OK on a remote shows the address, and opens nothing.
            if (!showAddress) ActionButton(labels.showAddress, onShowAddress, focused = true)
            if (onOpenInBrowser != null) ActionButton(labels.openInBrowser, onOpenInBrowser, focused = false)
            ActionButton(labels.close, onClose, focused = showAddress)
        }
    }
}

/** A dialog's button; the one marked [focused] takes the focus, so OK on a remote presses it. */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, focused: Boolean, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    if (!LocalInspectionMode.current) LaunchedEffect(focused) {
        if (focused) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    TextButton(onClick = onClick, modifier = modifier.focusRequester(requester)) { Text(label) }
}
