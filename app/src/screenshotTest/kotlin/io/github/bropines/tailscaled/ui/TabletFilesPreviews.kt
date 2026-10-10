package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// Files, Taildrop, Taildrive, Logs and the console in every window size (AdaptivePreviews.kt).

@PreviewTest @WindowSizes @Composable
fun TabletFiles() = AdaptiveShowcase(TaildropDemo.data) { FilesScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletTaildrop() = AdaptiveShowcase(TaildropDemo.data) { FilesScreen(onBack = {}, openTaildrop = true) }

@PreviewTest @WindowSizes @Composable
fun TabletTaildrive() = AdaptiveShowcase { TaildriveScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletLogs() = AdaptiveShowcase { LogsScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletConsole() = AdaptiveShowcase { ConsoleScreen(initialCmd = "", onBack = {}) }
