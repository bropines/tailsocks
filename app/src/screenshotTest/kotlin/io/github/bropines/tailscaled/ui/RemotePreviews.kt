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
    RemoteEvent(NOW_S - 2_000, "nB7annCNTRL", "ann-iphone", "guest", "denied", "settings.set: not for a guest", false),
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

// ─── The managing device ────────────────────────────────────────────────────────────────────

private const val TV_NAME = "living-room-tv"

private val status = RemoteStatus(
    running = true, backendState = "Running", version = "1.104.1", appVersion = "4.11.0", deviceClass = "tv",
    name = TV_NAME, dnsName = "living-room-tv.tail1234.ts.net", nodeId = "nKq7Xw2CNTRL",
    exitNode = RemoteExitNode(id = "nFRAexitCNTRL", name = "exit-frankfurt", online = true, selected = true, country = "Germany", city = "Frankfurt"),
    since = NOW_S - 40_000, you = RemoteYou("owner", -1),
)

private val settings = listOf(
    RemoteSetting("accept_dns", "bool", writable = true, value = "true"),
    RemoteSetting("accept_routes", "bool", writable = true, value = "false"),
    RemoteSetting("exit_node_lan_access", "bool", writable = true, value = "true"),
    RemoteSetting("device_class", "text", value = "tv"),
    RemoteSetting("app_version", "text", value = "4.11.0"),
)

private val files = listOf(
    RemoteFile("holiday.mp4", 812_647_424, NOW_S - 86_400),
    RemoteFile("Quarterly report Q3 2026 with a name that does not fit in one line.pdf", 2_516_582, NOW_S - 3 * 86_400),
    RemoteFile("Договор аренды.docx", 90_112, NOW_S - 6 * 86_400),
)

private val exitNodes = listOf(
    RemoteExitNode("nAMSexitCNTRL", "exit-amsterdam", online = true, country = "Netherlands", city = "Amsterdam"),
    RemoteExitNode("nFRAexitCNTRL", "exit-frankfurt", online = true, selected = true, country = "Germany", city = "Frankfurt"),
    RemoteExitNode("nHOMEexitCNTRL", "home-router", online = false),
    RemoteExitNode("nHELexitCNTRL", "exit-helsinki", online = true, country = "Finland", city = "Helsinki"),
)

private val owner = RemoteManageUi.Managing(
    name = TV_NAME, tv = true, owner = true, status = status, settings = settings, files = files,
)

private val guest = owner.copy(
    owner = false, left = 75_600,
    status = status.copy(
        you = RemoteYou("guest", 75_600), exitNode = null, running = true,
        health = listOf("Tailscale could not connect to any relay server. Check your internet connection."),
    ),
    settings = settings.map { it.copy(writable = false) },
    files = emptyList(),
)

@Composable
private fun Manage(ui: RemoteManageUi, scan: Boolean = true) = AdaptiveShowcase(null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            RemoteManageContent(ui, RemoteManageActions.None, onScan = if (scan) ({}) else null)
        }
    }
}

/** The entry in a peer's details: nothing until the peer offers management, then one button. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageButtons() = AdaptiveShowcase(null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val label = stringResource(R.string.remote_manage_button)
            RemoteManageButton(RemoteManageEntryState(label).apply { offered = true; tv = true }, Modifier.fillMaxWidth())
            RemoteManageButton(RemoteManageEntryState(label).apply { offered = true }, Modifier.fillMaxWidth())
            // Not offered: draws nothing.
            RemoteManageButton(RemoteManageEntryState(label), Modifier.fillMaxWidth())
        }
    }
}

/** Not paired yet, and the device shows no code: where to get one. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagePair() = Manage(RemoteManageUi.Pairing(TV_NAME, tv = true, codeOnShow = false))

/** A wrong code, with what is left of the guesses. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagePairWrong() = Manage(
    RemoteManageUi.Pairing(TV_NAME, tv = true, code = "483901", error = RemotePairError(RemotePairFailure.WRONG, attemptsLeft = 3))
)

/** Pairing in flight, on a device with no scanner. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagePairBusy() = Manage(RemoteManageUi.Pairing(TV_NAME, code = "483902", busy = true), scan = false)

/** The device is online and the port is closed: management is off there, or the mode keeps peers out. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagePairNotAccepting() = Manage(
    RemoteManageUi.Pairing(TV_NAME, tv = true, code = "483902", error = RemotePairError(RemotePairFailure.UNREACHABLE, online = true))
)

/** The first question is still on its way. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageLoading() = Manage(RemoteManageUi.Managing(TV_NAME, tv = true, owner = true, loading = true))

/** An owner's view: everything answers. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageOwner() = Manage(owner)

/** A guest's: it looks, picks the exit node, and is told why the rest does not answer. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageGuest() = Manage(guest)

/** The managed device's exit nodes; the one that is offline cannot be chosen. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageExitNodes() = Manage(owner.copy(picking = true, exitNodes = exitNodes))

/** Asked before a file is deleted there. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageDeleteFile() = Manage(owner.copy(confirmDelete = "holiday.mp4"))

/** Asked before the device is forgotten. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageForget() = Manage(owner.copy(confirmForget = true, files = emptyList()))

/** After Reconnect: the device is away for a few seconds, and that is said instead of an error. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageReconnecting() = Manage(owner.copy(reconnecting = true, files = emptyList()))

/** Not connected on the device itself: the daemon's state, and a setting the app could not read. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageNeedsLogin() = Manage(
    owner.copy(
        status = status.copy(running = false, backendState = "NeedsLogin", exitNode = status.exitNode?.copy(online = false)),
        settings = settings.map { if (it.key == "exit_node_lan_access") it.copy(value = "", error = "unavailable") else it },
        files = emptyList(),
    )
)

/** The device went away while it was being managed: what it last said stays readable. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageOffline() = Manage(owner.copy(problem = RemoteProblem.OFFLINE, files = emptyList()))

/** Online, and the port is closed. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageNotAccepting() = Manage(
    RemoteManageUi.Managing(TV_NAME, tv = true, owner = true, problem = RemoteProblem.NOT_ACCEPTING)
)

/** The pairing was revoked on the device. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageRevoked() = Manage(owner.copy(problem = RemoteProblem.REVOKED))

/** A guest's day is over. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageExpired() = Manage(guest.copy(problem = RemoteProblem.EXPIRED))

/** The app there said no, in its own words. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageRefused() = Manage(
    owner.copy(problem = RemoteProblem.REFUSED, problemDetail = "set by an extra argument on the device", files = emptyList())
)

/** No answer yet to a change: it may still be applied. */
@PreviewTest @RemotePhone @Composable
fun RemoteManagePending() = Manage(owner.copy(problem = RemoteProblem.PENDING, files = emptyList()))

/** This device's own TailSocks is not connected. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageNotConnected() = Manage(
    RemoteManageUi.Managing(TV_NAME, tv = true, owner = true, problem = RemoteProblem.NOT_CONNECTED)
)

/** Forgotten here; the device could not be told. */
@PreviewTest @RemotePhone @Composable
fun RemoteManageForgotten() = Manage(RemoteManageUi.Forgotten(TV_NAME, tv = true))
