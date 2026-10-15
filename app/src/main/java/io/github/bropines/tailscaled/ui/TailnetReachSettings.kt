package io.github.bropines.tailscaled.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ExposureNet
import io.github.bropines.tailscaled.core.NetAddr
import io.github.bropines.tailscaled.core.ReachPort
import io.github.bropines.tailscaled.core.TailnetListener
import io.github.bropines.tailscaled.core.TailnetReach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → Local proxies → Reachable from the tailnet: one switch per local proxy that other
 * devices of the tailnet may be let in to, off unless turned on here, and strict mode, which
 * closes other apps' services too and leaves open only the ports listed under it
 * (core/TailnetReach.kt says why and how the daemon follows it). Each row's line says where the
 * proxy stands now and, folded, what opening it gives away. The SOCKS5 proxy without a password
 * asks before it opens and offers to set one; the Taildrive proxy without a login says so.
 */

/** What the card shows of the device; read by the caller, so previews can draw it. */
internal data class TailnetReachState(
    /** Each listener's listen address now; empty while it is off. */
    val binds: Map<TailnetListener, String>,
    val open: Set<TailnetListener>,
    val net: ExposureNet,
    /** This device's tailnet IPv4 address, when it has one. */
    val tailnetIp: String?,
    val socks5Login: Boolean,
    val driveLogin: Boolean,
    /** Strict mode, and the ports it leaves open as saved ("5555, 8022"). */
    val strict: Boolean = false,
    val extra: String = "",
    /** The app's own ports, which the field may not name (TailnetReach.ownPorts). */
    val own: Set<ReachPort> = emptySet(),
)

/**
 * The card of the Local proxies page. [open] is the page's, which shows it on the SOCKS5 card's
 * links too; [onSetSocks5Password] sets a username and a password the way that card's buttons do.
 */
@Composable
internal fun TailnetReachCard(
    open: Set<TailnetListener>,
    onOpenChange: (Set<TailnetListener>) -> Unit,
    tailnetIp: String?,
    socks5Login: Boolean,
    onSetSocks5Password: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var strict by remember { mutableStateOf(TailnetReach.isStrict(context)) }
    var extra by remember { mutableStateOf(TailnetReach.extraText(context)) }
    val state = TailnetReachState(
        binds = TailnetListener.entries.associateWith { TailnetReach.bind(context, it) },
        open = open,
        net = TailnetReach.net(context),
        tailnetIp = tailnetIp,
        socks5Login = socks5Login,
        driveLogin = TailnetReach.driveLogin(context),
        strict = strict,
        extra = extra,
        own = TailnetReach.ownPorts(context),
    )
    TailnetReachContent(
        state,
        onSwitch = { listener, on ->
            onOpenChange(if (on) open + listener else open - listener)
            scope.launch { withContext(Dispatchers.IO) { TailnetReach.setOpen(context, listener, on) } }
        },
        onSetSocks5Password = onSetSocks5Password,
        onStrict = { on ->
            strict = on
            scope.launch { withContext(Dispatchers.IO) { TailnetReach.setStrict(context, on) } }
        },
        onExtra = { text ->
            extra = TailnetReach.normalExtra(text)
            scope.launch { withContext(Dispatchers.IO) { TailnetReach.setExtra(context, text) } }
        },
    )
}

