package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyValidation
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyRefusal
import io.github.bropines.tailscaled.admin.policy.PolicyReview
import io.github.bropines.tailscaled.admin.policy.PolicyViewPrefs
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.lineOf
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visual editor's frame on the console: the switch between the views, the visual edit and
 * its refusal, undo, the way back from the review, the remembered view, and the Network page's
 * own edits — the console's state transitions as plain functions.
 */
class VisualConsoleTest {

    private val file = PolicyFile(sample, "\"etag-1\"")
    private fun p(vararg s: Any) = PolicyPath.of(*s)
    private fun editor(text: String = sample, visual: Boolean = true) = PolicyEditorState(base = file, text = text, visual = visual)

    // ---- views ----

    @Test
    fun typingInJsonComesBackAsOneUndoStep() {
        val visual = editor().visualEdit { PolicyEdits.putHost(it, "nas", "100.64.0.10") }.first
        assertEquals(1, visual.undoStack.size)

        val json = visual.toJson(line = 7)
        assertFalse(json.visual)
        assertEquals(7, json.goToLine)
        assertEquals(visual.text, json.jsonBase)

        // Two keystrokes' worth of typing, then back.
        val typed = json.copy(text = json.text.replace("\"100.64.0.10\"", "\"100.64.0.11\"")).copy(text = json.text.replace("\"100.64.0.10\"", "\"100.64.0.12\""))
        val back = typed.toVisual(cursorLine = null)
        assertTrue(back.visual)
        assertNull(back.jsonBase)
        assertNull(back.goToLine)
        assertEquals(2, back.undoStack.size)
        assertEquals(visual.text, back.undo().text)
        assertEquals(sample, back.undo().undo().text)
        assertEquals(back.text, back.undo().redo().text)
    }

    @Test
    fun aRoundTripWithoutTypingAddsNoStep() {
        val e = editor()
        val back = e.toJson(null).toVisual(null)
        assertEquals(e.text, back.text)
        assertTrue(back.undoStack.isEmpty())
        // Opened in JSON, the base for the step is the file as it was opened.
        val openedInJson = editor(visual = false).copy(jsonBase = sample)
        val typed = openedInJson.copy(text = PolicyEdits.putHost(sample, "nas", "100.64.0.10")).toVisual(null)
        assertEquals(sample, typed.undo().text)
    }

    @Test
    fun theJsonCursorPicksTheElementAndItsPage() {
        val line = lineOf(sample, "\"tag:dns\":")
        val back = editor().toJson(null).toVisual(cursorLine = line)
        assertEquals(p("tagOwners", "tag:dns"), back.focus)
        assertEquals(VisualSection.TAGS, back.page)
        assertNull(back.selected)
        // The opening brace is no element: the page stays.
        val top = editor().copy(page = VisualSection.SSH).toJson(null).toVisual(cursorLine = 1)
        assertNull(top.focus)
        assertEquals(VisualSection.SSH, top.page)
        // A text that does not parse: the switch happens, nothing is focused.
        val broken = editor().toJson(null).copy(text = "{\"acls\": [").toVisual(cursorLine = 1)
        assertTrue(broken.visual)
        assertNull(broken.focus)
        assertNull(broken.draft.model)
    }

    @Test
    fun theVisualViewOpensJsonAtTheElementShown() {
        val e = editor().copy(selected = p("acls", 6))
        assertEquals(PolicyModel.read(sample)!!.acls[6].origin.line, jsonLineFor(e))
        // Nothing open: the page's first section.
        assertEquals(lineOf(sample, "\"ssh\": ["), jsonLineFor(editor().copy(page = VisualSection.SSH)))
        assertEquals(lineOf(sample, "\"derpMap\""), jsonLineFor(editor().copy(page = VisualSection.RELAYS)))
        // The relay map has its own page: the Network page has nothing of the sample's to open.
        assertNull(jsonLineFor(editor().copy(page = VisualSection.NETWORK)))
        assertEquals(1, jsonLineFor(editor("{\"acls\": [")))
    }

    // ---- edits ----

    @Test
    fun aVisualEditChangesTheTextAndDropsAStaleReview() {
        val reviewed = editor().copy(review = PolicyReview(file, sample), sendError = "nope")
        val (next, refused) = reviewed.visualEdit { PolicyEdits.removeRule(it, p("acls", 12)) }
        assertNull(refused)
        assertNull(next.review)
        assertNull(next.sendError)
        assertEquals(13, next.draft.model!!.acls.size)
        assertTrue(next.changed)

        // Refused: nothing changes, the engine says why.
        val (same, reason) = next.visualEdit { SourceEdits.remove(it, p("acls", 99)) }
        assertSame(next, same)
        assertNotNull(reason)

        // An edit that changes nothing keeps the review.
        assertSame(reviewed, reviewed.visualEdit { it }.first)
    }

