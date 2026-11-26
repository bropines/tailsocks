package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.*
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/*
 * A peer in a list: the Peers row with its address line and path text, the share-picker row,
 * the OS icon and exit-node badge they wear, and the timestamp helpers behind "2 h ago".
 */

/**
 * A hostname is one token to the line breaker, so a long one splits wherever the
 * line happens to end ("ffm-access-01-jonne" / "rly" in the exit-node sheet). A
 * zero-width space after every hyphen and dot gives the breaker the places a
 * reader would break at, and draws nothing.
 */
internal fun String.withBreakOpportunities(): String =
    replace("-", "-\u200B").replace(".", ".\u200B")

fun getOsVisuals(os: String?): Pair<ImageVector, Color> {
    val osLower = os?.lowercase().orEmpty()
    val icon = when {
        osLower.contains("android") -> Icons.Default.Android
        osLower.contains("windows") -> OsIcons.Windows
        osLower.contains("linux") -> OsIcons.Linux
        osLower.contains("macos") || osLower.contains("darwin") -> OsIcons.Apple
        osLower.contains("ios") -> OsIcons.Ios
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

/**
 * A peer's OS in its tinted circle, with TailSocks's sock in the corner when the peer runs
 * TailSocks ([PeerData.isTailSocks]): two Android phones look alike until the client is told
 * apart, and it is told to TalkBack as well. [ring] is the colour the row is drawn on, cut
 * around the sock so it stands off the circle. [tailsocksLabel] is the sock's TalkBack label;
 * inside a sheet the caller resolves it, since the sheet's window follows the system locale,
 * and null resolves it here.
 */
@Composable
internal fun PeerOsIcon(
    peer: PeerData,
    size: Dp,
    iconSize: Dp,
    ring: Color,
    modifier: Modifier = Modifier,
    tailsocksLabel: String? = null
) {
    val (osIcon, osColor) = getOsVisuals(peer.iconOs())
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(osColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(osIcon, null, Modifier.size(iconSize), tint = osColor)
        }
        if (peer.isTailSocks) {
            val label = tailsocksLabel ?: stringResource(R.string.peer_client_tailsocks)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(size * 0.5f)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_qs_tile),
                    null,
                    Modifier.size(size * 0.36f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
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
                .clip(MaterialTheme.shapes.small)
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
                    .clip(MaterialTheme.shapes.small)
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

/**
 * An RFC 3339 stamp as the daemon writes one (Go's time.Time: fractional seconds optional,
 * "Z" or an offset) in epoch milliseconds; null for Go's zero time and anything unreadable.
 * By hand rather than through java.time, which is API 26 and this app runs from 24.
 */
internal fun parseRfc3339Millis(stamp: String?): Long? {
    if (stamp.isNullOrBlank() || stamp.startsWith("0001-01-01")) return null
    val m = RFC3339.matchEntire(stamp.trim()) ?: return null
    val base = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(m.groupValues[1])?.time
    }.getOrNull() ?: return null
    val millis = m.groupValues[2].drop(1).padEnd(3, '0').take(3).toInt()
    val zone = m.groupValues[3]
    val offset = if (zone == "Z" || zone == "z") 0L else {
        val sign = if (zone[0] == '-') -1 else 1
        sign * (zone.substring(1, 3).toLong() * 60 + zone.substring(4, 6).toLong()) * 60_000L
    }
    return base + millis - offset
}

private val RFC3339 = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

/** "2 h ago", in the app's language: [context] must be the locale-wrapped one. */
internal fun agoText(context: Context, thenMillis: Long, nowMillis: Long): String {
    val res = context.resources
    val minutes = ((nowMillis - thenMillis) / 60_000L).coerceAtLeast(0L)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> res.getString(R.string.ago_just_now)
        hours < 1 -> res.getString(R.string.ago_minutes, minutes.toInt())
        days < 1 -> res.getString(R.string.ago_hours, hours.toInt())
        days < 60 -> res.getQuantityString(R.plurals.ago_days, days.toInt(), days.toInt())
        days < 365 -> res.getString(R.string.ago_months, (days / 30).toInt())
        else -> res.getQuantityString(R.plurals.ago_years, (days / 365).toInt(), (days / 365).toInt())
    }
}

/**
 * One peer in the list. [nowMillis] is what "last seen" is measured against; a caller that
 * renders a demo pins it, so a preview does not age between two runs. [onLongClick], with
 * [onLongClickLabel] for a screen reader's actions menu, is the peers screen's "Copy as…";
 * a list without one (the Files hub) leaves both null.
 */
@Composable
internal fun PeerItem(
    peer: PeerData,
    isSelf: Boolean,
    ping: PeerPingState = PeerPingState.Idle,
    nowMillis: Long = System.currentTimeMillis(),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    /** The row whose details the pane beside the list is showing; only a two-pane layout
     *  has one. Outlined, so this device's tinted row can be the selected one as well. */
    selected: Boolean = false,
    /** The path's words; a list resolves them once for all its rows. */
    pathWords: PeerPathWords = peerPathWords(),
    /** For the screen to place the row: the remote's first stop, say. */
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // Named by the list's LocalPeerPathNames: a relay's node, a region's city. A ping fills in
    // the path of a peer nothing else has talked to yet.
    val names = LocalPeerPathNames.current
    val path = remember(peer, isSelf, names, ping) { names.pathOf(peer, isSelf, ping) }
    val ago = remember(path, nowMillis, context) {
        (path as? PeerPath.Offline)?.lastSeenMillis?.let { agoText(context, it, nowMillis) }
    }
    // The green dot said "online" in colour alone; this says it in words, with the path.
    val online = peer.online == true || isSelf
    val spokenState = listOfNotNull(
        stringResource(if (online) R.string.peer_status_online else R.string.peer_status_offline),
        pathWords.spoken(path, ago)
    ).joinToString(", ")
    // Clipped before the click: a clickable on an unclipped modifier drew its ripple as a
    // rectangle spilling past the rounded corners. Not Surface(onClick), which has no long
    // press.
    val shape = MaterialTheme.shapes.large
    // A remote's ring goes before the clip, or it loses its corners.
    val focusSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .tvFocusRing(focusSource, shape)
            .clip(shape)
            .combinedClickable(
                interactionSource = focusSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .semantics {
                stateDescription = spokenState
                if (selected) this.selected = true
            },
        shape = shape,
        color = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PeerOsIcon(
                peer,
                size = 40.dp,
                iconSize = 20.dp,
                ring = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val displayName = peer.getDisplayName()
                val primaryIp = peer.getPrimaryIp()
                // Long names travel across the row rather than being cut off.
                Text(
                    displayName,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
                if (displayName != primaryIp) {
                    PeerAddressLine(primaryIp, path, ago, ping, pathWords)
                }
                if (!peer.tags.isNullOrEmpty()) {
                    Text(
                        text = peer.tags.joinToString(", "),
                        fontSize = if (isTv) 13.sp else 11.sp,
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
            if (online) {
                Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(PEER_ONLINE_GREEN))
            }
        }
    }
}

/**
 * The address, the path and the latency on one line — each on this line and not on one of
 * its own: the tag list of an exit node already makes a row taller than its neighbours, and
 * a chip that appeared when a ping came back would change the height of every row at once.
 * The latency sits outside the part that is cut short, so a narrow row loses the path
 * before it loses the figure someone just asked for. The path comes long when the row has
 * the width — the endpoint, the relay node, the city — and short when it has not.
 */
@Composable
private fun PeerAddressLine(address: String, path: PeerPath, ago: String?, ping: PeerPingState, words: PeerPathWords) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Read from a sofa: two sp more.
    val size = if (isTv) 14.sp else 12.sp
    val variant: @Composable (PathLine?) -> Unit = { line ->
        val (text, inline) = peerPathText(address, line, muted)
        Text(
            text,
            inlineContent = inline,
            fontFamily = FontFamily.Monospace,
            fontSize = size,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = muted
        )
    }
    val latency = when (ping) {
        is PeerPingState.Measured -> " · ${ping.latency}"
        PeerPingState.InFlight -> " · …"
        PeerPingState.Failed -> " · ✕"
        PeerPingState.Idle -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The row's own state description says the path in words; the lines here are
        // pictures of it, longest first, and only the one that fits is placed.
        FirstThatFits(Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = address }) {
            val lines = words.lines(path, ago)
            if (lines.isEmpty()) variant(null) else lines.forEach { variant(it) }
        }
        if (latency != null) {
            Text(
                latency,
                fontFamily = FontFamily.Monospace,
                fontSize = size,
                maxLines = 1,
                softWrap = false,
                color = if (ping is PeerPingState.Measured) MaterialTheme.colorScheme.primary else muted
            )
        }
    }
}

/**
 * An address followed by a path line: the icon, inline and as the separator, then the path's
 * word and what it goes through. The address keeps the face of the Text it is set in; words
 * take the text face and machine text (an endpoint, a relay's address) monospace — in
 * monospace "direct" took a third more width and was the first thing cut on an exit node's
 * row. The icon is decoration — the word after it says the same — so a screen reader gets a
 * blank in its place.
 */
internal fun peerPathText(
    address: String,
    line: PathLine?,
    iconTint: Color
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val text = buildAnnotatedString {
        append(address)
        if (line != null) {
            append(" ")
            appendInlineContent(PEER_PATH_ICON, " ")
            if (line.word.isNotEmpty()) {
                withStyle(SpanStyle(fontFamily = FontFamily.Default)) {
                    append(" ")
                    append(line.word)
                }
            }
            if (line.detail != null) {
                append(" ")
                if (line.detailIsAddress) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(line.detail) }
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium)) { append(line.detail) }
                }
            }
        }
    }
    val inline = if (line == null) emptyMap() else mapOf(
        PEER_PATH_ICON to InlineTextContent(Placeholder(14.sp, 12.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Icon(line.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
            }
        }
    )
    return text to inline
}

