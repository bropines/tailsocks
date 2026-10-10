package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.rememberFullSheetState
import kotlinx.coroutines.launch

/**
 * Picks selectors for one field: everything [slot] accepts on this backend — autogroups with
 * what they mean, the policy's groups, tags (also those devices carry without an owner, marked),
 * hosts, IP sets, the tailnet's users — with device counts, a search, and a typed entry checked
 * as it is typed. Several can be ticked; [onDone] gets the new list, the values that stay in
 * their old order and the new ones after them. A bottom sheet, its words in the app's locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectorPickerSheet(
    title: String,
    slot: SelectorSlot,
    env: VisualEnv,
    selected: List<String>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    single: Boolean = false,
    extra: List<SelectorOption> = emptyList(),
) {
    val parent = rememberParentLocals()
    val sheetState = rememberFullSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        parent.Provide {
            SelectorPickerContent(title, slot, env, selected, single = single, extra = extra) { picked ->
                onDone(picked)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

/**
 * What [SelectorPickerSheet] holds, without the sheet: a preview can draw it. [single] makes
 * it a choice of one, made by tapping a row; [extra] adds suggestions after the slot's own.
 */
@Composable
fun SelectorPickerContent(
    title: String,
    slot: SelectorSlot,
    env: VisualEnv,
    selected: List<String>,
    single: Boolean = false,
    extra: List<SelectorOption> = emptyList(),
    onDone: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val model = env.model
    val options = remember(model, env.users, env.devices, env.headscale, slot, extra) {
        val own = model?.let { SelectorRules.options(slot, it, env.headscale, env.users, env.devices) }.orEmpty()
        extra.filter { e -> own.none { it.value == e.value } } + own
    }
    // Values already in the field that no option offers (typed earlier, defined nowhere) stay pickable.
    val all = remember(options, selected) {
        options + selected.filter { s -> options.none { it.value == s } }.map { SelectorOption(it, Selectors.parse(it).kind, defined = false) }
    }
    val q = query.trim()
    val shown = all.filter { o -> q.isEmpty() || o.value.contains(q, ignoreCase = true) || VisualText.label(ctx, o.value).contains(q, ignoreCase = true) }
    val typedProblem = if (q.isEmpty() || all.any { it.value == q } || model == null) null else SelectorRules.check(q, slot, model, env.headscale)
    // Undefined names can be used: the server will say so, and the person may define them next.
    val typedUsable = q.isNotEmpty() && all.none { it.value == q } && (typedProblem == null || typedProblem == SelectorProblem.UNDEFINED)

    fun toggle(v: String) {
        if (single) {
            onDone(listOf(v))
            return
        }
        chosen = if (v in chosen) chosen - v else chosen + v
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (!single) TextButton(onClick = { onDone(chosen) }) { Text(ctx.getString(R.string.admin_pv_picker_done)) }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(ctx.getString(R.string.admin_pv_picker_search)) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (q.isNotEmpty() && all.none { it.value == q }) {
            TextButton(onClick = { toggle(q); query = "" }, enabled = typedUsable, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_pv_picker_use, q), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    typedProblem?.let {
                        Text(VisualText.problem(ctx, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        // Past this the list scrolls under the search, which stays put.
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            if (shown.isEmpty()) item {
                Text(
                    ctx.getString(R.string.admin_pv_picker_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            // What a rule names most — the file's groups and tags, the users — before the automatic groups.
            val groups = shown.sortedBy { order(it.kind, slot) }.groupBy { heading(ctx, it.kind, slot) }
            groups.forEach { (heading, list) ->
                item(key = "h:$heading") {
                    Text(
                        heading,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { "o:${it.value}" }) { o -> OptionRow(o, o.value in chosen, single) { toggle(o.value) } }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

/** Where options of [kind] stand in the picker for [slot]: names first, login names first of all for SSH users. */
private fun order(kind: SelectorKind, slot: SelectorSlot): Int = when (kind) {
    SelectorKind.UNKNOWN, SelectorKind.LOCALPART -> if (slot == SelectorSlot.SSH_USER) 0 else 6
    SelectorKind.GROUP, SelectorKind.EXTERNAL -> 1
    SelectorKind.TAG -> 2
    SelectorKind.USER, SelectorKind.USER_DOMAIN -> 3
    SelectorKind.ANY, SelectorKind.AUTOGROUP -> 4
    SelectorKind.HOST, SelectorKind.IP, SelectorKind.CIDR, SelectorKind.IP_RANGE, SelectorKind.SERVICE -> 5
    SelectorKind.IPSET -> 7
    SelectorKind.POSTURE -> 8
}

private fun heading(ctx: android.content.Context, kind: SelectorKind, slot: SelectorSlot): String =
    if (slot == SelectorSlot.SSH_USER && (kind == SelectorKind.UNKNOWN || kind == SelectorKind.LOCALPART || kind == SelectorKind.ANY)) {
        ctx.getString(R.string.admin_pv_group_logins)
    } else VisualText.group(ctx, kind)

/** One option: a checkbox row (a radio row when the picker takes one), the whole row the toggle. */
@Composable
private fun OptionRow(o: SelectorOption, on: Boolean, single: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val label = VisualText.label(ctx, o.value)
    val second = when {
        label != o.value -> VisualText.help(ctx, o.value) ?: o.value
        else -> null
    }
    val meta = listOfNotNull(
        o.devices?.let { VisualText.devices(ctx, it) },
        ctx.getString(R.string.admin_pv_tag_undefined).takeIf { o.kind == SelectorKind.TAG && !o.defined },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = on, role = if (single) Role.RadioButton else Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (single) RadioButton(selected = on, onClick = null) else Checkbox(checked = on, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Icon(kindIcon(o.kind), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The first sentence says enough to choose; the rest unfolds from the ⓘ.
            second?.let { HelpText(it, lines = 1, inClickableRow = true) }
            // Everyone, and anyone at all, open more than a rule usually means to: said, not only coloured.
            if (o.value == "*" || o.value == "autogroup:danger-all") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(ctx.getString(R.string.admin_pv_picker_broad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
