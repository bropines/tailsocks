package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.FakeManagedDevice
import io.github.bropines.tailscaled.core.ManagedElevated
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.RemoteAccess
import io.github.bropines.tailscaled.core.RemoteElevation
import io.github.bropines.tailscaled.core.RemoteEvent
import io.github.bropines.tailscaled.core.RemotePairedDevice
import io.github.bropines.tailscaled.core.RemotePolicy
import io.github.bropines.tailscaled.core.RemoteReach
import io.github.bropines.tailscaled.core.RemoteState

// Full rights for a guest for a while (core/RemoteElevate.kt): the question the managed device
// asks before it gives them — offered from its page with a screen lock to confirm with, asked
// for by the guest on a device with none — the device's own page while they last, and the
// managing side of a guest that may ask and of one that has them. Each on a phone and on a
// 1080p television, in English and in Russian. A window cannot be rendered over a page, so the
// question is drawn by what it holds; nothing on the television has the focus.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class ElevatePhone

@Preview(name = "1-phone", device = "spec:width=411dp,height=1900dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=1900dp,dpi=420", locale = "ru")
internal annotation class ElevatePhoneTall

@Preview(name = "3-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "4-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
internal annotation class ElevateTv

// 12 Oct 2026, 14:03 UTC.
private const val NOW = 1_791_813_780_000L
private const val NOW_S = NOW / 1000

// ─── The question ────────────────────────────────────────────────────────────────────────────

/** Offered from the page, on a device with a screen lock: the default length chosen, full control not. */
private val offered = RemoteElevateForm.of(
    name = "ann-iphone", login = "ann@example.org", asked = null, pairingLeft = 75_600, fullOn = false, locked = true,
)

/** Asked for by the guest, for 15 minutes, on a television with no lock at all: the warning says so. */
private val asked = RemoteElevateForm.of(
    name = "ann-iphone", login = "ann@example.org", asked = 900, pairingLeft = 75_600, fullOn = false, locked = false,
)

/** The lock could not be asked for; full control is on at the device, a pairing with five hours left. */
private val failed = RemoteElevateForm.of(
    name = "ann-iphone", login = "", asked = null, pairingLeft = 5 * 3_600, fullOn = true, locked = true,
).copy(full = false, failure = RemoteElevateFailure.LOCK_UNAVAILABLE)

@Composable
private fun Question(form: RemoteElevateForm, tv: Boolean = false) = AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            RemoteElevateQuestion(form, Modifier.widthIn(max = 560.dp))
        }
    }
}

@PreviewTest @ElevatePhone @Composable
fun ElevateQuestionOffered() = Question(offered)

@PreviewTest @ElevateTv @Composable
fun ElevateQuestionOfferedTv() = Question(offered, tv = true)

@PreviewTest @ElevatePhone @Composable
fun ElevateQuestionAskedNoLock() = Question(asked)

@PreviewTest @ElevateTv @Composable
fun ElevateQuestionAskedNoLockTv() = Question(asked, tv = true)

@PreviewTest @ElevatePhone @Composable
fun ElevateQuestionFailed() = Question(failed)

// ─── The managed device's page while they last ───────────────────────────────────────────────

private val policy = RemotePolicy(
    allowGuests = true, codeSeconds = 600, codeAttempts = 5, guestSeconds = 86_400, ownerSeconds = 0, maxPairings = 8,
)

private val running = RemoteState(
    available = true, enabled = true, listening = true, port = 28115, inbound = RemoteState.INBOUND_NETSTACK,
    delegate = true, policy = policy, rev = 7,
)

private val pixel = RemotePairedDevice(
    nodeId = "nP9pixelCNTRL", name = "pixel-9-pro", login = "bropines@example.com", role = "owner",
    created = NOW_S - 9 * 86_400, lastUsed = NOW_S - 130, left = -1,
)
private val ann = RemotePairedDevice(
    nodeId = "nB7annCNTRL", name = "ann-iphone", login = "ann@example.org", role = "guest",
    created = NOW_S - 10_800, lastUsed = NOW_S - 40, left = 75_600, expires = NOW_S + 75_600,
    elevated = RemoteElevation(left = 3_000, until = NOW_S + 3_000, seconds = 3_600, full = true),
)
private val kid = RemotePairedDevice(
    nodeId = "nK4kidCNTRL", name = "kid-tablet", login = "kid@example.org", role = "guest",
    created = NOW_S - 3_600, lastUsed = NOW_S - 600, left = 82_800, expires = NOW_S + 82_800,
)

private fun event(ago: Long, op: String, detail: String, guest: RemotePairedDevice = ann, ok: Boolean = true) =
    RemoteEvent(NOW_S - ago, guest.nodeId, guest.name, if (op == "setting") "owner" else "guest", op, detail, ok)

private val log = listOf(
    event(120, "setting", "accept_routes=true"),
    event(600, "elevated", "3600: full"),
    event(660, "elevate_asked", "3600: shown"),
    event(3_000, "elevation_ended", "taken_back", kid),
    event(3_400, "elevated", "900", kid),
    event(9_000, "elevation_ended", "time", kid),
)

private val managed = RemoteManagedUi(
    enabled = true, state = running, reach = RemoteReach.READY, devices = listOf(pixel, ann, kid), now = NOW,
    access = RemoteAccess(guests = true, elevate = true), log = log, logOpen = true,
)

@PreviewTest @ElevatePhoneTall @Composable
fun ElevateManagedRows() = AdaptiveShowcase(null) { RemoteManagedContent(managed, RemoteManagedActions.None, onBack = {}) }

@PreviewTest @ElevateTv @Composable
fun ElevateManagedRowsTv() = AdaptiveShowcase(null, device = DeviceClass.TV) {
    RemoteManagedContent(managed.copy(logOpen = false), RemoteManagedActions.None, onBack = {})
}

/** The switch off: no guest is offered full rights, and nobody holds them. */
@PreviewTest @ElevatePhoneTall @Composable
fun ElevateManagedSwitchOff() = AdaptiveShowcase(null) {
    RemoteManagedContent(
        managed.copy(elevate = false, devices = listOf(pixel, ann.copy(elevated = null), kid), logOpen = false),
        RemoteManagedActions.None,
        onBack = {},
    )
}

// ─── The managing side ───────────────────────────────────────────────────────────────────────

private fun guestDevice(elevated: ManagedElevated? = null) = FakeManagedDevice(clock = { NOW }).apply {
    become(owner = false, elevated = elevated)
}

/** A guest that may ask, and asked: the device showed a notification. */
private val askedGuest = ManageDemo.read(guestDevice()).copy(rightsAsked = ManagedShown.NOTIFIED)

/** A guest given full rights with full control: an owner's page, and how long it lasts. */
private val elevatedGuest = ManageDemo.read(guestDevice(ManagedElevated(left = 2_700, until = NOW_S + 2_700, seconds = 3_600, full = true)))

@PreviewTest @ElevatePhone @Composable
fun ElevateManageGuestMayAsk() = ManagePage(ManageDemo.read(guestDevice()))

@PreviewTest @ElevatePhone @Composable
fun ElevateManageGuestAsked() = ManagePage(askedGuest)

@PreviewTest @ElevateTv @Composable
fun ElevateManageGuestAskedTv() = ManagePage(askedGuest, tv = true)

@PreviewTest @ElevatePhone @Composable
fun ElevateManageElevated() = ManagePage(elevatedGuest, opened = setOf(ManageGroup.PAIRING))

@PreviewTest @ElevateTv @Composable
fun ElevateManageElevatedTv() = ManagePage(elevatedGuest, tv = true, opened = setOf(ManageGroup.PAIRING))
