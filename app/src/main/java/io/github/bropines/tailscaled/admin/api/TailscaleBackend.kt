package io.github.bropines.tailscaled.admin.api

import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Locale

/** How reads back off. Writes are retried only on a 429, under the same limits. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    /** A Retry-After longer than this is not waited out: the call fails as rate-limited. */
    val maxRetryAfterMs: Long = 30_000,
) {
    fun backoff(attempt: Int): Long = (baseDelayMs shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(maxDelayMs)
}

/**
 * The Tailscale Admin API, v2, as the OpenAPI schema describes it.
 *
 * - The tailnet in the path is `-`, the credential's own tailnet, unless a Tailnet ID is given;
 *   the MagicDNS suffix the old console used there is not an identifier the API documents.
 * - The device list is fetched with the default fields only: `fields=all` on the list answers
 *   404 for the whole tailnet when shared-in devices exist (a live API bug). Routes come from
 *   the per-device call, where `fields=all` is asked for and dropped again if refused.
 * - Lists are decoded element by element; what fails to decode is reported in the Listing.
 * - Reads retry on 429 (honouring Retry-After), 502–504 and a dropped connection. Writes go
 *   out once over one route and are retried only on 429 and on an expired OAuth token (401),
 *   both answered before anything was applied.
 * - Every answer's `x-tailscale-request-id` goes to [log] and to a [RequestIds] in context.
 */
class TailscaleBackend(
    private val credential: AdminCredential,
    private val transport: HttpTransport,
    baseUrl: String = DEFAULT_BASE_URL,
    tailnet: String = "-",
    initialCapabilities: Capabilities? = null,
    private val retry: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    /** Where OAuth access tokens outlive this backend: a console opened again reuses its hour. */
    private val tokenCache: OAuthTokenCache? = null,
) : AdminBackend {

    override val kind: BackendKind = BackendKind.TAILSCALE

    private val api = baseUrl.trimEnd('/') + "/api/v2"
    private val tn = "/tailnet/" + Urls.seg(tailnet.trim().ifBlank { "-" })

    private val caps = MutableStateFlow(
        initialCapabilities ?: Capabilities(
            backend = kind,
            credential = credential.kind,
            features = FEATURES,
            defaultAccess = Access.UNKNOWN,
            ownKeyId = credential.keyId,
        )
    )
    override val capabilities: StateFlow<Capabilities> = caps.asStateFlow()

    // ---------------------------------------------------------------- capabilities

    override suspend fun refreshCapabilities(): Capabilities {
        val keyId = credential.keyId
        val own = keyId?.let {
            try {
                decodeObject<ApiKey>(exchange("GET", "$tn/keys/${Urls.seg(it)}", area = null), "key")
            } catch (e: AdminApiException) {
                if (e is AdminApiException.Network || e is AdminApiException.Unauthorized) throw e
                log("own key $it not readable: ${e.message}")
                null
            }
        }
        caps.update { current ->
            val scopes = own?.scopes.orEmpty()
            val base = if (scopes.isNotEmpty()) Capabilities.fromScopes(kind, FEATURES, scopes).copy(credential = credential.kind)
            else current
            base.copy(
                ownKeyId = keyId,
                ownUserId = own?.userId?.takeIf { credential is AdminCredential.ApiToken && it.isNotBlank() } ?: base.ownUserId,
                credentialExpires = own?.expires ?: base.credentialExpires,
                credentialTags = own?.tags ?: base.credentialTags,
            )
        }
        return caps.value
    }

    // ---------------------------------------------------------------- devices

    /** Set once the list was refused with fields=all: this session asks for the plain list from then on. */
    @Volatile
    private var plainDeviceList = false

    /**
     * The list with every field: routes and connectivity in one call, not one call per device.
     * A live API bug answers the whole list 404 with fields=all once a device is shared in; the
     * plain list follows, and stays for the rest of the session.
     */
    override suspend fun listDevices(filters: List<Pair<String, String>>): Listing<ApiDevice> {
        val query = filters.filter { it.first != "fields" }
        if (!plainDeviceList) {
            try {
                return decodeList(exchange("GET", "$tn/devices", AdminArea.DEVICES, query = query + ("fields" to "all")), "devices", "device")
            } catch (e: AdminApiException) {
                if (e !is AdminApiException.NotFound && e !is AdminApiException.BadRequest && e !is AdminApiException.Server) throw e
                log("device list refused with fields=all (${e.javaClass.simpleName}); asking without")
                plainDeviceList = true
            }
        }
        return decodeList(exchange("GET", "$tn/devices", AdminArea.DEVICES, query = query), "devices", "device")
    }

    override suspend fun getDevice(deviceId: String): ApiDevice {
        val path = "/device/${Urls.seg(deviceId)}"
        val resp = try {
            exchange("GET", path, AdminArea.DEVICES, query = listOf("fields" to "all"))
        } catch (e: AdminApiException) {
            // fields=all has been refused for whole lists; never let it cost the device itself.
            if (e !is AdminApiException.NotFound && e !is AdminApiException.BadRequest && e !is AdminApiException.Server) throw e
            exchange("GET", path, AdminArea.DEVICES)
        }
        return decodeObject(resp, "device")
    }

    override suspend fun deviceRoutes(deviceId: String): DeviceRoutes =
        decodeObject(exchange("GET", "/device/${Urls.seg(deviceId)}/routes", AdminArea.ROUTES), "routes")

    override suspend fun setDeviceRoutes(deviceId: String, routes: List<String>): DeviceRoutes =
        decodeObject(
            exchange("POST", "/device/${Urls.seg(deviceId)}/routes", AdminArea.ROUTES, body = buildJsonObject {
                putJsonArray("routes") { routes.forEach { add(JsonPrimitive(it)) } }
            }.toString()),
            "routes"
        )

    override suspend fun setDeviceAuthorized(deviceId: String, authorized: Boolean) {
        exchange("POST", "/device/${Urls.seg(deviceId)}/authorized", AdminArea.DEVICES,
            body = buildJsonObject { put("authorized", authorized) }.toString())
    }

    override suspend fun renameDevice(deviceId: String, name: String) {
        require(name.isNotBlank()) { "a blank name resets the device to its hostname; not sent" }
        exchange("POST", "/device/${Urls.seg(deviceId)}/name", AdminArea.DEVICES,
            body = buildJsonObject { put("name", name.trim()) }.toString())
    }

    override suspend fun setDeviceTags(deviceId: String, tags: List<String>) {
        exchange("POST", "/device/${Urls.seg(deviceId)}/tags", AdminArea.DEVICES, body = buildJsonObject {
            putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        }.toString())
    }

    override suspend fun setDeviceKeyExpiryDisabled(deviceId: String, disabled: Boolean) {
        exchange("POST", "/device/${Urls.seg(deviceId)}/key", AdminArea.DEVICES,
            body = buildJsonObject { put("keyExpiryDisabled", disabled) }.toString())
    }

    override suspend fun expireDevice(deviceId: String) {
        exchange("POST", "/device/${Urls.seg(deviceId)}/expire", AdminArea.DEVICES, body = "")
    }

    override suspend fun deleteDevice(deviceId: String) {
        exchange("DELETE", "/device/${Urls.seg(deviceId)}", AdminArea.DEVICES)
    }

    override suspend fun setDeviceIpv4(deviceId: String, ipv4: String) {
        exchange("POST", "/device/${Urls.seg(deviceId)}/ip", AdminArea.DEVICES,
            body = buildJsonObject { put("ipv4", ipv4) }.toString())
    }

    override suspend fun resetDeviceName(deviceId: String) {
        exchange("POST", "/device/${Urls.seg(deviceId)}/name", AdminArea.DEVICES,
            body = buildJsonObject { put("name", "") }.toString())
    }

    // ---------------------------------------------------------------- keys

    override suspend fun listKeys(): Listing<ApiKey> =
        decodeList(exchange("GET", "$tn/keys", AdminArea.AUTH_KEYS, query = listOf("all" to "true")), "keys", "key")

    override suspend fun getKey(keyId: String): ApiKey =
        decodeObject(exchange("GET", "$tn/keys/${Urls.seg(keyId)}", AdminArea.AUTH_KEYS), "key")

    override suspend fun createAuthKey(request: AuthKeyRequest): ApiKey {
        val body = buildJsonObject {
            put("keyType", "auth")
            if (request.description.isNotBlank()) put("description", request.description.trim())
            put("expirySeconds", request.expirySeconds)
            putJsonObject("capabilities") {
                putJsonObject("devices") {
                    putJsonObject("create") {
                        put("reusable", request.reusable)
                        put("ephemeral", request.ephemeral)
                        put("preauthorized", request.preauthorized)
                        if (request.tags.isNotEmpty()) putJsonArray("tags") { request.tags.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
        }
        return decodeObject(exchange("POST", "$tn/keys", AdminArea.AUTH_KEYS, body = body.toString()), "key")
    }

    override suspend fun deleteKey(keyId: String) {
        exchange("DELETE", "$tn/keys/${Urls.seg(keyId)}", AdminArea.AUTH_KEYS)
    }

    // ---------------------------------------------------------------- users

    override suspend fun listUsers(type: UserListType): Listing<ApiUser> =
        decodeList(exchange("GET", "$tn/users", AdminArea.USERS, query = listOf("type" to type.wire)), "users", "user")

    override suspend fun getUser(userId: String): ApiUser =
        decodeObject(exchange("GET", "/users/${Urls.seg(userId)}", AdminArea.USERS), "user")

    override suspend fun setUserRole(userId: String, role: UserRole) {
        require(role != UserRole.UNKNOWN) { "no such role" }
        exchange("POST", "/users/${Urls.seg(userId)}/role", AdminArea.USERS,
            body = buildJsonObject { put("role", role.wire) }.toString())
    }

    override suspend fun approveUser(userId: String) = userAction(userId, "approve")
    override suspend fun suspendUser(userId: String) = userAction(userId, "suspend")
    override suspend fun restoreUser(userId: String) = userAction(userId, "restore")
    override suspend fun deleteUser(userId: String) = userAction(userId, "delete")

    private suspend fun userAction(userId: String, action: String) {
        exchange("POST", "/users/${Urls.seg(userId)}/$action", AdminArea.USERS, body = "")
    }

    // ---------------------------------------------------------------- trust credentials, invites

    override suspend fun createOAuthClient(request: OAuthClientRequest): ApiKey {
        require(request.scopes.isNotEmpty()) { "an OAuth client needs at least one scope" }
        val body = buildJsonObject {
            put("keyType", "client")
            if (request.description.isNotBlank()) put("description", request.description.trim())
            putJsonArray("scopes") { request.scopes.forEach { add(JsonPrimitive(it)) } }
            if (request.tags.isNotEmpty()) putJsonArray("tags") { request.tags.forEach { add(JsonPrimitive(it)) } }
        }
        return decodeObject(exchange("POST", "$tn/keys", AdminArea.OAUTH_KEYS, body = body.toString()), "key")
    }

    override suspend fun listUserInvites(): Listing<ApiUserInvite> =
        decodeArray(exchange("GET", "$tn/user-invites", AdminArea.USERS), "user invite")

    override suspend fun createUserInvite(email: String?, role: UserRole): ApiUserInvite {
        require(role != UserRole.UNKNOWN && role != UserRole.OWNER) { "no such invite role" }
        val body = JsonArray(listOf(buildJsonObject {
            put("role", role.wire)
            email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
        }))
        val created = decodeArray<ApiUserInvite>(exchange("POST", "$tn/user-invites", AdminArea.USERS, body = body.toString()), "user invite")
        return created.items.firstOrNull() ?: throw AdminApiException.Decode("user invite", null)
    }

    override suspend fun resendUserInvite(inviteId: String) {
        exchange("POST", "/user-invites/${Urls.seg(inviteId)}/resend", AdminArea.USERS, body = "")
    }

    override suspend fun deleteUserInvite(inviteId: String) {
        exchange("DELETE", "/user-invites/${Urls.seg(inviteId)}", AdminArea.USERS)
    }

    override suspend fun listDeviceInvites(deviceId: String): Listing<ApiDeviceInvite> =
        decodeArray(exchange("GET", "/device/${Urls.seg(deviceId)}/device-invites", AdminArea.DEVICE_INVITES), "device invite")

    override suspend fun createDeviceInvite(deviceId: String, request: DeviceInviteRequest): ApiDeviceInvite {
        val body = JsonArray(listOf(buildJsonObject {
            put("multiUse", request.multiUse)
            put("allowExitNode", request.allowExitNode)
            request.email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
        }))
        val created = decodeArray<ApiDeviceInvite>(
            exchange("POST", "/device/${Urls.seg(deviceId)}/device-invites", AdminArea.DEVICE_INVITES, body = body.toString()),
            "device invite"
        )
        return created.items.firstOrNull() ?: throw AdminApiException.Decode("device invite", null)
    }

    override suspend fun deleteDeviceInvite(inviteId: String) {
        exchange("DELETE", "/device-invites/${Urls.seg(inviteId)}", AdminArea.DEVICE_INVITES)
    }

    /** A list the server answers as a bare JSON array (the invite endpoints), element by element. */
    private inline fun <reified T> decodeArray(resp: HttpResponse, what: String, idField: String = "id"): Listing<T> {
        val root = try {
            AppJson.parseToJsonElement(resp.body.ifBlank { "[]" })
        } catch (e: Exception) {
            throw AdminApiException.Decode("$what list", resp.requestId, e)
        }
        val array = root as? JsonArray ?: throw AdminApiException.Decode("$what list", resp.requestId)
        val items = ArrayList<T>(array.size)
        val issues = mutableListOf<DecodeIssue>()
        array.forEachIndexed { index, el ->
            try {
                items += AppJson.decodeFromJsonElement<T>(el)
            } catch (e: Exception) {
                val id = (el as? JsonObject)?.get(idField)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                issues += DecodeIssue(what, index, id, (e.message ?: e.javaClass.simpleName).lineSequence().first().take(200))
            }
        }
        if (issues.isNotEmpty()) log("$what list: ${issues.size} of ${array.size} unreadable")
        return Listing(items, issues)
    }

    // ---------------------------------------------------------------- DNS

    override suspend fun dnsConfiguration(): DnsConfiguration = try {
        decodeObject(exchange("GET", "$tn/dns/configuration", AdminArea.DNS), "DNS configuration")
    } catch (e: AdminApiException.NotFound) {
        // A server without the combined endpoint: the four older ones say the same, and the
        // console stops offering what only the combined one can set.
        caps.update { it.copy(features = it.features - BackendFeature.DNS_CONFIGURATION) }
        legacyDnsConfiguration()
    }

    override suspend fun setDnsConfiguration(config: DnsConfiguration): DnsConfiguration {
        fun resolvers(list: List<DnsResolver>) = JsonArray(list.map { r ->
            buildJsonObject {
                put("address", r.address)
                r.useWithExitNode?.let { put("useWithExitNode", it) }
            }
        })
        val body = buildJsonObject {
            put("nameservers", resolvers(config.nameservers))
            put("splitDNS", JsonObject(config.splitDns.mapValues { (_, v) -> resolvers(v) }))
            putJsonArray("searchPaths") { config.searchPaths.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("preferences") {
                config.preferences.overrideLocalDNS?.let { put("overrideLocalDNS", it) }
                config.preferences.magicDNS?.let { put("magicDNS", it) }
            }
        }
        return decodeObject(exchange("POST", "$tn/dns/configuration", AdminArea.DNS, body = body.toString()), "DNS configuration")
    }

    private suspend fun legacyDnsConfiguration(): DnsConfiguration {
        val prefs = decodeObject<JsonObject>(exchange("GET", "$tn/dns/preferences", AdminArea.DNS), "DNS preferences")
        val ns = decodeObject<JsonObject>(exchange("GET", "$tn/dns/nameservers", AdminArea.DNS), "nameservers")
        val split = decodeObject<JsonObject>(exchange("GET", "$tn/dns/split-dns", AdminArea.DNS), "split DNS")
        val search = decodeObject<JsonObject>(exchange("GET", "$tn/dns/searchpaths", AdminArea.DNS), "search paths")
        fun strings(el: kotlinx.serialization.json.JsonElement?): List<String> =
            (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
        return DnsConfiguration(
            nameservers = strings(ns["dns"]).map { DnsResolver(it) },
            splitDns = split.mapValues { (_, v) -> strings(v).map { DnsResolver(it) } },
            searchPaths = strings(search["searchPaths"]),
            preferences = DnsConfigPreferences(magicDNS = (prefs["magicDNS"] as? JsonPrimitive)?.content == "true"),
        )
    }

    override suspend fun setMagicDns(enabled: Boolean) {
        exchange("POST", "$tn/dns/preferences", AdminArea.DNS, body = buildJsonObject { put("magicDNS", enabled) }.toString())
    }

    override suspend fun setNameservers(nameservers: List<String>) {
        exchange("POST", "$tn/dns/nameservers", AdminArea.DNS, body = buildJsonObject {
            putJsonArray("dns") { nameservers.forEach { add(JsonPrimitive(it)) } }
        }.toString())
    }

    override suspend fun setSearchPaths(paths: List<String>) {
        exchange("POST", "$tn/dns/searchpaths", AdminArea.DNS, body = buildJsonObject {
            putJsonArray("searchPaths") { paths.forEach { add(JsonPrimitive(it)) } }
        }.toString())
    }

    override suspend fun setSplitDnsDomain(domain: String, nameservers: List<String>?) {
        exchange("PATCH", "$tn/dns/split-dns", AdminArea.DNS, body = buildJsonObject {
            if (nameservers == null) put(domain, JsonNull)
            else putJsonArray(domain) { nameservers.forEach { add(JsonPrimitive(it)) } }
        }.toString())
    }

    // ---------------------------------------------------------------- settings

    override suspend fun tailnetSettings(): TailnetSettings =
        decodeObject(exchange("GET", "$tn/settings", AdminArea.SETTINGS), "tailnet settings")

    override suspend fun updateTailnetSetting(key: TailnetSettingKey, value: Any): TailnetSettings {
        val v = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("unsupported value for ${key.wire}")
        }
        val body = JsonObject(mapOf(key.wire to v)).toString()
        return decodeObject(exchange("PATCH", "$tn/settings", key.scopeArea, body = body), "tailnet settings")
    }

    // ---------------------------------------------------------------- policy

    override suspend fun policyFile(): PolicyFile {
        val resp = exchange("GET", "$tn/acl", AdminArea.POLICY, accept = "application/hujson")
        return PolicyFile(resp.body, resp.header("ETag"))
    }

    override suspend fun policyTags(): List<String> {
        val resp = exchange("GET", "$tn/acl", AdminArea.POLICY)
        val fromOwners = runCatching {
            AppJson.parseToJsonElement(resp.body).jsonObject["tagOwners"]?.jsonObject?.keys?.toList()
        }.getOrNull()
        val tags = fromOwners ?: TAG.findAll(resp.body).map { it.groupValues[1] }.toList()
        return tags.filter { it.startsWith("tag:") }.distinct().sorted()
    }

    override suspend fun validatePolicy(text: String): PolicyValidation {
        val resp = try {
            exchange("POST", "$tn/acl/validate", AdminArea.POLICY, body = text, contentType = HUJSON, write = false)
        } catch (e: AdminApiException.BadRequest) {
            // A policy that does not parse may come back as a 400 with the reason in its message.
            return PolicyValidation(false, e.apiMessage)
        }
        return PolicyValidation.parse(resp.body)
    }

    override suspend fun previewPolicy(text: String, type: PolicyPreviewType, previewFor: String): PolicyPreview =
        decodeObject(
            exchange(
                "POST", "$tn/acl/preview", AdminArea.POLICY,
                query = listOf("type" to type.wire, "previewFor" to previewFor),
                body = text, contentType = HUJSON, write = false,
            ),
            "policy preview"
        )

    override suspend fun setPolicyFile(text: String, ifMatch: String): PolicyFile {
        require(ifMatch.isNotBlank()) { "a policy write always carries the ETag it was made against" }
        val resp = exchange(
            "POST", "$tn/acl", AdminArea.POLICY,
            body = text, contentType = HUJSON, accept = HUJSON,
            extraHeaders = mapOf("If-Match" to quotedEtag(ifMatch)),
        )
        return PolicyFile(resp.body, resp.header("ETag"))
    }

    /**
     * Public, from the control plane rather than the API, so it goes without the credential:
     * nothing of the profile's goes to another host. Through the same transport (the profile's
     * proxy) and the same retries as every other read.
     */
    override suspend fun defaultDerpMap(): ApiDerpMap {
        val request = HttpRequest("GET", DERP_MAP_URL, mapOf("Accept" to "application/json"))
        val resp = send(request, write = false, path = "/derpmap/default")
        if (resp.status !in 200..299) throw errorFor(resp, area = null, write = false)
        val map = decodeObject<ApiDerpMap>(resp, "relay map")
        // An answer with no region in it is not a map to plan exclusions from.
        if (map.regions.isEmpty()) throw AdminApiException.Decode("relay map", resp.requestId)
        return map
    }

    // ---------------------------------------------------------------- webhooks

    override suspend fun listWebhooks(): Listing<ApiWebhook> =
        decodeList(exchange("GET", "$tn/webhooks", AdminArea.WEBHOOKS), "webhooks", "webhook", idField = "endpointId")

    override suspend fun createWebhook(url: String, providerType: String, subscriptions: List<String>): ApiWebhook {
        require(subscriptions.isNotEmpty()) { "a webhook needs at least one event" }
        val body = buildJsonObject {
            put("endpointUrl", url)
            if (providerType.isNotBlank()) put("providerType", providerType)
            putJsonArray("subscriptions") { subscriptions.forEach { add(JsonPrimitive(it)) } }
        }
        return decodeObject(exchange("POST", "$tn/webhooks", AdminArea.WEBHOOKS, body = body.toString()), "webhook")
    }

    override suspend fun updateWebhookSubscriptions(endpointId: String, subscriptions: List<String>): ApiWebhook =
        decodeObject(
            exchange("PATCH", "/webhooks/${Urls.seg(endpointId)}", AdminArea.WEBHOOKS, body = buildJsonObject {
                putJsonArray("subscriptions") { subscriptions.forEach { add(JsonPrimitive(it)) } }
            }.toString()),
            "webhook"
        )

    override suspend fun deleteWebhook(endpointId: String) {
        exchange("DELETE", "/webhooks/${Urls.seg(endpointId)}", AdminArea.WEBHOOKS)
    }

    override suspend fun testWebhook(endpointId: String) {
        exchange("POST", "/webhooks/${Urls.seg(endpointId)}/test", AdminArea.WEBHOOKS, body = "")
    }

    override suspend fun rotateWebhookSecret(endpointId: String): ApiWebhook =
        decodeObject(exchange("POST", "/webhooks/${Urls.seg(endpointId)}/rotate", AdminArea.WEBHOOKS, body = ""), "webhook")

    // ---------------------------------------------------------------- services

    override suspend fun listServices(): Listing<ApiService> {
        val resp = try {
            exchange("GET", "$tn/services", AdminArea.SERVICES)
        } catch (e: AdminApiException.NotFound) {
            // The schema's path is /services; the console's older one is still answered by some tailnets.
            exchange("GET", "$tn/vip-services", AdminArea.SERVICES)
        }
        val field = if (hasField(resp, "vipServices")) "vipServices" else "services"
        return decodeList(resp, field, "service", idField = "name")
    }

    override suspend fun getService(name: String): ApiService? = try {
        decodeObject<ApiService>(exchange("GET", "$tn/services/${Urls.seg(name)}", AdminArea.SERVICES), "service")
    } catch (e: AdminApiException.NotFound) {
        null
    }

    override suspend fun putService(service: ApiService, pathName: String) {
        val body = buildJsonObject {
            put("name", service.name)
            service.displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            if (service.addrs.isNotEmpty()) putJsonArray("addrs") { service.addrs.forEach { add(JsonPrimitive(it)) } }
            service.comment?.let { put("comment", it) }
            putJsonArray("ports") { service.ports.forEach { add(JsonPrimitive(it)) } }
            if (service.tags.isNotEmpty()) putJsonArray("tags") { service.tags.forEach { add(JsonPrimitive(it)) } }
        }
        exchange("PUT", "$tn/services/${Urls.seg(pathName)}", AdminArea.SERVICES, body = body.toString())
    }

    override suspend fun deleteService(name: String) {
        exchange("DELETE", "$tn/services/${Urls.seg(name)}", AdminArea.SERVICES)
    }

    override suspend fun serviceHosts(name: String): Listing<ApiServiceHost> =
        decodeList(exchange("GET", "$tn/services/${Urls.seg(name)}/devices", AdminArea.SERVICES), "hosts", "service host", idField = "stableNodeID")

    override suspend fun setServiceHostApproved(name: String, deviceId: String, approved: Boolean) {
        exchange("POST", "$tn/services/${Urls.seg(name)}/device/${Urls.seg(deviceId)}/approved", AdminArea.SERVICES,
            body = buildJsonObject { put("approved", approved) }.toString())
    }

    // ---------------------------------------------------------------- logs

    override suspend fun auditLog(start: String, end: String, filters: AuditLogFilters): Listing<ApiAuditLogEntry> =
        decodeList(
            exchange("GET", "$tn/logging/configuration", AdminArea.AUDIT_LOGS, query = listOf("start" to start, "end" to end) + filters.query()),
            "logs", "audit event", idField = "eventGroupID"
        )

    // ---------------------------------------------------------------- plumbing

    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L

    private suspend fun bearer(): String = when (val c = credential) {
        is AdminCredential.ApiToken -> c.token
        is AdminCredential.HeadscaleKey -> c.key
        is AdminCredential.OAuthClient -> tokenLock.withLock {
            accessToken?.takeIf { clock() < accessTokenExpiresAt }
                ?: tokenCache?.get(tokenCacheKey(c), clock())?.also { (token, expiresAt) ->
                    accessToken = token
                    accessTokenExpiresAt = expiresAt
                }?.first
                ?: mintToken(c)
        }
    }

    private suspend fun forgetToken() = tokenLock.withLock {
        accessToken = null
        (credential as? AdminCredential.OAuthClient)?.let { tokenCache?.remove(tokenCacheKey(it)) }
    }

    private fun tokenCacheKey(c: AdminCredential.OAuthClient) = OAuthTokenCache.key(api, c.clientId, c.clientSecret)

    /** client_credentials at /oauth/token: a one-hour token. No refresh token exists; a new one is minted. */
    private suspend fun mintToken(c: AdminCredential.OAuthClient): String {
        val request = HttpRequest(
            method = "POST",
            url = "$api/oauth/token",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            body = Urls.form(listOf("client_id" to c.clientId.ifBlank { c.keyId.orEmpty() }, "client_secret" to c.clientSecret, "grant_type" to "client_credentials")),
            // Exchanging the secret twice changes nothing on the server.
            idempotent = true,
        )
        val resp = send(request, write = false, path = "/oauth/token")
        when (resp.status) {
            in 200..299 -> Unit
            400, 401, 403 -> throw AdminApiException.Unauthorized(resp.requestId, apiMessage(resp))
            else -> throw errorFor(resp, null, false)
        }
        val token = decodeObject<OauthTokenResponse>(resp, "OAuth token")
        if (token.accessToken.isBlank()) throw AdminApiException.Decode("OAuth token", resp.requestId)
        accessToken = token.accessToken
        // Renewed a minute early; an hour is what the server hands out.
        accessTokenExpiresAt = clock() + (token.expiresIn.takeIf { it > 0 } ?: 3600L).minus(60).coerceAtLeast(30) * 1000
        tokenCache?.put(tokenCacheKey(c), token.accessToken, accessTokenExpiresAt)
        return token.accessToken
    }

    /**
     * One API call with its retries. [area] is what a 403 narrows; null for calls that must
     * not narrow anything (the credential reading its own key).
     */
    private suspend fun exchange(
        method: String,
        path: String,
        area: AdminArea?,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        accept: String = "application/json",
        contentType: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        /** A POST that changes nothing (policy validate and preview) is a read: retried, and a 403 narrows reading. */
        write: Boolean = method != "GET",
    ): HttpResponse {
        var reauthorized = false
        while (true) {
            val headers = buildMap {
                put("Authorization", "Bearer ${bearer()}")
                put("Accept", accept)
                if (body != null) put("Content-Type", contentType)
                putAll(extraHeaders)
            }
            val request = HttpRequest(method, api + path + Urls.query(query), headers, body, idempotent = !write)
            val resp = send(request, write, path)
            when {
                resp.status in 200..299 -> return resp
                // An OAuth token can lapse between the check and the call; the 401 means nothing was applied.
                resp.status == 401 && credential is AdminCredential.OAuthClient && !reauthorized -> {
                    reauthorized = true
                    forgetToken()
                }
                else -> throw errorFor(resp, area, write)
            }
        }
    }

    /** The transport call with the back-off loop, off the main thread. */
    private suspend fun send(request: HttpRequest, write: Boolean, path: String): HttpResponse =
        withContext(io) { sendWithRetries(request, write, path) }

    /** Returns the first answer that is not worth retrying, or the last one. */
    private suspend fun sendWithRetries(request: HttpRequest, write: Boolean, path: String): HttpResponse {
        var attempt = 0
        while (true) {
            attempt++
            val resp = try {
                transport.execute(request)
            } catch (e: TransportException) {
                log("${request.method} $path: no answer (${e.message})")
                if (!write && attempt < retry.maxAttempts) {
                    sleeper(retry.backoff(attempt))
                    continue
                }
                throw AdminApiException.Network(e.message ?: "no answer", e)
            }
            resp.requestId?.let { id -> currentCoroutineContext()[RequestIds]?.add(id) }
            log("${request.method} $path -> ${resp.status}" + (resp.requestId?.let { " [$it]" } ?: ""))
            val retryable = resp.status == 429 || (!write && resp.status in RETRYABLE_5XX)
            if (retryable && attempt < retry.maxAttempts) {
                val wait = retryAfterMs(resp) ?: retry.backoff(attempt)
                if (wait <= retry.maxRetryAfterMs) {
                    sleeper(wait)
                    continue
                }
            }
            return resp
        }
    }

    private fun retryAfterMs(resp: HttpResponse): Long? {
        val raw = resp.header("Retry-After")?.trim() ?: return null
        raw.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        return runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            (fmt.parse(raw)!!.time - clock()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun apiMessage(resp: HttpResponse): String? =
        runCatching { AppJson.decodeFromString(ApiErrorBody.serializer(), resp.body).message }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(300)

    private fun errorFor(resp: HttpResponse, area: AdminArea?, write: Boolean): AdminApiException {
        val id = resp.requestId
        val msg = apiMessage(resp)
        return when (resp.status) {
            400, 422 -> AdminApiException.BadRequest(resp.status, id, msg)
            401 -> AdminApiException.Unauthorized(id, msg)
            402 -> AdminApiException.PaymentRequired(id, msg)
            403 -> {
                // Only what was unknown is learned from a refusal: a scoped client's 403 may be
                // about something else (its own user, say), and its scopes already say enough.
                if (area != null) caps.update { if (it.access(area) == Access.UNKNOWN) it.denied(area, write) else it }
                AdminApiException.Forbidden(id, msg, area, write)
            }
            404 -> AdminApiException.NotFound(id, msg)
            409 -> AdminApiException.Conflict(id, msg)
            412 -> AdminApiException.PreconditionFailed(id, msg)
            429 -> AdminApiException.RateLimited(id, msg, retryAfterMs(resp)?.div(1000))
            in 500..599 -> AdminApiException.Server(resp.status, id, msg)
            else -> AdminApiException.Unexpected(resp.status, id, msg)
        }
    }

    private inline fun <reified T> decodeObject(resp: HttpResponse, what: String): T = try {
        AppJson.decodeFromString<T>(resp.body.ifBlank { "{}" })
    } catch (e: Exception) {
        throw AdminApiException.Decode(what, resp.requestId, e)
    }

    private fun hasField(resp: HttpResponse, field: String): Boolean =
        runCatching { AppJson.parseToJsonElement(resp.body).jsonObject.containsKey(field) }.getOrDefault(false)

    private inline fun <reified T> decodeList(resp: HttpResponse, field: String, what: String, idField: String = "id"): Listing<T> {
        val root = try {
            AppJson.parseToJsonElement(resp.body.ifBlank { "{}" })
        } catch (e: Exception) {
            throw AdminApiException.Decode("$what list", resp.requestId, e)
        }
        val array = when (val el = (root as? JsonObject ?: throw AdminApiException.Decode("$what list", resp.requestId))[field]) {
            null, JsonNull -> return Listing(emptyList())
            is JsonArray -> el
            else -> throw AdminApiException.Decode("$what list", resp.requestId)
        }
        val items = ArrayList<T>(array.size)
        val issues = mutableListOf<DecodeIssue>()
        array.forEachIndexed { index, el ->
            try {
                items += AppJson.decodeFromJsonElement<T>(el)
            } catch (e: Exception) {
                val id = (el as? JsonObject)?.get(idField)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                issues += DecodeIssue(what, index, id, (e.message ?: e.javaClass.simpleName).lineSequence().first().take(200))
            }
        }
        if (issues.isNotEmpty()) log("$what list: ${issues.size} of ${array.size} unreadable")
        return Listing(items, issues)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tailscale.com"
        /** Tailscale's default relay map; login.tailscale.com serves the same. */
        const val DERP_MAP_URL = "https://controlplane.tailscale.com/derpmap/default"
        private const val HUJSON = "application/hujson"

        /** An ETag as If-Match wants it: quoted, as the GET sent it, or quoted here when it came bare. */
        fun quotedEtag(etag: String): String {
            val t = etag.trim()
            return if (t.startsWith("\"") || t.startsWith("W/")) t else "\"$t\""
        }
        private val RETRYABLE_5XX = setOf(502, 503, 504)
        private val TAG = Regex("\"(tag:[A-Za-z0-9_\\-/]+)\"")

        val FEATURES: Set<BackendFeature> = BackendFeature.entries.toSet() - BackendFeature.headscaleOnly
    }
}