private const val PEER_PATH_ICON = "peerPath"

/**
 * One row of a send picker. [taildrop] is the daemon's verdict on this peer: Blocked makes
 * the row dead and prints the reason under the name, Offline keeps it live and prints the
 * note (the Online bit lags; the transfer itself finds out), Available prints nothing extra.
 * [tailsocksLabel] and [osUnknown] come from the caller: the row is drawn in a sheet, whose
 * window follows the system locale rather than the app's.
 */
@Composable
fun PeerShareItem(
    peer: PeerData,
    enabled: Boolean,
    tailsocksLabel: String,
    osUnknown: String,
    taildrop: TaildropStatus = TaildropStatus.Available,
    onClick: () -> Unit
) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
    val refused = taildrop is TaildropStatus.Blocked
    val note = when (taildrop) {
        TaildropStatus.Available -> null
        is TaildropStatus.Offline -> taildrop.reason
        is TaildropStatus.Blocked -> taildrop.reason
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(if (refused) 0.55f else 1f)
            // Clipped first, so the ripple keeps to the card's corners.
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled && !refused) { onClick() },
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerOsIcon(
                peer,
                size = 38.dp,
                iconSize = 18.dp,
                ring = cardColor.compositeOver(MaterialTheme.colorScheme.surface),
                tailsocksLabel = tailsocksLabel
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.getDisplayName(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    peer.os ?: osUnknown,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
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
