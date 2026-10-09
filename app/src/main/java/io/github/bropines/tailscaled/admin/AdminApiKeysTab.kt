package io.github.bropines.tailscaled.admin

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText

/**
 * Every key of the tailnet the credential may see (`all=true`): auth keys, API tokens, OAuth
 * clients and federated identities, each labelled as what it is. The console's own credential
 * is marked and has no revoke button.
 */
@Composable
fun KeysTabContent(
    state: Loadable<List<ApiKey>>,
    ownKeyId: String?,
    canWrite: Boolean,
    onRetry: () -> Unit,
    onRevokeClick: (ApiKey) -> Unit,
    onCreateKeyClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val keys = remember(state.value) {
        state.value.orEmpty().sortedWith(
            compareBy<ApiKey> { it.isRevoked || it.invalid == true || (it.expires != null && isTimeExpired(it.expires)) }
                .thenByDescending { it.created.orEmpty() }
        )
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when {
                keys.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
                keys.isEmpty() -> EmptyState(Icons.Default.VpnKey, ctx.getString(R.string.admin_keys_no_active))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(keys, key = { it.id }) { key ->
                        KeyRow(key = key, own = ownKeyId != null && key.id == ownKeyId, canRevoke = canWrite, onRevoke = { onRevokeClick(key) })
                    }
                }
            }
        }
        if (canWrite) {
            FloatingActionButton(
                onClick = onCreateKeyClick,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = ctx.getString(R.string.admin_keys_cd_generate))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyRow(key: ApiKey, own: Boolean, canRevoke: Boolean, onRevoke: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val revoked = key.isRevoked || key.invalid == true
    val expired = !revoked && key.expires != null && isTimeExpired(key.expires)
    val dim = revoked || expired
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (dim) scheme.surfaceContainerLow else scheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background((if (dim) scheme.outline else scheme.primary).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.VpnKey, null, tint = if (dim) scheme.outline else scheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val type = ConsoleText.keyType(ctx, key.type)
                Text(
                    key.description?.takeIf { it.isNotBlank() } ?: type,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (dim) scheme.outline else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("$type · ${key.id}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.outline)
                if (key.expires != null) {
                    Text(ctx.getString(R.string.admin_keys_expires_prefix, formatExpires(key.expires)), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                key.createOptions?.let { o ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (o.reusable == true) StatusTag(ctx.getString(R.string.admin2_key_reusable), scheme.surfaceContainerHighest, scheme.onSurface)
                        if (o.ephemeral == true) StatusTag(ctx.getString(R.string.admin_keys_ephemeral_title), scheme.surfaceContainerHighest, scheme.onSurface)
                        if (o.preauthorized == true) StatusTag(ctx.getString(R.string.admin_keys_preauth_title), scheme.surfaceContainerHighest, scheme.onSurface)
                        o.tags?.forEach { StatusTag(it, scheme.secondaryContainer, scheme.onSecondaryContainer) }
                    }
                }
                if (key.scopes.isNotEmpty()) {
                    Text(key.scopes.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                own -> StatusTag(ctx.getString(R.string.admin2_key_own), scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.Lock)
                revoked -> StatusTag(ctx.getString(R.string.admin_keys_status_revoked), scheme.surfaceContainerHighest, scheme.outline)
                expired -> StatusTag(ctx.getString(R.string.admin_keys_status_expired), scheme.surfaceContainerHighest, scheme.outline)
                canRevoke -> IconButton(onClick = onRevoke) {
                    Icon(Icons.Default.Delete, contentDescription = ctx.getString(R.string.admin_keys_cd_revoke), tint = scheme.error)
                }
            }
        }
    }
}

/**
 * A new auth key: reusable, ephemeral and pre-approved are three switches of their own (the
 * old dialog made every non-ephemeral key reusable), tags from the policy as chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateKeyDialog(
    policyTags: List<String>,
    onDismiss: () -> Unit,
    onGenerate: (AuthKeyRequest) -> Unit,
) {
    val ctx = LocalContext.current
    var desc by rememberSaveable { mutableStateOf("") }
    var expiryDays by rememberSaveable { mutableStateOf("90") }
    var reusable by rememberSaveable { mutableStateOf(false) }
    var ephemeral by rememberSaveable { mutableStateOf(false) }
    var preauth by rememberSaveable { mutableStateOf(false) }
    val tags = remember { mutableStateListOf<String>() }
    var extraTags by rememberSaveable { mutableStateOf("") }
    val days = expiryDays.toLongOrNull()?.takeIf { it in 1..90 }

    @Composable
    fun SwitchRow(title: String, help: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                HelpText(help, inClickableRow = true)
            }
            Switch(checked = value, onCheckedChange = null)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_keys_generate_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = desc,
                    onValueChange = { v -> desc = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(50) },
                    label = { Text(ctx.getString(R.string.admin_keys_desc_label)) },
                    placeholder = { Text(ctx.getString(R.string.admin_keys_desc_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { v -> expiryDays = v.filter { it.isDigit() }.take(2) },
                    label = { Text(ctx.getString(R.string.admin_keys_expiry_label)) },
                    isError = days == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(ctx.getString(R.string.admin2_key_reusable), ctx.getString(R.string.admin2_key_reusable_desc), reusable) { reusable = it }
                SwitchRow(ctx.getString(R.string.admin_keys_ephemeral_title), ctx.getString(R.string.admin2_key_ephemeral_desc), ephemeral) { ephemeral = it }
                SwitchRow(ctx.getString(R.string.admin_keys_preauth_title), ctx.getString(R.string.admin2_key_preauth_desc), preauth) { preauth = it }
                if (policyTags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        policyTags.forEach { tag ->
                            val on = tag in tags
                            FilterChip(selected = on, onClick = { if (on) tags.remove(tag) else tags.add(tag) }, label = { Text(tag.removePrefix("tag:")) })
                        }
                    }
                }
                OutlinedTextField(
                    value = extraTags,
                    onValueChange = { extraTags = it },
                    label = { Text(ctx.getString(R.string.admin_keys_tags_label)) },
                    placeholder = { Text(ctx.getString(R.string.admin_keys_tags_placeholder)) },
                    supportingText = { Text(ctx.getString(R.string.admin_keys_tags_supporting)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = days != null,
                onClick = {
                    val typed = extraTags.split(',').map { it.trim() }.filter { it.startsWith("tag:") && it.length > 4 }
                    onGenerate(
                        AuthKeyRequest(
                            description = desc.trim(),
                            expirySeconds = (days ?: 90) * 24 * 3600,
                            reusable = reusable,
                            ephemeral = ephemeral,
                            preauthorized = preauth,
                            tags = (tags + typed).distinct(),
                        )
                    )
                },
            ) { Text(ctx.getString(R.string.action_generate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}
