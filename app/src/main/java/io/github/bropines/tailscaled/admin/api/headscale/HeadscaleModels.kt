package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.KeyCapabilities
import io.github.bropines.tailscaled.admin.api.KeyCreateOptions
import io.github.bropines.tailscaled.admin.api.KeyDeviceCapabilities
import io.github.bropines.tailscaled.admin.api.Rfc3339
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Headscale's /api/v1 objects, protojson-shaped: lowerCamelCase names, 64-bit ids as strings,
 * timestamps RFC 3339 or null. Fields that changed between releases are all here, optional:
 * forced/valid/invalid tags (to 0.27) beside `tags` (0.28+), a pre-auth key's user as a name
 * (0.25) or an object (0.26+), approved/available routes (0.26+).
 */

@Serializable
data class HsUser(
    val id: String = "",
    val name: String = "",
    val createdAt: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val providerId: String? = null,
    val provider: String? = null,
    val profilePicUrl: String? = null,
) {
    /** The synthetic owner every tagged node shows from 0.28 on: no person owns it. */
    val isTaggedDevices: Boolean get() = name == TAGGED_DEVICES || id == TAGGED_DEVICES_ID

    companion object {
        const val TAGGED_DEVICES = "tagged-devices"
        const val TAGGED_DEVICES_ID = "2147455555"
    }
}

