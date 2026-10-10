package io.github.bropines.tailscaled.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Tailscale's default relay (DERP) map, as the control plane serves it at /derpmap/default:
 * public, the same for every tailnet, its regions keyed by id. Only what the policy editor's
 * Relays page shows is read; the rest of each region (addresses, ports, STUN) is left out.
 */

@Serializable
data class ApiDerpMap(
    @SerialName("Regions") val regions: Map<String, ApiDerpRegion> = emptyMap(),
)

@Serializable
data class ApiDerpRegion(
    @SerialName("RegionID") val regionId: Int = 0,
    /** Three letters: "fra", "hel". */
    @SerialName("RegionCode") val code: String = "",
    /** The city: what a device's latency report is keyed by. */
    @SerialName("RegionName") val name: String = "",
    @SerialName("Nodes") val nodes: List<ApiDerpNode> = emptyList(),
)

@Serializable
data class ApiDerpNode(
    @SerialName("Name") val name: String = "",
    @SerialName("HostName") val hostName: String = "",
)
