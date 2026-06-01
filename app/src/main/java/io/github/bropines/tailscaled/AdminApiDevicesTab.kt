package io.github.bropines.tailscaled

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DevicesTabContent(
    devices: List<ApiDevice>,
    onDeviceClick: (ApiDevice) -> Unit
) {
    if (devices.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No devices found", color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device ->
                DeviceRow(device = device, onClick = { onDeviceClick(device) })
            }
        }
    }
}

@Composable
fun DeviceRow(
    device: ApiDevice,
    onClick: () -> Unit
) {
    val (osIcon, osColor) = getOsVisuals(device.os)

    val isExpired = device.expires != null && isTimeExpired(device.expires)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(osIcon, null, tint = osColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(device.getDisplayName(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.getPrimaryIp(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                if (!device.tags.isNullOrEmpty()) {
                    Text(
                        device.tags.joinToString(", "),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(8.dp))
            // Status marker
            if (isExpired) {
                Text(
                    "Expired",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else if (device.authorized == false) {
                Text(
                    "Pending",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailBottomSheet(
    device: ApiDevice,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAuthorize: (Boolean) -> Unit,
    onExpire: () -> Unit,
    onDelete: () -> Unit,
    onUpdateTags: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    var showRenameDialog by remember { mutableStateOf(false) }
    var showTagsDialog by remember { mutableStateOf(false) }
    var showExpireConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val (osIcon, osColor) = getOsVisuals(device.os)

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(osIcon, null, tint = osColor, modifier = Modifier.size(28.dp))
            }

            Text(
                device.getDisplayName(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Quick actions
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showRenameDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Rename")
                }

                OutlinedButton(
                    onClick = { showTagsDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Label, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Tags")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("Full Name", device.name)
                    DetailRow("IP Address", device.getPrimaryIp())
                    DetailRow("OS", device.os ?: "Unknown")
                    DetailRow("User Owner", device.user ?: "N/A")
                    DetailRow("Key Expiry", formatExpires(device.expires))
                    DetailRow("Authorization", if (device.authorized == true) "Approved" else "Required")
                    if (!device.tags.isNullOrEmpty()) {
                        DetailRow("Tags", device.tags.joinToString(", "))
                    }
                }
            }

            // Administrative actions
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (device.authorized == false) {
                    Button(
                        onClick = { onAuthorize(true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Authorize Node")
                    }
                } else {
                    OutlinedButton(
                        onClick = { onAuthorize(false) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Cancel, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Deauthorize Node")
                    }
                }

                val isExpired = device.expires != null && isTimeExpired(device.expires)
                if (!isExpired && device.keyExpiryDisabled != true) {
                    Button(
                        onClick = { showExpireConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Icon(Icons.Default.TimerOff, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Expire Key Now")
                    }
                }

                Button(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Device")
                }
            }
        }
    }

    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(device.getDisplayName()) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Device") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    singleLine = true,
                    label = { Text("New Base Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(tempName.trim()); showRenameDialog = false }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTagsDialog) {
        var tempTags by remember { mutableStateOf(device.tags?.joinToString(", ") ?: "") }
        AlertDialog(
            onDismissRequest = { showTagsDialog = false },
            title = { Text("Edit Device Tags") },
            text = {
                OutlinedTextField(
                    value = tempTags,
                    onValueChange = { tempTags = it },
                    placeholder = { Text("tag:server, tag:prod") },
                    label = { Text("Tags (comma separated)") },
                    supportingText = { Text("Tags must start with 'tag:' prefix") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val tagsList = tempTags.split(",")
                            .map { it.trim() }
                            .filter { it.startsWith("tag:") }
                        onUpdateTags(tagsList)
                        showTagsDialog = false
                    }
                ) {
                    Text("Update Tags")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTagsDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showExpireConfirm) {
        AlertDialog(
            onDismissRequest = { showExpireConfirm = false },
            title = { Text("Expire Device Key?") },
            text = { Text("This will immediately log this node out from the tailnet. Continue?") },
            confirmButton = {
                Button(
                    onClick = {
                        showExpireConfirm = false
                        onExpire()
                    }
                ) {
                    Text("Expire")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExpireConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Device?") },
            text = { Text("Are you sure you want to permanently delete this device? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
