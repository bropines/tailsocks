package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.lineOf
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The draft, the locator, selector rules and coverage: what the visual editor's screens stand on. */
class VisualSupportTest {

    private val model = PolicyModel.read(sample)!!
    private fun p(vararg s: Any) = PolicyPath.of(*s)

    // ---- draft ----

    @Test
    fun aDraftUndoesRedoesAndRefuses() {
        val d0 = PolicyDraft(sample)
        assertTrue(d0.editable)
        val d1 = d0.edit { PolicyEdits.removeRule(it, p("acls", 12)) }.draft
        assertEquals(13, d1.model!!.acls.size)
        assertTrue(d1.canUndo)
        val d2 = d1.edit { PolicyEdits.putNamed(it, Section.GROUPS, "group:new", listOf("x@example.com")) }.draft
        assertEquals(sample, d2.undo().undo().text)
        assertEquals(d2.text, d2.undo().undo().redo().redo().text)

        val refused = d2.edit { SourceEdits.remove(it, p("acls", 99)) }
        assertTrue(refused is DraftEdit.Refused)
        assertEquals(d2, refused.draft)
        // An edit that changes nothing adds no step.
        assertEquals(d2, d2.edit { it }.draft)
        // What the JSON editor typed comes back as one undoable step.
        val typed = d2.replaced(d2.text.replace("\"group:new\"", "\"group:renamed\""))
        assertEquals(d2.text, typed.undo().text)
    }

    @Test
    fun aDraftThatDoesNotParseOrHasDuplicateSectionsIsReadOnly() {
        val broken = PolicyDraft("{\"acls\": [")
        assertNull(broken.model)
        assertTrue(broken.issue != null)
        assertFalse(broken.editable)
        assertTrue(broken.edit { it + " " } is DraftEdit.Refused)
        assertFalse(PolicyDraft("{\"acls\": [], \"acls\": []}").editable)
    }

    @Test
    fun aDraftKeepsWindowsLineEndings() {
        val d = PolicyDraft(sample.replace("\n", "\r\n")).edit { PolicyEdits.putHost(it, "nas", "100.64.0.10") }.draft
        assertFalse(d.text.replace("\r\n", "").contains('\n'))
        assertEquals("100.64.0.10", d.model!!.hosts.single().address)
    }

    // ---- locator ----

    @Test
    fun linesAndMessagesLeadToElements() {
        val t = SourceTree.parse(sample)
        assertEquals(p("acls", 6), PolicyLocator.elementAtLine(t, lineOf(sample, "\"dst\":    [\"tag:dns:53\"]")))
        assertEquals(p("tagOwners", "tag:dns"), PolicyLocator.elementAtLine(t, lineOf(sample, "\"tag:dns\":")))
        assertEquals(p("autoApprovers", "exitNode"), PolicyLocator.elementAtLine(t, lineOf(sample, "\"exitNode\"")))
        assertEquals(p("derpMap"), PolicyLocator.elementAtLine(t, lineOf(sample, "\"28\": null")))
        assertEquals(p("acls", 0, "dst", 1), PolicyLocator.pathAt(t, sample.indexOf("\"group:dev-admin:*\"")))

        // A message naming a line, and one naming a tag: the elements they are about.
        val line = lineOf(sample, "\"src\":    [\"carol@example.com\"]")
        assertEquals(p("acls", 12), PolicyLocator.locate(t, "line $line, column 4: invalid src").first())
        val byTag = PolicyLocator.locate(t, "tag \"tag:guest\" not found in tagOwners")
        assertTrue(p("acls", 10) in byTag)
        assertTrue(p("tagOwners", "tag:guest") in byTag)
        assertTrue(PolicyLocator.locate(t, "internal error").isEmpty())

        // Lint findings land on rules too.
        val risky = PolicyEdits.addRule(sample, Section.SSH, PolicyEdits.sshFields("accept", listOf("group:guest-users"), listOf("tag:master"), listOf("root")))
        val finding = PolicyLint.introduced(HuJson.parse(sample), HuJson.parse(risky)).first()
        assertEquals(p("ssh", 3), PolicyLocator.element(SourceTree.parse(risky), finding))
    }

    // ---- selector rules ----

