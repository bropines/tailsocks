package io.github.bropines.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                LogsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    // ЗУМ
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        OutputStreamWriter(os).use { writer -> writer.write(Appctr.getLogs()) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Logs saved successfully", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun loadLogsData() {
        coroutineScope.launch(Dispatchers.IO) {
            val logsString = try { Appctr.getLogs() } catch (e: Exception) { "" }
            val logsList = if (logsString.isEmpty()) emptyList() else logsString.split("\n").filter { it.isNotEmpty() }
            withContext(Dispatchers.Main) {
                logs = logsList
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadLogsData()
            delay(2000)
        }
    }

    LaunchedEffect(logs.size) {
        if (isAutoScroll && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            isAutoScroll = lastVisibleItem?.index == listState.layoutInfo.totalItemsCount - 1
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("System Logs") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { 
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Tailscale Logs", Appctr.getLogs()))
                            Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.List, contentDescription = "Copy") }
                        
                        IconButton(onClick = { saveFileLauncher.launch("tailscaled_logs_${System.currentTimeMillis()}.txt") }) { Icon(Icons.Default.Done, contentDescription = "Save File") }
                        
                        IconButton(onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, Appctr.getLogs())
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share logs"))
                        }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                    }
                )
                if (isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                Appctr.clearLogs()
                logs = emptyList()
                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Delete, contentDescription = "Clear Logs") }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                    }
                }
        ) {
            items(logs) { logLine ->
                Text(
                    text = logLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (12 * scale).sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}