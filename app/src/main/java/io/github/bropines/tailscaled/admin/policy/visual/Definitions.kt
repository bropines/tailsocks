package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.ReachResolver
import io.github.bropines.tailscaled.admin.policy.RiskFinding
import io.github.bropines.tailscaled.admin.policy.TailnetView

/*
 * What the definitions pages (groups, tags, hosts and IP sets, auto approval, device attributes,
 * postures) decide without a screen: which names and addresses are valid, where a name is used,
 * which tags devices carry without an owner, how an IP set's rows read and are written. The
 * pages draw this; the edits themselves go through PolicyEdits and SourceEdits.
 */

/** A kind of name the policy defines, with the prefix every reference to it carries. */
enum class DefKind(val prefix: String) {
    GROUP("group:"),
    TAG("tag:"),
    /** Host aliases have no prefix: `nas`, used as `nas:443`. */
    HOST(""),
    IPSET("ipset:"),
    POSTURE("posture:"),
    /** A Tailscale Service in autoApprovers.services. */
    SERVICE("svc:"),
}

/** Why a name cannot be used for a new or renamed definition. */
enum class NameProblem {
    EMPTY,
    /** A tag must start with a letter. */
    BAD_START,
    /** Characters the server does not take in this kind of name. */
    BAD_CHARS,
    /** Another definition already has it. */
    TAKEN,
}

/** Why an address (a host alias's value, a route) is not one. */
enum class AddressProblem {
    EMPTY,
    NOT_AN_ADDRESS,
    /** A route needs its prefix length: `192.168.1.0/24`. */
    NEEDS_PREFIX,
    BAD_PREFIX_LENGTH,
}

/**
 * One row of an IP set: add or remove [target] (an address, a range, a CIDR, `host:name`,
 * `ipset:name`). [explicit] keeps the `add ` a row was written with; a bare target adds too.
 */
data class IpSetOp(val remove: Boolean, val target: String, val explicit: Boolean = remove) {
    val text: String
        get() = when {
            remove -> "remove $target"
            explicit -> "add $target"
            else -> target
        }

    companion object {
        fun parse(s: String): IpSetOp {
            val t = s.trim()
            return when (t.substringBefore(' ', "")) {
                "add" -> IpSetOp(false, t.substringAfter(' ').trim(), explicit = true)
                "remove" -> IpSetOp(true, t.substringAfter(' ').trim(), explicit = true)
                else -> IpSetOp(false, t, explicit = false)
            }
        }
    }
}

/** A tag that devices carry or rules use, missing from tagOwners: the server refuses rules that use it. */
data class UnownedTag(val tag: String, val devices: Int, val places: Int)

object Definitions {

    private val GENERIC_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
    private val TAG_NAME = Regex("""[A-Za-z0-9-]+""")
    private val SERVICE_NAME = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")

    /** The full names of [kind] the policy defines. */
    fun names(kind: DefKind, model: PolicyModel): List<String> = when (kind) {
        DefKind.GROUP -> model.groups.map { it.name }
        DefKind.TAG -> model.tagOwners.map { it.name }
        DefKind.HOST -> model.hosts.map { it.name }
        DefKind.IPSET -> model.ipsets.map { it.name }
        DefKind.POSTURE -> model.postures.map { it.name }
        DefKind.SERVICE -> model.autoApprovers?.services?.map { it.name }.orEmpty()
    }

