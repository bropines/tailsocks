package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

// In every window size (AdaptivePreviews.kt): from expanded up the list and a licence side by side.

@PreviewTest @WindowSizes @Composable
fun TabletLicenses() = AdaptiveShowcase { LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}) }

/** A component picked: its text beside the list. Two-pane windows only — elsewhere the
 *  text is a sheet, which the renderer does not draw. */
@PreviewTest
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletLicensesPicked() = AdaptiveShowcase {
    LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}, initialOpen = "go/github.com/aws/aws-sdk-go-v2")
}

@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun FoldLicensesBook() = AdaptiveShowcase {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) {
        LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {})
    }
}

@PreviewTest @PhoneSizes @Composable
fun PhoneLicenses() = AdaptiveShowcase { LicensesContent(doc = loadLicenses(LocalContext.current), onBack = {}) }
