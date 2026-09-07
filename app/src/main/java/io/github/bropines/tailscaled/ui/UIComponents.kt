package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Outbound
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import appctr.Appctr
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// --- PEERS ---

/** How far the finger has to travel before the details sheet turns to the next peer. In dp:
 *  in raw pixels the same flick would turn the page on one device and not on another. */
private val PEER_SWIPE_THRESHOLD = 100.dp
/** A flick faster than this turns the page whatever distance it covered. */
private val PEER_SWIPE_FLING_VELOCITY = 400.dp
/** How much more horizontal than vertical the finger has to be before the page-turn claims
 *  the gesture. A diagonal drag is left to the sheet's own drag-to-dismiss. */
private const val PEER_SWIPE_DIRECTION_RATIO = 2f
/** How much of the drag survives when there is no peer in that direction. */
private const val PEER_SWIPE_RESISTANCE = 0.25f
/** Half a pixel of residual is where a page has visibly landed. The swipe animates raw
 *  pixels, so the library's own 0.01 default — written for a 0..1 fraction — leaves a spring
 *  running for roughly another 165 ms on a phone-width displacement, and the page turn is
 *  only committed once the spring reports done: without this the peer under the finger has
 *  arrived while the ping state, the arrows and the cards still belong to the last
 *  one. */
private const val PEER_SWIPE_SETTLE_THRESHOLD_PX = 0.5f
/** How long a copied row shows its tick before going back to the copy icon. */
private const val PEER_COPIED_ACK_MS = 1500L

/** Material's spatial spring, told what "finished" means in pixels. Every field but the
 *  threshold is the scheme's, so the motion is still the theme's and not this file's; a spec
 *  that is not a spring (a future scheme could hand back something else) is left alone. */
private fun FiniteAnimationSpec<Float>.settlingInPixels(): FiniteAnimationSpec<Float> =
    (this as? SpringSpec<Float>)?.let {
        spring(it.dampingRatio, it.stiffness, PEER_SWIPE_SETTLE_THRESHOLD_PX)
    } ?: this
/** The green of "this peer is up", shared by the list row, the share row, the identity chip
 *  and the status strip so the four cannot drift apart. */
private val PEER_ONLINE_GREEN = Color(0xFF4CAF50)
/** And the grey of "it is not". Fixed rather than theme-derived for the same reason the green
 *  is: the identity chip's dot and the status strip's dot sit ten dp apart and say the same
 *  thing, so they must be the same colour in both schemes. */
private val PEER_OFFLINE_GREY = Color(0xFF9E9E9E)

@Serializable
private data class PingResult(
    @SerialName("Err") val err: String? = null,
    @SerialName("LatencySeconds") val latencySeconds: Double? = null,
    @SerialName("LatencyMs") val latencyMs: Double? = null
)

/** What the daemon writes into the raw ping state while the round trip is in flight. Internal,
 *  not private: the exit-node sheet in MainActivity keeps its chips in the same raw form and
 *  reads them with [pingStateOf], so there is one parser for both. */
internal const val PING_IN_FLIGHT = "Pinging..."

/** The millisecond figure in a plain `tailscale ping` line. Compiled once for the process:
 *  it is the fallback path of every ping the sheet parses. */
private val PING_LATENCY_RE = """\b\d+(?:\.\d+)?\s*ms\b""".toRegex()

/**
 * Where a ping stands for the peer on screen. The sheet holds the daemon's raw answer and
 * hands the page this instead: the connection card and the status strip both
 * describe one measurement, so none of them can parse it differently from the others.
 */
internal sealed interface PeerPingState {
    /** Nothing has been asked yet — or it was asked about a peer this is no longer showing. */
    data object Idle : PeerPingState
    data object InFlight : PeerPingState
    /** A round trip that came back, already formatted for display ("24 ms"). */
    data class Measured(val latency: String) : PeerPingState
    data object Failed : PeerPingState
}

/**
 * The daemon's answer, read once. Two shapes arrive: the JSON of a disco ping, and — when
 * that is refused — the plain `tailscale ping` line, where the only thing worth having is
 * the millisecond figure in it.
 */
internal fun pingStateOf(raw: String?): PeerPingState = when {
    raw == null -> PeerPingState.Idle
    raw == PING_IN_FLIGHT -> PeerPingState.InFlight
    raw.isBlank() || raw.contains("Failed") || raw.startsWith("Error") -> PeerPingState.Failed
    else -> {
        val latency = try {
            val ping = AppJson.decodeFromString<PingResult>(raw)
            if (!ping.err.isNullOrEmpty()) {
                null
            } else {
                val sec = ping.latencySeconds
                if (sec != null && sec > 0) "${(sec * 1000).toInt()} ms"
                else ping.latencyMs?.takeIf { it > 0 }?.let { "${it.toInt()} ms" }
            }
        } catch (e: Exception) {
            PING_LATENCY_RE.find(raw)?.value
        }
        if (latency != null) PeerPingState.Measured(latency) else PeerPingState.Failed
    }
}

/**
 * One round trip to [ip], as the raw line [pingStateOf] reads. A disco ping first; when the
 * daemon refuses that, the plain `tailscale ping` output. Blocking work, so it runs on IO and
 * hands back to whatever the caller was on — a caller in a coroutine scope that is gone by
 * then never sees the answer, which is what a closed sheet wants.
 */
internal suspend fun pingPeer(ip: String): String = withContext(Dispatchers.IO) {
    val out = try {
        val res = Appctr.pingTarget(ip, "disco")
        if (res.isNotBlank() && !res.startsWith("Error")) res
        else Appctr.runTailscaleCmd("ping $ip")
    } catch (e: Exception) { "Error" }
    (out.split("\n").find { it.contains("pong from") || it.contains("LatencyMs") } ?: out.ifBlank { "Failed" }).trim()
}

/**
 * The cards the detail rows are dealt into, in the order they are drawn under the connection
 * card. Each is one card of the sheet: a heading, an icon, the group's most important value
 * carried large, and the rest folded away as quieter rows. OTHER is the safety net:
 * PeerData.getDetailsList() is free to grow a field without this file, and an unplaced row
 * must still be shown.
 */
private enum class PeerDetailGroup { ADDRESSES, ROUTE, DEVICE, CAPABILITIES, TRAFFIC, TIMING, OTHER }

/** Which card each row of PeerData.getDetailsList() belongs on. Keyed on the row's
 *  [PeerDetailId], not on its label: the label is display text and may yet be translated. */
private val PEER_DETAIL_GROUP_OF: Map<PeerDetailId, PeerDetailGroup> = mapOf(
    PeerDetailId.MACHINE_NAME to PeerDetailGroup.ADDRESSES,
    PeerDetailId.DNS_NAME to PeerDetailGroup.ADDRESSES,
    PeerDetailId.IPV4 to PeerDetailGroup.ADDRESSES,
    PeerDetailId.IPV6 to PeerDetailGroup.ADDRESSES,
    PeerDetailId.ALLOWED_IPS to PeerDetailGroup.ADDRESSES,
    PeerDetailId.PEER_API to PeerDetailGroup.ADDRESSES,
    PeerDetailId.RELAY to PeerDetailGroup.ROUTE,
    PeerDetailId.CUR_ADDR to PeerDetailGroup.ROUTE,
    PeerDetailId.IN_NETWORK_MAP to PeerDetailGroup.ROUTE,
    PeerDetailId.IN_MAGICSOCK to PeerDetailGroup.ROUTE,
    PeerDetailId.IN_WG_ENGINE to PeerDetailGroup.ROUTE,
    PeerDetailId.OS to PeerDetailGroup.DEVICE,
    PeerDetailId.VERSION to PeerDetailGroup.DEVICE,
    PeerDetailId.NODE_ID to PeerDetailGroup.DEVICE,
    PeerDetailId.TAGS to PeerDetailGroup.DEVICE,
    PeerDetailId.IS_EXIT_NODE to PeerDetailGroup.CAPABILITIES,
    PeerDetailId.EXIT_NODE_OPTION to PeerDetailGroup.CAPABILITIES,
    PeerDetailId.CAPABILITIES to PeerDetailGroup.CAPABILITIES,
    PeerDetailId.TAILDROP_TARGET to PeerDetailGroup.CAPABILITIES,
    PeerDetailId.NO_FILE_SHARING to PeerDetailGroup.CAPABILITIES,
    PeerDetailId.RX_BYTES to PeerDetailGroup.TRAFFIC,
    PeerDetailId.TX_BYTES to PeerDetailGroup.TRAFFIC,
    PeerDetailId.CREATED to PeerDetailGroup.TIMING,
    PeerDetailId.KEY_EXPIRY to PeerDetailGroup.TIMING,
    PeerDetailId.LAST_SEEN to PeerDetailGroup.TIMING,
    PeerDetailId.LAST_WRITE to PeerDetailGroup.TIMING,
    PeerDetailId.LAST_HANDSHAKE to PeerDetailGroup.TIMING
)

