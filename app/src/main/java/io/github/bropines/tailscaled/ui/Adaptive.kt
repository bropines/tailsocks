package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
