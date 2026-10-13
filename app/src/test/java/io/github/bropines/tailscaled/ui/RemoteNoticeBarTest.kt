package io.github.bropines.tailscaled.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the bar of a paired device's line goes on the screen (ui/RemoteNoticeBar.kt): inside
 * the system's bars and a television's safe area, in the middle of the top edge, and at the
 * bottom of a television when what the remote is on would be under it. All in pixels.
 */
class RemoteNoticeBarTest {
    /** A 1080p television: 1920 x 1080, no bars, a safe area of 96 x 54. */
    private val tv = noticeFrame(IntRect(0, 0, 1920, 1080), NoticeInsets(), NoticeInsets(96, 54, 96, 54))

    /** A bar of two lines there: 480dp x 60dp at 320 dpi. */
    private val tvBar = IntSize(960, 120)

    @Test
    fun theFrameIsTheWindowLessTheBarsAndTheSafeArea() {
        // A phone upright: a status bar above, a gesture bar below.
        assertEquals(IntRect(0, 84, 1080, 2277), noticeFrame(IntRect(0, 0, 1080, 2340), NoticeInsets(top = 84, bottom = 63)))
        // On its side, with a cutout to the left and three buttons to the right.
        assertEquals(IntRect(96, 0, 2214, 1080), noticeFrame(IntRect(0, 0, 2340, 1080), NoticeInsets(left = 96, right = 126)))
        assertEquals(IntRect(96, 54, 1824, 1026), tv)
        // A window that does not start at the screen's corner: half of a split screen.
        assertEquals(IntRect(0, 1200, 1080, 2277), noticeFrame(IntRect(0, 1170, 1080, 2340), NoticeInsets(top = 30, bottom = 63)))
    }

    @Test
    fun theBarIsAsWideAsAPhoneLessItsGuttersAndNoWiderThanItsMost() {
        // 411dp at 2.625: 1080 px, gutters of 12dp, at most 480dp.
        assertEquals(1016, noticeWidth(frame = 1080, max = 1260, gutter = 32))
        // A tablet and a television take the most.
        assertEquals(1260, noticeWidth(frame = 3360, max = 1260, gutter = 32))
        assertEquals(960, noticeWidth(frame = tv.width, max = 960, gutter = 0))
        // No room at all is no bar, not a negative one.
        assertEquals(0, noticeWidth(frame = 40, max = 960, gutter = 32))
    }

    @Test
    fun theBarIsInTheMiddleOfTheTopEdge() {
        val phone = IntRect(0, 84, 1080, 2277)
        assertEquals(IntOffset(20, 84), noticePosition(phone, IntSize(1040, 200), NoticeEdge.TOP))
        assertEquals(IntOffset(20, 94), noticePosition(phone, IntSize(1040, 200), NoticeEdge.TOP, gap = 10))
        // A television's: on the safe area's edge, and its shadow's room may reach past it.
        assertEquals(IntOffset(480, 54), noticePosition(tv, tvBar, NoticeEdge.TOP))
        assertEquals(IntOffset(464, 38), noticePosition(tv, IntSize(992, 208), NoticeEdge.TOP, gap = -16))
    }

    @Test
    fun atTheBottomItStandsOnTheBottomEdge() {
        assertEquals(IntOffset(480, 1026 - 120), noticePosition(tv, tvBar, NoticeEdge.BOTTOM))
        assertEquals(IntOffset(464, 1026 + 16 - 208), noticePosition(tv, IntSize(992, 208), NoticeEdge.BOTTOM, gap = -16))
    }

    @Test
    fun aTelevisionsBarKeepsToTheTopWhileTheRemoteIsElsewhere() {
        assertEquals(NoticeEdge.TOP, noticeEdge(null, tv, tvBar))
        // The dashboard's status card, its tiles, a row of settings.
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(144, 384, 800, 848), tv, tvBar))
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(848, 206, 1056, 430), tv, tvBar))
        // The top bar's buttons, right of where the bar ends.
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(1624, 70, 1720, 166), tv, tvBar))
        // Nothing of any size has the focus.
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(500, 60, 500, 60), tv, tvBar))
    }

    @Test
    fun itGoesToTheBottomWhenTheRemoteIsUnderItsPlaceAtTheTop() {
        // A tab in the middle of a top bar, a field at the head of a page.
        assertEquals(NoticeEdge.BOTTOM, noticeEdge(IntRect(700, 80, 1000, 160), tv, tvBar))
        assertEquals(NoticeEdge.BOTTOM, noticeEdge(IntRect(96, 150, 1824, 260), tv, tvBar))
        // Touching the bar's edge is not being under it.
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(700, 174, 1000, 300), tv, tvBar))
        // A line long enough for a third row makes the bar taller, and the same tile is under it.
        val tile = IntRect(848, 206, 1056, 430)
        assertEquals(NoticeEdge.TOP, noticeEdge(tile, tv, tvBar))
        assertEquals(NoticeEdge.BOTTOM, noticeEdge(tile, tv, IntSize(960, 160)))
    }

    @Test
    fun aPaneAsTallAsTheScreenIsUnderBothAndTheBarStaysAtTheTop() {
        // A log or a licence with the remote in it: there is no edge it does not reach.
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(96, 54, 1824, 1026), tv, tvBar))
        assertEquals(NoticeEdge.TOP, noticeEdge(IntRect(0, 0, 1920, 1080), tv, tvBar))
    }
}
