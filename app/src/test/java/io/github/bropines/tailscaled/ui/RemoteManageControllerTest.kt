package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import io.github.bropines.tailscaled.core.FakeManagedDevice
import io.github.bropines.tailscaled.core.Listing
import io.github.bropines.tailscaled.core.ManagedDenial
import io.github.bropines.tailscaled.core.ManagedGate
import io.github.bropines.tailscaled.core.ManagedKeys
import io.github.bropines.tailscaled.core.ManagedOp
import io.github.bropines.tailscaled.core.ManagedRefusal
import io.github.bropines.tailscaled.core.ManagedResult
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.RemoteAnswer
import io.github.bropines.tailscaled.core.RemoteClient
import io.github.bropines.tailscaled.core.RemoteContract
import io.github.bropines.tailscaled.core.RemotePairAnswer
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemotePairingRecord
import io.github.bropines.tailscaled.core.RemoteTokenStore
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The managing page's logic (ui/RemoteManageController.kt) against a device that keeps the
 * contract (core/RemoteClientFake.kt): what is offered to whom, what each refusal becomes,
 * and a guarded change from the press to "applied" — or to the device putting it back.
 *
 * Nothing waits here. The page's waiting is [pause], which moves [clock] instead of sleeping,
 * notes what the page showed at that moment and lets a test change the world in between.
 */
class RemoteManageControllerTest {

    private companion object {
        const val PEER = "nKq7Xw2CNTRL"
        const val NAME = "living-room-tv"
        const val AMS = "nAMSexitCNTRL"
        const val FRA = "nFRAexitCNTRL"
        const val CONFIRM = "POST /v1/confirm"
        const val ADDRESS = "100.64.0.7"
        const val HELLO = "GET /v1/hello"
        const val STATUS = "GET /v1/status"
    }

    @Volatile private var clock = 1_791_813_780_000L
    private val tokens = RemoteTokenStore(MemoryKeyValueStore(), SoftwareSecretBox())
    private val device = FakeManagedDevice(clock = { clock })
    private val client = RemoteClient(device, tokens, "default", PEER, now = { clock })
    private var closed = false

    /** What this device's own network map says of the peer: there, at its address, unless a test says otherwise. */
    @Volatile private var peer = ManagePeer(RemotePresence.ONLINE, address = ADDRESS)
    private var online: Boolean
        get() = peer.presence == RemotePresence.ONLINE
        set(value) {
            peer = peer.copy(presence = if (value) RemotePresence.ONLINE else RemotePresence.OFFLINE)
        }

    /** How many times the map was read: a page that asks nobody still reads it. */
    private var looks = 0

    /** What the pages of this test remember of the device between them, as the app does while it lives. */
    private val memory = ManageMemory()

    /** This device's own TailSocks was asked to start; [startable] is whether it could be. */
    private var started = 0
    private var startable = true

    /** What the guard's banner showed at each wait, and what a test does to the world during one. */
    private val shown = ArrayList<GuardPhase?>()
    private var during: (waited: Long) -> Unit = {}
    private val pause: suspend (Long) -> Unit = { ms ->
        shown += managing?.guard?.phase
        clock += ms
        during(ms)
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val keeper = GuardKeeper(scope, Dispatchers.Unconfined, pause) { clock }
    private val controller = controller()

    private fun controller() = RemoteManageController(
        client = client, scope = scope, guards = keeper, local = { looks++; peer }, onClose = { closed = true },
        name = NAME, tv = true, appVersion = "4.11.0", memory = memory, startOwn = { started++; startable },
        io = Dispatchers.Unconfined, pause = pause, now = { clock },
    )

    private val managing: RemoteManageUi.Managing? get() = controller.ui as? RemoteManageUi.Managing
    private val ui: RemoteManageUi.Managing get() = managing ?: throw AssertionError("not managing: ${controller.ui}")
    private val pairing: RemoteManageUi.Pairing get() = controller.ui as? RemoteManageUi.Pairing ?: throw AssertionError("not pairing: ${controller.ui}")

    private fun paired(owner: Boolean = true, expiresAt: Long = 0) {
        tokens.put("default", PEER, RemotePairingRecord(token = device.token, name = NAME, owner = owner, expiresAt = expiresAt, tv = true))
    }

    /** A paired page that has read its device. */
    private fun open(owner: Boolean = true, full: Boolean = false, caps: List<String>? = RemoteContract.ALL_CAPS) {
        device.become(owner = owner, full = full, caps = caps)
        paired(owner)
        runBlocking { controller.start() }
        device.sent.clear()
    }

    private fun tick() = runBlocking { controller.tick() }
    private fun sent(): List<String> = device.sent.map { it.toString() }
    private fun posts(): List<String> = sent().filter { it.startsWith("POST") }

    /**
     * The page as the app runs it: its state on one thread, each request on a thread of its
     * own. For what the rest of this file cannot show, where every request is over before the
     * next line runs: what the page looks like while a device is still being asked.
     */
    private class Threads : AutoCloseable {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(main)

        override fun close() {
            scope.cancel()
            main.close()
        }
    }

    private fun threaded(present: Boolean = false, test: (page: RemoteManageController, at: Threads) -> Unit) = Threads().use { at ->
        val page = RemoteManageController(
            client = client, scope = at.scope, guards = GuardKeeper(at.scope, Dispatchers.IO, now = { clock }), local = { peer },
            onClose = { closed = true }, name = NAME, tv = true, appVersion = "4.11.0", present = present, memory = memory,
            io = Dispatchers.IO, now = { clock },
        )
        try {
            test(page, at)
        } finally {
            device.releaseAll()
        }
    }

    /** Waits for the page to get there; a page that never does fails by the name of what was awaited. */
    private fun await(what: String, reached: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!reached()) {
            if (System.nanoTime() > deadline) throw AssertionError("never happened: $what")
            Thread.sleep(5)
        }
    }

    /** Lets whatever is still on its way arrive: for saying that nothing more happened. */
    private fun settle(at: Threads) = repeat(4) {
        Thread.sleep(40)
        runBlocking(at.main) { }
    }

    // ─── Opening: a device this one is not paired with ───────────────────────────────────────

    @Test
    fun withoutAPairingTheDeviceIsAskedFirstAndThenTheDigits() {
        device.codeOnShow = false
        runBlocking { controller.start() }
        // Online and taking management: only now is there a form, and the device said no code is on show yet.
        assertEquals(listOf(HELLO), sent())
        assertTrue(pairing.form)
        assertNull(pairing.problem)
        assertFalse(pairing.codeOnShow)
        assertEquals(ManagePeer(RemotePresence.ONLINE, address = ADDRESS), pairing.peer)
        controller.setCode("48 39-02x")
        assertEquals("483902", pairing.code)
        controller.pair()
        // Paired, and read at once.
        assertEquals(NAME, ui.status?.name)
        assertTrue(ui.owner)
        assertEquals(clock, ui.pairedAt)
    }

    @Test
    fun noCodeIsAskedForWhileThisDevicesOwnTailSocksIsDown() {
        peer = ManagePeer(stopped = true)
        runBlocking { controller.start() }
        assertEquals(ManageProblem.NOT_CONNECTED, pairing.problem)
        assertFalse(pairing.form)
        assertFalse(pairing.checking)
        // Known without asking anybody, and it can be started from here.
        assertTrue(sent().isEmpty())
        assertEquals(ManageProblemAction.START, pairing.problemAction)
        controller.setCode("483902")
        controller.pair()
        assertTrue(sent().isEmpty())

        // Started: up after the second look, and then the device is asked like a page that has just opened.
        var waits = 0
        during = { if (++waits == 2) peer = ManagePeer(RemotePresence.ONLINE, address = ADDRESS) }
        controller.resolve(ManageProblemAction.START)
        assertEquals(1, started)
        assertEquals(2, waits)
        assertTrue(pairing.form)
        assertFalse(pairing.starting)
        assertEquals("483902", pairing.code)
        assertEquals(listOf(HELLO), sent())
    }

    @Test
    fun aTailSocksThatIsUpButNotConnectedIsNotStartedAgain() {
        // Signing in, or still starting: there is nothing to start, only to wait for.
        peer = ManagePeer()
        runBlocking { controller.start() }
        assertEquals(ManageProblem.NOT_CONNECTED, pairing.problem)
        assertEquals(ManageProblemAction.RETRY, pairing.problemAction)
        peer = ManagePeer(RemotePresence.ONLINE, address = ADDRESS)
        controller.resolve(ManageProblemAction.RETRY)
        assertTrue(pairing.form)
        assertEquals(0, started)
    }

    @Test
    fun aStartThatCannotBeAskedForChangesNothing() {
        peer = ManagePeer(stopped = true)
        startable = false
        runBlocking { controller.start() }
        controller.resolve(ManageProblemAction.START)
        assertEquals(1, started)
        assertFalse(pairing.starting)
        assertEquals(ManageProblem.NOT_CONNECTED, pairing.problem)
    }

