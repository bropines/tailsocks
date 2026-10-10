package io.github.bropines.tailscaled.admin.policy.visual

import java.util.Locale

/** A value the visual editor writes into the file. [Raw] is source text moved as it was written. */
sealed class PolicyValue {
    data class Str(val value: String) : PolicyValue()
    data class Num(val raw: String) : PolicyValue()
    data class Bool(val value: Boolean) : PolicyValue()
    data object Null : PolicyValue()
    data class Arr(val items: List<PolicyValue>) : PolicyValue()
    /** Fields in the order they are written. */
    data class Obj(val fields: List<Pair<String, PolicyValue>>) : PolicyValue()
    /** Already formatted source; written as is. */
    data class Raw(val text: String) : PolicyValue()

    val isScalar: Boolean get() = this is Str || this is Num || this is Bool || this is Null || (this is Raw && '\n' !in text)

    companion object {
        fun strings(items: List<String>) = Arr(items.map { Str(it) })
        fun obj(vararg fields: Pair<String, PolicyValue>) = Obj(fields.toList())
    }
}

fun String.pv(): PolicyValue = PolicyValue.Str(this)
fun List<String>.pv(): PolicyValue = PolicyValue.strings(this)
fun Boolean.pv(): PolicyValue = PolicyValue.Bool(this)

/**
 * How the file is laid out, read from the file itself so that what the visual editor adds looks
 * like what was already there: the indentation unit, whether the last member of a multi-line
 * list keeps its comma (HuJSON style) or not (JSON style), whether field values are aligned in a
 * column (`"src":    [...]`, as Tailscale's own formatter does), the separator in one-line lists,
 * and whether top-level sections are separated by blank lines.
 */
data class SourceStyle(
    val indentUnit: String = "\t",
    val trailingCommas: Boolean = true,
    val alignValues: Boolean = true,
    val inlineSeparator: String = " ",
    val blankBetweenSections: Boolean = true,
    val lineWidth: Int = 100,
    val tabWidth: Int = 4,
) {
    companion object {
        fun of(t: SourceTree): SourceStyle {
            val text = t.text
            var unit: String? = null
            var trailingYes = 0
            var trailingNo = 0
            var alignYes = 0
            var alignNo = 0
            var sepSpace = 0
            var sepNone = 0
            fun visit(c: SrcContainer) {
                val ms = c.members
                if (ms.isNotEmpty()) {
                    val lastLines = Trivia.lines(t, c, ms.lastIndex)
                    if (lastLines.ownLines) {
                        if (ms.last().comma >= 0) trailingYes++ else trailingNo++
                        if (unit == null) {
                            val outer = Trivia.indentAt(text, c.open)
                            if (lastLines.indent.startsWith(outer) && lastLines.indent.length > outer.length) unit = lastLines.indent.substring(outer.length)
                        }
                    } else if (ms.size >= 2) {
                        val gap = text.substring(ms[0].end, ms[1].start)
                        if (gap.isEmpty()) sepNone++ else if (gap == " ") sepSpace++
                    }
                }
                if (c is SrcObject) {
                    // Runs of one-line fields, each on its own line: aligned when their values share a column.
                    val oneLine = ms.indices.filter { i ->
                        val m = ms[i]
                        Trivia.lines(t, c, i).ownLines && text.indexOf('\n', m.value.start).let { it < 0 || it >= m.value.end }
                    }
                    if (oneLine.size >= 2) {
                        val keyLens = oneLine.map { ms[it].keyEnd - ms[it].keyStart }
                        val cols = oneLine.map { Trivia.column(text, ms[it].value.start, 4) }
                        val gaps = oneLine.map { ms[it].value.start - text.indexOf(':', ms[it].keyEnd) - 1 }
                        if (keyLens.distinct().size > 1) {
                            if (cols.distinct().size == 1 && gaps.any { it > 1 }) alignYes++ else if (gaps.all { it == 1 }) alignNo++
                        }
                    }
                }
                ms.forEach { (it.value as? SrcContainer)?.let(::visit) }
            }
            visit(t.root)
            val root = t.root.members
            val gaps = (1 until root.size).filter { Trivia.lines(t, t.root, it).ownLines }.map { i ->
                val between = text.substring(root[i - 1].end, root[i].start)
                between.count { it == '\n' } >= 2
            }
            return SourceStyle(
                indentUnit = unit ?: "\t",
                trailingCommas = trailingYes >= trailingNo,
                alignValues = alignYes >= alignNo,
                inlineSeparator = if (sepNone > sepSpace) "" else " ",
                blankBetweenSections = gaps.isEmpty() || gaps.count { it } * 2 >= gaps.size,
            )
        }
    }
}

