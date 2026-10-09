package io.github.bropines.tailscaled.admin.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.ui.HelpText

/**
 * Tags over several devices: what to do and with which tags, then the dry run — every selected
 * device before → after, or why it is left alone — and only then the HIGH confirmation. After
 * the run, each device's result.
 */
@Composable
fun BulkTagsSheet(
    devices: List<ApiDevice>,
    draft: BulkDraft,
    results: List<BulkOutcome>?,
    policyTags: List<String>,
    onDraft: (BulkDraft) -> Unit,
    onApply: (List<BulkTagRow>) -> Unit,
    onClose: () -> Unit,
) {
    DeviceSheet(onDismiss = onClose) {
        BulkTagsContent(devices, draft, results, policyTags, onDraft, onApply, onClose)
    }
}

/** The sheet's body, apart so a preview can draw it without the sheet's window. */
@Composable
fun BulkTagsContent(
    devices: List<ApiDevice>,
    draft: BulkDraft,
    results: List<BulkOutcome>?,
    policyTags: List<String>,
    onDraft: (BulkDraft) -> Unit,
    onApply: (List<BulkTagRow>) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    var typed by rememberSaveable { mutableStateOf("") }
    val rows = remember(devices, draft.mode, draft.tags) { BulkTags.plan(devices, draft.mode, draft.tags) }

    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            ctx.resources.getQuantityString(R.plurals.admin_dev_bulk_title, devices.size, devices.size),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (results != null) {
            Results(results, onClose)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BulkTagMode.entries.forEach { m ->
                val on = draft.mode == m
                FilterChip(
                    selected = on,
                    onClick = { onDraft(draft.copy(mode = m)) },
                    label = { Text(ctx.getString(modeLabel(m))) },
                    leadingIcon = if (on) {
                        { Icon(Icons.Default.Done, null, Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        HelpText(
            ctx.getString(
                when (draft.mode) {
                    BulkTagMode.ADD -> R.string.admin_dev_bulk_mode_add_desc
                    BulkTagMode.REMOVE -> R.string.admin_dev_bulk_mode_remove_desc
                    BulkTagMode.REPLACE -> R.string.admin_dev_bulk_mode_replace_desc
                }
            )
        )
        TagPicker(
            offered = (policyTags + devices.flatMap { it.tags } + draft.offered).distinct().sorted(),
            selected = draft.tags,
            typed = typed,
            onToggle = { t -> onDraft(draft.copy(tags = if (t in draft.tags) draft.tags - t else draft.tags + t)) },
            onTyped = { typed = it },
            onAdd = { t ->
                onDraft(draft.copy(tags = (draft.tags + t).distinct(), offered = (draft.offered + t).distinct()))
                typed = ""
            },
        )

        if (draft.tags.isNotEmpty()) {
            Text(ctx.getString(R.string.admin_dev_bulk_preview), style = MaterialTheme.typography.titleSmall)
            Text(
                ctx.getString(
                    R.string.admin_dev_bulk_summary,
                    rows.count { it.changes },
                    rows.count { it.skip == BulkSkip.UNCHANGED },
                    rows.count { it.skip == BulkSkip.SHARED || it.skip == BulkSkip.LAST_TAG },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val owned = rows.count { it.takesOwnership }
            if (owned > 0) DialogWarning(ctx.resources.getQuantityString(R.plurals.admin_dev_bulk_owner_warning, owned, owned))
            rows.forEach { PlanRow(it) }
        }

        Button(
            onClick = { onApply(rows) },
            enabled = draft.tags.isNotEmpty() && rows.any { it.changes },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) { Text(ctx.getString(R.string.admin_dev_bulk_review)) }
    }
}

private fun modeLabel(m: BulkTagMode) = when (m) {
    BulkTagMode.ADD -> R.string.admin_dev_bulk_mode_add
    BulkTagMode.REMOVE -> R.string.admin_dev_bulk_mode_remove
    BulkTagMode.REPLACE -> R.string.admin_dev_bulk_mode_replace
}

/** One device of the dry run: before struck through, after plain — or why it is left alone. */
@Composable
private fun PlanRow(row: BulkTagRow) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val nothing = ctx.getString(R.string.admin2_confirm_nothing)
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.device.shortName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            when (row.skip) {
                null -> {
                    Row {
                        Text(ctx.getString(R.string.admin2_confirm_before) + ": ", style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                        Text(
                            ConsoleText.list(row.before) ?: nothing,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.error,
                            textDecoration = if (row.before.isNotEmpty()) TextDecoration.LineThrough else null,
                        )
                    }
                    Row {
                        Text(ctx.getString(R.string.admin2_confirm_after) + ": ", style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                        Text(
                            ConsoleText.list(row.after) ?: nothing,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = scheme.primary,
                        )
                    }
                    if (row.takesOwnership) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = scheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                ctx.getString(R.string.admin_dev_bulk_row_owner, row.device.user?.takeIf { it.isNotBlank() } ?: nothing),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.error,
                            )
                        }
                    }
                }
                else -> Text(
                    ctx.getString(
                        when (row.skip) {
                            BulkSkip.SHARED -> R.string.admin_dev_bulk_skip_shared
                            BulkSkip.UNCHANGED -> R.string.admin_dev_bulk_skip_unchanged
                            BulkSkip.LAST_TAG -> R.string.admin_dev_bulk_skip_last_tag
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.outline,
                )
            }
        }
    }
}

@Composable
private fun Results(results: List<BulkOutcome>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val done = results.count { it.state == BulkState.VERIFIED || it.state == BulkState.APPLIED }
    val failed = results.count { it.state == BulkState.MISMATCH || it.state == BulkState.FAILED || it.state == BulkState.NOT_SENT }
    Text(ctx.getString(R.string.admin_dev_bulk_results), style = MaterialTheme.typography.titleSmall)
    Text(ctx.getString(R.string.admin_dev_bulk_results_summary, done, failed), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    val running = results.any { it.state == BulkState.PENDING }
    if (running) LinearProgressIndicator(progress = { (done + failed).toFloat() / results.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
    results.forEach { r ->
        val (icon, tint, text) = when (r.state) {
            BulkState.PENDING -> Triple(Icons.Default.HourglassEmpty, scheme.outline, ctx.getString(R.string.admin_dev_bulk_pending))
            BulkState.VERIFIED -> Triple(Icons.Default.CheckCircle, scheme.primary, ctx.getString(R.string.admin2_result_verified))
            BulkState.APPLIED -> Triple(Icons.Default.Check, scheme.primary, ctx.getString(R.string.admin2_result_applied))
            BulkState.MISMATCH -> Triple(Icons.Default.Warning, scheme.tertiary, ctx.getString(R.string.admin2_result_mismatch))
            BulkState.FAILED -> Triple(
                Icons.Default.ErrorOutline, scheme.error,
                ctx.getString(R.string.admin_dev_bulk_failed, r.error?.let { ConsoleText.error(ctx, it) }.orEmpty()),
            )
            BulkState.NOT_SENT -> Triple(
                Icons.Default.Block, scheme.outline,
                ctx.getString(R.string.admin_dev_bulk_not_sent, r.error?.let { ConsoleText.error(ctx, it) }.orEmpty()),
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(20.dp).padding(top = 2.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
    Button(onClick = onClose, enabled = !running, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Text(ctx.getString(R.string.admin_dev_bulk_done))
    }
}
