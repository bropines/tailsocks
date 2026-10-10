package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.ui.CardColumns
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.rememberWindowLayout

/*
 * The NETWORK page: the policy's top-level options (a random client port, no IPv4 addresses,
 * the macOS CGNAT route), what the relay map does, and every section the visual editor does not
 * edit — kept exactly as written, each with its way to the JSON editor.
 */

/** The OneCGNATRoute values the policy documents; "" is the default. */
internal val CGNAT_VALUES = listOf("", "mac-always", "mac-never")

/** Top-level sections the editor knows but only shows: they stand on this page beside the unknown ones. */
private val SHOWN_ONLY = setOf(Section.EXTERNAL_TAILNETS, Section.ATTR_CONFIG)

@Composable
fun NetworkSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val model = env.model ?: return
    val ctx = LocalContext.current
    val window = rememberWindowLayout()
    val others = model.sections.filter { it.section == null || it.section in SHOWN_ONLY }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = window.margin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardColumns(minColumnWidth = 360.dp, maxColumns = if (layout.twoPane) 2 else 1) {
            OptionsCard(model, env, actions)
            model.derpMap?.let { DerpCard(it, env, actions) }
        }
        if (others.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                Text(ctx.getString(R.string.admin_pv_net_other), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                HelpText(ctx.getString(R.string.admin_pv_net_other_help))
            }
            CardColumns(minColumnWidth = 360.dp, maxColumns = if (layout.twoPane) 2 else 1) {
                others.forEach { OtherSectionCard(it, env, actions) }
            }
        }
    }
}

/** Scrolls the card in when the shell asks for one of [paths] (a server error, the JSON cursor). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun bringsIntoView(env: VisualEnv, paths: Set<PolicyPath>): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val focus = env.focus
    LaunchedEffect(focus) { if (focus != null && focus in paths) runCatching { requester.bringIntoView() } }
    return Modifier.bringIntoViewRequester(requester)
}

// ---------------------------------------------------------------- options

/** Why an option's row is shown and not edited: a value of another type, or a key in another spelling. */
private fun optionLocked(model: PolicyModel, section: Section, valueReadable: Boolean): ShapeIssue? {
    val keys = model.sections.map { it.key }
    return when {
        keys.any { it != section.key && it.equals(section.key, ignoreCase = true) } -> ShapeIssue.ODD_CASE
        section.key in keys && !valueReadable -> ShapeIssue.NOT_A_STRING
        else -> null
    }
}

/**
 * The option [section] set to [value], or back to the server's default with [value] null: the
 * line goes when nothing is written about it, and keeps its comment otherwise, holding [default].
 */
internal fun setOrDefault(text: String, section: Section, value: PolicyValue?, default: PolicyValue): String {
    if (value != null) return PolicyEdits.setOption(text, section, value)
    val t = SourceEdits.tree(text)
    val i = t.root.indexOf(section.key)
    if (i < 0) return text
    val c = t.commentsOf(t.root, i)
    val commented = c.note != null || c.header != null || c.trailing != null
    return if (commented) PolicyEdits.setOption(text, section, default) else PolicyEdits.setOption(text, section, null)
}

