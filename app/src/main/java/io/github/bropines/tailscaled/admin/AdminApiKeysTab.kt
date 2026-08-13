package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun KeysTabContent(
    keys: List<ApiKeyInfo>,
    onRevokeClick: (ApiKeyInfo) -> Unit,
    onCreateKeyClick: () -> Unit
) {
    var keyToRevoke by remember { mutableStateOf<ApiKeyInfo?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (keys.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.admin_keys_no_active), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(keys) { key ->
                    KeyRow(key = key, onRevoke = { keyToRevoke = key })
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateKeyClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.admin_keys_cd_generate))
        }
    }

    if (keyToRevoke != null) {
        AlertDialog(
            onDismissRequest = { keyToRevoke = null },
            title = { Text(stringResource(R.string.admin_keys_revoke_title)) },
            text = { Text(stringResource(R.string.admin_keys_revoke_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeClick(keyToRevoke!!)
                        keyToRevoke = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToRevoke = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
fun KeyRow(
    key: ApiKeyInfo,
    onRevoke: () -> Unit
) {
    val isRevoked = key.revoked == true
    val isExpired = key.expires != null && isTimeExpired(key.expires)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isRevoked || isExpired) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRevoked || isExpired) MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    null,
                    tint = if (isRevoked || isExpired) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    key.description?.takeIf { it.isNotBlank() } ?: "Auth Key (${key.id})",
                    fontWeight = FontWeight.Bold,
                    color = if (isRevoked || isExpired) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.admin_keys_id_prefix, key.id),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.admin_keys_expires_prefix, formatExpires(key.expires)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isRevoked && !isExpired) {
                IconButton(
                    onClick = onRevoke
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.admin_keys_cd_revoke), tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    text = if (isRevoked) stringResource(R.string.admin_keys_status_revoked) else stringResource(R.string.admin_keys_status_expired),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun CreateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (String, Long, Boolean, Boolean, List<String>?) -> Unit
) {
    var desc by remember { mutableStateOf("") }
    var expiryDays by remember { mutableStateOf("90") }
    var ephemeral by remember { mutableStateOf(false) }
    var preauth by remember { mutableStateOf(true) }
    var tagsInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_keys_generate_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.admin_keys_desc_label)) },
                    placeholder = { Text(stringResource(R.string.admin_keys_desc_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { newValue ->
                        expiryDays = newValue.filter { it.isDigit() }
                    },
                    label = { Text(stringResource(R.string.admin_keys_expiry_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { ephemeral = !ephemeral },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = ephemeral, onCheckedChange = { ephemeral = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.admin_keys_ephemeral_title))
                        Text(stringResource(R.string.admin_keys_ephemeral_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { preauth = !preauth },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = preauth, onCheckedChange = { preauth = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.admin_keys_preauth_title))
                        Text(stringResource(R.string.admin_keys_preauth_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }

                OutlinedTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it },
                    label = { Text(stringResource(R.string.admin_keys_tags_label)) },
                    placeholder = { Text(stringResource(R.string.admin_keys_tags_placeholder)) },
                    supportingText = { Text(stringResource(R.string.admin_keys_tags_supporting)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val days = expiryDays.toLongOrNull() ?: 90
                    val expirySeconds = days * 24 * 3600
                    val tagsList = tagsInput.split(",")
                        .map { it.trim() }
                        .filter { it.startsWith("tag:") }
                        .takeIf { it.isNotEmpty() }
                    
                    onGenerate(desc.trim(), expirySeconds, ephemeral, preauth, tagsList)
                }
            ) {
                Text(stringResource(R.string.action_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
