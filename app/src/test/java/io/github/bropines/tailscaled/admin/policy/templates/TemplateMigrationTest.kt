package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PortSet
import io.github.bropines.tailscaled.admin.policy.ReachResolver
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.acls
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.apply
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.items
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.load
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.plan
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.policies
import io.github.bropines.tailscaled.admin.policy.visual.AccessRule
import io.github.bropines.tailscaled.admin.policy.visual.AclRule
import io.github.bropines.tailscaled.admin.policy.visual.Fields
import io.github.bropines.tailscaled.admin.policy.visual.GrantRule
import io.github.bropines.tailscaled.admin.policy.visual.PeerRelays
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import io.github.bropines.tailscaled.admin.policy.visual.PolicyValue
import io.github.bropines.tailscaled.admin.policy.visual.RuleForms
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.SelectorKind
import io.github.bropines.tailscaled.admin.policy.visual.Selectors
import io.github.bropines.tailscaled.admin.policy.visual.pv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every built-in template, moved into policy-templates/, writes byte for byte what the code it
 * replaced wrote for the same inputs: the same policies, both backends, both syntaxes. That code
 * is frozen below ([Legacy]) as it was when the templates became data; the first run of this
 * test compared against it in place, before it was removed.
 */
class TemplateMigrationTest {

    private fun model(text: String) = PolicyModel.read(text)!!

    // ---- the Access page's templates ----

    /** What the empty Access page wrote for [t], its form filled in by [fill]. */
    private fun oldAccess(t: Legacy.Access, text: String, headscale: Boolean, fill: (AccessRule) -> AccessRule = { it }): String {
        val m = model(text)
        val section = RuleForms.newAccessSection(m, headscale)
        val rule = fill(Legacy.access(t, m, section))
        assertTrue(RuleForms.complete(rule))
        return PolicyEdits.addRule(text, rule.section, RuleForms.fields(rule), index = null)
    }

    private fun eachPolicy(block: (name: String, text: String, headscale: Boolean) -> Unit) {
        for ((name, text) in policies) for (headscale in listOf(false, true)) block(name, text, headscale)
    }

    @Test
    fun ownDevices() = eachPolicy { name, text, hs ->
        assertEquals("$name hs=$hs", oldAccess(Legacy.Access.OWN_DEVICES, text, hs), apply(load("own-devices"), text, headscale = hs))
    }

    @Test
    fun adminsEverything() = eachPolicy { name, text, hs ->
        if (hs) return@eachPolicy
        assertEquals(name, oldAccess(Legacy.Access.ADMINS_EVERYTHING, text, false), apply(load("admins-everything"), text))
    }

    @Test
    fun adminsEverythingIsNotOfferedOnHeadscale() {
        assertTrue(TemplateIssue.NotOnHeadscale in plan(load("admins-everything"), acls, headscale = true).issues)
        val catalog = TemplateCatalog(TemplateTestKit.all().map { LoadedTemplate(it, TemplateOrigin.BUNDLED) })
        assertTrue("admins-everything" !in catalog.offered(headscale = true).map { it.template.id })
        assertTrue("admins-everything" in catalog.offered(headscale = false).map { it.template.id })
    }

    @Test
    fun guests() = eachPolicy { name, text, hs ->
        val guests = listOf("group:family", "tag:tv")
        val old = oldAccess(Legacy.Access.GUESTS_INTERNET, text, hs) { r ->
            when (r) {
                is AccessRule.Acl -> r.copy(rule = r.rule.copy(src = guests))
                is AccessRule.Grant -> r.copy(rule = r.rule.copy(src = guests))
            }
        }
        assertEquals("$name hs=$hs", old, apply(load("guests-exit-nodes"), text, mapOf("guests" to ParamValue.Items(guests)), headscale = hs))
    }

    @Test
    fun groupToTag() = eachPolicy { name, text, hs ->
        for (ports in listOf(listOf("22"), listOf("80", "443"), listOf("8000-8100"), listOf("*"))) {
            // The rule editor wrote an ACL destination's ports as one list, a grant's one per entry.
            val old = oldAccess(Legacy.Access.GROUP_TO_TAG, text, hs) { r ->
                when (r) {
                    is AccessRule.Acl -> r.copy(rule = r.rule.copy(src = listOf("group:admins"), dst = listOf("tag:web:" + ports.joinToString(","))))
                    is AccessRule.Grant -> r.copy(rule = r.rule.copy(src = listOf("group:admins"), dst = listOf("tag:web"), ip = ports))
                }
            }
            val values = mapOf("group" to items("group:admins"), "tag" to items("tag:web"), "ports" to ParamValue.Items(ports))
            assertEquals("$name hs=$hs $ports", old, apply(load("group-to-tag"), text, values, headscale = hs))
        }
    }

