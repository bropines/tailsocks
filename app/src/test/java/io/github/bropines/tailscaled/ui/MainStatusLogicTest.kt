package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main screen watchdog's decisions (ui/MainStatusLogic.kt): the status card's state machine,
 * which slower reads a tick makes, the account picture taken off the status, what the managed
 * devices are handed and the avatar's decode size.
 */
class MainStatusLogicTest {

    // ─── nextMainState ───────────────────────────────────────────────────────────────────────

    private fun tick(
        previous: String = "ACTIVE",
        alive: Boolean = true,
        backend: String = "Running",
        seconds: Int = 0,
        wasLoggedIn: Boolean = false,
        hasLoginUrl: Boolean = false,
    ) = nextMainState(previous, alive, backend, seconds, wasLoggedIn, hasLoginUrl)

    @Test
    fun `no daemon is STOPPED and resets the counter`() {
        assertEquals(MainTick("STOPPED", 0), tick(alive = false, seconds = 8))
        assertEquals(MainTick("STOPPED", 0), tick(previous = "LOGGED_OUT", alive = false, backend = "NeedsLogin", seconds = 20))
    }

    @Test
    fun `a running or starting backend is ACTIVE and resets the counter`() {
        assertEquals(MainTick("ACTIVE", 0), tick(previous = "CONNECTION_ISSUE", backend = "Running", seconds = 12))
        assertEquals(MainTick("ACTIVE", 0), tick(previous = "STOPPED", backend = "Starting", seconds = 4))
        // A failed read of the backend is not "logged out" either.
        assertEquals(MainTick("ACTIVE", 0), tick(backend = "Error"))
    }

    @Test
    fun `NeedsLogin for less than ten seconds keeps what the card showed`() {
        assertEquals(MainTick("ACTIVE", 2), tick(previous = "ACTIVE", backend = "NeedsLogin", seconds = 0))
        assertEquals(MainTick("LOGGED_OUT", 6), tick(previous = "LOGGED_OUT", backend = "NoState", seconds = 4))
        // 8 seconds in: still the grace period, whatever the flags say.
        assertEquals(MainTick("ACTIVE", 8), tick(previous = "ACTIVE", backend = "NoState", seconds = 6, wasLoggedIn = true))
    }

    @Test
    fun `a daemon between states turns a stopped card ACTIVE during the grace period`() {
        assertEquals(MainTick("ACTIVE", 2), tick(previous = "STOPPED", backend = "NoState", seconds = 0))
    }

    @Test
    fun `after ten seconds a node that was logged in and is offered no link is a CONNECTION_ISSUE`() {
        assertEquals(MainTick("CONNECTION_ISSUE", 10), tick(backend = "NoState", seconds = 8, wasLoggedIn = true, hasLoginUrl = false))
        assertEquals(MainTick("CONNECTION_ISSUE", 12), tick(backend = "NeedsLogin", seconds = 10, wasLoggedIn = true))
    }

    @Test
    fun `after ten seconds anything else is LOGGED_OUT`() {
        // Never logged in.
        assertEquals(MainTick("LOGGED_OUT", 10), tick(backend = "NeedsLogin", seconds = 8, wasLoggedIn = false))
        // Logged in before, but the daemon is offering a login link: it wants a login.
        assertEquals(MainTick("LOGGED_OUT", 10), tick(backend = "NeedsLogin", seconds = 8, wasLoggedIn = true, hasLoginUrl = true))
    }

    @Test
    fun `the counter adds one tick of seconds`() {
        assertEquals((MAIN_TICK_MS / 1000).toInt(), tick(backend = "NeedsLogin", seconds = 0).loggedOutSeconds)
    }

    // ─── mainReadsDue ────────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing is read while the backend is not running`() {
        assertEquals(MainReads(status = false, avatar = false), mainReadsDue(1_000_000, running = false, lastStatusAtMs = 0, lastAvatarAtMs = 0))
    }

    @Test
    fun `the first tick and the first after a return read the status and the picture at once`() {
        assertEquals(MainReads(status = true, avatar = true), mainReadsDue(1_000_000, running = true, lastStatusAtMs = 0, lastAvatarAtMs = 0))
        // Back in front (last status reset to 0), picture checked 5 s ago: only the status.
        assertEquals(MainReads(status = true, avatar = false), mainReadsDue(1_000_000, true, 0, 995_000))
    }

    @Test
    fun `the status every ten seconds, the picture from it every thirty`() {
        val t0 = 1_000_000L
        assertFalse(mainReadsDue(t0 + 9_000, true, t0, t0).status)
        assertFalse(mainReadsDue(t0 + 10_000, true, t0, t0).status)
        assertEquals(MainReads(status = true, avatar = false), mainReadsDue(t0 + 10_001, true, t0, t0))
        assertEquals(MainReads(status = true, avatar = true), mainReadsDue(t0 + 30_001, true, t0 + 20_000, t0))
    }

    @Test
    fun `the picture is never checked without a status read to take it from`() {
        // Thirty seconds since the picture, but the status was read two seconds ago.
        assertEquals(MainReads(status = false, avatar = false), mainReadsDue(1_040_000, true, 1_038_000, 1_000_000))
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
