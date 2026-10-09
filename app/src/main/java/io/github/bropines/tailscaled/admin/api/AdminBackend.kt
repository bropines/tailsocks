package io.github.bropines.tailscaled.admin.api

import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class BackendKind {
    /** Tailscale's own control plane, api.tailscale.com/api/v2. */
    TAILSCALE,
    /** Headscale 0.30+: the Tailscale-compatible subset at /api/v2. */
    HEADSCALE_V2,
    /** Headscale 0.25–0.29: /api/v1, adapted. */
    HEADSCALE_V1,
}

/**
 * A list as far as it could be read: the [items] that decoded, and an [issues] entry for each
 * one that did not. A device whose JSON changed shape costs that device, not the list — and
 * the console says so instead of showing an empty list as if it were the truth.
 */
data class Listing<T>(val items: List<T>, val issues: List<DecodeIssue> = emptyList()) {
    val isComplete: Boolean get() = issues.isEmpty()
}

/** One list element that could not be read: where it was, its id if it had one, and why. */
data class DecodeIssue(val what: String, val index: Int, val id: String?, val reason: String)

/**
 * The console's view of a control server. Tailscale implements all of it; a Headscale backend
 * implements what its API has and leaves the rest to the defaults, which throw
 * [AdminApiException.Unsupported] — the console never calls them, because [capabilities]
 * says the feature is not there.
 *
 * Everything suspends and runs off the main thread on its own. Reads may be retried (429,
 * 502–504, a dropped proxy); writes are sent once over one route, and retried only on a 429,
 * which the server answers before doing anything.
 */
interface AdminBackend {
    val kind: BackendKind

    /** What may be offered; narrows on its own as 403s arrive. */
    val capabilities: StateFlow<Capabilities>

    /**
     * Learns what the credential may do — an OAuth client's scopes from its own key entry, a
     * personal token's owner and expiry from its — and publishes it in [capabilities].
     */
    suspend fun refreshCapabilities(): Capabilities

    // Devices
    suspend fun listDevices(filters: List<Pair<String, String>> = emptyList()): Listing<ApiDevice>
    /** One device with every field (routes included), falling back to the default set. */
    suspend fun getDevice(deviceId: String): ApiDevice
    suspend fun deviceRoutes(deviceId: String): DeviceRoutes
    suspend fun setDeviceRoutes(deviceId: String, routes: List<String>): DeviceRoutes
    suspend fun setDeviceAuthorized(deviceId: String, authorized: Boolean)
    suspend fun renameDevice(deviceId: String, name: String)
    suspend fun setDeviceTags(deviceId: String, tags: List<String>)
    suspend fun setDeviceKeyExpiryDisabled(deviceId: String, disabled: Boolean)
    suspend fun expireDevice(deviceId: String)
    suspend fun deleteDevice(deviceId: String)
    suspend fun setDeviceIpv4(deviceId: String, ipv4: String): Unit = unsupported(BackendFeature.DEVICE_IPV4)
    /** Back to the name the server derives from the OS hostname: the empty name [renameDevice] refuses. */
    suspend fun resetDeviceName(deviceId: String): Unit = unsupported(BackendFeature.DEVICES)

    // Keys
    /** Every key of the tailnet the credential may see (`all=true`), of every type. */
    suspend fun listKeys(): Listing<ApiKey>
    suspend fun getKey(keyId: String): ApiKey
    /** The answer carries the secret, once. */
    suspend fun createAuthKey(request: AuthKeyRequest): ApiKey
    suspend fun deleteKey(keyId: String)

    // Users
    suspend fun listUsers(type: UserListType = UserListType.ALL): Listing<ApiUser>
    suspend fun getUser(userId: String): ApiUser
    suspend fun setUserRole(userId: String, role: UserRole): Unit = unsupported(BackendFeature.USER_ROLES)
    suspend fun approveUser(userId: String): Unit = unsupported(BackendFeature.USER_ROLES)
    suspend fun suspendUser(userId: String): Unit = unsupported(BackendFeature.USER_ROLES)
    suspend fun restoreUser(userId: String): Unit = unsupported(BackendFeature.USER_ROLES)
    suspend fun deleteUser(userId: String): Unit = unsupported(BackendFeature.USER_ROLES)

    // Trust credentials and invites
    /** An OAuth client; the answer carries its secret (`key`), once. */
    suspend fun createOAuthClient(request: OAuthClientRequest): ApiKey = unsupported(BackendFeature.OAUTH_CLIENTS)
    /** Open invites to join the tailnet; accepted ones are gone from it. */
    suspend fun listUserInvites(): Listing<ApiUserInvite> = unsupported(BackendFeature.USER_INVITES)
    /** Emailed when [email] is set; the answer carries the link when the server makes one. Needs a personal token. */
    suspend fun createUserInvite(email: String?, role: UserRole): ApiUserInvite = unsupported(BackendFeature.USER_INVITES)
    suspend fun resendUserInvite(inviteId: String): Unit = unsupported(BackendFeature.USER_INVITES)
    suspend fun deleteUserInvite(inviteId: String): Unit = unsupported(BackendFeature.USER_INVITES)
    suspend fun listDeviceInvites(deviceId: String): Listing<ApiDeviceInvite> = unsupported(BackendFeature.DEVICE_INVITES)
    /** Shares [deviceId] with someone outside the tailnet. Needs a personal token. */
    suspend fun createDeviceInvite(deviceId: String, request: DeviceInviteRequest): ApiDeviceInvite = unsupported(BackendFeature.DEVICE_INVITES)
    suspend fun deleteDeviceInvite(inviteId: String): Unit = unsupported(BackendFeature.DEVICE_INVITES)

