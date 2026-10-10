package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.moveAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.setAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule pages' logic: which syntax new rules take, what forms and templates write, which
 * fields an edit touches, where moves put a rule, the "Add a test" flow, port words, and the
 * SSH page's port-22 check — on the author's sample policy.
 */
class RuleFormTest {

    private val model = PolicyModel.read(sample)!!
    private fun p(vararg s: Any) = PolicyPath.of(*s)
    private fun read(text: String) = PolicyModel.read(text)!!

    // ---- which syntax ----

    @Test
    fun newRulesFollowTheSyntaxTheFileUsesMost() {
        // 14 acls, 1 grant: acls, on Tailscale and Headscale alike.
        assertEquals(Section.ACLS, RuleForms.newAccessSection(model, headscale = false))
        assertEquals(Section.ACLS, RuleForms.newAccessSection(model, headscale = true))
        val grants = read("""{"grants": [{"src": ["*"], "dst": ["*"], "ip": ["*"]}]}""")
        assertEquals(Section.GRANTS, RuleForms.newAccessSection(grants, headscale = true))
        val empty = read("{}")
        assertEquals(Section.GRANTS, RuleForms.newAccessSection(empty, headscale = false))
        assertEquals(Section.ACLS, RuleForms.newAccessSection(empty, headscale = true))
    }

    // ---- forms ----

    @Test
    fun aFormIsWrittenOnlyWhenItSaysEnough() {
        val acl = RuleForms.emptyAccess(model, Section.ACLS) as AccessRule.Acl
        assertFalse(RuleForms.complete(acl))
        assertFalse(RuleForms.complete(AccessRule.Acl(acl.rule.copy(src = listOf("group:dev-admin")))))
        val full = AccessRule.Acl(acl.rule.copy(src = listOf("group:dev-admin"), dst = listOf("tag:lab:22")))
        assertTrue(RuleForms.complete(full))
        assertEquals(p("acls", 14), full.origin.path)

        // A grant also needs its ports: none would be refused, a guessed "*" would open too much.
        val grant = RuleForms.emptyAccess(model, Section.GRANTS) as AccessRule.Grant
        val noPorts = AccessRule.Grant(grant.rule.copy(src = listOf("group:dev-admin"), dst = listOf("tag:lab")))
        assertFalse(RuleForms.complete(noPorts))
        assertTrue(RuleForms.complete(AccessRule.Grant(noPorts.rule.copy(ip = listOf("22")))))

        assertFalse(RuleForms.complete(RuleForms.emptySsh(model).copy(src = listOf("group:a"), dst = listOf("tag:b"))))
        assertTrue(RuleForms.complete(RuleForms.emptySsh(model).copy(src = listOf("group:a"), dst = listOf("tag:b"), users = listOf("root"))))
        assertFalse(RuleForms.complete(RuleForms.emptyTest(model).copy(src = "guest@example.com")))
        assertTrue(RuleForms.complete(RuleForms.emptyTest(model).copy(src = "guest@example.com", deny = listOf("tag:lab:22"))))
        assertTrue(RuleForms.complete(RuleForms.emptySshTest(model).copy(src = "group:a", dst = listOf("tag:b"), check = listOf("root"))))
    }

    @Test
    fun aNewAclLandsAtTheEndOfItsSectionAsTheFormSays() {
        val form = AccessRule.Acl(AclRule(RuleForms.newOrigin(model, Section.ACLS), "accept", listOf("group:dev-admin"), listOf("tag:lab:22", "tag:dns:53"), "tcp", emptyList()))
        val after = PolicyEdits.addRule(sample, Section.ACLS, RuleForms.fields(form))
        val written = read(after).acls.last()
        assertEquals(form.origin.path, written.origin.path)
        assertEquals(listOf("group:dev-admin"), written.src)
        assertEquals(listOf("tag:lab:22", "tag:dns:53"), written.dst)
        assertEquals("tcp", written.proto)
        // Written like its neighbours: tabs, aligned values, trailing commas.
        assertTrue(after.contains("\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"group:dev-admin\"],"))
    }