@Composable
internal fun TailnetReachContent(
    state: TailnetReachState,
    onSwitch: (TailnetListener, Boolean) -> Unit,
    onSetSocks5Password: () -> Unit,
    onStrict: (Boolean) -> Unit = {},
    onExtra: (String) -> Unit = {},
) {
    var askSocks5 by remember { mutableStateOf(false) }
    SettingsCard(title = stringResource(R.string.tailnet_reach_card)) {
        HelpText(
            stringResource(R.string.tailnet_reach_intro) + when (state.net) {
                ExposureNet.NETSTACK -> ""
                ExposureNet.VPN -> " " + stringResource(R.string.tailnet_reach_vpn)
                ExposureNet.INTERFACE -> " " + stringResource(R.string.tailnet_reach_interface)
            },
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        )
        TailnetReachRow(state, TailnetListener.SOCKS5, R.string.tailnet_reach_socks5, R.string.tailnet_reach_socks5_help, Icons.Default.Language) { on ->
            // Without a password the proxy opens only after the question.
            if (on && !state.socks5Login) askSocks5 = true else onSwitch(TailnetListener.SOCKS5, on)
        }
        if (TailnetListener.SOCKS5 in state.open && !state.socks5Login) {
            Warning(stringResource(R.string.tailnet_reach_socks5_no_password)) {
                TextButton(onClick = onSetSocks5Password) { Text(stringResource(R.string.tailnet_reach_set_password)) }
            }
        }
        TailnetReachRow(state, TailnetListener.HTTP_PROXY, R.string.tailnet_reach_http, R.string.tailnet_reach_http_help, Icons.Default.Http) {
            onSwitch(TailnetListener.HTTP_PROXY, it)
        }
        TailnetReachRow(state, TailnetListener.DNS_PROXY, R.string.tailnet_reach_dns, R.string.tailnet_reach_dns_help, Icons.Default.Dns) {
            onSwitch(TailnetListener.DNS_PROXY, it)
        }
        TailnetReachRow(state, TailnetListener.TAILDRIVE_PROXY, R.string.tailnet_reach_drive, R.string.tailnet_reach_drive_help, Icons.Default.CloudSync) {
            onSwitch(TailnetListener.TAILDRIVE_PROXY, it)
        }
        if (TailnetListener.TAILDRIVE_PROXY in state.open && !state.driveLogin) {
            Warning(stringResource(R.string.tailnet_reach_drive_no_login))
        }
        TailnetStrictRows(state, onStrict, onExtra)
    }

    if (askSocks5) {
        // A dialog's window does not follow the app's language: its words come from here.
        val title = stringResource(R.string.tailnet_reach_socks5_ask_title)
        val text = stringResource(R.string.tailnet_reach_socks5_ask_text)
        val setAndOpen = stringResource(R.string.tailnet_reach_set_password_and_open)
        val openAnyway = stringResource(R.string.tailnet_reach_open_anyway)
        val cancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { askSocks5 = false },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    askSocks5 = false
                    onSetSocks5Password()
                    onSwitch(TailnetListener.SOCKS5, true)
                }) { Text(setAndOpen) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSocks5 = false }) { Text(cancel) }
                    TextButton(onClick = {
                        askSocks5 = false
                        onSwitch(TailnetListener.SOCKS5, true)
                    }) { Text(openAnyway) }
                }
            },
        )
    }
}

/**
 * The card's last rows: everything else that listens on this device — other apps' servers, served
 * as on any node — closed by strict mode, and the ports it leaves open, shown while it is on.
 */
@Composable
internal fun TailnetStrictRows(state: TailnetReachState, onStrict: (Boolean) -> Unit, onExtra: (String) -> Unit) {
    // The field's dialog has a window of its own, which ignores the app's language: its words come from here.
    val context = LocalContext.current
    SettingsSwitchItem(
        title = stringResource(R.string.tailnet_reach_strict),
        subtitle = stringResource(R.string.tailnet_reach_strict_help),
        icon = Icons.Default.Lock,
        checked = state.strict,
        onCheckedChange = onStrict,
    )
    if (state.strict) {
        SettingsEditItem(
            title = stringResource(R.string.tailnet_reach_extra),
            value = state.extra,
            icon = Icons.Default.SettingsEthernet,
            placeholder = "5555, 8022",
            description = stringResource(R.string.tailnet_reach_extra_desc),
            error = { text -> extraProblem(context, text, state.own) },
            onSave = onExtra,
        )
    }
}

/** What is wrong with the ports typed into the field, in the page's language; null when nothing is. */
private fun extraProblem(context: android.content.Context, text: String, own: Set<ReachPort>): String? {
    val read = TailnetReach.parseExtra(text, own)
    return when {
        read.bad.isNotEmpty() -> context.getString(R.string.tailnet_reach_extra_bad, read.bad.joinToString(", "))
        read.own.isNotEmpty() -> context.getString(R.string.tailnet_reach_extra_own, read.own.joinToString(", "))
        else -> null
    }
}

/** One proxy's switch; its line: where it stands, then — folded — what opening it gives away. */
@Composable
private fun TailnetReachRow(
    state: TailnetReachState,
    listener: TailnetListener,
    @StringRes title: Int,
    @StringRes help: Int,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bind = state.binds[listener].orEmpty()
    val port = NetAddr.port(bind)
    val on = listener in state.open
    val where = when {
        bind.isBlank() || port == null -> stringResource(R.string.tailnet_reach_state_off)
        state.net == ExposureNet.VPN -> stringResource(R.string.tailnet_reach_state_interface)
        state.net == ExposureNet.INTERFACE ->
            stringResource(if (NetAddr.isWildcard(bind)) R.string.tailnet_reach_state_interface_wildcard else R.string.tailnet_reach_state_interface)
        !TailnetReach.reachableThroughLoopback(bind) -> stringResource(R.string.tailnet_reach_state_alias, bind)
        on -> state.tailnetIp?.let { stringResource(R.string.tailnet_reach_state_open, "$it:$port") }
            ?: stringResource(R.string.tailnet_reach_state_open_port, port.toString())
        else -> stringResource(R.string.tailnet_reach_state_closed)
    }
    SettingsSwitchItem(
        title = stringResource(title),
        subtitle = where + " " + stringResource(help),
        icon = icon,
        checked = on,
        // A proxy that is off has nothing to open, but one opened earlier can still be closed.
        enabled = bind.isNotBlank() || on,
        onCheckedChange = onCheckedChange,
    )
}

/** A warning under a row, in the colours the Local network card warns in. */
@Composable
private fun Warning(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (action != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
    }
}
