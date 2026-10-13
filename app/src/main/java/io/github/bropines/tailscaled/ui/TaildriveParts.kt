package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DriveAddress

/*
 * Pieces of the Taildrive screen (ui/TaildriveActivity.kt) that were added for a remote: the way
 * into the other devices' shares in each of its three places, the address card for a screen with
 * no clipboard, and the question before a share is removed.
 */

/**
 * Marks this element as one the remote may be sent back to ([requester]), and, while [enabled],
 * as the one it starts on: asks for the focus when composed — on a television, for real: a render
 * cannot give focus. [tvInitialFocus] with the frame the target needs to exist.
 */
@Composable
internal fun Modifier.driveInitialFocus(
    enabled: Boolean = true,
    requester: FocusRequester = remember { FocusRequester() },
): Modifier {
    if (!isTv || LocalInspectionMode.current) return this
    if (enabled) LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return focusRequester(requester)
}

/**
 * The way into the other devices' shares on a television: a row at the top of the first column,
 * which is where the remote starts. Phones and tablets have [BrowseSharesButton] in the bar, which
 * leaves the controls where they were.
 */
@Composable
internal fun BrowseSharesRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = colors.primaryContainer
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Devices, contentDescription = null) },
            supportingContent = {
                Text(stringResource(R.string.taildrive_browse_desc), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = colors.onPrimaryContainer,
                supportingColor = colors.onPrimaryContainer,
                leadingIconColor = colors.onPrimaryContainer,
                trailingIconColor = colors.onPrimaryContainer
            )
        ) { Text(stringResource(R.string.taildrive_browse_title), fontWeight = FontWeight.Bold) }
    }
}

/** The same entry for a window with a top bar of its own: an icon in it. */
@Composable
internal fun BrowseSharesButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.taildrive_browse_title))
    }
}

/** …and for the page inside the Files hub, which has no bar of its own: a small button over the Add button. */
@Composable
internal fun BrowseSharesFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.taildrive_browse_title))
    }
}

/**
 * The proxy's address for a screen with no clipboard (the Feature that hides copying): large
 * enough to read from a sofa, and as a code for a phone or laptop to scan. The login is behind
 * its own button — it is on the screen, and in the code, only once [revealLogin] says the user
 * asked. A proxy only this device can reach has no code: scanned elsewhere it would lead nowhere.
 */
@Composable
internal fun DriveAddressCard(
    address: DriveAddress,
    revealLogin: Boolean,
    onToggleLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = MaterialTheme.colorScheme.onSecondaryContainer
    val login = address.login
    val qr = address.qrText(withLogin = revealLogin)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.taildrive_address_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = on
            )
            Text(address.url, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = on)
            if (address.deviceOnly) {
                HelpText(stringResource(R.string.taildrive_address_device_only), color = on)
            }
            if (login != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        if (revealLogin) {
                            LoginLine(stringResource(R.string.taildrive_username), login.user, on)
                            LoginLine(stringResource(R.string.taildrive_password), login.password, on)
                        } else {
                            Text(stringResource(R.string.taildrive_address_login_hidden), style = MaterialTheme.typography.bodyMedium, color = on)
                        }
                    }
                    TextButton(onClick = onToggleLogin) {
                        Text(stringResource(if (revealLogin) R.string.taildrive_address_hide_login else R.string.taildrive_address_show_login))
                    }
                }
            }
            if (qr != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCodeImage(qr, Modifier.size(QR_SIZE))
                    Text(
                        stringResource(if (revealLogin && login != null) R.string.taildrive_address_qr_login else R.string.taildrive_address_qr),
                        style = MaterialTheme.typography.bodyMedium,
                        color = on,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private val QR_SIZE = 144.dp

@Composable
private fun LoginLine(label: String, value: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = color)
    }
}

/**
 * Asks before a share is removed. A remote presses OK on whatever it is on, and a share takes a
 * path and a name to get back. The focus starts on Cancel. The words are resolved here, in the
 * screen's composition, for the dialog's window does not follow the app's language.
 */
@Composable
internal fun RemoveShareDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.taildrive_remove_title, name)
    val body = stringResource(R.string.taildrive_remove_body)
    val confirm = stringResource(R.string.taildrive_remove_confirm)
    val cancel = stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.driveInitialFocus()) { Text(cancel) }
        }
    )
}
