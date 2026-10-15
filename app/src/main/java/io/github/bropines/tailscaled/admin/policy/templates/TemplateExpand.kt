package io.github.bropines.tailscaled.admin.policy.templates

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Placeholders: how a parameter's value gets into a fragment. Only strings carry them, written
 * `{{id}}` or `{{id|join}}`; where the string stands decides what it becomes (the rules are
 * [Placeholders.expandItems], [Placeholders.expandValue] and [Placeholders.expandKey], and the
 * README says the same for people). Nothing else of a template is ever substituted: a value
 * comes from the form, never from another value.
 */

/** A parameter's value as the form holds it: items (a single parameter has at most one), or a switch. */
sealed class ParamValue {
    data class Items(val items: List<String>) : ParamValue()
    data class Flag(val on: Boolean) : ParamValue()

    /** Whether a condition `param:<id>` holds: a switch that is on, a list that is not empty. */
    val isSet: Boolean
        get() = when (this) {
            is Items -> items.isNotEmpty()
            is Flag -> on
        }

    /** The items, a switch as its one word. */
    val words: List<String>
        get() = when (this) {
            is Items -> items
            is Flag -> listOf(on.toString())
        }
}

/** One `{{...}}` in a string: the parameter, whether it is joined, where it stands. */
data class Placeholder(val id: String, val join: Boolean, val range: IntRange)

/** A placeholder the form cannot fill: an unknown parameter, a list where one value must stand. */
class ExpandException(message: String) : IllegalArgumentException(message)

/** The form's values and the parameters they belong to: what a placeholder is expanded from. */
class Bindings(private val params: Map<String, TemplateParam>, private val values: Map<String, ParamValue>) {

    fun param(id: String): TemplateParam = params[id] ?: throw ExpandException("no parameter \"$id\"")

    fun value(id: String): ParamValue {
        val p = param(id)
        return values[id] ?: if (p.kind == ParamKind.BOOL) ParamValue.Flag(false) else ParamValue.Items(emptyList())
    }

    /** Whether [id] can stand for more than one item: a multi parameter. */
    fun isList(id: String): Boolean = param(id).let { it.multi && it.kind != ParamKind.BOOL }
}

object Placeholders {

    /** `{{id}}` and `{{id|join}}`; ids are lowercase words with `_` and `-`. */
    private val PATTERN = Regex("""\{\{([a-z][a-z0-9_-]*)(\|join)?\}\}""")

    /** What `|join` puts between items: the separator of a port list. */
    const val JOIN = ","

    fun find(s: String): List<Placeholder> = PATTERN.findAll(s).map { Placeholder(it.groupValues[1], it.groupValues[2].isNotEmpty(), it.range) }.toList()

    /** Whether [s] has a `{{` or `}}` that is not part of a placeholder: a mistake in the file. */
    fun hasStray(s: String): Boolean = PATTERN.replace(s, "").let { "{{" in it || "}}" in it }

    /** [s] is exactly one placeholder (`"{{p}}"`, not `"x{{p}}"`). */
    fun whole(s: String): Placeholder? = find(s).singleOrNull()?.takeIf { it.range.first == 0 && it.range.last == s.lastIndex }

    /**
     * A string that is an item of an array, as items:
     * - one whole placeholder: the parameter's items spliced in, none when it is empty; a switch
     *   gives one boolean;
     * - text with placeholders: one string per combination of the items of the placeholders
     *   that are not `|join`ed, the first placeholder varying slowest; none when one of them is
     *   empty. A `|join`ed one, or a switch, is one word.
     * A string without placeholders is itself.
     */
    fun expandItems(s: String, b: Bindings): List<JsonElement> {
        val found = find(s)
        if (found.isEmpty()) return listOf(JsonPrimitive(s))
        whole(s)?.takeIf { !it.join }?.let { p ->
            return when (val v = b.value(p.id)) {
                is ParamValue.Flag -> listOf(JsonPrimitive(v.on))
                is ParamValue.Items -> v.items.map(::JsonPrimitive)
            }
        }
        // Each placeholder's choices: its items one by one, or its single joined word.
        val choices = found.map { p ->
            val v = b.value(p.id)
            if (p.join || v is ParamValue.Flag) listOf(v.words.joinToString(JOIN)) else v.words
        }
        if (choices.any { it.isEmpty() }) return emptyList()
        var combos = listOf(emptyList<String>())
        for (c in choices) combos = combos.flatMap { prefix -> c.map { prefix + it } }
        return combos.map { picked -> JsonPrimitive(fill(s, found, picked)) }
    }

    /**
     * A string that is an object's value:
     * - one whole placeholder: a list parameter's items as an array, a single one's item, a
     *   switch's boolean; null — the field is left out — when there is nothing to write;
     * - text with placeholders: one string; every placeholder in it is a single parameter, a
     *   switch, or `|join`ed (a list without `|join` is refused: it cannot be one value).
     */
    fun expandValue(s: String, b: Bindings): JsonElement? {
        val found = find(s)
        if (found.isEmpty()) return JsonPrimitive(s)
        whole(s)?.takeIf { !it.join }?.let { p ->
            return when (val v = b.value(p.id)) {
                is ParamValue.Flag -> JsonPrimitive(v.on)
                is ParamValue.Items -> when {
                    b.isList(p.id) -> v.items.takeIf { it.isNotEmpty() }?.let { items -> JsonArray(items.map(::JsonPrimitive)) }
                    else -> v.items.firstOrNull()?.let(::JsonPrimitive)
                }
            }
        }
        return JsonPrimitive(text(s, found, b))
    }

    /** A key of an object: one string, as text with placeholders in a value ([expandValue]). */
    fun expandKey(s: String, b: Bindings): String {
        val found = find(s)
        return if (found.isEmpty()) s else text(s, found, b)
    }

    private fun text(s: String, found: List<Placeholder>, b: Bindings): String {
        val words = found.map { p ->
            if (!p.join && b.isList(p.id)) throw ExpandException("{{${p.id}}} is a list; write {{${p.id}|join}} where one value stands")
            b.value(p.id).words.joinToString(JOIN)
        }
        return fill(s, found, words)
    }

    private fun fill(s: String, found: List<Placeholder>, words: List<String>): String = buildString {
        var at = 0
        found.forEachIndexed { i, p ->
            append(s, at, p.range.first)
            append(words[i])
            at = p.range.last + 1
        }
        append(s, at, s.length)
    }

    /** [e] in a value's place: objects and arrays expanded through, strings by [expandValue]; null when left out. */
    fun expand(e: JsonElement, b: Bindings): JsonElement? = when (e) {
        is JsonObject -> JsonObject(
            LinkedHashMap<String, JsonElement>().also { out ->
                e.forEach { (k, v) -> expand(v, b)?.let { out[expandKey(k, b)] = it } }
            },
        )
        is JsonArray -> JsonArray(e.flatMap { expandItem(it, b) })
        is JsonPrimitive -> if (e.isString) expandValue(e.content, b) else e
    }

    /** [e] as an array's item: zero, one or more items. */
    fun expandItem(e: JsonElement, b: Bindings): List<JsonElement> = when (e) {
        is JsonPrimitive -> if (e.isString) expandItems(e.content, b) else listOf(e)
        else -> listOfNotNull(expand(e, b))
    }

    /** The strings of [list] as items: what a check's and an access rule's lists become. */
    fun strings(list: List<String>, b: Bindings): List<String> =
        list.flatMap { expandItems(it, b) }.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
