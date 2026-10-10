package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.ui.PaneEmptyState

/*
 * The SSH page: SSH rules as cards — who → can SSH to → as which users, accepted or checked
 * every so often — and their editor beside or over the list.
 */

private data class PendingSsh(val rule: SshRule, val index: Int? = null)

@Composable
fun SshSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val m = env.model ?: return
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<PendingSsh?>(null) }
    val selected = layout.selected?.let { p -> m.ssh.firstOrNull { it.origin.path == p } }
    val twoPane = layout.twoPane && (m.ssh.isNotEmpty() || pending != null)
    val shown = pending?.rule ?: selected ?: m.ssh.firstOrNull()?.takeIf { twoPane }
    val listState = rememberLazyListState()
    val rows = remember(m) { pageRows(m.ssh, Section.SSH) { it.origin } }
    val headers = remember(m) { m.ssh.map { it.origin.header != null } }
    val policy = rememberPolicy(env)
    val mover = rememberCardMover(listState) { p -> rowIndex(rows, p, 1) }
    ScrollTo(listState, rows, layout.selected ?: env.focus, skip = mover.followed)

    fun select(p: PolicyPath?) {
        pending = null
        layout.onSelect(p)
    }

    fun startNew(index: Int? = null) {
        layout.onSelect(null)
        val r = RuleForms.emptySsh(m)
        pending = PendingSsh(if (index == null) r else r.copy(origin = r.origin.copy(path = PolicyPath.of(Section.SSH.key, index))), index)
    }

    fun change(old: SshRule, new: SshRule) {
        val p = pending
        if (p != null) {
            val next = p.copy(rule = new)
            val at = p.index ?: m.ssh.size
            if (RuleForms.complete(new) && actions.edit { PolicyEdits.addRule(it, Section.SSH, RuleForms.fields(new), index = p.index) }) {
                select(PolicyPath.of(Section.SSH.key, at))
            } else pending = next
            return
        }
        val diff = RuleForms.changed(RuleForms.fields(old), RuleForms.fields(new))
        if (diff.isNotEmpty()) actions.edit { PolicyEdits.updateRule(it, old.origin.path, diff) }
    }

    fun act(rule: SshRule, a: RuleAction) {
        val path = rule.origin.path
        val i = (path.last as? PathStep.Index)?.index ?: return
        when (a) {
            RuleAction.DISCARD -> select(null)
            RuleAction.DELETE -> if (actions.edit { PolicyEdits.removeRule(it, path) }) {
                select(null)
                actions.notify(ctx.getString(R.string.admin_pv_ssh_deleted), undoable = true)
            }
            RuleAction.DUPLICATE -> if (actions.edit { RuleForms.duplicate(it, path) }) {
                layout.onSelect(PolicyPath.of(Section.SSH.key, i + 1))
                actions.notify(ctx.getString(R.string.admin_pv_rule_duplicated))
            }
            else -> Unit
        }
    }

    // The reminder goes away by itself once the rule is in; the page stays where the person is.
    fun addAccess(rule: SshRule) {
        val section = RuleForms.newAccessSection(m, env.headscale)
        if (actions.edit { PolicyEdits.addRule(it, section, RuleForms.accessForSsh(rule, section)) }) {
            actions.notify(ctx.getString(R.string.admin_pv_ssh_port22_added))
        }
    }

    RulePage(
        layout = layout.copy(twoPane = twoPane),
        sheetOpen = shown != null,
        list = {
            RuleList(layout.copy(twoPane = twoPane), listState) {
                if (m.ssh.isEmpty()) {
                    item(key = "empty") {
                        EmptySection(
                            icon = Icons.Default.Terminal,
                            text = ctx.getString(R.string.admin_pv_ssh_empty),
                            actionLabel = ctx.getString(R.string.admin_pv_ssh_add).takeIf { env.editable },
                            onAction = { startNew() }.takeIf { env.editable },
                        )
                    }
                    return@RuleList
                }
                item(key = "top") {
                    PageHeader(
                        title = ctx.resources.getQuantityString(R.plurals.admin_pv_ssh_count, m.ssh.size, m.ssh.size),
                        help = ctx.getString(R.string.admin_pv_ssh_help),
                        env = env,
                        addLabel = ctx.getString(R.string.admin_pv_ssh_add),
                        onAdd = { startNew() },
                        notes = sectionNotes(m, Section.SSH),
                    )
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is PageRow.Heading -> CommentHeading(row.text, action = if (env.editable) ({
                            IconButton(onClick = { startNew(row.insertAt) }) {
                                Icon(Icons.Default.Add, ctx.getString(R.string.admin_pv_add_here, headingTitle(row.text.lines().first())))
                            }
                        }) else null)
                        is PageRow.Divider -> Unit
                        is PageRow.Item<*> -> {
                            val rule = row.item as SshRule
                            val i = (rule.origin.path.last as? PathStep.Index)?.index ?: 0
                            SshCard(
                                rule, env, actions,
                                selected = twoPane && pending == null && shown?.origin?.path == rule.origin.path,
                                policy = policy,
                                moves = mover.moves(Section.SSH, headers, i, env, actions, layout),
                                modifier = mover.decor(rule.origin.path, env.draft.text),
                            ) { select(rule.origin.path) }
                        }
                    }
                }
            }
        },
        editor = {
            val rule = shown
            if (rule == null) {
                PaneEmptyState(Icons.Default.Terminal, ctx.getString(R.string.admin_pv_pane_empty))
                return@RulePage
            }
            EditorFrame(onDismiss = { select(null) }) {
                key(rule.origin.path, pending != null) {
                    SshRuleEditorContent(
                        rule = rule,
                        env = env,
                        actions = actions,
                        isNew = pending != null,
                        onChange = { change(rule, it) },
                        onAction = { act(rule, it) },
                        onAddAccess = { addAccess(rule) },
                    )
                }
            }
        },
    )
}

