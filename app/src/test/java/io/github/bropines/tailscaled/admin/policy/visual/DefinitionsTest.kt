package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.RiskKind
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.removeAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.setAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The definitions pages' decisions: names, addresses, uses, unowned tags, IP set rows, attributes, and their edits. */
class DefinitionsTest {

    private val model = PolicyModel.read(sample)!!
    private val tree = SourceTree.parse(sample)

    private fun device(name: String, user: String, vararg tags: String, ip: String = "100.64.0.9") =
        ApiDevice(id = name, nodeId = name, name = "$name.tail1234.ts.net", user = user, tags = tags.toList(), addresses = listOf(ip, "fd7a:115c:a1e0::9"))

    @Test
    fun namesAreCheckedTheWayTheServerChecksThem() {
        fun c(kind: DefKind, bare: String, current: String? = null) = Definitions.checkName(kind, bare, model, current)
        assertNull(c(DefKind.TAG, "web-1"))
        assertEquals(NameProblem.BAD_START, c(DefKind.TAG, "1web"))
        assertEquals(NameProblem.BAD_CHARS, c(DefKind.TAG, "web_1"))
        assertEquals(NameProblem.TAKEN, c(DefKind.TAG, "master"))
        // Its own name is no conflict: a rename that changes nothing.
        assertNull(c(DefKind.TAG, "master", current = "tag:master"))
        assertEquals(NameProblem.EMPTY, c(DefKind.GROUP, "  "))
        assertEquals(NameProblem.TAKEN, c(DefKind.GROUP, "prod-admin"))
        assertNull(c(DefKind.GROUP, "ops.team_2"))
        assertEquals(NameProblem.BAD_CHARS, c(DefKind.GROUP, "ops team"))
        // A host is a bare word that is no address and no user.
        assertNull(c(DefKind.HOST, "nas"))
        assertEquals(NameProblem.BAD_CHARS, c(DefKind.HOST, "10.0.0.1"))
        assertEquals(NameProblem.BAD_CHARS, c(DefKind.HOST, "a@b"))
        assertEquals(NameProblem.BAD_CHARS, c(DefKind.HOST, "a:b"))
        assertNull(c(DefKind.SERVICE, "jellyfin"))
        assertEquals(NameProblem.BAD_CHARS, c(DefKind.SERVICE, "Jellyfin"))
        assertNull(c(DefKind.POSTURE, "latest"))
        assertNull(c(DefKind.IPSET, "lab-net"))
    }

    @Test
    fun addressesAndRoutes() {
        for (ok in listOf("100.64.0.1", "192.168.1.0/24", "0.0.0.0/0", "fd7a:115c:a1e0::1", "::1", "::/0", "fe80::", "2001:db8::/32", "::ffff:10.0.0.1", "1:2:3:4:5:6:7:8")) {
            assertNull(ok, Definitions.checkAddress(ok))
        }
        assertEquals(AddressProblem.EMPTY, Definitions.checkAddress(""))
        for (bad in listOf("256.1.1.1", "10.0.0", "nas", "1:2:3:4:5:6:7:8:9", "1::2::3", "12345::1", "::ffff:10.0.0.300")) {
            assertEquals(bad, AddressProblem.NOT_AN_ADDRESS, Definitions.checkAddress(bad))
        }
        assertEquals(AddressProblem.BAD_PREFIX_LENGTH, Definitions.checkAddress("10.0.0.0/33"))
        assertEquals(AddressProblem.BAD_PREFIX_LENGTH, Definitions.checkAddress("fd00::/129"))
        assertEquals(AddressProblem.BAD_PREFIX_LENGTH, Definitions.checkAddress("10.0.0.0/x"))
        assertEquals(AddressProblem.NEEDS_PREFIX, Definitions.checkRoute("10.0.0.0"))
        assertNull(Definitions.checkRoute("10.0.0.0/8"))
    }

