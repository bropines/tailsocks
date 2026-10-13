package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DriveError
import io.github.bropines.tailscaled.core.DriveLocation
import io.github.bropines.tailscaled.core.TaildriveAccess

/*
 * What the tailnet's policy lets a device do with Taildrive, said where a person looks: the
 * Taildrive screen (a verdict beside the switches and the folders that are kept but not shared), the
 * share browser (a device the policy keeps out, Taildrive that does not answer) — on a phone, on a
 * tablet and on a 1080p television, in English and Russian. The Taildrive screen's own states for
 * a daemon that has no map yet ("Connect to see…") are the ones the older previews show.
 */

/** A phone in both languages and a tablet. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class DrivePolicySizes

private val keptShares = listOf(
    LocalShare("Downloads", "/storage/emulated/0/Download"),
    LocalShare("Camera", "/storage/emulated/0/DCIM/Camera"),
    LocalShare("Music", "/storage/emulated/0/Music"),
)

private val nothing = TaildriveAccess.Report(known = true)
private val both = TaildriveAccess.Report(known = true, canShare = true, canAccess = true)
private val shareOnly = TaildriveAccess.Report(known = true, canShare = true)

@Composable
private fun Screen(demo: TaildriveDemo, device: DeviceClass? = null) =
    AdaptiveShowcase(device = device) {
        CompositionLocalProvider(LocalTaildriveDemo provides demo) { TaildriveScreen(onBack = {}) }
    }

// The Taildrive screen ---------------------------------------------------------------------

/** The policy gives the device nothing: both lines say so, the folders are kept and look it, the way to the editor is under the first. */
@PreviewTest @DrivePolicySizes @Composable
fun DrivePolicyRefused() = Screen(TaildriveDemo(keptShares, policy = nothing, canFixPolicy = true))

/** The same where there is no console to fix it in: the lines, and nothing to press. */
@PreviewTest @DrivePolicySizes @Composable
fun DrivePolicyRefusedNoConsole() = Screen(TaildriveDemo(keptShares, policy = nothing))

/** Sharing is allowed, opening the others' folders is not: the way to the editor is under the second line. */
@PreviewTest @DrivePolicySizes @Composable
fun DrivePolicyOpenRefused() = Screen(TaildriveDemo(keptShares, proxy = true, policy = shareOnly, canFixPolicy = true))

/** Everything allowed. */
@PreviewTest @DrivePolicySizes @Composable
fun DrivePolicyAllowed() = Screen(TaildriveDemo(keptShares, proxy = true, policy = both))

/** A television: the row to the other devices' folders carries its line, and no way to the editor is offered. */
@PreviewTest @DriveTvSizes @Composable
fun TvDrivePolicyRefused() = Screen(TaildriveDemo(keptShares, policy = nothing), DeviceClass.TV)

@PreviewTest @DriveTvSizes @Composable
fun TvDrivePolicyAllowed() = Screen(TaildriveDemo(keptShares, policy = both), DeviceClass.TV)

// The share browser ------------------------------------------------------------------------

private val tailnet = DriveLocation(listOf("tail4a2c9.ts.net"))

@Composable
private fun Browser(
    location: DriveLocation,
    error: DriveError,
    actions: DriveBrowserActions = DriveBrowserActions.None,
    device: DeviceClass? = null,
) = AdaptiveShowcase(device = device) { TaildriveBrowserContent(location, BrowserPhase.Failed(error), null, null, actions) }

private val withEditor = DriveBrowserActions({}, {}, {}, {}, {}, {}, onFixPolicy = {})

/** This device may not open shares, and the console can fix it. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserNotPermittedFix() = Browser(DriveLocation.ROOT, DriveError.NotPermitted, withEditor)

/** …and where it cannot: look again, after an admin has. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserNotPermitted() = Browser(DriveLocation.ROOT, DriveError.NotPermitted)

/** Its own Taildrive closed the connection: the causes are the device's, not another's. */
@PreviewTest @DriveBrowserSizes @Composable
fun DriveBrowserNoAnswer() = Browser(DriveLocation.ROOT, DriveError.NoAnswer)
