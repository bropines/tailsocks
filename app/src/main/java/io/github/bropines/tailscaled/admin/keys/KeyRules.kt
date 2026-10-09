package io.github.bropines.tailscaled.admin.keys

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.KeyType
import io.github.bropines.tailscaled.admin.parseIso
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What the Keys tab decides without a screen: how keys group and order, how far an expiry is,
 * what a new key may be (description, expiry, tags), which tags the policy lets this
 * credential put on a key, and an OAuth client's scopes. All of it is plain Kotlin, tested.
 */

/** The tab's sections, in the order they show: what devices join with first, people's tokens last. */
enum class KeyGroup(val type: KeyType) {
    AUTH(KeyType.AUTH), CLIENT(KeyType.CLIENT), FEDERATED(KeyType.FEDERATED), API(KeyType.API), OTHER(KeyType.UNKNOWN);

    companion object {
        fun of(type: KeyType): KeyGroup = entries.first { it.type == type }
    }
}

data class KeySection(val group: KeyGroup, val keys: List<ApiKey>)

enum class ExpiryState {
    /** No expiry: OAuth clients, federated identities. */
    NEVER,
    OK,
    /** Within [KeyList.WARN_MS]: worth a look before something stops working. */
    SOON,
    EXPIRED,
}

/** How far [remainingMs] is, negative once past; 0 with [ExpiryState.NEVER]. */
data class KeyExpiry(val state: ExpiryState, val remainingMs: Long = 0)

enum class TimeUnitShown { MINUTES, HOURS, DAYS }

/** A span as one count of one unit, rounded down, never below one. */
data class Span(val unit: TimeUnitShown, val amount: Int)

object KeyList {
    const val WARN_MS = 7L * 24 * 3600 * 1000
    private const val HOUR_MS = 3600L * 1000
    private const val DAY_MS = 24 * HOUR_MS

    fun expiryOf(expires: String?, now: Long): KeyExpiry {
        val at = parseIso(expires)?.time ?: return KeyExpiry(ExpiryState.NEVER)
        val left = at - now
        val state = when {
            left <= 0 -> ExpiryState.EXPIRED
            left <= WARN_MS -> ExpiryState.SOON
            else -> ExpiryState.OK
        }
        return KeyExpiry(state, left)
    }

    /** Still good for something: not revoked, not reported invalid, not past its expiry. */
    fun isUsable(k: ApiKey, now: Long): Boolean =
        !k.isRevoked && k.invalid != true && expiryOf(k.expires, now).state != ExpiryState.EXPIRED

    /**
     * Keys by type, sections in [KeyGroup] order and only those with keys. Inside a section the
     * usable keys come first, the soonest to expire on top (those without an expiry after
     * them), then the dead ones, newest first.
     */
    fun sections(keys: List<ApiKey>, now: Long): List<KeySection> =
        keys.groupBy { KeyGroup.of(it.type) }.toSortedMap().map { (group, list) ->
            val (usable, dead) = list.partition { isUsable(it, now) }
            KeySection(
                group,
                usable.sortedWith(compareBy<ApiKey> { parseIso(it.expires)?.time ?: Long.MAX_VALUE }.thenByDescending { it.created.orEmpty() }) +
                    dead.sortedByDescending { it.created.orEmpty() },
            )
        }

    /** [ms] (its absolute value) in days, else hours, else minutes. */
    fun span(ms: Long): Span {
        val a = kotlin.math.abs(ms)
        return when {
            a >= DAY_MS -> Span(TimeUnitShown.DAYS, (a / DAY_MS).toInt())
            a >= HOUR_MS -> Span(TimeUnitShown.HOURS, (a / HOUR_MS).toInt())
            else -> Span(TimeUnitShown.MINUTES, (a / 60_000L).toInt().coerceAtLeast(1))
        }
    }

    /** The area whose write scope revoking a key of [type] needs. */
    fun revokeArea(type: KeyType): AdminArea = when (type) {
        KeyType.CLIENT -> AdminArea.OAUTH_KEYS
        KeyType.FEDERATED -> AdminArea.FEDERATED_KEYS
        KeyType.API -> AdminArea.API_TOKENS
        KeyType.AUTH, KeyType.UNKNOWN -> AdminArea.AUTH_KEYS
    }
}

/** The expiry a new auth key is offered with; [CUSTOM] takes a number of days. */
enum class ExpiryPreset(val seconds: Long?) {
    HOUR(3600), DAY(86_400), WEEK(7 * 86_400L), MONTH(30 * 86_400L), QUARTER(90 * 86_400L), CUSTOM(null);
}

enum class DescriptionProblem { TOO_LONG, BAD_CHARACTERS, MISSING }

object KeyRules {
    /** The API's limit on a key's description. */
    const val DESCRIPTION_MAX = 50
    /** An auth key lives at most this long. */
    const val MAX_DAYS = 90

