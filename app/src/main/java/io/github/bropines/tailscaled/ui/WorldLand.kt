package io.github.bropines.tailscaled.ui

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.R
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/*
 * The world outline behind the exit-node map: res/raw/world_land.bin, baked from Natural
 * Earth's 1:110m land by scripts/bake_world_land.py. A few kilobytes of coastline, not a map
 * library — the map is a backdrop for a handful of dots.
 */
internal object WorldLand {
    private const val TAG = "WorldLand"

    @Volatile private var cached: List<FloatArray>? = null

    /**
     * The land as closed rings, each `[lon0, lat0, lon1, lat1, …]` in degrees. Read once per
     * process; a file that cannot be read gives no land, and the dots are drawn without it.
     */
    fun rings(context: Context): List<FloatArray> {
        cached?.let { return it }
        // By id, not by name: a raw resource only looked up by name is one R8's resource
        // shrinking is free to drop from a release build.
        val rings = try {
            context.resources.openRawResource(R.raw.world_land).use { parse(it.readBytes()) }
        } catch (e: Exception) {
            Log.w(TAG, "world outline: ${e.javaClass.simpleName}")
            emptyList()
        }
        cached = rings
        return rings
    }

    /**
     * The baked format, big-endian: u16 ring count; per ring a u16 point count, then that
     * many (i16 lon × 100, i16 lat × 100). A file cut short gives the rings that were whole:
     * half an outline still tells where the dots are.
     */
    fun parse(bytes: ByteArray): List<FloatArray> {
        val buf = ByteBuffer.wrap(bytes)
        val rings = ArrayList<FloatArray>()
        try {
            val count = buf.short.toInt() and 0xFFFF
            repeat(count) {
                val points = buf.short.toInt() and 0xFFFF
                val ring = FloatArray(points * 2)
                for (i in ring.indices) ring[i] = buf.short / 100f
                // Fewer than three points enclose nothing.
                if (points >= 3) rings.add(ring)
            }
        } catch (e: BufferUnderflowException) {
            // Truncated: keep what was read.
        }
        return rings
    }
}
