package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties

/*
 * The app's dialogs: Material's, which on a handheld is all they are. A dialog is
 * a window of its own, outside the safe area the theme lays around a screen
 * (ui/DeviceClass.kt), and Material's is as tall as the screen when its text is
 * long: on a television it touched the top and the bottom edge, which a set may
 * cut off. So on a television the dialog is laid out inside the safe area, on
 * whatever room that leaves — the window above the keyboard, when there is one
 * on screen, since the window itself shrinks to it.
 *
 * Every dialog a television can reach goes through one of these; an AlertDialog
 * of the admin console, which a television is not offered, is Material's own.
 * A dialog of a shape that is neither (a full-screen page, a scene) pads itself
 * with LocalTvSafeArea.
 *
 * And a television opens a dialog with the remote in it, on the safe action: the
 * cancel button of a dialog that has two, the only button of one that has one. A
 * dialog that put the remote somewhere itself (tvInitialFocus, a field) keeps it
 * there; the wrapper sends it in only for the dialog that asked for nothing.
 *
 * On every device a dialog carries the bar of what a paired device makes this one say
 * (ui/RemoteNoticeBar.kt): the screen's own would be dimmed under the dialog.
 */

/**
 * `AlertDialog` for the app: the same parameters, the same dialog, and on a
 * television one that keeps clear of the screen's edges however long its text.
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val entry = rememberWindowEntry()
    // The safe action: cancel when there is one, the only button otherwise.
    val safeIsConfirm = dismissButton == null
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            // The box is the button's size and adds nothing to the row; the bar's host is a
            // point inside it, where the row does not count it as one more button.
            Box(if (safeIsConfirm) Modifier.tvEntryPoint(entry) else Modifier) {
                confirmButton()
                RemoteNoticeWindowHost()
            }
        },
        modifier = modifier.keepClearOfTheEdges().tvWindowEntered(entry),
        dismissButton = if (entry != null && dismissButton != null) {
            { Box(Modifier.tvEntryPoint(entry)) { dismissButton() } }
        } else dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
    TvWindowEntry(entry)
}

/**
 * `BasicAlertDialog` for the app: for a dialog that draws its own surface; see [AppAlertDialog].
 * On a television the remote is sent into [content] as a whole, onto its first stop: a dialog
 * that has several says which is the safe one itself, as [AppAlertDialog]'s callers do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBasicAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val entry = rememberWindowEntry()
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.keepClearOfTheEdges().tvWindowEntered(entry),
        properties = properties,
        content = {
            if (entry == null) content()
            else Box(Modifier.tvEntryPoint(entry).focusGroup(), propagateMinConstraints = true) { content() }
            RemoteNoticeWindowHost()
        },
    )
    TvWindowEntry(entry)
}

/**
 * The safe area as a margin, ahead of the size limits Material gives the
 * dialog: they are measured inside it, so the dialog is no taller than what
 * the margin leaves of its window, and no wider. Nothing off a television.
 */
@Composable
private fun Modifier.keepClearOfTheEdges(): Modifier = if (isTv) padding(LocalTvSafeArea.current) else this
