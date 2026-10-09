package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.DecodeIssue
import io.github.bropines.tailscaled.admin.api.Listing
import io.github.bropines.tailscaled.admin.api.PolicyValidation
import io.github.bropines.tailscaled.admin.api.Rfc3339
import io.github.bropines.tailscaled.admin.api.Urls
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Headscale's REST API at /api/v1, call by call, in the shape of the release [level] names:
 * the gRPC gateway of 0.25–0.29 and the plain HTTP API 0.30 keeps beside /api/v2. What
 * changed between releases is decided here and nowhere else (see [V1Level]).
 */
internal class HeadscaleV1Api(val http: HeadscaleHttp, @Volatile var level: V1Level) {

    // ------------------------------------------------------------------ server

    /** `GET /version`, unauthenticated: null before 0.27 (404) or behind a proxy that hides it. */
    suspend fun version(): HeadscaleVersion? {
        val resp = http.raw("GET", "/version", auth = false)
        if (resp.status !in 200..299) return null
        val v = runCatching { AppJson.decodeFromString(HsVersion.serializer(), resp.body) }.getOrNull()
        return HeadscaleVersion.parse(v?.version)
    }

    /**
     * Which backend the server needs and what release it is. `/version` answers from 0.27;
     * 0.30 and development builds are asked whether they serve the Tailscale-compatible
     * /api/v2; with no `/version`, the routes API tells 0.25 (it has one) from 0.26.
     * A refused key is thrown as [AdminApiException.Unauthorized].
     */
    suspend fun detect(): Pair<BackendKind, HeadscaleVersion> {
        val v = version() ?: return BackendKind.HEADSCALE_V1 to probeLegacy()
        if (v.development || v.atLeast(30)) {
            val probe = http.raw("GET", "/api/v2/tailnet/-/devices")
            if (probe.status == 401) throw http.errorFor(probe, null, false)
            if (probe.status in 200..299) return BackendKind.HEADSCALE_V2 to v
        }
        return BackendKind.HEADSCALE_V1 to v
    }

    /** A server without `/version`: 0.25 if it still has the routes API, 0.26 otherwise. */
    suspend fun probeLegacy(): HeadscaleVersion {
        val routes = http.raw("GET", "/api/v1/routes")
        if (routes.status == 401) throw http.errorFor(routes, null, false)
        return if (routes.status in 200..299) HeadscaleVersion.V0_25 else HeadscaleVersion.V0_26
    }

    // ------------------------------------------------------------------ nodes

    suspend fun nodes(): Listing<HsNode> =
        http.call("GET", "/api/v1/node", AdminArea.DEVICES).decodeList(HsNode.serializer(), "nodes", "device")

    suspend fun node(id: String): HsNode =
        http.call("GET", "/api/v1/node/${Urls.seg(id)}", AdminArea.DEVICES).decode(HsNodeEnvelope.serializer(), "device").node

    suspend fun deleteNode(id: String) {
        http.call("DELETE", "/api/v1/node/${Urls.seg(id)}", AdminArea.DEVICES)
    }

    /**
     * Expires the node's key now, or with [disableExpiry] makes it never expire (0.29+).
     * Sent both ways at once: the gateway reads the query, 0.30's API the body, each ignores the other.
     */
    suspend fun expireNode(id: String, disableExpiry: Boolean = false): HsNode {
        val query = if (disableExpiry) listOf("disableExpiry" to "true") else emptyList()
        val body = if (disableExpiry) """{"disableExpiry":true}""" else "{}"
        return http.call("POST", "/api/v1/node/${Urls.seg(id)}/expire", AdminArea.DEVICES, query = query, body = body)
            .decode(HsNodeEnvelope.serializer(), "device").node
    }

    suspend fun renameNode(id: String, newName: String): HsNode =
        http.call("POST", "/api/v1/node/${Urls.seg(id)}/rename/${Urls.seg(newName)}", AdminArea.DEVICES, body = "{}")
            .decode(HsNodeEnvelope.serializer(), "device").node

