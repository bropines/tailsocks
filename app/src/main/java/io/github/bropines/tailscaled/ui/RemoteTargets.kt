package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape

/*
 * What the main screen's rows need for a remote beyond ui/Focus.kt. All of it is what it
 * was off a television. (What a sheet needs is AppModalSheet's, ui/AppSheet.kt.)
 */

/**
 * `clickable` for a container that draws a shape of its own and is clicked through its
 * modifier — a Card or a Surface with `Modifier.clickable`, a row of a sheet.
 *
 * Off a television it is `clickable` and nothing else. On one, the ring Material draws on
 * such a target is a rectangle around the rounded container (ui/Focus.kt): here it is cut
 * to [shape] and the shaped ring laid over it, so the remote marks the row as it is drawn.
 */
@Composable
internal fun Modifier.shapedClickable(shape: Shape, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    if (!isTv) return clickable(enabled = enabled, onClick = onClick)
    val source = remember { MutableInteractionSource() }
    return tvFocusRing(source, shape)
        .clip(shape)
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}
