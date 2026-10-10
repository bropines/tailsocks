package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDerpLatency
import io.github.bropines.tailscaled.admin.api.ApiDerpMap
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import kotlin.math.roundToInt

/*
 * What the Relays page decides, apart from how it looks: the policy's derpMap as written
 * (DerpFile), Tailscale's regions with what the file and the devices say about each
 * (RelayRow), and what excluding one would cost (ExcludeImpact). The page edits through
 * PolicyEdits.setDerpRegionExcluded only.
 */

/** What a key under derpMap.Regions says. */
enum class DerpEntryKind {
    /** `null`: Tailscale's region of that id is not used. */
    EXCLUDED,
    /** An object: a region of the tailnet's own, or one that replaces Tailscale's of that id. */
    CUSTOM,
    /** Anything else: the server's to judge; shown, edited in JSON. */
    OTHER,
}

/** One key under derpMap.Regions as written: its id, what it says, where it is and its comment. */
data class DerpEntry(
    val id: String,
    val kind: DerpEntryKind,
    val origin: Origin,
    val code: String? = null,
    val name: String? = null,
    val servers: Int = 0,
)

/** Why the Relays page shows the relay map and edits none of it. */
enum class DerpLock {
    /** derpMap under another spelling ("DERPMap"): the server reads it, an edit would write a second one. */
    SPELLING,
    /** derpMap or its Regions holds something other than an object. */
    SHAPE,
    /** Regions written twice (in two spellings, say), or one region id twice: which counts is the server's guess. */
    TWICE,
}

/** The policy's derpMap as the Relays page reads it. */
data class DerpFile(
    /** The derpMap section; null when the file has none under its documented key. */
    val origin: Origin?,
    /** Regions' key as the file spells it, "Regions" when it has none: where an exclusion is written. */
    val regionsKey: String,
    /** OmitDefaultRegions is true: none of Tailscale's regions are used, excluded or not. */
    val omitDefaults: Boolean,
    val entries: List<DerpEntry>,
    val lock: DerpLock?,
    /** The key a [DerpLock.SPELLING] lock is about, as written. */
    val oddKey: String? = null,
) {
    val excluded: Set<String> get() = entries.filter { it.kind == DerpEntryKind.EXCLUDED }.map { it.id }.toSet()

    /** Regions the file defines or writes oddly: shown as written, edited in JSON. */
    val ownEntries: List<DerpEntry> get() = entries.filter { it.kind != DerpEntryKind.EXCLUDED }

    /** The file's own regions that have servers: what still carries traffic with every default region gone. */
    val ownServing: Int get() = entries.count { it.kind == DerpEntryKind.CUSTOM && it.servers > 0 }

    companion object {
        private val DERP = Section.DERP_MAP.key

        fun read(text: String): DerpFile? = SourceTree.parseOrNull(text)?.let(::read)

        fun read(t: SourceTree): DerpFile {
            val root = t.root
            val lines = HuJson.Lines(t.text)
            fun origin(c: SrcContainer, i: Int, path: PolicyPath): Origin {
                val cm = t.commentsOf(c, i)
                return Origin(path, lines.lineOf(c.members[i].start), cm.note, cm.header, cm.trailing)
            }
            val odd = root.members.firstOrNull { it.key != DERP && it.key.equals(DERP, ignoreCase = true) }?.key
            val i = root.indexOf(DERP)
            val derp = if (i >= 0) root.members[i].value else null
            val obj = derp as? SrcObject
            val spellings = obj?.members?.filter { it.key.equals("Regions", ignoreCase = true) }.orEmpty()
            val regionsKey = spellings.lastOrNull()?.key ?: "Regions"
            val regions = spellings.lastOrNull()?.value
            val base = PolicyPath.of(DERP, regionsKey)
            val entries = (regions as? SrcObject)?.let { o -> o.members.indices.map { j -> entry(o, j, origin(o, j, base + o.members[j].key!!)) } }.orEmpty()
            val lock = when {
                odd != null -> DerpLock.SPELLING
                derp != null && obj == null -> DerpLock.SHAPE
                regions != null && regions !is SrcObject -> DerpLock.SHAPE
                spellings.size > 1 || entries.groupingBy { it.id }.eachCount().any { it.value > 1 } -> DerpLock.TWICE
                else -> null
            }
            val omit = obj?.members?.lastOrNull { it.key.equals("OmitDefaultRegions", ignoreCase = true) }?.value
            return DerpFile(
                origin = if (i >= 0) origin(root, i, PolicyPath.of(DERP)) else null,
                regionsKey = regionsKey,
                omitDefaults = omit is SrcLiteral && omit.raw == "true",
                entries = entries,
                lock = lock,
                oddKey = odd,
            )
        }

        private fun entry(o: SrcObject, j: Int, origin: Origin): DerpEntry {
            val m = o.members[j]
            val v = m.value
            val r = v as? SrcObject
            fun field(name: String) = r?.members?.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val kind = when {
                v is SrcLiteral && v.raw == "null" -> DerpEntryKind.EXCLUDED
                r != null -> DerpEntryKind.CUSTOM
                else -> DerpEntryKind.OTHER
            }
            return DerpEntry(
                id = m.key!!,
                kind = kind,
                origin = origin,
                code = (field("RegionCode") as? SrcString)?.value?.takeIf { it.isNotBlank() },
                name = (field("RegionName") as? SrcString)?.value?.takeIf { it.isNotBlank() },
                servers = (field("Nodes") as? SrcArray)?.members?.size ?: 0,
            )
        }
    }
}

