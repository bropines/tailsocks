package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.PeerTrafficSampler
import io.github.bropines.tailscaled.core.TrafficPoint
import io.github.bropines.tailscaled.core.graphCeiling
import io.github.bropines.tailscaled.core.topTalkers

/*
 * The speed graph of the main screen: the tailnet's download and upload rate over the last
 * minute as two curves, the two rates now in figures above them, and beside them who is using
 * the link — the three peers with the most traffic this moment.
 *
 * Its height never changes — with traffic or without, connected or not — because it stands in
 * a dashboard that does not scroll: a card that grew a row whenever a third peer woke up would
 * push the grid under it around. Received is the theme's primary and filled; sent is its
 * tertiary and a bare line, so the two are told apart by more than their colour.
 */

/** How many peers "who is using it" names. */
private const val TALKERS = 3

/**
 * The height of the graph and of the list beside it at the default font size: three lines of
 * the list. It grows with the reader's font scale — fixed for any one reader, which is what
 * the dashboard needs, without cutting the third name off for one who reads large.
 */
private const val BODY_HEIGHT_DP = 60f

/**
 * The card, fed by the shared poller ([rememberLiveTraffic]): it reads the daemon only while
 * it is on screen and needs nothing from its caller. [onClick] makes the whole card a button —
 * the natural way into the tailnet map.
 */
@Composable
fun SpeedGraphCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    SpeedGraphCard(rememberLiveTraffic(), modifier, onClick)
}

/** Draws [state]; a preview hands it any. */
@Composable
fun SpeedGraphCard(state: LiveTrafficState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val formatRate = rememberRateFormatter()
    val colors = MaterialTheme.colorScheme
    val traffic = state.traffic
    val live = state.running && traffic.measured
    val bodyHeight = (BODY_HEIGHT_DP * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val spoken = if (state.running) {
        stringResource(R.string.speed_graph_spoken_format, formatRate(traffic.total.rxBps), formatRate(traffic.total.txBps))
    } else {
        stringResource(R.string.speed_graph_title) + ". " + stringResource(R.string.speed_graph_stopped)
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // One sentence for a screen reader instead of two arrows and a list of fragments.
            Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.speed_graph_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                RateFigure(Icons.Default.ArrowDownward, if (live) formatRate(traffic.total.rxBps) else "—", colors.primary)
                Spacer(Modifier.width(12.dp))
                RateFigure(Icons.Default.ArrowUpward, if (live) formatRate(traffic.total.txBps) else "—", colors.tertiary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(bodyHeight), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedCurves(
                    history = if (state.running) traffic.history else emptyList(),
                    nowMs = traffic.atMs,
                    modifier = Modifier.weight(1.15f).fillMaxHeight()
                )
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    val talkers = if (live) traffic.topTalkers(state.peers, TALKERS) else emptyList()
                    if (talkers.isEmpty()) {
                        Text(
                            stringResource(if (state.running) R.string.speed_graph_idle else R.string.speed_graph_stopped),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            for ((peer, rate) in talkers) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        peer.getDisplayName(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        formatRate(rate.totalBps),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Surface(onClick) rather than a clickable modifier: its ripple and a television's focus
    // ring follow the rounded corners (see MenuCard).
    if (onClick != null) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = colors.surfaceContainer, modifier = modifier, content = content)
    } else {
        Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer, modifier = modifier, content = content)
    }
}

@Composable
private fun RateFigure(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * The two curves over the last minute, the newest point at the right edge. The points stand
 * where their time puts them, not one per step, so a slow read does not squeeze the curve; a
 * pause in the readings — the screen was away — is a break in the line, not a slope across
 * it. An empty [history] is the baseline alone.
 */
@Composable
private fun SpeedCurves(history: List<TrafficPoint>, nowMs: Long, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val down = colors.primary
    val up = colors.tertiary
    val grid = colors.outlineVariant
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        // Half a stroke of room, so a curve at the ceiling or on the floor is not cut in two.
        val top = stroke
        val bottom = size.height - stroke / 2
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        drawLine(grid, Offset(0f, top), Offset(size.width, top), 1.dp.toPx(), pathEffect = dash)
        drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        if (history.isEmpty()) return@Canvas

        val ceiling = graphCeiling(history.maxOf { maxOf(it.rxBps, it.txBps) }).toFloat()
        fun x(p: TrafficPoint) = size.width * (1f - (nowMs - p.atMs).toFloat() / PeerTrafficSampler.HISTORY_MS)
        fun y(bps: Long) = bottom - (bottom - top) * (bps / ceiling).coerceIn(0f, 1f)

        // The runs of points with no pause inside them.
        val runs = ArrayList<List<TrafficPoint>>()
        var start = 0
        for (i in 1..history.size) {
            if (i == history.size || history[i].atMs - history[i - 1].atMs > PeerTrafficSampler.MAX_GAP_MS) {
                runs += history.subList(start, i)
                start = i
            }
        }
        for (run in runs) {
            drawCurve(run, ::x, { y(it.rxBps) }, down, stroke, fillTo = bottom)
            drawCurve(run, ::x, { y(it.txBps) }, up, stroke, fillTo = null)
        }
    }
}

/** One run of one curve; filled down to [fillTo] when that is given. A single point is a dot. */
private fun DrawScope.drawCurve(
    run: List<TrafficPoint>,
    x: (TrafficPoint) -> Float,
    y: (TrafficPoint) -> Float,
    color: Color,
    stroke: Float,
    fillTo: Float?,
) {
    if (run.size == 1) {
        drawCircle(color, stroke, Offset(x(run[0]), y(run[0])))
        return
    }
    val line = Path()
    run.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p), y(p)) else line.lineTo(x(p), y(p)) }
    if (fillTo != null) {
        val area = Path().apply {
            addPath(line)
            lineTo(x(run.last()), fillTo)
            lineTo(x(run.first()), fillTo)
            close()
        }
        drawPath(area, color.copy(alpha = 0.16f))
    }
    drawPath(line, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
