package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * One page of the peer details sheet: the identity block, the status strip and its chips, the
 * action row, and the cards laid out in one or two columns.
 */

/** The largest font scale at which Send file and Copy as… still share a row. Android's next
 *  step up is 1.5, where one word of either label no longer fits half a 360dp phone. */
private const val PEER_ACTIONS_SIDE_BY_SIDE_MAX_FONT_SCALE = 1.3f

/** The green of "this peer is up", shared by the list row, the share row, the identity chip
 *  and the status strip so the four cannot drift apart. */
internal val PEER_ONLINE_GREEN = Color(0xFF4CAF50)
/** And the grey of "it is not". Fixed rather than theme-derived for the same reason the green
 *  is: the identity chip's dot and the status strip's dot sit ten dp apart and say the same
 *  thing, so they must be the same colour in both schemes. */
private val PEER_OFFLINE_GREY = Color(0xFF9E9E9E)

/**
 * One peer's worth of sheet content. Two of these are alive during a page turn, so it owns
 * no state of its own and resolves no strings of its own: everything it shows — the ping,
 * which cards are open, which row was just copied — is handed to it by [PeerDetailsModal],
 * which composes outside the sheet's window and holds the one copy of each.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PeerDetailsPage(
    page: PeerPage,
    strings: PeerDetailsStrings,
    pathNames: PeerPathNames,
    ping: PeerPingState,
    selfAddress: String?,
    /** The cards currently open. Shared by both pages of a turn, so the arriving page lands
     *  with the same cards open as the one it replaces and nothing jumps when it does. */
    expanded: Set<PeerDetailGroup>,
    /** The row whose value was just put on the clipboard, while its acknowledgement shows. */
    copied: PeerDetailId?,
    /** "Manage this device", for the page on screen; null on the one arriving beside it. */
    manage: RemoteManageEntryState?,
    onPing: () -> Unit,
    onToggleGroup: (PeerDetailGroup) -> Unit,
    onSendFileClick: () -> Unit,
    onCopyAsClick: (() -> Unit)?,
    onPrevPeer: (() -> Unit)?,
    onNextPeer: (() -> Unit)?,
    onCopyDetail: (PeerDetail) -> Unit,
    modifier: Modifier = Modifier,
    /** How many columns the cards stand in under the header; one in a sheet. */
    columns: Int = 1
) {
    val peer = page.peer
    // A cache, not state: the cards only change when the page does (the peer, or the version
    // the Admin API answered for it), and during a turn this runs for two peers at once.
    // How the peer is reached: the status's reading, or — for a peer nothing has talked to yet
    // — the way this page's ping came back.
    val path = remember(page, pathNames, ping) { pathNames.pathOf(peer, page.isSelf, ping) }
    val cards = remember(page, strings, path) { peerCards(peer, strings, page.version, page.isSelf, path) }
    val taildrop = remember(page, strings) { peerTaildropStatus(peer, page.isSelf, strings) }
    // The page's parts. A sheet lists them one under another; a pane wide enough for it
    // keeps the header across the top and stands the cards in columns under it.
    val identity: @Composable () -> Unit = {
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
                val (osIcon, osColor) = getOsVisuals(peer.iconOs())
                val statusColor = if (peer.online == true) PEER_ONLINE_GREEN else PEER_OFFLINE_GREY

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
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
    val statusStrip: @Composable () -> Unit = {
        PeerStatusStrip(
            peer = peer,
            path = path,
            ping = ping,
            strings = strings,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }

    // The actions left up here: the ping moved into the connection card, which is the
    // thing it measures. Send file stays a button because it leaves the app. When the
    // daemon says the peer will not take a file the button is dead and the daemon's
    // reason stands beside it in the user's language — for a peer of another user's
    // that reason is an ACL grant, and the words say which one. A peer the control
    // plane calls offline keeps a live button under the same kind of note: the Online
    // bit lags, and a peer that woke a moment ago would otherwise show a dead button.
    // Copy as… shares the row: it opens a picker over the sheet, which is no place for
    // a card of its own, and the cards' copy icons copy one value each, not a command.
    val actions: @Composable () -> Unit = {
        // The reason is a tooltip, not a standing line: it costs no height in the
        // sheet, and it appears at the moment a person presses the button and
        // wonders why nothing happened. A blocked peer keeps a visible but dimmed
        // button for the same reason — a control that vanishes teaches nothing.
        val taildropNote = when (taildrop) {
            TaildropStatus.Available -> null
            is TaildropStatus.Offline -> taildrop.reason
            is TaildropStatus.Blocked -> taildrop.reason
        }
        val refusedHere = taildrop is TaildropStatus.Blocked
        val tooltipState = rememberTooltipState(isPersistent = true)
        val scope = rememberCoroutineScope()
        // A tooltip is a popup no key reaches, and a persistent one stays until its Close is
        // pressed: on a television the reason is a dialog, which the remote can close.
        val reasonInDialog = isTv
        var reasonShown by remember { mutableStateOf(false) }
        // Each button takes its place from the layout below: side by side, or stacked.
        // The Box is where that place lands — TooltipBox hands its own modifier to the
        // anchor inside it, where a Row never sees a weight.
        val sendButton: @Composable (Modifier) -> Unit = { place ->
            Box(place) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = {
                        if (taildropNote != null) {
                            RichTooltip(
                                title = { Text(strings.sendFile) },
                                action = {
                                    TextButton(onClick = { scope.launch { tooltipState.dismiss() } }) {
                                        Text(strings.tooltipDismiss)
                                    }
                                }
                            ) { Text(taildropNote) }
                        }
                    },
                    state = tooltipState,
                    enableUserInput = false
                ) {
                    Button(
                        onClick = {
                            if (refusedHere) {
                                if (reasonInDialog) reasonShown = true else scope.launch { tooltipState.show() }
                            } else onSendFileClick()
                        },
                        // Enabled even when refused: a disabled button swallows the tap,
                        // and the tap is what the explanation hangs on. The dimmed
                        // colours say it will not send.
                        colors = if (refusedHere) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else ButtonDefaults.buttonColors(),
                        // heightIn rather than height: «Отправить файл» wraps at a large font
                        // scale, and a fixed height cuts the second line off.
                        modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 46.dp)
                            .semantics { if (taildropNote != null) stateDescription = taildropNote },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            if (refusedHere) Icons.Default.Info else Icons.AutoMirrored.Filled.Send,
                            null,
                            Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        PeerActionLabel(strings.sendFile)
                    }
                }
                if (reasonShown && taildropNote != null) {
                    AppAlertDialog(
                        onDismissRequest = { reasonShown = false },
                        title = { Text(strings.sendFile) },
                        text = { Text(taildropNote) },
                        confirmButton = {
                            TextButton(onClick = { reasonShown = false }, modifier = Modifier.tvInitialFocus()) {
                                Text(strings.tooltipDismiss)
                            }
                        }
                    )
                }
            }
        }
        val copyAsButton: (@Composable (Modifier) -> Unit)? = onCopyAsClick?.let { onClick ->
            { place ->
                FilledTonalButton(
                    onClick = onClick,
                    modifier = place.heightIn(min = 46.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(if (Feature.CLIPBOARD_ACTIONS.offered()) Icons.Default.ContentCopy else Icons.Default.QrCode2, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    PeerActionLabel(strings.copyAs)
                }
            }
        }
        val actionsArea = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)
        Column(Modifier.fillMaxWidth()) {
            // Stacked once half the sheet no longer holds one word of a label: side by side,
            // a 2× font broke «Отправить» and «Копировать» in the middle.
            if (copyAsButton != null && LocalDensity.current.fontScale > PEER_ACTIONS_SIDE_BY_SIDE_MAX_FONT_SCALE) {
                Column(actionsArea, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sendButton(Modifier.fillMaxWidth())
                    copyAsButton(Modifier.fillMaxWidth())
                }
            } else {
                // Intrinsic height, so the two stand equally tall when one label wraps.
                Row(actionsArea.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sendButton(Modifier.weight(1f).fillMaxHeight())
                    copyAsButton?.invoke(Modifier.weight(1f).fillMaxHeight())
                }
            }
            // A row of its own under them, and no row at all for a peer that does not offer it.
            if (manage != null) {
                RemoteManageButton(manage, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp))
            }
        }
    }

    val connection: @Composable (Modifier) -> Unit = { place ->
        PeerConnectionCard(
            page = page,
            selfAddress = selfAddress,
            ping = ping,
            strings = strings,
            onPing = onPing,
            modifier = place
        )
    }
    val infoCard: @Composable (PeerCard, Modifier) -> Unit = { card, place ->
        PeerInfoCard(
            card = card,
            peer = peer,
            strings = strings,
            expanded = card.group in expanded,
            copied = copied,
            onToggle = { onToggleGroup(card.group) },
            onCopyDetail = onCopyDetail,
            modifier = place
        )
    }

    // Everything scrolls together, header included. The header is some 300dp of identity
    // row, status strip, button and connection card, and the sheet is capped at 85% of the
    // screen: in landscape, or in portrait at a large font scale, a fixed header over a
    // weighted card column left the column measured into nothing at all, and the cards it
    // could not fit were clipped with no way to scroll to them.
    if (columns <= 1) {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
        ) {
            item(key = "identity") { identity() }
            item(key = "status-strip") { statusStrip() }
            item(key = "actions") { actions() }
            item(key = "connection") { connection(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
            // One item per card, keyed on the group: a card's open/closed reveal is its own
            // business and must not re-lay the cards around it.
            items(cards, key = { it.group.name }) { card ->
                infoCard(card, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    } else {
        // Columns of their own, not a staggered grid: a grid put each card where the columns were
        // shortest, so a card that opened re-dealt the others, and they changed column under the
        // eye (and under the remote). Here a card's column is its place in the list
        // (peerCardColumn); one opening pushes down only the cards under it.
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp)
        ) {
            item(key = "identity") { identity() }
            item(key = "status-strip") { statusStrip() }
            item(key = "actions") { actions() }
            item(key = "connection") {
                connection(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
            }
            item(key = "cards") {
                Row(Modifier.fillMaxWidth()) {
                    dealPeerCards(cards, columns).forEach { column ->
                        Column(Modifier.weight(1f)) {
                            column.forEach { card ->
                                key(card.group) {
                                    infoCard(card, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A label of the action row. Two lines before it is cut: at half the sheet's width each,
 *  «Отправить файл» and «Копировать как…» wrap from a large font scale on. */
@Composable
private fun PeerActionLabel(text: String) {
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
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
    path: PeerPath,
    ping: PeerPingState,
    strings: PeerDetailsStrings,
    modifier: Modifier = Modifier
) {
    val (osIcon, osColor) = getOsVisuals(peer.iconOs())
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
        // Beside the OS it qualifies: an Android phone that runs TailSocks, or a "linux"
        // that is one in the masquerade. The official client gets no chip; its name is in
        // the Device card's Client row.
        if (peer.isTailSocks) {
            PeerStatusChip(
                label = strings.clientTailSocks,
                icon = ImageVector.vectorResource(R.drawable.ic_qs_tile),
                iconTint = MaterialTheme.colorScheme.primary
            )
        }
        val route = strings.path.chip(path)
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