    @Test
    fun undoAndRedoStepThroughVisualEdits() {
        val a = editor().visualEdit { PolicyEdits.putHost(it, "nas", "100.64.0.10") }.first
        val b = a.visualEdit { PolicyEdits.putHost(it, "router", "100.64.0.1") }.first
        assertEquals(a.text, b.undo().text)
        assertEquals(sample, b.undo().undo().text)
        assertEquals(sample, b.undo().undo().undo().text)
        assertEquals(b.text, b.undo().undo().redo().redo().text)
        // A new edit after an undo drops what could be redone.
        assertFalse(b.undo().visualEdit { PolicyEdits.putHost(it, "x", "100.64.0.9") }.first.draft.canRedo)
    }

    @Test
    fun anElementTheTextNoLongerHasIsClosed() {
        val added = editor().visualEdit { PolicyEdits.addRule(it, Section.SSH, PolicyEdits.sshFields("accept", listOf("tag:lab"), listOf("tag:lab"), listOf("root"))) }.first
        val open = added.copy(selected = p("ssh", 3), focus = p("ssh", 3))
        val undone = open.undo()
        assertNull(undone.selected)
        assertNull(undone.focus)
        // An edit elsewhere keeps it.
        val kept = open.visualEdit { PolicyEdits.putHost(it, "nas", "100.64.0.10") }.first
        assertEquals(p("ssh", 3), kept.selected)
        assertEquals(p("ssh", 3), kept.focus)
    }

    @Test
    fun focusingAnElementOpensItsPage() {
        val f = editor().copy(selected = p("acls", 1)).focused(p("groups", "group:dev-admin"))
        assertEquals(VisualSection.GROUPS, f.page)
        assertEquals(p("groups", "group:dev-admin"), f.focus)
        assertNull(f.selected)
        assertSame(f, f.focused(PolicyPath.ROOT))
        assertEquals(VisualSection.NETWORK, editor().focused(p("x-unknown")).page)
    }

    // ---- back from the review ----

    @Test
    fun backFromARefusedReviewKeepsTheRefusalAndShowsTheLine() {
        val line = lineOf(sample, "\"src\":    [\"carol@example.com\"]")
        val refused = PolicyReview(file, sample, running = null, validation = PolicyValidation(false, "line $line: bad src", listOf("tag:x not found")))
        val back = editor().copy(review = refused).backFromReview(line)
        assertNull(back.review)
        assertEquals(PolicyRefusal(sample, listOf("line $line: bad src", "tag:x not found")), back.refusal)
        assertEquals(p("acls", 12), back.focus)
        assertEquals(VisualSection.ACCESS, back.page)

        // A path given directly wins; the JSON view gets its line instead.
        assertEquals(p("tagOwners", "tag:dns"), editor().copy(review = refused).backFromReview(path = p("tagOwners", "tag:dns")).focus)
        val json = editor(visual = false).copy(review = refused)
        assertEquals(line, json.backFromReview(line).goToLine)
        assertEquals(lineOf(sample, "\"tag:dns\":"), json.backFromReview(path = p("tagOwners", "tag:dns")).goToLine)

        // The server said yes: the old refusal goes. A review left before the server answered keeps it.
        val accepted = PolicyReview(file, sample, running = null, validation = PolicyValidation.OK)
        assertNull(back.copy(review = accepted).backFromReview().refusal)
        assertEquals(back.refusal, back.copy(review = PolicyReview(file, sample)).backFromReview().refusal)
    }

    // ---- the remembered view ----

    @Test
    fun eachProfileRemembersItsView() {
        val prefs = PolicyViewPrefs(MemoryKeyValueStore())
        assertTrue(prefs.visual("a"))
        prefs.setVisual("a", false)
        assertFalse(prefs.visual("a"))
        assertTrue(prefs.visual("b"))
        prefs.setVisual("a", true)
        assertTrue(prefs.visual("a"))
        prefs.setVisual("b", false)
        prefs.forget("b")
        assertTrue(prefs.visual("b"))
    }

    // ---- navigation ----

