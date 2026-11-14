package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces of the one-shell Root Mode stop: routing cleanup, kill, state hand-back. */
class RootStopScriptTest {

    private val socket = "/data/user/0/io.github.bropines.tailscaled/files/tailscaled.sock"

    @Test
    fun `the kill is scoped to our socket and still ends in SIGKILL`() {
        val lines = RootUtils.daemonKillScript(socket).trimEnd().lines()
        val pat = "'--socket=$socket'"
        assertEquals("pkill -15 -f -- $pat 2>/dev/null || true", lines[0])
        // A poll instead of a fixed second: at most ten 0.1 s waits, ended early once the daemon is gone.
        assertEquals(
            "i=0; while [ \$i -lt 10 ] && pgrep -f -- $pat >/dev/null 2>&1; do sleep 0.1; i=\$((i+1)); done",
            lines[1]
        )
        assertEquals("pkill -9 -f -- $pat 2>/dev/null || true", lines[2])
        assertEquals("rm -f '$socket'", lines[3])
        assertEquals(4, lines.size)
        assertFalse(lines.any { it.contains("libtailscale.so") })
    }

    @Test
    fun `a socket path with a quote cannot break out of the pattern`() {
        val script = RootUtils.daemonKillScript("/x/it's.sock")
        assertTrue(script.contains("'--socket=/x/it'\\''s.sock'"))
    }

    @Test
    fun `without a socket path the kill falls back to the exact process name`() {
        val lines = RootUtils.daemonKillScript("").trimEnd().lines()
        assertEquals("killall -15 libtailscale.so 2>/dev/null || true", lines[0])
        assertTrue(lines[1].contains("pidof libtailscale.so"))
        assertEquals("killall -9 libtailscale.so 2>/dev/null || true", lines[2])
    }

    @Test
    fun `the cleanup can be followed by more commands in the same shell`() {
        val script = RootUtils.routingCleanupScript()
        assertFalse(script.lines().any { it.trim() == "exit" || it.trim().startsWith("exit ") })
        assertTrue(script.endsWith("\n"))
        // Every tier it is meant to remove is named in it.
        for (needle in listOf("TAILSOCKS_DNS", "TAILSOCKS_MARK", "table 53", "priority 200 lookup 52", "priority 150")) {
            assertTrue(needle, script.contains(needle))
        }
    }

    @Test
    fun `the hand-back chowns state and taildrop and has no exit of its own`() {
        val script = RootUtils.stateHandBackScript(10123, "/d/files/states", "/d/files/taildrop")
        assertTrue(script.contains("chown -R 10123:10123 '/d/files/states'"))
        assertTrue(script.contains("chown -R 10123:10123 '/d/files/taildrop'"))
        assertTrue(script.contains("-type d -exec chmod 700"))
        assertTrue(script.contains("-type f -exec chmod 600"))
        assertFalse(script.contains("exit"))
    }
}
