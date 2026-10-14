package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DriveEntry
import io.github.bropines.tailscaled.core.DriveKind
import io.github.bropines.tailscaled.core.DriveLocation
import io.github.bropines.tailscaled.core.KeySendStep
import io.github.bropines.tailscaled.core.RemoteDeviceNotice
import io.github.bropines.tailscaled.core.RemotePromptRules
import io.github.bropines.tailscaled.core.SetupTarget

/*
 * Screens for the 5.0.0 release notes that the other preview files do not draw as a whole
 * screen: a sheet shown over the main screen, as the app opens it, a managed television with
 * a change announced on it, a Taildrive folder of holiday files, a Russian search. Same look
 * as Showcase.kt, same invented tailnet; each in English and Russian, for the two halves of
 * the notes. The pictures are composed from these renders with .claude/tools/release/compose.py.
 */

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** [behind] dimmed by the sheet's scrim, and the sheet resting on the bottom edge with its handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetOver(behind: @Composable () -> Unit, sheet: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        behind()
        Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor))
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = BottomSheetDefaults.ExpandedShape,
            color = BottomSheetDefaults.ContainerColor,
            tonalElevation = BottomSheetDefaults.Elevation,
        ) {
            Column {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                sheet()
            }
        }
    }
}

@Composable
private fun Main() = MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })

// ─── A key sent to a television ───────────────────────────────────────────────

// The television of TvKeySetupPreviews: the same name and address its screen shows beside its code.
private val keyTarget = SetupTarget(ok = true, id = "K7Q2M", addresses = listOf("192.168.1.50"), port = 51234, relay = true)

/** The phone after scanning the television's code: the key typed in, a Headscale server named. */
@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseKeySend() = Showcase {
    SheetOver(behind = { Main() }) {
        KeySendContent(
            target = keyTarget,
            step = KeySendStep.Form,
            key = "tskey-auth-kAbCdEf1CNTRL-0123456789abcdefghijklmnopqrstuv",
            onKey = {},
            server = "https://headscale.example.com",
            onServer = {},
            onPaste = {},
            onSend = {},
            onCancel = {},
            onNext = {},
            onStopWaiting = {},
        )
    }
}

// ─── The devices this phone manages ─────────────────────────────────────────

// As in ManagedOnMainPreviews: two televisions among the demo tailnet's peers, one on and one
// off, and a guest's tablet that has left the tailnet.
private val managedTailnet = DemoTailnet.statusJson.replace("raspberry-pi", "living-room-tv").replace("steam-deck", "bedroom-tv")
private val managedDevices = listOf(
    RemoteDeviceRow("n6", "living-room-tv", tv = true, owner = true),
    RemoteDeviceRow("n8", "bedroom-tv", tv = true, owner = true),
    RemoteDeviceRow("nGONE", "ann-tablet", tv = false, owner = false),
)
private val managing = DemoTailnet.data.copy(statusJson = managedTailnet, managed = managedDevices)

/** The main screen with "+2" pressed: every managed device, and whether it is online. */
@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseManagedSheet() = AdaptiveShowcase(managing) {
    SheetOver(behind = { Main() }) {
        val devices = managedNow(
            managedDevices,
            LocalDemo.current?.statusJson?.let { listablePeers(AppJson.decodeFromString(it)) },
        )
        PickerSheetContent(stringResource(R.string.rmanage_devices_title), managedOptions(devices), selected = null, monospace = false) {}
    }
}

// ─── The television ─────────────────────────────────────────────────────────

// The demo tailnet seen from the television the phone manages: the same tailnet, this device
// named for what it is, and one phone paired with it.
private val television = DemoTailnet.data.copy(
    statusJson = DemoTailnet.statusJson.replace("pixel-9-pro", "living-room-tv"),
    remoteTile = RemoteTile(RemoteTile.Kind.PAIRED, paired = 1),
)

/** The television's home screen, the "From a phone" tile included. */
@PreviewTest
@TvBothLanguages
@Composable
fun ReleaseTvHome() = AdaptiveShowcase(television, device = DeviceClass.TV) { Main() }

/**
 * The same screen with the bar that tells whoever sits in front of it what the phone just
 * changed — laid over the screen where the theme's window puts it, as RemoteNoticePreviews does.
 */
@PreviewTest
@TvBothLanguages
@Composable
fun ReleaseTvChanged() = AdaptiveShowcase(television, device = DeviceClass.TV) {
    val context = LocalContext.current
    val line = remoteEventLine("exit_node", "exit-frankfurt", ok = true) { context.getString(it) }
    val notice = RemoteDeviceNotice(
        id = 2, kind = RemoteDeviceNotice.Kind.CHANGE, from = "pixel-9-pro",
        text = RemotePromptRules.noticeLine(line),
        spoken = RemotePromptRules.noticeText("pixel-9-pro", line),
        staysMs = 0, until = Long.MAX_VALUE,
    )
    Box(Modifier.fillMaxSize()) {
        Main()
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = RemoteNoticeMaxWidth)) {
            RemoteNoticeBar(notice, Modifier.fillMaxWidth())
        }
    }
}

// ─── Taildrive ──────────────────────────────────────────────────────────────

// Fixed dates (October 2026), so a render is the same on every run.
private const val OCT_12 = 1_791_800_000_000L
private const val OCT_03 = 1_791_000_000_000L
private const val AUG_20 = 1_787_200_000_000L

// Folders first, then names in natural order — what TaildriveBrowse.entriesOf hands over.
private val trips = listOf(
    DriveEntry("Iceland 2025", DriveKind.FOLDER, modified = AUG_20),
    DriveEntry("Lake weekend", DriveKind.FOLDER, modified = OCT_03),
    DriveEntry("campfire.m4a", DriveKind.AUDIO, 18_350_080, OCT_03),
    DriveEntry("drone-flyover.mp4", DriveKind.VIDEO, 2_254_857_830, OCT_12),
    DriveEntry("hike-day-1.mp4", DriveKind.VIDEO, 880_803_840, OCT_03),
    DriveEntry("IMG_2041.jpg", DriveKind.IMAGE, 4_404_019, OCT_03),
    DriveEntry("IMG_2042.jpg", DriveKind.IMAGE, 4_194_304, OCT_03),
    DriveEntry("packing-list.txt", DriveKind.TEXT, 1_843, AUG_20),
    DriveEntry("route.gpx", DriveKind.OTHER, 98_304, OCT_03),
    DriveEntry("trail-map.pdf", DriveKind.PDF, 2_411_724, AUG_20),
)

/** Another device's share, two folders deep. */
@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseDriveFolder() = AdaptiveShowcase {
    val at = DriveLocation(listOf("tail4a2c9.ts.net")).child("homelab-nas").child("Photos").child("Trips")
    TaildriveBrowserContent(at, BrowserPhase.Listed(trips), focusName = null, notice = null, actions = DriveBrowserActions.None)
}

// ─── Settings search, in Russian ───────────────────────────────────────────

@Composable
private fun Searching(query: String) = AdaptiveShowcase {
    CompositionLocalProvider(LocalSettingsSearchPreview provides SettingsSearchPreview(query, emptyList())) {
        SettingsScreen(
            onBack = {},
            currentTheme = "dark", onThemeChange = {},
            currentPreset = "emerald", onPresetChange = {},
            currentDynamicColor = false, onDynamicColorChange = {},
            currentAmoledMode = true, onAmoledModeChange = {},
        )
    }
}

/** A Russian question with a slip of the finger in it. */
@PreviewTest
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseSearchTypoRu() = Searching("как сменить язвк")
