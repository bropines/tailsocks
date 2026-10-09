package io.github.bropines.tailscaled.admin.headscale

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Rfc3339
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleServer
import io.github.bropines.tailscaled.admin.api.headscale.HsApiKey
import io.github.bropines.tailscaled.admin.api.headscale.PolicyMode
import io.github.bropines.tailscaled.admin.api.headscale.RegistrationLink
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.logs.LocalRecordCard
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.readText
import io.github.bropines.tailscaled.ui.rememberQrScanner
import kotlinx.coroutines.launch

/**
 * The Headscale tab: the server and how far this app can reach into it, registering a device
 * from the link `tailscale up` shows, pre-auth keys, local users, the server's API keys, and
 * the changes this phone made. Everything that writes goes through the console's gates.
 */
@Composable
fun HeadscaleTab(
    state: ConsoleState,
    hs: HeadscaleUiState,
    vm: AdminConsoleViewModel?,
    onManageKeys: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val ctx = LocalContext.current
    val console = vm?.headscale
    var newKey by rememberSaveable { mutableStateOf(false) }
    var newUser by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var newApiKey by rememberSaveable { mutableStateOf(false) }
    val users = state.users.value.orEmpty().sortedBy { it.loginName }
    val server = hs.server.value
    val ownPrefix = state.caps?.ownKeyId

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "server") { ServerCard(server, state.active?.baseUrl.orEmpty(), hs, onRetry = { vm?.refresh(ConsoleTab.SERVER, force = true) }) }
        item(key = "register") {
            RegisterCard(
                hs = hs,
                server = server,
                users = users,
                canWrite = state.canWrite(AdminArea.DEVICES),
                serverHost = console?.serverHost ?: state.active?.baseUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() },
                onLinkText = { console?.onLinkText(it) },
                onClear = { console?.clearLink() },
                onRegister = { link, user -> console?.register(link, user) },
                onReject = { console?.reject(it) },
            )
        }
        item(key = "keys") {
            SectionCard(Icons.Default.VpnKey, ctx.getString(R.string.admin_hs_keys_title)) {
                HelpText(ctx.getString(R.string.admin_hs_keys_help, state.active?.baseUrl.orEmpty()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onManageKeys, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Text(ctx.getString(R.string.admin_hs_keys_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(onClick = { newKey = true }, enabled = state.canWrite(AdminArea.AUTH_KEYS), modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ctx.getString(R.string.admin_hs_keys_new), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item(key = "users") {
            UsersCard(
                users = users,
                loading = state.users,
                canWrite = state.canWrite(AdminArea.USERS),
                onAdd = { newUser = true },
                onRename = { renaming = it.id },
                onDelete = { vm?.deleteUser(it) },
                onRetry = { vm?.refresh(ConsoleTab.SERVER, force = true) },
            )
        }
        item(key = "apikeys") {
            ApiKeysCard(hs, ownPrefix, state.canWrite(AdminArea.API_TOKENS), now, onNew = { newApiKey = true }, onExpire = { console?.expireApiKey(it) },
                onRetry = { vm?.refresh(ConsoleTab.SERVER, force = true) })
        }
        item(key = "local-title") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_log_section_phone), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.localLog.isNotEmpty()) TextButton(onClick = { vm?.clearLocalLog() }) { Text(ctx.getString(R.string.admin_log_phone_clear)) }
            }
        }
        if (state.localLog.isEmpty()) item(key = "local-empty") {
            Text(ctx.getString(R.string.admin_log_phone_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        items(state.localLog.take(LOCAL_SHOWN), key = { "local-${it.time}-${it.targetId}-${it.kind}" }) { LocalRecordCard(it) }
    }

    if (newKey) {
        NewPreAuthKeyDialog(users, state.policyTags, onDismiss = { newKey = false }) { console?.createPreAuthKey(it) }
    }
    if (newUser) {
        UserNameDialog(ctx.getString(R.string.admin_hs_user_add_title), null, withDetails = true, onDismiss = { newUser = false }) { name, display, email ->
            console?.createUser(name, display, email)
        }
    }
    renaming?.let { id ->
        val user = users.firstOrNull { it.id == id }
        // Gone after a refresh (deleted elsewhere, say): the dialog closes with it.
        LaunchedEffect(user == null) { if (user == null) renaming = null }
        if (user != null) UserNameDialog(ctx.getString(R.string.admin_hs_user_rename_title, user.loginName), user.loginName, withDetails = false, onDismiss = { renaming = null }) { name, _, _ ->
            if (name != user.loginName) console?.renameUser(user, name)
        }
    }
    if (newApiKey) NewApiKeyDialog(onDismiss = { newApiKey = false }) { console?.createApiKey(it) }
}

private const val LOCAL_SHOWN = 10

@Composable
private fun SectionCard(icon: ImageVector, title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun ServerCard(server: HeadscaleServer?, baseUrl: String, hs: HeadscaleUiState, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val version = server?.version
    val title = when {
        version == null -> ctx.getString(R.string.admin_hs_server_title_plain)
        version.development -> ctx.getString(R.string.admin_hs_server_title_dev)
        else -> ctx.getString(R.string.admin_hs_server_title, version.label)
    }
    SectionCard(Icons.Default.Dns, title) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(ctx.getString(R.string.admin_hs_server_address), baseUrl, mono = true)
                server?.let {
                    InfoRow(
                        ctx.getString(R.string.admin_hs_server_api),
                        ctx.getString(if (it.kind == BackendKind.HEADSCALE_V2) R.string.admin_hs_server_api_v2 else R.string.admin_hs_server_api_v1),
                    )
                    if (version?.development == true) InfoRow(ctx.getString(R.string.admin_hs_server_build), version.raw, mono = true)
                    InfoRow(
                        ctx.getString(R.string.admin_hs_server_policy),
                        ctx.getString(
                            when (it.policyMode) {
                                PolicyMode.DATABASE -> R.string.admin_hs_policy_database
                                PolicyMode.FILE -> R.string.admin_hs_policy_file
                                PolicyMode.UNKNOWN -> R.string.admin_hs_policy_unknown
                            }
                        ),
                    )
                    it.nodeKeyDays?.let { d ->
                        InfoRow(
                            ctx.getString(R.string.admin_hs_server_node_expiry),
                            if (d <= 0) ctx.getString(R.string.admin_hs_server_node_expiry_never) else ctx.resources.getQuantityString(R.plurals.pickers_days, d, d),
                        )
                    }
                }
            }
        }
        HelpText(ctx.getString(R.string.admin_hs_all_access))
        LoadProblems(hs.server, onRetry)
    }
}

@Composable
private fun RegisterCard(
    hs: HeadscaleUiState,
    server: HeadscaleServer?,
    users: List<ApiUser>,
    canWrite: Boolean,
    serverHost: String?,
    onLinkText: (String) -> Unit,
    onClear: () -> Unit,
    onRegister: (RegistrationLink, ApiUser) -> Unit,
    onReject: (RegistrationLink) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val scan = rememberQrScanner { onLinkText(it) }
    SectionCard(Icons.Default.QrCodeScanner, ctx.getString(R.string.admin_hs_register_title)) {
        HelpText(ctx.getString(R.string.admin_hs_register_help))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = scan, enabled = canWrite, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(ctx.getString(R.string.admin_hs_register_scan), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { scope.launch { onLinkText(clipboard.readText(ctx).orEmpty()) } },
                enabled = canWrite,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(ctx.getString(R.string.admin_hs_register_paste), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        hs.linkProblem?.let { problem ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(
                    ctx.getString(
                        when (problem) {
                            RegistrationLink.Problem.EMPTY -> R.string.admin_hs_register_problem_empty
                            RegistrationLink.Problem.NOT_A_LINK -> R.string.admin_hs_register_problem_link
                            RegistrationLink.Problem.BAD_ID -> R.string.admin_hs_register_problem_id
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        hs.link?.let { link ->
            RegistrationPanel(link, server, users, canWrite, serverHost, onClear, onRegister, onReject)
        }
    }
}

/** A link in hand: whose device it becomes, then Register (or Reject, from 0.29). */
@Composable
fun RegistrationPanel(
    link: RegistrationLink,
    server: HeadscaleServer?,
    users: List<ApiUser>,
    canWrite: Boolean,
    serverHost: String?,
    onClear: () -> Unit,
    onRegister: (RegistrationLink, ApiUser) -> Unit,
    onReject: (RegistrationLink) -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var chosen by rememberSaveable(link.authId) { mutableStateOf(users.singleOrNull()?.id) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val user = users.firstOrNull { it.id == chosen }
    val otherServer = serverHost != null && link.host != null && !link.host.equals(serverHost, ignoreCase = true)
    val wrongShape = server != null && server.authIdPrefixed != link.isPrefixed
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ctx.getString(R.string.admin_hs_register_waiting), style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(link.authId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
            }
            link.host?.let { Text(ctx.getString(R.string.admin_hs_register_from, it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            if (otherServer) Warning(ctx.getString(R.string.admin_hs_register_other_server, link.host, serverHost))
            if (wrongShape) Warning(ctx.getString(R.string.admin_hs_register_wrong_version))
            OutlinedButton(onClick = { picking = true }, enabled = canWrite && users.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Group, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    user?.let { ctx.getString(R.string.admin_hs_register_as, it.loginName) } ?: ctx.getString(R.string.admin_hs_register_choose_user),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (users.isEmpty()) Text(ctx.getString(R.string.admin_hs_register_no_users), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClear) { Text(ctx.getString(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                if (server?.canRejectRegistration == true) {
                    OutlinedButton(onClick = { onReject(link) }, enabled = canWrite, shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)) {
                        Text(ctx.getString(R.string.admin_hs_register_reject))
                    }
                }
                Button(onClick = { user?.let { onRegister(link, it) } }, enabled = canWrite && user != null, shape = MaterialTheme.shapes.medium) {
                    Text(ctx.getString(R.string.admin_hs_register_do))
                }
            }
        }
    }
    if (picking) {
        PickerSheet(
            title = ctx.getString(R.string.admin_hs_register_choose_user),
            options = users.map { PickerOption(it.id, it.loginName, supporting = it.displayName?.takeIf { d -> d != it.loginName }) },
            selected = chosen ?: "",
            onPick = { chosen = it },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Warning(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun UsersCard(
    users: List<ApiUser>,
    loading: Loadable<List<ApiUser>>,
    canWrite: Boolean,
    onAdd: () -> Unit,
    onRename: (ApiUser) -> Unit,
    onDelete: (ApiUser) -> Unit,
    onRetry: () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    SectionCard(Icons.Default.Group, ctx.getString(R.string.admin_hs_users_title), trailing = {
        if (canWrite) TextButton(onClick = onAdd) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(ctx.getString(R.string.admin_hs_user_add))
        }
    }) {
        HelpText(ctx.getString(R.string.admin_hs_users_help))
        LoadProblems(loading, onRetry)
        if (users.isEmpty() && !loading.loading) Text(ctx.getString(R.string.admin_hs_users_none), style = MaterialTheme.typography.bodyMedium, color = scheme.outline)
        users.forEachIndexed { i, u ->
            if (i > 0) HorizontalDivider(color = scheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(u.loginName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    val count = u.deviceCount ?: 0
                    Text(
                        listOfNotNull(
                            u.displayName?.takeIf { it.isNotBlank() && it != u.loginName },
                            ctx.resources.getQuantityString(R.plurals.admin2_user_devices, count, count),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (canWrite) {
                    IconButton(onClick = { onRename(u) }) { Icon(Icons.Default.Edit, ctx.getString(R.string.admin_hs_user_rename, u.loginName)) }
                    IconButton(onClick = { onDelete(u) }) { Icon(Icons.Default.Delete, ctx.getString(R.string.admin_hs_user_delete, u.loginName), tint = scheme.error) }
                }
            }
        }
    }
}

@Composable
private fun ApiKeysCard(hs: HeadscaleUiState, ownPrefix: String?, canWrite: Boolean, now: Long, onNew: () -> Unit, onExpire: (HsApiKey) -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val keys = hs.apiKeys.value.orEmpty()
    SectionCard(Icons.Default.Key, ctx.getString(R.string.admin_hs_apikeys_title), trailing = {
        if (canWrite) TextButton(onClick = onNew) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(ctx.getString(R.string.admin_hs_apikeys_new))
        }
    }) {
        HelpText(ctx.getString(R.string.admin_hs_apikeys_help))
        LoadProblems(hs.apiKeys, onRetry)
        keys.forEachIndexed { i, k ->
            if (i > 0) HorizontalDivider(color = scheme.outlineVariant)
            val own = ownPrefix != null && k.prefix == ownPrefix
            val expired = k.isExpired(now)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k.prefix, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (own) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = MaterialTheme.shapes.small, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                                Text(ctx.getString(R.string.admin_hs_apikeys_own), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(
                        listOfNotNull(
                            if (expired) ctx.getString(R.string.admin_hs_apikeys_expired) else k.expiration?.let { e -> Rfc3339.parse(e) }?.let { ctx.getString(R.string.admin_hs_apikeys_expires, date(ctx, it)) },
                            k.lastSeen?.let { e -> Rfc3339.parse(e) }?.let { ctx.getString(R.string.admin_hs_apikeys_last_used, date(ctx, it)) },
                        ).joinToString(" · ").ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) scheme.outline else scheme.onSurfaceVariant,
                    )
                }
                if (canWrite && !own && !expired) {
                    TextButton(onClick = { onExpire(k) }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) {
                        Text(ctx.getString(R.string.admin_hs_apikeys_expire))
                    }
                }
            }
        }
    }
}

private fun date(ctx: Context, ms: Long): String = DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

/** Lifetimes a pre-auth key is made with. */
private val KEY_HOURS = listOf(1, 24, 24 * 7, 24 * 30, 24 * 90)

/**
 * A pre-auth key: for a user, or tagged (then it belongs to no one, like the devices it
 * registers). Headscale authorizes every device a key registers; there is no "pre-authorized".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewPreAuthKeyDialog(users: List<ApiUser>, policyTags: List<String>, onDismiss: () -> Unit, onCreate: (AuthKeyRequest) -> Unit) {
    val ctx = LocalContext.current
    var userId by rememberSaveable { mutableStateOf(users.singleOrNull()?.id) }
    var tags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var reusable by rememberSaveable { mutableStateOf(false) }
    var ephemeral by rememberSaveable { mutableStateOf(false) }
    var hours by rememberSaveable { mutableIntStateOf(24) }
    val tagged = tags.isNotEmpty()
    val ok = tagged || userId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_hs_keys_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (policyTags.isNotEmpty()) {
                    Text(ctx.getString(R.string.admin_hs_key_tags), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        policyTags.forEach { t ->
                            val on = t in tags
                            FilterChip(selected = on, onClick = { tags = if (on) tags - t else tags + t }, label = { Text(t) })
                        }
                    }
                }
                Text(ctx.getString(if (tagged) R.string.admin_hs_key_tagged_owner else R.string.admin_hs_key_user), style = MaterialTheme.typography.labelLarge)
                if (!tagged) {
                    Column(Modifier.selectableGroup()) {
                        users.forEach { u ->
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                    .selectable(selected = userId == u.id, role = Role.RadioButton) { userId = u.id }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = userId == u.id, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(u.loginName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                SwitchRow(ctx.getString(R.string.admin_hs_key_reusable), reusable) { reusable = it }
                SwitchRow(ctx.getString(R.string.admin_hs_key_ephemeral), ephemeral) { ephemeral = it }
                Text(ctx.getString(R.string.admin_hs_key_expiry), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KEY_HOURS.forEach { h ->
                        FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(hoursLabel(ctx, h)) })
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = ok, onClick = {
                onCreate(
                    AuthKeyRequest(
                        expirySeconds = hours * 3600L, reusable = reusable, ephemeral = ephemeral, preauthorized = true,
                        tags = tags, user = if (tagged) null else userId,
                    )
                )
                onDismiss()
            }) { Text(ctx.getString(R.string.admin_hs_key_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

private fun hoursLabel(ctx: Context, h: Int): String =
    if (h < 24) ctx.resources.getQuantityString(R.plurals.admin_log_window_hours, h, h)
    else ctx.resources.getQuantityString(R.plurals.pickers_days, h / 24, h / 24)

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}

/** Headscale user names: no spaces, lowercase; the server has the last word. */
private val USER_NAME = Regex("^[a-z0-9][a-z0-9._@+-]{0,62}$")

@Composable
fun UserNameDialog(title: String, initial: String?, withDetails: Boolean, onDismiss: () -> Unit, onSave: (name: String, displayName: String?, email: String?) -> Unit) {
    val ctx = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    var display by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val valid = USER_NAME.matches(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.trim() },
                    label = { Text(ctx.getString(R.string.admin_hs_user_name)) },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text(ctx.getString(if (name.isNotEmpty() && !valid) R.string.admin_hs_user_name_error else R.string.admin_hs_user_name_help)) },
                    singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                if (initial != null) HelpText(ctx.getString(R.string.admin_hs_user_rename_help, initial))
                if (withDetails) {
                    OutlinedTextField(value = display, onValueChange = { display = it }, label = { Text(ctx.getString(R.string.admin_hs_user_display)) },
                        singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text(ctx.getString(R.string.admin_hs_user_email)) },
                        singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(name.trim(), display.ifBlank { null }, email.ifBlank { null }); onDismiss() }) {
                Text(ctx.getString(if (initial == null) R.string.action_add else R.string.action_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

private val API_KEY_DAYS = listOf(30, 90, 180, 365)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewApiKeyDialog(onDismiss: () -> Unit, onCreate: (days: Int) -> Unit) {
    val ctx = LocalContext.current
    var days by rememberSaveable { mutableIntStateOf(90) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(ctx.getString(R.string.admin_hs_apikeys_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ctx.getString(R.string.admin_hs_apikeys_new_text), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    API_KEY_DAYS.forEach { d ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(ctx.resources.getQuantityString(R.plurals.pickers_days, d, d)) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(days); onDismiss() }) { Text(ctx.getString(R.string.admin_hs_key_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}
