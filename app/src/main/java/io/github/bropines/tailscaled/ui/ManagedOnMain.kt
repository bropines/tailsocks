package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemoteTokenStore
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/*
 * The devices this one manages, on the main screen: the list Settings → Advanced keeps
 * (ui/RemoteManageDevices.kt), one press from where the app opens. Nothing of it is drawn, not
 * a gap, until the profile in use holds a pairing: whoever never paired has the screen as it was.
 *
 * It asks nobody. The pairings are a file, read when the screen comes to the front, when the
 * profile changes and when a page that pairs or forgets is left; whether a device is there is
 * read off the peer list the main screen keeps for its summary.
 *
 * Its form is the main screen's. A dashboard has tiles under its cards, the tile the menu
 * already has for a row of its own (WideMenuCard). Where that row is taken — a television,
 * by the tile of the other direction: who manages this device (ui/RemoteTile.kt) — one tile
 * stands beside that one. A phone has a pill, as light as the summary's, that opens something:
 * the device managed last, and a small button beside it for the others. Settings → Appearance
 * hides all of it.
 */

// ─── What is shown ───────────────────────────────────────────────────────────────────────────

/** The devices with an entry of their own, out of [total]. */
@Immutable
data class ManagedOnMain(val shown: List<RemoteDeviceRow>, val total: Int) {
    /** They do not all have a place: one more entry leads to every one of them. */
    val more: Boolean get() = total > shown.size
}

/**
 * [devices] in [places] entries: each its own while they fit; otherwise one place is the way to
 * all of them and the rest go to the first ones, which are those that are there.
 */
internal fun managedOnMain(devices: List<RemoteDeviceRow>, places: Int): ManagedOnMain =
    ManagedOnMain(if (devices.size <= places) devices else devices.take((places - 1).coerceAtLeast(0)), devices.size)

/**
 * The pairings [profileId] holds, each by the name its device had when it was paired and with
 * nothing said of whether it is there. Blocking: it opens the sealed tokens — none, and no key,
 * for a profile that never paired.
 */
internal fun storedPairings(tokens: RemoteTokenStore, profileId: String): List<RemoteDeviceRow> =
    pairedDevices(tokens, profileId, null)

/**
 * [stored] with what [peers] says of each now: the list the main screen already holds, null
 * while it has none — then the names stand without a state. The reading the Settings list makes
 * of the daemon's answer (pairedDevices), made of a list that is already here: the name the
 * tailnet has for the device now, and those that are there first.
 */
internal fun managedNow(stored: List<RemoteDeviceRow>, peers: List<PeerData>?): List<RemoteDeviceRow> {
    if (stored.isEmpty() || peers == null) return stored.map { it.copy(presence = RemotePresence.UNKNOWN) }.inOrder()
    val byId = peers.associateBy { it.id }
    return stored.map { device ->
        val peer = byId[device.nodeId]
        device.copy(
            name = peer?.getDisplayName()?.takeIf { it.isNotEmpty() } ?: device.name,
            presence = when {
                peer == null -> RemotePresence.GONE
                peer.online == true -> RemotePresence.ONLINE
                else -> RemotePresence.OFFLINE
            },
        )
    }.inOrder()
}

private fun List<RemoteDeviceRow>.inOrder(): List<RemoteDeviceRow> =
    sortedWith(compareBy<RemoteDeviceRow> { it.presence.ordinal }.thenBy { it.name.lowercase() })

/**
 * [devices] — in managedNow's order — with the one the main screen names first moved to the
 * front: the device managed [last] while it is there; otherwise the first that is there; and
 * with none there, the one managed last again, then simply the first.
 */
internal fun managedFirst(devices: List<RemoteDeviceRow>, last: String?): List<RemoteDeviceRow> {
    val there = devices.filter { it.presence == RemotePresence.ONLINE }
    val lead = there.firstOrNull { it.nodeId == last }
        ?: there.firstOrNull()
        ?: devices.firstOrNull { it.nodeId == last }
        ?: return devices
    return listOf(lead) + (devices - lead)
}

/** Which device each profile managed last, by StableNodeID: a page that opens for one notes it. */
internal object ManagedLast {
    private const val PREFS = "managed_on_main"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, profileId: String): String? = prefs(context).getString(profileId, null)

    fun note(context: Context, profileId: String, nodeId: String) {
        if (get(context, profileId) != nodeId) prefs(context).edit().putString(profileId, nodeId).apply()
    }

    fun forget(context: Context, profileId: String) = prefs(context).edit().remove(profileId).apply()
}

// ─── What the main screen holds of it ────────────────────────────────────────────────────────

