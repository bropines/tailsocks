package io.github.bropines.tailscaled.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.Diagnostics
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.models.parseHealthWarnings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The live state card at the top of Settings → Diagnostics: what the service is
 * doing this second, one monospace row per fact, red where it is wrong. It is
 * for the developer and for bug reports, so it lives on a page nobody opens by
 * accident — the everyday screens keep their plain language.
 *
 * Split in two, like the rest of the previewed UI: a loader that owns the
 * polling, and a stateless card that draws a Diagnostics.Live, so a preview can
 * hand it any state without a daemon.
 */

private const val REFRESH_MS = 2_000L

/**
 * The error lines come from the whole log buffer, marshalled over the bridge in
 * one piece; every fifth refresh is fresh enough for lines that are only read,
 * and keeps a long session's 10 000 entries from being parsed every 2 s.
 */
private const val LOG_EVERY = 5

/** Wide enough for the longest label, "Намерение", at the default font scale. */
private val LABEL_WIDTH = 84.dp

/**
 * Reads the live state every [REFRESH_MS] while the card is composed and the
 * page is in front, and draws it. In the preview renderer, which has no native
 * bridge, it draws [LocalDemo] instead and never touches Appctr.
 */
@Composable
fun LiveDiagnosticsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val demo = LocalDemo.current
    val offline = inPreview || demo != null
    var state by remember { mutableStateOf(if (offline) demoLiveState(demo) else null) }
    // A tap on SOCKS5 wakes the loop at once instead of waiting out the 2 s, so the
    // loop stays the only writer of [state] and a slow read cannot land on a newer one.
    val recheck = remember { Channel<Unit>(Channel.CONFLATED) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (!offline) {
        LaunchedEffect(lifecycle) {
            // STARTED, not just composed: a Settings page left open under another app
            // would otherwise keep calling into the daemon for nobody.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var tick = 0
                var probe = false
                while (true) {
                    val previous = state
                    val readLogs = probe || tick % LOG_EVERY == 0
                    state = withContext(Dispatchers.IO) { Diagnostics.live(context, previous, readLogs, probe) }
                    tick++
                    probe = withTimeoutOrNull(REFRESH_MS) { recheck.receive() } != null
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.diag_live_copied)
    LiveDiagnosticsCard(
        state = state,
        modifier = modifier,
        onCopy = {
            if (!offline) scope.launch {
                // A fresh read with the log lines, rather than the snapshot on screen:
                // its error lines may be up to ten seconds old.
                val text = withContext(Dispatchers.IO) { Diagnostics.liveClip(context, Diagnostics.live(context, state)) }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks live state", text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        },
        onRecheck = { recheck.trySend(Unit) },
    )
}

/**
 * How a value reads: as it should, as nothing to see, as wrong. There is no
 * "warning" tone: the presets leave tertiary at Material's default, whose dark
 * pink is the dark error colour to the eye, and a card that says "red is what
 * is wrong" cannot have a second red. Half-states say so in their words.
 */
private enum class Tone { Normal, Quiet, Bad }

@Composable
private fun Tone.color(): Color = when (this) {
    Tone.Normal -> MaterialTheme.colorScheme.onSurface
    Tone.Quiet -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Bad -> MaterialTheme.colorScheme.error
}

private class Line(val text: String, val tone: Tone = Tone.Normal)

/** Draws [state]; null while the first read is still on its way. */
@Composable
fun LiveDiagnosticsCard(
    state: Diagnostics.Live?,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit = {},
    onRecheck: () -> Unit = {},
) {
    // SettingsCard's look, with a copy button beside the title that SettingsCard has no room for.
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.diag_live_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopy, enabled = state != null) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.diag_live_copy))
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                HelpText(stringResource(R.string.diag_live_help))
                Spacer(Modifier.height(10.dp))
                if (state == null) {
                    Text(
                        stringResource(R.string.diag_live_collecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LiveRows(state, onRecheck)
                }
            }
        }
    }
}

