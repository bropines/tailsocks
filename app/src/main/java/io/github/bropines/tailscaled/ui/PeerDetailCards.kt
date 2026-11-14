package io.github.bropines.tailscaled.ui

import androidx.compose.material.icons.automirrored.filled.AltRoute
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The cards of the peer details sheet: which detail row goes on which card, the route card,
 * the sheet's Taildrop verdict, and the card composables with their rows and copy buttons.
 */

/**
 * The cards the detail rows are dealt into, in the order they are drawn under the connection
 * card. Each is one card of the sheet: a heading, an icon, the group's most important value
 * carried large, and the rest folded away as quieter rows. OTHER is the safety net:
 * PeerData.getDetailsList() is free to grow a field without this file, and an unplaced row
 * must still be shown.
 */
internal enum class PeerDetailGroup { ADDRESSES, ROUTE, DEVICE, CAPABILITIES, TRAFFIC, TIMING, OTHER }

/** The cards a details page starts with open: none, but in a preview, which cannot press a key. */
internal val LocalPeerCardsOpen = staticCompositionLocalOf<Set<PeerDetailGroup>> { emptySet() }

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
    PeerDetailId.PEER_RELAY to PeerDetailGroup.ROUTE,
    PeerDetailId.IN_NETWORK_MAP to PeerDetailGroup.ROUTE,
    PeerDetailId.IN_MAGICSOCK to PeerDetailGroup.ROUTE,
    PeerDetailId.IN_WG_ENGINE to PeerDetailGroup.ROUTE,
    PeerDetailId.OS to PeerDetailGroup.DEVICE,
    PeerDetailId.CLIENT to PeerDetailGroup.DEVICE,
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
    PeerDetailId.RELAY, PeerDetailId.PEER_RELAY, PeerDetailId.NO_FILE_SHARING
)

/** Of those, the rows a television shows with a QR code: an address, a name that resolves to one, a link. */
internal val PEER_QR_DETAILS = setOf(
    PeerDetailId.IPV4, PeerDetailId.IPV6, PeerDetailId.DNS_NAME, PeerDetailId.CUR_ADDR, PeerDetailId.PEER_API
)

/** The rows that are machine text and line up better in a monospace face. */
private val PEER_MONOSPACE_DETAILS = setOf(
    PeerDetailId.IPV4, PeerDetailId.IPV6, PeerDetailId.ALLOWED_IPS, PeerDetailId.NODE_ID,
    PeerDetailId.VERSION, PeerDetailId.CUR_ADDR, PeerDetailId.PEER_API, PeerDetailId.PEER_RELAY
)

/**
 * One card of the sheet below the connection card: what it leads with, and the quieter rows
 * it folds away. Built once per peer by [peerCards]; the composable only draws it, and during
 * a page turn it is built for two peers at once, so it does no work a draw does not need.
 */
internal class PeerCard(
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
    val rows: List<PeerDetail>,
    /** An explanation under the caption, folded to two lines. */
    val help: String? = null
)

/**
 * The rows of PeerData.getDetailsList() dealt into cards, each with its lead chosen. Where a
 * group has one row that is plainly the thing to look at, that row is the lead and leaves the
 * quiet rows (the IPv4 address, the OS, when the peer was last seen). Where
 * the interesting value is a reading of several fields — how the peer is reached, whether it
 * takes files, how much has passed — the lead is derived and the raw rows all stay, so
 * nothing the daemon reported is lost behind the summary of it. A group with no rows is
 * dropped rather than drawn empty.
 */
