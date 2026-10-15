package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuArray
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuNode
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PortSet
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.templates.AccessCoverage.MEMBER
import io.github.bropines.tailscaled.admin.policy.templates.AccessCoverage.SELF
import io.github.bropines.tailscaled.admin.policy.visual.Fields
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyValue
import io.github.bropines.tailscaled.admin.policy.visual.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote-management template (policy-templates/tailsocks-remote.json): the rule and test it
 * writes in either syntax, and when the policy needs neither — AccessCoverage's access-open
 * check and pair-test generator, which were RemoteRules before the template became data.
 */
class RemoteTemplateTest {

    private val groups = """
	"groups": {
		"group:admins":  ["alice@example.com"],
		"group:family":  ["mom@example.com", "dad@example.com"],
		"group:parents": ["mom@example.com"],
	},
	"tagOwners": {
		"tag:tv":    ["alice@example.com"],
		"tag:admin": ["alice@example.com"],
	},"""

    /** A file of ACLs: members reach their own devices on SSH and HTTPS. */
    private val acls = """{$groups
	"acls": [
		{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:self:22,443"]},
	],
}
"""

    /** The same as grants. */
    private val grants = """{$groups
	"grants": [
		{"src": ["autogroup:member"], "dst": ["autogroup:self"], "ip": ["22", "443"]},
	],
}
"""

    private fun model(text: String) = PolicyModel.read(text)!!

    private val remote = TemplateTestKit.load("tailsocks-remote")

    private fun values(from: List<String>, to: List<String>) = mapOf("managers" to ParamValue.Items(from), "managed" to ParamValue.Items(to))

    /** What the template plans: [Planned.section] the access rule's syntax, [Planned.test] the test it adds, if any. */
    private class Planned(val plan: TemplatePlan) {
        val section: Section get() = plan.accessSection
        val test: Fields? get() = plan.ops.filterIsInstance<TemplateOp.Add>().firstOrNull { it.section == Section.TESTS }?.fields
    }

    private fun plan(text: String, from: List<String>, to: List<String>, headscale: Boolean = false, view: TailnetView? = null) =
        Planned(TemplateTestKit.plan(remote, text, values(from, to), headscale, view))

    private fun create(text: String, from: List<String>, to: List<String>, headscale: Boolean = false, view: TailnetView? = null): String =
        TemplateTestKit.apply(remote, text, values(from, to), headscale, view)

    private fun at(text: String, section: String, index: Int): HuNode = ((HuJson.parse(text) as HuObject)[section] as HuArray).items[index]

    private fun assertNode(expected: String, actual: HuNode) = assertEquals(HuJson.canonical(json(expected)), HuJson.canonical(actual))

    private fun count(text: String, section: String): Int = ((HuJson.parse(text) as HuObject)[section] as? HuArray)?.items?.size ?: 0

    // ---- what Create writes ----

    @Test
    fun usersToUsersInAFileOfAcls() {
        val after = create(acls, listOf("alice@example.com"), listOf("mom@example.com", "dad@example.com"))
        assertNode("""{"action": "accept", "src": ["alice@example.com"], "dst": ["mom@example.com:28115", "dad@example.com:28115"], "proto": "tcp"}""", at(after, "acls", 1))
        assertNode("""{"src": "alice@example.com", "proto": "tcp", "accept": ["mom@example.com:28115"]}""", at(after, "tests", 0))
        // The comment says what the rule is for, right over it; the first rule is untouched.
        assertTrue(after.contains("\t\t// TailSocks remote management (TCP 28115)\n\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"alice@example.com\"],"))
        assertNode("""{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:self:22,443"]}""", at(after, "acls", 0))
        assertTrue(HuJson.check(after).isEmpty())
    }

    @Test
    fun groupToGroup() {
        val after = create(acls, listOf("group:admins"), listOf("group:family"))
        assertNode("""{"action": "accept", "src": ["group:admins"], "dst": ["group:family:28115"], "proto": "tcp"}""", at(after, "acls", 1))
        assertNode("""{"src": "group:admins", "proto": "tcp", "accept": ["group:family:28115"]}""", at(after, "tests", 0))
    }

    @Test
    fun everyMemberManagesTheirOwnDevicesWithNoTest() {
        val acl = create(acls, listOf(MEMBER), listOf(SELF))
        assertNode("""{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:self:28115"], "proto": "tcp"}""", at(acl, "acls", 1))
        // A test can name neither every member nor their own devices.
        assertNull(plan(acls, listOf(MEMBER), listOf(SELF)).test)
        assertEquals(0, count(acl, "tests"))
        // A file of grants gets a grant.
        val grant = create(grants, listOf(MEMBER), listOf(SELF))
        assertNode("""{"src": ["autogroup:member"], "dst": ["autogroup:self"], "ip": ["tcp:28115"]}""", at(grant, "grants", 1))
        assertEquals(0, count(grant, "acls"))
    }

