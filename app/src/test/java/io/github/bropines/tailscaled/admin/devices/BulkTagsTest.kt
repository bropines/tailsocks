package io.github.bropines.tailscaled.admin.devices

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.HttpRequest
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.RequestIds
import io.github.bropines.tailscaled.admin.api.status
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkTagsPlanTest {

    private fun dev(id: String, tags: List<String> = emptyList(), user: String? = "alex@example.com", shared: Boolean = false) =
        ApiDevice(nodeId = id, name = "$id.tail1.ts.net", tags = tags, user = user, isExternal = shared)

    private val personal = dev("nLAPTOP")
    private val server = dev("nSERVER", listOf("tag:server"))
    private val both = dev("nBOTH", listOf("tag:server", "tag:web"))
    private val shared = dev("nSHARED", shared = true)

    @Test
    fun addKeepsWhatIsThereAndSkipsWhatAlreadyHasIt() {
        val rows = BulkTags.plan(listOf(personal, server, both, shared), BulkTagMode.ADD, listOf("server"))
        val byId = rows.associateBy { it.device.nodeId }
        assertEquals(listOf("tag:server"), byId.getValue("nLAPTOP").after)
        assertTrue("an untagged device changes owner", byId.getValue("nLAPTOP").takesOwnership)
        assertEquals(BulkSkip.UNCHANGED, byId.getValue("nSERVER").skip)
        assertEquals(BulkSkip.UNCHANGED, byId.getValue("nBOTH").skip)
        assertEquals(BulkSkip.SHARED, byId.getValue("nSHARED").skip)
        assertEquals(listOf("nLAPTOP"), rows.filter { it.changes }.map { it.device.nodeId })
    }

    @Test
    fun removeNeverTakesTheLastTag() {
        val rows = BulkTags.plan(listOf(personal, server, both), BulkTagMode.REMOVE, listOf("tag:server"))
        val byId = rows.associateBy { it.device.nodeId }
        assertEquals(BulkSkip.UNCHANGED, byId.getValue("nLAPTOP").skip)
        assertEquals(BulkSkip.LAST_TAG, byId.getValue("nSERVER").skip)
        assertEquals("a skipped row shows the device as it stays", listOf("tag:server"), byId.getValue("nSERVER").after)
        assertEquals(listOf("tag:web"), byId.getValue("nBOTH").after)
        assertNull(byId.getValue("nBOTH").skip)
        assertFalse(byId.getValue("nBOTH").takesOwnership)
    }

    @Test
    fun replaceSetsExactlyTheChosenTags() {
        val rows = BulkTags.plan(listOf(personal, server, both), BulkTagMode.REPLACE, listOf("tag:web", "tag:web", "web"))
        assertEquals("duplicates and the bare name fold into one tag", listOf(listOf("tag:web"), listOf("tag:web"), listOf("tag:web")), rows.map { it.after })
        assertEquals("tag:server, tag:web → tag:web is a change too", listOf(null, null, null), rows.map { it.skip })
        assertEquals(listOf(true, false, false), rows.map { it.takesOwnership })
        val again = BulkTags.plan(listOf(dev("nWEB", listOf("tag:web"))), BulkTagMode.REPLACE, listOf("tag:web"))
        assertEquals(BulkSkip.UNCHANGED, again.single().skip)
    }

    @Test
    fun tagNamesAreNormalised() {
        assertEquals("tag:server", BulkTags.normalize(" server "))
        assertEquals("tag:my_box-2", BulkTags.normalize("tag:my_box-2"))
        assertNull(BulkTags.normalize("tag:"))
        assertNull(BulkTags.normalize("two words"))
        assertNull(BulkTags.normalize("tag:a/b"))
        val rows = BulkTags.plan(listOf(personal), BulkTagMode.ADD, listOf("bad name", "ok"))
        assertEquals("a bad name is dropped, not sent", listOf("tag:ok"), rows.single().after)
    }
}

class BulkTagsApplyTest {

    private fun dev(id: String, tags: List<String> = emptyList()) = ApiDevice(nodeId = id, name = "${id.lowercase()}.tail1.ts.net", tags = tags, user = "alex@example.com")

