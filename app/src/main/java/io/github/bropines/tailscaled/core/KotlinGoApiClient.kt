package io.github.bropines.tailscaled.core

import appctr.Appctr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What is left of a coroutine wrapper around the bridge's LocalAPI calls: the one call that
 * still has a caller (the peer's "copy as" forms). The rest went unused — the app talks to the
 * daemon through `Appctr` directly (agents.md: the bridge is the LocalAPI client) — and was
 * removed with LocalApiClient. When that caller calls `Appctr.whoIsAddr` itself, this file goes.
 */
object KotlinGoApiClient {
    suspend fun whoIs(addr: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.whoIsAddr(addr) }
    }
}
