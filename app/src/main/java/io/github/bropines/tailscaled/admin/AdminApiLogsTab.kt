package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.safety.AuditRecord
import io.github.bropines.tailscaled.admin.safety.AuditResult
import io.github.bropines.tailscaled.admin.safety.ChangeClassBadge
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.core.ScrollableSlidingSegmentedChips
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two logs: the tailnet's configuration audit log from the server, and this console's own
 * record of every change it sent or stopped, kept on the phone.
 */
@Composable
fun AdminApiLogsTabContent(
    tailnetLog: Loadable<List<ApiAuditLogEntry>>,
    daysRange: Int,
    onDaysRangeChange: (Int) -> Unit,
    onRetry: () -> Unit,
    localLog: List<AuditRecord>,
    onClearLocal: () -> Unit,
) {
    val ctx = LocalContext.current
    var which by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SlidingSegmentedChips(
            options = listOf(ctx.getString(R.string.admin2_logs_tailnet), ctx.getString(R.string.admin2_logs_this_phone)),
            selectedIndex = which,
            onOptionSelected = { which = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            height = 36.dp,
        )
        if (which == 0) TailnetLog(tailnetLog, daysRange, onDaysRangeChange, onRetry)
        else LocalLog(localLog, onClearLocal)
    }
}

@Composable
private fun TailnetLog(
    state: Loadable<List<ApiAuditLogEntry>>,
    daysRange: Int,
    onDaysRangeChange: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var actionFilter by remember { mutableStateOf("ALL") }
    // A sheet is a window of its own; its strings come from here (wrapContextWithLocale).
    val ctx = LocalContext.current
    fun daysLabel(days: Int): String = ctx.resources.getQuantityString(R.plurals.pickers_days, days, days)
    var rangePicker by remember { mutableStateOf(false) }
    if (rangePicker) {
        PickerSheet(
            title = ctx.getString(R.string.pickers_logs_period_title),
            options = listOf(1, 3, 7, 14, 30).map { PickerOption(it, daysLabel(it)) },
            selected = daysRange,
            onPick = onDaysRangeChange,
            onDismiss = { rangePicker = false },
        )
    }
    val logs = state.value.orEmpty()
    val filtered = remember(logs, searchQuery, actionFilter) {
        val q = searchQuery.trim()
        logs.filter { log ->
            (actionFilter == "ALL" || log.action?.uppercase() == actionFilter) && (q.isEmpty() || listOfNotNull(
                log.actor?.displayName, log.actor?.loginName, log.target?.name, log.target?.id, log.action, log.target?.property,
            ).any { it.contains(q, ignoreCase = true) })
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                // One line, cut: the Russian placeholder wrapped beside the period button.
                placeholder = { Text(ctx.getString(R.string.admin_logs_search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
            OutlinedButton(onClick = { rangePicker = true }, shape = MaterialTheme.shapes.medium) {
                Text(daysLabel(daysRange), maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
        val actions = listOf("ALL", "CREATE", "UPDATE", "DELETE")
        ScrollableSlidingSegmentedChips(
            options = actions,
            selectedIndex = actions.indexOf(actionFilter).coerceAtLeast(0),
            onOptionSelected = { actionFilter = actions[it] },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            height = 36.dp,
        )
        Text(
            ctx.getString(R.string.admin_logs_events_found, filtered.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                logs.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
                filtered.isEmpty() -> EmptyState(Icons.Default.History, ctx.getString(R.string.admin_logs_no_events))
                else -> SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filtered) { log -> AuditLogCard(log) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun AuditLogCard(log: ApiAuditLogEntry) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val action = log.action?.uppercase() ?: "?"
    val (icon, tint) = when (action) {
        "CREATE" -> Icons.Default.AddCircle to scheme.primary
        "UPDATE" -> Icons.Default.Edit to scheme.tertiary
        "DELETE" -> Icons.Default.Delete to scheme.error
        else -> Icons.Default.Info to scheme.secondary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionIcon(icon, tint)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ctx.getString(R.string.admin_logs_action_type_format, action, log.target?.property ?: log.target?.type ?: log.type ?: "CONFIG"),
                        style = MaterialTheme.typography.titleSmall,
                        color = tint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(formatLogTime(log.eventTime), style = MaterialTheme.typography.labelSmall, color = scheme.outline, maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    ctx.getString(R.string.admin_logs_actor_prefix, log.actor?.displayName ?: log.actor?.type ?: "—", log.actor?.loginName ?: log.actor?.id ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                (log.target?.name ?: log.target?.id)?.let {
                    Text(ctx.getString(R.string.admin_logs_target_prefix, it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                }
                if (log.old != null || log.new != null) {
                    Text(
                        "${log.old?.toString() ?: "—"} → ${log.new?.toString() ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                log.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
                log.origin?.let { Text(ctx.getString(R.string.admin_logs_origin_prefix, it), style = MaterialTheme.typography.labelSmall, color = scheme.outline) }
            }
        }
    }
}

@Composable
private fun LocalLog(records: List<AuditRecord>, onClear: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HelpText(ctx.getString(R.string.admin2_local_log_help), modifier = Modifier.weight(1f))
            if (records.isNotEmpty()) TextButton(onClick = onClear) { Text(ctx.getString(R.string.admin2_local_log_clear)) }
        }
        if (records.isEmpty()) {
            EmptyState(Icons.Default.History, ctx.getString(R.string.admin2_local_log_empty))
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(records) { LocalAuditCard(it) }
                }
            }
        }
    }
}

@Composable
fun LocalAuditCard(record: AuditRecord) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (record.result) {
        AuditResult.VERIFIED, AuditResult.APPLIED -> Icons.Default.CheckCircle to scheme.primary
        AuditResult.MISMATCH -> Icons.Default.Warning to scheme.tertiary
        AuditResult.FAILED -> Icons.Default.ErrorOutline to scheme.error
        AuditResult.REFUSED -> Icons.Default.Block to scheme.outline
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionIcon(if (record.undo) Icons.AutoMirrored.Filled.Undo else icon, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ConsoleText.auditResult(ctx, record.result), style = MaterialTheme.typography.titleSmall, color = tint, modifier = Modifier.weight(1f))
                    Text(
                        SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(record.time)),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.outline,
                    )
                }
                Text(record.effect, style = MaterialTheme.typography.bodySmall)
                Text("${record.kind.name.lowercase().replace('_', ' ')} · ${record.targetName}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                ChangeClassBadge(record.changeClass)
                record.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
                if (record.requestIds.isNotEmpty()) {
                    Text(record.requestIds.joinToString(", ") { ctx.getString(R.string.admin2_error_request_id, it) }, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.outline)
                }
            }
        }
    }
}

fun formatLogTime(isoTime: String?): String {
    val date = parseIso(isoTime) ?: return isoTime.orEmpty()
    return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)
}
