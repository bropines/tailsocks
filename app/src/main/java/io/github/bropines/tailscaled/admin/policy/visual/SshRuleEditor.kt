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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The editor of one SSH rule: who may SSH to which devices as which login names, accepted
 * outright or after a fresh sign-in (check) every so often; the environment a session may
 * bring and the device posture under "More options"; a reminder when the access rules do not
 * open port 22 for the same people, with the rule that would.
 */

/** A login name's icon: a badge, so "root" does not read as an unknown selector. */
internal fun loginIcon(value: String): ImageVector? = if (value.startsWith("autogroup:")) null else Icons.Default.Badge

/** Login names the picker suggests, as options of their own heading. */
internal fun loginOptions(m: PolicyModel?): List<SelectorOption> =
    m?.let { RuleForms.loginSuggestions(it) }.orEmpty().map { SelectorOption(it, SelectorKind.LOCALPART) }

/** "Accept", "Check every 12 h", "Check every time". */
internal fun sshMode(ctx: Context, r: SshRule): String = when (r.action) {
    "accept" -> ctx.getString(R.string.admin_pv_ssh_accept)
    "check" -> when (val p = r.checkPeriod ?: RuleForms.DEFAULT_PERIOD) {
        "always" -> ctx.getString(R.string.admin_pv_ssh_check_always)
        else -> ctx.getString(R.string.admin_pv_ssh_check_every, periodWords(ctx, p))
    }
    else -> r.action.ifEmpty { "—" }
}

/** "12h" → "12 h" ("12 ч"); anything else as written. */
internal fun periodWords(ctx: Context, p: String): String {
    val h = Regex("""^(\d+)h$""").matchEntire(p)?.groupValues?.get(1) ?: return p
    return ctx.getString(R.string.admin_pv_hours, h)
}

private val PERIODS = listOf("1h", "12h", "24h", "always")

