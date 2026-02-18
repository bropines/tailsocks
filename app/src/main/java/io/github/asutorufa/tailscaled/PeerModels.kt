package io.github.asutorufa.tailscaled

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class StatusResponse(
    @SerializedName("Self") val self: PeerData,
    @SerializedName("Peer") val peers: Map<String, PeerData>?,
    @SerializedName("MagicDNSSuffix") val magicDnsSuffix: String?
)

@Keep
data class PeerData(
    @SerializedName("HostName") val hostName: String,
    @SerializedName("DNSName") val dnsName: String?,
    @SerializedName("OS") val os: String?,
    @SerializedName("TailscaleIPs") val tailscaleIPs: List<String>?,
    @SerializedName("Online") val online: Boolean?,
    @SerializedName("Active") val active: Boolean?, 
    @SerializedName("Relay") val relay: String?,
    @SerializedName("Direct") val direct: Boolean?
) {
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "Unknown IP"
    fun isOnline(): Boolean = online == true || active == true
}