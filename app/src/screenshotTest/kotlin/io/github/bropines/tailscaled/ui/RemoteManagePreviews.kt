package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.FakeManagedDevice
import io.github.bropines.tailscaled.core.ManagedFetch
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.RemoteContract
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemotePaths
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Remote management as the managing device shows it (ui/RemoteManage*.kt): the page in every
// state, by every kind of caller and device build, on a phone, a tablet and a television, in
// English and in Russian. Nothing here is drawn by hand: each state is what the page's own
// parser makes of a device that answers by the contract (core/RemoteClientFake.kt), so the
// shapes are the real ones. A render cannot press a key, so nothing on the television has the
// focus, and no window opens over the page: what a window holds is drawn on its own.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class ManagePhone

/** A phone as tall as the page: every group at once, as a person scrolling it would meet them. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=2400dp,dpi=280")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=2400dp,dpi=280", locale = "ru")
internal annotation class ManagePhoneLong

@Preview(name = "3-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "4-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
internal annotation class ManageTablet

/** A tablet upright: one column, held to a readable width. */
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
internal annotation class ManageTabletPortrait

@Preview(name = "5-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
internal annotation class ManageTv

/** The devices the previews look at: each is what the page reads off a device that answers by the contract. */
internal object ManageDemo {
    // 12 Oct 2026, 14:03 UTC.
    const val NOW = 1_791_813_780_000L
    private const val PEER = "nKq7Xw2CNTRL"

    /** Every group open: for a render that shows what each holds. */
    val ALL = ManageGroup.entries.toSet()

    /**
     * The page's state as it would be after reading [device]: the status, the settings and the
     * files, each through the page's own parser.
     */
    fun read(device: FakeManagedDevice): RemoteManageUi.Managing {
        val status = RemoteContract.status(device.get(PEER, device.token, RemotePaths.STATUS).body)
        return RemoteManageUi.Managing(
            name = status.name,
            tv = status.tv,
            owner = status.you.owner,
            left = status.you.left,
            status = status,
            app = status.app,
            settings = RemoteContract.settings(device.get(PEER, device.token, RemotePaths.SETTINGS).body),
            files = RemoteContract.files(device.get(PEER, device.token, RemotePaths.FILES).body),
            now = NOW,
            appVersion = "4.11.0",
        )
    }

    fun device(owner: Boolean = true, full: Boolean = false, caps: List<String>? = RemoteContract.ALL_CAPS, shape: FakeManagedDevice.() -> Unit = {}) =
        FakeManagedDevice(clock = { NOW }).apply {
            become(owner = owner, full = full, caps = caps)
            shape()
        }

    /** Where received files go and work: the contract's example has a folder that needs access, which is its own state. */
    fun FakeManagedDevice.receiveReady() = editApp {
        put("receive", buildJsonObject { put("state", "ready"); put("folder", "Download/Taildrop"); put("reset", false); put("free", 51_234_567_890) })
    }

    val fullOwner = read(device(full = true) { receiveReady() })
    val basicOwner = read(device())
    val guest = read(device(owner = false) { receiveReady() })
    val firstBuild = read(device(caps = null))
    val firstBuildGuest = read(device(owner = false, caps = null))

    /** A television that needs looking at: not signed in, an older app, a key about to run out, a warning. */
    val troubled = read(
        device(full = true) {
            editStatus {
                put("running", false)
                put("backendState", "NeedsLogin")
                put("appVersion", "4.10.1")
                put("health", buildJsonArray { add("Tailscale could not connect to any relay server. Check your internet connection.") })
                put("device", buildJsonObject {
                    put("model", "Xiaomi MiTV-MOOQ1"); put("android", "11"); put("sdk", 30); put("build", "0e629420")
                    put("mode", "tun"); put("engine", "native"); put("keyExpiry", NOW / 1000 + 3 * 86_400)
                })
            }
        }
    )
}

@Composable
internal fun ManagePage(ui: RemoteManageUi, tv: Boolean = false, opened: Set<ManageGroup>? = null) =
    AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
        RemoteManageContent(ui, RemoteManageActions.None, RemoteManageHooks(scan = {}, sendFile = {}, openInbox = {}), opened)
    }

/** The page with one group open. */
@Composable
internal fun ManageGroupPage(ui: RemoteManageUi.Managing, group: ManageGroup, tv: Boolean = false) = ManagePage(ui, tv, opened = setOf(group))

// ─── Who is looking, and at what build ───────────────────────────────────────────────────────

/** An owner with Full control: the page as it opens, the two groups it is opened for unfolded. */
@PreviewTest @ManagePhone @Composable
fun ManageOwnerFull() = ManagePage(ManageDemo.fullOwner)

/** The same with every group open, top to bottom. */
@PreviewTest @ManagePhoneLong @Composable
fun ManageOwnerFullAll() = ManagePage(ManageDemo.fullOwner, opened = ManageDemo.ALL)

@PreviewTest @ManageTablet @Composable
fun ManageOwnerFullTablet() = ManagePage(ManageDemo.fullOwner)

@PreviewTest @ManageTabletPortrait @Composable
fun ManageOwnerFullTabletPortrait() = ManagePage(ManageDemo.fullOwner)

@PreviewTest @ManageTv @Composable
fun ManageOwnerFullTv() = ManagePage(ManageDemo.fullOwner, tv = true)

/** An owner at the basic level: what takes Full control is there, disabled, with the one reason. */
@PreviewTest @ManagePhoneLong @Composable
fun ManageOwnerBasicAll() = ManagePage(ManageDemo.basicOwner, opened = ManageDemo.ALL)

@PreviewTest @ManageTablet @Composable
fun ManageOwnerBasicTablet() = ManagePage(ManageDemo.basicOwner, opened = ManageDemo.ALL)

/** A ManageDemo.guest: it looks, picks the exit node, identifies — and is told once why the rest does not answer. */
@PreviewTest @ManagePhoneLong @Composable
fun ManageGuestAll() = ManagePage(ManageDemo.guest, opened = ManageDemo.ALL)

@PreviewTest @ManagePhone @Composable
fun ManageGuest() = ManagePage(ManageDemo.guest)

/** The first build, which announces nothing: exactly what the first version did, as a page. */
@PreviewTest @ManagePhoneLong @Composable
fun ManageFirstBuildAll() = ManagePage(ManageDemo.firstBuild, opened = ManageDemo.ALL)

@PreviewTest @ManagePhone @Composable
fun ManageFirstBuild() = ManagePage(ManageDemo.firstBuild)

@PreviewTest @ManageTv @Composable
fun ManageFirstBuildTv() = ManagePage(ManageDemo.firstBuild, tv = true)

@PreviewTest @ManagePhone @Composable
fun ManageFirstBuildGuest() = ManagePage(ManageDemo.firstBuildGuest)

/** A device that needs a person: the header says so before anything is read. */
@PreviewTest @ManagePhone @Composable
fun ManageTroubled() = ManagePage(ManageDemo.troubled, opened = setOf(ManageGroup.DEVICE))

