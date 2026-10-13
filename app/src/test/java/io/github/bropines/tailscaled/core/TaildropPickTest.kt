package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Taildrop does differently on a device without a picker, a share sheet or a notification shade. */
class TaildropPickTest {
    private fun offered(device: DeviceClass, caps: Capabilities) =
        DeviceProfile.availability(Feature.SYSTEM_FILE_PICKER, device, caps) == Availability.OFFERED

    // --- which picker ---------------------------------------------------------------------

    /** A phone: the system's picker starts, and that is all that happens. */
    @Test
    fun aDeviceWithAPickerUsesIt() {
        var launches = 0
        val kind = choosePicker(offered(DeviceClass.HANDHELD, Capabilities.ALL)) { launches++; true }
        assertEquals(PickerKind.SYSTEM, kind)
        assertEquals(1, launches)
    }

    /** A bare television: the placeholder that would show a toast and finish is never started. */
    @Test
    fun aTelevisionWithoutAPickerNeverStartsThePlaceholder() {
        var launches = 0
        val kind = choosePicker(offered(DeviceClass.TV, Capabilities.NONE)) { launches++; true }
        assertEquals(PickerKind.IN_APP, kind)
        assertEquals(0, launches)
    }

    /** A television with a file manager that answers the picker intents is a device with a picker. */
    @Test
    fun aTelevisionWithARealPickerUsesIt() {
        assertEquals(PickerKind.SYSTEM, choosePicker(offered(DeviceClass.TV, Capabilities.ALL)) { true })
    }

    /** A handheld is never asked what it has, so one without a picker finds out at the launch. */
    @Test
    fun aDeviceWhoseLaunchFailsFallsBackToTheAppsBrowser() {
        var launches = 0
        val kind = choosePicker(offered(DeviceClass.HANDHELD, Capabilities.NONE)) { launches++; false }
        assertEquals(PickerKind.IN_APP, kind)
        assertEquals(1, launches)
    }

    // --- how an arrival is told -------------------------------------------------------------

    @Test
    fun aPhoneIsToldByNotificationUnlessTheInboxIsOnScreen() {
        assertEquals(TaildropEvents.Telling(notification = true, onScreen = false), TaildropEvents.tellingOf(DeviceClass.HANDHELD, appOnScreen = false, inboxVisible = false))
        assertEquals(TaildropEvents.Telling(notification = true, onScreen = false), TaildropEvents.tellingOf(DeviceClass.HANDHELD, appOnScreen = true, inboxVisible = false))
        assertEquals(TaildropEvents.Telling(notification = false, onScreen = false), TaildropEvents.tellingOf(DeviceClass.HANDHELD, appOnScreen = true, inboxVisible = true))
    }

    @Test
    fun aTelevisionIsToldOnTheScreenWhileTheAppIsInFront() {
        assertTrue(TaildropEvents.tellingOf(DeviceClass.TV, appOnScreen = true, inboxVisible = false).onScreen)
        assertTrue(TaildropEvents.tellingOf(DeviceClass.TV, appOnScreen = true, inboxVisible = true).onScreen)
        // Something else is on the screen: the app does not write over it.
        assertFalse(TaildropEvents.tellingOf(DeviceClass.TV, appOnScreen = false, inboxVisible = false).onScreen)
        // The notification goes out as on a phone, for the televisions that show them.
        assertTrue(TaildropEvents.tellingOf(DeviceClass.TV, appOnScreen = false, inboxVisible = false).notification)
    }

    // --- whether the history's export has anywhere to go ----------------------------------------

    private val own = "io.github.bropines.tailscaled"

    @Test
    fun theAppsOwnShareTargetIsNotSomewhereToExportTo() {
        assertFalse(TaildropHistory.hasOutsideTaker(listOf(own to "$own.ui.ShareActivity"), own))
        assertFalse(TaildropHistory.hasOutsideTaker(emptyList(), own))
    }

    /** What the Android TV image of API 36 answers a shared CSV with: its e-mail placeholder, and this app. */
    @Test
    fun aTelevisionsPlaceholderIsNotSomewhereToExportTo() {
        val stubs = "com.android.tv.frameworkpackagestubs"
        assertFalse(TaildropHistory.hasOutsideTaker(listOf(stubs to "$stubs.Stubs\$EmailStub", own to "$own.ui.ShareActivity"), own))
    }

    @Test
    fun anotherAppThatTakesTheFileIs() {
        val takers = listOf(own to "$own.ui.ShareActivity", "com.google.android.gm" to "com.google.android.gm.ComposeActivityGmailExternal")
        assertTrue(TaildropHistory.hasOutsideTaker(takers, own))
    }
}
