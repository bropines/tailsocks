package io.github.bropines.tailscaled.admin.api

import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The policy, DNS-configuration and webhook calls the configuration tabs make. */
class ConfigBackendTest {

    @Test
    fun validateAnswersTwoHundredEitherWay() = runBlocking {
        val t = FakeTransport().on(
            "POST", "/acl/validate", null, false,
            status(200, ""),
            status(200, """{"message":"test(s) failed","data":[{"user":"a@example.com","errors":["want: Drop, got: Accept"]}]}"""),
            status(400, """{"message":"line 3, column 5: invalid character"}"""),
        )
        val b = testBackend(t)
        assertTrue(b.validatePolicy("{}").ok)
        val failed = b.validatePolicy("{}")
        assertFalse(failed.ok)
        assertEquals("test(s) failed", failed.message)
        assertEquals(listOf("a@example.com: want: Drop, got: Accept"), failed.details)
        val bad = b.validatePolicy("{")
        assertFalse(bad.ok)
        assertEquals("line 3, column 5: invalid character", bad.message)
        val req = t.requests.first()
        assertEquals("application/hujson", req.headers["Content-Type"])
        assertTrue("a validation changes nothing and may be retried", req.idempotent)
    }

    @Test
    fun aRefusedValidationNarrowsReadingNotWriting() = runBlocking {
        val t = FakeTransport().on("POST", "/acl/validate", null, true, status(403, """{"message":"forbidden"}"""))
        val b = testBackend(t)
        runCatching { b.validatePolicy("{}") }
        assertEquals(Access.NONE, b.capabilities.value.access(AdminArea.POLICY))
    }

    @Test
    fun previewAsksByQueryAndSendsThePolicy() = runBlocking {
        val t = FakeTransport().ok("POST", "/acl/preview", """{"matches":[{"users":["*"],"ports":["*:*"],"lineNumber":4}],"type":"ipport","previewFor":"100.64.0.1:22"}""")
        val p = testBackend(t).previewPolicy("{\"acls\":[]}", PolicyPreviewType.IP_PORT, "100.64.0.1:22")
        assertEquals(4, p.matches.single().lineNumber)
        val req = t.requests.single()
        assertTrue(req.url, req.url.contains("type=ipport"))
        assertTrue(req.url, req.url.contains("previewFor=100.64.0.1%3A22"))
        assertEquals("{\"acls\":[]}", req.body)
    }

    @Test
    fun aPolicyWriteCarriesTheQuotedEtag() = runBlocking {
        val t = FakeTransport().on("POST", "/tailnet/-/acl", null, true, { HttpResponse(200, "{}", mapOf("ETag" to "\"f00\"")) })
        val written = testBackend(t).setPolicyFile("{}", "abc123")
        assertEquals("\"f00\"", written.etag)
        val req = t.requests.single()
        assertEquals("\"abc123\"", req.headers["If-Match"])
        assertEquals("application/hujson", req.headers["Accept"])
        assertFalse("a write is never sent twice", req.idempotent)
        assertEquals("\"e\"", TailscaleBackend.quotedEtag("\"e\""))
        assertEquals("W/\"e\"", TailscaleBackend.quotedEtag("W/\"e\""))
    }

    @Test
    fun theWholeDnsConfigurationGoesInOneCall() = runBlocking {
        val t = FakeTransport().ok("POST", "/dns/configuration", recorded("dns_configuration.json"))
        testBackend(t).setDnsConfiguration(
            DnsConfiguration(
                nameservers = listOf(DnsResolver("1.1.1.1", useWithExitNode = true), DnsResolver("https://dns.example/q")),
                splitDns = mapOf("corp.example" to listOf(DnsResolver("10.0.0.53", false))),
                searchPaths = listOf("corp.example"),
                preferences = DnsConfigPreferences(overrideLocalDNS = true, magicDNS = true),
            )
        )
        val body = AppJson.parseToJsonElement(t.requests.single().body!!).jsonObject
        val ns = body["nameservers"]!!.jsonArray
        assertEquals("1.1.1.1", ns[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("true", ns[0].jsonObject["useWithExitNode"]!!.jsonPrimitive.content)
        assertNull("an unknown flag is left to the server's default", ns[1].jsonObject["useWithExitNode"])
        assertEquals("10.0.0.53", body["splitDNS"]!!.jsonObject["corp.example"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("true", body["preferences"]!!.jsonObject["overrideLocalDNS"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutTheCombinedEndpointTheFeatureGoes() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/dns/configuration", null, true, status(404, """{"message":"not found"}"""))
            .ok("GET", "/dns/preferences", """{"magicDNS":true}""")
            .ok("GET", "/dns/nameservers", """{"dns":["1.1.1.1"]}""")
            .ok("GET", "/dns/split-dns", """{"corp.example":["10.0.0.53"]}""")
            .ok("GET", "/dns/searchpaths", """{"searchPaths":["corp.example"]}""")
        val b = testBackend(t)
        assertTrue(b.capabilities.value.has(BackendFeature.DNS_CONFIGURATION))
        val cfg = b.dnsConfiguration()
        assertEquals(listOf("1.1.1.1"), cfg.nameserverAddresses)
        assertTrue(cfg.magicDns)
        assertFalse(b.capabilities.value.has(BackendFeature.DNS_CONFIGURATION))
    }

    @Test
    fun aWebhookTestIsAcceptedWithTwoHundredTwo() = runBlocking {
        val t = FakeTransport().on("POST", "/webhooks/123/test", null, true, status(202))
        testBackend(t).testWebhook("123")
        assertEquals(1, t.requests.size)
    }
}
