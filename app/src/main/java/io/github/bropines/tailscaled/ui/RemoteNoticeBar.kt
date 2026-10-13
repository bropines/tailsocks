package io.github.bropines.tailscaled.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.bropines.tailscaled.core.RemoteDeviceNotice
import io.github.bropines.tailscaled.core.RemoteDeviceNotices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Remote management, where a managed device says what a paired device makes it say while one
 * of the app's own screens is in front (core/RemoteDevicePrompts.kt): "managed from <name>",
 * a line for every change, a request whose page did not open. A bar at the top of the window,
 * inside the system's bars and a television's safe area, on whatever screen is open — the
 * theme hosts it once for all of them (ui/theme/Theme.kt) — in place of the toast this used
 * to be. It slides in, stays for its time (core/RemoteDeviceNotice.kt) and slides out; a touch
 * or a swipe towards the edge sends it off sooner, and nothing ever has to.
 *
 * It is no stop for a remote and takes no keys, and the screen under it is neither laid out
 * again nor loses the focus when it comes and goes: over a screen it is a layer beside the
 * screen's content, drawn in the same window, so it costs no window to put up — a slow
 * television takes its time over one. On a television it goes to the bottom edge instead when
 * what the remote is on lies under its place at the top.
 *
 * A dialog or a sheet is a window above the screen's and dims everything under it, the bar
 * included. The app's own ones (ui/AppDialog.kt, ui/AppSheet.kt, TvSheetDialog) carry it
 * themselves, in a small window over theirs at the same place on the screen, and the screen's
 * own stands back while one of them is open. Under any other window — a raw dialog of the
 * admin console, a page that opens as a window of its own — the bar is the screen's, dimmed
 * or hidden with it.
 */

// ─── The bar ─────────────────────────────────────────────────────────────────────────────────

/**
 * The bar itself: the feature's glyph, the managing device's name, and under it what it does.
 * A banner of the remote pages' kind (ui/RemoteManagePage.kt) that floats: the calm container
 * and the corners of a card on the background. Read out as one sentence, politely — it never
 * interrupts what a screen reader is saying.
 */
