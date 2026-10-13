package io.github.bropines.tailscaled.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.BrowseLocation
import io.github.bropines.tailscaled.core.BrowseRoot
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.FileBrowse
import io.github.bropines.tailscaled.core.FileEntry
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.Listing
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.core.StorageAccess
import io.github.bropines.tailscaled.core.TaildropFolder
import io.github.bropines.tailscaled.core.TaildropHistory
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import kotlinx.serialization.encodeToString
import java.io.File

/*
 * Taildrop on a television: the Files screen's Taildrop page drawn as that class of device
 * (AdaptiveShowcase(device = TV)) — the safe area, no back arrow, the inbox in a pane of its
 * own — with the receive folder a directory, as a television has it, and the app's own
 * browser in the place of the system's picker. English and Russian: the pane was too narrow
 * for a card's buttons in the second.
 *
 * A render presses no key, so nothing is in focus; what the remote does is checked on the
 * Android TV emulator.
 */

/** A 1080p television, in both languages. */
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class TvBothLanguages

private const val FOLDER = "/storage/emulated/0/Download/Taildrop"
private const val MIN = 60_000L

/** [TaildropDemo]'s history with the saved copies where a television keeps them: files of [FOLDER], not documents of a tree. */
private val tvHistoryJson: String
    get() = AppJson.encodeToString<List<TaildropHistoryEntry>>(
        TaildropHistory.decode(TaildropDemo.folderHistoryJson).map { e ->
            if (e.savedUri == null) e else e.copy(savedUri = "file://$FOLDER/" + Uri.encode(e.savedTo.orEmpty().substringAfterLast('/')))
        }
    )

/** The folder a television starts with, two files it took and two it could not. */
private val tvData get() = TaildropDemo.data.copy(taildropHistoryJson = tvHistoryJson, taildropFolder = FOLDER)

@Composable
private fun TvTaildropScreen(
    data: DemoData,
    seenAt: Long? = null,
    folderState: TaildropFolder.State? = null,
    openEntryAt: Long? = null,
) = AdaptiveShowcase(data, device = DeviceClass.TV) {
    FilesScreen(onBack = {}, openTaildrop = true, openEntryAt = openEntryAt, inboxSeenAt = seenAt, folderStateAtStart = folderState)
}

/** The page as it opens: the inbox with its folder's line on the left, the devices a file can go to on the right. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildrop() = TvTaildropScreen(tvData)

/** A file arriving: its card heads the inbox for as long as it arrives. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropIncoming() = TvTaildropScreen(TaildropDemo.receiving.copy(taildropHistoryJson = tvHistoryJson, taildropFolder = FOLDER))

/** The inbox last looked at three hours ago: the two files that came since are set apart, newest first. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropInboxNew() = TvTaildropScreen(tvData, seenAt = System.currentTimeMillis() - 180 * MIN)

/** The folder cannot be written — Android 10 and the Storage permission not given: files wait, and the line says what to do. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropNeedsAccess() = TvTaildropScreen(
    TaildropDemo.noFolder.copy(taildropFolder = FOLDER),
    seenAt = System.currentTimeMillis() - 60 * MIN,
    folderState = TaildropFolder.State.NEEDS_ACCESS,
)

/** Nothing received yet: the folder's line over an empty pane. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropEmpty() = TvTaildropScreen(TaildropDemo.empty.copy(taildropFolder = FOLDER))

/** An entry of the history, in the pane beside the inbox. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropEntry() {
    // One reading of the demo for the screen and for the entry: its times are counted from now.
    val data = tvData
    val entry = TaildropHistory.decode(data.taildropHistoryJson.orEmpty()).first { it.name == "boarding-pass.pdf" }
    TvTaildropScreen(data, openEntryAt = entry.timestamp)
}

/** A television with the interface enlarged to 125%: the window is medium, and the inbox folds into the grid. */
@PreviewTest
@Preview(name = "6-tv-125", device = "spec:width=768dp,height=432dp,dpi=400")
@Preview(name = "6-tv-125-ru", device = "spec:width=768dp,height=432dp,dpi=400", locale = "ru")
@Composable
fun TvTaildropScaled() = AdaptiveShowcase(tvData, device = DeviceClass.TV) {
    FilesScreen(onBack = {}, openTaildrop = true, inboxOpen = true, inboxSeenAt = System.currentTimeMillis() - 180 * MIN)
}

