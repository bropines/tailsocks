package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType

/** The tailnet the preview's words are resolved against; [users] is null when the list was not read. */
data class TailnetView(val devices: List<ApiDevice>, val users: List<ApiUser>?)

/** Ports as sorted, disjoint closed ranges within 0..65535. */
class PortSet private constructor(val ranges: List<IntRange>) {

    val isEmpty: Boolean get() = ranges.isEmpty()
    val isAll: Boolean get() = ranges == listOf(FULL)
    /** How many single ports and ranges it lists: what "port" or "ports" agrees with. */
    val parts: Int get() = ranges.size + ranges.count { it.first != it.last }

    operator fun plus(other: PortSet): PortSet = of(ranges + other.ranges)

    operator fun minus(other: PortSet): PortSet {
        var left = ranges
        for (cut in other.ranges) {
            left = left.flatMap { r ->
                if (cut.last < r.first || cut.first > r.last) listOf(r)
                else listOfNotNull(
                    (r.first until cut.first).takeIf { !it.isEmpty() }?.let { it.first..it.last },
                    (cut.last + 1..r.last).takeIf { !it.isEmpty() },
                )
            }
        }
        return PortSet(left)
    }

    /** "22", "80–90, 443". */
    fun text(): String = ranges.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }

    override fun equals(other: Any?) = other is PortSet && other.ranges == ranges
    override fun hashCode() = ranges.hashCode()
    override fun toString() = if (isAll) "*" else text()

    companion object {
        private val FULL = 0..65535
        val ALL = PortSet(listOf(FULL))
        val NONE = PortSet(emptyList())

        fun single(port: Int) = of(listOf(port..port))

        /** "*", "22", "80,443", "1000-2000"; null for anything else. */
        fun parse(spec: String): PortSet? {
            val s = spec.trim()
            if (s == "*") return ALL
            if (s.isEmpty()) return null
            val parts = s.split(',').map { it.trim() }
            val out = parts.map { p ->
                val bounds = p.split('-').map { it.trim().toIntOrNull() ?: return null }
                when (bounds.size) {
                    1 -> bounds[0]..bounds[0]
                    2 -> bounds[0]..bounds[1]
                    else -> return null
                }.takeIf { it.first in FULL && it.last in FULL && it.first <= it.last } ?: return null
            }
            return of(out)
        }

        private fun of(list: List<IntRange>): PortSet {
            val merged = mutableListOf<IntRange>()
            for (r in list.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
                else merged += r
            }
            return PortSet(merged)
        }
    }
}

/** A device the candidate stops matching, on [ports] (for a source that can no longer reach this phone, the port asked). */
data class LostDevice(val device: ApiDevice, val ports: PortSet, val kept: PortSet = PortSet.NONE)

/** What a probe really loses: devices, and the words that could not be resolved here, as the server wrote them. */
data class ResolvedLoss(val devices: List<LostDevice>, val unresolved: List<String>) {
    val isEmpty: Boolean get() = devices.isEmpty() && unresolved.isEmpty()
}

/**
 * What a preview's lost entries mean in devices. The server answers in the policy's own words
 * ("*:*", "group:ops:*", "tag:db:5432"), so a rule rewritten to say the same thing differently
 * reads as a loss. Here every word is resolved against the tailnet as the console last read it,
 * with the groups and hosts of the policy version it came from, and only the devices (and ports)
 * the candidate really stops matching are reported. What cannot be resolved here — the
 * internet, IPv6, an autogroup it does not model — is kept as the server wrote it: the check
 * may say less than before, never hide a loss it cannot judge.
 */
object ReachResolver {

    private val ROLE_GROUPS = setOf("owner", "admin", "it-admin", "network-admin", "billing-admin", "auditor")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?$")

    fun resolve(
        type: PolicyPreviewType,
        previewFor: String,
        before: PolicyPreview,
        after: PolicyPreview,
        beforePolicy: HuObject?,
        afterPolicy: HuObject?,
        view: TailnetView,
        subjectLogin: String?,
    ): ResolvedLoss = when (type) {
        PolicyPreviewType.USER -> destinations(before, after, beforePolicy, afterPolicy, view, subjectLogin)
        PolicyPreviewType.IP_PORT -> sources(previewFor, before, after, beforePolicy, afterPolicy, view, subjectLogin)
    }

