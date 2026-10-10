package io.github.bropines.tailscaled.admin.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.HelpText

/*
 * Pieces the configuration tabs (policy, DNS, webhooks, settings) share.
 */

/**
 * The parent composition's context, configuration and resources, to provide again inside a
 * dialog or a sheet: those are windows of their own and would answer in the system language
 * (see wrapContextWithLocale()).
 */
class ParentLocals(val context: Context, val configuration: Configuration, val resources: Resources)

@Composable
fun rememberParentLocals(): ParentLocals = ParentLocals(LocalContext.current, LocalConfiguration.current, LocalResources.current)

@Composable
fun ParentLocals.Provide(content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalContext provides context,
    LocalConfiguration provides configuration,
    LocalResources provides resources,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfigLoading(modifier: Modifier = Modifier) = LoadingIndicator(modifier)

/** Nothing loaded yet: the load's problem with a retry, or the loader. */
@Composable
fun <T> ConfigNotLoaded(state: Loadable<T>, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        LoadProblems(state, onRetry)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (state.loading) ConfigLoading() }
    }
}

/**
 * The narrowest a configuration card stands in a column of its own (DNS, Settings, the
 * Headscale server): a switch row's title, its folded explanation and the switch.
 */
val CONFIG_CARD_MIN_WIDTH = 360.dp

/** A card on the screen background, [title] over its rows. */
@Composable
fun ConfigCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/**
 * A switch row, one focus stop: the whole row toggles. [checked] null is a value the
 * credential may not read — the switch is off and the row disabled. [note] says why a row is
 * disabled, or warns, under the explanation.
 */
@Composable
fun ConfigSwitchRow(
    title: String,
    help: String,
    checked: Boolean?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    note: String? = null,
) {
    val on = enabled && checked != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked == true, enabled = on, role = Role.Switch) { onToggle(it) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            HelpText(help, inClickableRow = true)
            if (note != null) ConfigNote(note)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked == true, onCheckedChange = null, enabled = on)
    }
}

enum class NoteTone { INFO, LOCKED, WARNING }

/** One line with an icon, so what it says does not hang on its colour. */
@Composable
fun ConfigNote(text: String, tone: NoteTone = NoteTone.LOCKED, modifier: Modifier = Modifier) {
    val (icon: ImageVector, color) = when (tone) {
        NoteTone.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
        NoteTone.LOCKED -> Icons.Default.Lock to MaterialTheme.colorScheme.onSurfaceVariant
        NoteTone.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.error
    }
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(14.dp).padding(top = 2.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