    /**
     * What is wrong with [bare] (the name without its prefix) as a name of [kind]; null when it
     * can be used. [current] is the element's own name, which it may keep.
     */
    fun checkName(kind: DefKind, bare: String, model: PolicyModel, current: String? = null): NameProblem? {
        val b = bare.trim()
        if (b.isEmpty()) return NameProblem.EMPTY
        val ok = when (kind) {
            // tailcfg.CheckTag: a letter first, then letters, digits and dashes.
            DefKind.TAG -> if (!b.first().isAsciiLetter()) return NameProblem.BAD_START else TAG_NAME.matches(b)
            DefKind.SERVICE -> SERVICE_NAME.matches(b)
            // Headscale: no '@' (a user), no ':'; an address would read as one.
            DefKind.HOST -> GENERIC_NAME.matches(b) && checkAddress(b) != null
            else -> GENERIC_NAME.matches(b)
        }
        if (!ok) return NameProblem.BAD_CHARS
        val full = kind.prefix + b
        if (full != current && full in names(kind, model)) return NameProblem.TAKEN
        return null
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    // ---- addresses ----

    /** A host alias's value: an IPv4 or IPv6 address, with or without a prefix length. */
    fun checkAddress(s: String): AddressProblem? {
        val v = s.trim()
        if (v.isEmpty()) return AddressProblem.EMPTY
        val ip = v.substringBefore('/')
        val v4 = isIpv4(ip)
        if (!v4 && !isIpv6(ip)) return AddressProblem.NOT_AN_ADDRESS
        if ('/' in v) {
            val bits = v.substringAfter('/').toIntOrNull() ?: return AddressProblem.BAD_PREFIX_LENGTH
            if (bits !in 0..(if (v4) 32 else 128)) return AddressProblem.BAD_PREFIX_LENGTH
        }
        return null
    }

    /** A route to approve: an address with its prefix length. */
    fun checkRoute(s: String): AddressProblem? = checkAddress(s) ?: if ('/' !in s) AddressProblem.NEEDS_PREFIX else null

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it.isDigit() } && p.toInt() <= 255 }
    }

    fun isIpv6(s: String): Boolean {
        if (s.count { it == ':' } < 2) return false
        var str = s
        // An IPv4 tail ("::ffff:1.2.3.4") stands for the last two groups.
        val tail = s.substringAfterLast(':')
        if ('.' in tail) {
            if (!isIpv4(tail)) return false
            str = s.substring(0, s.length - tail.length) + "0:0"
        }
        val gap = str.indexOf("::")
        if (gap >= 0 && gap != str.lastIndexOf("::")) return false
        fun groups(part: String): List<String>? {
            if (part.isEmpty()) return emptyList()
            val g = part.split(':')
            return if (g.all { it.length in 1..4 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }) g else null
        }
        return if (gap >= 0) {
            val left = groups(str.substring(0, gap)) ?: return false
            val right = groups(str.substring(gap + 2)) ?: return false
            left.size + right.size < 8
        } else {
            groups(str)?.size == 8
        }
    }

    /** An IP set row's target: an address, a range, a CIDR, a defined `host:` or another defined `ipset:`. */
    fun checkIpSetTarget(target: String, model: PolicyModel, self: String? = null): SelectorProblem? {
        val t = target.trim()
        if (t.isEmpty()) return SelectorProblem.EMPTY
        return when {
            t.startsWith("host:") -> if (model.hosts.any { it.name == t.removePrefix("host:") }) null else SelectorProblem.UNDEFINED
            t.startsWith("ipset:") -> when {
                t == self -> SelectorProblem.NOT_ALLOWED_HERE
                model.ipsets.any { it.name == t } -> null
                else -> SelectorProblem.UNDEFINED
            }
            '-' in t && '/' !in t -> {
                val ends = t.split('-')
                if (ends.size == 2 && ends.all { isIpv4(it.trim()) || isIpv6(it.trim()) }) null else SelectorProblem.UNKNOWN_FORM
            }
            checkAddress(t) == null -> null
            else -> SelectorProblem.UNKNOWN_FORM
        }
    }

    /** A new row added to [existing]: written `add x` when the set already writes its additions so. */
    fun newIpSetOp(existing: List<IpSetOp>, target: String, remove: Boolean = false): IpSetOp =
        IpSetOp(remove, target.trim(), explicit = remove || existing.any { !it.remove && it.explicit })

    // ---- where names are used ----

    /**
     * The elements that use [name] — a rule, a tag's owners, an approval, an IP set — each once,
     * in file order; its own definition not among them. What blocks deleting it, and what a
     * rename rewrites.
     */
    fun places(t: SourceTree, name: String): List<PolicyPath> =
        PolicyEdits.uses(t, name).map(PolicyLocator::element).distinct().sortedBy { t.memberAt(it)?.second?.start ?: Int.MAX_VALUE }

    /** An element the model has no [Origin] for (autoApprovers.exitNode): its line and comments, read from the tree. */
    fun originAt(t: SourceTree, path: PolicyPath): Origin? {
        val (c, m) = t.memberAt(path) ?: return null
        val cm = t.commentsOf(c, c.members.indexOf(m))
        val issues = if (m.value is SrcArray) emptySet() else setOf(ShapeIssue.NOT_A_LIST)
        return Origin(path, HuJson.Lines(t.text).lineOf(m.start), cm.note, cm.header, cm.trailing, issues = issues)
    }

    /** Whether the element at [path] has lines of its own, so a comment can be written above it. */
    fun canComment(t: SourceTree, path: PolicyPath): Boolean {
        val (c, m) = t.memberAt(path) ?: return false
        return Trivia.lines(t, c, c.members.indexOf(m)).ownLines
    }

    /** The [Origin] the model holds for the element at [path], whichever list it is in. */
    fun originOf(m: PolicyModel, path: PolicyPath): Origin? = sequence {
        yieldAll(m.acls.map { it.origin })
        yieldAll(m.grants.map { it.origin })
        yieldAll(m.ssh.map { it.origin })
        yieldAll(m.nodeAttrs.map { it.origin })
        yieldAll(m.tests.map { it.origin })
        yieldAll(m.sshTests.map { it.origin })
        yieldAll(m.groups.map { it.origin })
        yieldAll(m.tagOwners.map { it.origin })
        yieldAll(m.hosts.map { it.origin })
        yieldAll(m.ipsets.map { it.origin })
        yieldAll(m.postures.map { it.origin })
        m.autoApprovers?.let { a -> yieldAll(a.routes.map { it.origin }); yieldAll(a.services.map { it.origin }) }
    }.firstOrNull { it.path == path }

    /** The 1-based line an element starts on, for "line 52" beside a place. */
    fun lineOf(t: SourceTree, path: PolicyPath): Int? = t.memberAt(path)?.let { HuJson.Lines(t.text).lineOf(it.second.start) }

    /** Every selector the policy writes in a place that names tags, groups, users or hosts. */
    fun selectorsInUse(m: PolicyModel): Sequence<String> = sequence {
        m.acls.forEach { r -> yieldAll(r.src); yieldAll(r.destinations.map { it.host }) }
        m.grants.forEach { r -> yieldAll(r.src); yieldAll(r.dst); yieldAll(r.via) }
        m.ssh.forEach { r -> yieldAll(r.src); yieldAll(r.dst) }
        m.nodeAttrs.forEach { yieldAll(it.target) }
        m.tests.forEach { r -> yield(r.src); yieldAll((r.accept + r.deny).map { Selectors.hostPorts(it).host }) }
        m.sshTests.forEach { r -> yield(r.src); yieldAll(r.dst) }
        m.tagOwners.forEach { yieldAll(it.values) }
        m.autoApprovers?.let { a ->
            a.routes.forEach { yieldAll(it.values) }
            yieldAll(a.exitNode)
            a.services.forEach { yieldAll(it.values) }
        }
    }

    /**
     * Tags devices carry or the policy uses that tagOwners does not define, by name: the server
     * refuses a policy that uses one, and a device's tag without an owner can be assigned by no
     * one. The tags page offers to add each.
     */
    fun unownedTags(m: PolicyModel, devices: List<ApiDevice>, t: SourceTree?): List<UnownedTag> {
        val owned = m.tagOwners.map { it.name }.toSet()
        val used = selectorsInUse(m).filter { it.startsWith("tag:") } + devices.asSequence().flatMap { it.tags }
        return used.filter { it !in owned }.distinct().sorted().map { tag ->
            UnownedTag(tag, devices.count { tag in it.tags }, t?.let { places(it, tag).size } ?: 0)
        }.toList()
    }

    /** Owners a tag gets when it is added for devices that already carry it (Headscale takes no autogroup here). */
    fun defaultOwners(headscale: Boolean): List<String> = if (headscale) emptyList() else listOf("autogroup:admin")

    /**
     * Every risk the lint finds in [text], old or new, by element: an editor says what its element
     * risks, where a card says only what the draft adds to the saved policy.
     */
    fun findings(text: String): Map<PolicyPath, List<RiskFinding>> {
        val t = SourceTree.parseOrNull(text) ?: return emptyMap()
        val root = HuJson.parseOrNull(text) ?: return emptyMap()
        return PolicyLint.findings(root).mapNotNull { f -> PolicyLocator.element(t, f)?.let { it to f } }.groupBy({ it.first }, { it.second })
    }

    // ---- devices ----

    /** The devices of a group's members (tagged devices belong to their tags, not their users). */
    fun groupDevices(members: List<String>, devices: List<ApiDevice>): List<ApiDevice> {
        val set = members.map { it.lowercase() }.toSet()
        return devices.filter { !it.isTagged && it.user?.lowercase() in set }
    }

    fun tagDevices(tag: String, devices: List<ApiDevice>): List<ApiDevice> = devices.filter { tag in it.tags }

    /** The devices a host alias's address points at: within an IPv4 prefix, or that exact IPv6 address. */
    fun hostDevices(address: String, devices: List<ApiDevice>): List<ApiDevice> {
        val a = address.trim()
        if (isIpv4(a.substringBefore('/'))) {
            return ReachResolver.devicesFor(a, null, TailnetView(devices, null), null)?.toList().orEmpty()
        }
        val ip = a.removeSuffix("/128")
        return devices.filter { d -> d.addresses.any { it.equals(ip, ignoreCase = true) } }
    }

    // ---- edits the engine has no single call for ----

    /** Set the list at [path] (approvers, members, owners) to [values], an empty list kept as `[]`. */
    fun setList(text: String, path: PolicyPath, values: List<String>): String = SourceEdits.setStrings(text, path, values)

    /** Change a route's prefix; it is a key, not a reference, so nothing else changes. */
    fun renameRoute(text: String, old: String, new: String): String =
        SourceEdits.rename(text, PolicyPath.of(Section.AUTO_APPROVERS.key, "routes", old), new.trim())

    /** Postures every rule without a posture of its own requires; empty removes the field. */
    fun setDefaultPosture(text: String, values: List<String>): String {
        val existing = SourceEdits.tree(text).root[Section.DEFAULT_SRC_POSTURE.key]
        return when {
            values.isEmpty() -> PolicyEdits.setOption(text, Section.DEFAULT_SRC_POSTURE, null)
            existing is SrcArray -> SourceEdits.setStrings(text, PolicyPath.of(Section.DEFAULT_SRC_POSTURE.key), values, SourceEdits.SECTION_ORDER)
            else -> PolicyEdits.setOption(text, Section.DEFAULT_SRC_POSTURE, values.pv())
        }
    }

    /** A posture's assertions, in either form it is written in. */
    fun setAssertions(text: String, posture: Posture, values: List<String>): String =
        if (posture.objectForm) SourceEdits.setStrings(text, posture.origin.path + "assertions", values)
        else PolicyEdits.putNamed(text, Section.POSTURES, posture.name, values)

    /** A copy of a node attribute rule right after it: targets and attributes (an `app` or `ipPool` stays with the original). */
    fun duplicateNodeAttr(text: String, rule: NodeAttr): String {
        val index = (rule.origin.path.last as? PathStep.Index)?.index ?: throw PolicyEditException("not a rule")
        return PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(rule.target, rule.attr), index + 1)
    }

    /** The index of an element of an array section (`nodeAttrs[3]` → 3). */
    fun indexOf(path: PolicyPath): Int? = (path.last as? PathStep.Index)?.index
}