    // ---- remote management ----

    private val note = "TailSocks remote management (TCP 28115)"

    /** What the remote-management form's Create wrote; null when it offered nothing to create. */
    private fun oldRemote(text: String, from: List<String>, to: List<String>, headscale: Boolean, view: TailnetView? = null): String? {
        val m = model(text)
        val plan = Legacy.remotePlan(m, headscale, from, to, view, HuJson.parseOrNull(text))
        val testOnly = Legacy.openedBy(m, from, to) != null
        if (testOnly && plan.test == null) return null
        return Legacy.remoteWrite(text, plan, note, rule = !testOnly)
    }

    private fun newRemote(text: String, from: List<String>, to: List<String>, headscale: Boolean, view: TailnetView? = null): String? {
        val values = mapOf("managers" to ParamValue.Items(from), "managed" to ParamValue.Items(to))
        val p = plan(load("tailsocks-remote"), text, values, headscale, view)
        if (TemplateIssue.NothingToAdd in p.issues) return null
        assertTrue(p.issues.toString(), p.ready)
        return p.apply(text)
    }

    @Test
    fun remote() = eachPolicy { name, text, hs ->
        val cases = listOf(
            listOf("alice@example.com") to listOf("mom@example.com", "dad@example.com"),
            listOf("group:admins") to listOf("group:family"),
            listOf("group:admins", "tag:admin") to listOf("tag:tv"),
            listOf(AccessCoverage.MEMBER) to listOf(AccessCoverage.SELF),
            listOf("group:parents") to listOf(AccessCoverage.SELF),
            listOf("alice@example.com") to listOf(AccessCoverage.SELF, "tag:tv"),
        )
        for ((from, to) in cases) assertEquals("$name hs=$hs $from → $to", oldRemote(text, from, to, hs), newRemote(text, from, to, hs))
    }

    @Test
    fun remoteOnAPathThatIsOpenWritesOnlyTheTest() {
        val open = PolicyEdits.addRule(acls, Section.ACLS, PolicyEdits.aclFields(listOf("group:admins"), listOf("tag:tv:*")))
        val old = oldRemote(open, listOf("group:admins"), listOf("tag:tv"), false)!!
        assertEquals(old, newRemote(open, listOf("group:admins"), listOf("tag:tv"), false))
        // And with the test there too, neither offers anything.
        assertEquals(null, oldRemote(old, listOf("group:admins"), listOf("tag:tv"), false))
        assertEquals(null, newRemote(old, listOf("group:admins"), listOf("tag:tv"), false))
    }

    @Test
    fun remoteTestAvoidsASideWithoutDevices() {
        val view = TailnetView(
            listOf(
                ApiDevice(nodeId = "1", name = "tv", tags = listOf("tag:tv"), addresses = listOf("100.64.0.2")),
                ApiDevice(nodeId = "2", name = "mom-phone", user = "mom@example.com", addresses = listOf("100.64.0.3")),
            ),
            null,
        )
        val cases = listOf(
            listOf("group:admins", "tag:admin") to listOf("tag:admin", "tag:tv"),
            listOf("dad@example.com", "mom@example.com") to listOf("tag:tv"),
        )
        for ((from, to) in cases) assertEquals("$from → $to", oldRemote(acls, from, to, false, view), newRemote(acls, from, to, false, view))
    }

    @Test
    fun remoteRefusesTagsWithTheirOwnDevices() {
        val p = plan(load("tailsocks-remote"), acls, mapOf("managers" to items("alice@example.com", "tag:admin"), "managed" to items(AccessCoverage.SELF)))
        assertEquals(Legacy.selfWithoutOwner(listOf("alice@example.com", "tag:admin"), listOf(AccessCoverage.SELF)), (p.issues.single() as TemplateIssue.SelfWithoutOwner).sources)
    }