/** The rows worth putting on the clipboard: names, addresses and identifiers, plus the two
 *  free-text rows a bug report is written out of — the daemon's reason for refusing Taildrop
 *  and the DERP region carrying the traffic. A byte counter or a boolean carries a copy icon
 *  in no useful sense, and it was the icon on every row that made none of them stand out. */
private val PEER_COPYABLE_DETAILS = setOf(
    PeerDetailId.MACHINE_NAME, PeerDetailId.DNS_NAME, PeerDetailId.IPV4, PeerDetailId.IPV6,
    PeerDetailId.ALLOWED_IPS, PeerDetailId.NODE_ID, PeerDetailId.CUR_ADDR,
    PeerDetailId.PEER_API, PeerDetailId.VERSION, PeerDetailId.TAGS,
    PeerDetailId.RELAY, PeerDetailId.NO_FILE_SHARING
)

/** The rows that are machine text and line up better in a monospace face. */
private val PEER_MONOSPACE_DETAILS = setOf(
    PeerDetailId.IPV4, PeerDetailId.IPV6, PeerDetailId.ALLOWED_IPS, PeerDetailId.NODE_ID,
    PeerDetailId.VERSION, PeerDetailId.CUR_ADDR, PeerDetailId.PEER_API
)

/**
 * One card of the sheet below the connection card: what it leads with, and the quieter rows
 * it folds away. Built once per peer by [peerCards]; the composable only draws it, and during
 * a page turn it is built for two peers at once, so it does no work a draw does not need.
 */
private class PeerCard(
    val group: PeerDetailGroup,
    /** The value carried large. */
    val lead: String,
    /** The words under the lead saying what it is. */
    val leadCaption: String,
    val leadMonospace: Boolean,
    /** The row the lead was read off, when it was read off one: the card offers to copy it
     *  the way an open row offers to copy itself, so taking a row out of the fold does not
     *  take the clipboard away from it. Null for a lead that is a reading of several rows. */
    val leadRow: PeerDetail?,
    /** Everything else in the group, shown when the card is open. The row the lead was
     *  taken from is not repeated here: it is on the card already, just larger. */
    val rows: List<PeerDetail>
)

/**
 * The rows of PeerData.getDetailsList() dealt into cards, each with its lead chosen. Where a
 * group has one row that is plainly the thing to look at, that row is the lead and leaves the
 * quiet rows (the IPv4 address, the Tailscale version, when the peer was last seen). Where
 * the interesting value is a reading of several fields — how the peer is reached, whether it
 * takes files, how much has passed — the lead is derived and the raw rows all stay, so
 * nothing the daemon reported is lost behind the summary of it. A group with no rows is
 * dropped rather than drawn empty.
 */
private fun peerCards(peer: PeerData, strings: PeerDetailsStrings): List<PeerCard> {
    val details = peer.getDetailsList()
    return PeerDetailGroup.entries.mapNotNull { group ->
        val rows = details.filter { (PEER_DETAIL_GROUP_OF[it.id] ?: PeerDetailGroup.OTHER) == group }
        if (rows.isEmpty()) return@mapNotNull null
        fun leadRow(id: PeerDetailId): PeerCard? = rows.firstOrNull { it.id == id }?.let { lead ->
            PeerCard(group, lead.value, lead.label, lead.id in PEER_MONOSPACE_DETAILS, lead, rows - lead)
        }
        val curAddr = peer.curAddr?.takeIf { it.isNotEmpty() }
        val relay = peer.relay?.takeIf { it.isNotEmpty() }
        val card = when (group) {
            PeerDetailGroup.ADDRESSES -> leadRow(PeerDetailId.IPV4)
            PeerDetailGroup.DEVICE -> leadRow(PeerDetailId.VERSION)
            PeerDetailGroup.TIMING -> leadRow(PeerDetailId.LAST_SEEN)
            // The same reading as the status strip's route chip, with the raw endpoint or
            // region as the figure. The row it was read from steps aside; the other one —
            // "Current Addr: N/A" under a relay, say — stays, because it is what the daemon
            // said and someone debugging a connection will want to see it said. With no
            // route at all the relay row steps aside too: the model writes "Direct" into an
            // empty relay, and "Direct" under "no route yet" is the card arguing with itself.
            PeerDetailGroup.ROUTE -> when {
                curAddr != null -> rows.first { it.id == PeerDetailId.CUR_ADDR }.let { lead ->
                    PeerCard(group, curAddr, strings.direct, true, lead, rows - lead)
                }
                relay != null -> rows.first { it.id == PeerDetailId.RELAY }.let { lead ->
                    PeerCard(group, relay, strings.routeRelay, false, lead, rows - lead)
                }
                else -> PeerCard(group, "—", strings.routeNone, false, null, rows.filterNot { it.id == PeerDetailId.RELAY })
            }
            // The verdict, in a few words: the reason behind it is a sentence, and it is
            // already under the Send File button where a sentence has room to wrap. Drawn
            // large here it would be cut after two lines — exactly the ACL instruction a
            // peer of another user's needs. "Offline" is short enough to stand as it is.
            PeerDetailGroup.CAPABILITIES -> PeerCard(
                group,
                when (val taildrop = taildropStatusOf(peer, strings)) {
                    TaildropStatus.Available -> strings.taildropOk
                    is TaildropStatus.Offline -> taildrop.reason
                    is TaildropStatus.Blocked -> strings.taildropBlocked
                },
                strings.taildrop,
                false,
                null,
                rows
            )
            PeerDetailGroup.TRAFFIC -> PeerCard(
                group,
                formatFileSize((peer.rxBytes ?: 0L) + (peer.txBytes ?: 0L)),
                strings.trafficTotal,
                false,
                null,
                rows
            )
            PeerDetailGroup.OTHER -> null
        }
        // A group with no lead of its own — OTHER, or a group whose lead row has gone missing
        // — leads with its first row, so a field added later still shows up large somewhere.
        card ?: rows.first().let {
            PeerCard(group, it.value, it.label, it.id in PEER_MONOSPACE_DETAILS, it, rows.drop(1))
        }
    }
}

