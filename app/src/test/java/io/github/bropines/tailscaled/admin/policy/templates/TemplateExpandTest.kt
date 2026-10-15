package io.github.bropines.tailscaled.admin.policy.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The placeholder rules, as the README states them: alone or in text, in a list, a value or a key; joined; a switch. */
class TemplateExpandTest {

    private fun p(id: String, kind: ParamKind = ParamKind.SELECTOR, multi: Boolean = true) =
        TemplateParam(id, kind, mapOf("en" to id), multi = multi, slot = if (kind == ParamKind.SELECTOR) "acl-src" else null)

    private val params = listOf(
        p("hosts"), p("ports", ParamKind.PORT), p("one", multi = false), p("none"), p("on", ParamKind.BOOL, multi = false),
    ).associateBy { it.id }

    private fun bind(vararg v: Pair<String, ParamValue>) = Bindings(params, v.toMap())

    private val b = bind(
        "hosts" to ParamValue.Items(listOf("a", "b")),
        "ports" to ParamValue.Items(listOf("22", "80")),
        "one" to ParamValue.Items(listOf("x")),
        "on" to ParamValue.Flag(true),
    )

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    // ---- in a list ----

    @Test
    fun aWholePlaceholderInAListSplicesItsItems() {
        assertEquals(j("""["a", "b"]"""), Placeholders.expand(j("""["{{hosts}}"]"""), b))
        assertEquals(j("""["first", "a", "b", "last"]"""), Placeholders.expand(j("""["first", "{{hosts}}", "last"]"""), b))
        // Empty: nothing in its place, the list stays a list.
        assertEquals(j("""["first"]"""), Placeholders.expand(j("""["first", "{{none}}"]"""), b))
        assertEquals(j("[]"), Placeholders.expand(j("""["{{none}}"]"""), b))
    }

    @Test
    fun textInAListIsOneItemPerCombinationTheFirstVaryingSlowest() {
        assertEquals(j("""["a:22", "a:80", "b:22", "b:80"]"""), Placeholders.expand(j("""["{{hosts}}:{{ports}}"]"""), b))
        assertEquals(j("""["tag:x", "tag:x"]"""), Placeholders.expand(j("""["tag:{{one}}", "tag:{{one}}"]"""), b))
        // One side empty: no item at all.
        assertEquals(j("[]"), Placeholders.expand(j("""["{{none}}:{{ports}}"]"""), b))
    }

    @Test
    fun joinedIsOneStringAnywhere() {
        assertEquals(j("""["a:22,80", "b:22,80"]"""), Placeholders.expand(j("""["{{hosts}}:{{ports|join}}"]"""), b))
        assertEquals(j("""{"k": "22,80"}"""), Placeholders.expand(j("""{"k": "{{ports|join}}"}"""), b))
        assertEquals(j("""["22,80"]"""), Placeholders.expand(j("""["{{ports|join}}"]"""), b))
    }

    @Test
    fun aSwitchIsABooleanAloneAndAWordInText() {
        assertEquals(j("""[true]"""), Placeholders.expand(j("""["{{on}}"]"""), b))
        assertEquals(j("""{"k": true}"""), Placeholders.expand(j("""{"k": "{{on}}"}"""), b))
        assertEquals(j("""{"k": "is true"}"""), Placeholders.expand(j("""{"k": "is {{on}}"}"""), b))
        // Unset, it is false, not absent.
        assertEquals(j("""{"k": false}"""), Placeholders.expand(j("""{"k": "{{on}}"}"""), bind()))
    }

    // ---- as a value ----

    @Test
    fun aWholePlaceholderAsAValueIsAListASingleValueOrNothing() {
        assertEquals(j("""{"dst": ["a", "b"]}"""), Placeholders.expand(j("""{"dst": "{{hosts}}"}"""), b))
        assertEquals(j("""{"proto": "x"}"""), Placeholders.expand(j("""{"proto": "{{one}}"}"""), b))
        // Nothing to write: the field is left out, the others stay in their order.
        assertEquals(j("""{"a": 1, "c": 3}"""), Placeholders.expand(j("""{"a": 1, "b": "{{none}}", "c": 3}"""), b))
        assertEquals(j("""{"a": 1}"""), Placeholders.expand(j("""{"a": 1, "b": "{{one}}"}"""), bind()))
    }

    @Test
    fun textAsAValueTakesSinglesAndRefusesAList() {
        assertEquals(j("""{"note": "for x"}"""), Placeholders.expand(j("""{"note": "for {{one}}"}"""), b))
        assertThrows(ExpandException::class.java) { Placeholders.expand(j("""{"note": "for {{hosts}}"}"""), b) }
    }

    // ---- as a key ----

    @Test
    fun aKeyIsOneString() {
        assertEquals(j("""{"group:x": ["a", "b"]}"""), Placeholders.expand(j("""{"group:{{one}}": ["{{hosts}}"]}"""), b))
        assertEquals("22,80", Placeholders.expandKey("{{ports|join}}", b))
        assertThrows(ExpandException::class.java) { Placeholders.expandKey("{{hosts}}", b) }
    }

    @Test
    fun keyOrderAndOtherValuesAreKept() {
        val e = Placeholders.expand(j("""{"src": ["{{hosts}}"], "n": 5, "b": false, "z": null, "o": {"in": ["{{one}}"]}}"""), b)
        assertEquals(j("""{"src": ["a", "b"], "n": 5, "b": false, "z": null, "o": {"in": ["x"]}}"""), e)
        assertEquals(listOf("src", "n", "b", "z", "o"), (e as kotlinx.serialization.json.JsonObject).keys.toList())
    }

    // ---- reading them ----

    @Test
    fun findAndStray() {
        assertEquals(listOf("a", "b"), Placeholders.find("{{a}}-{{b|join}}").map { it.id })
        assertTrue(Placeholders.find("{{b|join}}").single().join)
        assertTrue(Placeholders.hasStray("{{a}} and {{"))
        assertTrue(Placeholders.hasStray("x }}"))
        assertTrue(Placeholders.hasStray("{{Upper}}"))
        assertFalse(Placeholders.hasStray("{{a}}:{{b}}"))
        assertNull(Placeholders.whole("x{{a}}"))
        assertEquals("a", Placeholders.whole("{{a}}")?.id)
    }

    @Test
    fun anUnknownParameterIsRefused() {
        assertThrows(ExpandException::class.java) { Placeholders.expand(j("""["{{ghost}}"]"""), b) }
    }

    @Test
    fun stringsKeepOnlyStrings() {
        assertEquals(listOf("a", "b", "lit"), Placeholders.strings(listOf("{{hosts}}", "{{on}}", "lit"), b))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), Placeholders.expand(j("""["{{hosts}}"]"""), b))
    }
}