    @Test
    fun theAccessOpenCheckIsTheOldDuplicateDetection() {
        val texts = listOf(
            acls,
            PolicyEdits.addRule(acls, Section.ACLS, PolicyEdits.aclFields(listOf("*"), listOf("*:*"))),
            PolicyEdits.addRule(acls, Section.ACLS, PolicyEdits.aclFields(listOf("group:family"), listOf("group:family:28000-29000"))),
            PolicyEdits.addRule(acls, Section.ACLS, PolicyEdits.aclFields(listOf("autogroup:member"), listOf("autogroup:self:*"))),
            PolicyEdits.addRule(acls, Section.ACLS, PolicyEdits.aclFields(listOf("autogroup:tagged"), listOf("autogroup:tagged:28115"), proto = "udp")),
            PolicyEdits.addRule(TemplateTestKit.grants, Section.GRANTS, PolicyEdits.grantFields(listOf("group:admins"), listOf("tag:tv"), listOf("tcp:*"))),
        )
        val sides = listOf("alice@example.com", "mom@example.com", "group:admins", "group:parents", "group:family", "tag:tv", "tag:admin", AccessCoverage.MEMBER, AccessCoverage.SELF)
        for (text in texts) for (a in sides) for (b in sides) {
            val m = model(text)
            assertEquals("$a → $b", Legacy.openedBy(m, listOf(a), listOf(b)), AccessCoverage.openedBy(m, listOf(a), listOf(b), PortSet.single(Legacy.PORT), "tcp"))
        }
    }

    // ---- the Attributes page ----

    @Test
    fun taildrive() {
        for ((name, text) in policies) assertEquals(name, Legacy.taildrive(text), apply(load("taildrive-own-devices"), text))
        // The sample has a Taildrive grant: it stays as it is, and the page says so.
        val p = plan(load("taildrive-own-devices"), TemplateTestKit.sample)
        assertTrue(p.checks.single { it.check.id == "grant" }.holds)
        assertTrue(p.ops.none { it is TemplateOp.Add && it.section == Section.GRANTS })
    }

