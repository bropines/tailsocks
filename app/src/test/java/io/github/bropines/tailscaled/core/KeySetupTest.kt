package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge's JSON (appctr/setup.go, setup_send.go) read into what the two screens show, and the phone's checks. */
class KeySetupTest {
    // A code as a television draws it now, with one address, and as one drew it before the relay.
    private val code = "TSP2:" + "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(3).take(84)
    private val firstCode = "TSP1:" + code.drop(5)

    // The bridge writes every field every time; these are its answers as they come.
    private fun rx(
        state: String,
        reason: String = "",
        relay: String = "on",
        payload: String = "",
        addresses: String = "[]",
        port: Int = 0,
        expiresIn: Int = 0,
        failed: Int = 0,
        via: String = "",
        from: String = "",
        name: String = "",
        check: String = "",
        prefix: String = "",
        server: String = "",
    ) = """{"state":"$state","reason":"$reason","error":"","id":"K7Q2M","relay":"$relay","payload":"$payload","addresses":$addresses,""" +
        """"port":$port,"failedAttempts":$failed,"expiresIn":$expiresIn,"via":"$via","from":"$from","senderName":"$name",""" +
        """"code":"$check","keyPrefix":"$prefix","loginServer":"$server"}"""

    // The same from a bridge built before the relay: no "relay", no "via", and a sender always has an address.
    private fun rxBefore(
        state: String,
        payload: String = "",
        addresses: String = "[]",
        expiresIn: Int = 0,
        from: String = "",
        name: String = "",
        check: String = "",
        prefix: String = "",
    ) = """{"state":"$state","reason":"","error":"","id":"K7Q2M","payload":"$payload","addresses":$addresses,""" +
        """"port":41234,"failedAttempts":0,"expiresIn":$expiresIn,"from":"$from","senderName":"$name",""" +
        """"code":"$check","keyPrefix":"$prefix","loginServer":""}"""

    private fun step(json: String, handover: HandoverPhase = HandoverPhase.IDLE) = tvKeyStep(SetupReceiveStatus.parse(json), handover)

    // ─── The television ─────────────────────────────────────────────────────

    @Test
    fun aWaitingSessionShowsItsCode() {
        val shown = step(rx("waiting", payload = code, addresses = """["192.168.1.50","10.0.0.7"]""", port = 51234, expiresIn = 580, failed = 2))
        assertEquals(
            TvKeyStep.Code(code, "K7Q2M", listOf("192.168.1.50", "10.0.0.7"), secondsLeft = 580, refused = 2, relay = true),
            shown
        )
    }

    /** The relay was not to be had: the code is drawn all the same, for the television's own network. */
    @Test
    fun aCodeWithoutTheRelayIsForTheLocalNetworkAlone() {
        val shown = step(rx("waiting", relay = "unavailable", payload = code, addresses = """["192.168.1.50"]""", port = 51234, expiresIn = 600))
        assertEquals(TvKeyStep.Code(code, "K7Q2M", listOf("192.168.1.50"), secondsLeft = 600, refused = 0, relay = false), shown)
    }

    /** No address on any local network, no port: the relay alone leads to the television, and that is a code like any other. */
    @Test
    fun aTelevisionWithNoLocalAddressIsStillOffered() {
        val shown = step(rx("waiting", payload = code, expiresIn = 600))
        assertEquals(TvKeyStep.Code(code, "K7Q2M", emptyList(), secondsLeft = 600, refused = 0, relay = true), shown)
    }

    /** While the relay is asked for there is a session and no code: the screen waits, and no second session is opened. */
    @Test
    fun aSessionThatAsksForTheRelayIsStillOpening() {
        val preparing = rx("preparing", relay = "starting")
        assertEquals(TvKeyStep.Opening, step(preparing))
        assertFalse(SetupReceiveStatus.parse(preparing).vacant)
    }

    @Test
    fun aHeldBundleIsAskedAboutWithoutItsKey() {
        val shown = step(
            rx(
                "received", expiresIn = 171, via = "lan", from = "192.168.1.23", name = "pixel-9", check = "527184",
                prefix = "tskey-auth", server = "https://hs.example.com",
            )
        )
        assertEquals(
            TvKeyStep.Offer("pixel-9", "192.168.1.23", "527184", KeyKind.TAILSCALE_AUTH, "https://hs.example.com", secondsLeft = 171, road = KeyRoad.LAN),
            shown
        )
    }

