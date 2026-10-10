package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/*
 * Tests and SSH tests: what must stay reachable and what must stay closed, which the server
 * checks on every save. The editors take one source each — a test runs from one place — and
 * destinations with one port (access tests) or login names (SSH tests).
 */

/** A login name in an SSH test: a name, not an autogroup — the test tries that one login. */
private fun loginOnly(v: String): SelectorProblem? = if (v.startsWith("autogroup:") || v == "*" || v.isBlank()) SelectorProblem.NOT_ALLOWED_HERE else null

/** One access test's editor; [isNew] is a form, written once it has a source and a destination. */
@Composable
fun TestEditorContent(
    test: AclTest,
    env: VisualEnv,
    actions: VisualActions,
    isNew: Boolean,
    onChange: (AclTest) -> Unit,
    onAction: (RuleAction) -> Unit,
) {
    val ctx = LocalContext.current
    val origin = test.origin.takeIf { !isNew }
    val fe = fieldEnv(env, origin)
    EditorColumn {
        EditorHeader(ctx.getString(if (isNew) R.string.admin_pv_test_new else R.string.admin_pv_test_title), origin, env, actions)
        if (isNew) NewFormNote(ctx.getString(R.string.admin_pv_test_new_note))
        SelectorField(
            ctx.getString(R.string.admin_pv_f_test_src),
            listOf(test.src).filter { it.isNotBlank() },
            SelectorSlot.TEST_SRC,
            fe,
            onChange = { onChange(test.copy(src = it.lastOrNull().orEmpty())) },
            single = true,
            help = ctx.getString(R.string.admin_pv_f_test_src_help),
        )
        DestinationsField(
            ctx.getString(R.string.admin_pv_f_test_accept),
            test.accept,
            SelectorSlot.TEST_DST,
            fe,
            onChange = { onChange(test.copy(accept = it)) },
            single = true,
            help = ctx.getString(R.string.admin_pv_f_test_accept_help),
        )
        DestinationsField(
            ctx.getString(R.string.admin_pv_f_test_deny),
            test.deny,
            SelectorSlot.TEST_DST,
            fe,
            onChange = { onChange(test.copy(deny = it)) },
            single = true,
            help = ctx.getString(R.string.admin_pv_f_test_deny_help),
        )
        MoreSection(open = !isNew && test.proto != null, summary = test.proto?.let { PortWords.proto(it) } ?: ctx.getString(R.string.admin_pv_test_more_summary)) {
            ProtocolField(test.proto, fe) { onChange(test.copy(proto = it)) }
        }
        test.srcPostureAttrs?.let { attrs ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldTitle(ctx.getString(R.string.admin_pv_f_test_posture))
                Text(attrs, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                HelpText(ctx.getString(R.string.admin_pv_f_test_posture_help))
            }
        }
        if (!isNew) NoteField(test.origin, env, actions)
        TestActions(env, isNew, onAction)
    }
}

/** One SSH test's editor. */
@Composable
fun SshTestEditorContent(
    test: SshTest,
    env: VisualEnv,
    actions: VisualActions,
    isNew: Boolean,
    onChange: (SshTest) -> Unit,
    onAction: (RuleAction) -> Unit,
) {
    val ctx = LocalContext.current
    val origin = test.origin.takeIf { !isNew }
    val fe = fieldEnv(env, origin)
    val logins = loginOptions(env.model)
    EditorColumn {
        EditorHeader(ctx.getString(if (isNew) R.string.admin_pv_sshtest_new else R.string.admin_pv_sshtest_title), origin, env, actions)
        if (isNew) NewFormNote(ctx.getString(R.string.admin_pv_sshtest_new_note))
        SelectorField(
            ctx.getString(R.string.admin_pv_f_test_src),
            listOf(test.src).filter { it.isNotBlank() },
            SelectorSlot.SSH_TEST_SRC,
            fe,
            onChange = { onChange(test.copy(src = it.lastOrNull().orEmpty())) },
            single = true,
            help = ctx.getString(R.string.admin_pv_f_sshtest_src_help),
        )
        SelectorField(ctx.getString(R.string.admin_pv_f_ssh_to), test.dst, SelectorSlot.SSH_TEST_DST, fe, onChange = { onChange(test.copy(dst = it)) })
        SelectorField(
            ctx.getString(R.string.admin_pv_f_sshtest_accept), test.accept, SelectorSlot.SSH_USER, fe,
            onChange = { onChange(test.copy(accept = it)) }, check = ::loginOnly, extraOptions = logins, iconFor = ::loginIcon,
            help = ctx.getString(R.string.admin_pv_f_sshtest_accept_help),
        )
        SelectorField(
            ctx.getString(R.string.admin_pv_f_sshtest_check), test.check, SelectorSlot.SSH_USER, fe,
            onChange = { onChange(test.copy(check = it)) }, check = ::loginOnly, extraOptions = logins, iconFor = ::loginIcon,
            help = ctx.getString(R.string.admin_pv_f_sshtest_check_help),
        )
        SelectorField(
            ctx.getString(R.string.admin_pv_f_sshtest_deny), test.deny, SelectorSlot.SSH_USER, fe,
            onChange = { onChange(test.copy(deny = it)) }, check = ::loginOnly, extraOptions = logins, iconFor = ::loginIcon,
            help = ctx.getString(R.string.admin_pv_f_sshtest_deny_help),
        )
        if (!isNew) NoteField(test.origin, env, actions)
        TestActions(env, isNew, onAction)
    }
}

