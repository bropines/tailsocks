package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// The managing page (ui/RemoteManage*.kt) as it opens: before the device has answered, and when
// it cannot be asked at all. What stands on it then is what this device knows by itself — its
// own network map, the stored pairing, what the device said to an earlier page — and each of
// the five cases the page tells apart before it would ask for a pairing code. The fixtures are
// RemoteManagePreviews.kt's. The line under the bar moves; a render catches its first frame.

private object OpenDemo {
    const val NAME = "living-room-tv"
    private const val ADDRESS = "100.64.0.7"

    val online = ManagePeer(RemotePresence.ONLINE, address = ADDRESS)
    val offline = ManagePeer(RemotePresence.OFFLINE, lastSeen = ManageDemo.NOW - 2 * 3_600_000, address = ADDRESS)
    val gone = ManagePeer(RemotePresence.GONE)
    val stopped = ManagePeer(stopped = true)

    /** Paired two days ago; the device has said nothing to this app since it started. */
    val fresh = RemoteManageUi.Managing(NAME, tv = true, owner = true, now = ManageDemo.NOW, pairedAt = ManageDemo.NOW - 2 * 86_400_000L, peer = online)

    /** The same device with what it said five minutes ago, to an earlier page. */
    val remembered = ManageDemo.fullOwner.copy(
        peer = online, pairedAt = fresh.pairedAt, heardAt = ManageDemo.NOW - 5 * 60_000, remembered = true,
    )

    fun pairing(peer: ManagePeer?, problem: ManageProblem? = null, checking: Boolean = false, starting: Boolean = false) =
        RemoteManageUi.Pairing(NAME, tv = true, peer = peer, problem = problem, checking = checking, starting = starting, now = ManageDemo.NOW)
}

// ─── The page is there at once ───────────────────────────────────────────────────────────────

/** The first frame, while this device's own store is read: nothing is claimed yet. */
@PreviewTest @ManagePhone @Composable
fun OpenFirstFrame() = ManagePage(RemoteManageUi.Opening(OpenDemo.NAME, tv = true))

/** Just opened, nothing remembered: where the tailnet has the device, who this device is to it, and Forget. */
@PreviewTest @ManagePhone @Composable
fun OpenAsking() = ManagePage(OpenDemo.fresh.copy(loading = true))

@PreviewTest @ManageTv @Composable
fun OpenAskingTv() = ManagePage(OpenDemo.fresh.copy(loading = true), tv = true)

/** Just opened, and this app heard the device five minutes ago: all of it, dimmed, until it answers. */
@PreviewTest @ManagePhone @Composable
fun OpenRemembered() = ManagePage(OpenDemo.remembered.copy(loading = true))

@PreviewTest @ManageTablet @Composable
fun OpenRememberedTablet() = ManagePage(OpenDemo.remembered.copy(loading = true))

@PreviewTest @ManageTv @Composable
fun OpenRememberedTv() = ManagePage(OpenDemo.remembered.copy(loading = true), tv = true)

/** It answered: the same page, its own word now, with what the tailnet says of it under the model. */
@PreviewTest @ManagePhone @Composable
fun OpenAnswered() = ManagePage(OpenDemo.remembered.copy(remembered = false, heardAt = ManageDemo.NOW))

// ─── A device this one manages, and cannot ask ───────────────────────────────────────────────

/** (a) This device's own TailSocks is stopped: said at once, and started from here. */
@PreviewTest @ManagePhone @Composable
fun OpenNotConnected() = ManagePage(OpenDemo.remembered.copy(peer = OpenDemo.stopped, problem = ManageProblem.NOT_CONNECTED))

@PreviewTest @ManageTv @Composable
fun OpenNotConnectedTv() = ManagePage(OpenDemo.remembered.copy(peer = OpenDemo.stopped, problem = ManageProblem.NOT_CONNECTED), tv = true)

/** Being started: the button waits with the page. */
@PreviewTest @ManagePhone @Composable
fun OpenStarting() = ManagePage(OpenDemo.fresh.copy(peer = OpenDemo.stopped, problem = ManageProblem.NOT_CONNECTED, starting = true))

/** (b) It left the tailnet: no code is asked for, and forgetting it is the one thing to do. */
@PreviewTest @ManagePhone @Composable
fun OpenLeft() = ManagePage(OpenDemo.remembered.copy(peer = OpenDemo.gone, problem = ManageProblem.NO_PEER))

