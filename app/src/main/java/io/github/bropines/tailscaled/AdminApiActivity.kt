package io.github.bropines.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                AdminApiMainScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun AdminApiMainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    var token by remember { mutableStateOf(profilePrefs.getString("admin_api_token", "") ?: "") }
    var tailnet by remember { mutableStateOf(profilePrefs.getString("admin_api_tailnet", "") ?: "") }
    
    var isSetupMode by remember { mutableStateOf(token.isBlank()) }

    if (isSetupMode) {
        AdminApiSetupScreen(
            onBack = onBack,
            onSave = { enteredToken, enteredTailnet ->
                profilePrefs.edit()
                    .putString("admin_api_token", enteredToken)
                    .putString("admin_api_tailnet", enteredTailnet)
                    .apply()
                token = enteredToken
                tailnet = enteredTailnet
                isSetupMode = false
            }
        )
    } else {
        AdminApiDashboardScreen(
            token = token,
            tailnet = tailnet,
            onBack = onBack,
            onDisconnect = {
                profilePrefs.edit()
                    .remove("admin_api_token")
                    .remove("admin_api_tailnet")
                    .apply()
                token = ""
                tailnet = ""
                isSetupMode = true
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiSetupScreen(
    onBack: () -> Unit,
    onSave: (String, String) -> Unit
) {
    val context = LocalContext.current
    var enteredToken by remember { mutableStateOf("") }
    var enteredTailnet by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin API Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )

            Text(
                text = "Tailscale API Integration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Configure Tailscale public API Access Token to manage your tailnet (devices, auth keys, and DNS settings) directly from the application.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = enteredToken,
                onValueChange = { enteredToken = it },
                label = { Text("API Access Token (tskey-api-...)") },
                placeholder = { Text("tskey-api-XXXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = enteredTailnet,
                onValueChange = { enteredTailnet = it },
                label = { Text("Tailnet Name (Optional)") },
                placeholder = { Text("Use - for default tailnet") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                supportingText = {
                    Text("Leave blank or use '-' to automatically query the tailnet associated with this token.")
                }
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (enteredToken.isBlank()) {
                        Toast.makeText(context, "API Access Token is required", Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(enteredToken.trim(), enteredTailnet.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("Save & Connect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiDashboardScreen(
    token: String,
    tailnet: String,
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Devices", "Auth Keys", "DNS")

    val client = remember(token, tailnet) { TailscaleApiClient(token, tailnet) }

    // State holders
    var isRefreshing by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ApiDevice>>(emptyList()) }
    var keys by remember { mutableStateOf<List<ApiKeyInfo>>(emptyList()) }
    var magicDnsEnabled by remember { mutableStateOf(false) }
    var dnsNameservers by remember { mutableStateOf<List<String>>(emptyList()) }

    var selectedDevice by remember { mutableStateOf<ApiDevice?>(null) }
    var showCreateKeyDialog by remember { mutableStateOf(false) }
    var generatedKeyToShow by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun refreshTab(tabIndex: Int) {
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            try {
                when (tabIndex) {
                    0 -> {
                        val list = client.listDevices()
                        withContext(Dispatchers.Main) { devices = list }
                    }
                    1 -> {
                        val list = client.listKeys()
                        withContext(Dispatchers.Main) { keys = list.sortedBy { it.revoked == true } }
                    }
                    2 -> {
                        val pref = client.getDnsPreferences()
                        val ns = client.getDnsNameservers()
                        withContext(Dispatchers.Main) {
                            magicDnsEnabled = pref.magicDNS
                            dnsNameservers = ns
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { isRefreshing = false }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        refreshTab(selectedTab)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Console") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTab(selectedTab) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = { showDisconnectConfirm = true }
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Disconnect API", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { refreshTab(selectedTab) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (selectedTab) {
                        0 -> DevicesTabContent(
                            devices = devices,
                            onDeviceClick = { selectedDevice = it }
                        )
                        1 -> KeysTabContent(
                            keys = keys,
                            onRevokeClick = { key ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.revokeKey(key.id)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Key revoked", Toast.LENGTH_SHORT).show()
                                            refreshTab(1)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onCreateKeyClick = { showCreateKeyDialog = true }
                        )
                        2 -> DnsTabContent(
                            magicDns = magicDnsEnabled,
                            nameservers = dnsNameservers,
                            onMagicDnsChanged = { enabled ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.updateDnsPreferences(enabled)
                                        withContext(Dispatchers.Main) {
                                            magicDnsEnabled = enabled
                                            Toast.makeText(context, "MagicDNS updated", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onApplyNameservers = { updatedList ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.setDnsNameservers(updatedList)
                                        withContext(Dispatchers.Main) {
                                            dnsNameservers = updatedList
                                            Toast.makeText(context, "Nameservers applied", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect API?") },
            text = { Text("This will remove the API credentials for this profile. Continue?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        onDisconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (generatedKeyToShow != null) {
        AlertDialog(
            onDismissRequest = { generatedKeyToShow = null },
            title = { Text("Key Generated Successfully") },
            text = {
                Column {
                    Text("Please copy this key now. It cannot be shown again:")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = generatedKeyToShow!!,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tailscale Auth Key", generatedKeyToShow))
                        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                        generatedKeyToShow = null
                        refreshTab(1)
                    }
                ) {
                    Text("Copy & Close")
                }
            },
            dismissButton = {
                TextButton(onClick = { generatedKeyToShow = null }) { Text("Close") }
            }
        )
    }

    // Modal detailed sheets
    selectedDevice?.let { device ->
        DeviceDetailBottomSheet(
            device = device,
            onDismiss = { selectedDevice = null },
            onRename = { newName ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.renameDevice(device.id, newName)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Device renamed", Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onAuthorize = { authorized ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.setDeviceAuthorized(device.id, authorized)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, if (authorized) "Device authorized" else "Device deauthorized", Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onExpire = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.expireDevice(device.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Device key expired", Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDelete = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteDevice(device.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Device deleted", Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onUpdateTags = { tagsList ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.setDeviceTags(device.id, tagsList)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Device tags updated", Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    if (showCreateKeyDialog) {
        CreateKeyDialog(
            onDismiss = { showCreateKeyDialog = false },
            onGenerate = { desc, expiry, ephemeral, preauth, tagsList ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val newKey = client.createKey(desc, expiry, ephemeral, preauth, tagsList)
                        withContext(Dispatchers.Main) {
                            showCreateKeyDialog = false
                            generatedKeyToShow = newKey.key
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }
}

// --- Devices Tab ---
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

// --- Detailed Device Bottom Sheet ---
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

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

// --- Keys Tab ---
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

// --- Key Generation Dialog ---
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

// --- DNS Tab ---
@Composable
fun DnsTabContent(
    magicDns: Boolean,
    nameservers: List<String>,
    onMagicDnsChanged: (Boolean) -> Unit,
    onApplyNameservers: (List<String>) -> Unit
) {
    val nsListState = remember(nameservers) { mutableStateListOf(*nameservers.toTypedArray()) }
    var newNs by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // MagicDNS Status Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MagicDNS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Register DNS names for devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Switch(checked = magicDns, onCheckedChange = onMagicDnsChanged)
            }
        }

        // Nameservers Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Global Nameservers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                if (nsListState.isEmpty()) {
                    Text("No custom nameservers configured", color = MaterialTheme.colorScheme.outline)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        nsListState.forEach { ns ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(ns, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                IconButton(onClick = { nsListState.remove(ns) }) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                // Add Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newNs,
                        onValueChange = { newNs = it },
                        placeholder = { Text("e.g. 1.1.1.1") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newNs.isNotBlank() && !nsListState.contains(newNs.trim())) {
                                nsListState.add(newNs.trim())
                                newNs = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Add")
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Apply button
                val listChanged = nsListState.toList() != nameservers
                Button(
                    onClick = { onApplyNameservers(nsListState.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = listChanged,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Done, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apply DNS Nameservers")
                }
            }
        }
    }
}

// --- Utils ---
private fun isTimeExpired(isoTime: String): Boolean {
    return try {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = format.parse(isoTime)
        date != null && date.before(java.util.Date())
    } catch (e: Exception) {
        false
    }
}

private fun formatExpires(isoTime: String?): String {
    if (isoTime.isNullOrEmpty() || isoTime.startsWith("0001-01-01")) return "Never / Disabled"
    return try {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = format.parse(isoTime) ?: return isoTime
        
        val displayFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        displayFormat.format(date)
    } catch (e: Exception) {
        isoTime
    }
}
