package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.LifecycleResumeEffect
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.ClientPrefs
import io.github.bropines.tailscaled.core.ClientPrefs.Field
import io.github.bropines.tailscaled.core.TailnetLock
import io.github.bropines.tailscaled.core.TailnetLockStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/** What the Tailnet Lock row in Settings knows: nothing yet, no daemon, or the status. */
sealed interface TailnetLockRow {
    data object Unknown : TailnetLockRow
    data object Offline : TailnetLockRow
    data class Loaded(val status: TailnetLockStatus) : TailnetLockRow
}

/**
 * Settings' view of the node switches (core/ClientPrefs): what this profile stores, what the
 * daemon holds now, and what the extra arguments force. A switch shows, in that order of
 * precedence, the flag that overrides it, the profile's own value, the daemon's value, off.
 */
@Stable
class NodeSwitchesState(stored: ClientPrefs.Values, relayPortText: String) {
    var stored by mutableStateOf(stored)
    var relayPortText by mutableStateOf(relayPortText)
    var live by mutableStateOf<ClientPrefs.Values?>(null)
    var overrides by mutableStateOf(ClientPrefs.Values())
    /** Connections the relay forwards right now; null when unknown or not relaying. */
    var relaySessions by mutableStateOf<Int?>(null)
    /** The coordination server has turned this device's relay off (disable-relay-server). */
    var relayDisabledByControl by mutableStateOf(false)
    var tailnetLock by mutableStateOf<TailnetLockRow>(TailnetLockRow.Unknown)

    /** Bumped to read the daemon again. */
    internal var refreshTick by mutableIntStateOf(0)

    fun checked(field: Field): Boolean = when (field) {
        Field.SHIELDS_UP -> overrides.shieldsUp ?: stored.shieldsUp ?: live?.shieldsUp
        Field.EXIT_NODE_LAN -> overrides.exitNodeAllowLan ?: stored.exitNodeAllowLan ?: live?.exitNodeAllowLan
        Field.REPORT_POSTURE -> overrides.reportPosture ?: stored.reportPosture ?: live?.reportPosture
        Field.RELAY -> relay().enabled
        Field.APP_CONNECTOR -> overrides.appConnector ?: stored.appConnector ?: live?.appConnector
    } ?: false

    fun relay(): ClientPrefs.Relay = overrides.relay ?: stored.relay ?: live?.relay ?: ClientPrefs.Relay(false)

    /** The flag in the extra arguments that decides this switch, or null. */
    fun overriddenBy(field: Field): String? = if (overrides.isSet(field)) field.flag else null

    /**
     * Turns a switch. With the daemon running the change goes to it at once, so a refusal — shields
     * up while Funnel is on — is said now and nothing is stored; then the profile keeps it and
     * [save] asks the service to apply settings, which keeps the bridge's copy of the options
     * current for a daemon relaunch.
     */
    fun set(context: Context, scope: CoroutineScope, field: Field, on: Boolean, save: (String, Any) -> Unit) {
        val next = when (field) {
            Field.SHIELDS_UP -> stored.copy(shieldsUp = on)
            Field.EXIT_NODE_LAN -> stored.copy(exitNodeAllowLan = on)
            Field.REPORT_POSTURE -> stored.copy(reportPosture = on)
            Field.RELAY -> stored.copy(relay = ClientPrefs.Relay(on, ClientPrefs.parsePort(relayPortText) ?: 0))
            Field.APP_CONNECTOR -> stored.copy(appConnector = on)
        }
        val patch = ClientPrefs.maskedPrefsJson(only(next, field))
        scope.launch {
            val refusal = withContext(Dispatchers.IO) {
                // Not during a login: syncSettings keeps its hands off the prefs then, and so does this.
                val running = Appctr.isRunning() && runCatching { Appctr.getBackendState() }.getOrDefault("") == "Running"
                if (!running) null else Appctr.setPrefs(patch).takeIf { it != "OK" }
            }
            if (refusal != null) {
                Toast.makeText(context, context.getString(R.string.client_prefs_refused, refusal.removePrefix("Error: ")), Toast.LENGTH_LONG).show()
                return@launch
            }
            stored = next
            save(keyOf(field), on)
            refreshTick++
        }
    }

    /** Stores a typed relay port; false (and nothing stored) when it is not a port. */
    fun setRelayPort(context: Context, scope: CoroutineScope, text: String, save: (String, Any) -> Unit): Boolean {
        val port = ClientPrefs.parsePort(text) ?: return false
        relayPortText = if (port == 0) "" else port.toString()
        save(ClientPrefs.RELAY_PORT, relayPortText)
        if (stored.relay?.enabled == true || (stored.relay == null && relay().enabled)) {
            set(context, scope, Field.RELAY, true, save)
        }
        return true
    }