    @Test
    fun tagsToTagsAsAGrant() {
        val after = create(grants, listOf("tag:admin"), listOf("tag:tv"))
        assertNode("""{"src": ["tag:admin"], "dst": ["tag:tv"], "ip": ["tcp:28115"]}""", at(after, "grants", 1))
        assertNode("""{"src": "tag:admin", "proto": "tcp", "accept": ["tag:tv:28115"]}""", at(after, "tests", 0))
    }

    @Test
    fun aPersonAndTheirOwnDevicesAndATag() {
        // The test pins the first pair it can name: alice → the TV; "their own devices" has none.
        val after = create(acls, listOf("alice@example.com"), listOf(SELF, "tag:tv"))
        assertNode("""{"action": "accept", "src": ["alice@example.com"], "dst": ["autogroup:self:28115", "tag:tv:28115"], "proto": "tcp"}""", at(after, "acls", 1))
        assertNode("""{"src": "alice@example.com", "proto": "tcp", "accept": ["tag:tv:28115"]}""", at(after, "tests", 0))
    }

    @Test
    fun theSyntaxIsTheFilesOwnAndOnATieTheBackendsDefault() {
        assertEquals(Section.ACLS, plan(acls, listOf("tag:admin"), listOf("tag:tv")).section)
        assertEquals(Section.GRANTS, plan(grants, listOf("tag:admin"), listOf("tag:tv")).section)
        assertEquals(Section.GRANTS, plan("{}", listOf("tag:admin"), listOf("tag:tv")).section)
        assertEquals(Section.ACLS, plan("{}", listOf("tag:admin"), listOf("tag:tv"), headscale = true).section)
        // An empty file gets the section written.
        val written = create("{}\n", listOf("tag:admin"), listOf("tag:tv"), headscale = true)
        assertNode("""{"action": "accept", "src": ["tag:admin"], "dst": ["tag:tv:28115"], "proto": "tcp"}""", at(written, "acls", 0))
        assertEquals(1, count(written, "tests"))
    }

    @Test
    fun theTestSkipsASideWithNoDevicesAndOneTheFileHasAlready() {
        val devices = listOf(
            ApiDevice(nodeId = "1", name = "mom-phone.example.ts.net", user = "mom@example.com", addresses = listOf("100.64.0.1")),
            ApiDevice(nodeId = "2", name = "alice-pc.example.ts.net", user = "alice@example.com", addresses = listOf("100.64.0.2")),
            ApiDevice(nodeId = "3", name = "tv.example.ts.net", user = "alice@example.com", tags = listOf("tag:tv"), addresses = listOf("100.64.0.3")),
        )
        val view = TailnetView(devices, null)
        // Dad has no device: Headscale would refuse a test from him, so it is from alice.
        val from = listOf("dad@example.com", "alice@example.com")
        assertEquals("alice@example.com", (plan(acls, from, listOf("mom@example.com"), view = view).test!!.first { it.first == "src" }.second as PolicyValue.Str).value)
        // Without a device list nothing is known to be empty: the first pair.
        assertEquals("dad@example.com", (plan(acls, from, listOf("mom@example.com")).test!!.first { it.first == "src" }.second as PolicyValue.Str).value)
        // An empty tag gets no test at all.
        assertNull(plan(acls, listOf("tag:admin"), listOf("tag:tv"), view = view).test)
        // The same test twice is not written.
        val once = create(acls, listOf("alice@example.com"), listOf("mom@example.com"))
        assertNull(plan(once, listOf("alice@example.com"), listOf("mom@example.com")).test)
    }

    @Test
    fun onlyTheTestWhenTheRuleIsThereAlready() {
        val open = withAcl("""{"action": "accept", "src": ["alice@example.com"], "dst": ["mom@example.com:*"]}""")
        val p = plan(open, listOf("alice@example.com"), listOf("mom@example.com")).plan
        assertTrue(p.checks.single { it.check.id == "open" }.holds)
        val after = p.apply(open)
        assertEquals(2, count(after, "acls"))
        assertEquals(1, count(after, "tests"))
        assertFalse("// TailSocks remote management (TCP 28115)" in after.lines().map { it.trim() })
        // With the test there too there is nothing left to add.
        assertEquals(listOf(TemplateIssue.NothingToAdd), plan(after, listOf("alice@example.com"), listOf("mom@example.com")).plan.issues)
    }

    @Test
    fun theirOwnDevicesNeedPeopleNotTags() {
        assertEquals(listOf("tag:admin"), AccessCoverage.selfWithoutOwner(listOf("alice@example.com", "tag:admin"), listOf(SELF)))
        assertEquals(listOf("autogroup:tagged"), AccessCoverage.selfWithoutOwner(listOf("autogroup:tagged"), listOf(SELF, "tag:tv")))
        assertTrue(AccessCoverage.selfWithoutOwner(listOf("tag:admin"), listOf("tag:tv")).isEmpty())
        assertTrue(AccessCoverage.selfWithoutOwner(listOf(MEMBER, "group:admins"), listOf(SELF)).isEmpty())
        // The template refuses it before Apply.
        val p = plan(acls, listOf("alice@example.com", "tag:admin"), listOf(SELF)).plan
        assertEquals(listOf(TemplateIssue.SelfWithoutOwner(listOf("tag:admin"))), p.issues)
    }

