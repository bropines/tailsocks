package io.github.bropines.tailscaled.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ConnectionHistory
import io.github.bropines.tailscaled.core.ConnectionKind
import io.github.bropines.tailscaled.core.ConnectionLog
import io.github.bropines.tailscaled.core.ConnectionSegment
import io.github.bropines.tailscaled.core.ConnectionSnapshot
import io.github.bropines.tailscaled.core.ConnectionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

/*
 * The connection's last hours as an uptime bar: coloured stretches along a time axis, a window
 * of 1, 6 or 24 hours, one line that sums the window up and one that says what the selected
 * stretch was. The history itself and every number shown come from core/ConnectionHistory.kt.
 */

private const val HOUR_MS = 60 * 60 * 1000L

/** The windows offered, in hours. */
private val WINDOWS = intArrayOf(1, 6, 24)
private const val DEFAULT_WINDOW = 6

/** The last segment grows by itself; at 24 hours half a minute is a fraction of a pixel. */
private const val REFRESH_MS = 30_000L

private val BarHeight = 24.dp
private val BarShape @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.small

/** An interruption of seconds in a day is far thinner than a pixel; it is drawn this wide so it shows at all. */
private val MinMark = 2.dp

/** How far beside a stretch too thin to hit a tap still counts as on it. */
private val TapSlop = 12.dp

/**
 * The connection timeline, for the main screen. Needs nothing from its caller: it reads the
 * history itself, again whenever the service records an event, and every [REFRESH_MS] while
 * the screen is at least started — the open stretch grows with the clock, and a screen nobody
 * sees has no reason to redraw it.
 */
@Composable
fun ConnectionTimelineCard(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        // The renderer has no service and no files; previews call ConnectionTimelineContent with a made-up history.
        ConnectionTimelineContent(snapshot = ConnectionSnapshot(emptyList(), 0L), now = 0L, modifier = modifier)
        return
    }
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val changes by ConnectionHistory.changes.collectAsState()
    var read by remember { mutableStateOf<Pair<ConnectionSnapshot, Long>?>(null) }
    LaunchedEffect(lifecycle, changes) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // The snapshot and the clock it was taken at go into the state together: an event
                // newer than the window's right edge would be cut off the bar.
                val now = System.currentTimeMillis()
                read = withContext(Dispatchers.IO) { ConnectionHistory.snapshot(context, now) } to now
                delay(REFRESH_MS)
            }
        }
    }
    ConnectionTimelineContent(snapshot = read?.first, now = read?.second ?: 0L, modifier = modifier)
}

