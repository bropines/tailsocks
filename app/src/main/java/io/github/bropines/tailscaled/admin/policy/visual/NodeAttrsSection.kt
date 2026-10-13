package io.github.bropines.tailscaled.admin.policy.visual

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.RiskFinding
import io.github.bropines.tailscaled.ui.HelpText

/**
 * The ATTRIBUTES page (nodeAttrs): rules that turn features on for chosen devices — Funnel,
 * Taildrive, Mullvad, a NextDNS profile — each as "applies to → turns on". The editor offers the
 * attributes it knows as switches with what each does, keeps any other as written, and shows
 * application capabilities and IP pools, which it leaves to the JSON editor.
 */
@Composable
fun NodeAttrsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val policy = remember(text) { HuJson.parseOrNull(text) }
    val findings = remember(text) { Definitions.findings(text) }
    val rules = model.nodeAttrs
    // Taildrive in one step, while the policy gives it to no device; Headscale has no Taildrive.
    val taildrive = env.editable && !env.headscale && !TaildriveRules.covered(model)
    // The new rule's form, with what a template filled in; null while none is open. An editor a
    // Taildrive screen opened starts with that template's form up, once.
    val launched = env.launch == VisualLaunch.TAILDRIVE
    var form by remember { mutableStateOf(if (launched && taildrive) taildrivePreset(model) else null) }
    LaunchedEffect(launched) { if (launched) actions.launchTaken() }
    val creating = form != null
    val shown = shownOf(layout.selected, layout, rules) { it.origin.path }
    val start: (AttrPreset) -> Unit = { form = it; layout.onSelect(null) }
    val add = if (env.editable) ({ start(AttrPreset()) }) else null
    val addLabel = ctx.getString(R.string.admin_pvd_attr_new)
    val listState = rememberLazyListState()
    // Moved from their cards, under the file's headings as the rule pages move theirs.
    val headers = remember(model) { rules.map { it.origin.header != null } }
    // A card's item: after the intro and the Taildrive template, each rule's own, with its heading's before it.
    val before = if (taildrive) 2 else 1
    val mover = rememberCardMover(listState) { p ->
        val k = rules.indexOfFirst { it.origin.path == p }
        if (k < 0) null else before + (0..k).count { headers[it] } + k
    }

    val items = buildList {
        add(PageItem("intro") {
            PageIntro(ctx.getString(R.string.admin_pvd_attrs_help), addLabel.takeIf { rules.isNotEmpty() }, add, defsSectionNotes(model, Section.NODE_ATTRS), sectionErrors(env, Section.NODE_ATTRS))
        })
        // First, where it is seen; the mover counts it ([before]).
        if (taildrive) add(PageItem("tpl-drive") { AttrTemplateCard(AttrTemplate.TAILDRIVE) { start(taildrivePreset(model)) } })
        if (rules.isEmpty()) add(PageItem("empty") { EmptySection(Icons.Default.Hub, ctx.getString(R.string.admin_pvd_attrs_empty), addLabel.takeIf { add != null }, add) })
        rules.forEachIndexed { i, r ->
            r.origin.header?.let { add(PageItem("h$i") { CommentHeading(it) }) }
            add(PageItem("a$i", r.origin.path) {
                AttrCard(
                    r, env, actions, policy,
                    selected = layout.twoPane && !creating && shown == r,
                    moves = mover.moves(Section.NODE_ATTRS, headers, i, env, actions, layout),
                    modifier = mover.decor(r.origin.path, text),
                ) {
                    form = null
                    layout.onSelect(r.origin.path)
                }
            })
        }
        // After the rules, so that a card's place in the list stays what the mover counts.
        if (env.editable && rules.none { KnownAttrs.CACHE_NETMAPS in it.attr }) {
            add(PageItem("tpl-cache") { AttrTemplateCard(AttrTemplate.FASTER_START) { start(AttrTemplate.FASTER_START.preset) } })
        }
    }
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = if (creating) "new" else shown?.origin?.path,
        onCloseEditor = { form = null; layout.onSelect(null) },
        paneEmpty = Icons.Default.Hub to ctx.getString(R.string.admin_pvd_attrs_pane_empty),
        scrollTo = (env.focus ?: layout.selected).takeIf { it != mover.followed },
        state = listState,
    ) {
        val f = form
        if (f != null) NewAttrForm(env, actions, f) { p -> form = null; layout.onSelect(p) }
        else shown?.let { AttrEditor(it, env, actions, layout, tree, policy, findings[it.origin.path].orEmpty()) }
    }
}

/** "Device attributes 3": rules have no names, only their place. */
private fun ruleTitle(ctx: android.content.Context, r: NodeAttr): String =
    ctx.getString(R.string.admin_pvd_place_attr, (Definitions.indexOf(r.origin.path) ?: 0) + 1)

