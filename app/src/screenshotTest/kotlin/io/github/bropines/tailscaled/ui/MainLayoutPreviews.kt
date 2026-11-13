package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.MainItem
import io.github.bropines.tailscaled.core.MainLayout

/*
 * The main screen as the person arranged it (core/MainLayout.kt) and as its own editor
 * (ui/MainCanvas.kt, ui/MainLayoutEditor.kt), on the demo tailnet.
 *
 * MainLayoutUntouched is the one to hold against a render made before a change: with nothing
 * arranged the screen must be the screen it was. A render cannot hold a finger down: picking a
 * cell up, the cells making room and the page scrolling under a drag are checked on a device.
 */

private val untouched = MainLayout.default(remoteTile = false)
private val stopped = DemoTailnet.data.copy(running = false, exitNodeIp = null)

@Composable
private fun Main(layout: MainLayout? = null, editing: Boolean = false, data: DemoData = DemoTailnet.data, device: DeviceClass? = null) =
    AdaptiveShowcase(data, device = device) {
        MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }, previewLayout = layout, previewEditing = editing)
    }

// ─── In use ──────────────────────────────────────────────────────────────────────────────────

/** Nothing arranged: read off the old switches, which nobody flipped. */
@PreviewTest @ManagedPhoneSizes @Composable
fun MainLayoutUntouched() = Main()

@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutOneColumn() = Main(untouched.withColumns(1))

@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutThreeColumns() = Main(untouched.withColumns(3))

@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutFourColumns() = Main(untouched.withColumns(4))

/** The main button under the thumb: the tiles first, the card and its lines after them. */
private val cardBelow = untouched
    .move(MainItem.EXIT_NODE, MainItem.TAILNET_MAP)
    .move(MainItem.STATUS, MainItem.EXIT_NODE)
    .move(MainItem.SUMMARY, MainItem.STATUS)

@PreviewTest @ManagedPhoneSizes @Composable
fun MainLayoutCardBelow() = Main(cardBelow)

/** The card between two groups of tiles. */
@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutCardBetween() = Main(
    untouched.remove(MainItem.EXIT_NODE).move(MainItem.STATUS, MainItem.FILES).move(MainItem.SUMMARY, MainItem.STATUS)
)

/** The added destinations, some of them across the row with their second line. */
private val crowded = untouched
    .remove(MainItem.TAILNET_MAP)
    .add(MainItem.ADMIN).add(MainItem.SCAN).add(MainItem.TAILCAT).add(MainItem.TAILDRIVE)
    .add(MainItem.EXIT_PICKER).setWide(MainItem.EXIT_PICKER, true)
    .setWide(MainItem.PEERS, true)
    .add(MainItem.REMOTE)
    .add(MainItem.SPEED_GRAPH).add(MainItem.TIMELINE)
    .withColumns(3)

@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutCrowded() = Main(crowded)

/** A window with the menu beside the status reads the same list: the tiles in their order, the blocks in theirs. */
@PreviewTest
@Preview(name = "1-phone-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun MainLayoutCrowdedWide() = Main(crowded.remove(MainItem.SPEED_GRAPH).remove(MainItem.TIMELINE))

// ─── Being arranged ──────────────────────────────────────────────────────────────────────────

@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutEditing() = Main(untouched, editing = true)

/** Not connected: the blocks that have nothing to show are there by name, to be moved all the same. */
@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutEditingStopped() = Main(untouched.add(MainItem.SPEED_GRAPH), editing = true, data = stopped)

@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutEditingCrowded() = Main(crowded, editing = true)

/** In a wide window the editor is still the flow, and says what the window will make of it. */
@PreviewTest
@Preview(name = "1-phone-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun MainLayoutEditingWide() = Main(untouched, editing = true)

/** What the sheet lists for a screen nobody added anything to. */
@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutPalette() = AdaptiveShowcase {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
            MainPaletteContent(
                rows = paletteRows(untouched.addable { true }),
                title = stringResource(R.string.main_layout_palette_title),
                empty = stringResource(R.string.main_layout_palette_empty),
                addLabel = stringResource(R.string.main_layout_add),
                onAdd = {},
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun MainLayoutPaletteEmpty() = AdaptiveShowcase {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
            MainPaletteContent(
                rows = emptyList(),
                title = stringResource(R.string.main_layout_palette_title),
                empty = stringResource(R.string.main_layout_palette_empty),
                addLabel = stringResource(R.string.main_layout_add),
                onAdd = {},
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

/** Settings → Appearance → what is off the screen (ui/MainHiddenItems.kt), for a screen nobody arranged. */
@PreviewTest @PhoneBothLanguages @Composable
fun MainLayoutHidden() = AdaptiveShowcase {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
            MainHiddenContent(
                rows = paletteRows(untouched.addable { true }),
                onOpen = {},
                onRestore = {},
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
