package io.github.bropines.tailscaled.core

import appctr.Appctr
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one reader of the daemon's full status (`Appctr.getStatusFromAPI()`, peers included).
 *
 * Why it exists: the bridge reports a failed read as a JSON object, `{"Error": "…"}`
 * (appctr/status.go: not running, a LocalAPI error, an empty answer), never as a string
 * starting with "Error". The hand-written copies this replaces tested
 * `json.startsWith("Error")`, which never matches, and the lenient [AppJson]
 * (`ignoreUnknownKeys`, every field defaulting to null) then decoded the error object into
 * an empty, valid-looking [StatusResponse]: "the daemon did not answer" read as "a tailnet
 * with no peers" — an empty Peers screen on every start, a live map wiped by one failed
 * read, paired devices shown as gone.
 *
 * Deliberately thin: a synchronous, blocking read on the caller's thread, exactly where the
 * old copies ran, with no cache. Whether concurrent readers should share one answer is a
 * separate decision (a cache must not serve a status from before a profile switch).
 */
object DaemonStatus {

    sealed interface Read {
        /** The daemon answered. A status with no peers is still an answer. */
        data class Ok(val status: StatusResponse) : Read

        /** No status: the daemon's own error text, or why the answer could not be used. */
        data class Failed(val error: String) : Read
    }

    /** Fetches and decodes the status. Blocks on the LocalAPI: never call it on the main thread. */
    fun read(): Read {
        val json = try {
            Appctr.getStatusFromAPI()
        } catch (e: Exception) {
            // gomobile turns a Go-side failure into an exception; that is no status either.
            return Read.Failed(e.message ?: e.toString())
        }
        return parse(json)
    }

    /** [read] for the callers that only care whether there is a status. */
    fun readOrNull(): StatusResponse? = (read() as? Read.Ok)?.status

    /** [read] for the callers whose failure path is a catch: no status throws, with the reason. */
    fun readOrThrow(): StatusResponse = when (val r = read()) {
        is Read.Ok -> r.status
        is Read.Failed -> throw IllegalStateException(r.error)
    }

    /**
     * Decodes what the bridge returned. Blank text, the bridge's `{"Error": …}` envelope and
     * anything that does not decode as a status object are all [Read.Failed].
     */
    fun parse(json: String?): Read {
        if (json.isNullOrBlank()) return Read.Failed("empty status")
        if (ERROR_ENVELOPE.containsMatchIn(json)) return Read.Failed(errorText(json))
        return try {
            Read.Ok(AppJson.decodeFromString(StatusResponse.serializer(), json))
        } catch (e: Exception) {
            Read.Failed("undecodable status: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The message inside `{"Error": "…"}`; the raw text when it cannot be pulled out. */
    private fun errorText(json: String): String =
        runCatching { (AppJson.parseToJsonElement(json).jsonObject["Error"] as? JsonPrimitive)?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: json.trim()

    /** The bridge's failure shape: an object whose first key is "Error". A real status
     *  (ipnstate.Status) has no such key; its first key is "Version". */
    private val ERROR_ENVELOPE = Regex("""^\s*\{\s*"Error"\s*:""")
}