/** "3 devices", "3 devices and more" when a target cannot be told from here. */
private fun coverageText(ctx: android.content.Context, targets: List<String>, policy: HuObject?, env: VisualEnv): String? {
    if (env.devices.isEmpty() || targets.isEmpty()) return null
    val c = RuleCoverage.of(targets, policy, env.view)
    val n = VisualText.devices(ctx, c.devices.size)
    return when {
        c.complete -> n
        // Nothing resolved here and something left to the server: no count is better than "0 and more".
        c.devices.isEmpty() -> null
        else -> ctx.getString(R.string.admin_pvd_and_more, n)
    }
}

/** An attribute on a card: its name in words; one Headscale refuses is outlined and says so. */
@Composable
private fun AttrLabel(attr: String, headscale: Boolean) {
    val ctx = LocalContext.current
    val refused = KnownAttrs.refused(attr, headscale)
    val known = KnownAttrs.of(attr) != null
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (known) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (refused) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = if (refused) Modifier.semantics { contentDescription = "${KnownAttrs.label(ctx, attr)}: ${ctx.getString(R.string.admin_pv_problem_headscale)}" } else Modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                KnownAttrs.label(ctx, attr),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (known) null else FontFamily.Monospace,
                color = if (known) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (refused) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** What an attribute rule says besides target and attr: app capabilities and an IP pool, left to JSON. */
@Composable
private fun AttrExtras(r: NodeAttr) {
    val ctx = LocalContext.current
    // A capability the editor knows by its words (and stage), the others by name.
    val lines = r.app.map { a -> ctx.getString(R.string.admin_pvd_attr_app, KnownCaps.node(a.name)?.let { KnownCaps.label(ctx, it, a.name) } ?: a.name) } +
        r.ipPool.map { ctx.getString(R.string.admin_pvd_attr_ip_pool, it) }
    lines.forEach { line ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttrCard(
    r: NodeAttr,
    env: VisualEnv,
    actions: VisualActions,
    policy: HuObject?,
    selected: Boolean,
    moves: CardMoves?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    ElementCard(r.origin, env, actions, modifier, selected = selected, onClick = onClick, trailing = moves?.let { { CardMoveButtons(it) } }) {
        LimitedLabels(r.target, env.model?.hosts?.map { it.name }?.toSet().orEmpty(), caption = ctx.getString(R.string.admin_pvd_attr_targets))
        if (r.attr.isNotEmpty() || r.app.isEmpty()) {
            LimitedLabels(
                r.attr,
                max = 12,
                caption = ctx.getString(R.string.admin_pvd_attr_turns_on),
                empty = { Text(ctx.getString(R.string.admin_pvd_attr_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            ) { AttrLabel(it, env.headscale) }
        }
        AttrExtras(r)
        coverageText(ctx, r.target, policy, env)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The attribute switches: every attribute the editor knows, on when [attrs] has it, with what it
 * does folded under its name; one Headscale refuses is off-limits there and says so. A profile
 * attribute (NextDNS, Control D) asks for its profile when switched on and writes nothing until
 * one is typed.
 */
@Composable
private fun KnownAttrSwitches(attrs: List<String>, enabled: Boolean, headscale: Boolean, onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf(emptySet<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        KnownAttrs.offered(headscale, attrs).forEach { k ->
            val current = KnownAttrs.find(k, attrs)
            val on = current != null || k.key in pending
            val refused = headscale && !k.headscale
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = on, enabled = enabled && (!refused || on), role = Role.Checkbox) { now ->
                        when {
                            k.takesValue && now -> pending = pending + k.key
                            k.takesValue -> { pending = pending - k.key; if (current != null) onChange(KnownAttrs.toggle(attrs, k, false)) }
                            else -> onChange(KnownAttrs.toggle(attrs, k, now))
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = on, onCheckedChange = null, enabled = enabled && (!refused || on))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 1.dp)) {
                    Text(ctx.getString(k.label), style = MaterialTheme.typography.bodyLarge)
                    if (refused) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text(ctx.getString(R.string.admin_pv_problem_headscale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    } else if (headscale && !k.headscaleEffect) {
                        Text(ctx.getString(R.string.admin_pvc_attr_no_effect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    HelpText(ctx.getString(k.help), lines = 1, inClickableRow = true)
                }
            }
            if (k.takesValue && on) {
                CommitField(
                    value = current?.let { KnownAttrs.valueOf(it) }.orEmpty(),
                    label = ctx.getString(R.string.admin_pvd_attr_profile),
                    onCommit = { v -> pending = pending - k.key; onChange(KnownAttrs.toggle(attrs, k, true, v)) },
                    modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
                    enabled = enabled,
                    problem = { v -> if (v.isBlank() || v.any { it.isWhitespace() }) ctx.getString(R.string.admin_pvd_attr_profile_problem) else null },
                )
            }
        }
    }
}

/** Attributes the editor does not know, as written: removable chips and a field for one more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherAttrs(attrs: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val others = attrs.filter { KnownAttrs.of(it) == null }
    EditorBlock(ctx.getString(R.string.admin_pvd_attr_other), ctx.getString(R.string.admin_pvd_attr_other_help)) {
        if (others.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                others.forEach { a ->
                    InputChip(
                        selected = false,
                        enabled = enabled,
                        onClick = { onChange(attrs - a) },
                        label = { Text(a, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) },
                        modifier = Modifier.semantics { contentDescription = ctx.getString(R.string.admin_pv_remove, a) },
                    )
                }
            }
        }
        if (enabled) {
            androidx.compose.runtime.key(attrs.size) {
                CommitField(
                    value = "",
                    label = ctx.getString(R.string.admin_pvd_attr_type),
                    onCommit = { a -> if (a.trim() !in attrs) onChange(attrs + a.trim()) },
                    placeholder = "nextdns:abc123",
                    problem = { a -> if (a.isBlank() || a.trim().any { it.isWhitespace() || it == '"' }) ctx.getString(R.string.admin_pvd_attr_type_problem) else null },
                    resetAfterCommit = true,
                )
            }
        }
    }
}

/**
 * A rule's editor: the devices it applies to, the attributes it turns on, what it carries
 * besides (shown), the devices it reaches, its note; delete, duplicate. Moves are on its card.
 */
@Composable
internal fun AttrEditor(r: NodeAttr, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, policy: HuObject?, risks: List<RiskFinding>) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val editable = env.editable && r.origin.editable
    val fieldEnv = if (editable) env else env.copy(canWrite = false)
    val path = r.origin.path
    val index = Definitions.indexOf(path) ?: 0
    val base = env.draft.text
    fun attrs(v: List<String>) = actions.editAt(base) { PolicyEdits.updateRule(it, path, listOf("attr" to v.pv())) }

    EditorTop(Icons.Default.Hub, ruleTitle(ctx, r), coverageText(ctx, r.target, policy, env), r.origin, env, actions, monospace = false, risks = risks)
    SelectorField(ctx.getString(R.string.admin_pvd_attr_targets), r.target, SelectorSlot.NODE_TARGET, fieldEnv, onChange = { v ->
        actions.edit { PolicyEdits.updateRule(it, path, listOf("target" to v.pv())) }
    })
    EditorBlock(ctx.getString(R.string.admin_pvd_attr_turns_on)) {
        KnownAttrSwitches(r.attr, editable, env.headscale, ::attrs)
        // Tailscale's control plane lets the disabling one win: the devices do not cache.
        if (!env.headscale && KnownAttrs.CACHE_NETMAPS in r.attr && "disable-cache-network-maps" in r.attr) {
            WarningLine(ctx.getString(R.string.admin_pvc_attr_cache_both))
        }
    }
    OtherAttrs(r.attr, editable, ::attrs)
    if (r.app.isNotEmpty() || r.ipPool.isNotEmpty()) {
        EditorBlock(ctx.getString(R.string.admin_pvd_attr_json_only), ctx.getString(R.string.admin_pvd_attr_json_only_help)) {
            AttrExtras(r)
            // What the known capabilities do: group visibility, an app connector.
            r.app.mapNotNull { KnownCaps.node(it.name)?.help }.distinct().forEach { HelpText(ctx.getString(it)) }
            // Headscale parses an IP pool and refuses it (ErrNodeAttrIPPoolUnsupported); its nodeAttrs have no `app` at all.
            if (env.headscale && (r.ipPool.isNotEmpty() || r.app.isNotEmpty())) WarningLine(ctx.getString(R.string.admin_pvd_not_on_headscale))
        }
    }
    if (env.devices.isNotEmpty()) {
        val c = RuleCoverage.of(r.target, policy, env.view)
        DevicesBlock(
            ctx.getString(R.string.admin_pvd_attr_devices),
            c.devices.toList(),
            ctx.getString(R.string.admin_pvd_attr_no_devices),
            c.unresolved.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.admin_pvd_attr_unresolved, it.joinToString(", ")) },
        )
    }
    NoteField(r.origin, tree, editable, actions)
    val more = if (!editable) emptyList() else listOf(
        DefsEditorAction(Icons.Default.ContentCopy, ctx.getString(R.string.admin_pvd_duplicate), enabled = r.app.isEmpty() && r.ipPool.isEmpty() && r.origin.extra.isEmpty()) {
            if (actions.edit { Definitions.duplicateNodeAttr(it, r) }) layout.onSelect(path.parent() + (index + 1))
        },
    )
    EditorBottom(r.origin, actions, ctx.getString(R.string.admin_pvd_delete).takeIf { editable }, {
        if (actions.edit { PolicyEdits.removeRule(it, path) }) {
            layout.onSelect(null)
            actions.notify(ctx.getString(R.string.admin_pvd_rule_deleted), undoable = true)
        }
    }, more = more)
}

/**
 * A new rule's starting point: empty, or what a template fills in, with a [hint] under its
 * devices. With [driveGrant] the rule is written together with the grant Taildrive needs
 * ([TaildriveRules.withGrant]), as one step.
 */
internal data class AttrPreset(
    val targets: List<String> = emptyList(),
    val attrs: List<String> = emptyList(),
    @param:StringRes val hint: Int? = null,
    val driveGrant: Boolean = false,
)

/**
 * Ready-made device attribute rules, offered while the policy has none like them: Taildrive
 * over the list, the rest under it.
 */
internal enum class AttrTemplate(val preset: AttrPreset) {
    /** Members' devices keep the network map and start from it: autogroup:member, the servers' tags to add. */
    FASTER_START(AttrPreset(listOf("autogroup:member"), listOf(KnownAttrs.CACHE_NETMAPS), R.string.admin_pvc_tpl_faster_hint)),

    /** Members' devices share folders and open those on their other devices: both attributes, and the grant with them. */
    TAILDRIVE(AttrPreset(TaildriveRules.MEMBERS, TaildriveRules.ATTRS, R.string.admin_pvtd_hint, driveGrant = true)),
}

/** The Taildrive template's form: a policy that has a Taildrive grant keeps it as it is, and the form says so. */
internal fun taildrivePreset(model: PolicyModel): AttrPreset =
    AttrTemplate.TAILDRIVE.preset.let { if (TaildriveRules.hasGrant(model)) it.copy(hint = R.string.admin_pvtd_hint_grant_kept) else it }

/** A template among the rules: what it does, a tap opens the form it fills. */
@Composable
private fun AttrTemplateCard(t: AttrTemplate, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ctx.getString(R.string.admin_pv_templates), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(ctx.getString(templateTitle(t)), style = MaterialTheme.typography.titleSmall)
                HelpText(ctx.getString(templateHelp(t)), inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun templateTitle(t: AttrTemplate): Int = when (t) {
    AttrTemplate.FASTER_START -> R.string.admin_pvc_tpl_faster
    AttrTemplate.TAILDRIVE -> R.string.admin_pvtd_tpl
}

private fun templateHelp(t: AttrTemplate): Int = when (t) {
    AttrTemplate.FASTER_START -> R.string.admin_pvc_tpl_faster_help
    AttrTemplate.TAILDRIVE -> R.string.admin_pvtd_tpl_help
}

/** A new rule: devices and at least one attribute before anything is written; [preset] fills it in from a template. */
@Composable
internal fun NewAttrForm(env: VisualEnv, actions: VisualActions, preset: AttrPreset = AttrPreset(), onCreated: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var targets by remember { mutableStateOf(preset.targets) }
    var attrs by remember { mutableStateOf(preset.attrs) }
    // The Taildrive template's grant goes with its attributes: with both switched off the rule is like any other.
    val grant = preset.driveGrant && attrs.any { it in TaildriveRules.ATTRS }
    EditorTop(Icons.Default.Hub, ctx.getString(R.string.admin_pvd_attr_new), ctx.getString(R.string.admin_pvd_attrs_empty), null, env, actions, monospace = false)
    SelectorField(
        ctx.getString(R.string.admin_pvd_attr_targets), targets, SelectorSlot.NODE_TARGET, env,
        onChange = { targets = it },
        help = preset.hint?.takeIf { grant || !preset.driveGrant }?.let(ctx::getString),
    )
    EditorBlock(ctx.getString(R.string.admin_pvd_attr_turns_on)) {
        KnownAttrSwitches(attrs, true, env.headscale) { attrs = it }
    }
    OtherAttrs(attrs, true) { attrs = it }
    CreateButton(ctx.getString(R.string.admin_pvd_attr_create), enabled = targets.isNotEmpty() && attrs.isNotEmpty(), hint = ctx.getString(R.string.admin_pvd_attr_create_hint)) {
        // One edit, so one step to undo: the rule, and the template's grant after it.
        val written = actions.edit { text ->
            PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(targets, attrs)).let { if (grant) TaildriveRules.withGrant(it) else it }
        }
        if (written) onCreated(PolicyPath.of(Section.NODE_ATTRS.key, model.nodeAttrs.size))
    }
}
