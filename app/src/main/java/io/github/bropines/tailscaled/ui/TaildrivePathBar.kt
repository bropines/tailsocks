package io.github.bropines.tailscaled.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DriveLocation

/*
 * The share browser's path, under its title: every name a button that opens its level, a long path
 * scrolled to its end rather than cut; and, asked for, a field to type a path into. The reading of
 * what is typed is core/TaildrivePath.kt; the page does the looking and the moving.
 */

/**
 * The path from the list of devices to [location], one button a level: the devices first, as an
 * icon, then the device, the share and the folders. The tailnet is left out, as the line under the
 * title always has: it is the same everywhere. [top] is how deep the list of devices is. Pressing
 * the last name — where the page is — asks for the field ([onEdit]); any other goes there ([onGo]).
 * On a television each name is a stop of the bar.
 */
@Composable
internal fun PathCrumbs(location: DriveLocation, top: Int, onGo: (DriveLocation) -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // A long path shows its end, where the page is; the start is a scroll away.
    LaunchedEffect(location, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val colors = MaterialTheme.colorScheme
    val devices = DriveLocation(location.segments.take(top))
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Crumb(onClick = { onGo(devices) }, label = stringResource(R.string.taildrive_browser_level_devices)) {
            Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.taildrive_browser_level_devices), tint = colors.primary, modifier = Modifier.size(16.dp))
        }
        for (i in top until location.depth) {
            val last = i == location.depth - 1
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            val name = location.segments[i]
            Crumb(onClick = if (last) onEdit else ({ onGo(DriveLocation(location.segments.take(i + 1))) }), label = null) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                    fontWeight = if (last) FontWeight.SemiBold else null,
                    maxLines = 1
                )
            }
        }
    }
}

/** One name of the path: a small button, ringed on a television. [label] speaks for an icon. */
@Composable
private fun Crumb(onClick: () -> Unit, label: String?, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .tvFocusRing(source, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/** The field's state, as the page keeps it: what is typed, and what the last Go found wrong. */
internal class PathEdit(
    val text: String,
    val looking: Boolean = false,
    /** What was not found, in words; null while nothing was. */
    val problem: String? = null,
    /** The deepest place of the typed path that is there, offered as one button; null with none to offer. */
    val deepest: DriveLocation? = null,
    /** [deepest] as the field writes it. */
    val deepestLabel: String? = null,
)

/**
 * The path as a field, under the bar: Enter (or Go) goes there; Escape, Back and the cross leave
 * without moving. A path that is not there is said under the field, with the deepest part of it
 * that is, one press away. On a television the field is the house's row ([TvFieldRow]), which OK
 * opens with the keyboard around it, and Go is the stop beside it.
 */
@Composable
internal fun PathEditor(
    edit: PathEdit,
    onText: (String) -> Unit,
    onGo: () -> Unit,
    onDeepest: (DriveLocation) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.taildrive_path_field)
    val placeholder = stringResource(R.string.taildrive_path_placeholder)
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val escape = Modifier.onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                        onClose()
                        true
                    } else false
                }
                if (isTv) {
                    TvFieldRow(
                        value = edit.text,
                        onValueChange = onText,
                        label = label,
                        placeholder = placeholder,
                        isError = edit.problem != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f).then(escape).tvInitialFocus()
                    )
                } else {
                    val field = remember { FocusRequester() }
                    // The cursor after what is there: a path is added to more often than retyped.
                    var value by remember { mutableStateOf(TextFieldValue(edit.text, TextRange(edit.text.length))) }
                    if (!LocalInspectionMode.current) LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it; onText(it.text) },
                        label = { Text(label) },
                        placeholder = { Text(placeholder) },
                        singleLine = true,
                        isError = edit.problem != null,
                        // A path, not words: no suggestions or corrections over the names.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier.weight(1f).focusRequester(field).then(escape)
                    )
                }
                // Not disabled while it looks: a disabled button drops the remote to the first stop of the screen.
                TextButton(onClick = { if (!edit.looking) onGo() }) { Text(stringResource(R.string.taildrive_path_go)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            when {
                edit.looking -> Text(stringResource(R.string.taildrive_path_looking), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                edit.problem != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        edit.problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val deepest = edit.deepest
                    if (deepest != null && edit.deepestLabel != null) {
                        TextButton(onClick = { onDeepest(deepest) }) {
                            Text(stringResource(R.string.taildrive_path_go_to, edit.deepestLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
