package io.github.bropines.tailscaled.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    @SerialName("ID") val id: Long = 0,
    @SerialName("LoginName") val loginName: String? = null,
    @SerialName("DisplayName") val displayName: String? = null,
    @SerialName("ProfilePicURL") val profilePicUrl: String? = null
)

@Serializable
data class StatusResponse(
    @SerialName("Self") val self: PeerData? = null,
    @SerialName("Peer") val peers: Map<String, PeerData>? = null,
    @SerialName("User") val users: Map<String, UserProfile>? = null,
    @SerialName("MagicDNSSuffix") val magicDnsSuffix: String? = null
)

@Serializable
data class PeerData(
    @SerialName("ID") val id: String? = null,
    @SerialName("UserID") val userID: Long? = null,
    @SerialName("HostName") val hostName: String? = null,
    @SerialName("DNSName") val dnsName: String? = null,
    @SerialName("OS") val os: String? = null,
    @SerialName("TailscaleIPs") val tailscaleIPs: List<String>? = null,
    @SerialName("AllowedIPs") val allowedIPs: List<String>? = null,
    @SerialName("Addrs") val addrs: List<String>? = null,
    @SerialName("CurAddr") val curAddr: String? = null,
    @SerialName("Online") val online: Boolean? = null,
    @SerialName("Active") val active: Boolean? = null,
    @SerialName("Relay") val relay: String? = null,
    @SerialName("PeerRelay") val peerRelay: String? = null,
    @SerialName("Created") val created: String? = null,
    @SerialName("LastWrite") val lastWrite: String? = null,
    @SerialName("LastSeen") val lastSeen: String? = null,
    @SerialName("LastHandshake") val lastHandshake: String? = null,
    @SerialName("KeyExpiry") val keyExpiry: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ExitNode") val exitNode: Boolean? = null,
    @SerialName("ExitNodeOption") val exitNodeOption: Boolean? = null,
    @SerialName("RxBytes") val rxBytes: Long? = null,
    @SerialName("TxBytes") val txBytes: Long? = null,
    @SerialName("InNetworkMap") val inNetworkMap: Boolean? = null,
    @SerialName("InMagicSock") val inMagicSock: Boolean? = null,
    @SerialName("InEngine") val inEngine: Boolean? = null,
    @SerialName("PeerAPIURL") val peerApiUrl: List<String>? = null,
    @SerialName("TaildropTarget") val taildropTarget: Int? = null,
    @SerialName("NoFileSharingReason") val noFileSharingReason: String? = null,
    @SerialName("Capabilities") val capabilities: List<String>? = null,
    @SerialName("ShareeNode") val shareeNode: Boolean? = null,
    @SerialName("Tags") val tags: List<String>? = null
) {
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "0.0.0.0"

    fun getDisplayName(): String {
        val dns = dnsName?.split(".")?.firstOrNull()
        if (!dns.isNullOrEmpty()) return dns
        if (!hostName.isNullOrEmpty()) return hostName
        return getPrimaryIp()
    }

    fun getDetailsList(): List<Pair<String, String>> {
        fun formatTime(t: String?): String {
            if (t.isNullOrEmpty() || t.startsWith("0001-01-01")) return "Never"
            return t.replace("T", " ").substringBefore(".").removeSuffix("Z")
        }

        val displaySeen = if (lastSeen != null && lastSeen.contains("0001-01-01")) "Active now" else formatTime(lastSeen)

        val list = mutableListOf(
            "Machine Name" to getDisplayName(),
            "DNS Name" to (dnsName ?: "N/A"),
            "OS" to (os ?: "Unknown"),
            "IPv4" to getPrimaryIp(),
            "IPv6" to (tailscaleIPs?.getOrNull(1) ?: "N/A"),
            "Allowed IPs" to (allowedIPs?.joinToString(", ") ?: "N/A"),
            "Tailscale Version" to (version ?: "Unknown"),
            "Node ID" to (id ?: "N/A"),
            "Relay (DERP)" to (relay?.let { if (it.isEmpty()) "Direct" else it } ?: "Direct"),
            "Current Addr" to (curAddr?.let { if (it.isEmpty()) "N/A" else it } ?: "N/A"),
            "Key Expiry" to formatTime(keyExpiry),
            "Created" to formatTime(created),
            "Last Seen" to displaySeen,
            "Last Write" to formatTime(lastWrite),
            "Last Handshake" to formatTime(lastHandshake),
            "Rx Bytes" to (rxBytes?.toString() ?: "0"),
            "Tx Bytes" to (txBytes?.toString() ?: "0"),
            "Is Exit Node" to (exitNode?.toString() ?: "false"),
            "Exit Node Option" to (exitNodeOption?.toString() ?: "false"),
            "In Network Map" to (inNetworkMap?.toString() ?: "false"),
            "In MagicSock (P2P)" to (inMagicSock?.toString() ?: "false"),
            "In WG Engine" to (inEngine?.toString() ?: "false"),
            "Capabilities" to (capabilities?.size?.toString() ?: "0"),
            "Taildrop Target" to (taildropTarget?.toString() ?: "Unknown")
        )

        if (!tags.isNullOrEmpty()) {
            list.add("Tags" to tags.joinToString(", "))
        }

        if (!noFileSharingReason.isNullOrEmpty()) {
            list.add("No File Sharing" to noFileSharingReason)
        }
        if (!peerApiUrl.isNullOrEmpty()) {
            list.add("Peer API" to peerApiUrl.first())
        }

        return list
    }
}
