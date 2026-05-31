package io.github.bropines.tailscaled

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ApiDevice(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("addresses") val addresses: List<String>?,
    @SerializedName("user") val user: String?,
    @SerializedName("authorized") val authorized: Boolean?,
    @SerializedName("lastSeen") val lastSeen: String?,
    @SerializedName("keyExpiryDisabled") val keyExpiryDisabled: Boolean?,
    @SerializedName("expires") val expires: String?,
    @SerializedName("hostname") val hostname: String?,
    @SerializedName("os") val os: String?,
    @SerializedName("clientVersion") val clientVersion: String?,
    @SerializedName("tags") val tags: List<String>?
) {
    fun getPrimaryIp(): String = addresses?.firstOrNull() ?: "0.0.0.0"
    fun getDisplayName(): String = name.substringBefore(".ts.net").removeSuffix(".")
}

@Keep
data class ListDevicesResponse(
    @SerializedName("devices") val devices: List<ApiDevice>?
)

@Keep
data class ApiKeyInfo(
    @SerializedName("id") val id: String,
    @SerializedName("key") val key: String?, // Only present in POST response (creation)
    @SerializedName("keyType") val keyType: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("created") val created: String?,
    @SerializedName("expires") val expires: String?,
    @SerializedName("revoked") val revoked: Boolean?,
    @SerializedName("userId") val userId: String?
)

@Keep
data class ListKeysResponse(
    @SerializedName("keys") val keys: List<ApiKeyInfo>?
)

@Keep
data class DnsPreferences(
    @SerializedName("magicDNS") val magicDNS: Boolean
)

@Keep
data class DnsNameserversResponse(
    @SerializedName("dns") val dns: List<String>?
)