/** One SSH rule's editor; [isNew] is a form, written once it says who, where and as whom. */
@Composable
fun SshRuleEditorContent(
    rule: SshRule,
    env: VisualEnv,
    actions: VisualActions,
    isNew: Boolean,
    onChange: (SshRule) -> Unit,
    onAction: (RuleAction) -> Unit,
    onAddAccess: () -> Unit,
) {
    val ctx = LocalContext.current
    val origin = rule.origin.takeIf { !isNew }
    val fe = fieldEnv(env, origin)
    val policy = rememberPolicy(env)
    val m = env.model
    EditorColumn {
        EditorHeader(ctx.getString(if (isNew) R.string.admin_pv_ssh_new else R.string.admin_pv_ssh_title), origin, env, actions) {
            KindTag(sshMode(ctx, rule), strong = rule.action == "check")
        }
        if (isNew) NewFormNote(ctx.getString(R.string.admin_pv_ssh_new_note))
        SelectorField(ctx.getString(R.string.admin_pv_f_who), rule.src, SelectorSlot.SSH_SRC, fe, onChange = { onChange(rule.copy(src = it)) })
        SelectorField(
            ctx.getString(R.string.admin_pv_f_ssh_to),
            rule.dst,
            SelectorSlot.SSH_DST,
            fe,
            onChange = { onChange(rule.copy(dst = it)) },
            help = ctx.getString(R.string.admin_pv_f_ssh_to_help),
        )
        SelectorField(
            ctx.getString(R.string.admin_pv_f_ssh_as),
            rule.users,
            SelectorSlot.SSH_USER,
            fe,
            onChange = { onChange(rule.copy(users = it)) },
            extraOptions = loginOptions(m),
            help = ctx.getString(R.string.admin_pv_f_ssh_as_help),
            iconFor = ::loginIcon,
        )
        ModeField(rule, fe, onChange)

        if (!isNew && m != null) {
            val needs = remember(policy, rule, env.devices) { RuleForms.sshNeedsAccess(rule, m, policy, env.view) }
            if (needs) Port22Reminder(fe.editable, onAddAccess)
        }

        MoreSection(
            open = !isNew && (rule.acceptEnv.isNotEmpty() || rule.srcPosture.isNotEmpty()),
            summary = listOfNotNull(
                rule.acceptEnv.takeIf { it.isNotEmpty() }?.joinToString(", "),
                rule.srcPosture.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · ").ifEmpty { ctx.getString(R.string.admin_pv_ssh_more_summary) },
        ) {
            TextListField(
                ctx.getString(R.string.admin_pv_f_env),
                rule.acceptEnv,
                fe,
                onChange = { onChange(rule.copy(acceptEnv = it)) },
                placeholder = "GIT_*",
                help = ctx.getString(R.string.admin_pv_f_env_help),
                check = { v -> if (Regex("""^[A-Za-z_*?][A-Za-z0-9_*?]*$""").matches(v)) null else ctx.getString(R.string.admin_pv_f_env_bad) },
            )
            PostureField(rule.srcPosture, fe) { onChange(rule.copy(srcPosture = it)) }
        }

        if (!isNew) {
            NoteField(rule.origin, env, actions)
            val from = remember(policy, rule.src, env.devices) { RuleCoverage.of(rule.src, policy, env.view) }
            val to = remember(policy, rule.dst, env.devices) { RuleCoverage.of(rule.dst, policy, env.view) }
            CoverageBlock(ctx.getString(R.string.admin_pv_cov_title), from, to, env)
        }

        if (env.editable) {
            EditorActions {
                if (isNew) {
                    EditorAction(Icons.Default.Close, ctx.getString(R.string.admin_pv_action_discard), { onAction(RuleAction.DISCARD) })
                } else {
                    EditorAction(Icons.Default.Delete, ctx.getString(R.string.admin_pv_action_delete), { onAction(RuleAction.DELETE) }, danger = true)
                    EditorAction(Icons.Default.ContentCopy, ctx.getString(R.string.admin_pv_action_duplicate), { onAction(RuleAction.DUPLICATE) })
                }
            }
        }
    }
}

/** Accept or check, and how often a check asks again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeField(rule: SshRule, env: VisualEnv, onChange: (SshRule) -> Unit) {
    val ctx = LocalContext.current
    val check = rule.action == "check"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(ctx.getString(R.string.admin_pv_f_ssh_mode))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip(selected = rule.action == "accept", onClick = { onChange(rule.copy(action = "accept", checkPeriod = null)) }, label = ctx.getString(R.string.admin_pv_ssh_accept), enabled = env.editable)
            SelectChip(selected = check, onClick = { onChange(rule.copy(action = "check")) }, label = ctx.getString(R.string.admin_pv_ssh_check), enabled = env.editable)
        }
        HelpText(ctx.getString(if (check) R.string.admin_pv_ssh_check_help else R.string.admin_pv_ssh_accept_help))
    }
    if (!check) return
    val period = rule.checkPeriod
    val preset = (period ?: RuleForms.DEFAULT_PERIOD).takeIf { it in PERIODS }
    var other by remember { mutableStateOf(preset == null) }
    var typed by remember(period) { mutableStateOf(if (preset == null) period.orEmpty() else "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(ctx.getString(R.string.admin_pv_f_ssh_period))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PERIODS.forEach { p ->
                SelectChip(
                    selected = !other && preset == p,
                    enabled = env.editable,
                    // Tailscale's default is written out only when it was: picking 12 h on a rule that names none changes nothing.
                    onClick = { other = false; onChange(rule.copy(checkPeriod = if (p == RuleForms.DEFAULT_PERIOD && period == null) null else p)) },
                    label = if (p == "always") ctx.getString(R.string.admin_pv_ssh_period_always) else periodWords(ctx, p),
                )
            }
            SelectChip(selected = other, onClick = { other = true }, label = ctx.getString(R.string.admin_pv_chip_other), enabled = env.editable)
        }
        if (other) {
            val t = typed.trim()
            val ok = RuleForms.periodOk(t)
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.replace(" ", "").lowercase() },
                enabled = env.editable,
                singleLine = true,
                label = { Text(ctx.getString(R.string.admin_pv_f_ssh_period)) },
                placeholder = { Text("30m") },
                isError = t.isNotEmpty() && !ok,
                supportingText = { Text(ctx.getString(if (t.isNotEmpty() && !ok) R.string.admin_pv_ssh_period_bad else R.string.admin_pv_ssh_period_hint)) },
                trailingIcon = { IconButton(onClick = { onChange(rule.copy(checkPeriod = t)) }, enabled = ok && t != period) { Icon(Icons.Default.Check, ctx.getString(R.string.admin_pv_note_save)) } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onChange(rule.copy(checkPeriod = t)) }),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Tailscale SSH needs the connection too: the access rules must open port 22 for the same people. */
@Composable
private fun Port22Reminder(editable: Boolean, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_pv_ssh_port22), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                    HelpText(ctx.getString(R.string.admin_pv_ssh_port22_help))
                }
            }
            if (editable) {
                FilledTonalButton(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Lan, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ctx.getString(R.string.admin_pv_ssh_port22_add))
                }
            }
        }
    }
}
