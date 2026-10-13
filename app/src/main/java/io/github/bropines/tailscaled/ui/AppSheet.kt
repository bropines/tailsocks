package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The app's modal bottom sheet: Material's, with the state every sheet here
 * asks for ([rememberFullSheetState]). A drop-in for
 * `ModalBottomSheet(onDismissRequest, sheetState = …)`, and on a handheld
 * exactly that. Every sheet of the app goes through it, so that what a
 * television needs of a sheet is said once, here.
 *
 * On a television it is what the theme cannot do for a sheet, which is a
 * window of its own and outside the safe area laid around a screen
 * ([TvSafeArea]): the sheet stops short of the top edge by that margin and its
 * content ends above the bottom edge by it. And a remote has nothing to drag
 * with, so:
 *  - it has no drag handle, which would be the first stop in every sheet, with
 *    OK on it closing what was just opened;
 *  - it follows no gestures. A sheet that follows its list's scrolling follows
 *    the remote too: moving the focus down a list scrolls it, the sheet took
 *    that for a drag and slid down the screen with its last rows (seen on the
 *    Android TV emulator in three sheets by three people);
 *  - the remote is put into it as it opens ([TvSheetEntry]): a sheet nobody
 *    asked the focus for was a window the arrows did not reach.
 * Back closes a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberFullSheetState(),
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isTv) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            properties = properties,
            content = content
        )
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(top = TvSafeArea.calculateTopPadding()),
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(bottom = TvSafeArea.calculateBottomPadding()) },
        properties = properties,
    ) {
        // The room the handle gave the content above its first line.
        Spacer(Modifier.height(20.dp))
        TvSheetEntry(content)
    }
}

/** How often, and how many times, a sheet that has no stop yet is offered the remote again. */
private const val SHEET_ENTRY_RETRY_MS = 150L
private const val SHEET_ENTRY_TRIES = 40

/**
 * A television's sheet content as one group the remote is sent into when the
 * sheet opens: onto its first stop, unless something in it asked for the remote
 * itself by then (`tvInitialFocus` on the row in use, a list's first row). A
 * sheet whose list arrives after a spinner has no stop at first; it is offered
 * the remote again for a few seconds, until something takes it.
 */
@Composable
private fun TvSheetEntry(content: @Composable ColumnScope.() -> Unit) {
    val entry = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(entry) {
        // A frame for the content to be placed, and for its own request to land first.
        withFrameNanos { }
        repeat(SHEET_ENTRY_TRIES) {
            if (entered) return@LaunchedEffect
            runCatching { entry.requestFocus() }
            delay(SHEET_ENTRY_RETRY_MS)
        }
    }
    Column(
        modifier = Modifier
            .focusRequester(entry)
            .onFocusChanged { if (it.hasFocus) entered = true }
            .focusGroup(),
        content = content
    )
}