@Composable
internal fun RemoteNoticeBar(notice: RemoteDeviceNotice, modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        shadowElevation = 6.dp,
        // A dark preset's container may be a shade off its background, and black shows no shadow.
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = notice.spoken
            dismiss { onDismiss(); true }
        }
    ) {
        Row(
            modifier = Modifier.padding(start = BarStart, end = BarEnd, top = BarEdge, bottom = BarEdge),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The feature's glyph in the accent: what tells the bar from a card of the screen under it.
            Box(
                modifier = Modifier.size(GlyphDisc).background(scheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SettingsRemote, null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(GlyphGap))
            Column(Modifier.weight(1f)) {
                Text(text = notice.from, style = nameStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = notice.text, style = lineStyle(), maxLines = LINE_ROWS, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The managing device's name: the bar's first line, and the one that stands out. */
@Composable
private fun nameStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)

/** What it does: as many as [LINE_ROWS] rows under the name. */
@Composable
private fun lineStyle(): TextStyle = MaterialTheme.typography.bodyMedium

private const val LINE_ROWS = 2
private val BarStart = 12.dp
private val BarEnd = 16.dp
private val BarEdge = 10.dp
private val GlyphDisc = 36.dp
private val GlyphGap = 12.dp

/**
 * How tall the bar is for [notice] at [width], in pixels, before it is drawn: its two texts
 * measured the way the bar lays them out. For telling what the bar would cover.
 */
@Composable
private fun barHeight(notice: RemoteDeviceNotice, width: Int): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val name = nameStyle()
    val line = lineStyle()
    return remember(notice.id, width, density, name, line) {
        with(density) {
            val texts = Constraints(maxWidth = (width - (BarStart + GlyphDisc + GlyphGap + BarEnd).roundToPx()).coerceAtLeast(0))
            val rows = measurer.measure(notice.from, name, TextOverflow.Ellipsis, maxLines = 1, constraints = texts).size.height +
                measurer.measure(notice.text, line, TextOverflow.Ellipsis, maxLines = LINE_ROWS, constraints = texts).size.height
            maxOf(rows, GlyphDisc.roundToPx()) + BarEdge.roundToPx() * 2
        }
    }
}

/** No wider than this: a line of a hundred characters still fits in two. */
internal val RemoteNoticeMaxWidth = 480.dp

/** A handheld's margin from the edges of its window; a television's is its safe area. */
internal val RemoteNoticeGutter = 12.dp

/** Room kept around the bar for its shadow; it is also a handheld's gap under the status bar. */
internal val RemoteNoticeRoom = 8.dp

// ─── Where it goes ───────────────────────────────────────────────────────────────────────────

internal enum class NoticeEdge { TOP, BOTTOM }

/** What is kept clear along each edge of a window, in pixels. */
internal data class NoticeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** The part of the screen the bar keeps to: the [window] less the system's [bars] and, on a television, its [safe] area. */
internal fun noticeFrame(window: IntRect, bars: NoticeInsets, safe: NoticeInsets = NoticeInsets()): IntRect = IntRect(
    left = window.left + bars.left + safe.left,
    top = window.top + bars.top + safe.top,
    right = window.right - bars.right - safe.right,
    bottom = window.bottom - bars.bottom - safe.bottom,
)

/** How wide the bar is in a [frame] this wide: all of it but the [gutter]s, up to [max]. */
internal fun noticeWidth(frame: Int, max: Int, gutter: Int): Int = minOf(max, frame - 2 * gutter).coerceAtLeast(0)

/** Where a bar of [size] goes in [frame]: in the middle of its [edge], [gap] off it. */
internal fun noticePosition(frame: IntRect, size: IntSize, edge: NoticeEdge, gap: Int = 0): IntOffset = IntOffset(
    x = frame.left + (frame.width - size.width) / 2,
    y = when (edge) {
        NoticeEdge.TOP -> frame.top + gap
        NoticeEdge.BOTTOM -> frame.bottom - gap - size.height
    },
)

/**
 * The edge for a television's bar: the top, unless what the remote is on ([focused]) lies
 * under the bar's place there and not under its place at the bottom. A pane that runs the
 * height of the screen is under both, and the bar keeps to the top. [size] is the bar's own.
 */
internal fun noticeEdge(focused: IntRect?, frame: IntRect, size: IntSize): NoticeEdge {
    if (focused == null || focused.isEmpty) return NoticeEdge.TOP
    fun covered(edge: NoticeEdge) = IntRect(noticePosition(frame, size, edge), size).overlaps(focused)
    return if (covered(NoticeEdge.TOP) && !covered(NoticeEdge.BOTTOM)) NoticeEdge.BOTTOM else NoticeEdge.TOP
}

// ─── The host ────────────────────────────────────────────────────────────────────────────────

/**
 * What the windows of one screen share about the bar: whose frame it is placed in, and which
 * of them draws it. The theme makes one for every screen and hands it down, sheets and
 * dialogs included.
 */
@Stable
internal class RemoteNoticeSlot {
    /** The screen's own view: its window's frame is where the bar goes, whichever window draws it. */
    var screen: View? = null

    /** The windows open over the screen that draw the bar themselves, the newest last. */
    val windows = mutableStateListOf<Any>()
}

/** The screen's slot; null where no theme hosts the bar, and nothing is drawn. */
internal val LocalRemoteNoticeSlot = staticCompositionLocalOf<RemoteNoticeSlot?> { null }

/**
 * The bar of a screen: for the theme, once per screen, beside its content — and so in the place
 * where what is beside the content is laid over it, which is the root of a screen. It adds
 * nothing to the layout until there is something to say; then a layer the size of the window,
 * which takes no touch but the bar's own.
 */
@Composable
fun RemoteNoticeHost() = NoticeHost(overScreen = false)

/**
 * The bar of a window that opens over a screen — a dialog, a sheet — called from inside it:
 * while it is open, the bar is this window's and not the dimmed screen's. A point of no size
 * in the window's layout, and that only while the bar is up.
 */
@Composable
fun RemoteNoticeWindowHost() = NoticeHost(overScreen = true)

@Composable
private fun NoticeHost(overScreen: Boolean) {
    // A render has no board worth reading and no window to put a bar in.
    if (LocalInspectionMode.current) return
    val slot = LocalRemoteNoticeSlot.current ?: return
    val view = LocalView.current
    val token = remember { Any() }
    DisposableEffect(slot, view, overScreen) {
        if (overScreen) slot.windows.add(token) else slot.screen = view
        onDispose {
            if (overScreen) slot.windows.remove(token) else if (slot.screen === view) slot.screen = null
        }
    }

    val board = RemoteDeviceNotices.board
    val posted by board.current.collectAsState()
    // Its time is the board's, and whoever draws the line takes it down when that is over. Read
    // anew at every turn: the time starts over as the line is first drawn.
    LaunchedEffect(posted?.id) {
        val id = posted?.id ?: return@LaunchedEffect
        while (true) {
            val notice = board.current.value?.takeIf { it.id == id } ?: return@LaunchedEffect
            val left = board.remaining(notice)
            if (left <= 0) break
            delay(left)
        }
        board.expire()
    }

    // The newest window over the screen draws it; the screen does when there is none.
    val mine = if (overScreen) slot.windows.lastOrNull() === token else slot.windows.isEmpty()
    val live = posted?.takeIf { mine && board.remaining(it) > 0 }
    // The line last drawn stays for the bar to slide out with.
    val last = remember { arrayOfNulls<RemoteDeviceNotice>(1) }
    if (live != null) last[0] = live
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = live != null
    val shown = last[0]
    if (shown == null || (!visible.currentState && !visible.targetState)) return

    val tv = isTv
    val density = LocalDensity.current
    val safe = if (tv) LocalTvSafeArea.current.insets(density, LocalLayoutDirection.current) else NoticeInsets()
    val frame = screenFrame(slot.screen ?: view, safe) ?: return
    val room = with(density) { RemoteNoticeRoom.roundToPx() }
    val width = with(density) {
        noticeWidth(frame.width, RemoteNoticeMaxWidth.roundToPx(), if (tv) 0 else RemoteNoticeGutter.roundToPx())
    }
    // Decided as a line arrives, by where the remote is then: a bar that is up does not jump.
    val edge = if (!tv) NoticeEdge.TOP else {
        val bar = IntSize(width, barHeight(shown, width))
        remember(shown.id) { noticeEdge(view.focusedOnScreen(), frame, bar) }
    }
    // Where the bar, with the room for its shadow around it, goes on the screen, asked as it is
    // laid out: the frame is the window's of that moment. A television's stands on the safe
    // area's edge; a handheld's keeps the shadow's room off the system's bars.
    val place = remember(slot, view, safe, edge, tv, room) {
        { size: IntSize -> screenFrame(slot.screen ?: view, safe)?.let { noticePosition(it, size, edge, gap = if (tv) -room else 0) } }
    }

    val bar = @Composable {
        // Through a frame where it is drawn: the line is in front of a person from here.
        LaunchedEffect(shown.id) {
            withFrameNanos { }
            board.drawn(shown.id)
        }
        val towards: (Int) -> Int = { if (edge == NoticeEdge.TOP) -it else it }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(tween(220, easing = LinearOutSlowInEasing), towards) + fadeIn(tween(150)),
            exit = slideOutVertically(tween(180, easing = FastOutLinearInEasing), towards) + fadeOut(tween(150)),
        ) {
            val dismiss = { board.dismiss(shown.id) }
            Box(Modifier.padding(RemoteNoticeRoom)) {
                RemoteNoticeBar(
                    notice = shown,
                    modifier = Modifier.width(with(density) { width.toDp() }).wavedAway(shown.id, edge, dismiss),
                    onDismiss = dismiss,
                )
            }
        }
    }
    if (overScreen) OverWindow(place, view, bar) else OverScreen(place, bar)
}

/**
 * The bar over a screen, in the screen's own window: a layer the size of what it is laid out
 * in, with the bar where [place] has it on the screen. Nothing in it but the bar takes a
 * touch, and nothing in it is a stop for a remote.
 */
@Composable
private fun OverScreen(place: (IntSize) -> IntOffset?, bar: @Composable () -> Unit) {
    var corner by remember { mutableStateOf(IntOffset.Zero) }
    Layout(content = bar, modifier = Modifier.onGloballyPositioned { corner = it.positionOnScreen().round() }) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable?.width ?: 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable?.height ?: 0
        layout(width, height) {
            if (placeable != null) place(IntSize(placeable.width, placeable.height))?.let { placeable.place(it - corner) }
        }
    }
}

/**
 * The bar over a window that is open over the screen: a window of its own above that one,
 * which takes no focus, no keys and no touch but the bar's, so the dialog under it goes on as
 * it was. Hung on a point of no size in the window's layout.
 */
@Composable
private fun OverWindow(place: (IntSize) -> IntOffset?, view: View, bar: @Composable () -> Unit) {
    val anchor = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val placement = remember(view, place) { BarPlacement(place, { anchor[0] }, view) }
    Box(Modifier.onGloballyPositioned { anchor[0] = it }) {
        Popup(popupPositionProvider = placement, properties = BarWindow, content = bar)
    }
}

private val BarWindow = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)