    @Test
    fun templatesTakeTheSyntaxOfTheFile() {
        val acl = RuleForms.template(AccessTemplate.OWN_DEVICES, model, Section.ACLS) as AccessRule.Acl
        assertEquals(listOf("autogroup:member"), acl.rule.src)
        assertEquals(listOf("autogroup:self:*"), acl.rule.dst)
        assertTrue(RuleForms.complete(acl))

        val grant = RuleForms.template(AccessTemplate.ADMINS_EVERYTHING, model, Section.GRANTS) as AccessRule.Grant
        assertEquals(listOf("*"), grant.rule.dst)
        assertEquals(listOf("*"), grant.rule.ip)

        // The guests and the group are the person's to pick: those forms open, not write.
        assertFalse(RuleForms.complete(RuleForms.template(AccessTemplate.GUESTS_INTERNET, model, Section.ACLS)))
        assertEquals(listOf("autogroup:internet:*"), (RuleForms.template(AccessTemplate.GUESTS_INTERNET, model, Section.ACLS) as AccessRule.Acl).rule.dst)
        assertFalse(RuleForms.complete(RuleForms.template(AccessTemplate.GROUP_TO_TAG, model, Section.GRANTS)))

        assertFalse(AccessTemplate.ADMINS_EVERYTHING in RuleForms.templates(headscale = true))
        assertEquals(4, RuleForms.templates(headscale = false).size)

        // A template into an empty file creates the section.
        val written = PolicyEdits.addRule("{}", Section.ACLS, RuleForms.fields(RuleForms.template(AccessTemplate.OWN_DEVICES, read("{}"), Section.ACLS)))
        assertEquals(listOf("autogroup:self:*"), read(written).acls.single().dst)
    }

    // ---- edits touch what changed ----

    @Test
    fun anEditTouchesOnlyTheFieldThatChanged() {
        val rule = model.acls[4] // tag:server → tag:master:80,443
        val changed = rule.copy(dst = listOf("tag:master:443"))
        val diff = RuleForms.changed(RuleForms.fields(rule), RuleForms.fields(changed))
        assertEquals(listOf("dst"), diff.map { it.first })
        val after = PolicyEdits.updateRule(sample, rule.origin.path, diff)
        // The one string changed in place; every other byte is the sample's.
        assertEquals(sample.replaceFirst("\"tag:master:80,443\"", "\"tag:master:443\""), after)
        assertTrue(window(sample, after).inserted.isEmpty())
        assertMeaning(setAt(parsed(sample), listOf("acls", 4, "dst"), json("""["tag:master:443"]""")), after)

        // Opening a rule and leaving it changes nothing, field for field.
        for (r in model.acls) assertTrue(RuleForms.changed(RuleForms.fields(r), RuleForms.fields(r)).isEmpty())
        for (r in model.ssh) assertTrue(RuleForms.changed(RuleForms.fields(r), RuleForms.fields(r)).isEmpty())
        for (t in model.tests) assertTrue(RuleForms.changed(RuleForms.fields(t), RuleForms.fields(t)).isEmpty())
        // And writing every field back as it was gives the same bytes.
        assertEquals(sample, PolicyEdits.updateRule(sample, model.ssh[0].origin.path, RuleForms.fields(model.ssh[0])))
        assertEquals(sample, PolicyEdits.updateRule(sample, model.grants[0].origin.path, RuleForms.fields(model.grants[0])))
    }

    @Test
    fun switchingAnSshRuleToCheckWritesItsPeriodAndBackRemovesIt() {
        val rule = model.ssh[2]
        val check = rule.copy(action = "check", checkPeriod = "1h")
        val after = PolicyEdits.updateRule(sample, rule.origin.path, RuleForms.changed(RuleForms.fields(rule), RuleForms.fields(check)))
        val read = read(after).ssh[2]
        assertEquals("check", read.action)
        assertEquals("1h", read.checkPeriod)
        val back = read.copy(action = "accept", checkPeriod = null)
        val again = PolicyEdits.updateRule(after, rule.origin.path, RuleForms.changed(RuleForms.fields(read), RuleForms.fields(back)))
        assertEquals(sample, again)
    }

    // ---- moves ----

    private val headers = model.acls.map { it.origin.header != null }

