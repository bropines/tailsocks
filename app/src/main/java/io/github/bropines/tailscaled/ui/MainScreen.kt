package io.github.bropines.tailscaled.ui
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.bropines.tailscaled.ui.theme.findActivity
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import io.github.bropines.tailscaled.core.AppJson
import java.lang.Runtime

import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The main screen: the shell that holds its state and wires its parts together. The parts
 * live beside it — the daemon's state and the plumbing that keeps it current
 * (MainDaemonState.kt), the accounts (MainAccounts.kt), the exit node (MainExitNode.kt),
 * About (MainAboutDialog.kt), the cells around the status card (MainCells.kt), the
 * arrangement and the window layouts (MainWindows.kt, MainDashboard.kt, MainCanvas.kt).
 * Its activity, with the intents that open parts of it, is MainActivity.kt.
 */

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    showAccountSwitcher: MutableState<Boolean>,
    showChangelog: MutableState<Boolean> = remember { mutableStateOf(false) },
    addAccountRequest: MutableState<Boolean?> = remember { mutableStateOf(null) },
    exitNodeRequest: MutableState<Boolean> = remember { mutableStateOf(false) },
    scanRequest: MutableState<Boolean> = remember { mutableStateOf(false) },
    editLayoutRequest: MutableState<Boolean> = remember { mutableStateOf(false) },
    /** For previews: the arrangement to draw instead of the stored one; nothing is stored then. */
    previewLayout: MainLayout? = null,
    /** For previews: open as the editor. */
    previewEditing: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The account in use, the list, and the sheet and dialogs that change them (MainAccounts.kt).
    val acc = rememberMainAccounts(context)
    // A long press on the status card puts a line there for a while. Nothing reads it
    // back and nothing is stored: it is decoration, and it clears itself.
    val statusAsideState = remember { mutableStateOf<String?>(null) }
    var statusAside by statusAsideState
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(statusAside) {
        val line = statusAside ?: return@LaunchedEffect
        // Long lines scroll, so they are given the time to travel once and a bit.
        delay((6000L + 70L * line.length).coerceAtMost(30000L))
        statusAside = null
    }

    LaunchedEffect(addAccountRequest.value) {
        addAccountRequest.value?.let { honest ->
            acc.newAccountHonest = honest
            acc.showAddAccountDialog = true
            addAccountRequest.value = null
        }
    }
    // Saveable, like the dialogs and sheets that hold nothing but a flag or a draft that is not
    // a secret: neither activity handles configuration changes, so a rotation recreates the
    // screen and used to close whatever was open, with what had been typed in it.
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    // Read from preferences on every composition of this screen, so a recreation
    // (a language change, a rotation) and a process restart both bring the ask
    // back until the user has answered it.
    var showAutostartAsk by remember { mutableStateOf(OptionalPermissions.isAutostartAskPending(context)) }

    LaunchedEffect(showAccountSwitcher.value) {
        if (showAccountSwitcher.value) {
            acc.accountMenuExpanded = true
            showAccountSwitcher.value = false
        }
    }

    val prefs = remember(acc.activeAccount.id) { context.getSharedPreferences("appctr_${acc.activeAccount.id}", Context.MODE_PRIVATE) }
    val globalPrefs = remember { context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE) }
    // TUN, Root Mode, a foreign VPN, the DNS fallback and the battery: what the card says
    // besides the daemon's own state (MainDaemonState.kt).
    val flags = rememberMainConnectionFlags()

    // LocalInspectionMode is true only in the preview renderer, which has no
    // daemon and no native bridge: touching Appctr there loads gojni and fails.
    // In the app it is always false, so nothing below changes behaviour.
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    // A preview may hand in a whole state to draw; see LocalDemo.
    val demo = LocalDemo.current
    // The daemon as this screen shows it, kept current by MainDaemonEffects below.
    val daemon = rememberMainDaemonState(demo, inPreview)
    // The profile's exit node and the picker sheet (MainExitNode.kt).
    val exitNode = rememberMainExitNode(acc.activeAccount.id, prefs, demo, scope)
    val reconnectingRelaysState = remember { mutableStateOf(false) }
    // What is on the screen and where (core/MainLayout.kt), and the screen as its own editor
    // (MainWindows.kt).
    val remoteTileOffered = !Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS.offered()
    val itemOffered = rememberMainItemsOffered()
    val arrangeable = !isTv
    val layoutState = rememberMainLayoutState(previewLayout, previewEditing, remoteTileOffered, arrangeable)
    val layout = layoutState.layout
    val editing = layoutState.editing
    // Too short a window for the editor's bar under the canvas: a phone on its side.
    val editRail = editing && androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.screenHeightDp < 500 && it.screenWidthDp > it.screenHeightDp
    }
    // TunVpnService establishes a *full* tunnel exactly when an exit node is configured.
    // The old `tun_full_tunnel` pref was never written by anything and always read false,
    // so derive the indicator from the live exit-node state to keep the UI truthful.
    val isFullTunnel = exitNode.exitNodeIp.isNotEmpty()

    // A television cannot fill in a login form: it signs in with a code on its own screen
    // (ui/SignIn.kt), which opens by itself whenever the daemon turns out to want a login
    // and goes away by itself when it no longer does.
    val browserSignIn = Feature.BROWSER_SIGN_IN.offered()
    val signInOpenState = remember { mutableStateOf(false) }
    var signInOpen by signInOpenState
    // The code was left with the login still wanted: the card's button is where the remote
    // comes back to.
    var signInLeft by remember { mutableStateOf(false) }
    LaunchedEffect(daemon.proxyState, browserSignIn) {
        signInOpen = !browserSignIn && daemon.proxyState == "LOGGED_OUT"
        if (daemon.proxyState != "LOGGED_OUT") signInLeft = false
    }

    // Remote management has a tile of its own where its page in the settings would be three
    // levels down — a television — and wherever the person put one: it opens over this screen,
    // and the remote comes back to the tile.
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    var remoteLeft by remember { mutableStateOf(false) }
    val remoteTile = if (MainItem.REMOTE in layout) rememberRemoteTile(daemon.proxyState, remoteOpen) else null
    // The row under the cards is nearer to the status card than the cards are, and Right from it
    // would land there: the remote is sent to the first card instead.
    val menuStart = remember { FocusRequester() }

    // OK on a remote is pressed by accident, and on the start card that stops the service:
    // a television asks first. Starting asks nothing.
    val tv = isTv
    // A remote has nothing to drag with: a television keeps the arrangement made for it.
    LaunchedEffect(editLayoutRequest.value) {
        if (editLayoutRequest.value) {
            editLayoutRequest.value = false
            if (!tv) layoutState.editing = true
        }
    }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    fun stopNow() {
        daemon.isProcessing = true
        context.startService(Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" })
    }
    fun requestStop() {
        if (tv) confirmStop = true else stopNow()
    }
    val siblingFoundState = remember { mutableStateOf<SiblingBuild.Found?>(null) }
    // The status card's two swipes (core/Gestures.kt): read again on coming back, from Settings too.
    var statusSwipes by remember { mutableStateOf(Gestures.statusToEnd(context) to Gestures.statusToStart(context)) }
    var siblingFound by siblingFoundState
    fun startNow() {
        daemon.isProcessing = true
        ContextCompat.startForegroundService(context, Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" })
    }

    LaunchedEffect(exitNodeRequest.value) {
        if (exitNodeRequest.value) {
            exitNodeRequest.value = false
            exitNode.openExitNodeSheet()
        }
    }
    // A sheet that was open before a rotation comes back open, with its list still to read.
    LaunchedEffect(Unit) {
        if (exitNode.showExitNodeSheet && exitNode.exitNodes.isEmpty() && !exitNode.isExitNodesLoading) exitNode.openExitNodeSheet()
    }
    // The QR scanner, from the top bar and tailsocks://scan: an app link opens
    // as if tapped, a TailCat address in a new connection's editor; any other
    // text is only shown, in a sheet. A pairing code off another device's Remote
    // management screen (ui/RemoteManagedScreen.kt) opens the sheet that pairs
    // with that device, the code filled in — pairing stays the person's press.
    var scannedText by remember { mutableStateOf<String?>(null) }
    var scannedPairing by remember { mutableStateOf<RemoteQr?>(null) }
    val scanQr = rememberQrScanner { text ->
        scope.launch {
            // The bridge reads it, off the main thread like every call of its kind.
            val pairing = withContext(Dispatchers.Default) { remotePairingQr(text) }
            if (pairing != null) scannedPairing = pairing else scannedText = openScanned(context, text)
        }
    }
    val canScan = Feature.QR_SCANNER.availability().reachable
    LaunchedEffect(scanRequest.value) {
        if (scanRequest.value) {
            scanRequest.value = false
            // A link cannot open what the device does not have: no camera, no scanner.
            if (canScan) scanQr()
        }
    }
    // The devices this one manages, when it manages any (ui/ManagedOnMain.kt): read off the peer
    // list above, and again when the page a scanned code opened — which may have paired — is left.
    // Shown while their block is on the screen: the old switch in Settings → Appearance moves it too.
    val managed = rememberManagedDevices(acc.activeAccount.id, daemon.managedPeers.takeIf { daemon.proxyState != "STOPPED" }, MainItem.MANAGED in layout, scannedPairing == null)
    // Where the menu has the tile of the other direction — who manages this device — they stand
    // beside it, in its row, while a tile there is two cards wide. A handheld has no such tile,
    // and they have a place of their own: tiles under a dashboard's cards, pills on a phone.
    // "Its row" is the first tile that takes a row, which is where the menu stands them.
    val remoteLeadsWideTiles = layout.shown(itemOffered).firstOrNull { it.item.isTile && it.wide }?.item == MainItem.REMOTE
    fun managedBeside(columns: Int) = remoteTileOffered && remoteLeadsWideTiles && columns >= 4 && managed.devices.isNotEmpty()

    // The refresh loop, the login-tab watcher, the preference mirrors, the resume observer and
    // the service's broadcasts (MainDaemonState.kt), in their order. Coming back to the front
    // also re-reads the arrangement: Settings can change it too.
    MainDaemonEffects(
        daemon, acc, exitNode, flags, prefs, globalPrefs, demo, inPreview,
        onResumed = {
            if (previewLayout == null && !layoutState.editing) layoutState.layout = MainLayoutStore.load(context, remoteTileOffered, arrangeable)
            statusSwipes = Gestures.statusToEnd(context) to Gestures.statusToStart(context)
        },
    )

    MainAccountsOverlays(acc, daemon.proxyState, scope, onSwitching = { daemon.isProcessing = true })

    // The other way in on a television: a key sent from a phone (TvKeySetup.kt). It takes
    // the sign-in screen's place while it is open and hands back to it when it is left.
    var keySetupOpen by remember { mutableStateOf(false) }
    if (signInOpen && keySetupOpen) {
        TvKeySetupScreen(
            onSignedIn = { keySetupOpen = false; signInOpen = false },
            onClose = { keySetupOpen = false },
        )
    } else if (signInOpen) {
        // The whole screen, in place of the dashboard, which comes back when the code is
        // left or has done its work.
        TvSignIn(
            account = acc.activeAccount.name,
            url = daemon.loginUrl,
            onClose = { signInOpen = false; signInLeft = true },
            extraAction = { TvKeySetupButton(onClick = { keySetupOpen = true }) },
        )
    } else if (remoteOpen && tv) {
        // The page, in place of the dashboard, which comes back with the remote on its tile.
        RemoteManagedScreen(onBack = { remoteOpen = false; remoteLeft = true })
    } else
    // On a handheld the page lies over the dashboard, which stays as it was: the back gesture
    // shrinks the page off it. Drawn in its place, the page shrank off an empty window.
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().then(if (remoteOpen) Modifier.clearAndSetSemantics { } else Modifier)) {
    // A window lying on its side has no height to give a bar under the canvas: there the
    // editor's controls stand beside it (MainEditRail), and the window's edge is theirs.
    Row(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.weight(1f).then(
                if (editRail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)) else Modifier
            ),
            topBar = {
            if (editing) {
                // The screen is its own editor: the bar says so, and its arrow is one of the ways out.
                AppTopBar(
                    title = stringResource(R.string.main_layout_edit_title),
                    subtitle = stringResource(R.string.main_layout_edit_hint),
                    onBack = { layoutState.editing = false },
                )
            } else
            AppTopBar(
                title = stringResource(R.string.app_name),
                subtitle = acc.activeAccount.name,
                onTitleClick = { acc.accountMenuExpanded = true },
                actions = {
                    if (daemon.proxyState == "ACTIVE") {
                        IconButton(onClick = {
                            val intent = Intent(context, TailscaledService::class.java).apply { action = "REFRESH_ACTION" }
                            context.startService(intent)
                            Toast.makeText(context, context.getString(R.string.main_refreshing_config), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.main_cd_refresh_config))
                        }
                    }
                    IfOffered(Feature.QR_SCANNER) {
                        IconButton(onClick = scanQr) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.qr_scan_action))
                        }
                    }
                    IfOffered(Feature.ADMIN_CONSOLE) {
                        IconButton(onClick = {
                            context.startActivity(Intent(context, AdminApiActivity::class.java))
                        }) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = stringResource(R.string.main_cd_admin_api))
                        }
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.main_cd_about_licenses))
                    }
                }
            )
        },
        bottomBar = {
            if (editing && !editRail) {
                MainEditBar(
                    columns = layout.columns,
                    onColumns = { layoutState.changeLayout(layoutState.layout.withColumns(it)) },
                    canAdd = layout.addable(itemOffered).isNotEmpty(),
                    onAdd = { layoutState.paletteOpen = true },
                    onReset = { layoutState.confirmLayoutReset = true },
                    onDone = { layoutState.editing = false },
                )
            }
        }
    ) { paddingValues ->
        // The screen is two things: what the service is doing, and where to go
        // next. Since it can be arranged (core/MainLayout.kt) those are a list:
        // blocks — the status card, the lines and pictures around it — and tiles.
        // A phone held upright draws the list as one flow; a window with the
        // width for it puts the blocks on one side and the tiles on the other,
        // each in the list's order — before that the one column simply grew
        // wider, and eight menu cards stretched into slabs.

        // What names a tap on a phone names the remote's OK on a television.
        val touchWording = Feature.TOUCH_GESTURES_HINTS.offered()

        val proxyState = daemon.proxyState
        val exitNodeIp = exitNode.exitNodeIp
        val exitNodePausedName = exitNode.exitNodePausedName
        val connectionSummary = daemon.connectionSummary
        // Neither of these is an error: the tunnel is up and the tailnet is
        // reachable either way. Sharing means the other client bypasses some
        // apps and we took the default route for exactly those, so it is the
        // narrower claim of the two and has to be tested first.
        val sharedWithForeignVpn = flags.isRootEnabled && flags.isRootShared
        val yieldedToForeignVpn = flags.isRootEnabled && flags.isRootYielded && !sharedWithForeignVpn
        // A selected exit node carries no traffic while we are yielded,
        // so the row must not read as "traffic is routed". Shared is the
        // opposite case: it does carry traffic, for the apps the other
        // VPN left out, so it stays an active row with a narrower label.
        val exitNodeInert = exitNodeIp.isNotEmpty() && yieldedToForeignVpn
        val exitNodePartial = exitNodeIp.isNotEmpty() && sharedWithForeignVpn
        val exitNodeActive = exitNodeIp.isNotEmpty() && !exitNodeInert
        val exitNodePaused = exitNodeIp.isEmpty() && exitNodePausedName.isNotEmpty()

        // The card says what is true: the service may be up while the
        // tailnet is not — still waiting for its map, signed out, or cut
        // off from every relay — and each of those reads differently.
        val serviceUp = proxyState == "ACTIVE" || proxyState == "CONNECTION_ISSUE" ||
            (proxyState == "LOGGED_OUT" && !inPreview && ProxyState.isActualRunning())
        val cardState = when {
            proxyState == "LOGGED_OUT" && serviceUp -> "NEEDS_LOGIN"
            proxyState == "CONNECTION_ISSUE" -> "DEGRADED"
            // Signed in, but the tailnet requires an admin to approve new
            // devices — the notification already said so; the card said "Active".
            proxyState == "ACTIVE" && daemon.daemonBackend == "NeedsMachineAuth" -> "NEEDS_APPROVAL"
            proxyState == "ACTIVE" && daemon.daemonBackend == "Starting" -> "CONNECTING"
            proxyState == "ACTIVE" && warningsDegradeConnection(daemon.shownWarnings, flags.dnsHasFallback) -> "DEGRADED"
            else -> proxyState
        }

        // The screen being arranged does nothing: its cells are covered while it is, and this
        // stops the one tap a cover cannot — the end of the very press that started arranging.
        // Read from the holder when the tap comes, not from this composition.
        fun act(action: () -> Unit) {
            if (!layoutState.editing) action()
        }

        // What a swipe on the status card does now, or null where it can do nothing — and then
        // the card does not drag that way. The exit node is the connected daemon's to change.
        fun statusSwipe(kind: StatusSwipe): (() -> Unit)? = when (kind) {
            StatusSwipe.NONE -> null
            StatusSwipe.NEXT_ACCOUNT -> {
                val list = acc.accounts.value
                val next = if (list.size < 2 || daemon.isProcessing) null
                    else list[(list.indexOfFirst { it.id == acc.activeAccount.id } + 1).mod(list.size)]
                // As a row of the accounts sheet does: a running service asks before it restarts.
                next?.let { account -> {
                    act {
                        StatusAsides.bump(context, StatusAsides.SWITCHES)
                        if (ProxyState.isActualRunning()) acc.showSwitchConfirmDialog = account
                        else {
                            AccountManager.setActiveAccount(context, account.id)
                            acc.activeAccount = account
                        }
                    }
                } }
            }
            StatusSwipe.EXIT_NODE_TOGGLE -> when {
                proxyState != "ACTIVE" -> null
                exitNodeIp.isNotEmpty() -> ({ act { exitNode.applyExitNode("", "", haptics) } })
                else -> {
                    // The node in use before, as ExitNodeApply remembers it for the widget.
                    val lastIp = prefs.getString("last_exit_node_ip", "").orEmpty()
                    val lastId = prefs.getString("last_exit_node_id", "").orEmpty()
                    if (lastIp.isEmpty() || lastId.isEmpty()) null
                    else ({ act { exitNode.applyExitNode(lastId, lastIp, haptics) } })
                }
            }
            StatusSwipe.EXIT_NODE_PICKER -> if (proxyState != "ACTIVE") null else ({ act { exitNode.openExitNodeSheet() } })
            StatusSwipe.PREV_ACCOUNT -> {
                val list = acc.accounts.value
                val prev = if (list.size < 2 || daemon.isProcessing) null
                    else list[(list.indexOfFirst { it.id == acc.activeAccount.id } - 1).mod(list.size)]
                prev?.let { account -> {
                    act {
                        StatusAsides.bump(context, StatusAsides.SWITCHES)
                        if (ProxyState.isActualRunning()) acc.showSwitchConfirmDialog = account
                        else {
                            AccountManager.setActiveAccount(context, account.id)
                            acc.activeAccount = account
                        }
                    }
                } }
            }
            // The health banner's button, without waiting for the banner: the relays are dropped and found again.
            StatusSwipe.RECONNECT_RELAYS -> if (proxyState != "ACTIVE") null else ({
                act {
                    scope.launch(Dispatchers.IO) {
                        val err = runCatching { appctr.Appctr.reconnectRelays() }.getOrElse { it.message ?: "" }
                        if (!err.isNullOrBlank()) withContext(Dispatchers.Main) {
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
            // The screens a tile opens, opened as that tile would.
            StatusSwipe.OPEN_PEERS -> MainItem.PEERS.screen(context)?.let { intent -> { act { context.startActivity(intent) } } }
            StatusSwipe.OPEN_FILES -> MainItem.FILES.screen(context)?.let { intent -> { act { context.startActivity(intent) } } }
            StatusSwipe.OPEN_MAP -> MainItem.TAILNET_MAP.screen(context)?.let { intent -> { act { context.startActivity(intent) } } }
            StatusSwipe.OPEN_LOGS -> if (!itemOffered(MainItem.LOGS)) null
                else MainItem.LOGS.screen(context)?.let { intent -> { act { context.startActivity(intent) } } }
            StatusSwipe.OPEN_SETTINGS -> MainItem.SETTINGS.screen(context)?.let { intent -> { act { context.startActivity(intent) } } }
            StatusSwipe.SCAN_QR -> if (!canScan) null else ({ act { scanQr() } })
        }

        val banners: @Composable () -> Unit = {
            MainBanners(
                isBatteryOptimizationsIgnored = flags.isBatteryOptimizationsIgnored,
                show410Warning = daemon.show410Warning,
                proxyState = proxyState,
                yieldedToForeignVpn = yieldedToForeignVpn,
                sharedWithForeignVpn = sharedWithForeignVpn,
            )
        }

        val exitNodeLine: @Composable (Modifier) -> Unit = { modifier ->
            MainExitNodeLine(
                modifier = modifier,
                exitNodeIp = exitNodeIp,
                exitNodePausedName = exitNodePausedName,
                summaryExitNodeName = connectionSummary?.exitNodeName,
                exitNodeActive = exitNodeActive,
                exitNodeInert = exitNodeInert,
                exitNodePartial = exitNodePartial,
                exitNodePaused = exitNodePaused,
                touchWording = touchWording,
                onClick = { act { exitNode.openExitNodeSheet() } },
            )
        }

        val statusCell: @Composable ColumnScope.(Modifier, Boolean, Boolean) -> Unit = { cardModifier, bare, fillHeight ->
            MainStatusCell(
                cardModifier = cardModifier,
                bare = bare,
                daemon = daemon,
                flags = flags,
                layoutState = layoutState,
                cardState = cardState,
                serviceUp = serviceUp,
                isFullTunnel = isFullTunnel,
                yieldedToForeignVpn = yieldedToForeignVpn,
                sharedWithForeignVpn = sharedWithForeignVpn,
                exitNodeIp = exitNodeIp,
                exitNodePaused = exitNodePaused,
                exitNodeLine = exitNodeLine,
                remoteTileOffered = remoteTileOffered,
                menuStart = menuStart,
                statusAsideState = statusAsideState,
                reconnectingRelaysState = reconnectingRelaysState,
                signInOpenState = signInOpenState,
                signInLeft = signInLeft,
                browserSignIn = browserSignIn,
                accountId = acc.activeAccount.id,
                siblingFoundState = siblingFoundState,
                haptics = haptics,
                scope = scope,
                requestStop = { requestStop() },
                startNow = { startNow() },
                fillHeight = fillHeight,
                swipeToEnd = statusSwipes.first,
                swipeToStart = statusSwipes.second,
                swipeAction = ::statusSwipe,
            )
        }

        // A block with nothing to say right now is not drawn, and leaves no gap.
        fun blockShows(item: MainItem): Boolean = when (item) {
            MainItem.EXIT_NODE -> proxyState == "ACTIVE"
            // The summary stays whatever the card's warnings say: it is what the card is about.
            MainItem.SUMMARY -> cardState == "ACTIVE" && connectionSummary != null
            MainItem.SPEED_GRAPH -> cardState == "ACTIVE"
            MainItem.MANAGED -> managed.devices.isNotEmpty()
            else -> true
        }
        val block: @Composable ColumnScope.(MainItem, Modifier) -> Unit = { item, cardModifier ->
            when (item) {
                MainItem.STATUS -> statusCell(cardModifier, editing, false)
                MainItem.EXIT_NODE -> exitNodeLine(Modifier)
                MainItem.SUMMARY -> connectionSummary?.let { ConnectionSummaryRow(it) }
                MainItem.SPEED_GRAPH -> SpeedGraphCard(Modifier.fillMaxWidth()) {
                    act { context.startActivity(Intent(context, TailnetMapActivity::class.java)) }
                }
                MainItem.TIMELINE -> ConnectionTimelineCard(Modifier.fillMaxWidth())
                MainItem.MANAGED -> ManagedPills(managed)
                else -> Unit
            }
        }
        // The banners stand above the card, and above the exit-node line when that is the
        // card's upper neighbour — where they always were.
        fun bannersBefore(drawn: List<MainItem>): MainItem {
            val card = drawn.indexOf(MainItem.STATUS)
            return if (card > 0 && drawn[card - 1] == MainItem.EXIT_NODE) MainItem.EXIT_NODE else MainItem.STATUS
        }

        // Only what the device is offered in its usual place: what a television keeps
        // for its advanced settings, or does not have, is not a tile here.
        val placed = layout.shown(itemOffered)

        // fillHeight: beside the menu, the card takes the height the status
        // column has left, so the pair ends on one line (see the two-pane layout).
        val statusPane: @Composable ColumnScope.(fillHeight: Boolean) -> Unit = { fillHeight ->
            // Beside the menu the managed devices stand under it (pills, or a dashboard's tiles),
            // where they always were; only the one flow puts them where the person moved them.
            val drawn = placed.map { it.item }.filter { !it.isTile && it != MainItem.MANAGED && blockShows(it) }
            val anchor = bannersBefore(drawn)
            drawn.forEachIndexed { i, item ->
                if (i > 0) Spacer(modifier = Modifier.height(mainGap(drawn[i - 1], item)))
                if (item == anchor) banners()
                val weight = if (fillHeight) Modifier.weight(1f) else Modifier
                // The card is told it fills the pane: in a swipe box it fills the box that does.
                if (item == MainItem.STATUS) statusCell(weight, editing, fillHeight) else block(item, weight)
            }
        }

        fun openTile(item: MainItem) {
            when (item) {
                // It opens over this screen, and the remote comes back to it.
                MainItem.REMOTE -> { remoteLeft = false; remoteOpen = true }
                MainItem.EXIT_PICKER -> exitNode.openExitNodeSheet()
                MainItem.SCAN -> scanQr()
                MainItem.ACCOUNTS -> acc.accountMenuExpanded = true
                else -> item.screen(context)?.let { context.startActivity(it) }
            }
        }
        // A tile that takes a row has room for a second line, where there is something to say.
        @Composable
        fun tileState(item: MainItem): String? = when (item) {
            MainItem.REMOTE -> remoteTile?.let { remoteTileText(it) }
            MainItem.PEERS -> daemon.tailnetPeers?.takeIf { it.isNotEmpty() && proxyState == "ACTIVE" }
                ?.let { peers -> stringResource(R.string.summary_online, peers.count { it.online == true }, peers.size) }
            MainItem.EXIT_PICKER -> if (proxyState != "ACTIVE") null
                else connectionSummary?.exitNodeName ?: exitNodeIp.ifEmpty { stringResource(R.string.tile_exit_picker_none) }
            else -> null
        }
        @Composable
        fun entryOf(tile: MainLayout.Placed): MenuEntry {
            val item = tile.item
            val spec = item.spec(tv)
            return MenuEntry(
                title = stringResource(spec.title),
                icon = spec.icon,
                icon2 = spec.icon2,
                state = if (tile.wide) tileState(item) else null,
                stateWarns = item == MainItem.REMOTE && remoteTile?.blocked == true,
                wide = tile.wide,
                focusOnShow = item == MainItem.REMOTE && remoteLeft,
                onClick = { act { openTile(item) } },
            )
        }
        val menuEntries = placed.filter { it.item.isTile }.map { entryOf(it) }
        // How many cards there are: a tile with a row of its own takes no column.
        val cardCount = menuEntries.count { !it.wide }
        // The person's count where they chose one; otherwise what the width holds.
        fun columnsOr(auto: Int) = if (layout.columns == MainLayout.AUTO) auto else layout.columns
        val menuPane: @Composable (Int, androidx.compose.ui.unit.Dp) -> Unit = { auto, cardHeight ->
            val columns = columnsOr(auto)
            MenuGrid(
                columns = columns, cardHeight = cardHeight, entries = menuEntries, start = menuStart.takeIf { remoteTileOffered },
                // Beside the status there is no canvas to hold a tile on: a long press on the
                // menu opens the editor, which is the same list drawn as one flow.
                modifier = Modifier.onLongPressAnywhere(enabled = !tv) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    layoutState.editing = true
                },
                beside = if (managedBeside(columns)) ({ ManagedTileCells(managed, places = 1) }) else null
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // A foldable decides the split itself: open like a book, each half
            // is a pane; half open like a laptop, the upright half shows the
            // status and the half lying flat holds the menu, under the thumbs.
            val fold = rememberFold()
            val window = rememberWindowLayout()
            val insetStart = paddingValues.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current)
            val insetTop = paddingValues.calculateTopPadding()
            // Two panes want width and a window lying on its side. Upright, a
            // tablet's 600dp is one comfortable column; split in two it was a
            // card and a grid floating in the middle of an empty screen (a
            // Lenovo tablet, 600x960dp, 2026-09-30).
            val wideEnoughForTwoPanes = fold is Fold.Vertical || (maxWidth >= 600.dp && maxWidth > maxHeight)
            // Landscape on a phone leaves about 390dp of height, so the cards
            // give some of it back; upright there is room to spare.
            val cardHeight = if (maxHeight < 440.dp) 84.dp else 96.dp
            // A short window centres its panes, which then fill it anyway; a
            // tall one starts them under the top bar, where the eye does.
            val paneArrangement = if (maxHeight < 480.dp) Arrangement.Center else Arrangement.Top
            // One flow, top to bottom: a phone held upright — and every window while the
            // screen is being arranged, because the flow is the arrangement itself.
            val flowWindow = fold !is Fold.Horizontal && (window.isPhone || fold != null) && !wideEnoughForTwoPanes
            if (editing || flowWindow) {
                MainFlowWindow(
                    state = layoutState,
                    maxWidth = maxWidth,
                    cardHeight = cardHeight,
                    flowWindow = flowWindow,
                    placed = placed,
                    itemOffered = itemOffered,
                    tv = tv,
                    remoteTileOffered = remoteTileOffered,
                    menuStart = menuStart,
                    blockShows = ::blockShows,
                    bannersBefore = ::bannersBefore,
                    banners = banners,
                    block = block,
                    entryOf = { entryOf(it) },
                    columnsOr = ::columnsOr,
                )
            } else if (fold is Fold.Horizontal) {
                MainTabletopWindow(fold, maxWidth, insetTop, cardHeight, cardCount, managed, statusPane, menuPane)
            } else if (!window.isPhone && fold == null) {
                // Not a phone either way up, and no fold to lay out around: a tablet, a
                // desktop window. A dashboard that fills it (MainDashboard.kt) instead of
                // the phone's column or pair of panes parked at the top of the window. The
                // branches around this one are a phone's and a foldable's, as they were.
                MainDashboard(
                    window = window,
                    width = maxWidth,
                    height = maxHeight,
                    status = { fillHeight -> statusPane(fillHeight) },
                    // The managed devices are tiles under the cards, unless the menu has them in
                    // its own row already: a television's, which is counted below as it always was.
                    menu = { columns, rowHeight ->
                        menuPane(columns, rowHeight)
                        if (!managedBeside(columns)) ManagedTiles(managed)
                    },
                    menuCount = cardCount,
                    wideMenuRows = menuEntries.size - cardCount,
                    peers = daemon.tailnetPeers.takeIf { proxyState != "STOPPED" },
                    connected = proxyState == "ACTIVE" || proxyState == "STARTING",
                    onOpenPeers = { id ->
                        context.startActivity(Intent(context, PeersActivity::class.java).apply { id?.let { putExtra(PeersActivity.EXTRA_PEER_ID, it) } })
                    },
                )
            } else {
                MainTwoPaneWindow(fold, maxWidth, maxHeight, insetStart, cardHeight, paneArrangement, cardCount, managed, statusPane, menuPane)
            }
        }
    }
        if (editRail) {
            MainEditRail(
                columns = layout.columns,
                onColumns = { layoutState.changeLayout(layoutState.layout.withColumns(it)) },
                canAdd = layout.addable(itemOffered).isNotEmpty(),
                onAdd = { layoutState.paletteOpen = true },
                onReset = { layoutState.confirmLayoutReset = true },
                onDone = { layoutState.editing = false },
            )
        }
    }
    }
    if (remoteOpen) RemoteManagedScreen(onBack = { remoteOpen = false; remoteLeft = true })
    }

    // Back leaves the editor before it leaves the app.
    androidx.activity.compose.BackHandler(enabled = editing) { layoutState.editing = false }

    MainLayoutOverlays(layoutState, itemOffered, previewLayout, remoteTileOffered, arrangeable)

    if (showChangelog.value) {
        ChangelogDialog(onDismiss = {
            GlobalSettings.setLastSeenChangelogVersion(context, Changelog.currentVersion())
            showChangelog.value = false
        })
    }

    // Queued behind "What's new" so an update never stacks two dialogs.
    if (showAutostartAsk && !showChangelog.value) {
        AutostartAskDialog(onAnswered = { showAutostartAsk = false })
    }

    scannedText?.let { ScanResultSheet(text = it, onDismiss = { scannedText = null }) }
    // The device that showed the code is in front of the person who scanned it: taken for online.
    scannedPairing?.let { qr ->
        RemoteManagePage(peerId = qr.nodeId, name = qr.name, online = true, onClose = { scannedPairing = null }, code = qr.code)
    }

    if (confirmStop) {
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.tv_stop_title)
        val dlgText = stringResource(R.string.tv_stop_text)
        val dlgStop = stringResource(R.string.main_status_action_stop)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { confirmStop = false },
            icon = { Icon(Icons.Default.PowerSettingsNew, null) },
            title = { Text(dlgTitle) },
            text = { Text(dlgText) },
            confirmButton = {
                Button(onClick = { confirmStop = false; stopNow() }) { Text(dlgStop) }
            },
            // Opens on Cancel: the second OK of a double press must not be the one that stops.
            dismissButton = {
                TextButton(onClick = { confirmStop = false }, modifier = Modifier.tvInitialFocus()) { Text(dlgCancel) }
            }
        )
    }

    siblingFound?.let { found ->
        SiblingDialog(
            found = found,
            onOpen = {
                siblingFound = null
                SiblingBuild.launchIntent(context, found.packageName)?.let { runCatching { context.startActivity(it) } }
            },
            onStartAnyway = { siblingFound = null; startNow() },
            onDismiss = { siblingFound = null },
        )
    }

    if (showAboutDialog) {
        MainAboutDialog(
            scope = scope,
            onDismiss = { showAboutDialog = false },
            onWhatsNew = { showAboutDialog = false; showChangelog.value = true },
        )
    }

    if (exitNode.showExitNodeSheet) MainExitNodeSheet(exitNode, haptics)
}