    private val DESCRIPTION_CHARS = Regex("[A-Za-z0-9 \\-]*")
    private val TAG = Regex("tag:[A-Za-z0-9][A-Za-z0-9_\\-]*")
    private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    /**
     * What is wrong with a description, as the schema puts it: at most fifty letters and
     * digits, hyphens and spaces allowed. Null when it is fine; empty is fine unless [required].
     */
    fun descriptionProblem(text: String, required: Boolean = false): DescriptionProblem? = when {
        text.isBlank() && required -> DescriptionProblem.MISSING
        text.length > DESCRIPTION_MAX -> DescriptionProblem.TOO_LONG
        !DESCRIPTION_CHARS.matches(text) -> DescriptionProblem.BAD_CHARACTERS
        else -> null
    }

    /** Seconds for [preset], or for [customDays] when CUSTOM; null when the days are not 1–90. */
    fun expirySeconds(preset: ExpiryPreset, customDays: String): Long? =
        preset.seconds ?: customDays.trim().toLongOrNull()?.takeIf { it in 1..MAX_DAYS }?.let { it * 86_400 }

    fun isTag(text: String): Boolean = TAG.matches(text)

    /**
     * Tags typed by hand, comma- or space-separated; "server" becomes "tag:server". Returns
     * the good ones and, apart, what could not be a tag.
     */
    fun typedTags(text: String): Pair<List<String>, List<String>> {
        val parts = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("tag:")) it else "tag:$it" }
        return parts.filter(::isTag).distinct() to parts.filterNot(::isTag)
    }

    fun isEmail(text: String): Boolean = EMAIL.matches(text.trim())
}

/**
 * The policy's tagOwners: which tags exist and who may hand them out. The policy is HuJSON —
 * comments and trailing commas — which [parse] reads without a HuJSON library.
 */
object TagOwners {

    /** tag → its owners; null when [policy] cannot be read as a policy at all. */
    fun parse(policy: String): Map<String, List<String>>? {
        val root = runCatching { AppJson.parseToJsonElement(standardize(policy)) }.getOrNull() as? JsonObject ?: return null
        val owners = root.entries.firstOrNull { it.key.equals("tagOwners", ignoreCase = true) }?.value ?: return emptyMap()
        val obj = owners as? JsonObject ?: return null
        return obj.filterKeys { it.startsWith("tag:") }.mapValues { (_, v) ->
            (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        }.toSortedMap()
    }

    /**
     * The tags a new key may carry. A personal token acts with its owner's role and may use
     * any tag the policy defines; an OAuth client only its own tags and the tags those own.
     */
    fun assignable(owners: Map<String, List<String>>, credentialTags: List<String>, scoped: Boolean): List<String> =
        if (!scoped) owners.keys.sorted()
        else (credentialTags + owners.filterValues { list -> list.any { it in credentialTags } }.keys).distinct().sorted()

    /** HuJSON as plain JSON: comments blanked out and trailing commas dropped, strings left alone. */
    fun standardize(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    out.append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; out.append(c); i++ }
                c == '/' && text.getOrNull(i + 1) == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    out.append(' ')
                }
                c == ',' -> {
                    // A comma with only blanks and comments before the closing bracket goes.
                    var j = i + 1
                    while (j < text.length) {
                        when {
                            text[j].isWhitespace() -> j++
                            text[j] == '/' && text.getOrNull(j + 1) == '/' -> { while (j < text.length && text[j] != '\n') j++ }
                            text[j] == '/' && text.getOrNull(j + 1) == '*' -> {
                                val end = text.indexOf("*/", j + 2)
                                j = if (end < 0) text.length else end + 2
                            }
                            else -> break
                        }
                    }
                    if (j >= text.length || text[j] != '}' && text[j] != ']') out.append(c)
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

/** How far an OAuth client reaches into one area. */
enum class ScopeLevel { NONE, READ, WRITE }

/** One row of the scope picker: the scope's name and whether it has a write half at all. */
data class ScopeOption(val scope: String, val readOnly: Boolean, val area: AdminArea?)

object OAuthScopes {
    const val ALL = "all"

    /** "all" first, then every area this app knows, in the order the console lists them. */
    val options: List<ScopeOption> =
        listOf(ScopeOption(ALL, readOnly = false, area = null)) + AdminArea.entries.map { ScopeOption(it.scope, it.readOnly, it) }

    /**
     * The scopes a picker's levels ask for. "all" at a level makes the same level of every
     * other area redundant: those are dropped, a higher one stays.
     */
    fun toScopes(levels: Map<String, ScopeLevel>): List<String> {
        val all = levels[ALL] ?: ScopeLevel.NONE
        if (all == ScopeLevel.WRITE) return listOf(ALL)
        return options.mapNotNull { o ->
            val level = levels[o.scope] ?: ScopeLevel.NONE
            when {
                o.scope == ALL -> if (all == ScopeLevel.READ) "$ALL:read" else null
                level == ScopeLevel.NONE -> null
                level == ScopeLevel.READ && all == ScopeLevel.READ -> null
                level == ScopeLevel.WRITE && !o.readOnly -> o.scope
                else -> "${o.scope}:read"
            }
        }
    }

    /** Tags are mandatory once a client may write devices or auth keys. */
    fun needsTags(scopes: List<String>): Boolean = scopes.any { it == ALL || it == AdminArea.DEVICES.scope || it == AdminArea.AUTH_KEYS.scope }
}
