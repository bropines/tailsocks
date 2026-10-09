package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.HttpRequest
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Answers recorded from the local Headscale bench (0.29.4 and a main-branch build of the
 * 0.30 line) by scratchpad scripts, secrets replaced with fakes of the same shape:
 * src/test/resources/admin/headscale/<bench>/<name>.json, and <name>.status with the HTTP
 * status on the first line and the ETag, when there was one, on the second.
 */

/** A recorded answer from bench [bench] ("v029" or "main"), as a FakeTransport answer. */
fun hs(bench: String, name: String): () -> HttpResponse {
    val base = "/admin/headscale/$bench/$name"
    val body = requireNotNull(HeadscaleFixtures::class.java.getResource("$base.json")) { "no fixture $base" }.readText()
    val meta = requireNotNull(HeadscaleFixtures::class.java.getResource("$base.status")) { "no status for $base" }.readText().lines()
    val headers = buildMap {
        meta.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { put("ETag", it.trim()) }
        put("Content-Type", "application/json")
    }
    return { HttpResponse(meta.first().trim().toInt(), body, headers) }
}

fun hsText(bench: String, name: String): String =
    requireNotNull(HeadscaleFixtures::class.java.getResource("/admin/headscale/$bench/$name.json")).readText()

object HeadscaleFixtures {
    const val BASE_029 = "http://127.0.0.1:18029"
    const val BASE_MAIN = "http://127.0.0.1:18030"

    /** A key whose listed prefix is the 0.29 bench's console key in the (scrubbed) fixtures. */
    const val KEY_029 = "hskey-api-AbCd-EfGh123-fakefakefakefakefakefakefakefakefakefakefakefakefakefakefakefake"
    const val KEY_MAIN = "hskey-api-0a1b2c3d4e5f-fakefakefakefakefakefakefakefakefakefakefakefakefakefakefakefake"
}

fun v1Backend(t: FakeTransport, version: HeadscaleVersion? = HeadscaleVersion.parse("v0.29.4"), clock: Long = NOW) = HeadscaleV1Backend(
    apiKey = HeadscaleFixtures.KEY_029,
    transport = t,
    baseUrl = HeadscaleFixtures.BASE_029,
    initialVersion = version,
    clock = { clock },
    sleeper = {},
    io = Dispatchers.Unconfined,
)

fun v2Backend(t: FakeTransport, clock: Long = NOW) = HeadscaleV2Backend(
    apiKey = HeadscaleFixtures.KEY_MAIN,
    transport = t,
    baseUrl = HeadscaleFixtures.BASE_MAIN,
    clock = { clock },
    sleeper = {},
    io = Dispatchers.Unconfined,
)

/** 2026-10-09T18:30:00Z: after the recordings, before their keys expire. */
const val NOW = 1_791_570_600_000L

fun HttpRequest.json(): JsonObject = AppJson.parseToJsonElement(body!!).jsonObject

/** The path and query of a request, without the bench's base URL. */
val HttpRequest.target: String get() = url.removePrefix(HeadscaleFixtures.BASE_029).removePrefix(HeadscaleFixtures.BASE_MAIN)