    suspend fun setTags(id: String, tags: List<String>): HsNode =
        http.call("POST", "/api/v1/node/${Urls.seg(id)}/tags", AdminArea.DEVICES, body = buildJsonObject {
            putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        }.toString()).decode(HsNodeEnvelope.serializer(), "device").node

    /** 0.26+: the node's approved routes, replaced as a whole. */
    suspend fun approveRoutes(id: String, routes: List<String>): HsNode =
        http.call("POST", "/api/v1/node/${Urls.seg(id)}/approve_routes", AdminArea.ROUTES, body = buildJsonObject {
            putJsonArray("routes") { routes.forEach { add(JsonPrimitive(it)) } }
        }.toString()).decode(HsNodeEnvelope.serializer(), "device").node

    /** 0.25: the node's routes, one row each. */
    suspend fun nodeRoutes(id: String): List<HsRoute> =
        http.call("GET", "/api/v1/node/${Urls.seg(id)}/routes", AdminArea.ROUTES)
            .decodeList(HsRoute.serializer(), "routes", "route").items

    suspend fun setRouteEnabled(routeId: String, enabled: Boolean) {
        http.call("POST", "/api/v1/routes/${Urls.seg(routeId)}/${if (enabled) "enable" else "disable"}", AdminArea.ROUTES, body = "{}")
    }

    /**
     * Registers the node waiting under [authId] to [userName]: `/auth/register` from 0.29,
     * the older `/node/register?user=&key=` before.
     */
    suspend fun register(authId: String, userName: String): HsNode {
        val resp = if (level.authRegister) {
            http.call("POST", "/api/v1/auth/register", AdminArea.DEVICES, body = buildJsonObject {
                put("user", userName)
                put("authId", authId)
            }.toString())
        } else {
            http.call("POST", "/api/v1/node/register", AdminArea.DEVICES, query = listOf("user" to userName, "key" to authId), body = "{}")
        }
        return resp.decode(HsNodeEnvelope.serializer(), "device").node
    }

    suspend fun rejectAuth(authId: String) {
        http.call("POST", "/api/v1/auth/reject", AdminArea.DEVICES, body = buildJsonObject { put("authId", authId) }.toString())
    }

    // ------------------------------------------------------------------ users

    suspend fun users(): Listing<HsUser> =
        http.call("GET", "/api/v1/user", AdminArea.USERS).decodeList(HsUser.serializer(), "users", "user")

    suspend fun user(id: String): HsUser =
        http.call("GET", "/api/v1/user", AdminArea.USERS, query = listOf("id" to id))
            .decodeList(HsUser.serializer(), "users", "user").items.firstOrNull { it.id == id }
            ?: throw AdminApiException.NotFound(null, "user $id not found")

    suspend fun createUser(name: String, displayName: String?, email: String?): HsUser =
        http.call("POST", "/api/v1/user", AdminArea.USERS, body = buildJsonObject {
            put("name", name)
            displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            email?.takeIf { it.isNotBlank() }?.let { put("email", it) }
        }.toString()).decode(HsUserEnvelope.serializer(), "user").user

    suspend fun renameUser(id: String, newName: String): HsUser =
        http.call("POST", "/api/v1/user/${Urls.seg(id)}/rename/${Urls.seg(newName)}", AdminArea.USERS, body = "{}")
            .decode(HsUserEnvelope.serializer(), "user").user

    suspend fun deleteUser(id: String) {
        http.call("DELETE", "/api/v1/user/${Urls.seg(id)}", AdminArea.USERS)
    }

    // ------------------------------------------------------------------ pre-auth keys

