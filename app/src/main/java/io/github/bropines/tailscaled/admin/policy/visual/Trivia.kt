package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuToken
import io.github.bropines.tailscaled.admin.policy.HuTokenType

/**
 * The comments around one member, as the visual editor reads them.
 *
 * [note] is the comment written directly above the member, glued to it and to the previous member
 * (no blank line on either side). It describes the member: it is shown on its card, and it moves
 * and goes away with it. A top-level section's comment is always its note.
 *
 * [header] is a comment block directly above the member with a blank line over it, like
 * `// --- 2. DNS rules ---`, or one opening a list of several members. It heads a run of members
 * rather than this one: it is shown as a divider and stays where it is when the member below it
 * moves or goes.
 *
 * [trailing] is a comment after the member on its last line (`"tag:x": ["a"], // why`).
 */
data class MemberComments(
    val note: String?,
    /** Offset of the start of the note's first line; -1 without a note. */
    val noteStart: Int,
    val header: String?,
    val trailing: String?,
    /** [trailing] exactly as written, markers included, for moving it along. */
    val trailingRaw: String? = null,
)

/**
 * Where a member sits in the lines of the file. A member is on [ownLines] when nothing else
 * shares its lines: it starts its first line (after indentation and comments) and only its comma
 * and a comment follow it on its last. Such members are inserted and deleted as whole lines;
 * the others (`["a", "b"]`) by their characters.
 */
data class MemberLines(
    val ownLines: Boolean,
    /** Start of the member's first line. */
    val lineStart: Int,
    /** Past the newline that ends the member's last line (its comma and trailing comment included). */
    val lineEnd: Int,
    /** The indentation of the member's first line. */
    val indent: String,
)

/** Line and comment geometry over a [SourceTree]'s text and tokens. */
internal object Trivia {

