package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How many columns at least [minWidth] wide fit side by side in [width], [spacing]
 * apart: never fewer than one, never more than [maxColumns].
 */
fun columnsFor(width: Dp, minWidth: Dp, spacing: Dp = 12.dp, maxColumns: Int = 3): Int {
    if (width == Dp.Infinity) return 1
    return ((width + spacing) / (minWidth + spacing)).toInt().coerceIn(1, maxColumns.coerceAtLeast(1))
}

/**
 * Cards in as many columns as fit ([columnsFor]), each card into whichever column is
 * shortest so far, so cards of different heights still end the columns near one line.
 * For a screen of cards that is not a lazy list — Netcheck's, a Settings section's —
 * where one column on a tablet leaves a strip of cards and two empty margins; a lazy
 * list does the same with `LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(...))`.
 *
 * Reading order follows the cards' order in [content]; the layout does not reorder
 * them. With room for one column it is a column, [spacing] between the cards — but a
 * phone layout should not be rebuilt on this: keep the phone's own code behind
 * [WindowLayout.multiColumn], so it does not change by a pixel.
 */
@Composable
fun CardColumns(
    modifier: Modifier = Modifier,
    minColumnWidth: Dp = 320.dp,
    maxColumns: Int = 3,
    spacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val columns = if (constraints.hasBoundedWidth) {
            columnsFor(constraints.maxWidth.toDp(), minColumnWidth, spacing, maxColumns)
        } else 1
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else minColumnWidth.roundToPx()
        val columnWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val heights = IntArray(columns)
        val placed = measurables.map { measurable ->
            val placeable = measurable.measure(Constraints.fixedWidth(columnWidth))
            val column = heights.indices.minBy { heights[it] }
            val y = heights[column]
            heights[column] += placeable.height + gap
            Triple(placeable, column * (columnWidth + gap), y)
        }
        val height = ((heights.maxOrNull() ?: 0) - gap).coerceAtLeast(0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}
