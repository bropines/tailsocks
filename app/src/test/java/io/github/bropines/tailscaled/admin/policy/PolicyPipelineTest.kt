package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.api.status
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.admin.safety.AdminAuditLog
import io.github.bropines.tailscaled.admin.safety.AuditResult
import io.github.bropines.tailscaled.admin.safety.ChangeOutcome
import io.github.bropines.tailscaled.admin.safety.GateEvidence
import io.github.bropines.tailscaled.admin.safety.Refusal
import io.github.bropines.tailscaled.admin.safety.SafeChangeRunner
import io.github.bropines.tailscaled.admin.safety.SafetyContext
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.WriteGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PolicyPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = """
        {
          "acls": [
            {"action": "accept", "src": ["group:dev"], "dst": ["tag:web:443"]},
          ],
          "tagOwners": {"tag:web": ["group:dev"]},
        }
    """.trimIndent()
    private val mine = base.replace("[\"tag:web:443\"]", "[\"tag:web:443\", \"tag:web:80\"]")
    private val theirs = base.replace("\"tagOwners\": {\"tag:web\": [\"group:dev\"]}", "\"tagOwners\": {\"tag:web\": [\"group:dev\", \"group:ops\"]}")

    private val texts = PolicyChangeTexts("Apply the policy", "effect", "apply policy", "Lines", "6", "6 (+1 −1)", emptyList())

    private fun acl(text: String, etag: String): () -> HttpResponse = { HttpResponse(200, text, mapOf("ETag" to "\"$etag\"", "X-Tailscale-Request-Id" to "req-$etag")) }

    private fun runner(t: FakeTransport, audit: AdminAuditLog) =
        SafeChangeRunner(testBackend(t), audit, { SafetyContext("p1") }, clock = { 1_500L })

    private fun gates(typed: String = "apply policy") = GateEvidence(true, typed, WriteGrant(1_000L, LockState.CRYPTO_PER_USE))

    @Test
    fun aConflictIsReportedThenMyEditIsReappliedOnTheirVersion() = runBlocking {
        val merged = LineDiff.merge3(LineDiff.lines(base), LineDiff.lines(mine), LineDiff.lines(theirs)).lines!!.joinToString("\n")
        val t = FakeTransport()
            .on("GET", "/acl", null, false, acl(base, "e1"), acl(theirs, "e2"), acl(merged, "e3"))
            .ok("POST", "/acl/validate", "")
            .on("POST", "/acl", null, false, status(412, """{"message":"precondition failed, invalid old hash"}"""), acl(merged, "e3"))
        val backend = testBackend(t)
        val audit = AdminAuditLog(java.io.File(tmp.newFolder(), "audit.jsonl"))
        val run = runner(t, audit)

        val first = backend.policyFile()
        assertEquals("\"e1\"", first.etag)
        val review = PolicyPipeline.review(backend, first, mine, subject = null)
        assertTrue(review.passed)
        assertEquals(AccessSkip.NOT_IN_TAILNET, review.access?.skipped)
        assertEquals(1, LineDiff.added(review.diff!!))

        val out = run.run(PolicyChanges.plan(review, texts, "tail4a2c9.ts.net"), gates())
        assertTrue(out is ChangeOutcome.Failed && out.error is AdminApiException.PreconditionFailed)
        val sent = t.requestsTo("POST", "/acl").last()
        assertEquals("\"e1\"", sent.headers["If-Match"])
        assertEquals("application/hujson", sent.headers["Content-Type"])
        assertEquals(mine, sent.body)

        // Refetch, merge my edit onto theirs, and send that as a new diff against their version.
        val now = backend.policyFile()
        assertEquals("\"e2\"", now.etag)
        val again = PolicyPipeline.review(backend, now, merged, subject = null)
        assertTrue(again.passed)
        assertTrue("their change survives", merged.contains("group:ops"))
        assertTrue("mine too", merged.contains("tag:web:80"))
        val ok = run.run(PolicyChanges.plan(again, texts, "tail4a2c9.ts.net"), gates())
        assertEquals(ChangeOutcome.Applied(true, listOf("req-ok", "req-e3", "req-e3")), ok)
        assertEquals("\"e2\"", t.requestsTo("POST", "/acl").last { !it.url.contains("validate") }.headers["If-Match"])
        assertEquals(listOf(AuditResult.VERIFIED, AuditResult.FAILED), audit.records().map { it.result })
    }

    @Test
    fun aReviewThatFailedCannotBeSent() = runBlocking {
        val t = FakeTransport()
            .ok("POST", "/acl/validate", """{"message":"test(s) failed","data":[{"user":"a@example.com","errors":["address \"tag:db:5432\": want: Drop, got: Accept"]}]}""")
        val backend = testBackend(t)
        val audit = AdminAuditLog(java.io.File(tmp.newFolder(), "audit.jsonl"))
        val review = PolicyPipeline.review(backend, io.github.bropines.tailscaled.admin.api.PolicyFile(base, "\"e1\""), mine, subject = null)
        assertFalse(review.passed)
        assertEquals(PolicyStep.SERVER, review.failedAt)
        assertEquals(listOf("a@example.com: address \"tag:db:5432\": want: Drop, got: Accept"), review.validation!!.details)
        assertNull("the pipeline stopped before the diff", review.diff)

        val out = runner(t, audit).run(PolicyChanges.plan(review, texts, "tailnet"), gates())
        assertEquals(ChangeOutcome.Refused(Refusal.POLICY_PIPELINE), out)
        assertTrue(t.requestsTo("POST", "/acl").none { !it.url.contains("validate") })
    }

    @Test
    fun theLocalCheckStopsBeforeTheServerIsAsked() = runBlocking {
        val t = FakeTransport()
        val review = PolicyPipeline.review(testBackend(t), io.github.bropines.tailscaled.admin.api.PolicyFile(base, "\"e1\""), "{\n \"acls\": [\n}", subject = null)
        assertEquals(PolicyStep.LOCAL, review.failedAt)
        assertEquals(HuIssueKind.MISMATCHED, review.local!!.single().kind)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun theTypedPhraseAndAnUnchangedFileAreGates() = runBlocking {
        val t = FakeTransport().ok("POST", "/acl/validate", "").ok("POST", "/acl", base)
        val backend = testBackend(t)
        val audit = AdminAuditLog(java.io.File(tmp.newFolder(), "audit.jsonl"))
        val file = io.github.bropines.tailscaled.admin.api.PolicyFile(base, "\"e1\"")
        val review = PolicyPipeline.review(backend, file, mine, subject = null)
        assertEquals(ChangeOutcome.Refused(Refusal.TYPED_MISMATCH), runner(t, audit).run(PolicyChanges.plan(review, texts, "t"), gates("apply")))
        val same = PolicyPipeline.review(backend, file, base, subject = null)
        assertFalse("nothing to send", same.passed)
        assertEquals(ChangeOutcome.Refused(Refusal.POLICY_PIPELINE), runner(t, audit).run(PolicyChanges.plan(same, texts, "t"), gates()))
        val noEtag = PolicyPipeline.review(backend, file.copy(etag = null), mine, subject = null)
        assertFalse("a write without an ETag could overwrite anything", noEtag.passed)
    }

    @Test
    fun theAccessCheckSaysWhatThisPhoneLoses() = runBlocking {
        val t = FakeTransport()
            .ok("POST", "/acl/validate", "")
            .on(
                "POST", "/acl/preview", "type=user", false,
                { HttpResponse(200, """{"matches":[{"users":["alex@example.com"],"ports":["tag:web:443","tag:db:5432"],"lineNumber":3}]}""") },
                { HttpResponse(200, """{"matches":[{"users":["alex@example.com"],"ports":["tag:web:443"],"lineNumber":3}]}""") },
            )
            .on("POST", "/acl/preview", "type=ipport", true, { HttpResponse(200, """{"matches":[]}""") })
        val review = PolicyPipeline.review(
            testBackend(t), io.github.bropines.tailscaled.admin.api.PolicyFile(base, "\"e1\""), mine,
            subject = AccessSubject("alex@example.com", "100.101.34.12", listOf(22)),
        )
        val access = review.access!!
        assertTrue(access.ran)
        assertEquals(listOf("tag:db:5432"), access.probes.first { it.type == PolicyPreviewType.USER }.lost)
        assertEquals(1, access.lostCount)
        assertEquals(1, review.evidence().accessLost)
        val ipport = t.requestsTo("POST", "/acl/preview").map { it.url.substringAfter('?') }
        assertTrue(ipport.toString(), ipport.any { it.contains("previewFor=100.101.34.12%3A22") })
    }
}
