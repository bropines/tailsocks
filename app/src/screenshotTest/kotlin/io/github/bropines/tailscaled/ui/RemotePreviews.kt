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
import io.github.bropines.tailscaled.core.RemoteAttempt
import io.github.bropines.tailscaled.core.RemoteCode
import io.github.bropines.tailscaled.core.RemoteEvent
import io.github.bropines.tailscaled.core.RemoteExitNode
import io.github.bropines.tailscaled.core.RemoteFile
import io.github.bropines.tailscaled.core.RemoteOutcome
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemotePairedDevice
import io.github.bropines.tailscaled.core.RemotePolicy
import io.github.bropines.tailscaled.core.RemoteProblem
import io.github.bropines.tailscaled.core.RemoteReach
import io.github.bropines.tailscaled.core.RemoteSetting
import io.github.bropines.tailscaled.core.RemoteState
import io.github.bropines.tailscaled.core.RemoteStatus
import io.github.bropines.tailscaled.core.RemoteYou

// Remote management (RemoteManagedScreen.kt, RemoteManageScreen.kt) in every state, with a made-up
// bridge: the managed device's page on a phone and on a 1080p television, and the managing sheet's
// content on a phone, each in English and in Russian. A render cannot press a key, so nothing on
// the television is focused: its explanations stay folded, as they are before the remote arrives.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class RemotePhone