    // DNS
    suspend fun dnsConfiguration(): DnsConfiguration = unsupported(BackendFeature.DNS)
    suspend fun setMagicDns(enabled: Boolean): Unit = unsupported(BackendFeature.DNS)
    suspend fun setNameservers(nameservers: List<String>): Unit = unsupported(BackendFeature.DNS)
    suspend fun setSearchPaths(paths: List<String>): Unit = unsupported(BackendFeature.DNS)
    /** One split-DNS domain; null [nameservers] removes it. */
    suspend fun setSplitDnsDomain(domain: String, nameservers: List<String>?): Unit = unsupported(BackendFeature.DNS)

    // Tailnet settings
    suspend fun tailnetSettings(): TailnetSettings = unsupported(BackendFeature.SETTINGS)
    /** One setting per call, so a missing scope fails that setting alone. */
    suspend fun updateTailnetSetting(key: TailnetSettingKey, value: Any): TailnetSettings = unsupported(BackendFeature.SETTINGS)

    // Policy
    suspend fun policyFile(): PolicyFile = unsupported(BackendFeature.POLICY)
    /** Tags defined in the policy's tagOwners, sorted. */
    suspend fun policyTags(): List<String> = unsupported(BackendFeature.POLICY)
    /** Checks a candidate policy (HuJSON) on the server and runs its tests; nothing is saved. */
    suspend fun validatePolicy(text: String): PolicyValidation = unsupported(BackendFeature.POLICY)
    /** The rules of the policy [text] that match a user or an ip:port; nothing is saved. */
    suspend fun previewPolicy(text: String, type: PolicyPreviewType, previewFor: String): PolicyPreview = unsupported(BackendFeature.POLICY)
    /**
     * Replaces the policy with [text], only while the server still holds the version whose
     * ETag is [ifMatch]: a 412 ([AdminApiException.PreconditionFailed]) when someone changed it.
     */
    suspend fun setPolicyFile(text: String, ifMatch: String): PolicyFile = unsupported(BackendFeature.POLICY)

    /** Replaces the whole DNS configuration (/dns/configuration); the older endpoints are the methods above. */
    suspend fun setDnsConfiguration(config: DnsConfiguration): DnsConfiguration = unsupported(BackendFeature.DNS_CONFIGURATION)

    // Webhooks
    suspend fun listWebhooks(): Listing<ApiWebhook> = unsupported(BackendFeature.WEBHOOKS)
    /** The answer carries the signing secret, once. */
    suspend fun createWebhook(url: String, providerType: String, subscriptions: List<String>): ApiWebhook = unsupported(BackendFeature.WEBHOOKS)
    suspend fun updateWebhookSubscriptions(endpointId: String, subscriptions: List<String>): ApiWebhook = unsupported(BackendFeature.WEBHOOKS)
    suspend fun deleteWebhook(endpointId: String): Unit = unsupported(BackendFeature.WEBHOOKS)
    suspend fun testWebhook(endpointId: String): Unit = unsupported(BackendFeature.WEBHOOKS)
    suspend fun rotateWebhookSecret(endpointId: String): ApiWebhook = unsupported(BackendFeature.WEBHOOKS)

    // Services
    suspend fun listServices(): Listing<ApiService> = unsupported(BackendFeature.SERVICES)
    /** Null when the tailnet has no such service. */
    suspend fun getService(name: String): ApiService? = unsupported(BackendFeature.SERVICES)
    /**
     * Creates the service or replaces its definition. [pathName] is the service's current name:
     * a different [ApiService.name] renames it.
     */
    suspend fun putService(service: ApiService, pathName: String = service.name): Unit = unsupported(BackendFeature.SERVICES)
    suspend fun deleteService(name: String): Unit = unsupported(BackendFeature.SERVICES)
    suspend fun serviceHosts(name: String): Listing<ApiServiceHost> = unsupported(BackendFeature.SERVICES)
    suspend fun setServiceHostApproved(name: String, deviceId: String, approved: Boolean): Unit = unsupported(BackendFeature.SERVICES)

    // Logs
    /** The configuration audit log between two RFC 3339 instants, narrowed on the server by [filters]. */
    suspend fun auditLog(start: String, end: String, filters: AuditLogFilters = AuditLogFilters()): Listing<ApiAuditLogEntry> =
        unsupported(BackendFeature.AUDIT_LOGS)
}

fun unsupported(feature: BackendFeature): Nothing = throw AdminApiException.Unsupported(feature)

/**
 * Collects the `x-tailscale-request-id` of every call made inside it, for the admin audit log:
 * `withContext(RequestIds()) { backend.deleteDevice(id) }` and then read [ids]. A backend adds to
 * it if one is in the coroutine context and does nothing otherwise.
 */
class RequestIds : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestIds>

    private val list = mutableListOf<String>()
    val ids: List<String> get() = synchronized(list) { list.toList() }
    fun add(id: String) = synchronized(list) { list += id }
}
