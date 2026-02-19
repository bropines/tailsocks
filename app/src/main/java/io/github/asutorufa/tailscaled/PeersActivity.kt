package io.github.asutorufa.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PeersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                PeersScreen(onBack = { finish() })
            }
        }
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
    
    // Стейт для понимания, кому мы сейчас отправляем файл
    var peerForFileDrop by remember { mutableStateOf<PeerData?>(null) }

    // Лаунчер для выбора файла
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val targetPeer = peerForFileDrop
        if (uri != null && targetPeer != null) {
            sendFileToPeer(context, uri, targetPeer, coroutineScope)
        }
        peerForFileDrop = null // Сбрасываем стейт
    }

    fun loadPeers() {
        isRefreshing = true
        errorMsg = null
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val jsonOutput = Appctr.runTailscaleCmd("status --json")
                    if (jsonOutput.isEmpty() || jsonOutput.startsWith("Error")) {
                        throw Exception("Daemon not running or returned error")
                    }
                    val status = Gson().fromJson(jsonOutput, StatusResponse::class.java)
                    val parsedPeers = status.peers?.values?.toList() ?: emptyList()
                    val sortedList = parsedPeers.sortedWith(
                        compareByDescending<PeerData> { it.online == true }
                            .thenBy { it.getDisplayName() }
                    )
                    withContext(Dispatchers.Main) {
                        selfPeer = status.self
                        peersList = sortedList
                        isRefreshing = false
                        if (selfPeer == null && sortedList.isEmpty()) {
                            errorMsg = "No peers found."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isRefreshing = false
                        errorMsg = "Error: ${e.message}\nMake sure Tailscale is running."
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadPeers() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Network Devices") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { loadPeers() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
                if (isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    selfPeer?.let { self ->
                        item {
                            Text(
                                text = "This Device",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                            PeerItem(peer = self, isSelf = true) { selectedPeer = self }
                        }
                    }

                    if (peersList.isNotEmpty()) {
                        item {
                            Text(
                                text = "Other Devices",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                            )
                        }
                        items(peersList, key = { it.id ?: it.hashCode() }) { peer ->
                            PeerItem(peer = peer, isSelf = false) { selectedPeer = peer }
                        }
                    }
                }
            }
        }

        if (selectedPeer != null) {
            PeerDetailsModal(
                peer = selectedPeer!!,
                onDismiss = { selectedPeer = null },
                onSendFileClick = {
                    peerForFileDrop = selectedPeer
                    filePickerLauncher.launch("*/*") // Открываем выбор любых файлов
                }
            )
        }
    }
}

// Функция отправки файла
private fun sendFileToPeer(context: Context, fileUri: Uri, peer: PeerData, coroutineScope: CoroutineScope) {
    Toast.makeText(context, "Sending to ${peer.getDisplayName()}...", Toast.LENGTH_SHORT).show()
    coroutineScope.launch(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "drop_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            
            val targetName = peer.hostName ?: peer.getDisplayName()
            val cmd = "file cp ${tempFile.absolutePath} ${targetName}:"
            
            // Выполняем команду и собираем лог
            val res = Appctr.runTailscaleCmd(cmd)
            tempFile.delete()
            
            withContext(Dispatchers.Main) {
                // Если res пустой - значит команда выполнилась успешно и молча
                val msg = if (res.trim().isEmpty()) "Success!" else "Result: $res"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun PeerItem(peer: PeerData, isSelf: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.os?.take(2)?.uppercase() ?: "??",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.getDisplayName(),
                    fontWeight = FontWeight.Bold,
                    color = if (isSelf) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                )
                
                val ipText = if (peer.hostName != peer.getDisplayName()) {
                    "${peer.getPrimaryIp()} • ${peer.hostName}"
                } else {
                    peer.getPrimaryIp()
                }
                
                Text(
                    text = ipText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (isSelf) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            val statusColor = if (peer.online == true || isSelf) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailsModal(peer: PeerData, onDismiss: () -> Unit, onSendFileClick: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var isPinging by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Заголовок
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = peer.getDisplayName(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (pingResult != null) {
                    Text(
                        text = pingResult!!,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Кнопки действий
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isPinging) return@Button
                        isPinging = true
                        pingResult = "Pinging..."
                        coroutineScope.launch(Dispatchers.IO) {
                            val out = try { Appctr.runTailscaleCmd("ping ${peer.getPrimaryIp()}") } catch (e: Exception) { "Error" }
                            val lines = out.split("\n")
                            val pongLine = lines.find { it.contains("pong from") } ?: "Ping failed or timeout"
                            withContext(Dispatchers.Main) {
                                pingResult = pongLine.trim()
                                isPinging = false
                            }
                        }
                    }
                ) {
                    Text(if (isPinging) "..." else "Ping")
                }

                // НОВАЯ КНОПКА ОТПРАВКИ ФАЙЛА
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSendFileClick
                ) {
                    Text("Send File")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(peer.getDetailsList()) { (label, value) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                                Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}