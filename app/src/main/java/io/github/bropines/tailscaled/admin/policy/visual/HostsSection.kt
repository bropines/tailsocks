package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/**
 * The HOSTS page: host aliases (a name for an address or a network) and, on Tailscale, IP sets
 * (addresses, networks, ranges, hosts and other sets added and taken out in order). Both are
 * renamed everywhere and deleted only once nothing uses them; an IP set's rows are edited,
 * reordered and switched between adding and removing one by one.
 */
@Composable
fun HostsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val hosts = model.hosts
    val ipsets = model.ipsets
    // Headscale has no IP sets: the part shows only for a file that has them anyway.
    val showIpsets = !env.headscale || ipsets.isNotEmpty()
    val places = remember(text) {
        (hosts.map { it.name } + ipsets.map { it.name }).associateWith { n -> tree?.let { Definitions.places(it, n) }.orEmpty() }
    }
    var creating by remember { mutableStateOf<DefKind?>(null) }
    val selected = layout.selected
    val shownHost = hosts.firstOrNull { it.origin.path == selected }
    val shownSet = ipsets.firstOrNull { it.origin.path == selected }
    val fallbackHost = if (layout.twoPane && shownHost == null && shownSet == null) hosts.firstOrNull() else null
    val fallbackSet = if (layout.twoPane && shownHost == null && shownSet == null && fallbackHost == null) ipsets.firstOrNull() else null
    val host = shownHost ?: fallbackHost
    val set = shownSet ?: fallbackSet
    fun open(kind: DefKind) { creating = kind; layout.onSelect(null) }

    val items = buildList {
        add(PageItem("intro") {
            PageIntro(ctx.getString(R.string.admin_pvd_hosts_help), null, null, sectionNotes(model, Section.HOSTS, Section.IPSETS), sectionErrors(env, Section.HOSTS, Section.IPSETS))
        })
        add(PageItem("hosts") {
            SubsectionHeader(ctx.getString(R.string.admin_pvd_hosts_title), ctx.getString(R.string.admin_pvd_host_new).takeIf { env.editable }, { open(DefKind.HOST) })
        })
        if (hosts.isEmpty()) add(PageItem("hosts-empty") { EmptyLine(ctx.getString(R.string.admin_pvd_hosts_empty)) })
        hosts.forEachIndexed { i, h ->
            h.origin.header?.let { add(PageItem("hh$i") { CommentHeading(it) }) }
            add(PageItem("host$i:${h.name}", h.origin.path) {
                HostCard(h, env, actions, places[h.name].orEmpty().size, selected = layout.twoPane && creating == null && host == h) {
                    creating = null
                    layout.onSelect(h.origin.path)
                }
            })
        }
        if (showIpsets) {
            add(PageItem("ipsets") {
                SubsectionHeader(
                    ctx.getString(R.string.admin_pvd_ipsets_title),
                    ctx.getString(R.string.admin_pvd_ipset_new).takeIf { env.editable && !env.headscale },
                    { open(DefKind.IPSET) },
                    help = ctx.getString(if (env.headscale) R.string.admin_pvd_not_on_headscale else R.string.admin_pvd_ipsets_help),
                )
            })
            if (ipsets.isEmpty()) add(PageItem("ipsets-empty") { EmptyLine(ctx.getString(R.string.admin_pvd_ipsets_empty)) })
            ipsets.forEachIndexed { i, s ->
                s.origin.header?.let { add(PageItem("sh$i") { CommentHeading(it) }) }
                add(PageItem("set$i:${s.name}", s.origin.path) {
                    IpSetCard(s, env, actions, places[s.name].orEmpty().size, selected = layout.twoPane && creating == null && set == s) {
                        creating = null
                        layout.onSelect(s.origin.path)
                    }
                })
            }
        }
    }
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = creating?.let { "new-$it" } ?: host?.origin?.path ?: set?.origin?.path,
        onCloseEditor = { creating = null; layout.onSelect(null) },
        paneEmpty = Icons.Default.Dns to ctx.getString(R.string.admin_pvd_hosts_pane_empty),
        scrollTo = env.focus ?: selected,
    ) {
        when {
            creating == DefKind.HOST -> NewHostForm(env, actions) { creating = null; layout.onSelect(PolicyPath.of(Section.HOSTS.key, it)) }
            creating == DefKind.IPSET -> NewIpSetForm(env, actions) { creating = null; layout.onSelect(PolicyPath.of(Section.IPSETS.key, it)) }
            host != null -> HostEditor(host, env, actions, layout, tree, places[host.name].orEmpty())
            set != null -> IpSetEditor(set, env, actions, layout, tree, places[set.name].orEmpty())
        }
    }
}

