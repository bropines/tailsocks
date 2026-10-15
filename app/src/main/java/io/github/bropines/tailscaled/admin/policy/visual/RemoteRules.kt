package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PortSet
import io.github.bropines.tailscaled.admin.policy.ReachResolver
import io.github.bropines.tailscaled.admin.policy.TailnetView

/*
 * TailSocks remote management in the policy, without Compose. A managing device opens the
 * managed device's TailSocks on TCP port 28115 (`RemotePort` in appctr/remote.go), and that
 * connection is all the policy has to allow: whether the one who asks is the owner or a guest
 * is decided by the managed device from the asker's account, never by the policy. So the
 * template asks only who manages whom, writes one access rule for it in the syntax the file
 * already uses, and a test that keeps a later edit from dropping it silently. A path that a
 * rule of the file opens already is named instead of being written a second time.
 */
object RemoteRules {

    /** The port remote management listens on, on every managed device. */
    const val PORT = 28115

    const val SELF = "autogroup:self"
    const val MEMBER = "autogroup:member"
    private const val TAGGED = "autogroup:tagged"

    /** Every tailnet IPv4 address: a destination that names it opens every device. */
    private const val TAILNET_V4 = "100.64.0.0/10"

    /** What Create writes: the rule in [section]'s syntax, and the test that pins it when one makes sense. */
    data class Plan(val section: Section, val rule: Fields, val test: Fields?)

    /**
     * The rule and its test for [managers] → [managed] in [m]; [view] and [policy] (the draft
     * parsed) let the test avoid a side with no devices, which a server may refuse to test.
     */
    fun plan(
        m: PolicyModel,
        headscale: Boolean,
        managers: List<String>,
        managed: List<String>,
        view: TailnetView? = null,
        policy: HuObject? = null,
    ): Plan {
        val section = RuleForms.newAccessSection(m, headscale)
        return Plan(section, ruleFields(section, managers, managed), test(m, managers, managed, view, policy))
    }

    /** The access rule: TCP to [PORT] only, as an ACL (`proto`, `host:port`) or a grant (`ip`). */
    fun ruleFields(section: Section, managers: List<String>, managed: List<String>): Fields =
        if (section == Section.GRANTS) PolicyEdits.grantFields(managers, managed, listOf("tcp:$PORT"))
        else PolicyEdits.aclFields(managers, managed.map { "$it:$PORT" }, proto = "tcp")

    /**
     * The test: the first manager a test may name (a user, a group, a tag) reaching the first
     * managed user, group or tag on [PORT]. With a device list, the first such pair whose two
     * sides both have devices — Headscale refuses a test whose source has none. Their own
     * devices and every member cannot be named by a test, so a template of only those has
     * none; nor is one written that the file has already.
     */
    fun test(m: PolicyModel, managers: List<String>, managed: List<String>, view: TailnetView? = null, policy: HuObject? = null): Fields? {
        val known = view?.takeIf { it.devices.isNotEmpty() }
        // A side the phone cannot resolve is taken as it is: only a side known to be empty is passed over.
        fun hasDevices(s: String): Boolean = known == null || ReachResolver.devicesFor(s, policy, known, null)?.isNotEmpty() != false
        val pair = managers.filter(::testable).asSequence()
            .flatMap { a -> managed.filter(::testable).asSequence().map { b -> a to b } }
            .firstOrNull { (a, b) -> hasDevices(a) && hasDevices(b) } ?: return null
        val (src, dst) = pair
        val target = "$dst:$PORT"
        if (m.tests.any { it.src == src && target in it.accept }) return null
        return PolicyEdits.testFields(src, listOf(target), emptyList(), proto = "tcp")
    }

    private fun testable(s: String): Boolean = when (Selectors.parse(s).kind) {
        SelectorKind.USER, SelectorKind.GROUP, SelectorKind.TAG -> true
        else -> false
    }

    /**
     * [text] with [plan] written: the rule at the end of its section, [note] as the comment over
     * it, then the test at the end of `tests`. With [rule] false only the test — for a path a
     * rule of the file opens already. One call, so one step to undo.
     */
    fun write(text: String, plan: Plan, note: String? = null, rule: Boolean = true): String {
        var t = if (rule) PolicyEdits.addRule(text, plan.section, plan.rule, note = note) else text
        plan.test?.let { t = PolicyEdits.addRule(t, Section.TESTS, it) }
        return t
    }

    /**
     * The managers that cannot have "their own devices": tags, when [managed] has
     * autogroup:self. A tagged device belongs to no person, and the server refuses the rule.
     */
    fun selfWithoutOwner(managers: List<String>, managed: List<String>): List<String> =
        if (SELF !in managed) emptyList() else managers.filter { it == TAGGED || Selectors.parse(it).kind == SelectorKind.TAG }

    // ---- what the file opens already ----

    /**
     * The rules of [m] that already let every one of [managers] reach every one of [managed] on
     * TCP [PORT], one per pair, in the order found; null when some pair is not open (or a side is
     * empty). Read from the selectors alone — the same name, `*`, an autogroup or a group that
     * holds it — so it never says "open" for a path that is not; a rule that holds only under a
     * device posture or through a router does not count.
     */
    fun openedBy(m: PolicyModel, managers: List<String>, managed: List<String>): List<PolicyPath>? {
        if (managers.isEmpty() || managed.isEmpty()) return null
        val found = LinkedHashSet<PolicyPath>()
        for (a in managers) for (b in managed) found += opening(m, a, b) ?: return null
        return found.toList()
    }

    /** The first rule that opens [PORT] from [a]'s devices to [b]'s. */
    private fun opening(m: PolicyModel, a: String, b: String): PolicyPath? {
        m.acls.firstOrNull { r ->
            r.action == "accept" && r.srcPosture.isEmpty() && tcp(r.proto) && r.src.any { covers(m, it, a) } &&
                r.destinations.any { hp -> portOpen(hp.ports) && reaches(m, hp.host, a, b) }
        }?.let { return it.origin.path }
        m.grants.firstOrNull { g ->
            g.via.isEmpty() && g.srcPosture.isEmpty() && g.ipSpecs.any { tcp(it.proto) && portOpen(it.ports) } &&
                g.src.any { covers(m, it, a) } && g.dst.any { reaches(m, it, a, b) }
        }?.let { return it.origin.path }
        return null
    }

    private fun tcp(proto: String?): Boolean = proto == null || proto.equals("tcp", ignoreCase = true) || proto == "6"

    private fun portOpen(ports: String?): Boolean {
        val set = PortSet.parse(ports ?: return false) ?: return false
        return (PortSet.single(PORT) - set).isEmpty
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
}
