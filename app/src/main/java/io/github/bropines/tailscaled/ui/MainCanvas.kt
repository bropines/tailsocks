package io.github.bropines.tailscaled.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.MainItem
import io.github.bropines.tailscaled.core.MainLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The main screen as one flow (core/MainLayout.kt): blocks across the width, tiles in a grid
 * between them. It is the screen a phone held upright shows, and — in every window a finger
 * can reach — the canvas it turns into when it is being arranged: the same cells, which then
 * move under a finger, make room for one another and carry the buttons that take one off or
 * change a tile's size.
 *
 * A layout of its own and not a lazy grid: there are a couple of dozen cells at most, a row of
 * tiles has to be as tall as its tallest card (a lazy grid measures each cell alone), and the
 * screen it replaces was a plain column whose spacing an update must not move by a pixel.
 */

/** Between two tiles, across and down: the grid's gap since the first version. */
internal val MainTileGap = 16.dp

/**
 * The room between two things that follow one another on the main screen: tiles keep the
 * grid's gap, the status and the menu stand apart, and the summary sits close under whatever
 * it follows — it reads as the card's last line.
 */
internal fun mainGap(above: MainItem, below: MainItem): Dp = when {
    above.isTile && below.isTile -> MainTileGap
    above.isTile != below.isTile -> 32.dp
    below == MainItem.SUMMARY -> 12.dp
    else -> 16.dp
}

/**
 * Something that is not the person's to arrange and still has a place in the flow: the
 * banners above the status card, the pill of the devices this one manages. It stands above
 * the row that starts with [before], and is gone while the screen is being arranged. With
 * nothing to draw it takes no room; [replacesGap] is for what was always drawn in the gap
 * between two parts of the screen and brings its own padding.
 */
internal class MainCanvasInsert(
    val before: MainItem,
    val replacesGap: Boolean = false,
    val content: @Composable () -> Unit,
)

/** How long a finger rests on a cell of the canvas before the cell follows it. */
private const val EDIT_HOLD_MS = 160L

/** Where a cell was last drawn, and the animation that takes it to where it belongs now. */
private class CellPosition {
    var last: IntOffset? = null
    var animation: Animatable<IntOffset, *>? = null
}

/** What the canvas knows between two frames: where every cell belongs, and the one being dragged. */
@Stable
private class MainCanvasState {
    var dragging by mutableStateOf<MainItem?>(null)
    /** The finger, in the canvas' own coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
    /** From the dragged cell's corner to the finger. */
    var grab = Offset.Zero
    /** The dragged cell's place in the flow: drawn as an outline while the cell is away from it. */
    var ghost by mutableStateOf<IntRect?>(null)

    val slots = HashMap<MainItem, IntRect>()
    val positions = HashMap<MainItem, CellPosition>()
    var coordinates: LayoutCoordinates? = null
    /** The move asked for and not laid out yet: the same one is not asked for twice from stale slots. */
    var pending: Pair<MainItem, MainItem>? = null

    fun itemAt(point: Offset): MainItem? {
        val p = point.round()
        return slots.entries.firstOrNull { it.value.contains(p) }?.key
    }

    /**
     * Whether [point] is on one of [item]'s corner buttons, [target] pixels square: a tile's
     * two top corners (remove at its start, if it can be removed, and size at its end), a
     * block's top end (remove). A press there is the button's — taken for the start of a
     * drag, the moves it consumed cancelled the button's tap.
     */
    fun onBadge(item: MainItem, point: Offset, target: Int, rtl: Boolean): Boolean {
        val slot = slots[item] ?: return false
        val p = point.round()
        if (p.y - slot.top > target) return false
        val fromLeft = p.x - slot.left <= target
        val fromRight = slot.right - p.x <= target
        val atStart = if (rtl) fromRight else fromLeft
        val atEnd = if (rtl) fromLeft else fromRight
        return if (item.isTile) atEnd || (atStart && item.removable) else atEnd && item.removable
    }

    /**
     * The cell the dragged one has crossed, if any. Two cells of one size swap as soon as the
     * centre of one is over the other. Cells of different sizes would swap back at once — the
     * small one is still inside the large one after the move — so there the centre has to pass
     * the middle of the other, in the direction of travel.
     */
    fun crossed(order: List<MainItem>): MainItem? {
        val item = dragging ?: return null
        val own = slots[item] ?: return null
        val centre = (pointer - grab + Offset(own.width / 2f, own.height / 2f)).round()
        val (target, rect) = slots.entries.firstOrNull { it.key != item && it.value.contains(centre) } ?: return null
        if (rect.size != own.size) {
            val later = order.indexOf(target) > order.indexOf(item)
            if (later && centre.y < rect.center.y || !later && centre.y > rect.center.y) return null
        }
        return target.takeIf { pending != item to it }
    }
}

