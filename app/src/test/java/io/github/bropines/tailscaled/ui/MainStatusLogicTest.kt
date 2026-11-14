package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.DaemonEventKind
import io.github.bropines.tailscaled.models.HealthWarning
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main screen refresh loop's decisions (ui/MainStatusLogic.kt): the status card's state
 * machine, which events read what, when the loop wakes without one, the account picture taken
 * off the status, what the managed devices are handed and the avatar's decode size.
 */
class MainStatusLogicTest {

    // ─── nextMainState ───────────────────────────────────────────────────────────────────────

    private val t0 = 1_000_000L

    private fun tick(
        previous: String = "ACTIVE",
        alive: Boolean = true,
        backend: String = "Running",
        since: Long = 0,
        now: Long = t0,
        wasLoggedIn: Boolean = false,
        hasLoginUrl: Boolean = false,
    ) = nextMainState(previous, alive, backend, since, now, wasLoggedIn, hasLoginUrl)

    @Test
    fun `no daemon is STOPPED and resets the clock`() {
        assertEquals(MainTick("STOPPED", 0), tick(alive = false, since = t0 - 8_000))
        assertEquals(MainTick("STOPPED", 0), tick(previous = "LOGGED_OUT", alive = false, backend = "NeedsLogin", since = t0 - 20_000))
    }

    @Test
    fun `a running or starting backend is ACTIVE and resets the clock`() {
        assertEquals(MainTick("ACTIVE", 0), tick(previous = "CONNECTION_ISSUE", backend = "Running", since = t0 - 12_000))
        assertEquals(MainTick("ACTIVE", 0), tick(previous = "STOPPED", backend = "Starting", since = t0 - 4_000))
        // A failed read of the backend is not "logged out" either.
        assertEquals(MainTick("ACTIVE", 0), tick(backend = "Error"))
    }

    @Test
    fun `the first read that sees NeedsLogin starts the clock`() {
        assertEquals(MainTick("ACTIVE", t0), tick(previous = "ACTIVE", backend = "NeedsLogin", since = 0))
    }

    @Test
    fun `NeedsLogin for less than ten seconds keeps what the card showed`() {
        assertEquals(MainTick("LOGGED_OUT", t0 - 6_000), tick(previous = "LOGGED_OUT", backend = "NoState", since = t0 - 6_000))
        // 9.9 seconds in: still the grace period, whatever the flags say.
        assertEquals(MainTick("ACTIVE", t0 - 9_999), tick(previous = "ACTIVE", backend = "NoState", since = t0 - 9_999, wasLoggedIn = true))
    }

    @Test
    fun `a daemon between states turns a stopped card ACTIVE during the grace period`() {
        assertEquals(MainTick("ACTIVE", t0), tick(previous = "STOPPED", backend = "NoState", since = 0))
    }

    @Test
    fun `after ten seconds a node that was logged in and is offered no link is a CONNECTION_ISSUE`() {
        assertEquals(MainTick("CONNECTION_ISSUE", t0 - 10_000), tick(backend = "NoState", since = t0 - 10_000, wasLoggedIn = true, hasLoginUrl = false))
        assertEquals(MainTick("CONNECTION_ISSUE", t0 - 60_000), tick(backend = "NeedsLogin", since = t0 - 60_000, wasLoggedIn = true))
    }

    @Test
    fun `after ten seconds anything else is LOGGED_OUT`() {
        // Never logged in.
        assertEquals(MainTick("LOGGED_OUT", t0 - 10_000), tick(backend = "NeedsLogin", since = t0 - 10_000, wasLoggedIn = false))
        // Logged in before, but the daemon is offering a login link: it wants a login.
        assertEquals(MainTick("LOGGED_OUT", t0 - 10_000), tick(backend = "NeedsLogin", since = t0 - 10_000, wasLoggedIn = true, hasLoginUrl = true))
    }

    @Test
    fun `the grace period is the clock's, however few reads came in it`() {
        // Events, not ticks: one read when NeedsLogin arrives, the next when the wake timer
        // (mainWakeDelayMs) fires at the end of the grace period.
        val first = tick(backend = "NeedsLogin", now = t0, wasLoggedIn = true)
        assertEquals("ACTIVE", first.state)
        val wake = mainWakeDelayMs(t0, lastReadAtMs = t0, processingSinceMs = 0, loggedOutSinceMs = first.loggedOutSinceMs, warningSettlesAtMs = 0)
        assertEquals(MAIN_LOGGED_OUT_AFTER_MS, wake)
        val second = tick(previous = first.state, backend = "NeedsLogin", since = first.loggedOutSinceMs, now = t0 + wake, wasLoggedIn = true)
        assertEquals("CONNECTION_ISSUE", second.state)
        // Decided: no more early wake for it.
        assertEquals(MAIN_SAFETY_MS, mainWakeDelayMs(t0 + wake, t0 + wake, 0, second.loggedOutSinceMs, 0))
    }

