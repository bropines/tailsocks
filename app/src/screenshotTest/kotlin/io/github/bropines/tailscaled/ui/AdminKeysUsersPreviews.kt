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
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.api.ApiDeviceInvite
import io.github.bropines.tailscaled.admin.api.ApiInviteAcceptor
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiUserInvite
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.KeyCapabilities
import io.github.bropines.tailscaled.admin.api.KeyCreateOptions
import io.github.bropines.tailscaled.admin.api.KeyDeviceCapabilities
import io.github.bropines.tailscaled.admin.api.OAuthClientRequest
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.keys.AuthKeyFields
import io.github.bropines.tailscaled.admin.keys.AuthKeyForm
import io.github.bropines.tailscaled.admin.keys.ExpiryPreset
import io.github.bropines.tailscaled.admin.keys.KeyChanges
import io.github.bropines.tailscaled.admin.keys.KeysTab
import io.github.bropines.tailscaled.admin.keys.OAuthClientFields
import io.github.bropines.tailscaled.admin.keys.OAuthClientForm
import io.github.bropines.tailscaled.admin.keys.ScopeLevel
import io.github.bropines.tailscaled.admin.keys.TagOwners
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmButton
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmContent
import io.github.bropines.tailscaled.admin.safety.SecretRevealContent
import io.github.bropines.tailscaled.admin.users.DeviceShareContent
import io.github.bropines.tailscaled.admin.users.InviteAccess
import io.github.bropines.tailscaled.admin.users.InviteFields
import io.github.bropines.tailscaled.admin.users.InviteForm
import io.github.bropines.tailscaled.admin.users.InvitesContent
import io.github.bropines.tailscaled.admin.users.ShareFields
import io.github.bropines.tailscaled.admin.users.ShareForm
import io.github.bropines.tailscaled.admin.users.UserChanges
import io.github.bropines.tailscaled.admin.users.UserDetailContent
import io.github.bropines.tailscaled.admin.users.UsersTab
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The Keys and Users tabs with the showcase's invented tailnet: keys of every type, one about
 * to expire and the console's own; users, invites and a device's shares; the forms and the
 * gates' dialogs drawn without their windows. The clock is fixed so countdowns stay put.
 */

/** 2026-10-09T12:00:00Z. */
private const val NOW = 1_791_547_200_000L

private object KeysUsersDemo {
    private fun auth(id: String, desc: String, created: String, expires: String, reusable: Boolean, ephemeral: Boolean = false, preauth: Boolean = false, tags: List<String> = emptyList(), revoked: String? = null) =
        ApiKey(
            id = id, keyType = "auth", description = desc, created = created, expires = expires, revoked = revoked, invalid = revoked != null,
            capabilities = KeyCapabilities(KeyDeviceCapabilities(KeyCreateOptions(reusable, ephemeral, preauth, tags))),
        )

    val keys = listOf(
        auth("kAUTH1CNTRL", "homelab servers", "2026-09-01T10:00:00Z", "2026-11-30T10:00:00Z", reusable = true, preauth = true, tags = listOf("tag:server")),
        auth("kAUTH3CNTRL", "ci runners", "2026-07-14T08:00:00Z", "2026-10-12T08:00:00Z", reusable = true, ephemeral = true, preauth = true, tags = listOf("tag:ci")),
        auth("kAUTH4CNTRL", "laptop setup", "2026-09-24T09:00:00Z", "2026-10-01T09:00:00Z", reusable = false),
        auth("kAUTH2CNTRL", "old ci key", "2026-01-01T10:00:00Z", "2026-04-01T10:00:00Z", reusable = true, revoked = "2026-02-03T04:05:06Z"),
        ApiKey(id = "kCLIENT1CNTRL", keyType = "client", description = "grafana read-only", created = "2026-02-01T10:00:00Z", scopes = listOf("all:read")),
        ApiKey(
            id = "kCLIENT2CNTRL", keyType = "client", description = "github actions", created = "2026-05-11T10:00:00Z",
            scopes = listOf("devices:core", "auth_keys"), tags = listOf("tag:ci"),
        ),
        ApiKey(id = "kFED1CNTRL", keyType = "federated", description = "deploy from GitHub", created = "2026-06-02T10:00:00Z", scopes = listOf("devices:core"), tags = listOf("tag:ci")),
        ApiKey(id = "kAPI1CNTRL", keyType = "api", description = "phone console", created = "2026-09-20T10:00:00Z", expires = "2026-12-19T10:00:00Z"),
    )

