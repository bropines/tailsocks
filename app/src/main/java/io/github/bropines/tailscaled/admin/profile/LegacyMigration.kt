package io.github.bropines.tailscaled.admin.profile

import io.github.bropines.tailscaled.admin.secure.CredentialVault

/**
 * Moves the pre-4.9 console settings out of the plain-text `admin_api_keys` preference file:
 * one admin profile per tailnet that had credentials, its secrets sealed into the vault. The
 * caller deletes the old file only when [Result.complete] — every secret landed — so a failed
 * Keystore never costs a user their token.
 *
 * The old layout, keyed by the tailnet's MagicDNS suffix: the token under the bare name, the
 * rest under `<tailnet>_<field>` (`_auth_type` TOKEN/OAUTH, `_oauth_client_id`,
 * `_oauth_client_secret`, `_proxy_mode`, `_proxy_host`, `_proxy_port` as an Int, `_proxy_user`,
 * `_proxy_pass`).
 */
object LegacyMigration {
    const val LEGACY_PREFS = "admin_api_keys"

    private val SUFFIXES = listOf(
        "_auth_type", "_oauth_client_id", "_oauth_client_secret",
        "_proxy_mode", "_proxy_host", "_proxy_port", "_proxy_user", "_proxy_pass",
    )

    data class Result(
        val created: List<AdminProfile>,
        /** Tailnets left out: no credentials, or a profile from an earlier run already holds them. */
        val skipped: List<String>,
        /** Every secret was stored: the legacy file may go. */
        val complete: Boolean,
    )

    /** The tailnet names in a legacy preference snapshot. */
    fun tailnets(legacy: Map<String, *>): Set<String> = legacy.keys.mapNotNull { key ->
        val suffix = SUFFIXES.firstOrNull { key.endsWith(it) && key.length > it.length }
        if (suffix != null) key.removeSuffix(suffix) else key
    }.filter { it.isNotBlank() }.toSortedSet()

    fun migrate(
        legacy: Map<String, *>,
        store: AdminProfileStore,
        vault: CredentialVault,
        /** The tailnet the active TailSocks profile last saw: its profile becomes the active one. */
        lastKnownTailnet: String?,
        newId: () -> String,
        now: Long,
    ): Result {
        fun str(key: String) = (legacy[key] as? String)?.trim().orEmpty()
        val existing = store.profiles().mapNotNull { it.legacyTailnetKey }.toSet()
        val created = mutableListOf<AdminProfile>()
        val skipped = mutableListOf<String>()
        var complete = true

        for (tailnet in tailnets(legacy)) {
            if (tailnet in existing) {
                skipped += tailnet
                continue
            }
            val token = str(tailnet)
            val clientId = str("${tailnet}_oauth_client_id")
            val clientSecret = str("${tailnet}_oauth_client_secret")
            val tokenOk = token.isNotEmpty()
            val oauthOk = clientId.isNotEmpty() && clientSecret.isNotEmpty()
            // The chosen type when it is complete, the other one when only that is: the old
            // console used OAuth whenever a secret was stored, and it must keep working.
            val authType = when (str("${tailnet}_auth_type").uppercase()) {
                "OAUTH" -> if (oauthOk) AuthType.OAUTH_CLIENT else if (tokenOk) AuthType.API_TOKEN else null
                else -> if (tokenOk) AuthType.API_TOKEN else if (oauthOk) AuthType.OAUTH_CLIENT else null
            }
            if (authType == null) {
                skipped += tailnet
                continue
            }
            val id = newId()
            val proxyMode = str("${tailnet}_proxy_mode").uppercase().let {
                when (it) {
                    "CUSTOM_PROXY" -> AdminProxySettings.MODE_CUSTOM_SOCKS5
                    "AUTO" -> AdminProxySettings.MODE_CONTROL_PLANE
                    in AdminProxySettings.modes -> it
                    else -> AdminProxySettings.MODE_CONTROL_PLANE
                }
            }
            val profile = AdminProfile(
                id = id,
                name = tailnet,
                tailnetDnsName = tailnet.takeIf { '.' in it }?.lowercase().orEmpty(),
                authType = authType,
                oauthClientId = if (authType == AuthType.OAUTH_CLIENT) clientId else "",
                proxy = AdminProxySettings(
                    mode = proxyMode,
                    host = str("${tailnet}_proxy_host"),
                    port = (legacy["${tailnet}_proxy_port"] as? Number)?.toInt() ?: 0,
                    user = str("${tailnet}_proxy_user"),
                ),
                createdAt = now,
                legacyTailnetKey = tailnet,
            )
            val secretOk = runCatching {
                vault.put(id, authType.slot, if (authType == AuthType.API_TOKEN) token else clientSecret) &&
                    vault.put(id, CredentialVault.Slot.PROXY_PASSWORD, str("${tailnet}_proxy_pass"))
            }.getOrDefault(false)
            if (!secretOk || !store.save(profile)) {
                runCatching { vault.removeProfile(id) }
                complete = false
                continue
            }
            created += profile
        }

        val activeMatch = created.firstOrNull { it.legacyTailnetKey.equals(lastKnownTailnet, ignoreCase = true) }
        if (store.activeId() == null || store.get(store.activeId()!!) == null) {
            (activeMatch ?: created.firstOrNull())?.let { store.setActive(it.id) }
        }
        return Result(created, skipped, complete)
    }
}
