package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/* The licenses screen from the real assets/licenses.json, which the renderer can read. */

@Composable
private fun LicensesSample(dark: Boolean) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", dynamicColorEnabled = false) {
        LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {})
    }
}

@PreviewTest
@Preview(name = "licenses-phone", device = "spec:width=393dp,height=852dp,dpi=420")
@Preview(name = "licenses-phone-ru", device = "spec:width=393dp,height=852dp,dpi=420", locale = "ru")
@Composable
fun LicensesLight() = LicensesSample(dark = false)

@PreviewTest
@Preview(name = "licenses-phone-dark", device = "spec:width=393dp,height=852dp,dpi=420")
@Composable
fun LicensesDark() = LicensesSample(dark = true)
