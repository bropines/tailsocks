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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/*
 * `externalTailnets` — declarative node sharing, in alpha — on the Network page: read, not
 * edited. Each tailnet the file trusts with its alias, its ID, whether this tailnet accepts its
 * connections, which of this file's groups and tags it may name, and how many grants name its
 * own groups and tags (`group://alias/name`). The section is Tailscale's alone; Headscale
 * refuses a policy that has it.
 */

/** Grants that name a group or tag of the tailnet called [alias] here. */
internal fun externalUses(m: PolicyModel, alias: String): Int = m.grants.count { g ->
    (g.src + g.dst).any { Selectors.parse(it).kind == SelectorKind.EXTERNAL && Selectors.externalAlias(it) == alias }
}

@Composable
internal fun ExternalTailnetsCard(entry: SectionEntry, env: VisualEnv, actions: VisualActions, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    ElementCard(entry.origin, env, actions, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Share, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ctx.getString(R.string.admin_pvc_staged, ctx.getString(R.string.admin_pvc_ext_title), KnownCaps.stage(ctx, FeatureStage.ALPHA)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(entry.key, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(ctx.getString(R.string.admin_pv_net_line, entry.origin.line), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HelpText(ctx.getString(R.string.admin_pvc_ext_help))
        if (env.headscale) StatusRow(Icons.Default.ErrorOutline, ctx.getString(R.string.admin_pvd_not_on_headscale), MaterialTheme.colorScheme.error)
        model.externalTailnets.forEach { t -> TailnetRow(t, externalUses(model, t.alias)) }
        TextButton(onClick = { actions.openJson(entry.origin.line) }, modifier = Modifier.align(Alignment.End)) { Text(ctx.getString(R.string.admin_pv_edit_in_json)) }
    }
}

@Composable
private fun TailnetRow(t: ExternalTailnet, uses: Int) {
    val ctx = LocalContext.current
    val res = ctx.resources
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.alias, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.externalId?.let { Text(ctx.getString(R.string.admin_pvc_ext_id, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                ctx.getString(if (t.allowIncoming == true) R.string.admin_pvc_ext_incoming_yes else R.string.admin_pvc_ext_incoming_no),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (t.allowReferencesTo.isEmpty()) ctx.getString(R.string.admin_pvc_ext_refs_none)
                else ctx.getString(R.string.admin_pvc_ext_refs, t.allowReferencesTo.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (uses > 0) {
                Text(res.getQuantityString(R.plurals.admin_pvc_ext_used, uses, uses), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!t.origin.editable) HelpText(t.origin.issues.firstOrNull()?.let { VisualText.issue(ctx, it) } ?: ctx.getString(R.string.admin_pv_read_only))
        }
    }
}
