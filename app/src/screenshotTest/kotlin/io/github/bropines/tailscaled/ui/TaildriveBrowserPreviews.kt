package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DriveEntry
import io.github.bropines.tailscaled.core.DriveError
import io.github.bropines.tailscaled.core.DriveKind
import io.github.bropines.tailscaled.core.DriveLocation

/*
 * The Taildrive browser (ui/TaildriveBrowser.kt) in the states a remote walks through, on a phone
 * and on a 1080p television (960 x 540 dp), in English and Russian. Fake names and sizes, no
 * network: the page is drawn from its state, and the first frame is all the renderer takes.
 * Same look as Showcase.kt: dark, emerald, AMOLED.
 */

/** A phone and a 1080p television, each in both languages. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "7-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class DriveBrowserSizes

private const val TN = "tail4a2c9.ts.net"

// Fixed dates (October 2026), so a render is the same on every run.
private const val OCT_12 = 1_791_800_000_000L
private const val OCT_03 = 1_791_000_000_000L
private const val AUG_20 = 1_787_200_000_000L

private val devices = listOf(
    DriveEntry("homelab-nas", DriveKind.DEVICE),
    DriveEntry("desktop-home", DriveKind.DEVICE),
    DriveEntry("macbook-air", DriveKind.DEVICE),
    DriveEntry("raspberry-pi", DriveKind.DEVICE),
    DriveEntry("work-laptop", DriveKind.DEVICE),
)

private val shares = listOf(
    DriveEntry("Backups", DriveKind.SHARE),
    DriveEntry("Documents", DriveKind.SHARE),
    DriveEntry("Movies", DriveKind.SHARE),
    DriveEntry("Music", DriveKind.SHARE),
    DriveEntry("Photos", DriveKind.SHARE),
)

// Folders first, then names in natural order — what TaildriveBrowse.entriesOf hands over.
private val sciFi = listOf(
    DriveEntry("Extras", DriveKind.FOLDER, modified = AUG_20),
    DriveEntry("Season 2", DriveKind.FOLDER, modified = OCT_03),
    DriveEntry("Season 10", DriveKind.FOLDER, modified = OCT_12),
    DriveEntry("Arrival (2016).mp4", DriveKind.VIDEO, 3_400_000_000, OCT_03),
    DriveEntry("Dune.Part.One.2021.2160p.UHD.BluRay.x265.HDR.DDP5.1.Atmos-GROUP.mkv", DriveKind.VIDEO, 19_800_000_000, OCT_12),
    DriveEntry("Dune.Part.One.2021.ru.srt", DriveKind.SUBTITLES, 98_304, OCT_12),
    DriveEntry("Soundtrack 01 - Opening.flac", DriveKind.AUDIO, 44_040_192, AUG_20),
    DriveEntry("poster.jpg", DriveKind.IMAGE, 1_258_291, AUG_20),
    DriveEntry("notes.txt", DriveKind.TEXT, 1_200, AUG_20),
    DriveEntry("Manual.pdf", DriveKind.PDF, 2_411_724, AUG_20),
    DriveEntry("disc.iso", DriveKind.OTHER, 7_730_941_132, AUG_20),
)

private val devicesAt = DriveLocation(listOf(TN))
private val sharesAt = devicesAt.child("homelab-nas")
private val folderAt = sharesAt.child("Movies").child("Sci-Fi")

@Composable
private fun Page(
    location: DriveLocation,
    phase: BrowserPhase,
    focusName: String? = null,
    notice: OpenNotice? = null,
) = AdaptiveShowcase { TaildriveBrowserContent(location, phase, focusName, notice, BrowserActions.None) }

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserDevices() = Page(devicesAt, BrowserPhase.Listed(devices))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserShares() = Page(sharesAt, BrowserPhase.Listed(shares))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserFolder() = Page(folderAt, BrowserPhase.Listed(sciFi))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserLoading() = Page(folderAt, BrowserPhase.Loading)

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserEmptyFolder() = Page(folderAt, BrowserPhase.Listed(emptyList()))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserEmptyDevices() = Page(devicesAt, BrowserPhase.Listed(emptyList()))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserUnreachable() = Page(sharesAt.child("Movies"), BrowserPhase.Failed(DriveError.Unreachable))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserDenied() = Page(sharesAt.child("Backups"), BrowserPhase.Failed(DriveError.AccessDenied))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserProxyOff() = Page(DriveLocation.ROOT, BrowserPhase.Failed(DriveError.ProxyOff))

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserProxyStarting() = Page(DriveLocation.ROOT, BrowserPhase.StartingProxy)

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserDaemonOff() = Page(DriveLocation.ROOT, BrowserPhase.Failed(DriveError.NotConnected))

// The dialog is a window, which the renderer does not have: its card is drawn over the
// dimmed folder, where the dialog would stand.
@Composable
private fun NoAppOver(showAddress: Boolean) = AdaptiveShowcase {
    val labels = NoticeLabels(
        title = stringResource(if (showAddress) R.string.taildrive_browser_address_title else R.string.taildrive_browser_noapp_title),
        body = stringResource(R.string.taildrive_browser_noapp_body),
        addressHelp = stringResource(R.string.taildrive_browser_address_help),
        showAddress = stringResource(R.string.taildrive_browser_noapp_show_address),
        close = stringResource(R.string.action_close),
        qrDescription = stringResource(R.string.qr_image),
        qrTooLong = stringResource(R.string.qr_too_long),
    )
    Box(Modifier.fillMaxSize()) {
        TaildriveBrowserContent(folderAt, BrowserPhase.Listed(sciFi), null, null, BrowserActions.None)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 560.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                NoAppCard(
                    name = "Dune.Part.One.2021.2160p.UHD.BluRay.x265.HDR.DDP5.1.Atmos-GROUP.mkv",
                    address = "http://100.100.100.100:8080/$TN/homelab-nas/Movies/Sci-Fi/Dune.Part.One.2021.2160p.UHD.BluRay.x265.HDR.DDP5.1.Atmos-GROUP.mkv",
                    showAddress = showAddress,
                    labels = labels,
                    onShowAddress = {},
                    onClose = {}
                )
            }
        }
    }
}

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserNoApp() = NoAppOver(showAddress = false)

@PreviewTest
@DriveBrowserSizes
@Composable
fun DriveBrowserNoAppAddress() = NoAppOver(showAddress = true)
