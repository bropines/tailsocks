package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.Loadable
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
