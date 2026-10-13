package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.RemoteDeviceNotice
import io.github.bropines.tailscaled.core.RemoteDeviceNotice.Kind
import io.github.bropines.tailscaled.core.RemotePromptRules
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The bar a managed device says a paired device's line in (ui/RemoteNoticeBar.kt): "managed
 * from", a change, a request — over a phone's, a tablet's and a television's home screen, in
 * the showcase's dark look and in a light one, in English and in Russian, and on its own in
 * every preset.
 *
 * In the app the bar is a window the theme hosts; a render has no windows, so it is laid over
 * the screen here by the same measures: a phone's gutters and gap, a tablet's and a
 * television's width, the television's safe area. A real phone has a status bar above it.
 * Its words are the app's own, read the way core/RemoteDevicePrompts.kt reads them.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
private annotation class NoticePhone

@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
private annotation class NoticeTablet

private const val MANAGER = "pixel-9-pro"

/** What the manager did, as the bridge reports it: the op and its detail (ui/RemoteLogLine.kt). */
private val EXIT_NODE = "exit_node" to "exit-frankfurt"
private val SETTING = "setting" to "accept_routes=true"

/** A name and a change as long as they come: the first line is cut, the second takes its two rows. */
private const val LONG_MANAGER = "alexanders-galaxy-s24-ultra-work-profile.tail4a2c9.ts.net"
private val LONG_CHANGE = "share_added" to "family-photos-and-videos-archive-2019-2026 (/storage/emulated/0/DCIM/Camera/Archive/Family)"

@Composable
private fun identify(from: String = MANAGER) = RemoteDeviceNotice(
    id = 1, kind = Kind.IDENTIFY, from = from,
    text = stringResource(R.string.remote_notice_identify),
    spoken = stringResource(R.string.remote_prompt_identify, from),
    staysMs = 0, until = Long.MAX_VALUE,
)

@Composable
private fun change(what: Pair<String, String> = EXIT_NODE, from: String = MANAGER): RemoteDeviceNotice {
    val context = LocalContext.current
    val line = remoteEventLine(what.first, what.second, ok = true) { context.getString(it) }
    return RemoteDeviceNotice(
        id = 2, kind = Kind.CHANGE, from = from,
        text = RemotePromptRules.noticeLine(line),
        spoken = RemotePromptRules.noticeText(from, line),
        staysMs = 0, until = Long.MAX_VALUE,
    )
}

@Composable
private fun ask(): RemoteDeviceNotice {
    val permission = stringResource(R.string.remote_prompt_perm_all_files)
    return RemoteDeviceNotice(
        id = 3, kind = Kind.ASK, from = MANAGER,
        text = stringResource(R.string.remote_notice_ask, permission),
        spoken = stringResource(R.string.remote_prompt_ask_toast, MANAGER, permission),
        staysMs = 0, until = Long.MAX_VALUE,
    )
}

/** [screen] with the bar over it, where the host puts its window. */
@Composable
private fun UnderBar(notice: @Composable () -> RemoteDeviceNotice, edge: NoticeEdge = NoticeEdge.TOP, screen: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        screen()
        Box(
            Modifier
                .align(if (edge == NoticeEdge.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                // A television's bar stands on the edge of the safe area the theme has laid around the screen.
                .padding(if (isTv) PaddingValues() else PaddingValues(horizontal = RemoteNoticeGutter, vertical = RemoteNoticeRoom))
                .widthIn(max = RemoteNoticeMaxWidth)
        ) {
            RemoteNoticeBar(notice(), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Home() = MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })

/** The showcase's look: dark, emerald, AMOLED black. */
@Composable
private fun Dark(device: DeviceClass? = null, notice: @Composable () -> RemoteDeviceNotice, edge: NoticeEdge = NoticeEdge.TOP) =
    AdaptiveShowcase(device = device) { UnderBar(notice, edge) { Home() } }

/** The app as it is first installed, by day: light, the default preset. */
@Composable
private fun Light(device: DeviceClass? = null, notice: @Composable () -> RemoteDeviceNotice) {
    CompositionLocalProvider(LocalPreviewDeviceClass provides device) {
        TailSocksTheme(appTheme = "light", themePreset = "default", dynamicColorEnabled = false, amoledModeEnabled = false) {
            CompositionLocalProvider(LocalDemo provides DemoTailnet.data) { UnderBar(notice) { Home() } }
        }
    }
}

// A phone.

@PreviewTest @NoticePhone @Composable
fun NoticeIdentifyPhone() = Dark(notice = { identify() })

@PreviewTest @NoticePhone @Composable
fun NoticeChangePhone() = Dark(notice = { change() })

@PreviewTest @NoticePhone @Composable
fun NoticeIdentifyPhoneLight() = Light(notice = { identify() })

@PreviewTest @NoticePhone @Composable
fun NoticeChangePhoneLight() = Light(notice = { change(SETTING) })

/** A request whose page could not be opened from here: the notification has it, and the bar says so. */
@PreviewTest @NoticePhone @Composable
fun NoticeAskPhone() = Dark(notice = { ask() })

/** The longest it gets: a name that is cut, a change that takes both rows. */
@PreviewTest @NoticePhone @Composable
fun NoticeLongPhone() = Dark(notice = { change(LONG_CHANGE, LONG_MANAGER) })

// A tablet.

@PreviewTest @NoticeTablet @Composable
fun NoticeIdentifyTablet() = Dark(notice = { identify() })

@PreviewTest @NoticeTablet @Composable
fun NoticeChangeTablet() = Dark(notice = { change() })

@PreviewTest @NoticeTablet @Composable
fun NoticeIdentifyTabletLight() = Light(notice = { identify() })

@PreviewTest @NoticeTablet @Composable
fun NoticeChangeTabletLight() = Light(notice = { change(SETTING) })

// A television.

@PreviewTest @TvBothLanguages @Composable
fun NoticeIdentifyTv() = Dark(DeviceClass.TV, notice = { identify() })

@PreviewTest @TvBothLanguages @Composable
fun NoticeChangeTv() = Dark(DeviceClass.TV, notice = { change() })

@PreviewTest @TvBothLanguages @Composable
fun NoticeIdentifyTvLight() = Light(DeviceClass.TV, notice = { identify() })

@PreviewTest @TvBothLanguages @Composable
fun NoticeChangeTvLight() = Light(DeviceClass.TV, notice = { change(SETTING) })

/** Where it goes when what the remote is on lies under its place at the top. */
@PreviewTest @TvSizes @Composable
fun NoticeAtTheBottomTv() = Dark(DeviceClass.TV, notice = { change(LONG_CHANGE) }, edge = NoticeEdge.BOTTOM)

// Every preset: the bar on the screen's background and on a card's.

private val presets = listOf("default", "lavender", "emerald", "sapphire", "amber", "monochrome", "tokionight")

@Composable
private fun Specimens(appTheme: String, amoled: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        for (preset in presets) {
            TailSocksTheme(appTheme = appTheme, themePreset = preset, dynamicColorEnabled = false, amoledModeEnabled = amoled) {
                Row(
                    Modifier.fillMaxWidth().height(77.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(preset, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.width(96.dp))
                    RemoteNoticeBar(identify(), Modifier.weight(1f))
                    Box(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large).padding(8.dp)
                    ) {
                        RemoteNoticeBar(change(), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@PreviewTest @TvSizes @Composable
fun NoticePresetsDark() = Specimens("dark")

@PreviewTest @TvSizes @Composable
fun NoticePresetsBlack() = Specimens("dark", amoled = true)

@PreviewTest @TvSizes @Composable
fun NoticePresetsLight() = Specimens("light")
