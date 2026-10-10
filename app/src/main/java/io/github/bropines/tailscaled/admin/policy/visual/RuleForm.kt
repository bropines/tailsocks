package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PortSet
import io.github.bropines.tailscaled.admin.policy.TailnetView

/*
 * The rule pages' logic without Compose: which syntax a new access rule is written in, what a
 * form turns into, which fields an edit actually changes, where Move up / Move down put a rule,
 * the templates of an empty Access page and the SSH page's port-22 check. The editors only
 * draw it and hand the result to PolicyEdits.
 */

/** An access rule in either syntax: the Access page lists acls and grants together. */
sealed class AccessRule {
    abstract val origin: Origin
    abstract val src: List<String>
    abstract val section: Section

    data class Acl(val rule: AclRule) : AccessRule() {
        override val origin: Origin get() = rule.origin
        override val src: List<String> get() = rule.src
        override val section: Section get() = Section.ACLS
    }

    data class Grant(val rule: GrantRule) : AccessRule() {
        override val origin: Origin get() = rule.origin
        override val src: List<String> get() = rule.src
        override val section: Section get() = Section.GRANTS
    }

    /** Its place in its own list (acls or grants). */
    val index: Int get() = (origin.path.last as? PathStep.Index)?.index ?: -1
}

/** The ready-made rules an empty Access page offers. */
enum class AccessTemplate {
    /** Every member reaches the devices they own. */
    OWN_DEVICES,
    /** The tailnet's admins reach every device on every port (Tailscale: Headscale has no role autogroups). */
    ADMINS_EVERYTHING,
    /** Someone uses exit nodes and nothing else: the internet, not the nodes themselves. */
    GUESTS_INTERNET,
    /** A group reaches a tag on chosen ports: an empty form to fill in. */
    GROUP_TO_TAG,
}

object RuleForms {

    // ---- what a rule is, as fields ----

    fun fields(r: AclRule): Fields = PolicyEdits.aclFields(r.src, r.dst, r.proto, r.srcPosture)

    fun fields(r: GrantRule): Fields = PolicyEdits.grantFields(r.src, r.dst, r.ip, r.via, r.srcPosture)

    fun fields(r: AccessRule): Fields = when (r) {
        is AccessRule.Acl -> fields(r.rule)
        is AccessRule.Grant -> fields(r.rule)
    }

    /** SSH fields, device posture included (sshFields leaves it out: the console's own SSH rules never had one). */
    fun fields(r: SshRule): Fields =
        PolicyEdits.sshFields(r.action, r.src, r.dst, r.users, r.checkPeriod, r.acceptEnv) + ("srcPosture" to r.srcPosture.takeIf { it.isNotEmpty() }?.pv())

    fun fields(t: AclTest): Fields = PolicyEdits.testFields(t.src, t.accept, t.deny, t.proto)

    fun fields(t: SshTest): Fields = PolicyEdits.sshTestFields(t.src, t.dst, t.accept, t.check, t.deny)

    /**
     * The fields of [new] that differ from [old]: what an edit hands to updateRule, so that a
     * change of one field touches that field's text and nothing else of the rule.
     */
    fun changed(old: Fields, new: Fields): Fields = new.filter { (k, v) -> old.firstOrNull { it.first == k }?.second != v }

    // ---- new rules ----

    /**
     * The section a new access rule goes to: the syntax the file uses most, so that a file of
     * acls stays a file of acls; on a tie (an empty file) grants on Tailscale, where they are the
     * recommended form, and acls on Headscale, whose older releases know nothing else.
     */
    fun newAccessSection(m: PolicyModel, headscale: Boolean): Section = when {
        m.acls.size > m.grants.size -> Section.ACLS
        m.grants.size > m.acls.size -> Section.GRANTS
        headscale -> Section.ACLS
        else -> Section.GRANTS
    }