    @Test
    fun moveUpLiftsTheFirstRuleOverItsHeadingAndSwapsTheOthers() {
        assertNull(RuleForms.moveUp(headers, 0))
        // acls[2] opens "1. PRODUCTION CORE": it goes above the heading, keeping its index.
        assertEquals(2 to Anchor.AFTER_PREVIOUS, RuleForms.moveUp(headers, 2))
        val lifted = PolicyEdits.moveRule(sample, Section.ACLS, 2, 2, Anchor.AFTER_PREVIOUS)
        val m = read(lifted)
        assertNull(m.acls[2].origin.header)
        assertTrue(m.acls[3].origin.header!!.contains("PRODUCTION CORE"))
        assertEquals(model.acls[2].dst, m.acls[2].dst)

        // acls[3] swaps with acls[2] and stays under the heading.
        assertEquals(2 to Anchor.BEFORE_NEXT, RuleForms.moveUp(headers, 3))
        val swapped = PolicyEdits.moveRule(sample, Section.ACLS, 3, 2, Anchor.BEFORE_NEXT)
        val s = read(swapped)
        assertTrue(s.acls[2].origin.header!!.contains("PRODUCTION CORE"))
        assertEquals(model.acls[3].dst, s.acls[2].dst)
        assertMeaning(moveAt(parsed(sample), listOf("acls"), 3, 2), swapped)
    }

    @Test
    fun moveDownCrossesTheNextHeadingAndStopsAtTheEnd() {
        assertNull(RuleForms.moveDown(headers, headers.lastIndex))
        // acls[5] is the last of its run: it goes under "2. DNS", the first rule of that run now.
        assertEquals(5 to Anchor.BEFORE_NEXT, RuleForms.moveDown(headers, 5))
        val after = PolicyEdits.moveRule(sample, Section.ACLS, 5, 5, Anchor.BEFORE_NEXT)
        val m = read(after)
        assertTrue(m.acls[5].origin.header!!.contains("DNS"))
        assertNull(m.acls[6].origin.header)
        assertEquals(model.acls[5].dst, m.acls[5].dst)
        assertEquals(model.acls[6].dst, m.acls[6].dst)
        // Only the heading and the rule changed places: the same rules in the same order.
        assertEquals(HuJson.canonical(parsed(sample)), HuJson.canonical(parsed(after)))

        // Inside a run, a rule swaps with the one below it and both stay under their heading.
        assertEquals(7 to Anchor.AFTER_PREVIOUS, RuleForms.moveDown(headers, 6))
        val swapped = PolicyEdits.moveRule(sample, Section.ACLS, 6, 7, Anchor.AFTER_PREVIOUS)
        val s = read(swapped)
        assertTrue(s.acls[6].origin.header!!.contains("DNS"))
        assertEquals(model.acls[6].dst, s.acls[7].dst)
        assertMeaning(moveAt(parsed(sample), listOf("acls"), 6, 7), swapped)
    }

    private fun originsOf(m: PolicyModel, section: Section): List<Origin> = when (section) {
        Section.ACLS -> m.acls.map { it.origin }
        Section.SSH -> m.ssh.map { it.origin }
        Section.TESTS -> m.tests.map { it.origin }
        Section.NODE_ATTRS -> m.nodeAttrs.map { it.origin }
        else -> error("no moves on $section")
    }

    private fun headersOf(m: PolicyModel, section: Section): List<Boolean> = originsOf(m, section).map { it.header != null }

    /** Where one step takes rule [i], as the card's arrows do it: the new text and the rule's new index. */
    private fun step(text: String, section: Section, i: Int, down: Boolean): Pair<String, Int>? {
        val h = headersOf(read(text), section)
        val (to, anchor) = (if (down) RuleForms.moveDown(h, i) else RuleForms.moveUp(h, i)) ?: return null
        return PolicyEdits.moveRule(text, section, i, to, anchor) to to
    }

    /**
     * A step the text cannot take back exactly, by the comment rules (Trivia): a note the step
     * puts right under a heading, or at the top of the list, reads as part of that heading
     * afterwards; a heading the step leaves over no rule heads none any more. The meaning is
     * kept either way, and undo restores the bytes.
     */
    private fun blursComments(origins: List<Origin>, i: Int, down: Boolean): Boolean {
        val h = origins.map { it.header != null }
        val last = origins.lastIndex
        val involved = if (down) listOf(i, i + 1) else listOfNotNull(i, i - 1, (i + 1).takeIf { h[i] && it <= last })
        val emptied = if (down) h[i] && h[i + 1] else h[i] && (i == last || h[i + 1])
        return emptied || involved.any { origins[it].note != null }
    }

