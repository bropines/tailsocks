package io.github.bropines.tailscaled

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        
        val fileUri = if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else null
        
        if (fileUri == null || !ProxyState.isActualRunning()) {
            Toast.makeText(this, "Tailscale is not running or no file selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            TailSocksTheme {
                ShareScreen(fileUri = fileUri, onDismiss = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(fileUri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var peers by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isSending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val json = Appctr.runTailscaleCmd("status --json")
                val status = Gson().fromJson(json, StatusResponse::class.java)
                peers = status.peers?.values?.toList()?.sortedByDescending { it.online == true } ?: emptyList()
            } catch (e: Exception) {}
        }
    }

    fun sendFile(peer: PeerData) {
        isSending = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(context, fileUri) ?: "file_${System.currentTimeMillis()}"
                val tempFile = File(context.cacheDir, fileName)
                
                context.contentResolver.openInputStream(fileUri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val targetName = peer.dnsName ?: peer.hostName ?: peer.getDisplayName()
                val result = Appctr.runTailscaleCmd("file cp ${tempFile.absolutePath} ${targetName}:")
                
                logSentFile(context, fileName, peer.getDisplayName())
                tempFile.delete()
                
                withContext(Dispatchers.Main) {
                    if (result.isNotBlank() && (result.contains("error", true) || result.contains("failed", true))) {
                        Toast.makeText(context, "Tailscale: $result", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Sent to ${peer.getDisplayName()}", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    isSending = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Send via TailDrop") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                    }
                )
                if (isSending) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(peers) { peer ->
                ListItem(
                    headlineContent = { Text(peer.getDisplayName(), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(peer.os ?: "Unknown OS") },
                    trailingContent = { Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = !isSending) { sendFile(peer) }
                )
                HorizontalDivider()
            }
        }
    }
}

fun logSentFile(context: Context, fileName: String, targetName: String) {
    try {
        val historyFile = File(context.filesDir, "sent_history.json")
        val gson = Gson()
        val type = object : com.google.gson.reflect.TypeToken<MutableList<SentFileEntry>>() {}.type
        
        val history: MutableList<SentFileEntry> = if (historyFile.exists()) {
            gson.fromJson(historyFile.readText(), type)
        } else {
            mutableListOf()
        }
        
        history.add(0, SentFileEntry(fileName, targetName, System.currentTimeMillis()))
        if (history.size > 50) history.removeAt(history.size - 1)
        
        historyFile.writeText(gson.toJson(history))
    } catch (e: Exception) {}
}

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result
}
