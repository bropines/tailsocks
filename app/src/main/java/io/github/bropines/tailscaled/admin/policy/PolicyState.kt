package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.Loadable
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
)

/**
 * The editor: [text] as typed, [base] the version it started from — the diff's left side and
 * the ETag the write carries. With [review] set the review screen shows instead of the text.
 */
data class PolicyEditorState(
    val base: PolicyFile,
    val text: String,
    val isRevert: Boolean = false,
    /** Put the cursor on this 1-based line when the editor shows; cleared once it has. */
    val goToLine: Int? = null,
    val review: PolicyReview? = null,
    /** Why the last attempt to send did not apply, in words; shown on the review. */
    val sendError: String? = null,
) {
    val changed: Boolean get() = text != base.text
}

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
 * `files/admin/policy/` — outside backups, like the audit log.
 */
class PolicyStore(filesDir: File) {
    private val dir = File(File(filesDir, "admin"), "policy")

    private fun fileFor(profileId: String) = File(dir, profileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "default" } + ".json")

    fun load(profileId: String): StoredPolicy? = runCatching {
        val f = fileFor(profileId)
        if (!f.exists()) null else io.github.bropines.tailscaled.core.AppJson.decodeFromString(StoredPolicy.serializer(), f.readText())
    }.getOrNull()

    fun save(profileId: String, policy: StoredPolicy) {
        dir.mkdirs()
        fileFor(profileId).writeText(io.github.bropines.tailscaled.core.AppJson.encodeToString(StoredPolicy.serializer(), policy))
    }
}
