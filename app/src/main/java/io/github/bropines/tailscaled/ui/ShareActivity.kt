package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.res.stringResource
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
    var accountMenuExpanded by remember { mutableStateOf(false) }

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

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.share_files_count_format, fileUris.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { loadPeers() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    Surface(
                        onClick = { accountMenuExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(currentAccount.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DropdownMenu(
                        expanded = accountMenuExpanded,
                        onDismissRequest = { accountMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        accounts.forEach { acc ->
                            val isActive = acc.id == currentAccount.id
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        acc.name, 
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    ) 
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (isActive) Icons.Default.Check else Icons.Default.AccountCircle,
                                            contentDescription = null,
                                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    accountMenuExpanded = false
                                    if (acc.id != currentAccount.id) {
                                        AccountManager.setActiveAccount(context, acc.id)
                                        currentAccount = acc
                                        context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                                    }
                                },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }

            if (isLoadingPeers) Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { CircularProgressIndicator() }
            else if (errorMsg != null) Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center); Button(onClick = { loadPeers() }) { Text(stringResource(R.string.action_retry)) }
            } else Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(peers) { p -> 
                            PeerShareItem(p, !isSending) {
                                isSending = true
                                scope.launch(Dispatchers.IO) {
                                    sendFilesWithProgress(context, fileUris, p) { sendProgressText = it }
                                    withContext(Dispatchers.Main) { isSending = false; onDismiss() }
                                }
                            } 
                        }
                    }
                }
            }
        }
    }

    if (isSending) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.share_sending), fontWeight = FontWeight.Bold)
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
            val target = if (!peer.id.isNullOrEmpty()) peer.id else (peer.hostName ?: peer.dnsName ?: peer.getDisplayName())
            Appctr.sendFileFromAPI(target, tmp.absolutePath)
            logSentFile(context, originalName, peer.getDisplayName())
            tmp.delete()
        } catch (e: Exception) {}
    }
}