    /** Through the relay a sender has no address: the road is what the screen names in its place. */
    @Test
    fun aBundleThatCameThroughTheRelayHasNoAddress() {
        val shown = step(rx("received", expiresIn = 180, via = "relay", name = "pixel-9", check = "527184", prefix = "hskey-auth"))
        assertEquals(TvKeyStep.Offer("pixel-9", "", "527184", KeyKind.HEADSCALE_AUTH, "", secondsLeft = 180, road = KeyRoad.RELAY), shown)
    }

    @Test
    fun aKeyOfNoKnownKindIsStillAskedAbout() {
        val shown = step(rx("received", via = "lan", from = "192.168.1.23", check = "000042")) as TvKeyStep.Offer
        assertEquals(KeyKind.OTHER, shown.keyKind)
        assertEquals("", shown.server)
    }

    @Test
    fun noSessionYetIsOpening() {
        assertEquals(TvKeyStep.Opening, tvKeyStep(null, HandoverPhase.IDLE))
        assertEquals(TvKeyStep.Opening, step("""{"state":"idle","addresses":[]}"""))
        // A session whose key was taken is over; the next one is opened in its place.
        assertEquals(TvKeyStep.Opening, step(rx("released", via = "lan", from = "192.168.1.23")))
        assertTrue(SetupReceiveStatus.parse(rx("released")).vacant)
        assertTrue(SetupReceiveStatus().vacant)
        assertFalse(SetupReceiveStatus.parse(rx("rejected")).vacant)
    }

    /** A bridge built before the relay says nothing of it, and means what it always did: the local network alone. */
    @Test
    fun anAnswerFromBeforeTheRelayMeansTheLocalNetwork() {
        assertEquals(
            TvKeyStep.Code(firstCode, "K7Q2M", listOf("192.168.1.50"), secondsLeft = 580, refused = 0, relay = false),
            step(rxBefore("waiting", payload = firstCode, addresses = """["192.168.1.50"]""", expiresIn = 580))
        )
        assertEquals(
            TvKeyStep.Offer("pixel-9", "192.168.1.23", "527184", KeyKind.TAILSCALE_AUTH, "", secondsLeft = 171, road = KeyRoad.LAN),
            step(rxBefore("received", expiresIn = 171, from = "192.168.1.23", name = "pixel-9", check = "527184", prefix = "tskey-auth"))
        )
        assertEquals(TvKeyStep.Stopped(TvKeyStop.CODE_EXPIRED), step(rxBefore("expired")))
        assertEquals(TvKeyStep.Stopped(TvKeyStop.NOT_ANSWERED), step(rxBefore("expired", from = "192.168.1.23", check = "527184")))
    }

    @Test
    fun aConfirmedKeyIsOnItsWay() {
        assertEquals(TvKeyStep.SigningIn, step(rx("confirmed", expiresIn = 30, from = "192.168.1.23")))
    }

    @Test
    fun everyWayASessionEndsHasItsOwnWords() {
        val reasons = mapOf(
            "no_lan_address" to TvKeyStop.NO_NETWORK,
            "listen_failed" to TvKeyStop.CANNOT_LISTEN,
            "too_many_attempts" to TvKeyStop.TOO_MANY_ATTEMPTS,
            "network_changed" to TvKeyStop.NETWORK_CHANGED,
            "daemon_stopped" to TvKeyStop.SERVICE_STOPPED,
            "not_taken" to TvKeyStop.KEY_GONE,
            "something_new" to TvKeyStop.UNKNOWN,
        )
        for ((reason, stop) in reasons) assertEquals(reason, TvKeyStep.Stopped(stop), step(rx("failed", reason = reason)))
        assertEquals(TvKeyStep.Stopped(TvKeyStop.REJECTED), step(rx("rejected", from = "192.168.1.23")))
        assertEquals(TvKeyStep.Stopped(TvKeyStop.UNKNOWN), step(rx("a_state_of_a_later_build")))
    }

