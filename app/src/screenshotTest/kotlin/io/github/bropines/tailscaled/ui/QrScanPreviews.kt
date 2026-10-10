package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The QR scanner without a camera: the renderer has none, so a picture of a
 * code on a desk stands in for the preview. Its states without the camera —
 * the reason shown while Android asks, refused, refused for good, no camera —
 * and the sheet for a code that is neither an app link nor a TailCat address
 * (its content, without the sheet: the renderer takes the first frame).
 */

@Preview(name = "scan-phone", device = "spec:width=393dp,height=852dp,dpi=420")
@Preview(name = "scan-phone-ru", device = "spec:width=393dp,height=852dp,dpi=420", locale = "ru")
@Preview(name = "scan-phone-landscape", device = "spec:width=852dp,height=393dp,dpi=420")
annotation class ScanGeometries

@Composable
private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** What the camera might see: a code on a screen, a little askew, on a dim desk. */
@Composable
private fun FakeCamera() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3F44), Color(0xFF1E2124), Color(0xFF2B2722)))),
        contentAlignment = Alignment.Center
    ) {
        QrCodeImage(
            text = "tailsocks://tailcat/add?cmd=tcpGFwWCCa_YV4eQYH9TzAT1pAJ32RTL5JeIt1ik7V836CqQRlYGFrWCBwRbtA4k",
            modifier = Modifier.size(190.dp).rotate(-6f)
        )
    }
}

@Composable
private fun Scanner(dark: Boolean, access: CameraAccess, torch: Boolean? = null) = Themed(dark) {
    QrScanContent(
        access = access,
        torch = torch,
        reading = false,
        onBack = {},
        onAllow = {},
        onSettings = {},
        onRetry = {},
        onGallery = {},
        onTorch = {},
        camera = { FakeCamera() }
    )
}

@PreviewTest @ScanGeometries @Composable
fun QrScanCamera() = Scanner(dark = false, access = CameraAccess.GRANTED, torch = false)

@PreviewTest @ScanGeometries @Composable
fun QrScanAskingLight() = Scanner(dark = false, access = CameraAccess.ASKING)

@PreviewTest @ScanGeometries @Composable
fun QrScanAskingDark() = Scanner(dark = true, access = CameraAccess.ASKING)

@PreviewTest @Preview(name = "scan-denied", device = "spec:width=393dp,height=852dp,dpi=420") @Composable
fun QrScanDeniedLight() = Scanner(dark = false, access = CameraAccess.DENIED)

@PreviewTest @ScanGeometries @Composable
fun QrScanBlockedDark() = Scanner(dark = true, access = CameraAccess.BLOCKED)

@PreviewTest @Preview(name = "scan-no-camera", device = "spec:width=393dp,height=852dp,dpi=420") @Composable
fun QrScanNoCameraLight() = Scanner(dark = false, access = CameraAccess.NO_CAMERA)

@Composable
private fun ScanResult(dark: Boolean, text: String, web: Boolean) = TailSocksTheme(
    appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        ScanResultContent(
            text = text,
            labels = ScanResultLabels(
                title = stringResource(R.string.qr_scan_result_title),
                help = stringResource(if (web) R.string.qr_scan_result_web_help else R.string.qr_scan_result_help),
                copy = stringResource(R.string.action_copy),
                open = stringResource(R.string.qr_scan_open),
            ),
            onCopy = {},
            onOpen = if (web) ({}) else null
        )
    }
}

@PreviewTest @ScanGeometries @Composable
fun ScanResultWebLight() = ScanResult(dark = false, text = "https://grafana.example.com/d/home?orgId=1", web = true)

@PreviewTest @ScanGeometries @Composable
fun ScanResultTextDark() = ScanResult(
    dark = true,
    text = "WIFI:T:WPA;S:Home network;P:correct horse battery staple;;",
    web = false
)

// The scanner in every window size (AdaptivePreviews.kt): the camera, and the reason
// shown while Android asks for it.

@PreviewTest @WindowSizes @Composable
fun TabletQrScanCamera() = Scanner(dark = false, access = CameraAccess.GRANTED, torch = false)

@PreviewTest @WindowSizes @Composable
fun TabletQrScanAsking() = Scanner(dark = true, access = CameraAccess.ASKING)
