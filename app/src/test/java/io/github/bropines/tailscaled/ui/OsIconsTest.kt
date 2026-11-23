package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The share sheet's shortcut icons carry the OS logos as resources (a launcher draws a
 * drawable, not a Compose vector): each one must draw the same path as [OsIcons], or the
 * sheet and the app's device list show different marks.
 */
class OsIconsTest {
    private fun pathIn(drawable: String): String {
        val xml = File("src/main/res/drawable/$drawable.xml").readText()
        return Regex("""android:pathData="([^"]+)"""").findAll(xml).last().groupValues[1]
    }

    @Test
    fun shareTargetIconsDrawTheAppsLogos() {
        for ((drawable, path) in listOf(
            "ic_share_target_windows" to OsIcons.WINDOWS_PATH,
            "ic_share_target_macos" to OsIcons.APPLE_PATH,
            "ic_share_target_ios" to OsIcons.IOS_PATH,
            "ic_share_target_linux" to OsIcons.LINUX_PATH,
        )) {
            assertEquals(drawable, path, pathIn(drawable))
            assertEquals("${drawable}_fg", path, pathIn("${drawable}_fg"))
        }
    }
}
