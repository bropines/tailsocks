package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// The screens around Settings — permissions, the apps the tunnel leaves alone, onboarding —
// in every window size (AdaptivePreviews.kt), and on a phone to compare before and after.

// Permissions

@PreviewTest @WindowSizes @Composable
fun TabletPermissions() = AdaptiveShowcase { PermissionsScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhonePermissions() = AdaptiveShowcase { PermissionsScreen(onBack = {}) }

// The apps the tunnel leaves alone: a made-up phone's apps, some of them on the default list.

private val demoApps = listOf(
    "com.avito.android" to "Avito",
    "com.android.chrome" to "Chrome",
    "com.discord" to "Discord",
    "org.mozilla.firefox" to "Firefox",
    "com.github.android" to "GitHub",
    "com.google.android.gm" to "Gmail",
    "com.google.android.apps.maps" to "Maps",
    "ru.oneme.app" to "MAX",
    "ru.nspk.mirpay" to "Mir Pay",
    "ru.rostel" to "Rostelecom",
    "ru.vk.store.tv" to "RuStore TV",
    "com.spotify.music" to "Spotify",
    "org.telegram.messenger" to "Telegram",
    "com.termux" to "Termux",
    "com.vkontakte.android" to "VK",
    "com.whatsapp" to "WhatsApp",
    "com.google.android.youtube" to "YouTube",
    "ru.yandex.taxi" to "Yandex Go",
).map { (pkg, label) -> AppItem(pkg, label, icon = null) }

@PreviewTest @WindowSizes @Composable
fun TabletTunExcludedApps() = AdaptiveShowcase { TunExcludedAppsScreen(onBack = {}, initialApps = demoApps) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTunExcludedApps() = AdaptiveShowcase { TunExcludedAppsScreen(onBack = {}, initialApps = demoApps) }

// Onboarding: the first slide, the mode slide and the longest one (the bypass setup).

@PreviewTest @WindowSizes @Composable
fun TabletFirstStart() = AdaptiveShowcase { FirstStartScreen(onFinished = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletFirstStartMode() = AdaptiveShowcase { FirstStartScreen(onFinished = {}, initialPage = 1) }

@PreviewTest @WindowSizes @Composable
fun TabletFirstStartBypass() = AdaptiveShowcase { FirstStartScreen(onFinished = {}, initialPage = 3) }

@PreviewTest @PhoneSizes @Composable
fun PhoneFirstStart() = AdaptiveShowcase { FirstStartScreen(onFinished = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneFirstStartMode() = AdaptiveShowcase { FirstStartScreen(onFinished = {}, initialPage = 1) }

@PreviewTest @PhoneSizes @Composable
fun PhoneFirstStartLogin() = AdaptiveShowcase { FirstStartScreen(onFinished = {}, initialPage = 4) }
