package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.Capabilities
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.DeviceProfile
import io.github.bropines.tailscaled.core.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a `tailsocks://` link lands on a device that is not offered everything in its usual place. */
class DeepLinksTest {
    private fun reachable(host: String, device: DeviceClass): Boolean =
        DeepLinks.featureOf(host)?.let { DeviceProfile.availability(it, device, Capabilities.NONE).reachable } ?: true

    @Test
    fun aHandheldOpensEveryLink() {
        for (host in listOf("serve", "tailcat", "logs", "dns", "console", "scan", "peers", "files", "settings")) {
            assertTrue(host, reachable(host, DeviceClass.HANDHELD))
        }
    }

    @Test
    fun aTelevisionOpensWhatItKeepsForItsAdvancedSettings() {
        // Logs, the console, the DNS screen and Serve are reached from Settings → Advanced there.
        for (host in listOf("serve", "logs", "dns", "console")) assertTrue(host, reachable(host, DeviceClass.TV))
        for (host in listOf("peers", "netcheck", "files", "taildrive", "permissions", "licenses", "settings", "exitnode")) {
            assertNull(host, DeepLinks.featureOf(host))
        }
    }

    @Test
    fun aTelevisionDoesNotOpenWhatItDoesNotHave() {
        // No TailCat and no camera: the link lands on the main screen instead of a dead one.
        assertFalse(reachable("tailcat", DeviceClass.TV))
        assertFalse(reachable("scan", DeviceClass.TV))
    }

    @Test
    fun aLinkMayNameTheAdvancedPageAndThePagesUnderIt() {
        for (section in listOf("advanced", "theme", "root", "backup", "automation", "diagnostics")) {
            assertTrue(section, section in DeepLinks.SETTINGS_SECTIONS)
        }
    }

    // ─── Settings: the row a link names ─────────────────────────────────────────────────────

    @Test
    fun aLinkMayNameARowOfItsSection() {
        assertEquals("mode_vpn", DeepLinks.settingsRow(listOf("tunnel", "mode_vpn")))
        assertEquals("autostart", DeepLinks.settingsRow(listOf("background", "autostart")))
        // Every entry of the catalogue can be named, whatever stands before it.
        for (entry in SettingsCatalog.entries) assertEquals(entry.id, DeepLinks.settingsRow(listOf("x", entry.id)))
    }

    @Test
    fun aRowTheCatalogueDoesNotKnowIsNoRow() {
        assertNull(DeepLinks.settingsRow(listOf("tunnel")))
        assertNull(DeepLinks.settingsRow(emptyList()))
        assertNull(DeepLinks.settingsRow(listOf("tunnel", "nonsense")))
        assertNull(DeepLinks.settingsRow(listOf("tunnel", "../../etc")))
    }

    // ─── Settings: the page a link names, as the device has it ──────────────────────────────

    private val handheldHub = listOf(
        "appearance", "account", "tunnel", "proxies", "dns", "bypass", "sharing", "background",
        "backup", "automation", "diagnostics", "advanced"
    )
    private val tvHub = listOf("appearance", "account", "tunnel", "proxies", "dns", "bypass", "sharing", "background", "advanced")
    private val tvAdvanced = listOf("theme", "root", "backup", "automation", "diagnostics")

    @Test
    fun aPageTheDeviceListsOpensAsNamed() {
        assertEquals("bypass", settingsLanding("bypass", handheldHub, emptyList()))
        assertEquals("advanced", settingsLanding("advanced", handheldHub, emptyList()))
        assertEquals("advanced", settingsLanding("advanced", tvHub, tvAdvanced))
        // The pages a television moved under Advanced are still theirs to open.
        for (page in tvAdvanced) assertEquals(page, settingsLanding(page, tvHub, tvAdvanced))
        assertEquals("diagnostics", settingsLanding("diagnostics", handheldHub, emptyList()))
    }

    @Test
    fun aHandheldFindsTheThemeAndRootWhereItKeepsThem() {
        assertEquals("appearance", settingsLanding("theme", handheldHub, emptyList()))
        assertEquals("tunnel", settingsLanding("root", handheldHub, emptyList()))
    }

    @Test
    fun aNameWithNoPageOpensTheList() {
        assertNull(settingsLanding(null, handheldHub, emptyList()))
        assertNull(settingsLanding("nonsense", handheldHub, emptyList()))
        assertNull(settingsLanding("nonsense", tvHub, tvAdvanced))
    }
}
