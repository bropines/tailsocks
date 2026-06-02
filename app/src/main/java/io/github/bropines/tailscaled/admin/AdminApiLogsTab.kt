package io.github.bropines.tailscaled.admin

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.LogEntry
import io.github.bropines.tailscaled.ui.getDebugHeader
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

@Composable
fun AdminApiLogsTabContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var allLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val categories = listOf("ALL", "ERROR", "CORE", "TAILSCALE", "OTHER")

    val displayedLogs = remember(allLogs, selectedCategory, searchQuery) {
        allLogs.filter { log ->
            val matchCategory = selectedCategory == "ALL" || log.category.uppercase() == selectedCategory
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
            delay(3000)
        }
    }

    LaunchedEffect(displayedLogs.size) {
        if (isAutoScroll && displayedLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayedLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search logs...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )

            IconButton(onClick = {
                val fullLog = getDebugHeader(context) + Appctr.getLogs()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks Logs", fullLog))
                Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
            }

            IconButton(onClick = {
                saveFileLauncher.launch("tailsocks_admin_logs_${System.currentTimeMillis()}.txt")
            }) {
                Icon(Icons.Default.Save, contentDescription = "Save Logs")
            }

            IconButton(onClick = { showClearDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = MaterialTheme.colorScheme.error)
            }
        }

        // Categories Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${displayedLogs.size} log entries displayed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-Scroll", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
                Switch(
                    checked = isAutoScroll,
                    onCheckedChange = { isAutoScroll = it },
                    thumbContent = null,
                    modifier = Modifier.scale(0.8f)
                )
            }
        }

        // Logs List
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            if (displayedLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching logs", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(displayedLogs) { log ->
                            val color = when (log.level.uppercase()) {
                                "ERROR" -> MaterialTheme.colorScheme.error
                                "WARNING" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "[${log.timestamp.substringAfter("T").substringBefore(".")}]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    Text(
                                        text = "${log.category.uppercase()}:",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                                Text(
                                    text = log.message,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = color,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear system logs?") },
            text = { Text("Are you sure you want to permanently clear the in-memory system logs? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        Appctr.clearLogs()
                        allLogs = emptyList()
                        showClearDialog = false
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// Simple modifier helper to scale components easily
fun Modifier.scale(scale: Float): Modifier = this.then(
    androidx.compose.ui.draw.scale(scale)
)
