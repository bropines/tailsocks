package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.AdminConsoleContent
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.attention.LocalAttentionNow
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ProfileDraft

// The admin console in every window size (AdaptivePreviews.kt), a tab per preview.

@Composable
private fun Console(state: ConsoleState, tab: ConsoleTab) = AdaptiveShowcase(null) {
    CompositionLocalProvider(LocalAttentionNow provides AttentionDemo.now) {
        AdminDashboard(state, null, {}, startTab = tab)
    }
}

@PreviewTest @WindowSizes @Composable
fun TabletAdminAttention() = Console(AttentionDemo.state, ConsoleTab.ATTENTION)

@PreviewTest @WindowSizes @Composable
fun TabletAdminDevices() = Console(AdminDemo.state, ConsoleTab.DEVICES)

@PreviewTest @WindowSizes @Composable
fun TabletAdminDns() = Console(AdminConfigDemo.state, ConsoleTab.DNS)

@PreviewTest @WindowSizes @Composable
fun TabletAdminPolicy() = Console(AdminConfigDemo.state, ConsoleTab.POLICY)

@PreviewTest @WindowSizes @Composable
fun TabletAdminUsers() = Console(KeysUsersDemo.state, ConsoleTab.USERS)

@PreviewTest @WindowSizes @Composable
fun TabletAdminKeys() = Console(KeysUsersDemo.state, ConsoleTab.KEYS)

@PreviewTest @WindowSizes @Composable
fun TabletAdminWebhooks() = Console(AdminConfigDemo.state, ConsoleTab.WEBHOOKS)

@PreviewTest @WindowSizes @Composable
fun TabletAdminLogs() = Console(AdminDemo.state, ConsoleTab.LOGS)

@PreviewTest @WindowSizes @Composable
fun TabletAdminSettings() = Console(AdminConfigDemo.state, ConsoleTab.SETTINGS)

@PreviewTest @WindowSizes @Composable
fun TabletAdminSetup() = AdaptiveShowcase(null) {
    AdminConsoleContent(ConsoleState(phase = ConsolePhase.SETUP, draft = ProfileDraft(name = "Home tailnet")), null, {})
}

@PreviewTest @WindowSizes @Composable
fun TabletAdminServices() = Console(AdminDemo.state, ConsoleTab.SERVICES)
