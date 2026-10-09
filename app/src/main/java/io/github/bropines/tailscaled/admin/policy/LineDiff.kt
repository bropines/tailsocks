package io.github.bropines.tailscaled.admin.policy

enum class LineEditKind { EQUAL, DELETE, INSERT }

/** One line of a diff; [oldLine] and [newLine] are 1-based, null on the side it is missing from. */
data class LineEdit(val kind: LineEditKind, val oldLine: Int?, val newLine: Int?, val text: String)

/** A row of the diff as shown: a line, or [skipped] unchanged lines folded away. */
data class DiffRow(val edit: LineEdit?, val skipped: Int = 0)

/** Base lines [baseStart, baseEnd) (0-based) replaced by [lines]. */
data class Hunk(val baseStart: Int, val baseEnd: Int, val lines: List<String>)

/** A three-way merge: the merged lines, or null with the base line ranges (1-based) that both sides changed. */
data class Merge3(val lines: List<String>?, val conflicts: List<IntRange>)

/**
 * Line diffs of the policy file: Myers' algorithm after the common head and tail are cut off,
 * so a few changes in a long file cost little. A diff that would need more than [MAX_EDITS]
 * edits is not worth the search; the changed middle is then shown as removed and added whole.
 */
object LineDiff {

    const val MAX_EDITS = 1_000

    fun lines(text: String): List<String> = text.replace("\r\n", "\n").split('\n')

    fun diff(old: List<String>, new: List<String>): List<LineEdit> {
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++

        val out = ArrayList<LineEdit>(maxOf(old.size, new.size) + 8)
        for (i in 0 until head) out += LineEdit(LineEditKind.EQUAL, i + 1, i + 1, old[i])
        val a = old.subList(head, old.size - tail)
        val b = new.subList(head, new.size - tail)
        val middle = myers(a, b) ?: buildList {
            a.indices.forEach { add(Triple(LineEditKind.DELETE, it, -1)) }
            b.indices.forEach { add(Triple(LineEditKind.INSERT, -1, it)) }
        }
        for ((kind, i, j) in middle) {
            out += when (kind) {
                LineEditKind.EQUAL -> LineEdit(kind, head + i + 1, head + j + 1, a[i])
                LineEditKind.DELETE -> LineEdit(kind, head + i + 1, null, a[i])
                LineEditKind.INSERT -> LineEdit(kind, null, head + j + 1, b[j])
            }
        }
        for (k in 0 until tail) {
            val i = old.size - tail + k
            val j = new.size - tail + k
            out += LineEdit(LineEditKind.EQUAL, i + 1, j + 1, old[i])
        }
        return out
    }

    /**
     * The shortest edit script from [a] to [b] as (kind, index in a, index in b), or null when
     * it is longer than [MAX_EDITS]. Lines are compared by identity numbers first.
     */
    private fun myers(a: List<String>, b: List<String>): List<Triple<LineEditKind, Int, Int>>? {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        val ids = HashMap<String, Int>()
        val x0 = IntArray(n) { ids.getOrPut(a[it]) { ids.size } }
        val y0 = IntArray(m) { ids.getOrPut(b[it]) { ids.size } }
        val max = n + m
        val limit = minOf(max, MAX_EDITS)
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..limit) {
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && x0[x] == y0[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    trace += v.copyOfRange(offset - d - 1, offset + d + 2)
                    found = d
                    break@search
                }
            }
            trace += v.copyOfRange(offset - d - 1, offset + d + 2)
        }
        if (found < 0) return null

        // Back from (n, m): each trace[d] holds V for k in [-d-1, d+1] at index k + d + 1.
        val out = ArrayList<Triple<LineEditKind, Int, Int>>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            val prev = trace[d - 1]
            fun pv(k: Int) = prev[k + (d - 1) + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && pv(k - 1) < pv(k + 1))) k + 1 else k - 1
            val prevX = pv(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                out += Triple(LineEditKind.EQUAL, x - 1, y - 1)
                x--; y--
            }
            if (x == prevX) out += Triple(LineEditKind.INSERT, -1, y - 1) else out += Triple(LineEditKind.DELETE, x - 1, -1)
            x = prevX
            y = prevY
        }
        while (x > 0 && y > 0) {
            out += Triple(LineEditKind.EQUAL, x - 1, y - 1)
            x--; y--
        }
        out.reverse()
        return out
    }

    fun added(edits: List<LineEdit>): Int = edits.count { it.kind == LineEditKind.INSERT }
    fun removed(edits: List<LineEdit>): Int = edits.count { it.kind == LineEditKind.DELETE }
    fun changed(edits: List<LineEdit>): Boolean = edits.any { it.kind != LineEditKind.EQUAL }

    /** The diff to show: every change with [context] lines around it, the rest folded. */
    fun rows(edits: List<LineEdit>, context: Int = 3): List<DiffRow> {
        val keep = BooleanArray(edits.size)
        edits.forEachIndexed { i, e ->
            if (e.kind != LineEditKind.EQUAL) for (k in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(edits.lastIndex)) keep[k] = true
        }
        val out = mutableListOf<DiffRow>()
        var folded = 0
        edits.forEachIndexed { i, e ->
            if (keep[i]) {
                if (folded > 0) { out += DiffRow(null, folded); folded = 0 }
                out += DiffRow(e)
            } else folded++
        }
        if (folded > 0) out += DiffRow(null, folded)
        return out
    }

    /** Runs of changes, in base coordinates. */
    fun hunks(base: List<String>, other: List<String>): List<Hunk> {
        val out = mutableListOf<Hunk>()
        var oldIdx = 0
        var start = -1
        var lines = mutableListOf<String>()
        for (e in diff(base, other)) {
            if (e.kind == LineEditKind.EQUAL) {
                if (start >= 0) { out += Hunk(start, oldIdx, lines); start = -1; lines = mutableListOf() }
                oldIdx++
                continue
            }
            if (start < 0) start = oldIdx
            if (e.kind == LineEditKind.DELETE) oldIdx++ else lines += e.text
        }
        if (start >= 0) out += Hunk(start, oldIdx, lines)
        return out
    }

    /**
     * [mine] and [theirs], both edited from [base], merged: each side's changes applied to
     * the other's. Changes that overlap or touch — including two insertions at one place —
     * are conflicts, as git counts them, unless both sides made the very same change.
     */
    fun merge3(base: List<String>, mine: List<String>, theirs: List<String>): Merge3 {
        val a = hunks(base, mine)
        val b = hunks(base, theirs)
        val conflicts = mutableListOf<IntRange>()
        val taken = mutableListOf<Hunk>()
        taken += a
        for (hb in b) {
            val clash = a.filter { ha -> ha.baseStart <= hb.baseEnd && hb.baseStart <= ha.baseEnd }
            when {
                clash.isEmpty() -> taken += hb
                clash.size == 1 && clash[0] == hb -> Unit
                else -> clash.forEach { ha ->
                    conflicts += (minOf(ha.baseStart, hb.baseStart) + 1)..maxOf(ha.baseEnd, hb.baseEnd, minOf(ha.baseStart, hb.baseStart) + 1)
                }
            }
        }
        if (conflicts.isNotEmpty()) return Merge3(null, conflicts.distinct().sortedBy { it.first })
        val out = mutableListOf<String>()
        var idx = 0
        for (h in taken.sortedWith(compareBy({ it.baseStart }, { it.baseEnd }))) {
            out += base.subList(idx, h.baseStart)
            out += h.lines
            idx = h.baseEnd
        }
        out += base.subList(idx, base.size)
        return Merge3(out, emptyList())
    }
}
