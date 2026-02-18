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
    @SerializedName("ExitNode") val isExitNode: Boolean? = false
) {
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "0.0.0.0"
    fun isOnline(): Boolean = online == true || active == true
    
    // Имя из веб-панели обычно в DNSName (до первой точки)
    fun getDisplayName(): String {
        val dnsShort = dnsName?.split(".")?.firstOrNull()
        return if (!dnsShort.isNullOrEmpty()) dnsShort else hostName ?: "Unknown"
    }

    // Полная информация строкой для копирования
    fun getFullDetails(): String {
        return """
            Hostname: $hostName
            DNS Name: $dnsName
            OS: $os
            IPs: ${tailscaleIPs?.joinToString(", ")}
            Online: ${isOnline()}
            Relay: ${relay ?: "Direct"}
            Key Expiry: ${keyExpiry ?: "No expiry"}
        """.trimIndent()
    }
}