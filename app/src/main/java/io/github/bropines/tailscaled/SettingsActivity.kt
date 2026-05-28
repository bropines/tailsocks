package io.github.bropines.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import appctr.Appctr
import java.net.URLEncoder

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var appTheme by remember { mutableStateOf(GlobalSettings.getAppTheme(context)) }
            var themePreset by remember { mutableStateOf(GlobalSettings.getThemePreset(context)) }
            var dynamicColor by remember { mutableStateOf(GlobalSettings.isDynamicColorEnabled(context)) }

            TailSocksTheme(
                appTheme = appTheme,
                themePreset = themePreset,
                dynamicColorEnabled = dynamicColor
            ) {
                SettingsScreen(
                    onBack = { finish() },
                    currentTheme = appTheme,
                    onThemeChange = { 
                        appTheme = it
                        GlobalSettings.setAppTheme(context, it)
                    },
                    currentPreset = themePreset,
                    onPresetChange = {
                        themePreset = it
                        GlobalSettings.setThemePreset(context, it)
                    },
                    currentDynamicColor = dynamicColor,
                    onDynamicColorChange = {
                        dynamicColor = it
                        GlobalSettings.setDynamicColorEnabled(context, it)
                    }
                )
            }
        }
    }
}

fun generateRandomString(length: Int = 12): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length).map { allowedChars.random() }.joinToString("")
}

