package io.github.bropines.tailscaled.admin.api

import kotlinx.coroutines.Dispatchers

/** Reads a recorded answer from src/test/resources/admin. */
fun recorded(name: String): String =
    requireNotNull(FakeTransport::class.java.getResource("/admin/$name")) { "no fixture $name" }.readText()

/**
 * A transport that answers from a script: each request is matched against the routes in
 * order of registration (method plus a path fragment), and every request is kept for the
 * assertions. A route may answer several times or be consumed once.
 */
class FakeTransport : HttpTransport {
    class Route(
        val method: String,
        val pathContains: String,
        val queryContains: String?,
        val answers: ArrayDeque<() -> HttpResponse>,
        val repeatLast: Boolean,
    )

    private val routes = mutableListOf<Route>()
    val requests = mutableListOf<HttpRequest>()

    fun on(
        method: String,
        pathContains: String,
        queryContains: String? = null,
        repeat: Boolean = true,
        vararg answers: () -> HttpResponse,
    ): FakeTransport {
        routes += Route(method, pathContains, queryContains, ArrayDeque(answers.toList()), repeat)
        return this
    }

    fun ok(method: String, pathContains: String, body: String, headers: Map<String, String> = mapOf("X-Tailscale-Request-Id" to "req-ok")) =
        on(method, pathContains, null, true, { HttpResponse(200, body, headers) })

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val path = request.url.substringAfter("/api/v2").substringBefore('?')
        val query = request.url.substringAfter('?', "")
        val route = routes.firstOrNull {
            it.method == request.method && path.contains(it.pathContains) &&
                (it.queryContains == null || query.contains(it.queryContains)) && it.answers.isNotEmpty()
        } ?: throw AssertionError("unexpected request $request")
        val answer = if (route.answers.size == 1 && route.repeatLast) route.answers.first() else route.answers.removeFirst()
        return answer()
    }

    fun requestsTo(method: String, pathContains: String) =
        requests.filter { it.method == method && it.url.substringAfter("/api/v2").substringBefore('?').contains(pathContains) }
}

fun status(code: Int, body: String = "", headers: Map<String, String> = mapOf("X-Tailscale-Request-Id" to "req-$code")): () -> HttpResponse =
    { HttpResponse(code, body, headers) }

fun noAnswer(message: String = "dial tcp: connection refused"): () -> HttpResponse = { throw TransportException(message) }

/** A backend wired for tests: no real sleeping, the sleeps recorded instead. */
fun testBackend(
    transport: HttpTransport,
    credential: AdminCredential = AdminCredential.ApiToken("tskey-api-kAPI1CNTRL-secretpart"),
    sleeps: MutableList<Long> = mutableListOf(),
    clock: () -> Long = { 1_000_000L },
    logs: MutableList<String> = mutableListOf(),
) = TailscaleBackend(
    credential = credential,
    transport = transport,
    clock = clock,
    sleeper = { sleeps += it },
    io = Dispatchers.Unconfined,
    log = { logs += it },
)
