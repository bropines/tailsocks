package io.github.bropines.tailscaled.admin.api

/**
 * What the console authenticates with. Held decrypted only in memory, for the lifetime of
 * one backend; [toString] never shows a secret, so a stray log line cannot leak one.
 */
sealed class AdminCredential {
    abstract val kind: CredentialKind

    /** The id of this credential in the tailnet's keys list, when the secret reveals it. */
    abstract val keyId: String?

    /** A personal API access token, `tskey-api-<id>-<secret>`. */
    class ApiToken(val token: String) : AdminCredential() {
        override val kind get() = CredentialKind.API_TOKEN
        override val keyId: String? get() = keyIdOf(token, "tskey-api-")
        override fun toString() = "ApiToken(${keyId ?: "?"})"
    }

    /** An OAuth client (trust credential): its id and its `tskey-client-<id>-<secret>` secret. */
    class OAuthClient(val clientId: String, val clientSecret: String) : AdminCredential() {
        override val kind get() = CredentialKind.OAUTH_CLIENT
        override val keyId: String? get() = clientId.ifBlank { null } ?: keyIdOf(clientSecret, "tskey-client-")
        override fun toString() = "OAuthClient($clientId)"
    }

    /** A Headscale API key, sent as a bearer token. */
    class HeadscaleKey(val key: String) : AdminCredential() {
        override val kind get() = CredentialKind.HEADSCALE_API_KEY
        override val keyId: String? get() = key.substringBefore('.').takeIf { '.' in key && it.isNotBlank() }
        override fun toString() = "HeadscaleKey(${keyId ?: "?"})"
    }

    companion object {
        /**
         * The key id inside a Tailscale secret: "kAbC123CNTRL" of "tskey-api-kAbC123CNTRL-xyz".
         * Null when the text does not have that shape.
         */
        fun keyIdOf(secret: String, prefix: String): String? {
            val s = secret.trim()
            if (!s.startsWith(prefix)) return null
            val id = s.removePrefix(prefix).substringBefore('-')
            return id.takeIf { it.isNotBlank() && it.length < s.length - prefix.length && it.all(Char::isLetterOrDigit) }
        }
    }
}