data class PresetItem(val id: String, val color: Color, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    currentPreset: String,
    onPresetChange: (String) -> Unit,
    currentDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    // Tab Navigation State
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        Pair("Style", Icons.Default.Palette),
        Pair("Network", Icons.Default.Language),
        Pair("Core", Icons.Default.Tune),
        Pair("Profile", Icons.Default.AccountCircle)
    )

    // Global Settings
    var taildropRootUri by remember { mutableStateOf(GlobalSettings.getTaildropRootUri(context)) }
    var autoStart by remember { mutableStateOf(GlobalSettings.isAutoStartEnabled(context)) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var isProxyEnabled by remember { mutableStateOf(GlobalSettings.isCPProxyEnabled(context)) }
    
    var socks5 by remember { mutableStateOf(GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")) }
    var socks5User by remember { mutableStateOf(GlobalSettings.getString(context, "socks5_user", "")) }
    var socks5Pass by remember { mutableStateOf(GlobalSettings.getString(context, "socks5_pass", "")) }
    var httpProxy by remember { mutableStateOf(GlobalSettings.getString(context, "httpproxy", "")) }
    var dnsProxy by remember { mutableStateOf(GlobalSettings.getString(context, "dns_proxy", "127.0.0.1:1053")) }
    var dnsFallbacks by remember { mutableStateOf(GlobalSettings.getString(context, "dns_fallbacks", "8.8.8.8:53,1.1.1.1:53")) }
    var dohUrl by remember { mutableStateOf(GlobalSettings.getString(context, "doh_url", "https://1.1.1.1/dns-query")) }
    var loginServer by remember { mutableStateOf(GlobalSettings.getString(context, "login_server", "")) }
    
    var autoRefresh by remember { mutableStateOf(GlobalSettings.getBoolean(context, "auto_refresh", false)) }
    var acceptRoutes by remember { mutableStateOf(GlobalSettings.getBoolean(context, "accept_routes", false)) }
    var acceptDns by remember { mutableStateOf(GlobalSettings.getBoolean(context, "accept_dns", true)) }
    var forceBg by remember { mutableStateOf(GlobalSettings.getBoolean(context, "force_bg", false)) }
    var detailedLogs by remember { mutableStateOf(GlobalSettings.getBoolean(context, "detailed_logs", false)) }
    var extraArgs by remember { mutableStateOf(GlobalSettings.getString(context, "extra_args_raw", "")) }

    // Profile Settings
    var authKey by remember { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }
    var hostname by remember { mutableStateOf(profilePrefs.getString("hostname", "") ?: "") }
    var exitNodeIp by remember { mutableStateOf(profilePrefs.getString("exit_node_ip", "") ?: "") }
    var enableWebUI by remember { mutableStateOf(profilePrefs.getBoolean("enable_webui", false)) }
    var webUIAddr by remember { mutableStateOf(profilePrefs.getString("webui_addr", "127.0.0.1:8080") ?: "127.0.0.1:8080") }

    var advertiseTags by remember { mutableStateOf(profilePrefs.getString("advertise_tags", "") ?: "") }
    var advertiseRoutes by remember { mutableStateOf(profilePrefs.getString("advertise_routes", "") ?: "") }
    var appliedTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableNetworkTags by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(activeAccount.id) {
        scope.launch(Dispatchers.IO) {
            try {
                val pJson = Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error")) {
                    val status = Gson().fromJson(pJson, StatusResponse::class.java)
                    val selfTags = status.self?.tags ?: emptyList()
                    withContext(Dispatchers.Main) { appliedTags = selfTags }

                    val netTags = mutableSetOf<String>()
                    selfTags.forEach { netTags.add(it) }
                    status.peers?.values?.forEach { peer ->
                        peer.tags?.forEach { netTags.add(it) }
                    }
                    val sortedTags = netTags.toList().sorted()
                    withContext(Dispatchers.Main) { availableNetworkTags = sortedTags }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var backupPassword by remember { mutableStateOf("") }
    var showBackupPasswordDialog by remember { mutableStateOf(false) }

    var restorePassword by remember { mutableStateOf("") }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { GlobalSettings.setTaildropRootUri(context, uri); taildropRootUri = uri }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val allPrefs = profilePrefs.all
                    val backupData = mapOf("account" to activeAccount, "settings" to allPrefs)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(Gson().toJson(backupData).toByteArray()) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (jsonBytes != null) {
                        val jsonString = String(jsonBytes)
                        val backupData = Gson().fromJson(jsonString, Map::class.java)
                        val settings = backupData["settings"] as? Map<String, Any>
                        if (settings != null) {
                            val editor = profilePrefs.edit()
                            settings.forEach { (k, v) ->
                                when (v) {
                                    is String -> editor.putString(k, v)
                                    is Boolean -> editor.putBoolean(k, v)
                                    is Double -> editor.putFloat(k, v.toFloat())
                                    is Float -> editor.putFloat(k, v)
                                    is Int -> editor.putInt(k, v)
                                    is Long -> editor.putLong(k, v)
                                }
                            }
                            editor.apply()
                            withContext(Dispatchers.Main) { 
                                Toast.makeText(context, "Settings restored successfully. Please restart the app.", Toast.LENGTH_LONG).show() 
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    val fullBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val baos = java.io.ByteArrayOutputStream()
                    java.util.zip.ZipOutputStream(baos).use { zos ->
                        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                        if (prefsDir.exists()) {
                            prefsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = "shared_prefs/${file.name}"
                                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        val statesDir = File(context.filesDir, "states")
                        if (statesDir.exists()) {
                            statesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = "files/states/${file.relativeTo(statesDir).path}"
                                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        val historyFile = File(context.filesDir, "sent_history.json")
                        if (historyFile.exists()) {
                            zos.putNextEntry(java.util.zip.ZipEntry("files/sent_history.json"))
                            historyFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    val zipBytes = baos.toByteArray()
                    val encryptedBytes = BackupCrypto.encrypt(zipBytes, backupPassword.toCharArray())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(encryptedBytes) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Encrypted backup saved", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Full backup failed: ${e.message}", Toast.LENGTH_LONG).show() }
                } finally {
                    backupPassword = ""
                }
            }
        }
    }

    val fullRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestorePasswordDialog = true
        }
    }

    fun performRestore(uri: Uri, passwordStr: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val encryptedBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (encryptedBytes == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to read backup file", Toast.LENGTH_LONG).show() }
                    return@launch
                }
                val decryptedBytes = BackupCrypto.decrypt(encryptedBytes, passwordStr.toCharArray())
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(decryptedBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val targetFile: File? = when {
                                entry.name.startsWith("shared_prefs/") -> {
                                    File(context.applicationInfo.dataDir, entry.name)
                                }
                                entry.name.startsWith("files/") -> {
                                    val subPath = entry.name.substring("files/".length)
                                    File(context.filesDir, subPath)
                                }
                                else -> null
                            }
                            if (targetFile != null) {
                                targetFile.parentFile?.mkdirs()
                                targetFile.outputStream().use { fos -> zis.copyTo(fos) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "Full restore complete. Please FORCE RESTART the app.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Full restore failed: Invalid password or corrupted file", Toast.LENGTH_LONG).show() }
            } finally {
                restorePassword = ""
                pendingRestoreUri = null
            }
        }
    }

    fun saveGlobalPref(key: String, value: Any?) {
        when (value) {
            is String -> GlobalSettings.setString(context, key, value)
            is Boolean -> GlobalSettings.setBoolean(context, key, value)
        }
        context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
    }

    fun saveProfilePref(key: String, value: Any?, triggerService: Boolean = true) {
        val editor = profilePrefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
        editor.apply()
        if (triggerService) {
            context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
        }
    }

    fun copySagerNetLink() {
        try {
            val encodedUser = URLEncoder.encode(socks5User, "UTF-8").replace("+", "%20")
            val encodedPass = URLEncoder.encode(socks5Pass, "UTF-8").replace("+", "%20")
            val label = URLEncoder.encode("TAILSCALE (${activeAccount.name})", "UTF-8")
            val link = if (encodedUser.isNotEmpty()) {
                "socks5://$encodedUser:$encodedPass@$socks5#$label"
            } else {
                "socks5://$socks5#$label"
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SagerNet SOCKS5", link))
            Toast.makeText(context, "SagerNet link copied!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    var showResetDialog by remember { mutableStateOf(false) }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Log out from Tailnet?") },
            text = { Text("This will clear your current session and node state using native LocalAPI. You will need to re-authenticate. Continue?") },
            confirmButton = {
                Button(onClick = { 
                    scope.launch(Dispatchers.IO) {
                        Appctr.logout()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showResetDialog = false 
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Log Out") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Modern Tab Layout
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }
            ) {
                tabs.forEachIndexed { index, (title, icon) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) },
                        icon = { Icon(icon, null, modifier = Modifier.size(20.dp)) }
                    )
                }
            }

            // Animated Tab Content transitions
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "TabTransition"
            ) { targetTab ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    when (targetTab) {
                        0 -> { // TAB 0: Personalization & System
                            SettingsCard(title = "Personalization") {
                                // Theme selector (Chips row)
                                val themeOptions = listOf(
                                    Triple("system", Icons.Default.Settings, "System"),
                                    Triple("light", Icons.Default.LightMode, "Light"),
                                    Triple("dark", Icons.Default.DarkMode, "Dark"),
                                    Triple("amoled", Icons.Default.OfflineBolt, "Amoled")
                                )
                                Column(Modifier.padding(bottom = 8.dp)) {
                                    Text("Interface Theme", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        themeOptions.forEach { (id, icon, label) ->
                                            val isSelected = currentTheme == id
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { onThemeChange(id) },
                                                label = { Text(label) },
                                                leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            )
                                        }
                                    }
                                }

                                // Theme preset selector (Color Circles)
                                if (!currentDynamicColor || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                                    Spacer(Modifier.height(12.dp))
                                    val presets = listOf(
                                        PresetItem("default", Color(0xFF6750A4), "Default"),
                                        PresetItem("lavender", Color(0xFF704E9B), "Lavender"),
                                        PresetItem("emerald", Color(0xFF006B54), "Emerald"),
                                        PresetItem("sapphire", Color(0xFF005FAF), "Sapphire"),
                                        PresetItem("amber", Color(0xFF825500), "Amber"),
                                        PresetItem("monochrome", Color(0xFF1D2023), "Monochrome")
                                    )
                                    Column {
                                        Text("Color Palette", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            presets.forEach { item ->
                                                val isSelected = currentPreset == item.id
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .background(item.color, shape = CircleShape)
                                                        .clickable { onPresetChange(item.id) }
                                                        .border(
                                                            width = if (isSelected) 3.dp else 1.dp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                            shape = CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Dynamic Colors switcher (Android 12+)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Dynamic Colors", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text("Use Material You system colors", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                        Switch(checked = currentDynamicColor, onCheckedChange = onDynamicColorChange)
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Storage") {
                                SettingsClickableItem(
                                    "Taildrop Storage Folder",
                                    taildropRootUri?.path ?: "Uses app internal folder",
                                    Icons.Default.Folder
                                ) { folderPicker.launch(null) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "System & Backup") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showBackupPasswordDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Archive, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Backup (ZIP)", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                    OutlinedButton(
                                        onClick = { fullRestoreLauncher.launch("*/*") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.SettingsBackupRestore, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Restore ZIP", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                SettingsClickableItem("Battery Optimization", "Disable to prevent background sleep", Icons.Default.BatteryAlert) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot open battery settings", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem("Auto-start on Boot", "Start TailSocks when device turns on", Icons.Default.PowerSettingsNew, autoStart) {
                                    GlobalSettings.setAutoStartEnabled(context, it)
                                    autoStart = it
                                }
                            }
                        }

                        1 -> { // TAB 1: Network & Proxy
                            SettingsCard(title = "SOCKS5 Proxy (Internal)") {
                                SettingsEditItem("SOCKS5 Address", socks5, Icons.Default.Language) { socks5 = it; saveGlobalPref("socks5", it) }
                                SettingsEditItem("SOCKS5 Username", socks5User, Icons.Default.Person, onAction = { generateRandomString(8) }, actionIcon = Icons.Default.Casino) { socks5User = it; saveGlobalPref("socks5_user", it) }
                                SettingsEditItem("SOCKS5 Password", socks5Pass, Icons.Default.Password, onAction = { generateRandomString(12) }, actionIcon = Icons.Default.Casino) { socks5Pass = it; saveGlobalPref("socks5_pass", it) }
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { copySagerNetLink() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                    Icon(Icons.Default.Share, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copy SagerNet Link")
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "HTTP Proxy") {
                                SettingsEditItem("HTTP Proxy Address", httpProxy, Icons.Default.Http) { httpProxy = it; saveGlobalPref("httpproxy", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Control Plane Proxy") {
                                SettingsClickableItem(
                                    "Control Plane Proxy", 
                                    if (isProxyEnabled) "Enabled (${GlobalSettings.getCPField(context, "type")})" else "Disabled",
                                    Icons.Default.Shield
                                ) { showProxyDialog = true }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Service Advertisements") {
                                SettingsEditItem(
                                    title = "Advertise Tags",
                                    value = advertiseTags,
                                    icon = Icons.Default.Label,
                                    placeholder = "tag:server, tag:mobile",
                                    description = "Request access control tags for this node",
                                    suggestions = availableNetworkTags
                                ) { 
                                    advertiseTags = it
                                    saveProfilePref("advertise_tags", it) 
                                }
                                if (appliedTags.isNotEmpty()) {
                                    Text(
                                        text = "Applied Tags: " + appliedTags.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(
                                    title = "Advertise Routes",
                                    value = advertiseRoutes,
                                    icon = Icons.Default.Map,
                                    placeholder = "10.0.0.0/24, 192.168.1.0/24",
                                    description = "Advertise local subnets into Tailnet"
                                ) { 
                                    advertiseRoutes = it
                                    saveProfilePref("advertise_routes", it) 
                                }
                            }
                        }

                        2 -> { // TAB 2: Core Settings
                            SettingsCard(title = "DNS Proxy") {
                                SettingsEditItem("DNS Proxy Address", dnsProxy, Icons.Default.Toll) { dnsProxy = it; saveGlobalPref("dns_proxy", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Fallback DNS Servers") {
                                SettingsEditItem("DNS Fallbacks", dnsFallbacks, Icons.Default.List, placeholder = "8.8.8.8:53,1.1.1.1:53") { dnsFallbacks = it; saveGlobalPref("dns_fallbacks", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem("DoH Fallback URL", dohUrl, Icons.Default.Link, placeholder = "https://1.1.1.1/dns-query") { dohUrl = it; saveGlobalPref("doh_url", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Flags & Logs") {
                                SettingsSwitchItem("Accept Routes", "Allow network to configure routes", Icons.Default.Map, acceptRoutes) { acceptRoutes = it; saveGlobalPref("accept_routes", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem("Accept DNS", "Allow network to configure DNS", Icons.Default.Dns, acceptDns) { acceptDns = it; saveGlobalPref("accept_dns", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem("Force Background", "Keep WakeLock active in background", Icons.Default.BatteryFull, forceBg) { forceBg = it; saveGlobalPref("force_bg", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem("Detailed Logs", "Disable log filtering (noisy!)", Icons.Default.BugReport, detailedLogs) { detailedLogs = it; saveGlobalPref("detailed_logs", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem("Extra Arguments", extraArgs, Icons.Default.Code, "--flag=val ...") { extraArgs = it; saveGlobalPref("extra_args_raw", it) }
                            }
                        }

                        3 -> { // TAB 3: Account Profile & Advanced
                            SettingsCard(title = "Account Settings: ${activeAccount.name}") {
                                SettingsEditItem("Login Server (Headscale)", loginServer, Icons.Default.Cloud, placeholder = "https://controlplane.tailscale.com") { loginServer = it; saveProfilePref("login_server", it) }
                                SettingsEditItem("Auth Key", authKey, Icons.Default.VpnKey) { authKey = it; saveProfilePref("authkey", it) }
                                SettingsEditItem("Hostname", hostname, Icons.Default.Badge, onAction = { android.os.Build.MODEL.replace(" ", "-").lowercase() }, actionIcon = Icons.Default.AutoFixHigh) { hostname = it; saveProfilePref("hostname", it) }
                                SettingsExitNodeItem("Exit Node", exitNodeIp, Icons.Default.Input) { id, ip ->
                                    exitNodeIp = ip
                                    saveProfilePref("exit_node_ip", ip, triggerService = false)
                                    saveProfilePref("exit_node_id", id, triggerService = false)
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Web Interface") {
                                SettingsSwitchItem("Enable Web UI", "Run built-in Tailscale web server", Icons.Default.Web, enableWebUI) { enableWebUI = it; saveProfilePref("enable_webui", it) }
                                if (enableWebUI) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                    SettingsEditItem("Web UI Address", webUIAddr, Icons.Default.Link) { webUIAddr = it; saveProfilePref("webui_addr", it) }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = "Advanced Profile") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { backupLauncher.launch("tailsocks_backup_${activeAccount.name}.json") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Backup, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Backup", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                    OutlinedButton(
                                        onClick = { restoreLauncher.launch("application/json") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.SettingsBackupRestore, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Import", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { showResetDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.RestartAlt, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Reset Node State")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    if (showProxyDialog) {
        ControlProxyDialog(
            onDismiss = { showProxyDialog = false },
            onApply = { 
                isProxyEnabled = GlobalSettings.isCPProxyEnabled(context)
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
            }
        )
    }

    if (showBackupPasswordDialog) {
        var tempPassword by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showBackupPasswordDialog = false },
            title = { Text("Backup Encryption Password") },
            text = {
                Column {
                    Text("Enter a password to encrypt your backup. You will need this password to restore your state.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPassword.isNotBlank()) {
                            backupPassword = tempPassword
                            showBackupPasswordDialog = false
                            fullBackupLauncher.launch("tailsocks_full_backup.enc")
                        } else {
                            Toast.makeText(context, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Backup") }
            },
            dismissButton = {
                TextButton(onClick = { showBackupPasswordDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRestorePasswordDialog && pendingRestoreUri != null) {
        var tempPassword by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { 
                showRestorePasswordDialog = false
                pendingRestoreUri = null
            },
            title = { Text("Backup Decryption Password") },
            text = {
                Column {
                    Text("Enter the password that was used to encrypt this backup.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPassword.isNotBlank()) {
                            val uri = pendingRestoreUri!!
                            showRestorePasswordDialog = false
                            performRestore(uri, tempPassword)
                        } else {
                            Toast.makeText(context, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showRestorePasswordDialog = false
                    pendingRestoreUri = null
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun ControlProxyDialog(onDismiss: () -> Unit, onApply: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(GlobalSettings.isCPProxyEnabled(context)) }
    var type by remember { mutableStateOf(GlobalSettings.getCPField(context, "type", "SOCKS5")) }
    var host by remember { mutableStateOf(GlobalSettings.getCPField(context, "host")) }
    var port by remember { mutableStateOf(GlobalSettings.getCPField(context, "port")) }
    var user by remember { mutableStateOf(GlobalSettings.getCPField(context, "user")) }
    var pass by remember { mutableStateOf(GlobalSettings.getCPField(context, "pass")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Control Plane Proxy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Proxy", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Proxy Type", Modifier.weight(1f))
                    FilterChip(
                        selected = type == "SOCKS5",
                        onClick = { type = "SOCKS5" },
                        label = { Text("SOCKS5") }
                    )
                    FilterChip(
                        selected = type == "HTTP",
                        onClick = { type = "HTTP" },
                        label = { Text("HTTP") }
                    )
                }
                Spacer(Modifier.height(16.dp))
                
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (type == "HTTP") "8080" else "1080") })
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username (Optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password (Optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                GlobalSettings.setCPProxyEnabled(context, enabled)
                GlobalSettings.setCPField(context, "type", type)
                GlobalSettings.setCPField(context, "host", host)
                GlobalSettings.setCPField(context, "port", port)
                GlobalSettings.setCPField(context, "user", user)
                GlobalSettings.setCPField(context, "pass", pass)
                onApply()
                onDismiss()
            }) { Text("Apply & Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SettingsClickableItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick, 
        shape = RoundedCornerShape(12.dp), 
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
fun SettingsSwitchItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onCheckedChange(!checked) }, 
        shape = RoundedCornerShape(12.dp), 
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
fun SettingsEditItem(
    title: String, 
    value: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    placeholder: String = "", 
    description: String = "",
    suggestions: List<String> = emptyList(),
    onAction: (() -> String)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(showDialog) { if (showDialog) text = value }
    Surface(
        onClick = { showDialog = true }, 
        shape = RoundedCornerShape(12.dp), 
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { 
                Text(
                    text = if (value.isEmpty()) {
                        if (description.isNotEmpty()) description else (placeholder.ifEmpty { "Not set" })
                    } else value, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                ) 
            },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { 
                Column {
                    OutlinedTextField(
                        value = text, 
                        onValueChange = { text = it }, 
                        modifier = Modifier.fillMaxWidth(), 
                        singleLine = true,
                        label = { if (placeholder.isNotEmpty()) Text("Example: $placeholder") },
                        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                        trailingIcon = if (onAction != null && actionIcon != null) {
                            { IconButton(onClick = { text = onAction() }) { Icon(actionIcon, null) } }
                        } else null
                    )
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Suggested:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { tag ->
                                val cleanTag = if (tag.startsWith("tag:")) tag else "tag:$tag"
                                val isSelected = text.split(",").map { it.trim() }.contains(cleanTag)
                                
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val currentTags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                        if (currentTags.contains(cleanTag)) {
                                            currentTags.remove(cleanTag)
                                        } else {
                                            currentTags.add(cleanTag)
                                        }
                                        text = currentTags.joinToString(", ")
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { onSave(text); showDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SettingsChoiceItem(
    title: String,
    value: String,
    options: List<String>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDialog = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(value) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSave(option); showDialog = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (option == value), onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(option)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsExitNodeItem(
    title: String, 
    currentValue: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    onSave: (String, String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun applyExitNode(id: String, ip: String) {
        onSave(id, ip)
        scope.launch(Dispatchers.IO) {
            val prefsJson = "{\"ExitNodeID\": \"$id\", \"ExitNodeIDSet\": true}"
            val res = Appctr.setPrefs(prefsJson)
            if (res != "OK") {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "LocalAPI Error: $res", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            updateAllWidgets(context)
        }
    }

    Surface(
        onClick = { 
            showDialog = true 
            isLoading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val pJson = Appctr.getStatusFromAPI()
                    if (!pJson.startsWith("Error")) {
                        val status = Gson().fromJson(pJson, StatusResponse::class.java)
                        val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.toList() ?: emptyList()
                        withContext(Dispatchers.Main) { exitNodes = nodes }
                    }
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) { isLoading = false }
            }
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(if (currentValue.isEmpty()) "None" else currentValue, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }

    if (showDialog) {
        ModalBottomSheet(onDismissRequest = { showDialog = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Select Exit Node", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (isLoading) {
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
                                modifier = Modifier.clickable { applyExitNode("", ""); showDialog = false }
                            )
                        }
                        items(exitNodes.size) { i ->
                            val node = exitNodes[i]
                            ListItem(
                                headlineContent = { Text(node.getDisplayName()) },
                                supportingContent = { Text(node.getPrimaryIp()) },
                                leadingContent = { Icon(Icons.Default.VpnKey, null) },
                                modifier = Modifier.clickable { applyExitNode(node.id ?: "", node.getPrimaryIp()); showDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
