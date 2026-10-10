package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuParseException
import io.github.bropines.tailscaled.admin.policy.HuToken
import io.github.bropines.tailscaled.admin.policy.HuTokenType

/*
 * The policy file as a tree that remembers where every part of it is written: offsets into the
 * text for each value, key and comma, so the visual editor can change one value by replacing
 * exactly its characters and leave every other byte — comments, alignment, blank lines — alone.
 *
 * HuJson's own tree keeps lines for messages; this one keeps spans for edits. Both read the same
 * tokens (HuJson.tokenize), and a text one accepts the other accepts.
 */

/** A value and the characters it is written in: [start] inclusive, [end] exclusive. */
sealed class SrcValue {
    abstract val start: Int
    abstract val end: Int
}

/** An object or an array: [open] and [close] are the offsets of its brackets. */
sealed class SrcContainer : SrcValue() {
    abstract val members: List<SrcMember>
    val open: Int get() = start
    val close: Int get() = end - 1
}

class SrcObject(override val start: Int, override val end: Int, override val members: List<SrcMember>) : SrcContainer() {
    /** The last member of that name, as Go's decoder (and HuJson) would read it. */
    fun field(name: String): SrcMember? = members.lastOrNull { it.key == name }
    operator fun get(name: String): SrcValue? = field(name)?.value
    fun indexOf(name: String): Int = members.indexOfLast { it.key == name }
    /** Keys written more than once: only the last counts, so the visual editor must not edit the others. */
    val duplicateKeys: Set<String> get() = members.groupingBy { it.key }.eachCount().filter { it.value > 1 }.keys.filterNotNull().toSet()
}

class SrcArray(override val start: Int, override val end: Int, override val members: List<SrcMember>) : SrcContainer() {
    operator fun get(index: Int): SrcValue? = members.getOrNull(index)?.value
}

class SrcString(override val start: Int, override val end: Int, val value: String) : SrcValue()
class SrcNumber(override val start: Int, override val end: Int, val raw: String) : SrcValue()
/** true, false or null. */
class SrcLiteral(override val start: Int, override val end: Int, val raw: String) : SrcValue()

/**
 * A field of an object ([key] set) or an item of an array ([key] null). [keyStart]/[keyEnd] span
 * the quoted key; for an item both equal the value's start. [comma] is the offset of the comma
 * that follows the value, -1 when there is none (the last member without a trailing comma).
 */
class SrcMember(val key: String?, val keyStart: Int, val keyEnd: Int, val value: SrcValue, val comma: Int) {
    val start: Int get() = keyStart
    /** Past the comma when there is one, else past the value. */
    val end: Int get() = if (comma >= 0) comma + 1 else value.end
}

/** The parsed file: [text] and its root object, with the tokens kept for comment lookups. */
class SourceTree private constructor(val text: String, val root: SrcObject, val tokens: List<HuToken>) {

    /** The value at [path], or null when any step is missing or of the wrong kind. */
    fun at(path: PolicyPath): SrcValue? {
        var node: SrcValue = root
        for (step in path.steps) {
            node = when (step) {
                is PathStep.Key -> (node as? SrcObject)?.get(step.name)
                is PathStep.Index -> (node as? SrcArray)?.get(step.index)
            } ?: return null
        }
        return node
    }

    /** The member [path] names (its last step), with the container that holds it. */
    fun memberAt(path: PolicyPath): Pair<SrcContainer, SrcMember>? {
        val last = path.steps.lastOrNull() ?: return null
        val parent = at(path.parent()) as? SrcContainer ?: return null
        val m = when (last) {
            is PathStep.Key -> (parent as? SrcObject)?.field(last.name)
            is PathStep.Index -> (parent as? SrcArray)?.members?.getOrNull(last.index)
        } ?: return null
        return parent to m
    }

    /** The comments around one member, as the visual editor shows and moves them. */
    fun commentsOf(container: SrcContainer, index: Int): MemberComments = Trivia.comments(this, container, index)

    /** The source characters of [v], exactly as written. */
    fun slice(v: SrcValue): String = text.substring(v.start, v.end)

