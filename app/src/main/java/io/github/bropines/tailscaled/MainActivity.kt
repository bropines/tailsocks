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

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        handleAppStartup()
        checkForUpdatesSilent()

        setContent {
            TailSocksTheme {
                MainScreen()
            }
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
                    if (tag.removePrefix("v") != currentVersion.removePrefix("v")) {
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
fun MainScreen() {
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

    val prefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    var proxyState by remember { mutableStateOf(if (ProxyState.isActualRunning()) "ACTIVE" else "STOPPED") }
    var exitNodeIp by remember { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }
    var isProcessing by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var show410Warning by remember { mutableStateOf(false) }

    // Watchdog: Sync UI state with actual daemon status
    LaunchedEffect(Unit) {
        var urlDetected = false
        while (true) {
            val actualRunning = try { appctr.Appctr.isRunning() } catch (e: Exception) { false }
            
            // Sync state if not explicitly in transition
            if (!isProcessing) {
                proxyState = if (actualRunning) "ACTIVE" else "STOPPED"
            }

            if (actualRunning) {
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

                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false }
                        ) {
                            accounts.value.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = {
                                        accountMenuExpanded = false
                                        if (account.id != activeAccount.id) {
                                            if (ProxyState.isActualRunning()) {
                                                showSwitchConfirmDialog = account
                                            } else {
                                                AccountManager.setActiveAccount(context, account.id)
                                                activeAccount = account
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        if (account.id == activeAccount.id) Icon(Icons.Default.Check, null)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Rename Current") },
                                onClick = {
                                    accountMenuExpanded = false
                                    showRenameAccountDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Add Account") },
                                onClick = {
                                    accountMenuExpanded = false
                                    showAddAccountDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Add, null) }
                            )
                            if (activeAccount.id != "default") {
                                DropdownMenuItem(
                                    text = { Text("Delete Current", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        accountMenuExpanded = false
                                        AccountManager.deleteAccount(context, activeAccount.id)
                                        activeAccount = AccountManager.getActiveAccount(context)
                                        accounts.value = AccountManager.getAccounts(context)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
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

            if (proxyState == "ACTIVE" && exitNodeIp.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Traffic is routed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("Via exit node: $exitNodeIp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
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
                MenuCard(title = "Logs", icon = Icons.Default.Info, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
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
                Spacer(modifier = Modifier.weight(1f).padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("About & Licenses")
            }
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
                        val isNewer = latestVersion!!.removePrefix("v") != versionName.removePrefix("v")
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
            .height(160.dp)
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
            Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