// --- the app's browser where the system has no picker ------------------------------------------

private const val INTERNAL = "/storage/emulated/0"
private const val DOWNLOAD = "$INTERNAL/Download"
private const val STICK = "/storage/1234-ABCD"

// 12 Oct 2026, 14:03 UTC and earlier.
private const val NOW = 1_791_813_780_000L
private const val DAY = 86_400_000L

private fun dir(path: String, ago: Long = 3 * DAY) = FileEntry(File(path), isDirectory = true, modified = NOW - ago)
private fun file(path: String, size: Long, ago: Long = DAY) = FileEntry(File(path), isDirectory = false, size = size, modified = NOW - ago)

/** A television's storage: what is built in, and a stick in its USB port. */
private object TvDisk : FileSource {
    private val tree = mapOf(
        STICK to listOf(
            dir("$STICK/Movies"), dir("$STICK/Photos 2026"),
            file("$STICK/holiday-2026.mp4", 812_647_424),
            file("$STICK/IMG_0042.jpg", 3_250_585, ago = 2 * DAY),
            file("$STICK/IMG_0043.jpg", 2_988_410, ago = 2 * DAY),
            file("$STICK/Отчёт за третий квартал.pdf", 2_516_582, ago = 6 * DAY),
        ),
        DOWNLOAD to listOf(dir("$DOWNLOAD/Taildrop"), file("$DOWNLOAD/tailsocks-4.10.1-x86_64.apk", 33_130_086, ago = DAY / 2)),
    )

    override fun roots(start: File?) = listOf(
        BrowseRoot(RootKind.PRIMARY, File(INTERNAL)),
        BrowseRoot(RootKind.DOWNLOADS, File(DOWNLOAD)),
        BrowseRoot(RootKind.VOLUME, File(STICK), "SanDisk Ultra", removable = true),
    )

    override fun list(dir: File, showHidden: Boolean): Listing =
        tree[dir.path]?.let { Listing.Entries(dir, FileBrowse.arrange(it, showHidden), writable = true) }
            ?: Listing.Unreadable(dir, Listing.Reason.NOT_FOUND)

    override fun describe(file: File): FileEntry? = tree.values.flatten().firstOrNull { it.file == file }

    override val access = StorageAccess.GRANTED
}

/** The browser as a Taildrop picker opens it (ui/TaildropPickers.kt), with the caller's title and button. */
@Composable
private fun TvPicker(
    mode: FileBrowserMode,
    at: String,
    title: String,
    checked: List<String> = emptyList(),
    confirmLabel: (@Composable (Int) -> String)? = null,
) = AdaptiveShowcase(null, device = DeviceClass.TV) {
    val location = BrowseLocation.Dir(File(at))
    val ui = browserUi(
        mode, title, confirmLabel, location, TvDisk.roots(null), TvDisk.list(location.dir, false), checked.map(::File), false, TvDisk.access,
    )
    FileBrowserContent(ui, BrowserActions.None)
}

/** "Send file" for a device, on a television: files of the USB stick, two of them checked. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropPickFiles() = TvPicker(
    FileBrowserMode.Files(),
    at = STICK,
    title = stringResource(R.string.taildrop_pick_send_to_format, "desktop-home"),
    checked = listOf("$STICK/holiday-2026.mp4", "$STICK/IMG_0042.jpg"),
    confirmLabel = { n -> pluralStringResource(R.plurals.taildrop_pick_send_files, n, n) },
)

/** The folder received files go to, changed from the inbox's line. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropPickFolder() = TvPicker(
    FileBrowserMode.Folder(writable = true),
    at = DOWNLOAD,
    title = stringResource(R.string.taildrop_pick_folder_title),
    confirmLabel = { stringResource(R.string.taildrop_pick_folder_confirm) },
)

/** Save for a file the folder did not take: a place for this one file. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildropSaveCopy() = TvPicker(
    FileBrowserMode.Folder(writable = true),
    at = STICK,
    title = stringResource(R.string.taildrop_save_copy_title_format, "contract-scan.pdf"),
    confirmLabel = { stringResource(R.string.taildrop_save_copy_confirm) },
)
