package io.github.bropines.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

@Keep
data class LogEntry(
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("level") val level: String,
    @SerializedName("category") val category: String,
    @SerializedName("message") val message: String
)

class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                LogsScreen(onBack = { finish() })
            }
        }
    }
}

fun getDebugHeader(context: Context): String {
    val verName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "unknown" }
    val coreVer = try { Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
    return """
        --- TAILSOCKS DEBUG INFO ---
        App Version: $verName
        Tailscale Core: $coreVer
        Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})
        Arch: ${Build.SUPPORTED_ABIS.joinToString(", ")}
        ----------------------------
        
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var allLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    var isAutoScroll by remember { mutableStateOf(true) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val categories = listOf("ALL", "ERROR", "CORE", "TAILSCALE", "OTHER")

    val displayedLogs = remember(allLogs, selectedCategory, searchQuery) {
        allLogs.filter { log ->
            val matchCategory = selectedCategory == "ALL" || log.category == selectedCategory
            val matchQuery = searchQuery.isEmpty() || log.message.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fullLog = getDebugHeader(context) + Appctr.getLogs()
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        OutputStreamWriter(os).use { writer -> writer.write(fullLog) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Logs saved", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun loadLogsData() {
        coroutineScope.launch(Dispatchers.IO) {
            val jsonString = try { Appctr.getLogsJSON() } catch (e: Exception) { "[]" }
            val logsList: List<LogEntry> = try {
                Gson().fromJson(jsonString, object : TypeToken<List<LogEntry>>() {}.type)
            } catch (e: Exception) { emptyList() }

            withContext(Dispatchers.Main) {
                allLogs = logsList
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadLogsData()
            delay(2000)
        }
    }

    LaunchedEffect(displayedLogs.size) {
        if (isAutoScroll && displayedLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayedLogs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("System logs") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { 
                            Appctr.flushDNS()
                            Toast.makeText(context, "DNS Cache Flushed", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.CleaningServices, contentDescription = "Flush DNS") }

                        IconButton(onClick = { 
                            val fullLog = getDebugHeader(context) + Appctr.getLogs()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks Logs", fullLog))
                            Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                        
                        IconButton(onClick = { saveFileLauncher.launch("tailsocks_logs_${System.currentTimeMillis()}.txt") }) { Icon(Icons.Default.Save, contentDescription = "Save") }
                    }
                )
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category; isAutoScroll = true }, label = { Text(category) })
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                coroutineScope.launch(Dispatchers.IO) {
                    Appctr.clearLogs()
                    withContext(Dispatchers.Main) {
                        allLogs = emptyList()
                        Toast.makeText(context, "Cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Icon(Icons.Default.Delete, contentDescription = "Clear") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 4f) }
                    }
                ) {
                    items(displayedLogs) { log ->
                        val textColor = if (log.category == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Text(
                            text = "${log.timestamp} [${log.category}] ${log.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = (12 * scale).sp,
                            color = textColor,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
