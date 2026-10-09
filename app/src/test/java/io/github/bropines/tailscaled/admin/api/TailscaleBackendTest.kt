package io.github.bropines.tailscaled.admin.api

import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TailscaleBackendTest {

    private fun body(req: HttpRequest): JsonObject = AppJson.parseToJsonElement(req.body!!).jsonObject

    @Test
    fun deviceListUsesDashAndAsksForAllFields() = runBlocking {
        val t = FakeTransport().ok("GET", "/tailnet/-/devices", recorded("devices.json"))
        val list = testBackend(t).listDevices(listOf("fields" to "default", "isEphemeral" to "false"))
        val url = t.requests.single().url
        assertTrue(url, url.startsWith("https://api.tailscale.com/api/v2/tailnet/-/devices"))
        assertTrue("routes come with the list", url.contains("fields=all"))
        assertFalse("a caller's fields never reach the list", url.contains("fields=default"))
        assertTrue(url.contains("isEphemeral=false"))
        assertEquals("Bearer tskey-api-kAPI1CNTRL-secretpart", t.requests.single().headers["Authorization"])
        assertEquals(3, list.items.size)
    }

    @Test
    fun aListRefusedWithAllFieldsFallsBackForTheSession() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/tailnet/-/devices", "fields=all", true, status(404, """{"message":"not found"}"""))
            .ok("GET", "/tailnet/-/devices", recorded("devices.json"))
        val backend = testBackend(t)
        assertEquals(3, backend.listDevices().items.size)
        assertEquals(2, t.requests.size)
        assertFalse(t.requests[1].url.contains("fields"))
        backend.listDevices()
        assertEquals("the plain list from then on", 3, t.requests.size)
        assertFalse(t.requests[2].url.contains("fields"))
    }

    @Test
    fun aRefusedCredentialIsNotRetriedWithoutFields() = runBlocking {
        val t = FakeTransport().on("GET", "/tailnet/-/devices", null, true, status(401, """{"message":"bad token"}"""))
        try {
            testBackend(t).listDevices()
            fail()
        } catch (_: AdminApiException.Unauthorized) {
        }
        assertEquals(1, t.requests.size)
    }

    @Test
    fun oneBrokenDeviceIsReportedNotSilentlyDropped() = runBlocking {
        val t = FakeTransport().ok("GET", "/devices", recorded("devices.json"))
        val list = testBackend(t).listDevices()
        assertEquals(listOf("n292kg92CNTRL", "nPHONE11CNTRL", "nSHARED1CNTRL"), list.items.map { it.nodeId })
        assertFalse(list.isComplete)
        val issue = list.issues.single()
        assertEquals(2, issue.index)
        assertEquals("77", issue.id)
    }

    @Test
    fun deviceFieldsFromTheSchema() = runBlocking {
        val t = FakeTransport().ok("GET", "/devices", recorded("devices.json"))
        val (server, phone, shared) = testBackend(t).listDevices().items
        assertTrue(server.isOnline)
        assertEquals("pangolin", server.shortName)
        assertEquals("tailfe8c.ts.net", server.dnsSuffix)
        assertEquals("100.87.74.78", server.ipv4)
        assertEquals("fd7a:115c:a1e0:ac82:4843:ca90:697d:c36e", server.ipv6)
        assertEquals("n292kg92CNTRL", server.pathId)
        assertFalse(phone.isOnline)
        assertEquals(true, phone.multipleConnections)
        assertEquals("node key not signed", phone.tailnetLockError)
        assertEquals(false, phone.authorized)
        assertTrue(shared.isShared)
    }

    @Test
    fun oneDeviceAsksForAllFieldsAndFallsBack() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/device/n292kg92CNTRL", "fields=all", true, status(404, """{"message":"not found"}"""))
            .ok("GET", "/device/n292kg92CNTRL", recorded("device_all_fields.json"))
        val device = testBackend(t).getDevice("n292kg92CNTRL")
        assertEquals(2, t.requests.size)
        assertTrue(t.requests[0].url.contains("fields=all"))
        assertFalse(t.requests[1].url.contains("fields"))
        assertEquals(listOf("10.0.0.0/16"), device.enabledRoutes)
    }

    @Test
    fun keysListAllAndRevokedIsATimestamp() = runBlocking {
        val t = FakeTransport().ok("GET", "/tailnet/-/keys", recorded("keys.json"))
        val keys = testBackend(t).listKeys()
        assertTrue(t.requests.single().url.endsWith("/keys?all=true"))
        assertTrue(keys.isComplete)
        assertEquals(5, keys.items.size)
        val byId = keys.items.associateBy { it.id }
        assertTrue(byId.getValue("kAUTH2CNTRL").isRevoked)
        assertFalse(byId.getValue("kAUTH1CNTRL").isRevoked)
        assertEquals(KeyType.API, byId.getValue("kAPI1CNTRL").type)
        assertEquals(KeyType.CLIENT, byId.getValue("kCLIENT1CNTRL").type)
        assertEquals(KeyType.FEDERATED, byId.getValue("kFED1CNTRL").type)
        val create = byId.getValue("kAUTH1CNTRL").createOptions!!
        assertEquals(true, create.reusable)
        assertEquals(listOf("tag:server"), create.tags)
    }

    @Test
    fun authKeySwitchesAreIndependent() = runBlocking {
        val t = FakeTransport().ok("POST", "/tailnet/-/keys", """{"id":"kNEW","key":"tskey-auth-kNEW-secret","keyType":"auth"}""")
        val key = testBackend(t).createAuthKey(
            AuthKeyRequest(description = "ci", expirySeconds = 3600, reusable = true, ephemeral = true, preauthorized = false, tags = listOf("tag:ci"))
        )
        assertEquals("tskey-auth-kNEW-secret", key.key)
        val create = body(t.requests.single())["capabilities"]!!.jsonObject["devices"]!!.jsonObject["create"]!!.jsonObject
        assertEquals("true", create["reusable"]!!.jsonPrimitive.content)
        assertEquals("true", create["ephemeral"]!!.jsonPrimitive.content)
        assertEquals("false", create["preauthorized"]!!.jsonPrimitive.content)
        assertEquals("tag:ci", create["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("auth", body(t.requests.single())["keyType"]!!.jsonPrimitive.content)
    }

    @Test
    fun usersOfEveryTypeAndTheSchemasRoles() = runBlocking {
        val t = FakeTransport().ok("GET", "/tailnet/-/users", recorded("users.json"))
        val users = testBackend(t).listUsers().items
        assertTrue(t.requests.single().url.endsWith("/users?type=all"))
        assertEquals(UserRole.OWNER, users[0].userRole)
        assertEquals(UserRole.IT_ADMIN, users[1].userRole)
        assertEquals(UserStatus.NEEDS_APPROVAL, users[1].userStatus)
        assertEquals("bob", users[1].name)
        assertEquals(UserStatus.IDLE, users[2].userStatus)
    }

    @Test
    fun roleChangeSendsTheWireName() = runBlocking {
        val t = FakeTransport().ok("POST", "/users/uBOB1CNTRL/role", "{}")
        testBackend(t).setUserRole("uBOB1CNTRL", UserRole.NETWORK_ADMIN)
        assertEquals("network-admin", body(t.requests.single())["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun webhooksUseSubscriptionsAndKeepTheSecret() = runBlocking {
        val t = FakeTransport()
            .ok("GET", "/tailnet/-/webhooks", recorded("webhooks.json"))
            .ok("POST", "/tailnet/-/webhooks", """{"endpointId":"999","endpointUrl":"https://x.example/h","subscriptions":["nodeCreated"],"secret":"whsec-once"}""")
        val backend = testBackend(t)
        val hooks = backend.listWebhooks().items
        assertEquals(3, hooks.single().subscriptions.size)
        assertEquals("slack", hooks.single().providerType)
        val created = backend.createWebhook("https://x.example/h", "", listOf("nodeCreated"))
        assertEquals("whsec-once", created.secret)
        val sent = body(t.requestsTo("POST", "/webhooks").single())
        assertEquals("nodeCreated", sent["subscriptions"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse("subscribedEvents" in sent)
        assertFalse("an empty provider type is left out", "providerType" in sent)
    }

    @Test
    fun settingsArePatchedOneFieldAtATime() = runBlocking {
        val t = FakeTransport()
            .ok("GET", "/tailnet/-/settings", recorded("settings.json"))
            .ok("PATCH", "/tailnet/-/settings", recorded("settings.json"))
        val backend = testBackend(t)
        val s = backend.tailnetSettings()
        assertEquals("regional-routing", s.routeSelection)
        assertEquals(true, s.httpsEnabled)
        backend.updateTailnetSetting(TailnetSettingKey.DEVICES_KEY_DURATION, 30)
        val sent = body(t.requestsTo("PATCH", "/settings").single())
        assertEquals(setOf("devicesKeyDurationDays"), sent.keys)
        assertEquals("30", sent["devicesKeyDurationDays"]!!.jsonPrimitive.content)
    }

    @Test
    fun dnsConfigurationAndItsLegacyFallback() = runBlocking {
        val t = FakeTransport().ok("GET", "/dns/configuration", recorded("dns_configuration.json"))
        val cfg = testBackend(t).dnsConfiguration()
        assertTrue(cfg.magicDns)
        assertEquals(true, cfg.preferences.overrideLocalDNS)
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), cfg.nameserverAddresses)
        assertEquals(mapOf("corp.example" to listOf("10.0.0.53")), cfg.splitDnsAddresses)

        val legacy = FakeTransport()
            .on("GET", "/dns/configuration", null, true, status(404))
            .ok("GET", "/dns/preferences", """{"magicDNS":true}""")
            .ok("GET", "/dns/nameservers", """{"dns":["8.8.8.8"]}""")
            .ok("GET", "/dns/split-dns", """{"corp.example":["10.0.0.53"]}""")
            .ok("GET", "/dns/searchpaths", """{"searchPaths":["corp.example"]}""")
        val old = testBackend(legacy).dnsConfiguration()
        assertTrue(old.magicDns)
        assertEquals(listOf("8.8.8.8"), old.nameserverAddresses)
        assertEquals(listOf("10.0.0.53"), old.splitDnsAddresses["corp.example"])
        assertEquals(listOf("corp.example"), old.searchPaths)
    }

    @Test
    fun splitDnsRemovalSendsAnExplicitNull() = runBlocking {
        val t = FakeTransport().ok("PATCH", "/dns/split-dns", "{}")
        testBackend(t).setSplitDnsDomain("corp.example", null)
        assertEquals("""{"corp.example":null}""", t.requests.single().body)
    }

    @Test
    fun servicesUnderServicesFirst() = runBlocking {
        val t = FakeTransport().ok("GET", "/tailnet/-/services", recorded("services.json"))
        val list = testBackend(t).listServices()
        assertEquals("svc:web", list.items.single().name)
        assertTrue(t.requests.single().url.endsWith("/tailnet/-/services"))

        val old = FakeTransport()
            .on("GET", "/tailnet/-/services", null, true, status(404))
            .ok("GET", "/tailnet/-/vip-services", recorded("services.json"))
        assertEquals(1, testBackend(old).listServices().items.size)
    }

    @Test
    fun missingServiceIsNullAndPutKeepsTheColon() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/services/svc:nope", null, true, status(404))
            .ok("PUT", "/services/svc:web", "{}")
        val backend = testBackend(t)
        assertNull(backend.getService("svc:nope"))
        backend.putService(ApiService(name = "svc:web", ports = listOf("tcp:443")))
        val put = t.requestsTo("PUT", "/services").single()
        assertTrue(put.url.endsWith("/tailnet/-/services/svc:web"))
        assertFalse("no addrs means the server assigns them", "addrs" in body(put))
    }

    @Test
    fun auditLogKeepsOldAndNewOfAnyType() = runBlocking {
        val t = FakeTransport().ok("GET", "/logging/configuration", recorded("audit_log.json"))
        val logs = testBackend(t).auditLog("2026-10-01T00:00:00Z", "2026-10-09T00:00:00Z")
        assertTrue(t.requests.single().url.contains("start=2026-10-01T00%3A00%3A00Z"))
        assertEquals(2, logs.items.size)
        assertEquals("\"old-name\"", logs.items[0].old.toString())
        assertTrue(logs.items[1].new is JsonObject)
    }

    @Test
    fun errorsAreTyped() = runBlocking {
        suspend fun failWith(code: Int, call: suspend TailscaleBackend.() -> Unit): AdminApiException {
            val t = FakeTransport()
                .on("GET", "/", null, true, status(code, """{"message":"server says $code"}"""))
                .on("POST", "/", null, true, status(code, """{"message":"server says $code"}"""))
            try {
                testBackend(t).call()
            } catch (e: AdminApiException) {
                assertEquals("server says $code", e.apiMessage)
                assertEquals("req-$code", e.requestId)
                return e
            }
            fail("no exception for $code")
            throw IllegalStateException()
        }
        assertTrue(failWith(401) { listDevices() } is AdminApiException.Unauthorized)
        assertTrue(failWith(402) { setDeviceAuthorized("n1", true) } is AdminApiException.PaymentRequired)
        val forbidden = failWith(403) { dnsConfiguration() } as AdminApiException.Forbidden
        assertEquals("dns:read", forbidden.missingScope)
        val forbiddenWrite = failWith(403) { setMagicDns(true) } as AdminApiException.Forbidden
        assertEquals("dns", forbiddenWrite.missingScope)
        assertTrue(failWith(404) { getUser("u1") } is AdminApiException.NotFound)
        assertTrue(failWith(409) { setDeviceTags("n1", emptyList()) } is AdminApiException.Conflict)
        assertTrue(failWith(412) { setDeviceTags("n1", emptyList()) } is AdminApiException.PreconditionFailed)
        assertTrue(failWith(400) { setDeviceTags("n1", emptyList()) } is AdminApiException.BadRequest)
    }

    @Test
    fun readsHonourRetryAfterAndBackOff() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val t = FakeTransport().on(
            "GET", "/devices", null, true,
            status(429, headers = mapOf("Retry-After" to "2")),
            status(503),
            { HttpResponse(200, """{"devices":[]}""") },
        )
        testBackend(t, sleeps = sleeps).listDevices()
        assertEquals(3, t.requests.size)
        assertEquals(listOf(2000L, 1000L), sleeps)
    }

    @Test
    fun aRetryAfterTooLongIsNotWaitedOut() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val t = FakeTransport().on("GET", "/devices", null, true, status(429, headers = mapOf("Retry-After" to "3600")))
        try {
            testBackend(t, sleeps = sleeps).listDevices()
            fail()
        } catch (e: AdminApiException.RateLimited) {
            assertEquals(3600L, e.retryAfterSec)
        }
        assertEquals(1, t.requests.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aWriteIsNeverRepeatedOnAServerError() = runBlocking {
        val t = FakeTransport().on("DELETE", "/device/n1", null, true, status(502))
        try {
            testBackend(t).deleteDevice("n1")
            fail()
        } catch (e: AdminApiException.Server) {
            assertEquals(502, e.status)
        }
        assertEquals(1, t.requests.size)
    }

    @Test
    fun aWriteIsRetriedOnlyOnTooManyRequests() = runBlocking {
        val t = FakeTransport().on("DELETE", "/device/n1", null, true, status(429, headers = mapOf("Retry-After" to "1")), status(200))
        testBackend(t).deleteDevice("n1")
        assertEquals(2, t.requests.size)
    }

    @Test
    fun aDroppedWriteIsNotSentAgainOverAnotherRoute() = runBlocking {
        val direct = FakeTransport().ok("DELETE", "/device/n1", "{}").ok("GET", "/devices", """{"devices":[]}""")
        val proxy = FakeTransport().on("DELETE", "/device/n1", null, true, noAnswer()).on("GET", "/devices", null, true, noAnswer())
        val routed = FallbackTransport(proxy, direct)
        val backend = testBackend(routed)
        try {
            backend.deleteDevice("n1")
            fail()
        } catch (e: AdminApiException.Network) {
            assertTrue(e.detail.contains("connection refused"))
        }
        assertTrue("the write must not reach the direct route", direct.requestsTo("DELETE", "/device").isEmpty())
        assertEquals(1, proxy.requestsTo("DELETE", "/device").size)
        backend.listDevices()
        assertEquals("a read may take the other route", 1, direct.requestsTo("GET", "/devices").size)
    }

    @Test
    fun requestIdsAreCollectedForTheAuditLog() = runBlocking {
        val t = FakeTransport().on("POST", "/device/n1/authorized", null, true, status(200, "{}", mapOf("x-tailscale-request-id" to "abc-123")))
        val ids = RequestIds()
        withContext(ids) { testBackend(t).setDeviceAuthorized("n1", false) }
        assertEquals(listOf("abc-123"), ids.ids)
    }

    @Test
    fun oauthMintsOnceAndRemintsAfterA401() = runBlocking {
        var now = 0L
        val t = FakeTransport()
            .on("POST", "/oauth/token", null, true,
                { HttpResponse(200, """{"access_token":"tok-1","token_type":"Bearer","expires_in":3600}""") },
                { HttpResponse(200, """{"access_token":"tok-2","token_type":"Bearer","expires_in":3600}""") })
            .on("GET", "/devices", null, true,
                { HttpResponse(200, """{"devices":[]}""") },
                { HttpResponse(200, """{"devices":[]}""") },
                status(401),
                { HttpResponse(200, """{"devices":[]}""") })
        val backend = testBackend(t, AdminCredential.OAuthClient("kCLIENT1CNTRL", "tskey-client-kCLIENT1CNTRL-s3cret"), clock = { now })
        backend.listDevices()
        now += 60_000
        backend.listDevices()
        assertEquals(1, t.requestsTo("POST", "/oauth/token").size)
        val form = t.requestsTo("POST", "/oauth/token").single().body!!
        assertTrue(form.contains("grant_type=client_credentials"))
        assertTrue(form.contains("client_id=kCLIENT1CNTRL"))
        backend.listDevices()
        assertEquals(2, t.requestsTo("POST", "/oauth/token").size)
        assertEquals("Bearer tok-2", t.requests.last().headers["Authorization"])
    }

    @Test
    fun aSharedTokenCacheSavesTheNextBackendAMint() = runBlocking {
        val cache = OAuthTokenCache()
        val credential = AdminCredential.OAuthClient("kCLIENT1CNTRL", "tskey-client-kCLIENT1CNTRL-s3cret")
        val t = FakeTransport()
            .on("POST", "/oauth/token", null, true, { HttpResponse(200, """{"access_token":"tok-1","token_type":"Bearer","expires_in":3600}""") })
            .ok("GET", "/devices", """{"devices":[]}""")
        fun backend() = TailscaleBackend(credential = credential, transport = t, clock = { 1_000_000L }, io = Dispatchers.Unconfined, tokenCache = cache)
        backend().listDevices()
        backend().listDevices()
        assertEquals(1, t.requestsTo("POST", "/oauth/token").size)
        assertEquals("Bearer tok-1", t.requests.last().headers["Authorization"])
        // Another secret for the same client is another entry.
        TailscaleBackend(credential = AdminCredential.OAuthClient("kCLIENT1CNTRL", "tskey-client-kCLIENT1CNTRL-other"), transport = t, clock = { 1_000_000L },
            io = Dispatchers.Unconfined, tokenCache = cache).listDevices()
        assertEquals(2, t.requestsTo("POST", "/oauth/token").size)
    }

    @Test
    fun aSecretAloneNamesItsClient() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/oauth/token", null, true, { HttpResponse(200, """{"access_token":"tok-1","token_type":"Bearer","expires_in":3600}""") })
            .ok("GET", "/devices", """{"devices":[]}""")
        testBackend(t, AdminCredential.OAuthClient("", "tskey-client-kABC123CNTRL-s3cret")).listDevices()
        assertTrue(t.requestsTo("POST", "/oauth/token").single().body!!.contains("client_id=kABC123CNTRL"))
    }

    @Test
    fun aRefusedOauthClientIsUnauthorized() = runBlocking {
        val t = FakeTransport().on("POST", "/oauth/token", null, true, status(401, """{"message":"invalid client"}"""))
        try {
            testBackend(t, AdminCredential.OAuthClient("kX", "tskey-client-kX-bad")).listDevices()
            fail()
        } catch (e: AdminApiException.Unauthorized) {
            assertEquals("invalid client", e.apiMessage)
        }
        assertTrue(t.requestsTo("GET", "/devices").isEmpty())
    }

    @Test
    fun oauthCapabilitiesComeFromTheClientsOwnKey() = runBlocking {
        val t = FakeTransport()
            .ok("POST", "/oauth/token", """{"access_token":"tok","expires_in":3600}""")
            .ok("GET", "/tailnet/-/keys/kCLIENT1CNTRL", """{"id":"kCLIENT1CNTRL","keyType":"client","scopes":["devices:core:read","dns","logs:configuration:read"],"tags":["tag:admin"]}""")
        val backend = testBackend(t, AdminCredential.OAuthClient("kCLIENT1CNTRL", "tskey-client-kCLIENT1CNTRL-s"))
        val caps = backend.refreshCapabilities()
        assertTrue(caps.canRead(AdminArea.DEVICES))
        assertFalse(caps.canWrite(AdminArea.DEVICES))
        assertTrue(caps.canWrite(AdminArea.DNS))
        assertTrue(caps.canRead(AdminArea.AUDIT_LOGS))
        assertFalse(caps.canRead(AdminArea.USERS))
        assertEquals("kCLIENT1CNTRL", caps.ownKeyId)
        assertEquals(listOf("tag:admin"), caps.credentialTags)
    }

    @Test
    fun aPersonalTokenLearnsItsLimitsFromRefusals() = runBlocking {
        val t = FakeTransport()
            .ok("GET", "/tailnet/-/keys/kAPI1CNTRL", """{"id":"kAPI1CNTRL","keyType":"api","userId":"uAMELIE1CNTRL","expires":"2026-12-19T10:00:00Z"}""")
            .on("GET", "/tailnet/-/users", null, true, status(403))
            .on("POST", "/dns/preferences", null, true, status(403))
        val backend = testBackend(t)
        val caps = backend.refreshCapabilities()
        assertEquals("uAMELIE1CNTRL", caps.ownUserId)
        assertEquals("kAPI1CNTRL", caps.ownKeyId)
        assertEquals("2026-12-19T10:00:00Z", caps.credentialExpires)
        assertEquals(Access.UNKNOWN, caps.access(AdminArea.USERS))
        runCatching { backend.listUsers() }
        runCatching { backend.setMagicDns(true) }
        val after = backend.capabilities.value
        assertFalse(after.canRead(AdminArea.USERS))
        assertTrue(after.canRead(AdminArea.DNS))
        assertFalse(after.canWrite(AdminArea.DNS))
        assertTrue("an untouched area stays open", after.canWrite(AdminArea.DEVICES))
    }

    @Test
    fun aBlankRenameIsNeverSent() = runBlocking {
        val t = FakeTransport()
        try {
            testBackend(t).renameDevice("n1", "  ")
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun policyTagsFromTagOwners() = runBlocking {
        val t = FakeTransport().ok("GET", "/tailnet/-/acl", """{"tagOwners":{"tag:web":["autogroup:admin"],"tag:ci":[]},"acls":[{"action":"accept","src":["tag:other"],"dst":["*:*"]}]}""")
        assertEquals(listOf("tag:ci", "tag:web"), testBackend(t).policyTags())
    }

    @Test
    fun aGarbledListIsADecodeErrorNotAnEmptyList() = runBlocking {
        val t = FakeTransport().ok("GET", "/devices", "<html>captive portal</html>")
        try {
            testBackend(t).listDevices()
            fail()
        } catch (e: AdminApiException.Decode) {
            assertEquals("req-ok", e.requestId)
        }
    }

    @Test
    fun customBaseUrlAndTailnetId() = runBlocking {
        val t = FakeTransport().ok("GET", "/tailnet/T1234CNTRL/devices", """{"devices":[]}""")
        TailscaleBackend(
            credential = AdminCredential.ApiToken("tskey-api-k1-x"),
            transport = t,
            baseUrl = "https://hs.example.org/",
            tailnet = "T1234CNTRL",
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        ).listDevices()
        assertEquals("https://hs.example.org/api/v2/tailnet/T1234CNTRL/devices?fields=all", t.requests.single().url)
    }
}
