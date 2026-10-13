package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The managing side's reading of a device (core/RemoteClient*.kt) against the contract's own
 * examples (.claude/plans/tv/remote-api.md, kept in RemoteContract): every answer parsed, every
 * refusal named, what is offered to whom and why not, and what is never sent to a device that
 * did not announce it.
 */
class RemoteClientTest {

    private var clock = 1_791_813_780_000L
    private val tokens = RemoteTokenStore(MemoryKeyValueStore(), SoftwareSecretBox())
    private val device = FakeManagedDevice(clock = { clock })
    private val client = RemoteClient(device, tokens, "default", "nKq7Xw2CNTRL", now = { clock })

    private fun paired(owner: Boolean = true, expiresAt: Long = 0) {
        tokens.put("default", "nKq7Xw2CNTRL", RemotePairingRecord(token = device.token, name = "living-room-tv", owner = owner, expiresAt = expiresAt, tv = true))
    }

    private fun <T> ManagedResult<T>.ok(): T = (this as? ManagedResult.Ok)?.value ?: throw AssertionError("expected an answer, got $this")
    private fun ManagedResult<*>.refusal(): ManagedRefusal = (this as? ManagedResult.Failed)?.refusal ?: throw AssertionError("expected a refusal, got $this")

    // ─── The contract's examples ─────────────────────────────────────────────────────────────

    @Test
    fun theStatusExampleIsReadWhole() {
        val s = RemoteContract.status()
        assertTrue(s.running)
        assertEquals("Running", s.backendState)
        assertEquals("1.104.1", s.version)
        assertEquals("4.11.0", s.appVersion)
        assertTrue(s.tv)
        assertEquals("living-room-tv", s.name)
        assertEquals("nKq7Xw2CNTRL", s.nodeId)
        assertEquals("exit-frankfurt", s.exitNode?.name)
        assertEquals("Frankfurt, Germany", s.exitNode?.place)
        assertTrue(s.you.owner)
        assertEquals(-1L, s.you.left)
        assertEquals(14, s.you.can?.size)
        assertEquals(listOf("report", "permissions", "oneway", "suggest", "can", "device", "guard", "identify"), s.caps)
        assertEquals(ManagedLevel.BASIC, s.level)
        assertEquals(ManagedPendingGuard("g7Jk2", "exit_node", left = 42, after = 0), s.guard)
        assertEquals(ManagedDeviceInfo("Xiaomi MiTV-MOOQ1", "11", 30, "0e629420", "proxy", "", 1_799_539_200), s.device)
        assertEquals(ManagedOffer(on = true, approved = false), s.advertise?.exitNode)
        assertEquals(listOf(ManagedRoute("192.168.5.0/24", approved = true)), s.advertise?.routes)
        assertEquals(ManagedFetch("t3", "film.mkv", 734_003_200, ManagedFetch.SENDING, ""), s.fetch)
    }

    @Test
    fun theAppReportIsReadWhole() {
        val app = RemoteContract.status().app!!
        assertEquals(ManagedPermission.KNOWN, app.permissions.map { it.id })
        assertEquals(ManagedPermission("storage", ManagedPermission.MISSING, ask = true), app.permissions.first())
        assertEquals(listOf("storage", "install"), app.permissions.filter { it.ask }.map { it.id })
        assertEquals(ManagedAsked("storage", "notified", 42), app.asked)
        assertEquals(ManagedReceive(ManagedReceive.NEEDS_ACCESS, "Movies/From phone", reset = true, free = 51_234_567_890, waiting = 2), app.receive)
        assertEquals(listOf(ManagedSavedFile("holiday.mp4", 812_647_424, 1_791_727_380, "Download/Taildrop")), app.saved)
        assertEquals(listOf(ManagedFolder("Downloads", "/storage/emulated/0/Download")), app.folders)
        assertEquals(1, app.folderCount)
    }

    @Test
    fun theFirstBuildsStatusHasNoneOfTheLaterFields() {
        val s = RemoteContract.status(RemoteContract.STATUS_FIRST_BUILD)
        assertTrue(s.running)
        assertTrue(s.you.owner)
        assertNull(s.caps)
        assertNull(s.you.can)
        assertEquals("", s.level)
        assertNull(s.guard)
        assertNull(s.device)
        assertNull(s.advertise)
        assertNull(s.fetch)
        assertNull(s.app)
    }

