package io.github.bropines.tailscaled.admin.console

import kotlinx.coroutines.CancellationException

/**
 * runCatching that lets cancellation through: around a suspend call, `runCatching` would turn a
 * cancelled coroutine (the profile switched, the screen closed) into an ordinary failure and the
 * caller would go on to publish it.
 */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** How a loader's answer folds into what the console already shows; pure, so it is tested. */
internal object ConsoleLoads {

    /**
     * The policy's tags after a read: what it returned, or — offline, a timeout, a 5xx, a
     * credential that may not read the policy — the tags already shown (seeded from the
     * profile's copy). An empty list would empty every tag picker until a forced refresh.
     */
    fun tagsAfter(old: List<String>, read: Result<List<String>>): List<String> = read.getOrElse { old }
}
