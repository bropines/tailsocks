package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertOtherLinesKept
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.insertAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.setAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taildrive in the policy: what the Attributes page's template writes, where, and when the policy needs none of it. */
class TaildriveRulesTest {

    private fun p(vararg s: Any) = PolicyPath.of(*s)
    private val cap = TaildriveRules.CAP
    private val grantJson = """{"src": ["autogroup:member"], "dst": ["autogroup:self"], "app": {"$cap": [{"shares": ["*"], "access": "rw"}]}}"""
    private val attrsJson = """{"target": ["autogroup:member"], "attr": ["drive:share", "drive:access"]}"""

    /** What the template's form does on Create, left as the template filled it: the rule, and the grant with it. */
    private fun template(text: String): String {
        val preset = AttrTemplate.TAILDRIVE.preset
        return TaildriveRules.withGrant(PolicyEdits.addRule(text, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(preset.targets, preset.attrs)))
    }

    /** The author's file without its Taildrive grant: comments, other sections and both attributes still there. */
    private val noGrant = SourceEdits.remove(sample, p("grants"))

    /** The author's file with no Taildrive in it at all: neither attribute, a grant of relays only. */
    private val noDrive = PeerRelays.add(noGrant, listOf("tag:homelab"), listOf("tag:exit-node")).let { t ->
        val attrs = PolicyModel.read(t)!!.nodeAttrs[0].attr - TaildriveRules.ATTRS.toSet()
        PolicyEdits.updateRule(t, p("nodeAttrs", 0), listOf("attr" to attrs.pv()))
    }

    private fun covered(text: String) = TaildriveRules.covered(PolicyModel.read(text)!!)

    // ---- the template ----

    @Test
    fun theTemplateFillsTheFormWithMembersAndBothAttributes() {
        val preset = AttrTemplate.TAILDRIVE.preset
        assertEquals(listOf("autogroup:member"), preset.targets)
        assertEquals(listOf("drive:share", "drive:access"), preset.attrs)
        assertTrue(preset.driveGrant)
        // No other form writes a grant.
        assertFalse(AttrTemplate.FASTER_START.preset.driveGrant)
        assertFalse(AttrPreset().driveGrant)
        // Under the devices: the grant that Create writes too, or that the policy's own one stays.
        assertEquals(R.string.admin_pvtd_hint, taildrivePreset(PolicyModel.read(noDrive)!!).hint)
        assertEquals(R.string.admin_pvtd_hint, taildrivePreset(PolicyModel.read("{}")!!).hint)
        val grantOnly = PolicyEdits.updateRule(sample, p("nodeAttrs", 0), listOf("attr" to listOf("funnel").pv()))
        assertFalse(covered(grantOnly))
        assertEquals(R.string.admin_pvtd_hint_grant_kept, taildrivePreset(PolicyModel.read(grantOnly)!!).hint)
        assertEquals(preset.copy(hint = R.string.admin_pvtd_hint_grant_kept), taildrivePreset(PolicyModel.read(grantOnly)!!))
    }

    // ---- writing ----

    @Test
    fun anEmptyPolicyGetsBothSections() {
        val out = template("{}")
        assertEquals(
            "{\n" +
                "\t\"grants\": [\n" +
                "\t\t{\n" +
                "\t\t\t\"src\": [\"autogroup:member\"],\n" +
                "\t\t\t\"dst\": [\"autogroup:self\"],\n" +
                "\t\t\t\"app\": {\n" +
                "\t\t\t\t\"$cap\": [\n" +
                "\t\t\t\t\t{\n" +
                "\t\t\t\t\t\t\"shares\": [\"*\"],\n" +
                "\t\t\t\t\t\t\"access\": \"rw\",\n" +
                "\t\t\t\t\t},\n" +
                "\t\t\t\t],\n" +
                "\t\t\t},\n" +
                "\t\t},\n" +
                "\t],\n" +
                "\n" +
                "\t\"nodeAttrs\": [\n" +
                "\t\t{\n" +
                "\t\t\t\"target\": [\"autogroup:member\"],\n" +
                "\t\t\t\"attr\":   [\"drive:share\", \"drive:access\"],\n" +
                "\t\t},\n" +
                "\t],\n" +
                "}",
            out,
        )
        assertMeaning(json("""{"grants": [$grantJson], "nodeAttrs": [$attrsJson]}"""), out)
        assertTrue(covered(out))
    }