@Serializable
data class HsNode(
    val id: String = "",
    val machineKey: String? = null,
    val nodeKey: String? = null,
    val discoKey: String? = null,
    val ipAddresses: List<String> = emptyList(),
    /** The hostname the node reported. */
    val name: String = "",
    val user: HsUser? = null,
    val lastSeen: String? = null,
    val expiry: String? = null,
    /** An object from 0.26; kept raw, only `ephemeral` is read. */
    val preAuthKey: JsonElement? = null,
    val createdAt: String? = null,
    val registerMethod: String? = null,
    /** The MagicDNS label, which rename changes. */
    val givenName: String = "",
    val online: Boolean? = null,
    val approvedRoutes: List<String>? = null,
    val availableRoutes: List<String>? = null,
    val subnetRoutes: List<String>? = null,
    /** 0.28+. */
    val tags: List<String>? = null,
    /** Up to 0.27. */
    val forcedTags: List<String>? = null,
    val validTags: List<String>? = null,
    val invalidTags: List<String>? = null,
) {
    /** The tags the node carries, whichever generation told us. */
    val effectiveTags: List<String>
        get() = tags ?: (forcedTags.orEmpty() + validTags.orEmpty()).distinct()

    val isEphemeral: Boolean?
        get() = ((preAuthKey as? JsonObject)?.get("ephemeral") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /**
     * As the console's device model. Tagged nodes have no owner, whatever the server lists for
     * them (the synthetic "tagged-devices" user from 0.28, the creating user before).
     */
    fun toDevice(): ApiDevice {
        val tagged = effectiveTags.isNotEmpty() || user?.isTaggedDevices == true
        val exp = expiry?.takeUnless { isZeroTime(it) }
        return ApiDevice(
            id = id,
            nodeId = id,
            name = givenName.ifBlank { name },
            hostname = name.ifBlank { null },
            addresses = ipAddresses,
            user = if (tagged) null else user?.name?.ifBlank { null },
            created = createdAt?.takeUnless { isZeroTime(it) },
            connectedToControl = online,
            lastSeen = lastSeen?.takeUnless { isZeroTime(it) || online == true },
            keyExpiryDisabled = exp == null,
            expires = exp,
            authorized = true,
            isExternal = false,
            machineKey = machineKey,
            nodeKey = nodeKey,
            enabledRoutes = approvedRoutes,
            advertisedRoutes = availableRoutes,
            tags = effectiveTags,
            isEphemeral = isEphemeral,
        )
    }
}

/** 0.25's routes API: one row per advertised prefix, enabled or not. */
@Serializable
data class HsRoute(
    val id: String = "",
    val node: HsNode? = null,
    val prefix: String = "",
    val advertised: Boolean = false,
    val enabled: Boolean = false,
    val isPrimary: Boolean = false,
)

@Serializable
data class HsPreAuthKey(
    /** A name in 0.25, a user object from 0.26, null for a tagged key in 0.30. */
    val user: JsonElement? = null,
    val id: String = "",
    /** Masked ("hskey-auth-abcd-***") from 0.28; the whole secret before, which we drop. */
    val key: String = "",
    val reusable: Boolean = false,
    val ephemeral: Boolean = false,
    val used: Boolean = false,
    val expiration: String? = null,
    val createdAt: String? = null,
    val aclTags: List<String> = emptyList(),
) {
    val userId: String? get() = (user as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content }?.ifBlank { null }
    val userName: String?
        get() = when (val u = user) {
            is JsonObject -> (u["name"] as? JsonPrimitive)?.content
            is JsonPrimitive -> u.content.takeIf { u.isString }
            else -> null
        }?.ifBlank { null }

    /** The secret is only ever the masked form here: a full legacy key is cut to its first characters. */
    val maskedKey: String get() = if ("***" in key) key else if (key.length > 8) key.take(8) + "…" else key

    /**
     * As the console's key model; [now] decides `invalid` (used single-use, or expired). The
     * secret is never in it: a key is shown once, at creation, from [toCreatedKey].
     */
    fun toKey(now: Long): ApiKey {
        val exp = expiration?.takeUnless { isZeroTime(it) }
        val expired = exp?.let { parseRfc3339(it)?.let { t -> t <= now } } == true
        return ApiKey(
            id = id,
            key = null,
            keyType = "auth",
            description = maskedKey.ifBlank { null },
            created = createdAt?.takeUnless { isZeroTime(it) },
            expires = exp,
            invalid = expired || (used && !reusable),
            capabilities = KeyCapabilities(KeyDeviceCapabilities(KeyCreateOptions(reusable, ephemeral, preauthorized = true, tags = aclTags))),
            tags = aclTags,
            userId = if (aclTags.isNotEmpty()) null else userId ?: userName,
        )
    }

    fun toCreatedKey(now: Long): ApiKey = toKey(now).copy(key = key.takeUnless { "***" in it })
}

/** A Headscale API key: what the console itself signs in with, all access, 90 days by default. */
@Serializable
data class HsApiKey(
    val id: String = "",
    /** "hskey-api-AbCdEfGhIjKl-***" from 0.28, a bare 7-character prefix before. */
    val prefix: String = "",
    val expiration: String? = null,
    val createdAt: String? = null,
    val lastSeen: String? = null,
) {
    /** What `expire` takes: the prefix without the "hskey-api-" and "-***" around it. */
    val bareprefix: String get() = prefix.removePrefix(API_KEY_PREFIX).removeSuffix("-***")

    fun isExpired(now: Long): Boolean = expiration?.takeUnless { isZeroTime(it) }?.let { parseRfc3339(it)?.let { t -> t <= now } } == true

    companion object {
        const val API_KEY_PREFIX = "hskey-api-"
        private const val NEW_PREFIX_LENGTH = 12

        /**
         * The prefix of a full API key, as the list shows it: "hskey-api-<12>-***" for keys
         * from 0.28, the part before the dot for older ones. Null when it has neither shape.
         */
        fun listedPrefixOf(secret: String): String? {
            val s = secret.trim()
            if (s.startsWith(API_KEY_PREFIX)) {
                val rest = s.removePrefix(API_KEY_PREFIX)
                if (rest.length <= NEW_PREFIX_LENGTH + 1 || rest[NEW_PREFIX_LENGTH] != '-') return null
                return API_KEY_PREFIX + rest.take(NEW_PREFIX_LENGTH) + "-***"
            }
            val dot = s.indexOf('.')
            return if (dot > 0) s.substring(0, dot) else null
        }
    }
}

@Serializable
internal data class HsNodeList(val nodes: List<HsNode> = emptyList())

@Serializable
internal data class HsNodeEnvelope(val node: HsNode = HsNode())

@Serializable
internal data class HsUserEnvelope(val user: HsUser = HsUser())

@Serializable
internal data class HsPreAuthKeyEnvelope(val preAuthKey: HsPreAuthKey = HsPreAuthKey())

@Serializable
internal data class HsApiKeyCreated(val apiKey: String = "")

@Serializable
internal data class HsPolicy(val policy: String = "", val updatedAt: String? = null)

@Serializable
internal data class HsVersion(val version: String = "", val commit: String? = null, val buildTime: String? = null)

/** v1 user → the console's user: Headscale has no roles or statuses, every user is a member. */
fun HsUser.toApiUser(nodes: List<HsNode>?): ApiUser {
    val mine = nodes?.filter { it.user?.id == id && it.effectiveTags.isEmpty() }
    return ApiUser(
        id = id,
        displayName = displayName?.ifBlank { null },
        loginName = name.ifBlank { email.orEmpty() },
        profilePicUrl = profilePicUrl?.ifBlank { null },
        created = createdAt,
        type = "member",
        deviceCount = mine?.size,
        lastSeen = mine?.mapNotNull { it.lastSeen?.takeUnless(::isZeroTime) }?.maxOrNull(),
        currentlyConnected = mine?.any { it.online == true },
    )
}

internal fun isZeroTime(t: String): Boolean = t.startsWith("0001-01-01")

internal fun parseRfc3339(text: String): Long? = Rfc3339.parse(text)
