package io.github.bropines.tailscaled.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TvSafeAreaTest {
    private fun horizontal(width: Int, height: Int) = tvSafeArea(width.dp, height.dp).calculateLeftPadding(LayoutDirection.Ltr).value
    private fun vertical(width: Int, height: Int) = tvSafeArea(width.dp, height.dp).calculateTopPadding().value

    @Test
    fun aTelevisionKeepsItsOwnMargins() {
        // 720p, 1080p and 4K are all 960 x 540dp.
        assertEquals(48f, horizontal(960, 540), 0.001f)
        assertEquals(27f, vertical(960, 540), 0.001f)
        // A larger window is no wider a margin than that.
        assertEquals(48f, horizontal(1920, 1080), 0.001f)
        assertEquals(27f, vertical(1920, 1080), 0.001f)
    }

    @Test
    fun aSmallerWindowKeepsFivePercentOfItself() {
        // A phone upright and on its side, with the TV interface turned on.
        assertEquals(20.55f, horizontal(411, 891), 0.01f)
        assertEquals(27f, vertical(411, 891), 0.001f)
        assertEquals(44.55f, horizontal(891, 411), 0.01f)
        assertEquals(20.55f, vertical(891, 411), 0.01f)
    }

    @Test
    fun aWindowWithNoSizeYetIsATelevisionsMargin() {
        assertEquals(48f, horizontal(0, 0), 0.001f)
        assertEquals(27f, vertical(0, 0), 0.001f)
    }
}