/**
 * The card itself, history in. [snapshot] is null until the first read, which draws the card at
 * its full size with an empty bar rather than have the dashboard jump when the read comes back.
 * [initialHours] and [initialSelection] (a moment inside the stretch to select) are for previews.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionTimelineContent(
    snapshot: ConnectionSnapshot?,
    now: Long,
    modifier: Modifier = Modifier,
    initialHours: Int = DEFAULT_WINDOW,
    initialSelection: Long? = null,
    barFocused: Boolean = false,
) {
    var hours by rememberSaveable { mutableIntStateOf(initialHours) }
    // The selection is a moment, not an index: the window slides and stretches leave it on the
    // left, so an index would come to mean another stretch.
    var selectedAt by rememberSaveable { mutableStateOf(initialSelection) }
    val from = now - hours * HOUR_MS
    val timeline = remember(snapshot, now, hours) {
        snapshot?.let { ConnectionLog.timeline(it.events, from, now, it.aliveUntil) }
    }
    val segments = timeline?.segments.orEmpty()
    val selected = selectedAt?.let { at -> segments.indexOfFirst { at >= it.start && at < it.end } } ?: -1
    val select: (Int) -> Unit = { index ->
        selectedAt = segments.getOrNull(index)?.takeIf { index != selected }?.let { it.start + it.durationMs / 2 }
    }

    val title = stringResource(R.string.connection_timeline_title)
    val summary = timeline?.summary?.let { summaryText(it) }.orEmpty()
    val detail = segments.getOrNull(selected)?.let { kindLabel(it.kind) to spanText(it) }

    // The dashboard's card look (MainDashboard's peers and live-state cards).
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            // The title beside the windows where both fit; on a narrow screen or at a large font the
            // windows go to a line of their own rather than squeeze the title to its first letters.
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp).semantics { heading() }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.tvFocusGroup()) {
                    for (h in WINDOWS) {
                        FilterChip(
                            selected = h == hours,
                            onClick = { hours = h },
                            label = { Text(stringResource(R.string.connection_timeline_hours, h), maxLines = 1) },
                        )
                    }
                }
            }
            TimelineBar(
                segments = segments,
                from = from,
                to = now,
                selected = selected,
                onSelect = select,
                description = if (summary.isEmpty()) title else "$title. $summary",
                state = detail?.let { "${it.first}, ${it.second}" }.orEmpty(),
                forceFocused = barFocused,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                val axis = MaterialTheme.typography.labelSmall
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                Text(if (timeline == null) "" else clockText(from), style = axis, color = tint, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.connection_timeline_now), style = axis, color = tint)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(2.dp))
            DetailLine(segments.getOrNull(selected)?.kind, detail, hasBar = timeline?.summary?.hasData == true)
        }
    }
}

/** What the selected stretch was and when, behind a swatch drawn as the bar draws it; a hint while nothing is selected. */
@Composable
private fun DetailLine(kind: ConnectionKind?, detail: Pair<String, String>?, hasBar: Boolean) {
    val palette = timelinePalette()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        if (kind != null && detail != null) {
            Canvas(Modifier.size(12.dp).clip(MaterialTheme.shapes.extraSmall).background(palette.track)) {
                drawKind(kind, 0f, size.width, palette)
            }
            Spacer(Modifier.width(8.dp))
            val strong = MaterialTheme.colorScheme.onSurface
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = strong)) { append(detail.first) }
                    append(" · ")
                    append(detail.second)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // The line keeps its place, so selecting a stretch does not move the card's bottom edge.
            Text(
                if (hasBar) stringResource(R.string.connection_timeline_hint) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The bar. A tap selects the stretch under it (the same one again clears the selection). It is
 * one stop for a remote or a keyboard: focus selects the newest stretch, left and right step
 * through them, and past either end the key is let go so focus can leave the bar.
 */
@Composable
private fun TimelineBar(
    segments: List<ConnectionSegment>,
    from: Long,
    to: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
    description: String,
    state: String,
    forceFocused: Boolean,
) {
    val palette = timelinePalette()
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = BarShape
    Box(
        Modifier
            .fillMaxWidth()
            // The ring is drawn inside the bounds (ui/Focus.kt), so the bar keeps 4dp clear of them.
            .focusRing(focused || forceFocused, shape)
            .onFocusChanged { if (it.isFocused && selected < 0 && segments.isNotEmpty()) onSelect(segments.lastIndex) }
            .onKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onKeyEvent false
                }
                val target = if (selected < 0) segments.lastIndex else selected + step
                if (target !in segments.indices) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) onSelect(target)
                true
            }
            .focusable(interactionSource = source)
            .semantics {
                contentDescription = description
                if (state.isNotEmpty()) stateDescription = state
            }
            .pointerInput(segments, from, to, selected) {
                detectTapGestures { tap ->
                    val pad = 4.dp.toPx()
                    val hit = segmentAt(segments, from, to, size.width - 2 * pad, tap.x - pad, TapSlop.toPx())
                    if (hit >= 0) onSelect(hit)
                }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().height(BarHeight).clip(shape).background(palette.track)) {
            if (to <= from) return@Canvas
            val min = MinMark.toPx()
            fun x(t: Long) = (t - from).toFloat() / (to - from) * size.width
            // The long, calm kinds first; then what interrupted them, never thinner than a mark.
            for (s in segments) if (!s.kind.isMark) drawKind(s.kind, x(s.start), x(s.end), palette)
            for (s in segments) if (s.kind.isMark) {
                val (x0, x1) = widened(x(s.start), x(s.end), min, size.width)
                drawKind(s.kind, x0, x1, palette)
            }
            segments.getOrNull(selected)?.let { s ->
                val (x0, x1) = widened(x(s.start), x(s.end), 3 * min, size.width)
                // Two lines, as the focus ring has: one of them shows on any colour under it.
                val line = 2.dp.toPx()
                drawRect(palette.frameInner, Offset(x0 + line * 1.5f, line * 1.5f), Size((x1 - x0 - 3 * line).coerceAtLeast(0f), size.height - 3 * line), style = Stroke(line))
                drawRect(palette.frame, Offset(x0 + line / 2, line / 2), Size(x1 - x0 - line, size.height - line), style = Stroke(line))
            }
        }
    }
}

