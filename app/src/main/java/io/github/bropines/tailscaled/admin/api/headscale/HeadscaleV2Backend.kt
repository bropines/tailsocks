package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.AdminCredential
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.api.HttpTransport
import io.github.bropines.tailscaled.admin.api.Listing
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyValidation
import io.github.bropines.tailscaled.admin.api.RetryPolicy
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.UserListType
import io.github.bropines.tailscaled.admin.api.unsupported
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Headscale 0.30+: the Tailscale-compatible subset at /api/v2 — same paths, same JSON, tailnet
 * `-` — through [TailscaleBackend] itself, with Headscale's differences handled here:
 *
 * - v2 devices have no online state; it comes from /api/v1's `online` (0.30 keeps v1), and
 *   without that from `lastSeen`, which v2 sends only for an offline device.
 * - A user list asked for `type=all` comes back empty (Headscale filters for "member"), so
 *   it is asked for without a type.
 * - De-authorizing, clearing tags and switching key expiry back on are accepted by the server
 *   as no-ops; here they refuse before anything is sent, so nobody believes they happened.
 * - Expiring a device, registering one, users' create/rename/delete, untagged keys for a
 *   chosen user and the server's API keys go through /api/v1, which v2 does not cover.
 * - The policy is written with If-Match; a server with `policy.mode: file` refuses, which
 *   the settings' `aclsExternallyManagedOn` tells in advance. Settings are read-only (PATCH 501).
 */
