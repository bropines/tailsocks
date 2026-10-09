package io.github.bropines.tailscaled.admin.notify

import android.content.Context
import io.github.bropines.tailscaled.admin.secure.KeyValueStore
import io.github.bropines.tailscaled.admin.secure.PrefsKeyValueStore
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** A profile's background check: off by default, hourly once on. */
data class AttentionChecks(val enabled: Boolean = false, val intervalMinutes: Int = DEFAULT_INTERVAL) {
    companion object {
        /** WorkManager's shortest period is 15 minutes; 6 hours is the longest offered. */
        val INTERVALS = listOf(15, 60, 360)
        const val DEFAULT_INTERVAL = 60
    }
}

/**
 * The background check's state per admin profile, in its own preference file
 * (`admin_attention`): whether it runs, how often, and the keys of what it already notified.
 * No secrets and no tailnet data beyond ids — the profiles and the vault stay where they are.
 */
class AttentionPrefs(private val store: KeyValueStore) {

    private val list = ListSerializer(String.serializer())

    fun checks(profileId: String): AttentionChecks = AttentionChecks(
        enabled = store.getString(key(profileId, ENABLED)) == "1",
        intervalMinutes = store.getString(key(profileId, INTERVAL))?.toIntOrNull()?.takeIf { it in AttentionChecks.INTERVALS }
            ?: AttentionChecks.DEFAULT_INTERVAL,
    )

    @Synchronized
    fun setChecks(profileId: String, checks: AttentionChecks): Boolean =
        store.putString(key(profileId, ENABLED), if (checks.enabled) "1" else null) and
            store.putString(key(profileId, INTERVAL), checks.intervalMinutes.toString())

    fun seen(profileId: String): Set<String> =
        store.getString(key(profileId, SEEN))?.let { runCatching { AppJson.decodeFromString(list, it).toSet() }.getOrNull() } ?: emptySet()

    @Synchronized
    fun setSeen(profileId: String, seen: Set<String>): Boolean =
        store.putString(key(profileId, SEEN), if (seen.isEmpty()) null else AppJson.encodeToString(list, seen.sorted()))

    /** Every profile with anything stored here. */
    fun profileIds(): Set<String> = store.keys().mapNotNullTo(mutableSetOf()) { k -> k.substringBefore('/').takeIf { '/' in k } }

    @Synchronized
    fun forget(profileId: String) {
        listOf(ENABLED, INTERVAL, SEEN).forEach { store.putString(key(profileId, it), null) }
    }

    private fun key(profileId: String, field: String) = "$profileId/$field"

    companion object {
        const val PREFS_NAME = "admin_attention"
        private const val ENABLED = "checks"
        private const val INTERVAL = "interval"
        private const val SEEN = "seen"

        fun of(context: Context) = AttentionPrefs(
            PrefsKeyValueStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        )
    }
}
