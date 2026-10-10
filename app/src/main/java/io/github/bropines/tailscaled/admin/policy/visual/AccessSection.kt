package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.ui.PaneEmptyState

/*
 * The ACCESS page: acls and grants as cards — who → can reach what → on which ports — under
 * the file's own headings, the rule editor beside or over them, templates on an empty page.
 * New rules are written in the syntax the file already uses most (RuleForms.newAccessSection).
 */

/** A rule being made: its form, and where it goes (null: the end of its section). */
private data class PendingRule(val rule: AccessRule, val index: Int? = null)

/** A rule just deleted, and the text before it went: the offer of a test lasts until the next edit. */
private data class DeletedRule(val before: String, val rule: AccessRule)

/** The access rules of [m] in file order: acls and grants, each list in its own order. */
fun accessRules(m: PolicyModel): List<AccessRule> = m.acls.map { AccessRule.Acl(it) } + m.grants.map { AccessRule.Grant(it) }

@Composable
fun AccessSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val m = env.model ?: return
    val ctx = LocalContext.current
    val rules = remember(m) { accessRules(m) }
    var pending by remember { mutableStateOf<PendingRule?>(null) }
    var deleted by remember { mutableStateOf<DeletedRule?>(null) }
    val selected = layout.selected?.let { p -> rules.firstOrNull { it.origin.path == p } }
    val twoPane = layout.twoPane && (rules.isNotEmpty() || pending != null)
    val shown = pending?.rule ?: selected ?: rules.firstOrNull()?.takeIf { twoPane }
    val listState = rememberLazyListState()
    val newSection = RuleForms.newAccessSection(m, env.headscale)
    val policy = rememberPolicy(env)

    val rows = remember(m) {
        // acls and grants each under their own headings; a divider names the second list when there are both.
        val acls = pageRows(rules.filterIsInstance<AccessRule.Acl>(), Section.ACLS) { it.origin }
        val grants = pageRows(rules.filterIsInstance<AccessRule.Grant>(), Section.GRANTS) { it.origin }
        val aclsFirst = (m.acls.firstOrNull()?.origin?.line ?: Int.MAX_VALUE) <= (m.grants.firstOrNull()?.origin?.line ?: Int.MAX_VALUE)
        val (first, second) = if (aclsFirst) acls to grants else grants to acls
        val secondSection = if (aclsFirst) Section.GRANTS else Section.ACLS
        if (first.isNotEmpty() && second.isNotEmpty()) first + PageRow.Divider(secondSection.key, secondSection) + second else first + second
    }
    ScrollTo(listState, rows, layout.selected ?: env.focus)

    fun select(p: PolicyPath?) {
        pending = null
        layout.onSelect(p)
    }

    fun startNew(rule: AccessRule, index: Int? = null) {
        layout.onSelect(null)
        pending = PendingRule(rule, index)
    }

    fun sizeOf(section: Section) = if (section == Section.GRANTS) m.grants.size else m.acls.size

    /** Write a finished form; the editor then shows the rule as the file has it. */
    fun write(p: PendingRule): Boolean {
        val section = p.rule.section
        val at = p.index ?: sizeOf(section)
        val ok = actions.edit { PolicyEdits.addRule(it, section, RuleForms.fields(p.rule), index = p.index, anchor = Anchor.AFTER_PREVIOUS) }
        if (ok) select(PolicyPath.of(section.key, at))
        return ok
    }

    fun change(old: AccessRule, new: AccessRule) {
        val p = pending
        if (p != null) {
            // A form that turned into a grant goes to the end of the grants, not to its place among the acls.
            val next = if (new.section != p.rule.section) PendingRule(new) else p.copy(rule = new)
            if (!RuleForms.complete(new) || !write(next)) pending = next
            return
        }
        if (old is AccessRule.Acl && new is AccessRule.Grant) {
            // Given a via, the ACL became a grant: it moves to the grants, its note with it.
            val at = m.grants.size
            if (actions.edit { RuleForms.replaceWithGrant(it, old.rule, new.rule) }) {
                select(PolicyPath.of(Section.GRANTS.key, at))
                actions.notify(ctx.resources.getQuantityString(R.plurals.admin_pv_converted, 1, 1))
            }
            return
        }
        val diff = RuleForms.changed(RuleForms.fields(old), RuleForms.fields(new))
        if (diff.isNotEmpty()) actions.edit { PolicyEdits.updateRule(it, old.origin.path, diff) }
    }

    fun headers(section: Section) = rules.filter { it.section == section }.map { it.origin.header != null }

    fun act(rule: AccessRule, a: RuleAction) {
        val path = rule.origin.path
        when (a) {
            RuleAction.DISCARD -> select(null)
            RuleAction.DELETE -> {
                val before = env.draft.text
                if (actions.edit { PolicyEdits.removeRule(it, path) }) {
                    deleted = DeletedRule(before, rule)
                    select(null)
                    actions.notify(ctx.getString(R.string.admin_pv_rule_deleted), undoable = true)
                }
            }
            RuleAction.DUPLICATE -> if (actions.edit { RuleForms.duplicate(it, path) }) {
                layout.onSelect(PolicyPath.of(rule.section.key, rule.index + 1))
                actions.notify(ctx.getString(R.string.admin_pv_rule_duplicated))
            }
            RuleAction.MOVE_UP, RuleAction.MOVE_DOWN -> {
                val h = headers(rule.section)
                val to = (if (a == RuleAction.MOVE_UP) RuleForms.moveUp(h, rule.index) else RuleForms.moveDown(h, rule.index)) ?: return
                if (actions.edit { PolicyEdits.moveRule(it, rule.section, rule.index, to.first, to.second) }) {
                    layout.onSelect(PolicyPath.of(rule.section.key, to.first))
                }
            }
            RuleAction.ADD_TEST -> {
                val f = testOf(rule, accept = true) ?: return actions.notify(ctx.getString(R.string.admin_pv_test_none))
                if (actions.edit { PolicyEdits.addRule(it, Section.TESTS, f) }) {
                    actions.show(PolicyPath.of(Section.TESTS.key, m.tests.size))
                    actions.notify(ctx.getString(R.string.admin_pv_test_added))
                }
            }
            RuleAction.CONVERT -> {
                val acl = (rule as? AccessRule.Acl)?.rule ?: return
                val count = PolicyEdits.grantsFrom(acl).size
                if (actions.edit { RuleForms.convertToGrants(it, acl) }) {
                    select(PolicyPath.of(Section.GRANTS.key, m.grants.size))
                    actions.notify(ctx.resources.getQuantityString(R.plurals.admin_pv_converted, count, count))
                }
            }
        }
    }

    RulePage(
        layout = layout.copy(twoPane = twoPane),
        sheetOpen = shown != null,
        list = {
            RuleList(layout.copy(twoPane = twoPane), listState) {
                if (rules.isEmpty()) {
                    item(key = "empty") { AccessEmpty(env, m, newSection, onAdd = { startNew(RuleForms.emptyAccess(m, newSection)) }, onTemplate = { t ->
                        val form = RuleForms.template(t, m, newSection)
                        if (RuleForms.complete(form)) write(PendingRule(form)) else startNew(form)
                    }) }
                    return@RuleList
                }
                item(key = "top") {
                    PageHeader(
                        title = ctx.resources.getQuantityString(R.plurals.admin_pv_access_count, rules.size, rules.size),
                        help = ctx.getString(R.string.admin_pv_access_help),
                        env = env,
                        addLabel = ctx.getString(R.string.admin_pv_add_rule),
                        onAdd = { startNew(RuleForms.emptyAccess(m, newSection)) },
                        notes = sectionNotes(m, Section.ACLS, Section.GRANTS),
                    )
                }
                val d = deleted
                if (d != null && env.draft.undoStack.lastOrNull() == d.before && env.draft.text != d.before) {
                    val test = testOf(d.rule, accept = false)
                    if (test != null) item(key = "deleted") {
                        DeletedOffer(
                            text = ctx.getString(R.string.admin_pv_deleted_offer),
                            action = ctx.getString(R.string.admin_pv_deleted_offer_action),
                            onAction = {
                                if (actions.edit { PolicyEdits.addRule(it, Section.TESTS, test) }) actions.notify(ctx.getString(R.string.admin_pv_test_added))
                                deleted = null
                            },
                            onDismiss = { deleted = null },
                        )
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is PageRow.Heading -> CommentHeading(row.text, action = if (env.editable) ({
                            IconButton(onClick = {
                                val form = RuleForms.emptyAccess(m, row.section)
                                startNew(form.withPath(PolicyPath.of(row.section.key, row.insertAt)), row.insertAt)
                            }) { Icon(Icons.Default.Add, ctx.getString(R.string.admin_pv_add_here, headingTitle(row.text.lines().first()))) }
                        }) else null)
                        is PageRow.Divider -> ListDivider(ctx.getString(if (row.section == Section.GRANTS) R.string.admin_pv_divider_grants else R.string.admin_pv_divider_acls))
                        is PageRow.Item<*> -> {
                            val rule = row.item as AccessRule
                            AccessCard(rule, env, actions, selected = twoPane && shown?.origin?.path == rule.origin.path && pending == null, policy = policy) { select(rule.origin.path) }
                        }
                    }
                }
            }
        },
        editor = {
            val rule = shown
            if (rule == null) {
                PaneEmptyState(Icons.Default.Policy, ctx.getString(R.string.admin_pv_pane_empty))
                return@RulePage
            }
            EditorFrame(onDismiss = { select(null) }) {
                key(rule.origin.path, pending != null) {
                    val h = headers(rule.section)
                    val moves = movesOf(h, rule.index).copy(
                        test = testOf(rule, accept = true) != null,
                        convert = rule is AccessRule.Acl && rule.origin.editable && RuleForms.canConvert(m, env.headscale),
                    )
                    AccessRuleEditorContent(
                        rule = rule,
                        env = env,
                        actions = actions,
                        isNew = pending != null,
                        moves = moves,
                        onChange = { change(rule, it) },
                        onAction = { act(rule, it) },
                    )
                }
            }
        },
    )
}