// ---- hosts ----

/** "100.64.0.20 · nas · used in 2 places": the address, the device at it, the uses. */
private fun hostSubtitle(ctx: android.content.Context, h: HostAlias, env: VisualEnv, places: Int): String = listOfNotNull(
    h.address,
    Definitions.hostDevices(h.address, env.devices).singleOrNull()?.shortName,
    placesText(ctx, places),
).joinToString(" · ")

@Composable
private fun HostCard(h: HostAlias, env: VisualEnv, actions: VisualActions, places: Int, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    ElementCard(h.origin, env, actions, selected = selected, onClick = onClick) {
        DefinitionHeader(Icons.Default.Dns, h.name, hostSubtitle(ctx, h, env, places))
    }
}

/** A host alias's editor: its name (renamed everywhere), its address, the devices at it, note, uses, delete. */
@Composable
internal fun HostEditor(h: HostAlias, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, places: List<PolicyPath>) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val editable = env.editable && h.origin.editable
    EditorTop(Icons.Default.Dns, h.name, hostSubtitle(ctx, h, env, places.size), h.origin, env, actions)
    NameField(DefKind.HOST, h.name, model, places.size, editable) { new ->
        if (actions.edit { PolicyEdits.rename(it, h.name, new) }) layout.onSelect(PolicyPath.of(Section.HOSTS.key, new))
    }
    CommitField(
        value = h.address,
        label = ctx.getString(R.string.admin_pvd_host_address),
        onCommit = { a -> actions.editAt(env.draft.text) { PolicyEdits.putHost(it, h.name, a.trim()) } },
        enabled = editable,
        problem = { a -> Definitions.checkAddress(a)?.let { addressProblem(ctx, it) } },
        supporting = ctx.getString(R.string.admin_pvd_host_address_help),
    )
    if (env.devices.isNotEmpty()) {
        DevicesBlock(ctx.getString(R.string.admin_pvd_host_devices), Definitions.hostDevices(h.address, env.devices), ctx.getString(R.string.admin_pvd_host_no_device))
    }
    NoteField(h.origin, tree, editable, actions)
    PlacesBlock(places, model, tree, actions)
    EditorBottom(h.origin, actions, ctx.getString(R.string.admin_pvd_host_delete).takeIf { editable }, {
        if (actions.edit { PolicyEdits.removeNamed(it, Section.HOSTS, h.name) }) {
            layout.onSelect(null)
            actions.notify(ctx.getString(R.string.admin_pvd_deleted, h.name), undoable = true)
        }
    }, places.size)
}

