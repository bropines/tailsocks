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
import io.github.bropines.tailscaled.core.BrowseLocation
import io.github.bropines.tailscaled.core.BrowseRoot
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.FileEntry
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.Listing
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.core.StorageAccess
import java.io.File

/*
 * What the walk of a signed build on a television changed on screen, as far as a render can show
 * it: the sign-in that says a key was refused, the browser of another device's storage when that
 * device cannot read its files, and the Taildrive card for a file nothing opens. What is a window
 * (the dialogs, the sheets) and what is a key (where the remote starts) is checked on the emulator.
 */

@Composable
private fun Tv(content: @Composable () -> Unit) = AdaptiveShowcase(null, device = DeviceClass.TV, content = content)

// The sign-in after a key the server did not accept: the daemon's reason above the way in that is left.

@PreviewTest @TvBothLanguages @Composable
fun TvSignInKeyRefused() = Tv {
    TvSignInScreen(
        account = "Default",
        url = "https://login.tailscale.com/a/1a2b3c4d5e6f7",
        slow = false,
        renewing = false,
        error = null,
        onNewCode = {},
        onClose = {},
        keyNotice = stringResource(R.string.tv_signin_key_refused_reason, "invalid pre auth key"),
    )
}

/** The daemon named no reason, and the code is still on its way. */
@PreviewTest @TvBothLanguages @Composable
fun TvSignInKeyRefusedNoReason() = Tv {
    TvSignInScreen(
        account = "Default",
        url = null,
        slow = false,
        renewing = false,
        error = null,
        onNewCode = {},
        onClose = {},
        keyNotice = stringResource(R.string.tv_signin_key_refused),
    )
}

// The storage of another device, which has not been given the access to all files.

private const val INTERNAL = "/storage/emulated/0"

private class LockedDisk : FileSource {
    override fun roots(start: File?) = listOf(BrowseRoot(RootKind.PRIMARY, File(INTERNAL)))
    override fun list(dir: File, showHidden: Boolean): Listing = Listing.Entries(dir, emptyList(), limited = true, writable = true)
    override fun describe(file: File): FileEntry? = null
    override val access: StorageAccess get() = StorageAccess.GRANTED
}

@Composable
private fun RemoteStorage(askable: Boolean) = Tv {
    val source = LockedDisk()
    val at = File("$INTERNAL/Documents")
    val ui = browserUi(
        FileBrowserMode.Files(multiple = false), "Storage of living-room-tv", null,
        BrowseLocation.Dir(at), source.roots(null), source.list(at, false), emptyList(), false, source.access,
    ).copy(remoteName = "living-room-tv", canGrant = askable)
    FileBrowserContent(ui, BrowserActions.None)
}

@PreviewTest @TvBothLanguages @Composable
fun TvRemoteStorageNoAccess() = RemoteStorage(askable = true)

/** The device cannot be asked from here: the page says what is so and offers nothing. */
@PreviewTest @TvBothLanguages @Composable
fun TvRemoteStorageNoAccessCannotAsk() = RemoteStorage(askable = false)

// A file in a share that nothing here opens: not a player to install, for anything but media.

@Composable
private fun NoAppCardOver(body: Int, showAddress: Boolean) = Tv {
    val labels = NoticeLabels(
        title = stringResource(if (showAddress) R.string.taildrive_browser_address_title else R.string.taildrive_browser_noapp_title),
        body = stringResource(body),
        addressHelp = stringResource(R.string.taildrive_browser_address_help),
        showAddress = stringResource(R.string.taildrive_browser_noapp_show_address),
        close = stringResource(R.string.action_close),
        qrDescription = stringResource(R.string.qr_image),
        qrTooLong = stringResource(R.string.qr_too_long),
    )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            NoAppCard(
                name = "data.bin",
                address = "http://100.100.100.100:8080/tvemu1/downloads/data.bin",
                showAddress = showAddress,
                labels = labels,
                onShowAddress = {},
                onClose = {},
            )
        }
    }
}

@PreviewTest @TvBothLanguages @Composable
fun TvTaildriveNoAppForOtherFile() = NoAppCardOver(R.string.taildrive_browser_noapp_other_body, showAddress = false)

/** The address, within the screen: the code beside the text, and the button that closes it. */
@PreviewTest @TvBothLanguages @Composable
fun TvTaildriveAddressFits() = NoAppCardOver(R.string.taildrive_browser_noapp_other_body, showAddress = true)
