package io.github.bropines.tailscaled.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The OS logos Material's own set does not have: Windows, Apple, iOS and Linux (Tux), from
 * Material Design Icons by Pictogrammers (@mdi/svg 7.4.47, Apache-2.0; listed in the app's
 * open-source licenses). Material draws macOS and Windows as two monitors told apart by a
 * stand, which read alike in a 40 dp circle; these are the marks people know.
 *
 * The share sheet's shortcut icons (res/drawable/ic_share_target_*_fg.xml) carry the same
 * paths, because a launcher draws a resource and not a Compose vector; OsIconsTest keeps the
 * two copies equal.
 */
internal object OsIcons {
    internal const val WINDOWS_PATH =
        "M3,12V6.75L9,5.43V11.91L3,12M20,3V11.75L10,11.9V5.21L20,3M3,13L9,13.09V19.9L3,18.75V13M20,13.25V22L10,20.09V13.1L20,13.25Z"

    internal const val APPLE_PATH =
        "M18.71,19.5C17.88,20.74 17,21.95 15.66,21.97C14.32,22 13.89,21.18 12.37,21.18C10.84,21.18 10.37,21.95 9.1,22C7.79,22.05 6.8,20.68 5.96,19.47C4.25,17 2.94,12.45 4.7,9.39C5.57,7.87 7.13,6.91 8.82,6.88C10.1,6.86 11.32,7.75 12.11,7.75C12.89,7.75 14.37,6.68 15.92,6.84C16.57,6.87 18.39,7.1 19.56,8.82C19.47,8.88 17.39,10.1 17.41,12.63C17.44,15.65 20.06,16.66 20.09,16.67C20.06,16.74 19.67,18.11 18.71,19.5M13,3.5C13.73,2.67 14.94,2.04 15.94,2C16.07,3.17 15.6,4.35 14.9,5.19C14.21,6.04 13.07,6.7 11.95,6.61C11.8,5.46 12.36,4.26 13,3.5Z"

    internal const val IOS_PATH =
        "M2.09 16.8H3.75V9.76H2.09M2.92 8.84C3.44 8.84 3.84 8.44 3.84 7.94C3.84 7.44 3.44 7.04 2.92 7.04C2.4 7.04 2 7.44 2 7.94C2 8.44 2.4 8.84 2.92 8.84M9.25 7.06C6.46 7.06 4.7 8.96 4.7 12C4.7 15.06 6.46 16.96 9.25 16.96C12.04 16.96 13.8 15.06 13.8 12C13.8 8.96 12.04 7.06 9.25 7.06M9.25 8.5C10.96 8.5 12.05 9.87 12.05 12C12.05 14.15 10.96 15.5 9.25 15.5C7.54 15.5 6.46 14.15 6.46 12C6.46 9.87 7.54 8.5 9.25 8.5M14.5 14.11C14.57 15.87 16 16.96 18.22 16.96C20.54 16.96 22 15.82 22 14C22 12.57 21.18 11.77 19.23 11.32L18.13 11.07C16.95 10.79 16.47 10.42 16.47 9.78C16.47 9 17.2 8.45 18.28 8.45C19.38 8.45 20.13 9 20.21 9.89H21.84C21.8 8.2 20.41 7.06 18.29 7.06C16.21 7.06 14.73 8.21 14.73 9.91C14.73 11.28 15.56 12.13 17.33 12.53L18.57 12.82C19.78 13.11 20.27 13.5 20.27 14.2C20.27 15 19.47 15.57 18.31 15.57C17.15 15.57 16.26 15 16.16 14.11H14.5Z"

    internal const val LINUX_PATH =
        "M14.62,8.35C14.2,8.63 12.87,9.39 12.67,9.54C12.28,9.85 11.92,9.83 11.53,9.53C11.33,9.37 10,8.61 9.58,8.34C9.1,8.03 9.13,7.64 9.66,7.42C11.3,6.73 12.94,6.78 14.57,7.45C15.06,7.66 15.08,8.05 14.62,8.35M21.84,15.63C20.91,13.54 19.64,11.64 18,9.97C17.47,9.42 17.14,8.8 16.94,8.09C16.84,7.76 16.77,7.42 16.7,7.08C16.5,6.2 16.41,5.3 16,4.47C15.27,2.89 14,2.07 12.16,2C10.35,2.05 9,2.81 8.21,4.4C8,4.83 7.85,5.28 7.75,5.74C7.58,6.5 7.43,7.29 7.25,8.06C7.1,8.71 6.8,9.27 6.29,9.77C4.68,11.34 3.39,13.14 2.41,15.12C2.27,15.41 2.13,15.7 2.04,16C1.85,16.66 2.33,17.12 3.03,16.96C3.47,16.87 3.91,16.78 4.33,16.65C4.74,16.5 4.9,16.6 5,17C5.65,19.15 7.07,20.66 9.24,21.5C13.36,23.06 18.17,20.84 19.21,16.92C19.28,16.65 19.38,16.55 19.68,16.65C20.14,16.79 20.61,16.89 21.08,17C21.57,17.09 21.93,16.84 22,16.36C22.03,16.1 21.94,15.87 21.84,15.63"

    val Windows: ImageVector by lazy { icon("Windows", WINDOWS_PATH) }
    val Apple: ImageVector by lazy { icon("Apple", APPLE_PATH) }
    val Ios: ImageVector by lazy { icon("iOS", IOS_PATH) }
    val Linux: ImageVector by lazy { icon("Linux", LINUX_PATH) }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = "OsIcons.", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