/** Trouble of any sort is a mark on the bar: short, and the reason to look. Connected, stopped and no-data are its ground. */
private val ConnectionKind.isMark: Boolean
    get() = this != ConnectionKind.CONNECTED && this != ConnectionKind.STOPPED && this != ConnectionKind.NO_DATA

/** [x0]…[x1] made at least [min] wide about its middle, inside 0…[width]. */
private fun widened(x0: Float, x1: Float, min: Float, width: Float): Pair<Float, Float> {
    if (x1 - x0 >= min) return x0 to x1
    val start = ((x0 + x1 - min) / 2).coerceIn(0f, (width - min).coerceAtLeast(0f))
    return start to minOf(start + min, width)
}

/**
 * The stretch a tap at [x] of a bar [width] wide means, −1 for none. The one under the finger —
 * unless that one is wide and right beside it lies one too thin to hit, which is then what the
 * tap was for: a half-minute outage in a day is a mark two pixels wide.
 */
internal fun segmentAt(segments: List<ConnectionSegment>, from: Long, to: Long, width: Float, x: Float, slop: Float): Int {
    if (segments.isEmpty() || width <= 0f || to <= from) return -1
    fun px(t: Long) = (t - from).toFloat() / (to - from) * width
    val at = from + ((x / width).coerceIn(0f, 1f) * (to - from)).toLong()
    val exact = segments.indexOfFirst { at < it.end }.let { if (it < 0) segments.lastIndex else it }
    fun widthOf(i: Int) = px(segments[i].end) - px(segments[i].start)
    if (widthOf(exact) < 2 * slop) return exact
    var best = exact
    var bestWidth = Float.MAX_VALUE
    for (i in intArrayOf(exact - 1, exact + 1)) {
        val s = segments.getOrNull(i) ?: continue
        val w = widthOf(i)
        val distance = maxOf(px(s.start) - x, x - px(s.end))
        if (w < 2 * slop && distance <= slop && w < bestWidth) {
            best = i
            bestWidth = w
        }
    }
    return best
}

// -------------------------------------------------------------------------------------------
// How a kind looks. Colour is never the only sign: a stop is a thin line, a wait on something
// outside the app is hatched, and the detail line names whatever is selected.

private class TimelinePalette(
    val track: Color,
    val connected: Color,
    val recovering: Color,
    val trouble: Color,
    val waiting: Color,
    val stopped: Color,
    val frame: Color,
    val frameInner: Color,
)

@Composable
@ReadOnlyComposable
private fun timelinePalette(): TimelinePalette {
    val c = MaterialTheme.colorScheme
    return TimelinePalette(
        track = c.surfaceContainerHighest,
        connected = c.primary,
        recovering = c.tertiary,
        trouble = c.error,
        waiting = c.onSurfaceVariant,
        stopped = c.outline,
        frame = c.onSurface,
        frameInner = c.surface,
    )
}

