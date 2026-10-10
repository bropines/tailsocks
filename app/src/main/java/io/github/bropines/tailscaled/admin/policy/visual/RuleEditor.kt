package io.github.bropines.tailscaled.admin.policy.visual

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
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The editor of one access rule — an ACL or a grant — in plain words: who, can reach what, on
 * which ports; the rarer fields under "More options"; the note; which devices it connects; and
 * the actions on it in a row of their own. Every change is written to the draft at once (undo
 * takes it back); a new rule is a form until it says enough to be written.
 */

/** What an editor's action row asks of its page. */
enum class RuleAction { DELETE, DUPLICATE, MOVE_UP, MOVE_DOWN, ADD_TEST, CONVERT, DISCARD }

/** What a rule's actions may do here: decided by the page, which knows the rule's neighbours. */
data class RuleMoves(val up: Boolean = false, val down: Boolean = false, val test: Boolean = false, val convert: Boolean = false)

/** The draft parsed for coverage, once per text. */
@Composable
internal fun rememberPolicy(env: VisualEnv): HuObject? = remember(env.draft.text) { HuJson.parseOrNull(env.draft.text) }

/** [env] for the fields of an element that may not be edited visually: everything shows, nothing changes. */
internal fun fieldEnv(env: VisualEnv, origin: Origin?): VisualEnv = if (origin == null || origin.editable) env else env.copy(canWrite = false)

/**
 * One access rule's editor. [isNew] is the form of a rule not yet in the file ([NewFormNote]
 * says when it will be); [onChange] gets the rule as it should now read.
 */
@Composable
fun AccessRuleEditorContent(
    rule: AccessRule,
    env: VisualEnv,
    actions: VisualActions,
    isNew: Boolean,
    moves: RuleMoves,
    onChange: (AccessRule) -> Unit,
    onAction: (RuleAction) -> Unit,
) {
    val ctx = LocalContext.current
    val origin = rule.origin.takeIf { !isNew }
    val fe = fieldEnv(env, origin)
    val policy = rememberPolicy(env)
    EditorColumn {
        EditorHeader(
            title = ctx.getString(if (isNew) R.string.admin_pv_rule_new else R.string.admin_pv_rule_title),
            origin = origin,
            env = env,
            actions = actions,
        ) { KindTag(ctx.getString(if (rule is AccessRule.Grant) R.string.admin_pv_kind_grant else R.string.admin_pv_kind_acl)) }
        if (isNew) NewFormNote(ctx.getString(if (rule is AccessRule.Grant) R.string.admin_pv_rule_new_note_grant else R.string.admin_pv_rule_new_note))

        when (rule) {
            is AccessRule.Acl -> AclFields(rule.rule, fe, isNew, viaOk = moves.convert || (isNew && env.model?.let { RuleForms.canConvert(it, env.headscale) } == true), onChange = { onChange(AccessRule.Acl(it)) }) { via ->
                // Only grants go through devices: the rule becomes one, at the end of the grants.
                val m = env.model ?: return@AclFields
                RuleForms.grantWithVia(rule.rule, via, RuleForms.newOrigin(m, Section.GRANTS))?.let { onChange(AccessRule.Grant(it)) }
            }
            is AccessRule.Grant -> GrantFields(rule.rule, fe, actions, isNew) { onChange(AccessRule.Grant(it)) }
        }

        if (!isNew) {
            NoteField(rule.origin, env, actions)
            val from = remember(policy, rule.src, env.devices) { RuleCoverage.of(rule.src, policy, env.view) }
            val toHosts = when (rule) {
                is AccessRule.Acl -> rule.rule.destinations.map { it.host }
                is AccessRule.Grant -> rule.rule.dst
            }
            val to = remember(policy, toHosts, env.devices) { RuleCoverage.of(toHosts, policy, env.view) }
            val user = env.model?.let { RuleForms.previewUser(rule.src, it) }
            CoverageBlock(ctx.getString(R.string.admin_pv_cov_title), from, to, env) {
                if (!env.headscale && user != null) ServerCheck(user, actions)
            }
        }

        if (env.editable) {
            EditorActions {
                if (isNew) {
                    EditorAction(Icons.Default.Close, ctx.getString(R.string.admin_pv_action_discard), { onAction(RuleAction.DISCARD) })
                } else {
                    EditorAction(Icons.Default.Delete, ctx.getString(R.string.admin_pv_action_delete), { onAction(RuleAction.DELETE) }, danger = true)
                    EditorAction(Icons.Default.ContentCopy, ctx.getString(R.string.admin_pv_action_duplicate), { onAction(RuleAction.DUPLICATE) })
                    EditorAction(Icons.Default.ArrowUpward, ctx.getString(R.string.admin_pv_action_up), { onAction(RuleAction.MOVE_UP) }, enabled = moves.up)
                    EditorAction(Icons.Default.ArrowDownward, ctx.getString(R.string.admin_pv_action_down), { onAction(RuleAction.MOVE_DOWN) }, enabled = moves.down)
                    if (moves.test) EditorAction(Icons.AutoMirrored.Filled.FactCheck, ctx.getString(R.string.admin_pv_action_test), { onAction(RuleAction.ADD_TEST) })
                    if (moves.convert) EditorAction(Icons.Default.SwapHoriz, ctx.getString(R.string.admin_pv_action_convert), { onAction(RuleAction.CONVERT) })
                }
            }
            if (!isNew) HelpText(ctx.getString(R.string.admin_pv_order_help))
        }
    }
}

