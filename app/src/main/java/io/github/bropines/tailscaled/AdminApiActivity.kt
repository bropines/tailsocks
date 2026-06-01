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
import androidx.compose.material.icons.automirrored.filled.*
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
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    val globalPrefs = remember { context.getSharedPreferences("admin_api_keys", Context.MODE_PRIVATE) }

    var resolvedTailnet by remember { mutableStateOf(profilePrefs.getString("last_known_tailnet", "") ?: "") }
    var isLoadingSuffix by remember { mutableStateOf(true) }

    // Auth credentials
    var authType by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_auth_type", "TOKEN") ?: "TOKEN") }
    var token by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString(resolvedTailnet, "") ?: "") }
    var clientId by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_oauth_client_id", "") ?: "") }
    var clientSecret by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_oauth_client_secret", "") ?: "") }

    // Proxy settings
    var proxyMode by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_mode", "DIRECT") ?: "DIRECT") }
    var proxyHost by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_host", "") ?: "") }
    var proxyPort by remember(resolvedTailnet) { mutableIntStateOf(globalPrefs.getInt("${resolvedTailnet}_proxy_port", 0)) }
    var proxyUser by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_user", "") ?: "") }
    var proxyPass by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_pass", "") ?: "") }

    // Fetch magicDnsSuffix from LocalAPI on start
    LaunchedEffect(activeAccount.id) {
        scope.launch(Dispatchers.IO) {
            try {
                val pJson = appctr.Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error")) {
                    val status = com.google.gson.Gson().fromJson(pJson, StatusResponse::class.java)
                    val suffix = status.magicDnsSuffix?.trim()?.removeSuffix(".")
                    if (!suffix.isNullOrBlank()) {
                        profilePrefs.edit().putString("last_known_tailnet", suffix).apply()
                        withContext(Dispatchers.Main) {
                            resolvedTailnet = suffix
                        }
                    }
                }
            } catch (e: Exception) {}
            finally {
                withContext(Dispatchers.Main) {
                    isLoadingSuffix = false
                }
            }
        }
    }

    val hasCredentials = if (authType == "TOKEN") {
        token.isNotBlank()
    } else {
        clientId.isNotBlank() && clientSecret.isNotBlank()
    }

    if (isLoadingSuffix) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (resolvedTailnet.isBlank()) {
        AdminApiNoTailnetScreen(
            onBack = onBack,
            onSaveTailnet = { enteredTailnet ->
                profilePrefs.edit().putString("last_known_tailnet", enteredTailnet).apply()
                resolvedTailnet = enteredTailnet
            }
        )
    } else if (!hasCredentials) {
        AdminApiSetupScreen(
            tailnet = resolvedTailnet,
            initialAuthType = authType,
            initialToken = token,
            initialClientId = clientId,
            initialClientSecret = clientSecret,
            initialProxyMode = proxyMode,
            initialProxyHost = proxyHost,
            initialProxyPort = proxyPort,
            initialProxyUser = proxyUser,
            initialProxyPass = proxyPass,
            onBack = onBack,
            onSave = { type, tok, cid, csec, pmode, phost, pport, puser, ppass ->
                globalPrefs.edit().apply {
                    putString("${resolvedTailnet}_auth_type", type)
                    putString(resolvedTailnet, tok)
                    putString("${resolvedTailnet}_oauth_client_id", cid)
                    putString("${resolvedTailnet}_oauth_client_secret", csec)
                    putString("${resolvedTailnet}_proxy_mode", pmode)
                    putString("${resolvedTailnet}_proxy_host", phost)
                    putInt("${resolvedTailnet}_proxy_port", pport)
                    putString("${resolvedTailnet}_proxy_user", puser)
                    putString("${resolvedTailnet}_proxy_pass", ppass)
                }.apply()
                authType = type
                token = tok
                clientId = cid
                clientSecret = csec
                proxyMode = pmode
                proxyHost = phost
                proxyPort = pport
                proxyUser = puser
                proxyPass = ppass
            },
            onResetTailnet = {
                profilePrefs.edit().remove("last_known_tailnet").apply()
                resolvedTailnet = ""
            }
        )
    } else {
        AdminApiDashboardScreen(
            token = token,
            tailnet = resolvedTailnet,
            clientId = clientId,
            clientSecret = clientSecret,
            proxyMode = proxyMode,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            onUpdateProxy = { pmode, phost, pport, puser, ppass ->
                globalPrefs.edit().apply {
                    putString("${resolvedTailnet}_proxy_mode", pmode)
                    putString("${resolvedTailnet}_proxy_host", phost)
                    putInt("${resolvedTailnet}_proxy_port", pport)
                    putString("${resolvedTailnet}_proxy_user", puser)
                    putString("${resolvedTailnet}_proxy_pass", ppass)
                }.apply()
                proxyMode = pmode
                proxyHost = phost
                proxyPort = pport
                proxyUser = puser
                proxyPass = ppass
            },
            onBack = onBack,
            onDisconnect = {
                globalPrefs.edit().apply {
                    remove(resolvedTailnet)
                    remove("${resolvedTailnet}_auth_type")
                    remove("${resolvedTailnet}_oauth_client_id")
                    remove("${resolvedTailnet}_oauth_client_secret")
                    remove("${resolvedTailnet}_proxy_mode")
                    remove("${resolvedTailnet}_proxy_host")
                    remove("${resolvedTailnet}_proxy_port")
                    remove("${resolvedTailnet}_proxy_user")
                    remove("${resolvedTailnet}_proxy_pass")
                }.apply()
                token = ""
                clientId = ""
                clientSecret = ""
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiNoTailnetScreen(
    onBack: () -> Unit,
    onSaveTailnet: (String) -> Unit
) {
    val context = LocalContext.current
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
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(80.dp)
            )

            Text(
                text = "Tailnet Not Detected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "TailSocks must be connected to the VPN at least once to automatically detect your Tailnet domain.\n\nAlternatively, you can specify your Tailnet domain manually below:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = enteredTailnet,
                onValueChange = { enteredTailnet = it },
                label = { Text("Tailnet Domain Name") },
                placeholder = { Text("e.g. taila1b2.ts.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (enteredTailnet.isBlank()) {
                        Toast.makeText(context, "Tailnet domain is required", Toast.LENGTH_SHORT).show()
                    } else {
                        onSaveTailnet(enteredTailnet.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Set Tailnet Name")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiSetupScreen(
    tailnet: String,
    initialAuthType: String,
    initialToken: String,
    initialClientId: String,
    initialClientSecret: String,
    initialProxyMode: String,
    initialProxyHost: String,
    initialProxyPort: Int,
    initialProxyUser: String,
    initialProxyPass: String,
    onBack: () -> Unit,
    onSave: (String, String, String, String, String, String, Int, String, String) -> Unit,
    onResetTailnet: () -> Unit
) {
    val context = LocalContext.current
    var authType by remember { mutableStateOf(initialAuthType) }
    var enteredToken by remember { mutableStateOf(initialToken) }
    var enteredClientId by remember { mutableStateOf(initialClientId) }
    var enteredClientSecret by remember { mutableStateOf(initialClientSecret) }

    // Proxy State
    var proxyMode by remember { mutableStateOf(initialProxyMode) }
    var proxyHost by remember { mutableStateOf(initialProxyHost) }
    var proxyPort by remember { mutableStateOf(if (initialProxyPort > 0) initialProxyPort.toString() else "") }
    var proxyUser by remember { mutableStateOf(initialProxyUser) }
    var proxyPass by remember { mutableStateOf(initialProxyPass) }
    var isProxyExpanded by remember { mutableStateOf(false) }

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
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "Tailscale API Integration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active Tailnet Domain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(tailnet, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = onResetTailnet) {
                        Text("Edit")
                    }
                }
            }

            // Auth Type TabRow
            TabRow(
                selectedTabIndex = if (authType == "TOKEN") 0 else 1,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            ) {
                Tab(
                    selected = authType == "TOKEN",
                    onClick = { authType = "TOKEN" },
                    text = { Text("Personal Token", fontSize = 13.sp) }
                )
                Tab(
                    selected = authType == "OAUTH",
                    onClick = { authType = "OAUTH" },
                    text = { Text("OAuth Client", fontSize = 13.sp) }
                )
            }

            if (authType == "TOKEN") {
                OutlinedTextField(
                    value = enteredToken,
                    onValueChange = { enteredToken = it },
                    label = { Text("API Access Token (tskey-api-...)") },
                    placeholder = { Text("tskey-api-XXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                OutlinedTextField(
                    value = enteredClientId,
                    onValueChange = { enteredClientId = it },
                    label = { Text("OAuth Client ID") },
                    placeholder = { Text("e.g. cKXXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = enteredClientSecret,
                    onValueChange = { enteredClientSecret = it },
                    label = { Text("OAuth Client Secret (tskey-client-...)") },
                    placeholder = { Text("tskey-client-XXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Advanced Proxy Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { isProxyExpanded = !isProxyExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("API Proxy Configuration", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(
                            imageVector = if (isProxyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }

                    if (isProxyExpanded) {
                        Spacer(Modifier.height(12.dp))

                        // Proxy Mode selector
                        Text("Proxy Mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("DIRECT" to "Direct (No Proxy)", "LOCAL_SOCKS5" to "Tailsocks SOCKS5 Proxy", "CUSTOM_SOCKS5" to "Custom SOCKS5 Proxy").forEach { (modeVal, labelText) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { proxyMode = modeVal },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = proxyMode == modeVal, onClick = { proxyMode = modeVal })
                                    Text(labelText, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        if (proxyMode == "CUSTOM_SOCKS5") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text("SOCKS5 Host") },
                                placeholder = { Text("e.g. 192.168.1.100") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it },
                                label = { Text("SOCKS5 Port") },
                                placeholder = { Text("e.g. 1080") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyUser,
                                onValueChange = { proxyUser = it },
                                label = { Text("Proxy Username (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPass,
                                onValueChange = { proxyPass = it },
                                label = { Text("Proxy Password (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        } else if (proxyMode == "LOCAL_SOCKS5") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Routes API calls through the active Socks5 server. Useful if you want to route API calls through your Exit Node.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val portVal = proxyPort.toIntOrNull() ?: 0
                    if (authType == "TOKEN" && enteredToken.isBlank()) {
                        Toast.makeText(context, "API Access Token is required", Toast.LENGTH_SHORT).show()
                    } else if (authType == "OAUTH" && (enteredClientId.isBlank() || enteredClientSecret.isBlank())) {
                        Toast.makeText(context, "Client ID and Client Secret are required", Toast.LENGTH_SHORT).show()
                    } else if (proxyMode == "CUSTOM_SOCKS5" && (proxyHost.isBlank() || portVal <= 0)) {
                        Toast.makeText(context, "Valid SOCKS5 host and port are required", Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(
                            authType,
                            enteredToken.trim(),
                            enteredClientId.trim(),
                            enteredClientSecret.trim(),
                            proxyMode,
                            proxyHost.trim(),
                            portVal,
                            proxyUser.trim(),
                            proxyPass.trim()
                        )
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

// --- Proxy Settings Dialog ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsDialog(
    initialProxyMode: String,
    initialProxyHost: String,
    initialProxyPort: Int,
    initialProxyUser: String,
    initialProxyPass: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String, String) -> Unit
) {
    val context = LocalContext.current
    var proxyMode by remember { mutableStateOf(initialProxyMode) }
    var proxyHost by remember { mutableStateOf(initialProxyHost) }
    var proxyPort by remember { mutableStateOf(if (initialProxyPort > 0) initialProxyPort.toString() else "") }
    var proxyUser by remember { mutableStateOf(initialProxyUser) }
    var proxyPass by remember { mutableStateOf(initialProxyPass) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API Proxy Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf("DIRECT" to "Direct (No Proxy)", "LOCAL_SOCKS5" to "Tailsocks SOCKS5 Proxy", "CUSTOM_SOCKS5" to "Custom SOCKS5 Proxy").forEach { (modeVal, labelText) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { proxyMode = modeVal },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = proxyMode == modeVal, onClick = { proxyMode = modeVal })
                        Text(labelText, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (proxyMode == "CUSTOM_SOCKS5") {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text("SOCKS5 Host") },
                        placeholder = { Text("e.g. 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { proxyPort = it },
                        label = { Text("SOCKS5 Port") },
                        placeholder = { Text("e.g. 1080") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyUser,
                        onValueChange = { proxyUser = it },
                        label = { Text("Proxy Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyPass,
                        onValueChange = { proxyPass = it },
                        label = { Text("Proxy Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (proxyMode == "LOCAL_SOCKS5") {
                    Text(
                        "Uses the internal SOCKS5 proxy server. This routes your API calls through the active VPN / Exit Node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portVal = proxyPort.toIntOrNull() ?: 0
                    if (proxyMode == "CUSTOM_SOCKS5" && (proxyHost.isBlank() || portVal <= 0)) {
                        Toast.makeText(context, "Valid SOCKS5 host and port are required", Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(proxyMode, proxyHost.trim(), portVal, proxyUser.trim(), proxyPass.trim())
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
    splitDns: Map<String, List<String>>,
    searchPaths: List<String>,
    onMagicDnsChanged: (Boolean) -> Unit,
    onApplyNameservers: (List<String>) -> Unit,
    onUpdateSplitDns: (String, List<String>?) -> Unit,
    onApplySearchPaths: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val nsListState = remember(nameservers) { mutableStateListOf(*nameservers.toTypedArray()) }
    val searchPathsState = remember(searchPaths) { mutableStateListOf(*searchPaths.toTypedArray()) }

    var newNs by remember { mutableStateOf("") }
    var newSearchPath by remember { mutableStateOf("") }

    // Split DNS Form State
    var splitDomain by remember { mutableStateOf("") }
    var splitNameservers by remember { mutableStateOf("") }

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

        // Global Nameservers Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Global Nameservers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                if (nsListState.isEmpty()) {
                    Text("No custom nameservers configured", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
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

        // Split DNS Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Split DNS (Domain Routes)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                if (splitDns.isEmpty()) {
                    Text("No Split DNS routes configured", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        splitDns.forEach { (domain, ns) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(domain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(ns.joinToString(", "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onUpdateSplitDns(domain, null) }) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                Text("Add Split DNS Route", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = splitDomain,
                    onValueChange = { splitDomain = it },
                    label = { Text("Domain Name") },
                    placeholder = { Text("e.g. corp.internal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = splitNameservers,
                    onValueChange = { splitNameservers = it },
                    label = { Text("Nameservers (comma separated)") },
                    placeholder = { Text("e.g. 10.0.0.1, 10.0.0.2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Button(
                    onClick = {
                        val nsList = splitNameservers.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        if (splitDomain.isBlank() || nsList.isEmpty()) {
                            Toast.makeText(context, "Domain and nameservers are required", Toast.LENGTH_SHORT).show()
                        } else {
                            onUpdateSplitDns(splitDomain.trim(), nsList)
                            splitDomain = ""
                            splitNameservers = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Split DNS Route")
                }
            }
        }

        // DNS Search Domains Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DNS Search Domains", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                if (searchPathsState.isEmpty()) {
                    Text("No search domains configured", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        searchPathsState.forEach { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(path, fontSize = 14.sp)
                                IconButton(onClick = { searchPathsState.remove(path) }) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newSearchPath,
                        onValueChange = { newSearchPath = it },
                        placeholder = { Text("e.g. mycompany.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newSearchPath.isNotBlank() && !searchPathsState.contains(newSearchPath.trim())) {
                                searchPathsState.add(newSearchPath.trim())
                                newSearchPath = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Add")
                    }
                }

                val pathsChanged = searchPathsState.toList() != searchPaths
                Button(
                    onClick = { onApplySearchPaths(searchPathsState.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pathsChanged,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Done, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apply Search Domains")
                }
            }
        }
    }
}

// --- Users Tab ---
@Composable
fun UsersTabContent(
    users: List<ApiUser>
) {
    if (users.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No users found", color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(users) { user ->
                UserRow(user = user)
            }
        }
    }
}

@Composable
fun UserRow(user: ApiUser) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(user = user)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user.displayName?.takeIf { it.isNotBlank() } ?: user.loginName.substringBefore("@"),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    user.loginName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Role Badge
                    val roleLabel = user.role ?: "member"
                    val isPrivileged = roleLabel == "owner" || roleLabel.contains("admin")
                    Text(
                        text = roleLabel.uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPrivileged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isPrivileged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    // Status Badge
                    val statusLabel = user.status ?: "active"
                    val statusColor = when (statusLabel) {
                        "active" -> Color(0xFF4CAF50)
                        "suspended" -> MaterialTheme.colorScheme.error
                        else -> Color(0xFFFF9800)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(statusLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (user.deviceCount != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        user.deviceCount.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("devices", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun UserAvatar(user: ApiUser) {
    val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.loginName
    val firstChar = name.firstOrNull()?.uppercaseChar() ?: '?'
    
    val colors = listOf(
        Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF009688),
        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722)
    )
    val colorIndex = Math.abs(user.loginName.hashCode()) % colors.size
    val bgColor = colors[colorIndex]

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = firstChar.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

// --- Tailnet Settings Tab ---
@Composable
fun TailnetSettingsTabContent(
    settings: TailnetSettings?,
    onApplySettings: (TailnetSettings) -> Unit
) {
    if (settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var devicesApproval by remember(settings) { mutableStateOf(settings.devicesApprovalOn == true) }
    var usersApproval by remember(settings) { mutableStateOf(settings.usersApprovalOn == true) }
    var autoUpdates by remember(settings) { mutableStateOf(settings.devicesAutoUpdatesOn == true) }
    var keyDurationDays by remember(settings) { mutableIntStateOf(settings.devicesKeyDurationDays ?: 180) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Default Key Expiry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Set the default expiration time for device keys in this tailnet. Value must be between 1 and 180 days.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Duration (Days)", fontWeight = FontWeight.Medium)

                    var expandedDropdown by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expandedDropdown = true }) {
                            Text("$keyDurationDays Days")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            listOf(1, 7, 30, 90, 180).forEach { days ->
                                DropdownMenuItem(
                                    text = { Text("$days Days") },
                                    onClick = {
                                        keyDurationDays = days
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Access & Approval Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { devicesApproval = !devicesApproval },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device Approval Required", fontWeight = FontWeight.Medium)
                        Text("New devices must be approved by an administrator before they can join.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = devicesApproval, onCheckedChange = { devicesApproval = it })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { usersApproval = !usersApproval },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("User Approval Required", fontWeight = FontWeight.Medium)
                        Text("New members require manual approval from owners or admins to join.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = usersApproval, onCheckedChange = { usersApproval = it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Device Software Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { autoUpdates = !autoUpdates },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic Updates", fontWeight = FontWeight.Medium)
                        Text("Enable Tailscale to auto-update on devices belonging to this tailnet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = autoUpdates, onCheckedChange = { autoUpdates = it })
                }
            }
        }

        val hasChanges = devicesApproval != (settings.devicesApprovalOn == true) ||
                usersApproval != (settings.usersApprovalOn == true) ||
                autoUpdates != (settings.devicesAutoUpdatesOn == true) ||
                keyDurationDays != (settings.devicesKeyDurationDays ?: 180)

        Button(
            onClick = {
                val updated = TailnetSettings(
                    aclsExternallyManagedOn = settings.aclsExternallyManagedOn,
                    aclsExternalLink = settings.aclsExternalLink,
                    devicesApprovalOn = devicesApproval,
                    devicesAutoUpdatesOn = autoUpdates,
                    devicesKeyDurationDays = keyDurationDays,
                    usersApprovalOn = usersApproval,
                    usersRoleAllowedToJoinExternalTailnets = settings.usersRoleAllowedToJoinExternalTailnets,
                    networkFlowLoggingOn = settings.networkFlowLoggingOn,
                    regionalRoutingOn = settings.regionalRoutingOn
                )
                onApplySettings(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasChanges,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Done, null)
            Spacer(Modifier.width(8.dp))
            Text("Apply Settings")
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
