package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiDashboardScreen(
    token: String,
    tailnet: String,
    clientId: String,
    clientSecret: String,
    proxyMode: String,
    proxyHost: String,
    proxyPort: Int,
    proxyUser: String,
    proxyPass: String,
    onUpdateProxy: (String, String, Int, String, String) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Devices", "Auth Keys", "DNS", "Users", "Settings")

    // Fetch SOCKS5 settings from global configurations
    val localSocksAddr = remember { GlobalSettings.getString(context, "socks5", "127.0.0.1:48115") }
    val localSocksUser = remember { GlobalSettings.getString(context, "socks5_user", "") }
    val localSocksPass = remember { GlobalSettings.getString(context, "socks5_pass", "") }

    val client = remember(token, tailnet, proxyMode, proxyHost, proxyPort, proxyUser, proxyPass, localSocksAddr, localSocksUser, localSocksPass, clientId, clientSecret) {
        TailscaleApiClient(
            token = token,
            tailnetName = tailnet,
            proxyMode = proxyMode,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            localSocksAddr = localSocksAddr,
            localSocksUser = localSocksUser,
            localSocksPass = localSocksPass,
            clientId = clientId,
            clientSecret = clientSecret
        )
    }

    // State holders
    var isRefreshing by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ApiDevice>>(emptyList()) }
    var keys by remember { mutableStateOf<List<ApiKeyInfo>>(emptyList()) }
    var magicDnsEnabled by remember { mutableStateOf(false) }
    var dnsNameservers by remember { mutableStateOf<List<String>>(emptyList()) }
    var splitDns by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var dnsSearchPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var users by remember { mutableStateOf<List<ApiUser>>(emptyList()) }
    var tailnetSettings by remember { mutableStateOf<TailnetSettings?>(null) }

    // Cache Timestamps
    var lastDevicesFetch by remember { mutableLongStateOf(0L) }
    var lastKeysFetch by remember { mutableLongStateOf(0L) }
    var lastDnsFetch by remember { mutableLongStateOf(0L) }
    var lastUsersFetch by remember { mutableLongStateOf(0L) }
    var lastSettingsFetch by remember { mutableLongStateOf(0L) }

    var selectedDevice by remember { mutableStateOf<ApiDevice?>(null) }
    var showCreateKeyDialog by remember { mutableStateOf(false) }
    var generatedKeyToShow by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showProxySettingsDialog by remember { mutableStateOf(false) }

    fun refreshTab(tabIndex: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val cacheDuration = 60 * 1000L // 60 seconds throttle
        
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            try {
                when (tabIndex) {
                    0 -> {
                        if (force || now - lastDevicesFetch >= cacheDuration || devices.isEmpty()) {
                            val list = client.listDevices()
                            withContext(Dispatchers.Main) {
                                devices = list
                                lastDevicesFetch = now
                            }
                        }
                    }
                    1 -> {
                        if (force || now - lastKeysFetch >= cacheDuration || keys.isEmpty()) {
                            val list = client.listKeys()
                            withContext(Dispatchers.Main) {
                                keys = list.sortedBy { it.revoked == true }
                                lastKeysFetch = now
                            }
                        }
                    }
                    2 -> {
                        if (force || now - lastDnsFetch >= cacheDuration || dnsNameservers.isEmpty()) {
                            val pref = client.getDnsPreferences()
                            val ns = client.getDnsNameservers()
                            val sdns = client.getSplitDns()
                            val sp = client.listDnsSearchPaths()
                            withContext(Dispatchers.Main) {
                                magicDnsEnabled = pref.magicDNS
                                dnsNameservers = ns
                                splitDns = sdns
                                dnsSearchPaths = sp
                                lastDnsFetch = now
                            }
                        }
                    }
                    3 -> {
                        if (force || now - lastUsersFetch >= cacheDuration || users.isEmpty()) {
                            val list = client.listUsers()
                            withContext(Dispatchers.Main) {
                                users = list
                                lastUsersFetch = now
                            }
                        }
                    }
                    4 -> {
                        if (force || now - lastSettingsFetch >= cacheDuration || tailnetSettings == null) {
                            val s = client.getTailnetSettings()
                            withContext(Dispatchers.Main) {
                                tailnetSettings = s
                                lastSettingsFetch = now
                            }
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
        refreshTab(selectedTab, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Admin Console") 
                        Text(tailnet, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTab(selectedTab, force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showProxySettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Proxy Settings")
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
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
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
                    onRefresh = { refreshTab(selectedTab, force = true) },
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
                                            refreshTab(1, force = true)
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
                            splitDns = splitDns,
                            searchPaths = dnsSearchPaths,
                            onMagicDnsChanged = { enabled ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.updateDnsPreferences(enabled)
                                        withContext(Dispatchers.Main) {
                                            magicDnsEnabled = enabled
                                            Toast.makeText(context, "MagicDNS updated", Toast.LENGTH_SHORT).show()
                                            refreshTab(2, force = true)
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
                                            refreshTab(2, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onUpdateSplitDns = { domain, nsList ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.updateSplitDns(domain, nsList)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, if (nsList == null) "Split DNS route removed" else "Split DNS route applied", Toast.LENGTH_SHORT).show()
                                            refreshTab(2, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onApplySearchPaths = { updatedPaths ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.setDnsSearchPaths(updatedPaths)
                                        withContext(Dispatchers.Main) {
                                            dnsSearchPaths = updatedPaths
                                            Toast.makeText(context, "Search paths applied", Toast.LENGTH_SHORT).show()
                                            refreshTab(2, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                        3 -> UsersTabContent(
                            users = users
                        )
                        4 -> TailnetSettingsTabContent(
                            settings = tailnetSettings,
                            onApplySettings = { updatedSettings ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val res = client.updateTailnetSettings(updatedSettings)
                                        withContext(Dispatchers.Main) {
                                            tailnetSettings = res
                                            Toast.makeText(context, "Settings updated", Toast.LENGTH_SHORT).show()
                                            refreshTab(4, force = true)
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
            text = { Text("This will remove the API credentials for this tailnet ($tailnet). Continue?") },
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

    if (showProxySettingsDialog) {
        ProxySettingsDialog(
            initialProxyMode = proxyMode,
            initialProxyHost = proxyHost,
            initialProxyPort = proxyPort,
            initialProxyUser = proxyUser,
            initialProxyPass = proxyPass,
            onDismiss = { showProxySettingsDialog = false },
            onSave = { pmode, phost, pport, puser, ppass ->
                showProxySettingsDialog = false
                onUpdateProxy(pmode, phost, pport, puser, ppass)
                Toast.makeText(context, "Proxy settings saved", Toast.LENGTH_SHORT).show()
                refreshTab(selectedTab, force = true)
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
                        refreshTab(1, force = true)
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
                            refreshTab(0, force = true)
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
                            refreshTab(0, force = true)
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
                            refreshTab(0, force = true)
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
                            refreshTab(0, force = true)
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
                            refreshTab(0, force = true)
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
