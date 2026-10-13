package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.admin.profile.SoftwareSecretBox
import io.github.bropines.tailscaled.admin.secure.MemoryKeyValueStore
import io.github.bropines.tailscaled.admin.secure.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Remote management's app half: the bridge's documents as the screens read them, the delegate's
 * own allow-list, the sealed token store, and what a managing session does with an answer.
 * The documents below are written the way appctr/remote*.go emits them.
 */
class RemoteManageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── The bridge's documents ──────────────────────────────────────────────────────────────

    @Test
    fun theStateDocumentIsReadWithItsCodeAndPolicy() {
        val s = RemoteJson.state(
            """
            {"available":true,"enabled":true,"listening":true,"port":28115,"inbound":"netstack","delegate":true,"paired":2,
             "pairing":{"code":"483902","qr":"tsremote:1?c=483902&h=tv&n=nABC","left":582,"expires":1791814362,"attemptsLeft":4,
                        "lastWrong":{"name":"ann-iphone","login":"ann@example.org","nodeId":"nB7","time":1791813685}},
             "lastPairing":{"result":"paired","time":1791800000,"name":"pixel","nodeId":"nP9","class":"owner"},
             "policy":{"allowGuests":true,"codeSeconds":600,"codeAttempts":5,"guestSeconds":86400,"ownerSeconds":0,"maxPairings":8},
             "rev":41}
            """
        )
        assertTrue(s.available && s.enabled && s.listening && s.delegate)
        assertEquals(28115, s.port)
        assertEquals(RemoteState.INBOUND_NETSTACK, s.inbound)
        assertEquals("483902", s.pairing?.code)
        assertEquals(582L, s.pairing?.left)
        assertEquals(4, s.pairing?.attemptsLeft)
        assertEquals("ann@example.org", s.pairing?.lastWrong?.login)
        assertEquals(RemoteOutcome.PAIRED, s.lastPairing?.result)
        assertEquals(REMOTE_CLASS_OWNER, s.lastPairing?.role)
        assertEquals(86_400L, s.policy.guestSeconds)
        assertEquals(41L, s.rev)
    }

    @Test
    fun aStateWithoutACodeOrABrokenDocumentIsTheQuietOne() {
        val s = RemoteJson.state("""{"available":true,"enabled":false,"listening":false,"port":28115,"inbound":"none","delegate":false,"paired":0,"policy":{},"rev":3}""")
        assertNull(s.pairing)
        assertNull(s.lastPairing)
        assertEquals(RemoteState.INBOUND_NONE, s.inbound)
        // Nothing the screen polls may throw.
        assertEquals(RemoteState(), RemoteJson.state(""))
        assertEquals(RemoteState(), RemoteJson.state("not json"))
        assertEquals(RemoteState.INBOUND_UNKNOWN, RemoteJson.state("{}").inbound)
        assertEquals(emptyList<RemotePairedDevice>(), RemoteJson.paired("{"))
        assertEquals(emptyList<RemoteEvent>(), RemoteJson.actions(""))
    }

    @Test
    fun pairedDevicesAndTheLogAreRead() {
        val paired = RemoteJson.paired(
            """
            [{"nodeId":"nP9","name":"pixel-9-pro","login":"me@example.com","class":"owner","created":1791000000,"lastUsed":1791813650,"left":-1,"expires":0},
             {"nodeId":"nB7","name":"ann-iphone","login":"ann@example.org","class":"guest","created":1791802980,"lastUsed":0,"left":75600,"expires":1791889380}]
            """
        )
        assertEquals(2, paired.size)
        assertTrue(paired[0].owner)
        assertEquals(-1L, paired[0].left)
        assertFalse(paired[1].owner)
        assertEquals(75_600L, paired[1].left)
        assertEquals(0L, paired[1].lastUsed)

        val log = RemoteJson.actions(
            """[{"time":1791813650,"nodeId":"nP9","name":"pixel-9-pro","class":"owner","op":"setting","detail":"accept_dns=false","ok":true},
                {"time":1791800000,"op":"revoked_all","detail":"2","ok":true}]"""
        )
        assertEquals(listOf("setting", "revoked_all"), log.map { it.op })
        assertEquals("accept_dns=false", log[0].detail)
        assertEquals("", log[1].name)
    }

    @Test
    fun aRevocationThatIsNotOnDiskSaysSo() {
        assertTrue(RemoteJson.revoked("""{"ok":true,"removed":1}""").ok)
        val unsaved = RemoteJson.revoked("""{"ok":false,"removed":1,"error":"not_saved"}""")
        assertFalse(unsaved.ok)
        assertEquals(RemoteRevoked.NOT_SAVED, unsaved.error)
        assertFalse(RemoteJson.revoked("").ok)
    }

    @Test
    fun theProbeTellsATelevision() {
        val tv = RemoteJson.probe("""{"ok":true,"api":1,"deviceClass":"tv","name":"living-room-tv","pairing":true}""")
        assertTrue(tv.tv && tv.pairing)
        assertFalse(RemoteJson.probe("""{"ok":true,"api":1,"deviceClass":"phone","name":"x","pairing":false}""").tv)
        // Not offered: the class of whatever answered is not believed.
        assertFalse(RemoteJson.probe("""{"ok":false,"error":"not_offered","deviceClass":"tv","pairing":false}""").tv)
        assertFalse(RemoteJson.probe("garbage").ok)
    }

    @Test
    fun aScannedCodeIsOneOnlyWhenTheBridgeSaysSo() {
        val qr = RemoteJson.qr("""{"ok":true,"nodeId":"nKq7Xw2CNTRL","code":"483902","name":"living-room-tv"}""")
        assertEquals("nKq7Xw2CNTRL", qr?.nodeId)
        assertEquals("483902", qr?.code)
        assertNull(RemoteJson.qr("""{"ok":false}"""))
        assertNull(RemoteJson.qr("""{"ok":true,"nodeId":"nA","code":"48390"}"""))
        assertNull(RemoteJson.qr(""))
    }

    @Test
    fun pairingAnswersMapToWhatThePersonIsTold() {
        val ok = RemoteJson.pair("""{"ok":true,"token":"dG9rZW4","class":"guest","left":86400,"name":"living-room-tv"}""")
        assertTrue(ok.ok)
        assertEquals(86_400L, ok.left)
        assertEquals(-1L, RemoteJson.pair("""{"ok":true,"token":"dA","class":"owner","left":-1}""").left)
        // The bridge omits a zero: no time left, never "until revoked".
        assertEquals(0L, RemoteJson.pair("""{"ok":true,"token":"dA","class":"guest"}""").left)
        val wrong = RemoteJson.pair("""{"ok":false,"error":"wrong","attemptsLeft":3,"status":403}""")
        assertEquals(RemotePairFailure.WRONG, wrong.failure())
        assertEquals(3, wrong.attemptsLeft)
        val words = mapOf(
            "locked" to RemotePairFailure.LOCKED, "closed" to RemotePairFailure.CLOSED, "used" to RemotePairFailure.USED,
            "not_allowed" to RemotePairFailure.NOT_ALLOWED, "full" to RemotePairFailure.FULL, "slow_down" to RemotePairFailure.SLOW_DOWN,
            "not_connected" to RemotePairFailure.NOT_CONNECTED, "no_peer" to RemotePairFailure.NO_PEER,
            "unreachable" to RemotePairFailure.UNREACHABLE, "bad_answer" to RemotePairFailure.FAILED, "unavailable" to RemotePairFailure.FAILED,
        )
        for ((word, failure) in words) assertEquals(word, failure, RemotePairAnswer(error = word).failure())
    }

    @Test
    fun everyRefusalOfARequestHasItsOwnMeaning() {
        fun kind(status: Int, error: String = "", body: String = "") = RemoteAnswer(status, body, error).failure().failure
        assertEquals(RemoteFailure.NOT_CONNECTED, kind(0, "not_connected"))
        assertEquals(RemoteFailure.NO_PEER, kind(0, "no_peer"))
        assertEquals(RemoteFailure.UNREACHABLE, kind(0, "unreachable"))
        assertEquals(RemoteFailure.UNAUTHORIZED, kind(401, body = """{"error":"unauthorized"}"""))
        assertEquals(RemoteFailure.FORBIDDEN, kind(403))
        assertEquals(RemoteFailure.NOT_FOUND, kind(404))
        assertEquals(RemoteFailure.SLOW_DOWN, kind(429))
        assertEquals(RemoteFailure.UNAVAILABLE, kind(503))
        assertEquals(RemoteFailure.PENDING, kind(504))
        assertEquals(RemoteFailure.FAILED, kind(500))

        // Only the app's refusal carries words meant for a person.
        val refused = RemoteAnswer(409, """{"error":"refused","message":"set by an extra argument on the device"}""").failure()
        assertEquals(RemoteFailure.REFUSED, refused.failure)
        assertEquals("set by an extra argument on the device", refused.message)
        assertEquals("", RemoteAnswer(503, """{"error":"unavailable","message":"the app is busy; nothing was changed"}""").failure().message)
    }

    @Test
    fun aProblemIsNamedByWhatThisDeviceKnowsOfThePeer() {
        // Silence from a device that is online is a closed port, not an absence.
        assertEquals(RemoteProblem.NOT_ACCEPTING, RemoteProblem.of(RemoteFailure.UNREACHABLE, peerOnline = true))
        assertEquals(RemoteProblem.OFFLINE, RemoteProblem.of(RemoteFailure.UNREACHABLE, peerOnline = false))
        assertEquals(RemoteProblem.REVOKED, RemoteProblem.of(RemoteFailure.UNAUTHORIZED, true))
        assertEquals(RemoteProblem.EXPIRED, RemoteProblem.of(RemoteFailure.EXPIRED, true))
        assertEquals(RemoteProblemAction.PAIR_AGAIN, RemoteProblem.REVOKED.action)
        assertEquals(RemoteProblemAction.PAIR_AGAIN, RemoteProblem.EXPIRED.action)
        assertEquals(RemoteProblemAction.REFRESH, RemoteProblem.PENDING.action)
        assertTrue(RemoteProblem.REVOKED.final && RemoteProblem.EXPIRED.final && RemoteProblem.NO_PEER.final)
        assertFalse(RemoteProblem.OFFLINE.final)
        // Every failure has a sentence: none falls through.
        for (f in RemoteFailure.entries) assertNotNull(RemoteProblem.of(f, false))
    }

    @Test
    fun theManagedStatusAndItsListsAreRead() {
        val status = RemoteJson.decode<RemoteStatus>(
            """{"running":true,"backendState":"Running","version":"1.104.1","appVersion":"4.11.0","deviceClass":"tv","name":"tv",
                "dnsName":"tv.tail.ts.net","nodeId":"nK","exitNode":{"id":"nF","name":"exit-frankfurt","online":true,"selected":true,"city":"Frankfurt"},
                "health":["no relay"],"since":1791773780,"you":{"class":"guest","left":75600}}"""
        )!!
        assertTrue(status.running)
        assertEquals("exit-frankfurt", status.exitNode?.name)
        assertEquals(listOf("no relay"), status.health)
        assertFalse(status.you.owner)
        assertEquals(75_600L, status.you.left)

        val settings = RemoteJson.decode<RemoteSettings>(
            """{"settings":[{"key":"accept_dns","kind":"bool","writable":false,"value":"true"},
                            {"key":"exit_node_lan_access","kind":"bool","writable":false,"value":"","error":"unavailable"},
                            {"key":"device_class","kind":"text","writable":false,"value":"tv"}]}"""
        )!!.settings
        assertTrue(settings[0].on)
        assertFalse(settings[0].writable)
        assertEquals("unavailable", settings[1].error)
        assertFalse(settings[1].on)

        val files = RemoteJson.decode<RemoteFiles>("""{"files":[{"name":"a.pdf","size":2516582,"modTime":1791727380}]}""")!!.files
        assertEquals("a.pdf", files.single().name)
        assertEquals(2_516_582L, files.single().size)
    }

    @Test
    fun requestBodiesAreWhatTheEndpointDecodes() {
        assertEquals("""{"id":"nF"}""", RemoteJson.exitNodeBody("nF"))
        assertEquals("""{"id":""}""", RemoteJson.exitNodeBody(""))
        assertEquals("""{"key":"accept_dns","value":"false"}""", RemoteJson.settingBody("accept_dns", false))
        assertEquals("""{"action":"reconnect"}""", RemoteJson.actionBody("reconnect"))
        // A file's name goes in as a JSON string, whatever is in it.
        assertEquals("""{"name":"a \"b\".txt"}""", RemoteJson.fileBody("a \"b\".txt"))
    }

    @Test
    fun codesAndNodeIdsAreRecognised() {
        assertTrue(RemoteJson.isCode("483902"))
        for (bad in listOf("", "48390", "4839021", "48390a", "483 902", "４８３９０２")) assertFalse(bad, RemoteJson.isCode(bad))
        assertTrue(RemoteJson.isNodeId("nKq7Xw2CNTRL"))
        for (bad in listOf("", "a/b", "a b", "a\"b", "..", "x".repeat(65))) assertFalse(bad, RemoteJson.isNodeId(bad))
        assertEquals("483 902", remoteCodeGroups("483902"))
        assertEquals("48", remoteCodeGroups("48"))
    }

    @Test
    fun reachSaysWhyNobodyCanConnect() {
        val up = RemoteState(available = true, enabled = true, listening = true, inbound = RemoteState.INBOUND_NETSTACK)
        fun reach(on: Boolean, state: RemoteState, blocker: RemoteBlocker = RemoteBlocker.OTHER) = RemoteReach.of(on, state) { blocker }
        // Off is said once the endpoint is closed, not when the switch is turned.
        assertEquals(RemoteReach.CLOSING, reach(false, up))
        assertEquals(RemoteReach.CLOSING, reach(false, up.copy(listening = false)))
        assertEquals(RemoteReach.OFF, reach(false, up.copy(enabled = false, listening = false)))
        assertEquals(RemoteReach.OFF, reach(false, RemoteState()))
        assertEquals(RemoteReach.READY, reach(true, up))
        assertTrue(RemoteReach.READY.canPair)
        // Before the first start, and while the daemon does not answer.
        assertEquals(RemoteReach.NOT_RUNNING, reach(true, RemoteState()))
        assertEquals(RemoteReach.NOT_RUNNING, reach(true, up.copy(inbound = RemoteState.INBOUND_UNKNOWN)))
        // The daemon's mode keeps peers out, and the settings say which mode.
        val shut = up.copy(inbound = RemoteState.INBOUND_NONE)
        assertEquals(RemoteReach.NATIVE_TUN, reach(true, shut, RemoteBlocker.NATIVE_TUN))
        assertEquals(RemoteReach.ROOT_TUN, reach(true, shut, RemoteBlocker.ROOT_TUN))
        assertEquals(RemoteReach.OTHER_MODE, reach(true, shut, RemoteBlocker.OTHER))
        assertEquals(RemoteReach.PORT_BUSY, reach(true, up.copy(listening = false, error = "address already in use")))
        // Switched on a moment ago; the service has not applied it yet.
        assertEquals(RemoteReach.APPLYING, reach(true, up.copy(enabled = false, listening = false)))
        for (r in RemoteReach.entries) if (r != RemoteReach.READY) assertFalse(r.name, r.canPair)
    }

    @Test
    fun durationsAreRoundedUpToOneUnit() {
        assertEquals(1L to RemoteDurationUnit.MINUTES, remoteDuration(1))
        assertEquals(30L to RemoteDurationUnit.MINUTES, remoteDuration(1_800))
        assertEquals(120L to RemoteDurationUnit.MINUTES, remoteDuration(7_199))
        assertEquals(21L to RemoteDurationUnit.HOURS, remoteDuration(75_600))
        assertEquals(24L to RemoteDurationUnit.HOURS, remoteDuration(86_400))
        assertEquals(3L to RemoteDurationUnit.DAYS, remoteDuration(3 * 86_400L))
        assertEquals("9:42", remoteCountdown(582))
        assertEquals("0:05", remoteCountdown(5))
        assertEquals("0:00", remoteCountdown(-3))
    }

    // ─── The delegate ────────────────────────────────────────────────────────────────────────

    /** An app that records what was asked of it. */
    private class FakeHost : RemoteHost {
        val switches = mutableMapOf(RemoteKeys.ACCEPT_DNS to true, RemoteKeys.ACCEPT_ROUTES to false, RemoteKeys.EXIT_NODE_LAN to false)
        val calls = mutableListOf<String>()
        var refuse: String? = null

        override fun switchOn(key: String): Boolean = switches.getValue(key)
        override fun setSwitch(key: String, on: Boolean) {
            refuse?.let { throw RemoteRefusal(it) }
            calls += "set $key=$on"
            switches[key] = on
        }
        override fun setExitNode(id: String) { calls += "exit '$id'" }
        override fun reconnect() { calls += "reconnect" }
        override fun deviceClass() = "tv"
        override fun appVersion() = "4.11.0"
    }

    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (e: RemoteRefusal) {
            return e.message.orEmpty()
        }
        fail("not refused")
        return ""
    }

    @Test
    fun theDelegateReadsTheThreeSwitchesAndTheTwoFacts() {
        val d = RemoteSettingsDelegate(FakeHost())
        assertEquals("true", d.getSetting("accept_dns"))
        assertEquals("false", d.getSetting("accept_routes"))
        assertEquals("false", d.getSetting("exit_node_lan_access"))
        assertEquals("tv", d.getSetting("device_class"))
        assertEquals("4.11.0", d.getSetting("app_version"))
    }

    @Test
    fun theDelegateChangesOnlyWhatIsOnItsList() {
        val host = FakeHost()
        val d = RemoteSettingsDelegate(host)
        d.setSetting("accept_dns", "false")
        d.setSetting("accept_routes", "true")
        d.setSetting("exit_node_lan_access", "true")
        d.setSetting("exit_node", "nFRAexitCNTRL")
        d.setSetting("exit_node", "")
        d.performAction("reconnect")
        assertEquals(
            listOf("set accept_dns=false", "set accept_routes=true", "set exit_node_lan_access=true", "exit 'nFRAexitCNTRL'", "exit ''", "reconnect"),
            host.calls
        )
    }

    @Test
    fun theDelegateRefusesEverythingElseItself() {
        val host = FakeHost()
        val d = RemoteSettingsDelegate(host)
        // The bridge filters first; this list must hold without it.
        val never = listOf(
            "socks5_pass", "socks5", "lan_access_enabled", "extra_args_raw", "authkey", "login_server", "automation_secret",
            "root_mode_enabled", "tun_mode_enabled", "remote_management", "hostname", "ACCEPT_DNS", "accept_dns ", "", null,
        )
        for (key in never) {
            assertTrue(refused { d.getSetting(key) }.isNotEmpty())
            assertTrue(refused { d.setSetting(key, "true") }.isNotEmpty())
        }
        // The read-only facts cannot be written, and the exit node is not read through here.
        refused { d.setSetting("device_class", "phone") }
        refused { d.setSetting("app_version", "9") }
        refused { d.getSetting("exit_node") }
        // A switch takes "true" or "false" and nothing that merely looks like them.
        for (value in listOf("1", "TRUE", "yes", "", null)) refused { d.setSetting("accept_dns", value) }
        // An exit node is an id, never something to splice into a request.
        for (id in listOf("a b", "x\",\"ExitNodeIP\":\"1.2.3.4", "../x", "x".repeat(65))) refused { d.setSetting("exit_node", id) }
        for (action in listOf("stop", "disconnect", "logout", "restart", "RECONNECT", "", null)) refused { d.performAction(action) }
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun theAppsOwnRefusalReachesTheBridgeAsItsMessage() {
        val host = FakeHost().apply { refuse = "set by an extra argument on the device" }
        assertEquals("set by an extra argument on the device", refused { RemoteSettingsDelegate(host).setSetting("exit_node_lan_access", "true") })
    }

    @Test
    fun whatTheAppThrowsByAccidentNeverTravels() {
        val broken = object : RemoteHost by FakeHost() {
            override fun switchOn(key: String): Boolean = throw IllegalStateException("/data/user/0/app/shared_prefs/appctr_default.xml")
            override fun setExitNode(id: String) = throw SecurityException("token tskey-auth-secret")
            override fun reconnect() = throw RuntimeException("socks5 password hunter2")
        }
        val d = RemoteSettingsDelegate(broken)
        for (said in listOf(refused { d.getSetting("accept_dns") }, refused { d.setSetting("exit_node", "nF") }, refused { d.performAction("reconnect") })) {
            assertEquals("the app could not do it", said)
        }
    }

    // ─── The token store ─────────────────────────────────────────────────────────────────────

    private val vault = MemoryKeyValueStore()
    private val box = SoftwareSecretBox()
    private val tokens = RemoteTokenStore(vault, box)
    private val record = RemotePairingRecord(token = "dG9rZW4tc3VwZXJzZWNyZXQ", name = "living-room-tv", owner = true, tv = true)

    @Test
    fun aTokenIsStoredSealedAndComesBackWhole() {
        assertTrue(tokens.put("default", "nKq7", record))
        assertTrue(tokens.has("default", "nKq7"))
        assertEquals(record, tokens.get("default", "nKq7"))
        val raw = vault.getString("default/nKq7")!!
        assertFalse(raw.contains("supersecret") || raw.contains(record.token) || raw.contains("living-room"))
        // Another peer, and the same peer id under another account, hold nothing.
        assertNull(tokens.get("default", "nOther"))
        assertNull(tokens.get("work", "nKq7"))
    }

    @Test
    fun aBlobMovedUnderAnotherPeerOrAccountDoesNotOpenAndIsDropped() {
        tokens.put("default", "nKq7", record)
        val blob = vault.getString("default/nKq7")!!
        vault.putString("default/nEvil", blob)
        vault.putString("work/nKq7", blob)
        assertNull(tokens.get("default", "nEvil"))
        assertNull(tokens.get("work", "nKq7"))
        assertFalse(tokens.has("default", "nEvil"))
        assertFalse(tokens.has("work", "nKq7"))
        // The real one is untouched.
        assertEquals(record, tokens.get("default", "nKq7"))
    }

    @Test
    fun aBlobSealedOnAnotherDeviceIsNoPairing() {
        tokens.put("default", "nKq7", record)
        // The same file under another Keystore: a restored backup, a copied data directory.
        val elsewhere = RemoteTokenStore(vault, SoftwareSecretBox())
        assertNull(elsewhere.get("default", "nKq7"))
        assertFalse(elsewhere.has("default", "nKq7"))
        vault.putString("default/nJunk", "not a blob")
        assertNull(tokens.get("default", "nJunk"))
        assertFalse(tokens.has("default", "nJunk"))
    }

    @Test
    fun unpairingRemovesTheTokenAndAPairingReplacesTheOneBefore() {
        tokens.put("default", "nKq7", record)
        tokens.put("default", "nKq7", record.copy(token = "bmV3", owner = false))
        assertEquals("bmV3", tokens.get("default", "nKq7")?.token)
        assertTrue(tokens.remove("default", "nKq7"))
        assertFalse(tokens.has("default", "nKq7"))
        assertTrue(vault.keys().isEmpty())
    }

    @Test
    fun aDeletedAccountTakesItsTokensAndNoOthers() {
        tokens.put("work", "nKq7", record)
        tokens.put("work", "nB7", record)
        tokens.put("workshop", "nKq7", record)
        tokens.removeProfile("work")
        assertEquals(setOf("workshop/nKq7"), vault.keys())
    }

    @Test
    fun whatCannotBeAKeyOrCannotBeSealedIsNotStored() {
        assertFalse(tokens.put("default", "a/b", record))
        assertFalse(tokens.put("default", "", record))
        assertFalse(tokens.put("default", "nKq7", record.copy(token = "")))
        box.failSeal = true
        assertFalse(tokens.put("default", "nKq7", record))
        assertTrue(vault.keys().isEmpty())
    }

    @Test
    fun theFileStoreKeepsEntriesAcrossInstancesAndLeavesNoEmptyFile() {
        val file = File(tmp.root, "remote/tokens.sealed")
        val store = FileKeyValueStore(file)
        assertNull(store.getString("a"))
        assertTrue(store.putString("default/nKq7", "v1:aa:bb"))
        assertTrue(store.putString("work/nB", "v1:cc:dd"))
        val again = FileKeyValueStore(file)
        assertEquals("v1:aa:bb", again.getString("default/nKq7"))
        assertEquals(setOf("default/nKq7", "work/nB"), again.keys())
        assertTrue(again.putString("default/nKq7", null))
        assertNull(store.getString("default/nKq7"))
        assertTrue(store.putString("work/nB", null))
        assertFalse(file.exists())
        // A damaged file is an empty store, and the next write replaces it.
        file.parentFile!!.mkdirs()
        file.writeText("{ not json")
        assertNull(store.getString("work/nB"))
        assertTrue(store.putString("k", "v"))
        assertEquals("v", FileKeyValueStore(file).getString("k"))
        // On a real store the sealed tokens survive a new instance too.
        val sealed = RemoteTokenStore(FileKeyValueStore(file), box)
        assertTrue(sealed.put("default", "nKq7", record))
        assertEquals(record, RemoteTokenStore(FileKeyValueStore(file), box).get("default", "nKq7"))
    }

    @Test
    fun aDeviceThatNeverPairedSpendsNoKeyAndNoDiskOnLooking() {
        // What a peer's details and a deleted account do on a device that never used remote
        // management: look for a file that is not there. No key is asked for, nothing is written.
        val dir = File(tmp.root, "never")
        val untouched = object : SecretBox {
            override fun seal(plaintext: String, aad: String): String = throw AssertionError("a key was used to seal")
            override fun open(blob: String, aad: String): String = throw AssertionError("a key was used to open")
        }
        val never = RemoteTokenStore(FileKeyValueStore(File(dir, "tokens.sealed")), untouched)
        assertNull(never.get("default", "nKq7"))
        assertFalse(never.has("default", "nKq7"))
        never.removeProfile("default")
        assertFalse(dir.exists())
    }

    // ─── A managing session ──────────────────────────────────────────────────────────────────

    /** A managed device that answers from a script and records what it was sent. */
    private class FakeDevice : RemotePeerApi {
        var pairAnswer = RemotePairAnswer(ok = true, token = "dG9rZW4", role = "guest", left = 86_400, name = "living-room-tv")
        var answer: (path: String) -> RemoteAnswer = { RemoteAnswer(200, "{}") }
        val sent = mutableListOf<String>()

        override fun probe(peerId: String) = RemoteProbe(ok = true, deviceClass = "tv")
        override fun pair(peerId: String, code: String): RemotePairAnswer { sent += "pair $peerId $code"; return pairAnswer }
        override fun get(peerId: String, token: String, path: String): RemoteAnswer { sent += "GET $path $token"; return answer(path) }
        override fun post(peerId: String, token: String, path: String, body: String): RemoteAnswer {
            sent += "POST $path $token $body"
            return answer(path)
        }
        override fun parseQr(text: String): RemoteQr? = null
    }

    private var clock = 1_791_813_780_000L
    private val device = FakeDevice()
    private val session = RemoteSession(device, tokens, "default", "nKq7", now = { clock })

    @Test
    fun pairingKeepsTheTokenAndWhatThePairingIs() {
        assertNull(session.pairing())
        assertTrue(session.pair("483902", tv = true).ok)
        val kept = session.pairing()!!
        assertEquals("dG9rZW4", kept.token)
        assertFalse(kept.owner)
        assertTrue(kept.tv)
        assertEquals(clock + 86_400_000, kept.expiresAt)

        device.pairAnswer = device.pairAnswer.copy(token = "b3duZXI", role = "owner", left = -1)
        assertTrue(session.pair("111111").ok)
        assertTrue(session.pairing()!!.owner)
        assertEquals(0L, session.pairing()!!.expiresAt)
        assertEquals("b3duZXI", session.pairing()!!.token)
    }

    @Test
    fun whatIsNotACodeIsNeverSentAndAFailedPairingStoresNothing() {
        assertEquals("bad_request", session.pair("48390").error)
        assertEquals("bad_request", session.pair("abcdef").error)
        assertTrue(device.sent.isEmpty())
        device.pairAnswer = RemotePairAnswer(error = "wrong", attemptsLeft = 4, status = 403)
        assertEquals(RemotePairFailure.WRONG, session.pair("000000").failure())
        assertNull(session.pairing())
    }

    @Test
    fun aPairingThatCannotBeKeptIsGivenBack() {
        box.failSeal = true
        val answer = session.pair("483902")
        assertFalse(answer.ok)
        assertEquals(RemotePairFailure.NOT_STORED, answer.failure())
        assertNull(session.pairing())
        // The device was told at once, with the token it had just issued.
        assertEquals("POST /v1/unpair dG9rZW4 ", device.sent.last())
    }

    @Test
    fun requestsCarryTheTokenAndTheirBodies() {
        session.pair("483902")
        device.sent.clear()
        device.answer = { path ->
            when (path) {
                RemotePaths.STATUS -> RemoteAnswer(200, """{"running":true,"backendState":"Running","you":{"class":"guest","left":86000}}""")
                RemotePaths.EXIT_NODES -> RemoteAnswer(200, """{"nodes":[{"id":"nF","name":"exit-frankfurt","online":true}]}""")
                else -> RemoteAnswer(200, "{}")
            }
        }
        assertTrue((session.status() as RemoteResult.Ok).value.running)
        assertEquals("exit-frankfurt", (session.exitNodes() as RemoteResult.Ok).value.nodes.single().name)
        assertTrue(session.setExitNode("nF") is RemoteResult.Ok)
        assertTrue(session.setSwitch(RemoteKeys.ACCEPT_ROUTES, true) is RemoteResult.Ok)
        assertTrue(session.reconnect() is RemoteResult.Ok)
        assertTrue(session.deleteFile("a.pdf") is RemoteResult.Ok)
        assertEquals(
            listOf(
                "GET /v1/status dG9rZW4",
                "GET /v1/exit-nodes dG9rZW4",
                """POST /v1/exit-node dG9rZW4 {"id":"nF"}""",
                """POST /v1/settings dG9rZW4 {"key":"accept_routes","value":"true"}""",
                """POST /v1/actions dG9rZW4 {"action":"reconnect"}""",
                """POST /v1/files/delete dG9rZW4 {"name":"a.pdf"}""",
            ),
            device.sent
        )
    }

    @Test
    fun withoutAPairingNothingIsSent() {
        assertEquals(RemoteFailure.UNAUTHORIZED, (session.status() as RemoteResult.Failed).failure)
        assertEquals(RemoteFailure.UNAUTHORIZED, (session.reconnect() as RemoteResult.Failed).failure)
        assertTrue(device.sent.isEmpty())
    }

    @Test
    fun aRefusedTokenIsForgottenAndARevocationIsToldFromARunOutGuest() {
        session.pair("483902")
        device.answer = { RemoteAnswer(401, """{"error":"unauthorized"}""") }
        // A day has not passed: somebody revoked it.
        clock += 3_600_000
        assertEquals(RemoteFailure.UNAUTHORIZED, (session.status() as RemoteResult.Failed).failure)
        assertNull(session.pairing())

        session.pairAgain()
        clock += 86_400_000 + 1
        assertEquals(RemoteFailure.EXPIRED, (session.setSwitch(RemoteKeys.ACCEPT_DNS, true) as RemoteResult.Failed).failure)
        assertNull(session.pairing())

        // An owner's pairing has no term: refused is revoked, however long ago it was made.
        device.pairAnswer = device.pairAnswer.copy(role = "owner", left = -1)
        session.pairAgain()
        clock += 400 * 86_400_000L
        assertEquals(RemoteFailure.UNAUTHORIZED, (session.files() as RemoteResult.Failed).failure)
    }

    private fun RemoteSession.pairAgain() {
        val answer = device.answer
        device.answer = { RemoteAnswer(200, "{}") }
        assertTrue(pair("483902").ok)
        device.answer = answer
    }

    @Test
    fun otherFailuresKeepTheToken() {
        session.pair("483902")
        for (failing in listOf(RemoteAnswer(0, error = "unreachable"), RemoteAnswer(403), RemoteAnswer(503), RemoteAnswer(504), RemoteAnswer(429))) {
            device.answer = { failing }
            assertTrue(session.status() is RemoteResult.Failed)
            assertNotNull(session.pairing())
        }
    }

    @Test
    fun forgettingDropsTheTokenWhateverTheDeviceAnswers() {
        session.pair("483902")
        device.answer = { RemoteAnswer(0, error = "unreachable", message = "no answer in time") }
        // The device was not told, and the result says so; the token is gone all the same.
        assertEquals(RemoteFailure.UNREACHABLE, (session.unpair() as RemoteResult.Failed).failure)
        assertNull(session.pairing())
        assertTrue(device.sent.last().startsWith("POST /v1/unpair dG9rZW4"))

        session.pairAgain()
        device.answer = { RemoteAnswer(200, "{}") }
        assertTrue(session.unpair() is RemoteResult.Ok)
        assertNull(session.pairing())

        // Gone there already: that is what was asked for.
        session.pairAgain()
        device.answer = { RemoteAnswer(401) }
        assertTrue(session.unpair() is RemoteResult.Ok)

        // Nothing to forget, nothing sent.
        device.sent.clear()
        assertTrue(session.unpair() is RemoteResult.Ok)
        assertTrue(device.sent.isEmpty())
    }
}
