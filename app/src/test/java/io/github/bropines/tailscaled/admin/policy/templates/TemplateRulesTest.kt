package io.github.bropines.tailscaled.admin.policy.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The file format: what parses and passes the rules, and each way a file is refused, with the reason. */
class TemplateRulesTest {

    /** A small valid template with [fields] put over its own (a JSON object's members, without braces). */
    private fun file(fields: String = "", fragments: String = """{"access": [{"src": ["{{who}}"], "dst": ["autogroup:internet"]}]}"""): String = """
        {
          "schema": 1, "id": "guests", "version": 2, "category": "guests",
          "title": {"en": "Guests", "ru": "Гости"},
          "description": {"en": "The internet only."},
          "params": [{"id": "who", "kind": "selector", "slot": "acl-src", "label": {"en": "Who"}, "required": true, "multi": true}],
          "fragments": $fragments
          ${if (fields.isEmpty()) "" else ", $fields"}
        }
    """.trimIndent()

    private fun parse(text: String) = TemplateSource.parse(text.encodeToByteArray())

    private fun refused(text: String, reason: String) {
        try {
            parse(text)
            fail("accepted, expected: $reason")
        } catch (e: TemplateFileException) {
            assertTrue("\"${e.message}\" does not say \"$reason\"", e.message.orEmpty().contains(reason))
        }
    }

    @Test
    fun aGoodFile() {
        val t = parse(file())
        assertEquals("guests", t.id)
        assertEquals(2, t.version)
        assertEquals(HeadscaleLevel.FULL, t.headscale.supported)
        assertEquals("Гости", t.title.pick("ru"))
        assertEquals("The internet only.", t.description.pick("ru"))
        assertEquals(ParamKind.SELECTOR, t.params.single().kind)
    }

    @Test
    fun headscaleSupportIsTrueFalseOrPartial() {
        assertEquals(HeadscaleLevel.NONE, parse(file(""""headscale": {"supported": false}""")).headscale.supported)
        assertEquals(HeadscaleLevel.PARTIAL, parse(file(""""headscale": {"supported": "partial", "note": {"en": "x"}}""")).headscale.supported)
        refused(file(""""headscale": {"supported": "maybe"}"""), "does not parse")
    }

