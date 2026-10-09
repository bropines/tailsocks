package io.github.bropines.tailscaled.admin.api

/**
 * Everything an Admin API call can end in besides success, typed so the console can say what
 * happened in its own words instead of printing a raw body. [requestId] is the server's
 * `x-tailscale-request-id`, for the log and for a support ticket. No subclass ever carries a
 * credential: messages are built from the status, the server's `message` and our own text.
 */
sealed class AdminApiException(
    message: String,
    val status: Int,
    val requestId: String?,
    /** The server's own `message`, when it sent one. */
    val apiMessage: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 401: the credential is expired, revoked or wrong. */
    class Unauthorized(requestId: String?, apiMessage: String?) :
        AdminApiException("401 unauthorized", 401, requestId, apiMessage)

    /** 402: the plan does not include this. */
    class PaymentRequired(requestId: String?, apiMessage: String?) :
        AdminApiException("402 payment required", 402, requestId, apiMessage)

    /** 403: the credential may not do this; [missingScope] when the request's area says which. */
    class Forbidden(requestId: String?, apiMessage: String?, val area: AdminArea?, val write: Boolean) :
        AdminApiException("403 forbidden", 403, requestId, apiMessage) {
        val missingScope: String? get() = area?.scopeFor(write)
    }

    class NotFound(requestId: String?, apiMessage: String?) :
        AdminApiException("404 not found", 404, requestId, apiMessage)

    class Conflict(requestId: String?, apiMessage: String?) :
        AdminApiException("409 conflict", 409, requestId, apiMessage)

    /** 412: an If-Match that no longer matches — someone else changed it first. */
    class PreconditionFailed(requestId: String?, apiMessage: String?) :
        AdminApiException("412 precondition failed", 412, requestId, apiMessage)

    /** 400 or 422: the server refused the request's content. */
    class BadRequest(status: Int, requestId: String?, apiMessage: String?) :
        AdminApiException("$status bad request", status, requestId, apiMessage)

    /** 429 after the retries ran out; [retryAfterSec] as the server last asked. */
    class RateLimited(requestId: String?, apiMessage: String?, val retryAfterSec: Long?) :
        AdminApiException("429 rate limited", 429, requestId, apiMessage)

    /** 5xx after the retries a read gets (a write gets none). */
    class Server(status: Int, requestId: String?, apiMessage: String?) :
        AdminApiException("$status server error", status, requestId, apiMessage)

    /** Any other status. */
    class Unexpected(status: Int, requestId: String?, apiMessage: String?) :
        AdminApiException("HTTP $status", status, requestId, apiMessage)

    /** The request never got an answer: proxy, DNS, TLS, timeout. [detail] is already credential-free. */
    class Network(val detail: String, cause: Throwable? = null) :
        AdminApiException("network: $detail", 0, null, null, cause)

    /** The answer came but could not be read as [what]. */
    class Decode(val what: String, requestId: String?, cause: Throwable? = null) :
        AdminApiException("cannot read $what", 200, requestId, null, cause)

    /** The backend has no such feature at all (Headscale has no webhooks, for one). */
    class Unsupported(val feature: BackendFeature) :
        AdminApiException("not supported: $feature", 0, null, null)

    /** Whether trying the same request again later may succeed. */
    val isTransient: Boolean
        get() = this is Network || this is RateLimited || this is Server
}
