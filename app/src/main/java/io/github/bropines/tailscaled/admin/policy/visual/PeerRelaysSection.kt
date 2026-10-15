package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.RiskFinding
import io.github.bropines.tailscaled.admin.policy.RiskKind
import io.github.bropines.tailscaled.admin.policy.templates.AllTemplatesCard
import io.github.bropines.tailscaled.admin.policy.templates.PolicyTemplate
import io.github.bropines.tailscaled.admin.policy.templates.TemplateFlow
import io.github.bropines.tailscaled.admin.policy.templates.TemplateNav
import io.github.bropines.tailscaled.admin.policy.templates.TemplateSuggestionCard
import io.github.bropines.tailscaled.admin.policy.templates.rememberSuggested
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The PEER RELAYS page: the grants that carry the relay capability, each read as "these devices
 * relay for those", with an editor of its two sides, what the device list says about the devices
 * on each (a phone cannot relay, an old client neither relays nor uses one), the lint's word on a
 * source wide enough that every device would try the relays, and templates on an empty page
 * (policy-templates/, through TemplateFlow). A
 * grant that also opens ports or grants something else is an access rule first: here its sides
 * are shown, and it can be split so that its relay part becomes a rule of its own.
 */

@Composable
fun PeerRelaysSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val policy = remember(text) { HuJson.parseOrNull(text) }
    val findings = remember(text) { Definitions.findings(text) }
    val rules = remember(model) { PeerRelays.rules(model) }
    var form by remember { mutableStateOf<RelayForm?>(null) }
    // A template's page or the catalog, in the editor's place.
    var templates by remember { mutableStateOf<TemplateNav?>(null) }
    val shown = shownOf(layout.selected, layout, rules) { it.origin.path }
    val start: (RelayForm) -> Unit = { f -> form = f; templates = null; layout.onSelect(null) }
    val startTemplate: (TemplateNav) -> Unit = { n -> templates = n; form = null; layout.onSelect(null) }
    val add = if (env.editable) ({ start(RelayForm()) }) else null
    val addLabel = ctx.getString(R.string.admin_pvr_add)

    val items = buildList {
        add(PageItem("intro") {
            PageIntro(ctx.getString(R.string.admin_pvr_help), addLabel.takeIf { rules.isNotEmpty() }, add, errors = sectionErrors(env, Section.GRANTS))
        })
        if (rules.isEmpty()) {
            add(PageItem("empty") {
                RelaysEmpty(env, onAdd = add, onTemplate = { startTemplate(TemplateNav.Page(it.id)) }, onCatalog = { startTemplate(TemplateNav.Catalog()) })
            })
        }
        rules.forEachIndexed { i, r ->
            r.origin.header?.let { add(PageItem("h$i") { CommentHeading(it) }) }
            add(PageItem("r$i", r.origin.path) {
                RelayCard(r, env, actions, policy, selected = layout.twoPane && form == null && templates == null && shown == r) {
                    form = null
                    templates = null
                    layout.onSelect(r.origin.path)
                }
            })
        }
    }
    val flow = templates
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = when {
            flow != null -> "templates"
            form != null -> "new"
            else -> shown?.origin?.path
        },
        onCloseEditor = { form = null; templates = null; layout.onSelect(null) },
        paneEmpty = Icons.Default.DeviceHub to ctx.getString(R.string.admin_pvr_pane_empty),
        scrollTo = env.focus ?: layout.selected,
        scrolls = flow == null,
    ) {
        val f = form
        when {
            flow != null -> TemplateFlow(
                flow, env, actions,
                onNav = { templates = it },
                onApplied = { p ->
                    templates = null
                    // A grant opens here, where a relay rule is edited; a test stays where it went; anything else on its own page.
                    when ((p?.steps?.firstOrNull() as? PathStep.Key)?.let { Section.of(it.name) }) {
                        null, Section.TESTS, Section.SSH_TESTS -> Unit
                        Section.GRANTS -> layout.onSelect(p)
                        else -> actions.open(VisualSection.of(p), p)
                    }
                },
                onShow = { p -> templates = null; actions.open(VisualSection.of(p), p) },
            )
            f != null -> NewRelayForm(f, env, actions, onChange = { form = it }) { p -> form = null; layout.onSelect(p) }
            else -> shown?.let { RelayEditor(it, env, actions, layout, tree, policy, findings[it.origin.path].orEmpty()) }
        }
    }
}

// ---- words ----

/** "2 relays for 5 devices", with "and more" on a side the phone cannot tell whole; null without a device list. */
private fun relayCoverage(ctx: Context, c: RelayCheck, env: VisualEnv): String? {
    if (env.devices.isEmpty()) return null
    val res = ctx.resources
    fun side(cov: Coverage, plural: Int): String {
        val n = res.getQuantityString(plural, cov.devices.size, cov.devices.size)
        return if (cov.complete) n else ctx.getString(R.string.admin_pvd_and_more, n)
    }
    return ctx.getString(R.string.admin_pvr_coverage, side(c.relays, R.plurals.admin_pvr_n_relays), side(c.clients, R.plurals.admin_pvr_n_clients))
}

