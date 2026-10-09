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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiClientConnectivity
import io.github.bropines.tailscaled.admin.api.ApiClientSupports
import io.github.bropines.tailscaled.admin.api.ApiDerpLatency
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiDistro
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.devices.BulkDraft
import io.github.bropines.tailscaled.admin.devices.BulkOutcome
import io.github.bropines.tailscaled.admin.devices.BulkState
import io.github.bropines.tailscaled.admin.devices.BulkTagMode
import io.github.bropines.tailscaled.admin.devices.BulkTags
import io.github.bropines.tailscaled.admin.devices.BulkTagsContent
import io.github.bropines.tailscaled.admin.devices.DeviceActions
import io.github.bropines.tailscaled.admin.devices.DeviceChanges
import io.github.bropines.tailscaled.admin.devices.DeviceDetailContent
import io.github.bropines.tailscaled.admin.devices.DeviceFilter
import io.github.bropines.tailscaled.admin.devices.DeviceQuery
import io.github.bropines.tailscaled.admin.devices.DeviceSort
import io.github.bropines.tailscaled.admin.devices.DevicesListActions
import io.github.bropines.tailscaled.admin.devices.DevicesListContent
import io.github.bropines.tailscaled.admin.devices.DevicesUi
import io.github.bropines.tailscaled.admin.devices.Ipv4Fields
import io.github.bropines.tailscaled.admin.devices.Ipv4Rules
import io.github.bropines.tailscaled.admin.devices.PREVIEW_NOW
import io.github.bropines.tailscaled.admin.devices.RenameFields
import io.github.bropines.tailscaled.admin.devices.RouteSweep
import io.github.bropines.tailscaled.admin.devices.TagsFields
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmButton
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmContent
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The Devices tab of the admin console with the demo tailnet (AdminDemo): the list with its
 * badges and filters, a selection, the device sheet in its states, bulk tags before and after,
 * the dialogs and the gates they lead to — sheets and dialogs drawn without their windows.
 */

/** Tall enough for the whole device sheet, which scrolls on a phone. */
@Preview(name = "admin-sheet-tall", device = "spec:width=411dp,height=2100dp,dpi=420")
@Preview(name = "admin-sheet-tall-ru", device = "spec:width=411dp,height=2100dp,dpi=420", locale = "ru")
annotation class AdminTallSheet

object AdminDevicesDemo {
    val routes: Map<String, DeviceRoutes> = mapOf(
        "n3CNTRL" to DeviceRoutes(listOf("0.0.0.0/0", "::/0", "10.20.0.0/16"), listOf("0.0.0.0/0", "::/0")),
        "n2CNTRL" to DeviceRoutes(listOf("192.168.1.0/24"), listOf("192.168.1.0/24")),
        "n6CNTRL" to DeviceRoutes(listOf("10.0.0.0/24"), emptyList()),
        "nSELF7" to DeviceRoutes(),
        "n1CNTRL" to DeviceRoutes(),
    )

    val state: ConsoleState = AdminDemo.state.copy(routes = routes.mapValues { Loadable(it.value, loadedAt = 1) })

    fun device(name: String): ApiDevice = AdminDemo.devices.first { it.shortName == name }

    /** The exit node as a full read has it: connectivity, SSH, distro, the lock key. */
    val exit: ApiDevice = device("exit-frankfurt").copy(
        created = "2026-03-14T09:12:00Z",
        sshEnabled = true,
        distro = ApiDistro("debian", "12", "bookworm"),
        tailnetLockKey = "tlpub:7f3c9a0e51b2d4c86a1f0e9d3b7c5a2e",
        advertisedRoutes = routes.getValue("n3CNTRL").advertisedRoutes,
        enabledRoutes = routes.getValue("n3CNTRL").enabledRoutes,
        clientConnectivity = ApiClientConnectivity(
            endpoints = listOf("203.0.113.40:41641", "10.20.0.8:41641"),
            mappingVariesByDestIP = false,
            latency = mapOf("Frankfurt" to ApiDerpLatency(true, 4.2), "Amsterdam" to ApiDerpLatency(null, 11.8)),
            clientSupports = ApiClientSupports(ipv6 = true, pcp = false, pmp = false, udp = true, upnp = true),
        ),
        expires = "2026-10-12T08:00:00Z",
    )

    val bulkDevices = listOf(device("desktop-home"), device("homelab-nas"), device("raspberry-pi"), device("family-nas"))
}

@Composable
private fun Dev(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A sheet's body on the sheet's colour, without the window the renderer does not have. */
@Composable
private fun SheetFrame(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.padding(top = 24.dp)) { content() }
    }
}

