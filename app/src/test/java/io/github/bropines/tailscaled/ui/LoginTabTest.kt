package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which browser the login tab opens in (ui/LoginTab.kt, [LoginBrowser.choose]), over what a
 * device reports: its default handler of a web link, and the packages that both open such a
 * link and offer the Custom Tabs service. The tab itself needs a device with a browser.
 */
class LoginTabTest {
    private val chrome = "com.android.chrome"
    private val firefox = "org.mozilla.firefox"
    private val brave = "com.brave.browser"
    private val bridge = LoginBrowser.WINDOWS_BRIDGE

    /** What Android answers when nobody has picked a default browser: its own chooser. */
    private val chooser = "android"

    @Test
    fun theDefaultBrowserIsUsedWhenItCanShowATab() {
        assertEquals(firefox, LoginBrowser.choose(firefox, listOf(chrome, firefox)))
        assertEquals(chrome, LoginBrowser.choose(chrome, listOf(chrome, firefox)))
        // The only one there is.
        assertEquals(brave, LoginBrowser.choose(brave, listOf(brave)))
    }

    @Test
    fun onWindowsSubsystemForAndroidTheBridgeIsPassedOverForARealBrowser() {
        // The author's device, as it reports itself: the default handler of a web link is the
        // bridge, and the real Custom Tabs browsers installed are Chrome and Firefox.
        assertEquals(
            "com.android.chrome",
            LoginBrowser.choose("com.microsoft.windows.userapp", listOf("com.android.chrome", "org.mozilla.firefox")),
        )
        // The bridge declares the service too, so it is in the list a device hands over.
        assertEquals(chrome, LoginBrowser.choose(bridge, listOf(bridge, chrome, firefox)))
        assertEquals(chrome, LoginBrowser.choose(bridge, listOf(firefox, chrome, bridge)))
        assertEquals(firefox, LoginBrowser.choose(bridge, listOf(firefox, bridge)))
        assertEquals("com.microsoft.windows.userapp", bridge)
    }

    @Test
    fun theBridgeAloneIsNoBrowserAndThePageGoesThePlainWay() {
        assertNull(LoginBrowser.choose(bridge, listOf(bridge)))
        assertNull(LoginBrowser.choose(bridge, emptyList()))
    }

    @Test
    fun withNoDefaultBrowserAnInstalledOneShowsTheTab() {
        assertEquals(chrome, LoginBrowser.choose(chooser, listOf(firefox, chrome)))
        assertEquals(firefox, LoginBrowser.choose(chooser, listOf(firefox)))
        // Nothing resolved at all.
        assertEquals(chrome, LoginBrowser.choose(null, listOf(chrome)))
        assertEquals(brave, LoginBrowser.choose("", listOf(brave)))
    }

    @Test
    fun aDefaultBrowserWithoutTabsGivesWayToOneThatHasThem() {
        assertEquals(chrome, LoginBrowser.choose("org.example.plainbrowser", listOf(chrome, firefox)))
    }

    @Test
    fun chromeComesFirstAndTheRestByName() {
        assertEquals(chrome, LoginBrowser.choose(chooser, listOf(brave, firefox, chrome)))
        // Without Chrome, the first by package name.
        assertEquals(brave, LoginBrowser.choose(chooser, listOf(firefox, brave)))
        assertEquals("com.aaa.browser", LoginBrowser.choose(chooser, listOf(firefox, "com.aaa.browser", brave)))
    }

    @Test
    fun theAnswerDoesNotDependOnTheOrderTheDeviceListsThemIn() {
        val installed = listOf(firefox, brave, "com.opera.browser", "com.vivaldi.browser")
        val first = LoginBrowser.choose(chooser, installed)
        assertEquals(first, LoginBrowser.choose(chooser, installed.reversed()))
        assertEquals(first, LoginBrowser.choose(chooser, installed.shuffled(java.util.Random(7))))
        assertEquals(first, LoginBrowser.choose(chooser, installed + installed))
        assertEquals(brave, first)
    }

    @Test
    fun withNoTabBrowserAtAllThePageGoesThePlainWay() {
        assertNull(LoginBrowser.choose(chrome, emptyList()))
        assertNull(LoginBrowser.choose(chooser, emptyList()))
        assertNull(LoginBrowser.choose(null, emptyList()))
        // A blank name is nobody.
        assertNull(LoginBrowser.choose(chooser, listOf("", " ")))
    }
}
