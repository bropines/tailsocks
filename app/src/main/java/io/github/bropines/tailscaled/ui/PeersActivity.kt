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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { PeersScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selfPeer by remember { mutableStateOf<PeerData?>(null) }
    var peersList by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedPeer by remember { mutableStateOf<PeerData?>(null) }
    var peerForFileDrop by remember { mutableStateOf<PeerData?>(null) }

    val filteredPeers = remember(peersList, searchQuery) {
        if (searchQuery.isBlank()) peersList
        else peersList.filter { 
            it.getDisplayName().contains(searchQuery, ignoreCase = true) || 
            it.getPrimaryIp().contains(searchQuery) ||
            it.os?.contains(searchQuery, ignoreCase = true) == true
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
                // 2. Load peers status
                val json = Appctr.getStatusFromAPI()
                
                if (json.isNullOrBlank() || json.startsWith("Error")) {
                    throw Exception(if (json.isNullOrBlank()) context.getString(R.string.peers_daemon_not_running) else json)
                }
                val status = Gson().fromJson(json, StatusResponse::class.java)
                
                withContext(Dispatchers.Main) {
                    selfPeer = status.self
                    val selfId = status.self?.id
                    peersList = status.peers?.values
                        ?.filter { it.id != selfId && (!it.hostName.isNullOrBlank() || !it.dnsName.isNullOrBlank()) && it.shareeNode != true && it.hostName != "funnel-ingress-node" }
                        ?.toList()
                        ?.sortedByDescending { it.online == true } ?: emptyList()
                    isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    isRefreshing = false
                    errorMsg = e.message ?: context.getString(R.string.peers_network_error)
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadPeers() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.peers_title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                    actions = { IconButton(onClick = { 
                        loadPeers() 
                    }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) } })
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.peers_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadPeers() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (errorMsg != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loadPeers() }) { Text(stringResource(R.string.action_retry)) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (selfPeer != null && (searchQuery.isBlank() || selfPeer!!.getDisplayName().contains(searchQuery, ignoreCase = true))) {
                        item { PeerItem(selfPeer!!, true) { selectedPeer = selfPeer } }
                    }
                    items(filteredPeers) { p -> 
                        PeerItem(p, false) { selectedPeer = p } 
                    }
                }
            }
        }

        selectedPeer?.let { p ->
            val allSelectablePeers = listOfNotNull(selfPeer) + filteredPeers
            val currentIndex = allSelectablePeers.indexOf(p)
            val prevPeer = if (currentIndex > 0) allSelectablePeers[currentIndex - 1] else null
            val nextPeer = if (currentIndex < allSelectablePeers.size - 1) allSelectablePeers[currentIndex + 1] else null

            PeerDetailsModal(
                peer = p,
                onDismiss = { selectedPeer = null },
                onSendFileClick = { peerForFileDrop = p; filePickerLauncher.launch("*/*") },
                onPrevPeer = if (prevPeer != null) { { selectedPeer = prevPeer } } else null,
                onNextPeer = if (nextPeer != null) { { selectedPeer = nextPeer } } else null
            )
        }
    }
}

private fun sendFileToPeer(context: Context, uri: Uri, peer: PeerData, scope: CoroutineScope) {
    Toast.makeText(context, context.getString(R.string.peers_sending), Toast.LENGTH_SHORT).show()
    scope.launch(Dispatchers.IO) {
        try {
            val originalName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val outDir = File(context.cacheDir, "peer_out").apply { mkdirs() }
            val tmp = File(outDir, originalName)
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o); o.flush() } }
            val target = if (!peer.id.isNullOrEmpty()) peer.id else (peer.hostName ?: peer.dnsName ?: peer.getDisplayName())
            Appctr.sendFileFromAPI(target, tmp.absolutePath)
            logSentFile(context, originalName, peer.getDisplayName())
            tmp.delete()
            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.peers_sent), Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.peers_failed_format, e.message), Toast.LENGTH_LONG).show() } }
    }
}