/** The managed devices as the main screen draws them, and what a press on one does. */
@Stable
internal class ManagedDevices(
    val devices: List<RemoteDeviceRow>,
    val open: (RemoteDeviceRow) -> Unit,
    /** Every device, in a sheet: for the entry that stands for those without a place. */
    val openAll: () -> Unit,
    /** Answered by the first entry: where the remote goes when the one it stood on is gone. */
    val first: FocusRequester,
)

/**
 * The devices [profileId] manages, for the main screen, with the page a press opens — the one
 * the peer's details and the Settings list open — and the sheet that lists them all. [peers] is
 * the main screen's own list of the tailnet, null while it has none. With [shown] off (Settings →
 * Appearance) there are none to draw. The pairings are read again each time the screen is shown,
 * when a page opened here is left (a device may have been forgotten in it) and whenever one of
 * [keys] changes: a page of someone else's that pairs.
 *
 * Call it in the screen's own composition: its page and its sheet take the language from there.
 */
@Composable
internal fun rememberManagedDevices(profileId: String, peers: List<PeerData>?, shown: Boolean, vararg keys: Any?): ManagedDevices {
    val demo = LocalDemo.current
    val live = demo == null && !LocalInspectionMode.current
    var stored by remember(profileId) { mutableStateOf(demo?.managed.orEmpty()) }
    var last by remember(profileId) { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<RemoteDeviceRow?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    // A page was opened from an entry here: on a television the remote comes back to that entry.
    var fromEntry by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    if (live) {
        val context = LocalContext.current.applicationContext
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val tv = isTv
        val focus = LocalFocusManager.current
        val window = LocalWindowInfo.current
        LaunchedEffect(lifecycle, profileId, opened == null, *keys) {
            if (opened != null) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val fresh = withContext(Dispatchers.IO) {
                    last = ManagedLast.get(context, profileId)
                    runCatching { storedPairings(RemoteManage.tokens(context), profileId) }.getOrDefault(emptyList())
                }
                val left = fromEntry
                fromEntry = false
                // A device forgotten in its page changes the entries, and on a television the
                // remote stands on one of them: its focus would go with it, to wherever Compose
                // finds a place. With the last device gone it steps off first — to the tile
                // beside, or up — once this window has the remote again; otherwise it is put on
                // the entry that is first now.
                if (tv && left && fresh.map { it.nodeId }.toSet() != stored.map { it.nodeId }.toSet()) {
                    snapshotFlow { window.isWindowFocused }.first { it }
                    withFrameNanos { }
                    if (fresh.isEmpty()) {
                        if (!focus.moveFocus(FocusDirection.Left)) focus.moveFocus(FocusDirection.Up)
                        stored = fresh
                    } else {
                        stored = fresh
                        withFrameNanos { }
                        runCatching { first.requestFocus() }
                    }
                } else {
                    stored = fresh
                }
            }
        }
    }

    val devices = remember(stored, peers, last) { managedFirst(managedNow(stored, peers), last) }

    opened?.let { device ->
        RemoteManagePage(
            peerId = device.nodeId,
            name = device.name,
            online = device.presence == RemotePresence.ONLINE,
            tv = device.tv,
            onClose = { opened = null },
        )
    }
    if (listOpen) {
        PickerSheet(
            title = stringResource(R.string.rmanage_devices_title),
            options = managedOptions(devices),
            onPick = { fromEntry = true; opened = it },
            onDismiss = { listOpen = false },
        )
    }
    return remember(devices, shown) {
        ManagedDevices(if (shown) devices else emptyList(), open = { fromEntry = true; opened = it }, openAll = { listOpen = true }, first = first)
    }
}

/**
 * The rows of the sheet that lists every device, each with what the Settings list says of it:
 * whether it is there, and what this device is to it. The sheet is a window of its own, so its
 * words are resolved by whoever calls this, in the screen's language (see wrapContextWithLocale).
 */
@Composable
internal fun managedOptions(devices: List<RemoteDeviceRow>): List<PickerOption<RemoteDeviceRow>> {
    val owner = stringResource(R.string.remote_role_owner)
    val guest = stringResource(R.string.remote_role_guest)
    return devices.map { device ->
        PickerOption(
            value = device,
            label = device.name,
            icon = remoteGlyph(device.tv),
            supporting = listOfNotNull(presenceWord(device.presence), if (device.owner) owner else guest).joinToString(" · "),
        )
    }
}

/** Whether a device is there, in the Settings list's words; nothing when this device cannot say. */
@Composable
private fun presenceWord(presence: RemotePresence): String? = when (presence) {
    RemotePresence.ONLINE -> stringResource(R.string.rmanage_device_online)
    RemotePresence.OFFLINE -> stringResource(R.string.rmanage_device_offline)
    RemotePresence.GONE -> stringResource(R.string.rmanage_device_gone)
    RemotePresence.UNKNOWN -> null
}

