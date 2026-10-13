package io.github.bropines.tailscaled.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.TaildrivePolicyFix
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.DriveEntry
import io.github.bropines.tailscaled.core.DriveError
import io.github.bropines.tailscaled.core.DriveKind
import io.github.bropines.tailscaled.core.DriveLaunch
import io.github.bropines.tailscaled.core.DriveLocation
import io.github.bropines.tailscaled.core.DriveProxy
import io.github.bropines.tailscaled.core.DriveResult
import io.github.bropines.tailscaled.core.TaildriveBrowse
import io.github.bropines.tailscaled.core.formatFileSize
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
 * the states it passes through.
 *
 * Built for a D-pad first. Every row is one focus stop and OK on it does the whole thing — enters a
 * folder, or hands a file to another app; Back climbs one level and leaves at the top. There is
 * no long press, swipe or menu anywhere, so nothing is out of reach of four arrows, OK and Back.
 * Rows are plain Material clickables, so whatever focus indication the theme gives a clickable
 * is what they get.
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
    class NoApp(val name: String, val address: String, val openInBrowser: (() -> Unit)? = null) : OpenNotice

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
) {
    companion object {
        val None = DriveBrowserActions({}, {}, {}, {}, {}, {})
    }
}

private val LocationSaver = Saver<DriveLocation, ArrayList<String>>(
    save = { ArrayList(it.segments) },
    restore = { DriveLocation(it) }
)

/**
 * The page. A full-screen composable, not an Activity: the host puts it where it likes and
 * supplies two callbacks.
 *
 * - [onClose] runs when Back (or the arrow) is used at the top level, the list of devices.
 *   Deeper, Back goes one level up and the callback is not called.
 * - [onEnableProxy] runs from the "Turn it on" button of the proxy-off state. It should switch
 *   the Taildrive proxy on for the profile and tell the service to apply it (what
 *   Settings -> Taildrive does); the page then waits a few seconds for the proxy to answer and
 *   carries on by itself. Returning from another screen, a settings page say, also re-checks.
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

    fun go(to: DriveLocation, focus: String? = null) {
        notice = null
        location = to
        focusName = focus
        loadKey++
    }

    val up: () -> Unit = {
        val parent = location.parent
        if (parent != null && location.depth > top) go(parent, focus = location.name) else onClose()
    }
    BackHandler(onBack = up)

    // Back from another app or a settings page: whatever was missing may be there now.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentPhase by rememberUpdatedState(phase)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val current = currentPhase
            if (event == Lifecycle.Event.ON_RESUME && current is BrowserPhase.Failed && current.error.isFixableOutside) loadKey++
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

    val actions = DriveBrowserActions(
        onOpen = { entry ->
            if (entry.isFolder) go(location.child(entry.name))
            else scope.launch {
                val file = location.child(entry.name)
                when (val prepared = withContext(Dispatchers.IO) { TaildriveBrowse.prepareOpen(appContext, account, file, viaLink) }) {
                    // The link was asked for and the proxy is not there: say so, and carry on from here.
                    is DriveResult.Err -> phase = BrowserPhase.Failed(prepared.error)
                    is DriveResult.Ok -> {
                        val spec = prepared.value
                        notice = when (val launched = spec.launch(context, viaLink)) {
                            DriveLaunch.Started -> null
                            DriveLaunch.NoApp -> OpenNotice.NoApp(entry.name, spec.tailnetUrl)
                            // Nothing but a browser takes it: not opened until the person says so.
                            is DriveLaunch.OnlyBrowser -> OpenNotice.NoApp(entry.name, spec.tailnetUrl, openInBrowser = {
                                notice = null
                                (launched.openAnyway() as? DriveLaunch.Failed)?.let { notice = OpenNotice.Failed(entry.name, it.detail) }
                            })
                            is DriveLaunch.Failed -> OpenNotice.Failed(entry.name, launched.detail)
                        }
                    }
                }
            }
        },
        onUp = up,
        onRetry = { loadKey++ },
        onEnableProxy = {
            starting = true
            onEnableProxy()
            loadKey++
        },
        onDaemonStarted = { loadKey++ },
        onDismissNotice = { notice = null },
        onFixPolicy = fix?.let { intent -> { context.tryStartActivity(intent); Unit } },
        viaLink = viaLink,
        onToggleLink = {
            viaLink = !viaLink
            DriveProxy.setOpenViaLink(appContext, account, viaLink)
        },
    )
    TaildriveBrowserContent(location, phase, focusName, notice, actions, modifier)
}

/** Whether the user can put this right somewhere else and come back. */
private val DriveError.isFixableOutside: Boolean
    get() = this == DriveError.ProxyOff || this == DriveError.NotConnected || this == DriveError.ProxyNotListening ||
        this == DriveError.NotPermitted

/**
 * The page for a given state, with nothing live behind it: the previews draw this, and the
 * page above feeds it.
 */
@Composable
internal fun TaildriveBrowserContent(
    location: DriveLocation,
    phase: BrowserPhase,
    focusName: String?,
    notice: OpenNotice?,
    actions: DriveBrowserActions,
    modifier: Modifier = Modifier,
) {
    val layout = rememberWindowLayout()
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
            AppTopBar(
                title = stringResource(R.string.taildrive_browser_title),
                subtitle = subtitle,
                onBack = actions.onUp,
                actions = {
                    actions.onToggleLink?.let { toggle ->
                        IconToggleButton(checked = actions.viaLink, onCheckedChange = { toggle() }) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = stringResource(R.string.taildrive_open_via_link),
                                tint = if (actions.viaLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ReadableWidth {
                when (phase) {
                    BrowserPhase.Loading -> Busy(stringResource(R.string.taildrive_browser_loading))
                    BrowserPhase.StartingProxy -> Busy(stringResource(R.string.taildrive_browser_proxy_starting))
                    is BrowserPhase.Listed -> Entries(location, phase.entries, focusName, actions.onOpen, actions.onRetry)
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

// ---------------------------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------------------------

@Composable
private fun Entries(location: DriveLocation, entries: List<DriveEntry>, focusName: String?, onOpen: (DriveEntry) -> Unit, onRetry: () -> Unit) {
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
            onAction = if (location.depth <= 2) onRetry else null
        )
        return
    }

    val layout = rememberWindowLayout()
    // The folder just left when going up, so the list is where it was; the first row otherwise.
    val target = remember(entries, focusName) { entries.indexOfFirst { it.name == focusName }.coerceAtLeast(0) }
    // The row asks for the remote as it is placed (see ListStart); a touch screen has none to give.
    val start = rememberListStart(entries to focusName)
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
            EntryRow(
                entry,
                onClick = { onOpen(entry) },
                modifier = if (entry.name == entries[target].name) Modifier.tvListStart(start) else Modifier
            )
        }
    }
}

@Composable
private fun EntryRow(entry: DriveEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val meta = remember(entry, locale) { entryMeta(entry, locale) }
    // One clickable for the whole row: a single stop for the D-pad, and OK does what the row says.
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (entry.isFolder) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        body = stringResource(if (browserOnly) R.string.taildrive_open_browseronly_body else R.string.taildrive_browser_noapp_body),
        openInBrowser = stringResource(R.string.taildrive_open_in_browser),
        addressHelp = stringResource(R.string.taildrive_browser_address_help),
        showAddress = stringResource(R.string.taildrive_browser_noapp_show_address),
        close = stringResource(R.string.action_close),
        qrDescription = stringResource(R.string.qr_image),
        qrTooLong = stringResource(R.string.qr_too_long),
    )
    AppBasicAlertDialog(onDismissRequest = onDismiss) {
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
