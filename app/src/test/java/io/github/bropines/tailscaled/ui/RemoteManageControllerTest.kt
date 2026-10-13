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
import io.github.bropines.tailscaled.core.RemotePairingRecord
import io.github.bropines.tailscaled.core.RemoteTokenStore
import io.github.bropines.tailscaled.core.RootKind
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    }

    private var clock = 1_791_813_780_000L
    private val tokens = RemoteTokenStore(MemoryKeyValueStore(), SoftwareSecretBox())
    private val device = FakeManagedDevice(clock = { clock })
    private val client = RemoteClient(device, tokens, "default", PEER, now = { clock })
    private var online = true
    private var closed = false

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
        client = client, scope = scope, guards = keeper, peerOnline = { online }, onClose = { closed = true },
        name = NAME, tv = true, appVersion = "4.11.0", io = Dispatchers.Unconfined, pause = pause, now = { clock },
    )

    private val managing: RemoteManageUi.Managing? get() = controller.ui as? RemoteManageUi.Managing
    private val ui: RemoteManageUi.Managing get() = managing ?: throw AssertionError("not managing: ${controller.ui}")

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

    // ─── Opening ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun withoutAPairingTheDigitsAreAskedFor() {
        runBlocking { controller.start() }
        assertTrue(controller.ui is RemoteManageUi.Pairing)
        assertTrue(device.sent.isEmpty())
        controller.setCode("48 39-02x")
        assertEquals("483902", (controller.ui as RemoteManageUi.Pairing).code)
        controller.pair()
        // Paired, and read at once.
        assertEquals(NAME, ui.status?.name)
        assertTrue(ui.owner)
    }

    @Test
    fun thePageReadsTheStatusTheSettingsAndTheFiles() {
        open()
        runBlocking { controller.start() }
        assertEquals(listOf("GET /v1/status", "GET /v1/settings", "GET /v1/files"), sent())
        assertFalse(ui.loading)
        assertNull(ui.problem)
        assertEquals(13, ui.settings.size)
        assertEquals(2, ui.files?.size)
        assertEquals("Xiaomi MiTV-MOOQ1", ui.status?.device?.model)
        assertNotNull(ui.app)
        assertTrue(ui.usable)
        assertEquals("4.11.0", ui.appVersion)
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
        assertEquals(ManageProblemAction.PAIR_AGAIN, ui.problem!!.action(loaded = true))
        controller.resolve(ManageProblemAction.PAIR_AGAIN)
        assertTrue(controller.ui is RemoteManageUi.Pairing)
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
        assertEquals(ManageProblemAction.RETRY, ui.problem!!.action(loaded = true))
        controller.setSwitch(ManagedKeys.AUTO_RECONNECT, true)
        assertTrue(posts().isEmpty())

        tick()
        assertEquals(ManageProblem.OFFLINE, ui.problem)
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
        device.script("GET /v1/status", RemoteAnswer(status = 0, error = "no_peer"))
        tick()
        assertEquals(ManageProblem.NO_PEER, ui.problem)
        assertTrue(ui.problem!!.final)
        assertEquals(ManageProblemAction.CLOSE, ui.problem!!.action(loaded = true))
        // Nothing refused the token: it is still here, for the person to forget.
        assertNotNull(client.pairing())
        controller.resolve(ManageProblemAction.CLOSE)
        assertTrue(closed)
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
