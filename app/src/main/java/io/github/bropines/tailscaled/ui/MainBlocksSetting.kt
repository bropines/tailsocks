package io.github.bropines.tailscaled.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.R

/**
 * Settings → Appearance: the way to the main screen's editor that can be found by looking —
 * the other way in is a long press on the screen itself, which nobody finds by looking.
 *
 * It opens the main screen with its canvas (ui/MainCanvas.kt). Not on a television: there is
 * nothing to drag with, and its main screen is the one arrangement made for a remote.
 */
@Composable
fun MainBlocksRow() {
    if (isTv) return
    val context = LocalContext.current
    SettingsClickableItem(
        title = stringResource(R.string.settings_main_blocks_title),
        subtitle = stringResource(R.string.settings_main_blocks_desc),
        icon = Icons.Default.Dashboard,
        onClick = { context.startActivity(MainActivity.editLayoutIntent(context)) },
    )
}
