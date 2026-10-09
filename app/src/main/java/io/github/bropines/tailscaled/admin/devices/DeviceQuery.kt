package io.github.bropines.tailscaled.admin.devices

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The chips over the device list. Each one narrows it; together they all must hold. */
enum class DeviceFilter {
    NEEDS_APPROVAL,
    /** The node key expires within [DeviceQueries.EXPIRING_DAYS] days. */
    EXPIRING,
    /** Not connected for more than [DeviceQuery.offlineDays] days. */
    OFFLINE,
    /** Advertises or has approved routes: exit nodes and subnet routers. */
    ROUTERS,
    UPDATE,
    TAGGED,
    SHARED,
}

enum class DeviceSort {
    NAME,
    /** Online devices first, then the most recently seen. */
    LAST_SEEN,
    /** Newest first. */
    CREATED,
}

/**
 * What the device list shows: a search, whose devices, the filter chips and the order. [owner]
 * (a login name) and [tag] are exact values; they and the chips that are top-level device fields
 * go to the server as filters. Everything is also checked on the phone, so a server that ignores
 * a filter still gets the list right.
 */
data class DeviceQuery(
    val text: String = "",
    val owner: String? = null,
    val filters: Set<DeviceFilter> = emptySet(),
    /** With [DeviceFilter.TAGGED]: this one tag; null for any tag. */
    val tag: String? = null,
    val offlineDays: Int = 30,
    val sort: DeviceSort = DeviceSort.NAME,
) {
    val narrowed: Boolean get() = text.isNotBlank() || owner != null || filters.isNotEmpty()

    fun with(filter: DeviceFilter, on: Boolean): DeviceQuery =
        copy(filters = if (on) filters + filter else filters - filter, tag = if (filter == DeviceFilter.TAGGED && !on) null else tag)

    fun cleared(): DeviceQuery = DeviceQuery(offlineDays = offlineDays, sort = sort)
}

/** How a device's node key stands. */
sealed interface KeyExpiry {
    /** No expiry known: shared-in devices, servers that do not say. */
    data object Unknown : KeyExpiry
    data object Disabled : KeyExpiry
    data class Valid(val at: Long) : KeyExpiry
    /** Within [DeviceQueries.EXPIRING_DAYS]; [days] whole days left, 0 for today. */
    data class Expiring(val at: Long, val days: Int) : KeyExpiry
    data class Expired(val at: Long) : KeyExpiry
}

enum class BadgeKind {
    THIS_PHONE, SHARED, NEEDS_APPROVAL, LOCK_ERROR, MULTIPLE_CONNECTIONS,
    KEY_EXPIRED, KEY_EXPIRING, ROUTES_PENDING, EXIT_NODE, SUBNET_ROUTER, UPDATE,
}

/** One label on a device row; [days] for KEY_EXPIRING. */
data class DeviceBadge(val kind: BadgeKind, val days: Int = 0)

object DeviceQueries {
    const val EXPIRING_DAYS = 7
    const val EXIT_V4 = "0.0.0.0/0"
    const val EXIT_V6 = "::/0"
    const val DAY_MS = 24L * 3600 * 1000

    /** The offline thresholds the chip offers, in days. */
    val offlineChoices = listOf(1, 7, 30, 90)

    fun isExitRoute(route: String): Boolean = route == EXIT_V4 || route == EXIT_V6

    /**
     * The query's exact-match part as the API's `<field>=<value>` filters: top-level fields only,
     * never `fields` — the list with fields=all fails for tailnets with shared-in devices.
     */
    fun serverFilters(q: DeviceQuery): List<Pair<String, String>> = buildList {
        q.owner?.let { add("user" to it) }
        if (DeviceFilter.NEEDS_APPROVAL in q.filters) add("authorized" to "false")
        if (DeviceFilter.UPDATE in q.filters) add("updateAvailable" to "true")
        if (DeviceFilter.SHARED in q.filters) add("isExternal" to "true")
        if (DeviceFilter.TAGGED in q.filters) q.tag?.let { add("tags" to it) }
    }

    /** [devices] filtered by [q] and in its order; [routes] answers what is known of a device's routes. */
    fun apply(devices: List<ApiDevice>, q: DeviceQuery, now: Long, routes: (ApiDevice) -> DeviceRoutes?): List<ApiDevice> =
        sorted(devices.filter { matches(it, q, now, routes(it)) }, q.sort)

    fun matches(d: ApiDevice, q: DeviceQuery, now: Long, routes: DeviceRoutes?): Boolean {
        if (!matchesText(d, q.text)) return false
        if (q.owner != null && !d.user.equals(q.owner, ignoreCase = true)) return false
        return q.filters.all { matches(d, it, q, now, routes) }
    }

