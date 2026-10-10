package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// Serve, TailCat, Netcheck and DNS in every window size (AdaptivePreviews.kt).

@PreviewTest @WindowSizes @Composable
fun TabletServe() = AdaptiveShowcase { ServeHost(startTab = 0, onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletTailcat() = AdaptiveShowcase(DemoTailnet.data.copy(tailcat = demoTailcat)) { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletNetcheck() = AdaptiveShowcase(DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy)) { NetcheckScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletDns() = AdaptiveShowcase { DnsScreen(onBack = {}) }