    private fun only(v: ClientPrefs.Values, field: Field) = when (field) {
        Field.SHIELDS_UP -> ClientPrefs.Values(shieldsUp = v.shieldsUp)
        Field.EXIT_NODE_LAN -> ClientPrefs.Values(exitNodeAllowLan = v.exitNodeAllowLan)
        Field.REPORT_POSTURE -> ClientPrefs.Values(reportPosture = v.reportPosture)
        Field.RELAY -> ClientPrefs.Values(relay = v.relay)
        Field.APP_CONNECTOR -> ClientPrefs.Values(appConnector = v.appConnector)
    }

    private fun keyOf(field: Field) = when (field) {
        Field.SHIELDS_UP -> ClientPrefs.SHIELDS_UP
        Field.EXIT_NODE_LAN -> ClientPrefs.EXIT_NODE_LAN
        Field.REPORT_POSTURE -> ClientPrefs.REPORT_POSTURE
        Field.RELAY -> ClientPrefs.RELAY_ENABLED
        Field.APP_CONNECTOR -> ClientPrefs.APP_CONNECTOR
    }
}

/**
 * The node switches for the profile behind [profilePrefs]. The daemon's values, the relay's
 * sessions and Tailnet Lock are read when the page opens, after every change and on every
 * return to it (from the Tailnet Lock screen, say); the extra arguments whenever they change.
 * The preview renderer has no bridge: there it shows the profile's values and nothing else.
 */
@Composable
fun rememberNodeSwitches(profilePrefs: SharedPreferences, extraArgs: String): NodeSwitchesState {
    val inPreview = LocalInspectionMode.current || LocalDemo.current != null
    val state = remember(profilePrefs) {
        // The preview renderer's preferences answer null here.
        val all: Map<String, *> = profilePrefs.all ?: emptyMap<String, Any>()
        NodeSwitchesState(ClientPrefs.stored(all), all[ClientPrefs.RELAY_PORT] as? String ?: "")
    }
    if (inPreview) return state
    LaunchedEffect(state, extraArgs) {
        state.overrides = withContext(Dispatchers.IO) {
            runCatching { ClientPrefs.overrides(Appctr.extraArgsPrefsJSON(extraArgs)) }.getOrDefault(ClientPrefs.Values())
        }
    }
    LifecycleResumeEffect(state) {
        state.refreshTick++
        onPauseOrDispose { }
    }
    LaunchedEffect(state, state.refreshTick) {
        val read = withContext(Dispatchers.IO) { readDaemon() }
        state.live = read.live
        state.relaySessions = read.relaySessions
        state.relayDisabledByControl = read.relayDisabledByControl
        state.tailnetLock = read.tailnetLock
    }
    return state
}

private class DaemonRead(
    val live: ClientPrefs.Values?,
    val relaySessions: Int?,
    val relayDisabledByControl: Boolean,
    val tailnetLock: TailnetLockRow,
)

/** The daemon's side of the switches, Tailnet Lock included. Blocking: call it off the main thread. */
private fun readDaemon(): DaemonRead {
    if (!Appctr.isRunning()) return DaemonRead(null, null, false, TailnetLockRow.Offline)
    val live = runCatching { ClientPrefs.live(Appctr.getPrefsJSON()) }.getOrNull()
    val relaying = live?.relay?.enabled == true
    val sessions = if (relaying) relaySessions() else null
    val refused = relaying && runCatching { ClientPrefs.relayDisabledByControl(Appctr.getStatusJSON(false)) }.getOrDefault(false)
    val lock = TailnetLock.parse(Appctr.doLocalAPIRequest("GET", TailnetLock.STATUS_PATH, ""))
        ?.let { TailnetLockRow.Loaded(it) } ?: TailnetLockRow.Offline
    return DaemonRead(live, sessions, refused, lock)
}

/** How many sessions the relay server holds, from its LocalAPI status; null when unreadable. */
private fun relaySessions(): Int? = runCatching {
    val body = Appctr.doLocalAPIRequest("GET", "/localapi/v0/debug-peer-relay-sessions", "")
    if (body.startsWith("Error")) return null
    (AppJson.parseToJsonElement(body).jsonObject["Sessions"] as? JsonArray)?.size ?: 0
}.getOrNull()

