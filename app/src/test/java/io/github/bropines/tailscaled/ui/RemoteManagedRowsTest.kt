package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The paired devices on the managed device's page, as a remote walks them: where it stands
 * after the row it was on is revoked.
 */
class RemoteManagedRowsTest {

    private fun heir(before: String, after: String, focused: String?) =
        remoteRowHeir(before.split(",").filter { it.isNotEmpty() }, after.split(",").filter { it.isNotEmpty() }, focused)

    @Test
    fun theRowThatTookItsPlaceIsNext() {
        assertEquals(RemoteRowHeir.Row("b"), heir("a,b,c", "b,c", "a"))
        assertEquals(RemoteRowHeir.Row("c"), heir("a,b,c", "a,c", "b"))
        // Two went at once, the one under the remote among them.
        assertEquals(RemoteRowHeir.Row("c"), heir("a,b,c", "c", "a"))
    }

    @Test
    fun theLastRowHandsItToTheOneBefore() {
        assertEquals(RemoteRowHeir.Row("b"), heir("a,b,c", "a,b", "c"))
        assertEquals(RemoteRowHeir.Row("a"), heir("a,b,c", "a", "c"))
        // None of the old rows is left and a new one came: the list still has a stop.
        assertEquals(RemoteRowHeir.Row("d"), heir("a,b", "d", "b"))
    }

    @Test
    fun anEmptiedListSendsItOut() {
        assertEquals(RemoteRowHeir.Out, heir("a", "", "a"))
        assertEquals(RemoteRowHeir.Out, heir("a,b", "", "b"))
        // "Revoke all" goes with the last row.
        assertEquals(RemoteRowHeir.Out, heir("a,b", "", REMOTE_ROWS_ALL))
    }

    @Test
    fun aRemoteThatIsElsewhereIsLeftAlone() {
        // Not in the list at all — on a switch, or a finger on a phone.
        assertEquals(RemoteRowHeir.Stay, heir("a,b", "b", null))
        assertEquals(RemoteRowHeir.Stay, heir("a,b", "", null))
        // On a row that stays.
        assertEquals(RemoteRowHeir.Stay, heir("a,b,c", "b,c", "b"))
        assertEquals(RemoteRowHeir.Stay, heir("a,b", "a,b,c", "a"))
        // On "Revoke all" while rows remain: the button is still there.
        assertEquals(RemoteRowHeir.Stay, heir("a,b", "b", REMOTE_ROWS_ALL))
        assertEquals(RemoteRowHeir.Stay, heir("", "", REMOTE_ROWS_ALL))
        // On a row this list never had.
        assertEquals(RemoteRowHeir.Stay, heir("a,b", "b", "z"))
    }
}