    @Test
    fun whatThisBuildDoesNotKnowIsSkippedNotRefused() {
        val newer = """{
          "running": true, "backendState": "Running", "name": "tv", "telemetry": {"cpu": 0.3, "cores": [1, 2]},
          "you": {"class": "owner", "left": -1, "can": ["status", "teleport"], "since": 12},
          "caps": ["report", "holograms"], "level": "ultra",
          "device": {"model": "X", "android": "17", "sdk": 37, "ram": 4096},
          "app": {
            "permissions": [{"id": "storage", "state": "granted", "ask": false, "why": "x"}, {"id": "camera", "state": "missing", "ask": true}],
            "receive": {"state": "teleported", "folder": "", "reset": false},
            "saved": null, "wallpaper": "blue"
          },
          "fetch": null
        }"""
        val s = RemoteContract.status(newer)
        assertEquals("tv", s.name)
        assertEquals(listOf("report", "holograms"), s.caps)
        assertEquals("X", s.device?.model)
        // An unknown permission is carried, and dropped where the rows are drawn (ManagedPermission.KNOWN).
        assertEquals(listOf("storage", "camera"), s.app?.permissions?.map { it.id })
        assertEquals("teleported", s.app?.receive?.state)
        assertEquals(-1L, s.app?.receive?.free)
        assertEquals(emptyList<ManagedSavedFile>(), s.app?.saved)
        assertNull(s.fetch)

        val access = ManagedAccess(s)
        assertFalse(access.levelKnown)
        assertFalse(access.full)
        assertTrue(access.has(ManagedCaps.REPORT))
        // "teleport" opens nothing this build can ask for.
        assertEquals(ManagedGate.Absent, access.gate(ManagedOp.IDENTIFY))
    }

    @Test
    fun theSettingsExampleIsReadWithItsOneWayAndGuardMarks() {
        val rows = RemoteContract.settings()
        assertEquals(13, rows.size)
        val dns = rows.setting(ManagedKeys.ACCEPT_DNS, ManagedSetting.BOOL)!!
        assertTrue(dns.on)
        assertTrue(dns.writable)
        assertEquals(ManagedSetting.GUARD_OPTIONAL, dns.guard)
        assertNull(dns.only)
        val auto = rows.setting(ManagedKeys.AUTO_RECONNECT, ManagedSetting.BOOL)!!
        assertEquals("true", auto.only)
        assertEquals("", auto.needs)
        val drive = rows.setting(ManagedKeys.TAILDRIVE, ManagedSetting.BOOL)!!
        assertEquals("false", drive.only)
        assertEquals(ManagedLevel.FULL, drive.needs)
        assertEquals("tv", rows.setting("device_class", ManagedSetting.TEXT)?.value)
    }

    @Test
    fun aSettingsRowOfAnUnknownKeyOrKindIsSkipped() {
        val rows = RemoteContract.settings(
            """{"settings": [
              {"key": "accept_dns", "kind": "bool", "writable": true, "value": "true"},
              {"key": "warp_drive", "kind": "bool", "writable": true, "value": "true"},
              {"key": "accept_routes", "kind": "dial", "writable": true, "value": "7", "steps": 11},
              {"key": "exit_node_lan_access", "kind": "bool", "writable": true, "value": "", "error": "unavailable"}
            ]}"""
        )
        val full = ManagedAccess(RemoteContract.status())
        val shown = rows.switches(ManagedKeys.CONNECTION + ManagedKeys.DEVICE + ManagedKeys.SHARING, full)
        assertEquals(listOf(ManagedKeys.ACCEPT_DNS, ManagedKeys.EXIT_NODE_LAN), shown.map { it.key })
        assertEquals(ManagedLock.UNKNOWN, shown.last().lock(owner = true))
    }