/**
 * [cells] laid out top to bottom in [columns]; [cell] draws each. Tiles are at least
 * [cardHeight] tall and a row is as tall as its tallest card.
 *
 * A finger held on a cell picks it up: [onLongPress] is asked first while the screen is not
 * being arranged, and its yes is what starts [editing] — the same press then drags the cell.
 * While [editing], cells take no input of their own; the canvas reports what the person does
 * with them and the caller answers with a new list. [scroll] is the page the canvas stands in,
 * moved when a cell is dragged to its edge.
 */
@Composable
internal fun MainCanvas(
    cells: List<MainLayout.Placed>,
    columns: Int,
    cardHeight: Dp,
    editing: Boolean,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    /** Off where nothing can be held: a television. */
    gestures: Boolean = true,
    inserts: List<MainCanvasInsert> = emptyList(),
    title: @Composable (MainItem) -> String = { "" },
    onLongPress: (MainItem) -> Boolean = { false },
    onMove: (item: MainItem, target: MainItem) -> Unit = { _, _ -> },
    onStep: (item: MainItem, by: Int) -> Unit = { _, _ -> },
    onRemove: (MainItem) -> Unit = {},
    onSetWide: (MainItem, Boolean) -> Unit = { _, _ -> },
    cell: @Composable (MainLayout.Placed) -> Unit,
) {
    val state = remember { MainCanvasState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val layoutRtl by rememberUpdatedState(androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl)
    val density = LocalDensity.current
    val order = cells.map { it.item }
    val currentOrder by rememberUpdatedState(order)
    val currentEditing by rememberUpdatedState(editing)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentMove by rememberUpdatedState(onMove)

    fun reorder() {
        val target = state.crossed(currentOrder) ?: return
        val item = state.dragging ?: return
        state.pending = item to target
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        currentMove(item, target)
    }

    // A cell held at the top or bottom edge of what is visible scrolls the page under it.
    LaunchedEffect(state.dragging != null) {
        if (state.dragging == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val fastest = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val coordinates = state.coordinates?.takeIf { it.isAttached } ?: continue
            val visible = coordinates.boundsInWindow()
            val y = coordinates.positionInWindow().y + state.pointer.y
            val push = when {
                y < visible.top + edge -> -((visible.top + edge - y) / edge).coerceAtMost(1f)
                y > visible.bottom - edge -> ((y - (visible.bottom - edge)) / edge).coerceAtMost(1f)
                else -> 0f
            }
            if (push == 0f) continue
            val moved = scroll.scrollBy(push * fastest)
            if (moved != 0f) {
                // The canvas moved under a finger that did not: the finger is further down it now.
                state.pointer += Offset(0f, moved)
                reorder()
            }
        }
    }

    // A cell that turns up while the screen is being arranged was just added and grows into its
    // place; the cells the canvas opens with are simply there.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val ghostColor = MaterialTheme.colorScheme.outline
    Layout(
        content = {
            for (placed in cells) {
                key(placed.item) {
                    CanvasCell(
                        placed = placed,
                        editing = editing,
                        dragged = state.dragging == placed.item,
                        growIn = editing && opened,
                        title = title(placed.item),
                        // A press that became a drag ends on the button it started on: not a tap on it.
                        onRemove = { if (state.dragging == null) onRemove(placed.item) },
                        onSetWide = { if (state.dragging == null) onSetWide(placed.item, it) },
                        onStep = { onStep(placed.item, it) },
                    ) { cell(placed) }
                }
            }
            for (insert in inserts) {
                key(insert.before, insert.replacesGap) {
                    Box(Modifier.layoutId(insert)) { if (!editing) insert.content() }
                }
            }
        },
        modifier = modifier
            .onGloballyPositioned { state.coordinates = it }
            .drawBehind {
                val ghost = state.ghost ?: return@drawBehind
                drawRoundRect(
                    color = ghostColor,
                    topLeft = ghost.topLeft.toOffset(),
                    size = ghost.size.toSize(),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                    alpha = 0.6f,
                )
            }
            .then(
                if (!gestures) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val item = state.itemAt(down.position) ?: return@awaitEachGesture
                        if (currentEditing && state.onBadge(item, down.position, EditBadgeTarget.roundToPx(), layoutRtl)) return@awaitEachGesture
                        // Arranging, a short rest is enough — a swipe that scrolls the page has
                        // moved by then; before that it is the system's long press.
                        val hold = if (currentEditing) EDIT_HOLD_MS else viewConfiguration.longPressTimeoutMillis
                        val gaveUp = withTimeoutOrNull(hold) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed || change.isConsumed) break
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            }
                        }
                        if (gaveUp != null) return@awaitEachGesture
                        if (!currentEditing && !currentLongPress(item)) return@awaitEachGesture
                        val slot = state.slots[item] ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.grab = down.position - slot.topLeft.toOffset()
                        state.pointer = down.position
                        state.pending = null
                        state.dragging = item
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            state.pointer = change.position
                            change.consume()
                            reorder()
                        }
                        state.dragging = null
                        state.ghost = null
                    }
                }
            ),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = MainTileGap.roundToPx()
        val count = columns.coerceAtLeast(1)
        // The left edge of column i; the widths differ by a pixel where the row does not divide.
        fun columnStart(i: Int) = (i.toLong() * (width + gap) / count).toInt()
        val byId = measurables.associateBy { it.layoutId }

        val rtl = layoutDirection == LayoutDirection.Rtl
        class Laid(val item: MainItem?, val placeable: Placeable, x: Int, y: Int) {
            // Mirrored here and placed as it stands: a finger is looked up in the same coordinates.
            val at = IntOffset(if (rtl) width - x - placeable.width else x, y)
        }
        val laid = ArrayList<Laid>(measurables.size)
        var y = 0
        var above: MainItem? = null
        for (row in MainLayout.rows(cells, count)) {
            val first = row.first().item
            val space = above?.let { mainGap(it, first).roundToPx() } ?: 0
            val inserted = inserts.filter { it.before == first }.mapNotNull { insert ->
                byId[insert]?.measure(Constraints.fixedWidth(width))?.takeIf { it.height > 0 }?.let { insert to it }
            }
            if (inserted.none { it.first.replacesGap }) y += space
            for ((_, placeable) in inserted) {
                laid += Laid(null, placeable, 0, y)
                y += placeable.height
            }
            if (row.size == 1 && row[0].full) {
                val placeable = byId[first]?.measure(Constraints.fixedWidth(width)) ?: continue
                laid += Laid(first, placeable, 0, y)
                y += placeable.height
            } else {
                val widths = row.indices.map { columnStart(it + 1) - gap - columnStart(it) }
                val cards = row.map { byId[it.item] }
                val height = maxOf(
                    cardHeight.roundToPx(),
                    cards.indices.maxOf { cards[it]?.minIntrinsicHeight(widths[it]) ?: 0 },
                )
                cards.forEachIndexed { i, card ->
                    card ?: return@forEachIndexed
                    laid += Laid(row[i].item, card.measure(Constraints.fixed(widths[i], height)), columnStart(i), y)
                }
                y += height
            }
            above = row.last().item
        }

        state.slots.clear()
        for (l in laid) l.item?.let { state.slots[it] = IntRect(l.at, IntSize(l.placeable.width, l.placeable.height)) }
        state.positions.keys.retainAll(state.slots.keys)
        state.pending = null
        state.ghost = state.dragging?.let { state.slots[it] }

        layout(width, y) {
            for (l in laid) {
                val item = l.item
                if (item == null) {
                    l.placeable.place(l.at)
                    continue
                }
                val position = state.positions.getOrPut(item) { CellPosition() }
                if (state.dragging == item) {
                    // Under the finger, over everything else.
                    val at = (state.pointer - state.grab).round()
                    position.last = at
                    position.animation = null
                    l.placeable.place(at, zIndex = 1f)
                } else {
                    l.placeable.place(position.settle(l.at, animate = editing, scope = scope))
                }
            }
        }
    }
}

