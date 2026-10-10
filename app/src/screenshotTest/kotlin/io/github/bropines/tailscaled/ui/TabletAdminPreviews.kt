package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.AdminConsoleContent
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.attention.LocalAttentionNow
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.ProfileDraft
import io.github.bropines.tailscaled.admin.headscale.HeadscaleUiState

// The admin console in every window size (AdaptivePreviews.kt), a tab per preview: the chip
// row below 840dp, the tab rail from there, the list tabs with their detail in a pane, the
// tabs of cards in columns. Plus the phone on its side (PhoneLandscape): with WindowSizes'
// 1-phone, the two renders a change to the console must leave exactly as they were.
// AdminPanesPreviews.kt has the states these do not open on.

/** A phone on its side — WindowSizes has the phone upright; together they are PhoneSizes. */
@Preview(name = "1-phone-landscape", device = "spec:width=891dp,height=411dp,dpi=420")
annotation class PhoneLandscape

/** The demo services with their hosts, and the longer audit log, so panes have something to show. */
internal val adminServicesState = AdminDemo.state.copy(
    services = Loadable(HsLogsDemo.services, loadedAt = 1),
    serviceHosts = mapOf(HsLogsDemo.services.first().name to Loadable(HsLogsDemo.hosts, loadedAt = 1)),
)
internal val adminLogsState = AdminDemo.state.copy(tailnetLog = Loadable(HsLogsDemo.log, loadedAt = 1))

@Composable
internal fun AdminConsoleShowcase(state: ConsoleState, tab: ConsoleTab, headscale: HeadscaleUiState? = null, deviceId: String? = null) = AdaptiveShowcase(null) {
    CompositionLocalProvider(LocalAttentionNow provides AttentionDemo.now) {
        AdminDashboard(state, null, {}, startTab = tab, headscaleDemo = headscale, initialDeviceId = deviceId)
    }
}

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminAttention() = AdminConsoleShowcase(AttentionDemo.state, ConsoleTab.ATTENTION)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminDevices() = AdminConsoleShowcase(AdminDemo.state, ConsoleTab.DEVICES)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminDns() = AdminConsoleShowcase(AdminConfigDemo.state, ConsoleTab.DNS)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminPolicy() = AdminConsoleShowcase(AdminConfigDemo.state, ConsoleTab.POLICY)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminUsers() = AdminConsoleShowcase(KeysUsersDemo.state, ConsoleTab.USERS)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminKeys() = AdminConsoleShowcase(KeysUsersDemo.state, ConsoleTab.KEYS)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminWebhooks() = AdminConsoleShowcase(AdminConfigDemo.state, ConsoleTab.WEBHOOKS)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminLogs() = AdminConsoleShowcase(adminLogsState, ConsoleTab.LOGS)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminSettings() = AdminConsoleShowcase(AdminConfigDemo.state, ConsoleTab.SETTINGS)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminSetup() = AdaptiveShowcase(null) {
    AdminConsoleContent(ConsoleState(phase = ConsolePhase.SETUP, draft = ProfileDraft(name = "Home tailnet")), null, {})
}

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminServices() = AdminConsoleShowcase(adminServicesState, ConsoleTab.SERVICES)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminWeb() = AdminConsoleShowcase(AdminDemo.state, ConsoleTab.WEB)

@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminServer() = AdminConsoleShowcase(HsLogsDemo.hsConsole, ConsoleTab.SERVER, HsLogsDemo.hsState)

/** The lock in front of the console. */
@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminLocked() = AdaptiveShowcase(null) {
    AdminConsoleContent(AdminDemo.state.copy(phase = ConsolePhase.LOCKED), null, {})
}

/** An existing profile in the editor. */
@PreviewTest @WindowSizes @PhoneLandscape @Composable
fun TabletAdminEditProfile() = AdaptiveShowcase(null) {
    AdminConsoleContent(
        AdminDemo.state.copy(phase = ConsolePhase.EDIT_PROFILE, draft = ProfileDraft(id = "demo", name = "Home tailnet", hasStoredSecret = true)),
        null, {},
    )
}
