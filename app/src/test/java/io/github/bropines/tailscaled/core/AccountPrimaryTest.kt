package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Which account is the main one: the one that cannot be deleted and that the app falls back to.
 * Whatever is stored, the answer must name an account that exists — the app reads its state
 * directory and preferences by that id.
 */
class AccountPrimaryTest {
    private val first = TailscaleAccount(AccountManager.FIRST_ID, "Default")
    private val work = TailscaleAccount("1760000000000", "Work")
    private val home = TailscaleAccount("1760000000001", "Home")

    @Test
    fun theFirstAccountIsTheMainOneUntilAnotherIsChosen() {
        assertEquals(AccountManager.FIRST_ID, AccountManager.primaryOf(listOf(first, work), null))
    }

    @Test
    fun aChosenAccountIsTheMainOne() {
        assertEquals(work.id, AccountManager.primaryOf(listOf(first, work, home), work.id))
    }

    @Test
    fun withTheFirstAccountDeletedTheChosenOneStands() {
        assertEquals(home.id, AccountManager.primaryOf(listOf(work, home), home.id))
    }

    @Test
    fun aChoiceWhoseAccountIsGoneFallsBackToOneThatExists() {
        // A backup from before the choice existed, restored over a device that made one.
        assertEquals(AccountManager.FIRST_ID, AccountManager.primaryOf(listOf(first, work), "1759999999999"))
        // And with the first account gone too: whichever comes first.
        assertEquals(work.id, AccountManager.primaryOf(listOf(work, home), "1759999999999"))
    }
}