    companion object {
        /** The tree of [text], or a [HuParseException] naming the first problem. */
        fun parse(text: String): SourceTree {
            // HuJson.check reports unterminated strings and brackets with better positions than
            // a plain descent would; run it first so both parsers reject exactly the same texts.
            HuJson.check(text).firstOrNull()?.let { throw HuParseException(it) }
            val tokens = HuJson.tokenize(text)
            val root = Builder(text, tokens).document()
            return SourceTree(text, root, tokens)
        }

        fun parseOrNull(text: String): SourceTree? = try {
            parse(text)
        } catch (_: HuParseException) {
            null
        }
    }

    /** Recursive descent over the significant tokens; [HuJson.check] has already vouched for the grammar. */
    private class Builder(private val text: String, all: List<HuToken>) {
        private val tokens = all.filter { it.type != HuTokenType.SPACE && it.type != HuTokenType.COMMENT }
        private var pos = 0

        private fun at(c: Char): Boolean = tokens.getOrNull(pos)?.let { it.type == HuTokenType.PUNCT && text[it.start] == c } == true

        fun document(): SrcObject = value() as SrcObject

        private fun value(): SrcValue {
            val t = tokens[pos]
            return when {
                at('{') -> obj()
                at('[') -> array()
                t.type == HuTokenType.STRING || t.type == HuTokenType.KEY -> { pos++; SrcString(t.start, t.end, unquote(text, t.start, t.end)) }
                t.type == HuTokenType.NUMBER -> { pos++; SrcNumber(t.start, t.end, text.substring(t.start, t.end)) }
                else -> { pos++; SrcLiteral(t.start, t.end, text.substring(t.start, t.end)) }
            }
        }

        private fun obj(): SrcObject {
            val open = tokens[pos++].start
            val members = mutableListOf<SrcMember>()
            while (!at('}')) {
                val k = tokens[pos++]
                pos++ // ':'
                val v = value()
                val comma = if (at(',')) tokens[pos++].start else -1
                members += SrcMember(unquote(text, k.start, k.end), k.start, k.end, v, comma)
            }
            val close = tokens[pos++].start
            return SrcObject(open, close + 1, members)
        }

        private fun array(): SrcArray {
            val open = tokens[pos++].start
            val members = mutableListOf<SrcMember>()
            while (!at(']')) {
                val v = value()
                val comma = if (at(',')) tokens[pos++].start else -1
                members += SrcMember(null, v.start, v.start, v, comma)
            }
            val close = tokens[pos++].start
            return SrcArray(open, close + 1, members)
        }
    }
}

/** The JSON string literal at [start, end) (quotes included), decoded. */
internal fun unquote(text: String, start: Int, end: Int): String {
    val sb = StringBuilder()
    var i = start + 1
    val stop = end - 1
    while (i < stop) {
        val c = text[i]
        if (c != '\\' || i + 1 >= stop) { sb.append(c); i++; continue }
        when (val e = text[i + 1]) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'u' -> {
                val code = text.substring(i + 2, minOf(i + 6, stop)).takeIf { it.length == 4 }?.toIntOrNull(16)
                if (code != null) { sb.append(code.toChar()); i += 4 } else sb.append(e)
            }
            else -> sb.append(e)
        }
        i += 2
    }
    return sb.toString()
}

/** One step into the file: a field of an object or an item of an array. */
sealed class PathStep {
    data class Key(val name: String) : PathStep() {
        override fun toString() = name
    }
    data class Index(val index: Int) : PathStep() {
        override fun toString() = "[$index]"
    }
}

/** Where a value is: `acls[3].dst` is `PolicyPath.of("acls", 3, "dst")`. */
data class PolicyPath(val steps: List<PathStep>) {
    fun parent(): PolicyPath = PolicyPath(steps.dropLast(1))
    operator fun plus(name: String) = PolicyPath(steps + PathStep.Key(name))
    operator fun plus(index: Int) = PolicyPath(steps + PathStep.Index(index))
    val last: PathStep? get() = steps.lastOrNull()
    val isRoot: Boolean get() = steps.isEmpty()

    override fun toString(): String = steps.joinToString("") { if (it is PathStep.Key) ".${it.name}" else it.toString() }.removePrefix(".")

    companion object {
        val ROOT = PolicyPath(emptyList())

        /** Strings are keys, ints are indexes. */
        fun of(vararg steps: Any): PolicyPath = PolicyPath(steps.map {
            when (it) {
                is String -> PathStep.Key(it)
                is Int -> PathStep.Index(it)
                else -> throw IllegalArgumentException("a path step is a String or an Int, not $it")
            }
        })
    }
}
