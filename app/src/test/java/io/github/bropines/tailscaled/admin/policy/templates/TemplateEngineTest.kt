package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuArray
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuNode
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.EMPTY
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.acls
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.grants
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.items
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine on templates made for the test: rendering by the file's syntax, merging, leaving out, deciding. */
class TemplateEngineTest {

    private fun template(fragments: String, params: String = "[]", checks: String = "[]", extra: String = ""): PolicyTemplate = TemplateSource.parse(
        """{"schema": 1, "id": "t", "version": 1, "category": "access", "title": {"en": "T"}, "description": {"en": "D"},
           "params": $params, "checks": $checks, "fragments": $fragments $extra}""".encodeToByteArray(),
    )

    private val who = """[{"id": "who", "kind": "selector", "slot": "acl-src", "label": {"en": "Who"}, "required": true, "multi": true},
        {"id": "ports", "kind": "port", "label": {"en": "Ports"}, "multi": true, "default": ["22"]},
        {"id": "full", "kind": "bool", "label": {"en": "Full"}},
        {"id": "name", "kind": "text", "format": "name", "label": {"en": "Name"}},
        {"id": "addr", "kind": "host", "label": {"en": "Address"}}]"""

    private fun plan(t: PolicyTemplate, text: String, vararg v: Pair<String, ParamValue>, headscale: Boolean = false, view: TailnetView? = null) =
        TemplateEngine.plan(t, TemplateEngine.defaults(t) + v.toMap(), TemplateContext(text, headscale, view, "ru"))

    private fun at(text: String, section: String, index: Int): HuNode = ((HuJson.parse(text) as HuObject)[section] as HuArray).items[index]
    private fun count(text: String, section: String) = ((HuJson.parse(text) as HuObject)[section] as? HuArray)?.items?.size ?: 0
    private fun assertNode(expected: String, actual: HuNode) = assertEquals(HuJson.canonical(json(expected)), HuJson.canonical(actual))

    // ---- access rules in the file's own syntax ----

    private val access = template("""{"access": [{"src": ["{{who}}"], "dst": ["tag:web"], "ports": ["{{ports}}"], "proto": "tcp"}]}""", who)

    @Test
    fun anAccessRuleIsAnAclInAFileOfAcls() {
        val p = plan(access, acls, "who" to items("group:admins"), "ports" to items("80", "443"))
        assertEquals(Section.ACLS, p.accessSection)
        val out = p.apply(acls)
        assertNode("""{"action": "accept", "src": ["group:admins"], "dst": ["tag:web:80,443"], "proto": "tcp"}""", at(out, "acls", 1))
        assertEquals(PolicyPath.of("acls", 1), p.landing(acls))
    }

    @Test
    fun aGrantInAFileOfGrants() {
        val out = plan(access, grants, "who" to items("group:admins"), "ports" to items("80", "443")).apply(grants)
        assertNode("""{"src": ["group:admins"], "dst": ["tag:web"], "ip": ["tcp:80", "tcp:443"]}""", at(out, "grants", 1))
    }

    @Test
    fun anEmptyFileTakesGrantsOnTailscaleAndAclsOnHeadscale() {
        assertEquals(Section.GRANTS, plan(access, EMPTY, "who" to items("a@")).accessSection)
        assertEquals(Section.ACLS, plan(access, EMPTY, "who" to items("a@"), headscale = true).accessSection)
    }

    @Test
    fun everyPortIsOneStarAndViaMakesAGrant() {
        val t = template("""{"access": [{"src": ["*"], "dst": ["tag:web"], "ports": ["22", "*"], "via": ["tag:exit"]}]}""")
        val out = plan(t, acls).apply(acls)
        assertNode("""{"src": ["*"], "dst": ["tag:web"], "ip": ["*"], "via": ["tag:exit"]}""", at(out, "grants", 0))
        assertEquals(1, count(out, "acls"))
    }

    @Test
    fun anAccessRuleWithAnEmptySideIsLeftOut() {
        val t = template("""{"access": [{"src": ["{{who}}"], "dst": ["*"]}], "tests": [{"src": "x@", "accept": ["y@:22"]}]}""", who.replace("\"required\": true", "\"required\": false"))
        val p = plan(t, acls)
        assertEquals(listOf(Section.TESTS), p.ops.map { (it as TemplateOp.Add).section })
    }

    @Test
    fun theirOwnDevicesFromATagIsRefused() {
        val t = template("""{"access": [{"src": ["{{who}}"], "dst": ["autogroup:self"]}]}""", who)
        assertEquals(TemplateIssue.SelfWithoutOwner(listOf("tag:tv")), plan(t, acls, "who" to items("alice@example.com", "tag:tv")).issues.single())
        assertTrue(plan(t, acls, "who" to items("alice@example.com")).ready)
    }

    // ---- conditions, comments, leaving out ----

