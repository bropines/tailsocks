package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.ui.NetcheckVerdict.CONFIRMING
import io.github.bropines.tailscaled.ui.NetcheckVerdict.CONNECTED
import io.github.bropines.tailscaled.ui.NetcheckVerdict.OFFLINE
import org.junit.Assert.assertEquals
import org.junit.Test

class NetcheckVerdictTest {
    private val young = 5_000L
    private val old = 120_000L

    private fun verdict(
        selfOnline: Boolean = false,
        running: Boolean = true,
        onlinePeers: Int = 0,
        relay: Boolean = false,
        age: Long? = old
    ) = netcheckVerdict(selfOnline, running, onlinePeers, relay, age)

    @Test
    fun onlineIsConnectedWhateverElse() {
        assertEquals(CONNECTED, verdict(selfOnline = true))
        // As before: the coordination server's word is enough, even with no backend state to hand (a preview).
        assertEquals(CONNECTED, verdict(selfOnline = true, running = false, age = null))
    }

    @Test
    fun aBackendThatIsNotRunningIsOffline() {
        assertEquals(OFFLINE, verdict(running = false, onlinePeers = 5, relay = true, age = young))
    }

    /** The case from the device: Netcheck straight after Start, most peers online, Self.Online still false. */
    @Test
    fun aFreshStartNotYetMarkedOnlineIsConfirming() {
        assertEquals(CONFIRMING, verdict(onlinePeers = 7, relay = true, age = young))
        assertEquals(CONFIRMING, verdict(age = young))
        assertEquals(CONFIRMING, verdict(age = 0))
    }

    @Test
    fun pastTheGraceTimeTheTailnetMustShowItWorks() {
        assertEquals(CONFIRMING, verdict(onlinePeers = 1, age = old))
        assertEquals(CONFIRMING, verdict(relay = true, age = old))
        assertEquals(OFFLINE, verdict(age = old))
        assertEquals(OFFLINE, verdict(age = NETCHECK_CONFIRM_GRACE_MS))
        assertEquals(CONFIRMING, verdict(age = NETCHECK_CONFIRM_GRACE_MS - 1))
    }

    @Test
    fun anUnknownOrImpossibleAgeGivesNoGrace() {
        assertEquals(OFFLINE, verdict(age = null))
        // A start time in the future (a clock change) is not "just started".
        assertEquals(OFFLINE, verdict(age = -1_000))
        assertEquals(CONFIRMING, verdict(onlinePeers = 2, age = null))
    }
}
