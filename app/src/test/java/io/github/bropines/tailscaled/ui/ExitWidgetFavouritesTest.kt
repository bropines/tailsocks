package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** An exit-node widget's favourites: what it shows, and what its configuration screen lists. */
class ExitWidgetFavouritesTest {

    private val a = ExitRow("idA", "100.64.0.1", "alpha", selected = false, online = true)
    private val b = ExitRow("idB", "100.64.0.2", "bravo", selected = true, online = true)
    private val c = ExitRow("idC", "100.64.0.3", "charlie", selected = false, online = false)
    private val all = listOf(b, a, c)

    @Test
    fun noFavouritesShowsEveryNode() {
        assertEquals(all, instanceRows(all, emptyList()))
        assertEquals(listOf(b, a), instanceRows(all, emptyList(), limit = 2))
    }

    @Test
    fun favouritesInTheirOrder() {
        assertEquals(listOf(c, b), instanceRows(all, listOf("idC", "idB")))
    }

    @Test
    fun selectedNodeLeadsWhenNotAFavourite() {
        assertEquals(listOf(b, c, a), instanceRows(all, listOf("idC", "idA")))
    }

    @Test
    fun goneFavouritesAreSkippedAndDuplicatesIgnored() {
        assertEquals(listOf(b, a), instanceRows(all, listOf("idGone", "idA", "idA")))
        // Nothing selected and every favourite gone: an empty list, not every node.
        val unselected = listOf(a.copy(), c)
        assertEquals(emptyList<ExitRow>(), instanceRows(unselected, listOf("idGone")))
    }

    @Test
    fun configScreenKeepsGoneFavouritesMarked() {
        val (chosen, others) = configLists(all, listOf("idC", "idGone"))
        assertEquals(listOf("idC", "idGone"), chosen.map { it.id })
        assertEquals(null, chosen[1].name)
        assertEquals("charlie", chosen[0].name)
        assertEquals(listOf("idB", "idA"), others.map { it.id })
    }

    @Test
    fun movingStaysInBounds() {
        val list = listOf("x", "y", "z")
        assertEquals(listOf("y", "x", "z"), moved(list, 1, -1))
        assertEquals(listOf("x", "z", "y"), moved(list, 1, 1))
        assertSame(list, moved(list, 0, -1))
        assertSame(list, moved(list, 2, 1))
    }

    @Test
    fun configSurvivesItsStore() {
        val config = ExitWidgetConfig(listOf("idB", "idA"), showBest = false)
        assertEquals(config, decodeConfig(encodeConfig(config)))
        assertEquals(ExitWidgetConfig(), decodeConfig(null))
        assertEquals(ExitWidgetConfig(), decodeConfig("garbage"))
    }
}
