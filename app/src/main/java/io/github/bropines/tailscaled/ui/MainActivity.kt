package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val isDev = current.contains("-dev", ignoreCase = true) || current.contains("dev", ignoreCase = true) || BuildConfig.DEBUG
    val cleanCurrent = current.removePrefix("v").substringBefore("-").replace(Regex("[^0-9.]"), "")
    val cleanLatest = latest.removePrefix("v").substringBefore("-").replace(Regex("[^0-9.]"), "")
    val c = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }
    val l = cleanLatest.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(c.size, l.size)) {
        val cVal = c.getOrNull(i) ?: 0
        val lVal = l.getOrNull(i) ?: 0
        if (lVal > cVal) return true
        if (lVal < cVal) return false
    }
    // If base numeric versions are equal (e.g. 3.1.4-dev vs 3.1.4 release),
    // a DEV/Debug build is inherently newer than the published release.
    if (isDev) return false
    return false
}

fun launchApkInstaller(context: Context, apkFile: java.io.File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, context.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
        return
    }

    val apkUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val resolveInfos = context.packageManager.queryIntentActivities(installIntent, 0)
    val systemInstaller = resolveInfos.firstOrNull {
        val pkg = it.activityInfo.packageName
        pkg == "com.google.android.packageinstaller" ||
        pkg == "com.android.packageinstaller" ||
        pkg == "com.samsung.android.packageinstaller" ||
        pkg.contains("packageinstaller")
    }
    if (systemInstaller != null) {
        installIntent.setClassName(systemInstaller.activityInfo.packageName, systemInstaller.activityInfo.name)
    }

    try {
        context.startActivity(installIntent)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Failed to launch package installer", e)
        Toast.makeText(context, "Installer failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun downloadAndCacheAvatar(context: Context, accountId: String, urlStr: String) {
    try {
        val url = java.net.URL(urlStr)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.doInput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.connect()
        val input = connection.inputStream
        val avatarsDir = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
        val targetFile = java.io.File(avatarsDir, "$accountId.png")
        val output = java.io.FileOutputStream(targetFile)
        input.use { inStream ->
            output.use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("AvatarSync", "Failed to download avatar: ${e.message}")
    }
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val showAccountSwitcher = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!appPrefs.getBoolean("first_start_done", false)) {
            startActivity(Intent(this, FirstStartActivity::class.java))
            finish()
            return
        }

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

    override fun onResume() {
        super.onResume()
        val lang = GlobalSettings.getString(this, "app_locale", "sys")
        val currentLocale = resources.configuration.locales.get(0)
        val targetLocale = if (lang == "sys") {
            android.content.res.Resources.getSystem().configuration.locales.get(0)
        } else {
            java.util.Locale.forLanguageTag(lang)
        }
        if (currentLocale.language != targetLocale.language) {
            recreate()
        }
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
                            Toast.makeText(this@MainActivity, getString(R.string.main_update_available_format, tag), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(showAccountSwitcher: MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var activeAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { mutableStateOf(AccountManager.getAccounts(context)) }
    
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showRenameAccountDialog by remember { mutableStateOf(false) }
    var showSwitchConfirmDialog by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountOptionsModal by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountToDeleteConfirm by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountToRename by remember { mutableStateOf<TailscaleAccount?>(null) }
    var editingAccountId by remember { mutableStateOf<String?>(null) }
    
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
    val globalPrefs = remember { context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE) }
    var isTunEnabled by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    var isFullTunnel by remember { mutableStateOf(GlobalSettings.isTunFullTunnel(context)) }

    val globalPrefsListener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "tun_mode_enabled") {
                isTunEnabled = sharedPreferences.getBoolean("tun_mode_enabled", false)
            } else if (key == "tun_full_tunnel") {
                isFullTunnel = sharedPreferences.getBoolean("tun_full_tunnel", false)
            }
        }
    }

    var proxyState by remember { mutableStateOf(if (ProxyState.isActualRunning()) "ACTIVE" else "STOPPED") }
    var exitNodeIp by remember(activeAccount.id) { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }

    val profilePrefsListener = remember(activeAccount.id) {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "exit_node_ip") {
                exitNodeIp = sharedPreferences.getString("exit_node_ip", "") ?: ""
            }
        }
    }
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
            updateAllWidgets(context)
            if (GlobalSettings.isTunModeEnabled(context)) {
                context.startService(Intent(context, TunVpnService::class.java).apply {
                    action = TunVpnService.ACTION_START
                })
            }
        }
    }

    // Watchdog: Sync UI state with actual daemon status
    LaunchedEffect(Unit) {
        var urlDetected = false
        var lastAvatarSync = 0L
        var loggedOutSeconds = 0
        while (true) {
            val isProcessAlive = try { appctr.Appctr.isRunning() } catch (e: Exception) { false }
            val backendState = if (isProcessAlive) {
                try { appctr.Appctr.getBackendState() } catch (e: Exception) { "Error" }
            } else "Stopped"

            if (isProcessAlive && backendState == "Running") {
                val wasLoggedIn = prefs.getBoolean("was_logged_in", false)
                if (!wasLoggedIn) {
                    prefs.edit().putBoolean("was_logged_in", true).apply()
                }
            }

            // Sync state if not explicitly in transition
            if (!isProcessing) {
                proxyState = if (isProcessAlive) {
                    if (backendState == "NeedsLogin" || backendState == "NoState") {
                        loggedOutSeconds += 2
                        if (loggedOutSeconds >= 10) {
                            if (prefs.getBoolean("was_logged_in", false) && loginUrl.isNullOrBlank()) {
                                "CONNECTION_ISSUE"
                            } else {
                                "LOGGED_OUT"
                            }
                        } else {
                            if (proxyState == "STOPPED") "ACTIVE" else proxyState
                        }
                    } else {
                        loggedOutSeconds = 0
                        "ACTIVE"
                    }
                } else {
                    loggedOutSeconds = 0
                    "STOPPED"
                }
            }

            if (isProcessAlive) {
                val url = try { appctr.Appctr.getLoginURL() } catch (e: Exception) { "" }
                loginUrl = if (url.isNullOrBlank()) null else url
                
                if (loginUrl != null) urlDetected = true
                else if (urlDetected) {
                    show410Warning = false
                    urlDetected = false
                }

                val lastErr = try { appctr.Appctr.getLastError() } catch (e: Exception) { "" }
                if (lastErr == "410_GONE") show410Warning = true

                // Background avatar sync
                val now = System.currentTimeMillis()
                if (now - lastAvatarSync > 30000) { // Every 30 seconds
                    lastAvatarSync = now
                    scope.launch(Dispatchers.IO) {
                        try {
                            val pJson = appctr.Appctr.getStatusFromAPI()
                            if (!pJson.startsWith("Error")) {
                                val status = com.google.gson.Gson().fromJson(pJson, StatusResponse::class.java)
                                val selfUserId = status.self?.userID
                                val selfUser = status.users?.get(selfUserId?.toString()) ?: status.users?.values?.firstOrNull()
                                val picUrl = selfUser?.profilePicUrl
                                if (!picUrl.isNullOrEmpty() && picUrl != activeAccount.avatarUrl) {
                                    downloadAndCacheAvatar(context, activeAccount.id, picUrl)
                                    AccountManager.updateAccountAvatar(context, activeAccount.id, picUrl)
                                    withContext(Dispatchers.Main) {
                                        accounts.value = AccountManager.getAccounts(context)
                                        activeAccount = AccountManager.getActiveAccount(context)
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
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
        prefs.registerOnSharedPreferenceChangeListener(profilePrefsListener)
        exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(profilePrefsListener)
        }
    }

    DisposableEffect(globalPrefs) {
        globalPrefs.registerOnSharedPreferenceChangeListener(globalPrefsListener)
        isTunEnabled = globalPrefs.getBoolean("tun_mode_enabled", false)
        isFullTunnel = globalPrefs.getBoolean("tun_full_tunnel", false)
        onDispose {
            globalPrefs.unregisterOnSharedPreferenceChangeListener(globalPrefsListener)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isTunEnabled = GlobalSettings.isTunModeEnabled(context)
                isFullTunnel = GlobalSettings.isTunFullTunnel(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
        var newAccountServer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddAccountDialog = false },
            title = { Text(stringResource(R.string.main_add_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAccountName,
                        onValueChange = { newAccountName = it },
                        label = { Text(stringResource(R.string.main_account_name_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newAccountServer,
                        onValueChange = { newAccountServer = it },
                        label = { Text(stringResource(R.string.settings_login_server_title)) },
                        placeholder = { Text("https://controlplane.tailscale.com") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newAccountName.isNotBlank()) {
                        val acc = AccountManager.addAccount(context, newAccountName)
                        val accPrefs = context.getSharedPreferences("appctr_${acc.id}", Context.MODE_PRIVATE)
                        accPrefs.edit().putBoolean("do_reset", true).apply()
                        if (newAccountServer.isNotBlank()) {
                            accPrefs.edit().putString("login_server", newAccountServer.trim()).apply()
                        }
                        accounts.value = AccountManager.getAccounts(context)
                        AccountManager.setActiveAccount(context, acc.id)
                        activeAccount = acc
                        newAccountName = ""
                        showAddAccountDialog = false

                        if (ProxyState.isActualRunning()) {
                            context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                        }
                    }
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { showAddAccountDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showRenameAccountDialog) {
        val targetAcc = accountToRename ?: activeAccount
        var renameText by remember(targetAcc.id) { mutableStateOf(targetAcc.name) }
        AlertDialog(
            onDismissRequest = { 
                showRenameAccountDialog = false
                accountToRename = null
            },
            title = { Text(stringResource(R.string.main_rename_account_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.main_new_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameText.isNotBlank()) {
                        AccountManager.renameAccount(context, targetAcc.id, renameText)
                        accounts.value = AccountManager.getAccounts(context)
                        activeAccount = AccountManager.getActiveAccount(context)
                        showRenameAccountDialog = false
                        accountToRename = null
                    }
                }) { Text(stringResource(R.string.action_rename)) }
            },
            dismissButton = { 
                TextButton(onClick = { 
                    showRenameAccountDialog = false
                    accountToRename = null
                }) { Text(stringResource(R.string.action_cancel)) } 
            }
        )
    }

    if (accountOptionsModal != null) {
        val targetAcc = accountOptionsModal!!
        AlertDialog(
            onDismissRequest = { accountOptionsModal = null },
            icon = { Icon(Icons.Default.ManageAccounts, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(targetAcc.name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.main_account_options_subtitle, targetAcc.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    
                    if (targetAcc.id != activeAccount.id) {
                        FilledTonalButton(
                            onClick = {
                                val accToSwitch = targetAcc
                                accountOptionsModal = null
                                accountMenuExpanded = false
                                if (ProxyState.isActualRunning()) showSwitchConfirmDialog = accToSwitch
                                else { 
                                    AccountManager.setActiveAccount(context, accToSwitch.id)
                                    activeAccount = accToSwitch 
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.main_switch_to_account), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    
                    OutlinedButton(
                        onClick = {
                            accountToRename = targetAcc
                            accountOptionsModal = null
                            showRenameAccountDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_rename), fontWeight = FontWeight.SemiBold)
                    }
                    
                    if (targetAcc.id != "default") {
                        Button(
                            onClick = {
                                accountToDeleteConfirm = targetAcc
                                accountOptionsModal = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_delete), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { accountOptionsModal = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (accountToDeleteConfirm != null) {
        val targetAcc = accountToDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { accountToDeleteConfirm = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.main_delete_account_confirm_title, targetAcc.name)) },
            text = { Text(stringResource(R.string.main_delete_account_confirm_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        accountToDeleteConfirm = null
                        accountMenuExpanded = false
                        AccountManager.deleteAccount(context, targetAcc.id)
                        activeAccount = AccountManager.getActiveAccount(context)
                        accounts.value = AccountManager.getAccounts(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDeleteConfirm = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showSwitchConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirmDialog = null },
            title = { Text(stringResource(R.string.main_switch_account_title)) },
            text = { Text(stringResource(R.string.main_switch_account_text, showSwitchConfirmDialog!!.name)) },
            confirmButton = {
                Button(onClick = {
                    val target = showSwitchConfirmDialog!!
                    showSwitchConfirmDialog = null
                    isProcessing = true

                    // Move core logic to Service via RESTART_ACTION
                    AccountManager.setActiveAccount(context, target.id)
                    activeAccount = target
                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                }) { Text(stringResource(R.string.main_restart_switch)) }
            },
            dismissButton = { TextButton(onClick = { showSwitchConfirmDialog = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (accountMenuExpanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { accountMenuExpanded = false },
            sheetState = sheetState
        ) {
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val activeAvatarFile = remember(activeAccount.id) { java.io.File(context.filesDir, "avatars/${activeAccount.id}.png") }
            val activeBitmap = remember(activeAvatarFile) {
                if (activeAvatarFile.exists()) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(activeAvatarFile.absolutePath)
                    } catch (e: Exception) { null }
                } else null
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (activeBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = activeBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 12.dp)
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 12.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ManageAccounts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Text(
                    stringResource(R.string.main_switch_account_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts.value.size) { i ->
                        val account = accounts.value[i]
                        val isActive = account.id == activeAccount.id
                        val isEditing = editingAccountId == account.id
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .combinedClickable(
                                        onClick = {
                                            if (editingAccountId != null) {
                                                editingAccountId = null
                                            } else {
                                                accountMenuExpanded = false
                                                if (account.id != activeAccount.id) {
                                                    if (ProxyState.isActualRunning()) showSwitchConfirmDialog = account
                                                    else { AccountManager.setActiveAccount(context, account.id); activeAccount = account }
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            editingAccountId = if (isEditing) null else account.id
                                        }
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                border = if (isActive) androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary
                                ) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val avatarFile = remember(account.id) { java.io.File(context.filesDir, "avatars/${account.id}.png") }
                                    val bitmap = remember(avatarFile) {
                                        if (avatarFile.exists()) {
                                            try {
                                                android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath)
                                            } catch (e: Exception) { null }
                                        } else null
                                    }

                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        val nameLower = account.name.lowercase()
                                        val (smartIcon, smartColor) = when {
                                            nameLower.contains("github") -> Icons.Default.Hub to Color(0xFFFCC624)
                                            nameLower.contains("headscale") -> Icons.Default.Cloud to Color(0xFF0078D4)
                                            nameLower.contains("google") || nameLower.contains("gmail") -> Icons.Default.Email to Color(0xFFE91E63)
                                            else -> Icons.Default.AccountCircle to (if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(smartColor.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                smartIcon,
                                                null,
                                                tint = smartColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        account.name,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActive) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = isEditing,
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally()
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            editingAccountId = null
                                            accountToRename = account
                                            showRenameAccountDialog = true
                                        },
                                        modifier = Modifier.size(40.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.action_rename),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (account.id != "default") {
                                        FilledTonalIconButton(
                                            onClick = {
                                                editingAccountId = null
                                                accountToDeleteConfirm = account
                                            },
                                            modifier = Modifier.size(40.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.action_delete),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = {
                        editingAccountId = null
                        accountMenuExpanded = false
                        showAddAccountDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.action_add),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { accountMenuExpanded = true }) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
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
                            Toast.makeText(context, context.getString(R.string.main_refreshing_config), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.main_cd_refresh_config))
                        }
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(context, AdminApiActivity::class.java))
                    }) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = stringResource(R.string.main_cd_admin_api))
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.main_cd_about_licenses))
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
                            Toast.makeText(context, context.getString(R.string.main_cannot_open_battery_settings), Toast.LENGTH_SHORT).show()
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
                            Text(stringResource(R.string.main_battery_warn_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(stringResource(R.string.main_battery_warn_desc), 
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
                            Text(stringResource(R.string.main_network_sync_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(stringResource(R.string.main_network_sync_desc), 
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
                                if (exitNodeIp.isNotEmpty()) stringResource(R.string.main_traffic_routed) else stringResource(R.string.main_exit_node_none_label), 
                                fontWeight = FontWeight.Bold, 
                                color = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (exitNodeIp.isNotEmpty()) stringResource(R.string.main_exit_node_routed_desc, exitNodeIp) else stringResource(R.string.main_exit_node_none_desc), 
                                style = MaterialTheme.typography.bodySmall, 
                                color = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, 
                            null, 
                            tint = if (exitNodeIp.isNotEmpty()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            StatusCard(
                state = if (proxyState == "CONNECTION_ISSUE" || (proxyState == "LOGGED_OUT" && ProxyState.isActualRunning())) "ACTIVE" else proxyState,
                isProcessing = isProcessing,
                isTunEnabled = isTunEnabled,
                isFullTunnel = isFullTunnel
            ) {
                if (isProcessing) return@StatusCard

                if (proxyState == "ACTIVE" || proxyState == "STARTING" || proxyState == "CONNECTION_ISSUE" || proxyState == "LOGGED_OUT") {
                    isProcessing = true
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                    context.startService(intent)
                } else {
                    val currentSocks = prefs.getString("socks5", "127.0.0.1:1055") ?: "127.0.0.1:1055"

                    if (currentSocks.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.main_error_socks5_empty), Toast.LENGTH_LONG).show()
                        return@StatusCard
                    }

                    isProcessing = true
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            if (proxyState == "LOGGED_OUT") {
                Spacer(modifier = Modifier.height(16.dp))
                LoggedOutCard(
                    loginUrl = loginUrl,
                    onConfigureProxy = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    onStop = {
                        isProcessing = true
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                        context.startService(intent)
                    }
                )
            }

            if (proxyState == "CONNECTION_ISSUE") {
                Spacer(modifier = Modifier.height(16.dp))
                ConnectionIssueCard(
                    onConfigureProxy = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    onStop = {
                        isProcessing = true
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                        context.startService(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_console), icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, ConsoleActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_peers), icon = Icons.Default.Share, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, PeersActivity::class.java))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_logs), icon = Icons.AutoMirrored.Filled.List, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, LogsActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_files), icon = Icons.Default.Folder, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, FilesActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_dns), icon = Icons.Default.Language, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, DnsActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_netcheck), icon = Icons.Default.Refresh, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, NetcheckActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_settings), icon = Icons.Default.Settings, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_serve), icon = Icons.Default.Public, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
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
        var downloadUrl by remember { mutableStateOf<String?>(null) }
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableIntStateOf(0) }

        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.main_about_title))
                }
            },
            text = { 
                Column(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.main_app_version, versionName), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.main_core_version, coreVer), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    if (latestVersion != null) {
                        val isNewer = isVersionNewer(versionName, latestVersion!!)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = if (isNewer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isNewer) Icons.Default.Download else Icons.Default.CheckCircle,
                                    null,
                                    tint = if (isNewer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isNewer) stringResource(R.string.main_new_version, latestVersion!!) else stringResource(R.string.main_update_up_to_date),
                                    color = if (isNewer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.main_license_text))
                    
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bropines"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(stringResource(R.string.main_dev_app)) }
                    
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bropines/tailsocks"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(stringResource(R.string.main_dev_patch)) }

                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Asutorufa/tailscale"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(stringResource(R.string.main_dev_anet_patch)) }

                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tailscale/tailscale"))) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(stringResource(R.string.main_dev_core)) }

                    Spacer(Modifier.height(12.dp))

                    if (isDownloading) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(
                                progress = { if (downloadProgress > 0) downloadProgress / 100f else 0f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.main_update_downloading, downloadProgress),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (latestVersion != null && isVersionNewer(versionName, latestVersion!!)) {
                        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                        val cleanVer = latestVersion!!.removePrefix("v")
                        val destFile = java.io.File(destDir, "tailsocks-update-$cleanVer.apk")

                        var isApkCached by remember(destFile.absolutePath) {
                            mutableStateOf(
                                if (destFile.exists() && destFile.length() > 0) {
                                    try {
                                        val pInfo = context.packageManager.getPackageArchiveInfo(destFile.absolutePath, 0)
                                        pInfo != null && pInfo.packageName == context.packageName
                                    } catch (e: Exception) {
                                        false
                                    }
                                } else false
                            )
                        }

                        Button(
                            onClick = {
                                if (isApkCached) {
                                    Toast.makeText(context, context.getString(R.string.main_update_installing), Toast.LENGTH_SHORT).show()
                                    launchApkInstaller(context, destFile)
                                    return@Button
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                    Toast.makeText(context, context.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                    } catch (e: Exception) {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                                    }
                                    return@Button
                                }
                                val targetUrl = downloadUrl ?: "https://github.com/bropines/tailsocks/releases/latest/download/app-release.apk"
                                isDownloading = true
                                downloadProgress = 0
                                scope.launch(Dispatchers.IO) {
                                    val tempFile = java.io.File(destDir, "tailsocks-update-$cleanVer.tmp")
                                    try {
                                        val url = java.net.URL(targetUrl)
                                        val conn = url.openConnection() as java.net.HttpURLConnection
                                        conn.instanceFollowRedirects = true
                                        conn.connect()
                                        val totalLength = conn.contentLength
                                        
                                        conn.inputStream.use { input ->
                                            tempFile.outputStream().use { output ->
                                                val buffer = ByteArray(8192)
                                                var read: Int
                                                var totalRead = 0L
                                                while (input.read(buffer).also { read = it } != -1) {
                                                    output.write(buffer, 0, read)
                                                    totalRead += read
                                                    if (totalLength > 0) {
                                                        val pct = (totalRead * 100 / totalLength).toInt()
                                                        withContext(Dispatchers.Main) { downloadProgress = pct }
                                                    }
                                                }
                                            }
                                        }

                                        val pInfo = context.packageManager.getPackageArchiveInfo(tempFile.absolutePath, 0)
                                        if (pInfo != null && pInfo.packageName == context.packageName) {
                                            if (destFile.exists()) destFile.delete()
                                            tempFile.renameTo(destFile)
                                            withContext(Dispatchers.Main) {
                                                isDownloading = false
                                                isApkCached = true
                                                Toast.makeText(context, context.getString(R.string.main_update_installing), Toast.LENGTH_SHORT).show()
                                                launchApkInstaller(context, destFile)
                                            }
                                        } else {
                                            if (tempFile.exists()) tempFile.delete()
                                            withContext(Dispatchers.Main) {
                                                isDownloading = false
                                                Toast.makeText(context, context.getString(R.string.main_check_failed_format, "Corrupted APK downloaded"), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        if (tempFile.exists()) tempFile.delete()
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            Toast.makeText(context, context.getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(if (isApkCached) Icons.Default.SystemUpdate else Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isApkCached) stringResource(R.string.main_update_install_cached) else stringResource(R.string.main_update_download))
                        }
                    } else {
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
                                            var foundApkUrl: String? = null
                                            var anyApkUrl: String? = null
                                            val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else ""
                                            if (json.has("assets")) {
                                                val assets = json.getAsJsonArray("assets")
                                                for (asset in assets) {
                                                    val obj = asset.asJsonObject
                                                    val name = obj.get("name").asString.lowercase()
                                                    val url = obj.get("browser_download_url").asString
                                                    if (name.endsWith(".apk")) {
                                                        if (anyApkUrl == null) anyApkUrl = url
                                                        if (primaryAbi.isNotEmpty() && name.contains(primaryAbi.lowercase())) {
                                                            foundApkUrl = url
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                latestVersion = tag
                                                downloadUrl = foundApkUrl ?: anyApkUrl
                                                isCheckingUpdate = false
                                            }
                                        } else { throw Exception("HTTP ${connection.responseCode}") }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_SHORT).show()
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
                                Text(stringResource(R.string.main_check_updates))
                            }
                        }
                    }
                } 
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = if (latestVersion != null) "https://github.com/bropines/tailsocks/releases/latest" 
                             else "https://github.com/bropines/tailsocks"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    showAboutDialog = false
                }) { Text(stringResource(R.string.action_github)) }
            },
            dismissButton = { TextButton(onClick = { showAboutDialog = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }

    if (showExitNodeSheet) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.85f).dp

        ModalBottomSheet(
            onDismissRequest = { showExitNodeSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .navigationBarsPadding()
            ) {
                Text(
                    stringResource(R.string.main_select_exit_node),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isExitNodesLoading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (exitNodes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { 
                        Text(stringResource(R.string.main_no_exit_nodes), color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    val currentExitNodeId = remember(showExitNodeSheet, activeAccount.id) { prefs.getString("exit_node_id", "") ?: "" }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                item {
                                    val isSelected = exitNodeIp.isEmpty()
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode("", ""); showExitNodeSheet = false },
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Clear,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    stringResource(R.string.main_exit_node_none),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    stringResource(R.string.main_route_traffic_directly),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    }
                                }

                                items(exitNodes) { node ->
                                    val isSelected = node.id == currentExitNodeId || node.getPrimaryIp() == exitNodeIp
                                    val (osIcon, osColor) = getOsVisuals(node.os).let { (icon, color) ->
                                        if (icon == Icons.Default.Devices) Icons.Default.VpnKey to MaterialTheme.colorScheme.primary
                                        else icon to color
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode(node.id ?: "", node.getPrimaryIp()); showExitNodeSheet = false },
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        else osColor.copy(alpha = 0.12f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    osIcon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else osColor
                                                )
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    node.getDisplayName(),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    node.getPrimaryIp(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: String, isProcessing: Boolean, isTunEnabled: Boolean, isFullTunnel: Boolean, onToggle: () -> Unit) {
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
            .heightIn(min = 130.dp)
            .alpha(if (isProcessing) 0.6f else 1f)
            .clickable(enabled = !isProcessing) { onToggle() },
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state == "ACTIVE" && isTunEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = when(state) {
                        "ACTIVE" -> Icons.Default.CheckCircle
                        "STARTING" -> Icons.Default.Refresh
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when(state) {
                    "ACTIVE" -> if (isTunEnabled) "${stringResource(R.string.main_status_active)} + TUN" else stringResource(R.string.main_status_active)
                    "STARTING" -> stringResource(R.string.main_status_starting)
                    else -> stringResource(R.string.status_stopped)
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    isProcessing -> stringResource(R.string.main_status_please_wait)
                    state == "ACTIVE" -> {
                        if (isTunEnabled) {
                            if (isFullTunnel) stringResource(R.string.main_tun_full_tunnel_desc)
                            else stringResource(R.string.main_tun_split_tunnel_desc)
                        } else {
                            stringResource(R.string.main_status_active_desc)
                        }
                    }
                    state == "STARTING" -> stringResource(R.string.main_status_starting_desc)
                    else -> stringResource(R.string.tap_to_start)
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(0.6f),
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

@Composable
fun LoggedOutCard(
    loginUrl: String?,
    onConfigureProxy: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val activeAccount = remember { io.github.bropines.tailscaled.core.AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    var enteredKey by remember { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }
    var showKeyInput by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.main_logged_out_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            Text(
                text = stringResource(R.string.main_logged_out_desc),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (loginUrl != null) {
                    Button(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                            } catch (e: Exception) {
                                Toast.makeText(context, context.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.main_logged_out_btn_login),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                OutlinedButton(
                    onClick = { showKeyInput = !showKeyInput },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (showKeyInput) "Hide Key" else "Use Key",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            AnimatedVisibility(visible = showKeyInput) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = enteredKey,
                        onValueChange = { enteredKey = it },
                        label = { Text(stringResource(R.string.main_logged_out_authkey_label), fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            profilePrefs.edit().putString("authkey", enteredKey).apply()
                            val stopIntent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                            context.startService(stopIntent)
                            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            mainHandler.postDelayed({
                                val startIntent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                                ContextCompat.startForegroundService(context, startIntent)
                            }, 500)
                            Toast.makeText(context, "Key saved. Restarting service...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Submit Key", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onConfigureProxy,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Proxy Setup", fontSize = 11.sp)
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.main_logged_out_btn_stop_short), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ConnectionIssueCard(
    onConfigureProxy: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.main_conn_issue_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Text(
                text = stringResource(R.string.main_conn_issue_desc),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConfigureProxy,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.main_conn_issue_btn_proxy),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.main_logged_out_btn_stop_short),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
