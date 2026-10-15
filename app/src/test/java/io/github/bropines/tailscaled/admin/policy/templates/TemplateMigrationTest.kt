package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.acls
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.apply
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.items
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.load
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.plan
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.policies
import io.github.bropines.tailscaled.admin.policy.visual.AccessRule
import io.github.bropines.tailscaled.admin.policy.visual.AccessTemplate
import io.github.bropines.tailscaled.admin.policy.visual.AttrTemplate
import io.github.bropines.tailscaled.admin.policy.visual.KnownAttrs
import io.github.bropines.tailscaled.admin.policy.visual.PeerRelays
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import io.github.bropines.tailscaled.admin.policy.visual.RemoteRules
import io.github.bropines.tailscaled.admin.policy.visual.RuleForms
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.TaildriveRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every built-in template, moved into policy-templates/, writes byte for byte what the code it
 * replaces wrote for the same inputs: the same policies, both backends, both syntaxes.
 */
class TemplateMigrationTest {

    private fun model(text: String) = PolicyModel.read(text)!!

    // ---- the Access page's templates ----

    /** What the empty Access page wrote for [t], its form filled in by [fill]. */
    private fun oldAccess(t: AccessTemplate, text: String, headscale: Boolean, fill: (AccessRule) -> AccessRule = { it }): String {
        val m = model(text)
        val section = RuleForms.newAccessSection(m, headscale)
        val rule = fill(RuleForms.template(t, m, section))
        assertTrue(RuleForms.complete(rule))
        return PolicyEdits.addRule(text, rule.section, RuleForms.fields(rule), index = null)
    }

    private fun eachPolicy(block: (name: String, text: String, headscale: Boolean) -> Unit) {
        for ((name, text) in policies) for (headscale in listOf(false, true)) block(name, text, headscale)
    }

    @Test
    fun ownDevices() = eachPolicy { name, text, hs ->
        assertEquals("$name hs=$hs", oldAccess(AccessTemplate.OWN_DEVICES, text, hs), apply(load("own-devices"), text, headscale = hs))
    }

    @Test
    fun adminsEverything() = eachPolicy { name, text, hs ->
        if (hs) return@eachPolicy
        assertEquals(name, oldAccess(AccessTemplate.ADMINS_EVERYTHING, text, false), apply(load("admins-everything"), text))
    }

    @Test
    fun adminsEverythingIsNotOfferedOnHeadscale() {
        assertTrue(AccessTemplate.ADMINS_EVERYTHING !in RuleForms.templates(headscale = true))
        assertTrue(TemplateIssue.NotOnHeadscale in plan(load("admins-everything"), acls, headscale = true).issues)
    }

    @Test
    fun guests() = eachPolicy { name, text, hs ->
        val guests = listOf("group:family", "tag:tv")
        val old = oldAccess(AccessTemplate.GUESTS_INTERNET, text, hs) { r ->
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
            val old = oldAccess(AccessTemplate.GROUP_TO_TAG, text, hs) { r ->
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
        val plan = RemoteRules.plan(m, headscale, from, to, view, HuJson.parseOrNull(text))
        val testOnly = RemoteRules.openedBy(m, from, to) != null
        if (testOnly && plan.test == null) return null
        return RemoteRules.write(text, plan, note, rule = !testOnly)
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
            listOf(RemoteRules.MEMBER) to listOf(RemoteRules.SELF),
            listOf("group:parents") to listOf(RemoteRules.SELF),
            listOf("alice@example.com") to listOf(RemoteRules.SELF, "tag:tv"),
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
            listOf(ApiDevice(id = "1", name = "tv", tags = listOf("tag:tv"), addresses = listOf("100.64.0.2"))),
            null,
        )
        val from = listOf("group:admins", "tag:admin")
        val to = listOf("tag:admin", "tag:tv")
        assertEquals(oldRemote(acls, from, to, false, view), newRemote(acls, from, to, false, view))
    }

    @Test
    fun remoteRefusesTagsWithTheirOwnDevices() {
        val p = plan(load("tailsocks-remote"), acls, mapOf("managers" to items("alice@example.com", "tag:admin"), "managed" to items(RemoteRules.SELF)))
        assertEquals(RemoteRules.selfWithoutOwner(listOf("alice@example.com", "tag:admin"), listOf(RemoteRules.SELF)), (p.issues.single() as TemplateIssue.SelfWithoutOwner).sources)
    }

    // ---- the Attributes page ----

    /** What the Taildrive template's form wrote with its preset left as it was. */
    private fun oldTaildrive(text: String): String {
        val preset = AttrTemplate.TAILDRIVE.preset
        val out = PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(preset.targets, preset.attrs))
        return if (preset.driveGrant) TaildriveRules.withGrant(out) else out
    }

    @Test
    fun taildrive() {
        for ((name, text) in policies) assertEquals(name, oldTaildrive(text), apply(load("taildrive-own-devices"), text))
        // The sample has a Taildrive grant: it stays as it is, and the page says so.
        val p = plan(load("taildrive-own-devices"), TemplateTestKit.sample)
        assertTrue(p.checks.single { it.check.id == "grant" }.holds)
        assertTrue(p.ops.none { it is TemplateOp.Add && it.section == Section.GRANTS })
    }

    @Test
    fun fasterStart() {
        val preset = AttrTemplate.FASTER_START.preset
        assertEquals(listOf(KnownAttrs.CACHE_NETMAPS), preset.attrs)
        for ((name, text) in policies) {
            for (hs in listOf(false, true)) {
                val old = PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(preset.targets, preset.attrs))
                assertEquals("$name hs=$hs", old, apply(load("faster-start"), text, headscale = hs))
            }
            val old = PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(listOf("autogroup:member", "tag:web"), preset.attrs))
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
}