    @Test
    fun oneStepAndOneBackPutEveryRuleBackByteForByte() {
        var checked = 0
        for (section in listOf(Section.ACLS, Section.SSH, Section.TESTS, Section.NODE_ATTRS)) {
            val origins = originsOf(model, section)
            for (i in origins.indices) for (down in listOf(true, false)) {
                val (moved, at) = step(sample, section, i, down) ?: continue
                val what = "$section[$i] ${if (down) "down" else "up"}"
                assertMeaning(moveAt(parsed(sample), listOf(section.key), i, at), moved)
                if (blursComments(origins, i, down)) continue
                val (back, home) = step(moved, section, at, !down) ?: error("$what: no way back")
                assertEquals("$what and back", i, home)
                assertEquals("$what and back", sample, back)
                checked++
            }
        }
        // Most steps of the sample are plain ones: across its headings and between rules alike.
        assertTrue("only $checked steps checked", checked >= 20)
    }

    @Test
    fun theRuleOpenInAPaneFollowsAMove() {
        // The moved rule itself goes where it was taken.
        assertEquals(2, RuleForms.indexAfterMove(5, 5, 2))
        // A rule moved down past the open one lifts it by one; moved up past it, pushes it down.
        assertEquals(3, RuleForms.indexAfterMove(4, 2, 4))
        assertEquals(5, RuleForms.indexAfterMove(4, 6, 2))
        // Rules outside the stretch stay; a move that keeps its index changes nothing.
        assertEquals(1, RuleForms.indexAfterMove(1, 2, 4))
        assertEquals(7, RuleForms.indexAfterMove(7, 2, 4))
        assertEquals(3, RuleForms.indexAfterMove(3, 5, 5))
    }

    @Test
    fun addHereGoesAfterTheLastRuleOfTheRun() {
        assertEquals(2, RuleForms.insertAfterRun(headers, 0))
        assertEquals(6, RuleForms.insertAfterRun(headers, 2))
        // 4. GUESTS runs on through the two rules with notes, to 5. ANDROID.
        assertEquals(13, RuleForms.insertAfterRun(headers, 10))
        assertEquals(14, RuleForms.insertAfterRun(headers, 13))

        val rows = pageRows(model.acls, Section.ACLS) { it.origin }
        val headings = rows.filterIsInstance<PageRow.Heading>()
        assertEquals(listOf(2, 6, 8, 10, 13, 14), headings.map { it.insertAt })
        assertEquals(model.acls.size + headings.size, rows.size)

        // A rule added "here" lands under that heading, the next heading still over its own rule.
        val f = PolicyEdits.aclFields(listOf("group:dev-admin"), listOf("tag:dns:853"))
        val after = PolicyEdits.addRule(sample, Section.ACLS, f, index = 8, anchor = Anchor.AFTER_PREVIOUS)
        val m = read(after)
        assertEquals(listOf("tag:dns:853"), m.acls[8].dst)
        assertNull(m.acls[8].origin.header)
        assertTrue(m.acls[9].origin.header!!.contains("DEV / SANDBOX"))
    }

    // ---- duplicate, convert, tests from rules ----

    @Test
    fun duplicateCopiesTheRuleAsWritten() {
        val after = RuleForms.duplicate(sample, p("acls", 4))
        val m = read(after)
        assertEquals(15, m.acls.size)
        assertEquals(RuleForms.fields(m.acls[4]), RuleForms.fields(m.acls[5]))
        // A grant's app capabilities come along, parameters and all.
        val g = read(RuleForms.duplicate(sample, p("grants", 0)))
        assertEquals(2, g.grants.size)
        assertEquals(g.grants[0].app.map { it.name to it.source }, g.grants[1].app.map { it.name to it.source })
    }