@Composable
private fun AclFields(r: AclRule, env: VisualEnv, isNew: Boolean, viaOk: Boolean, onChange: (AclRule) -> Unit, onVia: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    SelectorField(ctx.getString(R.string.admin_pv_f_who), r.src, SelectorSlot.ACL_SRC, env, onChange = { onChange(r.copy(src = it)) })
    DestinationsField(
        ctx.getString(R.string.admin_pv_f_reach),
        r.dst,
        SelectorSlot.ACL_DST,
        env,
        onChange = { onChange(r.copy(dst = it)) },
        help = ctx.getString(R.string.admin_pv_f_reach_acl_help),
    )
    MoreSection(
        open = !isNew && (r.proto != null || r.srcPosture.isNotEmpty()),
        summary = moreSummary(ctx, listOfNotNull(r.proto?.let { PortWords.proto(it) }, r.srcPosture.takeIf { it.isNotEmpty() }?.joinToString(", "))),
    ) {
        ProtocolField(r.proto, env) { onChange(r.copy(proto = it)) }
        PostureField(r.srcPosture, env) { onChange(r.copy(srcPosture = it)) }
        if (viaOk) {
            val sameHosts = r.destinations.map { it.ports }.distinct().size <= 1
            SelectorField(
                ctx.getString(R.string.admin_pv_f_via),
                emptyList(),
                SelectorSlot.GRANT_VIA,
                if (sameHosts) env else env.copy(canWrite = false),
                onChange = { if (it.isNotEmpty()) onVia(it) },
                help = ctx.getString(if (sameHosts) R.string.admin_pv_f_via_acl_help else R.string.admin_pv_f_via_acl_ports),
            )
        }
    }
}

@Composable
private fun GrantFields(r: GrantRule, env: VisualEnv, actions: VisualActions, isNew: Boolean, onChange: (GrantRule) -> Unit) {
    val ctx = LocalContext.current
    SelectorField(ctx.getString(R.string.admin_pv_f_who), r.src, SelectorSlot.GRANT_SRC, env, onChange = { onChange(r.copy(src = it)) })
    SelectorField(ctx.getString(R.string.admin_pv_f_reach), r.dst, SelectorSlot.GRANT_DST, env, onChange = { onChange(r.copy(dst = it)) })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IpField(ctx.getString(R.string.admin_pv_f_ports), r.ip, env, onChange = { onChange(r.copy(ip = it)) })
        if (r.ip.isEmpty()) {
            HelpText(ctx.getString(if (r.app.isEmpty()) R.string.admin_pv_ip_none else R.string.admin_pv_ip_none_apps))
        }
    }
    if (r.app.isNotEmpty()) AppsBlock(r.app, actions)
    MoreSection(
        open = !isNew && (r.via.isNotEmpty() || r.srcPosture.isNotEmpty()),
        summary = moreSummary(ctx, listOfNotNull(r.via.takeIf { it.isNotEmpty() }?.joinToString(", "), r.srcPosture.takeIf { it.isNotEmpty() }?.joinToString(", "))),
    ) {
        SelectorField(
            ctx.getString(R.string.admin_pv_f_via),
            r.via,
            SelectorSlot.GRANT_VIA,
            env,
            onChange = { onChange(r.copy(via = it)) },
            help = ctx.getString(R.string.admin_pv_f_via_help),
        )
        PostureField(r.srcPosture, env) { onChange(r.copy(srcPosture = it)) }
    }
}

/** What "More options" holds, folded: the values set there, or what can be set. */
private fun moreSummary(ctx: android.content.Context, set: List<String>): String =
    set.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: ctx.getString(R.string.admin_pv_more_summary)

