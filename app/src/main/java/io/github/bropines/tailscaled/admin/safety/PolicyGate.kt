package io.github.bropines.tailscaled.admin.safety

import java.security.MessageDigest

/**
 * What the policy pipeline established about one candidate policy before it may be sent:
 * the local parse, the server's validation, the risk lint and the access check, against the
 * version whose ETag the write will carry. A POLICY change without it — or with a check that
 * did not pass — is refused by [SafeChangeRunner] before anything is sent.
 */
data class PolicyEvidence(
    /** SHA-256 of the exact text that will be sent. */
    val candidateSha256: String,
    /** The ETag of the version the diff was made against, sent as If-Match. */
    val baseEtag: String?,
    /** The text parsed here: brackets, strings, grammar. */
    val localOk: Boolean,
    /** /acl/validate passed it, tests included. */
    val serverValid: Boolean,
    /** Lines added and removed, as the diff showed them. */
    val linesAdded: Int,
    val linesRemoved: Int,
    /** Risk findings the review showed. */
    val riskFindings: Int,
    /** Whether the "don't lock yourself out" previews ran (they may not: the phone is not in the tailnet). */
    val accessChecked: Boolean,
    /** Access this phone had that the candidate takes away, as the previews counted it. */
    val accessLost: Int,
)

object PolicyGate {

    fun sha256(text: String): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (b in MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))) {
            sb.append(hex[(b.toInt() shr 4) and 0xf]).append(hex[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    /**
     * Whether [change] may go out as a policy write as far as the pipeline goes: it carries
     * evidence, every check passed, the write has an ETag to carry, and it changes something.
     */
    fun passed(change: AdminChange): Boolean {
        val e = change.policy ?: return false
        return e.localOk && e.serverValid && !e.baseEtag.isNullOrBlank() && e.candidateSha256.isNotBlank() &&
            (e.linesAdded > 0 || e.linesRemoved > 0)
    }
}
