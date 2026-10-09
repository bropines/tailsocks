package io.github.bropines.tailscaled.admin.policy

import java.math.BigDecimal
import java.util.Locale

/*
 * HuJSON — JSON with comments and trailing commas, the format of the policy file — read
 * without a library: a tokenizer the highlighter colours from, a parser that keeps line
 * numbers for the lint and the error hints, and a canonical form that compares two files by
 * what they say rather than how they are laid out.
 */

enum class HuTokenType { PUNCT, KEY, STRING, NUMBER, LITERAL, COMMENT, SPACE, ERROR }

/** [start] inclusive, [end] exclusive, in the text that was tokenized. */
data class HuToken(val type: HuTokenType, val start: Int, val end: Int)

/** Why a text is not a policy file yet, and where: 1-based [line] and [column]. */
enum class HuIssueKind {
    UNTERMINATED_STRING,
    UNTERMINATED_COMMENT,
    /** A bracket opened on [HuIssue.otherLine] is never closed. */
    UNCLOSED,
    /** A closing bracket that does not match the one opened on [HuIssue.otherLine]. */
    MISMATCHED,
    /** A character or token that has no place there; [HuIssue.text] is what was found. */
    UNEXPECTED,
    MISSING_COLON,
    MISSING_COMMA,
    NOT_AN_OBJECT,
    EMPTY,
}

data class HuIssue(val kind: HuIssueKind, val line: Int, val column: Int, val text: String = "", val otherLine: Int? = null)

class HuParseException(val issue: HuIssue) : Exception("${issue.kind} at ${issue.line}:${issue.column}")

sealed class HuNode {
    /** The 1-based line the value starts on. */
    abstract val line: Int
}

class HuObject(val fields: List<HuField>, override val line: Int) : HuNode() {
    /** The last field of that name wins, as in Go's decoder. */
    operator fun get(name: String): HuNode? = fields.lastOrNull { it.name == name }?.value
}

class HuField(val name: String, val value: HuNode, val line: Int)
class HuArray(val items: List<HuNode>, override val line: Int) : HuNode()
class HuString(val value: String, override val line: Int) : HuNode()
class HuNumber(val raw: String, override val line: Int) : HuNode()
/** true, false or null. */
class HuLiteral(val raw: String, override val line: Int) : HuNode()

object HuJson {

    private val LITERALS = setOf("true", "false", "null")