    /** "expired" is two things: nobody sent, or somebody did and nobody answered. The bundle left in the status tells them apart. */
    @Test
    fun anExpiredCodeIsNotAnUnansweredKey() {
        assertEquals(TvKeyStep.Stopped(TvKeyStop.CODE_EXPIRED), step(rx("expired")))
        assertEquals(TvKeyStep.Stopped(TvKeyStop.NOT_ANSWERED), step(rx("expired", via = "lan", from = "192.168.1.23", check = "527184")))
        // A bundle that came through the relay left no address behind, and was a bundle all the same.
        assertEquals(TvKeyStep.Stopped(TvKeyStop.NOT_ANSWERED), step(rx("expired", via = "relay", check = "527184")))
    }

    @Test
    fun aWaitingSessionWithoutACodeIsNotDrawn() {
        assertEquals(TvKeyStep.Stopped(TvKeyStop.UNKNOWN), step(rx("waiting")))
    }

    @Test
    fun anAnswerThatCannotBeReadIsAFailure() {
        for (bad in listOf(null, "", "not json", "[]")) {
            assertEquals(bad, TvKeyStep.Stopped(TvKeyStop.UNKNOWN), tvKeyStep(SetupReceiveStatus.parse(bad), HandoverPhase.IDLE))
        }
    }

    /** Once a key is on its way the session that brought it has nothing more to say. */
    @Test
    fun theHandoverOutranksTheSession() {
        val released = rx("released", from = "192.168.1.23")
        assertEquals(TvKeyStep.SigningIn, step(released, HandoverPhase.SIGNING_IN))
        assertEquals(TvKeyStep.SignedIn, step(released, HandoverPhase.SIGNED_IN))
        assertEquals(TvKeyStep.Stopped(TvKeyStop.NOT_SIGNED_IN), step(released, HandoverPhase.FAILED))
        assertEquals(TvKeyStep.SigningIn, tvKeyStep(null, HandoverPhase.SIGNING_IN))
    }

    @Test
    fun theKindIsReadOffThePrefixAndNothingElse() {
        assertEquals(KeyKind.TAILSCALE_AUTH, KeyKind.ofPrefix("tskey-auth"))
        assertEquals(KeyKind.TAILSCALE_CLIENT, KeyKind.ofPrefix("tskey-client"))
        assertEquals(KeyKind.HEADSCALE_AUTH, KeyKind.ofPrefix("hskey-auth"))
        assertEquals(KeyKind.OTHER, KeyKind.ofPrefix(""))
        assertEquals(KeyKind.OTHER, KeyKind.ofPrefix("tskey-api"))
        assertEquals(KeyKind.TAILSCALE_AUTH, KeyKind.of("tskey-auth-kAbCdEf1CNTRL-0123456789abcdef"))
        assertEquals(KeyKind.TAILSCALE_CLIENT, KeyKind.of("tskey-client-kAbCdEf1CNTRL-0123456789abcdef?ephemeral=false"))
        // The prefix alone, or without its dash, is not a key of that kind.
        assertEquals(KeyKind.OTHER, KeyKind.of("tskey-auth"))
        assertEquals(KeyKind.OTHER, KeyKind.of("tskey-authentic"))
        assertEquals(KeyKind.OTHER, KeyKind.of(""))
    }

    // ─── The key, once it is on the television ──────────────────────────────

    @Test
    fun signedInCountsOnlyAfterTheDaemonWasSeenNotToBe() {
        // The first look finds the old daemon still up and signed in: not yet.
        var look = handoverLook(armed = false, daemonAlive = true, backendState = "Running")
        assertEquals(HandoverLook(armed = false, signedIn = false), look)
        // Down for the restart: armed.
        look = handoverLook(look.armed, daemonAlive = false, backendState = "")
        assertEquals(HandoverLook(armed = true, signedIn = false), look)
        look = handoverLook(look.armed, daemonAlive = true, backendState = "Starting")
        assertEquals(HandoverLook(armed = true, signedIn = false), look)
        look = handoverLook(look.armed, daemonAlive = true, backendState = "Running")
        assertEquals(HandoverLook(armed = true, signedIn = true), look)
    }

