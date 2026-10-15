package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit
import java.io.File

/** The repository's template set and the policies the template tests write into. */
object TemplateTestKit {

    /** policy-templates/ at the repository's root: the tests run in the app module's directory. */
    val dir: File = File("../policy-templates").absoluteFile.normalize()

    fun load(id: String): PolicyTemplate = TemplateSource.parse(File(dir, "$id.json").readBytes())

    /** Every template the bundled index lists. */
    fun all(): List<PolicyTemplate> {
        val index = TemplateJson.decodeFromString(TemplateIndex.serializer(), File(dir, "index.json").readText())
        return index.templates.map { load(it.id) }
    }

    fun items(vararg v: String) = ParamValue.Items(v.toList())

    fun plan(t: PolicyTemplate, text: String, values: Map<String, ParamValue> = emptyMap(), headscale: Boolean = false, view: TailnetView? = null): TemplatePlan =
        TemplateEngine.plan(t, TemplateEngine.defaults(t) + values, TemplateContext(text, headscale, view))

    /** What Apply writes into [text]. */
    fun apply(t: PolicyTemplate, text: String, values: Map<String, ParamValue> = emptyMap(), headscale: Boolean = false, view: TailnetView? = null): String =
        plan(t, text, values, headscale, view).also { check(it.ready) { "not ready: ${it.issues}" } }.apply(text)

    // ---- policies ----

    const val EMPTY = "{\n}\n"

    val groups = """
	"groups": {
		"group:admins":  ["alice@example.com"],
		"group:family":  ["mom@example.com", "dad@example.com"],
		"group:parents": ["mom@example.com"],
	},
	"tagOwners": {
		"tag:tv":    ["alice@example.com"],
		"tag:admin": ["alice@example.com"],
		"tag:web":   ["alice@example.com"],
	},"""

    /** A file of ACLs, tabs and trailing commas: members reach their own devices on SSH and HTTPS. */
    val acls = """{$groups
	"acls": [
		{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:self:22,443"]},
	],
}
"""

    /** The same as grants. */
    val grants = """{$groups
	"grants": [
		{"src": ["autogroup:member"], "dst": ["autogroup:self"], "ip": ["22", "443"]},
	],
}
"""

    /** Plain JSON, two spaces, no trailing commas, a section of ACLs. */
    val json = """{
  "groups": {
    "group:admins": ["alice@example.com"]
  },
  "tagOwners": {
    "tag:web": ["alice@example.com"]
  },
  "acls": [
    {
      "action": "accept",
      "src": ["group:admins"],
      "dst": ["*:*"]
    }
  ]
}
"""

    /** The author's real policy, anonymised. */
    val sample: String get() = PolicyTestKit.sample

    val policies: Map<String, String> get() = mapOf("empty" to EMPTY, "acls" to acls, "grants" to grants, "json" to json, "sample" to sample)
}
