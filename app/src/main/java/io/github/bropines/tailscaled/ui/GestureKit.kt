package io.github.bropines.tailscaled.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * The app's two gestures beyond tapping (Settings → Appearance → Gestures, core/Gestures.kt):
 * a swipe on a row or card that does one thing and springs back, and the back gesture inside
 * a screen that previews where it leads.
 */

/** One direction of a [SwipeActionBox]: what it says under the finger, and what it does. */
class SwipeAction(
    val label: String,
    val icon: ImageVector,
    /** The colour behind the row as the finger drags; null takes the theme's secondary container. */
    val color: Color? = null,
    val onAction: () -> Unit,
)

/**
 * How a released gesture settles: Seenly's FlightSpring damping with a softer stiffness —
 * at 420 the row snapped home faster than the eye followed it.
 */
private val GestureSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 260f)

/**
 * For the screenshot tests only: rows drawn as if dragged this fraction of their width (negative:
 * towards the start). A real drag never reads it.
 */
internal val LocalSwipePreview = androidx.compose.runtime.staticCompositionLocalOf { 0f }

/** How far the content follows the finger past the point that arms the action: a little, heavily damped. */
private const val OVERDRAG = 0.35f

/**
 * [content] that can be swiped sideways to do [toEnd] or [toStart] (core/Gestures.kt names the
 * directions). The content follows the finger over a coloured panel naming the action; past
 * about a third of its width the panel's icon grows and the phone ticks, and letting go there
 * does the action. Either way the content springs back into place with the speed it was let go
 * at — the row is not dismissed: what the action changes (a hidden file leaving its list, say)
 * the list animates by itself.
 *
 * A direction without an action is not a swipe at all: the drag is not claimed, so it goes to
 * whatever is underneath (a pager's tabs, say), and a drag that started the other way stops at
 * zero. With neither, or on a television, [content] is drawn as it is.
 */
@Composable
fun SwipeActionBox(
    toEnd: SwipeAction?,
    toStart: SwipeAction?,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if ((toEnd == null && toStart == null) || isTv) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    val currentToEnd by rememberUpdatedState(toEnd)
    val currentToStart by rememberUpdatedState(toStart)
    // A positive offset moves the content rightwards; in a right-to-left layout that is towards the start.
    fun actionFor(value: Float): SwipeAction? = when {
        value == 0f -> null
        (value > 0f) != rtl -> currentToEnd
        else -> currentToStart
    }
    val minArm = with(density) { 72.dp.toPx() }
    fun armAt(): Float = maxOf(width * 0.3f, minArm)

    /** The finger moved [delta] more; the content follows, never past zero into a direction with nothing to do. */
    fun follow(delta: Float) {
        val arm = armAt()
        val raw = offset.value + delta
        // The side the drag started on is the only one it may use: back across zero it stops.
        val next = if (actionFor(raw) == null) 0f else raw
        val limited = if (abs(next) > arm) sign(next) * (arm + (abs(next) - arm) * OVERDRAG) else next
        val nowArmed = actionFor(limited) != null && abs(limited) >= arm
        if (nowArmed != armed) {
            armed = nowArmed
            haptics.performHapticFeedback(if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.GestureEnd)
        }
        scope.launch { offset.snapTo(limited) }
    }

    /** The finger lifted: the armed action fires and the content springs home with the finger's speed. */
    fun release(velocity: Float) {
        val fired = if (armed) actionFor(offset.value) else null
        armed = false
        fired?.onAction?.invoke()
        // Seenly's settle spring: quick, a trace of overshoot, no wobble.
        scope.launch { offset.animateTo(0f, GestureSpring, initialVelocity = velocity) }
    }

    // Which side shows is read in composition, and changes only when the drag crosses zero;
    // how far it has gone is read in the draw phase, so a moving finger recomposes nothing.
    val preview = LocalSwipePreview.current
    fun shown() = offset.value + preview * width
    // A preview has its side before the row has a width (a single frame is all a screenshot gets).
    val side by remember(preview) { derivedStateOf { if (preview != 0f) sign(preview) else sign(shown()) } }
    fun progress() = (abs(shown()) / armAt()).coerceIn(0f, 1f)
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .clip(shape)
    ) {
        val action = actionFor(side)
        if (action != null) {
            val towardsRight = side > 0f
            val base = action.color ?: MaterialTheme.colorScheme.secondaryContainer
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind { drawRect(base.copy(alpha = 0.35f + 0.65f * progress())) },
                contentAlignment = if (towardsRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    Modifier.padding(horizontal = 24.dp).graphicsLayer {
                        val p = progress()
                        val grow = if (armed) 1.12f else 0.88f + 0.12f * p
                        scaleX = grow
                        scaleY = grow
                        alpha = 0.4f + 0.6f * p
                        // Grown from the edge it stands at: from its centre a long label ran off that edge.
                        transformOrigin = TransformOrigin(if (towardsRight) 0f else 1f, 0.5f)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val tint = MaterialTheme.colorScheme.onSecondaryContainer
                    // The icon stands at the edge the row uncovers first, so it shows before the words do.
                    if (towardsRight) Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(action.label, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (!towardsRight) Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(shown().roundToInt(), 0) }
                // Not `draggable`: that takes every sideways drag, so a direction set to "Nothing"
                // still swallowed the gesture (and the Peers tabs could not be swiped over a row).
                // Here a drag is claimed only once it is past the touch slop towards a direction
                // that has an action; otherwise it is left to whatever is underneath.
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var overSlop = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            if (actionFor(over) != null) {
                                change.consume()
                                overSlop = over
                            }
                        } ?: return@awaitEachGesture
                        follow(overSlop)
                        val ended = horizontalDrag(drag.id) { change ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            follow(change.positionChange().x)
                            change.consume()
                        }
                        release(if (ended) tracker.calculateVelocity().x else 0f)
                    }
                }
        ) { content() }
    }
}

