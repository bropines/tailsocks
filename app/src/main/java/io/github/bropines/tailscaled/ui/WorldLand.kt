package io.github.bropines.tailscaled.ui

/*
 * The world outline behind the exit-node map: WORLD_LAND_PATH, an SVG path baked from Natural
 * Earth's 1:110m land by scripts/bake_world_land.py. A few kilobytes of coastline in source
 * code, not a map library and not a binary resource — the map projects, pans and clips it
 * itself and draws the dots on top.
 */
internal object WorldLand {
    /** The land as closed rings, each `[lon0, lat0, lon1, lat1, …]` in degrees. Parsed once. */
    val rings: List<FloatArray> by lazy { parse(WORLD_LAND_PATH) }

    /**
     * The subset of SVG path data the baker writes: `M x y x y … Z` per ring, absolute
     * coordinates, an optional `L`. A ring that does not parse is skipped; the others still
     * tell where the dots are.
     */
    fun parse(path: String): List<FloatArray> {
        val rings = ArrayList<FloatArray>()
        for (chunk in path.split('M', 'm')) {
            val body = chunk.trim().removeSuffix("Z").removeSuffix("z")
            if (body.isBlank()) continue
            val values = body.split(SEPARATORS).filter { it.isNotEmpty() }.map { it.toFloatOrNull() }
            if (values.size % 2 != 0 || values.any { it == null }) continue
            // Fewer than three points enclose nothing.
            if (values.size < 6) continue
            rings.add(FloatArray(values.size) { values[it]!! })
        }
        return rings
    }

    private val SEPARATORS = Regex("[\\s,Ll]+")
}