/**
 * Where to draw a cell that belongs at [target] now: there, or on its way there from where it
 * was last drawn. Only while the screen is being arranged — the screen in use never slides.
 */
private fun CellPosition.settle(target: IntOffset, animate: Boolean, scope: CoroutineScope): IntOffset {
    val from = last
    if (!animate || from == null || (animation == null && from == target)) {
        last = target
        animation = null
        return target
    }
    val running = animation ?: Animatable(from, IntOffset.VectorConverter).also { animation = it }
    if (running.targetValue != target) {
        scope.launch {
            running.animateTo(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)))
        }
    }
    return running.value.also { last = it }
}

/**
 * One cell of the canvas: what it holds, and over it — while the screen is being arranged —
 * a cover that takes the touches meant for dragging, an outline, and the buttons of its
 * corners. The cell reads as itself and does nothing.
 */
@Composable
private fun CanvasCell(
    placed: MainLayout.Placed,
    editing: Boolean,
    dragged: Boolean,
    growIn: Boolean,
    title: String,
    onRemove: () -> Unit,
    onSetWide: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val item = placed.item
    val shape = if (item == MainItem.STATUS) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large
    val lift by animateFloatAsState(if (dragged) 1f else 0f, label = "lift")
    val appear = remember { Animatable(if (growIn) 0.7f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
    val earlier = stringResource(R.string.main_layout_move_earlier)
    val later = stringResource(R.string.main_layout_move_later)
    val removeLabel = stringResource(R.string.main_layout_remove_cd, title)
    val sizeLabel = stringResource(if (placed.wide) R.string.main_layout_narrow_cd else R.string.main_layout_wide_cd, title)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .layoutId(item)
            .graphicsLayer {
                val scale = appear.value * (1f + 0.04f * lift)
                scaleX = scale
                scaleY = scale
                alpha = ((appear.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
                shadowElevation = lift * 12.dp.toPx()
                this.shape = shape
            }
            // A line of pills is too low to hold the buttons of its corners.
            .then(if (editing && !item.isTile) Modifier.heightIn(min = EditBadgeTarget) else Modifier),
        contentAlignment = Alignment.Center,
        // A tile is given its size by the row; a block takes its own height.
        propagateMinConstraints = item.isTile,
    ) {
        Box(
            if (editing) Modifier.fillMaxWidth().clearAndSetSemantics { } else Modifier.fillMaxWidth(),
            propagateMinConstraints = true,
        ) { content() }
        if (editing) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(if (dragged) 2.dp else 1.dp, if (dragged) colors.primary else colors.outlineVariant, shape)
                    .semantics {
                        contentDescription = title
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(earlier) { onStep(-1); true },
                            CustomAccessibilityAction(later) { onStep(+1); true },
                            CustomAccessibilityAction(removeLabel) { onRemove(); true }.takeIf { item.removable },
                        )
                    }
                    // Takes the touches, so that nothing under it does; the canvas reads them from above.
                    .pointerInput(Unit) { }
            )
        }
        EditBadge(
            visible = editing && item.removable,
            icon = Icons.Default.Remove,
            label = removeLabel,
            container = colors.errorContainer,
            content = colors.onErrorContainer,
            // A tile has two buttons and this one leads; a block's leading corner holds its icon.
            alignment = if (item.isTile) Alignment.TopStart else Alignment.TopEnd,
            onClick = onRemove,
        )
        EditBadge(
            visible = editing && item.isTile,
            icon = if (placed.wide) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
            label = sizeLabel,
            container = colors.secondaryContainer,
            content = colors.onSecondaryContainer,
            alignment = Alignment.TopEnd,
            onClick = { onSetWide(!placed.wide) },
        )
    }
}

