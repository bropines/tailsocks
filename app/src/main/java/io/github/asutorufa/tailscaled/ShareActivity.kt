package io.github.asutorufa.tailscaled

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val fileUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        
        if (fileUri == null || !ProxyState.isActualRunning()) {
            Toast.makeText(this, "Tailscale is not running or no file selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
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
                // Копируем файл из Uri во временную папку, т.к. Tailscale нужен реальный путь
                val contentResolver = context.contentResolver
                val fileName = "shared_file_${System.currentTimeMillis()}"
                val tempFile = File(context.cacheDir, fileName)
                
                contentResolver.openInputStream(fileUri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Отправляем: tailscale file cp /path/to/file peername:
                // ВАЖНО: двоеточие в конце имени пира обязательно!
                val targetName = peer.hostName ?: peer.getDisplayName()
                Appctr.runTailscaleCmd("file cp ${tempFile.absolutePath} ${targetName}:")
                
                tempFile.delete() // Чистим за собой
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sent to ${peer.getDisplayName()}", Toast.LENGTH_SHORT).show()
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