    /** A placeholder origin for a form that is not in the file yet: the place it would take. */
    fun newOrigin(m: PolicyModel, section: Section): Origin {
        val size = when (section) {
            Section.ACLS -> m.acls.size
            Section.GRANTS -> m.grants.size
            Section.SSH -> m.ssh.size
            Section.TESTS -> m.tests.size
            Section.SSH_TESTS -> m.sshTests.size
            else -> 0
        }
        return Origin(PolicyPath.of(section.key, size), line = 0)
    }

    /** An empty access rule in [section]'s syntax. */
    fun emptyAccess(m: PolicyModel, section: Section): AccessRule {
        val o = newOrigin(m, section)
        return if (section == Section.GRANTS) AccessRule.Grant(GrantRule(o, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        else AccessRule.Acl(AclRule(o, "accept", emptyList(), emptyList(), null, emptyList()))
    }

    fun emptySsh(m: PolicyModel): SshRule = SshRule(newOrigin(m, Section.SSH), "accept", emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())

    fun emptyTest(m: PolicyModel): AclTest = AclTest(newOrigin(m, Section.TESTS), "", null, emptyList(), emptyList(), null)

    fun emptySshTest(m: PolicyModel): SshTest = SshTest(newOrigin(m, Section.SSH_TESTS), "", emptyList(), emptyList(), emptyList(), emptyList())

    /**
     * Whether a form has what the server needs before it is written: a source and a destination,
     * and for a grant the ports too — a grant with neither `ip` nor `app` is refused, and one
     * written with a guessed "all ports" would open more than was asked.
     */
    fun complete(r: AccessRule): Boolean = when (r) {
        is AccessRule.Acl -> r.rule.src.isNotEmpty() && r.rule.dst.isNotEmpty()
        is AccessRule.Grant -> r.rule.src.isNotEmpty() && r.rule.dst.isNotEmpty() && (r.rule.ip.isNotEmpty() || r.rule.app.isNotEmpty())
    }

    fun complete(r: SshRule): Boolean = r.action.isNotEmpty() && r.src.isNotEmpty() && r.dst.isNotEmpty() && r.users.isNotEmpty()

    fun complete(t: AclTest): Boolean = t.src.isNotBlank() && (t.accept.isNotEmpty() || t.deny.isNotEmpty())

    fun complete(t: SshTest): Boolean = t.src.isNotBlank() && t.dst.isNotEmpty() && (t.accept.isNotEmpty() || t.check.isNotEmpty() || t.deny.isNotEmpty())

    /** The templates this backend accepts: Headscale has no admin role to name. */
    fun templates(headscale: Boolean): List<AccessTemplate> =
        AccessTemplate.entries.filter { !(headscale && it == AccessTemplate.ADMINS_EVERYTHING) }

    /** A template as a form in [section]'s syntax; complete ones are written at once, the others opened to fill in. */
    fun template(t: AccessTemplate, m: PolicyModel, section: Section): AccessRule {
        val (src, hosts) = when (t) {
            AccessTemplate.OWN_DEVICES -> listOf("autogroup:member") to listOf("autogroup:self")
            AccessTemplate.ADMINS_EVERYTHING -> listOf("autogroup:admin") to listOf("*")
            AccessTemplate.GUESTS_INTERNET -> emptyList<String>() to listOf("autogroup:internet")
            AccessTemplate.GROUP_TO_TAG -> emptyList<String>() to emptyList()
        }
        val o = newOrigin(m, section)
        return if (section == Section.GRANTS) {
            AccessRule.Grant(GrantRule(o, src, hosts, if (hosts.isEmpty()) emptyList() else listOf("*"), emptyList(), emptyList(), emptyList()))
        } else {
            AccessRule.Acl(AclRule(o, "accept", src, hosts.map { "$it:*" }, null, emptyList()))
        }
    }

    // ---- derived rules ----

    /** Whether "Convert to grant" is offered: Tailscale always; Headscale once the file has grants (so the server takes them). */
    fun canConvert(m: PolicyModel, headscale: Boolean): Boolean = !headscale || m.grants.isNotEmpty()

    /**
     * The edit that replaces the ACL at [rule] by its grants, the ACL's note on the first of
     * them; the grants go at the end of `grants`.
     */
    fun convertToGrants(text: String, rule: AclRule): String {
        var out = PolicyEdits.removeRule(text, rule.origin.path)
        PolicyEdits.grantsFrom(rule).forEachIndexed { i, f ->
            out = PolicyEdits.addRule(out, Section.GRANTS, f, note = rule.origin.note.takeIf { i == 0 })
        }
        return out
    }

    /**
     * [rule] as one grant that also goes [via] these tags: what an ACL becomes when it is given
     * something only grants have. Null when its destinations have different ports — one grant
     * cannot say that; Convert to grant makes one per port list.
     */
    fun grantWithVia(rule: AclRule, via: List<String>, origin: Origin): GrantRule? {
        val ports = rule.destinations.map { it.ports ?: "*" }.distinct()
        if (ports.size > 1) return null
        val ip = ports.singleOrNull()?.let { p -> PortWords.parts(p).map { if (rule.proto != null) "${rule.proto}:$it" else it } }.orEmpty()
        return GrantRule(origin, rule.src, rule.destinations.map { it.host }.distinct(), ip, emptyList(), via, rule.srcPosture)
    }

    /** The edit that replaces the ACL at [rule] by [grant], keeping its note; the grant goes at the end of `grants`. */
    fun replaceWithGrant(text: String, rule: AclRule, grant: GrantRule): String {
        val out = PolicyEdits.removeRule(text, rule.origin.path)
        return PolicyEdits.addRule(out, Section.GRANTS, fields(grant), note = rule.origin.note)
    }

    /** A copy of the rule at [path] right after it, written exactly as the original is — its unknown fields and app capabilities too. */
    fun duplicate(text: String, path: PolicyPath): String {
        val t = SourceTree.parse(text)
        val v = t.at(path) ?: throw PolicyEditException("no rule at $path")
        val index = (path.last as? PathStep.Index)?.index ?: throw PolicyEditException("$path is not in a list")
        return SourceEdits.insert(text, path.parent(), index + 1, PolicyValue.Raw(t.slice(v)), Anchor.AFTER_PREVIOUS)
    }

    /**
     * Where Move up takes rule [i] of a list whose rules carry a heading where [headers] says so:
     * the first rule under a heading goes above it (into the run before, keeping its index);
     * any other rule swaps with the one above it, staying under their heading. Null at the top.
     */
    fun moveUp(headers: List<Boolean>, i: Int): Pair<Int, Anchor>? = when {
        i <= 0 || i > headers.lastIndex -> null
        headers[i] -> i to Anchor.AFTER_PREVIOUS
        else -> i - 1 to Anchor.BEFORE_NEXT
    }

    /**
     * Where "Add a rule here" under the heading over rule [i] puts the new one: after the last
     * rule of the run that heading starts, which ends where the next heading begins.
     */
    fun insertAfterRun(headers: List<Boolean>, i: Int): Int {
        var end = i
        while (end + 1 < headers.size && !headers[end + 1]) end++
        return end + 1
    }

    /** Where Move down takes rule [i]: below the next one, across its heading if it has one. Null at the bottom. */
    fun moveDown(headers: List<Boolean>, i: Int): Pair<Int, Anchor>? = when {
        i < 0 || i >= headers.lastIndex -> null
        else -> i + 1 to Anchor.AFTER_PREVIOUS
    }

    /**
     * The user the server is asked about for a rule's "Check with the server": the first login
     * among its sources, else the first member of its first group the file defines.
     */
    fun previewUser(src: List<String>, m: PolicyModel): String? {
        src.firstOrNull { Selectors.parse(it).kind == SelectorKind.USER }?.let { return it }
        return src.asSequence().filter { it.startsWith("group:") }.mapNotNull { g -> m.groups.firstOrNull { it.name == g }?.values?.firstOrNull() }.firstOrNull()
    }

    // ---- SSH ----

    /** Login names an SSH users picker suggests: root, then every name the file's SSH rules and tests already use. */
    fun loginSuggestions(m: PolicyModel): List<String> =
        (listOf("root") + m.ssh.flatMap { it.users } + m.sshTests.flatMap { it.accept + it.check + it.deny })
            .filter { !it.startsWith("autogroup:") && !it.startsWith("localpart:") && it != "*" }
            .distinct()

    private val DURATION = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""")

    /** A check period the server takes: "always", or a duration from one minute to 168 hours ("12h", "30m", "1h30m"). */
    fun periodOk(p: String): Boolean {
        if (p == "always") return true
        val m = DURATION.matchEntire(p.trim()) ?: return false
        if (p.isBlank()) return false
        val (h, min, sec) = m.destructured
        val seconds = (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
        return seconds in 60..168 * 3600
    }

    /** The period a check rule has when it names none: Tailscale's default. */
    const val DEFAULT_PERIOD = "12h"

    // ---- SSH needs the network too ----

    private fun opens22(ports: String?): Boolean {
        val set = PortSet.parse(ports ?: return false) ?: return false
        return (PortSet.single(22) - set).isEmpty
    }

    private fun tcp(proto: String?): Boolean = proto == null || proto.equals("tcp", ignoreCase = true) || proto == "6"

    /**
     * Whether some device among [rule]'s sources cannot reach some device among its
     * destinations on TCP port 22 under the access rules of [m]: Tailscale SSH needs that
     * connection as well as the SSH rule. False whenever the phone cannot tell — a selector it
     * cannot resolve, or no device list.
     */
    fun sshNeedsAccess(rule: SshRule, m: PolicyModel, policy: HuObject?, view: TailnetView): Boolean {
        if (view.devices.isEmpty() || rule.src.isEmpty() || rule.dst.isEmpty()) return false
        val from = RuleCoverage.of(rule.src, policy, view, subjectOf(rule.src))
        val to = RuleCoverage.of(rule.dst, policy, view, subjectOf(rule.src))
        if (!from.complete || !to.complete || from.devices.isEmpty() || to.devices.isEmpty()) return false
        val open = mutableListOf<Pair<Set<io.github.bropines.tailscaled.admin.api.ApiDevice>, Set<io.github.bropines.tailscaled.admin.api.ApiDevice>>>()
        for (a in m.acls) {
            if (!tcp(a.proto)) continue
            val hosts = a.destinations.filter { opens22(it.ports) }.map { it.host }
            if (hosts.isEmpty()) continue
            open += RuleCoverage.of(a.src, policy, view).devices to RuleCoverage.of(hosts, policy, view).devices
        }
        for (g in m.grants) {
            if (g.ipSpecs.none { tcp(it.proto) && opens22(it.ports) }) continue
            open += RuleCoverage.of(g.src, policy, view).devices to RuleCoverage.of(g.dst, policy, view).devices
        }
        return from.devices.any { s -> to.devices.any { d -> s != d && open.none { (a, b) -> s in a && d in b } } }
    }

    /** autogroup:self needs a subject; a rule from one user resolves it to theirs. */
    private fun subjectOf(src: List<String>): String? = src.singleOrNull()?.takeIf { Selectors.parse(it).kind == SelectorKind.USER }

    /** The access rule that opens port 22 for an SSH rule, in [section]'s syntax. */
    fun accessForSsh(rule: SshRule, section: Section): Fields =
        if (section == Section.GRANTS) PolicyEdits.grantFields(rule.src, rule.dst, listOf("22"))
        else PolicyEdits.aclFields(rule.src, rule.dst.map { "$it:22" })
}
