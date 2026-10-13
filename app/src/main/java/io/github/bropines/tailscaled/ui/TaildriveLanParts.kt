package io.github.bropines.tailscaled.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DriveAddresses
import io.github.bropines.tailscaled.core.DriveLan
import io.github.bropines.tailscaled.core.DriveLanSettings
import io.github.bropines.tailscaled.core.DriveLanState
import io.github.bropines.tailscaled.core.DriveProxy

/*
 * "Allow from the local network" on the Taildrive screen (ui/TaildriveActivity.kt): the switch in
 * its three states, the notice for a proxy that was open before the switch existed, the choice a
 * custom address is left by, and the fold the bind address went under. What each of them does to
 * the settings is core/TaildriveLan.kt; here they are only drawn, stored and read.
 */

/**
 * What a preview draws in place of the proxy's stored settings and of this device's addresses on
 * the local network. Only previews provide [LocalDriveLanDemo]; in the app it is null.
 */
internal class DriveLanDemo(val settings: DriveLanSettings, val lans: List<String> = listOf("192.168.1.40"))

internal val LocalDriveLanDemo = staticCompositionLocalOf<DriveLanDemo?> { null }

/** What "Require authentication" was when the local network was let in, or null when nothing is remembered. */
internal fun SharedPreferences.driveAuthBeforeLan(): Boolean? =
    if (contains(DriveLan.KEY_AUTH_BEFORE_LAN)) getBoolean(DriveLan.KEY_AUTH_BEFORE_LAN, false) else null

/** Stores [settings] under the keys the service starts the proxy from, in one write. */
internal fun SharedPreferences.storeDriveLan(settings: DriveLanSettings) {
    val editor = edit()
    editor.putString(DriveProxy.KEY_IP, settings.bind)
    editor.putBoolean(DriveProxy.KEY_AUTH_ENABLED, settings.auth)
    editor.putString(DriveProxy.KEY_USER, settings.user)
    editor.putString(DriveProxy.KEY_PASSWORD, settings.password)
    val remembered = settings.authBeforeLan
    if (remembered == null) editor.remove(DriveLan.KEY_AUTH_BEFORE_LAN) else editor.putBoolean(DriveLan.KEY_AUTH_BEFORE_LAN, remembered)
    editor.apply()
}

/**
 * This device's addresses on the local network, read again whenever the screen comes back: the
 * way to a network leads through the system's settings. A render has no interfaces to ask.
 */
@Composable
internal fun rememberLanAddresses(): List<String> {
    if (LocalInspectionMode.current) return emptyList()
    var lans by remember { mutableStateOf(DriveAddresses.lanAddresses()) }
    LifecycleResumeEffect(Unit) {
        lans = DriveAddresses.lanAddresses()
        onPauseOrDispose { }
    }
    return lans
}

/**
 * The switch, as a row of the settings family. Off and on it is a switch and [onToggle] is its
 * press. At a custom address it is not one: a switch has two positions, and either would be a lie
 * about an address that is neither. The row then names the address, wears a badge where the
 * switch was, and its press is [onChoose], which asks. It stays the same row through all three,
 * so the remote stays on it.
 */
@Composable
internal fun DriveLanRow(
    settings: DriveLanSettings,
    onToggle: (Boolean) -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val state = settings.state
    val subtitle = when (state) {
        DriveLanState.OFF -> stringResource(R.string.taildrive_lan_off_desc)
        // "With the login" is not said of a proxy that asks for none; the notice under the row says the rest.
        DriveLanState.ON -> stringResource(if (settings.openToNetwork) R.string.taildrive_lan_on_open_desc else R.string.taildrive_lan_on_desc)
        DriveLanState.CUSTOM -> stringResource(R.string.taildrive_lan_custom_desc, DriveLan.host(settings.bind))
    }
    val help = remember(subtitle) { mutableStateOf(false) }
    ExplainedRow(
        onClick = { if (state == DriveLanState.CUSTOM) onChoose() else onToggle(state != DriveLanState.ON) },
        help = help,
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            supportingContent = { HelpText(subtitle, expanded = help, inClickableRow = true) },
            leadingContent = { Icon(Icons.Default.Router, null, tint = colors.primary) },
            trailingContent = {
                if (state == DriveLanState.CUSTOM) {
                    Surface(shape = MaterialTheme.shapes.small, color = colors.tertiaryContainer) {
                        Text(
                            stringResource(R.string.taildrive_lan_custom_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    // The row toggles it: for a remote the switch is no stop of its own.
                    Switch(checked = state == DriveLanState.ON, onCheckedChange = onToggle, modifier = Modifier.tvNoFocus())
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(stringResource(R.string.taildrive_lan_title)) }
    }
}

/**
 * What the switch asks at a custom address: the local network, or this device only. Neither is
 * taken for the user — the remote starts on Cancel, which keeps [host]. The words are resolved
 * here, in the screen's composition, for the dialog's window does not follow the app's language.
 */
@Composable
internal fun DriveLanChoiceDialog(host: String, onAllow: () -> Unit, onDeviceOnly: () -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.taildrive_lan_title)
    val text = stringResource(R.string.taildrive_lan_choice_text, host)
    val onDesc = stringResource(R.string.taildrive_lan_choice_on_desc)
    val offTitle = stringResource(R.string.taildrive_lan_choice_off_title)
    val offDesc = stringResource(R.string.taildrive_lan_choice_off_desc)
    val cancel = stringResource(R.string.action_cancel)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                ChoiceCard(Icons.Default.Router, title, onDesc, onAllow)
                ChoiceCard(Icons.Default.Shield, offTitle, offDesc, onDeviceOnly)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.driveInitialFocus()) { Text(cancel) }
        }
    )
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The notice of a proxy that let the local network in with no login before the switch existed
 * (core/TaildriveLan.kt, MIGRATION). It states what is so and has one action; there is nothing to
 * dismiss it with, and it is gone once a login is required.
 */
@Composable
internal fun DriveOpenNotice(onRequireLogin: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Text(stringResource(R.string.taildrive_lan_open_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            HelpText(stringResource(R.string.taildrive_lan_open_text), color = colors.onTertiaryContainer)
            Button(onClick = onRequireLogin) { Text(stringResource(R.string.taildrive_lan_open_action)) }
        }
    }
}

/**
 * The fold over what few need — the proxy's bind address. Closed it is one line; the button
 * stays where it is when [content] comes and goes, so the remote does too.
 */
@Composable
internal fun DriveAdvancedFold(open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onToggle) {
            Text(stringResource(R.string.taildrive_lan_advanced))
            Spacer(Modifier.width(4.dp))
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (open) R.string.help_collapse else R.string.help_expand),
                modifier = Modifier.size(18.dp)
            )
        }
        if (open) content()
    }
}
