package io.github.bropines.tailscaled.admin.policy

import android.content.Context
import io.github.bropines.tailscaled.admin.api.ApiDerpMap
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.policy.visual.DraftEdit
import io.github.bropines.tailscaled.admin.policy.visual.PolicyDraft
import io.github.bropines.tailscaled.admin.policy.visual.PolicyLocator
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.SourceTree
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.admin.secure.KeyValueStore
import io.github.bropines.tailscaled.admin.secure.PrefsKeyValueStore
import io.github.bropines.tailscaled.admin.secure.SecretBox
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import java.io.File

/** The policy tab's part of the console state: the file, the editor over it, the previews. */
data class PolicyState(
    val file: Loadable<PolicyFile> = Loadable(),
    /** The version this phone's last policy write replaced: what the one-tap revert puts back. */
    val previous: StoredPolicy? = null,
    val editor: PolicyEditorState? = null,
    /** A write came back 412; the choice is the person's. */
    val conflict: PolicyConflict? = null,
    val reachQuery: ReachQuery? = null,
    val reach: Loadable<PolicyPreview> = Loadable(),
    /** The editor's view this profile used last ([PolicyViewPrefs]): the visual one until JSON is picked. */
    val visual: Boolean = true,
    /** Tailscale's default relay map, for the visual editor's Relays page: read once a session, kept in the console's copy. */
    val derpMap: Loadable<ApiDerpMap> = Loadable(),
)

/**
 * The editor: [text] as typed, [base] the version it started from — the diff's left side and
 * the ETag the write carries. With [review] set the review screen shows instead of the text.
 *
 * Two views edit the same [text]: the visual one ([visual], pages of cards built on
 * [PolicyDraft]) and the JSON one. Nothing is converted in a switch; the JSON view's typing
 * comes back to the visual one as a single undo step ([toVisual]).
 */
data class PolicyEditorState(
    val base: PolicyFile,
    val text: String,
    val isRevert: Boolean = false,
    /** Put the cursor on this 1-based line when the JSON view shows; cleared once it has. */
    val goToLine: Int? = null,
    val review: PolicyReview? = null,
    /** Why the last attempt to send did not apply, in words; shown on the review. */
    val sendError: String? = null,
    /** The visual view shows instead of the JSON text. */
    val visual: Boolean = true,
    /** The visual view's steps back and forward, whole texts ([PolicyDraft]). */
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList(),
    /** The visual view's page. */
    val page: VisualSection = VisualSection.ACCESS,
    /** The element whose editor is open: a sheet on a phone, the pane beside the list on a large window. */
    val selected: PolicyPath? = null,
    /** The element to bring into view and outline: a server error, the JSON cursor, a use of a name. */
    val focus: PolicyPath? = null,
    /** The text when the JSON view opened: what the visual view's one undo step for the typing goes back to. */
    val jsonBase: String? = null,
    /** What the server said when it last refused the text, kept after the review is left. */
    val refusal: PolicyRefusal? = null,
) {
    val changed: Boolean get() = text != base.text

    /** The visual view's working copy. */
    val draft: PolicyDraft get() = PolicyDraft(text, undoStack, redoStack)

    /**
     * [d] as the editor's text and steps. A different text drops the review it no longer
     * matches, and the open or outlined element when the text no longer has it (an undo took
     * back the rule just added).
     */
    fun withDraft(d: PolicyDraft): PolicyEditorState {
        if (d.text == text) return if (d.undoStack == undoStack && d.redoStack == redoStack) this else copy(undoStack = d.undoStack, redoStack = d.redoStack)
        val tree = if (selected != null || focus != null) SourceTree.parseOrNull(d.text) else null
        fun kept(p: PolicyPath?) = p?.takeIf { tree?.at(it) != null }
        return copy(
            text = d.text, undoStack = d.undoStack, redoStack = d.redoStack, review = null, sendError = null,
            selected = kept(selected), focus = kept(focus),
        )
    }

    /** One visual edit: the new state, and the engine's reason when it refused (the state then unchanged). */
    fun visualEdit(op: (String) -> String): Pair<PolicyEditorState, String?> = when (val r = draft.edit(op)) {
        is DraftEdit.Done -> withDraft(r.draft) to null
        is DraftEdit.Refused -> this to r.reason
    }

    fun undo(): PolicyEditorState = withDraft(draft.undo())

    fun redo(): PolicyEditorState = withDraft(draft.redo())

    /** To the JSON view, the cursor on [line] (1-based) when one is given. */
    fun toJson(line: Int?): PolicyEditorState =
        if (!visual) copy(goToLine = line ?: goToLine) else copy(visual = false, goToLine = line, jsonBase = text)

    /**
     * To the visual view: what was typed since the JSON view opened becomes one undo step, and
     * the element under the JSON cursor ([cursorLine]) is the one brought into view.
     */
    fun toVisual(cursorLine: Int?): PolicyEditorState {
        if (visual) return this
        val typed = PolicyDraft(jsonBase ?: text, undoStack, redoStack).replaced(text)
        val back = copy(visual = true, goToLine = null, jsonBase = null).withDraft(typed)
        return cursorLine?.let { elementAt(text, it) }?.let(back::focused) ?: back
    }

    /** [path] brought into view: its page, its card outlined (and, on a large window, in the pane). */
    fun focused(path: PolicyPath): PolicyEditorState =
        if (path.isRoot) this else copy(page = VisualSection.of(path), focus = path, selected = null)

    /**
     * Back from the review to the editor: a refusal from the server is kept for the visual
     * view's cards; [line] or [path] says what to show — the cursor's line in the JSON view,
     * the element (or the element written on that line) in the visual one.
     */
    fun backFromReview(line: Int? = null, path: PolicyPath? = null): PolicyEditorState {
        val r = review
        val v = r?.validation
        val kept = when {
            r == null || v == null -> refusal
            v.ok -> null
            else -> PolicyRefusal(r.candidate, listOfNotNull(v.message) + v.details)
        }
        val back = copy(review = null, refusal = kept)
        if (!visual) return back.copy(goToLine = line ?: path?.let { lineOf(text, it) } ?: goToLine)
        val target = path ?: line?.let { elementAt(text, it) }
        return target?.let(back::focused) ?: back
    }

    companion object {
        /** The element written on 1-based [line] of [text]; null when the text does not parse or the line holds none. */
        fun elementAt(text: String, line: Int): PolicyPath? =
            SourceTree.parseOrNull(text)?.let { PolicyLocator.elementAtLine(it, line) }?.takeUnless { it.isRoot }

        /** The 1-based line [path] starts on in [text]; null when it is not there. */
        fun lineOf(text: String, path: PolicyPath): Int? {
            val t = SourceTree.parseOrNull(text) ?: return null
            val m = t.memberAt(path)?.second ?: return null
            return HuJson.Lines(text).lineOf(m.start)
        }
    }
}

