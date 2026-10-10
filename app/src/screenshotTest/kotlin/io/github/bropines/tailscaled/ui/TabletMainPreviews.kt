package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.android.tools.screenshot.PreviewTest

// The dashboard in every window size (AdaptivePreviews.kt): running with an exit node, and stopped.

@PreviewTest @WindowSizes @Composable
fun TabletMain() = AdaptiveShowcase { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }

@PreviewTest @WindowSizes @Composable
fun TabletMainStopped() = AdaptiveShowcase(DemoTailnet.data.copy(running = false, exitNodeIp = null)) {
    MainScreen(showAccountSwitcher = remember { mutableStateOf(false) })
}
