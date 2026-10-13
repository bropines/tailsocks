package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemoteTokenStore
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The devices this one manages: a way back to them that does not go through a peer's details.
 * Each is a row that opens its page (ui/RemoteManageScreen.kt) with one press, whether the
 * device is there or not — a pairing can be forgotten while it is away.
 *
 * Nothing is drawn until this device is paired with one. Someone who never used remote
 * management sees the screens exactly as they were.
 */

/** Whether a managed device is there, as this device's own network map has it. */
enum class RemotePresence {
    ONLINE,
    OFFLINE,
    /** Not in this tailnet any more: removed, or its key expired. */
    GONE,
    /** This device's own TailSocks is not running, so it cannot say. */
    UNKNOWN,
}

/** One device this one is paired with. */
@Immutable
data class RemoteDeviceRow(
    val nodeId: String,
    val name: String,
    val tv: Boolean = false,
    val owner: Boolean = false,
    val presence: RemotePresence = RemotePresence.UNKNOWN,
)

/**
 * The devices [profileId] is paired with: the stored pairings, each with what this device's
 * network map says of the peer. [peers] is that map by StableNodeID, or null when the daemon
 * cannot be asked. Those that are there come first. Blocking: it opens the sealed tokens.
 */
internal fun pairedDevices(tokens: RemoteTokenStore, profileId: String, peers: Map<String, PeerData>?): List<RemoteDeviceRow> =
    tokens.peers(profileId).mapNotNull { nodeId ->
        val record = tokens.get(profileId, nodeId) ?: return@mapNotNull null
        val peer = peers?.get(nodeId)
        RemoteDeviceRow(
            nodeId = nodeId,
            // The name this device knows the peer by now; the one it had when it was paired, while it is away.
            name = peer?.getDisplayName()?.takeIf { it.isNotEmpty() } ?: record.name.ifEmpty { nodeId },
            tv = record.tv,
            owner = record.owner,
            presence = when {
                peers == null -> RemotePresence.UNKNOWN
                peer == null -> RemotePresence.GONE
                peer.online == true -> RemotePresence.ONLINE
                else -> RemotePresence.OFFLINE
            },
        )
    }.sortedWith(compareBy<RemoteDeviceRow> { it.presence.ordinal }.thenBy { it.name.lowercase() })

/** This device's network map by StableNodeID; null when the daemon is not running or does not answer. */
private fun localPeers(): Map<String, PeerData>? = runCatching {
    if (!Appctr.isRunning()) return null
    val json = Appctr.getStatusFromAPI()
    if (json.isBlank() || json.startsWith("Error")) return null
    AppJson.decodeFromString<StatusResponse>(json).peers?.values.orEmpty().mapNotNull { peer -> peer.id?.let { it to peer } }.toMap()
}.getOrNull()

/** How often the list looks again while it is on screen: a device comes and goes. */
private const val DEVICES_EVERY_MS = 10_000L

/**
 * The paired devices as rows of a Settings card, under a line that names them — and nothing
 * at all, not even the line, while there is none. A row opens the device's page. For the
 * card that leads to remote management; call it in that screen's own composition.
 */
@Composable
fun RemoteDevices(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current || LocalDemo.current != null) return
    val context = LocalContext.current.applicationContext
    var devices by remember { mutableStateOf(emptyList<RemoteDeviceRow>()) }
    var opened by remember { mutableStateOf<RemoteDeviceRow?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Read again when a page closes: a device may have been forgotten in it.
    LaunchedEffect(lifecycle, opened == null) {
        if (opened != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                devices = withContext(Dispatchers.IO) {
                    runCatching {
                        val tokens = RemoteManage.tokens(context)
                        val profile = AccountManager.getActiveAccount(context).id
                        // The daemon is asked only once there is something to say of its answer.
                        if (tokens.peers(profile).isEmpty()) emptyList() else pairedDevices(tokens, profile, localPeers())
                    }.getOrDefault(emptyList())
                }
                if (devices.isEmpty()) return@repeatOnLifecycle
                delay(DEVICES_EVERY_MS)
            }
        }
    }
    RemoteDeviceRows(devices, modifier) { opened = it }
    opened?.let { device ->
        RemoteManagePage(
            peerId = device.nodeId,
            name = device.name,
            online = device.presence == RemotePresence.ONLINE,
            tv = device.tv,
            onClose = { opened = null },
        )
    }
}

/** The rows themselves, for [RemoteDevices] and for a preview. */
@Composable
internal fun RemoteDeviceRows(devices: List<RemoteDeviceRow>, modifier: Modifier = Modifier, onOpen: (RemoteDeviceRow) -> Unit) {
    if (devices.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        ManageSubtitle(stringResource(R.string.rmanage_devices_title), Modifier.padding(start = 4.dp))
        devices.forEach { device ->
            val role = stringResource(if (device.owner) R.string.remote_role_owner else R.string.remote_role_guest)
            val presence = when (device.presence) {
                RemotePresence.ONLINE -> stringResource(R.string.rmanage_device_online)
                RemotePresence.OFFLINE -> stringResource(R.string.rmanage_device_offline)
                RemotePresence.GONE -> stringResource(R.string.rmanage_device_gone)
                RemotePresence.UNKNOWN -> null
            }
            // The page's own row, not Settings': a device is no setting, and the search's
            // catalogue (ui/SettingsCatalog.kt) lists every Settings row by its title.
            ManageOpenRow(
                icon = remoteGlyph(device.tv),
                title = device.name.withBreakOpportunities(),
                value = listOfNotNull(presence, role).joinToString(" · "),
                enabled = true,
                onClick = { onOpen(device) }
            )
        }
    }
}
