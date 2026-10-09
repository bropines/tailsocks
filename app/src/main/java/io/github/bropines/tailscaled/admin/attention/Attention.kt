package io.github.bropines.tailscaled.admin.attention

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.api.KeyType
import io.github.bropines.tailscaled.admin.api.UserStatus
import java.text.SimpleDateFormat
import java.util.Locale

/** Which read an item comes from: a source that was read decides which items still stand. */
enum class AttentionSource { DEVICES, USERS, KEYS, CREDENTIAL }

/** How the home screen groups items, most urgent first. */
enum class AttentionSection { APPROVALS, CREDENTIAL, EXPIRY, PROBLEMS, ROUTES, UPDATES }

/**
 * One kind of thing that needs a person. [area] is what its action writes, null where the
 * action only opens something (the device, the keys, the profile); [notifies] marks the kinds
 * the background check posts a notification for.
 */
enum class AttentionKind(val section: AttentionSection, val source: AttentionSource, val area: AdminArea?, val notifies: Boolean) {
    DEVICE_APPROVAL(AttentionSection.APPROVALS, AttentionSource.DEVICES, AdminArea.DEVICES, notifies = true),
    USER_APPROVAL(AttentionSection.APPROVALS, AttentionSource.USERS, AdminArea.USERS, notifies = true),
    /** The console's own credential was refused (401): nothing else can be read. */
    CREDENTIAL_REFUSED(AttentionSection.CREDENTIAL, AttentionSource.CREDENTIAL, null, notifies = true),
    CREDENTIAL_EXPIRING(AttentionSection.CREDENTIAL, AttentionSource.CREDENTIAL, null, notifies = true),
    DEVICE_KEY_EXPIRING(AttentionSection.EXPIRY, AttentionSource.DEVICES, null, notifies = true),
    AUTH_KEY_EXPIRING(AttentionSection.EXPIRY, AttentionSource.KEYS, null, notifies = true),
    TAILNET_LOCK_ERROR(AttentionSection.PROBLEMS, AttentionSource.DEVICES, null, notifies = false),
    MULTIPLE_CONNECTIONS(AttentionSection.PROBLEMS, AttentionSource.DEVICES, null, notifies = false),
    ROUTES_PENDING(AttentionSection.ROUTES, AttentionSource.DEVICES, AdminArea.ROUTES, notifies = false),
    UPDATE_AVAILABLE(AttentionSection.UPDATES, AttentionSource.DEVICES, null, notifies = false),
}

/**
 * Something in the tailnet that waits for a person: what ([kind]), on what ([targetId],
 * [targetName]), and what the row needs to offer its one action — the device, user or key it
 * is about, the expiry, the routes waiting for approval.
 */
data class AttentionItem(
    val kind: AttentionKind,
    val targetId: String,
    val targetName: String,
    /** RFC 3339, for the expiring kinds, and the same instant in epoch milliseconds. */
    val expires: String? = null,
    val expiresAt: Long? = null,
    /** Advertised and not yet approved, for ROUTES_PENDING. */
    val pendingRoutes: List<String> = emptyList(),
    /** What the device has approved now, for ROUTES_PENDING: the "before" of approving the rest. */
    val enabledRoutes: List<String> = emptyList(),
    val device: ApiDevice? = null,
    val user: ApiUser? = null,
    val key: ApiKey? = null,
) {
    /** Stable on screen: one row per kind and target. */
    val id: String get() = "${kind.name}:$targetId"

    /**
     * What "already notified" remembers. An expiry carries its date, so a key that is renewed
     * and comes up for expiry again later is news again.
     */
    val notifyKey: String get() = if (expires != null) "$id:$expires" else id
}

/**
 * What the attention list is computed from. A null list was not read (no access, not loaded
 * yet, failed); its kinds are simply absent, never reported as "nothing to do".
 */
data class AttentionInput(
    val devices: List<ApiDevice>? = null,
    val users: List<ApiUser>? = null,
    val keys: List<ApiKey>? = null,
    /** By device path id, as far as they were read: the device list itself carries no routes. */
    val routes: Map<String, DeviceRoutes> = emptyMap(),
    val ownKeyId: String? = null,
    /** The console's own credential's expiry, from its own key entry. */
    val credentialExpires: String? = null,
    val credentialRefused: Boolean = false,
)

/**
 * The "needs attention" list as a pure function of what was read, so the home screen and the
 * background check agree on it and a test can pin it down.
 */
object Attention {
    const val DEFAULT_WINDOW_DAYS = 7
    private const val DAY_MS = 24L * 3600 * 1000
    private const val EXIT_V4 = "0.0.0.0/0"
    private const val EXIT_V6 = "::/0"

