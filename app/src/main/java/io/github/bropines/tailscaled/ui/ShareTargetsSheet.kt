package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DaemonStatus
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.ShareTargetMode
import io.github.bropines.tailscaled.core.ShareTargetPicks
import io.github.bropines.tailscaled.core.ShareTargets
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A device the share menu can offer, as the picker below lists it. */
private class ShareTargetDevice(val peer: PeerData, val id: String, val online: Boolean)

private sealed interface ShareDevicesLoad {
    data object Loading : ShareDevicesLoad
    /** No netmap to list from: the daemon is stopped or not logged in. */
    data object NotRunning : ShareDevicesLoad
    class Ready(val devices: List<ShareTargetDevice>) : ShareDevicesLoad
}

/**
 * The words of [ShareTargetsSheet], resolved by the caller: the sheet is a window of its own
 * and would look them up in the system language (see wrapContextWithLocale()).
 */
private class ShareTargetsWords(
    val title: String,
    val desc: String,
    val overCapacity: String,
    val notRunning: String,
    val empty: String,
    val auto: String,
    val pinned: String,
    val hidden: String,
    val pinAll: String,
    val reset: String,
    val online: String,
    val offline: String,
    /** TalkBack's name for the TailSocks mark on a device's icon. */
    val tailsocks: String,
)

/** The devices the share menu can offer: the ones the daemon would take a file for now or later. */
private fun loadShareDevices(context: Context): ShareDevicesLoad {
    val running = runCatching { Appctr.isRunning() && Appctr.getBackendState() == "Running" }.getOrDefault(false)
    if (!running) return ShareDevicesLoad.NotRunning
    return runCatching {
        val strings = TaildropReasonStrings.from(context)
        val devices = taildropPickerPeers(DaemonStatus.readOrThrow(), strings).mapNotNull { p ->
            val id = p.id?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when (taildropStatusOf(p, strings)) {
                TaildropStatus.Available -> ShareTargetDevice(p, id, online = true)
                is TaildropStatus.Offline -> ShareTargetDevice(p, id, online = false)
                is TaildropStatus.Blocked -> null
            }
        }
        ShareDevicesLoad.Ready(devices)
    }.getOrElse { ShareDevicesLoad.NotRunning }
}

/**
 * Settings → Sharing & access → Choose devices: per device of the active account, whether
 * the share menu offers it on its own (recent and online ones), always (pinned, first) or
 * never (hidden). A choice is stored at once; the service publishes the new row
 * (core/ShareTargets.kt). [onChanged] hands the new picks back to the settings row.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShareTargetsSheet(profileId: String, onChanged: (ShareTargetPicks) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val capacity = remember { ShareTargets.capacity(context) }
    val words = ShareTargetsWords(
        title = stringResource(R.string.share_targets_pick_title),
        desc = stringResource(R.string.share_targets_pick_desc, capacity),
        overCapacity = stringResource(R.string.share_targets_pick_over_capacity, capacity),
        notRunning = stringResource(R.string.share_targets_pick_not_running),
        empty = stringResource(R.string.share_targets_pick_empty),
        auto = stringResource(R.string.share_targets_mode_auto),
        pinned = stringResource(R.string.share_targets_mode_pinned),
        hidden = stringResource(R.string.share_targets_mode_hidden),
        pinAll = stringResource(R.string.share_targets_pin_all),
        reset = stringResource(R.string.share_targets_reset),
        online = stringResource(R.string.share_targets_online),
        offline = stringResource(R.string.share_targets_offline),
        tailsocks = stringResource(R.string.peer_client_tailsocks),
    )
    var picks by remember { mutableStateOf(GlobalSettings.getShareTargetPicks(context, profileId)) }
    val load by produceState<ShareDevicesLoad>(ShareDevicesLoad.Loading) {
        value = withContext(Dispatchers.IO) { loadShareDevices(context) }
    }
    fun update(new: ShareTargetPicks) {
        picks = new
        GlobalSettings.setShareTargetPicks(context, profileId, new)
        onChanged(new)
    }

    AppModalSheet(onDismissRequest = onDismiss) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .animateContentSize()
        ) {
            Text(
                words.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                words.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )
            when (val l = load) {
                // Held at a height while the list comes, so the sheet does not open short and jump.
                ShareDevicesLoad.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) { LoadingIndicator() }
                ShareDevicesLoad.NotRunning -> SheetNote(words.notRunning)
                is ShareDevicesLoad.Ready -> {
                    val devices = l.devices
                    if (devices.isEmpty()) {
                        SheetNote(words.empty)
                    } else {
                        Row(
                            Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { update(ShareTargetPicks(pinned = picks.pinned + devices.map { it.id }, hidden = picks.hidden - devices.map { it.id }.toSet())) },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.tvInitialFocus()
                            ) { Text(words.pinAll) }
                            OutlinedButton(
                                onClick = { update(ShareTargetPicks()) },
                                enabled = !picks.isEmpty,
                                shape = MaterialTheme.shapes.medium
                            ) { Text(words.reset) }
                        }
                        AnimatedVisibility(devices.count { picks.modeOf(it.id) == ShareTargetMode.PINNED } > capacity) {
                            Text(
                                words.overCapacity,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)
                            )
                        }
                        LazyColumn(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(devices, key = { it.id }) { d ->
                                ShareTargetRow(d, picks.modeOf(d.id), words) { update(picks.with(d.id, it)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShareTargetRow(d: ShareTargetDevice, mode: ShareTargetMode, words: ShareTargetsWords, onMode: (ShareTargetMode) -> Unit) {
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(shape = MaterialTheme.shapes.medium, color = ring, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PeerOsIcon(d.peer, size = 38.dp, iconSize = 22.dp, ring = ring, tailsocksLabel = words.tailsocks)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.peer.getDisplayName(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (d.online) words.online else words.offline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((m, label) in listOf(ShareTargetMode.AUTO to words.auto, ShareTargetMode.PINNED to words.pinned, ShareTargetMode.HIDDEN to words.hidden)) {
                        FilterChip(
                            selected = mode == m,
                            onClick = { onMode(m) },
                            label = { Text(label) },
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
            }
        }
    }
}
