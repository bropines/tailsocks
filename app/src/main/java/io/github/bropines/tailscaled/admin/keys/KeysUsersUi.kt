package io.github.bropines.tailscaled.admin.keys

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.rememberFullSheetState
import kotlinx.coroutines.launch

/*
 * Small pieces the Keys and Users tabs share: windows that keep the app's language, a label
 * chip, a switch row, and spans of time in words.
 */

/**
 * A full-height sheet whose content reads strings in the app's language: the parent's
 * context, configuration and resources provided again inside — the sheet's own window
 * answers in the system language (see wrapContextWithLocale). [content] gets a `hide`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocaleSheet(onDismiss: () -> Unit, content: @Composable (hide: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val sheetState = rememberFullSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalResources provides resources) {
            content { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
        }
    }
}

/** An AlertDialog whose title, body and buttons resolve strings in the app's language. */
@Composable
internal fun LocaleDialog(
    onDismiss: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    @Composable
    fun Provide(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalResources provides resources, content = content)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null) } },
        title = { Text(title) },
        text = { Provide(text) },
        confirmButton = { Provide(confirmButton) },
        dismissButton = dismissButton?.let { { Provide(it) } },
    )
}

/** A small label with an optional icon: a state that is read by its words, not its colour. */
@Composable
internal fun Chip(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A switch with its title and folded explanation: one row, one focus stop. */
@Composable
internal fun SwitchRow(title: String, help: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            HelpText(help, inClickableRow = true)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** "3 days", "5 hours", "1 minute" — the form that follows "in" and precedes "ago". */
internal fun spanText(ctx: Context, ms: Long): String {
    val s = KeyList.span(ms)
    val res = when (s.unit) {
        TimeUnitShown.DAYS -> R.plurals.admin_k_span_days
        TimeUnitShown.HOURS -> R.plurals.admin_k_span_hours
        TimeUnitShown.MINUTES -> R.plurals.admin_k_span_minutes
    }
    return ctx.resources.getQuantityString(res, s.amount, s.amount)
}

/** "in 3 days" ahead, "3 days ago" past. */
internal fun relativeText(ctx: Context, ms: Long): String =
    ctx.getString(if (ms >= 0) R.string.admin_k_in else R.string.admin_k_ago, spanText(ctx, ms))
