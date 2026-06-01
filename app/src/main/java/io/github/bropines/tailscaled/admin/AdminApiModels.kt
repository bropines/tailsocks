package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

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

@Keep
data class ApiUser(
    @SerializedName("id") val id: String,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("loginName") val loginName: String,
    @SerializedName("profilePicUrl") val profilePicUrl: String?,
    @SerializedName("created") val created: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("role") val role: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("deviceCount") val deviceCount: Int?
)

@Keep
data class ListUsersResponse(
    @SerializedName("users") val users: List<ApiUser>?
)

@Keep
data class DnsSearchPaths(
    @SerializedName("searchPaths") val searchPaths: List<String>
)

@Keep
data class TailnetSettings(
    @SerializedName("aclsExternallyManagedOn") val aclsExternallyManagedOn: Boolean?,
    @SerializedName("aclsExternalLink") val aclsExternalLink: String?,
    @SerializedName("devicesApprovalOn") val devicesApprovalOn: Boolean?,
    @SerializedName("devicesAutoUpdatesOn") val devicesAutoUpdatesOn: Boolean?,
    @SerializedName("devicesKeyDurationDays") val devicesKeyDurationDays: Int?,
    @SerializedName("usersApprovalOn") val usersApprovalOn: Boolean?,
    @SerializedName("usersRoleAllowedToJoinExternalTailnets") val usersRoleAllowedToJoinExternalTailnets: String?,
    @SerializedName("networkFlowLoggingOn") val networkFlowLoggingOn: Boolean?,
    @SerializedName("regionalRoutingOn") val regionalRoutingOn: Boolean?
)

@Keep
data class OauthTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Long
)

@Keep
data class DeviceRoutes(
    @SerializedName("advertisedRoutes") val advertisedRoutes: List<String>?,
    @SerializedName("enabledRoutes") val enabledRoutes: List<String>?
)

