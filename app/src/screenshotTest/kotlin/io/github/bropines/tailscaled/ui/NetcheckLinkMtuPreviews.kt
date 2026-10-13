package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.LinkMtuReading
import io.github.bropines.tailscaled.core.MtuVerdict

/*
 * The network check when the link cannot carry a direct connection's packets whole:
 * the one warning, in the protocol card, with its folded explanation. A link that is
 * fine adds nothing, so the report's own previews (NetcheckShowcase and the rest)
 * are the "no finding" side of this.
 */

/** Windows Subsystem for Android's eth0 as it was measured: the tunnel's own 1280. */
private val smallLink = LinkMtuReading("eth0", 1280, MtuVerdict.TooSmall(1280, 1360))

/** A phone in both languages, the narrowest two-pane window in Russian, and a tablet on its side. */
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "4-foldable-ru", device = "spec:width=840dp,height=900dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun NetcheckLinkMtuSmall() = AdaptiveShowcase(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy)) {
    CompositionLocalProvider(LocalDemoLinkMtu provides smallLink) { NetcheckScreen(onBack = {}) }
}

/** A television in both languages: the explanation unfolds with OK. */
@PreviewTest @TvBothLanguages @Composable
fun TvNetcheckLinkMtuSmall() = AdaptiveShowcase(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy), device = DeviceClass.TV) {
    CompositionLocalProvider(LocalDemoLinkMtu provides smallLink) { NetcheckScreen(onBack = {}) }
}

/**
 * The same television on a window tall enough to hold the whole pane, which a real screen has to
 * scroll to: the explanation is open there, because a fold inside the pane cannot be reached with
 * the remote's arrows.
 */
@PreviewTest
@Preview(name = "6-tv-tall", device = "spec:width=960dp,height=900dp,dpi=320")
@Preview(name = "6-tv-tall-ru", device = "spec:width=960dp,height=900dp,dpi=320", locale = "ru")
@Composable
fun TvNetcheckLinkMtuSmallTall() = AdaptiveShowcase(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy), device = DeviceClass.TV) {
    CompositionLocalProvider(LocalDemoLinkMtu provides smallLink) { NetcheckScreen(onBack = {}) }
}

/** A readable link with the report's other findings on screen: nothing is added. */
@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun NetcheckLinkMtuFine() = AdaptiveShowcase(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy)) {
    CompositionLocalProvider(LocalDemoLinkMtu provides LinkMtuReading("wlan0", 1500, MtuVerdict.Fine)) {
        NetcheckScreen(onBack = {})
    }
}
