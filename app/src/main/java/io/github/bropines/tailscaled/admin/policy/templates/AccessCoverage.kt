package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PortSet
import io.github.bropines.tailscaled.admin.policy.ReachResolver
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.visual.Fields
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.SelectorKind
import io.github.bropines.tailscaled.admin.policy.visual.Selectors

/*
 * What the engine reads off the access rules a policy has, for the checks and the generators a
 * template calls: whether rules already open a path (the `access-open` check — the remote
 * management template's duplicate detection, made general), the one test that pins a new path
 * (the `pair-test` generator), and which sources cannot have "their own devices". Read from
 * the selectors alone, so it never says "open" for a path that is not.
 */
object AccessCoverage {

    const val SELF = "autogroup:self"
    const val MEMBER = "autogroup:member"
    private const val TAGGED = "autogroup:tagged"

    /** Every tailnet IPv4 address: a destination that names it opens every device. */
    private const val TAILNET_V4 = "100.64.0.0/10"

    /**
     * The rules of [m] that already let every one of [src] reach every one of [dst] on all of
     * [ports] over [proto] (null: every protocol), one per pair, in the order found; null when
     * some pair is not open, or a side is empty. A rule that holds only under a device posture
     * or through a router does not count.
     */
    fun openedBy(m: PolicyModel, src: List<String>, dst: List<String>, ports: PortSet, proto: String?): List<PolicyPath>? {
        if (src.isEmpty() || dst.isEmpty() || ports.isEmpty) return null
        val found = LinkedHashSet<PolicyPath>()
        for (a in src) for (b in dst) found += opening(m, a, b, ports, proto) ?: return null
        return found.toList()
    }

    /** The first rule that opens [ports] from [a]'s devices to [b]'s. */
    private fun opening(m: PolicyModel, a: String, b: String, ports: PortSet, proto: String?): PolicyPath? {
        m.acls.firstOrNull { r ->
            r.action == "accept" && r.srcPosture.isEmpty() && protoCovers(r.proto, proto) && r.src.any { covers(m, it, a) } &&
                r.destinations.any { hp -> portsOpen(hp.ports, ports) && reaches(m, hp.host, a, b) }
        }?.let { return it.origin.path }
        m.grants.firstOrNull { g ->
            g.via.isEmpty() && g.srcPosture.isEmpty() && g.ipSpecs.any { protoCovers(it.proto, proto) && portsOpen(it.ports, ports) } &&
                g.src.any { covers(m, it, a) } && g.dst.any { reaches(m, it, a, b) }
        }?.let { return it.origin.path }
        return null
    }

    /** A rule of [rule] protocol carries [wanted]: no protocol carries them all; TCP is also "6", UDP "17". */
    private fun protoCovers(rule: String?, wanted: String?): Boolean {
        if (rule == null) return true
        if (wanted == null) return false
        fun norm(p: String) = when (p.lowercase()) {
            "6" -> "tcp"
            "17" -> "udp"
            "1" -> "icmp"
            else -> p.lowercase()
        }
        return norm(rule) == norm(wanted)
    }

    private fun portsOpen(spec: String?, wanted: PortSet): Boolean {
        val set = PortSet.parse(spec ?: return false) ?: return false
        return (wanted - set).isEmpty
    }

    /** Whether destination [host] of a rule whose source holds [a] takes in every device of [b]. */
    private fun reaches(m: PolicyModel, host: String, a: String, b: String): Boolean {
        if (host == TAILNET_V4) return true
        if (b == SELF) {
            if (host == SELF) return true
            // A person's own devices are among a destination that holds that person.
            val person = a == MEMBER || Selectors.parse(a).kind.let { it == SelectorKind.USER || it == SelectorKind.GROUP }
            return person && covers(m, host, a)
        }
        return covers(m, host, b)
    }

    /** Whether selector [rule] takes in every device [x] stands for. */
    private fun covers(m: PolicyModel, rule: String, x: String): Boolean {
        if (rule == x || rule == "*") return true
        val kx = Selectors.parse(x).kind
        return when (Selectors.parse(rule).kind) {
            SelectorKind.AUTOGROUP -> when (rule) {
                MEMBER -> kx == SelectorKind.USER || kx == SelectorKind.GROUP
                TAGGED -> kx == SelectorKind.TAG
                else -> false
            }
            SelectorKind.GROUP -> {
                val members = members(m, rule) ?: return false
                fun holds(user: String) = members.any { it.equals(user, ignoreCase = true) }
                when (kx) {
                    SelectorKind.USER -> holds(x)
                    SelectorKind.GROUP -> members(m, x)?.takeIf { it.isNotEmpty() }?.all(::holds) == true
                    else -> false
                }
            }
            // user:*@example.com holds every login of that domain.
            SelectorKind.USER_DOMAIN -> kx == SelectorKind.USER && x.endsWith("@" + rule.substringAfter("*@"), ignoreCase = true)
            else -> false
        }
    }

    private fun members(m: PolicyModel, group: String): List<String>? = m.groups.firstOrNull { it.name == group }?.values

    /**
     * The test that pins a new path from [src] to [dst] on [port]: the first source a test may
     * name (a user, a group, a tag) reaching the first such destination. With a device list
     * ([view]), the first pair whose two sides both have devices — Headscale refuses a test
     * whose source has none. Their own devices and every member cannot be named by a test, so
     * a path of only those has none; nor is one written that [m] has already.
     */
    fun pairTest(m: PolicyModel, src: List<String>, dst: List<String>, port: String, proto: String?, view: TailnetView? = null, policy: HuObject? = null): Fields? {
        val known = view?.takeIf { it.devices.isNotEmpty() }
        // A side the phone cannot resolve is taken as it is: only a side known to be empty is passed over.
        fun hasDevices(s: String): Boolean = known == null || ReachResolver.devicesFor(s, policy, known, null)?.isNotEmpty() != false
        val pair = src.filter(::testable).asSequence()
            .flatMap { a -> dst.filter(::testable).asSequence().map { b -> a to b } }
            .firstOrNull { (a, b) -> hasDevices(a) && hasDevices(b) } ?: return null
        val (from, to) = pair
        val target = "$to:$port"
        if (m.tests.any { it.src == from && target in it.accept }) return null
        return PolicyEdits.testFields(from, listOf(target), emptyList(), proto = proto)
    }

    private fun testable(s: String): Boolean = when (Selectors.parse(s).kind) {
        SelectorKind.USER, SelectorKind.GROUP, SelectorKind.TAG -> true
        else -> false
    }

    /**
     * The sources that cannot have "their own devices": tags, when [dst] has autogroup:self. A
     * tagged device belongs to no person, and the server refuses the rule.
     */
    fun selfWithoutOwner(src: List<String>, dst: List<String>): List<String> =
        if (SELF !in dst) emptyList() else src.filter { it == TAGGED || Selectors.parse(it).kind == SelectorKind.TAG }
}
