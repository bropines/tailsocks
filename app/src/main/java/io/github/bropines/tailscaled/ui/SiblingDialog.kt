package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.SiblingBuild

/**
 * Before a start from the main screen while the other build of the app runs and
 * would collide with this one ([SiblingBuild.check]): what collides, and three
 * ways on — open the other app (the place to stop it), start anyway, or cancel.
 * Nothing here touches the sibling.
 *
 * Strings are resolved here, in the parent composition, and handed to the
 * dialog as plain text — see the comment above wrapContextWithLocale().
 */
@Composable
fun SiblingDialog(
    found: SiblingBuild.Found,
    onOpen: () -> Unit,
    onStartAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    val words = siblingDialogWords(found)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text(words.title) },
        text = { Text(words.text) },
        // Opening the other app is the way out that leaves both working; starting
        // anyway stays a plain text button beside it.
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStartAnyway) { Text(words.startAnyway) }
                Button(onClick = onOpen) { Text(words.open) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(words.cancel) }
        },
    )
}

/**
 * What [SiblingDialog] says, resolved in the composition that shows it. Its own
 * function so the screenshot previews (SiblingDialogPreviews) draw the dialog's
 * content without a window, which a preview cannot render over a page.
 */
internal class SiblingDialogWords(
    val title: String,
    val text: String,
    val open: String,
    val startAnyway: String,
    val cancel: String,
)

@Composable
internal fun siblingDialogWords(found: SiblingBuild.Found) = SiblingDialogWords(
    title = stringResource(if (found.isDev) R.string.sibling_title_dev else R.string.sibling_title_release),
    text = stringResource(
        when (found.conflict) {
            SiblingBuild.Conflict.SAME_NODE -> R.string.sibling_text_same_node
            SiblingBuild.Conflict.BOTH_ROOT_TUN -> R.string.sibling_text_root_tun
            SiblingBuild.Conflict.VPN_SLOT -> R.string.sibling_text_vpn_slot
            SiblingBuild.Conflict.PORTS -> R.string.sibling_text_ports
        }
    ),
    open = stringResource(R.string.sibling_action_open),
    startAnyway = stringResource(R.string.sibling_action_start_anyway),
    cancel = stringResource(R.string.action_cancel),
)
