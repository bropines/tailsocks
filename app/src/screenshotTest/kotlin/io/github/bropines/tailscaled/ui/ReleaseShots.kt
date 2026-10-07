package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppIcons
import io.github.bropines.tailscaled.core.TailcatConnection
import io.github.bropines.tailscaled.core.TailcatServerConfig
import io.github.bropines.tailscaled.core.TailcatServerStatus
import io.github.bropines.tailscaled.core.TailcatStatus

/*
 * Screens for the release notes on GitHub, in the look of the README shots (Showcase):
 * the ones the other preview files do not already draw that way. Each in English and
 * Russian, for the two halves of the notes.
 */

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseLicenses() = Showcase { LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}) }

@PreviewTest
@Preview(name = "en", device = "spec:width=411dp,height=1100dp,dpi=420", locale = "en")
@Preview(name = "ru", device = "spec:width=411dp,height=1100dp,dpi=420", locale = "ru")
@Composable
fun ReleaseAppIcons() = Showcase {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        AppIconSheetContent(selected = AppIcons.DEFAULT, onPick = {})
    }
}

/** The interface scale (Settings → Appearance) as it applies: the whole screen at [scale]. */
@Composable
private fun Scaled(scale: Float) = Showcase {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
        MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
    }
}

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseScale80() = Scaled(0.8f)

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReleaseScale120() = Scaled(1.2f)

/** A made-up TailCat setup: two connections up (one with a SOCKS5 proxy), one off, and the phone serving a port. */
private val demoTailcat = DemoTailcat(
    publicKey = "nodekey:5c1e8a07d2b94f6e31a8c0d7e9b2f4a6c8e0d1b3f5a7c9e2d4b6f8a0c2e4b6d8",
    connections = listOf(
        TailcatConnection(id = "nas", name = "home-nas", address = "tcpGFwWCCaDemoHomeNas", ports = "8080, 2222:22", enabled = true),
        TailcatConnection(id = "office", name = "office", address = "tcpGFwWCCaDemoOffice", socks = 1081, socksUser = "u", socksPass = "p", enabled = true),
        TailcatConnection(id = "media", name = "media-box", address = "tcpGFwWCCaDemoMedia", ports = "8096"),
    ),
    statuses = mapOf(
        "nas" to TailcatStatus(state = "forwarding", listening = listOf("127.0.0.1:8080", "127.0.0.1:2222"), path = "direct", latencyMs = 18, active = 2, served = 41),
        "office" to TailcatStatus(state = "forwarding", socks = "127.0.0.1:1081", path = "fra", latencyMs = 64, active = 5, served = 230),
    ),
    serverAddress = "tcpGFwWCCaDemoThisPhoneServerAddress",
    serverConfig = TailcatServerConfig(enabled = true, ports = "5555", allowed = "nodekey:9a8b7c6d5e4f30211203f4e5d6c7b8a9"),
    serverStatus = TailcatServerStatus(state = "serving", active = 1, served = 12, clients = 1),
)

@PreviewTest
@Preview(name = "en", device = "spec:width=411dp,height=1300dp,dpi=420", locale = "en")
@Preview(name = "ru", device = "spec:width=411dp,height=1300dp,dpi=420", locale = "ru")
@Composable
fun ReleaseTailcat() = Showcase {
    CompositionLocalProvider(LocalDemo provides DemoTailnet.data.copy(tailcat = demoTailcat)) {
        TailcatScreen(onBack = {})
    }
}
