package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TaildriveLanTest {
    /** The proxy as it comes: loopback, no login, a name for one and no password yet. */
    private fun settings(
        bind: String = "127.0.0.1",
        auth: Boolean = false,
        user: String = "tailsocks",
        password: String = "",
        authBeforeLan: Boolean? = null,
    ) = DriveLanSettings(bind, auth, user, password, authBeforeLan)

    private val made = { "made4you" }
    private val unasked: () -> String = { throw AssertionError("a password was made where one was stored") }

    // The switch as a reading of the bind address ----------------------------------------------

    @Test
    fun loopbackIsOff() {
        for (bind in listOf("127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1")) {
            assertEquals(bind, DriveLanState.OFF, DriveLan.stateOf(bind))
        }
    }

    @Test
    fun everyInterfaceIsOn() {
        for (bind in listOf("0.0.0.0", "::", "[::]")) assertEquals(bind, DriveLanState.ON, DriveLan.stateOf(bind))
    }

    @Test
    fun anyOtherAddressIsCustom() {
        for (bind in listOf("192.168.1.77", "10.0.0.5", "100.101.34.12", "fd7a:115c::5", "[fd7a:115c::5]", "nas.local")) {
            assertEquals(bind, DriveLanState.CUSTOM, DriveLan.stateOf(bind))
        }
    }

    @Test
    fun whitespaceAroundAnAddressDoesNotChangeWhatItIs() {
        assertEquals(DriveLanState.OFF, DriveLan.stateOf("  127.0.0.1 "))
        assertEquals(DriveLanState.OFF, DriveLan.stateOf(" localhost\n"))
        assertEquals(DriveLanState.ON, DriveLan.stateOf(" 0.0.0.0 "))
        assertEquals(DriveLanState.ON, DriveLan.stateOf(" [::] "))
        assertEquals(DriveLanState.CUSTOM, DriveLan.stateOf(" 192.168.1.77 "))
        assertEquals("192.168.1.77", DriveLan.host(" 192.168.1.77 "))
        assertEquals("fd7a:115c::5", DriveLan.host("[fd7a:115c::5]"))
    }

    /**
     * A value an older build stored empty: the service starts the proxy on ":port", and that is
     * every interface. It is read as what it does, not as the default the field's hint named.
     */
    @Test
    fun aValueStoredEmptyListensOnEveryInterface() {
        assertEquals(DriveLanState.ON, DriveLan.stateOf(""))
        assertEquals(DriveLanState.ON, DriveLan.stateOf("   "))
        assertTrue(settings(bind = "").openToNetwork)
        // …and the field shows it as that, not as its hint: the hint is the default, loopback.
        assertEquals("0.0.0.0", DriveLan.shown(""))
        assertEquals("192.168.1.77", DriveLan.shown("192.168.1.77"))
        assertEquals("localhost", DriveLan.shown("localhost"))
    }

    // What the field stores ---------------------------------------------------------------------

    /** An emptied field means the default, and the default is this device alone. */
    @Test
    fun nothingTypedIsStoredAsTheDefault() {
        assertEquals("127.0.0.1", DriveLan.stored(""))
        assertEquals("127.0.0.1", DriveLan.stored("  \t"))
        assertEquals(DriveLanState.OFF, DriveLan.bindTo(settings(bind = "0.0.0.0", auth = true, password = "k3Fq9Zx2", authBeforeLan = false), "").state)
    }

    @Test
    fun whatIsTypedIsStoredWithoutTheSpacesAroundIt() {
        assertEquals("192.168.1.77", DriveLan.stored("  192.168.1.77 "))
        assertEquals("localhost", DriveLan.stored("localhost"))
        assertEquals("0.0.0.0", DriveLan.stored("0.0.0.0\n"))
    }

    /** The service joins address and port with a colon: an IPv6 literal has to be in brackets to be one address. */
    @Test
    fun anIpv6LiteralIsStoredInBrackets() {
        assertEquals("[::]", DriveLan.stored("::"))
        assertEquals("[::]", DriveLan.stored(" [::] "))
        assertEquals("[::1]", DriveLan.stored("::1"))
        assertEquals("[fd7a:115c::5]", DriveLan.stored("fd7a:115c::5"))
        // Not an IPv6 literal, whatever else it is: left as typed.
        assertEquals("192.168.1.77:8080", DriveLan.stored("192.168.1.77:8080"))
    }

    // Turning it on ------------------------------------------------------------------------------

    /** One switch may not open a door to every share of the tailnet: on comes with a login, made if there is none. */
    @Test
    fun turningOnBindsEveryInterfaceAndRequiresALogin() {
        val on = DriveLan.allow(settings(), made)
        assertEquals(settings(bind = "0.0.0.0", auth = true, password = "made4you", authBeforeLan = false), on)
        assertEquals(DriveLanState.ON, on.state)
        assertTrue(on.hasLogin)
        assertTrue(on.loginRequired)
        assertFalse(on.openToNetwork)
    }

    @Test
    fun turningOnKeepsTheLoginThatIsThere() {
        val on = DriveLan.allow(settings(auth = true, user = "me", password = "k3Fq9Zx2"), unasked)
        assertEquals(settings(bind = "0.0.0.0", auth = true, user = "me", password = "k3Fq9Zx2", authBeforeLan = true), on)
        // A password from an earlier time the login was on is the existing login too.
        assertEquals("k3Fq9Zx2", DriveLan.allow(settings(auth = false, password = "k3Fq9Zx2"), unasked).password)
    }

    /** With the name emptied the daemon would check nothing, whatever the switch says. */
    @Test
    fun turningOnFillsAnEmptiedUserName() {
        val on = DriveLan.allow(settings(auth = true, user = "", password = "k3Fq9Zx2"), unasked)
        assertEquals("tailsocks", on.user)
        assertTrue(on.hasLogin)
    }

    @Test
    fun whileOnTheLoginCannotBeSwitchedOff() {
        val on = DriveLan.allow(settings(), made)
        assertEquals(on, DriveLan.setAuth(on, false, unasked))
        assertEquals(on, DriveLan.setUser(on, ""))
        assertEquals(on, DriveLan.setPassword(on, ""))
        // Changing the login is not switching it off.
        assertEquals("me", DriveLan.setUser(on, "me").user)
        assertEquals("another", DriveLan.setPassword(on, "another").password)
    }

    // Turning it off -----------------------------------------------------------------------------

    @Test
    fun turningOffRestoresWhatTheLoginSwitchWas() {
        val off = DriveLan.deviceOnly(DriveLan.allow(settings(auth = false), made))
        // The password that was made stays, for the next time.
        assertEquals(settings(bind = "127.0.0.1", auth = false, password = "made4you"), off)

        val kept = DriveLan.deviceOnly(DriveLan.allow(settings(auth = true, password = "k3Fq9Zx2"), unasked))
        assertEquals(settings(bind = "127.0.0.1", auth = true, password = "k3Fq9Zx2"), kept)
    }

    @Test
    fun onLoopbackTheLoginSwitchIsTheUsersOwn() {
        val off = settings()
        val withLogin = DriveLan.setAuth(off, true, made)
        assertEquals(settings(auth = true, password = "made4you"), withLogin)
        assertFalse(withLogin.loginRequired)
        assertEquals(settings(auth = false, password = "made4you"), DriveLan.setAuth(withLogin, false, unasked))
        assertEquals("", DriveLan.setUser(withLogin, "").user)
    }

    /** On loopback the switch is the choice for local use, whatever an older value remembered. */
    @Test
    fun aStaleMemoryDoesNotOutliveAChoiceMadeOnLoopback() {
        val on = DriveLan.allow(settings(auth = true, password = "k3Fq9Zx2", authBeforeLan = false), unasked)
        assertEquals(true, on.authBeforeLan)
        assertTrue(DriveLan.deviceOnly(on).auth)
    }

    @Test
    fun turningOnTwiceRemembersTheFirstAnswer() {
        val on = DriveLan.allow(DriveLan.allow(settings(auth = false), made), unasked)
        assertEquals(false, on.authBeforeLan)
        assertFalse(DriveLan.deviceOnly(on).auth)
    }

    // The advanced field -------------------------------------------------------------------------

    /** The wildcard typed by hand is the switch turned on, with everything that comes with it. */
    @Test
    fun typingEveryInterfaceIsTurningItOn() {
        assertEquals(DriveLan.allow(settings(), made), DriveLan.bindTo(settings(), " 0.0.0.0 ", made))
        val v6 = DriveLan.bindTo(settings(), "::", made)
        assertEquals(settings(bind = "[::]", auth = true, password = "made4you", authBeforeLan = false), v6)
        assertEquals(DriveLanState.ON, v6.state)
    }

    @Test
    fun typingLoopbackIsTurningItOff() {
        val on = DriveLan.allow(settings(auth = false), made)
        assertEquals(settings(bind = "localhost", auth = false, password = "made4you"), DriveLan.bindTo(on, "localhost"))
    }

    /** A custom address is the user's own business: it changes nothing about the login, in either direction. */
    @Test
    fun aCustomAddressLeavesTheLoginAsItIs() {
        val custom = DriveLan.bindTo(settings(auth = false), "192.168.1.77", unasked)
        assertEquals(settings(bind = "192.168.1.77", auth = false), custom)
        assertEquals(DriveLanState.CUSTOM, custom.state)
        assertFalse(custom.loginRequired)
        assertFalse(custom.openToNetwork)
        // …and there the switch is free.
        assertTrue(DriveLan.setAuth(custom, true, made).auth)
        assertFalse(DriveLan.setAuth(DriveLan.setAuth(custom, true, made), false, unasked).auth)
    }

    /** From on to a custom address the door is still one others can reach: the login stays, and so does the memory. */
    @Test
    fun fromOnToACustomAddressTheLoginStaysOn() {
        val custom = DriveLan.bindTo(DriveLan.allow(settings(auth = false), made), "192.168.1.77", unasked)
        assertEquals(settings(bind = "192.168.1.77", auth = true, password = "made4you", authBeforeLan = false), custom)
        // Off from there gives back what was chosen for local use…
        assertFalse(DriveLan.deviceOnly(custom).auth)
        assertNull(DriveLan.deviceOnly(custom).authBeforeLan)
        // …and on again does not take the forced login for the user's choice.
        assertEquals(false, DriveLan.allow(custom, unasked).authBeforeLan)
    }

    @Test
    fun aChoiceMadeAtACustomAddressReplacesTheMemory() {
        val custom = DriveLan.bindTo(DriveLan.allow(settings(auth = false), made), "192.168.1.77", unasked)
        val chosen = DriveLan.setAuth(DriveLan.setAuth(custom, false, unasked), true, unasked)
        assertNull(chosen.authBeforeLan)
        assertTrue(DriveLan.deviceOnly(chosen).auth)
    }

    /** What the switch does in its third state: nothing by itself. Either choice is the explicit one. */
    @Test
    fun fromACustomAddressOnAndOffAreBothExplicit() {
        val custom = settings(bind = "192.168.1.77", auth = false)
        assertEquals(settings(bind = "0.0.0.0", auth = true, password = "made4you", authBeforeLan = false), DriveLan.allow(custom, made))
        assertEquals(settings(bind = "127.0.0.1", auth = false), DriveLan.deviceOnly(custom))
    }

    // Migration: a wildcard typed in before the switch existed ----------------------------------

    @Test
    fun aWildcardTypedInBeforeTheSwitchExistedComesUpAsOn() {
        for (bind in listOf("0.0.0.0", "::", "[::]", "")) {
            assertEquals(bind, DriveLanState.ON, settings(bind = bind).state)
        }
    }

    /** It worked without a login and still does: reading the settings changes none of them. */
    @Test
    fun anOpenSetupIsNamedAndLeftAsItIs() {
        val old = settings(bind = "0.0.0.0", auth = false)
        assertTrue(old.openToNetwork)
        assertFalse(old.loginRequired)
        assertFalse(old.hasLogin)
        // The login switch is still off, and switching it "off" is not an act.
        assertEquals(old, DriveLan.setAuth(old, false, unasked))
    }

    @Test
    fun aSetupThatAlreadyAsksForALoginHasNothingToBeToldAndIsLockedAtOnce() {
        val old = settings(bind = "0.0.0.0", auth = true, password = "k3Fq9Zx2")
        assertFalse(old.openToNetwork)
        assertTrue(old.loginRequired)
        assertEquals(old, DriveLan.setAuth(old, false, unasked))
        // Nothing is remembered, so off leaves the login as it is.
        assertTrue(DriveLan.deviceOnly(old).auth)
    }

    /** The notice's one action, and the same thing done with the login switch. */
    @Test
    fun theNoticesActionRequiresALoginAndTheRuleHoldsFromThere() {
        val old = settings(bind = "0.0.0.0", auth = false)
        val closed = DriveLan.requireLogin(old, made)
        assertEquals(settings(bind = "0.0.0.0", auth = true, password = "made4you", authBeforeLan = false), closed)
        assertFalse(closed.openToNetwork)
        assertTrue(closed.loginRequired)
        assertEquals(closed, DriveLan.setAuth(closed, false, unasked))
        assertEquals(closed, DriveLan.setAuth(old, true, made))
        // Off later is off as the setup had it: no login for local use.
        assertEquals(settings(bind = "127.0.0.1", auth = false, password = "made4you"), DriveLan.deviceOnly(closed))
    }

    /** The address the user typed stays: an IPv6 wildcard is not swapped for the IPv4 one behind their back. */
    @Test
    fun theNoticesActionKeepsTheStoredAddress() {
        assertEquals("[::]", DriveLan.requireLogin(settings(bind = "[::]"), made).bind)
        assertEquals("", DriveLan.requireLogin(settings(bind = ""), made).bind)
    }

    /** The switch says a login is asked for, and the daemon checks none: the name was emptied. That is open too. */
    @Test
    fun aLoginSwitchWithNothingBehindItIsAnOpenSetupAsWell() {
        val old = settings(bind = "0.0.0.0", auth = true, user = "", password = "k3Fq9Zx2")
        assertTrue(old.openToNetwork)
        val closed = DriveLan.requireLogin(old, unasked)
        assertEquals("tailsocks", closed.user)
        assertFalse(closed.openToNetwork)
    }

    /** Any act that touches the switch closes it the same way: off and on again. */
    @Test
    fun turningAnOpenSetupOffAndOnBringsTheLogin() {
        val old = settings(bind = "0.0.0.0", auth = false)
        val off = DriveLan.deviceOnly(old)
        assertEquals(settings(bind = "127.0.0.1", auth = false), off)
        assertTrue(DriveLan.allow(off, made).loginRequired)
        assertTrue(DriveLan.bindTo(old, "::", made).loginRequired)
    }

    // The address a card shows -------------------------------------------------------------------

    @Test
    fun offShowsLoopbackAndNoCode() {
        val a = DriveLan.address(settings(), "33445", listOf("192.168.1.40"))
        assertEquals("http://127.0.0.1:33445", a.url)
        assertTrue(a.deviceOnly)
        assertFalse(a.noNetwork)
        assertNull(a.qrText(withLogin = true))
        assertTrue(a.alsoAt.isEmpty())
    }

    /** Never 0.0.0.0, which is nobody's address: the one this device has on the network. */
    @Test
    fun onShowsTheAddressOnTheLocalNetwork() {
        val on = DriveLan.allow(settings(), made)
        val a = DriveLan.address(on, "33445", listOf("192.168.1.40"))
        assertEquals("http://192.168.1.40:33445", a.url)
        assertFalse(a.deviceOnly)
        assertEquals("http://192.168.1.40:33445", a.qrText(withLogin = false))
        assertEquals("http://tailsocks:made4you@192.168.1.40:33445", a.qrText(withLogin = true))
        assertEquals("http://tailsocks:made4you@192.168.1.40:33445", a.copyText)
        for (bind in listOf("::", "[::]", "")) {
            assertEquals(bind, "http://192.168.1.40:33445", DriveLan.address(settings(bind = bind), "33445", listOf("192.168.1.40")).url)
        }
    }

    /** Wi-Fi and a cable, or a network of the phone's own: every one is named, the first is the one in the code. */
    @Test
    fun onNamesEveryAddressTheDeviceHas() {
        val a = DriveLan.address(DriveLan.allow(settings(), made), "8080", listOf("192.168.1.40", "192.168.43.1", "10.0.0.5"))
        assertEquals("http://192.168.1.40:8080", a.url)
        assertEquals(listOf("http://192.168.43.1:8080", "http://10.0.0.5:8080"), a.alsoAt)
        assertFalse(a.alsoAt.any { "made4you" in it })
    }

    @Test
    fun onWithNoNetworkSaysSo() {
        val a = DriveLan.address(DriveLan.allow(settings(), made), "33445", emptyList())
        assertEquals("http://127.0.0.1:33445", a.url)
        assertTrue(a.deviceOnly)
        assertTrue(a.noNetwork)
        assertNull(a.qrText(withLogin = false))
    }

    @Test
    fun aCustomAddressIsShownAsItIs() {
        val a = DriveLan.address(settings(bind = " 192.168.1.77 "), "33445", listOf("192.168.1.40", "10.0.0.5"))
        assertEquals("http://192.168.1.77:33445", a.url)
        assertFalse(a.deviceOnly)
        assertFalse(a.noNetwork)
        assertTrue(a.alsoAt.isEmpty())
        assertEquals("http://[fd7a:115c::5]:33445", DriveLan.address(settings(bind = "[fd7a:115c::5]"), "33445", emptyList()).url)
    }

    @Test
    fun copyingAnAddressWithNoLoginCopiesTheAddress() {
        assertEquals("http://127.0.0.1:33445", DriveLan.address(settings(), "33445", emptyList()).copyText)
    }

    // The addresses of the device ----------------------------------------------------------------

    @Test
    fun theAddressesAreRankedAsTheOneThatIsPicked() {
        val all = listOf("100.101.34.12", "10.8.0.2", "192.168.43.1", "172.16.0.9", "192.168.1.40", "192.168.43.1", "169.254.3.4")
        assertEquals(listOf("192.168.43.1", "192.168.1.40", "10.8.0.2", "172.16.0.9"), DriveAddresses.rankLan(all))
        assertEquals(DriveAddresses.pickLan(all), DriveAddresses.rankLan(all).first())
        assertTrue(DriveAddresses.rankLan(listOf("100.101.34.12", "127.0.0.1")).isEmpty())
    }

    // The password that is made ------------------------------------------------------------------

    @Test
    fun aMadePasswordIsOneThatCanBeReadAndTyped() {
        val password = DriveLan.generatePassword(Random(7))
        assertEquals(10, password.length)
        assertTrue(password, password.all { it.isLetterOrDigit() && it !in "0O1lI" })
        assertFalse(password == DriveLan.generatePassword(Random(8)))
    }

    @Test
    fun thePasswordIsNotInWhatIsPrinted() {
        assertFalse(settings(auth = true, password = "k3Fq9Zx2").toString().contains("k3Fq9Zx2"))
    }
}
