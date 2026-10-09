package io.github.bropines.tailscaled.admin.api

/**
 * A part of the Admin API that one OAuth scope governs. [scope] is the scope's name without
 * the ":read" suffix; [readOnly] areas have no write scope at all.
 */
enum class AdminArea(val scope: String, val readOnly: Boolean = false) {
    DEVICES("devices:core"),
    ROUTES("devices:routes"),
    POSTURE("devices:posture_attributes"),
    DEVICE_INVITES("device_invites"),
    AUTH_KEYS("auth_keys"),
    OAUTH_KEYS("oauth_keys"),
    API_TOKENS("api_access_tokens"),
    FEDERATED_KEYS("federated_keys"),
    DNS("dns"),
    POLICY("policy_file"),
    USERS("users"),
    WEBHOOKS("webhooks"),
    AUDIT_LOGS("logs:configuration", readOnly = true),
    NETWORK_LOGS("logs:network"),
    LOG_STREAMING("log_streaming"),
    SETTINGS("feature_settings"),
    NETWORKING_SETTINGS("networking_settings"),
    ACCOUNT_SETTINGS("account_settings"),
    SERVICES("services");

    /** The scope a request needs: "dns" to write, "dns:read" to read. */
    fun scopeFor(write: Boolean): String = if (write && !readOnly) scope else "$scope:read"
}

/** How far a credential reaches into one [AdminArea]. */
enum class Access {
    NONE, READ, WRITE,
    /** Not known yet: a personal token carries its owner's role, which only a 403 reveals. */
    UNKNOWN;

    val canRead: Boolean get() = this != NONE
    val canWrite: Boolean get() = this == WRITE || this == UNKNOWN
}

enum class CredentialKind {
    /** `tskey-api-…`: the owner's full role, 1–90 days. */
    API_TOKEN,
    /** `tskey-client-…`: scoped, owned by the tailnet, mints one-hour tokens. */
    OAUTH_CLIENT,
    /** A Headscale API key: all access, nothing scoped. */
    HEADSCALE_API_KEY,
}

/** What a backend implements at all, whatever the credential. Headscale lacks most of these. */
enum class BackendFeature {
    DEVICES, DEVICE_ROUTES, DEVICE_IPV4, KEYS, USERS, USER_ROLES, USER_INVITES,
    DNS, DNS_CONFIGURATION, SETTINGS, POLICY, WEBHOOKS, AUDIT_LOGS, SERVICES, POSTURE,
    DEVICE_INVITES, OAUTH_CLIENTS,
}

/**
 * What the console may offer for one backend and credential. The UI reads it to hide what the
 * backend lacks ([features]) and to disable what the credential may not do ([access]); the
 * backend narrows it as 403s arrive, so a personal token's limits show after the first refusal.
 */
data class Capabilities(
    val backend: BackendKind,
    val credential: CredentialKind,
    val features: Set<BackendFeature>,
    val access: Map<AdminArea, Access> = emptyMap(),
    /** For areas missing from [access]: UNKNOWN for a personal token, NONE for a scoped client. */
    val defaultAccess: Access = Access.UNKNOWN,
    /** The console's own credential in the keys list: never offered for revocation. */
    val ownKeyId: String? = null,
    /** The user the credential acts as: a personal token's owner. Actions on them are blocked. */
    val ownUserId: String? = null,
    val credentialExpires: String? = null,
    /** An OAuth client's tags: the tags its auth keys must carry. */
    val credentialTags: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
) {
    fun access(area: AdminArea): Access = access[area] ?: defaultAccess
    fun canRead(area: AdminArea): Boolean = access(area).canRead
    fun canWrite(area: AdminArea): Boolean = !area.readOnly && access(area).canWrite
    fun has(feature: BackendFeature): Boolean = feature in features

    /** After a 403: a refused read leaves nothing, a refused write leaves reading as it was. */
    fun denied(area: AdminArea, write: Boolean): Capabilities {
        val now = access(area)
        val next = if (!write) Access.NONE else if (now == Access.NONE) Access.NONE else Access.READ
        return if (next == now) this else copy(access = access + (area to next))
    }

    companion object {
        /** A scoped OAuth client: exactly what its scopes say, nothing else. */
        fun fromScopes(
            backend: BackendKind,
            features: Set<BackendFeature>,
            scopes: List<String>,
        ): Capabilities = Capabilities(
            backend = backend,
            credential = CredentialKind.OAUTH_CLIENT,
            features = features,
            access = Scopes.accessFrom(scopes),
            defaultAccess = Access.NONE,
            scopes = scopes,
        )
    }
}

object Scopes {
    /**
     * Area access from OAuth scopes: "all" and "all:read" reach every area, "dns" writes
     * and reads DNS, "dns:read" only reads it. A scope this app does not know is ignored.
     */
    fun accessFrom(scopes: List<String>): Map<AdminArea, Access> {
        val out = mutableMapOf<AdminArea, Access>()
        fun grant(area: AdminArea, level: Access) {
            val l = if (area.readOnly && level == Access.WRITE) Access.READ else level
            val prev = out[area]
            if (prev == null || prev.ordinal < l.ordinal) out[area] = l
        }
        for (raw in scopes) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            val read = s.endsWith(":read")
            val name = s.removeSuffix(":read")
            val level = if (read) Access.READ else Access.WRITE
            if (name == "all") AdminArea.entries.forEach { grant(it, level) }
            else AdminArea.entries.filter { it.scope == name }.forEach { grant(it, level) }
        }
        return out
    }
}
