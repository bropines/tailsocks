package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge's JSON (appctr/setup.go, setup_send.go) read into what the two screens show, and the phone's checks. */
class KeySetupTest {
    private val code = "TSP1:" + "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(3).take(84)

    // The bridge writes every field every time; these are its answers as they come.
    private fun rx(
        state: String,
        reason: String = "",
        payload: String = "",
        addresses: String = "[]",
        expiresIn: Int = 0,
        failed: Int = 0,
        from: String = "",
        name: String = "",
        check: String = "",
        prefix: String = "",
        server: String = "",
    ) = """{"state":"$state","reason":"$reason","error":"","id":"K7Q2M","payload":"$payload","addresses":$addresses,""" +
        """"port":41234,"failedAttempts":$failed,"expiresIn":$expiresIn,"from":"$from","senderName":"$name",""" +
        """"code":"$check","keyPrefix":"$prefix","loginServer":"$server"}"""

    private fun step(json: String, handover: HandoverPhase = HandoverPhase.IDLE) = tvKeyStep(SetupReceiveStatus.parse(json), handover)

    // ─── The television ─────────────────────────────────────────────────────

    @Test
    fun aWaitingSessionShowsItsCode() {
        val shown = step(rx("waiting", payload = code, addresses = """["192.168.1.50","10.0.0.7"]""", expiresIn = 580, failed = 2))
        assertEquals(TvKeyStep.Code(code, "K7Q2M", listOf("192.168.1.50", "10.0.0.7"), secondsLeft = 580, refused = 2), shown)
    }

    @Test
    fun aHeldBundleIsAskedAboutWithoutItsKey() {
        val shown = step(
            rx("received", expiresIn = 171, from = "192.168.1.23", name = "pixel-9", check = "527184", prefix = "tskey-auth", server = "https://hs.example.com")
        )
        assertEquals(
            TvKeyStep.Offer("pixel-9", "192.168.1.23", "527184", KeyKind.TAILSCALE_AUTH, "https://hs.example.com", secondsLeft = 171),
            shown
        )
    }

    @Test
    fun aKeyOfNoKnownKindIsStillAskedAbout() {
        val shown = step(rx("received", from = "192.168.1.23", check = "000042")) as TvKeyStep.Offer
        assertEquals(KeyKind.OTHER, shown.keyKind)
        assertEquals("", shown.server)
    }

    @Test
    fun noSessionYetIsOpening() {
        assertEquals(TvKeyStep.Opening, tvKeyStep(null, HandoverPhase.IDLE))
        assertEquals(TvKeyStep.Opening, step("""{"state":"idle","addresses":[]}"""))
        // A session whose key was taken is over; the next one is opened in its place.
        assertEquals(TvKeyStep.Opening, step(rx("released", from = "192.168.1.23")))
        assertTrue(SetupReceiveStatus.parse(rx("released")).vacant)
        assertTrue(SetupReceiveStatus().vacant)
        assertFalse(SetupReceiveStatus.parse(rx("rejected")).vacant)
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

    /** "expired" is two things: nobody sent, or somebody did and nobody answered. The sender tells them apart. */
    @Test
    fun anExpiredCodeIsNotAnUnansweredKey() {
        assertEquals(TvKeyStep.Stopped(TvKeyStop.CODE_EXPIRED), step(rx("expired")))
        assertEquals(TvKeyStep.Stopped(TvKeyStop.NOT_ANSWERED), step(rx("expired", from = "192.168.1.23", check = "527184")))
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
        assertTrue(looksLikeSetupCode(code))
        assertTrue(looksLikeSetupCode("  ${code.lowercase()}\n"))
        // A version this build does not read is still the scanner's to answer.
        assertTrue(looksLikeSetupCode("TSP2:ABC123"))
        assertTrue(looksLikeSetupCode("TSP12:ABC123"))
        for (other in listOf(
            "", "TSP1:", "TSP:ABC", "TSP1 ABC", "TSP1:ABC DEF", "TSP1:ABC-DEF", "XTSP1:ABC", "https://example.com/TSP1:ABC",
            "tailsocks://scan", "tskey-auth-kAbCdEf1CNTRL-0123456789abcdef", "TSP1234:ABC", "TSP1:" + "A".repeat(161),
        )) assertFalse(other, looksLikeSetupCode(other))
    }

    @Test
    fun aCodeThatParsesOpensTheForm() {
        val target = SetupTarget.parse("""{"ok":true,"error":"","id":"K7Q2M","addresses":["192.168.1.50"],"port":41234}""")
        assertEquals(SetupTarget(ok = true, id = "K7Q2M", addresses = listOf("192.168.1.50"), port = 41234), target)
        assertNull(KeySendEnd.ofTarget(target))
    }

    @Test
    fun aCodeThatDoesNotSaysWhy() {
        fun refused(error: String) = KeySendEnd.ofTarget(SetupTarget.parse("""{"ok":false,"error":"$error","id":"","addresses":[],"port":0}"""))
        assertEquals(KeySendEnd.NEWER_CODE, refused("unsupported_version"))
        assertEquals(KeySendEnd.INVALID_CODE, refused("malformed"))
        assertEquals(KeySendEnd.INVALID_CODE, refused("not_a_setup_code"))
        assertEquals(KeySendEnd.INVALID_CODE, KeySendEnd.ofTarget(SetupTarget.parse("garbage")))
        // "ok" with nowhere to send to is not ok.
        assertEquals(KeySendEnd.INVALID_CODE, KeySendEnd.ofTarget(SetupTarget(ok = true, id = "K7Q2M")))
    }

    private fun tx(state: String, code: String = "", address: String = "") =
        keySendStep(SetupSendStatus.parse("""{"state":"$state","code":"$code","address":"$address"}"""))

    @Test
    fun aSendIsFollowedToTheCheckCode() {
        assertEquals(KeySendStep.Form, tx("idle"))
        assertEquals(KeySendStep.Sending, tx("sending"))
        assertEquals(KeySendStep.Compare("527184", "192.168.1.50:41234"), tx("awaiting", "527184", "192.168.1.50:41234"))
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
            "no_answer" to (KeySendEnd.NO_ANSWER to KeySendNext.RESEND),
            "invalid_payload" to (KeySendEnd.INVALID_CODE to KeySendNext.RESCAN),
            "invalid_key" to (KeySendEnd.INVALID_KEY to KeySendNext.EDIT),
            "invalid_server" to (KeySendEnd.INVALID_SERVER to KeySendNext.EDIT),
            "busy" to (KeySendEnd.BUSY to KeySendNext.RESEND),
            "a_state_of_a_later_build" to (KeySendEnd.UNKNOWN to KeySendNext.CLOSE),
        )
        for ((state, expected) in ends) {
            val ended = tx(state, address = "192.168.1.50:41234") as KeySendStep.Ended
            assertEquals(state, expected.first, ended.how)
            assertEquals(state, expected.second, ended.how.next)
            assertEquals("192.168.1.50:41234", ended.address)
        }
        assertEquals(KeySendStep.Ended(KeySendEnd.UNKNOWN), keySendStep(SetupSendStatus.parse(null)))
    }

    @Test
    fun theNetworkHintGoesWithTheTwoEndsItExplains() {
        assertEquals(listOf(KeySendEnd.UNREACHABLE, KeySendEnd.WRONG_NETWORK), KeySendEnd.entries.filter { it.network })
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
