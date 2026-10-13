package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.RemoteAccess
import io.github.bropines.tailscaled.core.RemoteEvent
import io.github.bropines.tailscaled.core.RemotePairedDevice
import io.github.bropines.tailscaled.core.RemotePolicy
import io.github.bropines.tailscaled.core.RemoteReach
import io.github.bropines.tailscaled.core.RemoteState

// The managed device's page (RemoteManagedScreen.kt) in the states its two newer switches and
// its "what a paired device may do" card bring, next to the ones RemotePreviews.kt draws: each on
// a phone and on a 1080p television, in English and in Russian. The access documents are the
// bridge's own for each level (appctr/remote_level.go, RemoteAccessJSON), written out. A render
// cannot press a key, so on the television nothing is focused and every explanation is folded.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
internal annotation class ManagedPhone

/** A phone tall enough to show the whole page with a card unfolded. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=1900dp,dpi=420")
@Preview(name = "2-phone-ru", device = "spec:width=411dp,height=1900dp,dpi=420", locale = "ru")
internal annotation class ManagedPhoneTall

@Preview(name = "3-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "4-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
internal annotation class ManagedTv

// 12 Oct 2026, 14:03 UTC.
private const val NOW = 1_791_813_780_000L
private const val NOW_S = NOW / 1000

private val policy = RemotePolicy(
    allowGuests = true, codeSeconds = 600, codeAttempts = 5, guestSeconds = 86_400, ownerSeconds = 0, maxPairings = 8,
)

private val running = RemoteState(
    available = true, enabled = true, listening = true, port = 28115, inbound = RemoteState.INBOUND_NETSTACK,
    delegate = true, policy = policy, rev = 7,
)

private const val GUEST_OPS = """"status","exit_nodes","exit_node.set","settings","files","unpair","confirm","identify""""
private const val ACTIONS = """"actions":["reconnect","reconnect_relays","receive_folder_default","save_received"]"""
private const val PERMISSIONS = """"permissions":["storage","notifications","battery","alarms","install","autostart"]"""
private const val NEVER = """"never":["stop","sign_out","account","credentials","control_proxy","root_mode","grant_permission","full_control_on"]"""
private const val CONNECTION = """{"key":"accept_dns","guard":"optional"},{"key":"accept_routes","guard":"optional"},""" +
    """{"key":"exit_node_lan_access","guard":"optional"},{"key":"tun_mode","guard":"always"},""" +
    """{"key":"auto_reconnect","only":"true"},{"key":"auto_start","only":"true"},{"key":"keep_awake","only":"true"}"""

private fun basicAccess(guests: Boolean = true) = RemoteAccess.of(
    """{"level":"basic","guests":$guests,"guest":{"ops":[$GUEST_OPS]},
        "owner":{"ops":["status","exit_nodes","exit_node.set","settings","settings.set","action","files","files.delete","unpair",
                        "permission.request","folders","confirm","identify","shares.remove"],
                 "settings":[$CONNECTION,
                             {"key":"taildrive_enabled","only":"false","needs":"full"},{"key":"taildrive_proxy_enabled","only":"false","needs":"full"},
                             {"key":"advertise_exit_node","only":"false","needs":"full"},{"key":"advertise_routes","needs":"full"},
                             {"key":"full_control","only":"false"}],
                 $ACTIONS,$PERMISSIONS},
        "full":{"ops":["shares.add","storage.list","storage.send","diag.report","diag.log"],
                "settings":[{"key":"taildrive_enabled"},{"key":"taildrive_proxy_enabled"},{"key":"advertise_exit_node"},{"key":"advertise_routes"}]},
        $NEVER}"""
)

private val fullAccess = RemoteAccess.of(
    """{"level":"full","guests":true,"guest":{"ops":[$GUEST_OPS]},
        "owner":{"ops":["status","exit_nodes","exit_node.set","settings","settings.set","action","files","files.delete","unpair",
                        "permission.request","folders","confirm","identify","shares.remove","shares.add","storage.list","storage.send",
                        "diag.report","diag.log"],
                 "settings":[$CONNECTION,
                             {"key":"taildrive_enabled"},{"key":"taildrive_proxy_enabled"},{"key":"advertise_exit_node"},{"key":"advertise_routes"},
                             {"key":"full_control","only":"false"}],
                 $ACTIONS,$PERMISSIONS},
        "full":{"ops":[]},
        $NEVER}"""
)

private val pixel = RemotePairedDevice(
    nodeId = "nP9pixelCNTRL", name = "pixel-9-pro", login = "bropines@example.com", role = "owner",
    created = NOW_S - 9 * 86_400, lastUsed = NOW_S - 130, left = -1,
)
private val ann = RemotePairedDevice(
    nodeId = "nB7annCNTRL", name = "ann-iphone", login = "ann@example.org", role = "guest",
    created = NOW_S - 10_800, lastUsed = NOW_S - 7_500, left = 75_600, expires = NOW_S + 75_600,
)

private fun event(ago: Long, op: String, detail: String = "", ok: Boolean = true, guest: Boolean = false) =
    if (guest) RemoteEvent(NOW_S - ago, ann.nodeId, ann.name, "guest", op, detail, ok)
    else RemoteEvent(NOW_S - ago, pixel.nodeId, pixel.name, "owner", op, detail, ok)

/** Every operation the log learned with the two levels, the ones that failed among them. */
private val newLog = listOf(
    event(40, "permission", "storage: shown"),
    event(100, "permission", "notifications: notified"),
    event(160, "permission", "battery: waiting"),
    event(220, "permission", "install", ok = false),
    event(400, "confirmed", "exit_node"),
    event(460, "exit_node", "exit-frankfurt"),
    event(900, "reverted", "accept_routes: not confirmed"),
    event(965, "setting", "accept_routes=true"),
    event(1_500, "reverted", "exit_node: not confirmed; the one before is gone, so none"),
    event(2_000, "identify", "shown", guest = true),
    event(3_000, "share_added", "Movies"),
    event(3_100, "share_removed", "Camera", ok = false),
    event(3_700, "file_sent", "film (1).mkv"),
    event(3_800, "browse"),
    event(5_000, "diag", "log"),
    event(5_100, "diag", "report"),
    event(7_000, "action", "reconnect_relays"),
    event(7_300, "action", "save_received (no answer from the app yet)", ok = false),
    event(9_000, "setting", "advertise_routes=192.168.5.0/24,10.0.0.0/8"),
    event(9_500, "confirmed", "tun_mode=true"),
    event(12_000, "setting", "full_control=false"),
)