    /**
     * Every pre-auth key: one call from 0.28, one per user before (where the list also carries
     * each key's secret, which [HsPreAuthKey.toKey] drops).
     */
    suspend fun preAuthKeys(users: suspend () -> List<HsUser>): Listing<HsPreAuthKey> {
        if (level.preAuthKeysListAll) {
            return http.call("GET", "/api/v1/preauthkey", AdminArea.AUTH_KEYS)
                .decodeList(HsPreAuthKey.serializer(), "preAuthKeys", "auth key")
        }
        val items = mutableListOf<HsPreAuthKey>()
        val issues = mutableListOf<DecodeIssue>()
        for (u in users()) {
            val param = if (level.preAuthKeyUserIsId) u.id else u.name
            val part = http.call("GET", "/api/v1/preauthkey", AdminArea.AUTH_KEYS, query = listOf("user" to param))
                .decodeList(HsPreAuthKey.serializer(), "preAuthKeys", "auth key")
            // 0.25 names the user by name only; keep which one it was.
            items += part.items.map { k -> if (k.user == null) k.copy(user = JsonPrimitive(u.name)) else k }
            issues += part.issues
        }
        return Listing(items, issues)
    }

    /** A new key; [user] is required unless it carries [tags]. The answer has the secret, once. */
    suspend fun createPreAuthKey(user: HsUser?, reusable: Boolean, ephemeral: Boolean, expiresAtMs: Long, tags: List<String>): HsPreAuthKey =
        http.call("POST", "/api/v1/preauthkey", AdminArea.AUTH_KEYS, body = buildJsonObject {
            user?.let { put("user", if (level.preAuthKeyUserIsId) it.id else it.name) }
            put("reusable", reusable)
            put("ephemeral", ephemeral)
            put("expiration", Rfc3339.format(expiresAtMs))
            if (tags.isNotEmpty()) putJsonArray("aclTags") { tags.forEach { add(JsonPrimitive(it)) } }
        }.toString()).decode(HsPreAuthKeyEnvelope.serializer(), "auth key").preAuthKey

    /**
     * Expires a key: by id from 0.28, by its user and secret before — which the older lists
     * carry, so [key] must come from one.
     */
    suspend fun expirePreAuthKey(key: HsPreAuthKey) {
        val body = buildJsonObject {
            if (level.preAuthKeyById) put("id", key.id)
            else {
                val user = if (level.preAuthKeyUserIsId) key.userId else key.userName
                put("user", user ?: throw AdminApiException.NotFound(null, "the key's user is not known"))
                put("key", key.key.takeIf { "***" !in it && it.isNotBlank() } ?: throw AdminApiException.NotFound(null, "the key's secret is not listed"))
            }
        }
        http.call("POST", "/api/v1/preauthkey/expire", AdminArea.AUTH_KEYS, body = body.toString())
    }

    // ------------------------------------------------------------------ API keys

    suspend fun apiKeys(): Listing<HsApiKey> =
        http.call("GET", "/api/v1/apikey", AdminArea.API_TOKENS).decodeList(HsApiKey.serializer(), "apiKeys", "API key")

    suspend fun createApiKey(expiresAtMs: Long): String =
        http.call("POST", "/api/v1/apikey", AdminArea.API_TOKENS, body = buildJsonObject {
            put("expiration", Rfc3339.format(expiresAtMs))
        }.toString()).decode(HsApiKeyCreated.serializer(), "API key").apiKey.also {
            if (it.isBlank()) throw AdminApiException.Decode("API key", null)
        }

    /** By the listed prefix, with or without its "hskey-api-" and "-***" around it. */
    suspend fun expireApiKey(listedPrefix: String) {
        val bare = HsApiKey(prefix = listedPrefix).bareprefix
        http.call("POST", "/api/v1/apikey/expire", AdminArea.API_TOKENS, body = buildJsonObject { put("prefix", bare) }.toString())
    }

    // ------------------------------------------------------------------ policy

