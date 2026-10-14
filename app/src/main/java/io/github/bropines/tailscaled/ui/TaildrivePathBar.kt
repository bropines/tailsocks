package io.github.bropines.tailscaled.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 * The share browser's title line is its path: every name a button that opens its level, a long
 * path scrolled to its end rather than cut; and, asked for, the same line as a field to type a
 * path into. The reading of what is typed is core/TaildrivePath.kt; the page does the looking and
 * the moving.
 */

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
 * The title line: the path from the list of devices to [location] ([PathCrumbs]), or, while
 * [edit] is not null, the field in its place ([PathField]). [level] is what the line says at the
 * list of devices (or of tailnets), where there is no path yet. On a handheld a long press on the
 * line asks for the field ([onEdit]); a television has the bar's pencil for it instead.
 */
@Composable
internal fun PathTitle(
    location: DriveLocation,
    top: Int,
    level: String,
    edit: PathEdit?,
    onGo: (DriveLocation) -> Unit,
    onEdit: () -> Unit,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    if (edit != null) PathField(edit, onText, onSubmit, onClose)
    else PathCrumbs(location, top, level, onGo, onEdit, longPress = !isTv)
}

/**
 * The path, one button a level, at the size of a title: the devices first, as an icon, then the
 * device, the share and the folders. The tailnet is left out: it is the same everywhere. [top] is
 * how deep the list of devices is; at it, and above it, the line is [level], words and no buttons.
 * Pressing the last name — where the page is — asks for the field; any other goes there. With
 * [longPress] a long press anywhere on the line asks for the field too. On a television each name is
 * a stop of the bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PathCrumbs(
    location: DriveLocation,
    top: Int,
    level: String,
    onGo: (DriveLocation) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    longPress: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleLarge
    val edit = onEdit.takeIf { longPress }
    if (location.depth <= top) {
        // Nothing to press yet: the list of devices, or of tailnets.
        val source = remember { MutableInteractionSource() }
        Text(
            level,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (edit == null) modifier else modifier.combinedClickable(
                interactionSource = source, indication = null, onLongClick = edit, onClick = {}
            )
        )
        return
    }
    val scroll = rememberScrollState()
    // A long path shows its end, where the page is; the start is a scroll away.
    LaunchedEffect(location, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val devices = DriveLocation(location.segments.take(top))
    val label = stringResource(R.string.taildrive_browser_level_devices)
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Crumb(onClick = { onGo(devices) }, onLongClick = edit, label = label) {
            Icon(Icons.Default.Devices, contentDescription = label, tint = colors.primary, modifier = Modifier.size(24.dp))
        }
        for (i in top until location.depth) {
            val last = i == location.depth - 1
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Crumb(onClick = if (last) onEdit else ({ onGo(DriveLocation(location.segments.take(i + 1))) }), onLongClick = edit, label = null) {
                Text(
                    location.segments[i],
                    style = style,
                    color = if (last) colors.onSurface else colors.primary,
                    fontWeight = if (last) FontWeight.SemiBold else null,
                    maxLines = 1
                )
            }
        }
    }
}

/** One name of the path: a button, ringed on a television. [label] speaks for an icon. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Crumb(onClick: () -> Unit, onLongClick: (() -> Unit)?, label: String?, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .tvFocusRing(source, shape)
            .clip(shape)
            .combinedClickable(
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = label,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/**
 * The path as a field, in the title's own line and at its size: Go on the keyboard goes there; the
 * cross, Escape and Back leave without moving — Back at once, with the keyboard up as well: it is
 * taken before the keyboard can spend it on hiding itself. On a television the field is the house's
 * row ([TvFieldRow]), which OK opens with the keyboard around it, and Go and the cross are the stops
 * beside it.
 */
@Composable
internal fun PathField(edit: PathEdit, onText: (String) -> Unit, onSubmit: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.taildrive_path_field)
    val placeholder = stringResource(R.string.taildrive_path_placeholder)
    val close = stringResource(R.string.action_close)
    val keys = Modifier.onPreviewKeyEvent { event ->
        if (event.key == Key.Escape) {
            if (event.type == KeyEventType.KeyUp) onClose()
            true
        } else false
    }
    if (isTv) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvFieldRow(
                value = edit.text,
                onValueChange = onText,
                label = label,
                placeholder = placeholder,
                isError = edit.problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).then(keys).tvInitialFocus()
            )
            // A second press while it looks is dropped, not refused: a disabled button drops the remote.
            TextButton(onClick = { if (!edit.looking) onSubmit() }) { Text(stringResource(R.string.taildrive_path_go)) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = close) }
        }
        return
    }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The cursor after what is there: a path is added to more often than retyped.
    var value by remember { mutableStateOf(TextFieldValue(edit.text, TextRange(edit.text.length))) }
    if (!LocalInspectionMode.current) LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    val style = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = {
                    // Only what was typed: the keyboard also commits its composing text as the field
                    // goes, and that must not open it again.
                    val typed = it.text != value.text
                    value = it
                    if (typed) onText(it.text)
                },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.primary),
                // A path, not words: no suggestions or corrections over the names.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!edit.looking) onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .then(keys)
                    // Back while the keyboard is up: taken here, before the keyboard hides itself with it.
                    .onPreInterceptKeyBeforeSoftKeyboard { event ->
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyUp) onClose()
                            true
                        } else false
                    },
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) Text(placeholder, style = style.copy(color = colors.onSurfaceVariant), maxLines = 1)
                        inner()
                    }
                }
            )
            // The field's edge: a line under the text, red when the path is not there.
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (edit.problem != null) 2.dp else 1.dp)
                    .background(if (edit.problem != null) colors.error else colors.primary)
            )
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = close) }
    }
}

/**
 * Under the bar while the field is open and has something to say: that it is looking, or that the
 * path is not there, with the deepest part of it that is, one press away.
 */
@Composable
internal fun PathProblem(edit: PathEdit, onDeepest: (DriveLocation) -> Unit, modifier: Modifier = Modifier) {
    if (!edit.looking && edit.problem == null) return
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (edit.looking) stringResource(R.string.taildrive_path_looking) else edit.problem.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (edit.looking) colors.onSurfaceVariant else colors.error,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val deepest = edit.deepest
            if (!edit.looking && deepest != null && edit.deepestLabel != null) {
                TextButton(onClick = { onDeepest(deepest) }) {
                    Text(stringResource(R.string.taildrive_path_go_to, edit.deepestLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