@Composable
private fun LiveRows(s: Diagnostics.Live, onRecheck: () -> Unit) {
    val context = LocalContext.current
    val span = spanFormatter()
    val on = stringResource(R.string.diag_live_on)
    val off = stringResource(R.string.diag_live_off)
    val none = stringResource(R.string.diag_live_none)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LiveRow(
            stringResource(R.string.diag_live_process),
            when {
                !s.alive -> Line(stringResource(R.string.diag_live_not_running), if (s.wanted) Tone.Bad else Tone.Quiet)
                // Root Mode's daemon outlives the app; alive and unheld means nothing
                // in the app can talk to it yet.
                s.rootMode && !s.attached -> Line(stringResource(R.string.diag_live_detached))
                s.sinceMs > 0 -> Line(
                    stringResource(
                        if (s.rootMode) R.string.diag_live_alive_attached else R.string.diag_live_alive_up,
                        span(s.atMs - s.sinceMs)
                    )
                )
                else -> Line(stringResource(R.string.diag_live_alive))
            }
        )
        LiveRow(
            stringResource(R.string.diag_live_intent),
            Line(
                stringResource(R.string.diag_live_intent_value, if (s.wanted) on else off, if (s.alive) on else off),
                if (s.intentMismatch) Tone.Bad else Tone.Normal
            )
        )
        LiveRow(
            stringResource(R.string.diag_live_backend),
            Line(
                s.backend,
                when {
                    !s.wanted && !s.alive -> Tone.Quiet
                    // Starting passes by itself; anything else short of Running needs a hand.
                    s.backend == "Running" || s.backend == "Starting" -> Tone.Normal
                    else -> Tone.Bad
                }
            )
        )
        LiveRow(
            stringResource(R.string.diag_live_health),
            if (s.health.isEmpty()) listOf(Line(none, Tone.Quiet))
            else s.health.map { w ->
                val age = if (w.brokenSinceMs > 0) " · " + span(s.atMs - w.brokenSinceMs) else ""
                Line(w.code + age, if (w.impactsConnectivity || w.severity == "high") Tone.Bad else Tone.Normal)
            }
        )
        val engine = s.engine
        LiveRow(
            stringResource(R.string.diag_live_relays),
            when {
                engine == null -> Line(stringResource(R.string.diag_live_no_engine), if (s.alive) Tone.Normal else Tone.Quiet)
                s.relayKickMs > 0 -> Line(
                    stringResource(R.string.diag_live_relays_value, engine.liveRelays, span(s.atMs - s.relayKickMs)),
                    if (s.relaysDown) Tone.Bad else Tone.Normal
                )
                else -> Line(
                    stringResource(R.string.diag_live_relays_never, engine.liveRelays),
                    if (s.relaysDown) Tone.Bad else Tone.Normal
                )
            }
        )
        if (engine != null) {
            LiveRow(
                stringResource(R.string.diag_live_traffic),
                Line(
                    stringResource(
                        R.string.diag_live_traffic_value,
                        engine.livePeers,
                        Formatter.formatShortFileSize(context, engine.rxBytes),
                        Formatter.formatShortFileSize(context, engine.txBytes)
                    )
                )
            )
        }
        val net = s.network
        LiveRow(
            stringResource(R.string.diag_live_network),
            if (net == null) Line(stringResource(R.string.diag_live_no_network), Tone.Bad)
            else Line(
                "${net.iface} · ${net.transport} · " +
                    stringResource(if (net.validated) R.string.diag_live_validated else R.string.diag_live_not_validated),
                if (s.networkBad) Tone.Bad else Tone.Normal
            )
        )
        val probe = s.socksProbe
        LiveRow(
            stringResource(R.string.diag_live_socks),
            when {
                s.socks5.isBlank() -> listOf(Line(off, Tone.Quiet))
                probe == null -> listOf(Line(s.socks5))
                else -> {
                    val verdict = when {
                        s.socksTaken -> stringResource(R.string.diag_live_taken)
                        probe.listening -> stringResource(R.string.diag_live_listening)
                        else -> stringResource(R.string.diag_live_not_listening)
                    }
                    val tone = when {
                        s.socksDown || s.socksTaken -> Tone.Bad
                        !probe.listening -> Tone.Quiet
                        else -> Tone.Normal
                    }
                    // How old the answer is, once it is older than one refresh: while
                    // the daemon is up the port is only checked when something changes.
                    val age = s.atMs - probe.atMs
                    listOfNotNull(
                        Line("${s.socks5} · $verdict", tone),
                        if (age >= REFRESH_MS) Line(stringResource(R.string.diag_live_checked, span(age)), Tone.Quiet) else null
                    )
                }
            },
            onClick = if (s.socks5.isNotBlank()) onRecheck else null,
            onClickLabel = stringResource(R.string.diag_live_recheck)
        )
        LiveRow(
            stringResource(R.string.diag_live_http),
            if (s.http.isBlank()) Line(off, Tone.Quiet) else Line(s.http)
        )
        LiveRow(
            stringResource(R.string.diag_live_tun),
            when {
                s.rootMode -> Line(stringResource(if (s.rootTun) R.string.diag_live_root_tun else R.string.diag_live_root_no_tun))
                s.tunMode -> Line(
                    stringResource(if (s.vpnUp) R.string.diag_live_vpn_up else R.string.diag_live_vpn_down, s.tunEngine),
                    when {
                        s.vpnDown -> Tone.Bad
                        !s.vpnUp -> Tone.Quiet
                        else -> Tone.Normal
                    }
                )
                else -> Line(off, Tone.Quiet)
            }
        )
        LiveRow(
            stringResource(R.string.diag_live_errors),
            if (s.errors.isEmpty()) listOf(Line(none, Tone.Quiet))
            else s.errors.map { Line("${it.time} ${it.message}", Tone.Bad) },
            // A log line is long and only its start identifies it; Copy carries the rest.
            singleLine = true
        )
    }
}

