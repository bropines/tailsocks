package io.github.bropines.tailscaled.ui

import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.PeerTrafficSampler
import io.github.bropines.tailscaled.core.TrafficPoint
import io.github.bropines.tailscaled.core.TrafficRate
import io.github.bropines.tailscaled.core.TrafficSnapshot
import io.github.bropines.tailscaled.core.trafficKey
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/*
 * The live traffic the speed graph (SpeedGraph.kt) and the tailnet map (TailnetMapScreen.kt)
 * both draw: the daemon's status read on a timer and turned into rates by
 * core/PeerTraffic.kt. One poller for the process, so the two never ask twice, and so the
 * graph's last minute is still there when the map is opened from the screen that showed it.
 *
 * It runs only while somebody is looking: a composable that calls [rememberLiveTraffic]
 * subscribes while its activity is at least STARTED, and the poller stops with the last
 * subscriber. Nothing here starts, restarts or configures the daemon — it only reads.
 */

/**
 * What the poller last read. [loaded] is false until the first answer; [running] is whether
 * there was a daemon to ask. [peers] are the peers worth showing ([listablePeers]), [self]
 * this device, [users] the names of the tailnet's users by id — what the map groups devices
 * by owner with; all three keep their last values across a read that failed.
 */
@Immutable
data class LiveTrafficState(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val self: PeerData? = null,
    val peers: List<PeerData> = emptyList(),
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val users: Map<Long, String> = emptyMap(),
)

/** The users a status names, by id: the display name, or the login where there is none. */
private fun userNames(status: StatusResponse): Map<Long, String> =
    status.users.orEmpty().values.mapNotNull { u ->
        (u.displayName?.takeIf { it.isNotBlank() } ?: u.loginName?.takeIf { it.isNotBlank() })?.let { u.id to it }
    }.toMap()

internal object LiveTraffic {
    private const val TAG = "LiveTraffic"

    /**
     * Two seconds between the end of one read and the start of the next. A status with peers
     * is a LocalAPI round trip in which the daemon walks every peer under its locks and the
     * app decodes the whole tailnet — a few hundred nodes on a tailnet with Mullvad — so it
     * is not something to do every frame or every half second. Two seconds is the pace the
     * live diagnostics card already reads at, is short enough for a rate to feel live, and
     * gives the graph thirty points for its minute. The pause is counted from the answer, so
     * a slow daemon is asked less often, not queued up on.
     */
    const val POLL_MS = 2_000L

    private val sampler = PeerTrafficSampler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var latest = LiveTrafficState()

    /**
     * Collected by [rememberLiveTraffic] only. The upstream loop exists while there is a
     * collector; with none the last value stays, and the sampler discards it as too old to
     * compare with when the polling resumes.
     */
    val state: StateFlow<LiveTrafficState> = flow {
        while (true) {
            emit(read())
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(), LiveTrafficState())

    /**
     * One read. Synchronized because a cancelled loop cannot be interrupted inside the
     * bridge call: a new subscriber's loop may start while the old one is still coming back.
     */
    private fun read(): LiveTrafficState = synchronized(sampler) {
        // The bridge's own answer, not ProxyState.isActualRunning: in Root Mode the daemon
        // can be alive before the service has attached the bridge to its socket, and until
        // then there is nobody a status call could reach.
        val running = runCatching { Appctr.isRunning() }.getOrDefault(false)
        if (!running) {
            sampler.reset()
            return LiveTrafficState(loaded = true, running = false).also { latest = it }
        }
        val status = try {
            val json = Appctr.getStatusFromAPI()
            if (json.isNullOrBlank() || json.startsWith("Error")) null
            else AppJson.decodeFromString<StatusResponse>(json)
        } catch (e: Exception) {
            Log.w(TAG, "status: ${e.javaClass.simpleName}")
            null
        }
        // A daemon that is starting, or busy: what was on screen stays, without a new point.
        if (status == null) return latest.copy(loaded = true, running = true).also { latest = it }
        // elapsedRealtime, not the wall clock: a time sync must not look like a pause.
        val traffic = sampler.add(SystemClock.elapsedRealtime(), status)
        LiveTrafficState(true, true, status.self, listablePeers(status), traffic, userNames(status)).also { latest = it }
    }
}

/**
 * The live traffic, read every [LiveTraffic.POLL_MS] while the caller is composed and its
 * activity is at least STARTED — a dashboard left open under another app asks nothing. In a
 * preview, which has no bridge, it is [LocalDemo]'s tailnet with made-up rates.
 */
@Composable
fun rememberLiveTraffic(): LiveTrafficState {
    val demo = LocalDemo.current
    if (LocalInspectionMode.current || demo != null) return remember(demo) { demoLiveTraffic(demo) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(LiveTraffic.state.value) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            LiveTraffic.state.collect { state = it }
        }
    }
    return state
}

/** A rate in the reader's units and language: "2.4 MB/s", "180 кБ/с". */
@Composable
internal fun rememberRateFormatter(): (Long) -> String {
    val context = LocalContext.current
    val perSecond = stringResource(R.string.taildrop_speed_format)
    return remember(context, perSecond) {
        { bps: Long -> perSecond.format(Formatter.formatShortFileSize(context, bps.coerceAtLeast(0))) }
    }
}

/** The rates a demo's first peers are given, busiest first: a download, a stream, a trickle. */
private val DEMO_RATES = listOf(
    TrafficRate(rxBps = 2_400_000, txBps = 180_000),
    TrafficRate(rxBps = 310_000, txBps = 640_000),
    TrafficRate(rxBps = 42_000, txBps = 8_000),
)

/**
 * What the graph and the map draw where there is no daemon: [demo]'s status with made-up
 * rates on its first online peers and a minute of history that ends at their sum. Nothing
 * random in it — a preview is compared with its last picture. Without a demo, or with one
 * whose service is stopped, it is a daemon that is not running.
 */
internal fun demoLiveTraffic(demo: DemoData?): LiveTrafficState {
    val status = demo?.takeIf { it.running }?.statusJson
        ?.let { runCatching { AppJson.decodeFromString<StatusResponse>(it) }.getOrNull() }
        ?: return LiveTrafficState(loaded = true, running = false)
    val peers = listablePeers(status)
    // Online and talked to: traffic implies a path, and a demo should not draw one without the other.
    val rates = peers.filter { it.online == true && it.lastWrite?.startsWith("0001-01-01") != true }
        .mapNotNull { it.trafficKey() }
        .zip(DEMO_RATES).toMap()
    val total = TrafficRate(rates.values.sumOf { it.rxBps }, rates.values.sumOf { it.txBps })
    val points = 31
    val history = List(points) { i ->
        // A download that ramps up, dips and settles; an upload that follows at a distance.
        val t = i / (points - 1f)
        val shape = if (i == points - 1) 1f else 0.25f + 0.75f * t * (0.8f + 0.2f * kotlin.math.sin(t * 14f))
        val up = if (i == points - 1) 1f else 0.4f + 0.6f * t * (0.7f + 0.3f * kotlin.math.cos(t * 9f))
        TrafficPoint(i * LiveTraffic.POLL_MS, (total.rxBps * shape).toLong(), (total.txBps * up).toLong())
    }
    val now = history.last().atMs
    return LiveTrafficState(true, true, status.self, peers, TrafficSnapshot(now, true, total, rates, history), userNames(status))
}