@Composable
private fun TestActions(env: VisualEnv, isNew: Boolean, onAction: (RuleAction) -> Unit) {
    if (!env.editable) return
    val ctx = LocalContext.current
    EditorActions {
        if (isNew) {
            EditorAction(Icons.Default.Close, ctx.getString(R.string.admin_pv_action_discard), { onAction(RuleAction.DISCARD) })
        } else {
            EditorAction(Icons.Default.Delete, ctx.getString(R.string.admin_pv_action_delete), { onAction(RuleAction.DELETE) }, danger = true)
            EditorAction(Icons.Default.ContentCopy, ctx.getString(R.string.admin_pv_action_duplicate), { onAction(RuleAction.DUPLICATE) })
        }
    }
}

/** A test card's labels carry an icon (✓, ⊘, a clock) and need the room. */
private val TEST_LABEL = 108.dp

/** An access test as a card: from where, what it must reach (✓) and what must stay closed (⊘); [moves] at its end. */
@Composable
fun TestCard(
    test: AclTest,
    env: VisualEnv,
    actions: VisualActions,
    selected: Boolean = false,
    moves: CardMoves? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val m = env.model ?: return
    val hosts = remember(m) { m.hosts.map { it.name }.toSet() }
    ElementCard(test.origin, env, actions, modifier, selected = selected, onClick = onClick, trailing = moves?.let { { CardMoveButtons(it) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow(ctx.getString(R.string.admin_pv_c_from), labelWidth = TEST_LABEL) {
                if (test.src.isBlank()) NothingYet()
                else SelectorLabels(listOf(test.src), hosts = hosts, problem = { SelectorRules.check(it, SelectorSlot.TEST_SRC, m, env.headscale) })
            }
            if (test.accept.isNotEmpty()) {
                CardRow(ctx.getString(R.string.admin_pv_c_test_accept), labelWidth = TEST_LABEL, icon = Icons.Default.CheckCircle, iconTint = MaterialTheme.colorScheme.primary) {
                    DestinationLabels(test.accept.map(Selectors::hostPorts), env, showPorts = true, max = CARD_MAX)
                }
            }
            if (test.deny.isNotEmpty()) {
                CardRow(ctx.getString(R.string.admin_pv_c_test_deny), labelWidth = TEST_LABEL, icon = Icons.Default.Block, iconTint = MaterialTheme.colorScheme.error) {
                    DestinationLabels(test.deny.map(Selectors::hostPorts), env, showPorts = true, max = CARD_MAX)
                }
            }
            test.proto?.let { p -> CardRow(ctx.getString(R.string.admin_pv_c_ports), labelWidth = TEST_LABEL) { CardText(ctx.getString(R.string.admin_pv_c_proto_only, PortWords.proto(p))) } }
        }
    }
}

/** An SSH test as a card: from where to which devices, the logins allowed, checked and refused; [moves] at its end. */
@Composable
fun SshTestCard(
    test: SshTest,
    env: VisualEnv,
    actions: VisualActions,
    selected: Boolean = false,
    moves: CardMoves? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val m = env.model ?: return
    val hosts = remember(m) { m.hosts.map { it.name }.toSet() }
    ElementCard(test.origin, env, actions, modifier, selected = selected, onClick = onClick, trailing = moves?.let { { CardMoveButtons(it) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow(ctx.getString(R.string.admin_pv_c_from), labelWidth = TEST_LABEL) { if (test.src.isBlank()) NothingYet() else SelectorLabels(listOf(test.src), hosts = hosts) }
            CardRow(ctx.getString(R.string.admin_pv_c_ssh_to), labelWidth = TEST_LABEL) { if (test.dst.isEmpty()) NothingYet() else SelectorLabels(test.dst, hosts = hosts, max = CARD_MAX) }
            if (test.accept.isNotEmpty()) {
                CardRow(ctx.getString(R.string.admin_pv_c_sshtest_accept), labelWidth = TEST_LABEL, icon = Icons.Default.CheckCircle, iconTint = MaterialTheme.colorScheme.primary) {
                    SelectorLabels(test.accept, max = CARD_MAX, iconFor = ::loginIcon)
                }
            }
            if (test.check.isNotEmpty()) {
                CardRow(ctx.getString(R.string.admin_pv_c_sshtest_check), labelWidth = TEST_LABEL, icon = Icons.Default.Schedule, iconTint = MaterialTheme.colorScheme.tertiary) {
                    SelectorLabels(test.check, max = CARD_MAX, iconFor = ::loginIcon)
                }
            }
            if (test.deny.isNotEmpty()) {
                CardRow(ctx.getString(R.string.admin_pv_c_sshtest_deny), labelWidth = TEST_LABEL, icon = Icons.Default.Block, iconTint = MaterialTheme.colorScheme.error) {
                    SelectorLabels(test.deny, max = CARD_MAX, iconFor = ::loginIcon)
                }
            }
        }
    }
}
