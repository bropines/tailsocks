package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search

class PeersActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { PeersScreen(onBack = { finish() }) } }
    }
}

/**
 * The tailnet's devices. [initialQuery] opens the screen with the search out and filled in;
 * [initialTab] opens it on a tab, where the user's last choice would otherwise decide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(onBack: () -> Unit, initialQuery: String = "", initialTab: PeerTab? = null) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // In a preview the list comes from LocalDemo, parsed here and now: nothing
    // started from LaunchedEffect would land before the picture is taken.
    val demo = LocalDemo.current
    // The preview renderer has no daemon and no native bridge; there the demo, or its
    // absence, decides whether the service counts as running.
    val inPreview = LocalInspectionMode.current
    val demoStatus = remember(demo) {
        demo?.statusJson?.let { runCatching { AppJson.decodeFromString<StatusResponse>(it) }.getOrNull() }
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // Set by a load that found the service down; the screen offers to start it then.
    var daemonStopped by remember { mutableStateOf(inPreview && demo?.running != true) }
    // Whether a load has come back at all, so an empty list before the first answer is
    // not announced as an empty tailnet.
    var loaded by remember { mutableStateOf(demoStatus != null) }
    var selfPeer by remember { mutableStateOf<PeerData?>(demoStatus?.self) }
    var peersList by remember { mutableStateOf(demoStatus?.let(::listablePeers) ?: emptyList()) }
    // Saveable, with the tab and the field's place: a rotation keeps what is being looked for.
    var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }
    var selectedPeer by remember { mutableStateOf<PeerData?>(null) }
    var peerForFileDrop by remember { mutableStateOf<PeerData?>(null) }
    // The peer whose "Copy as…" is open — from a long press on its row, or from the details
    // sheet's button, over that sheet. Its forms are built when it opens, never for the list.
    var copyAsPeer by remember { mutableStateOf<PeerData?>(null) }
    /** Title and text of the QR code on screen, picked from "Copy as…". */
    var peerQr by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Whether the tailnet resolves MagicDNS names, from the same status as the list.
    var magicDns by remember { mutableStateOf(demoStatus?.let(MagicDnsNames::of) ?: MagicDnsNames.UNKNOWN) }
    // The Tailscale version of each node, by node id, as the Admin API reports it — the one
    // property the daemon's status does not carry for a peer. Resolved once per list load,
    // off the main thread, after the list is already on screen; the sheet reads the map and
    // never asks the network itself. Empty until the answer lands, and stays empty when the
    // Admin Console has not been set up: then no peer gets a version row.
    var peerVersions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // The daemon's raw answer per address, for as long as this screen is open. Cleared on a
    // reload: a figure measured before the list changed is not the figure now.
    val peerPings = remember { mutableStateMapOf<String, String>().apply { demo?.pings?.let { putAll(it) } } }
    var pingingAll by remember { mutableStateOf(false) }

    // Every tab's rows, the search applied within each; the counts on the tabs are their
    // sizes. Worked out once per change of the list or the query, never per page.
    val tabRows = remember(peersList, selfPeer, searchQuery) { peerTabRows(selfPeer, peersList, searchQuery.trim()) }
    // Which tabs there are follows the list alone, so typing never takes away the tab the
    // search runs in — a tab the search empties stays, with its 0.
    val tabs = remember(peersList, selfPeer) { visiblePeerTabs(selfPeer, peersList) }
    var selectedTab by rememberSaveable { mutableStateOf(initialTab ?: storedPeerTab(context)) }
    // A pager per set of tabs, and only a reload changes the set: it opens on the tab in
    // use, or on All when a reload took that tab away. Rebuilt rather than re-aimed, so a
    // page index never briefly means another tab.
    val pager = remember(tabs) { PagerState(currentPage = tabs.indexOf(selectedTab).coerceAtLeast(0)) { tabs.size } }
    LaunchedEffect(pager, loaded) {
        // Before the first load All is the only tab, and the stored one is not gone yet.
        if (loaded && selectedTab !in tabs) selectedTab = PeerTab.ALL
        // Where a swipe or a tap settles: kept for this screen and the next visit. The
        // first value is only where this pager opened, and a fallback to All is not a choice.
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            tabs.getOrNull(page)?.let {
                selectedTab = it
                GlobalSettings.setString(context, PEERS_TAB_PREF, it.name)
            }
        }
    }
    // What is on screen: the details sheet pages through it and "Ping all" pings it.
    val shownRows = tabRows.getValue(tabs.getOrElse(pager.currentPage) { PeerTab.ALL })
    // A scroll position per tab, held out here so a page that leaves the screen, or a pager
    // rebuilt by a reload, comes back where it was.
    val listStates = PeerTab.entries.associateWith { rememberLazyListState() }

    // The search field, folded away until a pull at the top of the list or the top bar's
    // button brings it out; out from the start when the screen opens on a query.
    val searchShown = rememberSaveable { mutableFloatStateOf(if (initialQuery.isNotEmpty()) 1f else 0f) }
    val searchSlotPx = with(LocalDensity.current) { SEARCH_SLOT_HEIGHT.toPx() }
    val reveal = remember { SearchReveal(searchShown, coroutineScope, searchSlotPx) { searchQuery.isNotEmpty() } }
    val searchOut by remember { derivedStateOf { reveal.fraction > 0f } }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Set by the top bar's button: the field takes the keyboard once it is there to take it.
    var focusSearchOnOpen by remember { mutableStateOf(false) }
    // Text typed into a field let go of half-way opens it the rest of the way.
    val searchPinned = searchQuery.isNotEmpty()
    LaunchedEffect(searchPinned) { if (searchPinned && reveal.fraction < 1f) reveal.animateTo(1f) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && peerForFileDrop != null) { sendFileToPeer(context, uri, peerForFileDrop!!, coroutineScope) }
        peerForFileDrop = null
    }

    fun loadPeers() {
        if (isRefreshing) return
        isRefreshing = true
        errorMsg = null
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // A stopped service is not an error to print: the screen offers to start it.
                if (!ProxyState.isActualRunning(context)) {
                    withContext(Dispatchers.Main) {
                        daemonStopped = true
                        loaded = true
                        isRefreshing = false
                    }
                    return@launch
                }
                val json = Appctr.getStatusFromAPI()

                if (json.isNullOrBlank() || json.startsWith("Error")) {
                    throw Exception(if (json.isNullOrBlank()) context.getString(R.string.peers_daemon_not_running) else json)
                }
                val status = AppJson.decodeFromString<StatusResponse>(json)
                val loadedPeers = listablePeers(status)
                withContext(Dispatchers.Main) {
                    selfPeer = status.self
                    peersList = loadedPeers
                    magicDns = MagicDnsNames.of(status)
                    daemonStopped = false
                    loaded = true
                    isRefreshing = false
                }
                // After the list is up, not before: the first answer is an Admin API round
                // trip (or nothing at all, when no token is configured), and the list must
                // not wait on it. One call for the whole list — the source reads its settings
                // once, fetches the device list once and answers every node from memory — so a
                // refresh a minute later costs no network at all.
                val versions = PeerVersionSource.versionsFor(context, listOfNotNull(status.self) + loadedPeers)
                withContext(Dispatchers.Main) { peerVersions = versions }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRefreshing = false
                    daemonStopped = false
                    loaded = true
                    errorMsg = e.message ?: context.getString(R.string.peers_network_error)
                }
            }
        }
    }

    LaunchedEffect(Unit) { if (demo == null && !inPreview) loadPeers() }

    // What "last seen" is measured against: the clock at each load. A demo carries no clock,
    // so there time stands at the newest stamp it holds — a render made next month reads as
    // one made today.
    val nowMillis = remember(peersList, selfPeer) {
        if (demo == null) System.currentTimeMillis()
        else (listOfNotNull(selfPeer) + peersList).mapNotNull { parseRfc3339Millis(it.lastSeen) }.maxOrNull()
            ?: System.currentTimeMillis()
    }

    PredictiveBackContainer(
        onBack = onBack,
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        Scaffold(
            topBar = {
            Column {
                AppTopBar(
                    title = stringResource(R.string.peers_title),
                    onBack = onBack,
                    actions = {
                        // The search without the pull, for whoever does not know the pull;
                        // and, once it is out, the one tap that empties and folds it.
                        if (!daemonStopped) {
                            IconButton(onClick = {
                                if (searchOut) {
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                    reveal.animateTo(0f)
                                } else {
                                    focusSearchOnOpen = true
                                    reveal.animateTo(1f)
                                }
                            }) {
                                if (searchOut) Icon(Icons.Default.SearchOff, stringResource(R.string.peers_search_close))
                                else Icon(Icons.Default.Search, stringResource(R.string.peers_search_open))
                            }
                        }
                        // One round trip per node, a few at a time; the figures land in the
                        // rows as they arrive rather than all at the end.
                        IconButton(
                            onClick = {
                                pingingAll = true
                                StatusAsides.bump(context, StatusAsides.PINGS)
                                coroutineScope.launch {
                                    // The rows on screen, this tab's under this search. This
                                    // device is not pinged: a node cannot measure a round trip
                                    // to itself, and the row would only ever show a failure.
                                    pingAll(
                                        shownRows.peers.filter { it.online == true }.map { it.getPrimaryIp() },
                                        peerPings
                                    )
                                    pingingAll = false
                                }
                            },
                            enabled = !pingingAll && !isRefreshing && !daemonStopped &&
                                shownRows.peers.any { it.online == true }
                        ) {
                            Icon(Icons.Default.NetworkPing, stringResource(R.string.action_ping_all))
                        }
                        // The one way to reload: a pull at the top of the list brings out the
                        // search instead, so this button also shows that a load is running.
                        IconButton(onClick = { loadPeers() }, enabled = !isRefreshing) {
                            if (isRefreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                        }
                    }
                )

                // Nothing to search while the service is stopped. Out of the composition
                // while folded, so neither a screen reader nor the keyboard finds a field
                // nobody can see; the top bar's button is the way in for both.
                if (!daemonStopped && searchOut) {
                    Box(
                        Modifier
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                // Measured whole, shown as far as it is out: it slides down
                                // from under the top bar with the finger, fading in as it comes.
                                val placeable = measurable.measure(constraints)
                                reveal.heightPx = placeable.height.toFloat()
                                val height = (placeable.height * reveal.fraction).roundToInt()
                                layout(placeable.width, height) {
                                    placeable.placeWithLayer(0, height - placeable.height) { alpha = reveal.fraction }
                                }
                            }
                    ) {
                        CompactSearchBar(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholderText = stringResource(R.string.peers_search_placeholder),
                            modifier = Modifier
                                .readableWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .focusRequester(searchFocus)
                                // A tap on a field let go of half-way opens it the rest of the way.
                                .onFocusChanged { if (it.isFocused) reveal.animateTo(1f) }
                        )
                    }
                    LaunchedEffect(Unit) {
                        if (focusSearchOnOpen) {
                            focusSearchOnOpen = false
                            searchFocus.requestFocus()
                        }
                    }
                }

                // Only when there is a choice: a list that no filter narrows has just All.
                if (!daemonStopped && errorMsg == null && tabs.size > 1) {
                    ScrollableSlidingSegmentedChips(
                        items = tabs.map { SegmentedChipItem(stringResource(it.label), count = tabRows.getValue(it).size) },
                        selectedIndex = pager.currentPage,
                        onOptionSelected = { coroutineScope.launch { pager.animateScrollToPage(it) } },
                        modifier = Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        height = 36.dp
                    )
                }
            }
        }
    ) { padding ->
        // Held to a readable width on a tablet; see ReadableWidth.
        ReadableWidth {
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (daemonStopped) {
                DaemonStoppedState(onStarted = { loadPeers() })
            } else if (errorMsg != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loadPeers() }) { Text(stringResource(R.string.action_retry)) }
                }
            } else {
                val searching = searchQuery.isNotBlank()
                val copyAsLabel = stringResource(R.string.peer_copy_as)
                key(pager) {
                // A swipe across the list turns the tab, as in Serve & TailCat; the lists only
                // scroll up and down, so the two gestures never compete. The pull that brings
                // out the search reaches the connection from whichever page is under the finger.
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().nestedScroll(reveal.connection),
                    key = { tabs[it] }
                ) { page ->
                    val tab = tabs[page]
                    val rows = tabRows.getValue(tab)
                    LazyColumn(Modifier.fillMaxSize(), state = listStates.getValue(tab), contentPadding = PaddingValues(bottom = 16.dp)) {
                        rows.self?.let { self ->
                            item {
                                PeerItem(
                                    self,
                                    true,
                                    pingStateOf(peerPings[self.getPrimaryIp()]),
                                    nowMillis,
                                    onLongClick = { copyAsPeer = self },
                                    onLongClickLabel = copyAsLabel
                                ) { selectedPeer = self }
                            }
                        }
                        items(rows.peers) { p ->
                            PeerItem(
                                p,
                                false,
                                pingStateOf(peerPings[p.getPrimaryIp()]),
                                nowMillis,
                                onLongClick = { copyAsPeer = p },
                                onLongClickLabel = copyAsLabel
                            ) { selectedPeer = p }
                        }
                        when {
                            // Nothing in this tab matched, not even this device: say so, and
                            // offer the way back. Without a search no tab is empty — one that
                            // holds nothing is not shown.
                            searching && rows.size == 0 -> item {
                                EmptyState(
                                    icon = Icons.Default.SearchOff,
                                    text = stringResource(R.string.state_nothing_found),
                                    modifier = Modifier.fillParentMaxSize(),
                                    actionLabel = stringResource(R.string.state_clear_search),
                                    onAction = { searchQuery = "" }
                                )
                            }
                            // Under this device's own row, so it takes a margin, not the page. Only
                            // once this device has an address: before that — logged out, mid-login —
                            // an empty list says nothing about who else is in the tailnet.
                            tab == PeerTab.ALL && !searching && loaded && peersList.isEmpty() &&
                                !selfPeer?.tailscaleIPs.isNullOrEmpty() -> item {
                                EmptyState(
                                    icon = Icons.Default.Devices,
                                    text = stringResource(R.string.state_peers_alone),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                                )
                            }
                        }
                    }
                }
                }
            }
        }

        selectedPeer?.let { p ->
            val allSelectablePeers = listOfNotNull(shownRows.self) + shownRows.peers
            // By node id, not by the object: PeerData is a data class whose equality covers
            // the traffic counters and the last-seen stamps, so every refresh replaces the
            // selected peer with an equal-looking but unequal instance. indexOf would then
            // return -1, the left arrow would vanish and a swipe right would jump to the top
            // of the list. The same reason self is compared by id below.
            val currentIndex =
                if (p.id != null) allSelectablePeers.indexOfFirst { it.id == p.id }
                else allSelectablePeers.indexOf(p)
            // The list is what the sheet pages through, so the list stays here and the sheet
            // borrows a window onto it: the peer at 0 (the refreshed instance, so the sheet
            // stops rendering the snapshot it was opened on), the two it can slide in under
            // the finger, and the two beyond those, which only decide whether the arriving
            // page draws an arrow of its own. Handing over the neighbouring PeerData rather
            // than bare prev/next callbacks is what lets the sheet draw the next peer while
            // the finger is still down; a callback can only be fired once it is up.
            val peerAt: (Int) -> PeerPage? = { offset ->
                // currentIndex is -1 when a refresh or a search has filtered the selected
                // peer out from under the sheet: then it is the only page there is.
                val target =
                    if (currentIndex < 0) p.takeIf { offset == 0 }
                    else allSelectablePeers.getOrNull(currentIndex + offset)
                target?.let { peer ->
                    PeerPage(
                        peer,
                        isSelf = peer.id?.let { it == selfPeer?.id } ?: (peer === selfPeer),
                        version = peer.id?.let { peerVersions[it] }
                    )
                }
            }

            PeerDetailsModal(
                peerAt = peerAt,
                // The near end of every latency the sheet measures. Taken from selfPeer, not
                // from the rows on screen: a tab or a search that hides this device from the
                // list does not change which address the pings leave from.
                // getPrimaryIp() would hand back its "0.0.0.0" sentinel for a Self the
                // daemon has reported without a tailnet address yet — logged out, or
                // mid-login before the node map lands — and the connection block would draw
                // exactly the arrow pointing at nothing it takes a null to avoid.
                selfAddress = selfPeer?.tailscaleIPs?.firstOrNull(),
                onDismiss = { selectedPeer = null },
                onSendFileClick = { peer -> peerForFileDrop = peer; filePickerLauncher.launch("*/*") },
                onCopyAsClick = { peer -> copyAsPeer = peer },
                onSelectPeer = { peer -> selectedPeer = peer }
            )
        }

        copyAsPeer?.let { p ->
            val qrLabel = stringResource(R.string.qr_show)
            PeerCopyAsSheet(
                peer = p,
                isSelf = p.id?.let { it == selfPeer?.id } ?: (p === selfPeer),
                names = magicDns,
                onDismiss = { copyAsPeer = null },
                extraOptions = { forms ->
                    // What a camera on another device can use: the first web address the peer
                    // serves, else its name, else its IPv4.
                    val target = forms.firstOrNull { it.kind == PeerCopyKind.WEB }
                        ?: forms.firstOrNull { it.kind == PeerCopyKind.FULL_NAME }
                        ?: forms.firstOrNull { it.kind == PeerCopyKind.IPV4 }
                    listOfNotNull(target?.let {
                        PickerOption<() -> Unit>(
                            value = { peerQr = p.getDisplayName() to it.text },
                            label = qrLabel, icon = Icons.Default.QrCode2, supporting = it.text
                        )
                    })
                }
            )
        }
        peerQr?.let { (title, text) -> QrSheet(title = title, text = text, onDismiss = { peerQr = null }) }
        }
    }
}
}