    // ─── which events read what ──────────────────────────────────────────────────────────────

    @Test
    fun `state health and prefs read the cheap state, netmap and prefs the status`() {
        assertTrue(mainWantsStateRead(setOf(DaemonEventKind.STATE)))
        assertTrue(mainWantsStateRead(setOf(DaemonEventKind.HEALTH)))
        assertTrue(mainWantsStateRead(setOf(DaemonEventKind.PREFS)))
        assertFalse(mainWantsStateRead(setOf(DaemonEventKind.NETMAP)))
        assertTrue(mainWantsStatusRead(setOf(DaemonEventKind.NETMAP)))
        assertTrue(mainWantsStatusRead(setOf(DaemonEventKind.PREFS)))
        assertFalse(mainWantsStatusRead(setOf(DaemonEventKind.STATE, DaemonEventKind.HEALTH)))
        assertFalse(mainWantsStateRead(emptySet()))
        assertFalse(mainWantsStatusRead(emptySet()))
    }

    // ─── avatarDue ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the picture on the first status read, then at most every thirty seconds`() {
        assertTrue(avatarDue(t0, lastAvatarAtMs = 0))
        assertFalse(avatarDue(t0 + 29_999, lastAvatarAtMs = t0))
        assertTrue(avatarDue(t0 + 30_000, lastAvatarAtMs = t0))
    }

    // ─── mainWakeDelayMs: the safety net and the timers ──────────────────────────────────────

    @Test
    fun `idle, the loop sleeps the safety interval from its last read`() {
        assertEquals(MAIN_SAFETY_MS, mainWakeDelayMs(t0, t0, 0, 0, 0))
        // Woken by an event that read nothing (a status read ending): the rest of the interval.
        assertEquals(MAIN_SAFETY_MS - 12_000, mainWakeDelayMs(t0 + 12_000, t0, 0, 0, 0))
        // Overdue: at once.
        assertEquals(0L, mainWakeDelayMs(t0 + 45_000, t0, 0, 0, 0))
    }

    @Test
    fun `the start watchdog looks every tick, and only for a bounded time`() {
        assertEquals(MAIN_TICK_MS, mainWakeDelayMs(t0, t0, processingSinceMs = t0, loggedOutSinceMs = 0, warningSettlesAtMs = 0))
        assertEquals(MAIN_TICK_MS, mainWakeDelayMs(t0 + 60_000, t0 + 60_000, t0, 0, 0))
        // A transition that never reported back falls to the safety net.
        val late = t0 + MAIN_START_WATCH_MS
        assertEquals(MAIN_SAFETY_MS, mainWakeDelayMs(late, late, t0, 0, 0))
    }

    @Test
    fun `a young warning wakes the loop when it settles`() {
        assertEquals(3_000L, mainWakeDelayMs(t0, t0, 0, 0, warningSettlesAtMs = t0 + 3_000))
        // Already past (read at or after it): no early wake.
        assertEquals(MAIN_SAFETY_MS, mainWakeDelayMs(t0, t0, 0, 0, warningSettlesAtMs = t0 - 1))
    }

    @Test
    fun `the earliest pending condition wins`() {
        assertEquals(MAIN_TICK_MS, mainWakeDelayMs(t0, t0, t0, t0, t0 + 3_000))
        assertEquals(3_000L, mainWakeDelayMs(t0, t0, 0, t0 - 7_000, t0 + 4_000))
    }

    /**
     * The measurement behind the change, on the loop's own timing function: a connected screen,
     * idle for a minute, no event at all. Before: a 2-second tick (30 cheap reads) and the full
     * status every 10 s (6 status reads). Now: one cheap read and one status read when the screen
     * starts, then only the safety net — 2 cheap reads in the minute, 0 status reads.
     */
    @Test
    fun `connected and idle for a minute`() {
        val minute = 60_000L
        val before = Pair((minute / 2_000L).toInt(), (minute / 10_000L).toInt())
        assertEquals(Pair(30, 6), before)

        var now = t0
        var lastRead = t0 // the start's read
        var cheapReads = 0
        while (true) {
            now += mainWakeDelayMs(now, lastRead, 0, 0, 0)
            if (now > t0 + minute) break
            lastRead = now
            cheapReads++
        }
        assertEquals(2, cheapReads)
    }

    @Test
    fun `warningSettlesAtMs names the first warning still too young to show`() {
        val now = 1_700_000_000_000L
        fun w(code: String, since: Long, high: Boolean = true) =
            HealthWarning(code = code, severity = if (high) "high" else "low", brokenSinceMs = since)
        assertEquals(0L, warningSettlesAtMs(emptyList(), now))
        // Settled already, or no time given: nothing to wait for.
        assertEquals(0L, warningSettlesAtMs(listOf(w("a", now - 60_000), w("b", 0)), now))
        // Two young ones: the earlier.
        assertEquals(now - 2_000 + HEALTH_SETTLE_MS, warningSettlesAtMs(listOf(w("a", now - 1_000), w("b", now - 2_000)), now))
        // One that would stay hidden anyway (low, not about the connection): no wake for it.
        assertEquals(0L, warningSettlesAtMs(listOf(w("some-other", now - 1_000, high = false)), now))
    }

    // ─── avatarToSync ────────────────────────────────────────────────────────────────────────

    private fun status(json: String) = AppJson.decodeFromString(StatusResponse.serializer(), json)

    private val withUsers = status(
        """{"Self":{"ID":"self","UserID":2},
            "User":{"1":{"ID":1,"ProfilePicURL":"https://pic/other"},"2":{"ID":2,"ProfilePicURL":"https://pic/me"}}}"""
    )

    @Test
    fun `the picture is Self's user's`() {
        assertEquals("https://pic/me", avatarToSync(withUsers, currentUrl = null))
        assertEquals("https://pic/me", avatarToSync(withUsers, currentUrl = "https://pic/old"))
    }

    @Test
    fun `an unchanged picture is not fetched again`() {
        assertNull(avatarToSync(withUsers, currentUrl = "https://pic/me"))
    }

    @Test
    fun `without Self's user the first user stands in, and no picture is nothing to do`() {
        val noSelf = status("""{"User":{"7":{"ID":7,"ProfilePicURL":"https://pic/first"}}}""")
        assertEquals("https://pic/first", avatarToSync(noSelf, null))
        assertNull(avatarToSync(status("""{"Self":{"UserID":3},"User":{"3":{"ID":3,"ProfilePicURL":""}}}"""), null))
        assertNull(avatarToSync(status("""{"Self":{"UserID":3}}"""), null))
    }

    // ─── managedPresence ─────────────────────────────────────────────────────────────────────

    private fun peer(id: String, host: String, online: Boolean, rx: Long = 0, lastWrite: String? = null) =
        PeerData(id = id, hostName = host, online = online, rxBytes = rx, lastWrite = lastWrite)

    @Test
    fun `traffic and handshakes do not change what the managed devices are handed`() {
        val before = listOf(peer("n1", "tv", true, rx = 10, lastWrite = "2026-11-14T10:00:00Z"), peer("n2", "pad", false))
        val after = listOf(peer("n1", "tv", true, rx = 99_999, lastWrite = "2026-11-14T10:00:10Z"), peer("n2", "pad", false))
        assertNotEquals(before, after)
        assertEquals(managedPresence(before), managedPresence(after))
    }

    @Test
    fun `a device coming online, renamed, joining or leaving does`() {
        val base = listOf(peer("n1", "tv", true), peer("n2", "pad", false))
        assertNotEquals(managedPresence(base), managedPresence(listOf(peer("n1", "tv", true), peer("n2", "pad", true))))
        assertNotEquals(managedPresence(base), managedPresence(listOf(peer("n1", "living-tv", true), peer("n2", "pad", false))))
        assertNotEquals(managedPresence(base), managedPresence(base + peer("n3", "phone", true)))
        assertNotEquals(managedPresence(base), managedPresence(base.take(1)))
    }

    @Test
    fun `no list and an empty list are told apart`() {
        assertNull(managedPresence(null))
        assertTrue(managedPresence(emptyList())!!.isEmpty())
        assertNotEquals(managedPresence(null), managedPresence(emptyList()))
    }

    // ─── avatarSampleSize ────────────────────────────────────────────────────────────────────

    @Test
    fun `the sample size keeps the shorter side at or above the target`() {
        assertEquals(1, avatarSampleSize(100, 100, 100))
        assertEquals(1, avatarSampleSize(199, 199, 100))
        assertEquals(2, avatarSampleSize(200, 200, 100))
        assertEquals(4, avatarSampleSize(512, 512, 114))
        // The shorter side decides: a wide picture is cropped to a circle, not shrunk past it.
        assertEquals(2, avatarSampleSize(2000, 300, 114))
    }

    @Test
    fun `nonsense sizes decode as they are`() {
        assertEquals(1, avatarSampleSize(0, 0, 100))
        assertEquals(1, avatarSampleSize(-1, 500, 100))
        assertEquals(1, avatarSampleSize(500, 500, 0))
    }
}
