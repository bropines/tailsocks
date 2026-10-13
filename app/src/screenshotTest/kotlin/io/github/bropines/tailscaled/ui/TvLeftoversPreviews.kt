package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.DeviceClass

/*
 * What the television's leftovers drew: the home screen's tile for remote management in the states
 * it tells apart (off is the ordinary TvHome), and the address a link shows where no browser
 * opens it. A render cannot press a key; the remote is checked on the emulator.
 */

@Composable
private fun TvHomeWith(tile: RemoteTile) =
    AdaptiveShowcase(DemoTailnet.data.copy(remoteTile = tile), device = DeviceClass.TV) {
        MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
    }

/** On and reachable, nobody paired yet. */
@PreviewTest @TvBothLanguages @Composable
fun TvHomeRemoteWaiting() = TvHomeWith(RemoteTile(RemoteTile.Kind.WAITING))

/** Devices are paired: how many. */
@PreviewTest @TvBothLanguages @Composable
fun TvHomeRemotePaired() = TvHomeWith(RemoteTile(RemoteTile.Kind.PAIRED, paired = 3))

/** The mode cannot receive connections (the native TUN engine, Root Mode's interface): the tile does not look ready. */
@PreviewTest @TvBothLanguages @Composable
fun TvHomeRemoteUnavailable() = TvHomeWith(RemoteTile(RemoteTile.Kind.UNAVAILABLE_IN_MODE))

/** A credit's link on a television with no browser: the address large, and a code for a phone's camera. */
@PreviewTest @TvBothLanguages @Composable
fun TvLinkAddress() = AdaptiveShowcase(device = DeviceClass.TV) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        ValueDialogContent(
            title = stringResource(R.string.main_dev_core),
            values = listOf(ShownValue(stringResource(R.string.link_address), "https://github.com/tailscale/tailscale", qr = true)),
            selected = 0,
            onSelect = {},
            strings = rememberShownValueStrings(),
            onDismiss = {}
        )
    }
}
