package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaildriveAddressTest {
    private fun address(
        bind: String = "0.0.0.0",
        port: String = "33445",
        auth: Boolean = false,
        user: String = "tailsocks",
        password: String = "",
        lan: String? = "192.168.1.40",
    ) = DriveAddresses.of(bind, port, auth, user, password, lan)

    /** The proxy's default: loopback. What a phone would scan is its own loopback, so there is no code. */
    @Test
    fun aProxyOnLoopbackIsForThisDeviceAlone() {
        val a = address(bind = "127.0.0.1")
        assertEquals("http://127.0.0.1:33445", a.url)
        assertTrue(a.deviceOnly)
        assertNull(a.qrText(withLogin = false))
        assertNull(a.qrText(withLogin = true))
    }

    @Test
    fun everyKindOfLoopbackCounts() {
        for (bind in listOf("127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "::1", "[::1]")) {
            assertTrue(bind, address(bind = bind).deviceOnly)
        }
    }

    /** 0.0.0.0 is not an address anyone connects to: the one to scan is the device's own on the network. */
    @Test
    fun aProxyOnEveryInterfaceIsOpenedAtTheAddressOnTheNetwork() {
        for (bind in listOf("0.0.0.0", "", "::", "[::]")) {
            val a = address(bind = bind)
            assertEquals(bind, "http://192.168.1.40:33445", a.url)
            assertFalse(bind, a.deviceOnly)
            assertEquals(bind, "http://192.168.1.40:33445", a.qrText(withLogin = false))
        }
    }

    @Test
    fun withNoAddressOnTheNetworkAWildcardCanOnlyBeReachedFromHere() {
        val a = address(bind = "0.0.0.0", lan = null)
        assertEquals("http://127.0.0.1:33445", a.url)
        assertTrue(a.deviceOnly)
        assertNull(a.qrText(withLogin = false))
    }

    @Test
    fun anAddressTheProxyIsBoundToIsTheOneShown() {
        val a = address(bind = "192.168.1.77", lan = "10.0.0.5")
        assertEquals("http://192.168.1.77:33445", a.url)
        assertFalse(a.deviceOnly)
        assertEquals("http://[fd7a:115c::5]:33445", address(bind = "fd7a:115c::5").url)
    }

    @Test
    fun aBadPortIsTheDefaultOne() {
        assertEquals("http://127.0.0.1:33445", address(bind = "127.0.0.1", port = "").url)
        assertEquals("http://127.0.0.1:33445", address(bind = "127.0.0.1", port = "99999").url)
        assertEquals("http://127.0.0.1:8080", address(bind = "127.0.0.1", port = " 8080 ").url)
    }

    /** The login is never in the address that is shown, and in the code only when it was asked for. */
    @Test
    fun theLoginIsInTheCodeOnlyWhenAskedFor() {
        val a = address(auth = true, user = "tailsocks", password = "k3Fq9Zx2")
        assertEquals("http://192.168.1.40:33445", a.url)
        assertEquals("http://192.168.1.40:33445", a.qrText(withLogin = false))
        assertEquals("http://tailsocks:k3Fq9Zx2@192.168.1.40:33445", a.qrText(withLogin = true))
        assertNotNull(a.login)
    }

    @Test
    fun theLoginIsEscapedInAnAddress() {
        val a = address(auth = true, user = "me@home", password = "p:ss/w d")
        assertEquals("http://me%40home:p%3Ass%2Fw%20d@192.168.1.40:33445", a.qrText(withLogin = true))
    }

    /** The daemon starts the proxy with a login only when the switch is on and both fields are filled. */
    @Test
    fun noLoginUnlessTheProxyAsksForOne() {
        for (a in listOf(
            address(auth = false, password = "k3Fq9Zx2"),
            address(auth = true, password = ""),
            address(auth = true, user = "", password = "k3Fq9Zx2"),
        )) {
            assertNull(a.login)
            assertEquals("http://192.168.1.40:33445", a.qrText(withLogin = true))
        }
    }

    @Test
    fun thePasswordIsNotInWhatIsPrinted() {
        val a = address(auth = true, password = "k3Fq9Zx2")
        assertFalse(a.toString().contains("k3Fq9Zx2"))
        assertFalse(a.login.toString().contains("k3Fq9Zx2"))
    }

    // The address on the local network ---------------------------------------------------------

    @Test
    fun aPrivateAddressIsThePickedOne() {
        assertEquals("192.168.1.40", DriveAddresses.pickLan(listOf("192.168.1.40")))
        assertEquals("10.0.0.5", DriveAddresses.pickLan(listOf("10.0.0.5")))
        assertEquals("172.16.4.2", DriveAddresses.pickLan(listOf("172.16.4.2")))
        assertEquals("172.31.255.1", DriveAddresses.pickLan(listOf("172.31.255.1")))
    }

    /** The tailnet's range, a carrier's, loopback and the link-local one lead to other networks. */
    @Test
    fun otherNetworksAreNotTheLan() {
        for (a in listOf("100.101.34.12", "100.64.0.1", "127.0.0.1", "169.254.3.4", "172.15.0.1", "172.32.0.1", "8.8.8.8", "0.0.0.0")) {
            assertNull(a, DriveAddresses.pickLan(listOf(a)))
        }
        assertNull(DriveAddresses.pickLan(emptyList()))
        assertNull(DriveAddresses.pickLan(listOf("not an address", "1.2.3", "300.1.1.1", "192.168.1")))
    }

    /** What the interfaces of a television are called: Wi-Fi and Ethernet are the local network, a tunnel is not. */
    @Test
    fun onlyRealInterfacesCanBeTheLan() {
        for (name in listOf("wlan0", "eth0", "eth1", "en0", "enp3s0", "wlan1")) assertTrue(name, DriveAddresses.isLanInterface(name))
        for (name in listOf("lo", "tun0", "tun1", "wg0", "ppp0", "tailscale0", "ipsec0", "dummy0", "rmnet_data1", "ccmni0", "v4-wlan0", "p2p0")) {
            assertFalse(name, DriveAddresses.isLanInterface(name))
        }
    }

    @Test
    fun theHomeNetworkComesBeforeTheRest() {
        assertEquals("192.168.1.40", DriveAddresses.pickLan(listOf("100.101.34.12", "10.8.0.2", "192.168.1.40", "172.16.0.9")))
        assertEquals("10.8.0.2", DriveAddresses.pickLan(listOf("100.101.34.12", "172.16.0.9", "10.8.0.2")))
        assertEquals("172.16.0.9", DriveAddresses.pickLan(listOf("100.101.34.12", "172.16.0.9")))
    }
}