private fun managed(
    access: RemoteAccess = basicAccess(),
    devices: List<RemotePairedDevice> = listOf(pixel, ann),
    fullControl: Boolean = false,
    guests: Boolean = true,
    tagged: Boolean = false,
    accessOpen: Boolean = true,
) = RemoteManagedUi(
    enabled = true, state = running, reach = RemoteReach.READY, devices = devices, now = NOW, tagged = tagged,
    fullControl = fullControl, guests = guests, access = access, accessOpen = accessOpen,
)

private val off = RemoteManagedUi(
    enabled = false, state = RemoteState(available = true, port = 28115, policy = policy), now = NOW, access = basicAccess(),
)
private val basic = managed()
private val full = managed(fullAccess, fullControl = true)
private val guestsOff = managed(basicAccess(guests = false), guests = false)
private val taggedGuestsOff = managed(basicAccess(guests = false), devices = listOf(ann), guests = false, tagged = true, accessOpen = false)
private val tagged = managed(devices = listOf(ann), tagged = true, accessOpen = false)
private val logged = managed(fullAccess, fullControl = true, accessOpen = false).copy(log = newLog, logOpen = true)
// Full control switched on with TailSocks stopped: the bridge still has the level from before, and the card says so.
private val fullNotApplied = managed(fullControl = true, devices = emptyList()).copy(
    state = RemoteState(available = true, enabled = true, port = 28115, policy = policy), reach = RemoteReach.NOT_RUNNING,
)

@Composable
private fun Managed(ui: RemoteManagedUi, tv: Boolean = false) =
    AdaptiveShowcase(null, device = if (tv) DeviceClass.TV else null) {
        RemoteManagedContent(ui, RemoteManagedActions.None, onBack = {})
    }

/** Off: the switch, and what turning it on would let a paired device do, folded and there to be read first. */
@PreviewTest @ManagedPhone @Composable
fun RemoteManagedOffAccess() = Managed(off)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedOffAccessTv() = Managed(off, tv = true)

/** The same with the card unfolded: nothing is hidden behind the switch. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedOffAccessOpen() = Managed(off.copy(accessOpen = true))

/** On at the basic level: the three switches, the code, and the card unfolded with what full control would add. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedBasic() = Managed(basic)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedBasicTv() = Managed(basic, tv = true)

/** The page as it opens at the basic level, both cards folded. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedBasicFolded() = Managed(basic.copy(accessOpen = false))

@PreviewTest @ManagedTv @Composable
fun RemoteManagedBasicFoldedTv() = Managed(basic.copy(accessOpen = false), tv = true)

/** Full control on: its lines are the owner's, and the group that promised them is gone. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedFullControl() = Managed(full)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedFullControlTv() = Managed(full, tv = true)

/** Full control switched on while TailSocks is stopped: the list is the one from before, and says why. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedFullControlNotApplied() = Managed(fullNotApplied)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedFullControlNotAppliedTv() = Managed(fullNotApplied, tv = true)

/** Guests shut out: the guest paired before is listed as refused, and the card says a guest is nobody. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedGuestsOff() = Managed(guestsOff)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedGuestsOffTv() = Managed(guestsOff, tv = true)

/** A tagged node with guests shut out: nobody at all can pair, and that is said where the devices are. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedTaggedGuestsOff() = Managed(taggedGuestsOff)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedTaggedGuestsOffTv() = Managed(taggedGuestsOff, tv = true)

/** A tagged node that lets guests in. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedTagged() = Managed(tagged)

/** The log with the operations of the two levels in it. */
@PreviewTest @ManagedPhoneTall @Composable
fun RemoteManagedNewLog() = Managed(logged)

@PreviewTest @ManagedTv @Composable
fun RemoteManagedNewLogTv() = Managed(logged, tv = true)

/** One of the page's switches could not be stored. */
@PreviewTest @ManagedPhone @Composable
fun RemoteManagedSwitchNotSaved() = Managed(basic.copy(accessOpen = false, notice = RemoteNotice.NO_FULL, devices = emptyList()))
