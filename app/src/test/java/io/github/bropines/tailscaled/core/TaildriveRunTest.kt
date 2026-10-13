package io.github.bropines.tailscaled.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the refresh tick applies Taildrive. Found on two emulators: after a switch between proxy and TUN mode the
 * shares still listed from the other device but every read answered 500, until the settings were applied once more.
 */
class TaildriveRunTest {
    private val first = 1_791_900_000L
    private val second = 1_791_900_090L

    @Test
    fun aRunThatIsRunningGetsItOnce() {
        assertTrue(TaildriveRun.due(true, "Running", TaildriveRun.NONE, first))
        assertFalse(TaildriveRun.due(true, "Running", first, first))
    }

    /** The switch of modes: a new daemon, a new stamp, the old one was applied for. */
    @Test
    fun aRelaunchedDaemonIsANewRun() {
        assertTrue(TaildriveRun.due(true, "Running", first, second))
    }

    @Test
    fun nothingIsAppliedBeforeTheMapIsIn() {
        for (state in listOf("", "NoState", "Starting", "NeedsLogin", "Stopped")) {
            assertFalse(state, TaildriveRun.due(true, state, TaildriveRun.NONE, second))
        }
    }

    @Test
    fun nothingIsAppliedWithoutADaemon() {
        assertFalse(TaildriveRun.due(false, "Running", TaildriveRun.NONE, TaildriveRun.NONE))
        assertFalse(TaildriveRun.due(false, "", first, second))
    }

    /** A fresh start forgets the old run, so a daemon whose stamp happens to repeat is still applied. */
    @Test
    fun aStartForgetsWhatWasApplied() {
        assertTrue(TaildriveRun.due(true, "Running", TaildriveRun.NONE, first))
    }
}
