package io.github.bropines.tailscaled

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileUris = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (fileUris.isNullOrEmpty()) { finish(); return }
        setContent { TailSocksTheme { ShareOverlay(fileUris = fileUris, onDismiss = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareOverlay(fileUris: List<Uri>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    
    var currentAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { AccountManager.getAccounts(context) }
    var peers by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoadingPeers by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var sendProgressText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun loadPeers() {
        isLoadingPeers = true
        scope.launch(Dispatchers.IO) {
            try {
                val json = Appctr.getStatusFromAPI()
                if (json.startsWith("Error")) throw Exception(json)
                val status = Gson().fromJson(json, StatusResponse::class.java)
                peers = status.peers?.values?.toList()?.sortedByDescending { it.online == true } ?: emptyList()
                withContext(Dispatchers.Main) { isLoadingPeers = false; errorMsg = null }
            } catch (e: Exception) { withContext(Dispatchers.Main) { errorMsg = e.message; isLoadingPeers = false } }
        }
    }

    LaunchedEffect(currentAccount) { loadPeers() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.fillMaxHeight(0.85f)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Send to...", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${fileUris.size} files", style = MaterialTheme.typography.bodySmall)
                }
                Surface(onClick = {
                    if (accounts.size > 1) {
                        val next = accounts[(accounts.indexOf(currentAccount) + 1) % accounts.size]
                        AccountManager.setActiveAccount(context, next.id)
                        currentAccount = next
                        context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
                    }
                }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(currentAccount.name, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }

            if (isLoadingPeers) Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { CircularProgressIndicator() }
            else if (errorMsg != null) Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center); Button(onClick = { loadPeers() }) { Text("Retry") }
            } else LazyColumn(Modifier.weight(1f)) {
                items(peers) { p -> PeerShareItem(p, !isSending) {
                    isSending = true
                    scope.launch(Dispatchers.IO) {
                        sendFilesWithProgress(context, fileUris, p) { sendProgressText = it }
                        withContext(Dispatchers.Main) { isSending = false; onDismiss() }
                    }
                } }
            }
        }
    }

    if (isSending) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.height(20.dp))
                Text("Sending...", fontWeight = FontWeight.Bold)
                Text(sendProgressText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

private suspend fun sendFilesWithProgress(context: Context, uris: List<Uri>, peer: PeerData, onProgress: (String) -> Unit) {
    uris.forEachIndexed { i, uri ->
        val originalName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        onProgress("${i + 1}/${uris.size}\n$originalName")
        try {
            val outDir = File(context.cacheDir, "share_out").apply { mkdirs() }
            val tmp = File(outDir, originalName)
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { output -> input.copyTo(output); output.flush() } }
            val target = peer.hostName ?: peer.dnsName ?: peer.getDisplayName()
            Appctr.sendFile(target, tmp.absolutePath)
            logSentFile(context, originalName, peer.getDisplayName())
            tmp.delete()
        } catch (e: Exception) {}
    }
}