private fun DrawScope.drawKind(kind: ConnectionKind, x0: Float, x1: Float, palette: TimelinePalette) {
    if (x1 <= x0) return
    val area = Size(x1 - x0, size.height)
    when (kind) {
        ConnectionKind.CONNECTED -> drawRect(palette.connected, Offset(x0, 0f), area)
        ConnectionKind.RECOVERING -> drawRect(palette.recovering, Offset(x0, 0f), area)
        ConnectionKind.TROUBLE -> drawRect(palette.trouble, Offset(x0, 0f), area)
        ConnectionKind.WAITING_FOR_NETWORK -> drawHatched(palette.waiting, x0, x1)
        ConnectionKind.NEEDS_YOU -> drawHatched(palette.trouble, x0, x1)
        ConnectionKind.STOPPED -> {
            val line = size.height / 4
            drawRect(palette.stopped, Offset(x0, (size.height - line) / 2), Size(x1 - x0, line))
        }
        ConnectionKind.NO_DATA -> Unit
    }
}

/** A light wash of [color] under diagonal lines of it. */
private fun DrawScope.drawHatched(color: Color, x0: Float, x1: Float) {
    drawRect(color.copy(alpha = 0.28f), Offset(x0, 0f), Size(x1 - x0, size.height))
    val step = 5.dp.toPx()
    val stroke = 1.5.dp.toPx()
    clipRect(left = x0, top = 0f, right = x1, bottom = size.height) {
        // Anchored to the bar, not to the stretch, so the lines stand still while the window slides under them.
        var x = (x0 / step).toInt() * step - size.height
        while (x < x1) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), stroke)
            x += step
        }
    }
}

// -------------------------------------------------------------------------------------------
// Words.

@Composable
private fun kindLabel(kind: ConnectionKind): String = stringResource(
    when (kind) {
        ConnectionKind.CONNECTED -> R.string.notif_connected
        ConnectionKind.RECOVERING -> R.string.connection_timeline_kind_recovering
        ConnectionKind.TROUBLE -> R.string.main_status_degraded
        ConnectionKind.WAITING_FOR_NETWORK -> R.string.connection_timeline_kind_waiting_network
        ConnectionKind.NEEDS_YOU -> R.string.connection_timeline_kind_needs_you
        ConnectionKind.STOPPED -> R.string.status_stopped
        ConnectionKind.NO_DATA -> R.string.connection_timeline_kind_no_data
    }
)

/** The time of day in the user's own 12- or 24-hour form. */
@Composable
private fun clockText(at: Long): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return format.format(Date(at))
}

@Composable
private fun durationText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.connection_timeline_duration_under_minute)
        minutes < 60 -> stringResource(R.string.connection_timeline_duration_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.connection_timeline_hours, minutes / 60)
        else -> stringResource(R.string.connection_timeline_duration_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** When a stretch was. One that began before the bar does has no start to name, and so no length either. */
@Composable
private fun spanText(segment: ConnectionSegment): String = when {
    segment.openStart && segment.openEnd -> stringResource(R.string.connection_timeline_span_whole)
    segment.openStart -> stringResource(R.string.connection_timeline_span_until, clockText(segment.end))
    segment.openEnd -> stringResource(R.string.connection_timeline_span_since, clockText(segment.start), durationText(segment.durationMs))
    else -> stringResource(
        R.string.connection_timeline_span_closed,
        clockText(segment.start), clockText(segment.end), durationText(segment.durationMs)
    )
}

@Composable
private fun summaryText(summary: ConnectionSummary): String {
    val fraction = summary.connectedFraction
    return when {
        !summary.hasData -> stringResource(R.string.connection_timeline_summary_empty)
        fraction == null -> stringResource(R.string.connection_timeline_summary_off)
        else -> stringResource(
            R.string.connection_timeline_summary,
            percentShown(fraction),
            if (summary.interruptions == 0) stringResource(R.string.connection_timeline_no_interruptions)
            else pluralStringResource(R.plurals.connection_timeline_interruptions, summary.interruptions, summary.interruptions)
        )
    }
}

/** A whole percent that does not round a blemish away: 100 only when it was all of the time, 0 only when none. */
internal fun percentShown(fraction: Float): Int = when {
    fraction >= 1f -> 100
    fraction <= 0f -> 0
    else -> (fraction * 100).roundToInt().coerceIn(1, 99)
}