/** Writes [PolicyValue]s as source text in a [SourceStyle]. */
internal class Renderer(private val style: SourceStyle) {

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * [v] written at [indent] (the indentation of the line it starts on) and [column] (where on
     * that line it starts). Lists of plain values stay on one line while they fit; objects go
     * one field per line unless [inlineObject] asks for `{"a": 1}` and it fits.
     */
    fun render(v: PolicyValue, indent: String, column: Int, inlineObject: Boolean = false): String = when (v) {
        is PolicyValue.Str -> quote(v.value)
        is PolicyValue.Num -> v.raw
        is PolicyValue.Bool -> v.value.toString()
        PolicyValue.Null -> "null"
        is PolicyValue.Raw -> v.text
        is PolicyValue.Arr -> array(v, indent, column)
        is PolicyValue.Obj -> obj(v, indent, column, inlineObject)
    }

    private fun fits(column: Int, s: String) = '\n' !in s && Trivia.width(s, style.tabWidth, column) + 1 <= style.lineWidth

    private fun inlineArray(v: PolicyValue.Arr, indent: String, column: Int): String? {
        if (!v.items.all { it.isScalar }) return null
        val s = v.items.joinToString(",${style.inlineSeparator}", "[", "]") { render(it, indent, column) }
        return s.takeIf { fits(column, it) }
    }

    private fun array(v: PolicyValue.Arr, indent: String, column: Int): String {
        if (v.items.isEmpty()) return "[]"
        inlineArray(v, indent, column)?.let { return it }
        val inner = indent + style.indentUnit
        val col = Trivia.width(inner, style.tabWidth)
        return buildString {
            append("[\n")
            v.items.forEachIndexed { i, item ->
                append(inner).append(render(item, inner, col))
                if (i < v.items.lastIndex || style.trailingCommas) append(',')
                append('\n')
            }
            append(indent).append(']')
        }
    }

    private fun obj(v: PolicyValue.Obj, indent: String, column: Int, inline: Boolean): String {
        if (v.fields.isEmpty()) return "{}"
        if (inline && v.fields.all { it.second.isScalar || it.second is PolicyValue.Arr }) {
            val s = v.fields.joinToString(",${style.inlineSeparator}", "{", "}") { (k, value) ->
                quote(k) + ": " + render(value, indent, column)
            }
            if (fits(column, s)) return s
        }
        val inner = indent + style.indentUnit
        val col = Trivia.width(inner, style.tabWidth)
        return buildString {
            append("{\n")
            fieldLines(v.fields, inner, col).forEachIndexed { i, line ->
                append(inner).append(line)
                if (i < v.fields.lastIndex || style.trailingCommas) append(',')
                append('\n')
            }
            append(indent).append('}')
        }
    }

    /** `"key": value` for each field, values aligned in a column when the style aligns them. */
    fun fieldLines(fields: List<Pair<String, PolicyValue>>, indent: String, column: Int): List<String> {
        val keys = fields.map { quote(it.first) + ":" }
        val plain = fields.mapIndexed { i, (_, value) -> render(value, indent, column + keys[i].length + 1) }
        if (!style.alignValues) return keys.mapIndexed { i, k -> "$k ${plain[i]}" }
        val oneLine = plain.map { '\n' !in it }
        val width = keys.filterIndexed { i, _ -> oneLine[i] }.maxOfOrNull { it.length } ?: 0
        return keys.mapIndexed { i, k ->
            if (!oneLine[i]) return@mapIndexed "$k ${plain[i]}"
            val pad = " ".repeat(width - k.length + 1)
            "$k$pad${render(fields[i].second, indent, column + width + 1)}"
        }
    }

    /** One field as written inside an object whose other values start at [alignColumn] (-1: no column to keep). */
    fun field(key: String, value: PolicyValue, indent: String, column: Int, alignColumn: Int): String {
        val k = quote(key) + ":"
        val keyEndCol = column + k.length
        val pad = if (alignColumn > keyEndCol) " ".repeat(alignColumn - keyEndCol) else " "
        val rendered = render(value, indent, keyEndCol + pad.length)
        return if ('\n' in rendered) "$k " + render(value, indent, keyEndCol + 1) else "$k$pad$rendered"
    }
}
