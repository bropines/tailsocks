package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

// The peers list in every window size (AdaptivePreviews.kt): from expanded up, list and
// details side by side; a phone keeps its list and its sheet (PhoneSizes, compared by eye
// against the same renders made before the change).

@PreviewTest @WindowSizes @Composable
fun TabletPeers() = AdaptiveShowcase { PeersScreen(onBack = {}) }

/** A peer picked in the list: its row outlined, its details in the pane. Two-pane windows
 *  only — elsewhere the details are a sheet, which the renderer does not draw. */
@PreviewTest
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletPeersPicked() = AdaptiveShowcase { PeersScreen(onBack = {}, initialPeerId = "n3") }

/** A search that matches nothing: the pane says what it would show. */
@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletPeersNoMatch() = AdaptiveShowcase { PeersScreen(onBack = {}, initialQuery = "printer") }

@PreviewTest @PhoneSizes @Composable
fun PhonePeers() = AdaptiveShowcase { PeersScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhonePeersSearch() = AdaptiveShowcase { PeersScreen(onBack = {}, initialQuery = "exit") }

/** A foldable open like a book: the list on one half, the details on the other. */
@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun FoldPeersBook() = AdaptiveShowcase {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { PeersScreen(onBack = {}) }
}

/** The service stopped: no list to pick from, so no panes — the message takes the width. */
@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletPeersStopped() = AdaptiveShowcase(DemoTailnet.data.copy(running = false)) { PeersScreen(onBack = {}) }
