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

// Модель данных для лога (должна совпадать с тем, что отдает Go в JSON)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var allLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val categories = listOf("ALL", "ERROR", "CORE", "TAILSCALE", "OTHER")

    // Фильтрация: сначала по категории, потом по поисковому запросу
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
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        OutputStreamWriter(os).use { writer -> writer.write(Appctr.getLogs()) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Логи сохранены", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun loadLogsData() {
        coroutineScope.launch(Dispatchers.IO) {
            val jsonString = try { Appctr.getLogsJSON() } catch (e: Exception) { "[]" }
            
            val logsList: List<LogEntry> = try {
                val type = object : TypeToken<List<LogEntry>>() {}.type
                Gson().fromJson(jsonString, type)
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                allLogs = logsList
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadLogsData()
            delay(2000) // Обновляем каждые 2 секунды
        }
    }

    LaunchedEffect(displayedLogs.size) {
        if (isAutoScroll && displayedLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayedLogs.size - 1)
        }
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
                    title = { Text("System logs") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { 
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks Logs", Appctr.getLogs()))
                            Toast.makeText(context, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy everything") }
                        
                        IconButton(onClick = { saveFileLauncher.launch("tailscaled_logs_${System.currentTimeMillis()}.txt") }) { Icon(Icons.Default.Save, contentDescription = "Save to File") }
                        
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
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search in logs...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { 
                                selectedCategory = category
                                isAutoScroll = true 
                            },
                            label = { Text(category) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                Appctr.clearLogs()
                allLogs = emptyList()
                Toast.makeText(context, "Logs clear", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Delete, contentDescription = "Clear") }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Включаем возможность выделения текста
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 4f)
                            }
                        }
                ) {
                    items(displayedLogs) { log ->
                        val textColor = if (log.category == "ERROR") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }

                        Text(
                            text = "${log.timestamp} [${log.category}] ${log.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = (12 * scale).sp,
                            color = textColor,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}