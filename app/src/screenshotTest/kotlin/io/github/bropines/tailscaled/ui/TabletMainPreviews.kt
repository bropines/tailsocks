package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

// The dashboard in every window size (AdaptivePreviews.kt), and its states on the two tablet
// windows: connected with an exit node and without one, stopped, connecting, with problems.
// The Phone* ones are what the large-window layouts must leave exactly as they were: compare
// them against renders made before a change.

/** The author's tablet, upright and on its side. */
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
internal annotation class TabletSizes

private val relayTrouble = """
    [{"Code": "no-derp-connection", "Title": "No DERP connection",
      "Text": "Tailscale could not connect to any relay server. Check your internet connection.",
      "Severity": "medium", "ImpactsConnectivity": true, "BrokenSinceMs": 0}]
""".trimIndent()

private val twoTroubles = """
    [{"Code": "no-derp-connection", "Title": "No DERP connection",
      "Text": "Tailscale could not connect to any relay server. Check your internet connection.",
      "Severity": "high", "ImpactsConnectivity": true, "BrokenSinceMs": 0},
     {"Code": "not-in-map-poll", "Title": "Not connected to the coordination server",
      "Text": "Unable to connect to the Tailscale coordination server to synchronize the state of your tailnet.",
      "Severity": "medium", "ImpactsConnectivity": true, "BrokenSinceMs": 0}]
""".trimIndent()

private val stopped = DemoTailnet.data.copy(running = false, exitNodeIp = null)
private val troubled = DemoTailnet.data.copy(healthJson = relayTrouble)
// The status document still names exit-frankfurt as the exit node; without one, nobody is.
private val noExit = DemoTailnet.data.copy(
    statusJson = DemoTailnet.statusJson.replace("\"ExitNode\": true", "\"ExitNode\": false"),
    exitNodeName = null,
    exitNodeIp = null,
)

@Composable
private fun Main() = MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })

@PreviewTest @WindowSizes @Composable
fun TabletMain() = AdaptiveShowcase { Main() }

@PreviewTest @WindowSizes @Composable
fun TabletMainStopped() = AdaptiveShowcase(stopped) { Main() }

@PreviewTest @WindowSizes @Composable
fun TabletMainProblem() = AdaptiveShowcase(troubled) { Main() }

/** Connected, every app's traffic on its own way out: no exit node chosen. */
@PreviewTest @TabletSizes @Composable
fun TabletMainNoExit() = AdaptiveShowcase(noExit) { Main() }

/** Two problems at once, no exit node: the banner under the card at its tallest. */
@PreviewTest @TabletSizes @Composable
fun TabletMainIssues() = AdaptiveShowcase(noExit.copy(healthJson = twoTroubles)) { Main() }

/** The daemon is up and still waiting for its map. */
@PreviewTest @TabletSizes @Composable
fun TabletMainConnecting() = AdaptiveShowcase(DemoTailnet.data.copy(backendState = "Starting")) { Main() }

/** The longer words: every label on the dashboard in Russian. */
@PreviewTest
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
@Composable
fun TabletMainRu() = AdaptiveShowcase { Main() }

/** A large system font on the tablet on its side: rows grow, and fewer of them fit. */
@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240", fontScale = 1.5f)
@Composable
fun TabletMainLargeFont() = AdaptiveShowcase { Main() }

/** A desktop-sized window (a 13" tablet on its side, a freeform window): the menu in one row. */
@PreviewTest
@Preview(name = "6-desktop", device = "spec:width=1920dp,height=1080dp,dpi=160")
@Composable
fun TabletMainDesktop() = AdaptiveShowcase { Main() }

@PreviewTest @PhoneSizes @Composable
fun PhoneMain() = AdaptiveShowcase { Main() }

@PreviewTest @PhoneSizes @Composable
fun PhoneMainStopped() = AdaptiveShowcase(stopped) { Main() }

@PreviewTest @PhoneSizes @Composable
fun PhoneMainProblem() = AdaptiveShowcase(troubled) { Main() }

@PreviewTest @PhoneSizes @Composable
fun PhoneMainNoExit() = AdaptiveShowcase(noExit) { Main() }

/** A foldable open like a book: the status on one half, the menu on the other. */
@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun FoldMainBook() = AdaptiveShowcase {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { Main() }
}