    @Test
    fun aDeviceThatIsNotInThisTailnetIsNeverAskedForACode() {
        peer = ManagePeer(RemotePresence.GONE)
        device.inTailnet = false
        runBlocking { controller.start() }
        // The author's screenshot: the form was offered, and "not in this tailnet" came only after Pair.
        assertEquals(ManageProblem.NO_PEER, pairing.problem)
        assertFalse(pairing.form)
        assertTrue(sent().isEmpty())
        // No pairing is kept here, so there is nothing to forget: the way out is all there is.
        assertEquals(ManageProblemAction.CLOSE, pairing.problemAction)
        controller.setCode("483902")
        controller.pair()
        assertTrue(sent().isEmpty())
        assertNull(pairing.error)
        controller.resolve(ManageProblemAction.CLOSE)
        assertTrue(closed)
    }

    @Test
    fun aDeviceTheTailnetHoldsAsOfflineIsSaidOfflineAndGetsNoForm() {
        val lastSeen = clock - 2 * 3_600_000
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = lastSeen, address = ADDRESS)
        device.reachable = false
        runBlocking { controller.start() }
        assertEquals(ManageProblem.OFFLINE, pairing.problem)
        assertFalse(pairing.form)
        assertEquals(lastSeen, pairing.peer?.lastSeen)
        assertEquals(clock, pairing.now)
        assertEquals(ManageProblemAction.RETRY, pairing.problemAction)
        // Asked once all the same — the map can be a moment behind — and never for a pairing.
        assertEquals(listOf(HELLO), sent())

        controller.resolve(ManageProblemAction.RETRY)
        assertEquals(listOf(HELLO, HELLO), sent())
        assertEquals(ManageProblem.OFFLINE, pairing.problem)