    @Test
    fun fasterStart() {
        for ((name, text) in policies) {
            for (hs in listOf(false, true)) {
                val old = PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(Legacy.FASTER_TARGETS, Legacy.FASTER_ATTRS))
                assertEquals("$name hs=$hs", old, apply(load("faster-start"), text, headscale = hs))
            }
            val old = PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(Legacy.FASTER_TARGETS + "tag:web", Legacy.FASTER_ATTRS))
            assertEquals(name, old, apply(load("faster-start"), text, mapOf("devices" to items("autogroup:member", "tag:web"))))
        }
    }

    // ---- the Peer relays page ----

    @Test
    fun relays() = eachPolicy { name, text, hs ->
        val site = mapOf("relays" to items("tag:web"), "clients" to items("tag:tv", "group:family"))
        assertEquals("$name hs=$hs", PeerRelays.add(text, listOf("tag:tv", "group:family"), listOf("tag:web")), apply(load("relays-site"), text, site, headscale = hs))
        val self = mapOf("devices" to items("tag:web", "tag:admin"))
        assertEquals("$name hs=$hs", PeerRelays.add(text, listOf("tag:web", "tag:admin"), listOf("tag:web", "tag:admin")), apply(load("relays-self"), text, self, headscale = hs))
    }

    /**
     * The code the templates replaced, as it was: RuleForms.template (RuleForm.kt), RemoteRules,
     * TaildriveRules.withGrant and the Attributes page's presets (NodeAttrsSection.kt).
     */
    private object Legacy {

        enum class Access { OWN_DEVICES, ADMINS_EVERYTHING, GUESTS_INTERNET, GROUP_TO_TAG }

        fun access(t: Access, m: PolicyModel, section: Section): AccessRule {
            val (src, hosts) = when (t) {
                Access.OWN_DEVICES -> listOf("autogroup:member") to listOf("autogroup:self")
                Access.ADMINS_EVERYTHING -> listOf("autogroup:admin") to listOf("*")
                Access.GUESTS_INTERNET -> emptyList<String>() to listOf("autogroup:internet")
                Access.GROUP_TO_TAG -> emptyList<String>() to emptyList()
            }
            val o = RuleForms.newOrigin(m, section)
            return if (section == Section.GRANTS) {
                AccessRule.Grant(GrantRule(o, src, hosts, if (hosts.isEmpty()) emptyList() else listOf("*"), emptyList(), emptyList(), emptyList()))
            } else {
                AccessRule.Acl(AclRule(o, "accept", src, hosts.map { "$it:*" }, null, emptyList()))
            }
        }

        const val PORT = 28115
        private const val SELF = "autogroup:self"
        private const val MEMBER = "autogroup:member"
        private const val TAGGED = "autogroup:tagged"
        private const val TAILNET_V4 = "100.64.0.0/10"

        data class Plan(val section: Section, val rule: Fields, val test: Fields?)

        fun remotePlan(m: PolicyModel, headscale: Boolean, managers: List<String>, managed: List<String>, view: TailnetView?, policy: HuObject?): Plan {
            val section = RuleForms.newAccessSection(m, headscale)
            val rule = if (section == Section.GRANTS) PolicyEdits.grantFields(managers, managed, listOf("tcp:$PORT"))
            else PolicyEdits.aclFields(managers, managed.map { "$it:$PORT" }, proto = "tcp")
            return Plan(section, rule, test(m, managers, managed, view, policy))
        }

        private fun test(m: PolicyModel, managers: List<String>, managed: List<String>, view: TailnetView?, policy: HuObject?): Fields? {
            val known = view?.takeIf { it.devices.isNotEmpty() }
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

        fun remoteWrite(text: String, plan: Plan, note: String?, rule: Boolean): String {
            var t = if (rule) PolicyEdits.addRule(text, plan.section, plan.rule, note = note) else text
            plan.test?.let { t = PolicyEdits.addRule(t, Section.TESTS, it) }
            return t
        }

        fun selfWithoutOwner(managers: List<String>, managed: List<String>): List<String> =
            if (SELF !in managed) emptyList() else managers.filter { it == TAGGED || Selectors.parse(it).kind == SelectorKind.TAG }

        fun openedBy(m: PolicyModel, managers: List<String>, managed: List<String>): List<io.github.bropines.tailscaled.admin.policy.visual.PolicyPath>? {
            if (managers.isEmpty() || managed.isEmpty()) return null
            val found = LinkedHashSet<io.github.bropines.tailscaled.admin.policy.visual.PolicyPath>()
            for (a in managers) for (b in managed) found += opening(m, a, b) ?: return null
            return found.toList()
        }

        private fun opening(m: PolicyModel, a: String, b: String): io.github.bropines.tailscaled.admin.policy.visual.PolicyPath? {
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

        private fun reaches(m: PolicyModel, host: String, a: String, b: String): Boolean {
            if (host == TAILNET_V4) return true
            if (b == SELF) {
                if (host == SELF) return true
                val person = a == MEMBER || Selectors.parse(a).kind.let { it == SelectorKind.USER || it == SelectorKind.GROUP }
                return person && covers(m, host, a)
            }
            return covers(m, host, b)
        }

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
                    val members = m.groups.firstOrNull { it.name == rule }?.values ?: return false
                    fun holds(user: String) = members.any { it.equals(user, ignoreCase = true) }
                    when (kx) {
                        SelectorKind.USER -> holds(x)
                        SelectorKind.GROUP -> m.groups.firstOrNull { it.name == x }?.values?.takeIf { it.isNotEmpty() }?.all(::holds) == true
                        else -> false
                    }
                }
                SelectorKind.USER_DOMAIN -> kx == SelectorKind.USER && x.endsWith("@" + rule.substringAfter("*@"), ignoreCase = true)
                else -> false
            }
        }

        private const val DRIVE = "tailscale.com/cap/drive"
        private val DRIVE_TARGETS = listOf("autogroup:member")
        private val DRIVE_ATTRS = listOf("drive:share", "drive:access")
        val FASTER_TARGETS = listOf("autogroup:member")
        val FASTER_ATTRS = listOf("cache-network-maps")

        private val driveGrant: Fields = listOf(
            "src" to DRIVE_TARGETS.pv(),
            "dst" to listOf("autogroup:self").pv(),
            "app" to PolicyValue.obj(DRIVE to PolicyValue.Arr(listOf(PolicyValue.obj("shares" to listOf("*").pv(), "access" to "rw".pv())))),
        )

        /** The Taildrive form's Create with its preset: the rule, then the grant unless one carries the capability. */
        fun taildrive(text: String): String {
            val out = PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(DRIVE_TARGETS, DRIVE_ATTRS))
            val hasGrant = PolicyModel.read(out)?.grants?.any { g -> g.app.any { it.name == DRIVE } } == true
            return if (hasGrant) out else PolicyEdits.addRule(out, Section.GRANTS, driveGrant)
        }
    }
}
