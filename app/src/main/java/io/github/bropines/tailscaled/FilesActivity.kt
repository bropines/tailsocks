package io.github.bropines.tailscaled

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                FilesScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    
    val taildropDir = File(context.filesDir, "taildrop")
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var sentFiles by remember { mutableStateOf<List<SentFileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshFiles() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            if (!taildropDir.exists()) taildropDir.mkdirs()
            val list = taildropDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
            withContext(Dispatchers.Main) {
                files = list
                isLoading = false
            }
        }
    }

    fun refreshSentLog() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val historyFile = File(context.filesDir, "sent_history.json")
                if (historyFile.exists()) {
                    val type = object : TypeToken<List<SentFileEntry>>() {}.type
                    val list: List<SentFileEntry> = Gson().fromJson(historyFile.readText(), type)
                    withContext(Dispatchers.Main) {
                        sentFiles = list
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        sentFiles = emptyList()
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) refreshFiles() else refreshSentLog()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Taildrop Manager") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (selectedTab == 0) refreshFiles() else refreshSentLog() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Inbox") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Sent Log") })
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTab == 0) {
                if (files.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No received files yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            FileItem(
                                file = file,
                                onOpen = { openFile(context, file) },
                                onSave = { 
                                    saveToDownloads(context, file)
                                    refreshFiles()
                                },
                                onDelete = {
                                    file.delete()
                                    refreshFiles()
                                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                if (sentFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No sent history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(sentFiles) { entry ->
                            SentFileItem(entry)
                            HorizontalDivider()
                        }
                    }
                }
            }
            
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun FileItem(file: File, onOpen: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    val sizeStr = formatFileSize(file.length())

    ListItem(
        headlineContent = { Text(file.name, fontWeight = FontWeight.Bold) },
        supportingContent = { Text("$dateStr • $sizeStr") },
        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Row {
                IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, "Open") }
                IconButton(onClick = onSave) { Icon(Icons.Default.Download, "Save") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
            }
        }
    )
}

@Composable
fun SentFileItem(entry: SentFileEntry) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    ListItem(
        headlineContent = { Text(entry.name, fontWeight = FontWeight.Bold) },
        supportingContent = { Text("To: ${entry.target} • $dateStr") },
        leadingContent = { Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
    )
}

fun openFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open file"))
    } catch (e: Exception) {
        Toast.makeText(context, "Can't open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun saveToDownloads(context: Context, file: File) {
    try {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val destFile = File(downloadsDir, file.name)
        file.copyTo(destFile, overwrite = true)
        Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
