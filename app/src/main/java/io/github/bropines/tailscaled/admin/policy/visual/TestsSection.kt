package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PaneEmptyState
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet

/*
 * The TESTS page: access tests and SSH tests, each what must stay allowed or denied; added by
 * hand or from an access rule. Deleting one asks first, once: tests are what stops a careless
 * change from reaching the server.
 */

/** A test being made, of either kind. */
private sealed class PendingTest {
    data class Access(val test: AclTest) : PendingTest()
    data class Ssh(val test: SshTest) : PendingTest()
}

@Composable
fun TestsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val m = env.model ?: return
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<PendingTest?>(null) }
    var fromRule by remember { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<PolicyPath?>(null) }
    val empty = m.tests.isEmpty() && m.sshTests.isEmpty()
    val selected: Any? = layout.selected?.let { p -> m.tests.firstOrNull { it.origin.path == p } ?: m.sshTests.firstOrNull { it.origin.path == p } }
    val twoPane = layout.twoPane && (!empty || pending != null)
    val shown: Any? = when (val p = pending) {
        is PendingTest.Access -> p.test
        is PendingTest.Ssh -> p.test
        null -> selected ?: (m.tests.firstOrNull() ?: m.sshTests.firstOrNull())?.takeIf { twoPane }
    }
    val listState = rememberLazyListState()
    val rows = remember(m) {
        pageRows(m.tests, Section.TESTS) { it.origin } + PageRow.Divider(Section.SSH_TESTS.key, Section.SSH_TESTS) + pageRows(m.sshTests, Section.SSH_TESTS) { it.origin }
    }
    ScrollTo(listState, rows, layout.selected ?: env.focus)

    fun select(p: PolicyPath?) {
        pending = null
        layout.onSelect(p)
    }

    fun headers(section: Section) = if (section == Section.TESTS) m.tests.map { it.origin.header != null } else m.sshTests.map { it.origin.header != null }

    fun delete(path: PolicyPath) {
        if (actions.edit { PolicyEdits.removeRule(it, path) }) {
            select(null)
            actions.notify(ctx.getString(R.string.admin_pv_test_deleted), undoable = true)
        }
    }

    fun act(origin: Origin, section: Section, a: RuleAction) {
        val path = origin.path
        val i = (path.last as? PathStep.Index)?.index ?: return
        when (a) {
            RuleAction.DISCARD -> select(null)
            RuleAction.DELETE -> if (confirmed) delete(path) else asking = path
            RuleAction.DUPLICATE -> if (actions.edit { RuleForms.duplicate(it, path) }) {
                layout.onSelect(PolicyPath.of(section.key, i + 1))
                actions.notify(ctx.getString(R.string.admin_pv_test_duplicated))
            }
            RuleAction.MOVE_UP, RuleAction.MOVE_DOWN -> {
                val h = headers(section)
                val to = (if (a == RuleAction.MOVE_UP) RuleForms.moveUp(h, i) else RuleForms.moveDown(h, i)) ?: return
                if (actions.edit { PolicyEdits.moveRule(it, section, i, to.first, to.second) }) layout.onSelect(PolicyPath.of(section.key, to.first))
            }
            else -> Unit
        }
    }

    fun changeAccess(old: AclTest, new: AclTest) {
        if (pending is PendingTest.Access) {
            val at = m.tests.size
            if (RuleForms.complete(new) && actions.edit { PolicyEdits.addRule(it, Section.TESTS, RuleForms.fields(new)) }) select(PolicyPath.of(Section.TESTS.key, at))
            else pending = PendingTest.Access(new)
            return
        }
        val diff = RuleForms.changed(RuleForms.fields(old), RuleForms.fields(new))
        if (diff.isNotEmpty()) actions.edit { PolicyEdits.updateRule(it, old.origin.path, diff) }
    }

    fun changeSsh(old: SshTest, new: SshTest) {
        if (pending is PendingTest.Ssh) {
            val at = m.sshTests.size
            if (RuleForms.complete(new) && actions.edit { PolicyEdits.addRule(it, Section.SSH_TESTS, RuleForms.fields(new)) }) select(PolicyPath.of(Section.SSH_TESTS.key, at))
            else pending = PendingTest.Ssh(new)
            return
        }
        val diff = RuleForms.changed(RuleForms.fields(old), RuleForms.fields(new))
        if (diff.isNotEmpty()) actions.edit { PolicyEdits.updateRule(it, old.origin.path, diff) }
    }

    fun newAccess() {
        layout.onSelect(null)
        pending = PendingTest.Access(RuleForms.emptyTest(m))
    }

    fun newSsh() {
        layout.onSelect(null)
        pending = PendingTest.Ssh(RuleForms.emptySshTest(m))
    }

    RulePage(
        layout = layout.copy(twoPane = twoPane),
        sheetOpen = shown != null,
        list = {
            RuleList(layout.copy(twoPane = twoPane), listState) {
                if (empty) {
                    item(key = "empty") { TestsEmpty(env, onAdd = ::newAccess, onFromRule = { fromRule = true }, onAddSsh = ::newSsh) }
                    return@RuleList
                }
                item(key = "top") {
                    PageHeader(
                        title = testsTitle(ctx, m),
                        help = ctx.getString(R.string.admin_pv_tests_help),
                        env = env,
                        addLabel = ctx.getString(R.string.admin_pv_test_add),
                        onAdd = ::newAccess,
                        notes = sectionNotes(m, Section.TESTS),
                        extra = {
                            OutlinedButton(onClick = { fromRule = true }, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text(ctx.getString(R.string.admin_pv_test_from_rule))
                            }
                        },
                    )
                }
                if (m.tests.isEmpty()) item(key = "no-tests") { NoneYet(ctx.getString(R.string.admin_pv_tests_none)) }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is PageRow.Heading -> CommentHeading(row.text)
                        is PageRow.Divider -> Column {
                            ListDivider(ctx.getString(R.string.admin_pv_divider_sshtests), action = if (env.editable) ({
                                IconButton(onClick = ::newSsh) { Icon(Icons.Default.Add, ctx.getString(R.string.admin_pv_sshtest_add)) }
                            }) else null)
                            sectionNotes(m, Section.SSH_TESTS).forEach { HelpText(noteText(it)) }
                            if (m.sshTests.isEmpty()) NoneYet(ctx.getString(R.string.admin_pv_sshtests_none))
                        }
                        is PageRow.Item<*> -> when (val t = row.item) {
                            is AclTest -> TestCard(t, env, actions, selected = twoPane && pending == null && shownPath(shown) == t.origin.path) { select(t.origin.path) }
                            is SshTest -> SshTestCard(t, env, actions, selected = twoPane && pending == null && shownPath(shown) == t.origin.path) { select(t.origin.path) }
                        }
                    }
                }
            }
        },
        editor = {
            val isNew = pending != null
            when (val t = shown) {
                is AclTest -> EditorFrame(onDismiss = { select(null) }) {
                    key(t.origin.path, isNew) {
                        TestEditorContent(
                            t, env, actions, isNew, movesOf(headers(Section.TESTS), t.index),
                            onChange = { changeAccess(t, it) }, onAction = { act(t.origin, Section.TESTS, it) },
                        )
                    }
                }
                is SshTest -> EditorFrame(onDismiss = { select(null) }) {
                    key(t.origin.path, isNew) {
                        SshTestEditorContent(
                            t, env, actions, isNew, movesOf(headers(Section.SSH_TESTS), t.index),
                            onChange = { changeSsh(t, it) }, onAction = { act(t.origin, Section.SSH_TESTS, it) },
                        )
                    }
                }
                else -> PaneEmptyState(Icons.AutoMirrored.Filled.FactCheck, ctx.getString(R.string.admin_pv_pane_empty))
            }
        },
    )

    if (fromRule) {
        val rules = accessRules(m).mapNotNull { r ->
            val f = when (r) {
                is AccessRule.Acl -> PolicyEdits.testFrom(r.rule)
                is AccessRule.Grant -> PolicyEdits.testFrom(r.rule)
            } ?: return@mapNotNull null
            r to f
        }
        PickerSheet(
            title = ctx.getString(R.string.admin_pv_test_from_rule_title),
            options = rules.mapIndexed { i, (r, _) -> PickerOption(i, ruleSummary(ctx, r), icon = Icons.Default.Policy, supporting = ruleWhere(ctx, r)) },
            onPick = { i ->
                val f = rules[i].second
                val at = m.tests.size
                if (actions.edit { PolicyEdits.addRule(it, Section.TESTS, f) }) {
                    select(PolicyPath.of(Section.TESTS.key, at))
                    actions.notify(ctx.getString(R.string.admin_pv_test_added))
                }
            },
            onDismiss = { fromRule = false },
        )
    }

    asking?.let { path ->
        // Resolved here: the dialog's window would answer in the system language.
        val title = ctx.getString(R.string.admin_pv_test_delete_title)
        val text = ctx.getString(R.string.admin_pv_test_delete_text)
        val yes = ctx.getString(R.string.admin_pv_action_delete)
        val no = ctx.getString(android.R.string.cancel)
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { confirmed = true; asking = null; delete(path) }) { Text(yes, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(no) } },
        )
    }
}