    @Test
    fun whenOnItemsAndBlocks() {
        val t = template(
            """[{"nodeAttrs": [{"target": ["{{who}}"], "attr": ["funnel"]}, {"when": "param:full", "target": ["{{who}}"], "attr": ["drive:share"]}]},
                {"when": "headscale", "tagOwners": {"tag:hs": ["{{who}}"]}},
                {"when": "style:acls && !param:full", "groups": {"group:acl-only": ["{{who}}"]}}]""",
            who,
        )
        val off = plan(t, acls, "who" to items("a@b"))
        assertEquals(listOf("nodeAttrs", "groups"), off.ops.map { if (it is TemplateOp.Add) it.section.key else (it as TemplateOp.Extend).section.key })
        val on = plan(t, acls, "who" to items("a@b"), "full" to ParamValue.Flag(true), headscale = true)
        assertEquals(listOf("nodeAttrs", "nodeAttrs", "tagOwners"), on.ops.map { if (it is TemplateOp.Add) it.section.key else (it as TemplateOp.Extend).section.key })
    }

    @Test
    fun theCommentIsInTheEditorsLanguage() {
        val t = template("""{"grants": [{"comment": {"en": "For {{who|join}}", "ru": "Для {{who|join}}"}, "src": ["{{who}}"], "dst": ["*"], "ip": ["*"]}]}""", who)
        val out = plan(t, grants, "who" to items("a@b", "c@d")).apply(grants)
        assertTrue(out, out.contains("// Для a@b,c@d\n"))
        // The template's own keys never reach the policy.
        assertNode("""{"src": ["a@b", "c@d"], "dst": ["*"], "ip": ["*"]}""", at(out, "grants", 1))
    }

    @Test
    fun whatThePolicyHasIsNotWrittenTwice() {
        val t = template("""{"grants": [{"src": ["autogroup:member"], "dst": ["autogroup:self"], "ip": ["22", "443"]}, {"src": ["*"], "dst": ["*"], "ip": ["*"]}, {"src": ["*"], "dst": ["*"], "ip": ["*"]}]}""")
        val p = plan(t, grants)
        assertEquals(2, p.kept)
        assertEquals(1, p.ops.size)
        val again = plan(t, p.apply(grants))
        assertEquals(TemplateIssue.NothingToAdd, again.issues.single())
    }

    // ---- objects are merged ----

    @Test
    fun namedListsAreMergedNotReplaced() {
        val t = template("""{"groups": {"group:family": ["{{who}}"], "group:{{name}}": ["{{who}}"]}, "tagOwners": {"tag:tv": ["alice@example.com"]}}""", who)
        val p = plan(t, acls, "who" to items("mom@example.com", "kid@example.com"), "name" to items("kids"))
        // group:family gains the one it lacks; tag:tv has its owner already.
        assertEquals(1, p.kept)
        val out = p.apply(acls)
        assertNode("""["mom@example.com", "dad@example.com", "kid@example.com"]""", ((HuJson.parse(out) as HuObject)["groups"] as HuObject)["group:family"]!!)
        assertNode("""["mom@example.com", "kid@example.com"]""", ((HuJson.parse(out) as HuObject)["groups"] as HuObject)["group:kids"]!!)
        // A key whose placeholder is empty is not written.
        assertEquals(1, plan(t, acls, "who" to items("x@y")).ops.size)
    }

    @Test
    fun hostsAndTheirConflicts() {
        val t = template("""{"hosts": {"nas": "{{addr}}"}}""", who.replace("\"required\": true", "\"required\": false"))
        val out = plan(t, EMPTY, "addr" to items("10.0.0.2")).apply(EMPTY)
        assertNode("\"10.0.0.2\"", ((HuJson.parse(out) as HuObject)["hosts"] as HuObject)["nas"]!!)
        assertEquals(TemplateIssue.NothingToAdd, plan(t, out, "addr" to items("10.0.0.2")).issues.single())
        assertEquals(TemplateIssue.Conflict("hosts.nas", "10.0.0.2", "10.0.0.3"), plan(t, out, "addr" to items("10.0.0.3")).issues.single())
        assertTrue(plan(t, out, "addr" to items("10.0.0.300")).issues.first() is TemplateIssue.BadValue)
    }

    @Test
    fun approversAreMerged() {
        val t = template("""{"autoApprovers": {"routes": {"10.0.0.0/24": ["{{who}}"]}, "exitNode": ["{{who}}"]}}""", who)
        val once = plan(t, acls, "who" to items("tag:tv")).apply(acls)
        val twice = plan(t, once, "who" to items("group:admins", "tag:tv")).apply(once)
        val a = (HuJson.parse(twice) as HuObject)["autoApprovers"] as HuObject
        assertNode("""["tag:tv", "group:admins"]""", a["exitNode"]!!)
        assertNode("""{"10.0.0.0/24": ["tag:tv", "group:admins"]}""", a["routes"]!!)
    }

    // ---- values ----

    @Test
    fun requiredAndBadValuesStopApply() {
        val p = plan(access, acls)
        assertEquals("who", (p.issues.single { it is TemplateIssue.Missing } as TemplateIssue.Missing).param.id)
        assertFalse(p.ready)
        val bad = plan(access, acls, "who" to items("a@b"), "ports" to items("99999"))
        assertEquals(TemplateIssue.BadValue(access.param("ports")!!, "99999"), bad.issues.single())
    }

