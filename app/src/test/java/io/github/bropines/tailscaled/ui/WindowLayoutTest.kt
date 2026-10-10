package io.github.bropines.tailscaled.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test
    fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(WindowWidthClass.COMPACT, WindowWidthClass.of(411.dp))
        assertEquals(WindowWidthClass.MEDIUM, WindowWidthClass.of(600.dp))
        assertEquals(WindowWidthClass.MEDIUM, WindowWidthClass.of(839.dp))
        assertEquals(WindowWidthClass.EXPANDED, WindowWidthClass.of(840.dp))
        assertEquals(WindowWidthClass.LARGE, WindowWidthClass.of(1280.dp))
        assertEquals(WindowWidthClass.EXTRA_LARGE, WindowWidthClass.of(1600.dp))
        assertEquals(WindowHeightClass.COMPACT, WindowHeightClass.of(411.dp))
        assertEquals(WindowHeightClass.MEDIUM, WindowHeightClass.of(800.dp))
        assertEquals(WindowHeightClass.EXPANDED, WindowHeightClass.of(1280.dp))
    }

    @Test
    fun aPhoneIsAPhoneEitherWayUp() {
        val upright = WindowLayout(411.dp, 891.dp)
        val turned = WindowLayout(891.dp, 411.dp)
        for (phone in listOf(upright, turned)) {
            assertTrue(phone.isPhone)
            assertFalse(phone.listDetail)
            assertFalse(phone.multiColumn)
            assertEquals(16.dp, phone.margin)
        }
    }

    @Test
    fun tabletsGetPanesFromExpandedAndColumnsFromMedium() {
        val small = WindowLayout(600.dp, 960.dp)
        assertTrue(small.multiColumn)
        assertFalse(small.listDetail)
        val upright = WindowLayout(800.dp, 1280.dp)
        assertTrue(upright.multiColumn)
        assertFalse(upright.listDetail)
        val foldable = WindowLayout(840.dp, 900.dp)
        assertTrue(foldable.listDetail)
        assertEquals(360.dp, foldable.listPaneWidth)
        val landscape = WindowLayout(1280.dp, 800.dp)
        assertTrue(landscape.listDetail)
        assertEquals(400.dp, landscape.listPaneWidth)
        assertEquals(24.dp, landscape.margin)
    }

    @Test
    fun aBookFoldSplitsWhateverItsWidth() {
        val book = WindowLayout(673.dp, 841.dp, Fold.Vertical(330.dp, 343.dp))
        assertTrue(book.listDetail)
        val tabletop = WindowLayout(841.dp, 673.dp, Fold.Horizontal(330.dp, 343.dp))
        assertTrue(tabletop.listDetail)
        val flat = WindowLayout(673.dp, 841.dp)
        assertFalse(flat.listDetail)
    }

    @Test
    fun columnsFitTheWidth() {
        assertEquals(1, columnsFor(379.dp, 320.dp))
        assertEquals(1, columnsFor(651.dp, 320.dp))
        assertEquals(2, columnsFor(652.dp, 320.dp))
        assertEquals(3, columnsFor(1232.dp, 320.dp))
        assertEquals(2, columnsFor(1232.dp, 320.dp, maxColumns = 2))
        assertEquals(1, columnsFor(100.dp, 320.dp))
        assertEquals(1, columnsFor(androidx.compose.ui.unit.Dp.Infinity, 320.dp))
    }
}
