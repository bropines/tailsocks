package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ExitNodeSuggestion
import io.github.bropines.tailscaled.models.PeerData

/*
 * What the exit-node sheet of the main screen shows: the node Tailscale recommends, "None",
 * and the nodes that offer themselves, each with its latency. The sheet, the pings and the
 * choice itself stay with MainScreen; this is the list, apart from the sheet so that a
 * preview can draw it — a sheet is a window the renderer does not have.
 *
 * With a remote: the list opens on the current choice, a row is one stop (the latency chip
 * is left to "Ping all", which measures every node), and OK chooses.
 */

/** The picker's words, resolved by the screen: the sheet is a window of its own — see wrapContextWithLocale(). */
internal data class ExitNodePickerStrings(
    val title: String,
    val pingAll: String,
    val noNodes: String,
    val none: String,
    val direct: String,
    val fastest: String,
    val ping: ExitNodePingStrings,
    val best: BestExitNodeStrings,
)

@Composable
internal fun exitNodePickerStrings() = ExitNodePickerStrings(
    title = stringResource(R.string.main_select_exit_node),
    pingAll = stringResource(R.string.action_ping_all),
    noNodes = stringResource(R.string.main_no_exit_nodes),
    none = stringResource(R.string.main_exit_node_none),
    direct = stringResource(R.string.main_route_traffic_directly),
    fastest = stringResource(R.string.exit_node_fastest),
    ping = ExitNodePingStrings(
        tapToMeasure = stringResource(R.string.peer_conn_tap_to_measure),
        pinging = stringResource(R.string.peer_pinging),
        resultFormat = stringResource(R.string.peer_ping_result),
        failed = stringResource(R.string.peer_conn_ping_failed)
    ),
    best = bestExitNodeStrings(),
)

/**
 * The list of the exit-node sheet. [nodes] are the peers that offer an exit; [loading] is the
 * list still on its way. [selectedIp] is the node in use, empty for none. [pings] holds the
 * daemon's raw answer per address, [best] the suggestion (null while it is asked).
 * [onChoose] with two empty strings clears the exit node.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExitNodePicker(
    nodes: List<PeerData>,
    loading: Boolean,
    selectedIp: String,
    pings: Map<String, String>,
    pingingAll: Boolean,
    best: ExitNodeSuggestion.Outcome?,
    strings: ExitNodePickerStrings,
    onPingAll: () -> Unit,
    onPing: (ip: String) -> Unit,
    onRetryBest: () -> Unit,
    onChoose: (id: String, ip: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = isTv
    // The quickest of the measured ones. Only once two have answered: "fastest" out of
    // one is not a comparison.
    val fastestIp = remember(pings.toMap()) {
        val measured = pings.mapNotNull { (ip, raw) ->
            val state = pingStateOf(raw)
            if (state is PeerPingState.Measured) {
                state.latency.takeWhile { it.isDigit() }.toIntOrNull()?.let { ip to it }
            } else null
        }
        if (measured.size > 1) measured.minByOrNull { it.second }?.first else null
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                strings.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (nodes.isNotEmpty()) {
                TextButton(
                    onClick = { if (!pingingAll) onPingAll() },
                    // A television keeps it a stop while the pings are out: a button that
                    // turns itself off under the remote leaves it nowhere.
                    enabled = !pingingAll || tv
                ) {
                    Icon(Icons.Default.NetworkPing, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.pingAll)
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { LoadingIndicator() }
        } else if (nodes.isEmpty()) {
            // An exit node can be set while the list is empty — the peers
            // have not loaded, or the node stopped offering one. Without a
            // way out of that the traffic stays routed through a node the
            // picker cannot even show, so the clear action is offered here
            // too, not only alongside the list.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(strings.noNodes, color = MaterialTheme.colorScheme.outline)
                if (selectedIp.isNotEmpty()) {
                    Button(
                        onClick = { onChoose("", "") },
                        modifier = Modifier.fillMaxWidth().tvInitialFocus()
                    ) {
                        Text(strings.none)
                    }
                }
            }
        } else {
            // On a television the list opens on the node in use: from the top while that
            // row is on the first screen, and scrolled to it when it is further down.
            val selectedRow = nodes.indexOfFirst { it.getPrimaryIp() == selectedIp } + LEADING_ROWS
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = if (tv && selectedRow >= FIRST_SCREEN_ROWS) selectedRow else 0
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        item {
                            BestExitNodeRow(
                                outcome = best,
                                strings = strings.best,
                                onApply = { onChoose(it.id, it.ip) },
                                onRetry = onRetryBest
                            )
                        }

                        item {
                            val isSelected = selectedIp.isEmpty()
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .then(if (isSelected) Modifier.tvInitialFocus() else Modifier)
                                    .shapedClickable(MaterialTheme.shapes.medium) { onChoose("", "") },
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            strings.none,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            strings.direct,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }

                        items(nodes) { node ->
                            val nodeIp = node.getPrimaryIp()
                            val isSelected = if (selectedIp.isNotEmpty()) nodeIp == selectedIp else false
                            // Parsed once per answer, not once per recomposition.
                            val pingRaw = pings[nodeIp]
                            val ping = remember(pingRaw) { pingStateOf(pingRaw) }
                            val (osIcon, osColor) = getOsVisuals(node.os).let { (icon, color) ->
                                if (icon == Icons.Default.Devices) Icons.Default.VpnKey to MaterialTheme.colorScheme.primary
                                else icon to color
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .then(if (isSelected) Modifier.tvInitialFocus() else Modifier)
                                    .shapedClickable(MaterialTheme.shapes.medium) { onChoose(node.id ?: "", node.getPrimaryIp()) },
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else osColor.copy(alpha = 0.12f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            osIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else osColor
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                node.getDisplayName(),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                // A name too long for the row travels
                                                // across it instead of wrapping onto a
                                                // second line and making the row taller.
                                                modifier = Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (nodeIp == fastestIp) {
                                                Spacer(Modifier.width(6.dp))
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = strings.fastest,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            if (node.id != null && node.id == best.suggestedId) {
                                                Spacer(Modifier.width(6.dp))
                                                SuggestedExitNodeMark(strings.best.suggestedBy)
                                            }
                                        }
                                        val latency = when (ping) {
                                            is PeerPingState.Measured -> " · ${ping.latency}"
                                            PeerPingState.InFlight -> " · …"
                                            PeerPingState.Failed -> " · —"
                                            PeerPingState.Idle -> ""
                                        }
                                        Text(
                                            nodeIp + latency,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (ping is PeerPingState.Measured) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    // Its own click target: measuring a node and
                                    // choosing it are two different taps, and the
                                    // row stays choosable while a ping is in flight.
                                    // Not a stop for a remote, which has "Ping all".
                                    ExitNodePingChip(
                                        ping = ping,
                                        strings = strings.ping,
                                        onPing = { onPing(nodeIp) },
                                        modifier = Modifier.tvNoFocus()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The rows above the nodes: the suggestion and "None". */
