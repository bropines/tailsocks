package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * as it should, and only what is under it is held in.
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
    val posture = currentWindowAdaptiveInfo().windowPosture
    val density = LocalDensity.current
    val hinge = posture.hingeList.firstOrNull { it.isSeparating || it.isOccluding } ?: return null
    return with(density) {
        if (hinge.isVertical) Fold.Vertical(hinge.bounds.left.toDp(), hinge.bounds.right.toDp())
        else if (posture.isTabletop) Fold.Horizontal(hinge.bounds.top.toDp(), hinge.bounds.bottom.toDp())
        else null
    }
}
