package io.github.bropines.tailscaled

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Runtime

import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

fun isVersionNewer(current: String, latest: String): Boolean {
    val c = current.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val l = latest.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(c.size, l.size)) {
        val cVal = c.getOrNull(i) ?: 0
        val lVal = l.getOrNull(i) ?: 0
        if (lVal > cVal) return true
        if (lVal < cVal) return false
    }
    return false
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val showAccountSwitcher = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        handleAppStartup()
        checkForUpdatesSilent()
        handleIntent(intent)

        setContent {
            TailSocksTheme {
                MainScreen(showAccountSwitcher)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            showAccountSwitcher.value = true
        }
    }

    private fun handleAppStartup() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val appctrPrefs = getSharedPreferences("appctr", Context.MODE_PRIVATE)
        
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val currentUpdateTime = packageInfo.lastUpdateTime
            val savedUpdateTime = prefs.getLong("last_update_time", 0)

            if (savedUpdateTime != currentUpdateTime) {
                Runtime.getRuntime().exec("killall tailscaled")
                prefs.edit().putLong("last_update_time", currentUpdateTime).apply()
            }
        } catch (e: Exception) {}

        val forceBg = appctrPrefs.getBoolean("force_bg", false)

        if (ProxyState.isUserLetRunning(this) && !ProxyState.isActualRunning()) {
            if (forceBg) {
                val authKey = appctrPrefs.getString("authkey", "") ?: ""
                if (authKey.isNotBlank()) {
                    val intent = Intent(this, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(this, intent)
                } else {
                    ProxyState.setUserState(this, false)
                }
            } else {
                ProxyState.setUserState(this, false)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkForUpdatesSilent() {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                val connection = java.net.URL("https://api.github.com/repos/bropines/tailsocks/releases/latest").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = com.google.gson.Gson().fromJson(response, com.google.gson.JsonObject::class.java)
                    val tag = json.get("tag_name").asString
                    if (isVersionNewer(currentVersion, tag)) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "🚀 New TailSocks update available: $tag", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(showAccountSwitcher: MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var activeAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { mutableStateOf(AccountManager.getAccounts(context)) }
    
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showRenameAccountDialog by remember { mutableStateOf(false) }
    var showSwitchConfirmDialog by remember { mutableStateOf<TailscaleAccount?>(null) }
    
    var newAccountName by remember { mutableStateOf("") }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var isBatteryOptimizationsIgnored by remember { mutableStateOf(true) }

    LaunchedEffect(showAccountSwitcher.value) {
        if (showAccountSwitcher.value) {
            accountMenuExpanded = true
            showAccountSwitcher.value = false
        }
    }

    val prefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    var proxyState by remember { mutableStateOf(if (ProxyState.isActualRunning()) "ACTIVE" else "STOPPED") }
    var exitNodeIp by remember { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }
    var isProcessing by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var show410Warning by remember { mutableStateOf(false) }

    var showExitNodeSheet by remember { mutableStateOf(false) }
    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isExitNodesLoading by remember { mutableStateOf(false) }

    fun applyExitNode(id: String, ip: String) {
        exitNodeIp = ip
        val editor = prefs.edit()
        editor.putString("exit_node_ip", ip)
        editor.putString("exit_node_id", id)
        editor.apply()
        
        scope.launch(Dispatchers.IO) {
            val prefsJson = "{\"ExitNodeID\": \"$id\", \"ExitNodeIDSet\": true}"
            appctr.Appctr.setPrefs(prefsJson)
        }
    }

    // Watchdog: Sync UI state with actual daemon status
    LaunchedEffect(Unit) {
        var urlDetected = false
        while (true) {
            val isProcessAlive = try { appctr.Appctr.isRunning() } catch (e: Exception) { false }
            
            if (isProcessAlive && BuildConfig.IS_DEV) {
                val backendState = try { appctr.Appctr.getBackendState() } catch (e: Exception) { "Error" }
                // If backend is in a terminal state but process is alive, we might need to reflect it
                if (backendState == "Stopped" || backendState == "Error") {
                    // Process is alive but API is not responding or backend is stopped
                }
            }

            // Sync state if not explicitly in transition
            if (!isProcessing) {
                proxyState = if (isProcessAlive) "ACTIVE" else "STOPPED"
            }

            if (isProcessAlive) {
                val url = try { appctr.Appctr.getLoginURLString() } catch (e: Exception) { "" }
                loginUrl = if (url.isNullOrBlank()) null else url
                
                if (loginUrl != null) urlDetected = true
                else if (urlDetected) {
                    show410Warning = false
                    urlDetected = false
                }

                val lastErr = try { appctr.Appctr.getLastError() } catch (e: Exception) { "" }
                if (lastErr == "410_GONE") show410Warning = true
            } else {
                loginUrl = null
                show410Warning = false
                urlDetected = false
            }

            // Check battery optimization status
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            isBatteryOptimizationsIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }

            delay(2000)
        }
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "exit_node_ip") {
                exitNodeIp = sharedPreferences.getString("exit_node_ip", "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "STARTING" -> { proxyState = "STARTING"; isProcessing = true }
                    "START" -> {
                        proxyState = "ACTIVE"
                        isProcessing = false
                        exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
                        show410Warning = false
                    }
                    "STOP" -> { proxyState = "STOPPED"; isProcessing = false }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("STARTING")
            addAction("START")
            addAction("STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    if (showAddAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAddAccountDialog = false },
            title = { Text("Add Account") },
            text = {
                OutlinedTextField(
                    value = newAccountName,
                    onValueChange = { newAccountName = it },
                    label = { Text("Account Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newAccountName.isNotBlank()) {
                        val acc = AccountManager.addAccount(context, newAccountName)
                        accounts.value = AccountManager.getAccounts(context)
                        AccountManager.setActiveAccount(context, acc.id)
                        activeAccount = acc
                        newAccountName = ""
                        showAddAccountDialog = false

                        if (ProxyState.isActualRunning()) {
                            context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddAccountDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRenameAccountDialog) {
        var renameText by remember { mutableStateOf(activeAccount.name) }
        AlertDialog(
            onDismissRequest = { showRenameAccountDialog = false },
            title = { Text("Rename Account") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameText.isNotBlank()) {
                        AccountManager.renameAccount(context, activeAccount.id, renameText)
                        accounts.value = AccountManager.getAccounts(context)
                        activeAccount = AccountManager.getActiveAccount(context)
                        showRenameAccountDialog = false
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameAccountDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSwitchConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirmDialog = null },
            title = { Text("Switch Account?") },
            text = { Text("Switching to '${showSwitchConfirmDialog!!.name}' will restart the core. Are you sure?") },
            confirmButton = {
                Button(onClick = {
                    val target = showSwitchConfirmDialog!!
                    showSwitchConfirmDialog = null
                    isProcessing = true

                    // Move core logic to Service via RESTART_ACTION
                    AccountManager.setActiveAccount(context, target.id)
                    activeAccount = target
                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                }) { Text("Restart & Switch") }
            },
            dismissButton = { TextButton(onClick = { showSwitchConfirmDialog = null }) { Text("Cancel") } }
        )
    }

    if (accountMenuExpanded) {
        ModalBottomSheet(onDismissRequest = { accountMenuExpanded = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Switch Account", modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.lazy.LazyColumn {
                    items(accounts.value.size) { i ->
                        val account = accounts.value[i]
                        ListItem(
                            headlineContent = { Text(account.name) },
                            leadingContent = { Icon(Icons.Default.AccountCircle, null) },
                            trailingContent = {
                                if (account.id == activeAccount.id) Icon(Icons.Default.Check, null)
                            },
                            modifier = Modifier.clickable {
                                accountMenuExpanded = false
                                if (account.id != activeAccount.id) {
                                    if (ProxyState.isActualRunning()) showSwitchConfirmDialog = account
                                    else { AccountManager.setActiveAccount(context, account.id); activeAccount = account }
                                }
                            }
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        ListItem(
                            headlineContent = { Text("Rename Current") },
                            leadingContent = { Icon(Icons.Default.Edit, null) },
                            modifier = Modifier.clickable { accountMenuExpanded = false; showRenameAccountDialog = true }
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Add Account") },
                            leadingContent = { Icon(Icons.Default.Add, null) },
                            modifier = Modifier.clickable { accountMenuExpanded = false; showAddAccountDialog = true }
                        )
                    }
                    if (activeAccount.id != "default") {
                        item {
                            ListItem(
                                headlineContent = { Text("Delete Current", color = MaterialTheme.colorScheme.error) },
                                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                modifier = Modifier.clickable {
                                    accountMenuExpanded = false
                                    AccountManager.deleteAccount(context, activeAccount.id)
                                    activeAccount = AccountManager.getActiveAccount(context)
                                    accounts.value = AccountManager.getAccounts(context)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { accountMenuExpanded = true }) {
                        Text("TailSocks", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                activeAccount.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (proxyState == "ACTIVE") {
                        IconButton(onClick = { 
                            val intent = Intent(context, TailscaledService::class.java).apply { action = "REFRESH_ACTION" }
                            context.startService(intent)
                            Toast.makeText(context, "Refreshing configuration...", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Config")
                        }
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About & Licenses")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            if (!isBatteryOptimizationsIgnored) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open battery settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Battery Optimization Enabled", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("TailSocks may be killed in the background. Tap to disable.", 
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (show410Warning) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Network Sync Warning", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Initial machine map parsing may take 1-3 minutes due to network backoff. Please do not restart.", 
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (proxyState == "ACTIVE") {
                Surface(
                    color = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable {
                        showExitNodeSheet = true
                        isExitNodesLoading = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val pJson = appctr.Appctr.getStatusFromAPI()
                                if (!pJson.startsWith("Error")) {
                                    val status = com.google.gson.Gson().fromJson(pJson, StatusResponse::class.java)
                                    val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.toList() ?: emptyList()
                                    withContext(Dispatchers.Main) { exitNodes = nodes }
                                }
                            } catch (e: Exception) {}
                            withContext(Dispatchers.Main) { isExitNodesLoading = false }
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (exitNodeIp.isNotEmpty()) Icons.Default.Lock else Icons.Default.Public, 
                            contentDescription = null, 
                            tint = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                if (exitNodeIp.isNotEmpty()) "Traffic is routed" else "Exit Node: None", 
                                fontWeight = FontWeight.Bold, 
                                color = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (exitNodeIp.isNotEmpty()) "Via exit node: $exitNodeIp" else "Tap to select exit node", 
                                style = MaterialTheme.typography.bodySmall, 
                                color = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.KeyboardArrowRight, 
                            null, 
                            tint = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            StatusCard(state = proxyState, isProcessing = isProcessing) {
                if (isProcessing) return@StatusCard

                if (proxyState == "ACTIVE" || proxyState == "STARTING") {
                    isProcessing = true
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                    context.startService(intent)
                } else {
                    val currentSocks = prefs.getString("socks5", "127.0.0.1:1055") ?: "127.0.0.1:1055"

                    if (currentSocks.isBlank()) {
                        Toast.makeText(context, "🚫 Error: SOCKS5 address cannot be empty!", Toast.LENGTH_LONG).show()
                        return@StatusCard
                    }

                    isProcessing = true
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            if (loginUrl != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Login Required", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Tap to authenticate via browser", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = "Console", icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, ConsoleActivity::class.java))
                }
                MenuCard(title = "Peers", icon = Icons.Default.Share, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, PeersActivity::class.java))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = "Logs", icon = Icons.AutoMirrored.Filled.List, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, LogsActivity::class.java))
                }
                MenuCard(title = "Files", icon = Icons.Default.Folder, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, FilesActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = "DNS", icon = Icons.Default.Language, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, DnsActivity::class.java))
                }
                MenuCard(title = "Netcheck", icon = Icons.Default.Refresh, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, NetcheckActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = "Settings", icon = Icons.Default.Settings, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
                MenuCard(title = "Serve", icon = Icons.Default.Public, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, ServeActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAboutDialog) {
        val versionName = remember {
            try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
            catch (e: Exception) { "?" }
        }
        val coreVer = remember {
            try { appctr.Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
        }
        var latestVersion by remember { mutableStateOf<String?>(null) }
        var isCheckingUpdate by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("About TailSocks")
                }
            },
            text = { 
                Column(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("App Version: $versionName", fontWeight = FontWeight.Bold)
                            Text("Tailscale Core: $coreVer", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    if (latestVersion != null) {
                        val isNewer = isVersionNewer(versionName, latestVersion!!)
                        if (isNewer) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("New version: $latestVersion", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Proxy is running via official Tailscale core.\nLicense: BSD-3-Clause\n")
                    
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bropines"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("App Developer: Bropines") }
                    
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Asutorufa/tailscale"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Patch Developer: Asutorufa") }

                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tailscale/tailscale"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Core Developer: Tailscale") }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val connection = java.net.URL("https://api.github.com/repos/bropines/tailsocks/releases/latest").openConnection() as java.net.HttpURLConnection
                                    connection.requestMethod = "GET"
                                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                                    if (connection.responseCode == 200) {
                                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                                        val json = com.google.gson.Gson().fromJson(response, com.google.gson.JsonObject::class.java)
                                        val tag = json.get("tag_name").asString
                                        withContext(Dispatchers.Main) {
                                            latestVersion = tag
                                            isCheckingUpdate = false
                                        }
                                    } else { throw Exception("HTTP ${connection.responseCode}") }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        isCheckingUpdate = false
                                    }
                                }
                            }
                        },
                        enabled = !isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                        } else {
                            Text("Check for App Updates")
                        }
                    }
                } 
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = if (latestVersion != null) "https://github.com/bropines/tailsocks/releases/latest" 
                             else "https://github.com/bropines/tailscaled-socks5-android"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    showAboutDialog = false
                }) { Text(if (latestVersion != null) "Download" else "GitHub") }
            },
            dismissButton = { TextButton(onClick = { showAboutDialog = false }) { Text("Close") } }
        )
    }

    if (showExitNodeSheet) {
        ModalBottomSheet(onDismissRequest = { showExitNodeSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Select Exit Node", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (isExitNodesLoading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (exitNodes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { 
                        Text("No exit nodes available", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item {
                            ListItem(
                                headlineContent = { Text("None") },
                                leadingContent = { Icon(Icons.Default.Clear, null) },
                                modifier = Modifier.clickable { applyExitNode("", ""); showExitNodeSheet = false }
                            )
                        }
                        items(exitNodes.size) { i ->
                            val node = exitNodes[i]
                            ListItem(
                                headlineContent = { Text(node.getDisplayName()) },
                                supportingContent = { Text(node.getPrimaryIp()) },
                                leadingContent = { Icon(Icons.Default.VpnKey, null) },
                                modifier = Modifier.clickable { applyExitNode(node.id ?: "", node.getPrimaryIp()); showExitNodeSheet = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: String, isProcessing: Boolean, onToggle: () -> Unit) {
    val backgroundColor = when (state) {
        "ACTIVE" -> MaterialTheme.colorScheme.primaryContainer
        "STARTING" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (state) {
        "ACTIVE" -> MaterialTheme.colorScheme.onPrimaryContainer
        "STARTING" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .alpha(if (isProcessing) 0.6f else 1f)
            .clickable(enabled = !isProcessing) { onToggle() },
        tonalElevation = 4.dp
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = when(state) {
                    "ACTIVE" -> Icons.Default.CheckCircle
                    "STARTING" -> Icons.Default.Refresh
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(48.dp).padding(bottom = 16.dp)
            )
            Text(
                text = when(state) {
                    "ACTIVE" -> "Active"
                    "STARTING" -> "Starting..."
                    else -> "Stopped"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = when {
                    isProcessing -> "Please wait..."
                    state == "ACTIVE" -> "Service is running • Tap to stop"
                    state == "STARTING" -> "Waking up the daemon..."
                    else -> "Tap to connect"
                },
                modifier = Modifier.alpha(0.6f).padding(top = 4.dp),
                color = contentColor
            )
        }
    }
}

@Composable
fun MenuCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}
