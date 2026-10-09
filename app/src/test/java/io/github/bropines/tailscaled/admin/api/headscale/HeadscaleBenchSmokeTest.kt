package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.HttpRequest
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.HttpTransport
import io.github.bropines.tailscaled.admin.api.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Both Headscale backends against a live server — the local test bench (Headscale 0.29.4 on
 * :18029, a main-branch build on :18030). Skipped unless TAILSOCKS_HS_BENCH names the directory
 * with the bench's API key files (hs029.apikey, hsmain.apikey) and the servers answer:
 *
 *     TAILSOCKS_HS_BENCH=/path/to/headscale ./gradlew :app:testDebugUnitTest --tests '*HeadscaleBenchSmokeTest*'
 *
 * The writes clean up after themselves: a user, a key and a node made for the test are gone
 * when it ends, failed or not.
 */
class HeadscaleBenchSmokeTest {

    private val dir = System.getenv("TAILSOCKS_HS_BENCH")?.let(::File)

    /** Plain HttpURLConnection: what the JVM has, no proxy. */
    private object JvmTransport : HttpTransport {
        override fun execute(request: HttpRequest): HttpResponse = try {
            val c = URL(request.url).openConnection() as HttpURLConnection
            c.requestMethod = request.method
            c.connectTimeout = 5_000
            c.readTimeout = 15_000
            request.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            request.body?.let { b ->
                c.doOutput = true
                c.outputStream.use { it.write(b.toByteArray()) }
            }
            val status = c.responseCode
            val body = (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
            HttpResponse(status, body, headers)
        } catch (e: java.io.IOException) {
            throw TransportException(e.javaClass.simpleName)
        }
    }

    private fun key(name: String): String {
        val d = dir
        assumeTrue("TAILSOCKS_HS_BENCH not set", d != null)
        val f = File(d, name)
        assumeTrue("no $f", f.isFile)
        return f.readText().trim()
    }

    private fun up(base: String): Boolean = runCatching {
        (URL("$base/version").openConnection() as HttpURLConnection).run { connectTimeout = 2_000; responseCode } == 200
    }.getOrDefault(false)

    private fun v1(base: String, key: String) = HeadscaleV1Backend(key, JvmTransport, base, io = Dispatchers.IO)
    private fun v2(base: String, key: String) = HeadscaleV2Backend(key, JvmTransport, base, io = Dispatchers.IO)

    /** A waiting registration, as the debug endpoint makes one (the bench has no spare devices). */
    private fun pendingRegistration(base: String, key: String, user: String): String {
        val id = "hskey-authreq-" + (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        val r = JvmTransport.execute(
            HttpRequest(
                "POST", "$base/api/v1/debug/node",
                mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                """{"user":"$user","key":"$id","name":"smoke-${id.takeLast(6)}"}""",
            )
        )
        assertEquals(r.body, 200, r.status)
        return id
    }

    @Test
    fun release029ThroughTheV1Adapter() = runBlocking {
        val base = "http://127.0.0.1:18029"
        val key = key("hs029.apikey")
        assumeTrue("bench 0.29 is down", up(base))
        val b = v1(base, key)

        val (kind, version) = b.detectServer()
        assertEquals(BackendKind.HEADSCALE_V1, kind)
        assertEquals("0.29.4", version.label)
        val caps = b.refreshCapabilities()
        assertTrue("the console's own key is found", caps.credentialExpires != null)
        assertTrue(b.listDevices().items.any { it.isOnline })
        assertTrue(b.listUsers().items.map { it.loginName }.containsAll(listOf("alice", "bob")))
        val policy = b.policyFile()
        assertTrue(b.validatePolicy(policy.text).ok)
        assertFalse(b.validatePolicy("""{"acls":[{"action":"jump","src":["*"],"dst":["*:*"]}]}""").ok)

        val name = "smoke" + Random.nextInt(10_000, 99_999)
        var userId: String? = null
        var nodeId: String? = null
        try {
            userId = b.createUser(name, "Smoke", null).id
            assertEquals("$name-r", b.renameUser(userId, "$name-r").loginName)
            val created = b.createAuthKey(AuthKeyRequest(expirySeconds = 3600, reusable = true, user = userId))
            assertTrue(created.key!!.startsWith("hskey-auth-"))
            b.deleteKey(created.id)
            assertTrue("expired keys read as invalid", b.getKey(created.id).invalid == true)

            val authId = pendingRegistration(base, key, "$name-r")
            val device = b.registerNode(authId, "$name-r")
            nodeId = device.pathId
            assertEquals("$name-r", device.user)
            b.renameDevice(nodeId, "$name-node")
            assertEquals("$name-node", b.getDevice(nodeId).name)
            b.setDeviceKeyExpiryDisabled(nodeId, true)
            assertEquals(true, b.getDevice(nodeId).keyExpiryDisabled)
            if ("tag:test" in b.policyTags()) {
                b.setDeviceTags(nodeId, listOf("tag:test"))
                val tagged = b.getDevice(nodeId)
                assertEquals(listOf("tag:test"), tagged.tags)
                assertEquals(null, tagged.user)
            }
            b.expireDevice(nodeId)
            b.deleteDevice(nodeId)
            nodeId = null
            try {
                b.getDevice(device.pathId)
                throw AssertionError("deleted device still there")
            } catch (_: AdminApiException.NotFound) {
            }
        } finally {
            nodeId?.let { runCatching { b.deleteDevice(it) } }
            userId?.let { runCatching { b.deleteUser(it) } }
        }
    }

    @Test
    fun theMainBranchThroughApiV2() = runBlocking {
        val base = "http://127.0.0.1:18030"
        val key = key("hsmain.apikey")
        assumeTrue("bench main is down", up(base))
        val b = v2(base, key)
        val detected = v1(base, key).detectServer()
        assertEquals(BackendKind.HEADSCALE_V2, detected.first)

        b.refreshCapabilities()
        assertEquals(PolicyMode.DATABASE, b.server.value.policyMode)
        val devices = b.listDevices().items
        assertTrue("online comes from v1", devices.any { it.isOnline })
        assertTrue(b.listUsers().items.isNotEmpty())

        // The policy written back as it is, with its ETag; a stale ETag is refused.
        val policy = b.policyFile()
        val saved = b.setPolicyFile(policy.text, policy.etag!!)
        assertTrue(saved.etag != null)
        try {
            b.setPolicyFile(policy.text, "\"stale\"")
            throw AssertionError("a stale ETag was accepted")
        } catch (_: AdminApiException.PreconditionFailed) {
        }
        assertTrue(b.validatePolicy(policy.text).ok)

        val tags = b.policyTags()
        if ("tag:server" in tags) {
            val k = b.createAuthKey(AuthKeyRequest(description = "smoke", expirySeconds = 600, tags = listOf("tag:server")))
            b.deleteKey(k.id)
            assertTrue(b.getKey(k.id).isRevoked)
        }
        val alice = b.listUsers().items.first()
        val forAlice = b.createAuthKey(AuthKeyRequest(expirySeconds = 600, user = alice.id))
        assertEquals(alice.id, forAlice.userId)
        b.deleteKey(forAlice.id)

        val authId = pendingRegistration(base, key, alice.loginName)
        val device = b.registerNode(authId, alice.loginName)
        try {
            assertEquals(alice.loginName, device.user)
            b.expireDevice(device.pathId)
        } finally {
            runCatching { b.deleteDevice(device.pathId) }
        }
    }
}
