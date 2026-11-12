package io.github.bropines.tailscaled.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.R

/**
 * Settings → Appearance: the way to the main screen's editor that can be found by looking —
 * the other way in is a long press on the screen itself, which nobody finds by looking.
 *
 * It opens the main screen with its canvas (ui/MainCanvas.kt). A television edits the same
 * arrangement as a list, here in Settings: there is nothing to drag with.
 */
@Composable
fun MainBlocksRow() {
    val context = LocalContext.current
    val tv = isTv
    var listOpen by remember { mutableStateOf(false) }
    SettingsClickableItem(
        title = stringResource(R.string.settings_main_blocks_title),
        subtitle = stringResource(if (tv) R.string.tv_main_layout_desc else R.string.settings_main_blocks_desc),
        icon = Icons.Default.Dashboard,
        onClick = { if (tv) listOpen = true else context.startActivity(MainActivity.editLayoutIntent(context)) },
    )
    if (listOpen) MainLayoutListDialog(onDismiss = { listOpen = false })
}
