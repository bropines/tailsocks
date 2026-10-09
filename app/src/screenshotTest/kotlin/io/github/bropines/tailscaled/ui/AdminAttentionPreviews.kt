package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.attention.AttentionActions
import io.github.bropines.tailscaled.admin.attention.AttentionChanges
import io.github.bropines.tailscaled.admin.attention.AttentionTabContent
import io.github.bropines.tailscaled.admin.attention.AttentionUiState
import io.github.bropines.tailscaled.admin.attention.LocalAttentionNow
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.notify.AttentionChecks
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmButton
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmContent
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * The admin console's "Needs attention" home with the invented tailnet of AdminPreviews
 * (tail4a2c9.ts.net) and a fixed "now", so the expiry lines read the same on every run.
 */

object AttentionDemo {
    private const val SUFFIX = "tail4a2c9.ts.net"

    val now: Long = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("2026-10-09T12:00:00Z")!!.time

    private fun device(id: String, host: String, os: String, v4: String, expires: String, lockError: String? = null, tags: List<String> = emptyList()) =
        ApiDevice(
            id = id.filter { it.isDigit() }, nodeId = id, name = "$host.$SUFFIX", hostname = host, os = os,
            addresses = listOf(v4), user = "alex@example.com", clientVersion = "1.104.0", connectedToControl = true,
            keyExpiryDisabled = false, expires = expires, authorized = true, tags = tags, tailnetLockError = lockError,
        )

    private val extraDevices = listOf(
        device("n20CNTRL", "work-laptop", "macOS", "100.80.4.17", "2026-10-12T08:30:00Z"),
        device("n21CNTRL", "media-box", "linux", "100.66.20.3", "2026-10-08T06:00:00Z"),
        device("n22CNTRL", "backup-vps", "linux", "100.90.1.200", "2027-02-01T00:00:00Z", lockError = "node key is not signed by a trusted key", tags = listOf("tag:server")),
    )

    private val extraKeys = listOf(
        ApiKey(id = "kAUTH3CNTRL", keyType = "auth", description = "ci runners", created = "2026-07-11T10:00:00Z", expires = "2026-10-11T10:00:00Z"),
    )

    /** Everything a busy week brings: approvals, expiries, a lock error, a copied key, routes, updates. */
    val state: ConsoleState = AdminDemo.state.copy(
        devices = Loadable(AdminDemo.devices + extraDevices, loadedAt = 1),
        keys = Loadable(AdminDemo.keys + extraKeys, loadedAt = 1),
        caps = AdminDemo.state.caps?.copy(credentialExpires = "2026-10-14T10:00:00Z"),
    )

    val attention = AttentionUiState(
        profileId = "demo",
        routes = mapOf(
            "n3CNTRL" to DeviceRoutes(advertisedRoutes = listOf("0.0.0.0/0", "::/0"), enabledRoutes = emptyList()),
            "n2CNTRL" to DeviceRoutes(advertisedRoutes = listOf("192.168.1.0/24", "10.20.0.0/16"), enabledRoutes = listOf("192.168.1.0/24")),
        ),
        routesReadAt = 2,
        scanned = 9,
        scanTotal = 9,
        checks = AttentionChecks(enabled = true, intervalMinutes = 60),
    )

    /** A quiet tailnet: every device approved, no key near its end. */
    val calm: ConsoleState = AdminDemo.state.copy(
        devices = Loadable(AdminDemo.devices.filter { it.authorized != false && it.updateAvailable != true && it.multipleConnections != true }, loadedAt = 1),
        users = Loadable(AdminDemo.users.filter { it.status != "needs-approval" }, loadedAt = 1),
    )
}

@Composable
private fun Attention(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalAttentionNow provides AttentionDemo.now) { content() }
        }
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminAttentionPreview() = Attention {
    AttentionTabContent(AttentionDemo.state, AttentionDemo.attention, AttentionDemo.now, {}, AttentionActions())
}

