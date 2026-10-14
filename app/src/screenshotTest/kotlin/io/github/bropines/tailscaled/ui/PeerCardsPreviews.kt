package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.models.StatusResponse

/*
 * A peer's details with cards open, drawn tall enough to show all of them: which column each card
 * stands in, what an open card pushes down, and the rows a remote stops on. The window is the
 * pane's own — nothing here presses a key, so a ring is not drawn; the emulator shows those.
 * macbook-air is the peer whose route card carries an explanation beside its lead's button.
 */

private val cardsPathFacts = PeerPathFacts(
    derpCities = mapOf("fra" to "Frankfurt", "ams" to "Amsterdam", "hel" to "Helsinki"),
    relayServers = setOf("100.72.5.101"),
)

@Composable
private fun Details(peerId: String, open: Set<PeerDetailGroup>, device: DeviceClass? = null) =
    AdaptiveShowcase(device = device) {
        val status = remember { AppJson.decodeFromString<StatusResponse>(DemoTailnet.statusJson) }
        val peers = remember { listablePeers(status) }
        val peer = peers.first { it.id == peerId }
        CompositionLocalProvider(
            LocalPeerPathNames provides PeerPathNames(peers, cardsPathFacts),
            LocalPeerCardsOpen provides open,
        ) {
            Surface(Modifier.fillMaxSize()) {
                PeerDetailsModal(
                    peerAt = { if (it == 0) PeerPage(peer, isSelf = false) else null },
                    selfAddress = "100.101.34.12",
                    onDismiss = {},
                    inPane = true
                )
            }
        }
    }

/** A column of cards, and the two columns of a wide pane, on a television in both languages. */
@Preview(name = "6-tv-narrow", device = "spec:width=500dp,height=1700dp,dpi=320")
@Preview(name = "6-tv-narrow-ru", device = "spec:width=500dp,height=1700dp,dpi=320", locale = "ru")
annotation class TvNarrowTall

@Preview(name = "6-tv-wide", device = "spec:width=1100dp,height=1400dp,dpi=320")
@Preview(name = "6-tv-wide-ru", device = "spec:width=1100dp,height=1400dp,dpi=320", locale = "ru")
annotation class TvWideTall

/** Addresses open: its rows act (a code for the address and the names, larger text for the rest). */
@PreviewTest @TvNarrowTall @Composable
fun PeerCardsTvAddressesOpen() = Details("n1", setOf(PeerDetailGroup.ADDRESSES), DeviceClass.TV)

/** Two columns, Addresses open: Device and Traffic stay under it, Route's column is as it was. */
@PreviewTest @TvWideTall @Composable
fun PeerCardsTvWideAddressesOpen() = Details("n1", setOf(PeerDetailGroup.ADDRESSES), DeviceClass.TV)

/** The route card with an explanation and a button beside its lead, open beside Capabilities. */
@PreviewTest @TvWideTall @Composable
fun PeerCardsTvWideRouteOpen() = Details("n5", setOf(PeerDetailGroup.ROUTE, PeerDetailGroup.CAPABILITIES), DeviceClass.TV)

/** Every card open on a television. */
@PreviewTest @TvWideTall @Composable
fun PeerCardsTvWideAllOpen() = Details("n1", PeerDetailGroup.entries.toSet(), DeviceClass.TV)

// The same cards on a tablet and a phone: the column of each card does not depend on what is open.
@PreviewTest
@Preview(name = "5-tablet-pane", device = "spec:width=840dp,height=1400dp,dpi=240")
@Composable
fun PeerCardsTabletAddressesOpen() = Details("n1", setOf(PeerDetailGroup.ADDRESSES))

@PreviewTest
@Preview(name = "5-tablet-pane", device = "spec:width=840dp,height=1400dp,dpi=240")
@Composable
fun PeerCardsTabletDeviceOpen() = Details("n1", setOf(PeerDetailGroup.DEVICE))

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=1500dp,dpi=420")
@Composable
fun PeerCardsPhoneAddressesOpen() = Details("n1", setOf(PeerDetailGroup.ADDRESSES))