/** Whether the daemon will send this peer a file, and if not, why, in the user's words. */
private sealed interface TaildropStatus {
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
 * The daemon's verdict on sending this peer a file, translated. The code is what is read,
 * and every code the bundled daemon can emit has its own words here. NoFileSharingReason is
 * a field the daemon declares (ipn/ipnstate) but, in the tree this app builds, never fills:
 * the fallback to it is for a foreign or newer daemon that has started to, and with ours it
 * is never taken. Likewise code 0 (Unknown) and a missing field: the bundled daemon always
 * decides (feature/taildrop/ext.go fills the field on every peer), so 0 only arrives from a
 * daemon this app did not ship, and is not treated as a refusal — the CLI does treat it as
 * one, but the CLI is about to send a file and this is about a button that can still try.
 */
private fun taildropStatusOf(peer: PeerData, strings: PeerDetailsStrings): TaildropStatus {
    val rawReason = peer.noFileSharingReason?.takeIf { it.isNotEmpty() }
    val reason = when (val code = peer.taildropTarget) {
        TAILDROP_TARGET_AVAILABLE -> return TaildropStatus.Available
        null, TAILDROP_TARGET_UNKNOWN -> rawReason ?: return TaildropStatus.Available
        TAILDROP_TARGET_NO_NETMAP -> strings.taildropNoNetmap
        TAILDROP_TARGET_NOT_RUNNING -> strings.taildropNotRunning
        TAILDROP_TARGET_MISSING_CAP -> strings.taildropMissingCap
        TAILDROP_TARGET_OFFLINE -> return TaildropStatus.Offline(strings.taildropOffline)
        TAILDROP_TARGET_NO_PEER_INFO -> strings.taildropNoPeerInfo
        TAILDROP_TARGET_UNSUPPORTED_OS -> strings.taildropUnsupportedOs
        TAILDROP_TARGET_NO_PEER_API -> strings.taildropNoPeerApi
        TAILDROP_TARGET_OWNED_BY_OTHER_USER -> strings.taildropOtherUser
        else -> rawReason ?: strings.taildropBlocked
    }
    return TaildropStatus.Blocked(reason)
}

fun getOsVisuals(os: String?): Pair<ImageVector, Color> {
    val osLower = os?.lowercase().orEmpty()
    val icon = when {
        osLower.contains("android") -> Icons.Default.Android
        osLower.contains("windows") -> Icons.Default.DesktopWindows
        osLower.contains("linux") -> Icons.Default.Terminal
        osLower.contains("macos") || osLower.contains("darwin") -> Icons.Default.DesktopMac
        osLower.contains("ios") -> Icons.Default.PhoneIphone
        else -> Icons.Default.Devices
    }
    val color = when {
        osLower.contains("android") -> Color(0xFF3DDC84)
        osLower.contains("windows") -> Color(0xFF0078D4)
        osLower.contains("linux") -> Color(0xFFFCC624)
        osLower.contains("macos") || osLower.contains("darwin") || osLower.contains("ios") -> Color(0xFFAF52DE) // Apple Purple
        else -> Color(0xFF9E9E9E)
    }
    return icon to color
}

/** The two exit-node states the status API reports for a peer. */
private enum class ExitNodeState { NONE, OFFERED, SELECTED }

private fun PeerData.exitNodeState(): ExitNodeState = when {
    // ExitNode wins over ExitNodeOption: the selected node has both flags set,
    // ipnlocal/local.go sets ExitNode only for the one whose StableID matches the pref.
    exitNode == true -> ExitNodeState.SELECTED
    exitNodeOption == true -> ExitNodeState.OFFERED
    else -> ExitNodeState.NONE
}

/**
 * The exit-node marker of a peer.
 *
 * SELECTED is the peer this device has *chosen* as its exit node — ipnstate.go documents
 * ExitNode as "the currently selected exit node", a daemon pref, not proof that traffic is
 * flowing through it: with another VPN holding the device the home screen says
 * main_exit_node_inert_label about this very peer. Hence "selected", not "in use". OFFERED
 * is a peer merely advertising exit routes, and NONE draws nothing at all, so the marker
 * takes no width on an ordinary peer.
 *
 * [showLabel] is false in the list row, where the words would eat the name column: there the
 * state is carried by the filled chip against the bare icon (fill and shape, not colour
 * alone) and the wording lives in the content description. The details sheet has a full row
 * to itself and spells both states out.
 *
 * The labels are passed in rather than resolved here: this also renders inside
 * PeerDetailsModal, where a stringResource would follow the system locale.
 */
@Composable
fun ExitNodeBadge(
    peer: PeerData,
    selectedLabel: String,
    offeredLabel: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false
) {
    when (peer.exitNodeState()) {
        ExitNodeState.SELECTED -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = if (showLabel) 8.dp else 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VpnKey,
                contentDescription = if (showLabel) null else selectedLabel,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
            if (showLabel) {
                Spacer(Modifier.width(4.dp))
                Text(
                    selectedLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        ExitNodeState.OFFERED -> if (showLabel) {
            Row(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    offeredLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Icon(
                Icons.Default.VpnKey,
                contentDescription = offeredLabel,
                modifier = modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        ExitNodeState.NONE -> Unit
    }
}

@Composable
fun PeerItem(peer: PeerData, isSelf: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val (osIcon, osColor) = getOsVisuals(peer.os)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    osIcon,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = osColor
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val displayName = peer.getDisplayName()
                val primaryIp = peer.getPrimaryIp()
                Text(displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (displayName != primaryIp) {
                    // One line each, like the name above them: the tag list of an exit node
                    // is long enough to wrap and make this row taller than its neighbours.
                    Text(
                        primaryIp,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!peer.tags.isNullOrEmpty()) {
                    Text(
                        text = peer.tags.joinToString(", "),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            // Icon-only here: the label is unweighted, so at a large font scale the words
            // would win the width contest against the name column and squeeze it to nothing.
            ExitNodeBadge(
                peer = peer,
                selectedLabel = stringResource(R.string.peer_exit_node_selected),
                offeredLabel = stringResource(R.string.peer_exit_node_offered),
                modifier = Modifier.padding(start = 8.dp)
            )
            if (peer.online == true || isSelf) {
                Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(PEER_ONLINE_GREEN))
            }
        }
    }
}

@Composable
fun PeerShareItem(peer: PeerData, enabled: Boolean, onClick: () -> Unit) {
    val (osIcon, osColor) = getOsVisuals(peer.os)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    osIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = osColor
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.getDisplayName(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    peer.os ?: "Device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (peer.online == true) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(PEER_ONLINE_GREEN)
                )
            }
        }
    }
}

/**
 * One page of the peer details sheet: a peer, plus the one thing about it the sheet cannot
 * work out for itself — whether it is this device.
 */
data class PeerPage(val peer: PeerData, val isSelf: Boolean)

/** Every word the details sheet shows, resolved in the parent composition — see
 *  wrapContextWithLocale(): inside the sheet's own window a stringResource follows the
 *  system locale, not the app's. */
private data class PeerDetailsStrings(
    val prevPeer: String,
    val nextPeer: String,
    val sendFile: String,
    val pinging: String,
    /** "Ping: %1$s" — formatted in the page with String.format, not with stringResource:
     *  two peers are composed at once during a turn and each carries its own figure. */
    val pingResultFormat: String,
    /** What the connection card says in place of a figure on this device's own page. */
    val pingSelf: String,
    val pingSelfCaption: String,
    /** "Measure round trip to %1$s": what the connection card is, for a screen reader. */
    val pingCardFormat: String,
    /** The connection card's caption before anything has been measured: the card is the
     *  thing that measures, and this is where it says so. */
    val tapToMeasure: String,
    val copy: String,
    /** The in-place acknowledgement a copied row shows for a moment. */
    val copied: String,
    val expanded: String,
    val collapsed: String,
    val exitNodeSelected: String,
    val exitNodeOffered: String,
    val online: String,
    val offline: String,
    val osUnknown: String,
    val direct: String,
    /** "Relay: %1$s", formatted in the page for the same reason as [pingResultFormat]. */
    val relayFormat: String,
    val latency: String,
    /** The connection card's caption for a ping that came back with nothing. */
    val connPingFailed: String,
    /** The route card's caption over a DERP region code. */
    val routeRelay: String,
    /** And over the dash it shows for a peer nothing has talked to yet. */
    val routeNone: String,
    /** The capabilities card's caption; what the Taildrop verdict under it is about. */
    val taildrop: String,
    val taildropOk: String,
    /** The verdict for a refusal code this file does not know and the daemon gave no words for. */
    val taildropBlocked: String,
    val taildropNoNetmap: String,
    val taildropNotRunning: String,
    val taildropMissingCap: String,
    val taildropOffline: String,
    val taildropNoPeerInfo: String,
    val taildropUnsupportedOs: String,
    val taildropNoPeerApi: String,
    val taildropOtherUser: String,
    /** The traffic card's caption over the sum of both byte counters. */
    val trafficTotal: String,
    val groupAddresses: String,
    val groupRoute: String,
    val groupConnection: String,
    val groupDevice: String,
    val groupCapabilities: String,
    val groupTraffic: String,
    val groupTiming: String,
    val groupOther: String
)

/** The heading a card is drawn under. */
private fun PeerDetailsStrings.titleOf(group: PeerDetailGroup): String = when (group) {
    PeerDetailGroup.ADDRESSES -> groupAddresses
    PeerDetailGroup.ROUTE -> groupRoute
    PeerDetailGroup.DEVICE -> groupDevice
    PeerDetailGroup.CAPABILITIES -> groupCapabilities
    PeerDetailGroup.TRAFFIC -> groupTraffic
    PeerDetailGroup.TIMING -> groupTiming
    PeerDetailGroup.OTHER -> groupOther
}

/** How a page sits while the sheet is turning: shifted by [dx], and — from half a page out
 *  — faded and shrunk a little, so the eye follows the page arriving at the centre rather
 *  than the one leaving. Both pages get it, so the incoming one fades up as it lands. */
private fun GraphicsLayerScope.peerPageTransform(dx: Float) {
    translationX = dx
    val halfPage = size.width * 0.5f
    val progress = if (halfPage > 0f) (abs(dx) / halfPage).coerceIn(0f, 1f) else 0f
    alpha = 1f - 0.45f * progress
    scaleX = 1f - 0.05f * progress
    scaleY = scaleX
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PeerDetailsModal(
    /**
     * The peer [offset] steps along the list from the one on screen: 0 is the page being
     * shown, -1 the page a swipe to the right brings in, +1 the page a swipe to the left
     * brings in, and null where the list ends. The sheet asks for at most two steps either
     * way — the two pages a turn has on screen at once, plus whether the arriving page
     * should carry its own arrows — and it draws nothing it was not handed.
     *
     * It takes the peers rather than bare prev/next callbacks because the neighbour has to
     * be *visible* under the finger: a callback can only be fired once the gesture is over,
     * which is exactly the blind drag this replaced.
     */
    peerAt: (offset: Int) -> PeerPage?,
    /** This device's own tailnet address, which is the near end of every latency the sheet
     *  measures. Null when the daemon has not reported a Self yet: then the connection block
     *  shows the far end alone rather than an arrow pointing at nothing. */
    selfAddress: String? = null,
    onDismiss: () -> Unit,
    onSendFileClick: (PeerData) -> Unit = {},
    /** Turn to a peer this sheet was handed. By the time this is called the page-turn has
     *  already carried that peer's content to the centre of the sheet. */
    onSelectPeer: (PeerData) -> Unit = {}
) {
    val page = peerAt(0) ?: return
    val peer = page.peer
    val prevPage = peerAt(-1)
    val nextPage = peerAt(1)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Keyed on the peer: the sheet deliberately stays composed across a peer change (see
    // swipeOffset below), so an unkeyed pingResult would show peer A's latency on peer B.
    var pingResult by remember(peer.id) { mutableStateOf<String?>(null) }
    // Which cards are open. Not keyed on the peer: both pages of a turn draw the same set,
    // so the arriving peer lands with the cards the finger left open and nothing jumps.
    var expandedGroups by remember { mutableStateOf(emptySet<PeerDetailGroup>()) }
    val onToggleGroup: (PeerDetailGroup) -> Unit = { group ->
        expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group
    }
    // The row whose value was just copied, for as long as its tick shows. Keyed on the peer:
    // a tick must not survive onto another peer's row of the same kind.
    var copiedDetail by remember(peer.id) { mutableStateOf<PeerDetailId?>(null) }
    LaunchedEffect(copiedDetail) {
        if (copiedDetail != null) {
            delay(PEER_COPIED_ACK_MS)
            copiedDetail = null
        }
    }

    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    // Page-turn state for the horizontal peer swipe. Both pages are on screen for the whole
    // gesture — the current one at swipeOffset, the arriving one a page width further along
    // in the drag direction — so the turn is one continuous motion with nothing to swap in
    // at the end: when the current page has finished leaving, the page that was sliding in
    // is already centred, and the swap below is a rename, not a movement.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { PEER_SWIPE_THRESHOLD.toPx() }
    val flingVelocityPx = with(density) { PEER_SWIPE_FLING_VELOCITY.toPx() }
    val maxOverscrollPx = with(density) { 56.dp.toPx() }
    /** Where the current page sits, in pixels from the centre. Read in the draw phase only
     *  (inside graphicsLayer), so a drag moves the pages without recomposing them. */
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    /** Which page is sliding in alongside the current one: -1 the previous peer, +1 the
     *  next, 0 nothing (the sheet is at rest, or the list ends that way). This one *is* read
     *  in composition — it decides whether the neighbour is composed at all — so it changes
     *  a couple of times per gesture rather than every frame. */
    var incomingStep by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // Compose exposes no reduced-motion flag here; the system animator duration scale is
    // the closest thing, and 0 is how "remove animations" reaches an app. ValueAnimator
    // keeps that scale in a process-local field, so this is a plain read that also stays
    // current; only the pre-O fallback pays for a binder call, once.
    val animateSwipe = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        android.animation.ValueAnimator.areAnimatorsEnabled()
    } else {
        remember {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }
    }
    // Material's own spatial springs: the default one finishes a turn, the fast one snaps
    // back a drag that decided nothing.
    // The gesture node below is keyed on nothing at all, so the lambda it runs is the one
    // captured at the first composition: everything it needs from a later one has to reach
    // it through state that outlives the recomposition.
    val turnSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>().settlingInPixels()
    val returnSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.fastSpatialSpec<Float>().settlingInPixels()
    val currentTurnSpec by rememberUpdatedState(turnSpec)
    val currentReturnSpec by rememberUpdatedState(returnSpec)
    val currentPrev by rememberUpdatedState(prevPage)
    val currentNext by rememberUpdatedState(nextPage)
    val currentOnSelect by rememberUpdatedState(onSelectPeer)
    // At the ends of the list the drag rubber-bands and springs back instead of doing
    // nothing at all, so "there is no next peer" is something you can feel.
    fun resistedOffset(travelled: Float): Float {
        val canMove = (travelled > 0f && currentPrev != null) || (travelled < 0f && currentNext != null)
        return if (canMove) travelled
        else (travelled * PEER_SWIPE_RESISTANCE).coerceIn(-maxOverscrollPx, maxOverscrollPx)
    }

    // Once per answer, not once per recomposition: incomingStep is read in composition and
    // the settle flips it several times per page turn, and this parses JSON and matches a
    // regex.
    val pingState = remember(pingResult) { pingStateOf(pingResult) }

    // Strings resolved in the parent composition — see wrapContextWithLocale().
    val strings = PeerDetailsStrings(
        prevPeer = stringResource(R.string.peer_details_prev),
        nextPeer = stringResource(R.string.peer_details_next),
        sendFile = stringResource(R.string.peer_send_file),
        pinging = stringResource(R.string.peer_pinging),
        pingResultFormat = stringResource(R.string.peer_ping_result),
        pingSelf = stringResource(R.string.peer_ping_self),
        pingSelfCaption = stringResource(R.string.peer_ping_self_caption),
        pingCardFormat = stringResource(R.string.peer_cd_ping_card),
        tapToMeasure = stringResource(R.string.peer_conn_tap_to_measure),
        copy = stringResource(R.string.action_copy),
        copied = stringResource(R.string.peer_copied),
        expanded = stringResource(R.string.peer_cd_expanded),
        collapsed = stringResource(R.string.peer_cd_collapsed),
        exitNodeSelected = stringResource(R.string.peer_exit_node_selected),
        exitNodeOffered = stringResource(R.string.peer_exit_node_offered),
        online = stringResource(R.string.peer_status_online),
        offline = stringResource(R.string.peer_status_offline),
        osUnknown = stringResource(R.string.peer_status_os_unknown),
        direct = stringResource(R.string.peer_status_direct),
        relayFormat = stringResource(R.string.peer_status_relay_format),
        latency = stringResource(R.string.peer_conn_latency),
        connPingFailed = stringResource(R.string.peer_conn_ping_failed),
        routeRelay = stringResource(R.string.peer_route_relay),
        routeNone = stringResource(R.string.peer_route_none),
        taildrop = stringResource(R.string.peer_taildrop_caption),
        taildropOk = stringResource(R.string.peer_taildrop_ok),
        taildropBlocked = stringResource(R.string.peer_taildrop_blocked),
        taildropNoNetmap = stringResource(R.string.peer_taildrop_no_netmap),
        taildropNotRunning = stringResource(R.string.peer_taildrop_not_running),
        taildropMissingCap = stringResource(R.string.peer_taildrop_missing_cap),
        taildropOffline = stringResource(R.string.peer_taildrop_offline),
        taildropNoPeerInfo = stringResource(R.string.peer_taildrop_no_peer_info),
        taildropUnsupportedOs = stringResource(R.string.peer_taildrop_unsupported_os),
        taildropNoPeerApi = stringResource(R.string.peer_taildrop_no_peer_api),
        taildropOtherUser = stringResource(R.string.peer_taildrop_other_user),
        trafficTotal = stringResource(R.string.peer_traffic_total),
        groupAddresses = stringResource(R.string.peer_group_addresses),
        groupRoute = stringResource(R.string.peer_group_route),
        groupConnection = stringResource(R.string.peer_group_connection),
        groupDevice = stringResource(R.string.peer_group_device),
        groupCapabilities = stringResource(R.string.peer_group_capabilities),
        groupTraffic = stringResource(R.string.peer_group_traffic),
        groupTiming = stringResource(R.string.peer_group_timing),
        groupOther = stringResource(R.string.peer_group_other)
    )
    // Also the parent's: the sheet's own context would put the toast in the system locale.
    // The toast stays for the case where the copied row has scrolled off under the finger;
    // the tick on the row itself is the acknowledgement for everyone else.
    val onCopyDetail: (PeerDetail) -> Unit = { row ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(row.label, row.value))
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, row.label), Toast.LENGTH_SHORT).show()
        copiedDetail = row.id
    }
    /** An arrow exists exactly when there is a page to turn to, and turns to that page. */
    fun turnTo(target: PeerPage?): (() -> Unit)? = target?.let { { onSelectPeer(it.peer) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                // The page leaving stops at the edge of the sheet, which on a tablet is
                // narrower than the window it sits in.
                .clipToBounds()
                // Keyed on nothing: a peer change, or a refresh that flips whether there is
                // a peer on either side, must not restart this node — a restart in the middle
                // of a drag leaves horizontalDrag hanging and the content stuck off-centre.
                // The neighbours are read through rememberUpdatedState instead.
                .pointerInput(Unit) {
                    // The sheet content, not the window: ModalBottomSheet caps its width at
                    // SheetMaxWidth, so on a tablet the window is much the wider of the two.
                    fun pageWidth(): Float = size.width.toFloat().takeIf { it > 0f } ?: 1f
                    // A page and a bit is all there is to show, so the finger cannot drag
                    // the current page past the arriving one and leave the sheet empty.
                    // Which neighbour belongs on screen at a given offset: the current page
                    // moving right uncovers the sheet's left half, which is where the
                    // previous peer lives, and the other way round. At rest the step stands
                    // as it is — there is no side to fill.
                    fun stepFor(offset: Float): Int = when {
                        offset > 0f -> -1
                        offset < 0f -> 1
                        else -> incomingStep
                    }
                    fun place(travelled: Float) {
                        val offset = resistedOffset(travelled).coerceIn(-pageWidth(), pageWidth())
                        swipeOffset = offset
                        val step = stepFor(offset)
                        if (step != incomingStep) incomingStep = step
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var overSlop = 0f
                        // The gesture is claimed only once the finger is clearly moving
                        // sideways — twice as much horizontally as vertically. A diagonal
                        // drag is left unconsumed, so the ModalBottomSheet keeps its own
                        // drag-to-dismiss; the detector keeps asking, so a drag that
                        // straightens out later is still picked up.
                        val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                            if (abs(over.x) > abs(over.y) * PEER_SWIPE_DIRECTION_RATIO) {
                                change.consume()
                                overSlop = over.x
                            }
                        }
                        if (drag != null) {
                            // A settle still in flight is abandoned where it stands. Both
                            // pages are on screen either way, so there is nothing to finish
                            // or unwind: the finger picks the motion up from there.
                            settleJob?.cancel()
                            var travelled = swipeOffset + overSlop
                            val tracker = VelocityTracker()
                            tracker.addPosition(drag.uptimeMillis, drag.position)
                            place(travelled)
                            val finished = horizontalDrag(drag.id) { change ->
                                travelled = (travelled + change.positionChange().x)
                                    .coerceIn(-pageWidth(), pageWidth())
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                place(travelled)
                            }
                            // Distance or velocity: a fast flick that never covers the
                            // threshold is the natural way to page through a list, and a
                            // slow deliberate crawl past it is the other one.
                            val velocity = tracker.calculateVelocity().x
                            val direction = when {
                                abs(velocity) > flingVelocityPx -> if (velocity > 0f) 1 else -1
                                travelled > swipeThresholdPx -> 1
                                travelled < -swipeThresholdPx -> -1
                                else -> 0
                            }
                            val target = when {
                                !finished -> null
                                direction > 0 -> currentPrev
                                direction < 0 -> currentNext
                                else -> null
                            }
                            val exitTo = if (direction > 0) pageWidth() else -pageWidth()
                            // A rubber-banded page moved at a quarter of the finger's speed,
                            // so it must not be handed the whole of it on release.
                            val resisted = (swipeOffset > 0f && currentPrev == null) ||
                                (swipeOffset < 0f && currentNext == null)
                            val settleVelocity = if (resisted) velocity * PEER_SWIPE_RESISTANCE else velocity
                            // The page drawn alongside now follows the direction that was
                            // committed, not whatever the last place() wrote. A drag one way
                            // ended by a fast flick back the other decides on the velocity
                            // before it has crossed zero, so the two disagree exactly there:
                            // without this the neighbour leaves by the same edge as the page
                            // it is replacing and the sheet turns blank for the whole settle.
                            if (target != null) incomingStep = -direction
                            // Settled from the composition scope, not this one: this scope
                            // dies with the gesture, and the settle outlives the finger.
                            settleJob = scope.launch {
                                if (target != null) {
                                    if (animateSwipe) {
                                        // Carried on from where the finger left the page, at
                                        // the speed it left it — one motion, not a new one.
                                        // The step is re-derived every frame, not frozen at
                                        // release: these springs are underdamped and carry
                                        // the finger's velocity, so the page regularly swings
                                        // back across zero on its way out. Whichever half of
                                        // the sheet it uncovers has the matching neighbour in
                                        // it, all the way to the end.
                                        animate(swipeOffset, exitTo, settleVelocity, currentTurnSpec) { value, _ ->
                                            swipeOffset = value
                                            val step = stepFor(value)
                                            if (step != incomingStep) incomingStep = step
                                        }
                                    }
                                    // The arriving page is already centred, so this moves
                                    // nothing: it makes that page the current one and zeroes
                                    // the offset it is drawn at. One snapshot, so no frame
                                    // can fall between the two and show the old page back at
                                    // the centre.
                                    Snapshot.withMutableSnapshot {
                                        swipeOffset = 0f
                                        incomingStep = 0
                                        currentOnSelect(target.peer)
                                    }
                                } else {
                                    if (animateSwipe) {
                                        animate(swipeOffset, 0f, settleVelocity, currentReturnSpec) { value, _ ->
                                            swipeOffset = value
                                            val step = stepFor(value)
                                            if (step != incomingStep) incomingStep = step
                                        }
                                    }
                                    swipeOffset = 0f
                                    incomingStep = 0
                                }
                            }
                        }
                    }
                }
        ) {
            // The neighbour, drawn a page width away in the drag direction and moving with
            // the current one: what is coming next is on screen from the first millimetre of
            // the drag, which is the whole point. It is measured against the sheet rather
            // than with the sheet, so more open cards on the next peer cannot resize
            // the sheet under the finger.
            val step = incomingStep
            val incoming = if (step != 0) peerAt(step) else null
            if (incoming != null) {
                PeerDetailsPage(
                    page = incoming,
                    strings = strings,
                    // No ping of its own can be in flight on a page nobody has landed on yet.
                    ping = PeerPingState.Idle,
                    selfAddress = selfAddress,
                    expanded = expandedGroups,
                    // Nothing has been copied off a page nobody has landed on either.
                    copied = null,
                    onPing = {},
                    onToggleGroup = onToggleGroup,
                    onSendFileClick = { onSendFileClick(incoming.peer) },
                    // The arriving page's own neighbours, so its arrows are already right
                    // when it lands and nothing pops in after the motion ends.
                    onPrevPeer = turnTo(peerAt(step - 1)),
                    onNextPeer = turnTo(peerAt(step + 1)),
                    onCopyDetail = onCopyDetail,
                    modifier = Modifier
                        .matchParentSize()
                        // Off screen, and a second copy of a peer's whole set of cards: it
                        // is there for the eye during the turn, not for a screen reader,
                        // which reaches the same peer through the page that lands.
                        .clearAndSetSemantics { }
                        .graphicsLayer { peerPageTransform(swipeOffset + step * size.width) }
                )
            }
            PeerDetailsPage(
                page = page,
                strings = strings,
                ping = pingState,
                selfAddress = selfAddress,
                expanded = expandedGroups,
                copied = copiedDetail,
                onToggleGroup = onToggleGroup,
                onPing = {
                    pingResult = PING_IN_FLIGHT
                    scope.launch { pingResult = pingPeer(peer.getPrimaryIp()) }
                },
                onSendFileClick = { onSendFileClick(peer) },
                onPrevPeer = turnTo(prevPage),
                onNextPeer = turnTo(nextPage),
                onCopyDetail = onCopyDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { peerPageTransform(swipeOffset) }
            )
        }
    }
}

/**
 * One peer's worth of sheet content. Two of these are alive during a page turn, so it owns
 * no state of its own and resolves no strings of its own: everything it shows — the ping,
 * which cards are open, which row was just copied — is handed to it by [PeerDetailsModal],
 * which composes outside the sheet's window and holds the one copy of each.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerDetailsPage(
    page: PeerPage,
    strings: PeerDetailsStrings,
    ping: PeerPingState,
    selfAddress: String?,
    /** The cards currently open. Shared by both pages of a turn, so the arriving page lands
     *  with the same cards open as the one it replaces and nothing jumps when it does. */
    expanded: Set<PeerDetailGroup>,
    /** The row whose value was just put on the clipboard, while its acknowledgement shows. */
    copied: PeerDetailId?,
    onPing: () -> Unit,
    onToggleGroup: (PeerDetailGroup) -> Unit,
    onSendFileClick: () -> Unit,
    onPrevPeer: (() -> Unit)?,
    onNextPeer: (() -> Unit)?,
    onCopyDetail: (PeerDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    val peer = page.peer
    // A cache, not state: the cards only change when the peer does, and during a turn this
    // runs for two peers at once.
    val cards = remember(peer, strings) { peerCards(peer, strings) }
    val taildrop = remember(peer, strings) { taildropStatusOf(peer, strings) }
    // Everything scrolls together, header included. The header is some 300dp of identity
    // row, status strip, button and connection card, and the sheet is capped at 85% of the
    // screen: in landscape, or in portrait at a large font scale, a fixed header over a
    // weighted card column left the column measured into nothing at all, and the cards it
    // could not fit were clipped with no way to scroll to them.
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item(key = "identity") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onPrevPeer != null) {
                    IconButton(onClick = onPrevPeer) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strings.prevPeer)
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (osIcon, osColor) = getOsVisuals(peer.os)
                    val statusColor = if (peer.online == true) PEER_ONLINE_GREEN else PEER_OFFLINE_GREY

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(osIcon, null, modifier = Modifier.size(16.dp), tint = osColor)
                        Spacer(Modifier.width(8.dp))
                        // Weighted, or a long node name pushes the status dot out of the chip.
                        Text(
                            peer.getDisplayName(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    }
                    // Under the chip rather than inside it: here there is room for the words,
                    // and "selected as exit node" vs "available as exit node" is a difference
                    // no one should have to read out of two shades of the same icon.
                    ExitNodeBadge(
                        peer = peer,
                        selectedLabel = strings.exitNodeSelected,
                        offeredLabel = strings.exitNodeOffered,
                        modifier = Modifier.padding(top = 6.dp),
                        showLabel = true
                    )
                }

