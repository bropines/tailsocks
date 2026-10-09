package io.github.bropines.tailscaled.admin.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Devices tab added to the backend: the name reset, the IPv4 call, connectivity. */
class DeviceExtrasBackendTest {

    @Test
    fun resetSendsTheEmptyNameTheApiDocuments() = runBlocking {
        val t = FakeTransport().ok("POST", "/device/nX/name", "")
        testBackend(t).resetDeviceName("nX")
        assertEquals("""{"name":""}""", t.requests.single().body)
    }

    @Test
    fun ipv4GoesToTheIpEndpoint() = runBlocking {
        val t = FakeTransport().ok("POST", "/device/nX/ip", "")
        testBackend(t).setDeviceIpv4("nX", "100.80.0.1")
        assertEquals("""{"ipv4":"100.80.0.1"}""", t.requests.single().body)
    }

    @Test
    fun connectivityFromAFullRead() = runBlocking {
        val t = FakeTransport().ok("GET", "/device/n292kg92CNTRL", recorded("device_all_fields.json"))
        val d = testBackend(t).getDevice("n292kg92CNTRL")
        val c = d.clientConnectivity!!
        assertEquals(listOf("199.9.14.201:59128"), c.endpoints)
        assertEquals(60.46, c.latency.getValue("Dallas").latencyMs!!, 0.001)
        assertNull(c.clientSupports)
        assertTrue(t.requests.single().url.contains("fields=all"))
    }

    @Test
    fun aDeviceWithoutConnectivityStillReads() = runBlocking {
        val t = FakeTransport().ok("GET", "/devices", recorded("devices.json"))
        assertTrue(testBackend(t).listDevices().items.all { it.clientConnectivity == null })
    }
}
