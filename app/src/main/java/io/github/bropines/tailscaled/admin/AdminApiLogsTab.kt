package io.github.bropines.tailscaled.admin

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiLogsTabContent(client: TailscaleApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var auditLogs by remember { mutableStateOf<List<ApiAuditLogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var daysRange by remember { mutableIntStateOf(7) } // Default 7 days
    var searchQuery by remember { mutableStateOf("") }
    var selectedActionFilter by remember { mutableStateOf("ALL") }

    var lastFetchTime by remember { mutableLongStateOf(0L) }
    var lastFetchedRange by remember { mutableIntStateOf(-1) }

    fun getRfc3339Time(timeMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timeMs))
    }

    fun loadAuditLogs(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        val rangeChanged = daysRange != lastFetchedRange
        if (!forceRefresh && !rangeChanged && now - lastFetchTime < 60 * 1000L && auditLogs.isNotEmpty()) {
            return
        }

        if (forceRefresh) isRefreshing = true else isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val nowMs = System.currentTimeMillis()
                val end = getRfc3339Time(nowMs)
                val start = getRfc3339Time(nowMs - daysRange * 24 * 60 * 60 * 1000L)
                val logsList = client.getAuditLogs(start, end)
                withContext(Dispatchers.Main) {
                    auditLogs = logsList
                    lastFetchTime = now
                    lastFetchedRange = daysRange
                    isLoading = false
                    isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error loading audit logs: ${e.message}", Toast.LENGTH_LONG).show()
                    isLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(daysRange) {
        loadAuditLogs(forceRefresh = false)
    }

    val filteredLogs = remember(auditLogs, searchQuery, selectedActionFilter) {
        auditLogs.filter { log ->
            val matchesAction = selectedActionFilter == "ALL" || log.action?.uppercase() == selectedActionFilter
            val query = searchQuery.trim()
            val matchesQuery = query.isEmpty() ||
                    (log.actor?.displayName?.contains(query, ignoreCase = true) == true) ||
                    (log.actor?.loginName?.contains(query, ignoreCase = true) == true) ||
                    (log.target?.name?.contains(query, ignoreCase = true) == true) ||
                    (log.target?.id?.contains(query, ignoreCase = true) == true) ||
                    (log.action?.contains(query, ignoreCase = true) == true) ||
                    (log.type?.contains(query, ignoreCase = true) == true)
            matchesAction && matchesQuery
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls and Filters Row
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
                placeholder = { Text("Search audit logs...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )

            var expandedRangeDropdown by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expandedRangeDropdown = true }) {
                    Text("$daysRange Days")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = expandedRangeDropdown,
                    onDismissRequest = { expandedRangeDropdown = false }
                ) {
                    listOf(1, 3, 7, 14, 30).forEach { days ->
                        DropdownMenuItem(
                            text = { Text("$days Days") },
                            onClick = {
                                daysRange = days
                                expandedRangeDropdown = false
                            }
                        )
                    }
                }
            }
        }

        // Action Filter Chips
        val actionsList = listOf("ALL", "CREATE", "UPDATE", "DELETE")
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(actionsList) { action ->
                FilterChip(
                    selected = selectedActionFilter == action,
                    onClick = { selectedActionFilter = action },
                    label = { Text(action) }
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
                text = "${filteredLogs.size} audit events found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadAuditLogs(forceRefresh = true) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No audit log events found", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredLogs) { log ->
                            AuditLogCard(log = log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AuditLogCard(log: ApiAuditLogEntry) {
    val actionText = log.action?.uppercase() ?: "UNKNOWN"
    val (actionIcon, actionColor) = when (actionText) {
        "CREATE" -> Icons.Default.AddCircle to Color(0xFF4CAF50)
        "UPDATE" -> Icons.Default.Edit to Color(0xFF2196F3)
        "DELETE" -> Icons.Default.Delete to Color(0xFFF44336)
        else -> Icons.Default.Info to MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(actionColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(actionIcon, null, tint = actionColor, modifier = Modifier.size(18.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$actionText (${log.target?.type ?: log.type ?: "CONFIG"})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = actionColor
                    )
                    Text(
                        text = formatLogTime(log.eventTime),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Actor: ${log.actor?.displayName ?: "System"} (${log.actor?.loginName ?: "system"})",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )

                log.target?.name?.let { targetName ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Target: $targetName",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                log.origin?.let { origin ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Origin: $origin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

fun formatLogTime(isoTime: String?): String {
    if (isoTime.isNullOrEmpty()) return ""
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        
        val cleanTime = isoTime.substringBefore(".") // Ignore nanoseconds
        val date = format.parse(cleanTime) ?: return isoTime
        
        val displayFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        displayFormat.format(date)
    } catch (e: Exception) {
        isoTime
    }
}