// ─── A dashboard: tiles under the cards ──────────────────────────────────────────────────────

/** The narrowest a tile gets: a card of the grid in its two columns. */
private val TILE_MIN = 176.dp

/** Past three in a row it is a list, and the list is one step away. */
private const val TILE_PLACES = 3

/** MenuGrid's own gap, between its cards and under them. */
private val TILE_GAP = 16.dp

/**
 * The managed devices as a row of tiles under the menu's cards. One device has the row to
 * itself; two or three share it while each keeps a card's width, and otherwise the last tile
 * leads to all of them. Nothing, not the gap above it, while there is no device. Its width
 * decides, so it stands where its width is known: not inside a row or column sized by its
 * content's intrinsic height.
 */
@Composable
internal fun ManagedTiles(managed: ManagedDevices, modifier: Modifier = Modifier) {
    if (managed.devices.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = TILE_GAP)) {
        val places = ((maxWidth + TILE_GAP) / (TILE_MIN + TILE_GAP)).toInt().coerceIn(1, TILE_PLACES)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            ManagedTileCells(managed, places)
        }
    }
}

/**
 * The tiles themselves, [places] of them at most, each with the same share of the row it is
 * called in and as tall as that row: the device's glyph and name, and under the name whether it
 * is there — the tile remote management has on a television (WideMenuCard), which is what one
 * stands beside there. With one place it is the device when there is one, and the way to all of
 * them when there are more.
 */
@Composable
internal fun RowScope.ManagedTileCells(managed: ManagedDevices, places: Int) {
    if (managed.devices.isEmpty()) return
    val entries = managedOnMain(managed.devices, places)
    val manage = stringResource(R.string.remote_manage_button)
    entries.shown.forEachIndexed { i, device ->
        key(device.nodeId) {
            WideMenuCard(
                MenuEntry(
                    title = device.name,
                    icon = remoteGlyph(device.tv),
                    state = presenceWord(device.presence),
                    // Not a state to wait out: the device left the tailnet.
                    stateWarns = device.presence == RemotePresence.GONE,
                    wide = true,
                    onClick = { managed.open(device) },
                ),
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (i == 0) Modifier.focusRequester(managed.first) else Modifier)
                    .semantics { onClick(label = manage, action = null) }
            )
        }
    }
    if (entries.more) {
        // How many of them are there, in the summary's words; how many there are, while this device cannot say.
        val known = managed.devices.none { it.presence == RemotePresence.UNKNOWN }
        val online = managed.devices.count { it.presence == RemotePresence.ONLINE }
        WideMenuCard(
            MenuEntry(
                title = stringResource(R.string.managed_all),
                icon = Icons.Default.Devices,
                state = if (known) stringResource(R.string.summary_online, online, entries.total)
                        else pluralStringResource(R.plurals.managed_count, entries.total, entries.total),
                wide = true,
                onClick = managed.openAll,
            ),
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (entries.shown.isEmpty()) Modifier.focusRequester(managed.first) else Modifier)
        )
    }
}

// ─── A phone: a pill ─────────────────────────────────────────────────────────────────────────

/**
 * One device as a pill, in the summary's shape (ConnectionSummaryRow) and with what the
 * summary's have not: an arrow, for it opens something. It is the device managedFirst names —
 * the one managed last while it is there — by name, with the peers list's dot when it is there
 * and a grey one when it is not. The others are behind a small round button beside it that
 * opens the list of them all. Nothing while there is no device.
 */
@Composable
internal fun ManagedPills(managed: ManagedDevices, modifier: Modifier = Modifier) {
    val lead = managed.devices.firstOrNull() ?: return
    val manage = stringResource(R.string.remote_manage_button)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        key(lead.nodeId) {
            ManagedPill(
                icon = remoteGlyph(lead.tv),
                text = lead.name,
                presence = lead.presence,
                onClick = { managed.open(lead) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .focusRequester(managed.first)
                    .semantics { onClick(label = manage, action = null) },
            )
        }
        val others = managed.devices.size - 1
        if (others > 0) {
            val label = stringResource(R.string.managed_all_count, managed.devices.size)
            val colors = MaterialTheme.colorScheme
            Surface(
                onClick = managed.openAll,
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.Devices, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+$others", style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ManagedPill(icon: ImageVector, text: String, presence: RemotePresence, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val word = presenceWord(presence)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        modifier = modifier.semantics { if (word != null) stateDescription = word },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (presence != RemotePresence.UNKNOWN) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (presence == RemotePresence.ONLINE) PEER_ONLINE_GREEN else colors.outline))
            }
            Spacer(Modifier.width(2.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
