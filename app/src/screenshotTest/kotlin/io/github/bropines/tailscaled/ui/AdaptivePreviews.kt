package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The window sizes the adaptive layouts (ui/Adaptive.kt) are designed for and
 * checked in. Names sort narrow to wide, so a directory listing reads as a
 * row of a contact sheet. Each Tablet*Previews.kt renders its screens through
 * these; render one class at a time, all of them at once may run out of memory.
 */

/** Compact, medium, expanded and large windows: a phone, a 7-8" tablet and the
 *  author's Lenovo upright, an unfolded book-style foldable, the Lenovo on its side. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "2-tablet7", device = "spec:width=600dp,height=960dp,dpi=213")
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class WindowSizes

/** The two shapes a phone comes in. What a layout change must leave exactly as it was:
 *  compare these renders before and after. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
annotation class PhoneSizes

/** A television. One size: 720p at tvdpi, 1080p at xhdpi and 4K at xxxhdpi are all 960 × 540 dp;
 *  the renderer draws it at 1080p. On its own the size is a small tablet's — the class is what
 *  makes it a television: `AdaptiveShowcase(device = DeviceClass.TV)`. */
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
annotation class TvSizes

/** [TvSizes] in both languages: the screens whose words were written for a television. */
@Preview(name = "6-tv", device = "spec:width=960dp,height=540dp,dpi=320")
@Preview(name = "6-tv-ru", device = "spec:width=960dp,height=540dp,dpi=320", locale = "ru")
annotation class TvBothLanguages

/**
 * [Showcase]'s look with any demo: dark, emerald, AMOLED black. [device] draws the screen
 * as that class of device (ui/DeviceClass.kt); without it the renderer is a handheld. It is
 * provided outside the theme, which is where the class is decided.
 */
@Composable
fun AdaptiveShowcase(data: DemoData? = DemoTailnet.data, device: DeviceClass? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPreviewDeviceClass provides device) {
        TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
            CompositionLocalProvider(LocalDemo provides data) { content() }
        }
    }
}
