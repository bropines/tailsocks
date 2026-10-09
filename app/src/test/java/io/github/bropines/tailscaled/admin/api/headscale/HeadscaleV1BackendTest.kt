package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.status
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The /api/v1 adapter against answers recorded from Headscale 0.29.4. */
class HeadscaleV1BackendTest {

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.simpleName}, got $e", e)
        }
        fail("expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------------ devices

    @Test
    fun nodesBecomeDevicesAndTaggedOnesHaveNoOwner() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/node", null, true, hs("v029", "nodes_tagged"))
        val list = v1Backend(t).listDevices()
        assertTrue(list.isComplete)
        val (a, _, tagged) = list.items
        assertEquals("node-a", a.name)
        assertEquals("node-a", a.shortName)
        assertEquals("1", a.pathId)
        assertEquals("alice", a.user)
        assertTrue(a.isOnline)
        assertNull("lastSeen is for offline devices", a.lastSeen)
        assertEquals(true, a.keyExpiryDisabled)
        assertEquals("100.64.0.1", a.ipv4)
        assertEquals("fd7a:115c:a1e0::1", a.ipv6)
        assertEquals(true, a.authorized)

        assertEquals("renamed-1", tagged.name)
        assertEquals("disposable-1", tagged.hostname)
        assertNull("the synthetic tagged-devices user is nobody", tagged.user)
        assertEquals(listOf("tag:test"), tagged.tags)
        assertFalse(tagged.isOnline)
        assertEquals("2026-10-09T18:18:50.522002665Z", tagged.lastSeen)
        assertEquals("Go's zero time is no expiry", true, tagged.keyExpiryDisabled)
        assertEquals("/api/v1/node", t.requests.single().target)
        assertEquals("Bearer ${HeadscaleFixtures.KEY_029}", t.requests.single().headers["Authorization"])
    }

    @Test
    fun releasesBefore028CarryTheirTagsInThreeFields() {
        val old = HsNode(id = "7", givenName = "old", forcedTags = listOf("tag:a"), validTags = listOf("tag:b", "tag:a"), invalidTags = listOf("tag:x"),
            user = HsUser("2", "bob"))
        val d = old.toDevice()
        assertEquals(listOf("tag:a", "tag:b"), d.tags)
        assertNull("a tagged node has no owner, whatever user created it", d.user)
        assertEquals(listOf("tag:new"), HsNode(tags = listOf("tag:new"), forcedTags = listOf("tag:old")).effectiveTags)
    }

    @Test
    fun renameGoesInThePathAndTheServersRefusalIsShown() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/rename/renamed-1", null, true, hs("v029", "node_rename"))
            .on("POST", "/rename/Bad%20Name", null, true, hs("v029", "node_rename_bad"))
        val b = v1Backend(t)
        b.renameDevice("3", "renamed-1")
        assertEquals("/api/v1/node/3/rename/renamed-1", t.requests.last().target)
        val e = assertThrows<AdminApiException.BadRequest> { runBlocking { b.renameDevice("3", "Bad Name") } }
        assertTrue(e.apiMessage!!, e.apiMessage!!.contains("not a valid DNS label"))
    }

    @Test
    fun whatHeadscaleCannotDoIsRefusedBeforeSending() = runBlocking {
        val t = FakeTransport()
        val b = v1Backend(t)
        assertEquals(BackendFeature.DEVICE_UNTAG, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceTags("3", emptyList()) } }.feature)
        assertEquals(BackendFeature.DEVICE_DEAUTHORIZE, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceAuthorized("3", false) } }.feature)
        assertEquals(BackendFeature.DEVICE_KEY_EXPIRY_ENABLE, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceKeyExpiryDisabled("3", false) } }.feature)
        b.setDeviceAuthorized("3", true)
        assertTrue("nothing reached the server", t.requests.isEmpty())
        val caps = b.capabilities.value
        assertFalse(caps.has(BackendFeature.DEVICE_UNTAG))
        assertFalse(caps.has(BackendFeature.DEVICE_DEAUTHORIZE))
        assertFalse(caps.has(BackendFeature.DEVICE_KEY_EXPIRY_ENABLE))
        assertTrue(caps.has(BackendFeature.DEVICE_KEY_EXPIRY_DISABLE))
        assertTrue(caps.has(BackendFeature.DEVICE_EXPIRE))
        assertFalse(caps.has(BackendFeature.DNS))
        assertFalse(caps.has(BackendFeature.AUDIT_LOGS))
        assertFalse(caps.has(BackendFeature.SERVICES))
        assertTrue(caps.has(BackendFeature.HEADSCALE_ADMIN))
    }

    @Test
    fun tagsAndTheServersOwnRefusals() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/api/v1/node/3/tags", null, false, hs("v029", "node_tags"), hs("v029", "node_tags_undefined"))
        val b = v1Backend(t)
        b.setDeviceTags("3", listOf("tag:test"))
        assertEquals("tag:test", t.requests.last().json()["tags"]!!.jsonArray.single().jsonPrimitive.content)
        val e = assertThrows<AdminApiException.BadRequest> { runBlocking { b.setDeviceTags("3", listOf("tag:undefined")) } }
        assertTrue(e.apiMessage!!.contains("invalid or not permitted"))
    }

    @Test
    fun keyExpiryIsSwitchedOffWithTheQueryAndTheBody() = runBlocking {
        val t = FakeTransport().on("POST", "/api/v1/node/3/expire", null, true, hs("v029", "node_disable_expiry"))
        v1Backend(t).setDeviceKeyExpiryDisabled("3", true)
        val r = t.requests.single()
        assertEquals("/api/v1/node/3/expire?disableExpiry=true", r.target)
        assertEquals("true", r.json()["disableExpiry"]!!.jsonPrimitive.content)
    }

    @Test
    fun before029KeyExpiryCannotBeSwitchedOff() = runBlocking {
        val b = v1Backend(FakeTransport(), HeadscaleVersion.parse("v0.28.0"))
        assertEquals(BackendFeature.DEVICE_KEY_EXPIRY_DISABLE, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceKeyExpiryDisabled("3", true) } }.feature)
        assertFalse(b.capabilities.value.has(BackendFeature.DEVICE_KEY_EXPIRY_DISABLE))
    }

    @Test
    fun expireIsAPlainPost() = runBlocking {
        val t = FakeTransport().on("POST", "/api/v1/node/3/expire", null, true, hs("v029", "node_expire"))
        v1Backend(t).expireDevice("3")
        assertEquals("/api/v1/node/3/expire", t.requests.single().target)
        assertEquals("{}", t.requests.single().body)
    }

    @Test
    fun routesAreApprovedOnTheNode() = runBlocking {
        val t = FakeTransport().on("POST", "/approve_routes", null, true, hs("v029", "node_routes"))
        val routes = v1Backend(t).setDeviceRoutes("3", listOf("10.9.0.0/24"))
        assertEquals(listOf("10.9.0.0/24"), routes.enabledRoutes)
        assertEquals("10.9.0.0/24", t.requests.single().json()["routes"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun release025SwitchesEachRouteOfTheRoutesApi() = runBlocking {
        val routes = """{"routes":[
            {"id":"11","prefix":"10.0.0.0/24","advertised":true,"enabled":false},
            {"id":"12","prefix":"0.0.0.0/0","advertised":true,"enabled":true},
            {"id":"13","prefix":"::/0","advertised":true,"enabled":true}]}"""
        val t = FakeTransport()
            .ok("GET", "/api/v1/node/5/routes", routes)
            .ok("POST", "/api/v1/routes/", "{}")
        val b = v1Backend(t, HeadscaleVersion.V0_25)
        b.setDeviceRoutes("5", listOf("10.0.0.0/24"))
        val writes = t.requests.filter { it.method == "POST" }.map { it.target }
        assertEquals(listOf("/api/v1/routes/11/enable", "/api/v1/routes/12/disable", "/api/v1/routes/13/disable"), writes)
        assertEquals(listOf("10.0.0.0/24", "0.0.0.0/0", "::/0"), b.deviceRoutes("5").advertisedRoutes)
    }

    @Test
    fun aDeletedNodeReadsAsNotFound() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/node/999", null, true, hs("v029", "node_missing"))
        assertThrows<AdminApiException.NotFound> { runBlocking { v1Backend(t).getDevice("999") } }
        Unit
    }

    // ------------------------------------------------------------------ keys

    @Test
    fun preAuthKeysAreAuthKeysWithoutTheirSecrets() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/preauthkey", null, true, hs("v029", "preauthkeys_after_expire"))
        val keys = v1Backend(t).listKeys().items.associateBy { it.id }
        val used = keys.getValue("1")
        assertEquals("auth", used.keyType)
        assertNull(used.key)
        assertEquals("hskey-auth-svBi7RSO58Pi-***", used.description)
        assertEquals("used single-use key", true, used.invalid)
        assertEquals("1", used.userId)
        val tagged = keys.getValue("4")
        assertNull(tagged.userId)
        assertEquals(listOf("tag:server"), tagged.tags)
        assertEquals(true, tagged.createOptions?.ephemeral)
        assertEquals("expired by the test run", true, keys.getValue("5").invalid)
        assertEquals("one call lists every key from 0.28", 1, t.requests.size)
    }

    @Test
    fun anUntaggedKeyIsMadeForTheChosenUser() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/api/v1/user", null, true, hs("v029", "users"))
            .on("POST", "/api/v1/preauthkey", null, true, hs("v029", "preauthkey_create"))
        val key = v1Backend(t).createAuthKey(AuthKeyRequest(expirySeconds = 86400, reusable = true, user = "1"))
        val body = t.requests.last().json()
        assertEquals("1", body["user"]!!.jsonPrimitive.content)
        assertEquals("true", body["reusable"]!!.jsonPrimitive.content)
        assertEquals("2026-10-10T18:30:00Z", body["expiration"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("aclTags"))
        assertTrue("the secret comes back once", key.key!!.startsWith("hskey-auth-tXkSxdTPquZM-"))
    }

    @Test
    fun anUntaggedKeyWithoutAUserIsNotSent() = runBlocking {
        val t = FakeTransport()
        assertThrows<AdminApiException.BadRequest> { runBlocking { v1Backend(t).createAuthKey(AuthKeyRequest()) } }
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun revokingExpiresById() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/api/v1/preauthkey", null, true, hs("v029", "preauthkeys"))
            .ok("POST", "/api/v1/preauthkey/expire", "{}")
        v1Backend(t).deleteKey("5")
        assertEquals("5", t.requests.last().json()["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun release025ListsKeysPerUserAndExpiresWithTheSecret() = runBlocking {
        val t = FakeTransport()
            .ok("GET", "/api/v1/user", """{"users":[{"id":"1","name":"alice"},{"id":"2","name":"bob"}]}""")
            .on("GET", "/api/v1/preauthkey", "user=alice", true, { HttpResponse(200, """{"preAuthKeys":[{"user":"alice","id":"3","key":"0123456789abcdef0123456789abcdef0123456789abcdef","reusable":false,"used":false,"expiration":"2026-12-01T00:00:00Z"}]}""") })
            .on("GET", "/api/v1/preauthkey", "user=bob", true, { HttpResponse(200, """{"preAuthKeys":[]}""") })
            .ok("POST", "/api/v1/preauthkey/expire", "{}")
        val b = v1Backend(t, HeadscaleVersion.V0_25)
        val key = b.listKeys().items.single()
        assertEquals("alice", key.userId)
        assertNull("a legacy list's secret never reaches the model", key.key)
        assertEquals("01234567…", key.description)
        b.deleteKey("3")
        val body = t.requests.last().json()
        assertEquals("alice", body["user"]!!.jsonPrimitive.content)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef", body["key"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ users

    @Test
    fun usersAreMembersWithDeviceCountsFromTheNodes() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/api/v1/user", null, true, hs("v029", "users"))
            .on("GET", "/api/v1/node", null, true, hs("v029", "nodes_tagged"))
        val users = v1Backend(t).listUsers().items
        assertEquals(listOf("alice", "bob"), users.map { it.loginName })
        assertEquals(1, users[0].deviceCount)
        assertEquals(true, users[0].currentlyConnected)
        assertEquals("member", users[0].type)
    }

    @Test
    fun usersAreMadeRenamedAndDeleted() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/api/v1/user/4/rename/carol2", null, true, hs("v029", "user_rename"))
            .on("POST", "/api/v1/user/999/rename/", null, true, hs("v029", "user_rename_missing"))
            .on("POST", "/api/v1/user", null, true, hs("v029", "user_create"))
            .ok("DELETE", "/api/v1/user/4", "{}")
        val b = v1Backend(t)
        val carol = b.createUser("carol", "Carol", "carol@example.com")
        assertEquals("carol", carol.loginName)
        assertEquals("Carol", t.requests.last().json()["displayName"]!!.jsonPrimitive.content)
        assertEquals("carol2", b.renameUser("4", "carol2").loginName)
        assertThrows<AdminApiException.NotFound> { runBlocking { b.renameUser("999", "nobody") } }
        b.deleteUser("4")
        assertEquals("/api/v1/user/4", t.requests.last().target)
    }

    // ------------------------------------------------------------------ policy

    @Test
    fun thePolicyComesWithAnEtagOfItsText() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/policy", null, true, hs("v029", "policy"))
        val b = v1Backend(t)
        val p = b.policyFile()
        assertTrue(p.text.contains("// bench policy"))
        assertEquals(PolicyText.etag(p.text), p.etag)
        assertEquals(listOf("tag:server", "tag:test"), b.policyTags())
    }

    @Test
    fun noPolicyYetIsTheDefaultOne() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/policy", null, true, hs("v029", "policy_before"))
        assertEquals(PolicyText.DEFAULT, v1Backend(t).policyFile().text)
    }

    @Test
    fun aStaleEtagIsRefusedBeforeWriting() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/policy", null, true, hs("v029", "policy"))
        assertThrows<AdminApiException.PreconditionFailed> { runBlocking { v1Backend(t).setPolicyFile("{}", "\"stale\"") } }
        assertTrue(t.requests.none { it.method == "PUT" })
    }

    @Test
    fun aCurrentEtagWrites() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/api/v1/policy", null, true, hs("v029", "policy"))
            .ok("PUT", "/api/v1/policy", """{"policy":"{}","updatedAt":"2026-10-09T18:40:00Z"}""")
        val b = v1Backend(t)
        val etag = b.policyFile().etag
        val saved = b.setPolicyFile("{}", etag!!)
        assertEquals("{}", t.requests.last().json()["policy"]!!.jsonPrimitive.content)
        assertEquals(PolicyText.etag("{}"), saved.etag)
        assertEquals(PolicyMode.DATABASE, b.server.value.policyMode)
    }

    @Test
    fun aPolicyFileOnTheServerMakesThePolicyReadOnly() = runBlocking {
        val t = FakeTransport().on("PUT", "/api/v1/policy", null, true,
            status(500, """{"code":2, "message":"update is disabled for modes other than 'database'", "details":[]}"""))
        val b = v1Backend(t)
        assertTrue(b.capabilities.value.has(BackendFeature.POLICY_WRITE))
        val e = assertThrows<AdminApiException.Unsupported> { runBlocking { b.setPolicyFile("{}", "*") } }
        assertEquals(BackendFeature.POLICY_WRITE, e.feature)
        assertEquals(PolicyMode.FILE, b.server.value.policyMode)
        assertFalse(b.capabilities.value.has(BackendFeature.POLICY_WRITE))
        assertEquals(Access.READ, b.capabilities.value.access(AdminArea.POLICY))
        assertFalse(b.capabilities.value.canWrite(AdminArea.POLICY))
        // Known now: the next attempt does not even reach the server.
        assertThrows<AdminApiException.Unsupported> { runBlocking { b.setPolicyFile("{}", "*") } }
        assertEquals(1, t.requests.size)
    }

    @Test
    fun aPolicyCheckReportsWhyItIsRefused() = runBlocking {
        val t = FakeTransport().on("POST", "/api/v1/policy/check", null, true, hs("v029", "policy_check_bad"))
        val e = assertThrows<AdminApiException.BadRequest> { runBlocking { v1Backend(t).checkPolicy("""{"acls":[]}""") } }
        assertTrue(e.apiMessage!!.contains("invalid ACL action"))
    }

    // ------------------------------------------------------------------ registration and API keys

    @Test
    fun registrationFrom029IsJson() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/api/v1/auth/register", null, false, hs("v029", "register"), hs("v029", "register_again"))
        val b = v1Backend(t)
        val id = "hskey-authreq-hVdrWB37PIjSteb2J2yw0qr0"
        val device = b.registerNode(id, "carol2")
        assertEquals("disposable-1", device.name)
        assertEquals("carol2", device.user)
        val body = t.requests.last().json()
        assertEquals(id, body["authId"]!!.jsonPrimitive.content)
        assertEquals("carol2", body["user"]!!.jsonPrimitive.content)
        // Registered once already: the waiting request is gone.
        assertThrows<AdminApiException.NotFound> { runBlocking { b.registerNode(id, "carol2") } }
        Unit
    }

    @Test
    fun registrationBefore029UsesTheNodeEndpoint() = runBlocking {
        val t = FakeTransport().on("POST", "/api/v1/node/register", null, true, hs("v029", "register_legacy"))
        val b = v1Backend(t, HeadscaleVersion.parse("v0.27.1"))
        b.registerNode("AbCdEfGhIjKlMnOpQrStUvWx", "carol2")
        assertEquals("/api/v1/node/register?user=carol2&key=AbCdEfGhIjKlMnOpQrStUvWx", t.requests.single().target)
        assertFalse(b.server.value.canRejectRegistration)
        assertThrows<AdminApiException.Unsupported> { runBlocking { b.rejectRegistration("AbCdEfGhIjKlMnOpQrStUvWx") } }
        Unit
    }

    @Test
    fun aRegistrationIsRejected() = runBlocking {
        val t = FakeTransport().on("POST", "/api/v1/auth/reject", null, false, hs("v029", "auth_reject"), hs("v029", "auth_reject_missing"))
        val b = v1Backend(t)
        b.rejectRegistration("hskey-authreq-AAAAAAAAAAAAAAAAAAAAAAAB")
        assertEquals("hskey-authreq-AAAAAAAAAAAAAAAAAAAAAAAB", t.requests.last().json()["authId"]!!.jsonPrimitive.content)
        assertThrows<AdminApiException.NotFound> { runBlocking { b.rejectRegistration("hskey-authreq-AAAAAAAAAAAAAAAAAAAAAAAA") } }
        Unit
    }

    @Test
    fun apiKeysAreListedCreatedAndExpiredByPrefix() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/api/v1/apikey", null, true, hs("v029", "apikeys"))
            .on("POST", "/api/v1/apikey/expire", null, true, { HttpResponse(200, "{}") })
            .on("POST", "/api/v1/apikey", null, true, hs("v029", "apikey_create"))
        val b = v1Backend(t)
        val keys = b.listApiKeys().items
        assertEquals(listOf("hskey-api-AbCd-EfGh123-***", "hskey-api-ZyXwVuTs9876-***"), keys.map { it.prefix })
        assertTrue(b.createApiKey(NOW + 86_400_000).startsWith("hskey-api-Nn0Pp1Qq2-Rr-"))
        assertEquals("2026-10-10T18:30:00Z", t.requests.last().json()["expiration"]!!.jsonPrimitive.content)
        b.expireApiKey("hskey-api-ZyXwVuTs9876-***")
        assertEquals("ZyXwVuTs9876", t.requests.last().json()["prefix"]!!.jsonPrimitive.content)
    }

    @Test
    fun theConsolesOwnKeyIsKnownWithItsExpiry() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/version", null, true, hs("v029", "version"))
            .on("GET", "/api/v1/apikey", null, true, hs("v029", "apikeys"))
        val caps = v1Backend(t, null).refreshCapabilities()
        assertEquals("hskey-api-AbCd-EfGh123-***", caps.ownKeyId)
        assertEquals("2027-01-07T17:07:31.360050141Z", caps.credentialExpires)
        assertEquals(BackendKind.HEADSCALE_V1, caps.backend)
        assertEquals("0.29.4", v1BackendVersion(t))
        assertTrue("a Headscale API key may do anything; read-only is the app's", caps.canWrite(AdminArea.DEVICES))
        assertTrue(caps.has(BackendFeature.DEVICE_KEY_EXPIRY_DISABLE))
    }

    private suspend fun v1BackendVersion(t: FakeTransport): String? = v1Backend(t, null).refreshServer().version?.label

    @Test
    fun aRefusedKeyIsUnauthorized() = runBlocking {
        val t = FakeTransport().on("GET", "/api/v1/node", null, true, hs("v029", "unauthorized"))
        val e = assertThrows<AdminApiException.Unauthorized> { runBlocking { v1Backend(t).listDevices() } }
        assertEquals("Unauthorized", e.apiMessage)
    }
}
