package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminApiLogsTabContent
import io.github.bropines.tailscaled.admin.AdminApiMainScreen
import io.github.bropines.tailscaled.admin.AdminConsoleContent
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.KeysTabContent
import io.github.bropines.tailscaled.admin.api.ApiAuditActor
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry
import io.github.bropines.tailscaled.admin.api.ApiAuditTarget
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.DecodeIssue
import io.github.bropines.tailscaled.admin.api.DnsConfigPreferences
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.DnsResolver
import io.github.bropines.tailscaled.admin.api.KeyCapabilities
import io.github.bropines.tailscaled.admin.api.KeyCreateOptions
import io.github.bropines.tailscaled.admin.api.KeyDeviceCapabilities
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.CredentialProblem
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.ProfileDraft
import io.github.bropines.tailscaled.admin.console.SelfIdentity
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.AuditRecord
import io.github.bropines.tailscaled.admin.safety.AuditResult
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmButton
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmContent
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.SecretRevealContent
import io.github.bropines.tailscaled.admin.safety.TargetType
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The admin console with an invented tailnet (the showcase's tail4a2c9.ts.net): every tab,
 * the profile editor, the lock in front, the read-only state, and the safety gates' dialogs
 * drawn without their windows. Nothing here is anyone's real network.
 */

@Preview(name = "admin-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "admin-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class AdminGeometries

@Preview(name = "admin-phone", device = "spec:width=411dp,height=891dp,dpi=420")
annotation class AdminPhone

object AdminDemo {
    private const val SUFFIX = "tail4a2c9.ts.net"

    private fun device(
        id: String, host: String, os: String, v4: String, online: Boolean,
        user: String = "alex@example.com", lastSeen: String? = null, tags: List<String> = emptyList(),
        authorized: Boolean = true, expires: String = "2027-03-01T00:00:00Z", update: Boolean = false,
        external: Boolean = false, suffix: String = SUFFIX, multiple: Boolean = false, version: String = "1.104.0",
    ) = ApiDevice(
        id = id.filter { it.isDigit() }.ifEmpty { "1" }, nodeId = id, name = "$host.$suffix", hostname = host,
        addresses = listOf(v4, "fd7a:115c:a1e0::${v4.substringAfterLast('.')}"), user = user, os = os,
        clientVersion = version, updateAvailable = update, connectedToControl = online,
        lastSeen = if (online) null else lastSeen, keyExpiryDisabled = false, expires = expires,
        authorized = authorized, isExternal = external, multipleConnections = multiple, tags = tags,
    )

    val devices = listOf(
        device("nSELF7", "pixel-9-pro", "android", "100.101.34.12", true),
        device("n1CNTRL", "desktop-home", "windows", "100.88.12.4", true),
        device("n2CNTRL", "homelab-nas", "linux", "100.72.5.101", true, tags = listOf("tag:server")),
        device("n3CNTRL", "exit-frankfurt", "linux", "100.94.210.8", true, tags = listOf("tag:exit")),
        device("n6CNTRL", "raspberry-pi", "linux", "100.71.3.9", true, tags = listOf("tag:iot"), update = true, version = "1.96.2"),
        device("n7CNTRL", "ipad", "iOS", "100.83.44.2", false, lastSeen = "2026-09-29T21:40:00Z"),
        device("n10CNTRL", "new-laptop", "windows", "100.77.160.99", true, user = "jordan@example.com", authorized = false),
        device("n11CNTRL", "old-vm", "linux", "100.70.0.5", false, lastSeen = "2026-07-30T10:00:00Z", expires = "2026-08-01T00:00:00Z"),
        device("n8CNTRL", "steam-deck", "linux", "100.99.201.50", false, lastSeen = "2026-09-27T18:05:00Z", multiple = true),
        device("n12CNTRL", "family-nas", "linux", "100.64.9.9", true, user = "robin@example.net", external = true, suffix = "tail8f3d1.ts.net"),
    )

    val users = listOf(
        ApiUser("uALEX", "Alex", "alex@example.com", type = "member", role = UserRole.OWNER.wire, status = "active", deviceCount = 6, currentlyConnected = true),
        ApiUser("uSAM", "Sam", "sam@example.com", type = "member", role = UserRole.NETWORK_ADMIN.wire, status = "active", deviceCount = 2),
        ApiUser("uJORDAN", "", "jordan@example.com", type = "member", role = UserRole.MEMBER.wire, status = "needs-approval", deviceCount = 1),
        ApiUser("uCASEY", "Casey", "casey@example.com", type = "member", role = UserRole.MEMBER.wire, status = "suspended", deviceCount = 0),
        ApiUser("uROBIN", "Robin", "robin@example.net", type = "shared", role = UserRole.MEMBER.wire, status = "idle", deviceCount = 1),
    )

    val keys = listOf(
        ApiKey(id = "kAPI1CNTRL", keyType = "api", description = "phone console", created = "2026-09-20T10:00:00Z", expires = "2026-12-19T10:00:00Z"),
        ApiKey(
            id = "kAUTH1CNTRL", keyType = "auth", description = "homelab servers", created = "2026-09-01T10:00:00Z", expires = "2026-11-30T10:00:00Z",
            capabilities = KeyCapabilities(KeyDeviceCapabilities(KeyCreateOptions(reusable = true, ephemeral = false, preauthorized = true, tags = listOf("tag:server")))),
        ),
        ApiKey(id = "kCLIENT1CNTRL", keyType = "client", description = "grafana read-only", created = "2026-02-01T10:00:00Z", scopes = listOf("all:read")),
        ApiKey(id = "kAUTH2CNTRL", keyType = "auth", description = "old ci key", created = "2026-01-01T10:00:00Z", expires = "2026-04-01T10:00:00Z", revoked = "2026-02-03T04:05:06Z"),
    )

    private val log = listOf(
        ApiAuditLogEntry(
            eventTime = "2026-10-08T12:00:00Z", origin = "ADMIN_CONSOLE", action = "UPDATE",
            actor = ApiAuditActor("uALEX", "USER", "alex@example.com", "Alex"),
            target = ApiAuditTarget("n6CNTRL", "raspberry-pi", "NODE", property = "MACHINE_NAME"),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-07T09:30:00Z", origin = "CONFIG_API", action = "CREATE",
            actor = ApiAuditActor("uSAM", "USER", "sam@example.com", "Sam"),
            target = ApiAuditTarget("kAUTH1CNTRL", "homelab servers", "API_KEY"),
        ),
        ApiAuditLogEntry(
            eventTime = "2026-10-06T18:12:00Z", origin = "CONTROL", action = "DELETE",
            actor = ApiAuditActor(type = "AUTOMATED_WORKER"),
            target = ApiAuditTarget("n99CNTRL", "ephemeral-runner", "NODE", isEphemeral = true),
        ),
    )

    private fun record(minutesAgo: Long, kind: ChangeKind, cls: ChangeClass, target: String, effect: String, result: AuditResult, detail: String? = null, ids: List<String> = emptyList()) =
        AuditRecord(
            time = 1_791_600_000_000L - minutesAgo * 60_000, profileId = "demo", profileName = "Home tailnet",
            kind = kind, changeClass = cls, targetType = TargetType.DEVICE, targetId = target, targetName = target,
            effect = effect, result = result, detail = detail, requestIds = ids,
        )

    val profile = AdminProfile("demo", "Home tailnet", tailnetDnsName = SUFFIX)

    val state = ConsoleState(
        phase = ConsolePhase.READY,
        profiles = listOf(profile, AdminProfile("work", "Work", tailnetDnsName = "corp-tailnet.ts.net", readOnly = true)),
        active = profile,
        caps = Capabilities(BackendKind.TAILSCALE, CredentialKind.API_TOKEN, TailscaleBackend.FEATURES, ownKeyId = "kAPI1CNTRL", ownUserId = "uALEX"),
        self = SelfIdentity("nSELF7", "alex@example.com"),
        devices = Loadable(devices, loadedAt = 1),
        policyTags = listOf("tag:exit", "tag:iot", "tag:server", "tag:web"),
        dns = Loadable(
            DnsConfiguration(
                nameservers = listOf(DnsResolver("1.1.1.1"), DnsResolver("9.9.9.9")),
                splitDns = mapOf("corp.example" to listOf(DnsResolver("10.0.0.53"))),
                searchPaths = listOf("corp.example"),
                preferences = DnsConfigPreferences(overrideLocalDNS = false, magicDNS = true),
            ),
            loadedAt = 1,
        ),
        users = Loadable(users, loadedAt = 1),
        keys = Loadable(keys, loadedAt = 1),
        services = Loadable(listOf(ApiService("svc:web", "Web", listOf("100.100.100.1"), ports = listOf("tcp:443"))), loadedAt = 1),
        webhooks = Loadable(
            listOf(ApiWebhook("12345", "https://hooks.example.com/tailscale", "slack", subscriptions = listOf("nodeNeedsApproval", "userNeedsApproval", "nodeKeyExpiringInOneDay"))),
            loadedAt = 1,
        ),
        tailnetLog = Loadable(log, loadedAt = 1),
        localLog = listOf(
            record(3, ChangeKind.DEVICE_RENAME, ChangeClass.LOW, "raspberry-pi", "It becomes raspberry-pi in MagicDNS.", AuditResult.VERIFIED, ids = listOf("8c1e0f2a")),
            record(9, ChangeKind.USER_SUSPEND, ChangeClass.HIGH, "alex@example.com", "The user loses access to the tailnet.", AuditResult.REFUSED, "OWN_USER"),
            record(30, ChangeKind.DEVICE_DELETE, ChangeClass.HIGH, "old-vm", "The device is removed from the tailnet.", AuditResult.FAILED, "403 forbidden", listOf("51b7d3c9")),
        ),
        settings = Loadable(
            TailnetSettings(
                devicesApprovalOn = true, devicesAutoUpdatesOn = false, devicesKeyDurationDays = 180, usersApprovalOn = false,
                usersRoleAllowedToJoinExternalTailnets = "member", networkFlowLoggingOn = false, regionalRoutingOn = true,
                routeSelection = "regional-routing", postureIdentityCollectionOn = null, httpsEnabled = true,
            ),
            loadedAt = 1,
        ),
    )
}

@Composable
private fun Admin(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminDevicesPreview() = Admin { AdminDashboard(AdminDemo.state, null, {}) }

@PreviewTest @AdminPhone @Composable
fun AdminDnsPreview() = Admin(dark = false) { AdminDashboard(AdminDemo.state, null, {}, startTab = ConsoleTab.DNS) }

@PreviewTest @AdminGeometries @Composable
fun AdminUsersPreview() = Admin { AdminDashboard(AdminDemo.state, null, {}, startTab = ConsoleTab.USERS) }

@PreviewTest @AdminPhone @Composable
fun AdminWebhooksPreview() = Admin(dark = false) { AdminDashboard(AdminDemo.state, null, {}, startTab = ConsoleTab.WEBHOOKS) }

@PreviewTest @AdminGeometries @Composable
fun AdminSettingsPreview() = Admin { AdminDashboard(AdminDemo.state, null, {}, startTab = ConsoleTab.SETTINGS) }

@PreviewTest @AdminPhone @Composable
fun AdminTailnetLogPreview() = Admin { AdminDashboard(AdminDemo.state, null, {}, startTab = ConsoleTab.LOGS) }

@PreviewTest @AdminGeometries @Composable
fun AdminLocalLogPreview() = Admin {
    AdminApiLogsTabContent(Loadable(emptyList()), 7, {}, {}, AdminDemo.state.localLog, {}, startOnLocal = true)
}

@PreviewTest @AdminGeometries @Composable
fun AdminKeysPreview() = Admin {
    KeysTabContent(AdminDemo.state.keys, ownKeyId = "kAPI1CNTRL", canWrite = true, onRetry = {}, onRevokeClick = {}, onCreateKeyClick = {})
}

/** Through the real entry point, with the demo handed in as LocalDemo. */
@PreviewTest @AdminPhone @Composable
fun AdminEntryPreview() = Admin {
    CompositionLocalProvider(LocalDemo provides DemoData(admin = AdminDemo.state)) { AdminApiMainScreen(onBack = {}) }
}

@PreviewTest @AdminGeometries @Composable
fun AdminReadOnlyNoLockPreview() = Admin(dark = false) {
    AdminDashboard(AdminDemo.state.copy(lockState = LockState.NO_SCREEN_LOCK), null, {}, startTab = ConsoleTab.DEVICES)
}

@PreviewTest @AdminPhone @Composable
fun AdminReadOnlyProfilePreview() = Admin {
    val work = AdminDemo.state.profiles[1]
    AdminDashboard(AdminDemo.state.copy(active = work), null, {}, startTab = ConsoleTab.SETTINGS)
}

@PreviewTest @AdminGeometries @Composable
fun AdminCredentialProblemPreview() = Admin {
    AdminDashboard(
        AdminDemo.state.copy(
            credentialProblem = CredentialProblem.UNREADABLE,
            devices = Loadable(AdminDemo.devices.take(3), issues = listOf(DecodeIssue("device", 3, "77", "addresses: expected a list"))),
        ),
        null, {},
    )
}

@PreviewTest @AdminGeometries @Composable
fun AdminSetupPreview() = Admin(dark = false) {
    AdminConsoleContent(ConsoleState(phase = ConsolePhase.SETUP, draft = ProfileDraft(name = "Home tailnet")), null, {})
}

@PreviewTest @AdminPhone @Composable
fun AdminEditProfilePreview() = Admin {
    AdminConsoleContent(
        AdminDemo.state.copy(
            phase = ConsolePhase.EDIT_PROFILE,
            draft = ProfileDraft(id = "demo", name = "Home tailnet", hasStoredSecret = true, error = "The credential did not work. The credential was refused: it expired or was revoked."),
        ),
        null, {},
    )
}

@PreviewTest @AdminGeometries @Composable
fun AdminLockedPreview() = Admin { AdminConsoleContent(AdminDemo.state.copy(phase = ConsolePhase.LOCKED), null, {}) }

/** A dialog as the gates draw it, without the window the renderer does not have. */
@Composable
private fun DialogFrame(title: String, icon: ImageVector, cancel: Boolean = true, confirm: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, null, Modifier.align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.secondary)
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (cancel) TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                    confirm()
                }
            }
        }
    }
}

