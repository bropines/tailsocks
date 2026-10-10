package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The horizontal padding a [SideBySide] column keeps: the window's margin on its outer
 * side, half of it towards the other column, so the gap between the two is one margin.
 * On a foldable open like a book each half is a phone's width, and keeps a phone's 16dp.
 */
@Immutable
internal class ColumnPadding(val start: Dp, val end: Dp) {
    /** This padding with [top] and [bottom] added, for a lazy list's contentPadding. */
    fun with(top: Dp = 0.dp, bottom: Dp = 0.dp) = PaddingValues(start = start, top = top, end = end, bottom = bottom)
}

/**
 * Two columns of one screen side by side, for a screen that is not a list with a detail
 * but has two things to show at once — TailCat's own identity beside its connections,
 * Netcheck's verdict beside the relays, DNS's tools beside its configuration — where a
 * tall single column would leave half a tablet empty.
 *
 * The start column is [startWidth] wide or, with null, half of the window; the end
 * column takes the rest. By default the start is a column of cards about a phone wide on
 * a large window (440dp: room for a card's row of buttons that a 400dp list pane cuts
 * off) and half of an expanded one, where a fixed column would leave the end one too
 * narrow for its own cards. A foldable open like a book puts each on its own half with
 * the hinge between them, whatever [startWidth] says. Each column is handed the
 * [ColumnPadding] its content keeps; the columns draw no surface of their own and scroll
 * on their own.
 *
 * Never on a phone: the screens that use it keep their phone layout behind
 * [WindowLayout.isPhone], and this is for what is wider.
 */
@Composable
internal fun SideBySide(
    window: WindowLayout,
    modifier: Modifier = Modifier,
    startWidth: Dp? = if (window.widthClass >= WindowWidthClass.LARGE) 440.dp else null,
    start: @Composable (ColumnPadding) -> Unit,
    end: @Composable (ColumnPadding) -> Unit,
) {
    val fold = window.fold as? Fold.Vertical
    val outer = if (fold != null) 16.dp else window.margin
    val inner = outer / 2
    Row(modifier) {
        val startColumn = when {
            fold != null -> Modifier.width(fold.start)
            startWidth != null -> Modifier.width(startWidth)
            else -> Modifier.weight(1f)
        }
        Box(startColumn.fillMaxHeight()) { start(ColumnPadding(outer, inner)) }
        if (fold != null) Spacer(Modifier.width(fold.end - fold.start))
        Box(Modifier.weight(1f).fillMaxHeight()) { end(ColumnPadding(inner, outer)) }
    }
}