@Composable
private fun LiveRow(
    label: String,
    line: Line,
) = LiveRow(label, listOf(line))

@Composable
private fun LiveRow(
    label: String,
    lines: List<Line>,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    singleLine: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(LABEL_WIDTH)
        )
        Column(Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = line.tone.color(),
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A duration in its two largest units, in the reader's language: "2h 14m", "2 ч 14 мин". */
@Composable
private fun spanFormatter(): (Long) -> String {
    val d = stringResource(R.string.diag_unit_d)
    val h = stringResource(R.string.diag_unit_h)
    val m = stringResource(R.string.diag_unit_m)
    val sec = stringResource(R.string.diag_unit_s)
    return remember(d, h, m, sec) {
        { ms: Long ->
            val s = (ms / 1000).coerceAtLeast(0)
            when {
                s < 60 -> sec.format(s)
                s < 3600 -> m.format(s / 60)
                s < 86_400 -> h.format(s / 3600) + (if (s % 3600 >= 60) " " + m.format((s % 3600) / 60) else "")
                else -> d.format(s / 86_400) + (if (s % 86_400 >= 3600) " " + h.format((s % 86_400) / 3600) else "")
            }
        }
    }
}

/**
 * What the card shows where there is no daemon to ask: in the preview renderer,
 * built from [LocalDemo] — its running flag, backend state, health warnings and
 * the status document's peers. Without a demo it is a service that is not running.
 */
internal fun demoLiveState(demo: DemoData?, now: Long = System.currentTimeMillis()): Diagnostics.Live {
    val running = demo?.running == true
    val health = parseHealthWarnings(demo?.healthJson)
    val peers = demo?.statusJson
        ?.let { runCatching { AppJson.decodeFromString<StatusResponse>(it) }.getOrNull() }
        ?.peers?.values.orEmpty()
    val relayWarning = health.any { healthKindOf(it.code) == HealthKind.Relay }
    return Diagnostics.Live(
        atMs = now,
        rootMode = false,
        alive = running,
        attached = running,
        sinceMs = if (running) now - (2 * 3600 + 14 * 60) * 1000L else 0L,
        wanted = running,
        backend = if (running) demo?.backendState ?: "Running" else "Stopped",
        health = health,
        engine = if (!running) null else Diagnostics.Engine(
            rxBytes = peers.sumOf { it.rxBytes ?: 0L },
            txBytes = peers.sumOf { it.txBytes ?: 0L },
            livePeers = peers.count { it.online == true },
            liveRelays = if (relayWarning) 0 else 1,
        ),
        relayKickMs = if (relayWarning) now - 38_000 else 0L,
        network = Diagnostics.Net("wlan0", "wifi", validated = true),
        socks5 = "127.0.0.1:48115",
        socksProbe = Diagnostics.Probe(listening = running, atMs = now),
        http = "",
        tunMode = false,
        tunEngine = "hev",
        vpnUp = false,
        rootTun = false,
        errors = emptyList(),
    )
}
