package io.github.bropines.tailscaled.admin.profile

import io.github.bropines.tailscaled.admin.secure.KeyValueStore
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * The admin profiles and which one is active, as JSON in one preference file
 * (`admin_profiles`). Metadata only: the secrets are in the vault.
 */
class AdminProfileStore(private val store: KeyValueStore) {

    private val serializer = ListSerializer(AdminProfile.serializer())

    @Synchronized
    fun profiles(): List<AdminProfile> =
        store.getString(KEY_PROFILES)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    fun get(id: String): AdminProfile? = profiles().firstOrNull { it.id == id }

    @Synchronized
    fun activeId(): String? = store.getString(KEY_ACTIVE)

    /** The active profile; the first one when the stored choice is gone; null with none at all. */
    fun active(): AdminProfile? {
        val all = profiles()
        return all.firstOrNull { it.id == activeId() } ?: all.firstOrNull()
    }

    @Synchronized
    fun setActive(id: String): Boolean = store.putString(KEY_ACTIVE, id)

    /** Adds [profile] or replaces the one with its id. */
    @Synchronized
    fun save(profile: AdminProfile): Boolean {
        val all = profiles()
        val next = if (all.any { it.id == profile.id }) all.map { if (it.id == profile.id) profile else it } else all + profile
        return store.putString(KEY_PROFILES, AppJson.encodeToString(serializer, next))
    }

    @Synchronized
    fun update(id: String, change: (AdminProfile) -> AdminProfile): AdminProfile? {
        val current = get(id) ?: return null
        val next = change(current)
        if (next != current) save(next)
        return next
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val next = profiles().filterNot { it.id == id }
        val ok = store.putString(KEY_PROFILES, AppJson.encodeToString(serializer, next))
        if (activeId() == id) store.putString(KEY_ACTIVE, next.firstOrNull()?.id)
        return ok
    }

    /**
     * The profile for the tailnet whose MagicDNS suffix is [dnsSuffix]: the one that learned
     * that suffix, else the only profile when it has not learned any. Null otherwise — a guess
     * would send the device list request to the wrong tailnet.
     */
    fun forTailnet(dnsSuffix: String?): AdminProfile? {
        val suffix = dnsSuffix?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
        val all = profiles()
        if (suffix != null) all.firstOrNull { it.tailnetDnsName.equals(suffix, ignoreCase = true) }?.let { return it }
        return all.singleOrNull()?.takeIf { it.tailnetDnsName.isBlank() }
    }

    companion object {
        const val PREFS_NAME = "admin_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active"
    }
}