/** How fast a region answers your devices: the median of what [devices] of them last reported. */
data class RelayLatency(val medianMs: Int, val devices: Int)

/** One of Tailscale's relay regions as the Relays page lists it. */
data class RelayRow(
    val id: String,
    val code: String?,
    /** The city; null while only the file names the region and Tailscale's map has not come. */
    val name: String?,
    /** Servers in the region, from Tailscale's map; null without it. */
    val servers: Int?,
    /** The file writes `"<id>": null`: Tailscale's region is not used. */
    val excluded: Boolean,
    /** The file defines a region of this id itself, which replaces Tailscale's: edited in JSON. */
    val replaced: Boolean,
    /** The file's line for this id, when it has one: its comment, its line for "Edit in JSON". */
    val entry: DerpEntry?,
    /** Devices that last reported this region as their home relay. */
    val homes: List<ApiDevice>,
    val latency: RelayLatency?,
) {
    /** A switch may say whether it is used: Tailscale's region, not replaced in the file. */
    val switchable: Boolean get() = !replaced
}

/**
 * What excluding a region would cost: the devices that call it home (they move to another
 * relay), and how many regions would carry traffic after it — Tailscale's and the file's own.
 */
data class ExcludeImpact(val homes: List<ApiDevice>, val defaultsLeft: Int, val ownLeft: Int) {
    /** Nothing would be left to relay through: refused. */
    val refused: Boolean get() = defaultsLeft + ownLeft == 0

    /** Only the file's own regions would be left: allowed, with a strong warning. */
    val onlyOwn: Boolean get() = defaultsLeft == 0 && ownLeft > 0

    /** Whether to ask before writing it. */
    val asks: Boolean get() = homes.isNotEmpty() || onlyOwn
}

object Relays {

    /**
     * Tailscale's regions from [map] (null while it loads or after it failed) and the ones the
     * file excludes that the map lacks, each with what [file] and [devices] say about it,
     * sorted as [sorted] says. Empty when the file turns Tailscale's regions off altogether.
     */
    fun rows(file: DerpFile, map: ApiDerpMap?, devices: List<ApiDevice>): List<RelayRow> {
        if (file.omitDefaults) return emptyList()
        val reports = reports(devices)
        val ids = (map?.regions?.keys.orEmpty() + file.excluded).distinct()
        return sorted(ids.map { id ->
            val region = map?.regions?.get(id)
            val entry = file.entries.lastOrNull { it.id == id }
            val name = region?.name?.takeIf { it.isNotBlank() }
            RelayRow(
                id = id,
                code = region?.code?.takeIf { it.isNotBlank() },
                name = name,
                servers = region?.nodes?.size,
                excluded = entry?.kind == DerpEntryKind.EXCLUDED,
                replaced = entry != null && entry.kind != DerpEntryKind.EXCLUDED,
                entry = entry,
                homes = name?.let { homes(reports, it) }.orEmpty(),
                latency = name?.let { latency(reports, it) },
            )
        })
    }

    /**
     * Home regions of your devices first, the most devices first; then the fastest by median
     * latency; then the regions no device measured, by id. Nothing about the file takes part,
     * so a row stays where it is when its switch is flipped.
     */
    fun sorted(rows: List<RelayRow>): List<RelayRow> = rows.sortedWith(
        compareByDescending<RelayRow> { it.homes.size }
            .thenBy { it.latency?.medianMs ?: Int.MAX_VALUE }
            .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.id },
    )

    /** What excluding region [id] would cost among [rows] of [file]. */
    fun impact(rows: List<RelayRow>, file: DerpFile, id: String): ExcludeImpact = ExcludeImpact(
        homes = rows.firstOrNull { it.id == id }?.homes.orEmpty(),
        defaultsLeft = rows.count { it.id != id && !it.excluded && !it.replaced && it.servers != null },
        ownLeft = file.ownServing,
    )

    /** Regions that would carry traffic as the file stands: Tailscale's not excluded, and the file's own. */
    fun serving(rows: List<RelayRow>, file: DerpFile): Int =
        rows.count { !it.excluded && !it.replaced && it.servers != null } + file.ownServing

    /** Devices that reported latencies, with them; a list fetched without them reports none. */
    fun reports(devices: List<ApiDevice>): List<Pair<ApiDevice, Map<String, ApiDerpLatency>>> =
        devices.mapNotNull { d -> d.clientConnectivity?.latency?.takeIf { it.isNotEmpty() }?.let { d to it } }

    /** Devices whose home relay ("preferred") is the region called [name] (latencies are keyed by RegionName). */
    fun homes(reports: List<Pair<ApiDevice, Map<String, ApiDerpLatency>>>, name: String): List<ApiDevice> =
        reports.filter { (_, l) -> l.entries.any { it.value.preferred == true && it.key.equals(name, ignoreCase = true) } }.map { it.first }

    fun latency(reports: List<Pair<ApiDevice, Map<String, ApiDerpLatency>>>, name: String): RelayLatency? {
        val ms = reports.mapNotNull { (_, l) -> l.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.latencyMs }.filter { it >= 0 }
        if (ms.isEmpty()) return null
        val s = ms.sorted()
        val mid = s.size / 2
        val median = if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        return RelayLatency(median.roundToInt(), s.size)
    }
}