class HeadscaleV2Backend(
    apiKey: String,
    transport: HttpTransport,
    baseUrl: String,
    retry: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    io: CoroutineDispatcher = Dispatchers.IO,
    log: (String) -> Unit = {},
) : AdminBackend, HeadscaleAdmin {

    override val kind: BackendKind = BackendKind.HEADSCALE_V2

    private val ownPrefix = HsApiKey.listedPrefixOf(apiKey)
    private val ts = TailscaleBackend(
        credential = AdminCredential.HeadscaleKey(apiKey),
        transport = transport,
        baseUrl = baseUrl,
        tailnet = "-",
        retry = retry,
        clock = clock,
        sleeper = sleeper,
        io = io,
        log = log,
    )
    private val http = HeadscaleHttp(baseUrl, apiKey, transport, retry, sleeper, io, log)
    internal val v1 = HeadscaleV1Api(http, V1Level.LATEST)

    private val serverState = MutableStateFlow(HeadscaleServer(kind, ownApiKeyPrefix = ownPrefix, canRejectRegistration = true))
    override val server: StateFlow<HeadscaleServer> = serverState.asStateFlow()

    private val caps = MutableStateFlow(capabilitiesFor(PolicyMode.UNKNOWN, null))
    override val capabilities: StateFlow<Capabilities> = caps.asStateFlow()

    private fun capabilitiesFor(mode: PolicyMode, expires: String?) = Capabilities(
        backend = kind,
        credential = CredentialKind.HEADSCALE_API_KEY,
        features = FEATURES + if (mode != PolicyMode.FILE) setOf(BackendFeature.POLICY_WRITE) else emptySet(),
        access = if (mode == PolicyMode.FILE) mapOf(AdminArea.POLICY to Access.READ) else emptyMap(),
        defaultAccess = Access.WRITE,
        ownKeyId = ownPrefix,
        credentialExpires = expires,
    )

    // ---------------------------------------------------------------- server

    override suspend fun refreshCapabilities(): Capabilities {
        refreshServer()
        val own = ownPrefix?.let { p -> runCatching { v1.apiKeys().items.firstOrNull { it.prefix == p } }.getOrNull() }
        caps.value = capabilitiesFor(serverState.value.policyMode, own?.expiration?.takeUnless(::isZeroTime))
        return caps.value
    }

    override suspend fun refreshServer(): HeadscaleServer {
        val version = v1.version()
        // The settings say where the policy lives: aclsExternallyManagedOn is policy.mode file.
        val settings = runCatching { settings() }.getOrNull()
        val mode = when (settings?.aclsExternallyManagedOn) {
            true -> PolicyMode.FILE
            false -> PolicyMode.DATABASE
            null -> serverState.value.policyMode
        }
        serverState.update {
            it.copy(version = version ?: it.version, policyMode = mode, nodeKeyDays = settings?.devicesKeyDurationDays ?: it.nodeKeyDays)
        }
        caps.update { capabilitiesFor(mode, it.credentialExpires) }
        return serverState.value
    }

    private suspend fun settings(): TailnetSettings =
        http.call("GET", "/api/v2/tailnet/-/settings", AdminArea.SETTINGS).decode(TailnetSettings.serializer(), "tailnet settings")

    private fun learnPolicyMode(mode: PolicyMode) {
        if (serverState.value.policyMode == mode) return
        serverState.update { it.copy(policyMode = mode) }
        caps.update { capabilitiesFor(mode, it.credentialExpires) }
    }

    // ---------------------------------------------------------------- devices

    override suspend fun listDevices(filters: List<Pair<String, String>>): Listing<ApiDevice> {
        val list = ts.listDevices(filters)
        val online = runCatching { v1.nodes().items.associate { it.id to it.online } }.getOrNull()
        return list.copy(items = list.items.map { adapt(it, online?.get(it.pathId)) })
    }

    override suspend fun getDevice(deviceId: String): ApiDevice {
        val device = ts.getDevice(deviceId)
        val online = runCatching { v1.node(deviceId).online }.getOrNull()
        return adapt(device, online)
    }

    /** Online from v1 when it answered, else from v2's `lastSeen` (sent only while offline); no owner for tagged devices. */
    private fun adapt(d: ApiDevice, online: Boolean?): ApiDevice = d.copy(
        connectedToControl = d.connectedToControl ?: online ?: (d.lastSeen == null),
        user = d.user?.takeUnless { d.tags.isNotEmpty() || it == HsUser.TAGGED_DEVICES || it.isBlank() },
    )

    override suspend fun deviceRoutes(deviceId: String): DeviceRoutes = ts.deviceRoutes(deviceId)

    override suspend fun setDeviceRoutes(deviceId: String, routes: List<String>): DeviceRoutes = ts.setDeviceRoutes(deviceId, routes)

    override suspend fun setDeviceAuthorized(deviceId: String, authorized: Boolean) {
        // The server answers 400 to false and does nothing for true: every node is authorized.
        if (!authorized) unsupported(BackendFeature.DEVICE_DEAUTHORIZE)
    }

    override suspend fun renameDevice(deviceId: String, name: String) = ts.renameDevice(deviceId, name)

    override suspend fun setDeviceTags(deviceId: String, tags: List<String>) {
        // An empty list is accepted and ignored by the server: tags cannot be taken off.
        if (tags.isEmpty()) unsupported(BackendFeature.DEVICE_UNTAG)
        ts.setDeviceTags(deviceId, tags)
    }

    override suspend fun setDeviceKeyExpiryDisabled(deviceId: String, disabled: Boolean) {
        // Switching expiry back on is accepted and ignored: Headscale keeps no lifetime to restore.
        if (!disabled) unsupported(BackendFeature.DEVICE_KEY_EXPIRY_ENABLE)
        ts.setDeviceKeyExpiryDisabled(deviceId, true)
    }

    override suspend fun expireDevice(deviceId: String) {
        v1.expireNode(deviceId)
    }

    override suspend fun deleteDevice(deviceId: String) = ts.deleteDevice(deviceId)

    // ---------------------------------------------------------------- keys

    override suspend fun listKeys(): Listing<ApiKey> = ts.listKeys()

    override suspend fun getKey(keyId: String): ApiKey = ts.getKey(keyId)

    /**
     * A tagged key through v2, which also keeps its description. An untagged one belongs to a
     * user: v2 can only make it for the API key's own user, so a chosen user goes through v1.
     */
    override suspend fun createAuthKey(request: AuthKeyRequest): ApiKey {
        if (request.tags.isNotEmpty()) return ts.createAuthKey(request)
        if (request.user == null) throw AdminApiException.BadRequest(400, null, "an untagged key belongs to a user; choose one")
        val user = v1.user(request.user)
        val created = v1.createPreAuthKey(user, request.reusable, request.ephemeral, clock() + request.expirySeconds * 1000, emptyList())
        return created.toCreatedKey(clock())
    }

    override suspend fun deleteKey(keyId: String) = ts.deleteKey(keyId)

    // ---------------------------------------------------------------- users

    override suspend fun listUsers(type: UserListType): Listing<ApiUser> =
        http.call("GET", "/api/v2/tailnet/-/users", AdminArea.USERS).decodeList(ApiUser.serializer(), "users", "user")

    override suspend fun getUser(userId: String): ApiUser = ts.getUser(userId)

    override suspend fun deleteUser(userId: String) = v1.deleteUser(userId)

    // ---------------------------------------------------------------- policy

    override suspend fun policyFile(): PolicyFile = ts.policyFile()

    override suspend fun policyTags(): List<String> = PolicyText.tags(ts.policyFile().text)

    override suspend fun setPolicyFile(text: String, ifMatch: String): PolicyFile {
        if (serverState.value.policyMode == PolicyMode.FILE) unsupported(BackendFeature.POLICY_WRITE)
        val resp = try {
            http.call(
                "POST", "/api/v2/tailnet/-/acl", AdminArea.POLICY, body = text, accept = "application/hujson",
                headers = buildMap {
                    put("Content-Type", "application/hujson")
                    put("If-Match", ifMatch)
                },
            )
        } catch (e: AdminApiException.BadRequest) {
            if (HeadscaleV1Api.isPolicyUpdateDisabled(e)) {
                learnPolicyMode(PolicyMode.FILE)
                unsupported(BackendFeature.POLICY_WRITE)
            }
            throw e
        }
        learnPolicyMode(PolicyMode.DATABASE)
        return PolicyFile(resp.body, resp.header("ETag"))
    }

    override suspend fun checkPolicy(text: String) = v1.checkPolicy(text)

    /** v2 has no /acl/validate; /api/v1's check parses the policy against the server's users and nodes. */
    override suspend fun validatePolicy(text: String): PolicyValidation = PolicyChecks.byServer { v1.checkPolicy(text) }

    // ---------------------------------------------------------------- Headscale's own

    override suspend fun registerNode(authId: String, userName: String): ApiDevice = v1.register(authId, userName).toDevice()

    override suspend fun rejectRegistration(authId: String) = v1.rejectAuth(authId)

    override suspend fun createUser(name: String, displayName: String?, email: String?): ApiUser =
        v1.createUser(name.trim(), displayName, email).toApiUser(null)

    override suspend fun renameUser(userId: String, newName: String): ApiUser = v1.renameUser(userId, newName.trim()).toApiUser(null)

    override suspend fun listApiKeys(): Listing<HsApiKey> = v1.apiKeys()

    override suspend fun createApiKey(expiresAtMs: Long): String = v1.createApiKey(expiresAtMs)

    override suspend fun expireApiKey(listedPrefix: String) = v1.expireApiKey(listedPrefix)

    companion object {
        internal val FEATURES = HeadscaleV1Backend.BASE_FEATURES + BackendFeature.DEVICE_KEY_EXPIRY_DISABLE
    }
}
