package io.github.bropines.tailscaled.admin.api.headscale

import java.net.URI

/**
 * What a joining device shows to be let in: `https://hs.example.com/register/<auth id>`, as
 * `tailscale up` prints it and `tailscale up --qr` draws it. [authId] is the part the API
 * registers; [host] is the server the link points at, which should be the profile's own.
 */
data class RegistrationLink(val authId: String, val host: String?, val raw: String) {

    /** Whether the link was made by the server at [baseUrl]; a link for another server cannot register here. */
    fun isFor(baseUrl: String): Boolean {
        val h = host ?: return true
        val own = runCatching { URI(baseUrl.trim()).host }.getOrNull() ?: return true
        return h.equals(own, ignoreCase = true)
    }

    /** 0.29's prefixed id, or the 24-character one of 0.25–0.28. */
    val isPrefixed: Boolean get() = authId.startsWith(AUTH_ID_PREFIX)

    enum class Problem { EMPTY, NOT_A_LINK, BAD_ID }

    sealed class Parsed {
        data class Ok(val link: RegistrationLink) : Parsed()
        data class Invalid(val problem: Problem) : Parsed()
    }

    companion object {
        const val AUTH_ID_PREFIX = "hskey-authreq-"
        /** "hskey-authreq-" and 24 URL-safe characters (0.29+). */
        private val PREFIXED = Regex("^hskey-authreq-[A-Za-z0-9_-]{24}$")
        /** 24 URL-safe characters (0.25–0.28). */
        private val PLAIN = Regex("^[A-Za-z0-9_-]{24}$")

        fun isValidId(id: String): Boolean = PREFIXED.matches(id) || PLAIN.matches(id)

        /**
         * The link from a scanned code or pasted text: a whole URL, a "headscale nodes register
         * --key …" or "auth register --auth-id …" command someone copied, or the bare id.
         */
        fun parse(text: String): Parsed {
            val t = text.trim()
            if (t.isEmpty()) return Parsed.Invalid(Problem.EMPTY)
            // The id itself, or the last argument of a CLI line that names it.
            Regex("(hskey-authreq-[A-Za-z0-9_-]{24})").find(t)?.let { m ->
                if (!t.contains("/register/")) return Parsed.Ok(RegistrationLink(m.value, null, t))
            }
            if (isValidId(t)) return Parsed.Ok(RegistrationLink(t, null, t))
            val start = t.indexOf("http")
            if (start < 0) {
                val key = Regex("(?:--key|--auth-id)[ =]+([A-Za-z0-9_-]{24,38})").find(t)?.groupValues?.get(1)
                return if (key != null && isValidId(key)) Parsed.Ok(RegistrationLink(key, null, t)) else Parsed.Invalid(Problem.NOT_A_LINK)
            }
            val url = t.substring(start).takeWhile { !it.isWhitespace() }
            val uri = runCatching { URI(url) }.getOrNull() ?: return Parsed.Invalid(Problem.NOT_A_LINK)
            val path = uri.rawPath ?: return Parsed.Invalid(Problem.NOT_A_LINK)
            val marker = path.indexOf("/register/")
            if (marker < 0) return Parsed.Invalid(Problem.NOT_A_LINK)
            // "/register/confirm/<id>" is the confirmation page of the same request.
            val id = path.substring(marker + "/register/".length).removePrefix("confirm/").substringBefore('/').trim()
            if (!isValidId(id)) return Parsed.Invalid(Problem.BAD_ID)
            return Parsed.Ok(RegistrationLink(id, uri.host?.lowercase(), url))
        }
    }
}
