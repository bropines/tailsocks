package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import android.content.Context

/*
 * Whether the daemon will send a peer a file, and why not, in the user's words: pure rules over
 * the status JSON shared by the details sheet, the Share sheet, the Files hub and Remote manage.
 */

/** Whether the daemon will send this peer a file, and if not, why, in the user's words.
 *  Shared by the details sheet and both send pickers (Share sheet, Files hub), so what a
 *  picker offers is the same verdict the sheet shows beside its Send button. The Files hub
 *  lists only the devices a file can go to and counts the Blocked ones under its list. */
sealed interface TaildropStatus {
    data object Available : TaildropStatus
    /** The control plane's Online bit for the peer is off. A note beside the button, not a
     *  refusal: that bit lags reality by seconds to minutes, and `tailscale file cp` itself
     *  does not gate on it (cmd/tailscale/cli/file.go) — it probes the peer and lets the
     *  transfer find out. So the button stays live and the words say why it might not land. */
    data class Offline(val reason: String) : TaildropStatus
    /** The daemon will refuse the file, and this is why. */
    data class Blocked(val reason: String) : TaildropStatus
}

/** ipnstate.TaildropTargetStatus as the daemon numbers it (ipn/ipnstate/ipnstate.go). */
private const val TAILDROP_TARGET_UNKNOWN = 0
private const val TAILDROP_TARGET_AVAILABLE = 1
private const val TAILDROP_TARGET_NO_NETMAP = 2
private const val TAILDROP_TARGET_NOT_RUNNING = 3
private const val TAILDROP_TARGET_MISSING_CAP = 4
private const val TAILDROP_TARGET_OFFLINE = 5
private const val TAILDROP_TARGET_NO_PEER_INFO = 6
private const val TAILDROP_TARGET_UNSUPPORTED_OS = 7
private const val TAILDROP_TARGET_NO_PEER_API = 8
private const val TAILDROP_TARGET_OWNED_BY_OTHER_USER = 9

/**
 * The daemon's verdict with its one blind spot filled in. It answers Offline before it
 * looks at the owner (feature/taildrop/ext.go), so an offline device of another user —
 * or a tagged one, which belongs to "tagged-devices" — read as one that was merely
 * offline, and a send to it would be refused even once it came back. Such a peer gets
 * the refusal it will get online. A grant of the file-sharing-target capability can lift
 * that for another user's device, but grants are not in the status: an offline device
 * with one is shown as refused until it is online again.
 */
internal fun PeerData.withTaildropOwnership(selfUserId: Long?): PeerData =
    if (taildropTarget == TAILDROP_TARGET_OFFLINE && selfUserId != null && userID != null && userID != selfUserId)
        copy(taildropTarget = TAILDROP_TARGET_OWNED_BY_OTHER_USER)
    else this

/**
 * The daemon's verdict on sending this peer a file, translated. The code is what is read,
 * and every code the bundled daemon can emit has its own words here. NoFileSharingReason is
 * a field the daemon declares (ipn/ipnstate) but, in the tree this app builds, never fills:
 * the fallback to it is for a foreign or newer daemon that has started to, and with ours it
 * is never taken. Likewise code 0 (Unknown) and a missing field: the bundled daemon always
 * decides (feature/taildrop/ext.go fills the field on every peer), so 0 only arrives from a
 * daemon this app did not ship, and is not treated as a refusal — the CLI does treat it as
 * one, but the CLI is about to send a file and this is about a button that can still try.
 */
fun taildropStatusOf(peer: PeerData, strings: TaildropReasonStrings): TaildropStatus {
    val rawReason = peer.noFileSharingReason?.takeIf { it.isNotEmpty() }
    val reason = when (val code = peer.taildropTarget) {
        TAILDROP_TARGET_AVAILABLE -> return TaildropStatus.Available
        null, TAILDROP_TARGET_UNKNOWN -> rawReason ?: return TaildropStatus.Available
        TAILDROP_TARGET_NO_NETMAP -> strings.noNetmap
        TAILDROP_TARGET_NOT_RUNNING -> strings.notRunning
        TAILDROP_TARGET_MISSING_CAP -> strings.missingCap
        TAILDROP_TARGET_OFFLINE -> return TaildropStatus.Offline(strings.offline)
        TAILDROP_TARGET_NO_PEER_INFO -> strings.noPeerInfo
        TAILDROP_TARGET_UNSUPPORTED_OS -> strings.unsupportedOs
        TAILDROP_TARGET_NO_PEER_API -> strings.noPeerApi
        TAILDROP_TARGET_OWNED_BY_OTHER_USER -> strings.otherUser
        else -> rawReason ?: strings.blocked
    }
    return TaildropStatus.Blocked(reason)
}

/**
 * The words [taildropStatusOf] puts to each daemon verdict. Resolved by the caller from its
 * own context (the Share sheet and the Files picker live inside a ModalBottomSheet, whose
 * window follows the system locale — see wrapContextWithLocale()), never inside the sheet.
 */
class TaildropReasonStrings(
    val blocked: String,
    val noNetmap: String,
    val notRunning: String,
    val missingCap: String,
    val offline: String,
    val noPeerInfo: String,
    val unsupportedOs: String,
    val noPeerApi: String,
    val otherUser: String
) {
    companion object {
        fun from(context: Context) = TaildropReasonStrings(
            blocked = context.getString(R.string.peer_taildrop_blocked),
            noNetmap = context.getString(R.string.peer_taildrop_no_netmap),
            notRunning = context.getString(R.string.peer_taildrop_not_running),
            missingCap = context.getString(R.string.peer_taildrop_missing_cap),
            offline = context.getString(R.string.peer_taildrop_offline),
            noPeerInfo = context.getString(R.string.peer_taildrop_no_peer_info),
            unsupportedOs = context.getString(R.string.peer_taildrop_unsupported_os),
            noPeerApi = context.getString(R.string.peer_taildrop_no_peer_api),
            otherUser = context.getString(R.string.peer_taildrop_other_user)
        )
    }
}

/** Sort key for a send picker: the peers the file can go to first, the refused ones last. */
fun taildropPickerRank(status: TaildropStatus): Int = when (status) {
    TaildropStatus.Available -> 0
    is TaildropStatus.Offline -> 1
    is TaildropStatus.Blocked -> 2
}

/**
 * The peers a send picker lists, and their order. Drops this device, sharee nodes, Funnel's
 * ingress node and peers the daemon named by nothing (none of them is a place a file can
 * go); the rest are kept, including the peers the daemon refuses — the Share sheet draws a
 * refused peer disabled with the daemon's reason (see [PeerShareItem]) rather than silently
 * missing, so a device that is on the list but greyed out explains itself the way the
 * details sheet does; the Files hub (taildropTargets) leaves it off and says how many and why.
 */
fun taildropPickerPeers(status: StatusResponse, strings: TaildropReasonStrings): List<PeerData> {
    val selfId = status.self?.id
    val selfUserId = status.self?.userID
    return status.peers?.values.orEmpty()
        .filter { (selfId == null || it.id != selfId) && (!it.hostName.isNullOrBlank() || !it.dnsName.isNullOrBlank()) && it.shareeNode != true && it.hostName != "funnel-ingress-node" }
        .map { it.withTaildropOwnership(selfUserId) }
        .sortedWith(
            compareBy<PeerData> { taildropPickerRank(taildropStatusOf(it, strings)) }
                .thenByDescending { it.online == true }
                .thenBy { it.getDisplayName().lowercase() }
        )
}
