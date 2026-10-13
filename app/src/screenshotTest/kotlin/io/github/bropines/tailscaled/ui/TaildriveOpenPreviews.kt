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
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DriveEntry
import io.github.bropines.tailscaled.core.DriveKind
import io.github.bropines.tailscaled.core.DriveLocation

/*
 * Handing a share's file to another app (core/TaildriveOpenPlan.kt): the dialog for a media file
 * that only a web browser would take — nothing is opened until the person says so — and the switch
 * in the bar that puts the link road first. Phone and 1080p television, English and Russian.
 */

private val tailnet = DriveLocation(listOf("tail4a2c9.ts.net"))
private val folder = tailnet.child("homelab-nas").child("Movies")

private val films = listOf(
    DriveEntry("Season 2", DriveKind.FOLDER),
    DriveEntry("Arrival (2016).mp4", DriveKind.VIDEO, 3_400_000_000),
    DriveEntry("Dune.2021.mkv", DriveKind.VIDEO, 19_800_000_000),
    DriveEntry("poster.jpg", DriveKind.IMAGE, 1_258_291),
)

// The dialog is a window, which the renderer does not have: its card is drawn over the dimmed folder.
@Composable
private fun OnlyBrowserOver(showAddress: Boolean) = AdaptiveShowcase {
    val labels = NoticeLabels(
        title = stringResource(if (showAddress) R.string.taildrive_browser_address_title else R.string.taildrive_open_browseronly_title),
        body = stringResource(R.string.taildrive_open_browseronly_body),
        addressHelp = stringResource(R.string.taildrive_browser_address_help),
        showAddress = stringResource(R.string.taildrive_browser_noapp_show_address),
        close = stringResource(R.string.action_close),
        qrDescription = stringResource(R.string.qr_image),
        qrTooLong = stringResource(R.string.qr_too_long),
        openInBrowser = stringResource(R.string.taildrive_open_in_browser),
    )
    Box(Modifier.fillMaxSize()) {
        TaildriveBrowserContent(folder, BrowserPhase.Listed(films), null, null, DriveBrowserActions.None)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                NoAppCard(
                    name = "Arrival (2016).mp4",
                    address = "http://100.100.100.100:8080/tail4a2c9.ts.net/homelab-nas/Movies/Arrival%20%282016%29.mp4",
                    showAddress = showAddress,
                    labels = labels,
                    onShowAddress = {},
                    onClose = {},
                    onOpenInBrowser = {}
                )
            }
        }
    }
}

/** Nothing here plays the file from a link, only a browser takes it: the choice is the person's, and "Show the address" has the focus. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserOnlyBrowser() = OnlyBrowserOver(showAddress = false)

/** …the address, large, and as a code for another device. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserOnlyBrowserAddress() = OnlyBrowserOver(showAddress = true)

private val deviceRows = listOf(DriveEntry("homelab-nas", DriveKind.DEVICE), DriveEntry("desktop-home", DriveKind.DEVICE))

/** The bar's switch for the link road, on. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserViaLink() = AdaptiveShowcase {
    TaildriveBrowserContent(tailnet, BrowserPhase.Listed(deviceRows), null, null, DriveBrowserActions({}, {}, {}, {}, {}, {}, viaLink = true, onToggleLink = {}))
}

/** …and off, the way files open from the device. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserViaDocument() = AdaptiveShowcase {
    TaildriveBrowserContent(tailnet, BrowserPhase.Listed(deviceRows), null, null, DriveBrowserActions({}, {}, {}, {}, {}, {}, viaLink = false, onToggleLink = {}))
}
