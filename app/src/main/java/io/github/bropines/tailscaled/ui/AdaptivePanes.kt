package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True for content drawn in the detail pane of a [ListDetailLayout] — a page
 * that is a sheet on a phone, opened in place beside its list on a large
 * window. [SheetOrPane] reads it, so a detail composable decides how to
 * present itself by where it is called from.
 */
val LocalInPane = staticCompositionLocalOf { false }

/** How the detail pane of a [ListDetailLayout] stands apart from the list. */
enum class PaneStyle {
    /**
     * A rounded surface in the colour of a sheet, inset from the window's
     * edges: the detail reads as the sheet it is on a phone, opened in place.
     * For details whose phone form is a bottom sheet (a peer, a device).
     */
    RAISED,

    /**
     * Flat, a hairline between the panes: for a detail that is a page with a
     * top bar of its own (a Settings section).
     */
    FLAT,
}

/**
 * A list and the detail of what is picked in it, side by side when the window
 * has room for both ([WindowLayout.listDetail]) and the list alone otherwise.
 *
 * Single-pane, this draws [list] and nothing else, so a phone keeps exactly the
 * screen it had: the caller opens the detail the way it always did — a sheet,
 * a dialog — and only when the window is two-pane does it leave that to the
 * pane. The usual shape:
 *
 * ```
 * val window = rememberWindowLayout()
 * ListDetailLayout(window = window, list = { Rows(onClick = { picked = it }) }, detail = { Details(shown) })
 * if (!window.listDetail) picked?.let { Details(it) }   // a sheet, as before
 * ```
 *
 * where `Details` presents itself through [SheetOrPane]. Two-pane, nothing has
 * to be picked first: a pane left empty is the dead space this layout exists
 * to remove, so the caller shows the first item, or [PaneEmptyState] when
 * there is none.
 *
 * A foldable open like a book puts the list on one half and the detail on the
 * other, the hinge between them, whatever [listWidth] says.
 *
 * @param twoPane whether the panes stand side by side; [WindowLayout.listDetail]
 *   unless a screen has a reason of its own (Settings goes two-pane on a phone
 *   on its side as well, and always has).
 * @param listWidth the list pane's width; the detail takes the rest.
 */
@Composable
fun ListDetailLayout(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    window: WindowLayout = rememberWindowLayout(),
    twoPane: Boolean = window.listDetail,
    listWidth: Dp = window.listPaneWidth,
    style: PaneStyle = PaneStyle.RAISED,
) {
    if (!twoPane) {
        Box(modifier) { list() }
        return
    }
    val fold = window.fold as? Fold.Vertical
    Row(modifier) {
        Box(Modifier.width(fold?.start ?: listWidth).fillMaxHeight()) { list() }
        when {
            fold != null -> Spacer(Modifier.width(fold.end - fold.start))
            style == PaneStyle.FLAT -> VerticalDivider()
        }
        val pane = Modifier.weight(1f).fillMaxHeight()
        CompositionLocalProvider(LocalInPane provides true) {
            when (style) {
                PaneStyle.RAISED -> Surface(
                    // No margin on the list's side: the list's rows carry their own, and on a
                    // foldable the hinge is the gap.
                    modifier = pane.padding(
                        start = 0.dp,
                        top = PaneDefaults.Gap,
                        end = PaneDefaults.Gap,
                        bottom = PaneDefaults.Gap,
                    ),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) { detail() }
                PaneStyle.FLAT -> Box(pane) { detail() }
            }
        }
    }
}

object PaneDefaults {
    /** Between a raised pane and what is around it. */
    val Gap = 8.dp
}

/**
 * A detail's frame: a modal bottom sheet on a phone, the pane itself in a
 * two-pane layout. [inPane] follows [LocalInPane], so the same call is a sheet
 * where a screen draws it over its list and the pane's content where
 * [ListDetailLayout] draws it.
 *
 * In a pane there is nothing to dismiss — the pane shows whatever is picked —
 * so [onDismiss] is a sheet's alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetOrPane(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    inPane: Boolean = LocalInPane.current,
    sheetState: SheetState = rememberFullSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (inPane) {
        Column(modifier.fillMaxSize(), content = content)
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier, content = content)
    }
}

/** What a detail pane shows when nothing in the list can be shown: [text], under a faint [icon]. */
@Composable
fun PaneEmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    EmptyState(icon = icon, text = text, modifier = modifier.fillMaxSize())
}
