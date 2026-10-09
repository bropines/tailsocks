package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AdminBackend
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
 * Headscale 0.25–0.29 through its REST API at /api/v1, adapted to the console's model.
 *
 * - Nodes are devices: given name as the name, `online` as the online state, no OS or client
 *   version (v1 has none). Tagged nodes have no owner — 0.28+ lists them under a synthetic
 *   "tagged-devices" user, which is not shown as one.
 * - Every node is authorized at registration; there is no de-authorizing, no clearing tags
 *   (tags are the node's identity) and no switching key expiry back on. Those calls refuse
 *   before anything is sent, and [capabilities] leaves them out so they are not offered.
 * - Pre-auth keys are the auth keys; revoking one expires it. An untagged one needs a user.
 * - The API key is all-access: "read-only" is the app's own promise, nothing the key enforces.
 * - The release is learned from `GET /version` (0.27+) or by probing; [V1Level] maps it to
 *   the paths and fields that release takes.
 */
class HeadscaleV1Backend(
    apiKey: String,
    transport: HttpTransport,
    baseUrl: String,
    initialVersion: HeadscaleVersion? = null,
    retry: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    io: CoroutineDispatcher = Dispatchers.IO,
    log: (String) -> Unit = {},
) : AdminBackend, HeadscaleAdmin {

    override val kind: BackendKind = BackendKind.HEADSCALE_V1

    private val ownPrefix = HsApiKey.listedPrefixOf(apiKey)
    internal val api = HeadscaleV1Api(
        HeadscaleHttp(baseUrl, apiKey, transport, retry, sleeper, io, log),
        V1Level(initialVersion ?: HeadscaleVersion.V0_26),
    )

    private val serverState = MutableStateFlow(serverFor(initialVersion, PolicyMode.UNKNOWN))
    override val server: StateFlow<HeadscaleServer> = serverState.asStateFlow()

    private val caps = MutableStateFlow(capabilitiesFor(api.level, PolicyMode.UNKNOWN, null))
    override val capabilities: StateFlow<Capabilities> = caps.asStateFlow()

    private fun serverFor(version: HeadscaleVersion?, mode: PolicyMode) = HeadscaleServer(
        kind = kind,
        version = version,
        policyMode = mode,
        ownApiKeyPrefix = ownPrefix,
        canRejectRegistration = api.level.authApproveReject,
        authIdPrefixed = api.level.authRegister,
    )

    private fun capabilitiesFor(level: V1Level, mode: PolicyMode, expires: String?) = Capabilities(
        backend = kind,
        credential = CredentialKind.HEADSCALE_API_KEY,
        features = buildSet {
            addAll(BASE_FEATURES)
            if (level.disableExpiry) add(BackendFeature.DEVICE_KEY_EXPIRY_DISABLE)
            if (mode != PolicyMode.FILE) add(BackendFeature.POLICY_WRITE)
        },
        access = if (mode == PolicyMode.FILE) mapOf(AdminArea.POLICY to Access.READ) else emptyMap(),
        defaultAccess = Access.WRITE,
        ownKeyId = ownPrefix,
        credentialExpires = expires,
    )

    // ---------------------------------------------------------------- server

    override suspend fun refreshCapabilities(): Capabilities {
        refreshServer()
        val own = ownPrefix?.let { p -> runCatching { api.apiKeys().items.firstOrNull { it.prefix == p } }.getOrNull() }
        caps.value = capabilitiesFor(api.level, serverState.value.policyMode, own?.expiration?.takeUnless(::isZeroTime))
        return caps.value
    }

    override suspend fun refreshServer(): HeadscaleServer {
        // No /version before 0.27: what an earlier probe found stands, else the routes API decides.
        val version = api.version() ?: serverState.value.version?.takeIf { it < HeadscaleVersion(0, 27, 0, "") } ?: api.probeLegacy()
        api.level = V1Level(version)
        serverState.update { serverFor(version, it.policyMode) }
        caps.update { capabilitiesFor(api.level, serverState.value.policyMode, it.credentialExpires) }
        return serverState.value
    }

    /** Which backend the server needs (v2 where it has the Tailscale-compatible API) and its release. */
    suspend fun detectServer(): Pair<BackendKind, HeadscaleVersion> = api.detect()

    private fun learnPolicyMode(mode: PolicyMode) {
        if (serverState.value.policyMode == mode) return
        serverState.update { it.copy(policyMode = mode) }
        caps.update { capabilitiesFor(api.level, mode, it.credentialExpires) }
    }

    // ---------------------------------------------------------------- devices

    override suspend fun listDevices(filters: List<Pair<String, String>>): Listing<ApiDevice> {
        val nodes = api.nodes()
        return Listing(nodes.items.map { it.toDevice() }, nodes.issues)
    }

    override suspend fun getDevice(deviceId: String): ApiDevice {
        val device = api.node(deviceId).toDevice()
        if (!api.level.routesApi) return device
        val routes = deviceRoutes(deviceId)
        return device.copy(advertisedRoutes = routes.advertisedRoutes, enabledRoutes = routes.enabledRoutes)
    }

    override suspend fun deviceRoutes(deviceId: String): DeviceRoutes {
        if (api.level.routesApi) {
            val rows = api.nodeRoutes(deviceId)
            return DeviceRoutes(rows.filter { it.advertised }.map { it.prefix }.distinct(), rows.filter { it.enabled }.map { it.prefix }.distinct())
        }
        val node = api.node(deviceId)
        return DeviceRoutes(node.availableRoutes.orEmpty(), node.approvedRoutes.orEmpty())
    }

    override suspend fun setDeviceRoutes(deviceId: String, routes: List<String>): DeviceRoutes {
        if (!api.level.routesApi) {
            val node = api.approveRoutes(deviceId, routes)
            return DeviceRoutes(node.availableRoutes.orEmpty(), node.approvedRoutes.orEmpty())
        }
        // 0.25: one switch per advertised route; an exit route counts for both families.
        val want = routes.toSet().let { if ("0.0.0.0/0" in it || "::/0" in it) it + "0.0.0.0/0" + "::/0" else it }
        for (row in api.nodeRoutes(deviceId)) {
            val on = row.prefix in want
            if (row.enabled != on) api.setRouteEnabled(row.id, on)
        }
        return deviceRoutes(deviceId)
    }

    override suspend fun setDeviceAuthorized(deviceId: String, authorized: Boolean) {
        // Every Headscale node is authorized the moment it registers.
        if (!authorized) unsupported(BackendFeature.DEVICE_DEAUTHORIZE)
    }

    override suspend fun renameDevice(deviceId: String, name: String) {
        require(name.isNotBlank()) { "a blank name is not a name; not sent" }
        api.renameNode(deviceId, name.trim())
    }

    override suspend fun setDeviceTags(deviceId: String, tags: List<String>) {
        // Tags are a node's identity: they can be changed, never taken away.
        if (tags.isEmpty()) unsupported(BackendFeature.DEVICE_UNTAG)
        api.setTags(deviceId, tags)
    }

    override suspend fun setDeviceKeyExpiryDisabled(deviceId: String, disabled: Boolean) {
        if (!disabled) unsupported(BackendFeature.DEVICE_KEY_EXPIRY_ENABLE)
        if (!api.level.disableExpiry) unsupported(BackendFeature.DEVICE_KEY_EXPIRY_DISABLE)
        api.expireNode(deviceId, disableExpiry = true)
    }

    override suspend fun expireDevice(deviceId: String) {
        api.expireNode(deviceId)
    }

    override suspend fun deleteDevice(deviceId: String) = api.deleteNode(deviceId)

    // ---------------------------------------------------------------- keys

    /** Pre-0.28 lists carry each key's secret, which expiring one needs; kept here only. */
    @Volatile
    private var lastKeys: List<HsPreAuthKey> = emptyList()

    override suspend fun listKeys(): Listing<ApiKey> {
        val keys = api.preAuthKeys { api.users().items }
        lastKeys = keys.items
        val now = clock()
        return Listing(keys.items.map { it.toKey(now) }, keys.issues)
    }

    override suspend fun getKey(keyId: String): ApiKey =
        listKeys().items.firstOrNull { it.id == keyId } ?: throw AdminApiException.NotFound(null, "auth key $keyId not found")

    override suspend fun createAuthKey(request: AuthKeyRequest): ApiKey {
        val user = request.user?.let { id -> api.users().items.firstOrNull { it.id == id } ?: throw AdminApiException.NotFound(null, "user $id not found") }
        if (user == null && request.tags.isEmpty()) {
            throw AdminApiException.BadRequest(400, null, "an untagged key belongs to a user; choose one")
        }
        val created = api.createPreAuthKey(user, request.reusable, request.ephemeral, clock() + request.expirySeconds * 1000, request.tags)
        return created.toCreatedKey(clock())
    }

    override suspend fun deleteKey(keyId: String) {
        val key = lastKeys.firstOrNull { it.id == keyId }
            ?: api.preAuthKeys { api.users().items }.items.also { lastKeys = it }.firstOrNull { it.id == keyId }
            ?: if (api.level.preAuthKeyById) HsPreAuthKey(id = keyId) else throw AdminApiException.NotFound(null, "auth key $keyId not found")
        api.expirePreAuthKey(key)
    }

    // ---------------------------------------------------------------- users

    override suspend fun listUsers(type: UserListType): Listing<ApiUser> {
        val users = api.users()
        val nodes = runCatching { api.nodes().items }.getOrNull()
        return Listing(users.items.map { it.toApiUser(nodes) }, users.issues)
    }

    override suspend fun getUser(userId: String): ApiUser = api.user(userId).toApiUser(null)

    override suspend fun deleteUser(userId: String) = api.deleteUser(userId)

    // ---------------------------------------------------------------- policy

    override suspend fun policyFile(): PolicyFile {
        val text = api.policy()?.policy?.takeIf { it.isNotBlank() } ?: PolicyText.DEFAULT
        return PolicyFile(text, PolicyText.etag(text))
    }

    override suspend fun policyTags(): List<String> = PolicyText.tags(policyFile().text)

    /**
     * Writes the policy. v1 has no ETag of its own: [policyFile] gives the SHA-256 of the text
     * (what /api/v2 uses), and [ifMatch] is compared with a fresh read right before the write.
     * That leaves the moment between that read and the write unguarded — the server's own
     * If-Match on /api/v2 has no such gap. A server whose policy is a file refuses; that is
     * remembered and thrown as [BackendFeature.POLICY_WRITE] unsupported.
     */
    override suspend fun setPolicyFile(text: String, ifMatch: String): PolicyFile {
        if (serverState.value.policyMode == PolicyMode.FILE) unsupported(BackendFeature.POLICY_WRITE)
        if (ifMatch != "*" && policyFile().etag != ifMatch) {
            throw AdminApiException.PreconditionFailed(null, "the policy changed since it was read")
        }
        val saved = try {
            api.setPolicy(text)
        } catch (e: AdminApiException) {
            if (HeadscaleV1Api.isPolicyUpdateDisabled(e)) {
                learnPolicyMode(PolicyMode.FILE)
                unsupported(BackendFeature.POLICY_WRITE)
            }
            throw e
        }
        learnPolicyMode(PolicyMode.DATABASE)
        val now = saved.policy.ifBlank { text }
        return PolicyFile(now, PolicyText.etag(now))
    }

    override suspend fun checkPolicy(text: String) {
        if (!api.level.policyCheck) unsupported(BackendFeature.POLICY)
        api.checkPolicy(text)
    }

    /**
     * `/policy/check` from 0.29: the server parses the policy against its users and nodes.
     * Older releases have no check; the text is then only parsed here, and the server's own
     * check runs when it is saved.
     */
    override suspend fun validatePolicy(text: String): PolicyValidation =
        if (api.level.policyCheck) PolicyChecks.byServer { api.checkPolicy(text) } else PolicyChecks.locally(text)

    // ---------------------------------------------------------------- Headscale's own

    override suspend fun registerNode(authId: String, userName: String): ApiDevice = api.register(authId, userName).toDevice()

    override suspend fun rejectRegistration(authId: String) {
        if (!api.level.authApproveReject) unsupported(BackendFeature.HEADSCALE_ADMIN)
        api.rejectAuth(authId)
    }

    override suspend fun createUser(name: String, displayName: String?, email: String?): ApiUser =
        api.createUser(name.trim(), displayName, email).toApiUser(null)

    override suspend fun renameUser(userId: String, newName: String): ApiUser = api.renameUser(userId, newName.trim()).toApiUser(null)

    override suspend fun listApiKeys(): Listing<HsApiKey> = api.apiKeys()

    override suspend fun createApiKey(expiresAtMs: Long): String = api.createApiKey(expiresAtMs)

    override suspend fun expireApiKey(listedPrefix: String) = api.expireApiKey(listedPrefix)

    companion object {
        internal val BASE_FEATURES = setOf(
            BackendFeature.DEVICES, BackendFeature.DEVICE_ROUTES, BackendFeature.KEYS, BackendFeature.USERS,
            BackendFeature.POLICY, BackendFeature.DEVICE_EXPIRE, BackendFeature.AUTH_KEY_OWNER, BackendFeature.HEADSCALE_ADMIN,
        )
    }
}
