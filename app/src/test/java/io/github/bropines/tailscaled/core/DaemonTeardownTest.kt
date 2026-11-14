package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The service's decisions around tearing a daemon down (DaemonTeardown.kt). */
class DaemonTeardownTest {

    private data class ExitRow(
        val teardownStarted: Boolean,
        val userWantsRunning: Boolean,
        val ownTeardown: Boolean,
        val expected: DaemonExit,
    )

    @Test
    fun `a daemon exit is a crash only outside every teardown of our own`() {
        val table = listOf(
            // Running, nobody tearing anything down: it died on its own.
            ExitRow(false, true, false, DaemonExit.CRASH),
            // A restart or auto-reconnect clears teardownStarted before its teardown;
            // its own kill is not a crash (the VPN -> Root switch was taken down by this).
            ExitRow(false, true, true, DaemonExit.EXPECTED),
            // A stop or the crash stand-down owns the service.
            ExitRow(true, true, false, DaemonExit.EXPECTED),
            ExitRow(true, false, false, DaemonExit.EXPECTED),
            ExitRow(true, true, true, DaemonExit.EXPECTED),
            // The user stopped: nothing to recover.
            ExitRow(false, false, false, DaemonExit.EXPECTED),
            ExitRow(false, false, true, DaemonExit.EXPECTED),
        )
        for (r in table) {
            assertEquals(r.toString(), r.expected, classifyDaemonExit(r.teardownStarted, r.userWantsRunning, r.ownTeardown))
        }
    }

    @Test
    fun `the native device's stop relaunches the daemon only when it is to outlive the tunnel`() {
        val table = listOf(
            // Settings apply switching TUN off while connected: the daemon carries on as a proxy.
            Triple(true, true, true),
            // Restart, recovery, crash stand-down: the daemon is killed next, no relaunch.
            Triple(false, true, false),
            // Stop: the connection is going down either way.
            Triple(true, false, false),
            Triple(false, false, false),
        )
        for ((stays, wanted, expected) in table) {
            assertEquals("stays=$stays wanted=$wanted", expected, relaunchAfterTunStop(stays, wanted))
        }
    }

    @Test
    fun `root routing is applied only for an attached root daemon`() {
        val table = listOf(
            // Root Mode on, our root daemon up.
            listOf(true, true, true) to true,
            // The switch into Root Mode: settings flipped, the userspace daemon still running.
            listOf(true, true, false) to false,
            // Root Mode without the tailscale0 tunnel (proxy only) has no routing.
            listOf(true, false, true) to false,
            listOf(false, true, true) to false,
            listOf(false, false, false) to false,
        )
        for ((args, expected) in table) {
            assertEquals(args.toString(), expected, rootRoutingApplies(args[0], args[1], args[2]))
        }
    }
}
