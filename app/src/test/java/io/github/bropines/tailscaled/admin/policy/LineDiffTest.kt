package io.github.bropines.tailscaled.admin.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    /** Applying the edits to [old] must give [new], and EQUAL lines must really be equal. */
    private fun assertReplays(old: List<String>, new: List<String>, edits: List<LineEdit>) {
        val rebuilt = edits.filter { it.kind != LineEditKind.DELETE }.map { it.text }
        assertEquals(new, rebuilt)
        val kept = edits.filter { it.kind != LineEditKind.INSERT }.map { it.text }
        assertEquals(old, kept)
        edits.filter { it.kind == LineEditKind.EQUAL }.forEach { assertEquals(old[it.oldLine!! - 1], new[it.newLine!! - 1]) }
    }

    @Test
    fun aChangedLineIsOneDeleteAndOneInsert() {
        val old = listOf("{", "  \"a\": 1,", "  \"b\": 2,", "}")
        val new = listOf("{", "  \"a\": 1,", "  \"b\": 3,", "}")
        val d = LineDiff.diff(old, new)
        assertReplays(old, new, d)
        assertEquals(1, LineDiff.added(d))
        assertEquals(1, LineDiff.removed(d))
        val del = d.single { it.kind == LineEditKind.DELETE }
        val ins = d.single { it.kind == LineEditKind.INSERT }
        assertEquals(3, del.oldLine)
        assertEquals(3, ins.newLine)
    }

    @Test
    fun scatteredEditsInALongFileStayMinimal() {
        val old = (1..3000).map { "line $it" }
        val new = old.toMutableList().apply {
            set(10, "changed 11")
            removeAt(1500)
            add(2500, "inserted")
            add("tail")
        }
        val d = LineDiff.diff(old, new)
        assertReplays(old, new, d)
        assertEquals(3, LineDiff.added(d))
        assertEquals(2, LineDiff.removed(d))
    }

    @Test
    fun identicalAndEmpty() {
        assertFalse(LineDiff.changed(LineDiff.diff(listOf("a"), listOf("a"))))
        assertReplays(emptyList(), listOf("a", "b"), LineDiff.diff(emptyList(), listOf("a", "b")))
        assertReplays(listOf("a", "b"), emptyList(), LineDiff.diff(listOf("a", "b"), emptyList()))
    }

    @Test
    fun aRewriteBeyondTheLimitIsStillCorrect() {
        val old = (1..1500).map { "o$it" }
        val new = (1..1500).map { "n$it" }
        val d = LineDiff.diff(old, new)
        assertReplays(old, new, d)
        assertEquals(1500, LineDiff.added(d))
    }

    @Test
    fun rowsFoldTheUnchanged() {
        val old = (1..20).map { "l$it" }
        val new = old.toMutableList().apply { set(9, "x") }
        val rows = LineDiff.rows(LineDiff.diff(old, new), context = 2)
        // 21 edits: nine equal, the delete and the insert, ten equal; two lines of context each side.
        assertEquals(7, rows.first().skipped)
        assertEquals(8, rows.last().skipped)
        assertEquals(2 + 2 + 2, rows.count { it.edit != null })
    }

    @Test
    fun mergeTakesBothSidesWhenTheyDoNotTouch() {
        val base = (1..10).map { "l$it" }
        val mine = base.toMutableList().apply { set(1, "mine") }
        val theirs = base.toMutableList().apply { set(7, "theirs"); add("their tail") }
        val m = LineDiff.merge3(base, mine, theirs)
        assertTrue(m.conflicts.isEmpty())
        assertEquals(base.toMutableList().apply { set(1, "mine"); set(7, "theirs"); add("their tail") }, m.lines)
    }

    @Test
    fun overlappingOrTouchingChangesConflict() {
        val base = (1..10).map { "l$it" }
        val mine = base.toMutableList().apply { set(4, "mine") }
        val theirs = base.toMutableList().apply { set(5, "theirs") }
        val m = LineDiff.merge3(base, mine, theirs)
        assertNull(m.lines)
        assertEquals(5, m.conflicts.single().first)
        val same = LineDiff.merge3(base, mine, mine)
        assertEquals("the same change on both sides is no conflict", mine, same.lines)
    }
}