internal fun peerCards(peer: PeerData, strings: PeerDetailsStrings, version: String?, isSelf: Boolean, path: PeerPath): List<PeerCard> {
    val details = peer.getDetailsList(tailscaleVersion = version)
    return PeerDetailGroup.entries.mapNotNull { group ->
        val rows = details.filter { (PEER_DETAIL_GROUP_OF[it.id] ?: PeerDetailGroup.OTHER) == group }
        if (rows.isEmpty()) return@mapNotNull null
        fun leadRow(id: PeerDetailId): PeerCard? = rows.firstOrNull { it.id == id }?.let { lead ->
            PeerCard(group, lead.value, lead.label, lead.id in PEER_MONOSPACE_DETAILS, lead, rows - lead)
        }
        val card = when (group) {
            PeerDetailGroup.ADDRESSES -> leadRow(PeerDetailId.IPV4)
            PeerDetailGroup.DEVICE -> leadRow(PeerDetailId.OS)
            PeerDetailGroup.TIMING -> leadRow(PeerDetailId.LAST_SEEN)
            PeerDetailGroup.ROUTE -> routeCard(peer, path, rows, strings.path)
            // The verdict, in a few words: the reason behind it is a sentence, and it is
            // already under the Send File button where a sentence has room to wrap. Drawn
            // large here it would be cut after two lines — exactly the ACL instruction a
            // peer of another user's needs. "Offline" is short enough to stand as it is.
            PeerDetailGroup.CAPABILITIES -> PeerCard(
                group,
                when (val taildrop = peerTaildropStatus(peer, isSelf, strings)) {
                    TaildropStatus.Available -> strings.taildropOk
                    is TaildropStatus.Offline -> taildrop.reason
                    is TaildropStatus.Blocked -> if (isSelf) strings.taildropSelfShort else strings.taildropBlocked
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

/**
 * The route card: the same reading as the status strip's chip and the list row, with the
 * endpoint, the relay node or the city as the figure. The row a lead was read off steps out of
 * the fold; the others stay — "Current Addr: N/A" under a relay is what the daemon said, and
 * someone debugging a connection will want to see it said. Where the lead is a name the
 * status does not carry (a relay's node, a region's city), the raw row stays in the fold
 * with its copy icon. A relay explains itself, once, folded.
 */
private fun routeCard(peer: PeerData, path: PeerPath, rows: List<PeerDetail>, w: PeerPathWords): PeerCard {
    val group = PeerDetailGroup.ROUTE
    fun row(id: PeerDetailId) = rows.firstOrNull { it.id == id }
    fun led(lead: String, caption: String, mono: Boolean, from: PeerDetail?, help: String?) =
        PeerCard(group, lead, caption, mono, from, if (from != null) rows - from else rows, help)
    // The model writes "Direct" into an empty Relay row; under a card that is not about a
    // direct path that row is the card arguing with itself.
    val withoutEmptyRelay = if (peer.relay.isNullOrEmpty()) rows.filterNot { it.id == PeerDetailId.RELAY } else rows
    return when (path) {
        is PeerPath.Direct -> led(
            path.endpoint, if (path.local) w.routeDirectLocal else w.routeDirectInternet, true, row(PeerDetailId.CUR_ADDR), null
        )
        is PeerPath.PeerRelay ->
            if (path.node != null) led(path.node, w.routePeerRelayFormat.format(path.address), false, null, w.help)
            else led(path.address, w.routePeerRelay, true, null, w.help)
        is PeerPath.Derp ->
            if (path.city != null) led(path.city, w.routeDerpFormat.format(path.region), false, null, w.help)
            else led(path.region, w.routeDerp, false, row(PeerDetailId.RELAY), w.help)
        is PeerPath.NotYet -> PeerCard(
            group, "—",
            path.home?.let { w.routeNotYetFormat.format(w.region(it, path.homeCity)) } ?: w.routeNotYet,
            false, null, withoutEmptyRelay, w.help
        )
        is PeerPath.Offline -> PeerCard(group, "—", w.routeOffline, false, null, withoutEmptyRelay)
        is PeerPath.Self -> when {
            path.home == null -> PeerCard(group, "—", w.noHome, false, null, withoutEmptyRelay, w.help)
            path.homeCity != null -> led(path.homeCity, w.routeHomeFormat.format(path.home), false, null, w.help)
            else -> led(path.home, w.routeHome, false, row(PeerDetailId.RELAY), w.help)
        }
    }
}

/**
 * The sheet's verdict. This device is never a target, whatever the daemon reports for it:
 * since a user-owned node may send to its owner's other devices, Self comes with no refusal
 * code, and a file sent to it ends in a 404 — the daemon's targets are the other devices.
 */
internal fun peerTaildropStatus(peer: PeerData, isSelf: Boolean, strings: PeerDetailsStrings): TaildropStatus =
    if (isSelf) TaildropStatus.Blocked(strings.taildropSelf) else taildropStatusOf(peer, strings)

private fun taildropStatusOf(peer: PeerData, strings: PeerDetailsStrings): TaildropStatus =
    taildropStatusOf(
        peer,
        TaildropReasonStrings(
            blocked = strings.taildropBlocked,
            noNetmap = strings.taildropNoNetmap,
            notRunning = strings.taildropNotRunning,
            missingCap = strings.taildropMissingCap,
            offline = strings.taildropOffline,
            noPeerInfo = strings.taildropNoPeerInfo,
            unsupportedOs = strings.taildropUnsupportedOs,
            noPeerApi = strings.taildropNoPeerApi,
            otherUser = strings.taildropOtherUser
        )
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

/** How far a card shrinks under the finger. */
private const val PEER_CARD_PRESSED_SCALE = 0.97f
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
        Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, content = content)
    } else {
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = modifier.pressScale(interaction),
            shape = MaterialTheme.shapes.large,
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
    PeerDetailGroup.ROUTE -> Icons.AutoMirrored.Filled.AltRoute
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
internal fun PeerConnectionCard(
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
 *
 * On a television the card is only a surface. A card that is one target as a whole hides the
 * targets inside it from the remote, which then reaches neither the lead's button nor a row. So
 * there the header is one stop (OK folds and unfolds; resting on it unfolds the explanation), the
 * lead's button is a stop beside it, and each row that acts is a stop under it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PeerInfoCard(
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
    val (icon, tint) = if (card.group == PeerDetailGroup.DEVICE) getOsVisuals(peer.iconOs())
    else card.group.icon() to MaterialTheme.colorScheme.primary
    val title = strings.titleOf(card.group)
    val stateWords = if (open) strings.expanded else strings.collapsed
    // The lead is the row taken out of the fold, so it keeps the fold's copy affordance: the
    // IPv4 address, the version, the endpoint and the relay region are the four leads, and
    // the address is the one thing this sheet is most often opened to copy.
    val copyableLead = card.leadRow?.takeIf { it.id in PEER_COPYABLE_DETAILS }
    val tv = isTv
    val helpOpen = remember(card.help) { mutableStateOf(false) }

    // The parts of the header, the same on a phone and on a television.
    val heading: @Composable RowScope.() -> Unit = {
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
    }
    val leadButton: @Composable () -> Unit = {
        if (copyableLead != null) {
            PeerLeadCopyButton(
                copied = copied == copyableLead.id,
                strings = strings,
                onCopy = { onCopyDetail(copyableLead) },
                showIcon = if (copyableLead.id in PEER_QR_DETAILS) Icons.Default.QrCode2 else Icons.Default.Fullscreen
            )
        }
    }
    val chevronIcon: @Composable () -> Unit = {
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = chevron },
            tint = LocalContentColor.current.copy(alpha = 0.6f)
        )
    }
    val help: @Composable () -> Unit = {
        if (card.help != null) {
            // Under the heading's column, the width of the card: beside the chevron two
            // lines would hold half as much. Only the ⓘ unfolds it — a tap anywhere else
            // on the card is the card's fold.
            HelpText(
                card.help,
                modifier = Modifier.padding(start = PEER_CARD_ICON_BOX + 14.dp, top = 8.dp),
                color = LocalContentColor.current.copy(alpha = 0.75f),
                expanded = helpOpen,
                inClickableRow = foldable
            )
        }
    }
    val fold: @Composable ColumnScope.() -> Unit = {
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
                    HorizontalDivider(
                        // The rows' ring sits wider than the card's padding of a phone.
                        modifier = if (tv) Modifier.padding(horizontal = 8.dp) else Modifier,
                        color = LocalContentColor.current.copy(alpha = 0.08f)
                    )
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

    if (tv) {
        PeerSheetCard(
            onClick = null,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier
        ) {
            // The padding is the stops': each rings itself inside the card, and the text in it
            // stands where a phone's does.
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row {
                    val headerInset = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    if (foldable) {
                        ExplainedRow(
                            onClick = onToggle,
                            help = helpOpen,
                            modifier = Modifier.weight(1f).semantics { stateDescription = stateWords },
                            color = Color.Transparent
                        ) {
                            Column(Modifier.fillMaxWidth().then(headerInset)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    heading()
                                    Spacer(Modifier.width(8.dp))
                                    chevronIcon()
                                }
                                help()
                            }
                        }
                    } else {
                        Column(Modifier.weight(1f).then(headerInset)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { heading() }
                            help()
                        }
                    }
                    // Beside the header and not in it: a stop inside a stop is out of the
                    // remote's reach. Level with the heading's middle, as on a phone.
                    if (copyableLead != null) Box(Modifier.padding(top = 18.dp)) { leadButton() }
                }
                fold()
            }
        }
        return
    }

    PeerSheetCard(
        onClick = if (foldable) onToggle else null,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.semantics {
            if (foldable) stateDescription = stateWords
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                heading()
                if (copyableLead != null) {
                    Spacer(Modifier.width(4.dp))
                    leadButton()
                }
                if (foldable) {
                    Spacer(Modifier.width(8.dp))
                    chevronIcon()
                }
            }
            help()
            fold()
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
    onCopy: () -> Unit,
    /** What stands for "show it large" where there is nothing to copy into: a code, or just bigger. */
    showIcon: ImageVector
) {
    if (!Feature.CLIPBOARD_ACTIONS.offered()) {
        // A television shows the value instead (onCopy opens it), on a target a remote can hit.
        IconButton(onClick = onCopy, modifier = Modifier.size(44.dp)) {
            Icon(showIcon, contentDescription = strings.showLarge, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        return
    }
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
    // Where nothing can be copied (a television) the row is a target that shows its value
    // large instead; its ring goes before the clip, so it keeps its corners.
    val copies = Feature.CLIPBOARD_ACTIONS.offered()
    val bump = if (isTv) 2 else 0
    val shape = MaterialTheme.shapes.medium
    val focusSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusRing(focusSource, shape)
            .clip(shape)
            .then(
                if (copyable) Modifier.clickable(
                    interactionSource = focusSource,
                    indication = LocalIndication.current,
                    onClickLabel = if (copies) strings.copy else strings.showLarge
                ) { onCopy(row) }
                else Modifier
            )
            // Wider on a television: its ring is 4dp inside the row, and the text stands
            // where it did with the card around it as the stop.
            .padding(horizontal = if (isTv) 14.dp else 6.dp, vertical = 8.dp),
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
                fontSize = (if (technical) 13 + bump else 14 + bump).sp,
                fontWeight = if (technical) FontWeight.Normal else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (copyable && !copies) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (row.id in PEER_QR_DETAILS) Icons.Default.QrCode2 else Icons.Default.Fullscreen,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (copyable) {
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
