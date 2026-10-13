package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/*
 * The search of Settings (SettingsSearch.kt, SettingsSearchUi.kt) on a phone and on a tablet,
 * in English and in Russian: the field in use before anything is typed, a query with results
 * of several kinds, a query typed in the wrong keyboard layout, and one that finds nothing.
 *
 * A render cannot type, so each hands the search its query (LocalSettingsSearchPreview); the
 * results are the real catalogue's, found by the real engine. The pages are those of a fresh
 * install — proxy mode — so what lives only in VPN (TUN) mode is found with its reason.
 * The field at rest, over the list of sections, is in every Settings preview there already was.
 */

/** A phone upright and a tablet on its side, each in English and in Russian. */
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class SearchSizes

@Composable
private fun Searching(query: String, recent: List<String> = emptyList()) = AdaptiveShowcase {
    CompositionLocalProvider(LocalSettingsSearchPreview provides SettingsSearchPreview(query, recent)) {
        SettingsScreen(
            onBack = {},
            currentTheme = "dark", onThemeChange = {},
            currentPreset = "emerald", onPresetChange = {},
            currentDynamicColor = false, onDynamicColorChange = {},
            currentAmoledMode = true, onAmoledModeChange = {},
        )
    }
}

/** The field taken, nothing typed: what was found before, then a few places to start from. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchEmpty() = Searching("", recent = listOf("exit_node", "autostart", "tun_ipv6"))

/** The very first time: nothing found before. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchEmptyFirstTime() = Searching("")

/** A title, a keyword, rows that are on their page only in VPN (TUN) mode, a section. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchVpn() = Searching("vpn")

/** The same in Russian letters, whatever the interface's language. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchPortRussian() = Searching("порт")

/** A question, with a slip of the finger in it. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchQuestion() = Searching("how do i change the langauge")

/** «прокси», typed with the keyboard left in English. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchWrongLayout() = Searching("ghjrcb")

/** A name people use and we do not: found by its keyword, which the result shows. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchKeyword() = Searching("kill switch")

/** One word known, one not: the nearest are offered and said to be that. */
@PreviewTest @SearchSizes @Composable
fun SettingsSearchClosest() = Searching("exit node banana")

@PreviewTest @SearchSizes @Composable
fun SettingsSearchNothing() = Searching("zzzzqqq")
