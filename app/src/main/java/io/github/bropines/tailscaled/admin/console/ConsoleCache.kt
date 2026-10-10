package io.github.bropines.tailscaled.admin.console

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDerpMap
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.secure.KeystoreSecretBox
import io.github.bropines.tailscaled.admin.secure.SecretBox
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The console's last good lists, one file per profile, so it opens on them at once and
 * refreshes in place. Sealed like the credentials, under a Keystore key of its own: a copied
 * data directory holds only ciphertext, and a restore on another phone finds it unreadable and
 * starts empty (files/admin is out of backups anyway). Left out: webhooks (their URLs often
 * carry a token), invites (their links admit people), the audit log and the policy file.
 * Tailscale's public relay map is kept: the policy editor's Relays page opens on it at once.
 */
class ConsoleCache(private val dir: File, private val box: SecretBox) {

    @Serializable
    data class Entry<T>(val value: T, val at: Long)

    @Serializable
    data class Snapshot(
        val version: Int = VERSION,
        val devices: Entry<List<ApiDevice>>? = null,
        val users: Entry<List<ApiUser>>? = null,
        val keys: Entry<List<ApiKey>>? = null,
        val dns: Entry<DnsConfiguration>? = null,
        val settings: Entry<TailnetSettings>? = null,
        val services: Entry<List<ApiService>>? = null,
        val policyTags: List<String> = emptyList(),
        val derpMap: Entry<ApiDerpMap>? = null,
    )

    /** The profile's snapshot; null when there is none or it cannot be opened (then it is dropped). */
    fun read(profileId: String): Snapshot? {
        val f = file(profileId)
        if (!f.exists()) return null
        return try {
            AppJson.decodeFromString(Snapshot.serializer(), box.open(f.readText(), aad(profileId)))
                .takeIf { it.version == VERSION }
                ?: null.also { f.delete() }
        } catch (_: Exception) {
            // Another phone's key, a reset Keystore, a damaged file: start empty.
            f.delete()
            null
        }
    }

    /** Replaces the profile's snapshot; one too large to be worth keeping removes it instead. */
    fun write(profileId: String, snapshot: Snapshot) {
        val json = AppJson.encodeToString(Snapshot.serializer(), snapshot)
        val f = file(profileId)
        if (json.length > MAX_CHARS) {
            f.delete()
            return
        }
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(box.seal(json, aad(profileId)))
        if (!tmp.renameTo(f)) tmp.delete()
    }

    fun clear(profileId: String) {
        file(profileId).delete()
    }

    private fun file(profileId: String) = File(dir, profileId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "profile" })

    companion object {
        const val VERSION = 1
        /** A few thousand devices; past that the copy costs more to seal than it saves. */
        private const val MAX_CHARS = 8_000_000
        private const val KEY_ALIAS = "tailsocks_admin_cache"

        fun of(filesDir: File) = ConsoleCache(File(filesDir, "admin/cache"), KeystoreSecretBox(KEY_ALIAS))

        /** Binds a file to its profile: one profile's copy moved into another's name does not open. */
        private fun aad(profileId: String) = "admin-console-cache:$profileId"

        /** What of [s] is worth keeping: what was read and may still be read, secrets stripped. */
        fun snapshotOf(s: ConsoleState): Snapshot = Snapshot(
            devices = s.devices.entry(s, AdminArea.DEVICES),
            users = s.users.entry(s, AdminArea.USERS),
            // The list never carries a key's secret; a creation's answer might, should it land here.
            keys = s.keys.entry(s, AdminArea.AUTH_KEYS)?.let { e -> e.copy(value = e.value.map { it.copy(key = null) }) },
            dns = s.dns.entry(s, AdminArea.DNS),
            settings = s.settings.entry(s, AdminArea.SETTINGS),
            services = s.services.entry(s, AdminArea.SERVICES),
            policyTags = s.policyTags,
            derpMap = s.policy.derpMap.let { d -> d.value?.let { Entry(it, d.loadedAt) } },
        )

        private fun <T> Loadable<T>.entry(s: ConsoleState, area: AdminArea): Entry<T>? =
            value?.takeIf { s.canRead(area) }?.let { Entry(it, loadedAt) }

        /**
         * Changes whenever a section was read from the server since the copy was opened; null
         * while everything on screen still came from disk, when there is nothing new to keep.
         */
        fun writeKey(s: ConsoleState): List<Any>? {
            val parts = listOf(s.devices, s.users, s.keys, s.dns, s.settings, s.services, s.policy.derpMap)
            if (parts.none { it.value != null && !it.fromDisk }) return null
            return parts.map { it.loadedAt } + s.policyTags.hashCode()
        }

        /** [s] with what [snapshot] kept, marked as from disk until the server answers again. */
        fun seed(s: ConsoleState, snapshot: Snapshot?): ConsoleState {
            if (snapshot == null) return s
            fun <T> Entry<T>?.loadable(current: Loadable<T>) = this?.let { Loadable(it.value, loadedAt = it.at, fromDisk = true) } ?: current
            return s.copy(
                devices = snapshot.devices.loadable(s.devices),
                users = snapshot.users.loadable(s.users),
                keys = snapshot.keys.loadable(s.keys),
                dns = snapshot.dns.loadable(s.dns),
                settings = snapshot.settings.loadable(s.settings),
                services = snapshot.services.loadable(s.services),
                policyTags = s.policyTags.ifEmpty { snapshot.policyTags },
                policy = s.policy.copy(derpMap = snapshot.derpMap.loadable(s.policy.derpMap)),
            )
        }
    }
}
