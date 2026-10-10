package io.github.bropines.tailscaled.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.logs.AuditLogQuery
import io.github.bropines.tailscaled.admin.logs.LogsTab

// The admin console's panes and rail in the states TabletAdminPreviews does not open on: a
// device picked, a read-only profile, this phone's log, and a foldable open like a book for
// every list with a detail. Apart from TabletAdminPreviews so neither class runs the renderer
// out of memory.

/** A foldable open like a book (see FoldPeersBook): the hinge at 330–343dp. */
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
annotation class FoldBook

@Composable
private fun Book(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { content() }

/** A device picked: its row outlined, its details in the pane. Two-pane windows only — elsewhere
 *  the details are a sheet, which the renderer does not draw. */
@PreviewTest
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletAdminDevicesPicked() = AdminConsoleShowcase(AdminDevicesDemo.state, ConsoleTab.DEVICES, deviceId = "n3CNTRL")

/** A read-only profile: the banner over the tab, beside the rail. */
@PreviewTest
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletAdminReadOnly() = AdminConsoleShowcase(AdminConfigDemo.state.copy(active = AdminDemo.profile.copy(readOnly = true)), ConsoleTab.SETTINGS)

/** The changes this phone made, on a tablet: no detail to open, so columns under the log switch. */
@PreviewTest @WindowSizes @Composable
fun TabletAdminPhoneLog() = AdaptiveShowcase(null) {
    // The tab alone, without the dashboard's Scaffold: the screen's background under it.
    Surface(color = MaterialTheme.colorScheme.background) {
        LogsTab(
            tailnetLog = Loadable(HsLogsDemo.log, loadedAt = 1),
            query = AuditLogQuery(),
            onQuery = {},
            onRetry = {},
            localLog = AdminDemo.state.localLog + AdminDemo.state.localLog.map { it.copy(time = it.time - 86_400_000L) },
            onClearLocal = {},
            startOnLocal = true,
            now = 1_791_600_000_000L,
        )
    }
}

@PreviewTest @FoldBook @Composable
fun FoldAdminDevicesBook() = Book { AdminConsoleShowcase(AdminDemo.state, ConsoleTab.DEVICES) }

@PreviewTest @FoldBook @Composable
fun FoldAdminUsersBook() = Book { AdminConsoleShowcase(KeysUsersDemo.state, ConsoleTab.USERS) }

@PreviewTest @FoldBook @Composable
fun FoldAdminServicesBook() = Book { AdminConsoleShowcase(adminServicesState, ConsoleTab.SERVICES) }

@PreviewTest @FoldBook @Composable
fun FoldAdminWebhooksBook() = Book { AdminConsoleShowcase(AdminConfigDemo.state, ConsoleTab.WEBHOOKS) }

@PreviewTest @FoldBook @Composable
fun FoldAdminLogsBook() = Book { AdminConsoleShowcase(adminLogsState, ConsoleTab.LOGS) }
