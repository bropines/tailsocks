package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.RemoteDeviceNotice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line a managed device shows in its own bar (core/RemoteDeviceNotice.kt): one at a time,
 * each for its time, on a clock the test holds. Drawing it needs a device.
 */
class RemoteDeviceNoticeTest {
    private var now = 10_000L
    private val board = RemoteDeviceNoticeBoard { now }
    private val wait = RemoteDeviceNoticeBoard.DRAW_WAIT_MS

    private fun identify(stays: Long = RemotePromptRules.IDENTIFY_BAR_MS) =
        board.post(Kind.IDENTIFY, "pixel-9-pro", "Managing this device", "Managed from pixel-9-pro", stays)

    private fun change(what: String = "Exit node: exit-frankfurt", stays: Long = RemotePromptRules.NOTICE_BAR_MS) =
        board.post(Kind.CHANGE, "pixel-9-pro", what, "pixel-9-pro: $what", stays)

    /** A screen has put the line in front of a person. */
    private fun drawn(notice: RemoteDeviceNotice): RemoteDeviceNotice {
        board.drawn(notice.id)
        return board.current.value!!
    }

    @Test
    fun nothingIsShownUntilSomethingIsSaid() {
        assertNull(board.current.value)
        assertNull(board.showing())
        // Nothing to take down, and no harm in trying.
        board.expire()
        board.dismiss(1)
        board.drawn(1)
        assertNull(board.current.value)
    }

    @Test
    fun aLineIsShownWithItsTwoLinesAndItsSentence() {
        val notice = identify()
        assertSame(notice, board.current.value)
        assertSame(notice, board.showing())
        assertEquals(Kind.IDENTIFY, notice.kind)
        assertEquals("pixel-9-pro", notice.from)
        assertEquals("Managing this device", notice.text)
        assertEquals("Managed from pixel-9-pro", notice.spoken)
        assertEquals(RemotePromptRules.IDENTIFY_BAR_MS, notice.staysMs)
        assertFalse(notice.drawn)
    }

    @Test
    fun aNewLineTakesThePlaceOfTheOneShowing() {
        val first = drawn(identify())
        now += 500
        val second = change()
        assertSame(second, board.showing())
        assertNotEquals(first.id, second.id)
        // It starts from the beginning: nothing of the first one's time or of its being drawn is left.
        assertFalse(second.drawn)
        // The same thing said twice is still two lines: the bar has to know a new one came.
        val third = change()
        assertNotEquals(second.id, third.id)
        assertEquals(second.copy(id = third.id), third)
        assertSame(third, board.current.value)
    }

    @Test
    fun onceDrawnALineStaysItsTimeAndNotAMomentMore() {
        val notice = drawn(change(stays = 2_000))
        assertTrue(notice.drawn)
        assertEquals(2_000L, board.remaining(notice))
        now += 1_999
        board.expire()
        assertSame(notice, board.showing())
        assertEquals(1L, board.remaining(notice))
        now += 1
        // Over: not drawn any more, even before anything has taken it down.
        assertNull(board.showing())
        assertEquals(0L, board.remaining(notice))
        assertSame(notice, board.current.value)
        board.expire()
        assertNull(board.current.value)
    }

    @Test
    fun theTimeToBeReadStartsWhenTheLineIsDrawnNotWhenItWasSaid() {
        val said = change(stays = 2_000)
        // A slow device: the bar's window is a second and a half in coming.
        now += 1_500
        val notice = drawn(said)
        assertEquals(2_000L, board.remaining(notice))
        now += 1_900
        board.expire()
        assertSame(notice, board.showing())
        now += 100
        board.expire()
        assertNull(board.current.value)
    }

    @Test
    fun beingDrawnAgainDoesNotStartTheTimeOver() {
        val notice = drawn(identify(stays = 3_500))
        now += 3_000
        // A dialog opens over the screen and draws the same line in its own window.
        board.drawn(notice.id)
        assertSame(notice, board.current.value)
        assertEquals(500L, board.remaining(notice))
        // And a line that is not the one showing is nobody's to start.
        board.drawn(notice.id + 1)
        assertSame(notice, board.current.value)
    }

    @Test
    fun aLineNobodyDrawsWaitsAFewSecondsAndIsGone() {
        val notice = identify()
        assertEquals(wait, board.remaining(notice))
        now += wait - 1
        board.expire()
        assertSame(notice, board.showing())
        now += 1
        assertNull(board.showing())
        board.expire()
        assertNull(board.current.value)
        // Too late to draw it now.
        board.drawn(notice.id)
        assertNull(board.current.value)
    }

    @Test
    fun aLineNobodyDrewIsNotShownWhenTheAppIsOpenedLater() {
        identify()
        now += 3_600_000
        assertNull(board.showing())
        board.expire()
        assertNull(board.current.value)
    }

    @Test
    fun theTimerOfALineThatWasReplacedLeavesTheNewOneUp() {
        val first = drawn(change(stays = 2_000))
        now += 1_500
        val second = drawn(identify(stays = 3_500))
        // The first one's two seconds are over; the second has three more.
        now += 500
        assertEquals(0L, board.remaining(first))
        board.expire()
        assertSame(second, board.showing())
        now += 3_000
        board.expire()
        assertNull(board.current.value)
    }

    @Test
    fun wavingALineAwayTakesDownThatLineOnly() {
        val first = change()
        board.dismiss(first.id + 1)
        assertSame(first, board.showing())
        val second = identify()
        // A touch that was meant for the first one lands after the second has replaced it.
        board.dismiss(first.id)
        assertSame(second, board.showing())
        board.dismiss(second.id)
        assertNull(board.current.value)
        assertNull(board.showing())
    }

    @Test
    fun identifyStaysLongerThanAChange() {
        assertTrue(RemotePromptRules.IDENTIFY_BAR_MS > RemotePromptRules.NOTICE_BAR_MS)
        // What a long and a short toast meant.
        assertEquals(3_500L, RemotePromptRules.IDENTIFY_BAR_MS)
        assertEquals(2_000L, RemotePromptRules.NOTICE_BAR_MS)
        val long = board.remaining(drawn(identify()))
        assertTrue(long > board.remaining(drawn(change())))
    }

    @Test
    fun aTimeThatMakesNoSenseIsNoTimeAtAll() {
        val notice = drawn(change(stays = -5))
        assertEquals(0L, notice.staysMs)
        assertNull(board.showing())
    }
}
