package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hev-socks5-tunnel YAML: every string a valid single-quoted scalar, whatever the user typed. */
class HevConfigTest {

    @Test
    fun `plain values come out as before`() {
        val text = HevConfig.text("127.0.0.1", 1055, 1280, "user", "secret")
        assertEquals(
            "tunnel:\n  mtu: 1280\n\nsocks5:\n  address: '127.0.0.1'\n  port: 1055\n  udp: 'udp'\n" +
                "  username: 'user'\n  password: 'secret'\n",
            text
        )
    }

    @Test
    fun `no credentials, no credential lines`() {
        val text = HevConfig.text("127.0.0.1", 1055, 1280, "user", "")
        assertFalse(text.contains("username"))
        assertFalse(text.contains("password"))
    }

    @Test
    fun `a quote in a password is doubled, the YAML way`() {
        val text = HevConfig.text("127.0.0.1", 1055, 1280, "o'neil", "a'b")
        assertTrue(text, text.contains("  username: 'o''neil'\n"))
        assertTrue(text, text.contains("  password: 'a''b'\n"))
    }

    @Test
    fun `a line break cannot add a key`() {
        val text = HevConfig.text("127.0.0.1", 1055, 1280, "u", "x\nudp: 'evil'\r")
        assertTrue(text, text.contains("  password: 'xudp: ''evil'''\n"))
        assertEquals(1, text.lines().count { it.trim().startsWith("udp:") })
    }
}
