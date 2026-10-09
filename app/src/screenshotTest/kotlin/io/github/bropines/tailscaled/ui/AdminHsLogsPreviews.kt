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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminConsoleContent
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.api.ApiAuditActor
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry
import io.github.bropines.tailscaled.admin.api.ApiAuditTarget
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiServiceHost
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleServer
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleVersion
import io.github.bropines.tailscaled.admin.api.headscale.HsApiKey
import io.github.bropines.tailscaled.admin.api.headscale.PolicyMode
import io.github.bropines.tailscaled.admin.api.headscale.RegistrationLink
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.ProfileDraft
import io.github.bropines.tailscaled.admin.headscale.HeadscaleChanges
import io.github.bropines.tailscaled.admin.headscale.HeadscaleUiState
import io.github.bropines.tailscaled.admin.logs.AuditLogQuery
import io.github.bropines.tailscaled.admin.logs.LogWindow
import io.github.bropines.tailscaled.admin.logs.LogsTab
import io.github.bropines.tailscaled.admin.logs.auditKey
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmButton
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmContent
import io.github.bropines.tailscaled.admin.services.ServiceChanges
import io.github.bropines.tailscaled.admin.services.ServiceEditorContent
import io.github.bropines.tailscaled.admin.services.ServiceFormState
import io.github.bropines.tailscaled.admin.services.ServiceSheetContent
import io.github.bropines.tailscaled.admin.services.ServicesTab
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/*
 * The console's Logs and Services tabs and its Headscale side with invented data: the
 * tailnet's audit log by day with an entry opened to its before → after, the filters, the
 * changes from this phone; services with their hosts and editor; the Headscale server tab
 * with a device waiting to join; the profile editor set to Headscale; the confirm steps
 * these changes go through. Nothing here is anyone's real network.
 */

