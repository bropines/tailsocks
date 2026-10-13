package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DriveAddress
import io.github.bropines.tailscaled.core.DriveAddresses
import io.github.bropines.tailscaled.core.storageGrantCommand

/*
 * The Taildrive screen (ui/TaildriveActivity.kt) on a 1080p television, in English and in
 * Russian: with the access to files missing, with it and the shares listed, and the proxy's address
 * card (ui/TaildriveParts.kt) on its own — on the screen it is below the fold, where the remote
 * scrolls to. The way into the other devices' shares is the first row of every screen. A render
 * cannot press a key, so nothing here is focused; the emulator shows where the remote starts.
 */

@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "7-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class DriveTvSizes

private val tvShares = listOf(
    LocalShare("Downloads", "/storage/emulated/0/Download"),
    LocalShare("Movies", "/storage/emulated/0/Movies"),
    LocalShare("Music", "/storage/emulated/0/Music"),
)

@Composable
private fun TvTaildrive(demo: TaildriveDemo) = AdaptiveShowcase(device = DeviceClass.TV) {
    CompositionLocalProvider(LocalTaildriveDemo provides demo) { TaildriveScreen(onBack = {}) }
}

/** Shares listed, the proxy off. */
@PreviewTest @DriveTvSizes @Composable
fun TvTaildriveShares() = TvTaildrive(TaildriveDemo(tvShares))

/** No access to files yet: the page says what to do, and the remote starts on its button. */
@PreviewTest @DriveTvSizes @Composable
fun TvTaildrivePermission() = TvTaildrive(TaildriveDemo(tvShares, storageAccess = false))

/** The device has no settings page for the access: the command to run from a computer. */
@PreviewTest @DriveTvSizes @Composable
fun TvTaildrivePermissionAdb() = TvTaildrive(
    TaildriveDemo(tvShares, storageAccess = false, grantCommand = storageGrantCommand(36, "io.github.bropines.tailscaled"))
)

/** Nothing shared yet. */
@PreviewTest @DriveTvSizes @Composable
fun TvTaildriveEmpty() = TvTaildrive(TaildriveDemo(emptyList()))

// The address card, at the width of the first column of the screen.

@Composable
private fun TvAddress(address: DriveAddress, revealLogin: Boolean = false) = AdaptiveShowcase(device = DeviceClass.TV) {
    Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
        DriveAddressCard(address, revealLogin, onToggleLogin = {}, modifier = Modifier.width(396.dp))
    }
}

private val onTheNetwork = DriveAddresses.of("0.0.0.0", "33445", false, "tailsocks", "", lan = "192.168.1.40")
private val withLogin = DriveAddresses.of("0.0.0.0", "33445", true, "tailsocks", "k3Fq9Zx2", lan = "192.168.1.40")

/** The proxy on every interface: its address on the network, readable, and as a code. */
@PreviewTest @DriveTvSizes @Composable
fun TvAddressOnTheNetwork() = TvAddress(onTheNetwork)

/** …with a login: asked for, not shown, and not in the code. */
@PreviewTest @DriveTvSizes @Composable
fun TvAddressLoginHidden() = TvAddress(withLogin)

/** …and once the user has asked to see it: in the words, and in the code. */
@PreviewTest @DriveTvSizes @Composable
fun TvAddressLoginShown() = TvAddress(withLogin, revealLogin = true)

/** The proxy as it comes, on loopback: only this device can open it, so there is no code. */
@PreviewTest @DriveTvSizes @Composable
fun TvAddressLoopback() = TvAddress(DriveAddresses.of("127.0.0.1", "33445", false, "tailsocks", "", lan = "192.168.1.40"))
