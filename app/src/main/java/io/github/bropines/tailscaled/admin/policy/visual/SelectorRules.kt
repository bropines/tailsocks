package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser

/** A place in a rule or definition that takes a selector; each accepts different kinds. */
enum class SelectorSlot {
    ACL_SRC,
    /** The host part of an ACL destination; its ports are checked on their own. */
    ACL_DST,
    GRANT_SRC,
    GRANT_DST,
    GRANT_VIA,
    SSH_SRC,
    SSH_DST,
    /** A login name on the destination machine. */
    SSH_USER,
    NODE_TARGET,
    TAG_OWNER,
    APPROVER,
    TEST_SRC,
    /** The host part of a test destination, which takes one numeric port. */
    TEST_DST,
    SSH_TEST_SRC,
    SSH_TEST_DST,
    GROUP_MEMBER,
    POSTURE,
}

/** Why a selector cannot go where it was put. */
enum class SelectorProblem {
    EMPTY,
    /** This kind of selector has no meaning here (`*` as an SSH source, a CIDR as a tag owner). */
    NOT_ALLOWED_HERE,
    /** A group, tag, host, IP set or posture the file does not define. */
    UNDEFINED,
    /** Fine on Tailscale, refused by Headscale's policy parser. */
    NOT_ON_HEADSCALE,
    /** Not a port list (`22`, `80,443`, `1000-2000`, `*`), or outside 0–65535. */
    BAD_PORTS,
    /** A test destination needs exactly one port number. */
    NEEDS_ONE_PORT,
    /** Not any selector the editor recognises. */
    UNKNOWN_FORM,
}

/** One entry a picker offers: what it writes, its kind, and how many devices it covers when known. */
data class SelectorOption(val value: String, val kind: SelectorKind, val devices: Int? = null, val defined: Boolean = true)

/**
 * Which selectors each slot takes, on Tailscale and on Headscale (whose policy is a strict
 * subset: no IP sets, services, postures or role autogroups, and owners without autogroups),
 * checked as the person types or picks — before the server is asked.
 */
object SelectorRules {

    private fun kinds(vararg k: SelectorKind) = k.toSet()

    private val ACCESS_SRC = kinds(SelectorKind.ANY, SelectorKind.AUTOGROUP, SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER,
        SelectorKind.USER_DOMAIN, SelectorKind.HOST, SelectorKind.IP, SelectorKind.CIDR, SelectorKind.IPSET)
    private val ACCESS_DST = ACCESS_SRC + SelectorKind.SERVICE

    /** The kinds [slot] accepts on Tailscale. */
    fun kinds(slot: SelectorSlot): Set<SelectorKind> = when (slot) {
        SelectorSlot.ACL_SRC -> ACCESS_SRC
        SelectorSlot.GRANT_SRC -> ACCESS_SRC + SelectorKind.EXTERNAL
        SelectorSlot.ACL_DST, SelectorSlot.GRANT_DST -> ACCESS_DST
        SelectorSlot.GRANT_VIA -> kinds(SelectorKind.TAG)
        SelectorSlot.SSH_SRC -> kinds(SelectorKind.AUTOGROUP, SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER)
        SelectorSlot.SSH_DST -> kinds(SelectorKind.AUTOGROUP, SelectorKind.TAG, SelectorKind.USER)
        SelectorSlot.SSH_USER -> kinds(SelectorKind.AUTOGROUP, SelectorKind.LOCALPART, SelectorKind.UNKNOWN, SelectorKind.ANY)
        SelectorSlot.NODE_TARGET -> kinds(SelectorKind.ANY, SelectorKind.AUTOGROUP, SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER,
            SelectorKind.HOST, SelectorKind.IP, SelectorKind.CIDR)
        SelectorSlot.TAG_OWNER, SelectorSlot.APPROVER -> kinds(SelectorKind.AUTOGROUP, SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER)
        SelectorSlot.TEST_SRC -> kinds(SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER, SelectorKind.HOST, SelectorKind.IP)
        SelectorSlot.TEST_DST -> kinds(SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER, SelectorKind.HOST, SelectorKind.IP, SelectorKind.SERVICE)
        SelectorSlot.SSH_TEST_SRC -> kinds(SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.USER)
        SelectorSlot.SSH_TEST_DST -> kinds(SelectorKind.AUTOGROUP, SelectorKind.TAG, SelectorKind.USER, SelectorKind.HOST, SelectorKind.IP)
        SelectorSlot.GROUP_MEMBER -> kinds(SelectorKind.USER)
        SelectorSlot.POSTURE -> kinds(SelectorKind.POSTURE)
    }

