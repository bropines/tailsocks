package io.github.bropines.tailscaled

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UserProfile(
    @SerializedName("ID") val id: Long,
    @SerializedName("LoginName") val loginName: String?,
    @SerializedName("DisplayName") val displayName: String?,
    @SerializedName("ProfilePicURL") val profilePicUrl: String?
)

@Keep
data class StatusResponse(
    @SerializedName("Self") val self: PeerData?,
    @SerializedName("Peer") val peers: Map<String, PeerData>?,
    @SerializedName("User") val users: Map<String, UserProfile>?,
    @SerializedName("MagicDNSSuffix") val magicDnsSuffix: String?
)

@Keep
data class PeerData(
    @SerializedName("ID") val id: String?,
    @SerializedName("HostName") val hostName: String?,
    @SerializedName("DNSName") val dnsName: String?,
    @SerializedName("OS") val os: String?,
    @SerializedName("TailscaleIPs") val tailscaleIPs: List<String>?,
    @SerializedName("AllowedIPs") val allowedIPs: List<String>?,
    @SerializedName("Addrs") val addrs: List<String>?,
    @SerializedName("CurAddr") val curAddr: String?,
    @SerializedName("Online") val online: Boolean?,
    @SerializedName("Active") val active: Boolean?,
    @SerializedName("Relay") val relay: String?,
    @SerializedName("PeerRelay") val peerRelay: String?,
    @SerializedName("Created") val created: String?,
    @SerializedName("LastWrite") val lastWrite: String?,
    @SerializedName("LastSeen") val lastSeen: String?,
    @SerializedName("LastHandshake") val lastHandshake: String?,
    @SerializedName("KeyExpiry") val keyExpiry: String?,
    @SerializedName("Version") val version: String?,
    @SerializedName("ExitNode") val exitNode: Boolean?,
    @SerializedName("ExitNodeOption") val exitNodeOption: Boolean?,
    @SerializedName("RxBytes") val rxBytes: Long?,
    @SerializedName("TxBytes") val txBytes: Long?,
    @SerializedName("InNetworkMap") val inNetworkMap: Boolean?,
    @SerializedName("InMagicSock") val inMagicSock: Boolean?,
    @SerializedName("InEngine") val inEngine: Boolean?,
    @SerializedName("PeerAPIURL") val peerApiUrl: List<String>?,
    @SerializedName("TaildropTarget") val taildropTarget: Int?,
    @SerializedName("NoFileSharingReason") val noFileSharingReason: String?,
    @SerializedName("Capabilities") val capabilities: List<String>?
) {
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "0.0.0.0"

    fun getDisplayName(): String = dnsName?.split(".")?.firstOrNull() ?: hostName ?: "Unknown"

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
        
        if (!noFileSharingReason.isNullOrEmpty()) {
            list.add("No File Sharing" to noFileSharingReason)
        }
        if (!peerApiUrl.isNullOrEmpty()) {
            list.add("Peer API" to peerApiUrl.first())
        }

        return list
    }
}