@Composable
private fun ConfirmSample(change: AdminChange, typed: String) = DialogFrame(
    change.title,
    if (change.changeClass == ChangeClass.HIGH) Icons.Default.Warning else Icons.Default.Lock,
    confirm = { ChangeConfirmButton(change, typed) {} },
) { ChangeConfirmContent(change, typed) {} }

@PreviewTest @AdminGeometries @Composable
fun AdminConfirmHighPreview() = Admin {
    val ctx = LocalContext.current
    ConfirmSample(ConsoleChanges.deleteDevice(ctx, AdminDemo.devices.first { it.shortName == "old-vm" }, "nSELF7").change, typed = "old-v")
}

@PreviewTest @AdminPhone @Composable
fun AdminConfirmHighTypedPreview() = Admin(dark = false) {
    val ctx = LocalContext.current
    ConfirmSample(ConsoleChanges.setUserRole(ctx, AdminDemo.users[1], UserRole.MEMBER).change, typed = "sam@example.com")
}

@PreviewTest @AdminGeometries @Composable
fun AdminConfirmMediumPreview() = Admin {
    val ctx = LocalContext.current
    val desktop = AdminDemo.devices.first { it.shortName == "desktop-home" }
    ConfirmSample(ConsoleChanges.setTags(ctx, desktop, listOf("tag:server"), "nSELF7").change, typed = "")
}

@PreviewTest @AdminPhone @Composable
fun AdminConfirmThisPhonePreview() = Admin {
    val ctx = LocalContext.current
    ConfirmSample(ConsoleChanges.setAuthorized(ctx, AdminDemo.devices.first(), false, "nSELF7").change, typed = "")
}

@PreviewTest @AdminGeometries @Composable
fun AdminSecretPreview() = Admin {
    val ctx = LocalContext.current
    DialogFrame(ctx.getString(R.string.admin_key_generated_title), Icons.Default.Key, cancel = false, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin2_secret_saved)) } }) {
        SecretRevealContent("key", ctx.getString(R.string.admin_key_generated_text), "tskey-auth-kD3m0K3yCNTRL-6RdkZq8v1sQpL2yBxWnT9cHjU4aMfE7")
    }
}
