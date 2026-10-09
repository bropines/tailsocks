package io.github.bropines.tailscaled.admin.logs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One line of a before → after view of an audit entry. [path] names the part ("" for the whole value). */
sealed class AuditChange {
    abstract val path: String

    data class Changed(override val path: String, val before: String, val after: String) : AuditChange()
    data class Added(override val path: String, val value: String) : AuditChange()
    data class Removed(override val path: String, val value: String) : AuditChange()
    /** A multi-line text (a policy file): changed lines with a little context, the rest folded. */
    data class Text(override val path: String, val lines: List<TextLine>) : AuditChange()
}

data class TextLine(val kind: Kind, val text: String, val folded: Int = 0) {
    enum class Kind { SAME, ADDED, REMOVED, GAP }
}

/**
 * The difference between an audit entry's `old` and `new`, which the API gives as any JSON
 * value: a name, a flag, a list of tags or routes, an object of settings, a whole policy file.
 *
 * - Lists of plain values are compared as sets — what was added and what was taken away —
 *   since tags and routes have no order that means anything.
 * - Objects are compared key by key, nested paths written "a.b" and "a[2]".
 * - A text with line breaks is diffed line by line with [CONTEXT] lines around each change.
 * - Equal values give nothing.
 */
object AuditDiff {
    const val CONTEXT = 2
    private const val MAX_RENDER = 240
    private const val MAX_TEXT_LINES = 1200

    fun of(old: JsonElement?, new: JsonElement?): List<AuditChange> = diff("", old.orNull(), new.orNull())

    private fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this

    private fun diff(path: String, a: JsonElement?, b: JsonElement?): List<AuditChange> = when {
        a == null && b == null -> emptyList()
        a == null -> added(path, b!!)
        b == null -> removed(path, a)
        a == b -> emptyList()
        a is JsonPrimitive && b is JsonPrimitive -> {
            val x = a.content
            val y = b.content
            if (a.isString && b.isString && ('\n' in x || '\n' in y)) listOf(AuditChange.Text(path, lines(x, y)))
            else listOf(AuditChange.Changed(path, x, y))
        }
        a is JsonArray && b is JsonArray -> arrays(path, a, b)
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).flatMap { k -> diff(join(path, k), a[k].orNull(), b[k].orNull()) }
        else -> listOf(AuditChange.Changed(path, render(a), render(b)))
    }

    private fun added(path: String, v: JsonElement): List<AuditChange> = when {
        v is JsonObject && v.isNotEmpty() -> v.flatMap { (k, x) -> x.orNull()?.let { added(join(path, k), it) }.orEmpty() }
        v is JsonArray && v.all { it is JsonPrimitive } -> v.map { AuditChange.Added(path, render(it)) }
        else -> listOf(AuditChange.Added(path, render(v)))
    }

    private fun removed(path: String, v: JsonElement): List<AuditChange> = when {
        v is JsonObject && v.isNotEmpty() -> v.flatMap { (k, x) -> x.orNull()?.let { removed(join(path, k), it) }.orEmpty() }
        v is JsonArray && v.all { it is JsonPrimitive } -> v.map { AuditChange.Removed(path, render(it)) }
        else -> listOf(AuditChange.Removed(path, render(v)))
    }

    private fun arrays(path: String, a: JsonArray, b: JsonArray): List<AuditChange> {
        if (a.all { it is JsonPrimitive } && b.all { it is JsonPrimitive }) {
            val gone = a.filter { it !in b }.distinct().map { AuditChange.Removed(path, render(it)) }
            val came = b.filter { it !in a }.distinct().map { AuditChange.Added(path, render(it)) }
            return gone + came
        }
        return (0 until maxOf(a.size, b.size)).flatMap { i -> diff("$path[$i]", a.getOrNull(i).orNull(), b.getOrNull(i).orNull()) }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    /** A value as one line: a string as itself, anything else as compact JSON, cut. */
    fun render(v: JsonElement): String {
        val s = if (v is JsonPrimitive) v.content else v.toString()
        return if (s.length > MAX_RENDER) s.take(MAX_RENDER - 1) + "…" else s
    }

    /**
     * Line diff by longest common subsequence; unchanged runs longer than the context are
     * folded into one GAP line that says how many lines it hides.
     */
    fun lines(before: String, after: String): List<TextLine> {
        val x = before.lines()
        val y = after.lines()
        // The unchanged head and tail need no table; only the middle is compared.
        var head = 0
        while (head < x.size && head < y.size && x[head] == y[head]) head++
        var tail = 0
        while (tail < x.size - head && tail < y.size - head && x[x.size - 1 - tail] == y[y.size - 1 - tail]) tail++
        val raw = ArrayList<TextLine>(x.size + y.size)
        x.subList(0, head).forEach { raw += TextLine(TextLine.Kind.SAME, it) }
        raw += middle(x.subList(head, x.size - tail), y.subList(head, y.size - tail))
        x.subList(x.size - tail, x.size).forEach { raw += TextLine(TextLine.Kind.SAME, it) }
        return fold(raw)
    }

    private fun middle(x: List<String>, y: List<String>): List<TextLine> {
        val n = x.size
        val m = y.size
        // Past this a table costs more memory than a phone should spend on a log line.
        if (n > MAX_TEXT_LINES || m > MAX_TEXT_LINES) {
            return x.map { TextLine(TextLine.Kind.REMOVED, it) } + y.map { TextLine(TextLine.Kind.ADDED, it) }
        }
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (x[i] == y[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<TextLine>(n + m)
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && x[i] == y[j] -> { out += TextLine(TextLine.Kind.SAME, x[i]); i++; j++ }
                // What goes before what comes, as diffs read.
                i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += TextLine(TextLine.Kind.REMOVED, x[i]); i++ }
                else -> { out += TextLine(TextLine.Kind.ADDED, y[j]); j++ }
            }
        }
        return out
    }

    private fun fold(lines: List<TextLine>): List<TextLine> {
        val changed = lines.indices.filter { lines[it].kind != TextLine.Kind.SAME }
        if (changed.isEmpty()) return emptyList()
        val keep = BooleanArray(lines.size)
        for (c in changed) for (k in (c - CONTEXT).coerceAtLeast(0)..(c + CONTEXT).coerceAtMost(lines.lastIndex)) keep[k] = true
        val out = mutableListOf<TextLine>()
        var hidden = 0
        lines.forEachIndexed { idx, line ->
            if (keep[idx]) {
                if (hidden > 0) out += TextLine(TextLine.Kind.GAP, "", folded = hidden)
                hidden = 0
                out += line
            } else hidden++
        }
        if (hidden > 0) out += TextLine(TextLine.Kind.GAP, "", folded = hidden)
        return out
    }
}
