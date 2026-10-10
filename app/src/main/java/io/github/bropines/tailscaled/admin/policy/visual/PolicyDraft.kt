package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuIssue
import io.github.bropines.tailscaled.admin.policy.HuJson

/**
 * The visual editor's working copy: the policy text both editors share, the model read from it,
 * and the steps to undo and redo. Immutable — each edit gives a new draft — so it can sit in
 * Compose state or the console's state as it is.
 *
 * The text is the single source of truth: switching to the JSON editor shows exactly [text],
 * and whatever is typed there comes back through [replaced]. The model is derived and never
 * written back.
 */
data class PolicyDraft(
    val text: String,
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList(),
) {
    /** Null while the text does not parse: the visual editor then shows [issue] and a way to the JSON editor. */
    val model: PolicyModel? by lazy { PolicyModel.read(text) }

    /** Why the text does not parse, the first problem; null when it does. */
    val issue: HuIssue? by lazy { HuJson.check(text).firstOrNull() }

    /** Duplicate top-level keys make every visual edit ambiguous; the editor is read-only until they are fixed. */
    val editable: Boolean get() = model?.duplicateSections?.isEmpty() == true

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Run one visual edit. A refused edit ([PolicyEditException]) leaves the draft as it was and
     * says why; an edit that changes nothing adds no undo step.
     */
    fun edit(op: (String) -> String): DraftEdit {
        if (!editable) return DraftEdit.Refused(this, "the policy cannot be edited visually until it parses and has no duplicate sections")
        val next = try {
            SourceEdits.preservingLineEndings(text, op)
        } catch (e: PolicyEditException) {
            return DraftEdit.Refused(this, e.message.orEmpty())
        }
        if (next == text) return DraftEdit.Done(this)
        return DraftEdit.Done(PolicyDraft(next, (undoStack + text).takeLast(MAX_UNDO), emptyList()))
    }

    fun undo(): PolicyDraft = if (!canUndo) this else PolicyDraft(undoStack.last(), undoStack.dropLast(1), redoStack + text)

    fun redo(): PolicyDraft = if (!canRedo) this else PolicyDraft(redoStack.last(), (undoStack + text).takeLast(MAX_UNDO), redoStack.dropLast(1))

    /** The text as the JSON editor left it: one undo step, so a switch back and forth can be undone. */
    fun replaced(newText: String): PolicyDraft = if (newText == text) this else PolicyDraft(newText, (undoStack + text).takeLast(MAX_UNDO), emptyList())

    companion object {
        const val MAX_UNDO = 50
    }
}

/** The outcome of [PolicyDraft.edit]. */
sealed class DraftEdit {
    abstract val draft: PolicyDraft

    data class Done(override val draft: PolicyDraft) : DraftEdit()

    /** Nothing changed; [reason] is the engine's own words, for the log and a generic message. */
    data class Refused(override val draft: PolicyDraft, val reason: String) : DraftEdit()
}
