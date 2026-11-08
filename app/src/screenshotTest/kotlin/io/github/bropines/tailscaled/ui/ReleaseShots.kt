package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal val demoTailcat = DemoTailcat(
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

/*
 * 5.7.0: the tailnet's devices in the system share sheet (core/ShareTargets.kt). The sheet
 * is the system's, not ours, so this draws its shape around our share targets — the
 * shortcut icons as published, the app's badge on each — with the made-up devices of
 * DemoTailnet. Illustration only: no screen of the app looks like this.
 */
private data class ShareDemoTarget(val name: String, val phone: Boolean)

private val shareDemoTargets = listOf(
    ShareDemoTarget("desktop-home", phone = false),
    ShareDemoTarget("pixel-8", phone = true),
    ShareDemoTarget("homelab-nas", phone = false),
    ShareDemoTarget("macbook-air", phone = false),
)

@Composable
private fun ShareDemoIcon(phone: Boolean) {
    androidx.compose.foundation.layout.Box(Modifier.size(64.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(60.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(colorResource(io.github.bropines.tailscaled.R.color.shortcut_background))
        ) {
            Image(
                painterResource(if (phone) io.github.bropines.tailscaled.R.drawable.ic_share_target_phone_fg else io.github.bropines.tailscaled.R.drawable.ic_share_target_computer_fg),
                contentDescription = null, modifier = Modifier.fillMaxSize()
            )
        }
        androidx.compose.foundation.layout.Box(
            Modifier.size(24.dp).align(androidx.compose.ui.Alignment.BottomEnd)
                .clip(androidx.compose.foundation.shape.CircleShape)
        ) {
            Image(painterResource(io.github.bropines.tailscaled.R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(io.github.bropines.tailscaled.R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
    }
}

@PreviewTest
@Preview(name = "en", device = "spec:width=411dp,height=420dp,dpi=420", locale = "en")
@Preview(name = "ru", device = "spec:width=411dp,height=420dp,dpi=420", locale = "ru")
@Composable
fun ReleaseShareSheet() = Showcase {
    val ru = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ru"
    val sheet = androidx.compose.ui.graphics.Color(0xFF1C1F1B)
    val chip = androidx.compose.ui.graphics.Color(0xFF262A25)
    val onSheet = androidx.compose.ui.graphics.Color(0xFFE3E3DC)
    Surface(color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(top = 24.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(sheet).padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFF8C9188))
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Text(if (ru) "Отправка 1 файла" else "Sharing 1 file", color = onSheet, fontSize = 22.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(chip).padding(18.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.InsertDriveFile, null, tint = onSheet)
                androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                androidx.compose.material3.Text("holiday-photos.zip", color = onSheet, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF2E322C))
            androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                shareDemoTargets.forEach { t ->
                    androidx.compose.foundation.layout.Column(
                        Modifier.width(80.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        ShareDemoIcon(t.phone)
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Text(
                            t.name, color = onSheet, fontSize = 13.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