/** A dialog as the app draws it, without its window. */
@Composable
private fun DialogFrame(title: String, icon: ImageVector? = null, confirm: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                icon?.let { Icon(it, null, Modifier.align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.secondary) }
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                    confirm()
                }
            }
        }
    }
}

@Composable
private fun Confirm(change: AdminChange, typed: String) = DialogFrame(
    change.title,
    if (change.changeClass == ChangeClass.HIGH) Icons.Default.Warning else Icons.Default.Lock,
    confirm = { ChangeConfirmButton(change, typed) {} },
) { ChangeConfirmContent(change, typed) {} }

@Composable
private fun DeviceList(ui: DevicesUi = DevicesUi(), state: ConsoleState = AdminDevicesDemo.state) =
    DevicesListContent(state, ui, PREVIEW_NOW, selfNodeId = "nSELF7", canSelect = true, actions = DevicesListActions())

// ------------------------------------------------------------------ the list

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesListPreview() = Dev { DeviceList() }

@PreviewTest @AdminPhone @Composable
fun AdminDevicesRoutersPreview() = Dev(dark = false) {
    DeviceList(DevicesUi(query = DeviceQuery(filters = setOf(DeviceFilter.ROUTERS), sort = DeviceSort.LAST_SEEN), sweep = RouteSweep(5, 9)))
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesFilteredPreview() = Dev {
    DeviceList(DevicesUi(query = DeviceQuery(owner = "alex@example.com", filters = setOf(DeviceFilter.OFFLINE), offlineDays = 7)))
}

@PreviewTest @AdminPhone @Composable
fun AdminDevicesNoMatchPreview() = Dev(dark = false) { DeviceList(DevicesUi(query = DeviceQuery(text = "printer", filters = setOf(DeviceFilter.SHARED)))) }

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesSelectingPreview() = Dev {
    DeviceList(DevicesUi(selecting = true, selected = setOf("n1CNTRL", "n2CNTRL", "n6CNTRL")))
}

// ------------------------------------------------------------------ the device sheet

@PreviewTest @AdminTallSheet @Composable
fun AdminDeviceDetailPreview() = Dev {
    SheetFrame {
        DeviceDetailContent(
            AdminDevicesDemo.exit, Loadable(AdminDevicesDemo.exit, loadedAt = 1), Loadable(AdminDevicesDemo.routes["n3CNTRL"], loadedAt = 1),
            PREVIEW_NOW, isThisPhone = false, canWriteDevices = true, canWriteRoutes = true, canSetIp = true, actions = DeviceActions(),
        )
    }
}

@PreviewTest @AdminTallSheet @Composable
fun AdminDeviceDetailRouterPreview() = Dev(dark = false) {
    val pi = AdminDevicesDemo.device("raspberry-pi").copy(created = "2026-05-02T18:30:00Z")
    SheetFrame {
        DeviceDetailContent(
            pi, Loadable(pi, loadedAt = 1), Loadable(DeviceRoutes(listOf("10.0.0.0/24", "10.0.1.0/24", "fd00:1::/64"), listOf("10.9.0.0/24")), loadedAt = 1),
            PREVIEW_NOW, isThisPhone = false, canWriteDevices = true, canWriteRoutes = true, canSetIp = true, actions = DeviceActions(),
        )
    }
}

@PreviewTest @AdminTallSheet @Composable
fun AdminDeviceDetailThisPhonePreview() = Dev {
    val self = AdminDevicesDemo.device("pixel-9-pro")
    SheetFrame {
        DeviceDetailContent(
            self, Loadable(error = AdminApiException.Server(503, "51b7d3c9", null)), Loadable(DeviceRoutes(), loadedAt = 1),
            PREVIEW_NOW, isThisPhone = true, canWriteDevices = true, canWriteRoutes = true, canSetIp = true, actions = DeviceActions(),
        )
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminDeviceDetailSharedPreview() = Dev(dark = false) {
    val shared = AdminDevicesDemo.device("family-nas")
    SheetFrame {
        DeviceDetailContent(
            shared, Loadable(shared, loadedAt = 1), null,
            PREVIEW_NOW, isThisPhone = false, canWriteDevices = true, canWriteRoutes = true, canSetIp = true, actions = DeviceActions(),
        )
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminDeviceDetailPendingPreview() = Dev {
    val pending = AdminDevicesDemo.device("new-laptop").copy(created = "2026-10-09T10:41:00Z", tailnetLockError = "node key not signed by a trusted lock key")
    SheetFrame {
        DeviceDetailContent(
            pending, Loadable(pending, loadedAt = 1), Loadable(DeviceRoutes(), loadedAt = 1),
            PREVIEW_NOW, isThisPhone = false, canWriteDevices = true, canWriteRoutes = true, canSetIp = true, actions = DeviceActions(),
        )
    }
}

// ------------------------------------------------------------------ bulk tags

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesBulkPreview() = Dev {
    SheetFrame {
        BulkTagsContent(
            AdminDevicesDemo.bulkDevices, BulkDraft(BulkTagMode.ADD, listOf("tag:server")), null, AdminDemo.state.policyTags, {}, {}, {},
        )
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminDevicesBulkRemovePreview() = Dev(dark = false) {
    SheetFrame {
        BulkTagsContent(
            AdminDevicesDemo.bulkDevices, BulkDraft(BulkTagMode.REMOVE, listOf("tag:server", "tag:iot")), null, AdminDemo.state.policyTags, {}, {}, {},
        )
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesBulkResultsPreview() = Dev {
    SheetFrame {
        BulkTagsContent(
            AdminDevicesDemo.bulkDevices.take(3),
            BulkDraft(BulkTagMode.ADD, listOf("tag:server")),
            listOf(
                BulkOutcome("n1CNTRL", "desktop-home", BulkState.VERIFIED),
                BulkOutcome("n6CNTRL", "raspberry-pi", BulkState.FAILED, AdminApiException.BadRequest(400, "8c1e0f2a", "tag:server is not permitted for this credential")),
                BulkOutcome("n7CNTRL", "ipad", BulkState.NOT_SENT, AdminApiException.RateLimited("9d2a7b11", null, 30)),
            ),
            AdminDemo.state.policyTags, {}, {}, {},
        )
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesConfirmBulkPreview() = Dev {
    val ctx = LocalContext.current
    val rows = BulkTags.plan(AdminDevicesDemo.bulkDevices, BulkTagMode.ADD, listOf("tag:server"))
    val change = DeviceChanges.bulkTags(ctx, rows, BulkTagMode.ADD, listOf("tag:server"), "nSELF7", "demo", "Home tailnet", {}, {}, {}).change
    Confirm(change, typed = "2 dev")
}

// ------------------------------------------------------------------ dialogs and their gates

@PreviewTest @AdminPhone @Composable
fun AdminDevicesConfirmExitOffPreview() = Dev {
    val ctx = LocalContext.current
    val exit = AdminDevicesDemo.exit
    Confirm(ConsoleChanges.setRoutes(ctx, exit, listOf("0.0.0.0/0", "::/0"), emptyList(), "nSELF7").change, typed = "")
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesIpDialogPreview() = Dev {
    val ctx = LocalContext.current
    val pi = AdminDevicesDemo.device("raspberry-pi")
    val check = Ipv4Rules.check("100.72.5.101", pi.ipv4, Ipv4Rules.holders(AdminDemo.devices, pi))
    DialogFrame(ctx.getString(R.string.admin_dev_ip_title, pi.shortName), confirm = { Button(onClick = {}, enabled = check.ok) { Text(ctx.getString(R.string.admin_dev_ip_continue)) } }) {
        Ipv4Fields("100.72.5.101", check) {}
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminDevicesConfirmIpPreview() = Dev(dark = false) {
    val ctx = LocalContext.current
    Confirm(DeviceChanges.setIpv4(ctx, AdminDevicesDemo.device("raspberry-pi"), "100.80.0.9", "nSELF7").change, typed = "raspberry-pi")
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesRenameResetPreview() = Dev {
    val ctx = LocalContext.current
    val desktop = AdminDevicesDemo.device("desktop-home")
    DialogFrame(ctx.getString(R.string.admin_dev_rename_title, desktop.shortName), confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin_dev_rename_reset)) } }) {
        RenameFields(desktop.copy(hostname = "DESKTOP-7Q2K1LM"), "") {}
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesTagsOwnershipPreview() = Dev {
    val ctx = LocalContext.current
    val desktop = AdminDevicesDemo.device("desktop-home")
    DialogFrame(ctx.getString(R.string.admin_dev_tags_title, desktop.shortName), confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin_dev_tags_save)) } }) {
        TagsFields(desktop, AdminDemo.state.policyTags, listOf("tag:server"), "", {}, {}, {})
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminDevicesConfirmTagsPreview() = Dev(dark = false) {
    val ctx = LocalContext.current
    Confirm(DeviceChanges.setTags(ctx, AdminDevicesDemo.device("desktop-home"), listOf("tag:server"), "nSELF7").change, typed = "")
}
