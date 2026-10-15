package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.ManagedElevated

// Network access to a managed device's files (appctr/remote_files.go), as the managing page
// shows it — closed there, where the files would be, and this device's own lock in front of
// them (ui/RemoteManageFiles.kt) — and the question that gives a guest full rights while they
// are closed. The managing page's fixtures are RemoteManagePreviews.kt's; the managed device's
// own page is in RemoteManagedPreviews.kt. A render cannot put up a fingerprint prompt: what
// the lock says afterwards is drawn instead.

private fun closed(full: Boolean) = ManageDemo.read(
    ManageDemo.device {
        become(full = full, files = false)
        with(ManageDemo) { receiveReady() }
    }
)

/** Full control on there, the files closed: each of their places says so, and where they are opened. */
private val closedFull = closed(full = true)

/** At the basic level as well: the line says Full control is needed too. */
private val closedBasic = closed(full = false)

/** A guest whose full rights were given while the files were closed: switching them on later does not reach it. */
private val rightsWithoutFiles = ManageDemo.read(
    ManageDemo.device(owner = false) {
        become(owner = false, files = true, elevated = ManagedElevated(left = 3_000, until = ManageDemo.NOW / 1000 + 3_000, seconds = 3_600, full = true, noFiles = true))
        with(ManageDemo) { receiveReady() }
    }
)

/** Files open there, a lock on this device that was not asked yet. */
private val open = ManageDemo.read(
    ManageDemo.device {
        become(full = true, files = true)
        with(ManageDemo) { receiveReady() }
    }
)

/** A lock that was never answered: the shared folders wait behind it. */
private fun untouched() = RemoteFilesLock(secure = { true }, ask = {}, now = { ManageDemo.NOW })

/** A device with no lock, after the log was asked for: opened, and said once where it was asked. */
private fun noLock() = RemoteFilesLock(secure = { false }, ask = {}, now = { ManageDemo.NOW }).apply { run(FilesPlace.DIAG) {} }

/** A lock that could not be asked for the storage. */
private fun failed() = RemoteFilesLock(secure = { true }, ask = { answer -> answer(RemoteUnlockOutcome.FAILED) }, now = { ManageDemo.NOW })
    .apply { run(FilesPlace.STORAGE) {} }

@Composable
private fun FilesPage(ui: RemoteManageUi.Managing, groups: Set<ManageGroup>, lock: RemoteFilesLock? = null, tv: Boolean = false) =
    AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
        RemoteManageContent(ui, RemoteManageActions.None, RemoteManageHooks(scan = {}, sendFile = {}, openInbox = {}, files = lock), groups)
    }

private val FILES_GROUPS = setOf(ManageGroup.FILES, ManageGroup.SHARES, ManageGroup.DIAGNOSTICS)

// ─── Closed there ────────────────────────────────────────────────────────────────────────────

/** Every group open: the storage, the shared folders and the diagnostics each say the files are closed. */
@PreviewTest @ManagePhoneLong @Composable
fun ManageFilesClosedAll() = FilesPage(closedFull, ManageDemo.ALL)

@PreviewTest @ManageTv @Composable
fun ManageFilesClosedTv() = FilesPage(closedFull, setOf(ManageGroup.FILES), tv = true)

@PreviewTest @ManageTablet @Composable
fun ManageFilesClosedTablet() = FilesPage(closedFull, FILES_GROUPS)

/** At the basic level the line names Full control as well. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesClosedBasic() = FilesPage(closedBasic, setOf(ManageGroup.DIAGNOSTICS))

/** Full rights given without the files: the line says so instead of where to switch them on. */
@PreviewTest @ManagePhone @Composable
fun ManageFilesClosedRights() = FilesPage(rightsWithoutFiles, setOf(ManageGroup.SHARES))

// ─── This device's lock ──────────────────────────────────────────────────────────────────────

/** The shared folders before this device's lock: their number on the group's line, which and where behind a press. */
@PreviewTest @ManagePhone @Composable
fun ManageSharesBehindLock() = FilesPage(open, setOf(ManageGroup.SHARES), untouched())

@PreviewTest @ManageTv @Composable
fun ManageSharesBehindLockTv() = FilesPage(open, setOf(ManageGroup.SHARES), untouched(), tv = true)

/**
 * No lock on this device: the log opened, and the page says once that anyone using it could —
 * and the shared folders are shown without a press. A phone as tall as the page: the line is
 * under the group's rows.
 */
@PreviewTest @ManagePhoneLong @Composable
fun ManageFilesNoLock() = FilesPage(open, setOf(ManageGroup.SHARES, ManageGroup.DIAGNOSTICS), noLock())

/** The lock could not be asked: nothing was opened, and that is said under the storage. */
@PreviewTest @ManagePhoneLong @Composable
fun ManageFilesUnlockFailed() = FilesPage(open, setOf(ManageGroup.FILES), failed())

// ─── Full rights for a guest, with the files closed ──────────────────────────────────────────

@Composable
private fun Question(form: RemoteElevateForm, tv: Boolean = false) = AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            RemoteElevateQuestion(form, Modifier.widthIn(max = 560.dp))
        }
    }
}

/** Offered from the page with the files closed: full control is still offered, the files are not, and it says so. */
@PreviewTest @ElevatePhone @Composable
fun ElevateQuestionFilesClosed() = Question(
    RemoteElevateForm.of(name = "ann-iphone", login = "ann@example.org", asked = null, pairingLeft = 75_600, fullOn = false, locked = true, files = false)
)

/** Asked for on a television with no lock, Full control on there and the files closed. */
@PreviewTest @ElevateTv @Composable
fun ElevateQuestionFilesClosedTv() = Question(
    RemoteElevateForm.of(name = "ann-iphone", login = "ann@example.org", asked = 3_600, pairingLeft = 75_600, fullOn = true, locked = false, files = false),
    tv = true,
)