/** Why a device will not take part, in a line: "pixel: Android cannot relay". */
internal fun relayNoteText(ctx: Context, n: RelayDeviceNote): String = ctx.getString(
    when (n.problem) {
        RelayDeviceProblem.CANNOT_RELAY -> R.string.admin_pvr_note_os
        RelayDeviceProblem.TOO_OLD -> R.string.admin_pvr_note_old
        RelayDeviceProblem.SHARED -> R.string.admin_pvr_note_shared
    },
    n.device.shortName,
    when (n.problem) {
        RelayDeviceProblem.CANNOT_RELAY -> osName(n.device.os)
        else -> n.device.clientVersion?.substringBefore('-').orEmpty()
    },
)

/** A system as people write it: the device list's "android" is Android. */
private fun osName(os: String?): String = when (os?.lowercase()) {
    "android" -> "Android"
    "ios" -> "iOS"
    "tvos" -> "tvOS"
    else -> os.orEmpty()
}

/** What else a rule that is not only about relays does: its ports, its other capabilities. */
private fun alsoText(ctx: Context, r: GrantRule): String {
    val parts = buildList {
        if (r.ip.isNotEmpty()) add(r.ip.joinToString(", ") { ipWords(ctx, it) })
        r.app.filter { it.name != PeerRelays.CAP }.forEach { add(appName(ctx, it.name)) }
        if (r.via.isNotEmpty()) add(ctx.getString(R.string.admin_pvr_also_via, r.via.joinToString(", ")))
        if (r.srcPosture.isNotEmpty()) add(ctx.getString(R.string.admin_pvr_also_posture, r.srcPosture.joinToString(", ")))
    }
    return parts.joinToString(" · ")
}

// ---- the list ----

/** One rule as a card: the relays, the devices they serve, how many of each, and what stands in the way. */
@Composable
private fun RelayCard(r: GrantRule, env: VisualEnv, actions: VisualActions, policy: HuObject?, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val hosts = env.model?.hosts?.map { it.name }?.toSet().orEmpty()
    val check = remember(policy, r, env.devices) { PeerRelays.check(r, policy, env.view) }
    val model = env.model ?: return
    fun problem(slot: SelectorSlot): (String) -> SelectorProblem? = { v -> SelectorRules.check(v, slot, model, env.headscale)?.takeIf { it != SelectorProblem.EMPTY } }
    ElementCard(r.origin, env, actions, selected = selected, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow(ctx.getString(R.string.admin_pvr_c_relays)) {
                if (r.dst.isEmpty()) NothingYet() else SelectorLabels(r.dst, hosts = hosts, max = CARD_MAX, problem = problem(SelectorSlot.RELAY_DST))
            }
            CardRow(ctx.getString(R.string.admin_pvr_c_for)) {
                if (r.src.isEmpty()) NothingYet() else SelectorLabels(r.src, hosts = hosts, max = CARD_MAX, problem = problem(SelectorSlot.RELAY_SRC))
            }
            if (!PeerRelays.relayOnly(r)) CardRow(ctx.getString(R.string.admin_pvr_c_also)) { CardText(alsoText(ctx, r)) }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val line = relayCoverage(ctx, check, env)
                if (line != null) CardFootnote(Icons.Default.Devices, line, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                val out = check.relayNotes.size + check.clientNotes.size
                if (out > 0) {
                    Spacer(Modifier.width(8.dp))
                    KindTag(ctx.resources.getQuantityString(R.plurals.admin_pvr_n_out, out, out), Icons.Default.ErrorOutline)
                }
            }
        }
    }
}

/** A Peer relays page with no rules: what that means, Add, the templates suggested here, and the way to all of them. */
@Composable
private fun RelaysEmpty(env: VisualEnv, onAdd: (() -> Unit)?, onTemplate: (PolicyTemplate) -> Unit, onCatalog: () -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptySection(Icons.Default.DeviceHub, ctx.getString(R.string.admin_pvr_empty), ctx.getString(R.string.admin_pvr_add).takeIf { onAdd != null }, onAdd)
        if (!env.editable) return@Column
        Text(ctx.getString(R.string.admin_pv_templates), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        rememberSuggested(env, "peer-relays", "empty").forEach { t -> TemplateSuggestionCard(t, inTemplates = true) { onTemplate(t) } }
        AllTemplatesCard(onCatalog)
    }
}

// ---- the editor ----

/** The devices on one side of a rule by name, and under them each one that will not take part, and why. */
@Composable
private fun SideDevices(title: String, c: Coverage, notes: List<RelayDeviceNote>, none: String) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DevicesBlock(
            title,
            c.devices.toList(),
            none,
            c.unresolved.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.admin_pvd_attr_unresolved, it.joinToString(", ")) },
        )
        notes.forEach { n -> StatusRow(Icons.Default.ErrorOutline, relayNoteText(ctx, n), MaterialTheme.colorScheme.error) }
    }
}

