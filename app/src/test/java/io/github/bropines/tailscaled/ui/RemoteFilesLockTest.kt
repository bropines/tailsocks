package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This device's own lock in front of another device's files on the managing page: asked once,
 * held five minutes by the page, never asked where there is no lock — and then said once —
 * and nothing opened on a cancel or a failure. The prompt itself (RemoteUnlockActivity, a
 * BiometricPrompt) is stood in for by [prompts]: a television, where this is verified on an
 * emulator, has no lock to show it with.
 */
class RemoteFilesLockTest {

    private var clock = 1_791_813_780_000L
    private var secure = true
    /** The prompts put up, each waiting for its answer. */
    private val prompts = ArrayList<(RemoteUnlockOutcome) -> Unit>()
    private val lock = RemoteFilesLock(secure = { secure }, ask = { prompts += it }, now = { clock })
    private val opened = ArrayList<String>()

    private fun open(place: FilesPlace, what: String) = lock.run(place) { opened += what }

    @Test
    fun oneConfirmationHoldsFiveMinutesOnThePage() {
        open(FilesPlace.STORAGE, "storage")
        assertEquals(emptyList<String>(), opened)
        assertEquals(1, prompts.size)
        prompts.removeAt(0)(RemoteUnlockOutcome.UNLOCKED)
        assertEquals(listOf("storage"), opened)
        assertTrue(lock.open())
        // Within the five minutes: no prompt, whatever is opened.
        clock += 4 * 60_000L
        open(FilesPlace.DIAG, "log")
        open(FilesPlace.SHARES, "shares")
        assertEquals(listOf("storage", "log", "shares"), opened)
        assertTrue(prompts.isEmpty())
        // After them, asked again.
        clock += 60_000L
        assertFalse(lock.open())
        open(FilesPlace.DIAG, "report")
        assertEquals(1, prompts.size)
        assertEquals(3, opened.size)
        // A clock set back is no confirmation either.
        prompts.removeAt(0)(RemoteUnlockOutcome.UNLOCKED)
        clock -= 60_000L
        assertFalse(lock.open())
        assertNull(lock.said)
    }

    @Test
    fun aPageThatClosesTakesItsConfirmationWithIt() {
        open(FilesPlace.STORAGE, "storage")
        prompts.removeAt(0)(RemoteUnlockOutcome.UNLOCKED)
        assertTrue(lock.open())
        // The page keeps the lock: a page opened again has a new one, and asks.
        val again = RemoteFilesLock(secure = { secure }, ask = { prompts += it }, now = { clock })
        assertFalse(again.open())
        again.run(FilesPlace.STORAGE) { opened += "again" }
        assertEquals(1, prompts.size)
    }

    @Test
    fun cancelledOrFailedOpensNothing() {
        open(FilesPlace.STORAGE, "storage")
        prompts.removeAt(0)(RemoteUnlockOutcome.CANCELLED)
        assertEquals(emptyList<String>(), opened)
        assertNull(lock.said)
        open(FilesPlace.DIAG, "log")
        prompts.removeAt(0)(RemoteUnlockOutcome.FAILED)
        assertEquals(FilesPlace.DIAG to RemoteUnlockOutcome.FAILED, lock.said)
        open(FilesPlace.SHARES, "shares")
        prompts.removeAt(0)(RemoteUnlockOutcome.LOCKOUT)
        assertEquals(FilesPlace.SHARES to RemoteUnlockOutcome.LOCKOUT, lock.said)
        assertEquals(emptyList<String>(), opened)
        assertFalse(lock.open())
        // Unlocked at last: what was said goes.
        open(FilesPlace.SHARES, "shares")
        prompts.removeAt(0)(RemoteUnlockOutcome.UNLOCKED)
        assertEquals(listOf("shares"), opened)
        assertNull(lock.said)
    }

    @Test
    fun oneRequestAtATime() {
        open(FilesPlace.STORAGE, "storage")
        open(FilesPlace.STORAGE, "storage again")
        assertEquals(1, prompts.size)
        prompts.removeAt(0)(RemoteUnlockOutcome.UNLOCKED)
        assertEquals(listOf("storage"), opened)
        // An answer nobody waits for opens nothing.
        lock.answered(RemoteUnlockOutcome.UNLOCKED)
        assertEquals(listOf("storage"), opened)
    }

    @Test
    fun withoutALockNothingIsAskedAndItIsSaidOnce() {
        secure = false
        open(FilesPlace.DIAG, "log")
        assertEquals(listOf("log"), opened)
        assertTrue(prompts.isEmpty())
        assertEquals(FilesPlace.DIAG to RemoteUnlockOutcome.NO_LOCK, lock.said)
        // Said where it was first said, not again under every group.
        clock += 10 * 60_000L
        open(FilesPlace.STORAGE, "storage")
        assertEquals(listOf("log", "storage"), opened)
        assertEquals(FilesPlace.DIAG to RemoteUnlockOutcome.NO_LOCK, lock.said)
    }

    @Test
    fun aLockRemovedWhileThePromptWasOnItsWayIsSaidAsNone() {
        open(FilesPlace.SHARES, "shares")
        prompts.removeAt(0)(RemoteUnlockOutcome.NO_LOCK)
        assertEquals(listOf("shares"), opened)
        assertEquals(FilesPlace.SHARES to RemoteUnlockOutcome.NO_LOCK, lock.said)
    }
}