private fun sendFileToPeer(context: Context, uri: Uri, peer: PeerData, scope: CoroutineScope) {
    Toast.makeText(context, context.getString(R.string.peers_sending), Toast.LENGTH_SHORT).show()
    scope.launch(Dispatchers.IO) {
        try {
            // StableNodeID or nothing: the daemon matches file-put targets by ID alone, so a
            // name here would only turn a missing ID into a 404 (see taildropTargetId).
            val target = taildropTargetId(context, peer)
            val originalName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val outDir = File(context.cacheDir, "peer_out").apply { mkdirs() }
            val tmp = File(outDir, originalName)
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o); o.flush() } }
            val res = Appctr.sendFileFromAPI(target, tmp.absolutePath)
            tmp.delete()
            // "OK" is a 2xx from the peer; every failure starts with "Error" and carries the
            // peer's HTTP status and body, or the local reason.
            if (res == "OK") {
                logSentFile(context, originalName, peer.getDisplayName())
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.peers_sent), Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.peers_failed_format, res.removePrefix("Error: ")), Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.peers_failed_format, e.message), Toast.LENGTH_LONG).show() } }
    }
}

/**
 * The peers worth a row: not this device, not a node with no name at all, not
 * a node shared in from another tailnet, and not Funnel's ingress; online ones
 * first. One function for the live load and for a demo, so the two cannot
 * disagree about what the list contains.
 */
