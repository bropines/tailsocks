package io.github.bropines.tailscaled

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { FilesScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val taildropDir = remember(activeAccount.id) { File(context.filesDir, "states/${activeAccount.id}/taildrop").apply { if (!exists()) mkdirs() } }

    var files by remember { mutableStateOf<List<TaildropFile>>(emptyList()) }
    var sentFiles by remember { mutableStateOf<List<SentFileEntry>>(emptyList()) }
    var peers by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var selfPeer by remember { mutableStateOf<PeerData?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    var selectedUriForSend by remember { mutableStateOf<Uri?>(null) }
    var showPeerPicker by remember { mutableStateOf(false) }
    var isSendingFile by remember { mutableStateOf(false) }
    var sendProgressText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) { selectedUriForSend = uri; showPeerPicker = true }
    }

    var isSavingFile by remember { mutableStateOf(false) }
    var fileToSaveManual by remember { mutableStateOf<TaildropFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null && fileToSaveManual != null) {
            scope.launch(Dispatchers.IO) { saveFileToUri(context, fileToSaveManual!!, uri); withContext(Dispatchers.Main) { fileToSaveManual = null } }
        }
    }

    fun refreshData() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            // 1. Load incoming files
            try {
                val json = if (BuildConfig.IS_DEV) {
                    Appctr.getTaildropFilesFromAPI()
                } else {
                    Appctr.getWaitingFiles(taildropDir.absolutePath)
                }
                val newFiles: List<TaildropFile> = Gson().fromJson(json, object : TypeToken<List<TaildropFile>>() {}.type) ?: emptyList()
                withContext(Dispatchers.Main) { files = newFiles }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to load waiting files", e)
            }

            // 2. Load peers status
            try {
                val pJson = if (BuildConfig.IS_DEV) {
                    Appctr.getStatusFromAPI()
                } else {
                    Appctr.getStatusFromAPI()
                }
                
                if (!pJson.startsWith("Error")) {
                    val status = Gson().fromJson(pJson, StatusResponse::class.java)
                    val newPeers = status.peers?.values?.toList()?.sortedWith(
                        compareByDescending<PeerData> { it.online == true }.thenBy { it.getDisplayName() }
                    ) ?: emptyList()
                    withContext(Dispatchers.Main) {
                        peers = newPeers
                        selfPeer = status.self
                    }
                } else {
                    android.util.Log.w("FilesActivity", "Status source error: $pJson")
                }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to parse status JSON", e)
            }

            // 3. Load sent history
            try {
                val hf = File(context.filesDir, "sent_history.json")
                if (hf.exists()) {
                    val newHistory: List<SentFileEntry> = Gson().fromJson(hf.readText(), object : TypeToken<List<SentFileEntry>>() {}.type) ?: emptyList()
                    withContext(Dispatchers.Main) { sentFiles = newHistory }
                }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to load sent history", e)
            }

            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    LaunchedEffect(activeAccount.id) { refreshData() }
    LaunchedEffect(selectedTab) { refreshData() }

    fun handleSaveRequest(file: TaildropFile) {
        val rootUri = GlobalSettings.getTaildropRootUri(context)
        if (rootUri != null) {
            scope.launch(Dispatchers.IO) {
                isSavingFile = true
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: throw Exception("Folder access error")
                    rootDoc.findFile(file.Name)?.delete()
                    val newFile = rootDoc.createFile("*/*", file.Name) ?: throw Exception("Create failed")
                    saveFileToUri(context, file, newFile.uri)
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Auto-save failed: ${e.message}", Toast.LENGTH_SHORT).show(); fileToSaveManual = file; saveLauncher.launch(file.Name) }
                } finally { withContext(Dispatchers.Main) { isSavingFile = false } }
            }
        } else { fileToSaveManual = file; saveLauncher.launch(file.Name) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Column { Text("Taildrop Hub"); Text(activeAccount.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { IconButton(onClick = { Appctr.forceRefresh(); refreshData() }) { Icon(Icons.Default.Refresh, "Refresh") } })
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Inbox") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Devices") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("History") })
                }
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = { filePickerLauncher.launch("*/*") }) { Icon(Icons.Default.FileUpload, "Send") } }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> if (files.isEmpty() && !isLoading) EmptyState(Icons.Default.Inbox, "No incoming files") 
                    else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(files) { f -> FileCard(f, { openTaildropFile(context, f) }, { handleSaveRequest(f) }, { 
                            val deleted = if (BuildConfig.IS_DEV) Appctr.deleteTaildropFileFromAPI(f.Name) else Appctr.deleteTaildropFile(f.Path)
                            if (deleted) refreshData() 
                        }) }
                    }
                1 -> if (peers.isEmpty() && selfPeer == null && !isLoading) EmptyState(Icons.Default.Devices, "No devices") 
                    else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (selfPeer != null) {
                            this@LazyColumn.item { PeerItem(selfPeer!!, true) {} }
                        }
                        this@LazyColumn.items(peers) { p -> 
                            PeerItem(p, false) { Toast.makeText(context, "Use FAB to send", Toast.LENGTH_SHORT).show() }
                        }
                    }
                2 -> if (sentFiles.isEmpty() && !isLoading) EmptyState(Icons.Default.History, "No history yet") 
                    else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sentFiles) { e -> SentFileCard(e) }
                    }
            }
            if (isLoading || isSavingFile) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (isSendingFile) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { 
                        CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Sending..."); Text(sendProgressText, style = MaterialTheme.typography.bodySmall) 
                    }
                }
            }
        }

        if (showPeerPicker && selectedUriForSend != null) {
            ModalBottomSheet(onDismissRequest = { showPeerPicker = false }, containerColor = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text("Select Device", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (peers.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { 
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No devices found", color = MaterialTheme.colorScheme.outline)
                                TextButton(onClick = { refreshData() }) { Text("Refresh") }
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            this@LazyColumn.items(peers) { p -> 
                                PeerShareItem(p, !isSendingFile) {
                                    showPeerPicker = false; isSendingFile = true
                                    scope.launch(Dispatchers.IO) {
                                        sendSingleFileInActivity(context, selectedUriForSend!!, p) { sendProgressText = it }
                                        withContext(Dispatchers.Main) { isSendingFile = false; selectedUriForSend = null; refreshData() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun saveFileToUri(context: Context, file: TaildropFile, destUri: Uri) {
    try { File(file.Path).inputStream().use { input -> context.contentResolver.openOutputStream(destUri)?.use { output -> input.copyTo(output); output.flush() } } }
    catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_SHORT).show() } }
}

private suspend fun sendSingleFileInActivity(context: Context, uri: Uri, peer: PeerData, onProgress: (String) -> Unit) {
    try {
        val originalName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        onProgress("Preparing $originalName...")
        val outDir = File(context.cacheDir, "taildrop_out").apply { mkdirs() }
        val tmp = File(outDir, originalName)
        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o); o.flush() } }
        onProgress("Uploading...")
        val res = if (BuildConfig.IS_DEV && !peer.id.isNullOrEmpty()) {
            Appctr.sendFileFromAPI(peer.id, tmp.absolutePath)
        } else {
            val target = peer.hostName ?: peer.dnsName ?: peer.getDisplayName()
            Appctr.sendFile(target, tmp.absolutePath)
        }
        
        if (res.isBlank() || !(res.contains("error", true) || res.contains("failed", true))) {
            logSentFile(context, originalName, peer.getDisplayName())
            withContext(Dispatchers.Main) { Toast.makeText(context, "Sent!", Toast.LENGTH_SHORT).show() }
        } else withContext(Dispatchers.Main) { Toast.makeText(context, "Error: $res", Toast.LENGTH_LONG).show() }
        tmp.delete()
    } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() } }
}