    /** This phone's user: what it reaches, device by device and port by port. */
    private fun destinations(
        before: PolicyPreview, after: PolicyPreview, beforePolicy: HuObject?, afterPolicy: HuObject?,
        view: TailnetView, subject: String?,
    ): ResolvedLoss {
        class Reach(val ports: MutableMap<ApiDevice, PortSet> = linkedMapOf(), val words: MutableSet<String> = linkedSetOf())

        fun reach(p: PolicyPreview, policy: HuObject?): Reach {
            val out = Reach()
            for (word in p.matches.flatMap { it.ports }.distinct()) {
                val colon = word.lastIndexOf(':')
                val ports = if (colon > 0) PortSet.parse(word.substring(colon + 1)) else null
                val devices = if (ports != null) devicesFor(word.substring(0, colon), policy, view, subject) else null
                if (ports == null || devices == null) {
                    out.words += word
                    continue
                }
                devices.forEach { d -> out.ports[d] = (out.ports[d] ?: PortSet.NONE) + ports }
            }
            return out
        }

        val b = reach(before, beforePolicy)
        val a = reach(after, afterPolicy)
        val lost = b.ports.mapNotNull { (d, ports) ->
            val kept = a.ports[d] ?: PortSet.NONE
            (ports - kept).takeIf { !it.isEmpty }?.let { LostDevice(d, it, kept) }
        }
        return ResolvedLoss(lost, (b.words - a.words).sorted())
    }

    /** This phone's address on one port: who reaches it, device by device. */
    private fun sources(
        previewFor: String, before: PolicyPreview, after: PolicyPreview, beforePolicy: HuObject?, afterPolicy: HuObject?,
        view: TailnetView, subject: String?,
    ): ResolvedLoss {
        val port = previewFor.substringAfterLast(':').toIntOrNull()?.let(PortSet::single) ?: PortSet.ALL

        fun reach(p: PolicyPreview, policy: HuObject?): Pair<Set<ApiDevice>, Set<String>> {
            val devices = linkedSetOf<ApiDevice>()
            val words = linkedSetOf<String>()
            for (word in p.matches.flatMap { it.users }.distinct()) {
                devicesFor(word, policy, view, subject)?.let { devices += it } ?: run { words += word }
            }
            return devices to words
        }

        val (bd, bw) = reach(before, beforePolicy)
        val (ad, aw) = reach(after, afterPolicy)
        return ResolvedLoss((bd - ad).map { LostDevice(it, port) }, (bw - aw).sorted())
    }

    /** The devices one word of a policy names; null when it cannot be told from here. */
    fun devicesFor(word: String, policy: HuObject?, view: TailnetView, subject: String?): Set<ApiDevice>? {
        val w = word.trim()
        val all = view.devices
        fun untaggedOf(logins: Collection<String>): Set<ApiDevice> {
            val set = logins.map { it.lowercase() }.toSet()
            return all.filter { !it.isTagged && it.user?.lowercase() in set }.toSet()
        }
        return when {
            w == "*" || w == "autogroup:danger-all" -> all.toSet()
            w.startsWith("tag:") -> all.filter { w in it.tags }.toSet()
            w.startsWith("group:") -> untaggedOf(stringsAt(policy, "groups", w) ?: return null)
            w == "autogroup:member" -> untaggedOf(view.users?.filter { it.type == null || it.type == "member" }?.map { it.loginName } ?: return null)
            w == "autogroup:tagged" -> all.filter { it.isTagged }.toSet()
            w == "autogroup:self" -> untaggedOf(listOf(subject ?: return null))
            w.startsWith("autogroup:") && w.removePrefix("autogroup:") in ROLE_GROUPS ->
                untaggedOf(view.users?.filter { it.role == w.removePrefix("autogroup:") }?.map { it.loginName } ?: return null)
            w.startsWith("autogroup:") -> null
            '@' in w -> untaggedOf(listOf(w))
            IPV4.matches(w) -> inRange(w, all)
            else -> (policy?.get("hosts") as? HuObject)?.get(w)?.let { (it as? HuString)?.value }
                ?.takeIf { IPV4.matches(it) }?.let { inRange(it, all) }
        }
    }

    private fun stringsAt(policy: HuObject?, section: String, key: String): List<String>? {
        val node = (policy?.get(section) as? HuObject)?.get(key) as? HuArray ?: return null
        return node.items.mapNotNull { (it as? HuString)?.value }
    }

    private fun inRange(cidr: String, devices: List<ApiDevice>): Set<ApiDevice>? {
        val m = IPV4.matchEntire(cidr) ?: return null
        val bits = m.groupValues[5].ifEmpty { "32" }.toInt().takeIf { it in 0..32 } ?: return null
        val net = toInt(m.groupValues.subList(1, 5)) ?: return null
        val mask = if (bits == 0) 0 else -1 shl (32 - bits)
        return devices.filter { d ->
            d.addresses.any { a -> IPV4.matchEntire(a)?.let { toInt(it.groupValues.subList(1, 5)) }?.let { it and mask == net and mask } == true }
        }.toSet()
    }

    private fun toInt(octets: List<String>): Int? {
        var v = 0
        for (o in octets) v = (v shl 8) or (o.toIntOrNull()?.takeIf { it in 0..255 } ?: return null)
        return v
    }
}