@Preview(name = "hs-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "hs-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class HsGeometries

@Preview(name = "hs-phone", device = "spec:width=411dp,height=891dp,dpi=420")
annotation class HsPhone

/** 2026-10-09 14:00 UTC: "today" in every preview, whatever day they are rendered. */
private const val NOW = 1_791_554_400_000L

object HsLogsDemo {
    private fun j(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })

    private const val POLICY_BEFORE = "{\n  \"tagOwners\": {\n    \"tag:server\": [\"alex@example.com\"],\n  },\n  \"acls\": [\n" +
        "    {\"action\": \"accept\", \"src\": [\"autogroup:member\"], \"dst\": [\"autogroup:self:*\"]},\n" +
        "    {\"action\": \"accept\", \"src\": [\"group:admins\"], \"dst\": [\"tag:server:22\"]},\n  ],\n}"
    private val POLICY_AFTER = POLICY_BEFORE.replace("\"tag:server:22\"", "\"tag:server:22,443\"")
        .replace("  ],\n}", "    {\"action\": \"accept\", \"src\": [\"tag:iot\"], \"dst\": [\"tag:server:1883\"]},\n  ],\n}")

    val log = listOf(
        ApiAuditLogEntry(
            eventTime = "2026-10-09T13:40:00Z", origin = "ADMIN_CONSOLE", action = "UPDATE", eventGroupID = "7c1f",
            actor = ApiAuditActor("uALEX", "USER", "alex@example.com", "Alex"),
            target = ApiAuditTarget("n6CNTRL", "raspberry-pi", "NODE", property = "ACL_TAGS"),
            old = j("tag:iot"), new = j("tag:iot", "tag:server"),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-09T11:05:00Z", origin = "CONFIG_API", action = "UPDATE", eventGroupID = "9a02",
            actor = ApiAuditActor("kCLIENT1CNTRL", "OAUTH_CLIENT", displayName = "terraform"),
            target = ApiAuditTarget("TAILNET", "example.com", "TAILNET", property = "ACL"),
            old = JsonPrimitive(POLICY_BEFORE), new = JsonPrimitive(POLICY_AFTER),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-09T09:12:00Z", origin = "ADMIN_CONSOLE", action = "UPDATE",
            actor = ApiAuditActor("uSAM", "USER", "sam@example.com", "Sam"),
            target = ApiAuditTarget("n1CNTRL", "desktop-home", "NODE", property = "MACHINE_NAME"),
            old = JsonPrimitive("desktop-3f9a"), new = JsonPrimitive("desktop-home"),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-08T18:12:00Z", origin = "CONTROL", action = "DELETE",
            actor = ApiAuditActor(type = "AUTOMATED_WORKER"),
            target = ApiAuditTarget("n99CNTRL", "ephemeral-runner", "NODE", isEphemeral = true),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-08T10:30:00Z", origin = "ADMIN_CONSOLE", action = "CREATE",
            actor = ApiAuditActor("uALEX", "USER", "alex@example.com", "Alex"),
            target = ApiAuditTarget("kAUTH1CNTRL", "homelab servers", "API_KEY"),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-06T08:00:00Z", origin = "ADMIN_CONSOLE", action = "UPDATE",
            actor = ApiAuditActor("uALEX", "USER", "alex@example.com", "Alex"),
            target = ApiAuditTarget("uJORDAN", "jordan@example.com", "USER", property = "USER_ROLE"),
            old = JsonPrimitive("member"), new = JsonPrimitive("network-admin"),
        ),
    )

    /** The tags change and the policy file open. */
    val expanded = setOf(auditKey(log[0], 0), auditKey(log[1], 1))

    val services = listOf(
        ApiService("svc:grafana", "Grafana", listOf("100.100.100.5", "fd7a:115c:a1e0::5"), "Dashboards for the homelab", listOf("tcp:443"), listOf("tag:server")),
        ApiService("svc:mqtt", null, listOf("100.100.100.9", "fd7a:115c:a1e0::9"), null, listOf("tcp:1883", "tcp:8883"), listOf("tag:iot")),
        ApiService("svc:web", "Web", listOf("100.100.100.1", "fd7a:115c:a1e0::1"), ports = listOf("tcp:80", "tcp:443")),
    )

    val hosts = listOf(
        ApiServiceHost("n2CNTRL", "approved:manual", "ready"),
        ApiServiceHost("n6CNTRL", "approved:auto", "ready"),
        ApiServiceHost("n3CNTRL", "not-approved", null),
    )

    val devices = AdminDemo.devices

    val hsUsers = listOf(
        ApiUser("1", "Alice", "alice", type = "member", deviceCount = 3, currentlyConnected = true),
        ApiUser("2", null, "bob", type = "member", deviceCount = 1),
        ApiUser("3", null, "ci", type = "member", deviceCount = 0),
    )

    val hsProfile = AdminProfile("hs", "Home Headscale", backend = BackendKind.HEADSCALE_V1, baseUrl = "https://hs.example.com", authType = AuthType.HEADSCALE_KEY)

    val hsCaps = Capabilities(
        BackendKind.HEADSCALE_V1, CredentialKind.HEADSCALE_API_KEY,
        setOf(
            BackendFeature.DEVICES, BackendFeature.DEVICE_ROUTES, BackendFeature.KEYS, BackendFeature.USERS, BackendFeature.POLICY,
            BackendFeature.POLICY_WRITE, BackendFeature.DEVICE_EXPIRE, BackendFeature.DEVICE_KEY_EXPIRY_DISABLE,
            BackendFeature.AUTH_KEY_OWNER, BackendFeature.HEADSCALE_ADMIN,
        ),
        defaultAccess = Access.WRITE,
        ownKeyId = "hskey-api-AbCdEfGh1234-***",
    )

    val link = (RegistrationLink.parse("https://hs.example.com/register/hskey-authreq-Qm7tZx2LbV9pRc4WnKa8sYd1") as RegistrationLink.Parsed.Ok).link

    val hsState = HeadscaleUiState(
        server = Loadable(
            HeadscaleServer(BackendKind.HEADSCALE_V1, HeadscaleVersion.parse("v0.29.4"), PolicyMode.DATABASE, "hskey-api-AbCdEfGh1234-***", canRejectRegistration = true),
            loadedAt = 1,
        ),
        apiKeys = Loadable(
            listOf(
                HsApiKey("1", "hskey-api-AbCdEfGh1234-***", "2027-01-07T17:07:31Z", "2026-10-09T17:07:31Z", "2026-10-09T13:58:00Z"),
                HsApiKey("2", "hskey-api-Zq81LmNo42Xy-***", "2026-12-01T00:00:00Z", "2026-09-01T00:00:00Z", null),
                HsApiKey("3", "hskey-api-Old0Key9Abcd-***", "2026-08-01T00:00:00Z", "2026-05-01T00:00:00Z", null),
            ),
            loadedAt = 1,
        ),
        link = link,
    )

    val hsConsole = AdminDemo.state.copy(
        profiles = listOf(hsProfile),
        active = hsProfile,
        caps = hsCaps,
        users = Loadable(hsUsers, loadedAt = 1),
        policyTags = listOf("tag:ci", "tag:server"),
        localLog = AdminDemo.state.localLog.take(2),
    )
}