private const val LEADING_ROWS = 2

/** How many rows a television's sheet shows without scrolling. */
private const val FIRST_SCREEN_ROWS = 4

// ---------------------------------------------------------------------------------------------
// Exit-node latency chips. The measurement and its parsing are the peer sheet's own —
// pingPeer() and pingStateOf() in UIComponents.kt — so the two cannot read a pong differently.

/** Strings the chip shows or announces, resolved by the parent — see wrapContextWithLocale(). */
internal data class ExitNodePingStrings(
    val tapToMeasure: String,
    val pinging: String,
    val resultFormat: String,
    val failed: String
)

/**
 * The trailing chip of an exit-node row: the ping icon before anything is measured, the
 * indicator while the round trip is out, then the figure — or a dash when it did not come
 * back. Same colours as the peer sheet's connection card: a measured figure earns the
 * secondary container, everything before it sits a step above the row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExitNodePingChip(
    ping: PeerPingState,
    strings: ExitNodePingStrings,
    onPing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val measured = ping as? PeerPingState.Measured
    val inFlight = ping == PeerPingState.InFlight
    val failed = ping == PeerPingState.Failed
    val description = when {
        measured != null -> strings.resultFormat.format(measured.latency)
        inFlight -> strings.pinging
        failed -> strings.failed
        else -> strings.tapToMeasure
    }
    val container = when {
        measured != null -> MaterialTheme.colorScheme.secondaryContainer
        failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        measured != null -> MaterialTheme.colorScheme.onSecondaryContainer
        failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onPing,
        enabled = !inFlight,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier
            .heightIn(min = 32.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            if (inFlight) {
                LoadingIndicator(modifier = Modifier.size(18.dp), color = content)
            } else {
                Icon(
                    Icons.Default.NetworkPing,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = content
                )
            }
            // The figure itself goes on the address line of the row, not in here: a chip
            // that grows when the answer lands takes the width from the name beside it,
            // wraps it onto a second line and makes the row — and the sheet — taller.
        }
    }
}
