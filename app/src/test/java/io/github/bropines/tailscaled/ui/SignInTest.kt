package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.HealthWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInTest {
    @Test
    fun theLinkIsShownWithoutItsScheme() {
        assertEquals("login.tailscale.com/a/1a2b3c4d5e6f7", signInLinkText("https://login.tailscale.com/a/1a2b3c4d5e6f7"))
        // A server of one's own, as Headscale prints it — plain http on a home network included.
        assertEquals(
            "hs.example.net/register/hskey-authreq-AbCd",
            signInLinkText("https://hs.example.net/register/hskey-authreq-AbCd")
        )
        assertEquals("192.168.1.5:8080/register/x", signInLinkText("http://192.168.1.5:8080/register/x"))
        // Nothing to cut: shown as it came.
        assertEquals("login.tailscale.com/a/1", signInLinkText("login.tailscale.com/a/1"))
    }

    @Test
    fun theServerIsTheLinksHost() {
        assertEquals("login.tailscale.com", signInServer("https://login.tailscale.com/a/1a2b3c4d5e6f7"))
        assertEquals("hs.example.net", signInServer("https://hs.example.net:8443/register/hskey-authreq-AbCd"))
        assertEquals("192.168.1.5", signInServer("http://192.168.1.5:8080/register/x"))
    }

    private fun warning(text: String, code: String = "login-state") = HealthWarning(code = code, text = text)

    /** The daemon's own words, as `tailscale status` printed them for a key the server refused. */
    @Test
    fun aRefusedKeyIsReadFromTheDaemonsLoggedOutWarning() {
        val refused = keyRefusal(
            listOf(warning("You are logged out. The last login error was: invalid pre auth key")),
            hasKey = true
        )
        assertEquals(KeyRefusal("invalid pre auth key"), refused)
    }

    @Test
    fun onlyTheFirstLineOfTheReasonIsKeptAndItsFullStop() {
        val refused = keyRefusal(listOf(warning("You are logged out. The last login error was: key expired.\nLog in again.")), hasKey = true)
        assertEquals("key expired", refused?.reason)
    }

    @Test
    fun aSignInThatWasNeverTriedWithAKeyHasNoRefusal() {
        assertNull(keyRefusal(listOf(warning("You are logged out. The last login error was: invalid pre auth key")), hasKey = false))
    }

    @Test
    fun withoutAnErrorFromTheDaemonThereIsNothingToSay() {
        assertNull(keyRefusal(emptyList(), hasKey = true))
        assertNull(keyRefusal(listOf(warning("You are logged out. Log in again.")), hasKey = true))
        assertNull(keyRefusal(listOf(warning("Tailscale could not connect to the relay", code = "no-derp-home")), hasKey = true))
    }

    @Test
    fun aLinkThatNamesNoServerHasNone() {
        assertNull(signInServer(null))
        assertNull(signInServer(""))
        assertNull(signInServer("not a link"))
        assertNull(signInServer("login.tailscale.com/a/1"))
    }
}
