package io.github.bropines.tailscaled.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLaunchTest {
    /** What the Android TV image of API 36 resolves the picker and a web link to. */
    @Test
    fun theTelevisionStubsAreNotHandlers() {
        val stubs = "com.android.tv.frameworkpackagestubs"
        assertTrue(isPlaceholderActivity(stubs, "$stubs.Stubs\$DocumentsStub"))
        assertTrue(isPlaceholderActivity(stubs, "$stubs.Stubs\$BrowserStub"))
        // A vendor's build of the same package.
        val vendor = "com.google.android.tv.frameworkpackagestubs"
        assertTrue(isPlaceholderActivity(vendor, "$vendor.Stubs\$BrowserStub"))
    }

    /** The battery-optimisation request on a television: a page of the settings app with nothing on it. */
    @Test
    fun theEmptySettingsPageIsNotAHandler() {
        assertTrue(isPlaceholderActivity("com.android.tv.settings", "com.android.tv.settings.EmptyStubActivity"))
    }

    @Test
    fun realHandlersAreHandlers() {
        assertFalse(isPlaceholderActivity("com.android.documentsui", "com.android.documentsui.picker.PickActivity"))
        assertFalse(isPlaceholderActivity("com.google.android.documentsui", "com.android.documentsui.picker.PickActivity"))
        assertFalse(isPlaceholderActivity("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher"))
        // Several apps answer: the system's chooser stands in for them.
        assertFalse(isPlaceholderActivity("android", "com.android.internal.app.ResolverActivity"))
        // The page a television does have for all-files access.
        assertFalse(
            isPlaceholderActivity("com.android.tv.settings", "com.android.tv.settings.device.apps.specialaccess.AllFilesAccessActivity")
        )
        assertFalse(isPlaceholderActivity(null, null))
    }
}