    @Test
    fun formats() {
        fun p(kind: ParamKind, format: String? = null) = TemplateParam("x", kind, mapOf("en" to "x"), format = format)
        assertTrue(TemplateEngine.valueOk(p(ParamKind.PORT), "8000-8100"))
        assertFalse(TemplateEngine.valueOk(p(ParamKind.PORT), "http"))
        assertTrue(TemplateEngine.valueOk(p(ParamKind.CIDR), "192.168.1.0/24"))
        assertFalse(TemplateEngine.valueOk(p(ParamKind.CIDR), "192.168.1.0"))
        assertFalse(TemplateEngine.valueOk(p(ParamKind.HOST), "10.0.0.0/8"))
        assertTrue(TemplateEngine.valueOk(p(ParamKind.TEXT, "tag-name"), "web-1"))
        assertFalse(TemplateEngine.valueOk(p(ParamKind.TEXT, "tag-name"), "1web"))
        assertTrue(TemplateEngine.valueOk(p(ParamKind.TEXT, "duration"), "12h"))
        assertFalse(TemplateEngine.valueOk(p(ParamKind.TEXT), "a\nb"))
        assertFalse(TemplateEngine.valueOk(p(ParamKind.TEXT), "x".repeat(201)))
    }

    // ---- checks ----

    @Test
    fun accessOpenFindsTheRules() {
        val t = template(
            """{"access": [{"when": "!check:open", "src": ["{{who}}"], "dst": ["tag:tv"], "ports": ["22"]}]}""", who,
            checks = """[{"id": "open", "kind": "access-open", "src": ["{{who}}"], "dst": ["tag:tv"], "ports": ["{{ports}}"], "proto": "tcp"}]""",
        )
        val open = """{"acls": [{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:*"]}]}"""
        val p = plan(t, open, "who" to items("group:admins"))
        assertEquals(listOf(PolicyPath.of("acls", 0)), p.checks.single().paths)
        assertEquals(TemplateIssue.NothingToAdd, p.issues.single())
        // Another port that rule opens too; a source it does not cover is not open.
        assertTrue(plan(t, open, "who" to items("group:admins"), "ports" to items("8080")).checks.single().holds)
        assertFalse(plan(t, open, "who" to items("alice@example.com")).checks.single().holds)
        // UDP is not TCP; a rule of no protocol opens both.
        val udp = """{"acls": [{"action": "accept", "src": ["group:admins"], "dst": ["tag:tv:*"], "proto": "udp"}]}"""
        assertFalse(plan(t, udp, "who" to items("group:admins")).checks.single().holds)
    }

    @Test
    fun relayDevices() {
        val t = template(
            """{"grants": [{"src": ["{{who}}"], "dst": ["tag:relay"], "app": {"tailscale.com/cap/relay": []}}]}""", who,
            checks = """[{"id": "d", "kind": "relay-devices", "relays": ["tag:relay"], "clients": ["{{who}}"]}]""",
        )
        assertEquals(listOf("autogroup:member"), plan(t, grants, "who" to items("autogroup:member")).checks.single().broad)
        val view = TailnetView(listOf(ApiDevice(nodeId = "1", name = "phone", os = "android", tags = listOf("tag:relay"), addresses = listOf("100.64.0.9"))), null)
        val c = plan(t, grants, "who" to items("tag:tv"), view = view).checks.single()
        assertTrue(c.holds)
        assertEquals("phone", c.relayNotes.single().device.name)
        assertFalse(plan(t, grants, "who" to items("tag:tv")).checks.single().holds)
    }

    @Test
    fun suggestions() {
        val t = template(
            """{"nodeAttrs": [{"target": ["autogroup:member"], "attr": ["cache-network-maps"]}]}""",
            checks = """[{"id": "cache", "kind": "node-attr", "attrs": ["cache-network-maps"]}]""",
            extra = """, "suggest": [{"page": "attributes", "slot": "after", "when": "!check:cache"}], "headscale": {"supported": "partial"}""",
        )
        val ctx = { text: String, hs: Boolean -> TemplateContext(text, hs) }
        assertTrue(TemplateEngine.suggested(t, "attributes", "after", ctx(EMPTY, false)))
        assertTrue(TemplateEngine.suggested(t, "attributes", "after", ctx(EMPTY, true)))
        assertFalse(TemplateEngine.suggested(t, "attributes", "before", ctx(EMPTY, false)))
        assertFalse(TemplateEngine.suggested(t, "access", "after", ctx(EMPTY, false)))
        val on = plan(t, EMPTY).apply(EMPTY)
        assertFalse(TemplateEngine.suggested(t, "attributes", "after", ctx(on, false)))
    }

    @Test
    fun anUnreadablePolicy() {
        val p = plan(access, "{ nope", "who" to items("a@b"))
        assertEquals(TemplateIssue.Unreadable, p.issues.single())
        assertNull(p.landing("{ nope"))
    }
}