    fun matches(d: ApiDevice, f: DeviceFilter, q: DeviceQuery, now: Long, routes: DeviceRoutes?): Boolean = when (f) {
        DeviceFilter.NEEDS_APPROVAL -> d.authorized == false
        DeviceFilter.EXPIRING -> keyExpiry(d, now) is KeyExpiry.Expiring
        DeviceFilter.OFFLINE -> (offlineFor(d, now) ?: -1L) > q.offlineDays * DAY_MS
        DeviceFilter.ROUTERS -> routes != null && (routes.advertisedRoutes.isNotEmpty() || routes.enabledRoutes.isNotEmpty())
        DeviceFilter.UPDATE -> d.updateAvailable == true
        DeviceFilter.TAGGED -> q.tag?.let { it in d.tags } ?: d.isTagged
        DeviceFilter.SHARED -> d.isShared
    }

    /**
     * Every word of [text] must be found in the name, MagicDNS name, hostname, an address, a tag
     * (with or without "tag:") or the user; case does not matter.
     */
    fun matchesText(d: ApiDevice, text: String): Boolean {
        val words = text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val fields = buildList {
            add(d.shortName)
            add(d.name)
            d.hostname?.let { add(it) }
            addAll(d.addresses)
            addAll(d.tags)
            d.user?.let { add(it) }
        }.map { it.lowercase(Locale.ROOT) }
        return words.all { w -> fields.any { it.contains(w) } }
    }

    fun sorted(devices: List<ApiDevice>, sort: DeviceSort): List<ApiDevice> {
        val byName = compareBy<ApiDevice, String>(String.CASE_INSENSITIVE_ORDER) { it.shortName }.thenBy { it.pathId }
        return when (sort) {
            DeviceSort.NAME -> devices.sortedWith(byName)
            DeviceSort.LAST_SEEN -> devices.sortedWith(
                compareByDescending<ApiDevice> { it.isOnline }
                    .thenByDescending { instant(it.lastSeen) ?: Long.MIN_VALUE }
                    .then(byName)
            )
            DeviceSort.CREATED -> devices.sortedWith(compareByDescending<ApiDevice> { instant(it.created) ?: Long.MIN_VALUE }.then(byName))
        }
    }

    /** How long a device has been away: since it was last seen, or since it was added if it never was. Null while online. */
    fun offlineFor(d: ApiDevice, now: Long): Long? {
        if (d.isOnline) return null
        val since = instant(d.lastSeen) ?: instant(d.created) ?: return null
        return (now - since).coerceAtLeast(0)
    }

    fun keyExpiry(d: ApiDevice, now: Long): KeyExpiry {
        if (d.keyExpiryDisabled == true) return KeyExpiry.Disabled
        val at = instant(d.expires) ?: return KeyExpiry.Unknown
        val left = at - now
        return when {
            left <= 0 -> KeyExpiry.Expired(at)
            left <= EXPIRING_DAYS * DAY_MS -> KeyExpiry.Expiring(at, (left / DAY_MS).toInt())
            else -> KeyExpiry.Valid(at)
        }
    }

    /** What the device has as routes: the loaded routes, else the fields a full read carried, else unknown. */
    fun knownRoutes(d: ApiDevice, loaded: DeviceRoutes?): DeviceRoutes? = loaded
        ?: if (d.advertisedRoutes != null || d.enabledRoutes != null) DeviceRoutes(d.advertisedRoutes.orEmpty(), d.enabledRoutes.orEmpty()) else null

    fun badges(d: ApiDevice, now: Long, routes: DeviceRoutes?, selfNodeId: String?): List<DeviceBadge> = buildList {
        if (selfNodeId != null && d.nodeId == selfNodeId) add(DeviceBadge(BadgeKind.THIS_PHONE))
        if (d.isShared) add(DeviceBadge(BadgeKind.SHARED))
        if (d.authorized == false) add(DeviceBadge(BadgeKind.NEEDS_APPROVAL))
        if (!d.tailnetLockError.isNullOrBlank()) add(DeviceBadge(BadgeKind.LOCK_ERROR))
        if (d.multipleConnections == true) add(DeviceBadge(BadgeKind.MULTIPLE_CONNECTIONS))
        when (val k = keyExpiry(d, now)) {
            is KeyExpiry.Expired -> add(DeviceBadge(BadgeKind.KEY_EXPIRED))
            is KeyExpiry.Expiring -> add(DeviceBadge(BadgeKind.KEY_EXPIRING, k.days))
            else -> Unit
        }
        if (routes != null) {
            if (routes.advertisedRoutes.any { it !in routes.enabledRoutes }) add(DeviceBadge(BadgeKind.ROUTES_PENDING))
            if (routes.enabledRoutes.any(::isExitRoute)) add(DeviceBadge(BadgeKind.EXIT_NODE))
            if (routes.enabledRoutes.any { !isExitRoute(it) }) add(DeviceBadge(BadgeKind.SUBNET_ROUTER))
        }
        if (d.updateAvailable == true) add(DeviceBadge(BadgeKind.UPDATE))
    }

    /** "1.104.0" of "1.104.0-t1a2b3c4d-g5e6f7a8b9"; the API's own prefix "v" kept off too. */
    fun shortVersion(version: String?): String? =
        version?.trim()?.removePrefix("v")?.substringBefore('-')?.takeIf { it.isNotEmpty() }