    private fun deviceJson(id: String, tags: List<String>) =
        """{"nodeId":"$id","name":"${id.lowercase()}.tail1.ts.net","tags":[${tags.joinToString(",") { "\"$it\"" }}]}"""

    private fun ok(id: String) = { HttpResponse(200, "", mapOf("X-Tailscale-Request-Id" to id)) }

    private fun sentTags(req: HttpRequest) = AppJson.parseToJsonElement(req.body!!).jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content }

    private val a = dev("nAAA")
    private val b = dev("nBBB", listOf("tag:old"))
    private val c = dev("nCCC")
    private val skipped = dev("nSKIP", listOf("tag:server"))

    @Test
    fun eachDeviceInTurnWithItsOwnResultAndTheIdsCollected() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/device/nAAA/tags", null, true, ok("req-a"))
            .on("GET", "/device/nAAA", null, true, { HttpResponse(200, deviceJson("nAAA", listOf("tag:server")), mapOf("X-Tailscale-Request-Id" to "req-a2")) })
            .on("POST", "/device/nBBB/tags", null, true, status(400, """{"message":"tag:server is not permitted"}"""))
            .on("POST", "/device/nCCC/tags", null, true, ok("req-c"))
            .on("GET", "/device/nCCC", null, true, { HttpResponse(200, deviceJson("nCCC", emptyList()), emptyMap()) })
        val rows = BulkTags.plan(listOf(a, b, c, skipped), BulkTagMode.ADD, listOf("tag:server"))
        val heard = mutableListOf<String>()
        val outer = RequestIds()
        val out = withContext(outer) { BulkTags.apply(testBackend(t), rows) { row, o -> heard += "${row.device.nodeId}:${o.state}" } }

        assertEquals(listOf("nAAA:VERIFIED", "nBBB:FAILED", "nCCC:MISMATCH"), heard)
        assertEquals(listOf(BulkState.VERIFIED, BulkState.FAILED, BulkState.MISMATCH), out.map { it.state })
        assertTrue(out[1].error is AdminApiException.BadRequest)
        assertEquals("the skipped device is never sent", 3, t.requests.count { it.method == "POST" })
        assertEquals(listOf("tag:server"), sentTags(t.requestsTo("POST", "/device/nAAA/tags").single()))
        assertEquals(listOf("tag:old", "tag:server"), sentTags(t.requestsTo("POST", "/device/nBBB/tags").single()))
        assertEquals(listOf("req-a", "req-a2"), out[0].requestIds)
        assertTrue("the batch's own record gets every id", outer.ids.containsAll(listOf("req-a", "req-a2", "req-400", "req-c")))
        assertEquals(false, BulkTags.verified(out))
    }

    @Test
    fun aRefusedCredentialStopsTheRun() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/device/nAAA/tags", null, true, status(403, """{"message":"forbidden"}"""))
        val rows = BulkTags.plan(listOf(a, b, c), BulkTagMode.ADD, listOf("tag:server"))
        val out = BulkTags.apply(testBackend(t), rows)
        assertEquals(listOf(BulkState.FAILED, BulkState.NOT_SENT, BulkState.NOT_SENT), out.map { it.state })
        assertEquals("nothing after the refusal goes out", 1, t.requests.size)
        assertTrue(out[2].error is AdminApiException.Forbidden)
    }

    @Test
    fun aReReadThatFailsLeavesTheResultOpen() = runBlocking {
        val t = FakeTransport()
            .on("POST", "/device/nAAA/tags", null, true, ok("req-a"))
            .on("GET", "/device/nAAA", null, true, status(500))
        val out = BulkTags.apply(testBackend(t), BulkTags.plan(listOf(a), BulkTagMode.ADD, listOf("tag:server")))
        assertEquals(BulkState.APPLIED, out.single().state)
        assertNull("unknown is neither verified nor failed", BulkTags.verified(out))
        assertEquals(true, BulkTags.verified(listOf(BulkOutcome("x", "x", BulkState.VERIFIED))))
    }
}