    @Test
    fun aFileWithCommentsKeepsThem() {
        val out = template(noDrive)
        // Nothing of the file goes: its lines, the Russian comments among them, are all still there in order.
        assertOtherLinesKept(noDrive, out, IntRange.EMPTY)
        assertTrue(out.contains("// Funnel только своим: гости не публикуют устройства под доменом тейлнета."))
        assertTrue(out.contains("// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП (USER-DEFINED GROUPS) ---"))
        // The rule after the file's two, the grant after its one.
        val expected = insertAt(insertAt(parsed(noDrive), listOf("nodeAttrs"), 2, json(attrsJson)), listOf("grants"), 1, json(grantJson))
        assertMeaning(expected, out)
        // Written the way the file writes its own: tabs, values in a column, trailing commas.
        assertTrue(out, out.contains("\t\t{\n\t\t\t\"target\": [\"autogroup:member\"],\n\t\t\t\"attr\":   [\"drive:share\", \"drive:access\"],\n\t\t},\n\t],"))
        assertTrue(
            out,
            out.contains(
                "\t\t{\n\t\t\t\"src\": [\"autogroup:member\"],\n\t\t\t\"dst\": [\"autogroup:self\"],\n\t\t\t\"app\": {\n" +
                    "\t\t\t\t\"$cap\": [\n\t\t\t\t\t{\n\t\t\t\t\t\t\"shares\": [\"*\"],\n\t\t\t\t\t\t\"access\": \"rw\",\n\t\t\t\t\t},\n\t\t\t\t],\n\t\t\t},\n\t\t},\n\t],"
            ),
        )
        assertFalse(covered(noDrive))
        assertTrue(covered(out))
    }

    @Test
    fun aFileWithoutGrantsGetsTheSection() {
        val out = TaildriveRules.withGrant(noGrant)
        assertEquals("only an insertion", "", window(noGrant, out).removed)
        assertMeaning(setAt(parsed(noGrant), listOf("grants"), json("[$grantJson]")), out)
        // In the place the editor gives a new grants section: after the ACLs, before SSH.
        assertTrue(out.indexOf("\"acls\"") < out.indexOf("\"grants\""))
        assertTrue(out.indexOf("\"grants\"") < out.indexOf("\"ssh\""))
    }

    @Test
    fun theGrantIsWrittenOnce() {
        // The author's own grant carries the capability, for other devices: it is their answer, and stays.
        assertEquals(sample, TaildriveRules.withGrant(sample))
        val once = TaildriveRules.withGrant(noGrant)
        assertEquals(once, TaildriveRules.withGrant(once))
        // Beside another capability it is still there.
        val mixed = SourceEdits.put(sample, p("grants", 0, "app"), PeerRelays.CAP, PolicyValue.Arr(emptyList()), emptyList())
        assertEquals(mixed, TaildriveRules.withGrant(mixed))
        // The template a second time adds its rule again, as any new rule would be, and no second grant.
        val twice = template(template("{}"))
        assertEquals(1, PolicyModel.read(twice)!!.grants.size)
        assertEquals(2, PolicyModel.read(twice)!!.nodeAttrs.size)
    }

    @Test
    fun theFormIsOneStepToUndo() {
        val done = PolicyDraft(noDrive).edit(::template) as DraftEdit.Done
        assertEquals(listOf(noDrive), done.draft.undoStack)
        assertEquals(noDrive, done.draft.undo().text)
        // Windows line endings stay what they were.
        val crlf = PolicyDraft(noDrive.replace("\n", "\r\n")).edit(::template) as DraftEdit.Done
        assertEquals(template(noDrive).replace("\n", "\r\n"), crlf.draft.text)
    }

    // ---- reading back ----