@Composable
private fun Hs(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@PreviewTest @HsGeometries @Composable
fun AdminHsTailnetLogPreview() = Hs {
    LogsTab(Loadable(HsLogsDemo.log, loadedAt = 1), AuditLogQuery(), {}, {}, AdminDemo.state.localLog, {}, now = NOW, expandedAtStart = HsLogsDemo.expanded)
}

@PreviewTest @HsPhone @Composable
fun AdminHsTailnetLogFilteredPreview() = Hs(dark = false) {
    LogsTab(
        Loadable(emptyList(), loadedAt = 1),
        AuditLogQuery(LogWindow.DAY, actor = "jordan", event = "NODE.UPDATE.ACL_TAGS"),
        {}, {}, emptyList(), {}, now = NOW,
    )
}

@PreviewTest @HsPhone @Composable
fun AdminHsPhoneLogPreview() = Hs(dark = false) {
    LogsTab(Loadable(emptyList()), AuditLogQuery(), {}, {}, AdminDemo.state.localLog, {}, startOnLocal = true, now = 1_791_600_000_000L)
}

@PreviewTest @HsGeometries @Composable
fun AdminHsServicesPreview() = Hs {
    ServicesTab(Loadable(HsLogsDemo.services, loadedAt = 1), canWrite = true, onRetry = {}, onServiceClick = {}, onCreate = {})
}

@PreviewTest @HsPhone @Composable
fun AdminHsServiceSheetPreview() = Hs(dark = false) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
        ServiceSheetContent(ctx, HsLogsDemo.services[0], Loadable(HsLogsDemo.hosts, loadedAt = 1), HsLogsDemo.devices, true, {}, { _, _, _ -> }, {}, {})
    }
}

@PreviewTest @HsGeometries @Composable
fun AdminHsServiceEditorPreview() = Hs {
    val ctx = LocalContext.current
    val s = HsLogsDemo.services[1]
    val form = ServiceFormState("broker", "", "tcp:1883, tcp:8883", "", "", listOf("tag:iot"))
    DialogFrame(stringResource(R.string.admin_svc_editor_edit), null, confirm = { TextButton(onClick = {}) { Text(stringResource(R.string.action_save)) } }) {
        ServiceEditorContent(ctx, form, s, listOf("tag:iot", "tag:server"))
    }
}

/** Through the dashboard, the way the tab shows for a Headscale profile; a device is waiting. */
@PreviewTest @HsGeometries @Composable
fun AdminHsServerTabPreview() = Hs {
    AdminDashboard(HsLogsDemo.hsConsole, null, {}, startTab = ConsoleTab.SERVER, headscaleDemo = HsLogsDemo.hsState)
}

