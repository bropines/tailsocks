package io.github.bropines.tailscaled.admin.policy.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The page pager and the console's page kept together: a swipe that comes to rest picks its
 * page; the pages a pager passes on its way to one the console asked for do not.
 */
class VisualNavTest {

    private val pages = VisualSection.entries.map { PageEntry(it, null, null) }
    private fun at(s: VisualSection) = pages.indexOfFirst { it.section == s }

    @Test
    fun aSwipeThatComesToRestPicksItsPage() {
        val ask = PagerAsk(at(VisualSection.ACCESS))
        assertEquals(VisualSection.SSH, settledPick(pages, at(VisualSection.SSH), VisualSection.ACCESS, ask))
        // Back where the console already is: nothing to pick.
        assertNull(settledPick(pages, at(VisualSection.ACCESS), VisualSection.ACCESS, ask))
    }

    @Test
    fun thePagesPassedOnTheWayToAnAskedPageAreNotPicks() {
        val ask = PagerAsk(at(VisualSection.ACCESS))
        // "Show" leads to a group: the console is on Groups, the pager on its way there.
        ask.request(at(VisualSection.GROUPS))
        // A tap on Tests before it got there, and the first slide stopping on SSH as it is cut short.
        ask.request(at(VisualSection.TESTS))
        assertNull(settledPick(pages, at(VisualSection.SSH), VisualSection.TESTS, ask))
        // There: done, and a swipe after it picks again.
        assertNull(settledPick(pages, at(VisualSection.TESTS), VisualSection.TESTS, ask))
        assertEquals(VisualSection.GROUPS, settledPick(pages, at(VisualSection.GROUPS), VisualSection.TESTS, ask))
    }

    @Test
    fun anArrivalOrAFingerEndsTheWait() {
        val ask = PagerAsk(0)
        ask.request(3)
        ask.arrived(2)
        assertEquals(3, ask.pending)
        ask.arrived(3)
        assertNull(ask.pending)
        // The same page asked again is no new request: a recomposition does not start a wait.
        ask.request(3)
        assertNull(ask.pending)
        // A drag clears the wait (the pager does that on DragInteraction.Start): the swipe wins.
        ask.request(5)
        ask.pending = null
        assertEquals(VisualSection.SSH, settledPick(pages, at(VisualSection.SSH), pages[5].section, ask))
        // A page the list no longer has picks nothing.
        assertNull(settledPick(pages, pages.size, VisualSection.ACCESS, ask))
    }
}
