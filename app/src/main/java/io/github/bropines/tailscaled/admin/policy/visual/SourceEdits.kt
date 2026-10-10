package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuJson

/** A visual edit that cannot be made on this text: a missing path, a taken key, a wrong kind of value. */
class PolicyEditException(message: String) : IllegalArgumentException(message)

/**
 * Where a new member goes when the member before it and the member after it are separated by a
 * header comment (`// --- DNS ---`): right after the previous member, above the header, or right
 * above the next member, under the header. Without a header between them both are the same place.
 * At index 0, [AFTER_PREVIOUS] is the very top of the list, above a comment that opens it.
 */
enum class Anchor { AFTER_PREVIOUS, BEFORE_NEXT }

/** What an insertion writes: a new member rendered in the file's style, or a moved one's own text. */
private sealed class MemberSource
private class NewMember(val key: String?, val value: PolicyValue) : MemberSource()
private class MovedMember(val text: String) : MemberSource()

/**
 * The visual editor's changes to the policy file, each one a minimal text edit: the characters
 * of the value, member or key it concerns change, and every other byte of the file — comments,
 * alignment, blank lines, the order of everything else — stays as it was. New members are laid
 * out like their neighbours ([SourceStyle]). Every result is checked with [HuJson.check]; a text
 * that would not parse is never returned.
 *
 * Each function takes the whole text and returns the whole new text: policies are small, and
 * parsing again after each edit keeps every edit independent of the ones before it.
 */
object SourceEdits {

    /** Replace [start, end) with [text]. */
    data class Edit(val start: Int, val end: Int, val text: String)

    /** Field order the editor writes new fields of rules in, and the order of top-level sections. */
    val RULE_FIELD_ORDER = listOf(
        "action", "src", "srcPosture", "proto", "dst", "ip", "app", "via", "users", "checkPeriod", "acceptEnv",
        "recorder", "enforceRecorder", "target", "attr", "accept", "check", "deny",
    )
    val SECTION_ORDER = listOf(
        "groups", "hosts", "ipsets", "postures", "defaultSrcPosture", "tagOwners", "autoApprovers",
        "acls", "grants", "ssh", "nodeAttrs", "tests", "sshTests",
        "derpMap", "randomizeClientPort", "disableIPv4", "OneCGNATRoute",
    )

    /**
     * Run [edit] on [text] with Windows line endings turned into `\n` and back: the engine writes
     * `\n`, and a file saved with `\r\n` must not end up with both. Every visual edit goes through here.
     */
    fun preservingLineEndings(text: String, edit: (String) -> String): String {
        if ("\r\n" !in text) return edit(text)
        return edit(text.replace("\r\n", "\n")).replace("\r\n", "\n").replace("\n", "\r\n")
    }