    fun lineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    /** Index of the newline ending the line at [offset], or the text's length. */
    fun lineBreak(text: String, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    fun indentAt(text: String, offset: Int): String {
        val s = lineStart(text, offset)
        var e = s
        while (e < text.length && (text[e] == ' ' || text[e] == '\t')) e++
        return text.substring(s, e)
    }

    /** The line holding [offset] is only whitespace. */
    fun isBlankLine(text: String, offset: Int): Boolean {
        val s = lineStart(text, offset)
        val e = lineBreak(text, s)
        return (s until e).all { text[it].isWhitespace() }
    }

    /** Index of the token that contains [offset] (tokens cover the text without gaps). */
    fun tokenIndex(tokens: List<HuToken>, offset: Int): Int {
        var lo = 0
        var hi = tokens.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tokens[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Comment text without its markers, one line per source line, trimmed. */
    fun commentText(raw: String): String = when {
        raw.startsWith("//") -> raw.removePrefix("//").trim()
        raw.startsWith("/*") -> raw.removePrefix("/*").removeSuffix("*/").lines()
            .joinToString("\n") { it.trim().removePrefix("*").trim() }.trim()
        else -> raw.trim()
    }

    /** Where the gap before member [index] starts: past the previous member, or past the opening bracket. */
    private fun gapStart(c: SrcContainer, index: Int): Int = if (index == 0) c.open + 1 else c.members[index - 1].end

    fun comments(t: SourceTree, c: SrcContainer, index: Int): MemberComments {
        val text = t.text
        val tokens = t.tokens
        val m = c.members[index]
        val from = gapStart(c, index)
        val firstBreak = text.indexOf('\n', from).takeIf { it in from until m.start }
        val block = ArrayList<HuToken>()
        var blank = false
        if (firstBreak != null) {
            var k = tokenIndex(tokens, m.start) - 1
            while (k >= 0) {
                val tok = tokens[k]
                if (tok.end <= from) break
                if (tok.type == HuTokenType.COMMENT) {
                    if (tok.start < firstBreak) break // the previous member's trailing comment
                    block += tok
                } else if (tok.type == HuTokenType.SPACE) {
                    val s = maxOf(tok.start, firstBreak)
                    val breaks = (s until tok.end).count { text[it] == '\n' }
                    if (breaks >= 2) { blank = true; break }
                    if (tok.start <= firstBreak) break
                } else break
                k--
            }
        }
        block.reverse()
        val joined = block.takeIf { it.isNotEmpty() }?.joinToString("\n") { commentText(text.substring(it.start, it.end)) }
        // Top-level sections are set apart by blank lines by convention, so the comment over one
        // describes it. Inside a list, a comment over its first member, when there are others,
        // reads as a heading for the run as much as for that member: it stays put, like a header.
        val isHeader = c !== t.root && (blank || (index == 0 && c.members.size > 1))
        val note = if (!isHeader) joined else null
        val header = if (isHeader) joined else null
        val noteStart = if (note != null) lineStart(text, block.first().start) else -1
        val tail = trailing(t, m)
        return MemberComments(note, noteStart, header, tail?.let { commentText(it) }, tail)
    }

    /** The comment after [m] on its last line, as written. */
    private fun trailing(t: SourceTree, m: SrcMember): String? {
        var k = tokenIndex(t.tokens, m.end - 1) + 1
        while (k < t.tokens.size) {
            val tok = t.tokens[k]
            when (tok.type) {
                HuTokenType.SPACE -> if (t.text.indexOf('\n', tok.start).let { it in tok.start until tok.end }) return null
                HuTokenType.COMMENT -> return t.text.substring(tok.start, tok.end)
                else -> return null
            }
            k++
        }
        return null
    }

    fun lines(t: SourceTree, c: SrcContainer, index: Int): MemberLines {
        val text = t.text
        val m = c.members[index]
        val from = gapStart(c, index)
        val startsLine = text.indexOf('\n', from).let { it in from until m.start } &&
            significantBetween(t, lineStart(text, m.start), m.start).not()
        // The member ends its line when the next significant token is on a later line.
        var k = tokenIndex(t.tokens, m.end - 1) + 1
        var breakAt = -1
        while (k < t.tokens.size) {
            val tok = t.tokens[k]
            if (tok.type == HuTokenType.SPACE) {
                val nl = text.indexOf('\n', tok.start)
                if (nl in tok.start until tok.end) { breakAt = nl; break }
            } else if (tok.type != HuTokenType.COMMENT) break
            k++
        }
        val endsLine = breakAt >= 0
        val lineEnd = if (endsLine) breakAt + 1 else m.end
        return MemberLines(startsLine && endsLine, lineStart(text, m.start), lineEnd, indentAt(text, m.start))
    }

    /** Whether anything but spaces and comments lies in [from, to). */
    fun significantBetween(t: SourceTree, from: Int, to: Int): Boolean {
        if (from >= to) return false
        var k = tokenIndex(t.tokens, from)
        while (k < t.tokens.size && t.tokens[k].start < to) {
            val tok = t.tokens[k]
            if (tok.type != HuTokenType.SPACE && tok.type != HuTokenType.COMMENT) return true
            k++
        }
        return false
    }

    fun hasCommentBetween(t: SourceTree, from: Int, to: Int): Boolean {
        if (from >= to) return false
        var k = tokenIndex(t.tokens, from)
        while (k < t.tokens.size && t.tokens[k].start < to) {
            if (t.tokens[k].type == HuTokenType.COMMENT && t.tokens[k].end > from) return true
            k++
        }
        return false
    }

    /** Display column of [offset] on its line, tabs counted as [tabWidth]. */
    fun column(text: String, offset: Int, tabWidth: Int): Int {
        var col = 0
        for (i in lineStart(text, offset) until offset) col = if (text[i] == '\t') col + tabWidth - col % tabWidth else col + 1
        return col
    }

    fun width(s: String, tabWidth: Int, startColumn: Int = 0): Int {
        var col = startColumn
        for (ch in s) col = if (ch == '\t') col + tabWidth - col % tabWidth else col + 1
        return col
    }
}
