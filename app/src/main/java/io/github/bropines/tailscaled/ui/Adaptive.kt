package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3's window width classes, the large and extra-large of 2024
 * included. A phone is compact upright; a 7-8" tablet and an upright 11" one
 * are medium; a book-style foldable opened flat and a small tablet on its side
 * are expanded; an 11" tablet on its side (1280dp) is large.
 */
enum class WindowWidthClass(val minWidth: Dp) {
    COMPACT(0.dp), MEDIUM(600.dp), EXPANDED(840.dp), LARGE(1200.dp), EXTRA_LARGE(1600.dp);

    companion object {
        fun of(width: Dp): WindowWidthClass = entries.last { width >= it.minWidth }
    }
}

/**
 * Material 3's window height classes. Compact is a phone on its side (some
 * 400dp); a tablet is medium on its side and expanded upright.
 */
enum class WindowHeightClass(val minHeight: Dp) {
    COMPACT(0.dp), MEDIUM(480.dp), EXPANDED(900.dp);

    companion object {
        fun of(height: Dp): WindowHeightClass = entries.last { height >= it.minHeight }
    }
}

/**
 * The window a screen lays itself out in, and the decisions every screen
 * takes from it in the same way — so that "two panes" or "more than one
 * column" means the same window on every screen.
 *
 * A phone is never laid out differently by any of these: [isPhone] covers it
 * upright and on its side, and every flag below is false there except where a
 * fold says otherwise. That is the guarantee the tablet layouts rest on — a
 * phone keeps the layout it always had.
 */
@Immutable
class WindowLayout(val width: Dp, val height: Dp, val fold: Fold? = null) {
    val widthClass: WindowWidthClass = WindowWidthClass.of(width)
    val heightClass: WindowHeightClass = WindowHeightClass.of(height)

    /** A phone, held either way: compact width, or compact height — a phone on
     *  its side is 891dp wide, as wide as a foldable, and still a phone. */
    val isPhone: Boolean get() = widthClass == WindowWidthClass.COMPACT || heightClass == WindowHeightClass.COMPACT

    /** List and detail side by side, the detail in a pane instead of a sheet:
     *  an expanded window or wider that is not a phone, or a foldable open like
     *  a book, whose halves are the two panes. */
    val listDetail: Boolean get() = fold is Fold.Vertical || (!isPhone && widthClass >= WindowWidthClass.EXPANDED)

    /** Room for cards to stand in more than one column: medium and wider, not a phone. */
    val multiColumn: Boolean get() = !isPhone && widthClass >= WindowWidthClass.MEDIUM

    /** Between the window's edge and the content: Material's 16dp on a phone, 24dp above. */
    val margin: Dp get() = if (isPhone) 16.dp else 24.dp

    /** The list's side of a list-detail layout: 360dp, 400dp from a large window up. */
    val listPaneWidth: Dp get() = if (widthClass >= WindowWidthClass.LARGE) 400.dp else 360.dp

    override fun equals(other: Any?): Boolean =
        other is WindowLayout && other.width == width && other.height == height && other.fold == fold

    override fun hashCode(): Int = (width.hashCode() * 31 + height.hashCode()) * 31 + fold.hashCode()

    override fun toString(): String = "WindowLayout($width x $height, $widthClass/$heightClass, fold=$fold)"
}

/**
 * The window this composition draws in: its whole size, as the size classes
 * are defined on, not what is left under a top bar. In a split screen it is
 * the app's half. The preview renderer reports the preview's device size.
 */
@Composable
fun rememberWindowLayout(): WindowLayout {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val fold = rememberFold()
    return remember(size, density, fold) {
        with(density) { WindowLayout(size.width.toDp(), size.height.toDp(), fold) }
    }
}

/**
 * The widest a column of text and controls should get.
 *
 * Beyond this a settings row has its label on one side of the screen and its
 * switch on the other, with nothing between them — which is what every list
 * and form in the app looked like on a tablet.
 */
val ReadableContentWidth = 720.dp

/**
 * Holds a screen's content to [ReadableContentWidth], centred, once the window
 * is wider than that. On a phone, upright or turned, and on an unfolded
 * foldable the window is narrower and this does nothing at all.
 *
 * Meant for the body of a Scaffold: the top bar stays the width of the window,
 * as it should, and only what is under it is held in. A form or a page of
 * text keeps it; a collection of cards does better in [CardColumns], and a
 * list with a detail in [ListDetailLayout].
 */
@Composable
fun ReadableWidth(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = ReadableContentWidth).fillMaxSize(), content = content)
    }
}

/**
 * [ReadableWidth] for a single element that lives outside a screen's body — a
 * search field or a tab row in the top bar slot, which the body's wrapper does
 * not reach. Without it such a row stays the width of the window while the
 * content under it is held in, and the two no longer line up.
 */
fun Modifier.readableWidth(): Modifier =
    this.fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = ReadableContentWidth)

/**
 * A fold a layout should respect, in dp from the window's own edges.
 *
 * [Vertical] is a book-style foldable held open: two halves side by side, and
 * whatever is drawn across the hinge is either split by it or hidden under it.
 * [Horizontal] is the same device half open like a laptop — the tabletop
 * posture — where the top half faces the user and the bottom lies flat.
 */
sealed interface Fold {
    data class Vertical(val start: Dp, val end: Dp) : Fold
    data class Horizontal(val top: Dp, val bottom: Dp) : Fold
}

/**
 * The renderer cannot report a hinge, so a preview hands one in through this;
 * the app never provides it.
 */
val LocalPreviewFold = staticCompositionLocalOf<Fold?> { null }

/**
 * The fold worth laying out around, if the window has one.
 *
 * Only a fold that actually separates the halves or hides something behind
 * it counts: a foldable opened fully flat has a hinge, but a screen that
 * crosses it reads as one surface and should be laid out as one. Every other
 * device answers null, and so does the preview renderer unless a preview says
 * otherwise through [LocalPreviewFold].
 */
@Composable
fun rememberFold(): Fold? {
    if (LocalInspectionMode.current) return LocalPreviewFold.current
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val density = LocalDensity.current
    val hinge = posture.hingeList.firstOrNull { it.isSeparating || it.isOccluding } ?: return null
    return with(density) {
        if (hinge.isVertical) Fold.Vertical(hinge.bounds.left.toDp(), hinge.bounds.right.toDp())
        else if (posture.isTabletop) Fold.Horizontal(hinge.bounds.top.toDp(), hinge.bounds.bottom.toDp())
        else null
    }
}
