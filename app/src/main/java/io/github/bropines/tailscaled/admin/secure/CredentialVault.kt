package io.github.bropines.tailscaled.admin.secure

/**
 * The admin console's secrets, one sealed blob per profile and slot. Nothing here is ever in
 * plain text on disk; what it hands out lives in memory for as long as a backend needs it.
 */
class CredentialVault(private val store: KeyValueStore, private val box: SecretBox) {

    enum class Slot {
        /** A personal API access token, `tskey-api-…`. */
        API_TOKEN,
        /** An OAuth client's secret, `tskey-client-…`; the client id is not secret and lives in the profile. */
        OAUTH_SECRET,
        /** A Headscale API key. */
        HEADSCALE_KEY,
        /** The password of the profile's own SOCKS5 proxy. */
        PROXY_PASSWORD,
    }

    private fun key(profileId: String, slot: Slot) = "$profileId/${slot.name}"
    private fun aad(profileId: String, slot: Slot) = "tailsocks-admin/$profileId/${slot.name}"

    /** Seals and stores [secret]; a blank one removes the slot. False when the write did not land. */
    fun put(profileId: String, slot: Slot, secret: String): Boolean =
        if (secret.isBlank()) store.putString(key(profileId, slot), null)
        else store.putString(key(profileId, slot), box.seal(secret, aad(profileId, slot)))

    /** The secret, null when none is stored; throws when one is stored and cannot be opened. */
    @Throws(SecretUnavailableException::class)
    fun get(profileId: String, slot: Slot): String? =
        store.getString(key(profileId, slot))?.let { box.open(it, aad(profileId, slot)) }

    fun has(profileId: String, slot: Slot): Boolean = store.getString(key(profileId, slot)) != null

    fun removeProfile(profileId: String) {
        Slot.entries.forEach { store.putString(key(profileId, it), null) }
    }
}
