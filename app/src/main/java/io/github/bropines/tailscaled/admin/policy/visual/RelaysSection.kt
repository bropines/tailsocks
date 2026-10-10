package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.columnsFor
import io.github.bropines.tailscaled.ui.rememberWindowLayout

/*
 * The RELAYS page: Tailscale's relay (DERP) regions, each with a switch that excludes it
 * through the policy's derpMap (`"Regions": {"28": null}`) or uses it again, how many of your
 * devices call it home and how fast it answers them; the regions the file defines itself, shown
 * as written with the way to JSON. A Headscale server takes its relays from its own
 * configuration: there the page only shows what the file says.
 *
 * On a phone the regions are one list; from a medium window up the rows of each group stand in
 * columns, read row by row, so the list stays in its order.
 */

private val ROW_MIN_WIDTH = 320.dp

@Composable
fun RelaysSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    env.model ?: return
    val ctx = LocalContext.current
    val window = rememberWindowLayout()
    val text = env.draft.text
    val file = remember(text) { DerpFile.read(text) } ?: return
    val load = env.derpMap
    val map = if (env.headscale) null else load.value
    // Once a session (the console keeps it); a copy from disk is refreshed behind what it shows.
    if (!env.headscale) LaunchedEffect(Unit) { actions.loadDerpMap() }

    val rows = remember(file, map, env.devices) { Relays.rows(file, map, env.devices) }
    val canEdit = env.editable && file.lock == null && !env.headscale
    var asking by remember { mutableStateOf<String?>(null) }

    fun exclude(id: String) = actions.edit { PolicyEdits.setDerpRegionExcluded(it, id, true) }
    fun include(id: String) = actions.edit { PolicyEdits.setDerpRegionExcluded(it, id, false) }
    val onToggle: (RelayRow, Boolean) -> Unit = { row, used ->
        if (used) include(row.id)
        else {
            val impact = Relays.impact(rows, file, row.id)
            when {
                impact.refused -> Unit
                impact.asks -> asking = row.id
                else -> exclude(row.id)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = window.margin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderCard(env, actions, file, rows, mapLoaded = map != null)
        if (!env.headscale && !file.omitDefaults) {
            if (load.error != null) {
                LoadProblems(load, onRetry = { actions.loadDerpMap(force = true) })
                if (load.value == null) HelpText(ctx.getString(R.string.admin_pv_derp_failed_hint), Modifier.padding(horizontal = 4.dp))
            } else if (load.loading && load.value == null) {
                Loading(ctx.getString(R.string.admin_pv_derp_loading))
            }
        }
        val wide = !window.isPhone
        if (env.headscale) {
            // Only what the file says: no switch, each row to its line.
            if (rows.isNotEmpty()) RegionGroup(ctx.getString(R.string.admin_pv_derp_group_file), rows, wide, null, false, false, actions, onToggle)
        } else {
            val home = rows.filter { it.homes.isNotEmpty() }
            val other = rows.filter { it.homes.isEmpty() }
            val lastOne = canEdit && Relays.serving(rows, file) == 1
            if (home.isNotEmpty()) {
                RegionGroup(ctx.getString(R.string.admin_pv_derp_group_home), home, wide, canEdit, map != null, lastOne, actions, onToggle)
            }
            if (other.isNotEmpty()) {
                val title = if (home.isEmpty()) R.string.admin_pv_derp_group_all else R.string.admin_pv_derp_group_other
                RegionGroup(ctx.getString(title), other, wide, canEdit, map != null, lastOne, actions, onToggle)
            }
        }
        if (file.ownEntries.isNotEmpty()) OwnGroup(file.ownEntries, env.devices, wide, actions)
    }

    // Gone from the list meanwhile (an undo, the JSON view): nothing to ask about.
    asking?.let { id -> rows.firstOrNull { it.id == id } }?.let { row ->
        ConfirmExclude(ctx, row, Relays.impact(rows, file, row.id), onConfirm = { asking = null; exclude(row.id) }, onDismiss = { asking = null })
    }
}

// ---------------------------------------------------------------- the card on top

/**
 * What relays are (folded), how many of Tailscale's regions are in use, and what stands in the
 * way of editing: Headscale, OmitDefaultRegions, a map written in a way the page does not edit.
 * The derpMap's own comment, the server's errors and new risks show here, on its element.
 */
@Composable
private fun HeaderCard(env: VisualEnv, actions: VisualActions, file: DerpFile, rows: List<RelayRow>, mapLoaded: Boolean) {
    val ctx = LocalContext.current
    val origin = file.origin
    // The line "Edit in JSON" opens: the derpMap, or the key that spells it another way.
    val jsonLine = origin?.line ?: file.oddKey?.let { k -> env.model?.sections?.firstOrNull { it.key == k }?.origin?.line }
    val body: @Composable () -> Unit = {
        CardTitle(Icons.Default.Public, ctx.getString(R.string.admin_pv_derp_title))
        HelpText(ctx.getString(R.string.admin_pv_derp_help))
        if (env.headscale) InfoLine(Icons.Default.Info, ctx.getString(R.string.admin_pv_derp_headscale))
        if (file.omitDefaults) InfoLine(Icons.Default.Info, ctx.getString(R.string.admin_pv_derp_omit))
        file.lock?.let { lock ->
            val reason = when (lock) {
                DerpLock.SPELLING -> ctx.getString(R.string.admin_pv_derp_lock_spelling, file.oddKey.orEmpty())
                DerpLock.SHAPE -> ctx.getString(R.string.admin_pv_derp_lock_shape)
                DerpLock.TWICE -> ctx.getString(R.string.admin_pv_derp_lock_twice)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                HelpText(reason, Modifier.weight(1f))
            }
        }
        val counted = mapLoaded && !file.omitDefaults
        if (counted && Relays.serving(rows, file) == 0) {
            StatusRow(Icons.Default.ErrorOutline, ctx.getString(R.string.admin_pv_derp_none_left), MaterialTheme.colorScheme.error)
        }
        if (env.derpMap.loading && env.derpMap.value != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        // How many of Tailscale's regions are in use, and the way to JSON beside it.
        if (counted || jsonLine != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (counted) {
                        val known = rows.count { it.servers != null }
                        val used = rows.count { it.servers != null && !it.excluded && !it.replaced }
                        Text(ctx.getString(R.string.admin_pv_derp_in_use, used, known), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (jsonLine != null) TextButton(onClick = { actions.openJson(jsonLine) }) { Text(ctx.getString(R.string.admin_pv_edit_in_json)) }
            }
        }
    }
    if (origin != null) {
        ElementCard(origin, env, actions, modifier = bringsIntoView(env, setOf(origin.path))) { body() }
    } else {
        // No derpMap under its own key: nothing of the file's to carry, the same card otherwise.
        val focused = file.oddKey != null && env.focus == PolicyPath.of(file.oddKey)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        HelpText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Loading(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- the regions

/** A group's rows in one card: a list on a phone, columns read row by row on a wider window. */
@Composable
private fun <T> GroupCard(title: String, items: List<T>, wide: Boolean, row: @Composable (T, Modifier) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 6.dp)) {
            val columns = if (wide) columnsFor(maxWidth, ROW_MIN_WIDTH, spacing = 8.dp, maxColumns = 3) else 1
            Column {
                items.chunked(columns).forEachIndexed { i, line ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { row(it, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionGroup(
    title: String,
    rows: List<RelayRow>,
    wide: Boolean,
    /** Null: the rows only show what the file says (Headscale), each opening its line in JSON. */
    canEdit: Boolean?,
    mapLoaded: Boolean,
    lastOne: Boolean,
    actions: VisualActions,
    onToggle: (RelayRow, Boolean) -> Unit,
) {
    GroupCard(title, rows, wide) { row, modifier ->
        RegionRow(row, canEdit, mapLoaded, lastOne && !row.excluded && !row.replaced && row.servers != null, actions, onToggle, modifier)
    }
}

/**
 * One region: its code and number, its city, and in a line under them what it is (excluded,
 * replaced, unknown to Tailscale's list), its servers, the devices that call it home and its
 * latency. The whole row toggles; the switch is on while the region is used. A region the file
 * replaces has no switch: the row opens its line in JSON.
 */
@Composable
private fun RegionRow(
    row: RelayRow,
    canEdit: Boolean?,
    mapLoaded: Boolean,
    last: Boolean,
    actions: VisualActions,
    onToggle: (RelayRow, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val res = ctx.resources
    val used = !row.excluded
    val title = row.name ?: ctx.getString(R.string.admin_pv_derp_region, row.id)
    val status = when {
        row.replaced -> ctx.getString(R.string.admin_pv_derp_replaced)
        row.excluded -> ctx.getString(R.string.admin_pv_derp_excluded)
        else -> null
    }
    val parts = buildList {
        status?.let(::add)
        if (row.name == null && mapLoaded) add(ctx.getString(R.string.admin_pv_derp_unknown))
        row.servers?.let { add(res.getQuantityString(R.plurals.admin_pv_derp_servers, it, it)) }
        if (row.homes.isNotEmpty()) add(res.getQuantityString(R.plurals.admin_pv_derp_home, row.homes.size, row.homes.size))
        row.latency?.let { add(ctx.getString(R.string.admin_pv_derp_latency, it.medianMs)) }
    }
    val latencyWords = row.latency?.let { res.getQuantityString(R.plurals.admin_pv_derp_latency_desc, it.devices, it.medianMs, it.devices) }
    val shownOnly = canEdit == null || row.replaced
    val enabled = canEdit == true && row.switchable && !last
    val action = when {
        shownOnly -> Modifier.clickable(role = Role.Button, onClickLabel = ctx.getString(R.string.admin_pv_edit_in_json)) {
            actions.openJson(row.entry?.origin?.line ?: 1)
        }
        else -> Modifier.toggleable(value = used, enabled = enabled, role = Role.Switch) { onToggle(row, it) }
    }
    Row(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .then(action)
            .semantics {
                if (!shownOnly) stateDescription = ctx.getString(if (used) R.string.admin_pv_derp_used else R.string.admin_pv_derp_excluded)
                latencyWords?.let { contentDescription = listOf(title, *parts.toTypedArray(), it).joinToString(", ") }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodeBadge(row.code, row.id, used && !row.replaced)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (used) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (last) Text(ctx.getString(R.string.admin_pv_derp_last), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            row.entry?.origin?.note?.let { Note(it) }
        }
        Spacer(Modifier.width(12.dp))
        if (shownOnly) Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else Switch(checked = used, onCheckedChange = null, enabled = enabled)
    }
}

/** The region's three letters over its number, in a chip that is filled while the region is used. */
@Composable
private fun CodeBadge(code: String?, id: String, used: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (used) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (used) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(48.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                (code ?: id).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (code != null) Text(id, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** The comment written above a region's line in the file, marked as one. */
@Composable
private fun Note(note: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.AutoMirrored.Outlined.Comment, null, Modifier.padding(top = 2.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(noteText(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The regions the file defines itself (or writes as something else): shown as written, each row to its line in JSON. */
@Composable
private fun OwnGroup(entries: List<DerpEntry>, devices: List<ApiDevice>, wide: Boolean, actions: VisualActions) {
    val ctx = LocalContext.current
    val res = ctx.resources
    val reports = remember(devices) { Relays.reports(devices) }
    GroupCard(ctx.getString(R.string.admin_pv_derp_group_own), entries, wide) { e, modifier ->
        val title = e.name ?: ctx.getString(R.string.admin_pv_derp_region, e.id)
        val parts = buildList {
            if (e.kind == DerpEntryKind.OTHER) add(ctx.getString(R.string.admin_pv_derp_odd_entry))
            else add(res.getQuantityString(R.plurals.admin_pv_derp_servers, e.servers, e.servers))
            e.name?.let { n ->
                Relays.homes(reports, n).size.takeIf { it > 0 }?.let { add(res.getQuantityString(R.plurals.admin_pv_derp_home, it, it)) }
                Relays.latency(reports, n)?.let { add(ctx.getString(R.string.admin_pv_derp_latency, it.medianMs)) }
            }
        }
        Row(
            modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClickLabel = ctx.getString(R.string.admin_pv_edit_in_json)) { actions.openJson(e.origin.line) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeBadge(e.code, e.id, used = e.kind == DerpEntryKind.CUSTOM)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.origin.note?.let { Note(it) }
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Default.Code, ctx.getString(R.string.admin_pv_edit_in_json), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- asking first

/**
 * Before excluding a region that is home to some of your devices, or the last of Tailscale's
 * with only the file's own left: what it costs, in words. Strings are resolved here, in the
 * page's locale, not in the dialog's window.
 */
@Composable
internal fun ConfirmExclude(ctx: Context, row: RelayRow, impact: ExcludeImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val res = ctx.resources
    val label = listOfNotNull(row.name, row.code?.uppercase()?.let { "($it)" }).joinToString(" ").ifEmpty { ctx.getString(R.string.admin_pv_derp_region, row.id) }
    val title = ctx.getString(R.string.admin_pv_derp_confirm_title, label)
    val body = buildList {
        if (impact.homes.isNotEmpty()) {
            val names = impact.homes.map { it.shortName }.sorted()
            val shown = if (names.size <= MAX_NAMED) names.joinToString(", ")
            else ctx.getString(R.string.admin_pv_derp_confirm_more, names.take(MAX_NAMED).joinToString(", "), names.size - MAX_NAMED)
            add(res.getQuantityString(R.plurals.admin_pv_derp_confirm_homes, names.size, names.size, shown))
        }
        if (impact.onlyOwn) add(ctx.getString(R.string.admin_pv_derp_confirm_only_own, impact.ownLeft))
    }.joinToString("\n\n")
    val ok = ctx.getString(R.string.admin_pv_derp_confirm_ok)
    val cancel = ctx.getString(R.string.action_cancel)
    val strong = impact.onlyOwn
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = if (strong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(ok, color = if (strong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel) } },
    )
}

private const val MAX_NAMED = 4
