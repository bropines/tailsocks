package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape

/*
 * What the main screen's rows and sheets need for a remote beyond ui/Focus.kt. All of it is
 * what it was off a television.
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

/**
 * Keeps a bottom sheet's content clear of a television's lower edge: a sheet is a window
 * of its own, which the theme's safe area does not reach. Nothing off a television.
 */
@Composable
internal fun Modifier.tvSheetInset(): Modifier =
    if (isTv) padding(bottom = TvSafeArea.calculateBottomPadding()) else this

/**
 * Whether a bottom sheet follows gestures — not on a television. There is no gesture to
 * give it there, and a sheet that follows its list's scrolling follows the remote too:
 * moving the focus scrolls the list, the sheet slid down with it, and its last row went
 * off the bottom of the screen (seen on the Android TV emulator).
 */
@Composable
internal fun sheetGestures(): Boolean = !isTv

/**
 * A bottom sheet's drag handle — none on a television, where nothing drags, Back closes the
 * sheet, and the handle would be the first thing the remote lands on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun sheetDragHandle(): (@Composable () -> Unit)? =
    if (isTv) null else { { BottomSheetDefaults.DragHandle() } }