@PreviewTest @HsPhone @Composable
fun AdminHsServerTabNoLinkPreview() = Hs(dark = false) {
    AdminDashboard(
        HsLogsDemo.hsConsole.copy(caps = HsLogsDemo.hsCaps.copy(backend = BackendKind.HEADSCALE_V2)), null, {}, startTab = ConsoleTab.SERVER,
        headscaleDemo = HsLogsDemo.hsState.copy(
            link = null,
            linkProblem = RegistrationLink.Problem.NOT_A_LINK,
            server = Loadable(
                HeadscaleServer(BackendKind.HEADSCALE_V2, HeadscaleVersion.parse("v0.0.0-20261009094437-a8d6f5be81e5"), PolicyMode.FILE, nodeKeyDays = 0, canRejectRegistration = true),
                loadedAt = 1,
            ),
        ),
    )
}

@PreviewTest @HsGeometries @Composable
fun AdminHsProfileEditorPreview() = Hs(dark = false) {
    AdminConsoleContent(
        HsLogsDemo.hsConsole.copy(
            phase = ConsolePhase.EDIT_PROFILE,
            draft = ProfileDraft(
                id = "hs", name = "Home Headscale", backend = BackendKind.HEADSCALE_V1, baseUrl = "https://hs.example.com",
                authType = AuthType.HEADSCALE_KEY, hasStoredSecret = true,
            ),
        ),
        null, {},
    )
}

/** A dialog as the gates draw it, without the window the renderer does not have. */
@Composable
private fun DialogFrame(title: String, high: Boolean?, confirm: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (high != null) {
                    Icon(if (high) Icons.Default.Warning else Icons.Default.Lock, null, Modifier.align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.secondary)
                }
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.weight(1f, fill = false)) { content() }
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
    change.title, change.changeClass == ChangeClass.HIGH, confirm = { ChangeConfirmButton(change, typed) {} },
) { ChangeConfirmContent(change, typed) {} }

@PreviewTest @HsGeometries @Composable
fun AdminHsConfirmRegisterPreview() = Hs {
    val ctx = LocalContext.current
    Confirm(HeadscaleChanges.register(ctx, HsLogsDemo.link, HsLogsDemo.hsUsers[1], "hs.example.com") {}.change, typed = "")
}

@PreviewTest @HsPhone @Composable
fun AdminHsConfirmRegisterOtherServerPreview() = Hs(dark = false) {
    val ctx = LocalContext.current
    Confirm(HeadscaleChanges.register(ctx, HsLogsDemo.link, HsLogsDemo.hsUsers[0], "vpn.example.org") {}.change, typed = "")
}

@PreviewTest @HsGeometries @Composable
fun AdminHsConfirmApiKeyExpirePreview() = Hs {
    val ctx = LocalContext.current
    Confirm(HeadscaleChanges.expireApiKey(ctx, HsLogsDemo.hsState.apiKeys.value!![1]).change, typed = "Zq81LmNo")
}

@PreviewTest @HsPhone @Composable
fun AdminHsConfirmUserRenamePreview() = Hs {
    val ctx = LocalContext.current
    Confirm(HeadscaleChanges.renameUser(ctx, HsLogsDemo.hsUsers[1], "robert").change, typed = "bob")
}

@PreviewTest @HsGeometries @Composable
fun AdminHsConfirmServiceRenamePreview() = Hs(dark = false) {
    val ctx = LocalContext.current
    val s = HsLogsDemo.services[1]
    Confirm(ServiceChanges.update(ctx, s, s.copy(name = "svc:broker")).change, typed = "svc:mq")
}

@PreviewTest @HsPhone @Composable
fun AdminHsConfirmServiceDeletePreview() = Hs {
    val ctx = LocalContext.current
    Confirm(ServiceChanges.delete(ctx, HsLogsDemo.services[0]).change, typed = "")
}