private val AclTest.index: Int get() = (origin.path.last as? PathStep.Index)?.index ?: 0
private val SshTest.index: Int get() = (origin.path.last as? PathStep.Index)?.index ?: 0

private fun shownPath(shown: Any?): PolicyPath? = when (shown) {
    is AclTest -> shown.origin.path
    is SshTest -> shown.origin.path
    else -> null
}

/** "2 tests · 1 SSH test". */
private fun testsTitle(ctx: Context, m: PolicyModel): String = listOfNotNull(
    ctx.resources.getQuantityString(R.plurals.admin_pv_tests_count, m.tests.size, m.tests.size),
    m.sshTests.size.takeIf { it > 0 }?.let { ctx.resources.getQuantityString(R.plurals.admin_pv_sshtests_count, it, it) },
).joinToString(" · ")

/** A rule in one line for the "From a rule" picker: "group:guest-users → Internet". */
private fun ruleSummary(ctx: Context, r: AccessRule): String {
    val to = when (r) {
        is AccessRule.Acl -> r.rule.destinations.map { it.host }
        is AccessRule.Grant -> r.rule.dst
    }.distinct()
    fun words(list: List<String>) = list.take(2).joinToString(", ") { VisualText.label(ctx, it) } + if (list.size > 2) " +${list.size - 2}" else ""
    return "${words(r.src)} → ${words(to)}"
}

/** "ACL · line 57". */
private fun ruleWhere(ctx: Context, r: AccessRule): String =
    ctx.getString(if (r is AccessRule.Grant) R.string.admin_pv_kind_grant else R.string.admin_pv_kind_acl) + " · " + ctx.getString(R.string.admin_pv_line, r.origin.line)

@Composable
private fun NoneYet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A Tests page with no tests: why they matter, and the ways to add one. */
@Composable
private fun TestsEmpty(env: VisualEnv, onAdd: () -> Unit, onFromRule: () -> Unit, onAddSsh: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptySection(
            icon = Icons.AutoMirrored.Filled.FactCheck,
            text = ctx.getString(R.string.admin_pv_tests_empty),
            actionLabel = ctx.getString(R.string.admin_pv_test_add).takeIf { env.editable },
            onAction = onAdd.takeIf { env.editable },
        )
        if (env.editable) {
            OutlinedButton(onClick = onFromRule, shape = MaterialTheme.shapes.medium) { Text(ctx.getString(R.string.admin_pv_test_from_rule)) }
            TextButton(onClick = onAddSsh) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(ctx.getString(R.string.admin_pv_sshtest_add))
            }
        }
    }
}