internal fun listablePeers(status: StatusResponse): List<PeerData> {
    val selfId = status.self?.id
    return status.peers?.values
        ?.filter { it.id != selfId && (!it.hostName.isNullOrBlank() || !it.dnsName.isNullOrBlank()) && it.shareeNode != true && it.hostName != "funnel-ingress-node" }
        ?.sortedByDescending { it.online == true }
        ?: emptyList()
}

/**
 * The tabs over the peers list. Each is read off the status the list already holds, so a
 * tab costs no request. There is no tab for shared nodes: the netmap's ShareeNode ones —
 * there only because their owner was shared this device — never reach the list, and
 * ipnstate marks nothing as shared in from another tailnet.
 */
enum class PeerTab(@param:StringRes val label: Int) {
    ALL(R.string.peers_tab_all),
    ONLINE(R.string.peers_tab_online),
    OFFLINE(R.string.peers_tab_offline),
    /** Devices of the user this one belongs to. Untagged only: a tagged node belongs to its
     *  tags whoever enrolled it, and its UserID is control's tagged-devices user anyway. */
    MINE(R.string.peers_tab_mine),
    TAGGED(R.string.peers_tab_tagged),
    /** Peers that offer exit routes, the one in use among them. */
    EXIT_NODES(R.string.peers_tab_exit_nodes);

