package io.github.asutorufa.tailscaled

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class StatusResponse(
    @SerializedName("Self") val self: PeerData?,
    @SerializedName("Peer") val peers: Map<String, PeerData>?,
    @SerializedName("MagicDNSSuffix") val magicDnsSuffix: String?
)

@Keep
data class PeerData(
    @SerializedName("ID") val id: String?,
    @SerializedName("HostName") val hostName: String?,
    @SerializedName("DNSName") val dnsName: String?,
    @SerializedName("OS") val os: String?,
    @SerializedName("TailscaleIPs") val tailscaleIPs: List<String>?,
    @SerializedName("Online") val online: Boolean?,
    @SerializedName("Active") val active: Boolean?, 
    @SerializedName("Relay") val relay: String?,
    @SerializedName("LastSeen") val lastSeen: String?,
    @SerializedName("KeyExpiry") val keyExpiry: String?,
    @SerializedName("Version") val version: String?,
    @SerializedName("ExitNodeOption") val exitNodeOption: Boolean?
) {
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "0.0.0.0"
    
    fun getDisplayName(): String = dnsName?.split(".")?.firstOrNull() ?: hostName ?: "Unknown"

    fun getDetailsList(): List<Pair<String, String>> {
        val displaySeen = if (lastSeen != null && lastSeen.contains("0001-01-01")) {
            "Active now"
        } else {
            lastSeen?.replace("T", " ")?.substringBefore(".")?.removeSuffix("Z") ?: "Unknown"
        }

        return listOf(
            "Machine Name" to getDisplayName(),
            "OS" to (os ?: "Unknown"),
            "IPv4" to getPrimaryIp(),
            "IPv6" to (tailscaleIPs?.getOrNull(1) ?: "N/A"),
            "Tailscale Version" to (version ?: "Unknown"),
            "Node ID" to (id ?: "N/A"),
            "Relay" to (relay ?: "Direct"),
            "Key Expiry" to (keyExpiry?.split("T")?.firstOrNull() ?: "No expiry"),
            "Last Seen" to displaySeen
        )
    }
}