@Composable
private fun OptionsCard(model: PolicyModel, env: VisualEnv, actions: VisualActions) {
    val ctx = LocalContext.current
    val keys = model.sections.map { it.key }.toSet()
    // A key in another spelling stands in for the option: its line is where the lock leads.
    fun origin(s: Section) = model.sections.lastOrNull { it.key == s.key }?.origin
        ?: model.sections.lastOrNull { it.key.equals(s.key, ignoreCase = true) }?.origin
    val paths = setOf(Section.RANDOMIZE_CLIENT_PORT, Section.DISABLE_IPV4, Section.ONE_CGNAT_ROUTE).map { PolicyPath.of(it.key) }.toSet()
    val outlined = env.focus in paths
    var picking by remember { mutableStateOf(false) }

    Card(
        modifier = bringsIntoView(env, paths).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (outlined) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(Icons.Default.Tune, ctx.getString(R.string.admin_pv_net_options))
            OptionToggle(
                title = ctx.getString(R.string.admin_pv_net_random_port),
                help = ctx.getString(R.string.admin_pv_net_random_port_help),
                checked = model.randomizeClientPort == true,
                origin = origin(Section.RANDOMIZE_CLIENT_PORT),
                locked = optionLocked(model, Section.RANDOMIZE_CLIENT_PORT, model.randomizeClientPort != null),
                env = env,
                actions = actions,
            ) { on -> actions.edit { setOrDefault(it, Section.RANDOMIZE_CLIENT_PORT, if (on) true.pv() else null, false.pv()) } }
            if (!env.headscale || Section.DISABLE_IPV4.key in keys) OptionToggle(
                title = ctx.getString(R.string.admin_pv_net_no_ipv4),
                help = ctx.getString(R.string.admin_pv_net_no_ipv4_help),
                checked = model.disableIPv4 == true,
                origin = origin(Section.DISABLE_IPV4),
                locked = optionLocked(model, Section.DISABLE_IPV4, model.disableIPv4 != null),
                env = env,
                actions = actions,
            ) { on -> actions.edit { setOrDefault(it, Section.DISABLE_IPV4, if (on) true.pv() else null, false.pv()) } }
            if (!env.headscale || Section.ONE_CGNAT_ROUTE.key in keys) {
                val value = model.oneCGNATRoute
                OptionChoice(
                    title = ctx.getString(R.string.admin_pv_net_cgnat),
                    help = ctx.getString(R.string.admin_pv_net_cgnat_help),
                    value = cgnatLabel(ctx, value.orEmpty()),
                    origin = origin(Section.ONE_CGNAT_ROUTE),
                    locked = optionLocked(model, Section.ONE_CGNAT_ROUTE, value != null),
                    env = env,
                    actions = actions,
                    onClick = { picking = true },
                )
            }
        }
    }
    if (picking) {
        val current = model.oneCGNATRoute.orEmpty()
        PickerSheet(
            title = ctx.getString(R.string.admin_pv_net_cgnat),
            options = (CGNAT_VALUES + listOfNotNull(current.takeIf { it !in CGNAT_VALUES })).map { PickerOption(it, cgnatLabel(ctx, it)) },
            selected = current,
            onPick = { v -> actions.edit { setOrDefault(it, Section.ONE_CGNAT_ROUTE, v.takeIf { s -> s.isNotEmpty() }?.pv(), "".pv()) } },
            onDismiss = { picking = false },
        )
    }
}

private fun cgnatLabel(ctx: android.content.Context, value: String): String = when (value) {
    "" -> ctx.getString(R.string.admin_pv_net_cgnat_auto)
    "mac-always" -> ctx.getString(R.string.admin_pv_net_cgnat_always)
    "mac-never" -> ctx.getString(R.string.admin_pv_net_cgnat_never)
    else -> ctx.getString(R.string.admin_pv_net_cgnat_other, value)
}

@Composable
private fun CardTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** A switch row: the whole row toggles; its comment, what the checks say and why it is locked under it. */
@Composable
private fun OptionToggle(
    title: String,
    help: String,
    checked: Boolean,
    origin: Origin?,
    locked: ShapeIssue?,
    env: VisualEnv,
    actions: VisualActions,
    onChange: (Boolean) -> Unit,
) {
    val enabled = env.editable && locked == null
    val expanded = remember(help) { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                HelpText(help, expanded = expanded, inClickableRow = true)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        OptionFooter(origin, locked, env, actions)
    }
}

/** A row that opens a picker, its current value under its title. */
@Composable
private fun OptionChoice(
    title: String,
    help: String,
    value: String,
    origin: Origin?,
    locked: ShapeIssue?,
    env: VisualEnv,
    actions: VisualActions,
    onClick: () -> Unit,
) {
    val enabled = env.editable && locked == null
    val expanded = remember(help) { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                HelpText(help, expanded = expanded, inClickableRow = true)
            }
            if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OptionFooter(origin, locked, env, actions)
    }
}

