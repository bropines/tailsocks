package io.github.bropines.tailscaled.admin.api

import java.net.URLEncoder

/** One HTTP request as the Admin API layer hands it to a transport. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /**
     * Safe to send twice: a read, or the OAuth token exchange. Only such a request may be
     * repeated over another route after the first one failed to answer; a write never is —
     * it may have arrived and been applied, with only the answer lost.
     */
    val idempotent: Boolean = method == "GET",
) {
    override fun toString(): String = "$method ${url.substringBefore('?')}"
}

class HttpResponse(val status: Int, val body: String, headers: Map<String, String> = emptyMap()) {
    private val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = headers[name.lowercase()]

    /** Tailscale's id for the request, on every answer; what to quote in a support ticket. */
    val requestId: String? get() = header("x-tailscale-request-id")
}

/** The request got no HTTP answer at all. The message is credential-free by construction. */
class TransportException(message: String) : Exception(message)

/** Sends one request and waits for its answer. Blocking: call it off the main thread. */
fun interface HttpTransport {
    @Throws(TransportException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * [primary] first; when it could not answer an idempotent request, [fallback] once. This is
 * the old "the proxy is down, try directly" rescue, narrowed to what may safely run twice:
 * a write that failed on the way is reported, never re-sent over another route.
 */
class FallbackTransport(
    private val primary: HttpTransport,
    private val fallback: HttpTransport?,
    private val onFallback: (HttpRequest, TransportException) -> Unit = { _, _ -> },
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse = try {
        primary.execute(request)
    } catch (e: TransportException) {
        if (fallback == null || !request.idempotent) throw e
        onFallback(request, e)
        fallback.execute(request)
    }
}

internal object Urls {
    /** One path segment, percent-encoded; ':' and '@' stay, as in "svc:web". */
    fun seg(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%3A", ":")
            .replace("%40", "@")

    fun query(params: List<Pair<String, String>>): String =
        if (params.isEmpty()) "" else params.joinToString("&", prefix = "?") { (k, v) ->
            URLEncoder.encode(k, Charsets.UTF_8.name()) + "=" + URLEncoder.encode(v, Charsets.UTF_8.name())
        }

    fun form(params: List<Pair<String, String>>): String = query(params).removePrefix("?")
}
