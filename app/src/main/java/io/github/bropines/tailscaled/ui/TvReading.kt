package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import kotlinx.coroutines.launch

/*
 * What the reading screens (logs, console, netcheck, licences, "What's new", the live card) add
 * to the foundation's ui/Focus.kt: tvScrollKeys with the two things a long text needs that it
 * does not give — a pane that also moves sideways, and a held key that keeps the text moving.
 */

/** Of its height, how far a first press moves the text. */
private const val PAGE_FRACTION = 0.5f

/** Of its height, how far each repeat of a held key moves it, without animation. */
private const val HELD_FRACTION = 0.2f

/**
 * [tvScrollKeys] for a text that is long or wide: a stop for the remote, ringed while it has the focus, which Up and Down move by half its height
 * ([vertical]) and — for a text that is wider than its pane, [horizontal] — Left and Right by half
 * its width. At an end the key passes through, so the focus can leave. A key held down moves the
 * text continuously instead of restarting a half-page animation on every repeat, so a long log can
 * be crossed in a few seconds.
 *
 * Only for a pane with no stops inside it: the keys of the pane come before the focus search, so
 * a button inside could never be reached by Up or Down while the text can still move. A header
 * button above the text is the exception that works — it is reached from the side and the keys
 * on it scroll the text.
 *
 * Goes before the scroll modifier it serves, and insets it by the ring's width. With
 * [focusOnce], the screen's flag (see [tvFocusOnce]), the pane is where the remote starts.
 */
@Composable
fun Modifier.tvReadingPane(
    vertical: ScrollableState,
    horizontal: ScrollableState? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    focusOnce: MutableState<Boolean>? = null,
): Modifier {
    if (!isTv) return this
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val sideways by rememberUpdatedState(horizontal)
    return onSizeChanged { size = it }
        .onKeyEvent { event ->
            val axis: ScrollableState
            val forward: Boolean
            val extent: Int
            when (event.key) {
                Key.DirectionDown -> { axis = vertical; forward = true; extent = size.height }
                Key.DirectionUp -> { axis = vertical; forward = false; extent = size.height }
                Key.DirectionRight -> { axis = sideways ?: return@onKeyEvent false; forward = true; extent = size.width }
                Key.DirectionLeft -> { axis = sideways ?: return@onKeyEvent false; forward = false; extent = size.width }
                else -> return@onKeyEvent false
            }
            if (if (forward) !axis.canScrollForward else !axis.canScrollBackward) return@onKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                val sign = if (forward) 1f else -1f
                val held = event.nativeKeyEvent.repeatCount > 0
                scope.launch {
                    if (held) axis.scrollBy(extent * HELD_FRACTION * sign)
                    else axis.animateScrollBy(extent * PAGE_FRACTION * sign)
                }
            }
            true
        }
        .then(if (focusOnce != null) Modifier.tvFocusOnce(focusOnce) else Modifier)
        .tvFocusRing(source, shape)
        .focusable(interactionSource = source)
        // The ring is drawn inside the pane: keep the text out from under it.
        .padding(TvReadingRing)
}

/** The ring is 4dp across; the text stays that far again from it. */
private val TvReadingRing = 8.dp

/**
 * A search or lookup field for a remote. A text field in the screen raises the keyboard the
 * moment the remote passes over it, and the keyboard then takes the arrows: it is a stop nobody
 * can walk through. So the field is a button showing what is typed so far (or the hint), and OK
 * opens a dialog with the field and the keyboard. Back closes the keyboard, then the dialog; the
 * text applies as it is typed, and Done (or Search, when there is an [onDone] to run) closes it.
 *
 * Words come from this composition, not the dialog's window, which would take the system language.
 */
@Composable
fun TvQueryField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val closeLabel = stringResource(R.string.action_close)
    val clearLabel = stringResource(R.string.action_clear)
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            value.ifEmpty { placeholder },
            modifier = Modifier.weight(1f),
            color = if (value.isEmpty()) MaterialTheme.colorScheme.outline else LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (open) {
        val field = remember { FocusRequester() }
        var typed by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
        val finish: () -> Unit = {
            open = false
            onDone?.invoke()
        }
        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(placeholder) },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; onValueChange(it.text) },
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Search else ImeAction.Done),
                    keyboardActions = KeyboardActions(onSearch = { finish() }, onDone = { finish() }),
                )
            },
            confirmButton = { TextButton(onClick = finish) { Text(closeLabel) } },
            dismissButton = if (value.isNotEmpty()) {
                { TextButton(onClick = { typed = TextFieldValue(""); onValueChange("") }) { Text(clearLabel) } }
            } else null
        )
    }
}

/** The text sizes a television's button steps through; a phone pinches. */
private val TvTextScales = listOf(1f, 1.25f, 1.5f, 2f)

/** The size after [current] in [TvTextScales], round to the first. */
internal fun nextTvTextScale(current: Float): Float =
    TvTextScales.firstOrNull { it > current + 0.01f } ?: TvTextScales.first()

/**
 * A button of a television's top bar: the icon and its words, in the accent colour while
 * [active], the error colour when it is a [danger]. Words, because nothing on a remote says
 * what a bare icon is for.
 */
@Composable
internal fun TvTextAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = when {
                danger -> colors.error
                active -> colors.primary
                else -> colors.onSurface
            }
        ),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

private const val TV_FOCUS_TRIES = 8

/**
 * Puts the remote on this element once, when it first appears, and never again: a screen whose
 * first row is recomposed for every tab (the peers pager) would otherwise pull the focus off the
 * tab bar each time a tab is chosen. [spent] is the screen's own flag, so a rebuilt element does
 * not ask twice. The identity off a television.
 */
@Composable
fun Modifier.tvFocusOnce(spent: MutableState<Boolean>): Modifier {
    if (!isTv || spent.value) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        // A request before the element is laid out throws; a frame later it does not.
        repeat(TV_FOCUS_TRIES) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                spent.value = true
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }
    return focusRequester(requester)
}
