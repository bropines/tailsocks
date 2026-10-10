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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
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
) {
    val parent = rememberParentLocals()
    val sheetState = rememberFullSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        parent.Provide {
            SelectorPickerContent(title, slot, env, selected) { picked ->
                onDone(picked)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

/** What [SelectorPickerSheet] holds, without the sheet: a preview can draw it. */
@Composable
fun SelectorPickerContent(title: String, slot: SelectorSlot, env: VisualEnv, selected: List<String>, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val model = env.model
    val options = remember(model, env.users, env.devices, env.headscale, slot) {
        model?.let { SelectorRules.options(slot, it, env.headscale, env.users, env.devices) }.orEmpty()
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
        chosen = if (v in chosen) chosen - v else chosen + v
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDone(chosen) }) { Text(ctx.getString(R.string.admin_pv_picker_done)) }
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
            TextButton(onClick = { chosen = chosen + q; query = "" }, enabled = typedUsable, modifier = Modifier.fillMaxWidth()) {
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
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            if (shown.isEmpty()) item {
                Text(
                    ctx.getString(R.string.admin_pv_picker_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            val groups = shown.groupBy { VisualText.group(ctx, it.kind) }
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
                items(list, key = { "o:${it.value}" }) { o -> OptionRow(o, o.value in chosen) { toggle(o.value) } }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

/** One option: a checkbox row, the whole row the toggle. */
@Composable
private fun OptionRow(o: SelectorOption, on: Boolean, onToggle: () -> Unit) {
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
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Icon(kindIcon(o.kind), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (label == o.value) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            second?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