/** What a switch's row says under its title: who decides it, then the explanation. */
@Composable
private fun subtitle(state: NodeSwitchesState, field: Field, desc: Int, status: String? = null): String {
    val text = stringResource(desc)
    val head = state.overriddenBy(field)?.let { stringResource(R.string.client_prefs_set_by_flag, it) } ?: status
    return if (head == null) text else "$head\n$text"
}

@Composable
private fun NodeSwitchRow(
    state: NodeSwitchesState,
    field: Field,
    title: Int,
    desc: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    save: (String, Any) -> Unit,
    status: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsSwitchItem(
        title = stringResource(title),
        subtitle = subtitle(state, field, desc, status),
        icon = icon,
        checked = state.checked(field),
        enabled = state.overriddenBy(field) == null,
    ) { state.set(context, scope, field, it, save) }
}

/** "Allow LAN access", which belongs beside the exit node it qualifies. */
@Composable
fun ExitNodeLanRow(state: NodeSwitchesState, save: (String, Any) -> Unit) {
    NodeSwitchRow(state, Field.EXIT_NODE_LAN, R.string.client_prefs_lan_title, R.string.client_prefs_lan_desc, Icons.Default.Router, save)
}

/** Shields up, posture reporting and the Tailnet Lock row, as one card. */
@Composable
fun NodeSecurityCard(state: NodeSwitchesState, save: (String, Any) -> Unit, onOpenTailnetLock: () -> Unit) {
    SettingsCard(title = stringResource(R.string.client_prefs_sect_security)) {
        NodeSwitchRow(state, Field.SHIELDS_UP, R.string.client_prefs_shields_title, R.string.client_prefs_shields_desc, Icons.Default.Shield, save)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        NodeSwitchRow(state, Field.REPORT_POSTURE, R.string.client_prefs_posture_title, R.string.client_prefs_posture_desc, Icons.Default.HealthAndSafety, save)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        SettingsClickableItem(
            title = stringResource(R.string.tka_title),
            subtitle = tailnetLockSummary(state.tailnetLock) + "\n" + stringResource(R.string.tka_row_help),
            icon = Icons.Default.Lock,
            onClick = onOpenTailnetLock,
        )
    }
}

/** One line on Tailnet Lock for the Settings row. */
@Composable
fun tailnetLockSummary(row: TailnetLockRow): String = when (row) {
    TailnetLockRow.Unknown, TailnetLockRow.Offline -> stringResource(R.string.tka_summary_offline)
    is TailnetLockRow.Loaded -> {
        val st = row.status
        val waiting = st.lockedOutPeers.size
        when {
            !st.enabled -> stringResource(R.string.tka_summary_off)
            st.isLockedOut -> stringResource(R.string.tka_summary_locked_out)
            st.isTrustedSigner && waiting > 0 ->
                stringResource(R.string.tka_summary_signer) + " · " + pluralStringResource(R.plurals.tka_summary_waiting, waiting, waiting)
            st.isTrustedSigner -> stringResource(R.string.tka_summary_signer)
            else -> stringResource(R.string.tka_summary_signed)
        }
    }
}

/** The peer relay with its port, and the app connector: what this phone offers the tailnet. */
@Composable
fun NodeServiceRows(state: NodeSwitchesState, save: (String, Any) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relay = state.relay()
    // The port has a row of its own; the switch says what the relay is doing, or that the
    // coordination server will not let it.
    val relayStatus = when {
        !relay.enabled || state.overriddenBy(Field.RELAY) != null -> null
        state.relayDisabledByControl -> stringResource(R.string.client_prefs_relay_refused_by_control)
        else -> state.relaySessions?.let { pluralStringResource(R.plurals.client_prefs_relay_sessions, it, it) }
    }
    NodeSwitchRow(state, Field.RELAY, R.string.client_prefs_relay_title, R.string.client_prefs_relay_desc, Icons.Default.Hub, save, relayStatus)
    if (relay.enabled && state.overriddenBy(Field.RELAY) == null) {
        val invalid = stringResource(R.string.client_prefs_relay_port_invalid)
        SettingsEditItem(
            title = stringResource(R.string.client_prefs_relay_port_title),
            value = state.relayPortText,
            icon = Icons.Default.SettingsEthernet,
            placeholder = stringResource(R.string.client_prefs_relay_port_any),
            description = stringResource(R.string.client_prefs_relay_port_desc),
        ) {
            if (!state.setRelayPort(context, scope, it, save)) Toast.makeText(context, invalid, Toast.LENGTH_LONG).show()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    NodeSwitchRow(state, Field.APP_CONNECTOR, R.string.client_prefs_connector_title, R.string.client_prefs_connector_desc, Icons.Default.Apps, save)
}