/** What a finger needs to hit a corner button. */
private val EditBadgeTarget = 44.dp

/** A round button in a corner of a cell: small to look at, a full touch target to press. */
@Composable
private fun BoxScope.EditBadge(
    visible: Boolean,
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        // Its own size, in its corner: a tile's cell hands its children the tile's size as
        // their least, and a button stretched over the whole tile lay over the other one —
        // every press on a tile's corner went to the size button, never to Remove.
        modifier = Modifier.align(alignment).wrapContentSize(alignment),
    ) {
        Box(
            Modifier
                .size(EditBadgeTarget)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = onClick,
                )
                .semantics { contentDescription = label },
            contentAlignment = alignment,
        ) {
            Surface(shape = CircleShape, color = container, contentColor = content, shadowElevation = 2.dp, modifier = Modifier.padding(6.dp).size(24.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

/**
 * A long press anywhere on what this is set on, for the windows that show the menu beside the
 * status and so have no canvas to press on: it only reports the press. It watches from above
 * and takes nothing, so the cards under it keep their taps — the caller makes sure that the
 * tap which ends this very press does nothing.
 */
internal fun Modifier.onLongPressAnywhere(enabled: Boolean, onLongPress: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val gaveUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            }
            if (gaveUp == null) onLongPress()
        }
    }