    @Test
    fun selectorsAreCheckedForTheirSlot() {
        fun c(v: String, slot: SelectorSlot, hs: Boolean = false) = SelectorRules.check(v, slot, model, hs)
        assertNull(c("group:dev-admin", SelectorSlot.ACL_SRC))
        assertEquals(SelectorProblem.UNDEFINED, c("group:nobody", SelectorSlot.ACL_SRC))
        assertNull(c("group:eng@example.com", SelectorSlot.ACL_SRC))
        assertEquals(SelectorProblem.UNDEFINED, c("tag:undeclared", SelectorSlot.ACL_DST))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("*", SelectorSlot.SSH_SRC))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("autogroup:internet", SelectorSlot.ACL_SRC))
        assertNull(c("autogroup:internet", SelectorSlot.ACL_DST))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("tag:master", SelectorSlot.GROUP_MEMBER))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("group:dev-admin", SelectorSlot.GRANT_VIA))
        assertEquals(SelectorProblem.UNDEFINED, c("nas", SelectorSlot.ACL_DST))
        assertNull(c("root", SelectorSlot.SSH_USER))
        assertNull(c("autogroup:nonroot", SelectorSlot.SSH_USER))
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, c("autogroup:member", SelectorSlot.SSH_USER))
        // Headscale: no role autogroups, no IP sets, no autogroups as owners.
        assertEquals(SelectorProblem.NOT_ON_HEADSCALE, c("autogroup:admin", SelectorSlot.ACL_SRC, hs = true))
        assertNull(c("autogroup:admin", SelectorSlot.ACL_SRC))
        assertEquals(SelectorProblem.NOT_ON_HEADSCALE, c("ipset:x", SelectorSlot.ACL_SRC, hs = true))
        assertEquals(SelectorProblem.NOT_ON_HEADSCALE, c("autogroup:member", SelectorSlot.TAG_OWNER, hs = true))
        assertNull(c("autogroup:member", SelectorSlot.TAG_OWNER))
        assertEquals(SelectorProblem.NOT_ON_HEADSCALE, c("*", SelectorSlot.SSH_USER, hs = true))

        assertNull(SelectorRules.checkAclDestination("tag:homelab:80,443,8096", model))
        assertEquals(SelectorProblem.BAD_PORTS, SelectorRules.checkAclDestination("tag:homelab", model))
        assertEquals(SelectorProblem.BAD_PORTS, SelectorRules.checkAclDestination("tag:homelab:70000", model))
        assertEquals(SelectorProblem.BAD_PORTS, SelectorRules.checkAclDestination("tag:homelab:90-80", model))
        assertNull(SelectorRules.checkTestDestination("tag:master:443", model))
        assertEquals(SelectorProblem.NEEDS_ONE_PORT, SelectorRules.checkTestDestination("tag:master:*", model))
        assertEquals(SelectorProblem.NEEDS_ONE_PORT, SelectorRules.checkTestDestination("tag:master:80,443", model))
    }

    @Test
    fun everyRuleOfTheSampleChecksClean() {
        // The author's policy is accepted by Tailscale; the rules must not flag any of it.
        for (r in model.acls) {
            r.src.forEach { assertNull("acl src $it", SelectorRules.check(it, SelectorSlot.ACL_SRC, model)) }
            r.dst.forEach { assertNull("acl dst $it", SelectorRules.checkAclDestination(it, model)) }
        }
        for (r in model.ssh) {
            r.src.forEach { assertNull(SelectorRules.check(it, SelectorSlot.SSH_SRC, model)) }
            r.dst.forEach { assertNull(SelectorRules.check(it, SelectorSlot.SSH_DST, model)) }
            r.users.forEach { assertNull(SelectorRules.check(it, SelectorSlot.SSH_USER, model)) }
        }
        model.tagOwners.flatMap { it.values }.forEach { assertNull(SelectorRules.check(it, SelectorSlot.TAG_OWNER, model)) }
        model.nodeAttrs.flatMap { it.target }.forEach { assertNull(SelectorRules.check(it, SelectorSlot.NODE_TARGET, model)) }
        model.tests.flatMap { it.deny }.forEach { assertNull(it, SelectorRules.checkTestDestination(it, model)) }
    }

    @Test
    fun pickersOfferWhatTheSlotTakes() {
        val devices = listOf(
            ApiDevice(id = "1", name = "nas.ts.net", tags = listOf("tag:homelab")),
            ApiDevice(id = "2", name = "phone.ts.net", user = "alice@example.com"),
            ApiDevice(id = "3", name = "odd.ts.net", tags = listOf("tag:stray")),
        )
        val users = listOf(ApiUser(id = "u1", loginName = "alice@example.com"))
        val src = SelectorRules.options(SelectorSlot.ACL_SRC, model, headscale = false, users, devices)
        assertEquals("*", src.first().value)
        assertTrue(src.any { it.value == "autogroup:member" })
        assertFalse(src.any { it.value == "autogroup:internet" })
        assertEquals(1, src.first { it.value == "group:prod-admin" }.devices)
        assertEquals(1, src.first { it.value == "tag:homelab" }.devices)
        assertFalse(src.first { it.value == "tag:stray" }.defined)
        assertEquals(1, src.first { it.value == "alice@example.com" }.devices)

        val via = SelectorRules.options(SelectorSlot.GRANT_VIA, model, headscale = false, users, devices)
        assertTrue(via.all { it.kind == SelectorKind.TAG })
        val owners = SelectorRules.options(SelectorSlot.TAG_OWNER, model, headscale = true, users, devices)
        assertTrue(owners.none { it.kind == SelectorKind.AUTOGROUP })
        val sshUsers = SelectorRules.options(SelectorSlot.SSH_USER, model, headscale = false)
        assertEquals(listOf("autogroup:nonroot"), sshUsers.map { it.value })
    }

    // ---- coverage ----

    @Test
    fun aRuleCoversTheDevicesItsSelectorsName() {
        val devices = listOf(
            ApiDevice(id = "1", name = "nas.ts.net", tags = listOf("tag:homelab"), addresses = listOf("100.64.0.1")),
            ApiDevice(id = "2", name = "phone.ts.net", user = "alice@example.com", addresses = listOf("100.64.0.2")),
            ApiDevice(id = "3", name = "laptop.ts.net", user = "guest@example.com", addresses = listOf("100.64.0.3")),
        )
        val view = TailnetView(devices, users = null)
        val policy = HuJson.parse(sample)
        val admins = RuleCoverage.of(model.acls[0].src, policy, view)
        assertEquals(setOf("2"), admins.devices.map { it.id }.toSet())
        assertTrue(admins.complete)
        val dst = RuleCoverage.aclDestinations(model.acls[10], policy, view)
        assertEquals(listOf("autogroup:internet"), dst.unresolved)
        assertTrue(dst.devices.isEmpty())
        assertEquals(setOf("3"), RuleCoverage.of(model.acls[11].src, sample, view).devices.map { it.id }.toSet())
    }
}
