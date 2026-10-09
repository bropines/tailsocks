package io.github.bropines.tailscaled.admin.logs

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.LoadingIndicatorCompat
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry
import io.github.bropines.tailscaled.admin.api.Rfc3339
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.safety.AuditRecord
import io.github.bropines.tailscaled.admin.safety.AuditResult
import io.github.bropines.tailscaled.admin.safety.ChangeClassBadge
import io.github.bropines.tailscaled.admin.safety.Refusal
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import java.util.Date

/**
 * The Logs tab: the tailnet's configuration audit log, narrowed on the server (who, what,
 * which event, a bounded window), grouped by day, each entry opening to its before → after;
 * and the changes this phone made or was stopped from making.
 */
@Composable
fun LogsTab(
    tailnetLog: Loadable<List<ApiAuditLogEntry>>,
    query: AuditLogQuery,
    onQuery: (AuditLogQuery) -> Unit,
    onRetry: () -> Unit,
    localLog: List<AuditRecord>,
    onClearLocal: () -> Unit,
    startOnLocal: Boolean = false,
    now: Long = System.currentTimeMillis(),
    expandedAtStart: Set<String> = emptySet(),
) {
    val ctx = LocalContext.current
    var which by rememberSaveable { mutableIntStateOf(if (startOnLocal) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        SlidingSegmentedChips(
            options = listOf(ctx.getString(R.string.admin_log_section_tailnet), ctx.getString(R.string.admin_log_section_phone)),
            selectedIndex = which,
            onOptionSelected = { which = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            height = 36.dp,
        )
        if (which == 0) TailnetLog(tailnetLog, query, onQuery, onRetry, now, expandedAtStart)
        else LocalLogList(localLog, onClearLocal, now, Modifier.fillMaxSize())
    }
}

/** A stable key for one entry of a list that has no id of its own. */
fun auditKey(e: ApiAuditLogEntry, index: Int): String = "${e.eventTime}|${e.eventGroupID}|${e.target?.id}|${e.target?.property}|$index"

@Composable
private fun TailnetLog(
    state: Loadable<List<ApiAuditLogEntry>>,
    query: AuditLogQuery,
    onQuery: (AuditLogQuery) -> Unit,
    onRetry: () -> Unit,
    now: Long,
    expandedAtStart: Set<String>,
) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Filter?>(null) }
    var eventPicker by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateOf(expandedAtStart) }
    val entries = state.value.orEmpty()
    val keyed = remember(entries) { entries.mapIndexed { i, e -> auditKey(e, i) to e } }
    val days = remember(keyed) { LogDays.group(keyed, { Rfc3339.parse(it.second.eventTime) }) }

    Column(Modifier.fillMaxSize()) {
        FilterBar(query, onQuery, onEdit = { editing = it }, onEvent = { eventPicker = true })
        Text(
            ctx.resources.getQuantityString(R.plurals.admin_log_count, entries.size, entries.size, windowLabel(ctx, query.window)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                entries.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
                entries.isEmpty() -> EmptyState(
                    Icons.Default.History,
                    ctx.getString(if (query.hasFilters) R.string.admin_log_empty_filtered else R.string.admin_log_empty),
                    actionLabel = if (query.hasFilters) ctx.getString(R.string.admin_log_filter_clear_all) else null,
                    onAction = { onQuery(query.cleared()) },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    days.forEach { day ->
                        item(key = "day-${day.dayStart}") { DayHeader(day.dayStart, now) }
                        items(day.items, key = { it.first }) { (key, entry) ->
                            AuditEntryCard(entry, key in expanded.value) {
                                expanded.value = if (key in expanded.value) expanded.value - key else expanded.value + key
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { f ->
        TextFilterDialog(
            title = ctx.getString(if (f == Filter.ACTOR) R.string.admin_log_filter_actor else R.string.admin_log_filter_target),
            help = ctx.getString(if (f == Filter.ACTOR) R.string.admin_log_filter_actor_help else R.string.admin_log_filter_target_help),
            initial = if (f == Filter.ACTOR) query.actor else query.target,
            onApply = { v -> onQuery(if (f == Filter.ACTOR) query.copy(actor = v.trim()) else query.copy(target = v.trim())) },
            onDismiss = { editing = null },
        )
    }
    if (eventPicker) {
        // A sheet is a window of its own: its words are resolved here, in the app's language.
        PickerSheet(
            title = ctx.getString(R.string.admin_log_filter_event),
            options = listOf(PickerOption(ANY_EVENT, ctx.getString(R.string.admin_log_filter_event_any))) +
                AuditText.events.map { PickerOption(it, AuditText.event(ctx, it), supporting = it) },
            selected = query.event ?: ANY_EVENT,
            onPick = { onQuery(query.copy(event = it.takeIf { e -> e != ANY_EVENT })) },
            onDismiss = { eventPicker = false },
        )
    }
}

private const val ANY_EVENT = ""

private enum class Filter { ACTOR, TARGET }

fun windowLabel(ctx: Context, w: LogWindow): String = when (w) {
    LogWindow.HOUR, LogWindow.DAY -> ctx.resources.getQuantityString(R.plurals.admin_log_window_hours, w.hours, w.hours)
    LogWindow.WEEK, LogWindow.MONTH -> ctx.resources.getQuantityString(R.plurals.pickers_days, w.hours / 24, w.hours / 24)
}

@Composable
private fun FilterBar(query: AuditLogQuery, onQuery: (AuditLogQuery) -> Unit, onEdit: (Filter) -> Unit, onEvent: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogWindow.entries.forEach { w ->
                FilterChip(
                    selected = query.window == w,
                    onClick = { onQuery(query.copy(window = w)) },
                    label = { Text(windowLabel(ctx, w)) },
                    leadingIcon = if (query.window == w) ({ Icon(Icons.Default.Schedule, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValueChip(Icons.Default.Person, ctx.getString(R.string.admin_log_filter_actor), query.actor.ifBlank { null }, { onEdit(Filter.ACTOR) }) {
                onQuery(query.copy(actor = ""))
            }
            ValueChip(Icons.Default.TrackChanges, ctx.getString(R.string.admin_log_filter_target), query.target.ifBlank { null }, { onEdit(Filter.TARGET) }) {
                onQuery(query.copy(target = ""))
            }
            ValueChip(Icons.Default.Event, ctx.getString(R.string.admin_log_filter_event), query.event?.let { AuditText.event(ctx, it) }, onEvent) {
                onQuery(query.copy(event = null))
            }
        }
    }
}

/** A filter as a chip: its name when unset, "name: value" with a clear button when set. */
@Composable
private fun ValueChip(icon: ImageVector, name: String, value: String?, onClick: () -> Unit, onClear: () -> Unit) {
    val ctx = LocalContext.current
    FilterChip(
        selected = value != null,
        onClick = onClick,
        label = { Text(if (value == null) name else ctx.getString(R.string.admin_log_filter_value, name, value), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
        trailingIcon = if (value != null) ({
            Icon(
                Icons.Default.Close,
                contentDescription = ctx.getString(R.string.admin_log_filter_clear, name),
                modifier = Modifier.size(FilterChipDefaults.IconSize).clip(MaterialTheme.shapes.small).clickable(onClick = onClear),
            )
        }) else null,
    )
}

@Composable
private fun TextFilterDialog(title: String, help: String, initial: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                HelpText(help)
            }
        },
        confirmButton = { Button(onClick = { onApply(text); onDismiss() }) { Text(ctx.getString(R.string.admin_log_filter_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Composable
private fun DayHeader(dayStart: Long?, now: Long) {
    val ctx = LocalContext.current
    Text(
        dayLabel(ctx, dayStart, now),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

/** "Today", "Yesterday", or the date with its weekday; entries without a time under "Unknown time". */
fun dayLabel(ctx: Context, dayStart: Long?, now: Long): String = when (dayStart?.let { LogDays.daysAgo(it, now) }) {
    null -> ctx.getString(R.string.admin_log_day_unknown)
    0 -> ctx.getString(R.string.admin_log_day_today)
    1 -> ctx.getString(R.string.admin_log_day_yesterday)
    else -> DateUtils.formatDateTime(ctx, dayStart, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
}

private fun timeOf(ctx: Context, ms: Long?): String = ms?.let { DateFormat.getTimeFormat(ctx).format(Date(it)) }.orEmpty()

@Composable
internal fun ActionBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** One audit event: what, to what, by whom, when; opened, the before → after and the details. */
@Composable
fun AuditEntryCard(e: ApiAuditLogEntry, expanded: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (e.action?.uppercase()) {
        "CREATE", "APPROVE", "ENABLE", "RESTORE", "ACCEPT", "JOIN", "LOGIN" -> Icons.Default.AddCircle to scheme.primary
        "UPDATE" -> Icons.Default.Edit to scheme.tertiary
        "DELETE", "REVOKE", "SUSPEND", "DISABLE", "EXPIRED", "LEAVE", "CANCEL" -> Icons.Default.Delete to scheme.error
        else -> Icons.Default.Info to scheme.secondary
    }
    val changes = remember(e) { AuditDiff.of(e.old, e.new) }
    val property = AuditText.property(ctx, e.target?.property)
    val stateText = ctx.getString(if (expanded) R.string.admin_log_entry_expanded else R.string.admin_log_entry_collapsed)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClickLabel = ctx.getString(R.string.admin_log_entry_toggle), onClick = onToggle)
            .semantics { stateDescription = stateText },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionBadge(icon, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AuditText.title(ctx, e) + (property?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        color = tint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(timeOf(ctx, Rfc3339.parse(e.eventTime)), style = MaterialTheme.typography.labelSmall, color = scheme.outline, maxLines = 1, softWrap = false)
                }
                (e.target?.name?.takeIf { it.isNotBlank() } ?: e.target?.id)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
                }
                Text(actorLine(ctx, e), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!expanded && changes.isNotEmpty()) {
                    Text(
                        summary(ctx, changes.first(), property) + if (changes.size > 1) " " + ctx.resources.getQuantityString(R.plurals.admin_log_more_changes, changes.size - 1, changes.size - 1) else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                e.error?.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = scheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (expanded) EntryDetails(e, changes, property)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = scheme.outline)
        }
    }
}

private fun actorLine(ctx: Context, e: ApiAuditLogEntry): String {
    val a = e.actor
    val who = listOfNotNull(a?.displayName?.takeIf { it.isNotBlank() }, a?.loginName?.takeIf { it.isNotBlank() && it != a.displayName })
        .joinToString(" · ").ifBlank { AuditText.actorType(ctx, a?.type) ?: a?.id ?: ctx.getString(R.string.admin_log_unknown) }
    return ctx.getString(R.string.admin_log_entry_by, who)
}

/** One change in a line: "Name: old → new", "+ tag:web", "− tag:old". */
private fun summary(ctx: Context, c: AuditChange, property: String?): String {
    val label = c.path.ifBlank { property.orEmpty() }.let { if (it.isBlank()) "" else "$it: " }
    return when (c) {
        is AuditChange.Changed -> "$label${c.before} → ${c.after}"
        is AuditChange.Added -> "$label+ ${c.value}"
        is AuditChange.Removed -> "$label− ${c.value}"
        is AuditChange.Text -> label + ctx.resources.getQuantityString(
            R.plurals.admin_log_text_lines_changed, c.lines.count { it.kind == TextLine.Kind.ADDED || it.kind == TextLine.Kind.REMOVED },
            c.lines.count { it.kind == TextLine.Kind.ADDED || it.kind == TextLine.Kind.REMOVED },
        )
    }
}

@Composable
private fun EntryDetails(e: ApiAuditLogEntry, changes: List<AuditChange>, property: String?) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (changes.isNotEmpty()) AuditChangesBlock(changes, property)
        else if (e.old != null || e.new != null) Text(ctx.getString(R.string.admin_log_no_change), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AuditText.actorType(ctx, e.actor?.type)?.let { Detail(ctx.getString(R.string.admin_log_detail_actor_type), it) }
                e.actor?.tags?.takeIf { it.isNotEmpty() }?.let { Detail(ctx.getString(R.string.admin_log_detail_actor_tags), it.joinToString(", ")) }
                AuditText.origin(ctx, e.origin)?.let { Detail(ctx.getString(R.string.admin_log_detail_origin), it) }
                e.target?.id?.takeIf { it.isNotBlank() }?.let { Detail(ctx.getString(R.string.admin_log_detail_target_id), it, mono = true) }
                if (e.target?.isEphemeral == true) Detail(ctx.getString(R.string.admin_log_detail_ephemeral), ctx.getString(R.string.admin2_yes))
                e.eventGroupID?.takeIf { it.isNotBlank() }?.let { Detail(ctx.getString(R.string.admin_log_detail_group), it, mono = true) }
                e.actionDetails?.takeIf { it.isNotBlank() }?.let { Detail(ctx.getString(R.string.admin_log_detail_details), it) }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, mono: Boolean = false) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/**
 * Before → after, readable without colour: a change shows the old value struck through, an
 * addition a "+", a removal a "−"; a policy file shows its changed lines with a little context.
 */
@Composable
fun AuditChangesBlock(changes: List<AuditChange>, property: String?) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(scheme.surfaceContainerHigh).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        changes.forEach { c ->
            val label = c.path.ifBlank { property ?: ctx.getString(R.string.admin_log_value) }
            when (c) {
                is AuditChange.Changed -> Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                    Text(c.before, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.error, textDecoration = TextDecoration.LineThrough)
                    Text("→ " + c.after, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.primary, fontWeight = FontWeight.Medium)
                }
                is AuditChange.Added -> Text(
                    "$label: + ${c.value}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.primary,
                )
                is AuditChange.Removed -> Text(
                    "$label: − ${c.value}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.error,
                    textDecoration = TextDecoration.LineThrough,
                )
                is AuditChange.Text -> Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                    SelectionContainer {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            c.lines.forEach { l ->
                                when (l.kind) {
                                    TextLine.Kind.GAP -> Text(
                                        ctx.resources.getQuantityString(R.plurals.admin_log_text_folded, l.folded, l.folded),
                                        style = MaterialTheme.typography.labelSmall, color = scheme.outline,
                                    )
                                    else -> Text(
                                        when (l.kind) {
                                            TextLine.Kind.ADDED -> "+ "
                                            TextLine.Kind.REMOVED -> "− "
                                            else -> "  "
                                        } + l.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        softWrap = false,
                                        color = when (l.kind) {
                                            TextLine.Kind.ADDED -> scheme.primary
                                            TextLine.Kind.REMOVED -> scheme.error
                                            else -> scheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The changes this phone sent or was stopped from sending, by day; kept on the phone only. */
@Composable
fun LocalLogList(records: List<AuditRecord>, onClear: () -> Unit, now: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val days = remember(records) { LogDays.group(records, { it.time }) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HelpText(ctx.getString(R.string.admin_log_phone_help), modifier = Modifier.weight(1f))
            if (records.isNotEmpty()) TextButton(onClick = onClear) { Text(ctx.getString(R.string.admin_log_phone_clear)) }
        }
        if (records.isEmpty()) {
            EmptyState(Icons.Default.History, ctx.getString(R.string.admin_log_phone_empty))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                days.forEach { day ->
                    item(key = "day-${day.dayStart}") { DayHeader(day.dayStart, now) }
                    items(day.items, key = { "${it.time}|${it.targetId}|${it.kind}" }) { LocalRecordCard(it) }
                }
            }
        }
    }
}

/** One change from this phone: how it ended (in words and an icon), what it did, to what. */
@Composable
fun LocalRecordCard(record: AuditRecord) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (record.result) {
        AuditResult.VERIFIED, AuditResult.APPLIED -> Icons.Default.CheckCircle to scheme.primary
        AuditResult.MISMATCH -> Icons.Default.Warning to scheme.tertiary
        AuditResult.FAILED -> Icons.Default.ErrorOutline to scheme.error
        AuditResult.REFUSED -> Icons.Default.Block to scheme.outline
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionBadge(if (record.undo) Icons.AutoMirrored.Filled.Undo else icon, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ConsoleText.auditResult(ctx, record.result) + if (record.undo) " · " + ctx.getString(R.string.admin_log_phone_undo) else "",
                        style = MaterialTheme.typography.titleSmall, color = tint, modifier = Modifier.weight(1f),
                    )
                    Text(timeOf(ctx, record.time), style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                }
                Text(record.targetName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(record.effect, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                ChangeClassBadge(record.changeClass)
                // A refusal is stored by name; it reads as the sentence the console showed then.
                record.detail?.let { d ->
                    if (record.result == AuditResult.REFUSED) runCatching { ConsoleText.refusal(ctx, Refusal.valueOf(d)) }.getOrDefault(d) else d
                }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (record.result == AuditResult.REFUSED) scheme.onSurfaceVariant else scheme.error) }
                if (record.requestIds.isNotEmpty()) {
                    Text(
                        record.requestIds.joinToString(", ") { ctx.getString(R.string.admin2_error_request_id, it) },
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.outline,
                    )
                }
            }
        }
    }
}
