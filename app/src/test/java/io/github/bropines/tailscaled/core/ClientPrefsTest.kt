package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.core.ClientPrefs.Relay
import io.github.bropines.tailscaled.core.ClientPrefs.Values
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node switches: what a profile stores becomes the MaskedPrefs fragment syncSettings
 * sends, the extra arguments' reading of the same fields is understood, and applyTagsAndRoutes
 * leaves an app connector's routes alone only when it may.
 */
class ClientPrefsTest {

    private fun obj(json: String): JsonObject = AppJson.parseToJsonElement(json).jsonObject

    @Test
    fun untouchedSwitchesSendNothing() {
        val v = ClientPrefs.stored(mapOf("hostname" to "phone", "advertise_exit_node" to true))
        assertEquals(Values(), v)
        assertEquals("", ClientPrefs.maskedPrefsJson(v))
    }

    @Test
    fun storedSwitchesBecomeMaskedPrefs() {
        val v = ClientPrefs.stored(
            mapOf(
                ClientPrefs.SHIELDS_UP to true,
                ClientPrefs.EXIT_NODE_LAN to false,
                ClientPrefs.REPORT_POSTURE to true,
                ClientPrefs.RELAY_ENABLED to true,
                ClientPrefs.RELAY_PORT to "40000",
                ClientPrefs.APP_CONNECTOR to true,
            )
        )
        val o = obj(ClientPrefs.maskedPrefsJson(v))
        assertEquals(JsonPrimitive(true), o["ShieldsUp"])
        assertEquals(JsonPrimitive(true), o["ShieldsUpSet"])
        assertEquals(JsonPrimitive(false), o["ExitNodeAllowLANAccess"])
        assertEquals(JsonPrimitive(true), o["ExitNodeAllowLANAccessSet"])
        assertEquals(JsonPrimitive(true), o["PostureChecking"])
        assertEquals(JsonPrimitive(40000), o["RelayServerPort"])
        assertEquals(JsonPrimitive(true), o["RelayServerPortSet"])
        assertEquals(JsonPrimitive(true), (o["AppConnector"] as JsonObject)["Advertise"])
        assertEquals(JsonPrimitive(true), o["AppConnectorSet"])
    }

    @Test
    fun relayOffIsAnExplicitNullAndBlankPortIsAnyPort() {
        val off = obj(ClientPrefs.maskedPrefsJson(ClientPrefs.stored(mapOf(ClientPrefs.RELAY_ENABLED to false, ClientPrefs.RELAY_PORT to "40000"))))
        assertEquals(JsonNull, off["RelayServerPort"])
        assertEquals(JsonPrimitive(true), off["RelayServerPortSet"])

        val any = ClientPrefs.stored(mapOf(ClientPrefs.RELAY_ENABLED to true, ClientPrefs.RELAY_PORT to ""))
        assertEquals(Relay(true, 0), any.relay)
        assertEquals(JsonPrimitive(0), obj(ClientPrefs.maskedPrefsJson(any))["RelayServerPort"])

        // A broken stored port is not sent as a port: the relay runs on any free one.
        assertEquals(Relay(true, 0), ClientPrefs.stored(mapOf(ClientPrefs.RELAY_ENABLED to true, ClientPrefs.RELAY_PORT to "99999")).relay)
    }

    @Test
    fun portsAreParsedStrictly() {
        assertEquals(0, ClientPrefs.parsePort(""))
        assertEquals(0, ClientPrefs.parsePort("  "))
        assertEquals(0, ClientPrefs.parsePort("0"))
        assertEquals(41641, ClientPrefs.parsePort(" 41641 "))
        assertEquals(65535, ClientPrefs.parsePort("65535"))
        assertNull(ClientPrefs.parsePort("65536"))
        assertNull(ClientPrefs.parsePort("-1"))
        assertNull(ClientPrefs.parsePort("40 000"))
        assertNull(ClientPrefs.parsePort("abc"))
        assertNull(ClientPrefs.parsePort("1234567"))
    }