    /** Whether [peer] belongs in this tab. This device counts as online, as its row draws
     *  it, and is never an exit node for itself. */
    fun matches(peer: PeerData, isSelf: Boolean, ownerId: Long?): Boolean = when (this) {
        ALL -> true
        ONLINE -> isSelf || peer.online == true
        OFFLINE -> !isSelf && peer.online != true
        MINE -> peer.tags.isNullOrEmpty() && ownerId != null && peer.userID == ownerId
        TAGGED -> !peer.tags.isNullOrEmpty()
        EXIT_NODES -> !isSelf && peer.exitNodeOption == true
    }
}

/**
 * One tab's rows: this device, while the tab and the search leave it, pinned above the
 * peers. The list and the details sheet's page turn both read this one value — built
 * separately they once disagreed, and a swipe in the sheet landed on a device that was not
 * in the list behind it.
 */
private class TabRows(val self: PeerData?, val peers: List<PeerData>) {
    val size: Int get() = peers.size + if (self != null) 1 else 0
}

/** Every tab's rows under [query], which is already trimmed; empty is no search. */
private fun peerTabRows(self: PeerData?, peers: List<PeerData>, query: String): Map<PeerTab, TabRows> {
    val ownerId = self?.userID
    val selfHit = self?.takeIf { it.matchesQuery(query) }
    val hits = if (query.isEmpty()) peers else peers.filter { it.matchesQuery(query) }
    return PeerTab.entries.associateWith { tab ->
        TabRows(
            selfHit?.takeIf { tab.matches(it, isSelf = true, ownerId) },
            hits.filter { tab.matches(it, isSelf = false, ownerId) }
        )
    }
}

