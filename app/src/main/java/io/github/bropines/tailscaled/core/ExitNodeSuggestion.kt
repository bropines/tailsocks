package io.github.bropines.tailscaled.core

import android.util.Log
import appctr.Appctr
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The exit node the daemon itself recommends: `GET /localapi/v0/suggest-exit-node`, the call
 * behind `tailscale exit-node suggest`. It is asked once and the answer is applied as an
 * ordinary exit node, so everything that already follows `exit_node_id` / `exit_node_ip` —
 * the status card, the widgets, the tile, TUN, Root Mode — shows it without knowing where it
 * came from.
 *
 * This is deliberately not the daemon's automatic mode (`Prefs.AutoExitNode`, what
 * `--exit-node=auto:any` sets). The bridge sends `ExitNodeID` with every settings sync, and
 * tailscaled reads an explicit `ExitNodeID` as "stop choosing" (`adjustEditPrefsLocked` clears
 * `AutoExitNode`), so the mode would be switched off by the next start; and while it has no
 * candidate it holds the placeholder `auto:any`, which blackholes internet traffic.
 *
 * What the daemon needs before it can answer (1.104, `suggestExitNodeUsingDERP`): a netcheck
 * with a preferred DERP region — until the first one it fails with "no preferred DERP, try
 * again later" — and reachable exit nodes that the coordination server marked with the
 * `suggest-exit-node` capability. With none of those it answers 200 and an empty ID.
 */
object ExitNodeSuggestion {
    private const val TAG = "ExitNodeSuggestion"

    sealed interface Outcome {
        /** The node to use: its StableID (what the daemon routes by), its address and name. */
        data class Suggested(val id: String, val ip: String, val name: String, val place: String?) : Outcome
        data class Unavailable(val reason: Reason) : Outcome
    }

    enum class Reason(val retryable: Boolean) {
        /** No daemon to ask. */
        NOT_RUNNING(false),
        /** The daemon has not measured the relays yet; it asks to be asked again. */
        NO_RELAY_YET(true),
        /** No reachable exit node carries the coordination server's recommendation. */
        NONE_RECOMMENDED(false),
        FAILED(true),
    }

    @Serializable
    private data class Response(
        @SerialName("ID") val id: String = "",
        @SerialName("Name") val name: String = "",
        @SerialName("Location") val location: Location? = null,
    )

    @Serializable
    private data class Location(
        @SerialName("Country") val country: String? = null,
        @SerialName("City") val city: String? = null,
    )

    /**
     * Asks the daemon. Blocking LocalAPI work: call it off the main thread. [peers] are the
     * peers the caller already read from the status, used to turn the answer's StableID into
     * the address and the name the app keys an exit node by; null reads the status here.
     */
    fun fetch(peers: Collection<PeerData>? = null): Outcome {
        if (!Appctr.isRunning()) return Outcome.Unavailable(Reason.NOT_RUNNING)
        val raw = try {
            Appctr.doLocalAPIRequest("GET", "/localapi/v0/suggest-exit-node", "")
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
        if (raw.startsWith("Error")) {
            if (raw.contains("no preferred DERP")) return Outcome.Unavailable(Reason.NO_RELAY_YET)
            Log.w(TAG, "suggest-exit-node: $raw")
            return Outcome.Unavailable(if (Appctr.isRunning()) Reason.FAILED else Reason.NOT_RUNNING)
        }
        val answer = try {
            AppJson.decodeFromString<Response>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "suggest-exit-node: unreadable answer: ${raw.take(200)}")
            return Outcome.Unavailable(Reason.FAILED)
        }
        if (answer.id.isBlank()) return Outcome.Unavailable(Reason.NONE_RECOMMENDED)

        val known = peers ?: readPeers()
        val peer = known.firstOrNull { it.id == answer.id }
        val ip = peer?.tailscaleIPs?.firstOrNull()
        if (peer == null || ip.isNullOrBlank()) {
            // The status and the suggestion are two reads; a node can leave in between.
            Log.w(TAG, "suggest-exit-node: ${answer.id} is not among the peers")
            return Outcome.Unavailable(Reason.FAILED)
        }
        val place = listOfNotNull(
            answer.location?.city?.takeIf { it.isNotBlank() },
            answer.location?.country?.takeIf { it.isNotBlank() }
        ).joinToString(", ").ifEmpty { null }
        return Outcome.Suggested(answer.id, ip, peer.getDisplayName(), place)
    }

    private fun readPeers(): Collection<PeerData> = try {
        val json = Appctr.getStatusFromAPI()
        AppJson.decodeFromString<StatusResponse>(json).peers?.values ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}
