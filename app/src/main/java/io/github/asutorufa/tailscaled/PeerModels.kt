package io.github.asutorufa.tailscaled

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    @SerializedName("Self") val self: PeerData,
    @SerializedName("Peers") val peers: Map<String, PeerData>?,
    @SerializedName("MagicDNSSuffix") val magicDnsSuffix: String?
)

data class PeerData(
    @SerializedName("HostName") val hostName: String,
    @SerializedName("DNSName") val dnsName: String?,
    @SerializedName("OS") val os: String?,
    @SerializedName("TailscaleIPs") val tailscaleIPs: List<String>?,
    @SerializedName("Online") val online: Boolean?,
    @SerializedName("Active") val active: Boolean?, // Иногда используется Active вместо Online
    @SerializedName("Relay") val relay: String?, // Если через DERP
    @SerializedName("Direct") val direct: Boolean?
) {
    // Хелпер чтобы получить первый IP (обычно IPv4)
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "Unknown IP"
    
    // Хелпер для статуса
    fun isOnline(): Boolean = online == true || active == true
}