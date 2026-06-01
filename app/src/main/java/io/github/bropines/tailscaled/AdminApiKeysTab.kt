package io.github.bropines.tailscaled

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
                Text("No active keys", color = MaterialTheme.colorScheme.outline)
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
            Icon(Icons.Default.Add, contentDescription = "Generate Key")
        }
    }

    if (keyToRevoke != null) {
        AlertDialog(
            onDismissRequest = { keyToRevoke = null },
            title = { Text("Revoke Key?") },
            text = { Text("Are you sure you want to revoke this auth key? Devices using it will no longer authenticate.") },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeClick(keyToRevoke!!)
                        keyToRevoke = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToRevoke = null }) { Text("Cancel") }
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
                    "ID: ${key.id}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "Expires: ${formatExpires(key.expires)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isRevoked && !isExpired) {
                IconButton(
                    onClick = onRevoke
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    text = if (isRevoked) "Revoked" else "Expired",
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
        title = { Text("Generate Auth Key") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description") },
                    placeholder = { Text("e.g. Server node key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = expiryDays,
                    onValueChange = { expiryDays = it },
                    label = { Text("Expires in (Days)") },
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
                        Text("Ephemeral Node Key")
                        Text("Nodes auto-delete when offline", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { preauth = !preauth },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = preauth, onCheckedChange = { preauth = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Pre-authorized Key")
                        Text("Skip admin device approval", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }

                OutlinedTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it },
                    label = { Text("Apply Tags (comma separated)") },
                    placeholder = { Text("tag:server, tag:mobile") },
                    supportingText = { Text("Optional. Requires tag owner definition in ACL") },
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
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