    @Test
    fun aDeviceThatWasSignedOutArmsAtOnce() {
        for (state in listOf("NeedsLogin", "NoState", "Stopped", "Starting", "")) {
            assertEquals(state, HandoverLook(armed = true, signedIn = false), handoverLook(false, daemonAlive = true, backendState = state))
        }
    }

    @Test
    fun waitingForAnAdminsApprovalIsSignedInAsFarAsTheKeyGoes() {
        assertTrue(handoverLook(armed = true, daemonAlive = true, backendState = "NeedsMachineAuth").signedIn)
        assertFalse(handoverLook(armed = true, daemonAlive = true, backendState = "NeedsLogin").signedIn)
        // A state read off a daemon that is not there means nothing.
        assertFalse(handoverLook(armed = true, daemonAlive = false, backendState = "Running").signedIn)
    }

    // ─── The phone ──────────────────────────────────────────────────────────

    @Test
    fun aSetupCodeIsKnownByItsShape() {
        // The scanner keeps both versions to itself: the one televisions draw now, and the first.
        for (known in listOf(code, firstCode)) {
            assertTrue(known, looksLikeSetupCode(known))
            assertTrue(known, looksLikeSetupCode("  ${known.lowercase()}\n"))
        }
        // The shortest code there is, for the relay alone, and the longest, with three addresses.
        assertTrue(looksLikeSetupCode("TSP2:" + "7".repeat(78)))
        assertTrue(looksLikeSetupCode("TSP2:" + "Z".repeat(96)))
        // A version this build does not read is still the scanner's to answer.
        assertTrue(looksLikeSetupCode("TSP3:ABC123"))
        assertTrue(looksLikeSetupCode("TSP12:ABC123"))
        for (other in listOf(
            "", "TSP1:", "TSP2:", "TSP:ABC", "TSP1 ABC", "TSP2 ABC", "TSP1:ABC DEF", "TSP2:ABC-DEF", "XTSP1:ABC", "XTSP2:ABC",
            "https://example.com/TSP1:ABC", "https://example.com/TSP2:ABC", "tailsocks://scan",
            "tskey-auth-kAbCdEf1CNTRL-0123456789abcdef", "TSP1234:ABC", "TSP1:" + "A".repeat(161), "TSP2:" + "A".repeat(161),
        )) assertFalse(other, looksLikeSetupCode(other))
    }

    @Test
    fun aCodeThatParsesOpensTheForm() {
        val target = SetupTarget.parse("""{"ok":true,"error":"","id":"K7Q2M","addresses":["192.168.1.50"],"port":51234,"relay":true}""")
        assertEquals(SetupTarget(ok = true, id = "K7Q2M", addresses = listOf("192.168.1.50"), port = 51234, relay = true), target)
        assertNull(KeySendEnd.ofTarget(target))
    }

    /** A television with no local address draws a code for the relay alone: no addresses, no port, and somewhere to send all the same. */
    @Test
    fun aCodeForTheRelayAloneOpensTheForm() {
        val target = SetupTarget.parse("""{"ok":true,"error":"","id":"K7Q2M","addresses":[],"port":0,"relay":true}""")
        assertEquals(SetupTarget(ok = true, id = "K7Q2M", relay = true), target)
        assertNull(KeySendEnd.ofTarget(target))
    }

    /** A first-version code, or any code read by a bridge built before the relay: addresses and nothing said of a relay. */
    @Test
    fun aCodeWithoutARelayOpensTheFormForTheLocalNetwork() {
        val target = SetupTarget.parse("""{"ok":true,"error":"","id":"K7Q2M","addresses":["192.168.1.50"],"port":41234}""")
        assertEquals(SetupTarget(ok = true, id = "K7Q2M", addresses = listOf("192.168.1.50"), port = 41234, relay = false), target)
        assertNull(KeySendEnd.ofTarget(target))
        assertNull(KeySendEnd.ofTarget(SetupTarget.parse("""{"ok":true,"error":"","id":"K7Q2M","addresses":["192.168.1.50"],"port":41234,"relay":false}""")))
    }

