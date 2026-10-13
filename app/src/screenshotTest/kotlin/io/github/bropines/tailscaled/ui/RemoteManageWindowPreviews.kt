package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.ManagedPaths
import io.github.bropines.tailscaled.core.ManagedRefusal
import io.github.bropines.tailscaled.core.ManagedResult
import io.github.bropines.tailscaled.core.ManagedStorageList
import io.github.bropines.tailscaled.core.RemoteContract
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemotePairFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

// What the managing page opens over itself, pairing, and the ways in. A window cannot be rendered
// over a page, so each is drawn by what it holds. The fixtures are RemoteManagePreviews.kt's.

// ─── What the page opens over itself ─────────────────────────────────────────────────────────

@Composable
private fun Sheet(tv: Boolean = false, content: @Composable () -> Unit) = AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) { content() }
    }
}

private val picking = ManageDemo.fullOwner.copy(picking = true, exitNodes = RemoteContract.exitNodes())

/** The device's own exit nodes: its own pick first and marked, the one in use checked, the offline one out of reach. */
@PreviewTest @ManagePhone @Composable
fun ManageExitNodes() = Sheet { ExitNodeList(picking, RemoteManageActions.None) }

@PreviewTest @ManageTv @Composable
fun ManageExitNodesTv() = Sheet(tv = true) { ExitNodeList(picking, RemoteManageActions.None) }

/** The first build marks none. */
@PreviewTest @ManagePhone @Composable
fun ManageExitNodesFirstBuild() = Sheet { ExitNodeList(ManageDemo.firstBuild.copy(picking = true, exitNodes = RemoteContract.exitNodes()), RemoteManageActions.None) }

@PreviewTest @ManagePhone @Composable
fun ManageExitNodesLoading() = Sheet { ExitNodeList(ManageDemo.fullOwner.copy(picking = true), RemoteManageActions.None) }

private val logHooks = RemoteManageHooks(shareText = { _, _ -> }, copyText = {})

/** The device's log, with how many secrets it replaced before sending. */
@PreviewTest @ManagePhone @Composable
fun ManageLog() = AdaptiveShowcase(null) {
    val log = RemoteContract.log()
    DiagContent("living-room-tv", RemoteDiagState(log = true, loading = false, text = log.lines.joinToString("\n"), scrubbed = log.scrubbed), RemoteManageActions.None, logHooks)
}

/** On a television: read with the arrows, nothing to hand it to. */
@PreviewTest @ManageTv @Composable
fun ManageLogTv() = AdaptiveShowcase(null, device = DeviceClass.TV) {
    val log = RemoteContract.log()
    DiagContent("living-room-tv", RemoteDiagState(log = true, loading = false, text = log.lines.joinToString("\n"), scrubbed = log.scrubbed), RemoteManageActions.None, RemoteManageHooks())
}

@PreviewTest @ManagePhone @Composable
fun ManageReport() = AdaptiveShowcase(null) {
    DiagContent("living-room-tv", RemoteDiagState(log = false, loading = false, text = RemoteContract.report().text.trimEnd()), RemoteManageActions.None, logHooks)
}

/**
 * The device's storage as the app's browser shows it: the contract's roots and folders, asked
 * of the fake device through the same source the page uses.
 */
private fun storage(): RemoteFileSource {
    val device = ManageDemo.device(full = true) { putFolder("/storage/emulated/0/Movies", 7) }
    return RemoteFileSource(
        list = { path, offset ->
            val body = buildJsonObject { put("path", path); if (offset > 0) put("offset", offset) }.toString()
            val answer = device.post("nKq7Xw2CNTRL", device.token, ManagedPaths.STORAGE_LIST, body)
            RemoteJson.decode<ManagedStorageList>(answer.body)?.takeIf { answer.status == 200 }?.let { ManagedResult.Ok(it) }
                ?: ManagedResult.Failed(ManagedRefusal.NOT_FOUND)
        },
        onFailure = {},
        // As a person who walked down to a folder has it: the folders on the way were listed.
    ).also { it.roots(); it.list(File("/storage/emulated/0")) }
}

/** The places the device's storage starts from: its own, named here, and a stick named as the device names it. */
@PreviewTest @ManagePhone @Composable
fun ManageStorageRoots() = AdaptiveShowcase(null) {
    PickFilesScreen(source = storage(), onPicked = {}, onCancel = {}, multiple = false, title = stringResource(R.string.rmanage_browse_title, "living-room-tv"))
}

