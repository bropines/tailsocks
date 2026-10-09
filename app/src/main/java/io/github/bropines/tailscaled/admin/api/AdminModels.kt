package io.github.bropines.tailscaled.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The Admin API's objects as Tailscale's OpenAPI schema (api/v2, 2026-10) defines them.
 * Every field is optional here even where the schema marks it required: a field the server
 * leaves out must not cost the whole list, and decode failures that do happen are reported
 * per item by the backend (see Listing). Headscale's /api/v2 answers in the same shapes.
 */

@Serializable
data class ApiDevice(
    /** Legacy numeric id; still accepted in paths, but [nodeId] is the preferred one. */
    val id: String = "",
    val nodeId: String = "",
    /** The MagicDNS name, "host.tail1234.ts.net". */
    val name: String = "",
    /** The machine name in the console, from the device's OS hostname. */
    val hostname: String? = null,
    val addresses: List<String> = emptyList(),
    /** The user who registered the device; its owner unless it is tagged. */
    val user: String? = null,
    val os: String? = null,
    val clientVersion: String? = null,
    val updateAvailable: Boolean? = null,
    val created: String? = null,
    /** Whether the device holds a connection to control right now: the online state. */
    val connectedToControl: Boolean? = null,
    /** Omitted while [connectedToControl] is true and for devices never online. */
    val lastSeen: String? = null,
    val keyExpiryDisabled: Boolean? = null,
    val expires: String? = null,
    val authorized: Boolean? = null,
    /** Shared into this tailnet from another one: read-only here. */
    val isExternal: Boolean? = null,
    /** Several machines connect with the same node key: copied state. */
    val multipleConnections: Boolean? = null,
    val machineKey: String? = null,
    val nodeKey: String? = null,
    val blocksIncomingConnections: Boolean? = null,
    /** Only with fields=all. */
    val enabledRoutes: List<String>? = null,
    /** Only with fields=all. */
    val advertisedRoutes: List<String>? = null,
    val tags: List<String> = emptyList(),
    val tailnetLockError: String? = null,
    val tailnetLockKey: String? = null,
    val sshEnabled: Boolean? = null,
    val isEphemeral: Boolean? = null,
    val distro: ApiDistro? = null,
    /** The device's network as it last reported it; only with fields=all. */
    val clientConnectivity: ApiClientConnectivity? = null,
) {
    /** The id to put in a path: [nodeId], which the API prefers, else the legacy [id]. */
    val pathId: String get() = nodeId.ifBlank { id }

    /** The device's own label: "host" of "host.tail1234.ts.net", else the hostname. */
    val shortName: String
        get() = name.substringBefore('.').ifBlank { hostname?.takeIf { it.isNotBlank() } ?: pathId }

    /** "tail1234.ts.net" of "host.tail1234.ts.net."; null when the name has no domain. */
    val dnsSuffix: String?
        get() = name.trimEnd('.').substringAfter('.', "").takeIf { it.isNotBlank() }

    val ipv4: String? get() = addresses.firstOrNull { '.' in it && ':' !in it }
    val ipv6: String? get() = addresses.firstOrNull { ':' in it }

    val isOnline: Boolean get() = connectedToControl == true
    val isShared: Boolean get() = isExternal == true
    val isTagged: Boolean get() = tags.isNotEmpty()
}

@Serializable
data class ApiDistro(val name: String? = null, val version: String? = null, val codeName: String? = null)

/** A device's own report of its network: endpoints, relay latencies, what the network allows. */
@Serializable
data class ApiClientConnectivity(
    val endpoints: List<String> = emptyList(),
    /** True behind a NAT whose mapping changes per destination: direct connections are harder. */
    val mappingVariesByDestIP: Boolean? = null,
    /** By DERP region name. */
    val latency: Map<String, ApiDerpLatency> = emptyMap(),
    val clientSupports: ApiClientSupports? = null,
)

@Serializable
data class ApiDerpLatency(val preferred: Boolean? = null, val latencyMs: Double? = null)

@Serializable
data class ApiClientSupports(
    val ipv6: Boolean? = null,
    val pcp: Boolean? = null,
    val pmp: Boolean? = null,
    val udp: Boolean? = null,
    val upnp: Boolean? = null,
)

@Serializable
data class DeviceRoutes(
    val advertisedRoutes: List<String> = emptyList(),
    val enabledRoutes: List<String> = emptyList(),
)