/** The test that pins what [rule] allows, or with [accept] false what it no longer may. */
private fun testOf(rule: AccessRule, accept: Boolean): Fields? = when (rule) {
    is AccessRule.Acl -> PolicyEdits.testFrom(rule.rule, accept)
    is AccessRule.Grant -> PolicyEdits.testFrom(rule.rule, accept)
}

/** The form at another place in its list: "Add a rule here" under a heading. */
private fun AccessRule.withPath(path: PolicyPath): AccessRule = when (this) {
    is AccessRule.Acl -> copy(rule = rule.copy(origin = rule.origin.copy(path = path)))
    is AccessRule.Grant -> copy(rule = rule.copy(origin = rule.origin.copy(path = path)))
}

/**
 * One rule as a card: who, where, on which ports, and the rarer parts when it has them; under
 * them which devices it connects, and whether it is an ACL or a grant.
 */
@Composable
fun AccessCard(
    rule: AccessRule,
    env: VisualEnv,
    actions: VisualActions,
    selected: Boolean = false,
    policy: HuObject? = rememberPolicy(env),
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val m = env.model ?: return
    val hosts = remember(m) { m.hosts.map { it.name }.toSet() }
    fun problem(slot: SelectorSlot): (String) -> SelectorProblem? = { v -> SelectorRules.check(v, slot, m, env.headscale)?.takeIf { it != SelectorProblem.EMPTY } }
    ElementCard(rule.origin, env, actions, selected = selected, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow(ctx.getString(R.string.admin_pv_c_who)) {
                SelectorLabels(rule.src, hosts = hosts, max = CARD_MAX, problem = problem(if (rule is AccessRule.Grant) SelectorSlot.GRANT_SRC else SelectorSlot.ACL_SRC))
            }
            when (rule) {
                is AccessRule.Acl -> {
                    val r = rule.rule
                    val dests = r.destinations
                    val same = dests.map { it.ports }.distinct().singleOrNull()
                    val uniform = dests.isNotEmpty() && dests.all { it.ports != null } && same != null
                    CardRow(ctx.getString(R.string.admin_pv_c_reach)) {
                        if (dests.isEmpty()) NothingYet() else DestinationLabels(dests, env, showPorts = !uniform, max = CARD_MAX)
                    }
                    if (uniform || r.proto != null) {
                        CardRow(ctx.getString(R.string.admin_pv_c_ports)) {
                            val words = listOfNotNull(same?.takeIf { uniform }?.let { portWords(ctx, it) }, r.proto?.let { ctx.getString(R.string.admin_pv_c_proto_only, PortWords.proto(it)) })
                            CardText(words.joinToString(" · "))
                        }
                    }
                }
                is AccessRule.Grant -> {
                    val r = rule.rule
                    CardRow(ctx.getString(R.string.admin_pv_c_reach)) {
                        if (r.dst.isEmpty()) NothingYet() else SelectorLabels(r.dst, hosts = hosts, max = CARD_MAX, problem = problem(SelectorSlot.GRANT_DST))
                    }
                    if (r.ip.isNotEmpty()) CardRow(ctx.getString(R.string.admin_pv_c_ports)) { CardText(r.ip.joinToString(", ") { ipWords(ctx, it) }) }
                    if (r.via.isNotEmpty()) CardRow(ctx.getString(R.string.admin_pv_c_via)) { SelectorLabels(r.via, hosts = hosts, max = CARD_MAX) }
                    if (r.app.isNotEmpty()) CardRow(ctx.getString(R.string.admin_pv_c_apps)) { CardText(r.app.joinToString(", ") { appName(ctx, it.name) }) }
                }
            }
            val posture = when (rule) {
                is AccessRule.Acl -> rule.rule.srcPosture
                is AccessRule.Grant -> rule.rule.srcPosture
            }
            if (posture.isNotEmpty()) CardRow(ctx.getString(R.string.admin_pv_c_posture)) { SelectorLabels(posture, max = CARD_MAX) }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val toHosts = when (rule) {
                    is AccessRule.Acl -> rule.rule.destinations.map { it.host }
                    is AccessRule.Grant -> rule.rule.dst
                }
                val from = remember(policy, rule.src, env.devices) { RuleCoverage.of(rule.src, policy, env.view) }
                val to = remember(policy, toHosts, env.devices) { RuleCoverage.of(toHosts, policy, env.view) }
                CoverageFootnote(from, to, env, Modifier.weight(1f))
                if (env.devices.isEmpty()) Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                KindTag(ctx.getString(if (rule is AccessRule.Grant) R.string.admin_pv_kind_grant else R.string.admin_pv_kind_acl))
            }
        }
    }
}