    /**
     * Everything that needs attention at [now]. Device keys and auth keys count when they
     * expire within [windowDays]; a device key that ran out in the last [windowDays] still
     * counts (the device is cut off until someone logs it in again), older ones do not.
     */
    fun compute(input: AttentionInput, now: Long, windowDays: Int = DEFAULT_WINDOW_DAYS): List<AttentionItem> {
        val window = windowDays * DAY_MS
        val out = mutableListOf<AttentionItem>()

        if (input.credentialRefused) {
            out += AttentionItem(AttentionKind.CREDENTIAL_REFUSED, input.ownKeyId ?: "-", input.ownKeyId ?: "")
        }
        val ownExpires = input.credentialExpires
            ?: input.ownKeyId?.let { own -> input.keys?.firstOrNull { it.id == own }?.expires }
        if (!input.credentialRefused) Rfc3339.millis(ownExpires)?.let { at ->
            if (at > now && at - now <= window) {
                out += AttentionItem(AttentionKind.CREDENTIAL_EXPIRING, input.ownKeyId ?: "-", input.ownKeyId ?: "", ownExpires, at)
            }
        }

        input.devices?.forEach { d ->
            val mine = !d.isShared
            if (mine && d.authorized == false) out += deviceItem(AttentionKind.DEVICE_APPROVAL, d)
            if (mine && d.authorized != false && d.keyExpiryDisabled != true && d.isEphemeral != true) {
                Rfc3339.millis(d.expires)?.let { at ->
                    val left = at - now
                    if (left <= window && left > -window) out += deviceItem(AttentionKind.DEVICE_KEY_EXPIRING, d).copy(expires = d.expires, expiresAt = at)
                }
            }
            if (!d.tailnetLockError.isNullOrBlank()) out += deviceItem(AttentionKind.TAILNET_LOCK_ERROR, d)
            if (mine && d.multipleConnections == true) out += deviceItem(AttentionKind.MULTIPLE_CONNECTIONS, d)
            if (mine) {
                val read = input.routes[d.pathId]
                val advertised = read?.advertisedRoutes ?: d.advertisedRoutes
                val enabled = read?.enabledRoutes ?: d.enabledRoutes.orEmpty()
                val pending = advertised.orEmpty().filter { it !in enabled }
                if (pending.isNotEmpty()) out += deviceItem(AttentionKind.ROUTES_PENDING, d).copy(pendingRoutes = pending, enabledRoutes = enabled)
            }
            if (mine && d.updateAvailable == true) out += deviceItem(AttentionKind.UPDATE_AVAILABLE, d)
        }

        input.users?.forEach { u ->
            if (u.userStatus == UserStatus.NEEDS_APPROVAL) {
                out += AttentionItem(AttentionKind.USER_APPROVAL, u.id, u.loginName.ifBlank { u.name }, user = u)
            }
        }

        input.keys?.forEach { k ->
            if (k.type != KeyType.AUTH || k.isRevoked || k.invalid == true) return@forEach
            if (input.ownKeyId != null && k.id == input.ownKeyId) return@forEach
            Rfc3339.millis(k.expires)?.let { at ->
                val left = at - now
                if (left in 1..window) {
                    out += AttentionItem(
                        AttentionKind.AUTH_KEY_EXPIRING, k.id, k.description?.takeIf { it.isNotBlank() } ?: k.id,
                        expires = k.expires, expiresAt = at, key = k,
                    )
                }
            }
        }

        return out.distinctBy { it.id }.sortedWith(
            compareBy<AttentionItem> { it.kind.section.ordinal }
                .thenBy { it.kind.ordinal }
                .thenBy { it.expiresAt ?: Long.MAX_VALUE }
                .thenBy { it.targetName.lowercase() }
        )
    }

    private fun deviceItem(kind: AttentionKind, d: ApiDevice) = AttentionItem(kind, d.pathId, d.shortName, device = d)

    /** Whether [routes] include an exit node route: approving it routes the tailnet's internet. */
    fun hasExitRoute(routes: List<String>): Boolean = EXIT_V4 in routes || EXIT_V6 in routes

    /** Which sources an item list may be trusted for, given which reads succeeded. */
    fun sourcesRead(devices: Boolean, users: Boolean, keys: Boolean, credential: Boolean): Set<AttentionSource> = buildSet {
        if (devices) add(AttentionSource.DEVICES)
        if (users) add(AttentionSource.USERS)
        if (keys) add(AttentionSource.KEYS)
        if (credential) add(AttentionSource.CREDENTIAL)
    }
}

/** RFC 3339 instants as the API writes them, with or without fractional seconds. */
internal object Rfc3339 {
    private val FRACTION = Regex("""\.\d+""")

    /** Milliseconds since the epoch; null for blank, the zero time and anything unreadable. */
    fun millis(s: String?): Long? {
        val t = s?.trim()
        if (t.isNullOrEmpty() || t.startsWith("0001-01-01")) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(t.replaceFirst(FRACTION, ""))?.time
        } catch (_: Exception) {
            null
        }
    }
}