/** The server's refusal of [candidate], message by message; the visual view puts them on cards while the text is still that. */
data class PolicyRefusal(val candidate: String, val messages: List<String>)

/**
 * Someone changed the policy between the read and the write. [merged] is [mine]'s edit applied
 * to [theirs], when the two do not touch; [conflicts] the base lines where they do.
 */
data class PolicyConflict(
    val base: PolicyFile,
    val mine: String,
    val theirs: PolicyFile,
    val merged: String?,
    val conflicts: List<IntRange>,
    val isRevert: Boolean,
)

enum class ConflictChoice {
    /** Take their version; my edit is dropped. */
    DISCARD,
    /** My edit applied to their version, reviewed as a new diff. */
    REAPPLY,
    /** My text back in the editor, against their version: the diff shows what it undoes of theirs. */
    EDIT,
}

/** What "who can reach" was asked: [label] is what the person picked, in words. */
data class ReachQuery(val type: PolicyPreviewType, val previewFor: String, val label: String)

@Serializable
data class StoredPolicy(val text: String, val savedAt: Long, val etagAfter: String? = null)

/**
 * The policy as it was before this phone last changed it, one per admin profile, under
 * `files/admin/policy/` — outside backups, like the audit log, and sealed like the credentials
 * (the whole policy: groups, people, addresses). A copy written in the clear by an earlier build
 * is sealed on first read.
 */
class PolicyStore(filesDir: File, private val box: SecretBox) {
    private val dir = File(File(filesDir, "admin"), "policy")

    private fun name(profileId: String) = profileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "default" }
    private fun fileFor(profileId: String) = File(dir, name(profileId) + ".sealed")
    private fun legacyFor(profileId: String) = File(dir, name(profileId) + ".json")
    private fun aad(profileId: String) = "admin-policy:$profileId"

    fun load(profileId: String): StoredPolicy? {
        val legacy = legacyFor(profileId)
        if (legacy.exists()) {
            val old = runCatching { AppJson.decodeFromString(StoredPolicy.serializer(), legacy.readText()) }.getOrNull()
            if (old != null) runCatching { save(profileId, old) }
            if (old == null || fileFor(profileId).exists()) legacy.delete()
        }
        val f = fileFor(profileId)
        if (!f.exists()) return null
        return try {
            AppJson.decodeFromString(StoredPolicy.serializer(), box.open(f.readText(), aad(profileId)))
        } catch (_: Exception) {
            // Another phone's key or a reset Keystore: there is nothing to revert to.
            f.delete()
            null
        }
    }

    fun save(profileId: String, policy: StoredPolicy) {
        dir.mkdirs()
        val f = fileFor(profileId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(box.seal(AppJson.encodeToString(StoredPolicy.serializer(), policy), aad(profileId)))
        if (!tmp.renameTo(f)) tmp.delete()
    }

    fun clear(profileId: String) {
        fileFor(profileId).delete()
        legacyFor(profileId).delete()
    }
}

/**
 * Which view of the policy editor each admin profile opens in, in its own preference file
 * (`admin_policy_editor`): the visual one, unless the JSON one was the last picked. No tailnet
 * data, only the profile's id.
 */
class PolicyViewPrefs(private val store: KeyValueStore) {

    fun visual(profileId: String): Boolean = store.getString(key(profileId)) != JSON

    fun setVisual(profileId: String, visual: Boolean): Boolean = store.putString(key(profileId), if (visual) null else JSON)

    fun forget(profileId: String) {
        store.putString(key(profileId), null)
    }

    private fun key(profileId: String) = "$profileId/view"

    companion object {
        const val PREFS_NAME = "admin_policy_editor"
        private const val JSON = "json"

        fun of(context: Context) = PolicyViewPrefs(
            PrefsKeyValueStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        )
    }
}
