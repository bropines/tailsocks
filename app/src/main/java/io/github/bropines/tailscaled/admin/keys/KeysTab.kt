package io.github.bropines.tailscaled.admin.keys

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.KeyType
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.formatExpires
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet

private enum class CreateWhat { AUTH_KEY, OAUTH_CLIENT }

/**
 * The Keys tab: every key of the tailnet the credential may see, by type, with what each one
 * can do and when it runs out; new auth keys and OAuth clients; revoking, each through the
 * safety gates. The console's own credential is marked and cannot be revoked from here.
 * [now] is a parameter so a preview draws the same countdowns every time.
 */
@Composable
fun KeysTab(state: ConsoleState, vm: AdminConsoleViewModel?, now: Long = remember { System.currentTimeMillis() }) {
    val ctx = LocalContext.current
    val caps = state.caps
    val canCreateAuth = state.canWrite(AdminArea.AUTH_KEYS)
    val canCreateClient = state.canWrite(AdminArea.OAUTH_KEYS) && caps?.has(BackendFeature.OAUTH_CLIENTS) != false
    var choosing by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf<CreateWhat?>(null) }

    KeysList(
        keys = state.keys,
        ownKeyId = caps?.ownKeyId,
        now = now,
        canRevoke = { type -> state.canWrite(KeyList.revokeArea(type)) },
        onRetry = { vm?.refresh(ConsoleTab.KEYS, force = true) },
        onRevoke = { vm?.keysTab?.revoke(it) },
        onCreate = when {
            canCreateAuth && canCreateClient -> { { choosing = true } }
            canCreateAuth -> { { creating = CreateWhat.AUTH_KEY } }
            canCreateClient -> { { creating = CreateWhat.OAUTH_CLIENT } }
            else -> null
        },
    )

    if (choosing) {
        PickerSheet(
            title = ctx.getString(R.string.admin_k_create_title),
            options = listOf(
                PickerOption(CreateWhat.AUTH_KEY, ctx.getString(R.string.admin2_key_type_auth), Icons.Default.VpnKey, ctx.getString(R.string.admin_k_create_auth_supporting)),
                PickerOption(CreateWhat.OAUTH_CLIENT, ctx.getString(R.string.admin2_key_type_client), Icons.Default.SmartToy, ctx.getString(R.string.admin_k_create_client_supporting)),
            ),
            onPick = { creating = it },
            onDismiss = { choosing = false },
        )
    }

    val what = creating
    LaunchedEffect(what) { if (what != null) vm?.keysTab?.loadTagOwners() }
    val scoped = caps?.credential == CredentialKind.OAUTH_CLIENT
    val owners = state.tagOwners.value.orEmpty()
    val tagsProblem = state.tagOwners.error?.let { ConsoleText.error(ctx, it) }
    when (what) {
        CreateWhat.AUTH_KEY -> CreateAuthKeyDialog(
            tags = TagOwners.assignable(owners, caps?.credentialTags.orEmpty(), scoped),
            tagsLoading = state.tagOwners.loading,
            tagsProblem = tagsProblem,
            credentialTags = if (scoped) caps?.credentialTags.orEmpty() else emptyList(),
            scoped = scoped,
            onDismiss = { creating = null },
            onCreate = { request, expiry ->
                creating = null
                vm?.keysTab?.createAuthKey(request, expiry)
            },
        )
        CreateWhat.OAUTH_CLIENT -> CreateOAuthClientDialog(
            tags = TagOwners.assignable(owners, caps?.credentialTags.orEmpty(), scoped),
            tagsProblem = tagsProblem,
            onDismiss = { creating = null },
            onCreate = { request ->
                creating = null
                vm?.keysTab?.createOAuthClient(request)
            },
        )
        null -> Unit
    }
}

