package io.github.bropines.tailscaled.admin

import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.Fold
import io.github.bropines.tailscaled.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which window the console gives a rail, panes and columns of cards (AdminAdaptive.kt). */
class AdminAdaptiveTest {
    private val phone = WindowLayout(411.dp, 891.dp)
    private val phoneTurned = WindowLayout(891.dp, 411.dp)
    private val tablet7 = WindowLayout(600.dp, 960.dp)
    private val tabletUpright = WindowLayout(800.dp, 1280.dp)
    private val foldable = WindowLayout(840.dp, 900.dp)
    private val tablet = WindowLayout(1280.dp, 800.dp)
    private val book = WindowLayout(673.dp, 841.dp, Fold.Vertical(330.dp, 343.dp))
    private val wideBook = WindowLayout(1000.dp, 841.dp, Fold.Vertical(493.dp, 507.dp))

    @Test
    fun aPhoneKeepsItsChipsAndItsColumnEitherWayUp() {
        for (w in listOf(phone, phoneTurned)) {
            assertFalse(w.adminRail)
            assertEquals(CardPage.PHONE, w.cardPage)
        }
    }

    @Test
    fun theRailStandsFromExpandedUpAndNamesItsTabsFromLarge() {
        assertFalse(tablet7.adminRail)
        assertFalse(tabletUpright.adminRail)
        assertTrue(foldable.adminRail)
        assertFalse(foldable.adminRailWide)
        assertTrue(tablet.adminRail)
        assertTrue(tablet.adminRailWide)
    }

    @Test
    fun aBookFoldKeepsTheChipsSoTheListStaysOnItsHalf() {
        assertFalse(book.adminRail)
        assertFalse(wideBook.adminRail)
        assertTrue(book.listDetail)
        assertTrue(wideBook.listDetail)
    }

    @Test
    fun pagesOfCardsAreReadableOnMediumAndInColumnsFromExpanded() {
        assertEquals(CardPage.READABLE, tablet7.cardPage)
        assertEquals(CardPage.READABLE, tabletUpright.cardPage)
        assertEquals(CardPage.READABLE, book.cardPage)
        assertEquals(CardPage.COLUMNS, foldable.cardPage)
        assertEquals(CardPage.COLUMNS, tablet.cardPage)
    }
}
