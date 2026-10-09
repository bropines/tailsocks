package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.RequestIds
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.WriteGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Why a change did not go out. Every one of these is decided before anything is sent. */
enum class Refusal {
    /** The admin profile is marked read-only. */
    READ_ONLY_PROFILE,
    /** No screen lock: nothing can unlock a write. */
    NO_SCREEN_LOCK,
    /** The credential's scopes (or a 403 learned earlier) do not allow it. */
    NOT_ALLOWED,
    /** The console's own credential, in the keys list. */
    OWN_CREDENTIAL,
    /** The user the credential acts as, or the one this phone is signed in as. */
    OWN_USER,
    /** A device shared in from another tailnet: it is not this tailnet's to change. */
    SHARED_DEVICE,
    /** The confirm step did not happen. */
    NOT_CONFIRMED,
    /** The typed name does not match the target's. */
    TYPED_MISMATCH,
    /** No unlock, or one already used or gone stale. */
    NOT_UNLOCKED,
    /** The policy file has its own pipeline, not this one. */
    POLICY_PIPELINE,
}

/** Who "you" are, for the guards. */
data class SafetyContext(
    val profileId: String,
    val profileName: String = "",
    val readOnlyProfile: Boolean = false,
    val lockState: LockState = LockState.CRYPTO_PER_USE,
    val ownKeyId: String? = null,
    val ownUserId: String? = null,
    /** The login of the user this phone's TailSocks profile is signed in as, when known. */
    val ownLoginName: String? = null,
    val canWrite: (AdminArea) -> Boolean = { true },
)

/** What the person did at the gates: confirmed, typed, unlocked. */
data class GateEvidence(
    val confirmed: Boolean,
    val typedName: String? = null,
    val grant: WriteGrant? = null,
)

sealed class ChangeOutcome {
    /** [verified] is null when there was nothing to re-read. */
    data class Applied(val verified: Boolean?, val requestIds: List<String>) : ChangeOutcome()
    data class Refused(val reason: Refusal) : ChangeOutcome()
    data class Failed(val error: Throwable, val requestIds: List<String>) : ChangeOutcome()
}

/**
 * Applies a [PlannedChange] after checking, itself, that its gates were passed — the dialogs
 * ask, this enforces: a missing confirm, a wrong typed name, an absent or reused unlock, a
 * read-only profile, a credential without the scope, the console's own key, your own user, a
 * shared-in device — each refuses before a request is made. Then it applies, re-reads to
 * verify, and records the attempt in the [audit] log with the server's request ids.
 */
class SafeChangeRunner(
    private val backend: AdminBackend,
    private val audit: AdminAuditLog?,
    private val context: () -> SafetyContext,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The guard that would stop [change] whatever the person does at the gates; null if none. */
    fun blockedBy(change: AdminChange): Refusal? {
        val ctx = context()
        val t = change.target
        return when {
            change.changeClass == ChangeClass.POLICY -> Refusal.POLICY_PIPELINE
            ctx.readOnlyProfile -> Refusal.READ_ONLY_PROFILE
            change.needsUnlock && ctx.lockState == LockState.NO_SCREEN_LOCK -> Refusal.NO_SCREEN_LOCK
            !ctx.canWrite(change.area) -> Refusal.NOT_ALLOWED
            t.type == TargetType.KEY && change.kind == ChangeKind.KEY_REVOKE && ctx.ownKeyId != null && t.id == ctx.ownKeyId -> Refusal.OWN_CREDENTIAL
            t.type == TargetType.USER && isOwnUser(t, ctx) -> Refusal.OWN_USER
            t.type == TargetType.DEVICE && t.shared -> Refusal.SHARED_DEVICE
            else -> null
        }
    }

    private fun isOwnUser(t: ChangeTarget, ctx: SafetyContext): Boolean =
        (ctx.ownUserId != null && t.id == ctx.ownUserId) ||
            (ctx.ownLoginName != null && t.loginName != null && t.loginName.equals(ctx.ownLoginName, ignoreCase = true))

    /** The gate check alone, without applying: what [run] would refuse with, or null. */
    fun check(change: AdminChange, gates: GateEvidence): Refusal? {
        blockedBy(change)?.let { return it }
        if (!gates.confirmed) return Refusal.NOT_CONFIRMED
        if (change.needsTypedConfirmation && gates.typedName?.trim() != change.target.name.trim()) return Refusal.TYPED_MISMATCH
        if (change.needsUnlock && gates.grant == null) return Refusal.NOT_UNLOCKED
        return null
    }

    suspend fun run(planned: PlannedChange, gates: GateEvidence, isUndo: Boolean = false): ChangeOutcome {
        val change = planned.change
        val refusal = check(change, gates)
            ?: if (change.needsUnlock && gates.grant?.consume(clock()) != true) Refusal.NOT_UNLOCKED else null
        if (refusal != null) {
            record(change, AuditResult.REFUSED, refusal.name, emptyList(), isUndo)
            return ChangeOutcome.Refused(refusal)
        }
        val ids = RequestIds()
        try {
            withContext(ids) { planned.apply(backend) }
        } catch (e: CancellationException) {
            record(change, AuditResult.FAILED, "cancelled", ids.ids, isUndo)
            throw e
        } catch (e: Exception) {
            record(change, AuditResult.FAILED, describe(e), ids.ids, isUndo)
            return ChangeOutcome.Failed(e, ids.ids)
        }
        val verified = planned.verify?.let { verify ->
            try {
                withContext(ids) { verify(backend) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val result = when (verified) {
            true -> AuditResult.VERIFIED
            false -> AuditResult.MISMATCH
            null -> AuditResult.APPLIED
        }
        record(change, result, null, ids.ids, isUndo)
        return ChangeOutcome.Applied(verified, ids.ids)
    }

    private fun record(change: AdminChange, result: AuditResult, detail: String?, ids: List<String>, undo: Boolean) {
        val ctx = context()
        runCatching {
            audit?.append(
                AuditRecord(
                    time = clock(),
                    profileId = ctx.profileId,
                    profileName = ctx.profileName,
                    kind = change.kind,
                    changeClass = change.changeClass,
                    targetType = change.target.type,
                    targetId = change.target.id,
                    targetName = change.target.name,
                    effect = change.effect,
                    result = result,
                    detail = detail,
                    requestIds = ids,
                    undo = undo,
                )
            )
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is AdminApiException -> listOfNotNull(e.message, e.apiMessage).joinToString(": ")
        else -> e.javaClass.simpleName + (e.message?.let { ": ${it.take(200)}" } ?: "")
    }
}