                if (onNextPeer != null) {
                    IconButton(onClick = onNextPeer) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strings.nextPeer)
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        // The whole state of the peer, in words, before anything has been asked of the
        // network. It sits outside the identity row rather than inside it: between the two
        // arrows there is not enough width left for chips that are allowed to wrap.
        item(key = "status-strip") {
            PeerStatusStrip(
                peer = peer,
                ping = ping,
                strings = strings,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // The one action left up here: the ping moved into the connection card, which is the
        // thing it measures. Send file stays a button because it leaves the app. When the
        // daemon says the peer will not take a file the button is dead and the daemon's
        // reason stands beside it in the user's language — for a peer of another user's
        // that reason is an ACL grant, and the words say which one. A peer the control
        // plane calls offline keeps a live button under the same kind of note: the Online
        // bit lags, and a peer that woke a moment ago would otherwise show a dead button.
        item(key = "actions") {
            val taildropNote = when (taildrop) {
                TaildropStatus.Available -> null
                is TaildropStatus.Offline -> taildrop.reason
                is TaildropStatus.Blocked -> taildrop.reason
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                Button(
                    onClick = onSendFileClick,
                    enabled = taildrop !is TaildropStatus.Blocked,
                    // heightIn rather than height: «Отправить файл» wraps at a large font
                    // scale, and a fixed height cuts the second line off.
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.sendFile,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (taildropNote != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.padding(top = 1.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            taildropNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "connection") {
            PeerConnectionCard(
                page = page,
                selfAddress = selfAddress,
                ping = ping,
                strings = strings,
                onPing = onPing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // One item per card, keyed on the group: a card's open/closed reveal is its own
        // business and must not re-lay the cards around it.
        items(cards, key = { it.group.name }) { card ->
            PeerInfoCard(
                card = card,
                peer = peer,
                strings = strings,
                expanded = card.group in expanded,
                copied = copied,
                onToggle = { onToggleGroup(card.group) },
                onCopyDetail = onCopyDetail,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * The peer's state as words, in a row that wraps instead of clipping: online, what it runs,
 * how it is being reached, and the last latency once there is one. All of it comes out of
 * the status the sheet already has — nothing here asks the network anything.
 *
 * The exit-node state is deliberately *not* repeated here: the badge above the strip already
 * spells it out, in the same words, six dp away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerStatusStrip(
    peer: PeerData,
    ping: PeerPingState,
    strings: PeerDetailsStrings,
    modifier: Modifier = Modifier
) {
    val (osIcon, osColor) = getOsVisuals(peer.os)
    val online = peer.online == true
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PeerStatusChip(
            label = if (online) strings.online else strings.offline,
            icon = Icons.Default.Circle,
            iconTint = if (online) PEER_ONLINE_GREEN else PEER_OFFLINE_GREY,
            // The same dot as in the identity chip and the list row, at the same size.
            iconSize = 10.dp
        )
        PeerStatusChip(
            label = peer.os ?: strings.osUnknown,
            icon = osIcon,
            iconTint = osColor
        )
        val route = peerRoute(peer, strings)
        if (route != null) {
            PeerStatusChip(
                label = route.first,
                icon = route.second,
                iconTint = MaterialTheme.colorScheme.primary
            )
        }
        if (ping is PeerPingState.Measured) {
            PeerStatusChip(
                label = ping.latency,
                icon = Icons.Default.NetworkPing,
                iconTint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * How the peer is being reached right now, as the daemon reports it: a direct endpoint if
 * there is a CurAddr, otherwise the DERP region carrying the traffic. A peer nothing has
 * talked to yet has neither, and then the strip says nothing rather than guessing — the
 * "Relay (DERP)" and "Current Addr" rows below still carry the raw fields.
 */
private fun peerRoute(peer: PeerData, strings: PeerDetailsStrings): Pair<String, ImageVector>? = when {
    !peer.curAddr.isNullOrEmpty() -> strings.direct to Icons.Default.Lan
    !peer.relay.isNullOrEmpty() -> strings.relayFormat.format(peer.relay) to Icons.Default.Router
    else -> null
}

/**
 * One chip of the status strip. It states something instead of doing something, so it is not
 * a chip: an AssistChip with an empty onClick is still a full clickable — Role.Button
 * semantics and a ripple — and four of them per peer meant a screen reader walking four
 * buttons that announce "double tap to activate" and then do nothing, and a sighted user
 * getting a ripple that promises an action there isn't. This is the chip's own shape, height,
 * border and label style on a plain Surface, and it reads as one line of text.
 *
 * Everything the chip says is also in the rows further down for anyone who wants to copy it.
 */
@Composable
private fun PeerStatusChip(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    iconSize: Dp = AssistChipDefaults.IconSize
) {
    Surface(
        shape = AssistChipDefaults.shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        // One node with the label on it, not a button: the strip is four statements about the
        // peer, and each is read out as the statement it is.
        modifier = Modifier.clearAndSetSemantics { contentDescription = label }
    ) {
        Row(
            modifier = Modifier
                // defaultMinSize, the same as the chip itself: at a large font scale the pill
                // grows with the label instead of cutting it.
                .defaultMinSize(minHeight = AssistChipDefaults.Height)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AssistChipDefaults.HorizontalSpacing)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = iconTint)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** How far a card shrinks under the finger. */
private const val PEER_CARD_PRESSED_SCALE = 0.97f
/** The corner radius every card of the sheet shares. */
private val PEER_CARD_SHAPE = RoundedCornerShape(20.dp)
/** The side of the shaped box a card's icon sits in. */
private val PEER_CARD_ICON_BOX = 40.dp

/**
 * The card shrinking a little under the finger on the scheme's fast spatial spring, and
 * springing back when it lifts — the feedback the author asked for, over and above the
 * ripple. The scale is read in the draw phase, so a press animates without recomposing the
 * card, and during a page turn nothing is pressed, so it costs the turn nothing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PEER_CARD_PRESSED_SCALE else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "peerCardPress"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The one card surface of the sheet: tonal, rounded, and — when it has something to do —
 * alive under the finger. A card with no [onClick] is a plain Card, not a disabled clickable
 * one: a disabled clickable is read out as "disabled", which is the wrong thing to say about
 * a statement. [enabled] is for a card whose action is momentarily unavailable (a ping in
 * flight): it keeps its colours, since the card is busy, not dead.
 */
@Composable
private fun PeerSheetCard(
    onClick: (() -> Unit)?,
    containerColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val contentColor = contentColorFor(containerColor)
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = containerColor,
        disabledContentColor = contentColor
    )
    if (onClick == null) {
        Card(modifier = modifier, shape = PEER_CARD_SHAPE, colors = colors, content = content)
    } else {
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = modifier.pressScale(interaction),
            shape = PEER_CARD_SHAPE,
            colors = colors,
            content = content
        )
    }
}

/** A card's icon in a Material shape: the expressive idiom for "this is what the card is
 *  about". The shape is the card's own, so the eye can tell the cards apart before reading. */
@Composable
private fun PeerCardIcon(
    icon: ImageVector,
    shape: Shape,
    containerColor: Color,
    tint: Color,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier.size(PEER_CARD_ICON_BOX).clip(shape).background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) content()
        else Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}

/** Which Material shape frames each card's icon. Distinct per card on purpose. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerDetailGroup.iconShape(): Shape = when (this) {
    PeerDetailGroup.ADDRESSES -> MaterialShapes.Cookie4Sided
    PeerDetailGroup.ROUTE -> MaterialShapes.Arrow
    PeerDetailGroup.DEVICE -> MaterialShapes.Square
    PeerDetailGroup.CAPABILITIES -> MaterialShapes.Clover4Leaf
    PeerDetailGroup.TRAFFIC -> MaterialShapes.Gem
    PeerDetailGroup.TIMING -> MaterialShapes.Circle
    PeerDetailGroup.OTHER -> MaterialShapes.Pentagon
}.toShape()

/** The icon that names each card. The device card's comes from getOsVisuals and is chosen
 *  where the peer is known; this is everything else. */
private fun PeerDetailGroup.icon(): ImageVector = when (this) {
    PeerDetailGroup.ADDRESSES -> Icons.Default.Lan
    PeerDetailGroup.ROUTE -> Icons.Default.AltRoute
    PeerDetailGroup.DEVICE -> Icons.Default.Devices
    PeerDetailGroup.CAPABILITIES -> Icons.Default.Verified
    PeerDetailGroup.TRAFFIC -> Icons.Default.SwapVert
    PeerDetailGroup.TIMING -> Icons.Default.Schedule
    PeerDetailGroup.OTHER -> Icons.Default.MoreHoriz
}

/**
 * The measured connection, and the thing that measures it: the round trip as the figure, and
 * under it the path it was measured along — this device's tailnet address to the peer's —
 * because a bare "24 ms" says nothing about what was 24 ms away. Tapping the card starts a
 * ping; the LoadingIndicator runs in the icon's place while it is out, and the figure lands
 * where the dash was.
 *
 * Before a ping the card is not a hole: the path is known without measuring anything, so it
 * is drawn, and the figure slot holds a dash with "tap to measure" under it. That is also
 * where a failed ping reports itself. On this device's own page there is nothing to measure
 * and nothing to tap: the card says "this device" and is a statement, not a button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerConnectionCard(
    page: PeerPage,
    selfAddress: String?,
    ping: PeerPingState,
    strings: PeerDetailsStrings,
    onPing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val peer = page.peer
    val measured = ping as? PeerPingState.Measured
    val inFlight = ping == PeerPingState.InFlight
    val figure = when {
        page.isSelf -> strings.pingSelf
        measured != null -> measured.latency
        else -> "—"
    }
    val caption = when {
        page.isSelf -> strings.pingSelfCaption
        measured != null -> strings.latency
        inFlight -> strings.pinging
        ping == PeerPingState.Failed -> strings.connPingFailed
        else -> strings.tapToMeasure
    }
    // One description for the whole card: what it measures, where that stands, and — by its
    // role — that a tap measures again. A screen reader gets the same sentence a sighted
    // user reads off the figure and its caption.
    val description = if (page.isSelf) {
        "${strings.pingSelf}. ${strings.pingSelfCaption}"
    } else {
        val standing = measured?.let { strings.pingResultFormat.format(it.latency) } ?: caption
        "${strings.pingCardFormat.format(peer.getDisplayName())}. $standing"
    }
    PeerSheetCard(
        // A ping already in flight cannot be started again: the second tap would replace the
        // state the first one is about to write.
        onClick = if (page.isSelf) null else onPing,
        enabled = !inFlight,
        // A measured round trip is the one number in the sheet worth looking at, so it gets
        // the accent container; before that the card is quieter, but still a step above the
        // cards under it rather than the same surface again.
        containerColor = if (measured != null) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) {
            if (!page.isSelf) role = Role.Button
            contentDescription = description
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        strings.groupConnection,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // One line for a figure; two for the words on this device's own page,
                    // which at a large font scale or on a narrow phone do not fit one, and a
                    // word cut in half where a number is expected reads as a bug.
                    Text(
                        figure,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (page.isSelf) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.75f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                PeerCardIcon(
                    icon = Icons.Default.NetworkPing,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    containerColor = MaterialTheme.colorScheme.primary,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    content = if (!inFlight) null else {
                        {
                            // The measurement itself, not a word standing in for it. Handed the
                            // box's own content colour: LoadingIndicator defaults to the scheme's
                            // primary, which is the colour of the box it sits in.
                            LoadingIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (selfAddress != null && !page.isSelf) {
                    Text(
                        selfAddress,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowRightAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
                Text(
                    peer.getPrimaryIp(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

/**
 * One card of details in the connection card's language: an icon in a shape, the heading, the
 * lead carried large with its caption under it, and the quiet rows folded away. A tap opens
 * and closes the fold on the scheme's spatial spring — the card grows to show its rows and
 * shrinks back — with the chevron turning to say which way it will go. A card with nothing to
 * fold (one row, which is the lead) is a statement and does not react.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerInfoCard(
    card: PeerCard,
    peer: PeerData,
    strings: PeerDetailsStrings,
    expanded: Boolean,
    copied: PeerDetailId?,
    onToggle: () -> Unit,
    onCopyDetail: (PeerDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    val foldable = card.rows.isNotEmpty()
    val open = foldable && expanded
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "peerCardChevron"
    )
    val (icon, tint) = if (card.group == PeerDetailGroup.DEVICE) getOsVisuals(peer.os)
    else card.group.icon() to MaterialTheme.colorScheme.primary
    val title = strings.titleOf(card.group)
    val stateWords = if (open) strings.expanded else strings.collapsed
    // The lead is the row taken out of the fold, so it keeps the fold's copy affordance: the
    // IPv4 address, the version, the endpoint and the relay region are the four leads, and
    // the address is the one thing this sheet is most often opened to copy.
    val copyableLead = card.leadRow?.takeIf { it.id in PEER_COPYABLE_DETAILS }
    PeerSheetCard(
        onClick = if (foldable) onToggle else null,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.semantics {
            if (foldable) stateDescription = stateWords
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeerCardIcon(
                    icon = icon,
                    shape = card.group.iconShape(),
                    containerColor = tint.copy(alpha = 0.16f),
                    tint = tint
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Two lines, not one: a timestamp lead ("2025-08-01 12:00:00") does not fit
                    // one line of titleLarge beside the icon and the chevron on a narrow
                    // phone, and wrapping it after the date reads better than cutting the
                    // seconds off.
                    Text(
                        card.lead,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (card.leadMonospace) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (card.leadMonospace) 18.sp else MaterialTheme.typography.titleLarge.fontSize,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        card.leadCaption,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.75f)
                    )
                }
                if (copyableLead != null) {
                    Spacer(Modifier.width(4.dp))
                    PeerLeadCopyButton(
                        copied = copied == copyableLead.id,
                        strings = strings,
                        onCopy = { onCopyDetail(copyableLead) }
                    )
                }
                if (foldable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = chevron },
                        tint = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
            }
            if (foldable) {
                // The rows are composed only while open or animating, so a closed card is as
                // cheap as its header, and two peers' worth of closed cards are cheap during
                // a turn.
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                        shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec())
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.08f))
                        Spacer(Modifier.height(4.dp))
                        card.rows.forEach { row ->
                            PeerDetailRow(
                                row = row,
                                copied = copied == row.id,
                                strings = strings,
                                onCopy = onCopyDetail
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The copy button beside a card's lead. Its own button rather than a long-press on the card:
 * the card's tap is the fold, a long-press is not something a card advertises, and the open
 * rows below it show the same icon for the same action. After a tap the icon is a tick for
 * as long as the row's would be.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerLeadCopyButton(
    copied: Boolean,
    strings: PeerDetailsStrings,
    onCopy: () -> Unit
) {
    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
        Crossfade(
            targetState = copied,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "peerLeadCopyAck"
        ) { done ->
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = strings.copied,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = strings.copy,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * One quiet row of an open card: the label small over the value. A row worth copying is a
 * clickable with a copy icon at its end, and for a moment after the tap the icon becomes a
 * tick with "copied" beside it — the acknowledgement lands where the finger was, and not
 * only in a toast at the other end of the screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerDetailRow(
    row: PeerDetail,
    copied: Boolean,
    strings: PeerDetailsStrings,
    onCopy: (PeerDetail) -> Unit
) {
    val copyable = row.id in PEER_COPYABLE_DETAILS
    val technical = row.id in PEER_MONOSPACE_DETAILS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (copyable) Modifier.clickable(onClickLabel = strings.copy) { onCopy(row) }
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                row.value,
                fontFamily = if (technical) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (technical) 13.sp else 14.sp,
                fontWeight = if (technical) FontWeight.Normal else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (copyable) {
            Spacer(Modifier.width(8.dp))
            Crossfade(
                targetState = copied,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "peerCopyAck"
            ) { done ->
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            strings.copied,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

// --- FILES ---

@Composable
fun FileCard(file: TaildropFile, onOpen: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(file.ModTime * 1000))
    val sizeStr = formatFileSize(file.Size)
    val ext = file.Name.substringAfterLast('.', "").lowercase()

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(ext)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.Name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$dateStr • $sizeStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
            Button(onClick = onOpen, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_open)) }
        }
    }
}

@Composable
fun SentFileCard(entry: SentFileEntry) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        ListItem(headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text(stringResource(R.string.files_sent_to_format, entry.target, dateStr)) },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Outbound, null, tint = MaterialTheme.colorScheme.primary) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable
fun FileIcon(extension: String) {
    val (icon, color) = when (extension) {
        "jpg", "jpeg", "png", "webp", "gif" -> Icons.Default.Image to Color(0xFF4CAF50)
        "mp4", "mkv", "mov" -> Icons.Default.VideoFile to Color(0xFFFF9800)
        "mp3", "wav" -> Icons.Default.AudioFile to Color(0xFFE91E63)
        "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
        "zip", "rar", "7z" -> Icons.Default.FolderZip to Color(0xFF9C27B0)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF607D8B)
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(text, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
    }
}

fun openTaildropFile(context: Context, file: TaildropFile) {
    try {
        var f = File(file.Path)
        // The FileProvider only covers files/taildrop/. A Root Mode daemon
        // started by the boot script keeps writing into the pre-4.0 directory
        // until the app hands it the new one, so a path from outside is
        // migrated and re-resolved instead of failing to open.
        val root = File(context.filesDir, "taildrop").absolutePath + File.separator
        if (!f.absolutePath.startsWith(root)) {
            val accountId = AccountManager.getActiveAccount(context).id
            TaildropPaths.migrate(context, accountId)
            val moved = File(TaildropPaths.dir(context, accountId), f.name)
            if (moved.exists()) f = moved
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }, context.getString(R.string.files_open_file_chooser)))
    } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.files_error_cant_open, e.message), Toast.LENGTH_SHORT).show() }
}

fun highlightLogMessage(
    timestamp: String,
    category: String,
    message: String,
    defaultColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(style = SpanStyle(color = Color(0xFF757575))) {
            append(timestamp)
            append(" ")
        }

        val catColor = when (category) {
            "ERROR" -> Color(0xFFEF5350)
            "CORE" -> Color(0xFF42A5F5)
            "TAILSCALE" -> Color(0xFF66BB6A)
            else -> Color(0xFFFFA726)
        }
        withStyle(style = SpanStyle(color = catColor, fontWeight = FontWeight.Bold)) {
            append("[")
            append(category)
            append("] ")
        }

        val ipRegex = """\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?\b""".toRegex()
        val ipv6Regex = """\b([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""".toRegex()
        val keyValueRegex = """\b([a-zA-Z0-9_\-]+)=([^\s]+)\b""".toRegex()
        
        val successKeywords = setOf("success", "successful", "ok", "online", "active", "connected", "working", "available", "healthy")
        val errorKeywords = setOf("error", "failed", "fail", "blocked", "offline", "unavailable", "unreachable", "exception", "panic", "warning", "warn")

        val text = message
        var lastIdx = 0
        
        val matches = (ipRegex.findAll(text) + ipv6Regex.findAll(text) + keyValueRegex.findAll(text))
            .sortedBy { it.range.first }
            .toList()

        val nonOverlappingMatches = mutableListOf<MatchResult>()
        for (match in matches) {
            if (nonOverlappingMatches.isEmpty() || match.range.first >= nonOverlappingMatches.last().range.last + 1) {
                nonOverlappingMatches.add(match)
            }
        }

        for (match in nonOverlappingMatches) {
            val start = match.range.first
            val end = match.range.last + 1
            
            appendWithKeywords(text.substring(lastIdx, start), defaultColor, successKeywords, errorKeywords)
            
            val matchText = match.value
            if (ipRegex.matches(matchText) || ipv6Regex.matches(matchText)) {
                withStyle(style = SpanStyle(color = Color(0xFF80DEEA), fontWeight = FontWeight.Medium)) {
                    append(matchText)
                }
            } else {
                val parts = matchText.split("=", limit = 2)
                if (parts.size == 2) {
                    withStyle(style = SpanStyle(color = Color(0xFFFFCC80))) {
                        append(parts[0])
                        append("=")
                    }
                    val valText = parts[1]
                    val valColor = when {
                        valText.lowercase() in successKeywords -> Color(0xFFA5D6A7)
                        valText.lowercase() in errorKeywords -> Color(0xFFEF9A9A)
                        valText.all { it.isDigit() || it == '.' || it == ':' || it == 'm' || it == 's' } -> Color(0xFFB39DDB)
                        else -> Color(0xFFEEEEEE)
                    }
                    withStyle(style = SpanStyle(color = valColor)) {
                        append(valText)
                    }
                } else {
                    append(matchText)
                }
            }
            lastIdx = end
        }
        
        if (lastIdx < text.length) {
            appendWithKeywords(text.substring(lastIdx), defaultColor, successKeywords, errorKeywords)
        }
    }
}

private fun AnnotatedString.Builder.appendWithKeywords(
    text: String,
    defaultColor: Color,
    successKeywords: Set<String>,
    errorKeywords: Set<String>
) {
    val wordRegex = """\b[a-zA-Z_]+\b""".toRegex()
    var lastIdx = 0
    val matches = wordRegex.findAll(text).toList()
    
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        
        if (start > lastIdx) {
            withStyle(style = SpanStyle(color = defaultColor)) {
                append(text.substring(lastIdx, start))
            }
        }
        
        val word = match.value
        val lowerWord = word.lowercase()
        val wordColor = when {
            lowerWord in successKeywords -> Color(0xFF81C784)
            lowerWord in errorKeywords -> Color(0xFFE57373)
            else -> defaultColor
        }
        withStyle(style = SpanStyle(color = wordColor, fontWeight = if (wordColor != defaultColor) FontWeight.Bold else FontWeight.Normal)) {
            append(word)
        }
        lastIdx = end
    }
    
    if (lastIdx < text.length) {
        withStyle(style = SpanStyle(color = defaultColor)) {
            append(text.substring(lastIdx))
        }
    }
}
