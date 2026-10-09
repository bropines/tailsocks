package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.status
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The /api/v2 backend against answers recorded from a main-branch build of the 0.30 line. */
class HeadscaleV2BackendTest {

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

    @Test
    fun devicesTakeTheirOnlineStateFromV1AndTaggedOnesHaveNoOwner() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/tailnet/-/devices", null, true, hs("main", "devices_tagged"))
            .on("GET", "/api/v1/node", null, true, hs("main", "v1_nodes"))
        val (c, d, tagged) = v2Backend(t).listDevices().items
        assertEquals("node-c", c.name)
        assertTrue("v1 says online", c.isOnline)
        assertTrue(d.isOnline)
        assertEquals("linux", c.os)
        assertEquals("1.102.5-1-t5fb2a81b0", c.clientVersion)
        assertEquals("alice", c.user)
        assertEquals("renamed-m", tagged.name)
        assertFalse(tagged.isOnline)
        assertNull("v2 names tagged devices' owner tagged-devices: nobody", tagged.user)
        assertEquals(listOf("tag:test"), tagged.tags)
        assertTrue(t.requests[0].url.startsWith("http://127.0.0.1:18030/api/v2/tailnet/-/devices"))
    }

    @Test
    fun withoutV1TheOnlineStateComesFromLastSeen() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/tailnet/-/devices", null, true, hs("main", "devices_tagged"))
            .on("GET", "/api/v1/node", null, true, status(404, "404 page not found"))
        val (c, _, tagged) = v2Backend(t).listDevices().items
        assertTrue("no lastSeen: connected", c.isOnline)
        assertFalse("lastSeen is sent only for an offline device", tagged.isOnline)
    }

    @Test
    fun usersAreAskedForWithoutAType() = runBlocking {
        // type=all comes back empty from Headscale (recorded: users_all).
        assertEquals("""{"users":[]}""", hsText("main", "users_all").trim())
        val t = FakeTransport().on("GET", "/tailnet/-/users", null, true, hs("main", "users"))
        val users = v2Backend(t).listUsers().items
        assertEquals(listOf("alice", "bob"), users.map { it.loginName })
        assertFalse(t.requests.single().url.contains("type="))
        assertEquals(1, users[0].deviceCount)
    }

    @Test
    fun whatTheServerSilentlyIgnoresIsRefusedHere() = runBlocking {
        val t = FakeTransport()
        val b = v2Backend(t)
        assertEquals(BackendFeature.DEVICE_DEAUTHORIZE, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceAuthorized("3", false) } }.feature)
        assertEquals(BackendFeature.DEVICE_UNTAG, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceTags("3", emptyList()) } }.feature)
        assertEquals(BackendFeature.DEVICE_KEY_EXPIRY_ENABLE, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setDeviceKeyExpiryDisabled("3", false) } }.feature)
        assertTrue(t.requests.isEmpty())
        // What the server says when de-authorizing does reach it.
        assertTrue(hsText("main", "authorize_false").contains("does not support de-authorizing"))
    }

    @Test
    fun tailscaleShapedWritesGoToV2() = runBlocking {
        val t = FakeTransport()
            .ok("POST", "/device/3/name", "{}")
            .ok("POST", "/device/3/tags", "{}")
            .ok("POST", "/device/3/key", "{}")
            .ok("POST", "/device/3/routes", """{"advertisedRoutes":[],"enabledRoutes":["10.8.0.0/24"]}""")
            .ok("DELETE", "/device/3", "{}")
        val b = v2Backend(t)
        b.renameDevice("3", "renamed-m")
        b.setDeviceTags("3", listOf("tag:test"))
        b.setDeviceKeyExpiryDisabled("3", true)
        assertEquals(listOf("10.8.0.0/24"), b.setDeviceRoutes("3", listOf("10.8.0.0/24")).enabledRoutes)
        b.deleteDevice("3")
        assertEquals(
            listOf("/api/v2/device/3/name", "/api/v2/device/3/tags", "/api/v2/device/3/key", "/api/v2/device/3/routes", "/api/v2/device/3"),
            t.requests.map { it.target },
        )
        assertEquals("true", t.requests[2].json()["keyExpiryDisabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun expiringADeviceGoesThroughV1() = runBlocking {
        val t = FakeTransport().on("POST", "/api/v1/node/3/expire", null, true, hs("main", "v1_expire"))
        v2Backend(t).expireDevice("3")
        assertEquals("/api/v1/node/3/expire", t.requests.single().target)
    }

    @Test
    fun aDeletedDeviceIsNotFound() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/device/3", null, true, hs("main", "device_after_delete"))
            .on("GET", "/api/v1/node/3", null, true, status(404, """{"title":"Not Found","status":404,"detail":"node not found"}"""))
        assertThrows<AdminApiException.NotFound> { runBlocking { v2Backend(t).getDevice("3") } }
        Unit
    }

    @Test
    fun taggedKeysThroughV2UntaggedOnesForAUserThroughV1() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/tailnet/-/keys", null, true, hs("main", "key_create_tagged"))
            .ok("GET", "/api/v1/user", """{"users":[{"id":"3","name":"dave"}]}""")
            .on("POST", "/api/v1/preauthkey", null, true, hs("main", "v1_preauthkey_create"))
        val b = v2Backend(t)
        val tagged = b.createAuthKey(AuthKeyRequest(description = "bench tagged", expirySeconds = 3600, reusable = true, tags = listOf("tag:server")))
        assertEquals("4", tagged.id)
        assertTrue(tagged.key!!.startsWith("hskey-auth-8f27a0045e79-"))
        val create = t.requests[0].json()["capabilities"]!!.jsonObject["devices"]!!.jsonObject["create"]!!.jsonObject
        assertEquals("tag:server", create["tags"]!!.jsonArray.single().jsonPrimitive.content)

        val forDave = b.createAuthKey(AuthKeyRequest(expirySeconds = 86400, reusable = true, user = "3"))
        assertEquals("6", forDave.id)
        assertEquals("3", forDave.userId)
        assertEquals("3", t.requests.last().json()["user"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUntaggedKeyWithoutAUserIsNotSent() = runBlocking {
        // The server's answer when it is: tags are required without a user-owned API key.
        assertTrue(hsText("main", "key_create_untagged").contains("expected required property tags"))
        val t = FakeTransport()
        assertThrows<AdminApiException.BadRequest> { runBlocking { v2Backend(t).createAuthKey(AuthKeyRequest()) } }
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun keysAndOAuthClientsListTogetherAndRevokingKeepsTheKeyInvalid() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/tailnet/-/keys/4", null, true, hs("main", "key_after_delete"))
            .on("GET", "/tailnet/-/keys", null, true, hs("main", "keys"))
            .ok("DELETE", "/tailnet/-/keys/4", "{}")
        val b = v2Backend(t)
        val keys = b.listKeys().items.associateBy { it.id }
        assertEquals("client", keys.getValue("6ca9b77a50e4").keyType)
        assertEquals(listOf("devices:core:read"), keys.getValue("6ca9b77a50e4").scopes)
        assertEquals(true, keys.getValue("3").invalid)
        b.deleteKey("4")
        val after = b.getKey("4")
        assertTrue(after.isRevoked)
        assertEquals(true, after.invalid)
    }

    @Test
    fun thePolicyIsWrittenWithIfMatchAsHuJson() = runBlocking {
        val etag = "\"69aa7cf0dc30c5b15611e17052920b5b926ead907933fc237434878264f8b6de\""
        val t = FakeTransport()
            .on("GET", "/tailnet/-/acl", null, true, hs("main", "acl_before"))
            .on("POST", "/tailnet/-/acl", null, false, hs("main", "acl_set"), hs("main", "acl_set_stale"))
        val b = v2Backend(t)
        val before = b.policyFile()
        assertEquals(etag, before.etag)
        assertTrue(before.text.contains("Headscale default policy"))
        val saved = b.setPolicyFile("{\n  \"acls\": [],\n}\n", before.etag!!)
        val req = t.requests.last()
        assertEquals(etag, req.headers["If-Match"])
        assertEquals("application/hujson", req.headers["Content-Type"])
        assertEquals("{\n  \"acls\": [],\n}\n", req.body)
        assertEquals("\"5ebe3b33d178f8cf5776bce26aaa51903b73fd53c20adc27447f67bff6a0d457\"", saved.etag)
        assertThrows<AdminApiException.PreconditionFailed> { runBlocking { b.setPolicyFile("{}", "\"0000\"") } }
        Unit
    }

    @Test
    fun aFileModePolicyIsReadOnlyFromTheSettingsAlready() = runBlocking {
        val fileMode = hsText("main", "settings").replace("\"aclsExternallyManagedOn\":false", "\"aclsExternallyManagedOn\":true")
        val t = FakeTransport()
            .on("GET", "/version", null, true, hs("main", "version"))
            .ok("GET", "/tailnet/-/settings", fileMode)
            .on("GET", "/api/v1/apikey", null, true, hs("main", "v1_apikeys"))
        val b = v2Backend(t)
        val caps = b.refreshCapabilities()
        assertEquals(PolicyMode.FILE, b.server.value.policyMode)
        assertFalse(caps.has(BackendFeature.POLICY_WRITE))
        assertFalse(caps.canWrite(AdminArea.POLICY))
        assertTrue(caps.canRead(AdminArea.POLICY))
        assertThrows<AdminApiException.Unsupported> { runBlocking { b.setPolicyFile("{}", "*") } }
        assertTrue(t.requests.none { it.method == "POST" })
    }

    @Test
    fun aRefusedPolicyWriteLearnsTheFileMode() = runBlocking {
        val t = FakeTransport().on("POST", "/tailnet/-/acl", null, true,
            status(400, """{"message":"update is disabled for modes other than 'database'","status":400}"""))
        val b = v2Backend(t)
        assertEquals(BackendFeature.POLICY_WRITE, assertThrows<AdminApiException.Unsupported> { runBlocking { b.setPolicyFile("{}", "*") } }.feature)
        assertEquals(PolicyMode.FILE, b.server.value.policyMode)
    }

    @Test
    fun theServerReportsADevelopmentBuildInDatabaseMode() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/version", null, true, hs("main", "version"))
            .on("GET", "/tailnet/-/settings", null, true, hs("main", "settings"))
            .on("GET", "/api/v1/apikey", null, true, hs("main", "v1_apikeys"))
        val b = v2Backend(t)
        val caps = b.refreshCapabilities()
        val server = b.server.value
        assertEquals(BackendKind.HEADSCALE_V2, server.kind)
        assertTrue(server.version!!.development)
        assertEquals(PolicyMode.DATABASE, server.policyMode)
        assertEquals(0, server.nodeKeyDays)
        assertEquals("hskey-api-0a1b2c3d4e5f-***", caps.ownKeyId)
        assertTrue(caps.has(BackendFeature.POLICY_WRITE))
        assertTrue(server.canRejectRegistration)
    }

    @Test
    fun errorsReadTailscalesBodyAndHuma() = runBlocking {
        val t = FakeTransport()
            .on("GET", "/tailnet/-/devices", null, true, hs("main", "unauthorized"))
            .on("DELETE", "/api/v1/user/3", null, true, hs("main", "v1_user_delete_missing"))
            .on("POST", "/api/v1/user/3/rename/alice", null, true, hs("main", "v1_user_rename_taken"))
        val b = v2Backend(t)
        assertEquals("unauthorized", assertThrows<AdminApiException.Unauthorized> { runBlocking { b.listDevices() } }.apiMessage)
        val missing = assertThrows<AdminApiException.NotFound> { runBlocking { b.deleteUser("3") } }
        assertEquals("deleting user: user not found", missing.apiMessage)
        val taken = assertThrows<AdminApiException.Server> { runBlocking { b.renameUser("3", "alice") } }
        assertTrue(taken.apiMessage!!.contains("UNIQUE constraint failed"))
    }

    @Test
    fun registrationAndRejectionGoThroughV1() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/api/v1/auth/register", null, true, hs("main", "v1_register"))
            .on("POST", "/api/v1/auth/reject", null, true, hs("main", "v1_reject_missing"))
        val b = v2Backend(t)
        val device = b.registerNode("hskey-authreq-0123456789abcdef01234567", "dave")
        assertEquals("disposable-m", device.name)
        assertEquals("dave", device.user)
        assertThrows<AdminApiException.NotFound> { runBlocking { b.rejectRegistration("hskey-authreq-AAAAAAAAAAAAAAAAAAAAAAAA") } }
        Unit
    }
}
