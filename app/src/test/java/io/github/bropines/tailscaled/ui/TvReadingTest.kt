package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvReadingTest {
    @Test
    fun textSizeButtonStepsUpAndWrapsToTheFirst() {
        assertEquals(1.25f, nextTvTextScale(1f))
        assertEquals(1.5f, nextTvTextScale(1.25f))
        assertEquals(2f, nextTvTextScale(1.5f))
        // Past the largest it goes back to the smallest: one button, no way to get stuck.
        assertEquals(1f, nextTvTextScale(2f))
    }

    @Test
    fun aSizeBetweenTwoStepsGoesToTheNextOne() {
        // A pinch on a phone may have left 1.1 behind; the button still lands on a step.
        assertEquals(1.25f, nextTvTextScale(1.1f))
        assertEquals(1f, nextTvTextScale(3.5f))
    }

    @Test
    fun onlyAddressesAndLinksGetACode() {
        val withCode = PeerCopyKind.entries.filter { it.hasQr() }.toSet()
        assertEquals(
            setOf(PeerCopyKind.FULL_NAME, PeerCopyKind.IPV4, PeerCopyKind.IPV6, PeerCopyKind.WEB),
            withCode
        )
        // A command is read, not scanned.
        assertFalse(PeerCopyKind.SSH.hasQr())
        assertFalse(PeerCopyKind.PING.hasQr())
        assertTrue(PeerCopyKind.WEB.hasQr())
    }
}
