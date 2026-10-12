package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.insertAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the editor knows by name: posture attributes, app capabilities, node attributes, other tailnets. */
class CataloguesTest {

    // ---- posture attributes ----

    @Test
    fun aConditionIsReadByItsAttribute() {
        assertEquals("ip:publicAddress", PostureAttrs.keyOf("ip:publicAddress IN ['203.0.113.0/24']"))
        assertEquals("node:tsVersion", PostureAttrs.keyOf("node:tsVersion>='1.86'"))
        assertEquals("node:os", PostureAttrs.keyOf("  node:os == 'macos'"))
        assertEquals("custom:tier", PostureAttrs.keyOf("custom:tier IS SET"))

        assertEquals("ip:publicAddress", PostureAttrs.of("ip:publicAddress IN ['203.0.113.0/24']")?.key)
        assertEquals(FeatureStage.BETA, PostureAttrs.of("ip:publicAddress == '198.51.100.7'")?.stage)
        assertEquals("node:tsStateEncrypted", PostureAttrs.of("node:tsStateEncrypted == true")?.key)
        // An integration's attributes by its namespace.
        assertEquals("falcon:", PostureAttrs.of("falcon:ztaScore >= 70")?.key)
        assertEquals("Fleet", PostureAttrs.of("fleet:present == true")?.brand)
        assertEquals(PostureSource.CUSTOM, PostureAttrs.of("custom:tier == 'prod'")?.source)
        // A namespace alone, a misspelling or nothing at all is no attribute the editor knows.
        assertNull(PostureAttrs.of("falcon: == 1"))
        assertNull(PostureAttrs.of("node:osversion == '14'"))
        assertNull(PostureAttrs.of(""))
    }

    @Test
    fun everyExampleIsAboutItsOwnAttribute() {
        for (a in PostureAttrs.ALL) {
            assertEquals(a.example, a, PostureAttrs.of(a.example))
            if (a.source == PostureSource.INTEGRATION) assertTrue(a.key, a.keys.isNotEmpty() && a.brand != null)
        }
        // Each is written as Tailscale documents it: one prefix per source, keys unique.
        assertEquals(PostureAttrs.ALL.size, PostureAttrs.ALL.map { it.key }.distinct().size)
    }

    @Test
    fun aConditionFromTheCatalogueIsWrittenAsTyped() {
        val withPosture = PolicyEdits.putNamed(sample, Section.POSTURES, "posture:office", listOf("node:os IN ['macos', 'windows', 'linux']"))
        val p = PolicyModel.read(withPosture)!!.postures.single()
        val example = PostureAttrs.ALL.first { it.key == "ip:publicAddress" }.example
        val out = Definitions.setAssertions(withPosture, p, p.assertions + example)
        // Too long for one line now: the list goes one condition per line, and nothing else changes.
        assertEquals(withPosture.substringBefore("\"posture:office\""), out.substringBefore("\"posture:office\""))
        assertMeaning(PolicyTestKit.setAt(parsed(withPosture), listOf("postures", "posture:office"), json("[\"node:os IN ['macos', 'windows', 'linux']\", \"$example\"]")), out)
        assertEquals(listOf("node:os IN ['macos', 'windows', 'linux']", example), PolicyModel.read(out)!!.postures.single().assertions)
    }

    // ---- app capabilities ----

    @Test
    fun headscaleTakesOnlyTheCapabilitiesItLists() {
        assertFalse(KnownCaps.grantRefusedByHeadscale(PeerRelays.CAP))
        assertFalse(KnownCaps.grantRefusedByHeadscale("tailscale.com/cap/drive"))
        assertFalse(KnownCaps.grantRefusedByHeadscale("tailscale.com/cap/secrets"))
        assertTrue(KnownCaps.grantRefusedByHeadscale("tailscale.com/cap/pam"))
        assertTrue(KnownCaps.grantRefusedByHeadscale("tailscale.com/cap/unheard-of"))
        assertTrue(KnownCaps.grantRefusedByHeadscale("https://tailscale.com/cap/ingress"))
        // Another domain's capability is the owner's to define.
        assertFalse(KnownCaps.grantRefusedByHeadscale("example.com/cap/admin"))
        assertEquals(FeatureStage.ALPHA, KnownCaps.node("tailscale.com/visible-groups")?.stage)
        assertNull(KnownCaps.grant("tailscale.com/visible-groups"))
    }