/** A phone tall enough for a page that scrolls. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=1300dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=1300dp,dpi=420", locale = "ru")
internal annotation class RemotePhoneTall

@Preview(name = "3-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "4-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
internal annotation class RemoteTv

// 12 Oct 2026, 14:03 UTC.
private const val NOW = 1_791_813_780_000L
private const val NOW_S = NOW / 1000

private val policy = RemotePolicy(
    allowGuests = true, codeSeconds = 600, codeAttempts = 5, guestSeconds = 86_400, ownerSeconds = 0, maxPairings = 8,
)

private fun state(
    listening: Boolean = true,
    inbound: String = RemoteState.INBOUND_NETSTACK,
    pairing: RemoteCode? = null,
    last: RemoteOutcome? = null,
    error: String = "",
    unsaved: Boolean = false,
) = RemoteState(
    available = true, enabled = true, listening = listening, port = 28115, error = error, unsaved = unsaved,
    inbound = inbound, delegate = true, pairing = pairing, lastPairing = last, policy = policy, rev = 7,
)

private val code = RemoteCode(
    code = "483902",
    qr = "tsremote:1?c=483902&h=living-room-tv&n=nKq7Xw2CNTRL",
    left = 582, expires = NOW_S + 582, attemptsLeft = 5,
)

private val codeWithGuess = code.copy(
    left = 341, attemptsLeft = 4,
    lastWrong = RemoteAttempt(name = "ann-iphone", login = "ann@example.org", nodeId = "nB7annCNTRL", time = NOW_S - 95),
)

private val pixel = RemotePairedDevice(
    nodeId = "nP9pixelCNTRL", name = "pixel-9-pro", login = "bropines@example.com", role = "owner",
    created = NOW_S - 9 * 86_400, lastUsed = NOW_S - 130, left = -1,
)
private val ann = RemotePairedDevice(
    nodeId = "nB7annCNTRL", name = "ann-iphone", login = "ann@example.org", role = "guest",
    created = NOW_S - 10_800, lastUsed = 0, left = 75_600, expires = NOW_S + 75_600,
)
private val ipad = RemotePairedDevice(
    nodeId = "nK2ipadCNTRL", name = "kitchen-ipad-with-a-rather-long-name", login = "ann@example.org", role = "guest",
    created = NOW_S - 80_000, lastUsed = NOW_S - 7_500, left = 1_800, expires = NOW_S + 1_800,
)

private val log = listOf(
    RemoteEvent(NOW_S - 130, "nP9pixelCNTRL", "pixel-9-pro", "owner", "exit_node", "exit-frankfurt", true),
    RemoteEvent(NOW_S - 600, "nP9pixelCNTRL", "pixel-9-pro", "owner", "setting", "accept_routes=true", true),
    RemoteEvent(NOW_S - 2_000, "nB7annCNTRL", "ann-iphone", "guest", "denied", "op_not_allowed: settings.set", false),
    RemoteEvent(NOW_S - 4_000, "nP9pixelCNTRL", "pixel-9-pro", "owner", "action", "reconnect (no answer from the app yet)", false),
    RemoteEvent(NOW_S - 9_000, "nP9pixelCNTRL", "pixel-9-pro", "owner", "file_deleted", "holiday.mp4", true),
    RemoteEvent(NOW_S - 10_800, "nB7annCNTRL", "ann-iphone", "guest", "paired", "", true),
    RemoteEvent(NOW_S - 10_900, "nB7annCNTRL", "ann-iphone", "guest", "pair_failed", "wrong code", false),
    RemoteEvent(NOW_S - 86_400, "", "", "", "revoked_all", "2", true),
)

private fun managed(
    state: RemoteState = state(),
    reach: RemoteReach = RemoteReach.READY,
    devices: List<RemotePairedDevice> = emptyList(),
    enabled: Boolean = true,
) = RemoteManagedUi(enabled = enabled, state = state, reach = reach, devices = devices, now = NOW)

private val off = RemoteManagedUi(enabled = false, state = RemoteState(available = true, port = 28115, policy = policy), now = NOW)
private val offWithDevices = off.copy(devices = listOf(pixel))
private val closing = offWithDevices.copy(state = state(), reach = RemoteReach.CLOSING)
private val ready = managed(state(last = RemoteOutcome(RemoteOutcome.EXPIRED, NOW_S - 300)))
private val showingCode = managed(state(pairing = codeWithGuess), devices = listOf(pixel, ann))
private val freshCode = managed(state(pairing = code))
private val notForwarded = managed(state(inbound = RemoteState.INBOUND_NONE), RemoteReach.NOT_FORWARDED, listOf(pixel))
private val notRunning = managed(
    RemoteState(available = true, enabled = true, port = 28115, policy = policy), RemoteReach.NOT_RUNNING, listOf(pixel, ann),
)
private val portBusy = managed(state(listening = false, error = "listen tcp4 127.0.0.1:28115: bind: address already in use"), RemoteReach.PORT_BUSY)
private val full = managed(
    state(last = RemoteOutcome(RemoteOutcome.PAIRED, NOW_S - 10_800, "ann-iphone", "ann@example.org", "nB7annCNTRL", "guest"), unsaved = true),
    devices = listOf(pixel, ann, ipad),
).copy(log = log, logOpen = true, tagged = true, notice = RemoteNotice.NOT_SAVED)
private val locked = managed(
    state(last = RemoteOutcome(RemoteOutcome.LOCKED, NOW_S - 60, "ann-iphone", "ann@example.org", "nB7annCNTRL")),
    devices = listOf(pixel),
).copy(notice = RemoteNotice.NO_CODE)

@Composable
private fun Managed(ui: RemoteManagedUi, tv: Boolean = false) =
    AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
        RemoteManagedContent(ui, RemoteManagedActions.None, onBack = {})
    }

/** Off, as it ships: the switch and its folded explanation, nothing else. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedOff() = Managed(off)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedOffTv() = Managed(off, tv = true)

/** Off with a pairing left from before: it is listed, and can be revoked. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedOffPaired() = Managed(offWithDevices)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedOffPairedTv() = Managed(offWithDevices, tv = true)

/** Switched off a moment ago, and the service has not closed the endpoint yet: that is said. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedClosing() = Managed(closing)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedClosingTv() = Managed(closing, tv = true)

/** On and reachable, nobody paired, the last code ran out. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedReady() = Managed(ready)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedReadyTv() = Managed(ready, tv = true)

/** A code just put on show. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedCode() = Managed(freshCode)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedCodeTv() = Managed(freshCode, tv = true)

/** A code with one wrong guess made at it, and two devices paired: an owner and a guest. */
@PreviewTest @RemotePhoneTall @Composable
fun RemoteManagedCodeGuessed() = Managed(showingCode)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedCodeGuessedTv() = Managed(showingCode, tv = true)

/** A daemon that does not pass the port on: nobody can connect, and no code is offered. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedNotForwarded() = Managed(notForwarded)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedNotForwardedTv() = Managed(notForwarded, tv = true)

/** The daemon is not running. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedNotRunning() = Managed(notRunning)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedNotRunningTv() = Managed(notRunning, tv = true)

/** Another app holds the port. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedPortBusy() = Managed(portBusy)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedPortBusyTv() = Managed(portBusy, tv = true)

/** The last code died of wrong guesses, and a new one could not be made. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagedLocked() = Managed(locked)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedLockedTv() = Managed(locked, tv = true)

/** Everything at once: three devices, a tagged node, a removal not yet on disk, the log open. */
@PreviewTest @RemotePhoneTall @Composable
fun RemoteManagedFull() = Managed(full)

@PreviewTest @RemoteTv @Composable
fun RemoteManagedFullTv() = Managed(full, tv = true)
