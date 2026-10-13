package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DriveLan
import io.github.bropines.tailscaled.core.DriveLanSettings

/*
 * "Allow from the local network" on the Taildrive screen (ui/TaildriveLanParts.kt, the proxy's
 * part of ui/TaildriveActivity.kt): the switch off, on and at a custom address, and the notice of
 * a proxy that was open before the switch existed — on a phone and on a television, in English
 * and in Russian. The proxy's part of the page is longer than either screen, and a render cannot
 * scroll: both are drawn taller than they are, so that the whole of it shows. The address card
 * (ui/TaildriveParts.kt) is drawn on its own as well, in the cases only it tells apart.
 */

@Preview(name = "1-phone-tall", device = "spec:width=411dp,height=1500dp,dpi=420")
@Preview(name = "1-phone-tall-ru", device = "spec:width=411dp,height=1500dp,dpi=420", locale = "ru")
annotation class DriveLanPhoneSizes

@Preview(name = "6-tv-tall", device = "spec:width=960dp,height=1300dp,dpi=320")
@Preview(name = "7-tv-tall-ru", device = "spec:width=960dp,height=1300dp,dpi=320", locale = "ru")
annotation class DriveLanTvSizes

@Preview(name = "1-phone", device = "spec:width=411dp,height=420dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=420dp,dpi=420", locale = "ru")
annotation class DriveLanCardSizes

private val lanShares = TaildriveDemo(shares = listOf(LocalShare("Downloads", "/storage/emulated/0/Download")), proxy = true)

/** The proxy as it comes: this device only, no login. */
private val lanOff = DriveLanSettings(bind = DriveLan.LOOPBACK, auth = false, user = DriveLan.DEFAULT_USER, password = "")

/** What the switch makes of it: every interface, the login on, and what it was remembered. */
private val lanOn = DriveLan.allow(lanOff) { "k3Fq9Zx2Wm" }

/** An address typed into the field under Advanced. */
private val lanCustom = lanOff.copy(bind = "192.168.1.77")

/** An install older than the switch: every interface, typed by hand, and no login. */
private val lanOpen = lanOff.copy(bind = DriveLan.EVERY_INTERFACE)

@Composable
private fun LanScreen(settings: DriveLanSettings, device: DeviceClass? = null, lans: List<String> = listOf("192.168.1.40")) =
    AdaptiveShowcase(device = device) {
        CompositionLocalProvider(LocalTaildriveDemo provides lanShares, LocalDriveLanDemo provides DriveLanDemo(settings, lans)) {
            TaildriveScreen(onBack = {})
        }
    }

// A phone.

/** Off: the switch, the port, the login switch free, the bind address folded, the address to copy as before. */
@PreviewTest @DriveLanPhoneSizes @Composable
fun PhoneDriveLanOff() = LanScreen(lanOff)

/** On: the login switch on and held, the login under it, the address on the network to read, copy and scan. */
@PreviewTest @DriveLanPhoneSizes @Composable
fun PhoneDriveLanOn() = LanScreen(lanOn)

/** A custom address: named by the row, which wears a badge where the switch was; the field unfolded. */
@PreviewTest @DriveLanPhoneSizes @Composable
fun PhoneDriveLanCustom() = LanScreen(lanCustom)

/** The migration: on, as it was typed, and still open — the notice and its one action. */
@PreviewTest @DriveLanPhoneSizes @Composable
fun PhoneDriveLanOpenNotice() = LanScreen(lanOpen)

// A television.

@PreviewTest @DriveLanTvSizes @Composable
fun TvDriveLanOff() = LanScreen(lanOff, DeviceClass.TV)

@PreviewTest @DriveLanTvSizes @Composable
fun TvDriveLanOn() = LanScreen(lanOn, DeviceClass.TV)

@PreviewTest @DriveLanTvSizes @Composable
fun TvDriveLanCustom() = LanScreen(lanCustom, DeviceClass.TV)

@PreviewTest @DriveLanTvSizes @Composable
fun TvDriveLanOpenNotice() = LanScreen(lanOpen, DeviceClass.TV)

// The address card of a phone, which has a clipboard: the cases the screens above do not reach.

@Composable
private fun PhoneCard(settings: DriveLanSettings, lans: List<String>, revealLogin: Boolean = false) = AdaptiveShowcase {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.padding(16.dp)) {
            DriveAddressCard(DriveLan.address(settings, "33445", lans), revealLogin, onToggleLogin = {}, onCopy = {})
        }
    }
}

/** On two networks at once — Wi-Fi and a hotspot of its own: both addresses, the first in the code. */
@PreviewTest @DriveLanCardSizes @Composable
fun PhoneDriveLanCardTwoNetworks() = PhoneCard(lanOn, listOf("192.168.1.40", "192.168.43.1"))

/** On, and on no network: nothing for another device to open, and the card says why. */
@PreviewTest @DriveLanCardSizes @Composable
fun PhoneDriveLanCardNoNetwork() = PhoneCard(lanOn, emptyList())

/** The login asked for: on the card, and in the code. */
@PreviewTest @DriveLanCardSizes @Composable
fun PhoneDriveLanCardLoginShown() = PhoneCard(lanOn, listOf("192.168.1.40"), revealLogin = true)
