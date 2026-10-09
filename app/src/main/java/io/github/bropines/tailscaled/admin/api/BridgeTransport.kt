package io.github.bropines.tailscaled.admin.api

import appctr.Appctr
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * Requests through the Go bridge (`appctr/adminhttp.go`), which speaks SOCKS5 and HTTP proxies
 * with Go's own clients — the ones tailscaled uses for its control connection — so the console
 * reaches the API wherever the daemon reaches control. [proxyUrl] is `socks5://user:pass@host:port`,
 * `http://…`, or "" for a direct connection. The bridge keeps one connection pool per proxy.
 */
class BridgeTransport(
    private val proxyUrl: String,
    private val timeoutSec: Int = 20,
) : HttpTransport {

    @Serializable
    private data class BridgeResponse(
        val status: Int = 0,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val error: String? = null,
    )

    override fun execute(request: HttpRequest): HttpResponse {
        val headers = JsonObject(request.headers.mapValues { JsonPrimitive(it.value) }).toString()
        val raw = try {
            Appctr.fetchViaProxy(request.method, request.url, headers, request.body ?: "", proxyUrl, timeoutSec)
        } catch (e: Throwable) {
            throw TransportException("bridge call failed (${e.javaClass.simpleName})")
        }
        val parsed = runCatching { AppJson.decodeFromString(BridgeResponse.serializer(), raw) }.getOrNull()
            ?: throw TransportException("unreadable bridge response")
        // The bridge redacts proxy credentials itself; this is the second lock on that door.
        parsed.error?.let { throw TransportException(redact(it)) }
        return HttpResponse(parsed.status, parsed.body, parsed.headers)
    }

    private fun redact(message: String): String {
        val userInfo = proxyUrl.substringAfter("://", "").substringBefore('@', "")
        return if (userInfo.isEmpty()) message else message.replace(userInfo, "***")
    }
}
