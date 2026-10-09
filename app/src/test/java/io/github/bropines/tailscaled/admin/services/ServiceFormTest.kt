package io.github.bropines.tailscaled.admin.services

import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a service definition must be before it is sent, and how a rename is sent. */
class ServiceFormTest {

    @Test
    fun namesGetTheirPrefixAndPortsTheirProtocol() {
        assertEquals("svc:web", ServiceForm.name("web"))
        assertEquals("svc:web", ServiceForm.name(" SVC:Web "))
        assertEquals(listOf("tcp:443", "tcp:80", "tcp:8000-8010"), ServiceForm.ports("443, tcp:80 tcp:8000-8010, 443"))
    }

    @Test
    fun whatIsRefused() {
        assertNull(ServiceForm.validate("svc:web", listOf("tcp:443")))
        assertNull(ServiceForm.validate("svc:a-b-1", listOf("tcp:1-65535", "do-not-validate"), "100.100.100.1"))
        assertEquals(ServiceForm.Problem.NAME, ServiceForm.validate("svc:", listOf("tcp:443")))
        assertEquals(ServiceForm.Problem.NAME, ServiceForm.validate("svc:-web", listOf("tcp:443")))
        assertEquals(ServiceForm.Problem.NAME, ServiceForm.validate("svc:we b", listOf("tcp:443")))
        assertEquals(ServiceForm.Problem.PORTS, ServiceForm.validate("svc:web", emptyList()))
        assertEquals(ServiceForm.Problem.PORTS, ServiceForm.validate("svc:web", listOf("udp:53")))
        assertEquals(ServiceForm.Problem.PORTS, ServiceForm.validate("svc:web", listOf("tcp:70000")))
        assertEquals(ServiceForm.Problem.PORTS, ServiceForm.validate("svc:web", listOf("tcp:90-80")))
        assertEquals(ServiceForm.Problem.ADDRESS, ServiceForm.validate("svc:web", listOf("tcp:443"), "100.100.300.1"))
        assertEquals(ServiceForm.Problem.DISPLAY_NAME, ServiceForm.validate("svc:web", listOf("tcp:443"), displayName = "x".repeat(65)))
    }

    @Test
    fun normalizedDropsBlanksAndDuplicates() {
        val s = ServiceForm.normalized(ApiService("Web", displayName = " ", comment = "  hi ", ports = listOf("tcp:443", "tcp:443", " "), tags = listOf("tag:a", "tag:a")))
        assertEquals("svc:web", s.name)
        assertNull(s.displayName)
        assertEquals("hi", s.comment)
        assertEquals(listOf("tcp:443"), s.ports)
        assertEquals(listOf("tag:a"), s.tags)
    }

    @Test
    fun theDefinitionMatchesAsASet() {
        val a = ApiService("svc:web", ports = listOf("tcp:80", "tcp:443"), tags = listOf("tag:b", "tag:a"), comment = null)
        assertTrue(ServiceChanges.same(a, a.copy(ports = listOf("tcp:443", "tcp:80"), tags = listOf("tag:a", "tag:b"), comment = "")))
        assertFalse(ServiceChanges.same(a, a.copy(ports = listOf("tcp:443"))))
    }

    @Test
    fun renameIsHighEditIsMediumDeleteIsHigh() {
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.SERVICE_RENAME))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.classify(ChangeKind.SERVICE_PUBLISH))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.classify(ChangeKind.SERVICE_DELETE))
    }

    @Test
    fun aRenameIsAPutToTheOldNameWithTheNewOneInTheBody() = runBlocking {
        val t = FakeTransport().ok("PUT", "/services/", "{}")
        testBackend(t).putService(ApiService("svc:new", addrs = listOf("100.100.100.1", "fd7a::1"), ports = listOf("tcp:443")), pathName = "svc:old")
        val r = t.requests.single()
        assertTrue(r.url, r.url.endsWith("/tailnet/-/services/svc:old"))
        assertEquals("svc:new", AppJson.parseToJsonElement(r.body!!).jsonObject["name"]!!.jsonPrimitive.content)
    }
}