    @Test
    fun aCodeThatDoesNotSaysWhy() {
        fun refused(error: String) =
            KeySendEnd.ofTarget(SetupTarget.parse("""{"ok":false,"error":"$error","id":"","addresses":[],"port":0,"relay":false}"""))
        assertEquals(KeySendEnd.NEWER_CODE, refused("unsupported_version"))
        assertEquals(KeySendEnd.INVALID_CODE, refused("malformed"))
        assertEquals(KeySendEnd.INVALID_CODE, refused("not_a_setup_code"))
        assertEquals(KeySendEnd.INVALID_CODE, KeySendEnd.ofTarget(SetupTarget.parse("garbage")))
        // "ok" with nowhere to send to — no address and no relay — is not ok.
        assertEquals(KeySendEnd.INVALID_CODE, KeySendEnd.ofTarget(SetupTarget(ok = true, id = "K7Q2M")))
        // A relay does not make a code that was refused a good one.
        assertEquals(KeySendEnd.INVALID_CODE, KeySendEnd.ofTarget(SetupTarget(ok = false, error = "malformed", relay = true)))
    }

    private fun tx(state: String, code: String = "", address: String = "", path: String = "") =
        keySendStep(SetupSendStatus.parse("""{"state":"$state","path":"$path","code":"$code","address":"$address"}"""))

    // The same from a bridge built before the relay: it names no road.
    private fun txBefore(state: String, code: String = "", address: String = "") =
        keySendStep(SetupSendStatus.parse("""{"state":"$state","code":"$code","address":"$address"}"""))

    @Test
    fun aSendIsFollowedToTheCheckCode() {
        assertEquals(KeySendStep.Form, tx("idle"))
        assertEquals(
            KeySendStep.Compare("527184", "192.168.1.50:51234", KeyRoad.LAN),
            tx("awaiting", "527184", "192.168.1.50:51234", path = "lan")
        )
        // Through the relay there is no address, and the road is what is left to show.
        assertEquals(KeySendStep.Compare("527184", "", KeyRoad.RELAY), tx("awaiting", "527184", path = "relay"))
    }

    /** A send can take half a minute: the sheet says which road is being tried, once the bridge has named one. */
    @Test
    fun aSendOnItsWaySaysWhichRoadItIsOn() {
        assertEquals(KeySendStep.Sending(null), tx("sending"))
        assertEquals(KeySendStep.Sending(KeyRoad.LAN), tx("sending", path = "lan"))
        assertEquals(KeySendStep.Sending(KeyRoad.RELAY), tx("sending", path = "relay"))
        assertEquals(KeySendStep.Sending(null), tx("sending", path = "a_road_of_a_later_build"))
    }

    @Test
    fun aSendFromBeforeTheRelayWentOverTheLocalNetwork() {
        assertEquals(KeySendStep.Form, txBefore("idle"))
        assertEquals(KeySendStep.Sending(null), txBefore("sending"))
        assertEquals(KeySendStep.Compare("527184", "192.168.1.50:41234", KeyRoad.LAN), txBefore("awaiting", "527184", "192.168.1.50:41234"))
        assertEquals(KeySendStep.Ended(KeySendEnd.WRONG_NETWORK), txBefore("wrong_network"))
        assertEquals(KeySendStep.Ended(KeySendEnd.REJECTED, "192.168.1.50:41234"), txBefore("rejected", address = "192.168.1.50:41234"))
    }

    @Test
    fun theRoadIsReadOffItsName() {
        assertEquals(KeyRoad.LAN, KeyRoad.of("lan"))
        assertEquals(KeyRoad.RELAY, KeyRoad.of("relay"))
        assertNull(KeyRoad.of(""))
        assertNull(KeyRoad.of("LAN"))
    }