    /** The stored policy; null when none was ever set (the server then allows everything). */
    suspend fun policy(): HsPolicy? = try {
        http.call("GET", "/api/v1/policy", AdminArea.POLICY).decode(HsPolicy.serializer(), "policy")
    } catch (e: AdminApiException.NotFound) {
        null
    }

    suspend fun setPolicy(text: String): HsPolicy =
        http.call("PUT", "/api/v1/policy", AdminArea.POLICY, body = buildJsonObject { put("policy", text) }.toString())
            .decode(HsPolicy.serializer(), "policy")

    suspend fun checkPolicy(text: String) {
        http.call("POST", "/api/v1/policy/check", AdminArea.POLICY, body = buildJsonObject { put("policy", text) }.toString())
    }

    companion object {
        /** The words both API generations use when `policy.mode` is `file`. */
        fun isPolicyUpdateDisabled(e: AdminApiException): Boolean =
            e.apiMessage?.contains("update is disabled", ignoreCase = true) == true
    }
}

/** The server's tags in a HuJSON policy: tagOwners' keys when it parses, every "tag:…" string when it does not. */
internal object PolicyText {
    private val TAG = Regex("\"(tag:[A-Za-z0-9_\\-/.]+)\"")

    fun tags(text: String): List<String> {
        val owners = runCatching {
            (AppJson.parseToJsonElement(HuJson.standardize(text)) as JsonObject)["tagOwners"] as? JsonObject
        }.getOrNull()?.keys?.toList()
        val tags = owners ?: TAG.findAll(text).map { it.groupValues[1] }.toList()
        return tags.filter { it.startsWith("tag:") }.distinct().sorted()
    }

    /** The ETag Headscale's /api/v2 gives this exact text: the quoted SHA-256 of its bytes. */
    fun etag(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return "\"" + digest.joinToString("") { "%02x".format(it) } + "\""
    }

    /** What Headscale serves when no policy was ever set: everything allowed. */
    const val DEFAULT = "{\n\t// Headscale default policy. Allows all communication.\n\t\"acls\": [\n\t\t{\"action\": \"accept\", \"src\": [\"*\"], \"dst\": [\"*:*\"]},\n\t],\n}\n"
}

/** A policy check's answer in the shape the console's policy pipeline reads. */
internal object PolicyChecks {
    /** The server checked it: no error is a pass, a refusal (400, or the gateway's 500) says why. */
    suspend fun byServer(check: suspend () -> Unit): PolicyValidation = try {
        check()
        PolicyValidation.OK
    } catch (e: AdminApiException.BadRequest) {
        PolicyValidation(false, e.apiMessage)
    } catch (e: AdminApiException.Server) {
        PolicyValidation(false, e.apiMessage ?: e.message)
    }

    /** Only parsed here, for servers without a check: a syntax error is caught, nothing else is. */
    fun locally(text: String): PolicyValidation = try {
        val root = AppJson.parseToJsonElement(HuJson.standardize(text))
        if (root is JsonObject) PolicyValidation.OK else PolicyValidation(false, "the policy is not a JSON object")
    } catch (e: Exception) {
        PolicyValidation(false, e.message?.lineSequence()?.firstOrNull()?.take(300))
    }
}

/** HuJSON (JSON with comments and trailing commas) made plain JSON, strings left alone. */
internal object HuJson {
    fun standardize(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    out.append(text[i + 1]); i += 2; continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; out.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == ',' -> {
                    var j = i + 1
                    while (j < text.length) {
                        val d = text[j]
                        if (d.isWhitespace()) j++
                        else if (d == '/' && j + 1 < text.length && text[j + 1] == '/') { while (j < text.length && text[j] != '\n') j++ }
                        else if (d == '/' && j + 1 < text.length && text[j + 1] == '*') { j += 2; while (j + 1 < text.length && !(text[j] == '*' && text[j + 1] == '/')) j++; j += 2 }
                        else break
                    }
                    if (j < text.length && (text[j] == '}' || text[j] == ']')) i++ else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