    @Test
    fun convertToGrantKeepsTheNoteAndTheMeaningOfThePorts() {
        val rule = model.acls[11] // group:guest-users → autogroup:internet:*, with a note
        val after = RuleForms.convertToGrants(sample, rule)
        val m = read(after)
        assertEquals(13, m.acls.size)
        val grant = m.grants.last()
        assertEquals(listOf("group:guest-users"), grant.src)
        assertEquals(listOf("autogroup:internet"), grant.dst)
        assertEquals(listOf("*"), grant.ip)
        assertEquals(rule.origin.note, grant.origin.note)

        val ports = read(RuleForms.convertToGrants(sample, model.acls[4])).grants.last()
        assertEquals(listOf("80", "443"), ports.ip)
        assertTrue(RuleForms.canConvert(model, headscale = false))
        assertTrue(RuleForms.canConvert(model, headscale = true))
        assertFalse(RuleForms.canConvert(read("""{"acls": []}"""), headscale = true))
    }

    @Test
    fun anAclGivenAViaBecomesAGrant() {
        val rule = model.acls[4] // tag:server → tag:master:80,443
        val grant = RuleForms.grantWithVia(rule, listOf("tag:exit-node"), RuleForms.newOrigin(model, Section.GRANTS))!!
        assertEquals(listOf("tag:master"), grant.dst)
        assertEquals(listOf("80", "443"), grant.ip)
        assertEquals(listOf("tag:exit-node"), grant.via)
        val after = RuleForms.replaceWithGrant(sample, rule, grant)
        val m = read(after)
        assertEquals(13, m.acls.size)
        assertEquals(grant.copy(origin = m.grants.last().origin), m.grants.last())

        // A protocol goes with the ports; different ports per destination cannot be one grant.
        val tcp = RuleForms.grantWithVia(rule.copy(proto = "tcp"), listOf("tag:exit-node"), grant.origin)!!
        assertEquals(listOf("tcp:80", "tcp:443"), tcp.ip)
        assertNull(RuleForms.grantWithVia(rule.copy(dst = listOf("tag:master:22", "tag:dns:53")), listOf("tag:exit-node"), grant.origin))
        // The guests' rule keeps its note on the way.
        val guests = model.acls[11]
        val g = read(RuleForms.replaceWithGrant(sample, guests, RuleForms.grantWithVia(guests, listOf("tag:exit-node"), grant.origin)!!)).grants.last()
        assertEquals(guests.origin.note, g.origin.note)
        assertEquals(listOf("*"), g.ip)
    }

    @Test
    fun aTestFromARuleAndOneThatKeepsADeletedRuleClosed() {
        val rule = model.acls[12] // carol@example.com → tag:lab:*
        val f = PolicyEdits.testFrom(rule)!!
        val after = PolicyEdits.addRule(sample, Section.TESTS, f)
        val t = read(after).tests.last()
        assertEquals("carol@example.com", t.src)
        assertEquals(listOf("tag:lab:22"), t.accept)

        val deleted = PolicyEdits.removeRule(sample, rule.origin.path)
        val closed = PolicyEdits.addRule(deleted, Section.TESTS, PolicyEdits.testFrom(rule, accept = false)!!)
        assertEquals(listOf("tag:lab:22"), read(closed).tests.last().deny)
        // A rule from everyone has no single source to test from.
        assertNull(PolicyEdits.testFrom(model.acls[6]))
    }

    // ---- ports ----

    @Test
    fun portsInWordsAndChips() {
        assertEquals("80, 443, 8096", PortWords.display("80,443,8096"))
        assertEquals("8000–8999", PortWords.display("8000-8999"))
        assertEquals("80, 443, 8096", PortWords.short("80,443,8096"))
        assertEquals("80, 443 +6", PortWords.short("80,443,8096,4566,56569,8680,8443,7777"))
        assertEquals(22, PortWords.single("22"))
        assertNull(PortWords.single("22,80"))
        assertNull(PortWords.single("70000"))
        assertEquals("TCP", PortWords.proto("tcp"))
        assertEquals("47", PortWords.proto("47"))

        assertNull(PortWords.checkIp("443"))
        assertNull(PortWords.checkIp("tcp:8443"))
        assertNull(PortWords.checkIp("icmp:*"))
        assertNull(PortWords.checkIp("47:*"))
        assertEquals(SelectorProblem.UNKNOWN_FORM, PortWords.checkIp("foo:22"))
        assertEquals(SelectorProblem.BAD_PORTS, PortWords.checkIp("tcp:99999"))
        assertEquals(SelectorProblem.BAD_PORTS, PortWords.checkIp("443-80"))

        assertEquals(listOf("22"), PortWords.toggle(listOf("*"), IpPreset.SSH))
        assertEquals(listOf("*"), PortWords.toggle(listOf("22", "tcp:8443"), IpPreset.ALL))
        assertEquals(listOf("tcp:8443"), PortWords.toggle(listOf("22", "tcp:8443"), IpPreset.SSH))
        assertEquals(listOf("tcp:8443"), PortWords.custom(listOf("22", "tcp:8443", "*")))

        assertNull(portsProblem("80,443", single = false))
        assertEquals(SelectorProblem.BAD_PORTS, portsProblem("80,,", single = false))
        assertEquals(SelectorProblem.NEEDS_ONE_PORT, portsProblem("80,443", single = true))
        assertEquals(SelectorProblem.EMPTY, portsProblem(" ", single = true))
    }

