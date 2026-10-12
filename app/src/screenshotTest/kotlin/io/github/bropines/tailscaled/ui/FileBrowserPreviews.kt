package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.BrowseLocation
import io.github.bropines.tailscaled.core.BrowseRoot
import io.github.bropines.tailscaled.core.FileBrowse
import io.github.bropines.tailscaled.core.FileEntry
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.Listing
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.core.StorageAccess
import java.io.File

// The in-app file browser (FileBrowser.kt) over a made-up disk: the places to start from, a folder
// with every kind of row, files checked, a folder to save into, and the states a folder can be in
// when it shows no rows. A phone upright and a 1080p television, each in English and in Russian.

/** The two windows the browser is drawn for, in both languages. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "3-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "4-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
internal annotation class BrowserSizes

private const val INTERNAL = "/storage/emulated/0"
private const val DOWNLOAD = "$INTERNAL/Download"
private const val TAILDROP = "$DOWNLOAD/Taildrop"
private const val STICK = "/storage/1234-ABCD"

// 12 Oct 2026, 14:03 UTC and earlier.
private const val NOW = 1_791_813_780_000L
private const val DAY = 86_400_000L

private fun dir(path: String, ago: Long = 3 * DAY) = FileEntry(File(path), isDirectory = true, modified = NOW - ago)
private fun file(path: String, size: Long, ago: Long = DAY) = FileEntry(File(path), isDirectory = false, size = size, modified = NOW - ago)

private val places = listOf(
    BrowseRoot(RootKind.START, File(TAILDROP)),
    BrowseRoot(RootKind.PRIMARY, File(INTERNAL)),
    BrowseRoot(RootKind.DOWNLOADS, File(DOWNLOAD)),
    BrowseRoot(RootKind.VOLUME, File(STICK), "SanDisk Ultra", removable = true),
)

/** A disk that never touches the real one: folders by path, the ones listed in [denied] refuse. */
private class FakeDisk(
    private val tree: Map<String, List<FileEntry>>,
    override val access: StorageAccess = StorageAccess.GRANTED,
    private val limited: Set<String> = emptySet(),
    private val denied: Set<String> = emptySet(),
    private val readOnly: Set<String> = emptySet(),
) : FileSource {
    override fun roots(start: File?) = places

    override fun list(dir: File, showHidden: Boolean): Listing = when (val path = dir.path) {
        in denied -> Listing.Unreadable(dir, Listing.Reason.DENIED)
        else -> tree[path]?.let {
            Listing.Entries(dir, FileBrowse.arrange(it, showHidden), limited = path in limited, writable = path !in readOnly)
        } ?: Listing.Unreadable(dir, Listing.Reason.NOT_FOUND)
    }

    override fun describe(file: File): FileEntry? = tree.values.flatten().firstOrNull { it.file == file }
}

private val downloads = listOf(
    dir("$DOWNLOAD/Taildrop"),
    dir("$DOWNLOAD/Movies", ago = 9 * DAY),
    dir("$DOWNLOAD/Отчёты 2026", ago = 20 * DAY),
    dir("$DOWNLOAD/.thumbnails"),
    file("$DOWNLOAD/Quarterly report Q3 2026.pdf", 2_516_582),
    file("$DOWNLOAD/IMG_0042.jpg", 3_250_585, ago = 2 * DAY),
    file("$DOWNLOAD/IMG_0043.jpg", 2_988_410, ago = 2 * DAY),
    file("$DOWNLOAD/IMG_0100.jpg", 3_101_773, ago = 2 * DAY),
    file("$DOWNLOAD/notes.txt", 1_240, ago = 40 * DAY),
    file("$DOWNLOAD/Договор аренды.docx", 90_112, ago = 6 * DAY),
    file("$DOWNLOAD/tailsocks-4.10.1-arm64-v8a.apk", 33_130_086, ago = DAY / 2),
    file("$DOWNLOAD/backup 2026-10-12.zip", 431_914_496, ago = DAY / 3),
    file("$DOWNLOAD/a very long file name that goes on and on to show how a row cuts it short at the end.mkv", 1_503_238_553),
    file("$DOWNLOAD/.nomedia", 0),
)

