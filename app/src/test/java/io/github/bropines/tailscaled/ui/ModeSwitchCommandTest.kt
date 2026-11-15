package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One service command per tunnel-mode switch (0 Proxy, 1 VPN, 2 Root). Root -> VPN used
 * to send a restart and a settings apply, and the native TUN was established twice.
 */
class ModeSwitchCommandTest {

    @Test
    fun everySwitchSendsExactlyOneCommand() {
        val table = listOf(
            Triple(0, 1, ModeSwitchCommand.APPLY_SETTINGS), // Proxy -> VPN
            Triple(1, 0, ModeSwitchCommand.APPLY_SETTINGS), // VPN -> Proxy
            Triple(0, 2, ModeSwitchCommand.RESTART),        // Proxy -> Root
            Triple(1, 2, ModeSwitchCommand.RESTART),        // VPN -> Root
            Triple(2, 0, ModeSwitchCommand.RESTART),        // Root -> Proxy
            Triple(2, 1, ModeSwitchCommand.RESTART),        // Root -> VPN: no apply beside it
            Triple(0, 0, ModeSwitchCommand.NONE),
            Triple(1, 1, ModeSwitchCommand.NONE),
            Triple(2, 2, ModeSwitchCommand.NONE),
        )
        for ((from, to, expected) in table) {
            assertEquals("$from -> $to", expected, modeSwitchCommand(from, to))
        }
    }
}