    private val HEADSCALE_NEVER = kinds(SelectorKind.USER_DOMAIN, SelectorKind.IPSET, SelectorKind.SERVICE, SelectorKind.POSTURE,
        SelectorKind.EXTERNAL, SelectorKind.IP_RANGE, SelectorKind.LOCALPART)

    /** The autogroups [slot] accepts; on Headscale only those its parser knows, where it allows them. */
    fun autogroups(slot: SelectorSlot, headscale: Boolean): List<Autogroup> {
        val use = when (slot) {
            SelectorSlot.ACL_SRC, SelectorSlot.GRANT_SRC -> AutogroupUse.SRC
            SelectorSlot.ACL_DST, SelectorSlot.GRANT_DST -> AutogroupUse.DST
            SelectorSlot.SSH_SRC -> AutogroupUse.SSH_SRC
            SelectorSlot.SSH_DST, SelectorSlot.SSH_TEST_DST -> AutogroupUse.SSH_DST
            SelectorSlot.SSH_USER -> AutogroupUse.SSH_USERS
            SelectorSlot.NODE_TARGET -> AutogroupUse.TARGET
            SelectorSlot.TAG_OWNER, SelectorSlot.APPROVER -> if (headscale) return emptyList() else AutogroupUse.OWNER
            else -> return emptyList()
        }
        return Selectors.AUTOGROUPS.filter { use in it.uses && (!headscale || (it.headscale && headscaleAllows(it.raw, slot))) }
    }

    /** Headscale's own table of autogroups per context (policy v2). */
    private fun headscaleAllows(autogroup: String, slot: SelectorSlot): Boolean {
        val name = autogroup.removePrefix("autogroup:")
        return when (slot) {
            SelectorSlot.ACL_SRC, SelectorSlot.GRANT_SRC -> name in setOf("member", "tagged", "danger-all")
            SelectorSlot.ACL_DST, SelectorSlot.GRANT_DST -> name in setOf("internet", "member", "tagged", "self")
            SelectorSlot.SSH_SRC -> name in setOf("member", "tagged")
            SelectorSlot.SSH_DST, SelectorSlot.SSH_TEST_DST -> name in setOf("member", "tagged", "self")
            SelectorSlot.NODE_TARGET -> name in setOf("member", "tagged")
            SelectorSlot.SSH_USER -> name == "nonroot"
            else -> false
        }
    }

    /** What is wrong with [value] in [slot], or null when it fits. Defined names come from [model]. */
    fun check(value: String, slot: SelectorSlot, model: PolicyModel, headscale: Boolean = false): SelectorProblem? {
        val v = value.trim()
        if (v.isEmpty()) return SelectorProblem.EMPTY
        if (slot == SelectorSlot.SSH_USER) {
            if (v.startsWith("autogroup:")) return if (autogroups(slot, headscale).any { it.raw == v }) null else SelectorProblem.NOT_ALLOWED_HERE
            if (v == "*" && headscale) return SelectorProblem.NOT_ON_HEADSCALE
            if (v.startsWith("localpart:")) return if (headscale && !Regex("""localpart:\*@.+""").matches(v)) SelectorProblem.UNKNOWN_FORM else null
            return null
        }
        val hosts = model.hosts.map { it.name }.toSet()
        val s = Selectors.parse(v, hosts)
        if (s.kind !in kinds(slot)) {
            // A bare word where a host fits is a host alias the file does not define yet.
            if (s.kind == SelectorKind.UNKNOWN && SelectorKind.HOST in kinds(slot)) return SelectorProblem.UNDEFINED
            return if (s.kind == SelectorKind.UNKNOWN) SelectorProblem.UNKNOWN_FORM else SelectorProblem.NOT_ALLOWED_HERE
        }
        if (headscale && s.kind in HEADSCALE_NEVER) return SelectorProblem.NOT_ON_HEADSCALE
        if (s.kind == SelectorKind.AUTOGROUP) {
            val ok = autogroups(slot, headscale).any { it.raw == v }
            return when {
                ok -> null
                headscale && autogroups(slot, false).any { it.raw == v } -> SelectorProblem.NOT_ON_HEADSCALE
                else -> SelectorProblem.NOT_ALLOWED_HERE
            }
        }
        return when (s.kind) {
            // A SCIM-synced group (group:name@domain) is referenced without being defined.
            SelectorKind.GROUP -> if ('@' in v || model.groups.any { it.name == v }) null else SelectorProblem.UNDEFINED
            SelectorKind.TAG -> if (model.tagOwners.any { it.name == v }) null else SelectorProblem.UNDEFINED
            SelectorKind.IPSET -> if (model.ipsets.any { it.name == v }) null else SelectorProblem.UNDEFINED
            SelectorKind.POSTURE -> if (model.postures.any { it.name == v }) null else SelectorProblem.UNDEFINED
            else -> null
        }
    }

