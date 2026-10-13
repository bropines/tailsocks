package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass

/*
 * The devices this one manages, on the main screen (ui/ManagedOnMain.kt): a phone's pills, the
 * tiles of a tablet's and a television's dashboard, with no device, one, three, and more than
 * have a place. The pairings are made up; whether each is online is read off the demo tailnet,
 * as in the app. The None ones must be the main screen as it is without any of this.
 *
 * A render cannot press a key: where the remote lands is checked on the emulator.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "1-phone-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
internal annotation class ManagedPhoneSizes

@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
internal annotation class ManagedTabletSizes

// Two televisions and a tablet among the demo tailnet's peers: one is on, one is off, and the
// tablet — a guest's pairing — is not in the list at all.
private val tailnet = DemoTailnet.statusJson.replace("raspberry-pi", "living-room-tv").replace("steam-deck", "bedroom-tv")

private val living = RemoteDeviceRow("n6", "living-room-tv", tv = true, owner = true)
private val bedroom = RemoteDeviceRow("n8", "bedroom-tv", tv = true, owner = true)
private val tablet = RemoteDeviceRow("nGONE", "ann-tablet", tv = false, owner = false)
private val more = listOf(RemoteDeviceRow("n2", "homelab-nas"), RemoteDeviceRow("n9", "work-laptop"))

private fun managing(vararg devices: RemoteDeviceRow) = DemoTailnet.data.copy(statusJson = tailnet, managed = devices.toList())

@Composable
private fun Main(data: DemoData, device: DeviceClass? = null) =
    AdaptiveShowcase(data, device = device) { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }

// A phone: pills under the summary, and under the cards where the two stand side by side.

@PreviewTest @ManagedPhoneSizes @Composable
fun ManagedPhoneNone() = Main(managing())

@PreviewTest @ManagedPhoneSizes @Composable
fun ManagedPhoneOne() = Main(managing(living))

@PreviewTest @ManagedPhoneSizes @Composable
fun ManagedPhoneThree() = Main(managing(living, bedroom, tablet))

/** More than have a place: two of them and the way to all. */
@PreviewTest @ManagedPhoneSizes @Composable
fun ManagedPhoneFive() = Main(managing(living, bedroom, tablet, *more.toTypedArray()))

/** This device's own TailSocks is stopped: the names, and nothing claimed of anyone. */
@PreviewTest @PhoneBothLanguages @Composable
fun ManagedPhoneStopped() = Main(managing(living, bedroom).copy(running = false, exitNodeIp = null))

// A tablet's dashboard: tiles under the cards.

@PreviewTest @ManagedTabletSizes @Composable
fun ManagedTabletNone() = Main(managing())

@PreviewTest @ManagedTabletSizes @Composable
fun ManagedTabletOne() = Main(managing(living))

@PreviewTest @ManagedTabletSizes @Composable
fun ManagedTabletThree() = Main(managing(living, bedroom, tablet))

@PreviewTest @ManagedTabletSizes @Composable
fun ManagedTabletFive() = Main(managing(living, bedroom, tablet, *more.toTypedArray()))

// A television's: one tile, beside the tile of the other direction — the device, or the way to all.

@PreviewTest @TvBothLanguages @Composable
fun ManagedTvNone() = Main(managing(), DeviceClass.TV)

@PreviewTest @TvBothLanguages @Composable
fun ManagedTvOne() = Main(managing(living), DeviceClass.TV)

@PreviewTest @TvBothLanguages @Composable
fun ManagedTvThree() = Main(managing(living, bedroom, tablet), DeviceClass.TV)

/** Managed itself and managing two: both directions on one screen. */
@PreviewTest @TvBothLanguages @Composable
fun ManagedTvBothWays() = Main(managing(living, bedroom).copy(remoteTile = RemoteTile(RemoteTile.Kind.PAIRED, paired = 1)), DeviceClass.TV)

@PreviewTest @TvBothLanguages @Composable
fun ManagedTvStopped() = Main(managing(living, bedroom).copy(running = false, exitNodeIp = null), DeviceClass.TV)

/** The television interface switched on by hand on a phone: the tile's row there, at a phone's width. */
@PreviewTest @PhoneAsTvSizes @Composable
fun ManagedPhoneAsTv() = Main(managing(living), DeviceClass.TV)

// The sheet behind "All devices", without its window.

@Composable
private fun AllDevices() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.BottomCenter) {
            val devices = managedNow(
                listOf(living, bedroom, tablet) + more,
                LocalDemo.current?.statusJson?.let { listablePeers(io.github.bropines.tailscaled.core.AppJson.decodeFromString(it)) },
            )
            PickerSheetContent(stringResource(R.string.rmanage_devices_title), managedOptions(devices), selected = null, monospace = false) {}
        }
    }
}

@PreviewTest @PhoneBothLanguages @Composable
fun ManagedAllSheet() = AdaptiveShowcase(managing()) { AllDevices() }

@PreviewTest @TvBothLanguages @Composable
fun ManagedAllSheetTv() = AdaptiveShowcase(managing(), device = DeviceClass.TV) { AllDevices() }