        // Back: the page's own look finds it, and the form comes by itself.
        device.reachable = true
        online = true
        tick()
        assertNull(pairing.problem)
        assertTrue(pairing.form)
    }

    @Test
    fun aDeviceTheMapHoldsAsOfflineAndThatAnswersGetsItsForm() {
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = clock - 60_000, address = ADDRESS)
        runBlocking { controller.start() }
        assertNull(pairing.problem)
        assertTrue(pairing.form)
    }

    @Test
    fun aDeviceThatIsOnlineAndDoesNotTakeManagementGetsNoForm() {
        device.reachable = false
        runBlocking { controller.start() }
        assertEquals(ManageProblem.NOT_ACCEPTING, pairing.problem)
        assertFalse(pairing.form)
        assertEquals(ManageProblemAction.RETRY, pairing.problemAction)
        assertEquals(listOf(HELLO), sent())

        // Something else answers on that port: no TailSocks to pair with either.
        device.reachable = true
        device.script(HELLO, FakeManagedDevice.refusal(404, "not_found"))
        controller.resolve(ManageProblemAction.RETRY)
        assertEquals(ManageProblem.NOT_ACCEPTING, pairing.problem)

        // Management was switched on there meanwhile: the next look brings the form.
        tick()
        assertTrue(pairing.form)
    }

    @Test
    fun anErrorUnderTheFieldIsOnlyEverAboutTheCode() {
        runBlocking { controller.start() }
        controller.setCode("111111")
        device.scriptPair(RemotePairAnswer(error = "wrong", attemptsLeft = 2, status = 403))
        controller.pair()
        assertEquals(RemotePairError(RemotePairFailure.WRONG, attemptsLeft = 2), pairing.error)
        assertTrue(pairing.form)
        assertNull(pairing.problem)

        device.scriptPair(RemotePairAnswer(error = "closed", status = 403))
        controller.pair()
        assertEquals(RemotePairFailure.CLOSED, pairing.error?.failure)
        assertTrue(pairing.form)

        // The device stops answering between the form and the press: said in the banner, and the form goes.
        device.reachable = false
        controller.pair()
        assertNull(pairing.error)
        assertEquals(ManageProblem.NOT_ACCEPTING, pairing.problem)
        assertFalse(pairing.form)
        assertFalse(pairing.busy)
        // What was typed is still there when the form comes back.
        device.reachable = true
        controller.resolve(ManageProblemAction.RETRY)
        assertTrue(pairing.form)
        assertEquals("111111", pairing.code)
    }

    @Test
    fun aDeviceThatLeavesTheTailnetUnderTheFormTakesTheFormAway() {
        runBlocking { controller.start() }
        controller.setCode("483902")
        // It signed in to another account while the form was open; this device's map has caught up.
        device.inTailnet = false
        peer = ManagePeer(RemotePresence.GONE)
        controller.pair()
        assertNull(pairing.error)
        assertEquals(ManageProblem.NO_PEER, pairing.problem)
        assertEquals(ManageProblemAction.CLOSE, pairing.problemAction)
        assertFalse(pairing.form)
    }

    @Test
    fun thisDevicesTailSocksGoingDownUnderTheFormTakesTheFormAway() {
        runBlocking { controller.start() }
        controller.setCode("483902")
        device.connected = false
        peer = ManagePeer(stopped = true)
        controller.pair()
        assertEquals(ManageProblem.NOT_CONNECTED, pairing.problem)
        assertEquals(ManageProblemAction.START, pairing.problemAction)
        assertNull(pairing.error)
    }

    // ─── Opening: a device this one manages ──────────────────────────────────────────────────

    @Test
    fun thePageReadsTheStatusTheSettingsAndTheFiles() {
        open()
        runBlocking { controller.start() }
        // The small question and the status beside it; then, once the status is in, the settings and the files.
        assertEquals(listOf(HELLO, STATUS, "GET /v1/settings", "GET /v1/files"), sent())
        assertFalse(ui.loading)
        assertNull(ui.problem)
        assertEquals(14, ui.settings.size)
        assertEquals(2, ui.files?.size)
        assertEquals("Xiaomi MiTV-MOOQ1", ui.status?.device?.model)
        assertNotNull(ui.app)
        assertTrue(ui.usable)
        assertEquals("4.11.0", ui.appVersion)
        assertEquals(ManagePeer(RemotePresence.ONLINE, address = ADDRESS), ui.peer)
        assertFalse(ui.remembered)
        assertEquals(clock, ui.heardAt)
    }

    @Test
    fun withThisDevicesTailSocksDownAManagedDeviceIsNotAskedAndItCanBeStarted() {
        open()
        clock += 60_000
        peer = ManagePeer(stopped = true)
        device.connected = false
        val page = controller()
        runBlocking { page.start() }
        val m = page.ui as RemoteManageUi.Managing
        // At once, and with nothing sent: what the device said last stays, and cannot be used.
        assertEquals(ManageProblem.NOT_CONNECTED, m.problem)
        assertEquals(ManageProblemAction.START, m.problemAction)
        assertTrue(sent().isEmpty())
        assertFalse(m.loading)
        assertTrue(m.remembered)
        assertFalse(m.usable)

        var waits = 0
        during = {
            if (++waits == 3) {
                peer = ManagePeer(RemotePresence.ONLINE, address = ADDRESS)
                device.connected = true
            }
        }
        page.resolve(ManageProblemAction.START)
        assertEquals(1, started)
        val after = page.ui as RemoteManageUi.Managing
        assertNull(after.problem)
        assertFalse(after.starting)
        assertFalse(after.remembered)
        assertTrue(after.usable)
    }

    @Test
    fun aManagedDeviceThatLeftTheTailnetCanOnlyBeForgotten() {
        paired()
        peer = ManagePeer(RemotePresence.GONE)
        device.inTailnet = false
        runBlocking { controller.start() }
        assertEquals(ManageProblem.NO_PEER, ui.problem)
        assertTrue(ui.problem!!.final)
        assertFalse(ui.loading)
        // Known from this device's own network map: nothing was sent, and no code is asked for.
        assertTrue(sent().isEmpty())
        assertTrue(controller.ui is RemoteManageUi.Managing)
        assertEquals(ManageProblemAction.FORGET, ui.problemAction)
        // The pairing is still here, for the person to forget.
        assertNotNull(client.pairing())
        assertTrue(ui.owner)

        controller.resolve(ManageProblemAction.FORGET)
        assertTrue(controller.ui is RemoteManageUi.Forgotten)
        assertNull(client.pairing())
        assertNull(memory[client.key])
    }

    @Test
    fun aManagedDeviceTheTailnetHoldsAsOfflineIsSaidOfflineAtOnce() {
        paired()
        val lastSeen = clock - 20 * 60_000
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = lastSeen, address = ADDRESS)
        device.reachable = false
        runBlocking { controller.start() }
        assertEquals(ManageProblem.OFFLINE, ui.problem)
        assertEquals(lastSeen, ui.peer?.lastSeen)
        assertEquals(ManageProblemAction.RETRY, ui.problemAction)
        assertFalse(ui.loading)
        assertNull(ui.status)
        // The small question only — it gives up in four seconds — and never the status, which waits twelve.
        assertEquals(listOf(HELLO), sent())

        // A person asks: now the status is asked for too, beside the small question that still decides first.
        controller.resolve(ManageProblemAction.RETRY)
        assertEquals(listOf(HELLO, HELLO, STATUS), sent())
        assertEquals(ManageProblem.OFFLINE, ui.problem)
    }

    @Test
    fun aManagedDeviceTheMapHoldsAsOfflineAndThatAnswersIsRead() {
        paired()
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = clock - 60_000, address = ADDRESS)
        runBlocking { controller.start() }
        assertNull(ui.problem)
        assertEquals(NAME, ui.status?.name)
        assertEquals(listOf(HELLO, STATUS, "GET /v1/settings", "GET /v1/files"), sent())
        assertTrue(ui.usable)
    }

    @Test
    fun aManagedDeviceThatIsOnlineAndDoesNotAnswerIsAClosedPortFromTheStart() {
        paired()
        device.reachable = false
        runBlocking { controller.start() }
        assertEquals(ManageProblem.NOT_ACCEPTING, ui.problem)
        assertEquals(ManageProblemAction.RETRY, ui.problemAction)
        // Nothing follows a device that does not answer: no settings, no files, each with a wait of its own.
        assertEquals(listOf(HELLO, STATUS), sent())
    }

    // ─── What the device said last ───────────────────────────────────────────────────────────

    @Test
    fun whatTheDeviceSaidLastIsShownByThePageThatOpensNext() {
        open()
        val heard = clock
        clock += 5 * 60_000
        device.reachable = false
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = clock - 60_000, address = ADDRESS)

        val page = controller()
        runBlocking { page.start() }
        val m = page.ui as RemoteManageUi.Managing
        // Offline — and the page still has the device: its status, its switches, its files, as of five minutes ago.
        assertEquals(ManageProblem.OFFLINE, m.problem)
        assertEquals(NAME, m.status?.name)
        assertEquals(14, m.settings.size)
        assertEquals(2, m.files?.size)
        assertNotNull(m.app)
        assertTrue(m.remembered)
        assertTrue(m.old)
        assertEquals(heard, m.heardAt)
        assertEquals(clock, m.now)
        // It is shown, not offered: nothing of it answers.
        assertFalse(m.usable)
        device.sent.clear()
        page.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        page.identify()
        page.openExitNodes()
        assertTrue(sent().isEmpty())
        assertEquals(ManageProblemAction.RETRY, m.problemAction)

        // Back: its own word replaces what was remembered.
        device.reachable = true
        online = true
        page.resolve(ManageProblemAction.RETRY)
        val fresh = page.ui as RemoteManageUi.Managing
        assertNull(fresh.problem)
        assertFalse(fresh.remembered)
        assertFalse(fresh.old)
        assertEquals(clock, fresh.heardAt)
        assertTrue(fresh.usable)
    }

    @Test
    fun whatIsRememberedFollowsTheDeviceWhileAPageIsOpen() {
        open()
        assertEquals(FRA, memory[client.key]?.status?.exitNode?.id)
        controller.pickExitNode(AMS)
        controller.deleteFile("notes.txt")
        device.putSetting("""{"key": "auto_reconnect", "kind": "bool", "writable": true, "value": "true", "only": "true"}""")
        controller.refresh()
        val kept = memory[client.key]!!
        assertEquals(AMS, kept.status.exitNode?.id)
        assertEquals(1, kept.files?.size)
        assertTrue(kept.settings.first { it.key == "auto_reconnect" }.on)
    }

    @Test
    fun aPageThatDidNotReadEverythingKeepsNothing() {
        paired()
        device.script("GET /v1/settings", FakeManagedDevice.refusal(500, "failed"))
        runBlocking { controller.start() }
        assertEquals(ManageProblem.FAILED, ui.problem)
        assertNotNull(ui.status)
        // A status with no switches under it would be half a device on the next page.
        assertNull(memory[client.key])
    }

    @Test
    fun aProblemOverWhatWasRememberedIsRetriedNotWavedAway() {
        open()
        device.script(STATUS, FakeManagedDevice.refusal(503, "unavailable"))
        val page = controller()
        runBlocking { page.start() }
        val m = page.ui as RemoteManageUi.Managing
        assertEquals(ManageProblem.BUSY, m.problem)
        assertTrue(m.remembered)
        // "OK" would leave a page that shows an old device and answers nothing.
        assertEquals(ManageProblemAction.RETRY, m.problemAction)
        page.resolve(ManageProblemAction.RETRY)
        assertFalse((page.ui as RemoteManageUi.Managing).remembered)
    }

    @Test
    fun aDeviceThatIsForgottenOrRefusesThePairingIsNotRemembered() {
        open()
        assertNotNull(memory[client.key])
        device.script(STATUS, FakeManagedDevice.refusal(401, "unauthorized"))
        tick()
        assertEquals(ManageProblem.REVOKED, ui.problem)
        assertNull(memory[client.key])

        // Paired again, read, and forgotten.
        controller.resolve(ManageProblemAction.PAIR_AGAIN)
        controller.setCode("483902")
        controller.pair()
        assertNotNull(memory[client.key])
        controller.forget()
        assertTrue(closed)
        assertNull(memory[client.key])
    }

    // ─── Side by side, as in the app ─────────────────────────────────────────────────────────

    @Test
    fun beforeItsOwnStoreIsReadThePageClaimsNothing() {
        // Neither a pairing nor the lack of one: no role, no Forget, no code's field.
        assertEquals(RemoteManageUi.Opening(NAME, tv = true), controller.ui)
        assertTrue(controller.ui.asking)
        assertNull(controller.ui.peer)
        controller.pair()
        controller.forget()
        controller.refresh()
        tick()
        assertTrue(sent().isEmpty())
        assertEquals(0, looks)
    }

    @Test
    fun aDeviceTheMapHoldsAsOfflineIsSaidOfflineWhileItIsStillBeingAsked() = threaded { page, at ->
        paired()
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = clock - 60_000, address = ADDRESS)
        device.hold(HELLO)
        at.scope.launch { page.start() }
        await("asked") { device.sent.size == 1 }
        val m = page.ui as RemoteManageUi.Managing
        // Said before the small question is answered — and with the question still out.
        assertEquals(ManageProblem.OFFLINE, m.problem)
        assertTrue(m.loading)
        assertEquals(listOf(HELLO), sent())

        // It answers after all: the map was behind, and the page takes the device's word.
        device.release(HELLO)
        await("read") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && it.problem == null && it.status != null } == true }
    }

    @Test
    fun aDeviceTheCallerTakesForPresentIsAskedBeforeItIsCalledOffline() = threaded(present = true) { page, at ->
        paired()
        // A code was just scanned off its screen; this device's map still holds it as offline.
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = clock - 60_000, address = ADDRESS)
        device.hold(STATUS)
        device.hold(HELLO)
        at.scope.launch { page.start() }
        await("both asked") { device.sent.size == 2 }
        val m = page.ui as RemoteManageUi.Managing
        assertNull(m.problem)
        assertTrue(m.loading)
        device.release(HELLO)
        device.release(STATUS)
        await("read") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && it.status != null } == true }
        assertNull((page.ui as RemoteManageUi.Managing).problem)
    }

    @Test
    fun thePageIsThereBeforeTheDeviceAnswers() = threaded { page, at ->
        paired()
        device.hold(STATUS)
        device.hold(HELLO)
        at.scope.launch { page.start() }
        // Both questions are out and neither is answered: the page already shows what this device knows.
        await("both asked") { device.sent.size == 2 }
        val m = page.ui as RemoteManageUi.Managing
        assertTrue(m.loading)
        assertTrue(m.asking)
        assertNull(m.status)
        assertNull(m.problem)
        assertEquals(ManagePeer(RemotePresence.ONLINE, address = ADDRESS), m.peer)
        assertTrue(m.owner)
        assertEquals(setOf(STATUS, HELLO), sent().toSet())

        device.release(HELLO)
        device.release(STATUS)
        await("read") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && it.status != null } == true }
        assertTrue((page.ui as RemoteManageUi.Managing).usable)
    }

    @Test
    fun theSettingsAndTheFilesAreAskedWithoutWaitingForThePageToDraw() = threaded { page, at ->
        paired()
        device.hold(STATUS)
        at.scope.launch { page.start() }
        await("the status asked") { sent().contains(STATUS) }
        // The page's own thread is busy — drawing what it has — when the status comes in.
        val drawing = CountDownLatch(1)
        at.scope.launch { drawing.await() }
        device.release(STATUS)
        // What follows the status leaves all the same, from the thread that brought it.
        await("followed") { sent().containsAll(listOf("GET /v1/settings", "GET /v1/files")) }
        assertNull((page.ui as RemoteManageUi.Managing).status)

        drawing.countDown()
        await("read") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && it.status != null } == true }
        assertEquals(14, (page.ui as RemoteManageUi.Managing).settings.size)
    }

    @Test
    fun whatWasRememberedIsShownWhileTheDeviceIsAsked() = threaded { page, at ->
        open()
        val heard = clock
        clock += 3 * 60_000
        device.hold(STATUS)
        device.hold(HELLO)
        at.scope.launch { page.start() }
        await("both asked") { device.sent.size == 2 }
        val m = page.ui as RemoteManageUi.Managing
        assertEquals(NAME, m.status?.name)
        assertEquals(14, m.settings.size)
        assertTrue(m.remembered)
        assertTrue(m.old)
        assertEquals(heard, m.heardAt)
        assertFalse(m.usable)
        assertTrue(m.asking)

        device.release(HELLO)
        device.release(STATUS)
        await("fresh") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && !it.remembered } == true }
        val fresh = page.ui as RemoteManageUi.Managing
        assertEquals(clock, fresh.heardAt)
        assertTrue(fresh.usable)
    }

    @Test
    fun aDeviceThatDoesNotAnswerIsKnownByTheSmallQuestionWhileTheStatusStillWaits() = threaded { page, at ->
        paired()
        device.reachable = false
        // The status of a device that is not there waits out its twelve seconds; the small question gives up in four.
        device.hold(STATUS)
        at.scope.launch { page.start() }
        await("said") { (page.ui as? RemoteManageUi.Managing)?.problem != null }
        val m = page.ui as RemoteManageUi.Managing
        assertEquals(ManageProblem.NOT_ACCEPTING, m.problem)
        assertFalse(m.loading)
        assertEquals(ManageProblemAction.RETRY, m.problemAction)
        assertEquals(setOf(STATUS, HELLO), sent().toSet())

        // The status gives up at last: it says nothing the page has not said, and nothing follows it.
        device.release(STATUS)
        settle(at)
        assertEquals(ManageProblem.NOT_ACCEPTING, (page.ui as RemoteManageUi.Managing).problem)
        assertEquals(2, device.sent.size)
    }

    @Test
    fun aStatusThatComesAfterTheSmallQuestionGaveUpIsStillTaken() = threaded { page, at ->
        paired()
        device.script(HELLO, FakeManagedDevice.SILENCE)
        device.hold(STATUS)
        at.scope.launch { page.start() }
        await("said") { (page.ui as? RemoteManageUi.Managing)?.problem != null }
        assertEquals(ManageProblem.NOT_ACCEPTING, (page.ui as RemoteManageUi.Managing).problem)

        // A slow link, not a closed port: the device's own word wins over the page's guess.
        device.release(STATUS)
        await("read") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && it.status != null && it.problem == null } == true }
        val m = page.ui as RemoteManageUi.Managing
        assertEquals(14, m.settings.size)
        assertTrue(m.usable)
    }

    @Test
    fun whatWasInTheWayStaysSaidWhileTheDeviceIsAskedAgain() = threaded { page, at ->
        paired()
        device.reachable = false
        at.scope.launch { page.start() }
        await("said") { (page.ui as? RemoteManageUi.Managing)?.let { it.problem == ManageProblem.NOT_ACCEPTING && !it.loading } == true }

        // Asked again: the sentence and its button wait with the page, instead of going and coming back.
        device.hold(HELLO)
        device.hold(STATUS)
        at.scope.launch { page.resolve(ManageProblemAction.RETRY) }
        await("asked again") { sent().count { it == HELLO } == 2 }
        val m = page.ui as RemoteManageUi.Managing
        assertEquals(ManageProblem.NOT_ACCEPTING, m.problem)
        assertTrue(m.loading)

        // It answers this time, and the sentence goes with its first word.
        device.reachable = true
        device.release(HELLO)
        device.release(STATUS)
        await("read") { (page.ui as? RemoteManageUi.Managing)?.let { !it.loading && it.problem == null && it.status != null } == true }
    }

    @Test
    fun whatWasInTheWayOfAPairingStaysSaidWhileTheDeviceIsAskedAgain() = threaded { page, at ->
        device.reachable = false
        at.scope.launch { page.start() }
        await("said") { (page.ui as? RemoteManageUi.Pairing)?.let { it.problem == ManageProblem.NOT_ACCEPTING && !it.checking } == true }

        device.hold(HELLO)
        at.scope.launch { page.resolve(ManageProblemAction.RETRY) }
        await("asked again") { sent().count { it == HELLO } == 2 }
        val p = page.ui as RemoteManageUi.Pairing
        assertEquals(ManageProblem.NOT_ACCEPTING, p.problem)
        assertTrue(p.checking)
        assertFalse(p.form)

        device.reachable = true
        device.release(HELLO)
        await("the form") { (page.ui as? RemoteManageUi.Pairing)?.form == true }
        assertNull((page.ui as RemoteManageUi.Pairing).problem)
    }

    @Test
    fun aLateAnswerToAnEarlierReadingIsNotThisOnes() = threaded { page, at ->
        paired()
        device.script(HELLO, FakeManagedDevice.SILENCE)
        device.hold(STATUS)
        at.scope.launch { page.start() }
        await("said") { (page.ui as? RemoteManageUi.Managing)?.problem != null }
        // The person forgets the device while its first status is still on its way.
        at.scope.launch { page.forget() }
        await("forgotten") { page.ui is RemoteManageUi.Forgotten }
        device.release(STATUS)
        settle(at)
        assertTrue(page.ui is RemoteManageUi.Forgotten)
        assertNull(memory[client.key])
    }

    @Test
    fun forgettingADeviceThatIsAwayDoesNotWaitForIt() = threaded { page, at ->
        paired()
        device.reachable = false
        peer = ManagePeer(RemotePresence.OFFLINE, lastSeen = clock - 60_000, address = ADDRESS)
        at.scope.launch { page.start() }
        await("offline") { (page.ui as? RemoteManageUi.Managing)?.let { it.problem == ManageProblem.OFFLINE && !it.loading } == true }

        // Telling it would take the request's whole wait: it is forgotten here first, and told behind the page's back.
        device.hold("POST /v1/unpair")
        at.scope.launch { page.forget() }
        await("forgotten") { page.ui is RemoteManageUi.Forgotten }
        assertNull(client.pairing())
        await("told") { sent().contains("POST /v1/unpair") }
        device.release("POST /v1/unpair")
    }

    // ─── What is offered, and what is never sent ─────────────────────────────────────────────

    @Test
    fun theFirstBuildIsManagedExactlyAsBeforeAndIsSentNoNewField() {
        open(caps = null)
        assertTrue(ui.access.firstBuild)
        assertEquals(listOf(ManageGroup.CONNECTION, ManageGroup.FILES, ManageGroup.DEVICE, ManageGroup.PAIRING), groupsOf(ui))

        controller.pickExitNode(AMS)
        controller.setSwitch(ManagedKeys.ACCEPT_ROUTES, true)
        controller.reconnect()
        controller.deleteFile("notes.txt")
        // What such a device does not announce is not asked of it.
        controller.identify()
        controller.askPermission("storage")
        controller.openDiag(log = true)
        controller.fetch("/storage/emulated/0/Download/film.mkv")
        controller.addShare("Movies", "/storage/emulated/0/Movies")
        controller.removeShare("Downloads")
        controller.act(ManagedKeys.ACTION_RELAYS)
        controller.act(ManagedKeys.ACTION_SAVE_RECEIVED)
        assertFalse(controller.setRoutes("192.168.5.0/24"))
        controller.setSwitch(ManagedKeys.TUN_MODE, true)

        assertEquals(
            listOf(
                """POST /v1/exit-node {"id":"nAMSexitCNTRL"}""",
                """POST /v1/settings {"key":"accept_routes","value":"true"}""",
                """POST /v1/actions {"action":"reconnect"}""",
                """POST /v1/files/delete {"name":"notes.txt"}""",
            ),
            posts()
        )
        assertNull(ui.guard)
        assertNull(ui.problem)
        assertEquals(AMS, ui.exitNode?.id)
        assertEquals(listOf("Quarterly report Q3 2026 with a name that does not fit in one line.pdf"), ui.files?.map { it.name })
        assertFalse(ui.reconnecting)
    }

    @Test
    fun aGuestIsSentNothingItMayNotDo() {
        open(owner = false)
        assertFalse(ui.owner)
        controller.setSwitch(ManagedKeys.ACCEPT_DNS, false)
        controller.reconnect()
        controller.deleteFile("notes.txt")
        controller.askPermission("storage")
        controller.removeShare("Downloads")
        controller.openDiag(log = false)
        controller.act(ManagedKeys.ACTION_RECEIVE_DEFAULT)
        // The reconnect is refused by the device itself; the rest is not sent at all.
        assertEquals(listOf("""POST /v1/actions {"action":"reconnect"}"""), posts())
        assertEquals(ManageProblem.OWNER_ONLY, ui.problem)

        device.sent.clear()
        controller.identify()
        controller.pickExitNode(AMS)
        assertEquals(listOf("POST /v1/identify {}", """POST /v1/exit-node {"id":"nAMSexitCNTRL","guard":true}"""), posts().take(2))
        assertEquals(ManagedShown.SHOWN, ui.identified)
        // A guest reads how many folders, never which.
        assertNull(ui.app?.folders)
        assertEquals(1, ui.app?.folderCount)
    }

    @Test
    fun aBasicOwnerIsSentNothingThatTakesFullControl() {
        open(full = false)
        assertEquals(ManagedGate.Closed(ManagedDenial.NEEDS_FULL), ui.access.gate(ManagedOp.STORAGE_LIST))
        controller.fetch("/storage/emulated/0/Download/film.mkv")
        controller.addShare("Movies", "/storage/emulated/0/Movies")
        controller.openDiag(log = true)
        // A switch whose other value takes Full control stands still, and so does a list at this level.
        controller.setSwitch(ManagedKeys.TAILDRIVE_PROXY, true)
        controller.setSwitch(ManagedKeys.ADVERTISE_EXIT_NODE, true)
        assertFalse(controller.setRoutes("192.168.5.0/24"))
        // Off only at the device.
        controller.setSwitch(ManagedKeys.KEEP_AWAKE, false)
        // Never on from afar.
        controller.setSwitch(ManagedKeys.FULL_CONTROL, true)
        assertTrue(posts().toString(), posts().isEmpty())

        // What the basic level does allow.
        controller.removeShare("Downloads")
        controller.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        controller.setSwitch(ManagedKeys.TAILDRIVE, false)
        assertEquals(
            listOf(
                """POST /v1/shares/remove {"name":"Downloads"}""",
                """POST /v1/settings {"key":"auto_reconnect","value":"true"}""",
                """POST /v1/settings {"key":"taildrive_enabled","value":"false"}""",
            ),
            posts()
        )
        assertEquals(emptyList<String>(), ui.app?.folders?.map { it.name })
        assertNull(ui.problem)
    }

    @Test
    fun aFullOwnerSharesFetchesAndReadsTheLog() {
        open(full = true)
        controller.addShare("Movies", "/storage/emulated/0/Movies")
        assertEquals(listOf("Downloads", "Movies"), ui.app?.folders?.map { it.name })
        // A name the device would not take is not sent.
        controller.addShare("My films", "/storage/emulated/0/Movies")
        assertEquals(1, posts().size)

        controller.fetch("/storage/emulated/0/Download/film.mkv")
        assertEquals("film.mkv", ui.fetch?.name)
        assertTrue(ui.watching(clock))
        device.finishFetch()
        tick()
        assertEquals("sent", ui.fetch?.state)
        assertFalse(ui.watching(clock))

        controller.openDiag(log = true)
        assertEquals(17, ui.diag?.scrubbed)
        assertEquals(5, ui.diag?.text?.lines()?.size)
        controller.closeDiag()
        controller.openDiag(log = false)
        assertTrue(ui.diag!!.text.startsWith("--- TAILSOCKS DIAGNOSTICS ---"))
        assertFalse(ui.diag!!.text.endsWith("\n"))

        assertTrue(controller.setRoutes("192.168.5.0/24, 10.0.0.0/8"))
        assertEquals("192.168.5.0/24,10.0.0.0/8", device.settingValue(ManagedKeys.ADVERTISE_ROUTES))
        assertFalse(controller.setRoutes("0.0.0.0/0"))
    }

    @Test
    fun aTransferThatEndedBeforeThePageOpenedIsStillSaidWhileTheDeviceKeepsIt() {
        device.become(full = true)
        device.editStatus { put("fetch", buildJsonObject { put("id", "t9"); put("name", "film.mkv"); put("state", "sent"); put("age", 180) }) }
        paired()
        runBlocking { controller.start() }
        assertEquals(180L, ui.fetch?.age)
        assertFalse(ui.watching(clock))
        // A device whose build has no storage says nothing of one.
        device.become(full = true, caps = listOf("report", "can"))
        tick()
        assertNull(ui.fetch)
    }

    @Test
    fun fullControlCanBeGivenUpAndThePageSeesWhatIsLeft() {
        open(full = true)
        assertTrue(ui.access.full)
        controller.setSwitch(ManagedKeys.FULL_CONTROL, false)
        assertEquals(listOf("""POST /v1/settings {"key":"full_control","value":"false"}"""), posts())
        assertFalse(ui.access.full)
        assertEquals(ManagedGate.Closed(ManagedDenial.NEEDS_FULL), ui.access.gate(ManagedOp.DIAG_LOG))
    }

    // ─── Guarded changes ─────────────────────────────────────────────────────────────────────

    @Test
    fun aGuardedChangeIsConfirmedByItselfAndSaidApplied() {
        open()
        controller.pickExitNode(AMS)
        assertEquals(
            listOf("""POST /v1/exit-node {"id":"nAMSexitCNTRL","guard":true}""", """POST /v1/confirm {"id":"g1"}"""),
            posts()
        )
        // Shown as being checked while the device's own five seconds pass, then as applied.
        assertTrue(shown.toString(), GuardPhase.CHECKING in shown)
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
        assertEquals("exit_node", ui.guard?.key)
        assertFalse(device.guardPending)
        assertEquals(AMS, ui.exitNode?.id)
        assertNull(ui.problem)
        assertTrue(ui.usable)
        // "Applied" goes away by itself.
        controller.dismissGuard()
        assertNull(ui.guard)
    }

    @Test
    fun aSwitchIsGuardedOnlyWhereTheDeviceOffersIt() {
        open()
        controller.setSwitch(ManagedKeys.ACCEPT_ROUTES, true)
        // No "guard" for a setting that is not marked optional; "always" needs no flag either.
        controller.dismissGuard()
        controller.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        controller.setSwitch(ManagedKeys.TUN_MODE, true)
        assertEquals(
            listOf(
                """POST /v1/settings {"key":"accept_routes","value":"true","guard":true}""",
                """POST /v1/confirm {"id":"g1"}""",
                """POST /v1/settings {"key":"auto_reconnect","value":"true"}""",
                """POST /v1/settings {"key":"tun_mode","value":"true"}""",
                """POST /v1/confirm {"id":"g2"}""",
            ),
            posts()
        )
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
        assertEquals("tun_mode", ui.guard?.key)
        assertEquals("true", device.settingValue("tun_mode"))
    }

    @Test
    fun aConfirmationThatIsTooEarlyWaitsAsLongAsToldAndIsSentAgain() {
        open()
        device.script(CONFIRM, FakeManagedDevice.refusal(409, "too_early", after = 3))
        val before = clock
        controller.setSwitch(ManagedKeys.ACCEPT_DNS, false)
        assertEquals(2, posts().count { it.startsWith(CONFIRM) })
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
        // The five seconds the device asked for, then the three it asked for again.
        assertTrue(clock - before >= 8_000)
        assertFalse(GuardPhase.SILENT in shown)
        assertEquals("false", device.settingValue("accept_dns"))
    }

    @Test
    fun whenTheDeviceFallsSilentThePageSaysWhenItWillUndoAndKeepsTrying() {
        open()
        val started = clock
        // The exit node cut the channel: nothing comes back from the moment it is in use.
        during = { waited -> if (waited == 5_000L) device.reachable = false }
        online = false
        controller.pickExitNode(AMS)

        assertTrue(shown.toString(), GuardPhase.SILENT in shown)
        // It kept trying to the end of the device's minute, and a little after.
        assertTrue(posts().count { it.startsWith(CONFIRM) } >= 15)
        assertEquals(GuardPhase.REVERTED_AWAY, ui.guard?.phase)
        // The moment the device puts the old value back, by this device's clock.
        assertTrue(ui.guard!!.revertsAt in started + 60_000..started + 61_000)
        assertFalse(ui.usable)

        // It answers again: what the value is now, read from the device.
        during = {}
        device.reachable = true
        tick()
        assertEquals(GuardPhase.REVERTED, ui.guard?.phase)
        assertEquals(FRA, ui.exitNode?.id)
        assertEquals("not confirmed", device.reverted)
        assertNull(ui.problem)
        assertTrue(ui.usable)
        controller.dismissGuard()
        assertNull(ui.guard)
    }

    @Test
    fun aDeviceThatComesBackInTimeIsStillConfirmed() {
        open()
        var silent = 0
        during = { waited ->
            if (waited == 5_000L) device.reachable = false
            // Three unanswered tries, then it is there again: a restart, not a cut.
            if (waited == 3_000L && ++silent == 3) device.reachable = true
        }
        controller.setSwitch(ManagedKeys.TUN_MODE, true)
        assertTrue(shown.toString(), GuardPhase.SILENT in shown)
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
        assertEquals("true", device.settingValue("tun_mode"))
        assertFalse(device.guardPending)
    }

    @Test
    fun oneUnansweredTryIsNotYetSilence() {
        open()
        var tries = 0
        during = { waited ->
            if (waited == 5_000L) device.reachable = false
            if (waited == 3_000L && ++tries == 1) device.reachable = true
        }
        controller.setSwitch(ManagedKeys.TUN_MODE, true)
        assertFalse(shown.toString(), GuardPhase.SILENT in shown)
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
    }

    @Test
    fun aChangeTheDeviceHasAlreadyPutBackIsSaidUndone() {
        open()
        device.script(CONFIRM, FakeManagedDevice.refusal(404, "not_found"))
        controller.pickExitNode(AMS)
        assertEquals(GuardPhase.REVERTED, ui.guard?.phase)
        // Read again before anything is said of the value.
        assertEquals(listOf("GET /v1/status", "GET /v1/settings", "GET /v1/files"), sent().takeLast(3))
        assertTrue(ui.usable)
    }

    @Test
    fun aConfirmationWithNoWordBackIsAskedAgainWhileTheChangeStillWaits() {
        open()
        device.script(CONFIRM, FakeManagedDevice.refusal(504, "pending"))
        controller.pickExitNode(AMS)
        // The status still showed the guard, so it was confirmed again — and kept.
        assertEquals(2, posts().count { it.startsWith(CONFIRM) })
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
    }

    @Test
    fun aConfirmationWithNoWordBackAndNothingWaitingLeavesTheOutcomeOpen() {
        open()
        // The device took the confirmation and its answer was lost on the way: nothing waits there
        // any more, and "pending" is all this device was told.
        during = { waited ->
            if (waited == 5_000L) {
                device.script(CONFIRM, FakeManagedDevice.refusal(504, "pending"))
                device.settle()
            }
        }
        controller.pickExitNode(AMS)
        assertEquals(1, posts().count { it.startsWith(CONFIRM) })
        assertEquals(GuardPhase.UNCLEAR, ui.guard?.phase)
        // Neither "applied" nor "undone" is claimed; what the device says now is what the page shows.
        assertEquals(AMS, ui.exitNode?.id)
        assertTrue(ui.usable)
    }

    @Test
    fun aChangeAnsweredPendingButUnderGuardIsConfirmedAsUsual() {
        open()
        device.answerPending = true
        controller.pickExitNode(AMS)
        assertEquals(
            listOf("""POST /v1/exit-node {"id":"nAMSexitCNTRL","guard":true}""", """POST /v1/confirm {"id":"g1"}"""),
            posts()
        )
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
        assertNull(ui.problem)
        assertEquals(AMS, ui.exitNode?.id)
    }

    @Test
    fun nothingElseIsChangedWhileAChangeIsBeingConfirmed() {
        open()
        during = { waited ->
            if (waited == 5_000L) {
                assertFalse(ui.usable)
                controller.setSwitch(ManagedKeys.ACCEPT_DNS, false)
                controller.pickExitNode("")
                controller.reconnect()
            }
        }
        controller.pickExitNode(AMS)
        assertEquals(2, posts().size)
        assertEquals("true", device.settingValue("accept_dns"))
    }

    @Test
    fun aPageLeftInTheMiddleStillKeepsItsChange() {
        open()
        during = { waited -> if (waited == 5_000L) controller.stop() }
        controller.pickExitNode(AMS)
        assertFalse(device.guardPending)
        assertEquals(AMS, device.exitNodeId)
        // And a page opened afterwards finds it settled.
        val again = controller()
        runBlocking { again.start() }
        assertEquals(GuardPhase.APPLIED, (again.ui as RemoteManageUi.Managing).guard?.phase)
        // "Applied" is news for a moment: a page opened a minute later is not told it again.
        again.stop()
        clock += 60_000
        val later = controller()
        runBlocking { later.start() }
        assertNull((later.ui as RemoteManageUi.Managing).guard)
    }

    @Test
    fun aChangeUndoneWhileNobodyLookedIsToldToThePageThatOpensNext() {
        open()
        during = { waited ->
            if (waited == 5_000L) {
                controller.stop()
                device.reachable = false
            }
        }
        controller.pickExitNode(AMS)
        // The keeper gave up asking; the device put the old value back by itself.
        device.reachable = true
        clock += 600_000
        val later = controller()
        runBlocking { later.start() }
        // It answered the page's first question, so what it did is said at once, with the value it has now.
        val seen = later.ui as RemoteManageUi.Managing
        assertEquals(GuardPhase.REVERTED, seen.guard?.phase)
        assertEquals(FRA, seen.exitNode?.id)
        assertTrue(seen.usable)
    }

    @Test
    fun aChangeFoundWaitingInTheStatusIsConfirmedByThePageThatOpens() {
        device.become()
        paired()
        // Made by this device before its app was closed: nobody is confirming it.
        client.setExitNode(AMS, guard = true)
        clock += 20_000
        runBlocking { controller.start() }
        assertEquals(GuardPhase.APPLIED, ui.guard?.phase)
        assertFalse(device.guardPending)
        assertEquals(AMS, ui.exitNode?.id)
    }

    @Test
    fun aGuardedChangeRefusedBecauseAnotherWaitsSaysSo() {
        open()
        device.script("POST /v1/settings", FakeManagedDevice.refusal(409, "guard_pending"))
        controller.setSwitch(ManagedKeys.ACCEPT_DNS, false)
        assertEquals(ManageProblem.GUARD_PENDING, ui.problem)
        assertNull(ui.guard)
        assertEquals(ManageProblemAction.DISMISS, ui.problem!!.action(loaded = true))
    }

    // ─── Every refusal has a face ────────────────────────────────────────────────────────────

    @Test
    fun busyMeansNothingChangedAndTheNextPressClearsIt() {
        open()
        device.script("POST /v1/settings", FakeManagedDevice.refusal(503, "unavailable"))
        controller.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        assertEquals(ManageProblem.BUSY, ui.problem)
        // Nothing was read again: nothing was changed, and that may be said.
        assertEquals(1, sent().size)
        assertTrue(ui.usable)
        controller.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        assertNull(ui.problem)
        assertEquals("true", device.settingValue("auto_reconnect"))
    }

    @Test
    fun anOpenOutcomeIsReadAgainBeforeAnythingIsSaid() {
        open()
        device.script("POST /v1/actions", FakeManagedDevice.refusal(504, "pending"))
        controller.act(ManagedKeys.ACTION_RECEIVE_DEFAULT)
        assertEquals(listOf("""POST /v1/actions {"action":"receive_folder_default"}""", "GET /v1/status", "GET /v1/settings", "GET /v1/files"), sent())
        assertEquals(ManageProblem.UNCLEAR, ui.problem)
        assertEquals(ManageProblemAction.DISMISS, ui.problem!!.action(loaded = true))

        // The same for an answer the device itself did not understand.
        device.sent.clear()
        device.script("POST /v1/files/delete", FakeManagedDevice.refusal(502, "failed"))
        controller.deleteFile("notes.txt")
        assertEquals(4, sent().size)
        assertEquals(ManageProblem.UNCLEAR, ui.problem)
    }

    @Test
    fun theAppsOwnRefusalIsShownInItsWords() {
        open()
        device.script("POST /v1/permissions/request", FakeManagedDevice.refusal(409, "refused", "this device has no settings page for it"))
        controller.askPermission("storage")
        assertEquals(ManageProblem.REFUSED, ui.problem)
        assertEquals("this device has no settings page for it", ui.problemDetail)
        assertTrue(ui.asked.isEmpty())
    }

    @Test
    fun tooManyRequestsAndAnErrorThere() {
        open()
        device.script("POST /v1/identify", FakeManagedDevice.refusal(429, "slow_down"))
        controller.identify()
        assertEquals(ManageProblem.SLOW_DOWN, ui.problem)
        assertFalse(ui.identifying)
        device.script("POST /v1/identify", FakeManagedDevice.refusal(500, "failed"))
        controller.identify()
        assertEquals(ManageProblem.FAILED, ui.problem)
    }

    @Test
    fun fullControlSwitchedOffMeanwhileIsSeenAndSaid() {
        open(full = true)
        // At the device, while the page was open.
        device.become(full = false)
        controller.openDiag(log = true)
        assertEquals(ManageProblem.NEEDS_FULL, ui.problem)
        assertNull(ui.diag)
        // The status was read again: the rows now stand disabled for the same reason.
        assertFalse(ui.access.full)
        assertEquals(ManagedGate.Closed(ManagedDenial.NEEDS_FULL), ui.access.gate(ManagedOp.DIAG_LOG))
    }

    @Test
    fun whatIsNoLongerThereIsReadAgain() {
        open()
        device.script("POST /v1/files/delete", FakeManagedDevice.refusal(404, "not_found"))
        controller.deleteFile("notes.txt")
        assertEquals(ManageProblem.GONE, ui.problem)
        assertEquals(4, sent().size)
    }

    @Test
    fun aRevokedPairingEndsThePageAndForgetsTheToken() {
        open()
        device.script("GET /v1/status", FakeManagedDevice.refusal(401, "unauthorized"))
        tick()
        assertEquals(ManageProblem.REVOKED, ui.problem)
        assertTrue(ui.problem!!.final)
        assertFalse(ui.usable)
        assertNull(client.pairing())
        assertEquals(ManageProblemAction.PAIR_AGAIN, ui.problemAction)
        device.sent.clear()
        controller.resolve(ManageProblemAction.PAIR_AGAIN)
        // The code is asked for — of a device that was first asked whether it takes one.
        assertEquals(listOf(HELLO), sent())
        assertTrue(pairing.form)
    }

    @Test
    fun aRevokedPairingOfADeviceThatWentAwayGetsNoForm() {
        open()
        device.script("GET /v1/status", FakeManagedDevice.refusal(401, "unauthorized"))
        tick()
        device.reachable = false
        controller.resolve(ManageProblemAction.PAIR_AGAIN)
        assertEquals(ManageProblem.NOT_ACCEPTING, pairing.problem)
        assertFalse(pairing.form)
    }

    @Test
    fun aGuestWhoseTimeIsUpIsToldThatAndNotRevoked() {
        device.become(owner = false)
        paired(owner = false, expiresAt = clock + 1_000)
        runBlocking { controller.start() }
        clock += 2_000
        device.script("GET /v1/status", FakeManagedDevice.refusal(401, "unauthorized"))
        tick()
        assertEquals(ManageProblem.EXPIRED, ui.problem)
    }

    @Test
    fun aDeviceThatGoesAwayKeepsWhatItSaidAndIsAskedUntilItIsBack() {
        open()
        device.reachable = false
        online = false
        tick()
        assertEquals(ManageProblem.OFFLINE, ui.problem)
        assertTrue(ui.problem!!.stale)
        assertFalse(ui.usable)
        // What it said last stays on the page.
        assertEquals(NAME, ui.status?.name)
        assertEquals(ManageProblemAction.RETRY, ui.problemAction)
        assertTrue(ui.old)
        controller.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        assertTrue(posts().isEmpty())

        // While it is away it is asked the small question only: four seconds of waiting at most, not twelve.
        device.sent.clear()
        tick()
        tick()
        assertEquals(ManageProblem.OFFLINE, ui.problem)
        assertEquals(listOf(HELLO, HELLO), sent())
        device.reachable = true
        device.putSetting("""{"key": "auto_reconnect", "kind": "bool", "writable": true, "value": "true", "only": "true"}""")
        tick()
        // Back: the problem is gone and everything was read again.
        assertNull(ui.problem)
        assertTrue(ui.settings.first { it.key == "auto_reconnect" }.on)
    }

    @Test
    fun noAnswerFromADeviceThatIsOnlineIsAClosedPort() {
        open()
        device.reachable = false
        online = true
        tick()
        assertEquals(ManageProblem.NOT_ACCEPTING, ui.problem)
    }

    @Test
    fun aPollNobodyAskedForRaisesNoBannerOverAPassingRefusal() {
        open()
        device.script("GET /v1/status", FakeManagedDevice.refusal(503, "unavailable"), FakeManagedDevice.refusal(429, "slow_down"), FakeManagedDevice.refusal(504, "pending"))
        tick(); tick(); tick()
        assertNull(ui.problem)
        assertEquals(NAME, ui.status?.name)
    }

    @Test
    fun aStatusWithoutTheAppsReportKeepsTheLastOne() {
        open()
        val report = ui.app
        assertNotNull(report)
        // The app there was busy: the status comes without "app".
        device.script("GET /v1/status", RemoteAnswer(200, RemoteContract.STATUS_FIRST_BUILD.replace("\"you\"", "\"caps\": [\"report\"], \"you\"")))
        tick()
        assertEquals(report, ui.app)
    }

    @Test
    fun aDeviceNeverReachedCanStillBeRetriedOrForgotten() {
        paired()
        device.reachable = false
        online = false
        runBlocking { controller.start() }
        assertNull(ui.status)
        assertEquals(ManageProblem.OFFLINE, ui.problem)
        assertEquals(ManageProblemAction.RETRY, ui.problem!!.action(loaded = false))
        // It is asked again at the page's own pace, with no banner coming and going meanwhile.
        tick()
        assertEquals(ManageProblem.OFFLINE, ui.problem)
        device.reachable = true
        tick()
        assertNull(ui.problem)
        assertEquals(NAME, ui.status?.name)
        assertEquals(2, ui.files?.size)

        device.reachable = false
        controller.forget()
        // Forgotten here; the device was not told.
        assertTrue(controller.ui is RemoteManageUi.Forgotten)
        assertNull(client.pairing())
    }

    @Test
    fun aDeviceThatLeftTheTailnetKeepsItsPairingUntilItIsForgotten() {
        open()
        // The bridge says so before this device's own map has caught up.
        device.script("GET /v1/status", RemoteAnswer(status = 0, error = "no_peer"))
        tick()
        assertEquals(ManageProblem.NO_PEER, ui.problem)
        assertTrue(ui.problem!!.final)
        assertEquals(ManageProblemAction.FORGET, ui.problemAction)
        // Nothing refused the token: it is still here, for the person to forget.
        assertNotNull(client.pairing())
        // The map has it now. Nothing is sent after a device that is gone; the page stays as it is.
        peer = ManagePeer(RemotePresence.GONE)
        device.inTailnet = false
        device.sent.clear()
        tick()
        assertEquals(ManageProblem.NO_PEER, ui.problem)
        assertTrue(sent().isEmpty())

        controller.forget()
        // Forgotten at once: there is nobody to wait for.
        assertTrue(controller.ui is RemoteManageUi.Forgotten)
        assertNull(client.pairing())
    }

    @Test
    fun aDeviceThatComesBackToTheTailnetIsReadAgain() {
        open()
        peer = ManagePeer(RemotePresence.GONE)
        device.inTailnet = false
        tick()
        assertEquals(ManageProblem.NO_PEER, ui.problem)
        // Seen by this device's own map, and so with nothing sent.
        assertTrue(sent().isEmpty())

        peer = ManagePeer(RemotePresence.ONLINE, address = ADDRESS)
        device.inTailnet = true
        tick()
        assertNull(ui.problem)
        assertTrue(ui.usable)
    }

    @Test
    fun thisDevicesTailSocksGoingDownIsSaidAtOnceAndMendsItself() {
        open()
        peer = ManagePeer(stopped = true)
        device.connected = false
        tick()
        assertEquals(ManageProblem.NOT_CONNECTED, ui.problem)
        assertEquals(ManageProblemAction.START, ui.problemAction)
        assertTrue(sent().isEmpty())
        assertEquals(NAME, ui.status?.name)
        assertFalse(ui.usable)

        // Started from elsewhere — the tile, the main screen: the page's own look finds it up.
        peer = ManagePeer(RemotePresence.ONLINE, address = ADDRESS)
        device.connected = true
        tick()
        assertNull(ui.problem)
        assertTrue(ui.usable)
        assertEquals(0, started)
    }

    @Test
    fun forgettingADeviceThatHearsClosesThePage() {
        open()
        controller.forget()
        assertTrue(closed)
        assertEquals(listOf("POST /v1/unpair"), sent())
        assertNull(client.pairing())
    }

    @Test
    fun whatEachProblemOffers() {
        for (problem in ManageProblem.entries) {
            val loaded = problem.action(loaded = true)
            val expected = when (problem) {
                ManageProblem.NO_PEER -> ManageProblemAction.CLOSE
                ManageProblem.REVOKED, ManageProblem.EXPIRED -> ManageProblemAction.PAIR_AGAIN
                ManageProblem.NOT_CONNECTED, ManageProblem.OFFLINE, ManageProblem.NOT_ACCEPTING -> ManageProblemAction.RETRY
                else -> ManageProblemAction.DISMISS
            }
            assertEquals(problem.name, expected, loaded)
            // With nothing of the device to show, a passing problem is retried, not waved away.
            if (expected == ManageProblemAction.DISMISS) assertEquals(problem.name, ManageProblemAction.RETRY, problem.action(loaded = false))
        }
        assertEquals(ManageProblem.OFFLINE, ManageProblem.of(ManagedRefusal.UNREACHABLE, peerOnline = false))
        assertEquals(ManageProblem.NOT_ACCEPTING, ManageProblem.of(ManagedRefusal.UNREACHABLE, peerOnline = true))
        assertEquals(ManageProblem.UNCLEAR, ManageProblem.of(ManagedRefusal.GARBLED, peerOnline = true))

        // What this device's own network map settles before anything is sent.
        assertEquals(ManageProblem.NOT_CONNECTED, ManageProblem.local(ManagePeer()))
        assertEquals(ManageProblem.NO_PEER, ManageProblem.local(ManagePeer(RemotePresence.GONE)))
        assertEquals(ManageProblem.OFFLINE, ManageProblem.local(ManagePeer(RemotePresence.OFFLINE)))
        assertNull(ManageProblem.local(ManagePeer(RemotePresence.ONLINE)))
        // A person who asks is not answered from the map alone when it says "offline" — it may be a moment behind.
        assertNull(ManageProblem.local(ManagePeer(RemotePresence.OFFLINE), asked = true))
        assertEquals(ManageProblem.NO_PEER, ManageProblem.local(ManagePeer(RemotePresence.GONE), asked = true))
        assertEquals(ManageProblem.NOT_CONNECTED, ManageProblem.local(ManagePeer(), asked = true))

        // A device that left the tailnet: forgotten where a pairing is kept, closed where there is none.
        val managing = RemoteManageUi.Managing(NAME, problem = ManageProblem.NO_PEER)
        assertEquals(ManageProblemAction.FORGET, managing.problemAction)
        assertEquals(ManageProblemAction.CLOSE, RemoteManageUi.Pairing(NAME, problem = ManageProblem.NO_PEER).problemAction)
        // This device's TailSocks is started only where it is stopped.
        assertEquals(ManageProblemAction.START, managing.copy(problem = ManageProblem.NOT_CONNECTED, peer = ManagePeer(stopped = true)).problemAction)
        assertEquals(ManageProblemAction.RETRY, managing.copy(problem = ManageProblem.NOT_CONNECTED, peer = ManagePeer()).problemAction)
        assertNull(managing.copy(problem = null).problemAction)
    }

    // ─── The rest of what the page does ──────────────────────────────────────────────────────

    @Test
    fun aReconnectWaitsOutTheRestartBeforeSilenceMeansAnything() {
        open()
        during = { waited ->
            // The endpoint is away while the connection restarts, and back at the second look.
            if (waited == 5_000L) device.reachable = false
            if (waited == 4_000L) device.reachable = true
        }
        controller.reconnect()
        assertNull(ui.problem)
        assertFalse(ui.reconnecting)
        assertEquals(listOf("""POST /v1/actions {"action":"reconnect"}""", "GET /v1/status", "GET /v1/status"), sent())
    }

    @Test
    fun aReconnectOfTheRelaysThatLosesItsOwnAnswerIsNoProblem() {
        open()
        device.script("POST /v1/actions", FakeManagedDevice.SILENCE)
        controller.act(ManagedKeys.ACTION_RELAYS)
        assertNull(ui.problem)
        assertEquals(listOf("""POST /v1/actions {"action":"reconnect_relays"}""", "GET /v1/status"), sent())
    }

    @Test
    fun aPermissionRequestSaysHowItEndedAndIsWatchedUntilGranted() {
        open()
        controller.askPermission("storage")
        assertEquals(ManagedShown.SHOWN, ui.asked["storage"])
        // Shown to someone there: the status is read often for two minutes.
        assertTrue(ui.watching(clock))
        assertTrue(ui.watching(clock + WATCH_SHOWN_MS - 1))
        assertFalse(ui.watching(clock + WATCH_SHOWN_MS + 1))
        // Never askable: not sent.
        controller.askPermission("vpn")
        assertEquals(1, posts().size)

        device.grant("storage")
        tick()
        assertTrue(ui.asked.isEmpty())
        assertEquals("granted", ui.app?.permissions?.first { it.id == "storage" }?.state)

        device.permissionResult = "waiting"
        controller.askPermission("install")
        assertEquals(ManagedShown.WAITING, ui.asked["install"])
        assertEquals("install", ui.app?.asked?.id)
    }

    @Test
    fun aFileSentFromHereIsLookedForInTheDevicesLists() {
        open()
        controller.sending(RemoteSendState(progress = "holiday.mp4"))
        assertEquals("holiday.mp4", ui.send?.progress)
        device.sent.clear()
        controller.sent(RemoteSendState(sent = listOf("holiday.mp4")))
        assertNull(ui.send?.progress)
        assertTrue(ui.watching(clock))
        // Both lists: it lands in the folder or waits inside the app.
        assertEquals(listOf("GET /v1/status", "GET /v1/files"), sent())
    }

    @Test
    fun thisDevicesOwnVerdictOnSendingIsKept() {
        open()
        controller.sendVerdict("This device belongs to another user.")
        assertEquals("This device belongs to another user.", ui.sendBlocked)
        tick()
        assertEquals("This device belongs to another user.", ui.sendBlocked)
        controller.sendVerdict(null)
        assertNull(ui.sendBlocked)
    }

    @Test
    fun theExitNodeListMarksTheDevicesOwnPick() {
        open()
        controller.openExitNodes()
        assertTrue(ui.picking)
        assertEquals(AMS, ui.exitNodes?.single { it.suggested }?.id)
        controller.closeExitNodes()
        assertFalse(ui.picking)
    }

    @Test
    fun movingWhatWaitsIntoTheFolderReadsBothLists() {
        open(full = true)
        device.editApp { put("receive", buildJsonObject { put("state", "ready"); put("folder", "Download/Taildrop") }) }
        tick()
        assertEquals(2, ui.app?.receive?.waiting)
        controller.act(ManagedKeys.ACTION_SAVE_RECEIVED)
        assertEquals(0, ui.files?.size)
        assertEquals(0, ui.app?.receive?.waiting)
        assertEquals(3, ui.app?.saved?.size)
    }

    // ─── The device's storage, as the browser reads it ───────────────────────────────────────

    @Test
    fun theStorageIsReadAsPlacesAndFoldersAndItsFailuresAreToldApart() {
        open(full = true)
        val failures = ArrayList<ManagedRefusal>()
        val source = RemoteFileSource(client::storageList) { failures += it.refusal }
        val roots = source.roots()
        assertEquals(listOf(RootKind.PRIMARY, RootKind.VOLUME), roots.map { it.kind })
        // A stick is named as the managed device's own system names it.
        assertEquals("USB-накопитель SanDisk", roots[1].label)
        assertTrue(roots[1].removable)
        assertEquals(File("/storage/emulated/0"), roots[0].dir)

        val download = source.list(File("/storage/emulated/0/Download")) as Listing.Entries
        assertEquals(listOf("Taildrop", "film.mkv"), download.entries.map { it.name })
        assertTrue(download.entries.first().isDirectory)
        assertEquals(734_003_200L, source.describe(File("/storage/emulated/0/Download/film.mkv"))?.size)
        assertEquals(1_791_700_000_000L, source.describe(File("/storage/emulated/0/Download/film.mkv"))?.modified)
        assertTrue(source.describe(File("/storage/emulated/0"))!!.isDirectory)
        assertNull(source.describe(File("/storage/emulated/0/nothing")))

        // A folder longer than one answer is read to its end, in the device's order.
        device.putFolder("/storage/emulated/0/Photos", 1_200)
        val photos = source.list(File("/storage/emulated/0/Photos")) as Listing.Entries
        assertEquals(1_200, photos.entries.size)
        assertEquals(listOf("IMG_1000.jpg", "IMG_2199.jpg"), listOf(photos.entries.first().name, photos.entries.last().name))

        // A folder that is gone is the browser's own notice.
        assertEquals(Listing.Reason.NOT_FOUND, (source.list(File("/storage/none")) as Listing.Unreadable).reason)
        assertTrue(failures.isEmpty())

        // Full control switched off meanwhile is not about the folder: the page says it.
        device.become(full = false)
        assertTrue(source.list(File("/storage/emulated/0")) is Listing.Unreadable)
        assertEquals(listOf(ManagedRefusal.NEEDS_FULL), failures)
        runBlocking { controller.failedOutside(ManagedResult.Failed(ManagedRefusal.NEEDS_FULL)) }
        assertEquals(ManageProblem.NEEDS_FULL, ui.problem)
        assertFalse(ui.access.full)
    }

    // ─── The devices this one manages ────────────────────────────────────────────────────────

    @Test
    fun thePairedDevicesAreListedWithWhatThisDeviceKnowsOfThem() {
        assertTrue(pairedDevices(tokens, "default", emptyMap()).isEmpty())
        tokens.put("default", "nTVliving", RemotePairingRecord(token = "a", name = "living-room-tv", owner = true, tv = true))
        tokens.put("default", "nTVbedroom", RemotePairingRecord(token = "b", name = "bedroom-tv", owner = true, tv = true))
        tokens.put("default", "nTablet", RemotePairingRecord(token = "c", name = "ann-tablet", owner = false))
        tokens.put("work", "nOther", RemotePairingRecord(token = "d", name = "office-tv", owner = true, tv = true))
        val peers = mapOf(
            "nTVliving" to PeerData(id = "nTVliving", hostName = "living-room", dnsName = "living-room-tv.tail1234.ts.net.", online = false),
            "nTVbedroom" to PeerData(id = "nTVbedroom", hostName = "bedroom-tv", online = true),
        )
        val rows = pairedDevices(tokens, "default", peers)
        // Those that are there first; one that left the tailnet last, under the name it was paired by.
        assertEquals(listOf("bedroom-tv", "living-room-tv", "ann-tablet"), rows.map { it.name })
        assertEquals(listOf(RemotePresence.ONLINE, RemotePresence.OFFLINE, RemotePresence.GONE), rows.map { it.presence })
        assertEquals(listOf(true, true, false), rows.map { it.tv })
        assertEquals(listOf(true, true, false), rows.map { it.owner })
        // This device's own TailSocks is stopped: nothing is claimed of anyone.
        assertTrue(pairedDevices(tokens, "default", null).all { it.presence == RemotePresence.UNKNOWN })
        assertEquals(listOf("office-tv"), pairedDevices(tokens, "work", null).map { it.name })
    }
}