    // ---- SSH ----

    @Test
    fun sshPeriodsAndLogins() {
        assertTrue(RuleForms.periodOk("12h"))
        assertTrue(RuleForms.periodOk("always"))
        assertTrue(RuleForms.periodOk("1h30m"))
        assertTrue(RuleForms.periodOk("168h"))
        assertFalse(RuleForms.periodOk("169h"))
        assertFalse(RuleForms.periodOk("30s"))
        assertFalse(RuleForms.periodOk(""))
        assertFalse(RuleForms.periodOk("soon"))
        assertEquals(listOf("root", "administrator", "mini", "worker"), RuleForms.loginSuggestions(model))
    }

    @Test
    fun theServerIsAskedAboutTheFirstUserARuleNames() {
        assertEquals("alice@example.com", RuleForms.previewUser(listOf("tag:lab", "group:prod-admin"), model))
        assertEquals("carol@example.com", RuleForms.previewUser(listOf("group:prod-admin", "carol@example.com"), model))
        assertNull(RuleForms.previewUser(listOf("tag:lab", "*"), model))
    }

    private val devices = listOf(
        ApiDevice(id = "1", name = "alice-phone.ts.net", user = "alice@example.com"),
        ApiDevice(id = "2", name = "guest-pc.ts.net", user = "guest@example.com"),
        ApiDevice(id = "3", name = "master.ts.net", user = "alice@example.com", tags = listOf("tag:master")),
        ApiDevice(id = "4", name = "nas.ts.net", user = "alice@example.com", tags = listOf("tag:homelab")),
        ApiDevice(id = "5", name = "lab.ts.net", user = "alice@example.com", tags = listOf("tag:lab")),
    )
    private val view = TailnetView(devices, users = null)

    @Test
    fun sshWithoutPort22InTheAccessRulesIsNoticedAndFixed() {
        val policy = HuJson.parse(sample)
        // The author's rules open port 22 wherever his SSH rules point.
        for (r in model.ssh) assertFalse(r.src.toString(), RuleForms.sshNeedsAccess(r, model, policy, view))

        val guests = RuleForms.emptySsh(model).copy(src = listOf("group:guest-users"), dst = listOf("tag:homelab"), users = listOf("guest"))
        assertTrue(RuleForms.sshNeedsAccess(guests, model, policy, view))

        // The access rule "Add it" writes closes the gap.
        val fixed = PolicyEdits.addRule(sample, Section.ACLS, RuleForms.accessForSsh(guests, Section.ACLS))
        assertEquals(listOf("tag:homelab:22"), read(fixed).acls.last().dst)
        assertFalse(RuleForms.sshNeedsAccess(guests, read(fixed), HuJson.parse(fixed), view))
        assertEquals(listOf("22"), (RuleForms.accessForSsh(guests, Section.GRANTS).first { it.first == "ip" }.second as PolicyValue.Arr).items.map { (it as PolicyValue.Str).value })

        // What the phone cannot resolve is not called a gap.
        val self = guests.copy(dst = listOf("autogroup:self"))
        assertFalse(RuleForms.sshNeedsAccess(self, model, policy, view))
        assertFalse(RuleForms.sshNeedsAccess(guests, model, policy, TailnetView(emptyList(), null)))
    }
}
