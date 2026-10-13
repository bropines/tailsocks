package io.github.bropines.tailscaled.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's lists and the bridge's are one. `appctr/testdata/remote_allowlist.json` is what
 * the bridge's own test (TestRemoteAllowListFile) holds remote_policy.go to; here the names the
 * app answers to are held to the same file. A setting, an action, a permission or a request
 * added on one side alone fails a test on that side or on this one.
 */
class RemoteAllowListTest {

    @Serializable
    private data class Setting(val key: String = "", val kind: String = "", val writable: Boolean = false)

    @Serializable
    private data class AllowList(
        val settings: List<Setting> = emptyList(),
        val actions: List<String> = emptyList(),
        val permissions: List<String> = emptyList(),
        val askable: List<String> = emptyList(),
        val askResults: List<String> = emptyList(),
        val identifyResults: List<String> = emptyList(),
        val requests: List<String> = emptyList(),
    )

    /** Tests run in the module's directory; the bridge is its neighbour. */
    private val list: AllowList = AppJson.decodeFromString(
        AllowList.serializer(),
        File("../appctr/testdata/remote_allowlist.json").readText()
    )

    @Test
    fun everySettingOfTheBridgeIsOneTheAppAnswersTo() {
        val switches = list.settings.filter { it.kind == "bool" }.map { it.key }
        assertEquals(switches.toSet(), (RemoteKeys.SWITCHES + RemoteDeviceKeys.SWITCHES).toSet())
        // No key twice, between the two delegates or within one.
        assertEquals(switches.size, (RemoteKeys.SWITCHES + RemoteDeviceKeys.SWITCHES).size)
        assertTrue(switches.all { key -> list.settings.first { it.key == key }.writable })
        assertEquals(listOf(RemoteDeviceKeys.ADVERTISE_ROUTES), list.settings.filter { it.kind == "cidrs" }.map { it.key })
        assertEquals(listOf(RemoteKeys.DEVICE_CLASS, RemoteKeys.APP_VERSION), list.settings.filter { !it.writable }.map { it.key })
    }

    @Test
    fun everyActionIsTheAppsOrTheBridgesOwn() {
        // The relays are dialed again by the bridge itself; the app is never asked.
        val bridges = listOf("reconnect_relays")
        assertEquals(list.actions.toSet(), (listOf(RemoteKeys.ACTION_RECONNECT) + RemoteDeviceKeys.ACTIONS + bridges).toSet())
    }

    @Test
    fun thePermissionsAreTheAppsInTheBridgesOrder() {
        assertEquals(list.permissions.toSet(), AppPermission.entries.map { it.id }.toSet())
        // Everything but the VPN consent can be asked for, on both sides.
        assertEquals(list.askable, list.permissions - AppPermission.VPN.id)
        assertEquals(list.askResults.toSet(), RemoteAskRoute.entries.map { it.word }.toSet())
        assertEquals(list.identifyResults.toSet(), RemoteAskRoute.entries.map { it.identifyWord() }.toSet())
    }

    @Test
    fun everyRequestOfTheBridgeIsOneTheAppKnows() {
        assertEquals(list.requests, RemoteDeviceKeys.REQUESTS)
    }
}