/**
 * The tabs worth a place over this list: All, and every filter that narrows it. One that
 * holds nothing would open on an empty page, and one that holds everything would repeat
 * All — Mine in a tailnet of one person's untagged devices, Online when nothing is offline.
 */
private fun visiblePeerTabs(self: PeerData?, peers: List<PeerData>): List<PeerTab> {
    val total = peers.size + if (self != null) 1 else 0
    val rows = peerTabRows(self, peers, "")
    return PeerTab.entries.filter { it == PeerTab.ALL || rows.getValue(it).size in 1 until total }
}

/** What the search looks at: the name, the address and the OS. */
private fun PeerData.matchesQuery(query: String): Boolean =
    query.isEmpty() ||
        getDisplayName().contains(query, ignoreCase = true) ||
        getPrimaryIp().contains(query) ||
        os?.contains(query, ignoreCase = true) == true

/** The last tab picked by hand, for the next visit. A per-device preference, like the theme. */
private const val PEERS_TAB_PREF = "peers_tab"

private fun storedPeerTab(context: Context): PeerTab {
    val name = GlobalSettings.getString(context, PEERS_TAB_PREF, PeerTab.ALL.name)
    return PeerTab.entries.firstOrNull { it.name == name } ?: PeerTab.ALL
}

/** The search field's slot until a layout measures it: CompactSearchBar's 40 dp and the
 *  8 dp above and below. */