    @Test
    fun pagesCarryCountsMarksAndAvailability() {
        val model = PolicyModel.read(sample)!!
        val env = VisualEnv(
            PolicyDraft(sample),
            errors = mapOf(p("tagOwners", "tag:dns") to listOf("bad")),
            risks = mapOf(p("acls", 6) to emptyList(), p("tagOwners", "tag:lab") to emptyList()),
        )
        val pages = pageEntries(model, headscale = false, current = VisualSection.ACCESS, env = env).associateBy { it.section }
        assertEquals(VisualSection.entries.toSet(), pages.keys)
        assertEquals(15, pages.getValue(VisualSection.ACCESS).count)
        assertEquals(PageMark.RISK, pages.getValue(VisualSection.ACCESS).mark)
        // A refusal outranks a risk on the same page.
        assertEquals(PageMark.ERROR, pages.getValue(VisualSection.TAGS).mark)
        assertNull(pages.getValue(VisualSection.POSTURE).count)
        assertNull(pages.getValue(VisualSection.SSH).mark)

        // Headscale has no postures: the page goes, unless it is the one shown.
        val hs = pageEntries(model, headscale = true, current = VisualSection.ACCESS, env = null).map { it.section }
        assertFalse(VisualSection.POSTURE in hs)
        assertTrue(VisualSection.POSTURE in pageEntries(model, headscale = true, current = VisualSection.POSTURE, env = null).map { it.section })

        // Relays: the file's exclusions counted; on Headscale only while the file writes a derpMap anyway.
        assertEquals(1, pages.getValue(VisualSection.RELAYS).count)
        assertEquals(VisualSection.RELAYS, VisualSection.of(p("derpMap", "Regions", "28")))
        assertTrue(VisualSection.RELAYS in hs)
        val bare = PolicyModel.read(SourceEdits.remove(sample, p("derpMap")))!!
        assertFalse(VisualSection.RELAYS in pageEntries(bare, headscale = true, current = VisualSection.ACCESS, env = null).map { it.section })
        assertTrue(VisualSection.RELAYS in pageEntries(bare, headscale = false, current = VisualSection.ACCESS, env = null).map { it.section })
    }

    // ---- the Network page ----

    @Test
    fun optionsGoBackToTheDefaultWithoutLosingAComment() {
        val on = setOrDefault(sample, Section.RANDOMIZE_CLIENT_PORT, true.pv(), false.pv())
        assertEquals(true, PolicyModel.read(on)!!.randomizeClientPort)
        assertTrue(HuJson.check(on).isEmpty())
        // Nothing written about it: off removes the line, and the file is as it was.
        assertEquals(sample, setOrDefault(on, Section.RANDOMIZE_CLIENT_PORT, null, false.pv()))
        // Off when it is not there changes nothing.
        assertEquals(sample, setOrDefault(sample, Section.RANDOMIZE_CLIENT_PORT, null, false.pv()))

        // A comment above it stays, with the default under it.
        val commented = sample.replaceFirst("\n\t\"groups\": {", "\n\t// Guests' routers.\n\t\"randomizeClientPort\": true,\n\n\t\"groups\": {")
        val off = setOrDefault(commented, Section.RANDOMIZE_CLIENT_PORT, null, false.pv())
        assertTrue("// Guests' routers.\n\t\"randomizeClientPort\": false," in off)
        assertEquals(false, PolicyModel.read(off)!!.randomizeClientPort)

        val always = setOrDefault(sample, Section.ONE_CGNAT_ROUTE, "mac-always".pv(), "".pv())
        assertEquals("mac-always", PolicyModel.read(always)!!.oneCGNATRoute)
        assertEquals(sample, setOrDefault(always, Section.ONE_CGNAT_ROUTE, null, "".pv()))
    }

    @Test
    fun aSectionTheEditorOnlyShowsIsSummarised() {
        val text = sample.replaceFirst("\n\t\"groups\": {", "\n\t\"x-review\": {\n\t\t\"owner\": \"infra\",\n\t\t\"period\": \"quarterly\",\n\t},\n\n\t\"groups\": {")
        val entry = PolicyModel.read(text)!!.unknownSections.single()
        val shown = sectionPreview(text, entry)
        assertEquals(true to 2, shown.size)
        assertEquals(listOf("{", "  \"owner\": \"infra\",", "  \"period\": \"quarterly\",", "}"), shown.lines)

        val long = sample.replaceFirst("\n\t\"groups\": {", "\n\t\"x-list\": [\n\t\t1,\n\t\t2,\n\t\t3,\n\t\t4,\n\t\t5,\n\t],\n\n\t\"groups\": {")
        val list = sectionPreview(long, PolicyModel.read(long)!!.unknownSections.single())
        assertEquals(false to 5, list.size)
        assertEquals("…", list.lines.last())
        assertEquals(5, list.lines.size)
    }
}