    @Test
    fun newerOptionalFieldsAreIgnoredANewerSchemaIsNot() {
        parse(file(""""icon": "remote""""))
        refused(file().replace("\"schema\": 1", "\"schema\": 2"), "schema 2")
    }

    @Test
    fun brokenJson() {
        refused("{", "does not parse")
        refused(file().replace("\"category\": \"guests\",", "\"category\": \"guests\",,"), "does not parse")
        refused(file().replace("\"version\": 2", "\"version\": [2]"), "does not parse")
        refused(file().replace("\"kind\": \"selector\"", "\"kind\": \"script\""), "does not parse")
        refused("[]", "does not parse")
        refused("x".repeat(70 * 1024), "over")
    }

    @Test
    fun namesAndTexts() {
        refused(file().replace("\"id\": \"guests\"", "\"id\": \"Guests\""), "id \"Guests\"")
        refused(file().replace("\"category\": \"guests\"", "\"category\": \"Guests Now\""), "category")
        refused(file().replace("\"version\": 2", "\"version\": 0"), "version")
        refused(file().replace("\"title\": {\"en\": \"Guests\", \"ru\": \"Гости\"}", "\"title\": {\"ru\": \"Гости\"}"), "title has no English")
        refused(file(""""docs": [{"title": {"en": "x"}, "url": "http://tailscale.com/kb"}]"""), "docs url")
        refused(file(""""docs": [{"title": {"en": "x"}, "url": "https://evil.example/kb"}]"""), "docs url")
        refused(file(""""docs": [{"title": {"en": "x"}, "url": "https://tailscale.com.evil.example/kb"}]"""), "docs url")
        parse(file(""""docs": [{"title": {"en": "x"}, "url": "https://tailscale.com/kb/1337/policy-syntax"}]"""))
    }

    @Test
    fun parameters() {
        fun withParam(p: String) = file().replace(
            """"params": [{"id": "who", "kind": "selector", "slot": "acl-src", "label": {"en": "Who"}, "required": true, "multi": true}]""",
            """"params": [{"id": "who", "kind": "selector", "slot": "acl-src", "label": {"en": "Who"}, "required": true, "multi": true}, $p]""",
        )
        refused(withParam("""{"id": "who", "kind": "text", "label": {"en": "x"}}"""), "parameter \"who\" twice")
        refused(withParam("""{"id": "Bad", "kind": "text", "label": {"en": "x"}}"""), "parameter id")
        refused(withParam("""{"id": "s", "kind": "selector", "label": {"en": "x"}}"""), "needs a slot")
        refused(withParam("""{"id": "s", "kind": "selector", "slot": "nowhere", "label": {"en": "x"}}"""), "slot \"nowhere\"")
        refused(withParam("""{"id": "s", "kind": "selector", "slot": "acl-src", "allow": ["robots"], "label": {"en": "x"}}"""), "allow")
        refused(withParam("""{"id": "c", "kind": "choice", "label": {"en": "x"}}"""), "needs options")
        refused(withParam("""{"id": "c", "kind": "choice", "label": {"en": "x"}, "options": [{"value": "a", "label": {"en": "A"}}], "default": "b"}"""), "default is not an option")
        refused(withParam("""{"id": "f", "kind": "bool", "multi": true, "label": {"en": "x"}}"""), "not a list")
        refused(withParam("""{"id": "f", "kind": "bool", "label": {"en": "x"}, "default": "yes"}"""), "default does not fit")
        refused(withParam("""{"id": "p", "kind": "port", "label": {"en": "x"}, "default": ["22"]}"""), "default does not fit")
        refused(withParam("""{"id": "t", "kind": "text", "format": "regex", "label": {"en": "x"}}"""), "format")
        parse(withParam("""{"id": "p", "kind": "port", "multi": true, "label": {"en": "x"}, "default": ["22", "443"]}"""))
        parse(withParam("""{"id": "c", "kind": "choice", "label": {"en": "x"}, "options": [{"value": "a", "label": {"en": "A"}}], "default": "a"}"""))
    }

    @Test
    fun fragments() {
        refused(file(fragments = """{"derpMap": {}}"""), "unknown section \"derpMap\"")
        refused(file(fragments = """{"acls": {}}"""), "acls is not a list")
        refused(file(fragments = """{"groups": []}"""), "groups is not an object")
        refused(file(fragments = """{"groups": {"group:x": "alice@"}}"""), "is not a list")
        refused(file(fragments = """{"hosts": {"nas": ["10.0.0.1"]}}"""), "hosts.nas is not a string")
        refused(file(fragments = """{"autoApprovers": {"owners": []}}"""), "autoApprovers.owners")
        refused(file(fragments = """{"acls": [5]}"""), "is not an object")
        refused(file(fragments = """{"access": [{"src": ["{{who}}"]}]}"""), "access: dst is not a list")
        refused(file(fragments = """{"access": [{"src": ["{{who}}"], "dst": ["x"], "ip": ["22"]}]}"""), "unknown field \"ip\"")
        refused(file(fragments = """{"grants": [{"generate": "pair-test", "src": [], "dst": []}]}"""), "generate")
        refused(file(fragments = """{"tests": [{"generate": "pair-test", "src": [], "dst": []}]}"""), "port is not a string")
        refused(file(fragments = """{}"""), "write nothing")
        refused(file(fragments = "5"), "neither an object nor a list")
        parse(file(fragments = """[{"access": [{"src": ["{{who}}"], "dst": ["*"]}]}, {"when": "param:who", "tagOwners": {"tag:x": ["{{who}}"]}}]"""))
    }

    @Test
    fun placeholdersAndConditions() {
        refused(file(fragments = """{"access": [{"src": ["{{whom}}"], "dst": ["*"]}]}"""), "{{whom}}, which is not a parameter")
        refused(file(fragments = """{"access": [{"src": ["{{who"], "dst": ["*"]}]}"""), "not a placeholder")
        refused(file(fragments = """{"groups": {"group:{{who}}": ["a@"]}}"""), "is a list; write {{who|join}}")
        refused(file(fragments = """{"grants": [{"src": ["*"], "dst": ["*"], "ip": ["*"], "note": "for {{who}}"}]}"""), "is a list")
        refused(file(fragments = """{"access": [{"when": "param:nobody", "src": ["*"], "dst": ["*"]}]}"""), "names \"param:nobody\"")
        refused(file(fragments = """{"access": [{"when": "check:x", "src": ["*"], "dst": ["*"]}]}"""), "names \"check:x\"")
        refused(file(fragments = """{"access": [{"when": "a &&", "src": ["*"], "dst": ["*"]}]}"""), "condition")
        refused(file(fragments = """{"access": [{"comment": "plain", "src": ["*"], "dst": ["*"]}]}"""), "comment is not text in languages")
        refused(file(""""suggest": [{"page": "dns", "slot": "empty"}]"""), "suggest page")
        // Joined, or per item in a list, a list may stand anywhere.
        parse(file(fragments = """{"grants": [{"src": ["{{who}}:x"], "dst": ["*"], "ip": ["*"], "note": "for {{who|join}}"}]}"""))
    }

    @Test
    fun checks() {
        refused(file(""""checks": [{"id": "a", "kind": "access-open", "src": ["{{who}}"]}]"""), "src, dst and ports are needed")
        refused(file(""""checks": [{"id": "a", "kind": "grant-app"}]"""), "cap is needed")
        refused(file(""""checks": [{"id": "a", "kind": "node-attr", "attrs": []}]"""), "attrs are needed")
        refused(file(""""checks": [{"id": "a", "kind": "grant-app", "cap": "x"}, {"id": "a", "kind": "grant-app", "cap": "y"}]"""), "check \"a\" twice")
        refused(file(""""checks": [{"id": "a", "kind": "telemetry"}]"""), "does not parse")
    }
}
