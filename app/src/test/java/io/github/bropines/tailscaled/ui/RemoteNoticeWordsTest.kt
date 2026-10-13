package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line a managed device says on its screen for a change made from afar: the bridge's
 * detail in the words of the page's log, and left alone wherever it is a name already.
 */
class RemoteNoticeWordsTest {

    /** A resource is its number in angle brackets, except the one pattern the line is built with. */
    private fun text(id: Int): String = if (id == R.string.remote_log_with_note) "%1\$s (%2\$s)" else "<$id>"

    private fun what(op: String, detail: String) = remoteNoticeWhat(op, detail, ::text)

    @Test
    fun aSwitchIsSaidByItsNameAndWhichWayItWent() {
        assertEquals("<${R.string.remote_can_set_accept_routes}> (<${R.string.remote_log_on}>)", what("setting", "accept_routes=true"))
        assertEquals("<${R.string.remote_can_set_tun_mode}> (<${R.string.remote_log_off}>)", what("setting", "tun_mode=false"))
        assertEquals("<${R.string.remote_can_set_auto_start}> (<${R.string.remote_log_on}>)", what("setting", "auto_start=true"))
        // The bridge's remark after it is the log's business: the line on the screen is short.
        assertEquals("<${R.string.remote_can_set_accept_dns}> (<${R.string.remote_log_off}>)", what("setting", "accept_dns=false (applied late)"))
    }

    @Test
    fun aListIsSaidWithWhatItHoldsNow() {
        val routes = "<${R.string.remote_can_set_advertise_routes}>"
        assertEquals("$routes (192.168.5.0/24,fd00::/64)", what("setting", "advertise_routes=192.168.5.0/24,fd00::/64"))
        assertEquals("$routes (<${R.string.remote_log_none}>)", what("setting", "advertise_routes="))
    }

    @Test
    fun aKeyWithoutWordsStaysAsItCame() {
        assertEquals("something_new (<${R.string.remote_log_on}>)", what("setting", "something_new=true"))
        assertEquals("something_new", what("setting", "something_new"))
        assertEquals("", what("setting", ""))
    }

    @Test
    fun anActionAndAChangePutBackAreNamed() {
        assertEquals("<${R.string.remote_can_act_reconnect}>", what("action", "reconnect"))
        assertEquals("<${R.string.remote_can_act_save_received}>", what("action", "save_received (applied late)"))
        assertEquals("polish_the_screen", what("action", "polish_the_screen"))
        assertEquals("<${R.string.remote_can_set_tun_mode}>", what("reverted", "tun_mode: not confirmed"))
        assertEquals("<${R.string.remote_can_set_exit_node}>", what("reverted", "exit_node: not confirmed; the one before is gone, so none"))
    }

    @Test
    fun aNameIsNeverTakenApart() {
        // A node, a file, a share: the detail is theirs, with whatever signs are in it.
        assertEquals("exit-frankfurt", what("exit_node", "exit-frankfurt"))
        assertEquals("a=b: notes.txt", what("file_deleted", "a=b: notes.txt"))
        assertEquals("a=b: notes.txt", what("file_sent", "a=b: notes.txt"))
        assertEquals("Movies", what("share_added", "Movies"))
        assertEquals("Movies", what("share_removed", "Movies"))
        assertEquals("accept_routes=true", what("something_new", "accept_routes=true"))
    }
}