private val disk = FakeDisk(
    tree = mapOf(
        DOWNLOAD to downloads,
        TAILDROP to emptyList(),
        INTERNAL to listOf(dir("$INTERNAL/Download"), dir("$INTERNAL/DCIM"), dir("$INTERNAL/Documents"), dir("$INTERNAL/Movies"), dir("$INTERNAL/Music")),
        STICK to listOf(
            dir("$STICK/Movies"), dir("$STICK/Photos 2026"),
            file("$STICK/holiday.mp4", 812_647_424), file("$STICK/readme.txt", 4_096),
        ),
    ),
    readOnly = setOf(STICK),
)

/** Android 13 and no access to all files: Download lists nothing, whatever it holds. */
private val noAccess = FakeDisk(
    tree = mapOf(DOWNLOAD to emptyList(), INTERNAL to emptyList()),
    access = StorageAccess.NEEDS_ALL_FILES,
    limited = setOf(DOWNLOAD, INTERNAL),
)

private val unreadable = FakeDisk(tree = emptyMap(), denied = setOf("$INTERNAL/Android/data"))

@Composable
private fun Browser(
    source: FakeDisk,
    mode: FileBrowserMode,
    at: String? = null,
    checked: List<String> = emptyList(),
    confirmLabel: (@Composable (Int) -> String)? = null,
    hidden: Boolean = false,
) = AdaptiveShowcase(null) {
    val location = if (at == null) BrowseLocation.Roots else BrowseLocation.Dir(File(at))
    val listing = (location as? BrowseLocation.Dir)?.let { source.list(it.dir, hidden) }
    val ui = browserUi(
        mode, null, confirmLabel, location, source.roots(null), listing, checked.map(::File), hidden, source.access,
    )
    FileBrowserContent(ui, BrowserActions.None)
}

private val chooseFiles = FileBrowserMode.Files()
private val saveInto = FileBrowserMode.Folder(writable = true)

/** The places to start from: the caller's folder, internal storage, Downloads and a USB stick. */
@PreviewTest @BrowserSizes @Composable
fun BrowserPlaces() = Browser(disk, chooseFiles)

/** A folder with every kind of row: folders first, then files in natural order (IMG_42 before IMG_100). */
@PreviewTest @BrowserSizes @Composable
fun BrowserFolder() = Browser(disk, chooseFiles, at = DOWNLOAD)

/** Two files checked, and the caller's own words on the confirm button. */
@PreviewTest @BrowserSizes @Composable
fun BrowserChecked() = Browser(
    disk, chooseFiles, at = DOWNLOAD,
    checked = listOf("$DOWNLOAD/IMG_0042.jpg", "$DOWNLOAD/backup 2026-10-12.zip"),
    confirmLabel = { n -> pluralStringResource(R.plurals.taildrop_send_files_to, n, n) },
)

/** Choosing a folder to save into: files are shown dimmed and skipped by the remote. */
@PreviewTest @BrowserSizes @Composable
fun BrowserFolderMode() = Browser(disk, saveInto, at = DOWNLOAD)

/** A stick the app can read and not write to, offered as a place to save into. */
@PreviewTest @BrowserSizes @Composable
fun BrowserFolderReadOnly() = Browser(disk, saveInto, at = STICK)

@PreviewTest @BrowserSizes @Composable
fun BrowserEmpty() = Browser(disk, chooseFiles, at = TAILDROP)

@PreviewTest @BrowserSizes @Composable
fun BrowserNoAccess() = Browser(noAccess, chooseFiles, at = DOWNLOAD)

/** The list of places before the grant: a banner over them. */
@PreviewTest @BrowserSizes @Composable
fun BrowserPlacesNoAccess() = Browser(noAccess, chooseFiles)

@PreviewTest @BrowserSizes @Composable
fun BrowserUnreadable() = Browser(unreadable, chooseFiles, at = "$INTERNAL/Android/data")

/** Through the screen itself, not its parts: the start folder is gone, so it opens at the nearest one above. */
@PreviewTest @BrowserSizes @Composable
fun BrowserScreenOpensAtNearest() = AdaptiveShowcase(null) {
    FileBrowserScreen(
        mode = chooseFiles,
        start = File("$DOWNLOAD/Gone/Deeper"),
        source = disk,
        onResult = {},
        onCancel = {},
    )
}
