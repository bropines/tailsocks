package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.templates.TemplateRules.isSelector
import io.github.bropines.tailscaled.admin.policy.visual.SelectorKind
import io.github.bropines.tailscaled.admin.policy.visual.SelectorRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The set in policy-templates/, which the build copies into the APK and the site publishes:
 * every file listed with its sha256 and nothing left out, every template valid, and each one
 * written, with its defaults and sample values, into several policies on Tailscale and on
 * Headscale — the result always a policy that parses. The Headscale renders are kept under
 * build/ for `headscale policy check`.
 */
class BundledTemplatesTest {

    private val dir = TemplateTestKit.dir
    private val index = TemplateJson.decodeFromString(TemplateIndex.serializer(), File(dir, "index.json").readText())

    @Test
    fun theIndexListsEveryFileWithItsSha() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }!!.map { it.name }.sorted()
        assertEquals("files not in index.json (run policy-templates/update_index.py)", files, index.templates.map { it.file }.sorted())
        for (e in index.templates) {
            val bytes = File(dir, e.file).readBytes()
            assertEquals("${e.file}: sha256 (run policy-templates/update_index.py)", TemplateSource.sha256(bytes), e.sha256)
            val t = TemplateSource.parse(bytes)
            assertEquals(e.file, e.id, t.id)
            assertEquals(e.file, e.version, t.version)
            assertEquals(e.file, "${t.id}.json", e.file)
        }
    }

    @Test
    fun theSourceReadsTheWholeBundledSet() {
        val logs = mutableListOf<String>()
        val c = TemplateSource(TemplateFiles.dir(dir), File(System.getProperty("java.io.tmpdir"), "no-cache-${System.nanoTime()}"), null, AppIdentity("5.0.1-dev", 5000100), log = { logs += it }).local()
        assertEquals(logs.toString(), index.templates.map { it.id }, c.templates.map { it.template.id })
        assertTrue(c.templates.all { it.origin == TemplateOrigin.BUNDLED })
    }

    @Test
    fun everyTemplateHasRussianToo() {
        for (t in TemplateTestKit.all()) {
            val texts = listOf(t.title, t.description) + t.params.flatMap { listOfNotNull(it.label, it.help) + it.options.map { o -> o.label } } +
                t.checks.mapNotNull { it.note } + t.docs.map { it.title } + listOfNotNull(t.headscale.note)
            texts.forEach { assertTrue("${t.id}: $it has no Russian", !it["ru"].isNullOrBlank()) }
        }
    }

    /** A value for [p] where it has no default: what a person would pick. */
    private fun sample(p: TemplateParam, headscale: Boolean): ParamValue {
        // On Headscale a user of the testnet's, so that `headscale policy check` finds them.
        val user = if (headscale) "bob@" else "alice@example.com"
        val one: String = when (p.kind) {
            ParamKind.USER -> user
            ParamKind.GROUP -> "group:admins"
            ParamKind.TAG -> "tag:web"
            ParamKind.SELECTOR -> {
                val slot = TemplateRules.slotOf(p)!!
                val kinds = TemplateRules.allowedKinds(p).ifEmpty { SelectorRules.kinds(slot) }
                // On Headscale a group whose users have devices: a test it writes must resolve.
                when {
                    headscale && SelectorKind.GROUP in kinds -> "group:admins"
                    SelectorKind.TAG in kinds -> "tag:web"
                    SelectorKind.GROUP in kinds -> "group:admins"
                    else -> user
                }
            }
            ParamKind.PORT -> "443"
            ParamKind.HOST -> "100.64.0.10"
            ParamKind.CIDR -> "10.0.0.0/24"
            ParamKind.TEXT -> if (p.format == "login") user else "sample"
            ParamKind.CHOICE -> p.options.first().value
            ParamKind.BOOL -> return ParamValue.Flag(true)
        }
        return ParamValue.Items(listOf(one))
    }

    private val renders = File("build/policy-templates-rendered").also { it.mkdirs() }

    @Test
    fun everyTemplateRendersOnBothBackends() {
        for (t in TemplateTestKit.all()) for (headscale in listOf(false, true)) {
            val defaults = TemplateEngine.defaults(t)
            val withSamples = defaults + t.params.filter { !defaults.getValue(it.id).isSet }.associate { it.id to sample(it, headscale) }
            for ((name, text) in TemplateTestKit.policies + ("headscale" to HEADSCALE_BASE)) {
                for (values in listOf(defaults, withSamples)) {
                    val p = TemplateEngine.plan(t, values, TemplateContext(text, headscale))
                    if (headscale && t.headscale.supported == HeadscaleLevel.NONE) {
                        assertTrue("${t.id} on Headscale", TemplateIssue.NotOnHeadscale in p.issues)
                        continue
                    }
                    if (values === defaults && t.params.any { it.required && !defaults.getValue(it.id).isSet }) {
                        assertTrue("${t.id}: ${p.issues}", p.issues.any { it is TemplateIssue.Missing })
                        continue
                    }
                    assertTrue("${t.id} into $name (hs=$headscale): ${p.issues}", p.ready || p.issues == listOf(TemplateIssue.NothingToAdd))
                    val out = p.apply(text)
                    assertTrue("${t.id} into $name: does not parse\n$out", HuJson.check(out).isEmpty())
                    if (p.ready) assertTrue("${t.id} into $name wrote nothing", out != text)
                    if (headscale && name == "headscale" && values === withSamples) File(renders, "${t.id}.hujson").writeText(out)
                }
            }
        }
    }

    @Test
    fun selectorParametersHaveASlotThePickerKnows() {
        for (t in TemplateTestKit.all()) for (p in t.params.filter { it.kind.isSelector }) {
            assertTrue("${t.id}.${p.id}", TemplateRules.slotOf(p) != null)
        }
    }

    companion object {
        /** A Headscale policy of the testnet's shape: users `tester@` and `bob@`, a group of them, tags. */
        val HEADSCALE_BASE = """{
	"groups": {
		"group:admins": ["tester@", "bob@"],
	},
	"tagOwners": {
		"tag:web": ["tester@"],
		"tag:tv":  ["tester@"],
	},
	"acls": [
		{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:self:*"]},
	],
}
"""
    }
}