/**
 * Puts the bar's window where it is wanted on the screen. A popup is placed in the coordinates
 * of its anchor, which are a window's or the screen's by where it is hosted; the place wanted
 * is on the screen, so it is given as a distance from the anchor, which is known in both.
 */
private class BarPlacement(
    private val place: (IntSize) -> IntOffset?,
    private val anchor: () -> LayoutCoordinates?,
    private val view: View,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val wanted = place(popupContentSize) ?: return anchorBounds.topLeft
        val anchorOnScreen = anchor()?.takeIf { it.isAttached }?.positionOnScreen()?.round()
        if (anchorOnScreen != null) return anchorBounds.topLeft + (wanted - anchorOnScreen)
        // Not placed yet: the window's own corner, which is where a popup's coordinates start.
        val corner = IntArray(2)
        view.rootView.getLocationOnScreen(corner)
        return wanted - IntOffset(corner[0], corner[1])
    }
}

/**
 * The frame of the window [screen] is in, on the screen, less the system's bars and [safe]:
 * the same for a screen laid out under the bars and for one laid out between them. Null while
 * the view is in no window.
 */
private fun screenFrame(screen: View, safe: NoticeInsets): IntRect? {
    val root = screen.rootView?.takeIf { it.isAttachedToWindow } ?: return null
    val corner = IntArray(2)
    root.getLocationOnScreen(corner)
    val window = IntRect(corner[0], corner[1], corner[0] + root.width, corner[1] + root.height)
    val bars = ViewCompat.getRootWindowInsets(root)
        ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    return noticeFrame(window, NoticeInsets(bars?.left ?: 0, bars?.top ?: 0, bars?.right ?: 0, bars?.bottom ?: 0), safe)
}

