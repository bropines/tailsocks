package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class WorldLandTest {
    /** The baked format: u16 counts, i16 hundredths of a degree, big-endian. */
    private fun bake(vararg rings: List<Pair<Double, Double>>): ByteArray {
        val buf = ByteBuffer.allocate(2 + rings.sumOf { 2 + it.size * 4 })
        buf.putShort(rings.size.toShort())
        for (ring in rings) {
            buf.putShort(ring.size.toShort())
            for ((lon, lat) in ring) {
                buf.putShort(Math.round(lon * 100).toShort())
                buf.putShort(Math.round(lat * 100).toShort())
            }
        }
        return buf.array()
    }

    private val triangle = listOf(-180.0 to 80.0, 12.34 to -56.78, 179.99 to 0.0)
    private val square = listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)

    @Test
    fun readsRingsAsDegrees() {
        val rings = WorldLand.parse(bake(triangle, square))
        assertEquals(2, rings.size)
        assertArrayEquals(floatArrayOf(-180f, 80f, 12.34f, -56.78f, 179.99f, 0f), rings[0], 1e-4f)
        assertEquals(8, rings[1].size)
    }

    @Test
    fun aFileCutShortKeepsItsWholeRings() {
        val bytes = bake(triangle, square)
        val rings = WorldLand.parse(bytes.copyOf(bytes.size - 3))
        assertEquals(1, rings.size)
        assertEquals(6, rings[0].size)
        assertTrue(WorldLand.parse(ByteArray(0)).isEmpty())
        assertTrue(WorldLand.parse(byteArrayOf(0)).isEmpty())
    }

    @Test
    fun aRingThatEnclosesNothingIsDropped() {
        val rings = WorldLand.parse(bake(listOf(0.0 to 0.0, 1.0 to 1.0), square))
        assertEquals(1, rings.size)
    }

    /** The file the app ships is the one the script's docstring describes. */
    @Test
    fun theShippedOutlineParses() {
        val file = listOf("src/main/res/raw/world_land.bin", "app/src/main/res/raw/world_land.bin").map(::File).first { it.exists() }
        val rings = WorldLand.parse(file.readBytes())
        assertEquals(43, rings.size)
        assertEquals(1573, rings.sumOf { it.size / 2 })
        for (ring in rings) for (i in ring.indices step 2) {
            assertTrue(ring[i] in -180f..180f)
            assertTrue(ring[i + 1] in -60f..90f)
        }
    }
}
