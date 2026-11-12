package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.ConnectionEvent
import io.github.bropines.tailscaled.core.ConnectionKind
import io.github.bropines.tailscaled.core.ConnectionSnapshot
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

// The connection timeline (ConnectionTimeline.kt) over a made-up day, so it can be judged without
// waiting one out: a night's stop, a morning with a lost network, a sign-in that waited for the
// user, a process the system killed, and a short outage a few minutes ago — every kind the bar
// can draw. Shown at each window, with a stretch selected, and empty as on a fresh install.

private const val MIN = 60_000L

/** A fixed "now", so the picture does not change from run to run: 2026-11-12 15:30 UTC. */
private const val NOW = 1_794_497_400_000L

private fun at(minutesAgo: Int, kind: ConnectionKind) = ConnectionEvent(NOW - minutesAgo * MIN, kind)

private val day = ConnectionSnapshot(
    events = listOf(
        at(26 * 60, ConnectionKind.CONNECTED),
        at(17 * 60, ConnectionKind.STOPPED),
        at(9 * 60 + 30, ConnectionKind.RECOVERING),
        at(9 * 60 + 29, ConnectionKind.NEEDS_YOU),
        at(8 * 60 + 50, ConnectionKind.CONNECTED),
        // The system killed the process; the service came back forty minutes later.
        at(7 * 60 + 10, ConnectionKind.NO_DATA),
        at(6 * 60 + 30, ConnectionKind.RECOVERING),
        at(6 * 60 + 29, ConnectionKind.CONNECTED),
        at(4 * 60 + 12, ConnectionKind.WAITING_FOR_NETWORK),
        at(4 * 60 + 5, ConnectionKind.RECOVERING),
        at(4 * 60 + 4, ConnectionKind.CONNECTED),
        at(2 * 60 + 40, ConnectionKind.TROUBLE),
        at(2 * 60 + 22, ConnectionKind.CONNECTED),
        // Half a minute: a mark, not a stretch.
        at(21, ConnectionKind.WAITING_FOR_NETWORK),
        ConnectionEvent(NOW - 21 * MIN + 30_000L, ConnectionKind.CONNECTED),
    ),
    aliveUntil = NOW,
)

private val fresh = ConnectionSnapshot(emptyList(), NOW)
private val stopped = ConnectionSnapshot(listOf(at(30 * 60, ConnectionKind.STOPPED)), NOW)

@Composable
private fun Cards(tvFocus: Boolean = false) = Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    ConnectionTimelineContent(day, NOW, initialHours = 1, initialSelection = NOW - 21 * MIN + 10_000L)
    ConnectionTimelineContent(day, NOW, initialHours = 6, initialSelection = NOW - 250 * MIN, barFocused = tvFocus)
    ConnectionTimelineContent(day, NOW, initialHours = 24)
    ConnectionTimelineContent(stopped, NOW)
    ConnectionTimelineContent(fresh, NOW)
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=1000dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=1000dp,dpi=420", locale = "ru")
@Preview(name = "1-phone-zh", device = "spec:width=411dp,height=1000dp,dpi=420", locale = "zh-rCN")
@Preview(name = "0-narrow", device = "spec:width=320dp,height=1000dp,dpi=320", fontScale = 1.3f)
@Preview(name = "4-tablet", device = "spec:width=840dp,height=1000dp,dpi=240")
@Composable
fun ConnectionTimelinePhone() = AdaptiveShowcase { Cards() }

/** The light scheme: the bar's colours are the theme's, so they must read on both. */
@PreviewTest
@Preview(name = "1-phone-light", device = "spec:width=411dp,height=1000dp,dpi=420")
@Composable
fun ConnectionTimelineLight() =
    TailSocksTheme(appTheme = "light", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = false) { Cards() }

@PreviewTest @TvBothLanguages @Composable
fun ConnectionTimelineTv() = AdaptiveShowcase(device = DeviceClass.TV) { Cards(tvFocus = true) }