/** What has the focus in this view's window, on the screen: for Compose, the element itself and not the whole view. */
private fun View.focusedOnScreen(): IntRect? {
    val focused = rootView?.findFocus() ?: return null
    val rect = android.graphics.Rect()
    focused.getFocusedRect(rect)
    val corner = IntArray(2)
    focused.getLocationOnScreen(corner)
    return IntRect(rect.left + corner[0], rect.top + corner[1], rect.right + corner[0], rect.bottom + corner[1])
}

private fun PaddingValues.insets(density: Density, direction: LayoutDirection): NoticeInsets = with(density) {
    NoticeInsets(
        left = calculateLeftPadding(direction).roundToPx(),
        top = calculateTopPadding().roundToPx(),
        right = calculateRightPadding(direction).roundToPx(),
        bottom = calculateBottomPadding().roundToPx(),
    )
}

/**
 * Sends the bar off at a touch, or at a swipe towards the edge it came from, which it follows.
 * Not a click: a click would make it a stop for a remote, and it is never one.
 */
@Composable
private fun Modifier.wavedAway(key: Any, edge: NoticeEdge, onDismiss: () -> Unit): Modifier {
    val pulled = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    return offset { IntOffset(0, pulled.value.roundToInt()) }
        .pointerInput(key) { detectTapGestures { dismiss() } }
        .pointerInput(key, edge) {
            detectVerticalDragGestures(
                onDragEnd = { if (abs(pulled.value) > size.height / 3) dismiss() else scope.launch { pulled.animateTo(0f) } },
                onDragCancel = { scope.launch { pulled.animateTo(0f) } },
            ) { _, dragged ->
                val to = pulled.value + dragged
                scope.launch { pulled.snapTo(if (edge == NoticeEdge.TOP) to.coerceAtMost(0f) else to.coerceAtLeast(0f)) }
            }
        }
}