    @Test
    fun ipSetRowsReadAndWriteAsTheyWereWritten() {
        assertEquals(IpSetOp(false, "10.0.0.0/8", explicit = false), IpSetOp.parse("10.0.0.0/8"))
        assertEquals(IpSetOp(false, "host:nas", explicit = true), IpSetOp.parse("add host:nas"))
        assertEquals(IpSetOp(true, "10.1.0.0/16", explicit = true), IpSetOp.parse("remove  10.1.0.0/16"))
        for (s in listOf("10.0.0.0/8", "add host:nas", "remove 10.1.0.0/16")) assertEquals(s, IpSetOp.parse(s).text)
        // Taking out, then adding back: written with its verb from then on.
        assertEquals("add 10.0.0.1", IpSetOp.parse("remove 10.0.0.1").copy(remove = false).text)
        // A new row follows the set: bare unless the set already writes "add".
        assertEquals("10.2.0.0/16", Definitions.newIpSetOp(listOf(IpSetOp.parse("10.0.0.0/8")), "10.2.0.0/16").text)
        assertEquals("add 10.2.0.0/16", Definitions.newIpSetOp(listOf(IpSetOp.parse("add 10.0.0.0/8")), " 10.2.0.0/16 ").text)
        assertEquals("remove 10.2.0.1", Definitions.newIpSetOp(emptyList(), "10.2.0.1", remove = true).text)

        val m = PolicyModel.read("""{"hosts": {"nas": "100.64.0.20"}, "ipsets": {"ipset:a": ["10.0.0.0/8"], "ipset:b": ["ipset:a"]}}""")!!
        assertNull(Definitions.checkIpSetTarget("host:nas", m))
        assertEquals(SelectorProblem.UNDEFINED, Definitions.checkIpSetTarget("host:printer", m))
        assertNull(Definitions.checkIpSetTarget("ipset:a", m, self = "ipset:b"))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, Definitions.checkIpSetTarget("ipset:b", m, self = "ipset:b"))
        assertEquals(SelectorProblem.UNDEFINED, Definitions.checkIpSetTarget("ipset:c", m))
        assertNull(Definitions.checkIpSetTarget("10.0.0.1-10.0.0.9", m))
        assertNull(Definitions.checkIpSetTarget("fd00::/64", m))
        assertEquals(SelectorProblem.UNKNOWN_FORM, Definitions.checkIpSetTarget("10.0.0.1-nas", m))
        assertEquals(SelectorProblem.UNKNOWN_FORM, Definitions.checkIpSetTarget("tag:x", m))
        assertEquals(SelectorProblem.EMPTY, Definitions.checkIpSetTarget(" ", m))
    }

    @Test
    fun placesAreTheElementsThatUseANameInFileOrder() {
        val places = Definitions.places(tree, "group:prod-admin")
        // Owners of four tags, the admin rule, the DNS rule, the drive grant, the first SSH rule, the funnel attribute.
        assertEquals(
            listOf(
                PolicyPath.of("tagOwners", "tag:master"), PolicyPath.of("tagOwners", "tag:server"),
                PolicyPath.of("tagOwners", "tag:exit-node"), PolicyPath.of("tagOwners", "tag:dns"),
                PolicyPath.of("acls", 0), PolicyPath.of("acls", 7),
                PolicyPath.of("ssh", 0), PolicyPath.of("nodeAttrs", 1), PolicyPath.of("grants", 0),
            ),
            places,
        )
        assertFalse(PolicyPath.of("groups", "group:prod-admin") in places)
        val lines = places.map { Definitions.lineOf(tree, it)!! }
        assertEquals(lines.sorted(), lines)
        assertTrue(Definitions.places(tree, "group:nobody").isEmpty())
        // A tag used in a test's destination counts too: deleting it would break the test.
        assertTrue(PolicyPath.of("tests", 0) in Definitions.places(tree, "tag:homelab"))
    }

    @Test
    fun anElementTheModelHasNoOriginForIsReadFromTheTree() {
        val exit = Definitions.originAt(tree, PolicyPath.of("autoApprovers", "exitNode"))!!
        assertEquals(PolicyTestKit.lineOf(sample, "\"exitNode\""), exit.line)
        assertTrue(exit.editable)
        val single = SourceTree.parse("""{"autoApprovers": {"exitNode": "tag:exit"}}""")
        assertEquals(setOf(ShapeIssue.NOT_A_LIST), Definitions.originAt(single, PolicyPath.of("autoApprovers", "exitNode"))!!.issues)
        assertNull(Definitions.originAt(tree, PolicyPath.of("autoApprovers", "routes")))
        assertEquals(model.groups[1].origin, Definitions.originOf(model, PolicyPath.of("groups", "group:dev-admin")))
        assertTrue(Definitions.canComment(tree, PolicyPath.of("tagOwners", "tag:lab")))
        assertFalse(Definitions.canComment(SourceTree.parse("""{"hosts": {"a": "10.0.0.1", "b": "10.0.0.2"}}"""), PolicyPath.of("hosts", "b")))
    }

    @Test
    fun tagsInUseWithoutAnOwnerAreOffered() {
        val devices = listOf(device("pi", "bob@example.com", "tag:printer"), device("nas", "bob@example.com", "tag:homelab"))
        assertEquals(listOf(UnownedTag("tag:printer", 1, 0)), Definitions.unownedTags(model, devices, tree))
        val text = PolicyEdits.addRule(sample, Section.ACLS, PolicyEdits.aclFields(listOf("tag:new"), listOf("tag:homelab:22")))
        val m = PolicyModel.read(text)!!
        assertEquals(
            listOf(UnownedTag("tag:new", 0, 1), UnownedTag("tag:printer", 1, 0)),
            Definitions.unownedTags(m, devices, SourceTree.parse(text)),
        )
        assertEquals(listOf("autogroup:admin"), Definitions.defaultOwners(headscale = false))
        assertTrue(Definitions.defaultOwners(headscale = true).isEmpty())
    }

    @Test
    fun devicesOfGroupsTagsAndHosts() {
        val alice = device("phone", "alice@example.com", ip = "100.64.0.1")
        val server = device("server", "alice@example.com", "tag:server", ip = "100.64.0.11")
        val nas = device("nas", "bob@example.com", "tag:homelab", ip = "192.168.10.20")
        val all = listOf(alice, server, nas)
        // A tagged device is its tag's, not its user's.
        assertEquals(listOf(alice), Definitions.groupDevices(listOf("ALICE@example.com"), all))
        assertEquals(listOf(server), Definitions.tagDevices("tag:server", all))
        assertEquals(listOf(server), Definitions.hostDevices("100.64.0.11", all))
        assertEquals(listOf(nas), Definitions.hostDevices("192.168.10.0/24", all))
        assertEquals(listOf(alice, server, nas), Definitions.hostDevices("fd7a:115c:a1e0::9/128", all))
        assertTrue(Definitions.hostDevices("10.9.9.9", all).isEmpty())
    }

    @Test
    fun theDefaultPostureIsAddedChangedAndRemoved() {
        val added = Definitions.setDefaultPosture(sample, listOf("posture:latest"))
        assertMeaning(setAt(parsed(sample), listOf("defaultSrcPosture"), json("[\"posture:latest\"]")), added)
        val changed = Definitions.setDefaultPosture(added, listOf("posture:latest", "posture:desktop"))
        assertMeaning(setAt(parsed(sample), listOf("defaultSrcPosture"), json("[\"posture:latest\", \"posture:desktop\"]")), changed)
        assertEquals(sample, Definitions.setDefaultPosture(added, emptyList()))
        assertEquals(sample, Definitions.setDefaultPosture(sample, emptyList()))
    }

    @Test
    fun postureAssertionsInEitherForm() {
        val text = """
            {
            	"postures": {
            		"posture:a": ["node:os == 'macos'"],
            		// Desktops only.
            		"posture:b": {
            			"assertions": ["node:os == 'linux'"],
            			"onFailure":  {"message": "update"},
            		},
            	},
            }
        """.trimIndent() + "\n"
        val m = PolicyModel.read(text)!!
        val a = Definitions.setAssertions(text, m.postures[0], listOf("node:os == 'macos'", "node:tsVersion >= '1.80'"))
        assertMeaning(setAt(parsed(text), listOf("postures", "posture:a"), json("[\"node:os == 'macos'\", \"node:tsVersion >= '1.80'\"]")), a)
        val b = Definitions.setAssertions(text, m.postures[1], emptyList())
        assertMeaning(setAt(parsed(text), listOf("postures", "posture:b", "assertions"), json("[]")), b)
        // The note and onFailure stay as written.
        assertTrue(b.contains("\t\t// Desktops only.\n"))
        assertTrue(b.contains("\"onFailure\":  {\"message\": \"update\"}"))
    }

    @Test
    fun routesAreRenamedInPlaceAndKeepAnEmptyList() {
        val text = PolicyEdits.setRouteApprovers(sample, "192.168.1.0/24", listOf("tag:homelab"))
        val renamed = Definitions.renameRoute(text, "192.168.1.0/24", " 192.168.2.0/24 ")
        assertTrue(renamed.contains("\"192.168.2.0/24\": [\"tag:homelab\"]"))
        assertEquals(text.length, renamed.length)
        val path = PolicyPath.of("autoApprovers", "routes", "192.168.1.0/24")
        val emptied = Definitions.setList(text, path, emptyList())
        assertMeaning(setAt(parsed(text), listOf("autoApprovers", "routes", "192.168.1.0/24"), json("[]")), emptied)
        try {
            Definitions.renameRoute(PolicyEdits.setRouteApprovers(text, "10.0.0.0/8", listOf("tag:x")), "10.0.0.0/8", "192.168.1.0/24")
            throw AssertionError("a taken route must be refused")
        } catch (_: PolicyEditException) {
        }
    }

    @Test
    fun aNodeAttributeRuleIsDuplicatedRightAfterItself() {
        val rule = model.nodeAttrs[0]
        val after = Definitions.duplicateNodeAttr(sample, rule)
        val m = PolicyModel.read(after)!!
        assertEquals(3, m.nodeAttrs.size)
        assertEquals(rule.target, m.nodeAttrs[1].target)
        assertEquals(rule.attr, m.nodeAttrs[1].attr)
        // The original's note travels with neither: the copy is a plain rule.
        assertNull(m.nodeAttrs[1].origin.note)
        assertEquals(model.nodeAttrs[1].origin.note, m.nodeAttrs[2].origin.note)
        assertEquals(1, Definitions.indexOf(m.nodeAttrs[1].origin.path))
    }

    @Test
    fun theLintsFindingsLandOnTheirElements() {
        val text = PolicyEdits.setRouteApprovers(sample, "10.0.0.0/8", listOf("autogroup:member"))
        val found = Definitions.findings(text)[PolicyPath.of("autoApprovers", "routes", "10.0.0.0/8")].orEmpty().map { it.kind }.toSet()
        assertEquals(setOf(RiskKind.AUTOAPPROVER_WIDE_ROUTE, RiskKind.AUTOAPPROVER_BROAD), found)
        // The sample's own funnel rules target groups and tags, not everyone: nothing there.
        assertTrue(Definitions.findings(sample)[PolicyPath.of("nodeAttrs", 1)].isNullOrEmpty())
        assertTrue(Definitions.findings("{").isEmpty())
    }

    @Test
    fun knownAttributes() {
        assertEquals("funnel", KnownAttrs.of("funnel")?.key)
        assertEquals("nextdns:", KnownAttrs.of("nextdns:abc123")?.key)
        assertEquals("nextdns:no-device-info", KnownAttrs.of("nextdns:no-device-info")?.key)
        assertNull(KnownAttrs.of("nextdns:"))
        assertNull(KnownAttrs.of("cap:web-client"))
        assertEquals("abc123", KnownAttrs.valueOf("nextdns:abc123"))
        assertNull(KnownAttrs.valueOf("funnel"))
        assertTrue(KnownAttrs.refused("funnel", headscale = true))
        assertFalse(KnownAttrs.refused("funnel", headscale = false))
        assertFalse(KnownAttrs.refused("drive:share", headscale = true))

        val funnel = KnownAttrs.of("funnel")!!
        val nextdns = KnownAttrs.ALL.first { it.key == "nextdns:" }
        val attrs = listOf("cap:web-client", "funnel")
        assertEquals(listOf("cap:web-client"), KnownAttrs.toggle(attrs, funnel, false))
        assertEquals(attrs, KnownAttrs.toggle(attrs, funnel, true))
        assertEquals(attrs + "nextdns:abc", KnownAttrs.toggle(attrs, nextdns, true, " abc "))
        // A profile switched on without an ID writes nothing; another ID replaces it where it stands.
        assertEquals(attrs, KnownAttrs.toggle(attrs, nextdns, true, ""))
        assertEquals(listOf("nextdns:def", "funnel"), KnownAttrs.toggle(listOf("nextdns:abc", "funnel"), nextdns, true, "def"))
        assertEquals(listOf("funnel"), KnownAttrs.toggle(listOf("nextdns:abc", "funnel"), nextdns, false))
        // The no-device-info flag is not mistaken for a profile.
        assertNull(KnownAttrs.find(nextdns, listOf("nextdns:no-device-info")))
    }

    @Test
    fun aFieldWritesOnlyOverTheTextItWasShown() {
        var draft = PolicyDraft(sample.replace("\n", "\r\n"))
        val actions = object : VisualActions {
            override fun edit(op: (String) -> String): Boolean {
                val e = draft.edit(op)
                draft = e.draft
                return e is DraftEdit.Done
            }
            override fun openJson(line: Int) {}
            override fun show(path: PolicyPath) {}
        }
        // Shown a CRLF file, the field still writes: the engine sees it with "\n".
        actions.editAt(draft.text) { PolicyEdits.putHost(it, "nas", "100.64.0.9") }
        assertTrue(draft.text.contains("\"nas\": \"100.64.0.9\""))
        val shown = draft.text
        // The host is deleted while its address field still holds a change; the editor closes and
        // the field writes — over a text it was not shown, so nothing comes back.
        actions.edit { PolicyEdits.removeNamed(it, Section.HOSTS, "nas") }
        val afterDelete = draft.text
        actions.editAt(shown) { PolicyEdits.putHost(it, "nas", "100.64.0.10") }
        assertEquals(afterDelete, draft.text)
        assertFalse(draft.text.contains("\"nas\""))
    }

    @Test
    fun editsOnTheDefinitionsPagesTouchOnlyTheirLines() {
        // A member added, a rename, a delete: each a window of the file, everything else byte for byte.
        val added = PolicyEdits.putNamed(sample, Section.GROUPS, "group:guest-users", listOf("guest@example.com", "carol@example.com"))
        val w = window(sample, added)
        assertEquals("", w.removed)
        assertTrue(w.inserted, w.inserted.contains("\"carol@example.com\","))
        val removed = PolicyEdits.removeNamed(sample, Section.TAG_OWNERS, "tag:guest")
        assertMeaning(removeAt(parsed(sample), listOf("tagOwners", "tag:guest")), removed)
        assertTrue(HuJson.check(PolicyEdits.rename(sample, "tag:guest", "tag:visitor")).isEmpty())
    }
}
