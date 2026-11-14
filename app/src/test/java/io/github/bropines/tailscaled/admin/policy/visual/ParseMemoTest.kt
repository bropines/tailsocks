package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuParseException
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SourceTree's and HuJson's memo of the last two texts: a hit only for the very same
 * characters, never a stale tree, and one visual edit parsing each new text once.
 */
class ParseMemoTest {

    /** Every value's kind, span, key and comma, depth first: two trees with the same dump are the same tree. */
    private fun dump(t: SourceTree): String = StringBuilder().also { sb ->
        fun walk(v: SrcValue) {
            sb.append(v.javaClass.simpleName).append(v.start).append('-').append(v.end).append(' ')
            if (v is SrcContainer) v.members.forEach { m -> sb.append(m.key).append('@').append(m.keyStart).append(',').append(m.comma).append(' '); walk(m.value) }
            if (v is SrcString) sb.append(v.value).append(' ')
        }
        walk(t.root)
    }.toString()

    private fun fresh(text: String): SourceTree {
        SourceTree.memo.clear()
        return SourceTree.parse(text)
    }

    @Test
    fun aRememberedTreeIsTheTreeOfThatTextAndNoOther() {
        val a = sample
        val b = PolicyEdits.putHost(sample, "nas", "100.64.0.10")
        val c = PolicyEdits.putHost(sample, "nbs", "100.64.0.10") // same length as b, other characters
        assertEquals(b.length, c.length)
        val ta = SourceTree.parse(a)
        SourceTree.parse(b)
        val again = SourceTree.parse(a)
        assertSame("a, b, a: the second ask for a is the first answer", ta, again)
        assertEquals(dump(fresh(a)), dump(again))
        // Equal characters in another String instance hit; equal length and other characters never do.
        val ta2 = SourceTree.parse(a)
        assertSame(ta2, SourceTree.parse(String(a.toCharArray())))
        val tb = SourceTree.parse(b)
        val tc = SourceTree.parse(c)
        assertEquals(c, tc.text)
        assertEquals(dump(fresh(c)), dump(tc))
        assertEquals(b, tb.text)
        // Two entries: a third text pushes out the oldest, which is then parsed again, correctly.
        SourceTree.parse(a)
        SourceTree.parse(b)
        SourceTree.parse(c)
        val misses = SourceTree.memo.misses.get()
        assertEquals(a, SourceTree.parse(a).text)
        assertEquals(misses + 1, SourceTree.memo.misses.get())
    }

    @Test
    fun aRememberedFailureIsTheSameIssueEveryTime() {
        val broken = "{\n  \"acls\": [\n"
        val issue = HuJson.check(broken).first()
        repeat(3) {
            try {
                SourceTree.parse(broken)
                fail("a text that does not parse never gets a tree")
            } catch (e: HuParseException) {
                assertEquals(issue, e.issue)
            }
        }
        assertNull(SourceTree.parseOrNull(broken))
        assertEquals(issue, SourceTree.issueOf(broken))
        assertEquals(issue, PolicyDraft(broken).issue)
        assertNull(PolicyDraft(sample).issue)
        // HuJson's own memo: the same for its tree and its refusals.
        val h = HuJson.parse(sample)
        assertSame(h, HuJson.parse(String(sample.toCharArray())))
        assertNull(HuJson.parseOrNull("[1, 2]"))
        assertNull(HuJson.parseOrNull("[1, 2]"))
    }

    @Test
    fun visualEditsInARowParseEachNewTextOnce() {
        val file = PolicyFile(sample, "\"etag-1\"")
        val editor = PolicyEditorState(base = file, text = sample)
        // What the screen did with the text it showed before the first edit.
        editor.draft.model
        val n = 12
        val trees = SourceTree.memo.misses.get()
        val hus = HuJson.parsed.misses.get()
        val parsedTexts = mutableListOf<String>()
        SourceTree.memo.onMiss = { parsedTexts += it }
        try {
            edits(n, editor)
        } finally {
            SourceTree.memo.onMiss = null
        }
        assertEquals("no text was parsed twice", parsedTexts.size, parsedTexts.toSet().size)
        val parsed = SourceTree.memo.misses.get() - trees
        // One new text per edit, and one more for the first: it creates the group, a second step.
        // (Without the memo this sequence parsed 8–10 times per edit.)
        assertTrue("$parsed source-tree parses for $n edits", parsed <= n + 1)
        // The risks: the saved policy once, then each new text once.
        val huParsed = HuJson.parsed.misses.get() - hus
        assertTrue("$huParsed HuJson parses for $n edits", huParsed <= n + 1)
    }

    private fun edits(n: Int, start: PolicyEditorState) {
        var editor = start
        repeat(n) { i ->
            // The tap: the edit (model of the old text, the engine's primitives and checks), withDraft…
            val (next, refused) = editor.visualEdit { PolicyEdits.putNamed(it, Section.GROUPS, "group:dev", listOf("alice@example.com", "u$i@example.com")) }
            assertNull(refused)
            editor = next
            // …then the recomposition: the shell's draft, model and issue, a page's tree, the risks.
            val shown = editor.draft
            assertNotNull(shown.model)
            assertNull(shown.issue)
            assertNotNull(SourceTree.parseOrNull(editor.text))
            VisualEnv.risks(editor.base.text, editor.text)
            VisualEnv.errors(editor.text, emptyList())
        }
    }
}