    // ---- node attributes ----

    @Test
    fun netmapCachingIsAKnownAttribute() {
        val cache = KnownAttrs.of("cache-network-maps")!!
        val off = KnownAttrs.of("disable-cache-network-maps")!!
        assertTrue(cache.headscale && cache.headscaleEffect)
        // Headscale passes it on, but only Tailscale's control plane reads it.
        assertTrue(off.headscale)
        assertFalse(off.headscaleEffect)
        assertTrue(off in KnownAttrs.offered(headscale = false, attrs = emptyList()))
        assertFalse(off in KnownAttrs.offered(headscale = true, attrs = emptyList()))
        assertTrue("a file that has it shows it", off in KnownAttrs.offered(headscale = true, attrs = listOf("disable-cache-network-maps")))
        assertTrue(KnownAttrs.noEffect("disable-cache-network-maps", headscale = true))
        assertFalse(KnownAttrs.noEffect("cache-network-maps", headscale = true))
        assertFalse(KnownAttrs.refused("disable-cache-network-maps", headscale = true))
    }

    @Test
    fun theFasterStartTemplateWritesOneRule() {
        val preset = AttrTemplate.FASTER_START.preset
        assertEquals(listOf("autogroup:member"), preset.targets)
        val out = PolicyEdits.addRule(sample, Section.NODE_ATTRS, PolicyEdits.nodeAttrFields(preset.targets + "tag:homelab", preset.attrs))
        assertEquals("", window(sample, out).removed)
        assertMeaning(
            insertAt(parsed(sample), listOf("nodeAttrs"), 2, json("""{"target": ["autogroup:member", "tag:homelab"], "attr": ["cache-network-maps"]}""")),
            out,
        )
        // Headscale takes autogroup:member as a target.
        assertNull(SelectorRules.check("autogroup:member", SelectorSlot.NODE_TARGET, PolicyModel.read(sample)!!, headscale = true))
    }

    // ---- other tailnets ----

    private val shared = PolicyEdits.setOption(
        sample, Section.EXTERNAL_TAILNETS,
        PolicyValue.Raw("{\n\t\t\"partner\": {\n\t\t\t\"externalID\": \"T1234\",\n\t\t\t\"allowIncomingConnections\": true,\n\t\t\t\"allowExternalReferencesTo\": [\"group:prod-admin\"],\n\t\t},\n\t}"),
    ).let { t ->
        PolicyEdits.addRule(t, Section.GRANTS, PolicyEdits.grantFields(listOf("group://partner/eng"), listOf("tag:homelab"), listOf("*")))
    }

    @Test
    fun otherTailnetsAreReadAndTheirSelectorsChecked() {
        val m = PolicyModel.read(shared)!!
        val t = m.externalTailnets.single()
        assertEquals("partner", t.alias)
        assertEquals("T1234", t.externalId)
        assertEquals(true, t.allowIncoming)
        assertEquals(listOf("group:prod-admin"), t.allowReferencesTo)
        assertTrue(t.origin.editable)
        assertEquals(1, externalUses(m, "partner"))
        assertEquals("partner", Selectors.externalAlias("tag://partner/ci"))
        assertNull(Selectors.externalAlias("group:eng"))
        assertNull(SelectorRules.check("group://partner/eng", SelectorSlot.GRANT_SRC, m))
        assertEquals(SelectorProblem.UNDEFINED, SelectorRules.check("group://acme/eng", SelectorSlot.GRANT_SRC, m))
        assertEquals(SelectorProblem.NOT_ON_HEADSCALE, SelectorRules.check("group://partner/eng", SelectorSlot.GRANT_SRC, m, headscale = true))
        // Only a source: a grant cannot reach into another tailnet.
        assertEquals(SelectorProblem.NOT_ALLOWED_HERE, SelectorRules.check("tag://partner/ci", SelectorSlot.GRANT_DST, m))
        assertTrue(PolicyModel.read(sample)!!.externalTailnets.isEmpty())
    }
}
