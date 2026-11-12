package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse

/*
 * Traffic rates out of the daemon's status. The status carries no rate, only what each peer's
 * WireGuard session has received and sent so far (ipnstate.PeerStatus.RxBytes / TxBytes), so a
 * rate is the difference between two readings over the time between them. Everything here is
 * plain arithmetic on what it is handed — no clock, no daemon — which is what lets the tests
 * replay any sequence of readings; ui/LiveTraffic.kt is the part that asks the daemon.
 */

/** Bytes per second, received and sent. Never negative. */
data class TrafficRate(val rxBps: Long = 0, val txBps: Long = 0) {
    val totalBps: Long get() = rxBps + txBps

    companion object {
        val ZERO = TrafficRate()
    }
}

/** The whole tailnet's rate at [atMs]: one point of the speed graph. */
data class TrafficPoint(val atMs: Long, val rxBps: Long, val txBps: Long)

/**
 * What the sampler knows after a reading: the tailnet's rate, each peer's (by [trafficKey];
 * a peer with no entry has no rate yet, which reads as zero) and the recent points of the
 * total, oldest first. [measured] is false until two readings stand close enough to compare —
 * the first one after a start or a pause has nothing to be compared with.
 */
data class TrafficSnapshot(
    val atMs: Long = 0,
    val measured: Boolean = false,
    val total: TrafficRate = TrafficRate.ZERO,
    val peers: Map<String, TrafficRate> = emptyMap(),
    val history: List<TrafficPoint> = emptyList(),
) {
    fun rateOf(peer: PeerData): TrafficRate = peer.trafficKey()?.let { peers[it] } ?: TrafficRate.ZERO
}

/**
 * What a peer's counters are kept under: its stable node id, or its node key where a status
 * names none. Null for a peer with neither, which is then not measured on its own.
 */
fun PeerData.trafficKey(): String? = id?.takeIf { it.isNotEmpty() } ?: publicKey?.takeIf { it.isNotEmpty() }

/**
 * Turns successive status readings into rates.
 *
 * What the counters do that a plain subtraction would get wrong:
 *  - **They restart.** A daemon restart, a profile switch or a re-established session starts a
 *    peer's counters from zero. A counter that went down is taken as a new baseline with no
 *    rate for that interval — never a negative one, and never the new value as if it had all
 *    arrived just now.
 *  - **Peers come and go.** A peer seen for the first time has an unknown past: its first
 *    reading is its baseline. One that left takes its baseline with it, so its return is a
 *    first sight again. The total is the sum of the peers' own differences and so follows the
 *    same rules, instead of dropping by a departed peer's whole history.
 *  - **Readings are not evenly spaced.** A slow LocalAPI round trip or a busy device stretches
 *    an interval; each rate is divided by the time that really passed. Two readings further
 *    apart than [maxGapMs] — the poller was stopped, the app was in the background — are not
 *    compared at all: the average over minutes is not "now", and the later reading starts
 *    over. So does one that is not later than the last (a clock set back).
 *
 * The history holds the total's points of the last [historyMs], at most [maxPoints] of them.
 * Not thread-safe: one poller feeds it.
 */
class PeerTrafficSampler(
    private val historyMs: Long = HISTORY_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val maxPoints: Int = MAX_POINTS,
) {
    private class Counters(val rx: Long, val tx: Long)

    private var lastAtMs = 0L
    private var last: Map<String, Counters>? = null
    private val history = ArrayDeque<TrafficPoint>()

    /** The latest answer, as [add] last returned it. */
    var snapshot = TrafficSnapshot()
        private set

    /** Reads [status] taken at [atMs] (any monotonic or wall clock, as long as it is one clock). */
    fun add(atMs: Long, status: StatusResponse): TrafficSnapshot {
        val now = HashMap<String, Counters>()
        status.peers?.forEach { (mapKey, peer) ->
            // The map's own key is the node key; it stands in for a peer that names nothing.
            val key = peer.trafficKey() ?: mapKey
            now[key] = Counters(peer.rxBytes ?: 0L, peer.txBytes ?: 0L)
        }

        val before = last
        val elapsed = atMs - lastAtMs
        last = now
        lastAtMs = atMs
        history.removeAll { it.atMs > atMs || atMs - it.atMs > historyMs }

        if (before == null || elapsed <= 0 || elapsed > maxGapMs) {
            snapshot = TrafficSnapshot(atMs = atMs, measured = false, history = history.toList())
            return snapshot
        }

        val rates = HashMap<String, TrafficRate>(now.size)
        var totalRx = 0L
        var totalTx = 0L
        for ((key, cur) in now) {
            val prev = before[key] ?: continue
            // One counter going down means the session restarted; the other one's difference
            // would then be measured against a past that no longer exists.
            if (cur.rx < prev.rx || cur.tx < prev.tx) continue
            val rate = TrafficRate((cur.rx - prev.rx) * 1000 / elapsed, (cur.tx - prev.tx) * 1000 / elapsed)
            if (rate.totalBps > 0) rates[key] = rate
            totalRx += rate.rxBps
            totalTx += rate.txBps
        }

        history.addLast(TrafficPoint(atMs, totalRx, totalTx))
        while (history.size > maxPoints) history.removeFirst()
        snapshot = TrafficSnapshot(atMs, true, TrafficRate(totalRx, totalTx), rates, history.toList())
        return snapshot
    }

    /** Forgets everything: the daemon stopped, and what it counts next is another run's. */
    fun reset() {
        last = null
        lastAtMs = 0
        history.clear()
        snapshot = TrafficSnapshot()
    }

    companion object {
        /** What the speed graph shows. */
        const val HISTORY_MS = 60_000L

        /** Readings further apart than this are not compared; also where the graph's line breaks. */
        const val MAX_GAP_MS = 10_000L

        /** A bound for a poller much faster than the one there is; 60 s at 2 s is 30 points. */
        const val MAX_POINTS = 240
    }
}

/**
 * The [count] peers with the most traffic right now, busiest first — "who is using it". Only
 * peers that are moving something: a list padded with idle ones would name nobody in particular.
 */
fun TrafficSnapshot.topTalkers(peers: Collection<PeerData>, count: Int): List<Pair<PeerData, TrafficRate>> =
    peers.asSequence()
        .map { it to rateOf(it) }
        .filter { it.second.totalBps > 0 }
        .sortedWith(compareByDescending<Pair<PeerData, TrafficRate>> { it.second.totalBps }.thenBy { it.first.getDisplayName() })
        .take(count)
        .toList()

/** The lowest top the graph's scale has: under it an idle tailnet's keepalives would fill the card. */
private const val MIN_CEILING_BPS = 10_000L

/**
 * The top of the graph's scale for a highest rate of [maxBps]: the next of 1, 2, 5 × 10ⁿ at
 * or above it, and never under 10 kB/s. A round ceiling that moves in steps keeps the curve
 * from being rescaled by every new point, and can be printed beside the graph as it is.
 */
fun graphCeiling(maxBps: Long): Long {
    var step = MIN_CEILING_BPS
    while (true) {
        for (factor in longArrayOf(1, 2, 5)) {
            if (step * factor >= maxBps) return step * factor
        }
        // Past any rate a device will see; also where the next step would overflow.
        if (step >= 1_000_000_000_000L) return maxBps
        step *= 10
    }
}
