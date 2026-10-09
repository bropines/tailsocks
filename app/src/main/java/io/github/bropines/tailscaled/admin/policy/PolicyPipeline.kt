package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.api.PolicyValidation
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.PolicyEvidence
import io.github.bropines.tailscaled.admin.safety.PolicyGate
import io.github.bropines.tailscaled.admin.safety.TargetType
import kotlinx.coroutines.CancellationException

/** The pipeline's steps, in the order they run; each one runs only when the one before passed. */
enum class PolicyStep { LOCAL, SERVER, DIFF, RISKS, ACCESS }

/** Who "this phone" is in the policy: its user (unless the node is tagged) and its tailnet address. */
data class AccessSubject(val login: String?, val ipv4: String?, val ports: List<Int> = DEFAULT_PORTS) {
    companion object {
        /** SSH and HTTPS: what a phone most often reaches and is reached on. */
        val DEFAULT_PORTS = listOf(22, 443)
    }
}

/**
 * One /acl/preview asked of the current policy and of the candidate. [lost] is what the
 * candidate no longer matches: for a user, destinations it could reach; for an ip:port,
 * sources that could reach it. Compared as the rules spell them, so a rule rewritten to say
 * the same thing differently shows up too — it warns, it never blocks.
 */
data class AccessProbe(
    val type: PolicyPreviewType,
    val previewFor: String,
    val before: PolicyPreview? = null,
    val after: PolicyPreview? = null,
    val error: Throwable? = null,
) {
    val lost: List<String>
        get() {
            val b = before ?: return emptyList()
            val a = after ?: return emptyList()
            fun of(p: PolicyPreview) = p.matches.flatMap { if (type == PolicyPreviewType.USER) it.ports else it.users }.toSet()
            return (of(b) - of(a)).sorted()
        }
}

enum class AccessSkip {
    /** The phone is not a node of this tailnet (or the daemon is not running): nothing to check. */
    NOT_IN_TAILNET,
}

data class AccessReport(val probes: List<AccessProbe>, val skipped: AccessSkip? = null) {
    val ran: Boolean get() = skipped == null && probes.isNotEmpty() && probes.all { it.error == null }
    val lostCount: Int get() = probes.sumOf { it.lost.size }
}

/**
 * One candidate policy on its way through the checks, against [base] — the version the edit
 * started from, whose ETag the write will carry. [running] is the step under way; null once
 * the pipeline stopped, either done or at the first step that failed.
 */
data class PolicyReview(
    val base: PolicyFile,
    val candidate: String,
    val isRevert: Boolean = false,
    val running: PolicyStep? = PolicyStep.LOCAL,
    val local: List<HuIssue>? = null,
    val validation: PolicyValidation? = null,
    /** The validate call itself failed (403, network): the server never said yes. */
    val validationError: Throwable? = null,
    val diff: List<LineEdit>? = null,
    val risks: List<RiskFinding>? = null,
    val access: AccessReport? = null,
) {
    val changed: Boolean get() = diff?.let { LineDiff.changed(it) } == true

    /** Every check passed and there is something to send. */
    val passed: Boolean
        get() = running == null && local?.isEmpty() == true && validation?.ok == true && access != null &&
            changed && !base.etag.isNullOrBlank()

    /** The step that stopped the pipeline, if one did. */
    val failedAt: PolicyStep?
        get() = when {
            running != null -> null
            local == null || local.isNotEmpty() -> PolicyStep.LOCAL
            validation?.ok != true -> PolicyStep.SERVER
            else -> null
        }

    fun evidence(): PolicyEvidence = PolicyEvidence(
        candidateSha256 = PolicyGate.sha256(candidate),
        baseEtag = base.etag,
        localOk = local?.isEmpty() == true,
        serverValid = validation?.ok == true,
        linesAdded = diff?.let(LineDiff::added) ?: 0,
        linesRemoved = diff?.let(LineDiff::removed) ?: 0,
        riskFindings = risks?.size ?: 0,
        accessChecked = access?.ran == true,
        accessLost = access?.lostCount ?: 0,
    )
}

/** The server refused the candidate when it was checked again right before the write. */
class PolicyRejectedException(val validation: PolicyValidation) :
    Exception(validation.message ?: validation.details.firstOrNull() ?: "policy rejected")

object PolicyPipeline {

