package io.github.bropines.tailscaled.admin.api

import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OAuth clients and invites against the schema's paths and bodies. */
class InvitesBackendTest {

    private fun json(req: HttpRequest) = AppJson.parseToJsonElement(req.body!!)

    @Test
    fun anOauthClientCarriesItsScopesAndTags() = runBlocking {
        val t = FakeTransport().ok("POST", "/tailnet/-/keys", """{"id":"kNEW","keyType":"client","key":"tskey-client-kNEW-s3cr3t","scopes":["devices:core","dns:read"],"tags":["tag:ci"]}""")
        val created = testBackend(t).createOAuthClient(OAuthClientRequest("ci runner", listOf("devices:core", "dns:read"), listOf("tag:ci")))
        val body = json(t.requests.single()).jsonObject
        assertEquals("client", body["keyType"]!!.jsonPrimitive.content)
        assertEquals("ci runner", body["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("devices:core", "dns:read"), body["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("tag:ci"), body["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("no auth-key fields on a client", body.containsKey("capabilities") || body.containsKey("expirySeconds"))
        assertEquals("tskey-client-kNEW-s3cr3t", created.key)
        assertEquals(KeyType.CLIENT, created.type)
    }

    @Test
    fun aRefusedClientNarrowsOauthKeysNotAuthKeys() = runBlocking {
        val t = FakeTransport().on("POST", "/keys", null, true, status(403, """{"message":"no"}"""))
        val b = testBackend(t)
        runCatching { b.createOAuthClient(OAuthClientRequest("x", listOf("dns:read"))) }
        assertEquals(Access.READ, b.capabilities.value.access(AdminArea.OAUTH_KEYS))
        assertEquals(Access.UNKNOWN, b.capabilities.value.access(AdminArea.AUTH_KEYS))
    }

    @Test
    fun userInvitesAreABareArray() = runBlocking {
        val t = FakeTransport().ok(
            "GET", "/tailnet/-/user-invites",
            """[{"id":"29214","role":"admin","tailnetId":12345,"inviterId":34567,"email":"user@example.com","lastEmailSentAt":"2024-05-09T16:23:26.91778771Z","inviteUrl":"https://login.tailscale.com/uinv/abc"},
                {"id":"29215","role":"member","tailnetId":12345,"inviterId":34567},
                {"id":["broken"]}]""",
        )
        val list = testBackend(t).listUserInvites()
        assertEquals(listOf("29214", "29215"), list.items.map { it.id })
        assertEquals(UserRole.ADMIN, list.items[0].userRole)
        assertTrue(list.items[0].emailed)
        assertFalse(list.items[1].emailed)
        assertNull(list.items[1].inviteUrl)
        assertEquals("one broken entry costs that entry", 1, list.issues.size)
    }

    @Test
    fun aUserInviteIsSentAsAListOfOne() = runBlocking {
        val t = FakeTransport().ok("POST", "/tailnet/-/user-invites", """[{"id":"9","role":"auditor","inviteUrl":"https://login.tailscale.com/uinv/x"}]""")
        val inv = testBackend(t).createUserInvite("  ", UserRole.AUDITOR)
        val body = json(t.requests.single()) as JsonArray
        val first = body.single().jsonObject
        assertEquals("auditor", first["role"]!!.jsonPrimitive.content)
        assertFalse("a blank email is left out: the server makes a link", first.containsKey("email"))
        assertEquals("https://login.tailscale.com/uinv/x", inv.inviteUrl)
    }

    @Test
    fun resendAndDeleteGoToTheInvitesOwnPath() = runBlocking {
        val t = FakeTransport().ok("POST", "/user-invites/29214/resend", "{}").ok("DELETE", "/user-invites/29214", "{}")
        val b = testBackend(t)
        b.resendUserInvite("29214")
        b.deleteUserInvite("29214")
        assertEquals(listOf("POST", "DELETE"), t.requests.map { it.method })
        assertTrue(t.requests.all { it.url.startsWith("https://api.tailscale.com/api/v2/user-invites/29214") })
    }

    @Test
    fun deviceSharesListCreateAndDelete() = runBlocking {
        val t = FakeTransport()
            .ok("GET", "/device/n1/device-invites", """[{"id":"12345","deviceId":11055,"multiUse":false,"allowExitNode":true,"accepted":true,"acceptedBy":{"id":33223,"loginName":"someone@example.com"}}]""")
            .ok("POST", "/device/n1/device-invites", """[{"id":"12346","inviteUrl":"https://login.tailscale.com/admin/invite/code","multiUse":true}]""")
            .ok("DELETE", "/device-invites/12345", "{}")
        val b = testBackend(t)
        val list = b.listDeviceInvites("n1").items
        assertTrue(list.single().isAccepted)
        assertEquals("someone@example.com", list.single().acceptedBy?.loginName)
        val created = b.createDeviceInvite("n1", DeviceInviteRequest(email = "", multiUse = true, allowExitNode = false))
        val body = (json(t.requestsTo("POST", "/device-invites").single()) as JsonArray).single() as JsonObject
        assertEquals("true", body["multiUse"]!!.jsonPrimitive.content)
        assertEquals("false", body["allowExitNode"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("email"))
        assertEquals("https://login.tailscale.com/admin/invite/code", created.inviteUrl)
        b.deleteDeviceInvite("12345")
        assertEquals(1, t.requestsTo("DELETE", "/device-invites/12345").size)
    }

    @Test
    fun aWriteIsNotRetriedOnAServerError() = runBlocking {
        val t = FakeTransport().on("POST", "/device-invites", null, true, status(502))
        val r = runCatching { testBackend(t).createDeviceInvite("n1", DeviceInviteRequest()) }
        assertTrue(r.exceptionOrNull() is AdminApiException.Server)
        assertEquals(1, t.requests.size)
    }
}