/** Under an option: the comment written above it, the server's error, a new risk, and the lock with its way to JSON. */
@Composable
private fun OptionFooter(origin: Origin?, locked: ShapeIssue?, env: VisualEnv, actions: VisualActions) {
    val ctx = LocalContext.current
    origin ?: return
    origin.note?.let { note ->
        // The comment written above it in the file, marked as one: it is not the explanation.
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.AutoMirrored.Outlined.Comment, null, Modifier.padding(top = 2.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            HelpText(note, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    env.errors[origin.path].orEmpty().forEach { StatusRow(Icons.Default.ErrorOutline, it, MaterialTheme.colorScheme.error) }
    env.risks[origin.path].orEmpty().forEach { StatusRow(Icons.Default.Warning, PolicyText.riskTitle(ctx, it.kind), MaterialTheme.colorScheme.tertiary) }
    if (locked != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            HelpText(if (locked == ShapeIssue.ODD_CASE) VisualText.issue(ctx, locked) else ctx.getString(R.string.admin_pv_net_odd_value), Modifier.weight(1f))
            TextButton(onClick = { actions.openJson(origin.line) }) { Text(ctx.getString(R.string.admin_pv_edit_in_json)) }
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

// ---------------------------------------------------------------- the relay map

@Composable
private fun DerpCard(derp: DerpSummary, env: VisualEnv, actions: VisualActions) {
    val ctx = LocalContext.current
    ElementCard(derp.origin, env, actions, modifier = bringsIntoView(env, setOf(derp.origin.path))) {
        CardTitle(Icons.Default.Hub, ctx.getString(R.string.admin_pv_net_derp))
        Text(
            ctx.getString(if (derp.omitDefaultRegions) R.string.admin_pv_net_derp_defaults_off else R.string.admin_pv_net_derp_defaults_on),
            style = MaterialTheme.typography.bodyMedium,
        )
        val off = derp.regions.filter { it.disabled }
        if (off.isNotEmpty() && !derp.omitDefaultRegions) {
            Text(
                ctx.getString(R.string.admin_pv_net_derp_off, off.joinToString(", ") { ctx.getString(R.string.admin_pv_net_derp_region, it.id) }),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val own = derp.regions.filter { !it.disabled }
        if (own.isNotEmpty()) {
            Text(ctx.getString(R.string.admin_pv_net_derp_custom), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            own.forEach { r ->
                val name = listOfNotNull(r.code?.takeIf { it.isNotBlank() }, r.name?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { ctx.getString(R.string.admin_pv_net_derp_region, r.id) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ctx.resources.getQuantityString(R.plurals.admin_pv_net_derp_servers, r.nodes, r.nodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HelpText(ctx.getString(R.string.admin_pv_net_derp_help))
        JsonButton(derp.origin.line, actions)
    }
}

@Composable
private fun ColumnScope.JsonButton(line: Int, actions: VisualActions) {
    val ctx = LocalContext.current
    TextButton(onClick = { actions.openJson(line) }, modifier = Modifier.align(Alignment.End)) { Text(ctx.getString(R.string.admin_pv_edit_in_json)) }
}

// ---------------------------------------------------------------- sections only shown

/**
 * A top-level section the visual editor does not edit: its key, how much it holds, its first
 * lines as written, and the way to them in the JSON editor. A known key spelled another way
 * says so: the server reads it, the editor leaves it alone.
 */
@Composable
private fun OtherSectionCard(entry: SectionEntry, env: VisualEnv, actions: VisualActions) {
    val ctx = LocalContext.current
    val shown = remember(env.draft.text, entry.key, entry.origin.line) { sectionPreview(env.draft.text, entry) }
    ElementCard(entry.origin, env, actions, modifier = bringsIntoView(env, setOf(entry.origin.path))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DataObject, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                entry.key,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(ctx.getString(R.string.admin_pv_net_line, entry.origin.line), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shown.size?.let { (fields, n) ->
            Text(
                ctx.resources.getQuantityString(if (fields) R.plurals.admin_pv_net_fields else R.plurals.admin_pv_net_items, n, n),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.section == null && Section.of(entry.key) != null) HelpText(VisualText.issue(ctx, ShapeIssue.ODD_CASE))
        if (shown.lines.isNotEmpty()) {
            Text(
                shown.lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = PREVIEW_LINES + 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        JsonButton(entry.origin.line, actions)
    }
}

private const val PREVIEW_LINES = 4

/** A section's value as the card shows it: how many fields or items it has, and its first lines with the indentation they share taken off. */
internal data class SectionPreview(val size: Pair<Boolean, Int>?, val lines: List<String>)

internal fun sectionPreview(text: String, entry: SectionEntry): SectionPreview {
    val t = SourceTree.parseOrNull(text) ?: return SectionPreview(null, emptyList())
    val member = t.root.members.firstOrNull { it.key == entry.key && HuJson.Lines(text).lineOf(it.start) == entry.origin.line }
        ?: t.root.field(entry.key) ?: return SectionPreview(null, emptyList())
    val v = member.value
    val size = when (v) {
        is SrcObject -> true to v.members.size
        is SrcArray -> false to v.members.size
        else -> null
    }
    val all = t.slice(v).lines()
    val rest = all.drop(1).filter { it.isNotBlank() }
    val indent = rest.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
    val lines = listOf(all.first()) + all.drop(1).map { if (it.length >= indent) it.substring(indent) else it.trimStart() }
    val shown = lines.take(PREVIEW_LINES) + if (lines.size > PREVIEW_LINES) listOf("…") else emptyList()
    return SectionPreview(size, shown.map { it.replace("\t", "  ") })
}
