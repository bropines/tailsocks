package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.RiskKind
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.insertAt
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
import org.junit.Assert.fail
import org.junit.Test

/** Peer relays in the policy: the grants that carry the relay capability, written and read back byte for byte. */
class PeerRelaysTest {

    private fun p(vararg s: Any) = PolicyPath.of(*s)
    private val cap = PeerRelays.CAP
    private val relayJson = """{"src": ["tag:homelab"], "dst": ["tag:exit-node"], "app": {"$cap": []}}"""

    /** The author's file with one peer relay rule after its Taildrive grant. */
    private val withRelay = PeerRelays.add(sample, listOf("tag:homelab"), listOf("tag:exit-node"))

    /** The author's Taildrive grant, granting relays too: an access rule first. */
    private val driveAndRelay = SourceEdits.put(sample, p("grants", 0, "app"), cap, PolicyValue.Arr(emptyList()), emptyList())

    private fun grants(text: String) = PolicyModel.read(text)!!.grants

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("the edit should have been refused")
        } catch (_: PolicyEditException) {
        }
    }

    // ---- writing ----

    @Test
    fun aNewRuleIsOneGrantWrittenAsTheFileWritesItsOwn() {
        val w = window(sample, withRelay)
        assertEquals("only an insertion", "", w.removed)
        assertTrue(
            withRelay,
            withRelay.contains(
                "\t\t},\n\t\t{\n\t\t\t\"src\": [\"tag:homelab\"],\n\t\t\t\"dst\": [\"tag:exit-node\"],\n" +
                    "\t\t\t\"app\": {\n\t\t\t\t\"$cap\": [],\n\t\t\t},\n\t\t},\n\t],"
            ),
        )
        assertMeaning(insertAt(parsed(sample), listOf("grants"), 1, json(relayJson)), withRelay)
        val g = grants(withRelay)[1]
        assertTrue(PeerRelays.isRelay(g))
        assertTrue(PeerRelays.relayOnly(g))
        assertEquals(listOf(g), PeerRelays.rules(PolicyModel.read(withRelay)!!))
        // The author's own grant is Taildrive, not relays.
        assertTrue(PeerRelays.rules(PolicyModel.read(sample)!!).isEmpty())
    }

    @Test
    fun aFileWithoutGrantsGetsTheSection() {
        val bare = SourceEdits.remove(sample, p("grants"))
        val out = PeerRelays.add(bare, listOf("tag:lab"), listOf("tag:lab"))
        assertEquals("", window(bare, out).removed)
        assertMeaning(setAt(parsed(bare), listOf("grants"), json("""[{"src": ["tag:lab"], "dst": ["tag:lab"], "app": {"$cap": []}}]""")), out)
    }

    @Test
    fun deletingTheRuleGivesBackTheFileByteForByte() {
        assertEquals(sample, PeerRelays.remove(withRelay, p("grants", 1)))
    }

    @Test
    fun eachSideIsEditedItemByItem() {
        val path = p("grants", 1)
        val more = PeerRelays.setClients(withRelay, path, listOf("tag:homelab", "tag:lab"))
        assertEquals("", window(withRelay, more).removed)
        assertEquals(", \"tag:lab\"", window(withRelay, more).inserted)
        assertMeaning(setAt(parsed(withRelay), listOf("grants", 1, "src"), json("""["tag:homelab", "tag:lab"]""")), more)

        val relays = PeerRelays.setRelays(more, path, listOf("tag:dns"))
        assertMeaning(setAt(parsed(more), listOf("grants", 1, "dst"), json("""["tag:dns"]""")), relays)
        // Back again: the same bytes.
        assertEquals(withRelay, PeerRelays.setRelays(PeerRelays.setClients(relays, path, listOf("tag:homelab")), path, listOf("tag:exit-node")))
    }

    @Test
    fun anAccessRuleLosesOnlyItsRelays() {
        // The capability beside Taildrive's: only its key goes, and the file is what it was.
        val g = grants(driveAndRelay)[0]
        assertTrue(PeerRelays.isRelay(g))
        assertFalse(PeerRelays.relayOnly(g))
        assertEquals(sample, PeerRelays.remove(driveAndRelay, p("grants", 0)))

        // With ports and nothing else in app: app goes, the ports stay.
        val ported = PolicyEdits.addRule(sample, Section.GRANTS, PolicyEdits.grantFields(listOf("tag:lab"), listOf("tag:dns"), listOf("tcp:53")) +
            ("app" to PolicyValue.Obj(listOf(cap to PolicyValue.Arr(emptyList())))))
        val stripped = PeerRelays.remove(ported, p("grants", 1))
        assertMeaning(removeAt(parsed(ported), listOf("grants", 1, "app")), stripped)
        assertEquals(PolicyEdits.addRule(sample, Section.GRANTS, PolicyEdits.grantFields(listOf("tag:lab"), listOf("tag:dns"), listOf("tcp:53"))), stripped)
    }

    @Test
    fun anAccessRuleSplitsItsRelaysOff() {
        val split = PeerRelays.split(driveAndRelay, p("grants", 0))
        val expected = insertAt(parsed(sample), listOf("grants"), 1, json(
            """{"src": ["tag:homelab", "tag:android", "tag:taildrop", "group:prod-admin", "group:dev-admin"],
                "dst": ["tag:homelab", "tag:android", "tag:taildrop"], "app": {"$cap": []}}"""
        ))
        assertMeaning(expected, split)
        // The access rule is the author's own again, byte for byte, up to the new rule after it.
        val g = grants(split)
        assertFalse(PeerRelays.isRelay(g[0]))
        assertTrue(PeerRelays.relayOnly(g[1]))
        assertEquals(sample, PeerRelays.remove(split, p("grants", 1)))
        // A rule only about relays has nothing to split off.
        refused { PeerRelays.split(withRelay, p("grants", 1)) }
    }

    @Test
    fun aRelayRuleWithAViaIsNotTheRelayPagesWhole() {
        val via = PolicyEdits.updateRule(withRelay, p("grants", 1), listOf("via" to listOf("tag:exit-node").pv()))
        val g = grants(via)[1]
        assertTrue(PeerRelays.isRelay(g))
        assertFalse(PeerRelays.relayOnly(g))
        // Without the capability it would grant nothing: it is not split, and Delete takes it whole.
        assertFalse(PeerRelays.splittable(g))
        assertMeaning(removeAt(parsed(via), listOf("grants", 1)), PeerRelays.remove(via, p("grants", 1)))
    }

    @Test
    fun aRuleWithoutTheCapabilityIsRefused() {
        refused { PeerRelays.remove(sample, p("grants", 0)) }
        refused { PeerRelays.remove(sample, p("grants", 5)) }
        refused { PeerRelays.split(sample, p("grants", 0)) }
    }

    @Test
    fun aNewRuleNeedsBothSides() {
        // The page's templates (a site's relays, a device that relays for itself) are data now: policy-templates/relays-*.json.
        assertFalse(RelayForm().complete)
        assertFalse(RelayForm(clients = listOf("tag:lab")).complete)
        val form = RelayForm(clients = listOf("tag:lab"), relays = listOf("tag:dns"))
        assertTrue(form.complete)
        assertEquals(listOf("tag:lab"), form.src)
        assertEquals(listOf("tag:dns"), form.dst)
    }

    // ---- what the device list says ----

    private fun device(name: String, vararg tags: String, os: String = "linux", version: String? = "1.90.1-t0123abcd", shared: Boolean = false, ip: String) =
        ApiDevice(
            id = name, nodeId = name, name = "$name.tail1234.ts.net", user = "alice@example.com", os = os,
            clientVersion = version, tags = tags.toList(), addresses = listOf(ip), isExternal = shared,
        )

    @Test
    fun versionsAndSystems() {
        assertTrue(PeerRelays.tooOld("1.84.3-t1234"))
        assertFalse(PeerRelays.tooOld("1.86.0"))
        assertFalse(PeerRelays.tooOld("1.104.1-tabc-gdef"))
        assertFalse(PeerRelays.tooOld("2.0.0"))
        assertFalse("unknown is not judged", PeerRelays.tooOld(null))
        assertFalse(PeerRelays.tooOld("dev"))
        assertEquals(RelayDeviceProblem.CANNOT_RELAY, PeerRelays.relayProblem(device("p", os = "android", ip = "100.64.0.1")))
        assertEquals(RelayDeviceProblem.CANNOT_RELAY, PeerRelays.relayProblem(device("i", os = "iOS", ip = "100.64.0.2")))
        assertEquals(RelayDeviceProblem.TOO_OLD, PeerRelays.relayProblem(device("o", version = "1.80.0", ip = "100.64.0.3")))
        assertEquals(RelayDeviceProblem.SHARED, PeerRelays.relayProblem(device("s", shared = true, ip = "100.64.0.4")))
        assertNull(PeerRelays.relayProblem(device("ok", ip = "100.64.0.5")))
        // A phone may use a relay; it cannot be one.
        assertNull(PeerRelays.clientProblem(device("p", os = "android", ip = "100.64.0.1")))
    }

    @Test
    fun theCheckNamesWhoWillNotTakePart() {
        val devices = listOf(
            device("exit-fra", "tag:exit-node", ip = "100.64.0.30"),
            device("pixel", "tag:exit-node", os = "android", ip = "100.64.0.31"),
            device("nas", "tag:homelab", ip = "100.64.0.20"),
            device("old-pi", "tag:homelab", version = "1.80.2", ip = "100.64.0.21"),
        )
        val rule = grants(withRelay)[1]
        val c = PeerRelays.check(rule, HuJson.parse(withRelay), TailnetView(devices, null))
        assertEquals(setOf("exit-fra", "pixel"), c.relays.devices.map { it.shortName }.toSet())
        assertEquals(setOf("nas", "old-pi"), c.clients.devices.map { it.shortName }.toSet())
        assertEquals(listOf("pixel" to RelayDeviceProblem.CANNOT_RELAY), c.relayNotes.map { it.device.shortName to it.problem })
        assertEquals(listOf("old-pi" to RelayDeviceProblem.TOO_OLD), c.clientNotes.map { it.device.shortName to it.problem })
        assertTrue(c.broad.isEmpty())

        // A device that relays for itself is judged once, as a relay.
        val self = PeerRelays.add(sample, listOf("tag:homelab"), listOf("tag:homelab"))
        val s = PeerRelays.check(grants(self)[1], HuJson.parse(self), TailnetView(devices, null))
        assertEquals(listOf("old-pi"), s.relayNotes.map { it.device.shortName })
        assertTrue(s.clientNotes.isEmpty())
        assertEquals(listOf("*", "autogroup:member"), PeerRelays.broad(listOf("tag:lab", "*", "autogroup:member")))
    }

    // ---- the lint ----

    @Test
    fun theLintSaysWhenEveryoneWouldTryTheRelays() {
        fun kinds(text: String) = PolicyLint.introduced(HuJson.parse(sample), HuJson.parse(text)).map { it.kind }
        assertTrue(kinds(withRelay).isEmpty())
        // Everyone, in a rule of relays only: the relay risk, and not an open door.
        val everyone = PeerRelays.setClients(withRelay, p("grants", 1), listOf("*"))
        assertEquals(listOf(RiskKind.RELAY_BROAD), kinds(everyone))
        val members = PeerRelays.setClients(withRelay, p("grants", 1), listOf("autogroup:member"))
        assertEquals(listOf(RiskKind.RELAY_BROAD), kinds(members))
        // Any relay as the destination is no risk either.
        assertTrue(kinds(PeerRelays.setRelays(withRelay, p("grants", 1), listOf("*"))).isEmpty())
        // A rule that also opens ports keeps its wildcard warnings.
        val open = PeerRelays.setClients(driveAndRelay, p("grants", 0), listOf("*"))
        assertEquals(listOf(RiskKind.WILDCARD_SOURCE, RiskKind.RELAY_BROAD), kinds(open))
        // The finding lands on the rule.
        assertEquals(setOf(p("grants", 1)), VisualEnv.risks(sample, everyone).keys)
    }

    // ---- the pickers ----

    @Test
    fun relaySlotsTakeOnlyThisTailnetsDevices() {
        val m = PolicyModel.read(withRelay)!!
        fun c(v: String, slot: SelectorSlot, hs: Boolean = false) = SelectorRules.check(v, slot, m, hs)
        assertNull(c("tag:homelab", SelectorSlot.RELAY_SRC))
        assertNull(c("autogroup:member", SelectorSlot.RELAY_SRC))
        assertNull(c("autogroup:admin", SelectorSlot.RELAY_SRC))
        assertNull(c("*", SelectorSlot.RELAY_SRC))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("autogroup:danger-all", SelectorSlot.RELAY_SRC))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("autogroup:shared", SelectorSlot.RELAY_SRC))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("group://partner/eng", SelectorSlot.RELAY_SRC))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("svc:web", SelectorSlot.RELAY_DST))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("autogroup:internet", SelectorSlot.RELAY_DST))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("autogroup:admin", SelectorSlot.RELAY_DST))
        assertNull(c("autogroup:tagged", SelectorSlot.RELAY_DST))
        assertNull(c("100.64.0.30", SelectorSlot.RELAY_DST))
        assertEquals(SelectorProblem.UNDEFINED, c("tag:nope", SelectorSlot.RELAY_DST))
        // Headscale: its own autogroups, no role ones.
        assertEquals(SelectorProblem.NOT_ON_HEADSCALE, c("autogroup:admin", SelectorSlot.RELAY_SRC, hs = true))
        assertEquals(listOf("autogroup:member", "autogroup:tagged"), SelectorRules.autogroups(SelectorSlot.RELAY_SRC, true).map { it.raw })
        assertEquals(listOf("autogroup:member", "autogroup:tagged"), SelectorRules.autogroups(SelectorSlot.RELAY_DST, false).map { it.raw })
        val offered = SelectorRules.options(SelectorSlot.RELAY_SRC, m, headscale = false).map { it.value }
        assertFalse("autogroup:danger-all" in offered)
        assertTrue("tag:homelab" in offered)
    }

    // ---- the page ----

    @Test
    fun thePageCountsTheRulesAndIsAlwaysOffered() {
        assertEquals(0, VisualSection.PEER_RELAYS.count(PolicyModel.read(sample)!!))
        assertEquals(1, VisualSection.PEER_RELAYS.count(PolicyModel.read(withRelay)!!))
        assertEquals(1, VisualSection.PEER_RELAYS.count(PolicyModel.read(driveAndRelay)!!))
        assertTrue(VisualSection.PEER_RELAYS.available(headscale = true, m = PolicyModel.read(sample)))
        // The rules are grants: their home is the Access page, where errors on them land.
        assertEquals(VisualSection.ACCESS, VisualSection.of(p("grants", 1)))
    }

    @Test
    fun aRiskOnARelayRuleMarksBothItsPages() {
        val everyone = PeerRelays.setClients(withRelay, p("grants", 1), listOf("*"))
        val env = VisualEnv(PolicyDraft(everyone), risks = VisualEnv.risks(sample, everyone))
        val pages = pageEntries(env.model, headscale = false, current = VisualSection.ACCESS, env = env).associateBy { it.section }
        assertEquals(PageMark.RISK, pages.getValue(VisualSection.ACCESS).mark)
        assertEquals(PageMark.RISK, pages.getValue(VisualSection.PEER_RELAYS).mark)
        assertEquals(1, pages.getValue(VisualSection.PEER_RELAYS).count)
        assertNull(pages.getValue(VisualSection.RELAYS).mark)
    }
}
