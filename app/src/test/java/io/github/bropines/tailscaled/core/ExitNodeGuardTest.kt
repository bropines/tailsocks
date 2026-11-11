package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.ExitNodeGuard.Step
import io.github.bropines.tailscaled.models.PeerData
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the exit-node guard does on a tick, from what the tick read. */
class ExitNodeGuardTest {
    private val grace = ExitNodeGuard.OFFLINE_GRACE_MS

    @Test
    fun nothingSelectedAndNothingPausedIsQuiet() {
        assertEquals(Step.QUIET, ExitNodeGuard.step("", "", null, 0))
        assertEquals(Step.QUIET, ExitNodeGuard.step("", "", false, grace * 10))
    }

    @Test
    fun anOnlineExitNodeIsQuiet() {
        assertEquals(Step.QUIET, ExitNodeGuard.step("nA", "", true, 0))
    }

    @Test
    fun anOfflineExitNodeIsWaitedOutBeforeAnyoneIsTold() {
        assertEquals(Step.WAIT, ExitNodeGuard.step("nA", "", false, 0))
        assertEquals(Step.WAIT, ExitNodeGuard.step("nA", "", false, grace - 1))
        assertEquals(Step.WARN, ExitNodeGuard.step("nA", "", false, grace))
    }

    @Test
    fun aNodeGoneFromTheNetmapCountsAsOffline() {
        assertEquals(Step.WAIT, ExitNodeGuard.step("nA", "", null, 0))
        assertEquals(Step.WARN, ExitNodeGuard.step("nA", "", null, grace))
    }

    @Test
    fun aPausedNodeComesBackOnlyWhenItIsOnline() {
        assertEquals(Step.STAY_PAUSED, ExitNodeGuard.step("", "nA", false, 0))
        assertEquals(Step.STAY_PAUSED, ExitNodeGuard.step("", "nA", null, 0))
        assertEquals(Step.RESTORE, ExitNodeGuard.step("", "nA", true, 0))
    }

    @Test
    fun aSelectedNodeOutranksAStalePause() {
        // select() ends a pause; if one is left behind anyway, the choice in force is judged.
        assertEquals(Step.QUIET, ExitNodeGuard.step("nB", "nA", true, 0))
        assertEquals(Step.WARN, ExitNodeGuard.step("nB", "nA", false, grace))
    }

    @Test
    fun aNodeIsCalledByItsHostNameThenItsDnsLabelThenTheFallback() {
        assertEquals("pi", ExitNodeGuard.displayName(PeerData(hostName = "pi", dnsName = "pi-1.tail.ts.net."), "100.64.0.1"))
        assertEquals("pi-1", ExitNodeGuard.displayName(PeerData(hostName = " ", dnsName = "pi-1.tail.ts.net."), "100.64.0.1"))
        assertEquals("100.64.0.1", ExitNodeGuard.displayName(null, "100.64.0.1"))
    }
}