    @Test
    fun theOtherExamplesAreRead() {
        val nodes = RemoteContract.exitNodes()
        assertEquals(4, nodes.size)
        assertEquals("nAMSexitCNTRL", nodes.single { it.suggested }.id)
        assertEquals("nFRAexitCNTRL", nodes.single { it.selected }.id)
        assertFalse(nodes.single { it.name == "home-router" }.online)

        assertEquals(2, RemoteContract.files().size)
        assertEquals(RemoteFile("notes.txt", 2048, 1_791_800_000), RemoteContract.files().first())

        assertEquals(ManagedGuard("g7Jk2", "exit_node", seconds = 60, after = 5), RemoteJson.decode<ManagedChanged>(RemoteContract.GUARDED)?.guard)
        assertNull(RemoteJson.decode<ManagedChanged>("{}")?.guard)
        assertEquals(ManagedPermissionAnswer("storage", "shown"), RemoteJson.decode<ManagedPermissionAnswer>(RemoteContract.PERMISSION_SHOWN))

        val roots = RemoteJson.decode<ManagedStorageList>(RemoteContract.STORAGE_ROOTS)!!
        assertEquals(listOf("Internal storage", "USB-накопитель SanDisk"), roots.roots?.map { it.name })
        assertEquals(listOf(ManagedStorageRoot.PRIMARY, ManagedStorageRoot.REMOVABLE), roots.roots?.map { it.kind })
        assertEquals(118_111_600_640, roots.roots?.first()?.total)
        val dir = RemoteJson.decode<ManagedStorageList>(RemoteContract.STORAGE_DIR)!!
        assertNull(dir.roots)
        assertEquals("/storage/emulated/0", dir.parent)
        assertEquals(listOf(ManagedStorageEntry("Taildrop", dir = true, size = 0, time = 1_791_727_380), ManagedStorageEntry("film.mkv", size = 734_003_200, time = 1_791_700_000)), dir.entries)
        assertFalse(dir.truncated || dir.limited)
        assertEquals(0, dir.offset)
        assertEquals(ManagedStorageSent("t3", "film.mkv", 734_003_200), RemoteJson.decode<ManagedStorageSent>(RemoteContract.STORAGE_SENT))

        assertTrue(RemoteContract.report().text.startsWith("--- TAILSOCKS DIAGNOSTICS ---\n"))
        assertEquals(5, RemoteContract.log().lines.size)
        assertEquals(17, RemoteContract.log().scrubbed)
    }

    @Test
    fun whatADeviceShowedMapsToThreeOutcomes() {
        assertEquals(ManagedShown.SHOWN, ManagedShown.of("shown"))
        assertEquals(ManagedShown.NOTIFIED, ManagedShown.of("notified"))
        assertEquals(ManagedShown.WAITING, ManagedShown.of("waiting"))
        assertEquals(ManagedShown.WAITING, ManagedShown.of("unseen"))
        assertNull(ManagedShown.of("beamed"))
    }

