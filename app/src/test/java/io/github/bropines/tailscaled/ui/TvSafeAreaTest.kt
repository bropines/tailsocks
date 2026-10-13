package io.github.bropines.tailscaled.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TvSafeAreaTest {
    private fun horizontal(width: Int, height: Int) = tvSafeArea(width.dp, height.dp).calculateLeftPadding(LayoutDirection.Ltr).value
    private fun vertical(width: Int, height: Int) = tvSafeArea(width.dp, height.dp).calculateTopPadding().value

    @Test
    fun everyWindowATelevisionHasKeepsItsOwnMargins() {
        // 720p, 1080p and 4K are 960 x 540dp; the interface at 125% is 768 x 432dp, and a 720p
        // panel at 320 dpi, or the interface at 150%, is 640 x 360dp.
        for ((width, height) in listOf(960 to 540, 768 to 432, 640 to 360, 1920 to 1080)) {
            assertEquals("$width x $height", 48f, horizontal(width, height), 0.001f)
            assertEquals("$width x $height", 27f, vertical(width, height), 0.001f)
        }
    }

    @Test
    fun aSmallerWindowKeepsAShareOfItself() {
        // A phone upright, with the TV interface turned on: a share of its width, not 48dp.
        assertEquals(30.825f, horizontal(411, 891), 0.01f)
        assertEquals(27f, vertical(411, 891), 0.001f)
        // On its side it is as wide as a television and as low as one.
        assertEquals(48f, horizontal(891, 411), 0.001f)
        assertEquals(27f, vertical(891, 411), 0.001f)
        // And a window lower than that gives up some of its height.
        assertEquals(22.5f, vertical(891, 300), 0.001f)
    }

    @Test
    fun aWindowWithNoSizeYetIsATelevisionsMargin() {
        assertEquals(48f, horizontal(0, 0), 0.001f)
        assertEquals(27f, vertical(0, 0), 0.001f)
    }
}