    @Test
    fun everyEndOfASendIsNamedAndSaysWhatToDoNext() {
        val ends = mapOf(
            "confirmed" to (KeySendEnd.CONFIRMED to KeySendNext.DONE),
            "rejected" to (KeySendEnd.REJECTED to KeySendNext.RESCAN),
            "expired" to (KeySendEnd.EXPIRED to KeySendNext.RESCAN),
            "cancelled" to (KeySendEnd.CANCELLED to KeySendNext.RESCAN),
            "lost" to (KeySendEnd.LOST to KeySendNext.CLOSE),
            "taken" to (KeySendEnd.TAKEN to KeySendNext.RESCAN),
            "unsupported" to (KeySendEnd.UNSUPPORTED to KeySendNext.CLOSE),
            "closed" to (KeySendEnd.CLOSED to KeySendNext.RESCAN),
            "unreachable" to (KeySendEnd.UNREACHABLE to KeySendNext.RESEND),
            "wrong_network" to (KeySendEnd.WRONG_NETWORK to KeySendNext.RESEND),
            "relay_unavailable" to (KeySendEnd.RELAY_UNAVAILABLE to KeySendNext.RESEND),
            "no_answer" to (KeySendEnd.NO_ANSWER to KeySendNext.RESEND),
            "invalid_payload" to (KeySendEnd.INVALID_CODE to KeySendNext.RESCAN),
            "invalid_key" to (KeySendEnd.INVALID_KEY to KeySendNext.EDIT),
            "invalid_server" to (KeySendEnd.INVALID_SERVER to KeySendNext.EDIT),
            "busy" to (KeySendEnd.BUSY to KeySendNext.RESEND),
            "a_state_of_a_later_build" to (KeySendEnd.UNKNOWN to KeySendNext.CLOSE),
        )
        for ((state, expected) in ends) {
            val ended = tx(state, address = "192.168.1.50:51234", path = "lan") as KeySendStep.Ended
            assertEquals(state, expected.first, ended.how)
            assertEquals(state, expected.second, ended.how.next)
            assertEquals("192.168.1.50:51234", ended.address)
            // By the relay it ends the same way, with no address to show for it.
            assertEquals(state, KeySendStep.Ended(expected.first), tx(state, path = "relay"))
        }
        // Every end the sheet has words for is one the bridge can report, or a code that never got as far as a send.
        assertEquals(KeySendEnd.entries.toSet() - KeySendEnd.NEWER_CODE, ends.values.map { it.first }.toSet())
        assertEquals(KeySendStep.Ended(KeySendEnd.UNKNOWN), keySendStep(SetupSendStatus.parse(null)))
    }

    /** No road led to the television: these are the ends that say something about the two devices' networks. */
    @Test
    fun theEndsWithNoRoadToTheTelevisionAreAboutTheNetwork() {
        assertEquals(
            listOf(KeySendEnd.UNREACHABLE, KeySendEnd.WRONG_NETWORK, KeySendEnd.RELAY_UNAVAILABLE),
            KeySendEnd.entries.filter { it.network }
        )
    }

    @Test
    fun theKeyFieldAcceptsWhatTheBridgeAccepts() {
        assertEquals(KeyField.EMPTY, KeyField.of(""))
        assertEquals(KeyField.EMPTY, KeyField.of("  \n"))
        assertEquals(KeyField.OK, KeyField.of("tskey-auth-kAbCdEf1CNTRL-0123456789abcdef"))
        // A paste brings a line break with it; the bridge trims too.
        assertEquals(KeyField.OK, KeyField.of(" tskey-auth-kAbCdEf1CNTRL-0123456789abcdef\n"))
        assertEquals(KeyField.OK, KeyField.of("tskey-client-kAbCdEf1CNTRL-0123456789abcdef?ephemeral=false&preauthorized=true"))
        assertEquals(KeyField.OK, KeyField.of("hskey-auth-0123456789ab-0123456789abcdef"))
        // An older Headscale key is forty-eight hex digits and nothing else.
        assertEquals(KeyField.UNKNOWN_KIND, KeyField.of("0123456789abcdef0123456789abcdef0123456789abcdef"))
        assertEquals(KeyField.API_TOKEN, KeyField.of("tskey-api-kAbCdEf1CNTRL-0123456789abcdef"))
        assertEquals(KeyField.BAD_CHARACTERS, KeyField.of("tskey-auth-kAbCdEf1CNTRL 0123456789abcdef"))
        assertEquals(KeyField.BAD_CHARACTERS, KeyField.of("tskey-auth-kAbCdEf1CNTRL\n0123456789abcdef"))
        assertEquals(KeyField.BAD_CHARACTERS, KeyField.of("tskey-auth-ключ"))
        assertEquals(KeyField.BAD_CHARACTERS, KeyField.of("tskey-auth-k\u0000"))
        assertEquals(KeyField.OK, KeyField.of("tskey-auth-" + "a".repeat(KeyField.MAX_LENGTH - 11)))
        assertEquals(KeyField.TOO_LONG, KeyField.of("tskey-auth-" + "a".repeat(KeyField.MAX_LENGTH - 10)))
        assertEquals(setOf(KeyField.OK, KeyField.UNKNOWN_KIND), KeyField.entries.filter { it.sendable }.toSet())
    }