    fun applyEdits(text: String, edits: List<Edit>): String {
        val sorted = edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.end })
        val sb = StringBuilder(text)
        var limit = Int.MAX_VALUE
        for (e in sorted) {
            check(e.end <= limit && e.start <= e.end) { "overlapping edits" }
            sb.replace(e.start, e.end, e.text)
            limit = e.start
        }
        return sb.toString()
    }

    internal fun tree(text: String): SourceTree = SourceTree.parseOrNull(text)
        ?: throw PolicyEditException("the policy does not parse; fix it in the JSON editor first")

    internal fun done(text: String, edits: List<Edit>): String {
        val out = applyEdits(text, edits)
        HuJson.check(out).firstOrNull()?.let { throw IllegalStateException("edit produced unparsable text: $it") }
        return out
    }

    private fun container(t: SourceTree, path: PolicyPath): SrcContainer =
        t.at(path) as? SrcContainer ?: throw PolicyEditException("no object or array at $path")

    // ---- set ----

    /** Replace the value at [path] with [value]. For lists prefer [setStrings], which keeps the items' comments. */
    fun set(text: String, path: PolicyPath, value: PolicyValue): String {
        val t = tree(text)
        val v = t.at(path) ?: throw PolicyEditException("nothing at $path")
        val style = SourceStyle.of(t)
        val rendered = Renderer(style).render(value, Trivia.indentAt(text, v.start), Trivia.column(text, v.start, style.tabWidth))
        if (rendered == t.slice(v)) return text
        return done(text, listOf(Edit(v.start, v.end, rendered)))
    }

    // ---- insert ----

    /** Insert [value] into the array at [arrayPath] so that it becomes item [index]; [note] becomes a comment above it. */
    fun insert(text: String, arrayPath: PolicyPath, index: Int, value: PolicyValue, anchor: Anchor = Anchor.AFTER_PREVIOUS, note: String? = null): String {
        val t = tree(text)
        val c = container(t, arrayPath) as? SrcArray ?: throw PolicyEditException("$arrayPath is not an array")
        if (index !in 0..c.members.size) throw PolicyEditException("index $index out of 0..${c.members.size}")
        return done(text, insertMember(t, c, index, NewMember(null, value), anchor, note?.let { noteLines(it) }, null))
    }

    fun append(text: String, arrayPath: PolicyPath, value: PolicyValue, note: String? = null): String {
        val size = (tree(text).at(arrayPath) as? SrcArray)?.members?.size ?: throw PolicyEditException("$arrayPath is not an array")
        return insert(text, arrayPath, size, value, Anchor.AFTER_PREVIOUS, note)
    }

    /**
     * Set field [key] of the object at [objectPath]: its value replaced when it exists, otherwise
     * a new field placed by [order] (after the last field that comes before it there), or last.
     */
    fun put(text: String, objectPath: PolicyPath, key: String, value: PolicyValue, order: List<String> = RULE_FIELD_ORDER, note: String? = null): String {
        val t = tree(text)
        val o = container(t, objectPath) as? SrcObject ?: throw PolicyEditException("$objectPath is not an object")
        if (o.field(key) != null) return set(text, objectPath + key, value)
        val ms = o.members
        val rank = order.indexOf(key)
        val index = if (rank < 0) ms.size else {
            val before = ms.indexOfLast { order.indexOf(it.key) in 0 until rank }
            if (before >= 0) before + 1 else ms.indexOfFirst { order.indexOf(it.key) > rank }.let { if (it < 0) ms.size else it }
        }
        return done(text, insertMember(t, o, index, NewMember(key, value), Anchor.AFTER_PREVIOUS, note?.let { noteLines(it) }, null))
    }

    /** Add top-level section [key] holding [empty] when the file has none; the text unchanged otherwise. */
    fun ensureSection(text: String, key: String, empty: PolicyValue): String {
        val t = tree(text)
        if (t.root.field(key) != null) return text
        return put(text, PolicyPath.ROOT, key, empty, SECTION_ORDER)
    }

    private fun noteLines(note: String): List<String> = note.lines().map { if (it.isBlank()) "//" else "// ${it.trim()}" }

    /**
     * The edits that insert one member. [body] decides what is written: a [PolicyValue] rendered
     * here, or (for a move) source text kept as it was. [note] lines are written above it,
     * [trailing] after its comma.
     */
    private fun insertMember(
        t: SourceTree,
        c: SrcContainer,
        index: Int,
        member: MemberSource,
        anchor: Anchor,
        note: List<String>?,
        trailing: String?,
    ): List<Edit> {
        val text = t.text
        val style = SourceStyle.of(t)
        val r = Renderer(style)
        val ms = c.members
        val isRoot = c === t.root
        val tail = trailing?.let { " $it" }.orEmpty()

        fun body(indent: String, column: Int, inlineObject: Boolean): String = when (member) {
            is MovedMember -> member.text
            is NewMember ->
                if (member.key == null) r.render(member.value, indent, column, inlineObject)
                else r.field(member.key, member.value, indent, column, alignColumn(t, c, style))
        }
        val scalar = member is NewMember && member.value.isScalar

        fun notes(indent: String) = note.orEmpty().joinToString("") { "$indent$it\n" }

        if (ms.isEmpty()) {
            val interior = text.substring(c.open + 1, c.close)
            val parentIndent = Trivia.indentAt(text, c.open)
            val childIndent = parentIndent + style.indentUnit
            val blankInside = interior.isBlank()
            val oneLine = c is SrcArray && scalar && blankInside && note == null && trailing == null
            if (oneLine) return listOf(Edit(c.open + 1, c.close, body(parentIndent, Trivia.column(text, c.open + 1, style.tabWidth), false)))
            val col = Trivia.width(childIndent, style.tabWidth)
            val member = notes(childIndent) + childIndent + body(childIndent, col, false) + (if (style.trailingCommas) "," else "") + tail + "\n"
            return when {
                blankInside -> listOf(Edit(c.open + 1, c.close, "\n" + member + parentIndent))
                text.substring(Trivia.lineStart(text, c.close), c.close).isBlank() -> {
                    val at = Trivia.lineStart(text, c.close)
                    listOf(Edit(at, at, member))
                }
                else -> listOf(Edit(c.close, c.close, "\n" + member + parentIndent))
            }
        }

        val afterPrevious = index > 0 && (anchor == Anchor.AFTER_PREVIOUS || index == ms.size)
        val ref = if (afterPrevious) index - 1 else index
        val refLines = Trivia.lines(t, c, ref)
        // Objects written on one line in the file ({"action": ..., "src": ...}) get siblings written the same way.
        val inlineSiblings = ms.all { m -> m.value is SrcObject && text.indexOf('\n', m.value.start).let { it < 0 || it >= m.value.end } }

        if (refLines.ownLines) {
            val indent = refLines.indent
            val col = Trivia.width(indent, style.tabWidth)
            val b = body(indent, col, inlineSiblings)
            val blank = isRoot && style.blankBetweenSections
            if (index == 0 && anchor == Anchor.AFTER_PREVIOUS) {
                // Nothing precedes: the top of the container, above any comment that opens it.
                val at = Trivia.lineBreak(text, c.open) + 1
                return listOf(Edit(at, at, notes(indent) + indent + b + "," + tail + "\n" + (if (blank) "\n" else "")))
            }
            return if (afterPrevious) {
                val prev = ms[index - 1]
                val isLast = index == ms.size
                val comma = if (!isLast || prev.comma >= 0) "," else ""
                val edits = mutableListOf<Edit>()
                if (prev.comma < 0) edits += Edit(prev.value.end, prev.value.end, ",")
                edits += Edit(refLines.lineEnd, refLines.lineEnd, (if (blank) "\n" else "") + notes(indent) + indent + b + comma + tail + "\n")
                edits
            } else {
                val next = ms[index]
                val cm = Trivia.comments(t, c, index)
                val at = if (cm.noteStart >= 0) cm.noteStart else Trivia.lineStart(text, next.start)
                listOf(Edit(at, at, notes(indent) + indent + b + "," + tail + "\n" + (if (blank) "\n" else "")))
            }
        }

        // Members that share lines: insert between them by characters, separated like the first two.
        val between = if (ms.size >= 2) text.substring(ms[0].end, ms[1].start) else null
        val sep = when {
            between == null -> style.inlineSeparator
            between.isEmpty() -> ""
            between.isBlank() && '\n' !in between -> between
            else -> style.inlineSeparator
        }
        // A note cannot have lines of its own here; it is kept as a block comment in front.
        val lead = note?.let { lines ->
            "/* " + lines.joinToString(" ") { it.removePrefix("//").trim() }.replace("*/", "* /") + " */ "
        }.orEmpty()
        return if (index < ms.size) {
            val at = ms[index].start
            listOf(Edit(at, at, lead + body(Trivia.indentAt(text, at), Trivia.column(text, at, style.tabWidth), true) + "," + sep))
        } else {
            val last = ms.last()
            if (last.comma >= 0) {
                val at = last.comma + 1
                listOf(Edit(at, at, sep + lead + body(Trivia.indentAt(text, at), Trivia.column(text, at, style.tabWidth) + sep.length, true) + ","))
            } else {
                val at = last.value.end
                listOf(Edit(at, at, "," + sep + lead + body(Trivia.indentAt(text, at), Trivia.column(text, at, style.tabWidth) + 1 + sep.length, true)))
            }
        }
    }

    /** The column the object's one-line field values share, when the file aligns them; -1 otherwise. */
    private fun alignColumn(t: SourceTree, c: SrcContainer, style: SourceStyle): Int {
        if (!style.alignValues || c !is SrcObject) return -1
        val text = t.text
        val cols = c.members.indices.filter { i ->
            val m = c.members[i]
            Trivia.lines(t, c, i).ownLines && text.indexOf('\n', m.value.start).let { it < 0 || it >= m.value.end }
        }.map { Trivia.column(text, c.members[it].value.start, style.tabWidth) }
        // One field alone shows no column; two or more sharing one do.
        return if (cols.size >= 2) cols.distinct().singleOrNull() ?: -1 else -1
    }

    // ---- remove ----

    /**
     * Remove the member [path] names — an array item or an object field — with its comma and its
     * trailing comment; with [withNote] also the comment glued above it ([MemberComments.note]).
     * A header comment above it stays. A list or object left empty is written `[]` / `{}` unless
     * comments remain inside it.
     */
    fun remove(text: String, path: PolicyPath, withNote: Boolean = true): String {
        val t = tree(text)
        val (c, m) = t.memberAt(path) ?: throw PolicyEditException("nothing at $path")
        val index = c.members.indexOf(m)
        return done(text, removeMember(t, c, index, withNote))
    }

    private fun removeMember(t: SourceTree, c: SrcContainer, index: Int, withNote: Boolean): List<Edit> {
        val text = t.text
        val ms = c.members
        val m = ms[index]
        val lines = Trivia.lines(t, c, index)
        if (lines.ownLines) {
            val cm = Trivia.comments(t, c, index)
            var from = if (withNote && cm.noteStart >= 0) cm.noteStart else lines.lineStart
            var to = lines.lineEnd
            if (ms.size == 1 && !Trivia.hasCommentBetween(t, c.open + 1, from) && !Trivia.hasCommentBetween(t, to, c.close)) {
                return listOf(Edit(c.open + 1, c.close, ""))
            }
            val edits = mutableListOf<Edit>()
            if (index == ms.lastIndex && m.comma < 0 && index > 0) {
                val prev = ms[index - 1]
                edits += Edit(prev.comma, prev.comma + 1, "")
            }
            // Do not leave two blank lines, or a blank line right inside a bracket, where the member was.
            val openLineEnd = Trivia.lineBreak(text, c.open) + 1
            val above = when {
                from <= openLineEnd -> if (from == openLineEnd) Above.OPEN else Above.OTHER
                Trivia.isBlankLine(text, from - 1) -> Above.BLANK
                else -> Above.OTHER
            }
            val belowBlank = to < text.length && Trivia.isBlankLine(text, to) && Trivia.lineBreak(text, to) < c.close
            val belowClose = to <= c.close && text.substring(to, c.close).isBlank()
            if ((above == Above.BLANK || above == Above.OPEN) && belowBlank) to = Trivia.lineBreak(text, to) + 1
            else if (above == Above.BLANK && belowClose) from = Trivia.lineStart(text, from - 1)
            edits += Edit(from, to, "")
            return edits
        }
        if (ms.size == 1) {
            return if (Trivia.hasCommentBetween(t, c.open + 1, c.close)) listOf(Edit(m.start, m.end, "")) else listOf(Edit(c.open + 1, c.close, ""))
        }
        if (index < ms.lastIndex) return listOf(Edit(m.start, ms[index + 1].start, ""))
        val prev = ms[index - 1]
        return listOf(if (m.comma >= 0) Edit(prev.end, m.end, "") else Edit(prev.value.end, m.end, ""))
    }

    private enum class Above { OPEN, BLANK, OTHER }

    // ---- move ----

    /**
     * Move member [from] of the container at [path] so that it becomes member [to], with its note
     * and trailing comment and exactly its own text. [anchor] decides on which side of a header
     * between two members it lands ([Anchor]). Moving a member to its own index with
     * [Anchor.AFTER_PREVIOUS] lifts it above its own header.
     */
    fun move(text: String, path: PolicyPath, from: Int, to: Int, anchor: Anchor = Anchor.AFTER_PREVIOUS): String {
        val t = tree(text)
        val c = container(t, path)
        val ms = c.members
        if (from !in ms.indices || to !in ms.indices) throw PolicyEditException("move $from → $to out of ${ms.indices}")
        val m = ms[from]
        val cm = Trivia.comments(t, c, from)
        val lines = Trivia.lines(t, c, from)
        // Only a member with a header above it has somewhere to go without changing its index.
        if (from == to && (ms.size == 1 || !lines.ownLines || cm.header == null)) return text
        val src = t.text
        val note: List<String>?
        val body: String
        val trailing: String?
        if (lines.ownLines) {
            // Everything on the member's lines is moved as written, re-indented only if the indentation differs.
            note = if (cm.noteStart >= 0) src.substring(cm.noteStart, lines.lineStart).removeSuffix("\n").split('\n').map { it.removePrefix(lines.indent) } else null
            body = src.substring(lines.lineStart + lines.indent.length, m.value.end)
            trailing = cm.trailingRaw
        } else {
            note = null
            body = src.substring(m.start, m.value.end)
            trailing = null
        }
        val removed = applyEdits(src, removeMember(t, c, from, withNote = true))
        val t2 = tree(removed)
        val edits = insertMember(t2, container(t2, path), to, MovedMember(body), anchor, note, trailing)
        return done(removed, edits)
    }

    // ---- rename ----

    /** Rename the key of the field [path] names, keeping its value's column when the object aligns values. */
    fun rename(text: String, path: PolicyPath, newKey: String): String {
        val t = tree(text)
        val (c, m) = t.memberAt(path) ?: throw PolicyEditException("nothing at $path")
        val o = c as? SrcObject ?: throw PolicyEditException("$path is not a field")
        if (m.key == newKey) return text
        if (o.field(newKey) != null) throw PolicyEditException("\"$newKey\" already exists")
        return done(text, renameEdits(t, m, newKey))
    }

    internal fun renameEdits(t: SourceTree, m: SrcMember, newKey: String): List<Edit> {
        val text = t.text
        val quoted = Renderer(SourceStyle()).quote(newKey)
        val edits = mutableListOf(Edit(m.keyStart, m.keyEnd, quoted))
        val colon = text.indexOf(':', m.keyEnd)
        val gap = m.value.start - colon - 1
        val between = text.substring(colon + 1, m.value.start)
        if (gap > 1 && between.all { it == ' ' }) {
            val delta = quoted.length - (m.keyEnd - m.keyStart)
            val newGap = (gap - delta).coerceAtLeast(1)
            edits += Edit(colon + 1, m.value.start, " ".repeat(newGap))
        }
        return edits
    }

    // ---- lists of strings ----

    /**
     * Make the list at [path] hold exactly [items], in that order, by removing and inserting
     * single items: the items that stay keep their lines and comments. A missing field is
     * created (placed by [order]); a one-line list that grows past the line width is rewritten
     * one item per line, unless comments live inside it.
     */
    fun setStrings(text: String, path: PolicyPath, items: List<String>, order: List<String> = RULE_FIELD_ORDER): String {
        val t = tree(text)
        val existing = t.at(path)
        if (existing == null) {
            val key = (path.last as? PathStep.Key)?.name ?: throw PolicyEditException("nothing at $path")
            return put(text, path.parent(), key, items.pv(), order)
        }
        if (existing !is SrcArray) return set(text, path, items.pv())
        val old = existing.members.map { (it.value as? SrcString)?.value }
        val keep = lcs(old, items)
        var out = text
        // Removals from the end, so earlier indexes stay valid.
        for (i in old.indices.reversed()) if (i !in keep.map { it.first }) out = remove(out, path + i, withNote = true)
        // Insertions in final order: by then every item before the new one is in place.
        val kept = keep.map { it.second }.toSet()
        items.forEachIndexed { j, s -> if (j !in kept) out = insert(out, path, j, s.pv(), Anchor.AFTER_PREVIOUS) }
        return reflow(out, path)
    }

    /** Index pairs (old, new) of a longest common subsequence. */
    private fun lcs(a: List<String?>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] != null && a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] != null && a[i] == b[j] -> { out += i to j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    private fun reflow(text: String, path: PolicyPath): String {
        val t = tree(text)
        val a = t.at(path) as? SrcArray ?: return text
        val style = SourceStyle.of(t)
        if ('\n' in text.substring(a.start, a.end) || Trivia.hasCommentBetween(t, a.open + 1, a.close)) return text
        val lineEnd = Trivia.lineBreak(text, a.end)
        if (Trivia.width(text.substring(Trivia.lineStart(text, a.start), lineEnd), style.tabWidth) <= style.lineWidth) return text
        val values = a.members.map { m -> PolicyValue.Raw(t.slice(m.value)) }
        val indent = Trivia.indentAt(text, a.start)
        val inner = indent + style.indentUnit
        val body = buildString {
            append("[\n")
            values.forEachIndexed { i, v ->
                append(inner).append(v.text)
                if (i < values.lastIndex || style.trailingCommas) append(',')
                append('\n')
            }
            append(indent).append(']')
        }
        return done(text, listOf(Edit(a.start, a.end, body)))
    }
}
