package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ApiDevice(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("addresses") val addresses: List<String>? = null,
    @SerialName("user") val user: String? = null,
    @SerialName("authorized") val authorized: Boolean? = null,
    @SerialName("lastSeen") val lastSeen: String? = null,
    @SerialName("keyExpiryDisabled") val keyExpiryDisabled: Boolean? = null,
    @SerialName("expires") val expires: String? = null,
    @SerialName("hostname") val hostname: String? = null,
    @SerialName("os") val os: String? = null,
    @SerialName("clientVersion") val clientVersion: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("updateAvailable") val updateAvailable: Boolean? = null,
    @SerialName("machineKey") val machineKey: String? = null,
    @SerialName("nodeKey") val nodeKey: String? = null
) {
    fun getPrimaryIp(): String = addresses?.firstOrNull() ?: "0.0.0.0"
    fun getDisplayName(): String = name.substringBefore(".ts.net").removeSuffix(".")
}

@Serializable
data class ListDevicesResponse(
    @SerialName("devices") val devices: List<ApiDevice>? = null
)

@Serializable
data class ApiKeyInfo(
    @SerialName("id") val id: String = "",
    @SerialName("key") val key: String? = null, // Only present in POST response (creation)
    @SerialName("keyType") val keyType: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("created") val created: String? = null,
    @SerialName("expires") val expires: String? = null,
    @SerialName("revoked") val revoked: Boolean? = null,
    @SerialName("userId") val userId: String? = null
)

@Serializable
data class ListKeysResponse(
    @SerialName("keys") val keys: List<ApiKeyInfo>? = null
)

@Serializable
data class DnsPreferences(
    @SerialName("magicDNS") val magicDNS: Boolean = false
)

@Serializable
data class DnsNameserversResponse(
    @SerialName("dns") val dns: List<String>? = null
)

@Serializable
data class ApiUser(
    @SerialName("id") val id: String = "",
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("loginName") val loginName: String = "",
    @SerialName("profilePicUrl") val profilePicUrl: String? = null,
    @SerialName("created") val created: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("deviceCount") val deviceCount: Int? = null
)

@Serializable
data class ListUsersResponse(
    @SerialName("users") val users: List<ApiUser>? = null
)

@Serializable
data class DnsSearchPaths(
    @SerialName("searchPaths") val searchPaths: List<String> = emptyList()
)

@Serializable
data class TailnetSettings(
    @SerialName("aclsExternallyManagedOn") val aclsExternallyManagedOn: Boolean? = null,
    @SerialName("aclsExternalLink") val aclsExternalLink: String? = null,
    @SerialName("devicesApprovalOn") val devicesApprovalOn: Boolean? = null,
    @SerialName("devicesAutoUpdatesOn") val devicesAutoUpdatesOn: Boolean? = null,
    @SerialName("devicesKeyDurationDays") val devicesKeyDurationDays: Int? = null,
    @SerialName("usersApprovalOn") val usersApprovalOn: Boolean? = null,
    @SerialName("usersRoleAllowedToJoinExternalTailnets") val usersRoleAllowedToJoinExternalTailnets: String? = null,
    @SerialName("networkFlowLoggingOn") val networkFlowLoggingOn: Boolean? = null,
    @SerialName("regionalRoutingOn") val regionalRoutingOn: Boolean? = null,
    @SerialName("postureIdentityCollectionOn") val postureIdentityCollectionOn: Boolean? = null
)

@Serializable
data class OauthTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0
)

@Serializable
data class DeviceRoutes(
    @SerialName("advertisedRoutes") val advertisedRoutes: List<String>? = null,
    @SerialName("enabledRoutes") val enabledRoutes: List<String>? = null
)

@Serializable
data class WebhookEndpoint(
    @SerialName("endpointId") val endpointId: String = "",
    @SerialName("endpointUrl") val endpointUrl: String = "",
    @SerialName("subscribedEvents") val subscribedEvents: List<String>? = null,
    @SerialName("lastTriggered") val lastTriggered: String? = null
)

@Serializable
data class ListWebhooksResponse(
    @SerialName("webhooks") val webhooks: List<WebhookEndpoint>? = null
)

@Serializable
data class VIPServiceInfo(
    @SerialName("name") val name: String = "",
    @SerialName("addrs") val addrs: List<String>? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("ports") val ports: List<String>? = null,
    @SerialName("tags") val tags: List<String>? = null
)

@Serializable
data class ListServicesResponse(
    @SerialName("vipServices") val vipServices: List<VIPServiceInfo>? = null
)

@Serializable
data class ServiceHostInfo(
    @SerialName("stableNodeID") val stableNodeID: String = "",
    @SerialName("approvalLevel") val approvalLevel: String? = null,
    @SerialName("configured") val configured: String? = null
)

@Serializable
data class ListServiceHostsResponse(
    @SerialName("hosts") val hosts: List<ServiceHostInfo>? = null
)

@Serializable
data class ApiAuditLogActor(
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("loginName") val loginName: String? = null,
    @SerialName("type") val type: String? = null
)

@Serializable
data class ApiAuditLogTarget(
    @SerialName("id") val id: String? = null,
    @SerialName("isEphemeral") val isEphemeral: Boolean? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("type") val type: String? = null
)

@Serializable
data class ApiAuditLogEntry(
    @SerialName("action") val action: String? = null,
    @SerialName("actor") val actor: ApiAuditLogActor? = null,
    @SerialName("eventTime") val eventTime: String? = null,
    @SerialName("origin") val origin: String? = null,
    @SerialName("target") val target: ApiAuditLogTarget? = null,
    @SerialName("type") val type: String? = null
)

@Serializable
data class AuditLogsResponse(
    @SerialName("version") val version: String? = null,
    @SerialName("tailnet") val tailnet: String? = null,
    @SerialName("logs") val logs: List<ApiAuditLogEntry>? = null
)