/**
 * The list itself, by section. [canRevoke] answers per key type, since each type has a scope
 * of its own; [onCreate] null hides the add button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KeysList(
    keys: Loadable<List<ApiKey>>,
    ownKeyId: String?,
    now: Long,
    canRevoke: (KeyType) -> Boolean,
    onRetry: () -> Unit,
    onRevoke: (ApiKey) -> Unit,
    onCreate: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val sections = remember(keys.value, now) { KeyList.sections(keys.value.orEmpty(), now) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "problems") { LoadProblems(keys, onRetry) }
            when {
                sections.isEmpty() && keys.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                sections.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.Default.VpnKey, ctx.getString(R.string.admin_k_none), Modifier.fillMaxWidth().padding(vertical = 48.dp))
                }
                else -> sections.forEach { section ->
                    item(key = "h-${section.group}") { SectionHeader(section) }
                    items(section.keys, key = { "k-${it.id}" }) { k ->
                        KeyCard(k, own = ownKeyId != null && k.id == ownKeyId, now = now, canRevoke = canRevoke(k.type), onRevoke = { onRevoke(k) })
                    }
                }
            }
        }
        if (onCreate != null) {
            FloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = ctx.getString(R.string.admin_k_create_title))
            }
        }
    }
}

private fun groupTitle(group: KeyGroup): Int = when (group) {
    KeyGroup.AUTH -> R.string.admin_k_group_auth
    KeyGroup.CLIENT -> R.string.admin_k_group_client
    KeyGroup.FEDERATED -> R.string.admin_k_group_federated
    KeyGroup.API -> R.string.admin_k_group_api
    KeyGroup.OTHER -> R.string.admin_k_group_other
}

private fun groupHelp(group: KeyGroup): Int = when (group) {
    KeyGroup.AUTH -> R.string.admin_k_group_auth_help
    KeyGroup.CLIENT -> R.string.admin_k_group_client_help
    KeyGroup.FEDERATED -> R.string.admin_k_group_federated_help
    KeyGroup.API -> R.string.admin_k_group_api_help
    KeyGroup.OTHER -> R.string.admin_k_group_other_help
}

private fun typeIcon(type: KeyType): ImageVector = when (type) {
    KeyType.AUTH -> Icons.Default.VpnKey
    KeyType.CLIENT -> Icons.Default.SmartToy
    KeyType.FEDERATED -> Icons.Default.Cloud
    KeyType.API -> Icons.Default.Token
    KeyType.UNKNOWN -> Icons.Default.Key
}

@Composable
private fun SectionHeader(section: KeySection) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
        Text(
            ctx.getString(groupTitle(section.group)) + " · " + section.keys.size,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HelpText(ctx.getString(groupHelp(section.group)), lines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyCard(key: ApiKey, own: Boolean, now: Long, canRevoke: Boolean, onRevoke: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val expiry = KeyList.expiryOf(key.expires, now)
    val revoked = key.isRevoked || key.invalid == true
    val dead = revoked || expiry.state == ExpiryState.EXPIRED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (dead) scheme.surfaceContainerLow else scheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (canRevoke && !own && !dead) 4.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background((if (dead) scheme.outline else scheme.primary).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(typeIcon(key.type), null, tint = if (dead) scheme.outline else scheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        key.description?.takeIf { it.isNotBlank() } ?: ConsoleText.keyType(ctx, key.type),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (dead) scheme.outline else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(key.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.outline)
                }
                when {
                    own -> Chip(ctx.getString(R.string.admin_k_this_console), scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.Lock)
                    revoked -> Chip(ctx.getString(R.string.admin_keys_status_revoked), scheme.surfaceContainerHighest, scheme.outline, Icons.Default.Block)
                    expiry.state == ExpiryState.EXPIRED -> Chip(ctx.getString(R.string.admin_keys_status_expired), scheme.surfaceContainerHighest, scheme.outline, Icons.Default.Schedule)
                }
            }
            Spacer(Modifier.size(8.dp))
            ExpiryLine(key, expiry, revoked)
            KeyTraits(key)
            if (own) {
                HelpText(ctx.getString(R.string.admin2_refused_own_credential), modifier = Modifier.padding(top = 6.dp))
            } else if (canRevoke && !dead) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRevoke) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = scheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(ctx.getString(R.string.admin_k_revoke), color = scheme.error)
                    }
                }
            }
        }
    }
}

/** Created, and how long it has left — the warning in words and an icon, not only a colour. */
@Composable
private fun ExpiryLine(key: ApiKey, expiry: KeyExpiry, revoked: Boolean) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val created = key.created?.let { ctx.getString(R.string.admin_k_created, formatExpires(it)) }
    val (text, icon, color) = when {
        revoked -> Triple(key.revoked?.let { ctx.getString(R.string.admin_k_revoked_at, formatExpires(it)) }, null, scheme.outline)
        expiry.state == ExpiryState.NEVER -> Triple(ctx.getString(R.string.admin_k_never_expires), null, scheme.onSurfaceVariant)
        expiry.state == ExpiryState.EXPIRED -> Triple(ctx.getString(R.string.admin_k_expired, relativeText(ctx, expiry.remainingMs)), Icons.Default.Schedule, scheme.outline)
        expiry.state == ExpiryState.SOON -> Triple(ctx.getString(R.string.admin_k_expires_soon, relativeText(ctx, expiry.remainingMs)), Icons.Default.Warning, scheme.error)
        else -> Triple(ctx.getString(R.string.admin_k_expires, relativeText(ctx, expiry.remainingMs), formatExpires(key.expires)), null, scheme.onSurfaceVariant)
    }
    Column {
        if (text != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(14.dp), tint = color)
                    Spacer(Modifier.width(4.dp))
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (icon == Icons.Default.Warning) FontWeight.Bold else null)
            }
        }
        if (created != null) Text(created, style = MaterialTheme.typography.bodySmall, color = scheme.outline)
    }
}

/** What the key does: an auth key's switches and tags, a credential's scopes and tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyTraits(key: ApiKey) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val o = key.createOptions
    val tags = (o?.tags.orEmpty() + key.tags).distinct()
    if (o == null && tags.isEmpty() && key.scopes.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        if (o != null) {
            Chip(ctx.getString(if (o.reusable == true) R.string.admin2_key_reusable else R.string.admin_k_single_use), scheme.surfaceContainerHighest, scheme.onSurface)
            if (o.ephemeral == true) Chip(ctx.getString(R.string.admin_k_ephemeral), scheme.surfaceContainerHighest, scheme.onSurface)
            if (o.preauthorized == true) Chip(ctx.getString(R.string.admin_k_preauthorized), scheme.surfaceContainerHighest, scheme.onSurface)
        }
        tags.forEach { Chip(it, scheme.secondaryContainer, scheme.onSecondaryContainer) }
        key.scopes.forEach { Chip(it, scheme.tertiaryContainer, scheme.onTertiaryContainer) }
    }
}