    /** Every character of [text] in exactly one token, in order; never throws. */
    fun tokenize(text: String): List<HuToken> {
        val out = ArrayList<HuToken>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            var j: Int
            val type: HuTokenType
            when {
                c.isWhitespace() -> {
                    j = i + 1
                    while (j < n && text[j].isWhitespace()) j++
                    type = HuTokenType.SPACE
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    j = text.indexOf('\n', i + 2).let { if (it < 0) n else it }
                    type = HuTokenType.COMMENT
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    j = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    type = HuTokenType.COMMENT
                }
                c == '"' -> {
                    // A string ends at its quote or, unterminated, at the end of its line.
                    j = i + 1
                    while (j < n && text[j] != '"' && text[j] != '\n') j += if (text[j] == '\\' && j + 1 < n && text[j + 1] != '\n') 2 else 1
                    if (j < n && text[j] == '"') j++
                    type = HuTokenType.STRING
                }
                c in "{}[]:," -> {
                    j = i + 1
                    type = HuTokenType.PUNCT
                }
                c == '-' || c.isDigit() -> {
                    j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] in ".+-")) j++
                    type = HuTokenType.NUMBER
                }
                c.isLetter() -> {
                    j = i + 1
                    while (j < n && text[j].isLetterOrDigit()) j++
                    type = if (text.substring(i, j) in LITERALS) HuTokenType.LITERAL else HuTokenType.ERROR
                }
                else -> {
                    j = i + 1
                    type = HuTokenType.ERROR
                }
            }
            out += HuToken(type, i, j)
            i = j
        }
        // A string followed by a colon, past spaces and comments, is a key.
        for (k in out.indices) {
            if (out[k].type != HuTokenType.STRING) continue
            var m = k + 1
            while (m < out.size && (out[m].type == HuTokenType.SPACE || out[m].type == HuTokenType.COMMENT)) m++
            if (m < out.size && out[m].type == HuTokenType.PUNCT && text[out[m].start] == ':') out[k] = out[k].copy(type = HuTokenType.KEY)
        }
        return out
    }

    /** Line starts, for turning offsets into 1-based lines and columns. */
    class Lines(text: String) {
        private val starts: IntArray = buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()

        val count: Int get() = starts.size

        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo + 1
        }

        fun columnOf(offset: Int): Int = offset - starts[lineOf(offset) - 1] + 1

        /** The offset where 1-based [line] starts, clamped to the text. */
        fun startOf(line: Int): Int = starts[(line - 1).coerceIn(0, starts.size - 1)]
    }

    /**
     * What stops [text] from being a policy file, cheapest first: unterminated strings and
     * comments, then the brackets' balance, then the grammar. Empty when it parses; otherwise
     * the first problem, which is the one worth fixing first.
     */
    fun check(text: String): List<HuIssue> {
        val tokens = tokenize(text)
        val lines = Lines(text)
        fun issue(kind: HuIssueKind, at: Int, found: String = "", other: Int? = null) =
            listOf(HuIssue(kind, lines.lineOf(at), lines.columnOf(at), found, other))

        for (t in tokens) {
            when (t.type) {
                HuTokenType.STRING, HuTokenType.KEY ->
                    if (t.end - t.start < 2 || text[t.end - 1] != '"' || escapedQuote(text, t.end - 1)) return issue(HuIssueKind.UNTERMINATED_STRING, t.start)
                HuTokenType.COMMENT ->
                    if (text.startsWith("/*", t.start) && (t.end - t.start < 4 || !text.startsWith("*/", t.end - 2))) return issue(HuIssueKind.UNTERMINATED_COMMENT, t.start)
                HuTokenType.ERROR -> return issue(HuIssueKind.UNEXPECTED, t.start, text.substring(t.start, t.end).take(20))
                else -> Unit
            }
        }
        val stack = ArrayDeque<Pair<Char, Int>>()
        for (t in tokens) {
            if (t.type != HuTokenType.PUNCT) continue
            when (val c = text[t.start]) {
                '{', '[' -> stack.addLast(c to t.start)
                '}', ']' -> {
                    val open = stack.removeLastOrNull() ?: return issue(HuIssueKind.UNEXPECTED, t.start, c.toString())
                    if ((open.first == '{') != (c == '}')) return issue(HuIssueKind.MISMATCHED, t.start, c.toString(), lines.lineOf(open.second))
                }
            }
        }
        stack.lastOrNull()?.let { return issue(HuIssueKind.UNCLOSED, it.second, it.first.toString(), lines.lineOf(it.second)) }
        return try {
            parse(text)
            emptyList()
        } catch (e: HuParseException) {
            listOf(e.issue)
        }
    }

    private fun escapedQuote(text: String, quoteAt: Int): Boolean {
        var backslashes = 0
        var k = quoteAt - 1
        while (k >= 0 && text[k] == '\\') { backslashes++; k-- }
        return backslashes % 2 == 1
    }

    /** The document, which must be one object, as the policy file is. */
    fun parse(text: String): HuObject {
        val p = Parser(text)
        val root = p.document()
        return root as? HuObject ?: throw HuParseException(HuIssue(HuIssueKind.NOT_AN_OBJECT, root.line, 1))
    }

    /** [text] parsed, or null when it does not parse. */
    fun parseOrNull(text: String): HuObject? = try {
        parse(text)
    } catch (e: HuParseException) {
        null
    }

    /**
     * The document's meaning as one string: keys sorted (the last of a repeated key wins),
     * numbers normalised, comments, commas and layout gone. Two files with the same canonical
     * form are the same policy.
     */
    fun canonical(node: HuNode): String = StringBuilder().also { appendCanonical(it, node) }.toString()

    /** Whether [a] and [b] say the same thing; false when either does not parse. */
    fun sameMeaning(a: String, b: String): Boolean {
        val x = parseOrNull(a) ?: return false
        val y = parseOrNull(b) ?: return false
        return canonical(x) == canonical(y)
    }

    private fun appendCanonical(sb: StringBuilder, node: HuNode) {
        when (node) {
            is HuObject -> {
                sb.append('{')
                node.fields.associateBy { it.name }.toSortedMap().entries.forEachIndexed { i, (name, f) ->
                    if (i > 0) sb.append(',')
                    appendQuoted(sb, name)
                    sb.append(':')
                    appendCanonical(sb, f.value)
                }
                sb.append('}')
            }
            is HuArray -> {
                sb.append('[')
                node.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    appendCanonical(sb, item)
                }
                sb.append(']')
            }
            is HuString -> appendQuoted(sb, node.value)
            is HuNumber -> sb.append(runCatching { BigDecimal(node.raw).stripTrailingZeros().toPlainString() }.getOrDefault(node.raw))
            is HuLiteral -> sb.append(node.raw)
        }
    }

    private fun appendQuoted(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Recursive descent over the tokens, comments and spaces skipped, trailing commas allowed. */
    private class Parser(private val text: String) {
        private val tokens = tokenize(text).filter { it.type != HuTokenType.SPACE && it.type != HuTokenType.COMMENT }
        private val lines = Lines(text)
        private var pos = 0

        private fun peek(): HuToken? = tokens.getOrNull(pos)
        private fun at(t: HuToken, c: Char) = t.type == HuTokenType.PUNCT && text[t.start] == c
        private fun lineOf(t: HuToken) = lines.lineOf(t.start)

        private fun fail(kind: HuIssueKind, t: HuToken?): Nothing {
            val at = t?.start ?: text.length
            val found = t?.let { text.substring(it.start, it.end).take(20) } ?: ""
            throw HuParseException(HuIssue(kind, lines.lineOf(at), lines.columnOf(at), found))
        }

        fun document(): HuNode {
            if (tokens.isEmpty()) throw HuParseException(HuIssue(HuIssueKind.EMPTY, 1, 1))
            val v = value()
            peek()?.let { fail(HuIssueKind.UNEXPECTED, it) }
            return v
        }

        private fun value(): HuNode {
            val t = peek() ?: fail(HuIssueKind.UNEXPECTED, null)
            return when {
                at(t, '{') -> obj()
                at(t, '[') -> array()
                t.type == HuTokenType.STRING || t.type == HuTokenType.KEY -> { pos++; HuString(unquote(t), lineOf(t)) }
                t.type == HuTokenType.NUMBER -> {
                    pos++
                    val raw = text.substring(t.start, t.end)
                    if (!NUMBER.matches(raw)) fail(HuIssueKind.UNEXPECTED, t)
                    HuNumber(raw, lineOf(t))
                }
                t.type == HuTokenType.LITERAL -> { pos++; HuLiteral(text.substring(t.start, t.end), lineOf(t)) }
                else -> fail(HuIssueKind.UNEXPECTED, t)
            }
        }

        private fun obj(): HuObject {
            val open = tokens[pos++]
            val fields = mutableListOf<HuField>()
            while (true) {
                val t = peek() ?: fail(HuIssueKind.UNEXPECTED, null)
                if (at(t, '}')) { pos++; break }
                if (t.type != HuTokenType.KEY && t.type != HuTokenType.STRING) fail(HuIssueKind.UNEXPECTED, t)
                pos++
                val colon = peek()
                if (colon == null || !at(colon, ':')) fail(HuIssueKind.MISSING_COLON, colon)
                pos++
                fields += HuField(unquote(t), value(), lineOf(t))
                val next = peek() ?: fail(HuIssueKind.UNEXPECTED, null)
                when {
                    at(next, ',') -> pos++
                    at(next, '}') -> Unit
                    else -> fail(HuIssueKind.MISSING_COMMA, next)
                }
            }
            return HuObject(fields, lineOf(open))
        }

        private fun array(): HuArray {
            val open = tokens[pos++]
            val items = mutableListOf<HuNode>()
            while (true) {
                val t = peek() ?: fail(HuIssueKind.UNEXPECTED, null)
                if (at(t, ']')) { pos++; break }
                items += value()
                val next = peek() ?: fail(HuIssueKind.UNEXPECTED, null)
                when {
                    at(next, ',') -> pos++
                    at(next, ']') -> Unit
                    else -> fail(HuIssueKind.MISSING_COMMA, next)
                }
            }
            return HuArray(items, lineOf(open))
        }

        private fun unquote(t: HuToken): String {
            val sb = StringBuilder()
            var i = t.start + 1
            val end = t.end - 1
            while (i < end) {
                val c = text[i]
                if (c != '\\' || i + 1 >= end) { sb.append(c); i++; continue }
                when (val e = text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        val hex = text.substring(i + 2, minOf(i + 6, end))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            sb.append(code.toChar())
                            i += 4
                        } else sb.append(e)
                    }
                    else -> sb.append(e)
                }
                i += 2
            }
            return sb.toString()
        }

        companion object {
            private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
}

/** Strings of an array node; other kinds of items are skipped. Empty for anything but an array. */
internal fun HuNode?.strings(): List<String> = (this as? HuArray)?.items?.mapNotNull { (it as? HuString)?.value }.orEmpty()
