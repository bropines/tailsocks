package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.codeStyle
import io.github.bropines.tailscaled.admin.policy.highlightHuJson
import io.github.bropines.tailscaled.admin.policy.rememberPolicyColors
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The Access page's "TailSocks remote management" template: a card under the rules (in the
 * templates of an empty page), and the form it opens — who manages, whose devices, what Create
 * writes or which rules allow it already. The policy side is RemoteRules.
 */

/** The template's form: what is chosen on each side so far. */
data class RemoteForm(val managers: List<String> = emptyList(), val managed: List<String> = emptyList())

/** The template as a card: what it does, a tap opens its form. */
@Composable
internal fun RemoteTemplateCard(onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SettingsRemote, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ctx.getString(R.string.admin_pv_templates), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(ctx.getString(R.string.admin_pvrm_tpl), style = MaterialTheme.typography.titleSmall)
                HelpText(ctx.getString(R.string.admin_pvrm_tpl_help), inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The form: the two pickers, then either the rules that open the path already — shown as their
 * cards, to open — or what Create writes, quoted as the file will have it. [onCreated] gets the
 * new rule's place (null when only a test was added); [onShow] opens a rule that allows it already.
 */
@Composable
internal fun RemoteManagementForm(
    form: RemoteForm,
    env: VisualEnv,
    actions: VisualActions,
    onChange: (RemoteForm) -> Unit,
    onCreated: (PolicyPath?) -> Unit,
    onShow: (PolicyPath) -> Unit,
) {
    val ctx = LocalContext.current
    val m = env.model ?: return
    val policy = rememberPolicy(env)
    val plan = remember(m, form, env.devices, env.users, env.headscale) {
        RemoteRules.plan(m, env.headscale, form.managers, form.managed, env.view, policy)
    }
    val opened = remember(m, form) { RemoteRules.openedBy(m, form.managers, form.managed) }
    val ownerless = RemoteRules.selfWithoutOwner(form.managers, form.managed)
    val complete = form.managers.isNotEmpty() && form.managed.isNotEmpty() && ownerless.isEmpty()
    val rules = remember(m) { accessRules(m) }

    EditorColumn {
        EditorHeader(title = ctx.getString(R.string.admin_pvrm_tpl), origin = null, env = env, actions = actions) {
            KindTag(ctx.getString(if (plan.section == Section.GRANTS) R.string.admin_pv_kind_grant else R.string.admin_pv_kind_acl))
        }
        HelpText(ctx.getString(R.string.admin_pvrm_note))
        SelectorField(
            ctx.getString(R.string.admin_pvrm_from), form.managers, SelectorSlot.REMOTE_FROM, env,
            onChange = { onChange(form.copy(managers = it)) },
            help = ctx.getString(R.string.admin_pvrm_from_help),
        )
        SelectorField(
            ctx.getString(R.string.admin_pvrm_to), form.managed, SelectorSlot.REMOTE_TO, env,
            onChange = { onChange(form.copy(managed = it)) },
            help = ctx.getString(R.string.admin_pvrm_to_help),
        )
        if (ownerless.isNotEmpty()) {
            WarningLine(ctx.getString(R.string.admin_pvrm_self_tags), ctx.getString(R.string.admin_pvrm_self_tags_help, ownerless.joinToString(", ")))
        }
        when {
            !complete -> Unit
            opened != null -> OpenedBlock(opened.mapNotNull { p -> rules.firstOrNull { it.origin.path == p } }, env, actions, onShow)
            else -> WritesBlock(RemoteRules.write("{\n}\n", plan, ctx.getString(R.string.admin_pvrm_rule_note)), plan.test != null)
        }
        // On a path that is open already, only the test is left to add — when there is one to add.
        val testOnly = opened != null && complete
        if (!testOnly || plan.test != null) {
            CreateButton(
                ctx.getString(
                    when {
                        testOnly -> R.string.admin_pvrm_create_test
                        complete && plan.test == null -> R.string.admin_pvrm_create_rule
                        else -> R.string.admin_pvrm_create
                    }
                ),
                enabled = complete,
                hint = ctx.getString(R.string.admin_pvrm_create_hint).takeIf { ownerless.isEmpty() },
            ) {
                val at = if (plan.section == Section.GRANTS) m.grants.size else m.acls.size
                val note = ctx.getString(R.string.admin_pvrm_rule_note)
                if (actions.edit { RemoteRules.write(it, plan, note, rule = !testOnly) }) {
                    actions.notify(ctx.getString(if (testOnly) R.string.admin_pv_test_added else if (plan.test != null) R.string.admin_pvrm_created else R.string.admin_pvrm_created_rule))
                    onCreated(if (testOnly) null else PolicyPath.of(plan.section.key, at))
                }
            }
        }
    }
}

/** The rules that open the path already, as their cards: a tap opens one. */
@Composable
private fun OpenedBlock(rules: List<AccessRule>, env: VisualEnv, actions: VisualActions, onShow: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.CheckCircle, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ctx.getString(R.string.admin_pvrm_open), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                HelpText(ctx.getString(R.string.admin_pvrm_open_help))
            }
        }
        rules.forEach { r -> AccessCard(r, env, actions) { onShow(r.origin.path) } }
    }
}

/** What Create writes, as the policy file will hold it: the rule, its comment, the test. */
@Composable
private fun WritesBlock(text: String, withTest: Boolean) {
    val ctx = LocalContext.current
    val colors = rememberPolicyColors()
    // The quoted file without its braces: only what is added; a tab is two spaces on a narrow sheet.
    val body = remember(text) { text.trim().removePrefix("{").removeSuffix("}").trimIndent().trim().replace("\t", "  ") }
    val code = remember(body, colors) { highlightHuJson(body, colors) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ctx.getString(R.string.admin_pvrm_writes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(code, style = codeStyle().copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.padding(12.dp))
        }
        if (!withTest) HelpText(ctx.getString(R.string.admin_pvrm_no_test))
    }
}
