package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.*
import appctr.Appctr
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/*
 * Pinging a peer: the daemon's ping JSON, the state a row and the details sheet draw from
 * it, and the one-peer and many-peer pings both share (ExitNodePicker reads pongs the same way).
 */

@Serializable
private data class PingResult(
    @SerialName("Err") val err: String? = null,
    @SerialName("LatencySeconds") val latencySeconds: Double? = null,
    @SerialName("LatencyMs") val latencyMs: Double? = null,
    /** Which way the pong came (ipnstate.PingResult): "ip:port" when direct… */
    @SerialName("Endpoint") val endpoint: String? = null,
    /** …"ip:port:vni:N" through a peer relay… */
    @SerialName("PeerRelay") val peerRelay: String? = null,
    /** …or the DERP region that carried it. */
    @SerialName("DERPRegionCode") val derpRegionCode: String? = null
) {
    /** The path the answer took, unnamed; null for an answer that does not say (TSMP). */
    val via: PeerPath?
        get() = when {
            !endpoint.isNullOrBlank() -> PeerPath.Direct(endpoint)
            !peerRelay.isNullOrBlank() -> PeerPath.PeerRelay(peerRelayAddress(peerRelay), null)
            !derpRegionCode.isNullOrBlank() -> PeerPath.Derp(derpRegionCode, null)
            else -> null
        }
}

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
    /** A round trip that came back, already formatted for display ("24 ms"), and the path
     *  the answer took when the daemon said — direct, a peer relay, a DERP region. */
    data class Measured(val latency: String, val via: PeerPath? = null) : PeerPingState
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
        var via: PeerPath? = null
        val latency = try {
            val ping = AppJson.decodeFromString<PingResult>(raw)
            if (!ping.err.isNullOrEmpty()) {
                null
            } else {
                via = ping.via
                val sec = ping.latencySeconds
                if (sec != null && sec > 0) "${(sec * 1000).toInt()} ms"
                else ping.latencyMs?.takeIf { it > 0 }?.let { "${it.toInt()} ms" }
            }
        } catch (e: Exception) {
            PING_LATENCY_RE.find(raw)?.value
        }
        if (latency != null) PeerPingState.Measured(latency, via) else PeerPingState.Failed
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
 * Pings every address in [ips], writing each answer into [into] the moment it lands so the
 * rows fill in as the measurements arrive rather than all at the end. A few at a time: a
 * tailnet of forty nodes would otherwise open forty disco pings at once, and the ones at the
 * back would time out waiting for the daemon rather than for the network. Addresses already
 * being measured are left alone. Call it from a UI scope — the writes land on that scope's
 * dispatcher, and a scope that is gone drops the answers still on their way.
 */
internal suspend fun pingAll(
    ips: List<String>,
    into: MutableMap<String, String>,
    parallelism: Int = 4
) {
    val queue = ips.filter { it.isNotBlank() && into[it] != PING_IN_FLIGHT }.distinct()
    queue.forEach { into[it] = PING_IN_FLIGHT }
    for (batch in queue.chunked(parallelism)) {
        coroutineScope {
            batch.map { ip -> async { into[ip] = pingPeer(ip) } }.awaitAll()
        }
    }
}
