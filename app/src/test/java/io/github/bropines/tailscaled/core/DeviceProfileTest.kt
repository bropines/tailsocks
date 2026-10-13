package io.github.bropines.tailscaled.core

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    private val television = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES
    private val normal = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES

    private val everyCapability = listOf(
        Capabilities.ALL,
        Capabilities.NONE,
        Capabilities(browser = true, documents = false),
        Capabilities(browser = false, documents = true),
    )

    @Test
    fun aTelevisionSaysSoInItsUiMode() {
        assertEquals(DeviceClass.TV, DeviceProfile.deviceClass(television, hasLeanback = true, hasTouchscreen = false))
        // The mode alone is enough, whatever the features claim.
        assertEquals(DeviceClass.TV, DeviceProfile.deviceClass(television, hasLeanback = false, hasTouchscreen = true))
    }

    /** The Android TV emulator: no touch device, and the touchscreen feature listed all the same. */
    @Test
    fun aTelevisionThatClaimsATouchscreenIsStillATelevision() {
        assertEquals(DeviceClass.TV, DeviceProfile.deviceClass(television, hasLeanback = true, hasTouchscreen = true))
    }

    @Test
    fun aLeanbackBoxWithoutTouchIsATelevision() {
        assertEquals(DeviceClass.TV, DeviceProfile.deviceClass(normal, hasLeanback = true, hasTouchscreen = false))
    }

    @Test
    fun everythingElseIsAHandheld() {
        assertEquals(DeviceClass.HANDHELD, DeviceProfile.deviceClass(normal, hasLeanback = false, hasTouchscreen = true))
        // Leanback alone is not enough: a touch device may declare it.
        assertEquals(DeviceClass.HANDHELD, DeviceProfile.deviceClass(normal, hasLeanback = true, hasTouchscreen = true))
        // No touchscreen alone is not enough either: a box that is not a television.
        assertEquals(DeviceClass.HANDHELD, DeviceProfile.deviceClass(normal, hasLeanback = false, hasTouchscreen = false))
        // A tablet in a dock, a phone in a car: not televisions.
        for (mode in listOf(Configuration.UI_MODE_TYPE_DESK, Configuration.UI_MODE_TYPE_CAR, Configuration.UI_MODE_TYPE_UNDEFINED)) {
            assertEquals(DeviceClass.HANDHELD, DeviceProfile.deviceClass(mode, hasLeanback = false, hasTouchscreen = true))
        }
    }

    @Test
    fun theOverrideWinsBothWays() {
        assertEquals(
            DeviceClass.TV,
            DeviceProfile.deviceClass(normal, hasLeanback = false, hasTouchscreen = true, override = DeviceOverride.TV)
        )
        assertEquals(
            DeviceClass.HANDHELD,
            DeviceProfile.deviceClass(television, hasLeanback = true, hasTouchscreen = false, override = DeviceOverride.HANDHELD)
        )
        assertEquals(
            DeviceClass.TV,
            DeviceProfile.deviceClass(television, hasLeanback = true, hasTouchscreen = false, override = DeviceOverride.AUTO)
        )
    }

    @Test
    fun theOverrideIsStoredByIdAndUnknownMeansAuto() {
        for (override in DeviceOverride.entries) assertEquals(override, DeviceOverride.fromId(override.id))
        assertEquals(DeviceOverride.AUTO, DeviceOverride.fromId(null))
        assertEquals(DeviceOverride.AUTO, DeviceOverride.fromId(""))
        assertEquals(DeviceOverride.AUTO, DeviceOverride.fromId("watch"))
    }

    /** The guarantee the television work rests on: a phone or a tablet is offered everything, whatever the system has. */
    @Test
    fun aHandheldIsOfferedEveryFeature() {
        for (feature in Feature.entries) {
            for (caps in everyCapability) {
                assertEquals(
                    "$feature with $caps",
                    Availability.OFFERED,
                    DeviceProfile.availability(feature, DeviceClass.HANDHELD, caps)
                )
            }
        }
    }

    @Test
    fun aTelevisionHidesWhatItCannotDo() {
        val hidden = listOf(
            Feature.ADMIN_CONSOLE, Feature.QR_SCANNER, Feature.TAILCAT, Feature.TAILNET_LOCK_SIGN,
            Feature.SEARCH, Feature.CLIPBOARD_ACTIONS, Feature.TOUCH_GESTURES_HINTS,
            Feature.BATTERY_OPTIMISATION_PROMPT,
            // A browser on the set does not bring the browser login back: the code is the way in.
            Feature.BROWSER_SIGN_IN,
        )
        for (feature in hidden) {
            // Not a matter of what is installed: a browser does not bring the admin console back.
            for (caps in everyCapability) {
                val answer = DeviceProfile.availability(feature, DeviceClass.TV, caps)
                assertEquals("$feature with $caps", Availability.HIDDEN, answer)
                assertFalse(answer.reachable)
            }
        }
    }

    @Test
    fun aTelevisionKeepsTheRestInAdvancedSettings() {
        val advanced = listOf(
            Feature.SERVE_EDIT, Feature.CONSOLE, Feature.LOGS, Feature.DNS_SCREEN,
            Feature.ROOT_MODE, Feature.BACKUP_RESTORE, Feature.THEME_AND_ICON,
            Feature.LIVE_STATE_ON_HOME,
            Feature.AUTOMATION, Feature.DIAGNOSTICS,
        )
        for (feature in advanced) {
            val answer = DeviceProfile.availability(feature, DeviceClass.TV, Capabilities.NONE)
            assertEquals(feature.name, Availability.ADVANCED, answer)
            assertTrue(answer.reachable)
        }
    }

    /** Settings → Advanced is built from this: nothing a television keeps may fall between the hub and that page. */
    @Test
    fun whatATelevisionIsNotOfferedIsEitherAdvancedOrHidden() {
        for (feature in Feature.entries) {
            for (caps in everyCapability) {
                val answer = DeviceProfile.availability(feature, DeviceClass.TV, caps)
                assertEquals("$feature with $caps", answer != Availability.HIDDEN, answer.reachable)
            }
        }
        // What moved off a television's main screen can still be reached, whatever is installed.
        for (feature in listOf(Feature.CONSOLE, Feature.LOGS, Feature.DNS_SCREEN, Feature.SERVE_EDIT)) {
            for (caps in everyCapability) {
                assertEquals("$feature with $caps", Availability.ADVANCED, DeviceProfile.availability(feature, DeviceClass.TV, caps))
            }
        }
    }

    @Test
    fun aTelevisionHasAPickerAndABrowserOnlyWhenTheSystemDoes() {
        val picker = Capabilities(browser = false, documents = true)
        val browser = Capabilities(browser = true, documents = false)
        assertEquals(Availability.OFFERED, DeviceProfile.availability(Feature.SYSTEM_FILE_PICKER, DeviceClass.TV, picker))
        assertEquals(Availability.HIDDEN, DeviceProfile.availability(Feature.SYSTEM_FILE_PICKER, DeviceClass.TV, browser))
        assertEquals(Availability.OFFERED, DeviceProfile.availability(Feature.BROWSER, DeviceClass.TV, browser))
        assertEquals(Availability.HIDDEN, DeviceProfile.availability(Feature.BROWSER, DeviceClass.TV, picker))
    }
}
