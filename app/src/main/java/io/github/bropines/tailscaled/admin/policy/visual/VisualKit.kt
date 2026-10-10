package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The visual editor's shared pieces: selectors as chips and labels, the field that edits a list
 * of them, the card every element sits in, the divider a heading comment becomes. Sections build
 * on these and on SelectorPickerSheet; they do not draw their own chips.
 */

/** The icon a selector of [kind] carries on its chip, so a chip reads without its colour. */
fun kindIcon(kind: SelectorKind): ImageVector = when (kind) {
    SelectorKind.ANY -> Icons.Default.Public
    SelectorKind.AUTOGROUP -> Icons.Default.AutoAwesome
    SelectorKind.GROUP -> Icons.Default.Groups
    SelectorKind.TAG -> Icons.AutoMirrored.Filled.Label
    SelectorKind.USER -> Icons.Default.Person
    SelectorKind.USER_DOMAIN -> Icons.Default.Domain
    SelectorKind.HOST -> Icons.Default.Dns
    SelectorKind.IP, SelectorKind.CIDR, SelectorKind.IP_RANGE -> Icons.Default.Lan
    SelectorKind.IPSET -> Icons.Default.Hub
    SelectorKind.SERVICE -> Icons.Default.Apps
    SelectorKind.POSTURE -> Icons.Default.VerifiedUser
    SelectorKind.LOCALPART -> Icons.Default.Badge
    SelectorKind.EXTERNAL -> Icons.Default.Link
    SelectorKind.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * A selector on a card, not tappable: its icon and plain words, [suffix] after them (the ports
 * of a destination: "· 80, 443"). A [problem] outlines it in the error colour and adds the
 * error icon, and says what is wrong to a screen reader.
 */
@Composable
fun SelectorLabel(value: String, modifier: Modifier = Modifier, suffix: String? = null, problem: SelectorProblem? = null, hosts: Set<String> = emptySet()) {
    val ctx = LocalContext.current
    val kind = Selectors.parse(value, hosts).kind
    val text = VisualText.label(ctx, value) + suffix.orEmpty()
    Surface(
        modifier = modifier.semantics { if (problem != null) contentDescription = "$text: ${VisualText.problem(ctx, problem)}" },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = problem?.let { BorderStroke(1.dp, MaterialTheme.colorScheme.error) },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (problem != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Selectors on a card, wrapped: one row of a rule ("Who", "Can reach"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectorLabels(values: List<String>, modifier: Modifier = Modifier, suffix: (String) -> String? = { null }, hosts: Set<String> = emptySet()) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { SelectorLabel(it, suffix = suffix(it), hosts = hosts) }
    }
}

/**
 * A list of selectors being edited: [title], a chip per value and an Add chip that opens the
 * picker for [slot]. Tapping a chip removes it — unless [onChipClick] gives chips an editor of
 * their own (a destination's ports), which then offers the removal itself. Each value is
 * checked with [check]; a problem outlines its chip and is said under the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectorField(
    title: String,
    values: List<String>,
    slot: SelectorSlot,
    env: VisualEnv,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    suffix: (String) -> String? = { null },
    onChipClick: ((String) -> Unit)? = null,
    check: (String) -> SelectorProblem? = { v -> env.model?.let { SelectorRules.check(v, slot, it, env.headscale) } },
) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val hosts = env.model?.hosts?.map { it.name }?.toSet().orEmpty()
    val problems = values.associateWith(check)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            values.forEach { v ->
                val problem = problems[v]
                val label = VisualText.label(ctx, v) + suffix(v).orEmpty()
                InputChip(
                    selected = false,
                    enabled = env.editable,
                    onClick = { onChipClick?.invoke(v) ?: onChange(values - v) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(kindIcon(Selectors.parse(v, hosts).kind), null, Modifier.size(18.dp)) },
                    trailingIcon = if (onChipClick == null) {
                        { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) }
                    } else if (problem != null) {
                        { Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                    } else null,
                    border = if (problem != null) BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    else InputChipDefaults.inputChipBorder(enabled = env.editable, selected = false),
                    modifier = Modifier.semantics {
                        contentDescription = if (onChipClick == null) ctx.getString(R.string.admin_pv_remove, label) else label
                    },
                )
            }
            if (env.editable) {
                AssistChip(
                    onClick = { picking = true },
                    label = { Text(ctx.getString(R.string.admin_pv_add)) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                )
            }
        }
        problems.filterValues { it != null }.forEach { (v, p) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("${VisualText.label(ctx, v)}: ${VisualText.problem(ctx, p!!)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (picking) {
        SelectorPickerSheet(title, slot, env, values, onDone = { onChange(it) }, onDismiss = { picking = false })
    }
}

/**
 * The card one element sits in: its note on top (folded to two lines), [content], then what
 * the checks say about it — a server error, a new risk — each with its icon, and, for an
 * element the visual editor only shows, why and the way to the JSON editor. [selected] or the
 * environment's focus outlines it.
 */
@Composable
fun ElementCard(
    origin: Origin,
    env: VisualEnv,
    actions: VisualActions,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val errors = env.errors[origin.path].orEmpty()
    val risks = env.risks[origin.path].orEmpty()
    val outlined = selected || env.focus == origin.path
    val border = when {
        errors.isNotEmpty() -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        outlined -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            origin.note?.let { HelpText(it) }
            content()
            errors.forEach { m -> StatusLine(Icons.Default.ErrorOutline, m, MaterialTheme.colorScheme.error) }
            risks.forEach { r -> StatusLine(Icons.Default.Warning, PolicyText.riskTitle(ctx, r.kind), MaterialTheme.colorScheme.tertiary) }
            if (!origin.editable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    HelpText(origin.issues.firstOrNull()?.let { VisualText.issue(ctx, it) } ?: ctx.getString(R.string.admin_pv_read_only), Modifier.weight(1f))
                    TextButton(onClick = { actions.openJson(origin.line) }) { Text(ctx.getString(R.string.admin_pv_edit_in_json)) }
                }
            } else if (origin.extra.isNotEmpty()) {
                Text(
                    ctx.getString(R.string.admin_pv_more_in_json, origin.extra.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors, border = border, content = body)
    else Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors, border = border, content = body)
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * A heading comment over a run of elements (`// --- 2. DNS rules ---`) as a divider: its first
 * line as a title with the decoration trimmed, the rest folded under it.
 */
@Composable
fun CommentHeading(text: String, modifier: Modifier = Modifier) {
    val lines = text.lines()
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        Text(
            headingTitle(lines.first()),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.drop(1).joinToString("\n").takeIf { it.isNotBlank() }?.let { HelpText(it) }
    }
}

/** "--- 2. DNS rules ---" → "2. DNS rules". */
fun headingTitle(line: String): String = line.trim().trim('-', '=', '#', '*', '/', ' ').ifBlank { line.trim() }

/** A section with nothing in it yet: what it is for, and the one action that starts it. */
@Composable
fun EmptySection(icon: ImageVector, text: String, actionLabel: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    EmptyState(icon = icon, text = text, modifier = modifier.fillMaxWidth().padding(vertical = 24.dp), actionLabel = actionLabel, onAction = onAction)
}