    val owners = mapOf(
        "tag:ci" to listOf("tag:server", "autogroup:admin"),
        "tag:exit" to listOf("autogroup:admin"),
        "tag:server" to listOf("group:ops"),
        "tag:web" to emptyList(),
    )

    val invites = listOf(
        ApiUserInvite("29214", "network-admin", email = "dana@example.com", lastEmailSentAt = "2026-10-08T16:20:00Z", inviteUrl = "https://login.tailscale.com/uinv/demo1"),
        ApiUserInvite("29215", "member", inviteUrl = "https://login.tailscale.com/uinv/demo2"),
    )

    val shares = listOf(
        ApiDeviceInvite(
            "12345", created = "2026-09-12T10:00:00Z", allowExitNode = false, accepted = true,
            acceptedBy = ApiInviteAcceptor(33223, "robin@example.net"), email = "robin@example.net",
        ),
        ApiDeviceInvite("12346", created = "2026-10-07T18:00:00Z", multiUse = true, allowExitNode = true, inviteUrl = "https://login.tailscale.com/admin/invite/demo"),
    )

    val nas = AdminDemo.devices.first { it.shortName == "homelab-nas" }

    val state = AdminDemo.state.copy(
        keys = Loadable(keys, loadedAt = 1),
        tagOwners = Loadable(owners, loadedAt = 1),
        userInvites = Loadable(invites, loadedAt = 1),
        deviceInvites = mapOf(nas.pathId to Loadable(shares, loadedAt = 1)),
        users = Loadable(
            AdminDemo.users.mapIndexed { i, u ->
                u.copy(lastSeen = listOf(null, "2026-10-09T09:12:00Z", null, "2026-08-02T10:00:00Z", "2026-06-30T10:00:00Z")[i], created = "2025-03-0${i + 1}T10:00:00Z")
            },
            loadedAt = 1,
        ),
    )

    /** The console on a scoped OAuth client: tags it owns, invites out of reach. */
    val oauthState = state.copy(
        caps = Capabilities.fromScopes(BackendKind.TAILSCALE, TailscaleBackend.FEATURES, listOf("auth_keys", "devices:core", "users:read", "oauth_keys:read"))
            .copy(credential = CredentialKind.OAUTH_CLIENT, ownKeyId = "kCLIENT2CNTRL", credentialTags = listOf("tag:ci")),
    )
}

@Composable
private fun Theme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A dialog as it would look, without the window the renderer does not have. */
@Composable
private fun Frame(title: String, icon: ImageVector, cancel: Boolean = true, confirm: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, null, Modifier.align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.secondary)
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.weight(1f, fill = false)) { content() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (cancel) TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                    confirm()
                }
            }
        }
    }
}

@Composable
private fun Confirm(change: AdminChange, typed: String) = Frame(
    change.title,
    if (change.changeClass == ChangeClass.HIGH) Icons.Default.Warning else Icons.Default.Lock,
    confirm = { ChangeConfirmButton(change, typed) {} },
) { ChangeConfirmContent(change, typed) {} }

/** A sheet's body on the sheet's colour, without the sheet window. */
@Composable
private fun SheetBody(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(top = 24.dp)) { content() }
        }
    }
}

// ------------------------------------------------------------------------------------- keys

@PreviewTest @AdminGeometries @Composable
fun AdminKeysTabPreview() = Theme { KeysTab(KeysUsersDemo.state, null, now = NOW) }

@PreviewTest @AdminPhone @Composable
fun AdminKeysTabInConsolePreview() = Theme(dark = false) { AdminDashboard(KeysUsersDemo.state, null, {}, startTab = ConsoleTab.KEYS) }

@PreviewTest @AdminPhone @Composable
fun AdminKeysTabOAuthPreview() = Theme { KeysTab(KeysUsersDemo.oauthState, null, now = NOW) }

