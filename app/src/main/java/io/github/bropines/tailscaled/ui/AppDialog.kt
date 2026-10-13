package io.github.bropines.tailscaled.ui

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
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.keepClearOfTheEdges(),
        dismissButton = dismissButton,
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
}

/** `BasicAlertDialog` for the app: for a dialog that draws its own surface; see [AppAlertDialog]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBasicAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.keepClearOfTheEdges(),
        properties = properties,
        content = content,
    )
}

/**
 * The safe area as a margin, ahead of the size limits Material gives the
 * dialog: they are measured inside it, so the dialog is no taller than what
 * the margin leaves of its window, and no wider. Nothing off a television.
 */
@Composable
private fun Modifier.keepClearOfTheEdges(): Modifier = if (isTv) padding(LocalTvSafeArea.current) else this