    // ─── Refusals ────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyRefusalOfTheContractHasItsOwnName() {
        fun kind(status: Int, word: String = "", error: String = "") =
            RemoteAnswer(status, if (word.isEmpty()) "" else """{"error":"$word"}""", error).refusal().refusal
        assertEquals(ManagedRefusal.NOT_CONNECTED, kind(0, error = "not_connected"))
        assertEquals(ManagedRefusal.NO_PEER, kind(0, error = "no_peer"))
        assertEquals(ManagedRefusal.UNREACHABLE, kind(0, error = "unreachable"))
        assertEquals(ManagedRefusal.FAILED, kind(0, error = "bad_request"))
        assertEquals(ManagedRefusal.FAILED, kind(400, "bad_request"))
        assertEquals(ManagedRefusal.UNAUTHORIZED, kind(401, "unauthorized"))
        assertEquals(ManagedRefusal.FORBIDDEN, kind(403, "forbidden"))
        assertEquals(ManagedRefusal.NEEDS_FULL, kind(403, "needs_full"))
        assertEquals(ManagedRefusal.NOT_FOUND, kind(404, "not_found"))
        assertEquals(ManagedRefusal.FAILED, kind(405, "method_not_allowed"))
        assertEquals(ManagedRefusal.REFUSED, kind(409, "refused"))
        assertEquals(ManagedRefusal.TOO_EARLY, kind(409, "too_early"))
        assertEquals(ManagedRefusal.GUARD_PENDING, kind(409, "guard_pending"))
        assertEquals(ManagedRefusal.FAILED, kind(413, "bad_request"))
        assertEquals(ManagedRefusal.FAILED, kind(415, "bad_request"))
        assertEquals(ManagedRefusal.SLOW_DOWN, kind(429, "slow_down"))
        assertEquals(ManagedRefusal.FAILED, kind(500, "failed"))
        assertEquals(ManagedRefusal.FAILED, kind(500, "not_saved"))
        assertEquals(ManagedRefusal.GARBLED, kind(502, "failed"))
        assertEquals(ManagedRefusal.UNAVAILABLE, kind(503, "unavailable"))
        assertEquals(ManagedRefusal.PENDING, kind(504, "pending"))
        // A 403 or a 409 with no body, or a word of a later build: the plain meaning of the status.
        assertEquals(ManagedRefusal.FORBIDDEN, kind(403))
        assertEquals(ManagedRefusal.REFUSED, kind(409, "moonphase"))
    }

    @Test
    fun aRefusalCarriesOnlyWhatIsForAPerson() {
        val refused = RemoteAnswer(409, """{"error":"refused","message":"this device has no settings page for it"}""").refusal()
        assertEquals("this device has no settings page for it", refused.message)
        val early = RemoteAnswer(409, RemoteContract.TOO_EARLY).refusal()
        assertEquals(ManagedRefusal.TOO_EARLY, early.refusal)
        assertEquals(3L, early.after)
        // The API's own words for the rest stay where they are.
        assertEquals("", RemoteAnswer(403, """{"error":"forbidden","message":"class guest"}""").refusal().message)
        assertEquals(0L, RemoteAnswer(503, """{"error":"unavailable","after":9}""").refusal().after)
    }

    @Test
    fun whichRefusalsLeaveTheOutcomeOpen() {
        assertTrue(ManagedRefusal.PENDING.unclear)
        assertTrue(ManagedRefusal.GARBLED.unclear)
        // Busy means nothing was changed: that one may be said.
        assertFalse(ManagedRefusal.UNAVAILABLE.unclear)
        assertTrue(ManagedRefusal.UNREACHABLE.away && ManagedRefusal.NOT_CONNECTED.away)
        assertTrue(ManagedRefusal.UNAUTHORIZED.final && ManagedRefusal.EXPIRED.final && ManagedRefusal.NO_PEER.final)
        assertFalse(ManagedRefusal.SLOW_DOWN.final || ManagedRefusal.SLOW_DOWN.away)
    }

    // ─── Who is offered what ─────────────────────────────────────────────────────────────────

    private fun access(owner: Boolean = true, full: Boolean = false, caps: List<String>? = RemoteContract.ALL_CAPS): ManagedAccess {
        device.become(owner = owner, full = full, caps = caps)
        paired(owner)
        return ManagedAccess(client.status().ok())
    }

    @Test
    fun theFirstBuildGetsExactlyTheFirstBuildsSet() {
        val a = access(caps = null)
        assertTrue(a.firstBuild)
        assertFalse(a.levelKnown)
        for (op in ManagedOp.entries) {
            val expected = if (op.cap == null) ManagedGate.Open else ManagedGate.Absent
            assertEquals(op.name, expected, a.gate(op))
        }
        assertFalse(a.guardsExitNode)
        val rows = RemoteContract.settings(RemoteContract.SETTINGS)
        // Even if such a device listed more, only its three switches are drawn.
        assertEquals(ManagedKeys.FIRST_BUILD, rows.switches(ManagedKeys.CONNECTION + ManagedKeys.DEVICE + ManagedKeys.SHARING, a).map { it.key })
    }

    @Test
    fun aGuestOfTheFirstBuildLooksAndPicksTheExitNode() {
        val a = access(owner = false, caps = null)
        assertEquals(ManagedGate.Open, a.gate(ManagedOp.EXIT_NODE_SET))
        for (op in listOf(ManagedOp.SETTINGS_SET, ManagedOp.ACTION, ManagedOp.FILES_DELETE)) {
            assertEquals(op.name, ManagedGate.Closed(ManagedDenial.OWNER_ONLY), a.gate(op))
        }
    }

    @Test
    fun aBasicOwnerIsToldThatFullControlIsOffForWhatNeedsIt() {
        val a = access(owner = true, full = false)
        assertTrue(a.levelKnown)
        assertFalse(a.full)
        for (op in ManagedOp.entries) {
            val expected = if (op.full) ManagedGate.Closed(ManagedDenial.NEEDS_FULL) else ManagedGate.Open
            assertEquals(op.name, expected, a.gate(op))
        }
    }

    @Test
    fun aFullOwnerHasEverything() {
        val a = access(owner = true, full = true)
        assertTrue(a.full)
        for (op in ManagedOp.entries) assertEquals(op.name, ManagedGate.Open, a.gate(op))
    }

    @Test
    fun aGuestIsToldOnlyAnOwnerWhateverTheLevel() {
        for (full in listOf(false, true)) {
            val a = access(owner = false, full = full)
            for (op in ManagedOp.entries) {
                val expected = if (op.owner) ManagedGate.Closed(ManagedDenial.OWNER_ONLY) else ManagedGate.Open
                assertEquals("${op.name} full=$full", expected, a.gate(op))
            }
        }
    }

    @Test
    fun aThingIsOfferedOnlyWhenItsCapabilityIsAnnounced() {
        // The contract's example device: no storage, shares or diagnostics yet.
        val a = access(owner = true, full = true, caps = listOf("report", "permissions", "oneway", "suggest", "can", "device", "guard", "identify"))
        assertEquals(ManagedGate.Open, a.gate(ManagedOp.PERMISSION_REQUEST))
        assertEquals(ManagedGate.Open, a.gate(ManagedOp.IDENTIFY))
        for (op in listOf(ManagedOp.SHARES_ADD, ManagedOp.SHARES_REMOVE, ManagedOp.STORAGE_LIST, ManagedOp.STORAGE_SEND, ManagedOp.DIAG_LOG, ManagedOp.DIAG_REPORT)) {
            assertEquals(op.name, ManagedGate.Absent, a.gate(op))
        }
        assertFalse(a.gate(ManagedOp.DIAG_LOG).offered)
    }

    @Test
    fun withoutTheListOfOpsTheClassAndTheLevelDecide() {
        // A build between the first and the one that sends `you.can`: capabilities, no list.
        val owner = access(owner = true, caps = listOf("report", "permissions", "oneway", "suggest"))
        assertFalse(owner.levelKnown)
        assertEquals(ManagedGate.Open, owner.gate(ManagedOp.PERMISSION_REQUEST))
        assertEquals(ManagedGate.Open, owner.gate(ManagedOp.FOLDERS))
        assertEquals(ManagedGate.Absent, owner.gate(ManagedOp.IDENTIFY))
        val guest = access(owner = false, caps = listOf("report", "permissions", "oneway", "suggest"))
        assertEquals(ManagedGate.Closed(ManagedDenial.OWNER_ONLY), guest.gate(ManagedOp.PERMISSION_REQUEST))
        assertEquals(ManagedGate.Open, guest.gate(ManagedOp.EXIT_NODE_SET))
    }

    @Test
    fun aListOfOpsFromADeviceThatDoesNotAnnounceItIsNotBelieved() {
        val s = RemoteContract.status().copy(caps = listOf("report"), you = ManagedYou("guest", 100, can = listOf("settings.set")))
        assertEquals(ManagedGate.Closed(ManagedDenial.OWNER_ONLY), ManagedAccess(s).gate(ManagedOp.SETTINGS_SET))
    }

    @Test
    fun aSwitchSaysTheOneReasonItCannotBeMoved() {
        val rows = RemoteContract.settings()
        fun lock(key: String, owner: Boolean = true) = rows.setting(key, ManagedSetting.BOOL)!!.lock(owner)
        assertEquals(ManagedLock.NONE, lock(ManagedKeys.ACCEPT_DNS))
        // Off, and it may only be written "true": it can be moved.
        assertEquals(ManagedLock.NONE, lock(ManagedKeys.AUTO_RECONNECT))
        // On, and it may only be written "false": it can be moved.
        assertEquals(ManagedLock.NONE, lock(ManagedKeys.TAILDRIVE))
        // Off, may only be written "false", and the other way takes Full control.
        assertEquals(ManagedLock.NEEDS_FULL, lock(ManagedKeys.TAILDRIVE_PROXY))

        val on = ManagedSetting("auto_reconnect", "bool", writable = true, value = "true", only = "true")
        assertEquals(ManagedLock.OFF_AT_DEVICE, on.lock(owner = true))
        val off = ManagedSetting("full_control", "bool", writable = true, value = "false", only = "false")
        assertEquals(ManagedLock.ON_AT_DEVICE, off.lock(owner = true))

        val guests = rows.map { it.copy(writable = false) }
        assertEquals(ManagedLock.OWNER_ONLY, guests.setting(ManagedKeys.ACCEPT_DNS, ManagedSetting.BOOL)!!.lock(owner = false))
        assertEquals(ManagedLock.READ_ONLY, guests.setting(ManagedKeys.ACCEPT_DNS, ManagedSetting.BOOL)!!.lock(owner = true))
        assertEquals(ManagedLock.UNKNOWN, ManagedSetting("accept_dns", "bool", writable = true, error = "unavailable").lock(owner = true))
    }

    // ─── What is sent ────────────────────────────────────────────────────────────────────────

    @Test
    fun requestsAreTheBodiesTheContractNames() {
        device.become(owner = true, full = true)
        paired()
        client.status(); client.exitNodes(); client.settings(); client.files()
        client.setExitNode("nAMSexitCNTRL", guard = true)
        client.confirm("g1")
        client.setSetting("accept_routes", "true", guard = false)
        client.action("reconnect")
        client.deleteFile("notes.txt")
        client.askPermission("storage")
        client.identify()
        client.storageList("")
        client.storageSend("/storage/emulated/0/Download/film.mkv")
        client.addShare("Movies", "/storage/emulated/0/Movies")
        client.removeShare("Movies")
        client.report(); client.log()
        assertEquals(
            listOf(
                "GET /v1/status", "GET /v1/exit-nodes", "GET /v1/settings", "GET /v1/files",
                """POST /v1/exit-node {"id":"nAMSexitCNTRL","guard":true}""",
                """POST /v1/confirm {"id":"g1"}""",
                """POST /v1/settings {"key":"accept_routes","value":"true"}""",
                """POST /v1/actions {"action":"reconnect"}""",
                """POST /v1/files/delete {"name":"notes.txt"}""",
                """POST /v1/permissions/request {"permission":"storage"}""",
                "POST /v1/identify {}",
                """POST /v1/storage/list {"path":""}""",
                """POST /v1/storage/send {"path":"/storage/emulated/0/Download/film.mkv"}""",
                """POST /v1/shares/add {"name":"Movies","path":"/storage/emulated/0/Movies"}""",
                """POST /v1/shares/remove {"name":"Movies"}""",
                "GET /v1/diag/report", "GET /v1/diag/log",
            ),
            device.sent.map { it.toString() }
        )
    }

    @Test
    fun theDeviceItselfRefusesAFieldItDoesNotKnow() {
        // What the manager must never do: the first build takes "guard" for a malformed body.
        device.become(caps = null)
        paired()
        assertEquals(ManagedRefusal.FAILED, client.setExitNode("nAMSexitCNTRL", guard = true).refusal())
        assertEquals(ManagedRefusal.FAILED, client.setSetting("accept_dns", "false", guard = true).refusal())
        assertNull(client.setExitNode("nAMSexitCNTRL").ok().guard)
        assertEquals("nAMSexitCNTRL", device.exitNodeId)
    }

    @Test
    fun aChangeAnswersWithItsGuardOrWithNothing() {
        paired()
        val guarded = client.setSetting("accept_routes", "true", guard = true).ok().guard
        assertNotNull(guarded)
        assertEquals("accept_routes", guarded!!.key)
        assertEquals(60L, guarded.seconds)
        assertEquals(5L, guarded.after)
        assertEquals(ManagedRefusal.TOO_EARLY, client.confirm(guarded.id).refusal())
        clock += 5_000
        client.confirm(guarded.id).ok()
        // Confirmed: nothing is pending any more, so a second confirmation finds nothing.
        assertEquals(ManagedRefusal.NOT_FOUND, client.confirm(guarded.id).refusal())
        assertEquals("true", device.settingValue("accept_routes"))
        assertNull(client.setSetting("auto_reconnect", "true").ok().guard)
    }

    @Test
    fun anUnconfirmedChangeIsPutBackByTheDevice() {
        paired()
        val guard = client.setExitNode("nAMSexitCNTRL", guard = true).ok().guard!!
        assertEquals("nAMSexitCNTRL", client.status().ok().exitNode?.id)
        assertEquals(guard.id, client.status().ok().guard?.id)
        clock += 61_000
        val after = client.status().ok()
        assertNull(after.guard)
        assertEquals("nFRAexitCNTRL", after.exitNode?.id)
        assertEquals("not confirmed", device.reverted)
        assertEquals(ManagedRefusal.NOT_FOUND, client.confirm(guard.id).refusal())
    }

    @Test
    fun aSecondGuardedChangeOfAnotherKeyWaits() {
        paired()
        client.setExitNode("nAMSexitCNTRL", guard = true).ok()
        assertEquals(ManagedRefusal.GUARD_PENDING, client.setSetting("accept_dns", "false", guard = true).refusal())
        // The same key replaces the first, and what it goes back to stays the original value.
        client.setExitNode("nHELexitCNTRL", guard = true).ok()
        clock += 61_000
        assertEquals("nFRAexitCNTRL", client.status().ok().exitNode?.id)
    }

    @Test
    fun theOneWaySwitchesAreRefusedTheOtherWayByTheirOwnWord() {
        paired()
        // Off only at the device.
        client.setSetting("auto_reconnect", "true").ok()
        assertEquals(ManagedRefusal.FORBIDDEN, client.setSetting("auto_reconnect", "false").refusal())
        // On takes Full control.
        assertEquals(ManagedRefusal.NEEDS_FULL, client.setSetting("taildrive_proxy_enabled", "true").refusal())
        device.become(owner = true, full = true)
        client.setSetting("taildrive_proxy_enabled", "true").ok()
    }

    @Test
    fun withoutAPairingNothingIsSent() {
        assertEquals(ManagedRefusal.UNAUTHORIZED, client.status().refusal())
        assertEquals(ManagedRefusal.UNAUTHORIZED, client.identify().refusal())
        assertTrue(device.sent.isEmpty())
    }

    @Test
    fun aRefusedTokenIsForgottenAndARunOutGuestIsToldSo() {
        tokens.put("default", "nKq7Xw2CNTRL", RemotePairingRecord(token = "stale", owner = true))
        assertEquals(ManagedRefusal.UNAUTHORIZED, client.status().refusal())
        assertNull(client.pairing())

        tokens.put("default", "nKq7Xw2CNTRL", RemotePairingRecord(token = "stale", owner = false, expiresAt = clock - 1))
        assertEquals(ManagedRefusal.EXPIRED, client.settings().refusal())
        assertNull(client.pairing())
    }

    @Test
    fun otherRefusalsKeepTheToken() {
        paired()
        device.script("GET /v1/status", FakeManagedDevice.refusal(503, "unavailable"), FakeManagedDevice.refusal(504, "pending"), FakeManagedDevice.SILENCE)
        assertEquals(ManagedRefusal.UNAVAILABLE, client.status().refusal())
        assertEquals(ManagedRefusal.PENDING, client.status().refusal())
        assertEquals(ManagedRefusal.UNREACHABLE, client.status().refusal())
        assertNotNull(client.pairing())
        client.status().ok()
    }

    @Test
    fun aTwoHundredThatIsNotTheDocumentLeavesTheOutcomeOpen() {
        paired()
        device.script("GET /v1/status", RemoteAnswer(200, "<html>"))
        assertEquals(ManagedRefusal.GARBLED, client.status().refusal())
        // A change's empty answer is the plain "done".
        device.script("POST /v1/settings", RemoteAnswer(200, ""))
        assertNull(client.setSetting("accept_dns", "false").ok().guard)
    }

    @Test
    fun forgettingDropsTheTokenWhateverTheDeviceAnswers() {
        paired()
        device.reachable = false
        assertEquals(ManagedRefusal.UNREACHABLE, client.unpair().refusal())
        assertNull(client.pairing())
        device.reachable = true
        paired()
        client.unpair().ok()
        assertNull(client.pairing())
    }

    // ─── The device of the contract ──────────────────────────────────────────────────────────

    @Test
    fun aPermissionRequestSaysHowItEndedAndWaitsInTheStatus() {
        paired()
        assertEquals("shown", client.askPermission("storage").ok().result)
        assertNull(client.status().ok().app?.asked)
        device.permissionResult = "notified"
        assertEquals(ManagedShown.NOTIFIED, ManagedShown.of(client.askPermission("install").ok().result))
        assertEquals("install", client.status().ok().app?.asked?.id)
        // Never askable, and nothing a guest may ask for.
        assertEquals(ManagedRefusal.FORBIDDEN, client.askPermission("vpn").refusal())
        device.grant("install")
        assertNull(client.status().ok().app?.asked)
        val refused = client.askPermission("install") as ManagedResult.Failed
        assertEquals(ManagedRefusal.REFUSED, refused.refusal)
        assertEquals("already granted", refused.message)
        device.become(owner = false)
        assertEquals(ManagedRefusal.FORBIDDEN, client.askPermission("storage").refusal())
    }

    @Test
    fun storageSharesAndDiagnosticsTakeFullControl() {
        device.become(owner = true, full = false)
        paired()
        assertEquals(ManagedRefusal.NEEDS_FULL, client.storageList("").refusal())
        assertEquals(ManagedRefusal.NEEDS_FULL, client.addShare("Movies", "/storage/emulated/0/Movies").refusal())
        assertEquals(ManagedRefusal.NEEDS_FULL, client.log().refusal())
        // Removing a share is the owner's at any level.
        client.removeShare("Downloads").ok()
        assertEquals(emptyList<String>(), device.shareNames)

        device.become(owner = true, full = true)
        assertEquals(2, client.storageList("").ok().roots?.size)
        assertEquals(ManagedRefusal.NOT_FOUND, client.storageList("/storage/none").refusal())
        assertEquals(ManagedRefusal.FORBIDDEN, client.storageList("/storage/emulated/0/../..").refusal())
        client.addShare("Movies", "/storage/emulated/0/Movies").ok()
        assertEquals("the name is taken", (client.addShare("movies", "/storage/emulated/0/Movies") as ManagedResult.Failed).message)
        assertEquals(listOf("Movies"), client.status().ok().app?.folders?.map { it.name })

        val sent = client.storageSend("/storage/emulated/0/Download/film.mkv").ok()
        assertEquals("film.mkv", sent.name)
        assertEquals(ManagedFetch.SENDING, client.status().ok().fetch?.state)
        assertEquals(ManagedRefusal.REFUSED, client.storageSend("/storage/emulated/0/Download/film.mkv").refusal())
        device.finishFetch()
        assertEquals(ManagedFetch.SENT, client.status().ok().fetch?.state)
        assertEquals(17, client.log().ok().scrubbed)
    }

    @Test
    fun aLongFolderComesInPartsAndTheOffsetIsSentOnlyForTheNext() {
        device.become(owner = true, full = true)
        device.putFolder("/storage/emulated/0/Photos", 1_200)
        paired()
        val first = client.storageList("/storage/emulated/0/Photos").ok()
        assertEquals(500, first.entries.size)
        assertTrue(first.truncated)
        val second = client.storageList("/storage/emulated/0/Photos", offset = 500).ok()
        assertEquals(500, second.offset)
        assertTrue(second.truncated)
        val third = client.storageList("/storage/emulated/0/Photos", offset = 1_000).ok()
        assertEquals(200, third.entries.size)
        assertFalse(third.truncated)
        assertEquals("IMG_2199.jpg", third.entries.last().name)
        assertEquals(
            listOf(
                """POST /v1/storage/list {"path":"/storage/emulated/0/Photos"}""",
                """POST /v1/storage/list {"path":"/storage/emulated/0/Photos","offset":500}""",
                """POST /v1/storage/list {"path":"/storage/emulated/0/Photos","offset":1000}""",
            ),
            device.sent.map { it.toString() }
        )
    }

    // ─── Small rules ─────────────────────────────────────────────────────────────────────────

    @Test
    fun anOlderAppIsToldFromANewerOne() {
        assertTrue(isOlderAppVersion("4.10.1", "4.11.0"))
        assertTrue(isOlderAppVersion("4.11.0-dev", "4.11.1"))
        assertTrue(isOlderAppVersion("v4.9", "4.10.0"))
        assertFalse(isOlderAppVersion("4.11.0", "4.11.0-dev"))
        assertFalse(isOlderAppVersion("4.11.0", "4.10.9"))
        assertFalse(isOlderAppVersion("", "4.11.0"))
        assertFalse(isOlderAppVersion("nightly", "4.11.0"))
    }

    @Test
    fun aShareNameIsMadeOfWhatTheDeviceTakes() {
        assertTrue(isShareName("Movies_2026"))
        assertFalse(isShareName(""))
        assertFalse(isShareName("My films"))
        assertFalse(isShareName("x".repeat(41)))
        assertEquals("From_phone", shareNameOf("From phone"))
        assertEquals("share", shareNameOf("Фильмы"))
        assertEquals("a_b", shareNameOf("__a.b__"))
        assertTrue(isShareName(shareNameOf("A folder with a very long name that goes on and on and on")))
    }

    @Test
    fun routesAreAListOfPrefixesAndNeverADefaultRoute() {
        assertEquals("192.168.5.0/24,10.0.0.0/8", cidrList("192.168.5.0/24, 10.0.0.0/8"))
        assertEquals("fd7a:115c:a1e0::/48", cidrList("fd7a:115c:a1e0::/48"))
        assertEquals("", cidrList("  "))
        assertNull(cidrList("192.168.5.0"))
        assertNull(cidrList("192.168.5.0/33"))
        assertNull(cidrList("0.0.0.0/0"))
        assertNull(cidrList("::/0"))
        assertNull(cidrList("router/24"))
        assertNull(cidrList((1..17).joinToString(",") { "10.$it.0.0/16" }))
    }
}
