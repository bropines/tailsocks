package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.TailnetView
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
 * Headscale — the result always a policy that parses. The renders are kept under build/, the
 * Headscale ones for `headscale policy check` on the test tailnet.
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

    /**
     * What a person would pick where [sample] would make a test that pins nothing: an address
     * inside the sample subnet, a guest and a device that stays closed to them, a version. On
     * Headscale the testnet's users, and no test of tagged devices: the testnet has none, and
     * Headscale refuses a test of servers, or of a route through routers, that do not exist.
     */
    private fun picked(id: String, headscale: Boolean): Map<String, ParamValue> {
        val guest = if (headscale) "bob@" else "mom@example.com"
        val other = if (headscale) "tester@" else "alice@example.com"
        return when (id) {
            "subnet-router" -> if (headscale) mapOf("tester" to TemplateTestKit.items(), "probe" to TemplateTestKit.items())
            else mapOf("tester" to TemplateTestKit.items(guest), "probe" to TemplateTestKit.items("10.0.0.10"))
            "guests-exit-nodes" -> mapOf("guests" to TemplateTestKit.items(guest), "tester" to TemplateTestKit.items(guest), "closed" to TemplateTestKit.items(other))
            "posture-tailscale-version" -> mapOf("version" to TemplateTestKit.items("1.98"))
            "app-connector" -> mapOf("name" to TemplateTestKit.items("GitHub"), "domains" to TemplateTestKit.items("github.com", "*.github.com"))
            "ssh-admins" -> if (headscale) mapOf("test" to ParamValue.Flag(false)) else emptyMap()
            else -> emptyMap()
        }
    }

    private val renders = File("build/policy-templates-rendered").also { File(it, "tailscale").mkdirs() }

    @Test
    fun everyTemplateRendersOnBothBackends() {
        for (t in TemplateTestKit.all()) for (headscale in listOf(false, true)) {
            val defaults = TemplateEngine.defaults(t)
            val withSamples = defaults + t.params.filter { !defaults.getValue(it.id).isSet }.associate { it.id to sample(it, headscale) } + picked(t.id, headscale)
            // On Headscale the console knows the devices: a generated test skips sides that have none, as on the testnet.
            val view = if (headscale) TESTNET else null
            for ((name, text) in TemplateTestKit.policies + ("headscale" to HEADSCALE_BASE)) {
                for (values in listOf(defaults, withSamples)) {
                    val p = TemplateEngine.plan(t, values, TemplateContext(text, headscale, view))
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
                    if (!headscale && name == "grants" && values === withSamples) File(renders, "tailscale/${t.id}.hujson").writeText(out)
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
        /** The testnet's devices: untagged, of `tester@` and `bob@`. */
        val TESTNET = TailnetView(
            listOf(
                ApiDevice(nodeId = "1", name = "tvemu1.tailnet.test", user = "tester@", addresses = listOf("100.64.0.1")),
                ApiDevice(nodeId = "2", name = "guest7.tailnet.test", user = "bob@", addresses = listOf("100.64.0.10")),
            ),
            null,
        )

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
