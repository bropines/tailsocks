package io.github.bropines.tailscaled

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
    var selectedPeer by remember { mutableStateOf<PeerData?>(null) }
    var peerForFileDrop by remember { mutableStateOf<PeerData?>(null) }

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
                // Принудительно очищаем списки для визуального фидбека
                withContext(Dispatchers.Main) {
                    selfPeer = null
                    peersList = emptyList()
                }
                
                // 2. Load peers status
                val json = Appctr.getStatusFromAPI()
                
                if (json.isNullOrBlank() || json.startsWith("Error")) {
                    throw Exception(if (json.isNullOrBlank()) "Daemon not running" else json)
                }
                val status = Gson().fromJson(json, StatusResponse::class.java)
                
                withContext(Dispatchers.Main) {
                    selfPeer = status.self
                    peersList = status.peers?.values?.toList()?.sortedByDescending { it.online == true } ?: emptyList()
                    isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    isRefreshing = false
                    errorMsg = e.message ?: "Network error" 
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadPeers() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Network Devices") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { 
                    Appctr.forceRefresh()
                    loadPeers() 
                }) { Icon(Icons.Default.Refresh, "Refresh") } })
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (errorMsg != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loadPeers() }) { Text("Retry") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (selfPeer != null) {
                        item { PeerItem(selfPeer!!, true) { selectedPeer = selfPeer } }
                    }
                    items(peersList) { p -> 
                        PeerItem(p, false) { selectedPeer = p } 
                    }
                }
            }
            if (isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        selectedPeer?.let { p ->
            PeerDetailsModal(p, { selectedPeer = null }, { peerForFileDrop = p; filePickerLauncher.launch("*/*") })
        }
    }
}

private fun sendFileToPeer(context: Context, uri: Uri, peer: PeerData, scope: CoroutineScope) {
    Toast.makeText(context, "Sending...", Toast.LENGTH_SHORT).show()
    scope.launch(Dispatchers.IO) {
        try {
            val originalName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val outDir = File(context.cacheDir, "peer_out").apply { mkdirs() }
            val tmp = File(outDir, originalName)
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o); o.flush() } }
            val target = peer.hostName ?: peer.dnsName ?: peer.getDisplayName()
            Appctr.sendFile(target, tmp.absolutePath)
            logSentFile(context, originalName, peer.getDisplayName())
            tmp.delete()
            withContext(Dispatchers.Main) { Toast.makeText(context, "Sent!", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show() } }
    }
}
