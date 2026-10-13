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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.FakeManagedDevice
import io.github.bropines.tailscaled.core.ManagedFetch
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.RemoteContract
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemotePaths
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The groups of the managing page (ui/RemoteManageGroups.kt), each open on its own, in the states
// worth looking at. The fixtures are RemoteManagePreviews.kt's.

// ─── The groups ──────────────────────────────────────────────────────────────────────────────

/** Reconnecting: the device is away for a few seconds, and that is said instead of an error. */
@PreviewTest @ManagePhone @Composable
fun ManageReconnecting() = ManageGroupPage(ManageDemo.fullOwner.copy(reconnecting = true), ManageGroup.CONNECTION)

/** Identify, answered. */
@PreviewTest @ManagePhone @Composable
fun ManageIdentified() = ManageGroupPage(ManageDemo.fullOwner.copy(identified = ManagedShown.SHOWN), ManageGroup.CONNECTION)

@PreviewTest @ManagePhone @Composable
fun ManageIdentifyUnseen() = ManageGroupPage(ManageDemo.guest.copy(identified = ManagedShown.WAITING), ManageGroup.CONNECTION)

/** A file on its way from this device. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesSending() = ManageGroupPage(ManageDemo.fullOwner.copy(send = RemoteSendState(progress = "2/3\nholiday-2026.mp4")), ManageGroup.FILES)

/** Sent, and one that was refused, with the daemon's own reason. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesSent() = ManageGroupPage(
    ManageDemo.fullOwner.copy(send = RemoteSendState(sent = listOf("holiday-2026.mp4", "notes.txt"), failed = listOf("backup.zip" to "HTTP 507: not enough space"))),
    ManageGroup.FILES
)

/** Said before a file is picked: the device cannot receive now. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesNotRunning() = ManageGroupPage(ManageDemo.read(ManageDemo.device(full = true) { editStatus { put("running", false); put("backendState", "Stopped") } }), ManageGroup.FILES)

/** Said before a file is picked: this device's own daemon will not send to it. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesBlocked() = ManageGroupPage(ManageDemo.guest.copy(sendBlocked = "This device belongs to another user."), ManageGroup.FILES)

/** The receive folder needs storage access: where files wait meanwhile, and the two things to do. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesNeedsAccess() = ManageGroupPage(ManageDemo.read(ManageDemo.device(full = true)), ManageGroup.FILES)

/** Asked for a file from the device's storage: on its way, and arrived. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesFetching() = ManageGroupPage(
    ManageDemo.read(ManageDemo.device(full = true) {
        with(ManageDemo) { receiveReady() }
        editStatus { put("fetch", buildJsonObject { put("id", "t3"); put("name", "film.mkv"); put("size", 734_003_200); put("state", ManagedFetch.SENDING); put("error", "") }) }
    }),
    ManageGroup.FILES
)

@PreviewTest @ManagePhone @Composable
fun ManageFilesFetched() = ManageGroupPage(
    ManageDemo.read(ManageDemo.device(full = true) {
        with(ManageDemo) { receiveReady() }
        editStatus { put("fetch", buildJsonObject { put("id", "t3"); put("name", "film.mkv"); put("size", 734_003_200); put("state", ManagedFetch.SENT); put("error", ""); put("age", 180) }) }
    }),
    ManageGroup.FILES
)

@PreviewTest @ManageTv @Composable
fun ManageFilesTv() = ManageGroupPage(ManageDemo.fullOwner, ManageGroup.FILES, tv = true)

@PreviewTest @ManagePhone @Composable
fun ManageShares() = ManageGroupPage(
    ManageDemo.read(ManageDemo.device(full = true) {
        editApp {
            put("folders", buildJsonArray {
                add(buildJsonObject { put("name", "Downloads"); put("path", "/storage/emulated/0/Download") })
                add(buildJsonObject { put("name", "Movies_from_the_living_room"); put("path", "/storage/1234-ABCD/Video/Films and series/2026") })
            })
            put("folderCount", 2)
        }
    }),
    ManageGroup.SHARES
)

@PreviewTest @ManagePhone @Composable
fun ManageSharesBasic() = ManageGroupPage(ManageDemo.basicOwner, ManageGroup.SHARES)

@PreviewTest @ManagePhone @Composable
fun ManageSharesGuest() = ManageGroupPage(ManageDemo.guest, ManageGroup.SHARES)

/** What the app holds, and a request that was answered "shown". */
@PreviewTest @ManagePhone @Composable
fun ManagePermissions() = ManageGroupPage(ManageDemo.fullOwner.copy(asked = mapOf("storage" to ManagedShown.SHOWN)), ManageGroup.PERMISSIONS)

/** A request that still waits for someone to open TailSocks there. */
@PreviewTest @ManagePhone @Composable
fun ManagePermissionsWaiting() = ManageGroupPage(
    ManageDemo.read(ManageDemo.device { editApp { put("asked", buildJsonObject { put("id", "install"); put("result", "notified"); put("age", 42) }) } }),
    ManageGroup.PERMISSIONS
)

@PreviewTest @ManagePhone @Composable
fun ManagePermissionsGuest() = ManageGroupPage(ManageDemo.guest, ManageGroup.PERMISSIONS)

@PreviewTest @ManageTv @Composable
fun ManagePermissionsTv() = ManageGroupPage(ManageDemo.fullOwner, ManageGroup.PERMISSIONS, tv = true)

@PreviewTest @ManagePhone @Composable
fun ManageDevice() = ManageGroupPage(
    ManageDemo.read(ManageDemo.device(full = true) {
        putSetting("""{"key": "advertise_exit_node", "kind": "bool", "writable": true, "value": "true"}""")
        putSetting("""{"key": "advertise_routes", "kind": "cidrs", "writable": true, "value": "192.168.5.0/24,10.20.0.0/16"}""")
        editStatus {
            put("advertise", buildJsonObject {
                put("exitNode", buildJsonObject { put("on", true); put("approved", false) })
                put("routes", buildJsonArray {
                    add(buildJsonObject { put("cidr", "192.168.5.0/24"); put("approved", true) })
                    add(buildJsonObject { put("cidr", "10.20.0.0/16"); put("approved", false) })
                })
            })
        }
    }),
    ManageGroup.DEVICE
)

@PreviewTest @ManagePhone @Composable
fun ManageDeviceBasic() = ManageGroupPage(ManageDemo.basicOwner, ManageGroup.DEVICE)

@PreviewTest @ManagePhone @Composable
fun ManageDiagnostics() = ManageGroupPage(ManageDemo.fullOwner, ManageGroup.DIAGNOSTICS)

@PreviewTest @ManagePhone @Composable
fun ManageDiagnosticsBasic() = ManageGroupPage(ManageDemo.basicOwner, ManageGroup.DIAGNOSTICS)

@PreviewTest @ManagePhone @Composable
fun ManagePairing() = ManageGroupPage(ManageDemo.fullOwner, ManageGroup.PAIRING)

@PreviewTest @ManagePhone @Composable
fun ManagePairingGuest() = ManageGroupPage(ManageDemo.guest, ManageGroup.PAIRING)

