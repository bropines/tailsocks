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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DevicesTabContent(
    devices: List<ApiDevice>,
    onDeviceClick: (ApiDevice) -> Unit
) {
    var sortBy by remember { mutableStateOf("name") } // name, name_desc, last_seen, update
    
    val sortedDevices = remember(devices, sortBy) {
        when (sortBy) {
            "name" -> devices.sortedBy { it.getDisplayName().lowercase() }
            "name_desc" -> devices.sortedByDescending { it.getDisplayName().lowercase() }
            "last_seen" -> devices.sortedByDescending { it.lastSeen ?: "" }
            "update" -> devices.sortedByDescending { it.updateAvailable == true }
            else -> devices
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${devices.size} devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            
            var expandedSortMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expandedSortMenu = true }) {
                    Icon(Icons.Default.Sort, contentDescription = "Sort Devices")
                }
                DropdownMenu(
                    expanded = expandedSortMenu,
                    onDismissRequest = { expandedSortMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sort by Name (A-Z)") },
                        leadingIcon = { Icon(Icons.Default.SortByAlpha, null) },
                        onClick = { sortBy = "name"; expandedSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by Name (Z-A)") },
                        leadingIcon = { Icon(Icons.Default.SortByAlpha, null) },
                        onClick = { sortBy = "name_desc"; expandedSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by Last Seen") },
                        leadingIcon = { Icon(Icons.Default.AccessTime, null) },
                        onClick = { sortBy = "last_seen"; expandedSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by Update Available") },
                        leadingIcon = { Icon(Icons.Default.SystemUpdate, null) },
                        onClick = { sortBy = "update"; expandedSortMenu = false }
                    )
                }
            }
        }
        
        if (sortedDevices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No devices found", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedDevices) { device ->
                    DeviceRow(device = device, onClick = { onDeviceClick(device) })
                }
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

    val isExpired = device.expires != null && isTimeExpired(device.expires) && device.keyExpiryDisabled != true

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
            if (device.updateAvailable == true) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                )
                Spacer(Modifier.width(8.dp))
            }
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
    client: TailscaleApiClient,
    allTailnetTags: List<String>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAuthorize: (Boolean) -> Unit,
    onExpire: () -> Unit,
    onDelete: () -> Unit,
    onUpdateTags: (List<String>) -> Unit,
    onToggleKeyExpiryDisabled: (Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    var showRenameDialog by remember { mutableStateOf(false) }
    var showTagsDialog by remember { mutableStateOf(false) }
    var showExpireConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Routing and Subnets Local State
    var deviceRoutes by remember { mutableStateOf<DeviceRoutes?>(null) }
    var isLoadingRoutes by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(device.id) {
        scope.launch(Dispatchers.IO) {
            try {
                val r = client.getDeviceRoutes(device.id)
                withContext(Dispatchers.Main) {
                    deviceRoutes = r
                    isLoadingRoutes = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingRoutes = false
                }
            }
        }
    }

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
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

            val context = LocalContext.current

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CopyableDetailBlock("Full Name", device.name)
                CopyableDetailBlock("IP Address", device.getPrimaryIp())
                CopyableDetailBlock("OS", device.os ?: "Unknown")
                CopyableDetailBlock("User Owner", device.user ?: "N/A")
                CopyableDetailBlock("Key Expiry", if (device.keyExpiryDisabled == true) "Disabled" else formatExpires(device.expires))
                CopyableDetailBlock("Authorization", if (device.authorized == true) "Approved" else "Required")
                if (!device.tags.isNullOrEmpty()) {
                    CopyableDetailBlock("Tags", device.tags.joinToString(", "))
                }
            }

            // Update Available info (clickable trigger temporarily disabled)
            if (device.updateAvailable == true) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Column {
                            Text("Update Available", fontWeight = FontWeight.Bold)
                            Text("Current: ${device.clientVersion ?: "N/A"}. Use the web console to trigger an upgrade.", fontSize = 11.sp)
                        }
                    }
                }
            }

            // Key Expiry Row Control
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Disable Key Expiry", fontWeight = FontWeight.Bold)
                        Text("Prevent key from expiring on this specific device.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = device.keyExpiryDisabled == true,
                        onCheckedChange = { onToggleKeyExpiryDisabled(it) }
                    )
                }
            }

            // Routing & Subnets Settings Control
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Routing & Subnets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    if (isLoadingRoutes) {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        val routes = deviceRoutes
                        if (routes == null || (routes.advertisedRoutes.isNullOrEmpty() && routes.enabledRoutes.isNullOrEmpty())) {
                            Text("No subnet routes or exit nodes advertised by this device.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        } else {
                            val advertised = routes.advertisedRoutes ?: emptyList()
                            val enabled = routes.enabledRoutes ?: emptyList()

                            val isExitNodeAdvertised = advertised.contains("0.0.0.0/0")
                            val isExitNodeEnabled = enabled.contains("0.0.0.0/0")

                            if (isExitNodeAdvertised) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Use as Exit Node", fontWeight = FontWeight.Medium)
                                        Text("Route internet traffic through this device.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Switch(
                                        checked = isExitNodeEnabled,
                                        onCheckedChange = { useAsExitNode ->
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val newEnabled = enabled.toMutableList()
                                                    if (useAsExitNode) {
                                                        if (!newEnabled.contains("0.0.0.0/0")) newEnabled.add("0.0.0.0/0")
                                                        if (!newEnabled.contains("::/0") && advertised.contains("::/0")) newEnabled.add("::/0")
                                                    } else {
                                                        newEnabled.remove("0.0.0.0/0")
                                                        newEnabled.remove("::/0")
                                                    }
                                                    val res = client.setDeviceRoutes(device.id, newEnabled)
                                                    withContext(Dispatchers.Main) {
                                                        deviceRoutes = res
                                                    }
                                                } catch (e: Exception) {}
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }

                            val otherAdvertised = advertised.filter { it != "0.0.0.0/0" && it != "::/0" }
                            if (otherAdvertised.isEmpty()) {
                                if (!isExitNodeAdvertised) {
                                    Text("No subnet routes advertised.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            } else {
                                Text("Advertised Subnets", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                otherAdvertised.forEach { route ->
                                    val isRouteEnabled = enabled.contains(route)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(route, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                        Switch(
                                            checked = isRouteEnabled,
                                            onCheckedChange = { enableRoute ->
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val newEnabled = enabled.toMutableList()
                                                        if (enableRoute) {
                                                            if (!newEnabled.contains(route)) newEnabled.add(route)
                                                        } else {
                                                            newEnabled.remove(route)
                                                        }
                                                        val res = client.setDeviceRoutes(device.id, newEnabled)
                                                        withContext(Dispatchers.Main) {
                                                            deviceRoutes = res
                                                        }
                                                    } catch (e: Exception) {}
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
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

                val isExpired = device.expires != null && isTimeExpired(device.expires) && device.keyExpiryDisabled != true
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
        val selectedTags = remember { mutableStateListOf<String>().apply { addAll(device.tags ?: emptyList()) } }

        AlertDialog(
            onDismissRequest = { showTagsDialog = false },
            title = { Text("Edit Device Tags") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (allTailnetTags.isNotEmpty()) {
                        Text("Available ACL Tags:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            items(allTailnetTags) { tag ->
                                val isSelected = selectedTags.contains(tag)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) {
                                            selectedTags.remove(tag)
                                        } else {
                                            selectedTags.add(tag)
                                        }
                                    },
                                    label = { Text(tag.removePrefix("tag:")) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = tempTags,
                        onValueChange = { tempTags = it },
                        placeholder = { Text("tag:server, tag:prod") },
                        label = { Text("Custom Tags (comma separated)") },
                        supportingText = { Text("Tags must start with 'tag:' prefix") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val inputTags = tempTags.split(",")
                            .map { it.trim() }
                            .filter { it.startsWith("tag:") }
                        val finalTags = (selectedTags + inputTags).distinct()
                        onUpdateTags(finalTags)
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