/** How many selectors a card shows of one row before "+N". */
internal const val CARD_MAX = 6

@Composable
internal fun CardText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
}

/** An empty row of a card: a rule that names nothing there yet. */
@Composable
internal fun NothingYet() {
    val ctx = LocalContext.current
    Text(ctx.getString(R.string.admin_pv_c_nothing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 3.dp))
}

/** An Access page with no rules: what that means, Add, and the templates. */
@Composable
private fun AccessEmpty(env: VisualEnv, m: PolicyModel, section: Section, onAdd: () -> Unit, onTemplate: (AccessTemplate) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptySection(
            icon = Icons.Default.Policy,
            text = ctx.getString(R.string.admin_pv_access_empty),
            actionLabel = ctx.getString(R.string.admin_pv_add_rule).takeIf { env.editable },
            onAction = onAdd.takeIf { env.editable },
        )
        if (!env.editable) return@Column
        Text(ctx.getString(R.string.admin_pv_templates), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        RuleForms.templates(env.headscale).forEach { t ->
            val form = RuleForms.template(t, m, section)
            Card(
                onClick = { onTemplate(t) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ctx.getString(templateTitle(t)), style = MaterialTheme.typography.titleSmall)
                        Text(
                            ctx.getString(templateHelp(t)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(if (RuleForms.complete(form)) Icons.Default.Add else Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun templateTitle(t: AccessTemplate): Int = when (t) {
    AccessTemplate.OWN_DEVICES -> R.string.admin_pv_tpl_own
    AccessTemplate.ADMINS_EVERYTHING -> R.string.admin_pv_tpl_admins
    AccessTemplate.GUESTS_INTERNET -> R.string.admin_pv_tpl_guests
    AccessTemplate.GROUP_TO_TAG -> R.string.admin_pv_tpl_group_tag
}

private fun templateHelp(t: AccessTemplate): Int = when (t) {
    AccessTemplate.OWN_DEVICES -> R.string.admin_pv_tpl_own_help
    AccessTemplate.ADMINS_EVERYTHING -> R.string.admin_pv_tpl_admins_help
    AccessTemplate.GUESTS_INTERNET -> R.string.admin_pv_tpl_guests_help
    AccessTemplate.GROUP_TO_TAG -> R.string.admin_pv_tpl_group_tag_help
}