/** Device posture a source must pass: Tailscale only, unless the file has it anyway (then shown, the server's refusal mapped). */
@Composable
internal fun PostureField(values: List<String>, env: VisualEnv, onChange: (List<String>) -> Unit) {
    if (env.headscale && values.isEmpty()) return
    val ctx = LocalContext.current
    SelectorField(
        ctx.getString(R.string.admin_pv_f_posture),
        values,
        SelectorSlot.POSTURE,
        env,
        onChange = onChange,
        help = ctx.getString(R.string.admin_pv_f_posture_help),
    )
}

/**
 * An ACL's or a test's `proto`: any (the field left out), TCP, UDP, ICMP, or another protocol
 * by name or number.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProtocolField(proto: String?, env: VisualEnv, onChange: (String?) -> Unit) {
    val ctx = LocalContext.current
    val common = listOf("tcp", "udp", "icmp")
    val isOther = proto != null && proto.lowercase() !in common
    var other by remember { mutableStateOf(isOther) }
    var typed by remember(proto) { mutableStateOf(if (isOther) proto.orEmpty() else "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(ctx.getString(R.string.admin_pv_f_proto))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip(selected = proto == null && !other, onClick = { other = false; onChange(null) }, label = ctx.getString(R.string.admin_pv_proto_any), enabled = env.editable)
            common.forEach { p ->
                SelectChip(selected = proto.equals(p, ignoreCase = true) && !other, onClick = { other = false; onChange(p) }, label = PortWords.proto(p), enabled = env.editable)
            }
            SelectChip(selected = other, onClick = { other = true }, label = ctx.getString(R.string.admin_pv_chip_other), enabled = env.editable)
        }
        if (other) {
            val t = typed.trim()
            val ok = t.isNotEmpty() && PortWords.protoOk(t)
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.replace(" ", "") },
                enabled = env.editable,
                singleLine = true,
                label = { Text(ctx.getString(R.string.admin_pv_proto_other)) },
                isError = t.isNotEmpty() && !ok,
                supportingText = { Text(ctx.getString(if (t.isNotEmpty() && !ok) R.string.admin_pv_proto_bad else R.string.admin_pv_proto_hint)) },
                trailingIcon = { IconButton(onClick = { onChange(t.lowercase()) }, enabled = ok && t != proto) { Icon(Icons.Default.Check, ctx.getString(R.string.admin_pv_note_save)) } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onChange(t.lowercase()) }),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HelpText(ctx.getString(R.string.admin_pv_f_proto_help))
    }
}

/** A grant's app capabilities: their names, the parameters left to the JSON editor. */
@Composable
private fun AppsBlock(apps: List<AppCapability>, actions: VisualActions) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(ctx.getString(R.string.admin_pv_f_apps))
        apps.forEach { a ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Apps, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appName(ctx, a.name), style = MaterialTheme.typography.bodyMedium)
                        Text(a.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { actions.openJson(a.line) }) { Text(ctx.getString(R.string.admin_pv_json)) }
                }
            }
        }
        HelpText(ctx.getString(R.string.admin_pv_f_apps_help))
    }
}

/** The capabilities Tailscale documents, in words; others as named. */
internal fun appName(ctx: android.content.Context, name: String): String = when (name) {
    "tailscale.com/cap/drive" -> ctx.getString(R.string.admin_pv_app_drive)
    "tailscale.com/cap/tsidp" -> ctx.getString(R.string.admin_pv_app_tsidp)
    "tailscale.com/cap/kubernetes" -> ctx.getString(R.string.admin_pv_app_kubernetes)
    "tailscale.com/cap/relay" -> ctx.getString(R.string.admin_pv_app_relay)
    else -> name.substringAfterLast('/')
}

/**
 * "Check with the server": what [user] reaches under the draft, asked of /acl/preview — the
 * server's answer, for what the phone's own count cannot tell.
 */
@Composable
private fun ServerCheck(user: String, actions: VisualActions) {
    val ctx = LocalContext.current
    var loading by remember(user) { mutableStateOf(false) }
    var result by remember(user) { mutableStateOf<Result<PolicyPreview>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                loading = true
                actions.previewDraft(PolicyPreviewType.USER, user) { r -> result = r; loading = false }
            },
            enabled = !loading,
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(ctx.getString(R.string.admin_pv_server_check, user), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        result?.let { r ->
            r.onSuccess { p ->
                if (p.matches.isEmpty()) {
                    Text(ctx.getString(R.string.admin_pv_server_none, user), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    p.matches.forEach { m ->
                        CardRow(m.lineNumber?.let { ctx.getString(R.string.admin_pv_line, it) } ?: "—") {
                            Text(m.ports.joinToString(", "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }.onFailure {
                Text(ctx.getString(R.string.admin_pv_server_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
