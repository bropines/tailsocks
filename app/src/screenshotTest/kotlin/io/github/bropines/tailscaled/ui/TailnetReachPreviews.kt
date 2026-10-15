package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.DeviceClass
import io.github.bropines.tailscaled.core.ExposureNet
import io.github.bropines.tailscaled.core.TailnetListener

// Settings → Local proxies → Reachable from the tailnet (TailnetReachSettings.kt), standing alone
// like the other cards of a section that does not render whole: as installed (everything closed,
// the HTTP and Taildrive proxies off), with SOCKS5 opened without a password and the Taildrive
// proxy without a login, and on the native engine, where the switches change nothing.

private val binds = mapOf(
    TailnetListener.SOCKS5 to "127.0.0.1:48115",
    TailnetListener.HTTP_PROXY to "",
    TailnetListener.DNS_PROXY to "127.0.0.1:1053",
    TailnetListener.TAILDRIVE_PROXY to "",
)

private val installed = TailnetReachState(
    binds = binds, open = emptySet(), net = ExposureNet.NETSTACK,
    tailnetIp = "100.64.0.5", socks5Login = false, driveLogin = false,
)

private val opened = installed.copy(
    binds = binds + (TailnetListener.TAILDRIVE_PROXY to "127.0.0.1:33445"),
    open = setOf(TailnetListener.SOCKS5, TailnetListener.TAILDRIVE_PROXY),
)

private val native = installed.copy(net = ExposureNet.VPN, open = setOf(TailnetListener.DNS_PROXY))

@Composable
private fun Cards(vararg states: TailnetReachState) = Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    for (s in states) TailnetReachContent(s, onSwitch = { _, _ -> }, onSetSocks5Password = {})
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=2000dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=2000dp,dpi=420", locale = "ru")
@Composable
fun TailnetReachCardPhone() = AdaptiveShowcase { Cards(installed, opened, native) }

@PreviewTest @TvBothLanguages @Composable
fun TailnetReachCardTv() = AdaptiveShowcase(device = DeviceClass.TV) { Cards(opened) }