    /** Ports of an ACL destination: `*`, `22`, `80,443`, `1000-2000`, every number within 0–65535. */
    fun checkPorts(ports: String): SelectorProblem? {
        val p = ports.replace(" ", "")
        if (p == "*") return null
        if (!Selectors.isPortSpec(p)) return SelectorProblem.BAD_PORTS
        val ok = p.split(',').all { part ->
            val b = part.split('-').map { it.toIntOrNull() ?: return SelectorProblem.BAD_PORTS }
            b.all { it in 0..65535 } && (b.size == 1 || b[0] <= b[1])
        }
        return if (ok) null else SelectorProblem.BAD_PORTS
    }

    /** A whole ACL destination `host:ports`. */
    fun checkAclDestination(dst: String, model: PolicyModel, headscale: Boolean = false): SelectorProblem? {
        val hp = Selectors.hostPorts(dst)
        val ports = hp.ports ?: return SelectorProblem.BAD_PORTS
        return check(hp.host, SelectorSlot.ACL_DST, model, headscale) ?: checkPorts(ports)
    }

    /** A test destination: one host and exactly one port. */
    fun checkTestDestination(dst: String, model: PolicyModel, headscale: Boolean = false): SelectorProblem? {
        val hp = Selectors.hostPorts(dst)
        val port = hp.ports ?: return SelectorProblem.NEEDS_ONE_PORT
        if (port.toIntOrNull()?.takeIf { it in 0..65535 } == null) return SelectorProblem.NEEDS_ONE_PORT
        return check(hp.host, SelectorSlot.TEST_DST, model, headscale)
    }

    /**
     * What a picker for [slot] offers, in the order it shows them: autogroups, then the file's
     * groups, tags (from tagOwners, and tags devices carry that tagOwners lacks — marked
     * undefined), hosts and IP sets, then users from [users]. [devices] fills in device counts.
     */
    fun options(
        slot: SelectorSlot,
        model: PolicyModel,
        headscale: Boolean,
        users: List<ApiUser> = emptyList(),
        devices: List<ApiDevice> = emptyList(),
    ): List<SelectorOption> {
        val allowed = kinds(slot).filter { !headscale || it !in HEADSCALE_NEVER }.toSet()
        val out = mutableListOf<SelectorOption>()
        fun add(o: SelectorOption) {
            if (o.kind in allowed && out.none { it.value == o.value }) out += o
        }
        if (SelectorKind.ANY in allowed && slot != SelectorSlot.SSH_USER) add(SelectorOption("*", SelectorKind.ANY, devices.size.takeIf { devices.isNotEmpty() }))
        autogroups(slot, headscale).forEach { add(SelectorOption(it.raw, SelectorKind.AUTOGROUP)) }
        model.groups.forEach { g ->
            val count = devices.count { d -> !d.isTagged && g.values.any { it.equals(d.user, ignoreCase = true) } }
            add(SelectorOption(g.name, SelectorKind.GROUP, count.takeIf { devices.isNotEmpty() }))
        }
        val tags = (model.tagOwners.map { it.name } + devices.flatMap { it.tags }).distinct()
        tags.forEach { tag ->
            add(SelectorOption(tag, SelectorKind.TAG, devices.count { tag in it.tags }.takeIf { devices.isNotEmpty() }, model.tagOwners.any { it.name == tag }))
        }
        model.hosts.forEach { add(SelectorOption(it.name, SelectorKind.HOST)) }
        model.ipsets.forEach { add(SelectorOption(it.name, SelectorKind.IPSET)) }
        model.postures.forEach { add(SelectorOption(it.name, SelectorKind.POSTURE)) }
        users.filter { it.loginName.isNotBlank() }.forEach { u ->
            add(SelectorOption(u.loginName, SelectorKind.USER, devices.count { !it.isTagged && it.user.equals(u.loginName, ignoreCase = true) }))
        }
        return out
    }
}
