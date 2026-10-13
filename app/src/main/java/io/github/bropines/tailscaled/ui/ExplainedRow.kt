package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The row a setting is — a tinted surface that is one button, its explanation
 * folded under the title (HelpText) — and what a television needs of it and of
 * what stands around it on a settings page.
 *
 * On a handheld the row is a tap and a long press: the tap acts, the long press
 * unfolds the explanation, and so does the ⓘ at the end of it. A remote has no
 * long press to rely on, and an ⓘ that took a stop of its own would double
 * every list. So on a television the row is one stop, ringed while the remote
 * is on it, and it unfolds its own explanation once the remote has rested
 * there. Not at once: a list the remote runs down would swell and shrink under
 * every row it passed.
 */

/** How long the remote rests on a row before the row's explanation unfolds. */
private const val HelpRevealDelayMillis = 700L

/**
 * Whether the row around an explanation has unfolded it for the remote. Null
 * where no such row is around, and everywhere off a television: only
 * [ExplainedRow] provides it, and only there.
 */
val LocalRowHelpRevealed = compositionLocalOf<Boolean?> { null }

/**
 * One row of the settings family: [content] on a tinted surface that is a
 * button as a whole. [onClick] is the row's action; a long press folds [help]
 * — the state the row's `HelpText` is given — out and in, with a tick, unless
 * [onLongClick] does something of its own. A row that is switched off still
 * takes the press and decides in [onClick]: it keeps its place under a thumb
 * and its stop for a remote.
 *
 * On a television the row is ringed while it has the focus and tells the
 * explanation inside it when to unfold ([LocalRowHelpRevealed]); a control in
 * [content] that only repeats the row's action — a switch, a radio button —
 * takes `Modifier.tvNoFocus()`, so the row stays one stop.
 *
 * [clipped] keeps the ripple inside [shape]; the tunnel modes never had that.
 * A television clips every row: Material's own ring is a rectangle, and its
 * corners would stand out from under the row's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplainedRow(
    onClick: () -> Unit,
    help: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    border: BorderStroke? = null,
    clipped: Boolean = true,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val tv = isTv
    // A row inside what is already unfolded stays unfolded: how a preview, which cannot
    // press a key, draws a row the remote has rested on.
    val revealed = tv && (rememberHelpReveal(source) || LocalRowHelpRevealed.current == true)
    Surface(
        shape = shape,
        color = color,
        border = border,
        modifier = modifier
            .then(if (tv) Modifier.keepInViewWhile(revealed) else Modifier)
            .tvFocusRing(source, shape)
            .then(if (clipped || tv) Modifier.clip(shape) else Modifier)
            .combinedClickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClickLabel = onClickLabel,
                onClick = onClick,
                onLongClick = onLongClick ?: {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    help.value = !help.value
                }
            )
    ) {
        if (tv) CompositionLocalProvider(LocalRowHelpRevealed provides revealed, content = content) else content()
    }
}

/** True once the remote has rested on the target of [source] for [HelpRevealDelayMillis]; false again as it leaves. */
@Composable
private fun rememberHelpReveal(source: InteractionSource): Boolean {
    val focused by source.collectIsFocusedAsState()
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) delay(HelpRevealDelayMillis)
        revealed = focused
    }
    return revealed
}

/**
 * Scrolls the element back into view as it grows while [wanted]: an explanation
 * that unfolds on the last row of a page would otherwise unfold below its edge.
 */
@Composable
private fun Modifier.keepInViewWhile(wanted: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val now by rememberUpdatedState(wanted)
    return bringIntoViewRequester(requester).onSizeChanged { if (now) scope.launch { requester.bringIntoView() } }
}

/**
 * The ring of an explanation that is a stop for the remote itself — one with
 * no row around it to unfold it. Drawn outside the text, which has no margin
 * of its own to give up: the edge line of [focusRing] with its contrast line
 * inside. Goes before `animateContentSize`, which clips.
 */
@Composable
internal fun Modifier.tvHelpFocusRing(source: InteractionSource): Modifier {
    if (!isTv) return this
    val focused by source.collectIsFocusedAsState()
    val edge = MaterialTheme.colorScheme.secondary
    val contrast = MaterialTheme.colorScheme.onSecondary
    return drawBehind {
        if (!focused) return@drawBehind
        val line = 2.dp.toPx()
        // Room at the sides only: above and below, the lines of a text keep their own
        // distance, and what stands over it is close.
        val side = 4.dp.toPx()
        // From the text outwards: the contrast line, then the edge line.
        for ((color, distance) in listOf(contrast to line / 2, edge to line * 1.5f)) {
            drawRoundRect(
                color = color,
                topLeft = Offset(-distance - side, -distance),
                size = Size(size.width + (distance + side) * 2, size.height + distance * 2),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(line)
            )
        }
    }
}

/**
 * A [Switch] that is a stop for the remote on its own, not through a row. On a
 * television it is ringed as a whole while it has the focus: Material rings
 * the thumb, and the thumb of a switch that is off is too small to carry it.
 */
@Composable
fun FocusSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    if (!isTv) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, interactionSource = source)
        return
    }
    // The ring hugs the track: without the touch margin a finger needs, with a gap of its own.
    Box(modifier.tvFocusRing(source, CircleShape).padding(6.dp)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, interactionSource = source)
        }
    }
}

/**
 * Lets the remote reach the parts of a page that no stop leads to: the title
 * above the first row, a line of status under the last, a card with nothing
 * in it to press. Up and Down move the focus as they always do; where the
 * next stop is outside the page, or there is none, and the page still has
 * somewhere to go, they scroll it by a third of its height instead. Only at
 * the page's end does the focus leave it.
 *
 * Goes on the scrolling container, before its scroll modifier. It makes the
 * container a focus group of its own, apart from any [tvFocusGroup] inside:
 * the two would overwrite each other's exit rule on one group.
 */
@Composable
fun Modifier.tvScrollPastStops(state: ScrollableState): Modifier {
    if (!isTv) return this
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(0) }
    fun canScroll(direction: FocusDirection) = when (direction) {
        FocusDirection.Down -> state.canScrollForward
        FocusDirection.Up -> state.canScrollBackward
        else -> false
    }
    return onSizeChanged { height = it.height }
        .onKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionUp -> FocusDirection.Up
                else -> return@onKeyEvent false
            }
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (focus.moveFocus(direction)) return@onKeyEvent true
            if (!canScroll(direction)) return@onKeyEvent false
            scope.launch { state.animateScrollBy(height / 3f * if (direction == FocusDirection.Down) 1 else -1) }
            true
        }
        .focusProperties { onExit = { if (canScroll(requestedFocusDirection)) cancelFocusChange() } }
        .focusGroup()
}
