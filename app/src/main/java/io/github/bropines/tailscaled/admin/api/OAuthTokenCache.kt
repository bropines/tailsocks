package io.github.bropines.tailscaled.admin.api

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth access tokens kept for the life of the process, so a console opened again goes out
 * with the hour it was already given instead of minting first. Memory only: a token is never
 * written anywhere, and the secret only enters the key as a hash.
 */
class OAuthTokenCache {
    private val tokens = ConcurrentHashMap<String, Pair<String, Long>>()

    /** The token and when it is to be renewed, while that is still ahead of [now]. */
    fun get(key: String, now: Long): Pair<String, Long>? = tokens[key]?.takeIf { now < it.second }

    fun put(key: String, token: String, renewAt: Long) {
        tokens[key] = token to renewAt
    }

    fun remove(key: String) {
        tokens.remove(key)
    }

    companion object {
        val shared = OAuthTokenCache()

        /** One entry per server and client; a replaced secret starts a new one. */
        fun key(api: String, clientId: String, secret: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
            return api + "\n" + clientId + "\n" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