@PreviewTest @ManageTablet @Composable
fun OpenLeftTablet() = ManagePage(OpenDemo.fresh.copy(peer = OpenDemo.gone, problem = ManageProblem.NO_PEER))

@PreviewTest @ManageTv @Composable
fun OpenLeftTv() = ManagePage(OpenDemo.fresh.copy(peer = OpenDemo.gone, problem = ManageProblem.NO_PEER), tv = true)

/** (c) Offline, and since when; what it said last stays readable. */
@PreviewTest @ManagePhone @Composable
fun OpenOffline() = ManagePage(OpenDemo.remembered.copy(peer = OpenDemo.offline, problem = ManageProblem.OFFLINE))

@PreviewTest @ManageTv @Composable
fun OpenOfflineTv() = ManagePage(OpenDemo.remembered.copy(peer = OpenDemo.offline, problem = ManageProblem.OFFLINE), tv = true)

/** The same with nothing remembered: the device as the tailnet has it. */
@PreviewTest @ManagePhone @Composable
fun OpenOfflineNeverHeard() = ManagePage(OpenDemo.fresh.copy(peer = OpenDemo.offline, problem = ManageProblem.OFFLINE))

/** Held as offline and being asked all the same: the button waits. */
@PreviewTest @ManageTv @Composable
fun OpenOfflineAskedTv() = ManagePage(OpenDemo.fresh.copy(peer = OpenDemo.offline, problem = ManageProblem.OFFLINE, loading = true), tv = true)

/** (d) Online, and the port is closed to this device. */
@PreviewTest @ManagePhone @Composable
fun OpenNotAccepting() = ManagePage(OpenDemo.remembered.copy(problem = ManageProblem.NOT_ACCEPTING))

@PreviewTest @ManageTv @Composable
fun OpenNotAcceptingTv() = ManagePage(OpenDemo.fresh.copy(problem = ManageProblem.NOT_ACCEPTING), tv = true)

// ─── A device this one is not paired with: no code is asked for before it is known to take one ──

/** Being asked whether it takes a pairing: no form yet. */
@PreviewTest @ManagePhone @Composable
fun OpenPairChecking() = ManagePage(OpenDemo.pairing(OpenDemo.online, checking = true))

@PreviewTest @ManageTv @Composable
fun OpenPairCheckingTv() = ManagePage(OpenDemo.pairing(OpenDemo.online, checking = true), tv = true)

/** (a) */
@PreviewTest @ManagePhone @Composable
fun OpenPairNotConnected() = ManagePage(OpenDemo.pairing(OpenDemo.stopped, ManageProblem.NOT_CONNECTED))

@PreviewTest @ManageTv @Composable
fun OpenPairNotConnectedTv() = ManagePage(OpenDemo.pairing(OpenDemo.stopped, ManageProblem.NOT_CONNECTED), tv = true)

/** (b) A code scanned off a device of another tailnet: it was never in this one, and the page says only that. */
@PreviewTest @ManagePhone @Composable
fun OpenPairNotInTailnet() = ManagePage(OpenDemo.pairing(OpenDemo.gone, ManageProblem.NO_PEER))

@PreviewTest @ManageTv @Composable
fun OpenPairNotInTailnetTv() = ManagePage(OpenDemo.pairing(OpenDemo.gone, ManageProblem.NO_PEER), tv = true)

/** (c) */
@PreviewTest @ManagePhone @Composable
fun OpenPairOffline() = ManagePage(OpenDemo.pairing(OpenDemo.offline, ManageProblem.OFFLINE))

@PreviewTest @ManageTv @Composable
fun OpenPairOfflineTv() = ManagePage(OpenDemo.pairing(OpenDemo.offline, ManageProblem.OFFLINE), tv = true)

/** (d) */
@PreviewTest @ManagePhone @Composable
fun OpenPairNotAccepting() = ManagePage(OpenDemo.pairing(OpenDemo.online, ManageProblem.NOT_ACCEPTING))

@PreviewTest @ManageTv @Composable
fun OpenPairNotAcceptingTv() = ManagePage(OpenDemo.pairing(OpenDemo.online, ManageProblem.NOT_ACCEPTING), tv = true)