    @Test
    fun whatIsWrittenReadsBackAsRulesTheEditorEdits() {
        val m = PolicyModel.read(template(noDrive))!!
        val rule = m.nodeAttrs.last()
        assertEquals(TaildriveRules.MEMBERS, rule.target)
        assertEquals(TaildriveRules.ATTRS, rule.attr)
        assertTrue(rule.origin.editable)
        assertTrue(rule.origin.extra.isEmpty())
        assertEquals(p("nodeAttrs", 2), rule.origin.path)

        val grant = m.grants.last()
        assertEquals(listOf("autogroup:member"), grant.src)
        assertEquals(listOf("autogroup:self"), grant.dst)
        assertEquals(listOf(cap), grant.app.map { it.name })
        assertTrue(grant.ip.isEmpty() && grant.via.isEmpty() && grant.srcPosture.isEmpty())
        assertTrue(grant.origin.editable)
        assertTrue(grant.origin.extra.isEmpty())
        assertTrue(TaildriveRules.isDrive(grant))
        assertFalse(PeerRelays.isRelay(grant))
        // The capability's parameters as Tailscale's own example writes them.
        assertEquals(HuJson.canonical(json("""[{"shares": ["*"], "access": "rw"}]""")), HuJson.canonical(json(grant.app.single().source)))
        // A grant: on the Access page, where the form says it can be narrowed.
        assertEquals(VisualSection.ACCESS, VisualSection.of(grant.origin.path))
        assertEquals(VisualSection.ATTRIBUTES, VisualSection.of(rule.origin.path))
        // The editor knows the capability and both attributes by name.
        assertEquals(cap, KnownCaps.grant(cap)?.name)
        assertEquals(TaildriveRules.ATTRS, TaildriveRules.ATTRS.mapNotNull { KnownAttrs.of(it)?.key })
    }

    @Test
    fun thePickersTakeWhatIsWritten() {
        val m = PolicyModel.read("{}")!!
        assertNull(SelectorRules.check("autogroup:member", SelectorSlot.NODE_TARGET, m))
        assertNull(SelectorRules.check("autogroup:member", SelectorSlot.GRANT_SRC, m))
        assertNull(SelectorRules.check("autogroup:self", SelectorSlot.GRANT_DST, m))
    }

    // ---- whether the policy needs it ----

    @Test
    fun coveredTakesBothAttributesAndTheGrant() {
        assertTrue("the author's own policy", covered(sample))
        assertFalse(covered("{}"))
        assertFalse("no grant", covered(noGrant))
        assertFalse(covered(noDrive))
        // The attributes without the grant, the grant without the attributes.
        assertFalse(covered(PolicyEdits.addRule("{}", Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(TaildriveRules.MEMBERS, TaildriveRules.ATTRS))))
        assertFalse(covered(TaildriveRules.withGrant("{}")))
        // One attribute is half of it, whichever.
        fun with(vararg attrs: String) = PolicyEdits.addRule(TaildriveRules.withGrant("{}"), Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(listOf("tag:nas"), attrs.toList()))
        assertFalse(covered(with("drive:share")))
        assertFalse(covered(with("drive:access", "funnel")))
        assertTrue(covered(with("drive:access", "drive:share")))
        // In two rules — a server that shares, people who open — it is all there.
        assertTrue(covered(PolicyEdits.addRule(with("drive:share"), Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(listOf("autogroup:member"), listOf("drive:access")))))
        // The capability beside another in one grant counts; a grant of relays alone does not.
        val relays = PeerRelays.add(PolicyEdits.addRule("{}", Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(TaildriveRules.MEMBERS, TaildriveRules.ATTRS)), listOf("tag:a"), listOf("tag:b"))
        assertFalse(covered(relays))
        assertFalse(TaildriveRules.hasGrant(PolicyModel.read(relays)!!))
        val both = SourceEdits.put(relays, p("grants", 0, "app"), cap, PolicyValue.Arr(emptyList()), emptyList())
        assertTrue(covered(both))
    }

    // ---- the lint ----

    @Test
    fun theLintFindsNoNewRiskInWhatIsWritten() {
        fun introduced(before: String, after: String) = PolicyLint.introduced(HuJson.parse(before), HuJson.parse(after))
        assertTrue(introduced("{}", template("{}")).isEmpty())
        assertTrue(introduced(noDrive, template(noDrive)).isEmpty())
        assertTrue(introduced(noGrant, TaildriveRules.withGrant(noGrant)).isEmpty())
        // Nothing lands on a card either, and the written rules have no finding of their own.
        assertTrue(VisualEnv.risks(noDrive, template(noDrive)).isEmpty())
        assertTrue(PolicyLint.findings(HuJson.parse(template("{}"))).isEmpty())
        // Members to their own devices is not the open door a wildcard is: the lint keeps its
        // warnings for `*` and danger-all, and for Funnel given to every member.
        val wide = PolicyEdits.updateRule(template("{}"), p("grants", 0), listOf("src" to listOf("*").pv()))
        assertEquals(1, introduced(template("{}"), wide).size)
    }
}