@PreviewTest @AdminGeometries @Composable
fun AdminCreateAuthKeyPreview() = Theme {
    val ctx = LocalContext.current
    val form = AuthKeyForm(desc = "ci runners", reusable = true, ephemeral = true, preset = ExpiryPreset.WEEK, picked = listOf("tag:ci"))
    Frame(ctx.getString(R.string.admin_k_create_auth_title), Icons.Default.VpnKey, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin_k_create)) } }) {
        AuthKeyFields(form, KeysUsersDemo.owners.keys.toList(), tagsLoading = false, tagsProblem = null, credentialTags = emptyList(), scoped = false)
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminCreateAuthKeyScopedPreview() = Theme(dark = false) {
    val ctx = LocalContext.current
    val form = AuthKeyForm(desc = "bad_name!", preset = ExpiryPreset.CUSTOM, customDays = "120", picked = listOf("tag:ci"), typed = "tag:web, -x")
    Frame(ctx.getString(R.string.admin_k_create_auth_title), Icons.Default.VpnKey, confirm = { Button(onClick = {}, enabled = false) { Text(ctx.getString(R.string.admin_k_create)) } }) {
        AuthKeyFields(
            form, TagOwners.assignable(emptyMap(), listOf("tag:ci"), scoped = true), tagsLoading = false,
            tagsProblem = "The credential may not do this: it needs the scope policy_file:read.", credentialTags = listOf("tag:ci"), scoped = true,
        )
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminCreateOAuthClientPreview() = Theme {
    val ctx = LocalContext.current
    val form = OAuthClientForm(
        desc = "github actions",
        levels = mapOf("devices:core" to ScopeLevel.WRITE, "auth_keys" to ScopeLevel.WRITE, "dns" to ScopeLevel.READ),
        picked = listOf("tag:ci"),
    )
    Frame(ctx.getString(R.string.admin_k_create_client_title), Icons.Default.SmartToy, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin_k_create)) } }) {
        OAuthClientFields(form, KeysUsersDemo.owners.keys.toList(), tagsProblem = null)
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminKeyRevealQrPreview() = Theme {
    val ctx = LocalContext.current
    Frame(ctx.getString(R.string.admin_k_reveal_auth_title), Icons.Default.Key, cancel = false, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin2_secret_saved)) } }) {
        SecretRevealContent("key", ctx.getString(R.string.admin_k_reveal_auth_text), "tskey-auth-kD3m0K3yCNTRL-6RdkZq8v1sQpL2yBxWnT9cHjU4aMfE7", qr = true, startShown = true)
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminKeyRevealMaskedPreview() = Theme(dark = false) {
    val ctx = LocalContext.current
    Frame(ctx.getString(R.string.admin_k_reveal_client_title), Icons.Default.Key, cancel = false, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin2_secret_saved)) } }) {
        SecretRevealContent("client", ctx.getString(R.string.admin_k_reveal_client_text, "kCLIENT9CNTRL"), "tskey-client-kCLIENT9CNTRL-Zq8v1sQpL2yBxWnT9cHjU4aMfE7", qr = true)
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminKeyRevokeConfirmPreview() = Theme {
    val ctx = LocalContext.current
    Confirm(KeyChanges.revoke(ctx, KeysUsersDemo.keys.first { it.id == "kAUTH3CNTRL" }).change, typed = "ci run")
}

@PreviewTest @AdminPhone @Composable
fun AdminCreateKeyConfirmPreview() = Theme(dark = false) {
    val ctx = LocalContext.current
    val request = AuthKeyRequest("ci runners", 7 * 86_400L, reusable = true, ephemeral = true, preauthorized = true, tags = listOf("tag:ci"))
    Confirm(KeyChanges.createAuthKey(ctx, request, "7 days") {}.change, typed = "")
}

@PreviewTest @AdminPhone @Composable
fun AdminOAuthClientConfirmPreview() = Theme {
    val ctx = LocalContext.current
    Confirm(KeyChanges.createOAuthClient(ctx, OAuthClientRequest("terraform", listOf("all"), listOf("tag:infra"))) {}.change, typed = "terraform")
}

// ------------------------------------------------------------------------------------- users

@PreviewTest @AdminGeometries @Composable
fun AdminUsersTabPreview() = Theme { UsersTab(KeysUsersDemo.state, null, now = NOW) }

@PreviewTest @AdminPhone @Composable
fun AdminUsersTabOAuthPreview() = Theme(dark = false) { UsersTab(KeysUsersDemo.oauthState, null, now = NOW) }

@PreviewTest @AdminGeometries @Composable
fun AdminUserDetailPreview() = Theme {
    SheetBody { UserDetailContent(KeysUsersDemo.state.users.value!![2], own = false, canWrite = true, tailscale = true, now = NOW, onAction = { _, _ -> }, onPickRole = {}) }
}

@PreviewTest @AdminPhone @Composable
fun AdminUserDetailOwnerPreview() = Theme(dark = false) {
    SheetBody { UserDetailContent(KeysUsersDemo.state.users.value!![0], own = false, canWrite = true, tailscale = true, now = NOW, onAction = { _, _ -> }, onPickRole = {}) }
}

@PreviewTest @AdminPhone @Composable
fun AdminUserDetailSharedPreview() = Theme {
    SheetBody { UserDetailContent(KeysUsersDemo.state.users.value!![4], own = false, canWrite = true, tailscale = true, now = NOW, onAction = { _, _ -> }, onPickRole = {}) }
}

@PreviewTest @AdminGeometries @Composable
fun AdminRoleConfirmPreview() = Theme {
    val ctx = LocalContext.current
    Confirm(UserChanges.setRole(ctx, KeysUsersDemo.state.users.value!![2], UserRole.ADMIN).change, typed = "jordan@example.com")
}

@PreviewTest @AdminPhone @Composable
fun AdminUserDeleteConfirmPreview() = Theme(dark = false) {
    val ctx = LocalContext.current
    Confirm(UserChanges.delete(ctx, KeysUsersDemo.state.users.value!![1]).change, typed = "")
}

@PreviewTest @AdminPhone @Composable
fun AdminUserRestoreConfirmPreview() = Theme {
    val ctx = LocalContext.current
    Confirm(UserChanges.restore(ctx, KeysUsersDemo.state.users.value!![3]).change, typed = "casey@example.com")
}

@PreviewTest @AdminGeometries @Composable
fun AdminInvitesPreview() = Theme {
    SheetBody {
        InvitesContent(KeysUsersDemo.state.userInvites, InviteAccess.ALLOWED, manage = true, now = NOW, onRetry = {}, onCreate = {}, onResend = {}, onDelete = {})
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminInvitesOAuthPreview() = Theme(dark = false) {
    SheetBody {
        InvitesContent(KeysUsersDemo.state.userInvites, InviteAccess.NEEDS_PERSONAL_TOKEN, manage = false, now = NOW, onRetry = {}, onCreate = {}, onResend = {}, onDelete = {})
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminCreateInvitePreview() = Theme {
    val ctx = LocalContext.current
    Frame(ctx.getString(R.string.admin_u_invite_new), Icons.Default.PersonAdd, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin_u_invite_create)) } }) {
        InviteFields(InviteForm("dana@example.com", UserRole.NETWORK_ADMIN))
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminInviteConfirmPreview() = Theme {
    val ctx = LocalContext.current
    Confirm(UserChanges.createInvite(ctx, null, UserRole.ADMIN, "tail4a2c9.ts.net") {}.change, typed = "tail4a2c9")
}

@PreviewTest @AdminGeometries @Composable
fun AdminDeviceSharePreview() = Theme {
    SheetBody {
        DeviceShareContent(
            KeysUsersDemo.nas, KeysUsersDemo.state.deviceInvites.values.first(), InviteAccess.ALLOWED,
            canCreate = true, canDelete = true, now = NOW, onRetry = {}, onCreate = {}, onDelete = {},
        )
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminCreateSharePreview() = Theme(dark = false) {
    val ctx = LocalContext.current
    Frame(ctx.getString(R.string.admin_u_share_title, KeysUsersDemo.nas.shortName), Icons.Default.Share, confirm = { Button(onClick = {}) { Text(ctx.getString(R.string.admin_u_share_create)) } }) {
        ShareFields(ShareForm("", multiUse = true, exitNode = false))
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminShareDeleteConfirmPreview() = Theme {
    val ctx = LocalContext.current
    Confirm(UserChanges.deleteDeviceInvite(ctx, KeysUsersDemo.nas, KeysUsersDemo.shares.first()).change, typed = "robin@")
}
