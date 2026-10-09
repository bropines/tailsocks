package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.DecodeIssue
import io.github.bropines.tailscaled.admin.api.HttpRequest
import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.HttpTransport
import io.github.bropines.tailscaled.admin.api.Listing
import io.github.bropines.tailscaled.admin.api.RequestIds
import io.github.bropines.tailscaled.admin.api.RetryPolicy
import io.github.bropines.tailscaled.admin.api.TransportException
import io.github.bropines.tailscaled.admin.api.Urls
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Requests to one Headscale server with its API key: the bearer header, the back-off a read
 * gets (429, 502–504, no answer) and a write does not (429 only, answered before anything
 * applies), and Headscale's three error bodies read into the console's typed errors —
 * Tailscale's `{"message"}` from /api/v2, the gRPC gateway's `{"code","message"}` from
 * /api/v1 up to 0.29, and RFC 9457 `{"title","detail","errors"}` from 0.30's /api/v1.
 *
 * The gateway answers many "no such thing" errors as 500 (gRPC's Unknown) with the database's
 * "record not found"; those are read as 404, so a re-read after a delete can tell it worked.
 */
internal class HeadscaleHttp(
    baseUrl: String,
    private val apiKey: String,
    private val transport: HttpTransport,
    private val retry: RetryPolicy = RetryPolicy(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    /** A 403 on [AdminArea] for a read or a write; Headscale API keys see none, OAuth tokens might. */
    private val onForbidden: (AdminArea, Boolean) -> Unit = { _, _ -> },
) {
    val base: String = baseUrl.trim().trimEnd('/')

    /** One call; a non-2xx answer is thrown as the matching [AdminApiException]. */
    suspend fun call(
        method: String,
        path: String,
        area: AdminArea?,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val resp = raw(method, path, query, body, accept, headers, auth = true)
        if (resp.status in 200..299) return resp
        throw errorFor(resp, area, method != "GET")
    }

    /** One call whose answer, whatever its status, is the caller's to read: probes, /version. */
    suspend fun raw(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
    ): HttpResponse {
        val write = method != "GET"
        val h = buildMap {
            if (auth) put("Authorization", "Bearer $apiKey")
            put("Accept", accept)
            if (body != null) put("Content-Type", "application/json")
            putAll(headers)
        }
        val request = HttpRequest(method, base + path + Urls.query(query), h, body, idempotent = !write)
        return withContext(io) { sendWithRetries(request, write, path) }
    }

    private suspend fun sendWithRetries(request: HttpRequest, write: Boolean, path: String): HttpResponse {
        var attempt = 0
        while (true) {
            attempt++
            val resp = try {
                transport.execute(request)
            } catch (e: TransportException) {
                log("${request.method} $path: no answer (${e.message})")
                if (!write && attempt < retry.maxAttempts) {
                    sleeper(retry.backoff(attempt))
                    continue
                }
                throw AdminApiException.Network(e.message ?: "no answer", e)
            }
            resp.requestId?.let { id -> currentCoroutineContext()[RequestIds]?.add(id) }
            log("${request.method} $path -> ${resp.status}")
            val retryable = resp.status == 429 || (!write && resp.status in RETRYABLE_5XX)
            if (retryable && attempt < retry.maxAttempts) {
                val wait = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: retry.backoff(attempt)
                if (wait <= retry.maxRetryAfterMs) {
                    sleeper(wait)
                    continue
                }
            }
            return resp
        }
    }

    fun errorFor(resp: HttpResponse, area: AdminArea?, write: Boolean): AdminApiException {
        val msg = messageOf(resp.body)
        val id = resp.requestId
        return when (resp.status) {
            400, 422 -> AdminApiException.BadRequest(resp.status, id, msg)
            401 -> AdminApiException.Unauthorized(id, msg)
            402 -> AdminApiException.PaymentRequired(id, msg)
            403 -> {
                if (area != null) onForbidden(area, write)
                AdminApiException.Forbidden(id, msg, area, write)
            }
            404 -> AdminApiException.NotFound(id, msg)
            409 -> AdminApiException.Conflict(id, msg)
            412 -> AdminApiException.PreconditionFailed(id, msg)
            429 -> AdminApiException.RateLimited(id, msg, resp.header("Retry-After")?.trim()?.toLongOrNull())
            in 500..599 -> when {
                resp.status != 500 || msg == null -> AdminApiException.Server(resp.status, id, msg)
                NOT_FOUND.containsMatchIn(msg) -> AdminApiException.NotFound(id, msg)
                // The gateway's Unknown (code 2) carries the domain's refusals: a bad name, a
                // user that still has nodes. Shown as what the server said, not as an outage.
                isGatewayError(resp.body) -> AdminApiException.BadRequest(500, id, msg)
                else -> AdminApiException.Server(resp.status, id, msg)
            }
            else -> AdminApiException.Unexpected(resp.status, id, msg)
        }
    }

    companion object {
        private val RETRYABLE_5XX = setOf(502, 503, 504)
        private val NOT_FOUND = Regex("record not found|not found|no such", RegexOption.IGNORE_CASE)

        /** `{"code": 2, "message": …, "details": []}`: the gRPC gateway of 0.25–0.29. */
        private fun isGatewayError(body: String): Boolean =
            runCatching { (AppJson.parseToJsonElement(body) as? JsonObject)?.get("code") is JsonPrimitive }.getOrDefault(false)

        /**
         * The server's own words from any of Headscale's error bodies, or the first line of a
         * plain-text one ("Unauthorized"). Capped; never a credential, since nothing we send
         * is echoed back.
         */
        fun messageOf(body: String): String? {
            val text = body.trim()
            if (text.isEmpty()) return null
            val obj = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val out = if (obj == null) text.lineSequence().first() else {
                fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                val detail = str("message") ?: str("detail") ?: str("error_description") ?: str("title")
                val inner = (obj["errors"] as? JsonArray)?.mapNotNull { e ->
                    ((e as? JsonObject)?.get("message") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }.orEmpty()
                listOfNotNull(detail, inner.takeIf { it.isNotEmpty() }?.joinToString("; ")).joinToString(": ").ifBlank { null }
            }
            return out?.take(300)
        }
    }
}

/** Decodes one object from [resp], or throws [AdminApiException.Decode] naming [what]. */
internal fun <T> HttpResponse.decode(serializer: KSerializer<T>, what: String): T = try {
    AppJson.decodeFromString(serializer, body.ifBlank { "{}" })
} catch (e: Exception) {
    throw AdminApiException.Decode(what, requestId, e)
}

/**
 * The list under [field] in [resp], element by element: what does not decode is reported in
 * the [Listing], never silently dropped. A missing or null field is an empty list.
 */
internal fun <T> HttpResponse.decodeList(serializer: KSerializer<T>, field: String, what: String, idField: String = "id"): Listing<T> {
    val root = try {
        AppJson.parseToJsonElement(body.ifBlank { "{}" })
    } catch (e: Exception) {
        throw AdminApiException.Decode("$what list", requestId, e)
    }
    val array = when (val el = (root as? JsonObject ?: throw AdminApiException.Decode("$what list", requestId))[field]) {
        null, JsonNull -> return Listing(emptyList())
        is JsonArray -> el
        else -> throw AdminApiException.Decode("$what list", requestId)
    }
    val items = ArrayList<T>(array.size)
    val issues = mutableListOf<DecodeIssue>()
    array.forEachIndexed { index, el ->
        try {
            items += AppJson.decodeFromJsonElement(serializer, el)
        } catch (e: Exception) {
            val id = (el as? JsonObject)?.get(idField)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            issues += DecodeIssue(what, index, id, (e.message ?: e.javaClass.simpleName).lineSequence().first().take(200))
        }
    }
    return Listing(items, issues)
}
