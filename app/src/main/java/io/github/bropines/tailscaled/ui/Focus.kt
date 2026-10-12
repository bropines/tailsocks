package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * What a remote needs from a component, in one place. Every `tv…` modifier
 * here is the identity off a television (ui/DeviceClass.kt), so a shared
 * component may carry one and stay what it was on a phone.
 *
 * Most of the app needs none of them: anything with a ripple — buttons, chips,
 * switches, clickable rows — is a stop for the remote already, and on a
 * television the theme draws Material's focus ring on it. These are for the
 * rest: a target that draws no ripple, a row whose ring loses its corners, a
 * pane of text with nothing in it to stop on, a group that should remember
 * where the remote was.
 *
 * What the Android TV emulator showed of Material's ring, for whoever places
 * these: it is right on a component that knows its own shape (a button, a
 * chip, a Surface or Card with onClick); on `clip(shape).clickable` it is a
 * rectangle cut by the clip, so the rounded corners are missing; a switch that
 * is off barely shows it; and it is drawn inside the target, over whatever
 * comes within 4dp of the edge.
 */

/**
 * The ring around what the remote is on, in the colours and the measure of the
 * one Material's ripple draws on a television (the theme turns that on): 2dp of
 * `secondary` along the edge and 2dp of `onSecondary` inside it, so that one of
 * the two shows on any container. Drawn within the element's own bounds, where
 * no clipping parent cuts it.
 *
 * A plain function of [focused], whatever the device: a row that tracks its own
 * focus passes it in, and a preview — which cannot press a key — passes true.
 */
@Composable
@ReadOnlyComposable
fun Modifier.focusRing(focused: Boolean, shape: Shape = MaterialTheme.shapes.medium): Modifier {
    if (!focused) return this
    val colors = MaterialTheme.colorScheme
    // The later border is drawn first: the inner line goes down, the edge over its outer half.
    return border(FocusRingStroke, colors.secondary, shape).border(FocusRingStroke * 2, colors.onSecondary, shape)
}

private val FocusRingStroke = 2.dp

/**
 * [focusRing] from a target's own [source] — the interaction source its click
 * was given. For a target that turned its indication off (`indication = null`)
 * and would take the remote's focus without showing it; and, placed before the
 * clip, for a row that clips itself to [shape] ahead of its click: it lays the
 * same two lines over Material's, corners included.
 */
@Composable
fun Modifier.tvFocusRing(source: InteractionSource, shape: Shape = MaterialTheme.shapes.medium): Modifier {
    if (!isTv) return this
    val focused by source.collectIsFocusedAsState()
    return focusRing(focused, shape)
}

/**
 * Takes a target out of the remote's way: a control inside a row that is a
 * button itself — the ⓘ of a folded explanation, a switch the row toggles — or
 * one a remote has a key for. Touch and a pointer still reach it.
 */
@Composable
fun Modifier.tvNoFocus(): Modifier = if (isTv) focusProperties { canFocus = false } else this

/**
 * Makes a container one stop on the way across the screen: leaving it and
 * coming back returns to the item the remote was on, not to the nearest one.
 * For a rail, either pane of a list-detail layout, a column of a dashboard.
 */
@Composable
fun Modifier.tvFocusGroup(): Modifier = if (isTv) focusRestorer().focusGroup() else this

/**
 * Where the remote starts on a screen: asks for focus once, when the element
 * appears. A television must always have something in focus to act on; one
 * element per screen carries this.
 */
@Composable
fun Modifier.tvInitialFocus(requester: FocusRequester = remember { FocusRequester() }): Modifier {
    if (!isTv) return this
    LaunchedEffect(requester) { requester.requestFocus() }
    return focusRequester(requester)
}

/**
 * Lets the remote scroll a pane that has nothing in it to stop on — a log, a
 * report, a licence. Compose scrolls such a pane on Page Up and Page Down only,
 * which a remote does not have. The pane becomes a stop, ringed while it has
 * the focus; Up and Down move it by half its height, and at either end pass
 * through, so the remote can leave. Goes before the scroll modifier it serves,
 * which it insets by the ring's width.
 */
@Composable
fun Modifier.tvScrollKeys(state: ScrollableState, shape: Shape = MaterialTheme.shapes.medium): Modifier {
    if (!isTv) return this
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    var height by remember { mutableIntStateOf(0) }
    return onSizeChanged { height = it.height }
        .onKeyEvent { event ->
            val forward = when (event.key) {
                Key.DirectionDown -> true
                Key.DirectionUp -> false
                else -> return@onKeyEvent false
            }
            if (if (forward) !state.canScrollForward else !state.canScrollBackward) return@onKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                scope.launch { state.animateScrollBy(height / 2f * if (forward) 1 else -1) }
            }
            true
        }
        .tvFocusRing(source, shape)
        .focusable(interactionSource = source)
        // The ring is drawn inside the pane: keep the text out from under it.
        .padding(FocusRingStroke * 2)
}