/** A new host alias: a name and an address the server takes, written on Create. */
@Composable
internal fun NewHostForm(env: VisualEnv, actions: VisualActions, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val nameProblem = Definitions.checkName(DefKind.HOST, name, model)
    val addressProblem = Definitions.checkAddress(address)
    EditorTop(Icons.Default.Dns, ctx.getString(R.string.admin_pvd_host_new), ctx.getString(R.string.admin_pvd_hosts_empty_page), null, env, actions, monospace = false)
    NewNameField(DefKind.HOST, name, { name = it }, model, showProblem = false)
    androidx.compose.material3.OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        singleLine = true,
        label = { Text(ctx.getString(R.string.admin_pvd_host_address)) },
        placeholder = { Text("100.64.0.20") },
        isError = address.isNotBlank() && addressProblem != null,
        supportingText = { Text(if (address.isNotBlank() && addressProblem != null) addressProblem(ctx, addressProblem) else ctx.getString(R.string.admin_pvd_host_address_help)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = nameProblem == null && addressProblem == null, hint = ctx.getString(R.string.admin_pvd_host_create_hint)) {
        val n = name.trim()
        if (actions.edit { PolicyEdits.putHost(it, n, address.trim()) }) onCreated(n)
    }
}

// ---- IP sets ----

/** One row of an IP set on a card: a plus or a minus (not only a colour) and its target. */
@Composable
private fun IpSetOpLabel(op: IpSetOp) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = scheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (op.remove) Icons.Default.Remove else Icons.Default.Add, null, Modifier.size(16.dp),
                tint = if (op.remove) scheme.error else scheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(op.target, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun ipSetSubtitle(ctx: android.content.Context, s: NamedList, places: Int): String = listOfNotNull(
    ctx.resources.getQuantityString(R.plurals.admin_pvd_n_rows, s.values.size, s.values.size),
    placesText(ctx, places),
).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpSetCard(s: NamedList, env: VisualEnv, actions: VisualActions, places: Int, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    ElementCard(s.origin, env, actions, selected = selected, onClick = onClick) {
        DefinitionHeader(Icons.Default.Hub, s.name, ipSetSubtitle(ctx, s, places))
        if (s.values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.values.take(8).forEach { IpSetOpLabel(IpSetOp.parse(it)) }
                if (s.values.size > 8) Text(ctx.getString(R.string.admin_pvd_more, s.values.size - 8), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * An IP set's editor: its name, its rows in order — each switched between adding and taking
 * out, its target edited, moved, removed — a field for the next row, note, uses, delete.
 */
@Composable
internal fun IpSetEditor(s: NamedList, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, places: List<PolicyPath>) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val editable = env.editable && s.origin.editable
    val ops = s.values.map(IpSetOp::parse)
    val base = env.draft.text
    fun write(next: List<IpSetOp>) = actions.editAt(base) { PolicyEdits.putNamed(it, Section.IPSETS, s.name, next.map { op -> op.text }) }
    val problem: (String) -> String? = { t -> Definitions.checkIpSetTarget(t, model, s.name)?.let { VisualText.problem(ctx, it) } }

    EditorTop(Icons.Default.Hub, s.name, ipSetSubtitle(ctx, s, places.size), s.origin, env, actions)
    if (env.headscale) WarningLine(ctx.getString(R.string.admin_pvd_not_on_headscale))
    NameField(DefKind.IPSET, s.name, model, places.size, editable) { new ->
        if (actions.edit { PolicyEdits.rename(it, s.name, new) }) layout.onSelect(PolicyPath.of(Section.IPSETS.key, new))
    }
    EditorBlock(ctx.getString(R.string.admin_pvd_ipset_rows), ctx.getString(R.string.admin_pvd_ipset_rows_help)) {
        ops.forEachIndexed { i, op ->
            IpSetRow(
                op = op,
                first = i == 0,
                last = i == ops.lastIndex,
                enabled = editable,
                problem = problem,
                onChange = { changed -> write(ops.toMutableList().also { it[i] = changed }) },
                onMove = { by -> write(ops.toMutableList().also { val x = it.removeAt(i); it.add(i + by, x) }) },
                onRemove = { write(ops.filterIndexed { j, _ -> j != i }) },
            )
        }
        if (editable) {
            // Keyed by the row count: a new row written, the field starts over for the next.
            key(ops.size) {
                CommitField(
                    value = "",
                    label = ctx.getString(R.string.admin_pvd_ipset_add_row),
                    onCommit = { t -> write(ops + Definitions.newIpSetOp(ops, t)) },
                    problem = problem,
                    placeholder = ctx.getString(R.string.admin_pvd_ipset_target),
                    resetAfterCommit = true,
                )
            }
        }
    }
    NoteField(s.origin, tree, editable, actions)
    PlacesBlock(places, model, tree, actions)
    EditorBottom(s.origin, actions, ctx.getString(R.string.admin_pvd_ipset_delete).takeIf { editable }, {
        if (actions.edit { PolicyEdits.removeNamed(it, Section.IPSETS, s.name) }) {
            layout.onSelect(null)
            actions.notify(ctx.getString(R.string.admin_pvd_deleted, s.name), undoable = true)
        }
    }, places.size)
}

/** One row of an IP set: Add / Remove, then its target, moved up or down or taken out. */
@Composable
private fun IpSetRow(
    op: IpSetOp,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    problem: (String) -> String?,
    onChange: (IpSetOp) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !op.remove,
                    enabled = enabled,
                    onClick = { if (op.remove) onChange(op.copy(remove = false, explicit = true)) },
                    label = { Text(ctx.getString(R.string.admin_pvd_ipset_op_add)) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = op.remove,
                    enabled = enabled,
                    onClick = { if (!op.remove) onChange(op.copy(remove = true, explicit = true)) },
                    label = { Text(ctx.getString(R.string.admin_pvd_ipset_op_remove)) },
                    leadingIcon = { Icon(Icons.Default.Remove, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.weight(1f))
                if (enabled) {
                    IconButton(onClick = { onMove(-1) }, enabled = !first) { Icon(Icons.Default.KeyboardArrowUp, ctx.getString(R.string.admin_pvd_move_up)) }
                    IconButton(onClick = { onMove(1) }, enabled = !last) { Icon(Icons.Default.KeyboardArrowDown, ctx.getString(R.string.admin_pvd_move_down)) }
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Close, ctx.getString(R.string.admin_pvd_remove_row)) }
                }
            }
            // Kept off the row's right edge, where its icons end; the row says what it is, the field needs no label.
            CommitField(
                value = op.target,
                label = null,
                placeholder = ctx.getString(R.string.admin_pvd_ipset_target_short),
                onCommit = { t -> onChange(op.copy(target = t.trim())) },
                modifier = Modifier.padding(end = 8.dp),
                enabled = enabled,
                problem = problem,
            )
        }
    }
}

/** A new IP set: a name and its first row, written on Create; more rows in its editor. */
@Composable
internal fun NewIpSetForm(env: VisualEnv, actions: VisualActions, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    val nameProblem = Definitions.checkName(DefKind.IPSET, name, model)
    val targetProblem = Definitions.checkIpSetTarget(target, model)
    EditorTop(Icons.Default.Hub, ctx.getString(R.string.admin_pvd_ipset_new), ctx.getString(R.string.admin_pvd_ipsets_help), null, env, actions, monospace = false)
    NewNameField(DefKind.IPSET, name, { name = it }, model, showProblem = false)
    androidx.compose.material3.OutlinedTextField(
        value = target,
        onValueChange = { target = it },
        singleLine = true,
        label = { Text(ctx.getString(R.string.admin_pvd_ipset_first_row)) },
        placeholder = { Text(ctx.getString(R.string.admin_pvd_ipset_target), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        isError = target.isNotBlank() && targetProblem != null,
        supportingText = targetProblem?.takeIf { target.isNotBlank() }?.let { { Text(VisualText.problem(ctx, it)) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    HelpText(ctx.getString(R.string.admin_pvd_ipset_rows_help))
    val ok = nameProblem == null && targetProblem == null
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = ok, hint = ctx.getString(R.string.admin_pvd_ipset_create_hint)) {
        val full = DefKind.IPSET.prefix + name.trim()
        if (actions.edit { PolicyEdits.putNamed(it, Section.IPSETS, full, listOf(Definitions.newIpSetOp(emptyList(), target).text)) }) onCreated(full)
    }
}