    /**
     * Runs the checks in order — the local parse, /acl/validate, the diff, the risk lint, the
     * access previews — publishing the review after each, and stops at the first that fails.
     */
    suspend fun review(
        backend: AdminBackend,
        base: PolicyFile,
        candidate: String,
        subject: AccessSubject?,
        isRevert: Boolean = false,
        publish: (PolicyReview) -> Unit = {},
    ): PolicyReview {
        var r = PolicyReview(base, candidate, isRevert)
        publish(r)

        val issues = HuJson.check(candidate)
        r = r.copy(local = issues, running = if (issues.isEmpty()) PolicyStep.SERVER else null)
        publish(r)
        if (issues.isNotEmpty()) return r

        val validated = attempt { backend.validatePolicy(candidate) }
        val ok = validated.getOrNull()?.ok == true
        r = r.copy(validation = validated.getOrNull(), validationError = validated.exceptionOrNull(), running = if (ok) PolicyStep.DIFF else null)
        publish(r)
        if (!ok) return r

        val diff = LineDiff.diff(LineDiff.lines(base.text), LineDiff.lines(candidate))
        r = r.copy(diff = diff, running = PolicyStep.RISKS)
        publish(r)

        val risks = PolicyLint.introduced(HuJson.parseOrNull(base.text), HuJson.parse(candidate))
        r = r.copy(risks = risks, running = PolicyStep.ACCESS)
        publish(r)

        r = r.copy(access = access(backend, base.text, candidate, subject), running = null)
        publish(r)
        return r
    }

    /** The "don't lock yourself out" previews: this phone's user and address, before and after. */
    suspend fun access(backend: AdminBackend, before: String, after: String, subject: AccessSubject?): AccessReport {
        if (subject == null || (subject.login == null && subject.ipv4 == null)) return AccessReport(emptyList(), AccessSkip.NOT_IN_TAILNET)
        val asks = buildList {
            subject.login?.let { add(PolicyPreviewType.USER to it) }
            subject.ipv4?.let { ip -> subject.ports.forEach { add(PolicyPreviewType.IP_PORT to "$ip:$it") } }
        }
        return AccessReport(asks.map { (type, what) ->
            val b = attempt { backend.previewPolicy(before, type, what) }
            val a = if (b.isSuccess) attempt { backend.previewPolicy(after, type, what) } else b
            AccessProbe(type, what, b.getOrNull(), a.getOrNull(), b.exceptionOrNull() ?: a.exceptionOrNull())
        })
    }

    /** runCatching that lets cancellation through. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** The words of a policy change, resolved by the caller in the user's language. */
data class PolicyChangeTexts(
    val title: String,
    val effect: String,
    /** The phrase typed back to confirm, "apply policy". */
    val phrase: String,
    val linesLabel: String,
    val linesBefore: String,
    val linesAfter: String,
    val warnings: List<String>,
)

object PolicyChanges {

    /**
     * The write a passed [review] allows, as a POLICY-class [PlannedChange]: the server checks
     * the candidate once more, then it is sent with If-Match on the review's base; [verify]
     * re-reads the file and compares it with the candidate by meaning. [onWritten] gets what
     * the server holds afterwards. A review that did not pass gives a change the runner refuses.
     */
    fun plan(
        review: PolicyReview,
        texts: PolicyChangeTexts,
        tailnetLabel: String,
        onWritten: (PolicyFile) -> Unit = {},
    ): PlannedChange {
        val candidate = review.candidate
        val etag = review.base.etag.orEmpty()
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.POLICY_FILE,
                changeClass = ChangeClass.POLICY,
                target = ChangeTarget(TargetType.TAILNET, "-", tailnetLabel),
                title = texts.title,
                effect = texts.effect,
                diff = listOf(DiffLine(texts.linesLabel, texts.linesBefore, texts.linesAfter)),
                warnings = texts.warnings,
                confirmPhrase = texts.phrase,
                policy = if (review.passed) review.evidence() else null,
            ),
            apply = { b ->
                // The review may be minutes old: the server judges the very text once more.
                val v = b.validatePolicy(candidate)
                if (!v.ok) throw PolicyRejectedException(v)
                onWritten(b.setPolicyFile(candidate, etag))
            },
            verify = { b -> HuJson.sameMeaning(b.policyFile().text, candidate) },
            isUndo = review.isRevert,
        )
    }
}