/** How far a page has been pulled back by the back gesture, 0 to 1, and from which edge. */
@Stable
class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
        internal set
    /** The gesture started at the left edge of the screen. */
    var fromLeft by mutableStateOf(true)
        internal set
    /** After the finger let go to go back: 0 to 1 as the page finishes its move and fades out. */
    var exit by mutableFloatStateOf(0f)
        internal set
}

/**
 * The back gesture inside a screen — a settings page back to the list, a folder back to its
 * parent — as Android 14's own between screens: while the finger moves, the page shrinks a
 * little and slides with it, so the user sees that going back is about to happen and can
 * change their mind; letting go goes back, cancelling springs the page back.
 *
 * With Settings → Appearance → Gestures → "Back with a preview" off, or before Android 14 where
 * the system sends no progress, it is a plain [BackHandler]. Draw the page with
 * [Modifier.predictiveBackPreview] and the returned state.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Off in Settings, or with system animations off (this platform's reduced motion): a plain back.
    val preview = remember(context) {
        io.github.bropines.tailscaled.core.Gestures.predictiveBack(context) &&
            runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) != 0f
    }
    val currentOnBack by rememberUpdatedState(onBack)
    if (!preview) {
        BackHandler(enabled) { currentOnBack() }
        return state
    }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events ->
        val settle = Animatable(0f)
        try {
            events.collect { e: BackEventCompat ->
                state.fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                state.progress = e.progress
            }
            // The rest of the way, unhurried, so the page leaves in one motion instead of snapping.
            val finish = Animatable(0f)
            val from = state.progress
            finish.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) {
                state.progress = from + (1f - from) * value
                state.exit = value
            }
            currentOnBack()
            state.progress = 0f
            state.exit = 0f
        } catch (e: CancellationException) {
            // Changed their mind: the page springs back from where the finger left it.
            settle.snapTo(state.progress)
            scope.launch {
                settle.animateTo(0f, GestureSpring) {
                    state.progress = value
                }
            }
            throw e
        }
    }
    return state
}

/**
 * Draws a page as [state] says: shrunk by up to 12% (as far as Seenly's viewer goes) and slid
 * towards the edge the gesture left, its corners rounded. All in the layer: nothing is laid out again.
 */
fun Modifier.predictiveBackPreview(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress
    if (p == 0f) return@graphicsLayer
    val scale = 1f - 0.12f * p
    scaleX = scale
    scaleY = scale
    // Let go, it slides the rest of the way off, opaque, as the screen-to-screen back does.
    translationX = (if (state.fromLeft) 1f else -1f) * size.width * (0.06f * p + state.exit)
    transformOrigin = TransformOrigin(if (state.fromLeft) 1f else 0f, 0.5f)
    shape = androidx.compose.foundation.shape.RoundedCornerShape((32 * p).dp)
    clip = true
}