    // ---- what the file opens already ----

    /** [text] with [rule] at the end of its last list. */
    private fun withAcl(rule: String, text: String = acls) = text.replace("\t],\n}", "\t\t$rule,\n\t],\n}")

    private fun withGrant(rule: String) = grants.replace("\t],\n}", "\t\t$rule,\n\t],\n}")

    private fun opened(text: String, from: List<String>, to: List<String>) = AccessCoverage.openedBy(model(text), from, to, PortSet.single(28115), "tcp")

    @Test
    fun everythingToEverythingOpensEveryPath() {
        val all = withAcl("""{"action": "accept", "src": ["*"], "dst": ["*:*"]}""")
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(all, listOf("group:admins", "alice@example.com"), listOf("tag:tv", SELF, "mom@example.com")))
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(all, listOf("tag:admin"), listOf("tag:tv", "group:family")))
        val grant = withGrant("""{"src": ["*"], "dst": ["*"], "ip": ["*"]}""")
        assertEquals(listOf(PolicyPath.of("grants", 1)), opened(grant, listOf("alice@example.com"), listOf("group:family")))
    }

    @Test
    fun aBroaderPortSetOpensItAndAnotherPortOrProtocolDoesNot() {
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(withAcl("""{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:28000-29000"]}"""), listOf("group:admins"), listOf("tag:tv")))
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(withAcl("""{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:22,28115"], "proto": "tcp"}"""), listOf("alice@example.com"), listOf("tag:tv")))
        assertNull(opened(withAcl("""{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:28116"]}"""), listOf("group:admins"), listOf("tag:tv")))
        assertNull(opened(withAcl("""{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:*"], "proto": "udp"}"""), listOf("group:admins"), listOf("tag:tv")))
        assertEquals(listOf(PolicyPath.of("grants", 1)), opened(withGrant("""{"src": ["group:admins"], "dst": ["tag:tv"], "ip": ["tcp:*"]}"""), listOf("group:admins"), listOf("tag:tv")))
        assertNull(opened(withGrant("""{"src": ["group:admins"], "dst": ["tag:tv"], "ip": ["udp:28115"]}"""), listOf("group:admins"), listOf("tag:tv")))
    }

    @Test
    fun aRuleThatHoldsOnlySometimesDoesNotCount() {
        assertNull(opened(withGrant("""{"src": ["group:admins"], "dst": ["tag:tv"], "ip": ["*"], "via": ["tag:router"]}"""), listOf("group:admins"), listOf("tag:tv")))
        assertNull(opened(withGrant("""{"src": ["group:admins"], "dst": ["tag:tv"], "ip": ["*"], "srcPosture": ["posture:latest"]}"""), listOf("group:admins"), listOf("tag:tv")))
    }

    @Test
    fun groupsAndAutogroupsHoldWhatTheyName() {
        val family = withAcl("""{"action": "accept", "src": ["group:family"], "dst": ["group:family:*"]}""")
        // Mom is in the family, and so is everyone of the parents; the admins are not.
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(family, listOf("mom@example.com"), listOf("dad@example.com")))
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(family, listOf("group:parents"), listOf("group:family")))
        assertNull(opened(family, listOf("group:admins"), listOf("group:family")))
        // The family's own devices are among the family's devices.
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(family, listOf("group:parents"), listOf(SELF)))
        // Every member reaches their own devices on every port: people managing their own, yes; a tag, or another's devices, no.
        val own = withAcl("""{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:self:*"]}""")
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(own, listOf(MEMBER), listOf(SELF)))
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(own, listOf("alice@example.com", "group:family"), listOf(SELF)))
        assertNull(opened(own, listOf("tag:admin"), listOf(SELF)))
        assertNull(opened(own, listOf("alice@example.com"), listOf("mom@example.com")))
        // The file's own first rule opens 22 and 443 only.
        assertNull(opened(acls, listOf(MEMBER), listOf(SELF)))
        // Tagged devices as a whole hold every tag.
        assertEquals(listOf(PolicyPath.of("acls", 1)), opened(withAcl("""{"action": "accept", "src": ["autogroup:tagged"], "dst": ["autogroup:tagged:28115"]}"""), listOf("tag:admin"), listOf("tag:tv")))
    }

    @Test
    fun everyPairMustBeOpenAndEachRuleIsNamedOnce() {
        val two = withAcl("""{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:28115"]}""")
            .let { withAcl("""{"action": "accept", "src": ["tag:admin"], "dst": ["tag:tv:*"]}""", it) }
        assertEquals(listOf(PolicyPath.of("acls", 1), PolicyPath.of("acls", 2)), opened(two, listOf("alice@example.com", "group:admins", "tag:admin"), listOf("tag:tv")))
        // One manager left out: not open.
        assertNull(opened(two, listOf("alice@example.com", "mom@example.com"), listOf("tag:tv")))
        assertNull(opened(two, emptyList(), listOf("tag:tv")))
    }
}