/** The sections under the fold: problems, routes waiting, updates, and a scan that was capped. */
@PreviewTest @AdminGeometries @Composable
fun AdminAttentionLowerPreview() = Attention {
    val lower = setOf("backup-vps", "steam-deck", "exit-frankfurt", "homelab-nas", "raspberry-pi")
    AttentionTabContent(
        AttentionDemo.state.copy(
            devices = Loadable(AttentionDemo.state.devices.value!!.filter { it.shortName in lower }, loadedAt = 1),
            users = Loadable(emptyList(), loadedAt = 1),
            keys = Loadable(emptyList(), loadedAt = 1),
            caps = AdminDemo.state.caps,
        ),
        AttentionDemo.attention.copy(scanned = 100, scanTotal = 140),
        AttentionDemo.now, {}, AttentionActions(),
    )
}

/** The console's own credential refused: the one item, and the reads that could not happen. */
@PreviewTest @AdminPhone @Composable
fun AdminAttentionRefusedPreview() = Attention(dark = false) {
    AttentionTabContent(
        AdminDemo.state.copy(
            devices = Loadable(error = io.github.bropines.tailscaled.admin.api.AdminApiException.Unauthorized("51b7d3c9", null)),
            users = Loadable(), keys = Loadable(),
        ),
        AttentionUiState(profileId = "demo"),
        AttentionDemo.now, {}, AttentionActions(),
    )
}

/** As the console opens: the first tab, under the tab row. */
@PreviewTest @AdminPhone @Composable
fun AdminAttentionDashboardPreview() = Attention(dark = false) {
    AdminDashboard(AttentionDemo.state, null, {}, startTab = ConsoleTab.ATTENTION)
}

@PreviewTest @AdminGeometries @Composable
fun AdminAttentionCalmPreview() = Attention(dark = false) {
    AttentionTabContent(
        AttentionDemo.calm,
        AttentionUiState(profileId = "demo", checks = AttentionChecks(enabled = true, intervalMinutes = 360), notificationsAllowed = false),
        AttentionDemo.now, {}, AttentionActions(),
    )
}

/** A read-only credential: the rows say why they offer nothing, and the users were not readable. */
@PreviewTest @AdminGeometries @Composable
fun AdminAttentionReadOnlyPreview() = Attention {
    val caps = AttentionDemo.state.caps!!.copy(
        access = mapOf(AdminArea.DEVICES to Access.READ, AdminArea.ROUTES to Access.READ, AdminArea.USERS to Access.NONE),
    )
    AttentionTabContent(
        AttentionDemo.state.copy(caps = caps, users = Loadable()),
        AttentionDemo.attention.copy(checks = AttentionChecks()),
        AttentionDemo.now, {}, AttentionActions(),
    )
}

/** A dialog as the gates draw it, without the window the renderer does not have. */
@Composable
private fun GateFrame(change: AdminChange, typed: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    if (change.changeClass == ChangeClass.HIGH) Icons.Default.Warning else Icons.Default.Lock, null,
                    Modifier.align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.secondary,
                )
                Text(change.title, style = MaterialTheme.typography.headlineSmall)
                ChangeConfirmContent(change, typed) {}
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                    ChangeConfirmButton(change, typed) {}
                }
            }
        }
    }
}

/** Reject from a notification or the list: a delete, so the device's name is typed back. */
@PreviewTest @AdminGeometries @Composable
fun AdminAttentionRejectPreview() = Attention {
    val ctx = LocalContext.current
    val laptop = AdminDemo.devices.first { it.shortName == "new-laptop" }
    GateFrame(AttentionChanges.rejectDevice(ctx, laptop, "nSELF7").change, typed = "new-lap")
}

@PreviewTest @AdminPhone @Composable
fun AdminAttentionApprovePreview() = Attention(dark = false) {
    val ctx = LocalContext.current
    val laptop = AdminDemo.devices.first { it.shortName == "new-laptop" }
    GateFrame(ConsoleChanges.setAuthorized(ctx, laptop, true, "nSELF7").change, typed = "")
}

@PreviewTest @AdminPhone @Composable
fun AdminAttentionIntervalPreview() = Attention {
    val ctx = LocalContext.current
    PickerSheetContent(
        title = ctx.getString(R.string.admin_attention_checks_interval),
        options = AttentionChecks.INTERVALS.map {
            PickerOption(
                it,
                ctx.getString(
                    when (it) {
                        15 -> R.string.admin_attention_interval_15
                        360 -> R.string.admin_attention_interval_360
                        else -> R.string.admin_attention_interval_60
                    }
                ),
                Icons.Default.Schedule,
            )
        },
        selected = 60,
        monospace = false,
    ) {}
}
