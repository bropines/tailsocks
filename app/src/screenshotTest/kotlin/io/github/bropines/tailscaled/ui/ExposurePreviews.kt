package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.ExposureFacts
import io.github.bropines.tailscaled.core.ExposureNet
import io.github.bropines.tailscaled.core.RemotePairing
import io.github.bropines.tailscaled.core.ServedPort
import io.github.bropines.tailscaled.core.TaildriveAccess
import io.github.bropines.tailscaled.core.TailcatServing

// What this device exposes (ExposureScreen.kt) on a phone, a tablet and a television, in English
// and in Russian: a quiet device — a fresh install in proxy mode — and a busy one, with Funnel,
// remote management with a guest given full rights for a while, and the proxies open to the
// local network. The facts are made up; the rows are what core/Exposure.kt makes of them.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class ExposureSizes

/** A phone tall enough for the whole of a busy page. */
@Preview(name = "1-phone-tall", device = "spec:width=411dp,height=3300dp,dpi=420")
@Preview(name = "1-phone-tall-ru", device = "spec:width=411dp,height=3300dp,dpi=420", locale = "ru")
annotation class ExposureTall

// 12 Oct 2026, 14:03 UTC, and a guest's full rights until 15:30.
private const val UNTIL = 1_791_819_000L

/** A fresh install as it runs: proxy mode, the listeners on loopback, Downloads shared and the policy silent. */
private val quiet = ExposureFacts(
    running = true,
    socks5 = "127.0.0.1:48115",
    dns = "127.0.0.1:1053",
    taildriveEnabled = true,
    taildriveStorage = true,
    taildriveShares = listOf("Downloads"),
    taildrivePolicy = TaildriveAccess.Verdict.DENIED,
    serveKnown = true,
    automation = true,
    appFunctions = true,
)

private val busy = quiet.copy(
    served = listOf(
        ServedPort(443, paths = listOf("/", "/photos"), funnel = true),
        ServedPort(8080, paths = listOf("/")),
        ServedPort(5432),
    ),
    remote = true,
    pairings = listOf(
        RemotePairing("nP9pixel", "pixel-9-pro", owner = true),
        RemotePairing("nB7ann", "ann-iphone", owner = false, elevatedUntil = UNTIL),
    ),
    remoteFull = true,
    lanAccess = true,
    socks5 = "0.0.0.0:48115",
    socks5Login = true,
    http = "0.0.0.0:8118",
    dns = "0.0.0.0:1053",
    taildrivePolicy = TaildriveAccess.Verdict.ALLOWED,
    taildriveShares = listOf("Downloads", "sdcard"),
    taildriveWholeStorage = true,
    driveProxy = "0.0.0.0:33445",
    driveProxyLogin = true,
    taildropFolder = "Download/Taildrop",
    exitNode = true,
    exitNodeSwitch = true,
    automationToken = true,
    tailcatServer = TailcatServing(anyone = true, keys = 0, ports = listOf(22)),
)

@Composable
private fun ExposurePage(facts: ExposureFacts, device: DeviceClass? = null, offOpen: Boolean = false) =
    AdaptiveShowcase(device = device) { ExposureContent(facts, onBack = {}, offOpenAtFirst = offOpen) }

@PreviewTest @ExposureSizes @Composable
fun ExposureQuiet() = ExposurePage(quiet)

@PreviewTest @ExposureSizes @Composable
fun ExposureBusy() = ExposurePage(busy)

/** The whole of the busy page, what is off unfolded. */
@PreviewTest @ExposureTall @Composable
fun ExposureBusyWhole() = ExposurePage(busy, offOpen = true)

/** The quiet page on the native engine, stopped, incoming connections blocked: the notes under the line at the top. */
@PreviewTest @ExposureTall @Composable
fun ExposureStoppedShields() = ExposurePage(quiet.copy(running = false, net = ExposureNet.INTERFACE, shieldsUp = true, serveKnown = false), offOpen = true)

@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class ExposureTv

@PreviewTest @ExposureTv @Composable
fun ExposureQuietTv() = ExposurePage(quiet, DeviceClass.TV)

@PreviewTest @ExposureTv @Composable
fun ExposureBusyTv() = ExposurePage(tvBusy, DeviceClass.TV)

/** A television has no TailCat (core/DeviceProfile.kt): its rows are not there. */
private val tvBusy = busy.copy(tailcatOffered = false, tailcatServer = null)

/** Settings → Advanced on a phone and a tablet, with the row that opens it. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class ExposureAdvancedSizes

@PreviewTest @ExposureAdvancedSizes @Composable
fun ExposureAdvancedPage() = AdaptiveShowcase {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {},
        initialSection = "advanced",
    )
}
