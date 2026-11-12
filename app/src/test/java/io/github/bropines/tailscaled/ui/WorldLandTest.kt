package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldLandTest {
    @Test
    fun readsRingsAsDegrees() {
        val rings = WorldLand.parse("M-180 80 12.34 -56.78 179.99 0Z\nM0 0 L10 0 L10,10 L0 10Z")
        assertEquals(2, rings.size)
        assertArrayEquals(floatArrayOf(-180f, 80f, 12.34f, -56.78f, 179.99f, 0f), rings[0], 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f), rings[1], 1e-4f)
    }

    @Test
    fun aBrokenRingIsSkippedAndTheRestKept() {
        assertEquals(1, WorldLand.parse("M1 2 3 4 5Z M0 0 10 0 10 10Z").size)
        assertEquals(1, WorldLand.parse("M1 2 x 4 5 6Z M0 0 10 0 10 10Z").size)
        assertTrue(WorldLand.parse("").isEmpty())
    }

    @Test
    fun aRingThatEnclosesNothingIsDropped() {
        assertEquals(1, WorldLand.parse("M0 0 1 1Z M0 0 10 0 10 10Z").size)
    }

    /** The outline the app ships is the one the baking script describes. */
    @Test
    fun theShippedOutlineParses() {
        val rings = WorldLand.rings
        assertEquals(43, rings.size)
        assertEquals(1573, rings.sumOf { it.size / 2 })
        for (ring in rings) for (i in ring.indices step 2) {
            assertTrue(ring[i] in -180f..180f)
            assertTrue(ring[i + 1] in -60f..90f)
        }
    }
}
