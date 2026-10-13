package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's modal bottom sheet: Material's, with the state every sheet here
 * asks for ([rememberFullSheetState]). A drop-in for
 * `ModalBottomSheet(onDismissRequest, sheetState = …)`, and on a handheld
 * exactly that.
 *
 * On a television it is what the theme cannot do for a sheet, which is a
 * window of its own and outside the safe area laid around a screen
 * ([TvSafeArea]): the sheet stops short of the top edge by that margin and its
 * content ends above the bottom edge by it. And it has no drag handle — a
 * remote cannot drag, and the handle would be the first stop in every sheet,
 * with OK on it closing what was just opened. Back closes a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberFullSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isTv) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState, content = content)
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(top = TvSafeArea.calculateTopPadding()),
        sheetState = sheetState,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(bottom = TvSafeArea.calculateBottomPadding()) },
    ) {
        // The room the handle gave the content above its first line.
        Spacer(Modifier.height(20.dp))
        content()
    }
}