    @Test
    fun overridesFollowTheSetFlags() {
        // As appctr.ExtraArgsPrefsJSON writes them.
        val o = ClientPrefs.overrides(
            """{"ShieldsUp":false,"ShieldsUpSet":true,"PostureChecking":true,"AppConnector":{"Advertise":true},"AppConnectorSet":true,"RelayServerPort":40000,"RelayServerPortSet":true}"""
        )
        assertEquals(false, o.shieldsUp)
        assertNull("a field without its Set flag is not an override", o.reportPosture)
        assertNull(o.exitNodeAllowLan)
        assertEquals(true, o.appConnector)
        assertEquals(Relay(true, 40000), o.relay)
        assertTrue(o.isSet(ClientPrefs.Field.SHIELDS_UP))
        assertFalse(o.isSet(ClientPrefs.Field.REPORT_POSTURE))

        assertEquals(Relay(false), ClientPrefs.overrides("""{"RelayServerPort":null,"RelayServerPortSet":true}""").relay)
        assertEquals(Values(), ClientPrefs.overrides("{}"))
        assertEquals(Values(), ClientPrefs.overrides("not json"))
    }

    @Test
    fun liveValuesComeFromTheDaemonsPrefs() {
        val json = """{"ControlURL":"https://controlplane.tailscale.com","ShieldsUp":true,"ExitNodeAllowLANAccess":false,
            |"PostureChecking":false,"AppConnector":{"Advertise":true},"RelayServerPort":0,
            |"AdvertiseRoutes":["10.0.0.0/24","203.0.113.9/32"]}""".trimMargin()
        val v = ClientPrefs.live(json)!!
        assertEquals(true, v.shieldsUp)
        assertEquals(false, v.exitNodeAllowLan)
        assertEquals(true, v.appConnector)
        assertEquals(Relay(true, 0), v.relay)
        assertEquals(listOf("10.0.0.0/24", "203.0.113.9/32"), ClientPrefs.liveRoutes(json))

        // RelayServerPort is omitted when the relay is off.
        assertEquals(Relay(false), ClientPrefs.live("""{"ShieldsUp":false}""")!!.relay)
        assertEquals(emptyList<String>(), ClientPrefs.liveRoutes("""{"ShieldsUp":false}"""))
        assertNull(ClientPrefs.live("Error: not running"))
        assertNull(ClientPrefs.live(""))
    }

    @Test
    fun connectorRoutesAreKeptOnlyWhenNothingTheUserOwnsChanged() {
        val wanted = listOf("10.0.0.0/24", "0.0.0.0/0", "::/0")
        val learned = listOf("203.0.113.9/32", "2001:db8::9/128")
        // The connector's host routes on top of the user's: keep.
        assertTrue(ClientPrefs.connectorRoutesCurrent(wanted, wanted + learned, wanted))
        // A wanted route is missing: write.
        assertFalse(ClientPrefs.connectorRoutesCurrent(wanted + "192.168.5.0/24", wanted + learned, wanted))
        // A subnet the user removed is still there: write.
        assertFalse(ClientPrefs.connectorRoutesCurrent(listOf("10.0.0.0/24"), wanted + learned, wanted))
        // A host route the user wrote last time and has since removed: write.
        assertFalse(ClientPrefs.connectorRoutesCurrent(listOf("10.0.0.0/24"), listOf("10.0.0.0/24", "192.168.1.5/32"), listOf("10.0.0.0/24", "192.168.1.5/32")))
        // Nothing wanted, nothing but learned routes: keep.
        assertTrue(ClientPrefs.connectorRoutesCurrent(emptyList(), learned, emptyList()))
    }

    @Test
    fun controlCanTurnTheRelayOff() {
        assertTrue(ClientPrefs.relayDisabledByControl("""{"Self":{"HostName":"phone","CapMap":{"disable-relay-server":null,"https":null}}}"""))
        assertFalse(ClientPrefs.relayDisabledByControl("""{"Self":{"HostName":"phone","CapMap":{"https":null}}}"""))
        assertFalse(ClientPrefs.relayDisabledByControl("""{"Self":{"HostName":"phone"}}"""))
        assertFalse(ClientPrefs.relayDisabledByControl("Error: not running"))
    }
}
