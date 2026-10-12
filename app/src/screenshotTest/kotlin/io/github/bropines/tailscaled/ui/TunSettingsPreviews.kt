package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TunEngines

// The TUN settings card under "VPN (TUN)" in Settings → Tunnel mode, on each engine,
// and its engine picker. The Settings previews show proxy mode, where the card is hidden.

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class TunSizes

@Composable
private fun TunCard(engine: String) = Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
    contentAlignment = Alignment.TopCenter
) {
    // Settings keeps a card this wide when it lays sections out in columns.
    Column(Modifier.widthIn(max = 560.dp)) {
        TunSettingsCard(
            engine = engine, onEngineChange = {},
            socks5 = "127.0.0.1:48115", onOpenProxies = {},
            ipv6 = false, onIpv6Change = {},
            tunAddress = "10.0.0.1/8", onTunAddressChange = {},
            excludedCidrs = "192.168.0.0/16,10.0.0.0/8,172.16.0.0/12", onExcludedCidrsChange = {},
            excludedAppsCount = 6, onOpenExcludedApps = {},
        )
    }
}

@PreviewTest @TunSizes @Composable
fun TunSettingsNative() = AdaptiveShowcase { TunCard(TunEngines.NATIVE) }

@PreviewTest @TunSizes @Composable
fun TunSettingsHev() = AdaptiveShowcase { TunCard(TunEngines.HEV) }

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Composable
fun TunEnginePicker() = AdaptiveShowcase {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        ) {
            PickerSheetContent(
                title = stringResource(R.string.tun_engine_title),
                options = tunEngineOptions(),
                selected = TunEngines.NATIVE,
                monospace = false,
                onRowClick = {}
            )
        }
    }
}
