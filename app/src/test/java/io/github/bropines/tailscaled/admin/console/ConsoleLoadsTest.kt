package io.github.bropines.tailscaled.admin.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ConsoleLoadsTest {

    @Test
    fun aFailedTagReadKeepsTheTagsShown() {
        val shown = listOf("tag:server", "tag:ci")
        assertEquals("offline: the pickers keep their tags", shown, ConsoleLoads.tagsAfter(shown, Result.failure(IOException("offline"))))
        assertEquals("a read replaces them", listOf("tag:new"), ConsoleLoads.tagsAfter(shown, Result.success(listOf("tag:new"))))
        assertEquals("a policy without tags is an answer too", emptyList<String>(), ConsoleLoads.tagsAfter(shown, Result.success(emptyList())))
    }

    @Test
    fun attemptLetsCancellationThroughAndWrapsTheRest() = runBlocking {
        val failed = attempt<Int> { throw IOException("x") }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertEquals(3, attempt { 3 }.getOrNull())
        try {
            attempt<Int> { throw CancellationException("gone") }
            fail("cancellation must not become a failure the caller publishes")
        } catch (_: CancellationException) {
        }
    }
}