/** A folder there, for a file to be sent to this device. */
@PreviewTest @ManagePhone @Composable
fun ManageStorageFolder() = AdaptiveShowcase(null) {
    PickFilesScreen(
        source = storage(), onPicked = {}, onCancel = {}, multiple = false, start = File("/storage/emulated/0/Movies"),
        title = stringResource(R.string.rmanage_browse_title, "living-room-tv"),
    )
}

/** The same browser choosing a folder to share, on a television. */
@PreviewTest @ManageTv @Composable
fun ManageStorageShareTv() = AdaptiveShowcase(null, device = DeviceClass.TV) {
    PickFolderScreen(
        source = storage(), onPicked = {}, onCancel = {}, start = File("/storage/emulated/0/Download"),
        title = stringResource(R.string.rmanage_share_pick_title, "living-room-tv"),
        confirmLabel = stringResource(R.string.rmanage_share_pick_confirm),
    )
}

// ─── Pairing ─────────────────────────────────────────────────────────────────────────────────

/** Not paired yet, and the device shows no code: where to get one. */
@PreviewTest @ManagePhone @Composable
fun ManagePair() = ManagePage(RemoteManageUi.Pairing("living-room-tv", tv = true, codeOnShow = false))

/** A wrong code, with what is left of the guesses. */
@PreviewTest @ManagePhone @Composable
fun ManagePairWrong() = ManagePage(
    RemoteManageUi.Pairing("living-room-tv", tv = true, code = "483901", error = RemotePairError(RemotePairFailure.WRONG, attemptsLeft = 3))
)

@PreviewTest @ManageTablet @Composable
fun ManagePairTablet() = ManagePage(RemoteManageUi.Pairing("living-room-tv", tv = true, code = "483902", busy = true))

@PreviewTest @ManageTv @Composable
fun ManagePairTv() = ManagePage(RemoteManageUi.Pairing("pixel-9-pro", code = "4839"), tv = true)

/** Forgotten here while the device could not be told. */
@PreviewTest @ManagePhone @Composable
fun ManageForgotten() = ManagePage(RemoteManageUi.Forgotten("living-room-tv", tv = true))

// ─── The ways in ─────────────────────────────────────────────────────────────────────────────

/** The entry in a peer's details: nothing until the peer offers management, a mark once it is paired. */
@PreviewTest @ManagePhone @Composable
fun ManageButtons() = AdaptiveShowcase(null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val label = stringResource(R.string.remote_manage_button)
            val paired = stringResource(R.string.rmanage_paired)
            RemoteManageButton(RemoteManageEntryState(label, paired).apply { offered = true; tv = true; this.paired = true }, Modifier.fillMaxWidth())
            RemoteManageButton(RemoteManageEntryState(label, paired).apply { offered = true; tv = true }, Modifier.fillMaxWidth())
            RemoteManageButton(RemoteManageEntryState(label, paired).apply { offered = true }, Modifier.fillMaxWidth())
            // Not offered: draws nothing.
            RemoteManageButton(RemoteManageEntryState(label, paired), Modifier.fillMaxWidth())
        }
    }
}

private val managed = listOf(
    RemoteDeviceRow("nKq7Xw2CNTRL", "living-room-tv", tv = true, owner = true, presence = RemotePresence.ONLINE),
    RemoteDeviceRow("nB3droomCNTRL", "bedroom-tv-with-a-rather-long-name", tv = true, owner = true, presence = RemotePresence.OFFLINE),
    RemoteDeviceRow("nP9annCNTRL", "ann-tablet", tv = false, owner = false, presence = RemotePresence.GONE),
)

/** The devices this one manages, as the Settings card that leads to remote management lists them. */
@PreviewTest @ManagePhone @Composable
fun ManageDevices() = AdaptiveShowcase(null) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            SettingsCard(title = stringResource(R.string.remote_experimental)) {
                SettingsClickableItem(
                    title = stringResource(R.string.remote_title),
                    subtitle = stringResource(R.string.settings_remote_entry_desc),
                    icon = remoteGlyph(false),
                    onClick = {}
                )
                RemoteDeviceRows(managed) {}
            }
        }
    }
}

@PreviewTest @ManageTv @Composable
fun ManageDevicesTv() = AdaptiveShowcase(null, device = DeviceClass.TV) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            SettingsCard(title = stringResource(R.string.remote_experimental)) { RemoteDeviceRows(managed.take(2)) {} }
        }
    }
}