    /** The API's OS names as people write them. */
    fun osName(os: String?): String? = when (os?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "linux" -> "Linux"
        "windows" -> "Windows"
        "macos", "darwin" -> "macOS"
        "ios" -> "iOS"
        "android" -> "Android"
        "freebsd" -> "FreeBSD"
        "openbsd" -> "OpenBSD"
        "tvos" -> "tvOS"
        else -> os.trim()
    }

    /**
     * An RFC 3339 stamp as the API writes one ("Z" or an offset, fractional seconds optional) in
     * epoch milliseconds; null for Go's zero time, an empty value and anything unreadable.
     */
    fun instant(stamp: String?): Long? {
        if (stamp.isNullOrBlank() || stamp.startsWith("0001-01-01")) return null
        val m = RFC3339.matchEntire(stamp.trim()) ?: return null
        val base = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(m.groupValues[1])?.time
        }.getOrNull() ?: return null
        val millis = m.groupValues[2].drop(1).padEnd(3, '0').take(3).toIntOrNull() ?: 0
        val zone = m.groupValues[3]
        val offset = if (zone == "Z" || zone == "z") 0L else {
            val sign = if (zone[0] == '-') -1 else 1
            sign * (zone.substring(1, 3).toLong() * 60 + zone.substring(4, 6).toLong()) * 60_000L
        }
        return base + millis - offset
    }

    private val RFC3339 = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")
}

/** Why an IPv4 address cannot be given to a device. */
enum class Ipv4Problem { MALFORMED, OUTSIDE_RANGE, RESERVED, TAKEN, UNCHANGED }

data class Ipv4Check(val problem: Ipv4Problem?, val takenBy: String? = null) {
    val ok: Boolean get() = problem == null
}

/**
 * The address a device may be given: dotted IPv4 inside Tailscale's 100.64.0.0/10, not one of
 * the addresses Tailscale keeps for itself, and not held by another device or service. The
 * server checks again (an IP pool may narrow the range); this catches the mistakes before the
 * gates.
 */
object Ipv4Rules {
    private const val CGNAT_BASE = 0x64400000L // 100.64.0.0
    private const val CGNAT_MASK = 0xFFC00000L // /10
    private const val CGNAT_LAST = 0x647FFFFFL // 100.127.255.255
    private const val QUAD_100 = 0x64646464L // 100.100.100.100
    private const val CHROMEOS_BASE = 0x64735C00L // 100.115.92.0
    private const val CHROMEOS_MASK = 0xFFFFFE00L // /23

    /** The address as a number, or null unless it is exactly four decimal octets without leading zeros. */
    fun parse(text: String): Long? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            val n = p.toInt()
            if (n > 255) return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    fun inTailnetRange(ip: Long): Boolean = ip and CGNAT_MASK == CGNAT_BASE

    fun isReserved(ip: Long): Boolean =
        ip == CGNAT_BASE || ip == CGNAT_LAST || ip == QUAD_100 ||
            ip and CHROMEOS_MASK == CHROMEOS_BASE

    /**
     * [taken] maps an address to whoever holds it: the other devices' IPv4 addresses and the
     * services' addresses, as far as they are loaded.
     */
    fun check(input: String, current: String?, taken: Map<String, String>): Ipv4Check {
        val text = input.trim()
        val ip = parse(text) ?: return Ipv4Check(Ipv4Problem.MALFORMED)
        if (!inTailnetRange(ip)) return Ipv4Check(Ipv4Problem.OUTSIDE_RANGE)
        if (isReserved(ip)) return Ipv4Check(Ipv4Problem.RESERVED)
        if (current != null && parse(current) == ip) return Ipv4Check(Ipv4Problem.UNCHANGED)
        taken.entries.firstOrNull { parse(it.key) == ip }?.let { return Ipv4Check(Ipv4Problem.TAKEN, it.value) }
        return Ipv4Check(null)
    }

    /** Who holds which IPv4 address among [devices] other than [except]. */
    fun holders(devices: List<ApiDevice>, except: ApiDevice): Map<String, String> =
        devices.filter { it.pathId != except.pathId }.flatMap { d -> d.addresses.filter { ':' !in it }.map { it to d.shortName } }.toMap()
}

/** A device name as a DNS label: letters, digits and inner hyphens, up to 63 characters. */
object DeviceNames {
    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun isValid(name: String): Boolean = LABEL.matches(name.trim())

    /** Whether [name] (trimmed) may be sent: blank resets to the hostname, anything else must be a new valid label. */
    fun renameAllowed(device: ApiDevice, name: String): Boolean =
        name.isEmpty() || (isValid(name) && !name.equals(device.shortName, ignoreCase = true))

    /**
     * Roughly the name the server makes of a hostname: its first label in lower case, spaces and
     * underscores as hyphens, anything else left out. A guess for the preview and the re-read.
     */
    fun fromHostname(hostname: String?): String? = hostname?.trim()?.lowercase(Locale.ROOT)
        ?.substringBefore('.')
        ?.replace(Regex("[\\s_]+"), "-")
        ?.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        ?.replace(Regex("-{2,}"), "-")
        ?.trim('-')
        ?.take(63)
        ?.takeIf { it.isNotEmpty() }
}