/**
 * One rule's editor: the relays, the devices that may use them, what the device list says about
 * each side, the note; Delete — the whole rule when it is only about relays, the capability alone
 * otherwise. A rule that is an access rule as well shows its sides without editing them here,
 * and offers to split its relay part off, or to open it on the Access page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelayEditor(r: GrantRule, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, policy: HuObject?, risks: List<RiskFinding>) {
    val ctx = LocalContext.current
    val path = r.origin.path
    val only = PeerRelays.relayOnly(r)
    val editable = env.editable && r.origin.editable
    val fieldEnv = if (editable && only) env else env.copy(canWrite = false)
    val base = env.draft.text
    val check = remember(policy, r, env.devices) { PeerRelays.check(r, policy, env.view) }
    val index = Definitions.indexOf(path) ?: 0

    EditorTop(
        Icons.Default.DeviceHub,
        ctx.getString(R.string.admin_pvr_rule_title, index + 1),
        relayCoverage(ctx, check, env),
        r.origin, env, actions, monospace = false, risks = risks,
    )
    if (!only) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Not a risk: what the rule is besides, and why its sides are edited elsewhere.
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_pvr_mixed, alsoText(ctx, r)), style = MaterialTheme.typography.bodyMedium)
                    HelpText(ctx.getString(R.string.admin_pvr_mixed_help))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editable && PeerRelays.splittable(r)) {
                    OutlinedButton(onClick = {
                        if (actions.editAt(base) { PeerRelays.split(it, path) }) layout.onSelect(PolicyPath.of(Section.GRANTS.key, index + 1))
                    }, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ctx.getString(R.string.admin_pvr_split))
                    }
                }
                OutlinedButton(onClick = { actions.show(path) }, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Policy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.admin_pvr_open_access))
                }
            }
        }
    }
    SelectorField(
        ctx.getString(R.string.admin_pvr_f_relays), r.dst, SelectorSlot.RELAY_DST, fieldEnv,
        onChange = { v -> actions.editAt(base) { PeerRelays.setRelays(it, path, v) } },
        help = ctx.getString(R.string.admin_pvr_f_relays_help),
    )
    SelectorField(
        ctx.getString(R.string.admin_pvr_f_clients), r.src, SelectorSlot.RELAY_SRC, fieldEnv,
        onChange = { v -> actions.editAt(base) { PeerRelays.setClients(it, path, v) } },
        help = ctx.getString(R.string.admin_pvr_f_clients_help),
    )
    if (env.devices.isNotEmpty()) {
        SideDevices(ctx.getString(R.string.admin_pvr_d_relays), check.relays, check.relayNotes, ctx.getString(R.string.admin_pvr_d_none))
        SideDevices(ctx.getString(R.string.admin_pvr_d_clients), check.clients, check.clientNotes, ctx.getString(R.string.admin_pvr_d_none))
        HelpText(ctx.getString(R.string.admin_pvr_d_help))
    }
    NoteField(r.origin, tree, editable, actions)
    EditorBottom(
        r.origin, actions,
        ctx.getString(if (only) R.string.admin_pvr_delete else R.string.admin_pvr_delete_cap).takeIf { editable },
        {
            if (actions.editAt(base) { PeerRelays.remove(it, path) }) {
                layout.onSelect(null)
                actions.notify(ctx.getString(if (only) R.string.admin_pvr_deleted else R.string.admin_pvr_deleted_cap), undoable = true)
            }
        },
    )
}

/** A new rule: the relays and the devices that use them, written on Create. */
@Composable
internal fun NewRelayForm(form: RelayForm, env: VisualEnv, actions: VisualActions, onChange: (RelayForm) -> Unit, onCreated: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    EditorTop(Icons.Default.DeviceHub, ctx.getString(R.string.admin_pvr_new), ctx.getString(R.string.admin_pvr_new_sub), null, env, actions, monospace = false)
    SelectorField(
        ctx.getString(R.string.admin_pvr_f_relays), form.relays, SelectorSlot.RELAY_DST, env,
        onChange = { onChange(form.copy(relays = it)) },
        help = ctx.getString(R.string.admin_pvr_f_relays_help),
    )
    SelectorField(
        ctx.getString(R.string.admin_pvr_f_clients), form.clients, SelectorSlot.RELAY_SRC, env,
        onChange = { onChange(form.copy(clients = it)) },
        help = ctx.getString(R.string.admin_pvr_f_clients_help),
    )
    if (PeerRelays.broad(form.src).isNotEmpty()) {
        WarningLine(PolicyText.riskTitle(ctx, RiskKind.RELAY_BROAD), PolicyText.riskHelp(ctx, RiskKind.RELAY_BROAD))
    }
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = form.complete, hint = ctx.getString(R.string.admin_pvr_create_hint)) {
        if (actions.edit { PeerRelays.add(it, form.src, form.dst) }) onCreated(PolicyPath.of(Section.GRANTS.key, model.grants.size))
    }
}