    @Test
    fun anEnteredKeyIsShownByItsKindOnly() {
        val key = "tskey-auth-kAbCdEf1CNTRL-0123456789abcdefSECRET"
        assertEquals("tskey-auth-…", maskAuthKey(key))
        assertEquals("tskey-auth-…", maskAuthKey("  $key\n"))
        assertEquals("tskey-client-…", maskAuthKey("tskey-client-kAbCdEf1CNTRL-0123456789abcdef"))
        assertEquals("hskey-auth-…", maskAuthKey("hskey-auth-0123456789ab-0123456789abcdef"))
        // No known kind: nothing of the key at all.
        assertEquals("…", maskAuthKey("0123456789abcdef0123456789abcdef0123456789abcdef"))
        assertEquals("…", maskAuthKey("tskey-api-kAbCdEf1CNTRL-0123456789abcdef"))
        assertEquals("…", maskAuthKey(""))
        // Whatever the key, nothing past its kind gets through.
        for (k in listOf(key, "hskey-auth-0123456789ab-SECRETSECRET", "plainSECRETkey")) {
            val shown = maskAuthKey(k)
            assertFalse(shown, shown.contains("SECRET") || shown.contains("kAbC") || shown.contains("0123"))
        }
    }

    @Test
    fun theServerFieldAcceptsWhatTheBridgeAccepts() {
        assertEquals(ServerField.EMPTY, ServerField.of(""))
        assertEquals(ServerField.EMPTY, ServerField.of("   "))
        for (ok in listOf(
            "https://hs.example.com", "http://192.168.1.10:8080", "https://hs.example.com/", "https://hs.example.com/headscale",
            "HTTPS://hs.example.com", " https://hs.example.com ", "hs.example.com", "hs.example.com:8443",
        )) assertEquals(ok, ServerField.OK, ServerField.of(ok))
        for (bad in listOf(
            "ftp://hs.example.com", "https://", "https:///path", "https://user:pass@hs.example.com", "https://hs.example.com/?a=b",
            "https://hs.example.com/#top", "https://hs example.com", "https://пример.рф", "mailto:admin@example.com",
            "https://hs.example.com/" + "a".repeat(ServerField.MAX_LENGTH),
        )) assertEquals(bad, ServerField.BAD, ServerField.of(bad))
        assertFalse(ServerField.BAD.sendable)
        assertTrue(ServerField.EMPTY.sendable && ServerField.OK.sendable)
    }

    @Test
    fun aServerTypedWithoutASchemeIsSentAsHttps() {
        assertEquals("", normalizeLoginServer("  "))
        assertEquals("https://hs.example.com", normalizeLoginServer(" hs.example.com "))
        assertEquals("https://hs.example.com:8443", normalizeLoginServer("hs.example.com:8443"))
        assertEquals("http://192.168.1.10:8080", normalizeLoginServer("http://192.168.1.10:8080"))
        assertEquals("https://hs.example.com", normalizeLoginServer("https://hs.example.com"))
    }

    // ─── Both ───────────────────────────────────────────────────────────────

    @Test
    fun theCheckCodeIsReadInTwoGroups() {
        assertEquals("527 184", formatCheckCode("527184"))
        assertEquals("000 042", formatCheckCode("000042"))
        // Anything else is shown as it came.
        assertEquals("5271", formatCheckCode("5271"))
        assertEquals("", formatCheckCode(""))
        assertEquals("52718x", formatCheckCode("52718x"))
    }

    @Test
    fun theTimeLeftIsMinutesAndSeconds() {
        assertEquals("10:00", formatClock(600))
        assertEquals("9:40", formatClock(580))
        assertEquals("0:05", formatClock(5))
        assertEquals("0:00", formatClock(0))
        assertEquals("0:00", formatClock(-3))
    }
}
