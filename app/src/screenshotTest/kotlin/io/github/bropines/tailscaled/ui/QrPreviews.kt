package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The QR sheet's content, without the sheet (the renderer takes the first
 * frame, before a sheet has slid in): a TailCat server's connect command —
 * a real-length address, twice — and a served link, light and dark.
 */

@Preview(name = "qr-phone", device = "spec:width=393dp,height=852dp,dpi=420")
@Preview(name = "qr-phone-ru", device = "spec:width=393dp,height=852dp,dpi=420", locale = "ru")
@Preview(name = "qr-phone-landscape", device = "spec:width=852dp,height=393dp,dpi=420")
annotation class QrGeometries

private const val SAMPLE_ADDRESS =
    "tcpGFwWCCa_YV4eQYH9TzAT1pAJ32RTL5JeIt1ik7V836CqQRlYGFrWCBwRbtA4ktTae4jSlp20ioJ33iVGfRoyYz1ctqW8oW4KWFxWCDbzpV_8lYJxS7diCnBrDy5Bkc56U5rutaJUl4HD5PBW2FpBA"

@Composable
private fun QrSample(dark: Boolean, title: String, text: String, help: String) =
    QrSample(dark, title, listOf(QrVariant("", text, help)))

@Composable
private fun QrSample(dark: Boolean, title: String, variants: List<QrVariant>, selected: Int = 0) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
            QrSheetContent(
                title = title,
                variants = variants,
                selected = selected,
                onSelect = {},
                labels = QrLabels(
                    image = stringResource(R.string.qr_image),
                    tooLong = stringResource(R.string.qr_too_long),
                    copy = stringResource(R.string.action_copy),
                    share = stringResource(R.string.qr_share),
                    showText = stringResource(R.string.qr_show_text),
                    hideText = stringResource(R.string.qr_hide_text),
                ),
                onCopy = {},
                onShare = {}
            )
        }
    }
}

private const val SAMPLE_SERVER_COMMAND =
    "tailcat forward $SAMPLE_ADDRESS 5555 8080\ntailcat socks --listen=127.0.0.1:1080 $SAMPLE_ADDRESS"

@Composable
private fun tailcatServerVariants() = listOf(
    QrVariant(stringResource(R.string.qr_tab_link), DeepLinks.tailcatAddLink(SAMPLE_SERVER_COMMAND), stringResource(R.string.qr_tailcat_server_link_help)),
    QrVariant(stringResource(R.string.qr_tab_command), SAMPLE_SERVER_COMMAND, stringResource(R.string.qr_tailcat_server_help)),
)

@PreviewTest @QrGeometries @Composable
fun QrTailcatServerDark() = QrSample(
    dark = true,
    title = stringResource(R.string.qr_tailcat_server_title),
    variants = tailcatServerVariants()
)

@PreviewTest @Preview(name = "qr-tailcat-command", device = "spec:width=393dp,height=852dp,dpi=420") @Composable
fun QrTailcatServerCommandLight() = QrSample(
    dark = false,
    title = stringResource(R.string.qr_tailcat_server_title),
    variants = tailcatServerVariants(),
    selected = 1
)

@PreviewTest @QrGeometries @Composable
fun QrServeLinkLight() = QrSample(
    dark = false,
    title = stringResource(R.string.qr_serve_title),
    text = "https://pixel-9-pro.tail4a2c9.ts.net:8443/grafana",
    help = stringResource(R.string.qr_serve_public_help)
)

@PreviewTest @Preview(name = "qr-too-long", device = "spec:width=393dp,height=852dp,dpi=420") @Composable
fun QrTooLong() = QrSample(dark = true, title = "x", text = "x".repeat(3000), help = "")
