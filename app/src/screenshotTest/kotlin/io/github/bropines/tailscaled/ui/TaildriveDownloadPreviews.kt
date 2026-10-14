package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DownloadFailReason
import io.github.bropines.tailscaled.core.DownloadFailure
import io.github.bropines.tailscaled.core.DownloadStage
import io.github.bropines.tailscaled.core.DownloadState
import io.github.bropines.tailscaled.core.DriveEntry
import io.github.bropines.tailscaled.core.DriveKind
import io.github.bropines.tailscaled.core.DriveLocation
import io.github.bropines.tailscaled.core.ReceiveFolder
import io.github.bropines.tailscaled.core.WalkTotals
import java.io.File

/*
 * The share browser's downloads (ui/TaildriveDownloadUi.kt), its path field (ui/TaildrivePathBar.kt)
 * and its link switch with the proxy gone, on a phone and on a television, in English and Russian.
 * Fake names and sizes; a dialog and a snackbar are windows or need a running coroutine the renderer
 * does not give, so they are drawn by hand where they would stand.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class DlPhone

@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "7-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class DlTv

private const val OCT_12 = 1_791_800_000_000L
private const val AUG_20 = 1_787_200_000_000L

private val here = DriveLocation(listOf("tail4a2c9.ts.net", "homelab-nas", "movies", "Sci-Fi"))

private val rows = listOf(
    DriveEntry("Extras", DriveKind.FOLDER, modified = AUG_20),
    DriveEntry("Season 2", DriveKind.FOLDER, modified = OCT_12),
    DriveEntry("Arrival (2016).mp4", DriveKind.VIDEO, 3_400_000_000, OCT_12),
    DriveEntry("Dune.Part.One.2021.2160p.mkv", DriveKind.VIDEO, 19_800_000_000, OCT_12),
    DriveEntry("Dune.Part.One.2021.ru.srt", DriveKind.SUBTITLES, 98_304, OCT_12),
    DriveEntry("poster.jpg", DriveKind.IMAGE, 1_258_291, AUG_20),
    DriveEntry("notes.txt", DriveKind.TEXT, 1_200, AUG_20),
)

private val folder = ReceiveFolder.Dir(File("/storage/emulated/0/Download/Movies"))

private val running = DownloadState(
    id = 1, folderLabel = "Download/Movies", folder = folder, pickedNow = true, stage = DownloadStage.RUNNING,
    files = 12, bytes = 7_200_000_000, filesDone = 4, bytesDone = 2_950_000_000,
    current = "Dune.Part.One.2021.2160p.mkv", currentDone = 820_000_000, currentSize = 1_900_000_000, saved = 4,
    bytesPerSecond = 11_800_000,
)

private val done = running.copy(
    stage = DownloadStage.DONE, filesDone = 12, bytesDone = 7_200_000_000, current = null, saved = 11, bytesPerSecond = null,
    failures = listOf(DownloadFailure("Season 2/Extras/commentary.mka", DownloadFailReason.NETWORK)),
)

@Composable
private fun Shown(device: DeviceClass?, content: @Composable () -> Unit) = AdaptiveShowcase(device = device) { content() }

@Composable
private fun Browser(extras: BrowserExtras, actions: DriveBrowserActions = withSwitch()) =
    TaildriveBrowserContent(here, BrowserPhase.Listed(rows), null, null, actions, extras = extras)

private fun withSwitch(on: Boolean = false) = DriveBrowserActions({}, {}, {}, {}, {}, {}, viaLink = on, onToggleLink = {})

// --- selection mode: three files and a folder --------------------------------------------------

private val selected = setOf("Extras", "Arrival (2016).mp4", "Dune.Part.One.2021.ru.srt", "poster.jpg")

@PreviewTest @DlPhone @Composable
fun DlSelectingPhone() = Shown(null) { Browser(BrowserExtras(selection = selected)) }

@PreviewTest @DlTv @Composable
fun DlSelectingTv() = Shown(DeviceClass.TV) { Browser(BrowserExtras(selection = selected)) }

/** A television's bar before selection mode: the Select button a long press stands in for. */
@PreviewTest @DlTv @Composable
fun DlListTv() = Shown(DeviceClass.TV) { Browser(BrowserExtras()) }

// --- a download under the list -----------------------------------------------------------------

@PreviewTest @DlPhone @Composable
fun DlRunningPhone() = Shown(null) { Browser(BrowserExtras(panel = running to 1)) }

@PreviewTest @DlTv @Composable
fun DlRunningTv() = Shown(DeviceClass.TV) { Browser(BrowserExtras(panel = running to 0)) }

@PreviewTest @DlPhone @Composable
fun DlDonePhone() = Shown(null) { Browser(BrowserExtras(panel = done to 0)) }

@PreviewTest @DlTv @Composable
fun DlDoneTv() = Shown(DeviceClass.TV) { Browser(BrowserExtras(panel = done to 0)) }

// --- the question before a download with folders -----------------------------------------------

/** The dialog's card, drawn where the dialog would stand: its window is out of the renderer's reach. */
@Composable
private fun Question(totals: WalkTotals) {
    Box(Modifier.fillMaxSize()) {
        Browser(BrowserExtras(selection = selected))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 420.dp).padding(24.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.taildrive_download_folders_title), style = MaterialTheme.typography.headlineSmall)
                    Text(folderQuestionBody(1, totals), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = {}) { Text(stringResource(R.string.taildrive_download_files_only)) }
                        TextButton(onClick = {}) { Text(stringResource(R.string.taildrive_download_all)) }
                    }
                }
            }
        }
    }
}

@PreviewTest @DlPhone @Composable
fun DlQuestionPhone() = Shown(null) { Question(WalkTotals(folders = 4, files = 240, bytes = 3_435_973_837, done = true)) }

@PreviewTest @DlTv @Composable
fun DlQuestionCountingTv() = Shown(DeviceClass.TV) { Question(WalkTotals(folders = 2, files = 57, bytes = 1_288_490_189)) }

// --- the path as a field ---------------------------------------------------------------------------

@Composable
private fun missing() = PathEdit(
    "homelab-nas/movies/Horor",
    problem = stringResource(R.string.taildrive_path_missing, "Horor", "homelab-nas/movies"),
    deepest = DriveLocation(listOf("tail4a2c9.ts.net", "homelab-nas", "movies")),
    deepestLabel = "homelab-nas/movies",
)

@PreviewTest @DlPhone @Composable
fun DlPathMissingPhone() = Shown(null) { Browser(BrowserExtras(pathEdit = missing())) }

@PreviewTest @DlTv @Composable
fun DlPathMissingTv() = Shown(DeviceClass.TV) { Browser(BrowserExtras(pathEdit = missing())) }

// --- the link switch on, the proxy off -------------------------------------------------------------

/** The crossed link in the bar, and the line a file opened directly leaves at the bottom. */
@Composable
private fun LinkDown() {
    Box(Modifier.fillMaxSize()) {
        Browser(BrowserExtras(linkDown = true), withSwitch(on = true))
        Snackbar(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            action = { TextButton(onClick = {}) { Text(stringResource(R.string.taildrive_link_turn_on_proxy)) } }
        ) { Text(stringResource(R.string.taildrive_link_opened_directly)) }
    }
}

@PreviewTest @DlPhone @Composable
fun DlLinkDownPhone() = Shown(null) { LinkDown() }

@PreviewTest @DlTv @Composable
fun DlLinkDownTv() = Shown(DeviceClass.TV) { LinkDown() }