private val SEARCH_SLOT_HEIGHT = 56.dp

/**
 * How far the peers list's search field is out, from 0 to 1, and the scrolling that moves it.
 *
 * A pull down at the top of the list draws it out under the finger; let go, it settles open
 * past half its height and folds back short of that. Empty, it folds again as soon as the
 * list is scrolled on — before the list itself moves, the way a collapsing top bar goes —
 * and while [pinned], with text in it, it stays. [shown] is a saveable state, so a rotation
 * leaves the field as it was.
 */
@Stable
private class SearchReveal(
    shown: MutableFloatState,
    private val scope: CoroutineScope,
    /** The field's full height in pixels; the screen's layout keeps it measured. */
    var heightPx: Float,
    private val pinned: () -> Boolean
) {
    var fraction by shown
        private set
    private var settling: Job? = null

    fun animateTo(target: Float) {
        settling?.cancel()
        settling = scope.launch { animate(fraction, target) { value, _ -> fraction = value } }
    }

    /** Moves the field by [delta] pixels, as far as it goes; returns the pixels that took. */
    private fun drag(delta: Float): Float {
        if (heightPx <= 0f) return 0f
        settling?.cancel()
        val before = fraction
        fraction = (before + delta / heightPx).coerceIn(0f, 1f)
        return (fraction - before) * heightPx
    }

    val connection = object : NestedScrollConnection {
        // The list moving on folds the field first.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f && fraction > 0f && !pinned()) Offset(0f, drag(available.y)) else Offset.Zero

        // The pull is what the list could not take at its top. A finger's only: a fling that
        // runs into the top was not asking for the search.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0f && source == NestedScrollSource.UserInput && fraction < 1f) Offset(0f, drag(available.y))
            else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (fraction > 0f && fraction < 1f) animateTo(if (fraction >= 0.5f || pinned()) 1f else 0f)
            return Velocity.Zero
        }
    }
}