/** One SSH rule as a card: who, to which devices, as whom; accept or check; the port-22 reminder; [moves] at its end. */
@Composable
fun SshCard(
    rule: SshRule,
    env: VisualEnv,
    actions: VisualActions,
    selected: Boolean = false,
    policy: HuObject? = rememberPolicy(env),
    moves: CardMoves? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val m = env.model ?: return
    val hosts = remember(m) { m.hosts.map { it.name }.toSet() }
    fun problem(slot: SelectorSlot): (String) -> SelectorProblem? = { v -> SelectorRules.check(v, slot, m, env.headscale)?.takeIf { it != SelectorProblem.EMPTY } }
    ElementCard(rule.origin, env, actions, modifier, selected = selected, onClick = onClick, trailing = moves?.let { { CardMoveButtons(it) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow(ctx.getString(R.string.admin_pv_c_who)) {
                if (rule.src.isEmpty()) NothingYet() else SelectorLabels(rule.src, hosts = hosts, max = CARD_MAX, problem = problem(SelectorSlot.SSH_SRC))
            }
            CardRow(ctx.getString(R.string.admin_pv_c_ssh_to)) {
                if (rule.dst.isEmpty()) NothingYet() else SelectorLabels(rule.dst, hosts = hosts, max = CARD_MAX, problem = problem(SelectorSlot.SSH_DST))
            }
            CardRow(ctx.getString(R.string.admin_pv_c_ssh_as)) {
                if (rule.users.isEmpty()) NothingYet() else SelectorLabels(rule.users, max = CARD_MAX, iconFor = ::loginIcon, problem = problem(SelectorSlot.SSH_USER))
            }
            if (rule.srcPosture.isNotEmpty()) CardRow(ctx.getString(R.string.admin_pv_c_posture)) { SelectorLabels(rule.srcPosture, max = CARD_MAX) }
            val needs = remember(policy, rule, env.devices) { RuleForms.sshNeedsAccess(rule, m, policy, env.view) }
            if (needs) CardFootnote(Icons.Default.Warning, ctx.getString(R.string.admin_pv_ssh_port22), tint = MaterialTheme.colorScheme.tertiary)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val from = remember(policy, rule.src, env.devices) { RuleCoverage.of(rule.src, policy, env.view) }
                val to = remember(policy, rule.dst, env.devices) { RuleCoverage.of(rule.dst, policy, env.view) }
                CoverageFootnote(from, to, env, Modifier.weight(1f))
                if (env.devices.isEmpty()) Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                KindTag(sshMode(ctx, rule), strong = rule.action == "check")
            }
        }
    }
}
