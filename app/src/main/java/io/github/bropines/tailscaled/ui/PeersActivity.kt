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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

class PeersActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { PeersScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(onBack: () -> Unit, initialQuery: String = "") {
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
    var searchQuery by remember { mutableStateOf(initialQuery) }
    var selectedPeer by remember { mutableStateOf<PeerData?>(null) }
    var peerForFileDrop by remember { mutableStateOf<PeerData?>(null) }
    // The peer whose "Copy as…" is open — from a long press on its row, or from the details
    // sheet's button, over that sheet. Its forms are built when it opens, never for the list.
    var copyAsPeer by remember { mutableStateOf<PeerData?>(null) }
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

    val filteredPeers = remember(peersList, searchQuery) {
        if (searchQuery.isBlank()) peersList
        else peersList.filter { 
            it.getDisplayName().contains(searchQuery, ignoreCase = true) || 
            it.getPrimaryIp().contains(searchQuery) ||
            it.os?.contains(searchQuery, ignoreCase = true) == true
        }
    }
    // This device, but only while the search leaves it on screen. The row below and the
    // sheet's page turn both read this one value: built separately they disagreed — the
    // list dropped self on a search that does not match its name while the sheet still
    // paged onto it, so a swipe right from the first result landed on a device that was
    // not in the list behind the sheet.
    val visibleSelfPeer = remember(selfPeer, searchQuery) {
        selfPeer?.takeIf {
            searchQuery.isBlank() || it.getDisplayName().contains(searchQuery, ignoreCase = true)
        }
    }

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
                        // One round trip per node, a few at a time; the figures land in the
                        // rows as they arrive rather than all at the end.
                        IconButton(
                            onClick = {
                                pingingAll = true
                                StatusAsides.bump(context, StatusAsides.PINGS)
                                coroutineScope.launch {
                                    // This device is not pinged: a node cannot measure a
                                    // round trip to itself, and the row would only ever
                                    // show a failure.
                                    pingAll(
                                        filteredPeers.filter { it.online == true }.map { it.getPrimaryIp() },
                                        peerPings
                                    )
                                    pingingAll = false
                                }
                            },
                            enabled = !pingingAll && !isRefreshing && !daemonStopped
                        ) {
                            Icon(Icons.Default.NetworkPing, stringResource(R.string.action_ping_all))
                        }
                        IconButton(onClick = {
                            loadPeers()
                        }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) }
                    }
                )
                
                // Nothing to search while the service is stopped.
                if (!daemonStopped) {
                    CompactSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholderText = stringResource(R.string.peers_search_placeholder),
                        modifier = Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        // Held to a readable width on a tablet; see ReadableWidth.
        ReadableWidth {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadPeers() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (daemonStopped) {
                // In a list, so a pull still re-checks.
                LazyColumn(Modifier.fillMaxSize()) {
                    item { DaemonStoppedState(onStarted = { loadPeers() }, modifier = Modifier.fillParentMaxSize()) }
                }
            } else if (errorMsg != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loadPeers() }) { Text(stringResource(R.string.action_retry)) }
                }
            } else {
                val searching = searchQuery.isNotBlank()
                val copyAsLabel = stringResource(R.string.peer_copy_as)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (visibleSelfPeer != null) {
                        item {
                            PeerItem(
                                visibleSelfPeer,
                                true,
                                pingStateOf(peerPings[visibleSelfPeer.getPrimaryIp()]),
                                nowMillis,
                                onLongClick = { copyAsPeer = visibleSelfPeer },
                                onLongClickLabel = copyAsLabel
                            ) { selectedPeer = visibleSelfPeer }
                        }
                    }
                    items(filteredPeers) { p ->
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
                        // Not even this device matched: say so, and offer the way back.
                        searching && filteredPeers.isEmpty() && visibleSelfPeer == null -> item {
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
                        !searching && loaded && peersList.isEmpty() &&
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

        selectedPeer?.let { p ->
            val allSelectablePeers = listOfNotNull(visibleSelfPeer) + filteredPeers
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
                // from visibleSelfPeer: a search that hides this device from the list does
                // not change which address the pings leave from.
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
            PeerCopyAsSheet(
                peer = p,
                isSelf = p.id?.let { it == selfPeer?.id } ?: (p === selfPeer),
                names = magicDns,
                onDismiss = { copyAsPeer = null }
            )
        }
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
