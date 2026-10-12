package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The posture editor's catalogue of attributes: folded under one row, then grouped by where an
 * attribute comes from — the device, Tailscale's control plane, an integration, the API — each
 * with its name, its key, what it holds folded under them, and a button that starts a condition
 * from Tailscale's own example. Nothing is written until the condition is.
 */

private fun sourceTitle(s: PostureSource): Int = when (s) {
    PostureSource.DEVICE -> R.string.admin_pvc_pa_from_device
    PostureSource.LOCATION -> R.string.admin_pvc_pa_from_control
    PostureSource.INTEGRATION -> R.string.admin_pvc_pa_from_integration
    PostureSource.CUSTOM -> R.string.admin_pvc_pa_from_custom
}

/**
 * The attributes a condition can check, folded until opened. [onUse] gets an attribute's
 * example condition to start a new row from; without [enabled] the list only explains.
 */
@Composable
internal fun PostureCatalogue(enabled: Boolean, onUse: (String) -> Unit, open: Boolean = false) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(open) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = expanded, role = Role.Switch, onValueChange = { expanded = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ctx.getString(R.string.admin_pvc_pa_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (!expanded) {
                    Text(
                        ctx.getString(R.string.admin_pvc_pa_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HelpText(ctx.getString(R.string.admin_pvc_pa_operators))
                PostureSource.entries.forEach { source ->
                    Text(
                        ctx.getString(sourceTitle(source)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    PostureAttrs.ALL.filter { it.source == source }.forEach { a -> AttrRow(a, enabled, onUse) }
                }
            }
        }
    }
}

@Composable
private fun AttrRow(a: PostureAttr, enabled: Boolean, onUse: (String) -> Unit) {
    val ctx = LocalContext.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(a.name(ctx), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (a.namespace) a.key + "…" else a.key,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HelpText(a.explain(ctx), lines = 1)
            }
            if (enabled) {
                IconButton(onClick = { onUse(a.example) }) {
                    Icon(Icons.Default.AddCircleOutline, ctx.getString(R.string.admin_pvc_pa_use, a.name(ctx)), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Under a condition: the attribute it checks, in words — "Public IP address (beta)" — when the editor knows it. */
internal fun postureConditionLabel(ctx: android.content.Context, condition: String): String? = PostureAttrs.of(condition)?.name(ctx)