/** An auth key, an API access token, an OAuth client or a federated identity. */
@Serializable
data class ApiKey(
    val id: String = "",
    /** The secret; present only in the answer that created the key. */
    val key: String? = null,
    val keyType: String? = null,
    val description: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val expires: String? = null,
    /** When the key was revoked — a timestamp, not a flag. */
    val revoked: String? = null,
    /** Set on a revoked or expired key fetched by id. */
    val invalid: Boolean? = null,
    val expirySeconds: Long? = null,
    val capabilities: KeyCapabilities? = null,
    val scopes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Who created the key; empty for keys created by trust credentials. */
    val userId: String? = null,
) {
    val type: KeyType get() = KeyType.of(keyType)
    val isRevoked: Boolean get() = !revoked.isNullOrBlank()
    val createOptions: KeyCreateOptions? get() = capabilities?.devices?.create
}

enum class KeyType(val wire: String) {
    AUTH("auth"), CLIENT("client"), API("api"), FEDERATED("federated"), UNKNOWN("");

    companion object {
        fun of(wire: String?): KeyType = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

@Serializable
data class KeyCapabilities(val devices: KeyDeviceCapabilities? = null)

@Serializable
data class KeyDeviceCapabilities(val create: KeyCreateOptions? = null)

@Serializable
data class KeyCreateOptions(
    val reusable: Boolean? = null,
    val ephemeral: Boolean? = null,
    val preauthorized: Boolean? = null,
    val tags: List<String>? = null,
)

/** What a new auth key is to be: each switch on its own, the way the API takes them. */
data class AuthKeyRequest(
    val description: String = "",
    val expirySeconds: Long = 90L * 24 * 3600,
    val reusable: Boolean = false,
    val ephemeral: Boolean = false,
    val preauthorized: Boolean = false,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ApiUser(
    val id: String = "",
    val displayName: String? = null,
    val loginName: String = "",
    val profilePicUrl: String? = null,
    val tailnetId: String? = null,
    val created: String? = null,
    /** "member" or "shared". */
    val type: String? = null,
    val role: String? = null,
    val status: String? = null,
    val deviceCount: Int? = null,
    val lastSeen: String? = null,
    val currentlyConnected: Boolean? = null,
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: loginName.substringBefore("@").ifBlank { id }
    val userRole: UserRole get() = UserRole.of(role)
    val userStatus: UserStatus get() = UserStatus.of(status)
}

enum class UserRole(val wire: String) {
    OWNER("owner"), ADMIN("admin"), IT_ADMIN("it-admin"), NETWORK_ADMIN("network-admin"),
    BILLING_ADMIN("billing-admin"), AUDITOR("auditor"), MEMBER("member"), UNKNOWN("");

    val isPrivileged: Boolean get() = this == OWNER || this == ADMIN || this == IT_ADMIN || this == NETWORK_ADMIN

    companion object {
        fun of(wire: String?): UserRole = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
        /** What a role change may set: every role but owner, which only a transfer in the console moves. */
        val assignable: List<UserRole> = listOf(ADMIN, NETWORK_ADMIN, IT_ADMIN, BILLING_ADMIN, AUDITOR, MEMBER)
    }
}

enum class UserStatus(val wire: String) {
    ACTIVE("active"), IDLE("idle"), SUSPENDED("suspended"), NEEDS_APPROVAL("needs-approval"),
    OVER_BILLING_LIMIT("over-billing-limit"), UNKNOWN("");

    companion object {
        fun of(wire: String?): UserStatus = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Which users a list asks for; the API's default is members only. */
enum class UserListType(val wire: String) { MEMBER("member"), SHARED("shared"), ALL("all") }

@Serializable
data class ApiWebhook(
    val endpointId: String = "",
    val endpointUrl: String = "",
    /** "slack", "mattermost", "googlechat", "discord", or empty for plain JSON. */
    val providerType: String? = null,
    val creatorLoginName: String? = null,
    val created: String? = null,
    val lastModified: String? = null,
    val subscriptions: List<String> = emptyList(),
    /** The signing secret; present only on creation and after a rotation. */
    val secret: String? = null,
)

/** The events a webhook may subscribe to, in the schema's order. */
object WebhookEvents {
    val all: List<String> = listOf(
        "nodeCreated", "nodeNeedsApproval", "nodeApproved", "nodeKeyExpiringInOneDay", "nodeKeyExpired",
        "nodeDeleted", "nodeSigned", "nodeNeedsSignature", "policyUpdate", "userCreated", "userNeedsApproval",
        "userSuspended", "userRestored", "userDeleted", "userApproved", "userRoleUpdated",
        "subnetIPForwardingNotEnabled", "exitNodeIPForwardingNotEnabled",
    )
    val providerTypes: List<String> = listOf("", "slack", "mattermost", "googlechat", "discord")
}

@Serializable
data class TailnetSettings(
    val aclsExternallyManagedOn: Boolean? = null,
    val aclsExternalLink: String? = null,
    val devicesApprovalOn: Boolean? = null,
    val devicesAutoUpdatesOn: Boolean? = null,
    val devicesKeyDurationDays: Int? = null,
    val usersApprovalOn: Boolean? = null,
    val usersRoleAllowedToJoinExternalTailnets: String? = null,
    val networkFlowLoggingOn: Boolean? = null,
    /** Read-only now; [routeSelection] replaced it and a PATCH must not carry it. */
    val regionalRoutingOn: Boolean? = null,
    val routeSelection: String? = null,
    val postureIdentityCollectionOn: Boolean? = null,
    val httpsEnabled: Boolean? = null,
)

/**
 * One tailnet setting, as a PATCH carries it: each is saved on its own, so a setting outside
 * the credential's scopes fails its own row and not the others. [scopeArea] is the scope that
 * governs it, per the schema's notes on updateTailnetSettings.
 */
enum class TailnetSettingKey(val wire: String, val scopeArea: AdminArea) {
    DEVICES_APPROVAL("devicesApprovalOn", AdminArea.SETTINGS),
    DEVICES_AUTO_UPDATES("devicesAutoUpdatesOn", AdminArea.SETTINGS),
    DEVICES_KEY_DURATION("devicesKeyDurationDays", AdminArea.SETTINGS),
    USERS_APPROVAL("usersApprovalOn", AdminArea.SETTINGS),
    USERS_EXTERNAL_ROLE("usersRoleAllowedToJoinExternalTailnets", AdminArea.SETTINGS),
    NETWORK_FLOW_LOGGING("networkFlowLoggingOn", AdminArea.NETWORK_LOGS),
    ROUTE_SELECTION("routeSelection", AdminArea.SETTINGS),
    POSTURE_IDENTITY("postureIdentityCollectionOn", AdminArea.SETTINGS),
    HTTPS("httpsEnabled", AdminArea.NETWORKING_SETTINGS),
    ACLS_EXTERNALLY_MANAGED("aclsExternallyManagedOn", AdminArea.POLICY),
    ACLS_EXTERNAL_LINK("aclsExternalLink", AdminArea.POLICY),
}

/** The values [TailnetSettings.routeSelection] takes. */
object RouteSelection {
    const val FAILOVER = "active-passive-failover"
    const val REGIONAL = "regional-routing"
    const val REGIONAL_FAILOVER = "regional-routing-failover"
    val all = listOf(FAILOVER, REGIONAL, REGIONAL_FAILOVER)
}

@Serializable
data class DnsResolver(
    val address: String = "",
    val useWithExitNode: Boolean? = null,
)

@Serializable
data class DnsConfigPreferences(
    val overrideLocalDNS: Boolean? = null,
    val magicDNS: Boolean? = null,
)

/** /dns/configuration: the whole DNS setup in one document; the only place for Override local DNS. */
@Serializable
data class DnsConfiguration(
    val nameservers: List<DnsResolver> = emptyList(),
    @SerialName("splitDNS") val splitDns: Map<String, List<DnsResolver>> = emptyMap(),
    val searchPaths: List<String> = emptyList(),
    val preferences: DnsConfigPreferences = DnsConfigPreferences(),
) {
    val magicDns: Boolean get() = preferences.magicDNS == true
    val nameserverAddresses: List<String> get() = nameservers.map { it.address }
    val splitDnsAddresses: Map<String, List<String>> get() = splitDns.mapValues { (_, v) -> v.map { it.address } }
}

/** A Tailscale Service (`svc:name`). */
@Serializable
data class ApiService(
    val name: String = "",
    val displayName: String? = null,
    val addrs: List<String> = emptyList(),
    val comment: String? = null,
    val ports: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class ApiServiceHost(
    val stableNodeID: String = "",
    /** "not-approved", "approved:auto" or "approved:manual". */
    val approvalLevel: String? = null,
    val configured: String? = null,
) {
    val isApproved: Boolean get() = approvalLevel?.startsWith("approved") == true
}

@Serializable
data class ApiAuditActor(
    val id: String? = null,
    val type: String? = null,
    val loginName: String? = null,
    val displayName: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ApiAuditTarget(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val isEphemeral: Boolean? = null,
    val property: String? = null,
)

@Serializable
data class ApiAuditLogEntry(
    val eventTime: String? = null,
    val type: String? = null,
    val eventGroupID: String? = null,
    val origin: String? = null,
    val actor: ApiAuditActor? = null,
    val target: ApiAuditTarget? = null,
    val action: String? = null,
    /** The property's value before and after; any JSON type. */
    val old: JsonElement? = null,
    val new: JsonElement? = null,
    val actionDetails: String? = null,
    val error: String? = null,
)

/** The policy file as the server holds it, with the ETag a later write must carry. */
data class PolicyFile(val text: String, val etag: String?)

@Serializable
internal data class OauthTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val scope: String? = null,
)

@Serializable
internal data class ApiErrorBody(val message: String? = null)
