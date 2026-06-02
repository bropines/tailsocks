package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState

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
import androidx.compose.ui.res.stringResource
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
            var amoledMode by remember { mutableStateOf(GlobalSettings.getBoolean(context, "amoled_mode", false)) }

            TailSocksTheme(
                appTheme = appTheme,
                themePreset = themePreset,
                dynamicColorEnabled = dynamicColor,
                amoledModeEnabled = amoledMode
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
                    },
                    currentAmoledMode = amoledMode,
                    onAmoledModeChange = {
                        amoledMode = it
                        GlobalSettings.setBoolean(context, "amoled_mode", it)
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
    onDynamicColorChange: (Boolean) -> Unit,
    currentAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    // Tab Navigation State
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        Pair(stringResource(R.string.settings_tab_app), Icons.Default.Palette),
        Pair(stringResource(R.string.settings_tab_network), Icons.Default.Language),
        Pair(stringResource(R.string.settings_tab_core), Icons.Default.Tune),
        Pair(stringResource(R.string.settings_tab_profile), Icons.Default.AccountCircle)
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
    val globalApiPrefs = remember { context.getSharedPreferences("admin_api_keys", Context.MODE_PRIVATE) }
    var adminApiTailnet by remember { mutableStateOf(profilePrefs.getString("last_known_tailnet", "") ?: "") }
    var adminApiToken by remember { mutableStateOf("") }
    LaunchedEffect(adminApiTailnet) {
        adminApiToken = if (adminApiTailnet.isNotEmpty()) {
            globalApiPrefs.getString(adminApiTailnet, "") ?: ""
        } else ""
    }

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
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_backup_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_backup_failed_format, e.message), Toast.LENGTH_LONG).show() }
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
                                Toast.makeText(context, context.getString(R.string.settings_restored_success), Toast.LENGTH_LONG).show() 
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_restore_failed_format, e.message), Toast.LENGTH_LONG).show() }
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
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_backup_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_backup_failed_format, e.message), Toast.LENGTH_LONG).show() }
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
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_read_backup_failed), Toast.LENGTH_LONG).show() }
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
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_restore_success), Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_restore_failed), Toast.LENGTH_LONG).show() }
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
            Toast.makeText(context, context.getString(R.string.settings_sagernet_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.settings_error_format, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    var showResetDialog by remember { mutableStateOf(false) }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = { Text(stringResource(R.string.settings_logout_text)) },
            confirmButton = {
                Button(onClick = { 
                    scope.launch(Dispatchers.IO) {
                        Appctr.logout()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.settings_logged_out), Toast.LENGTH_SHORT).show()
                        }
                    }
                    showResetDialog = false 
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.settings_logout_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Modern Tab Layout using Chips
            val listState = rememberLazyListState()
            LaunchedEffect(selectedTab) {
                listState.animateScrollToItem(selectedTab)
            }
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(tabs.size) { index ->
                    val (title, icon) = tabs[index]
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title) },
                        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
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
                            SettingsCard(title = stringResource(R.string.settings_sect_personalization)) {
                                // Theme selector (Chips row)
                                val themeOptions = listOf(
                                    Triple("system", Icons.Default.Settings, stringResource(R.string.settings_theme_system)),
                                    Triple("light", Icons.Default.LightMode, stringResource(R.string.settings_theme_light)),
                                    Triple("dark", Icons.Default.DarkMode, stringResource(R.string.settings_theme_dark))
                                )
                                Column(Modifier.padding(bottom = 8.dp)) {
                                    Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 
                                // Language selector (Chips row)
                                val currentLocales = AppCompatDelegate.getApplicationLocales()
                                val currentLang = if (currentLocales.isEmpty) "sys" else currentLocales.get(0)?.language ?: "sys"
                                val languageOptions = listOf(
                                    Triple("sys", Icons.Default.Language, stringResource(R.string.settings_lang_sys)),
                                    Triple("en", Icons.Default.Language, stringResource(R.string.settings_lang_en)),
                                    Triple("ru", Icons.Default.Language, stringResource(R.string.settings_lang_ru))
                                )
                                Spacer(Modifier.height(12.dp))
                                Column(Modifier.padding(bottom = 8.dp)) {
                                    Text(stringResource(R.string.settings_lang_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        languageOptions.forEach { (id, icon, label) ->
                                            val isSelected = currentLang == id
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    val localeList = if (id == "sys") {
                                                        LocaleListCompat.getEmptyLocaleList()
                                                    } else {
                                                        LocaleListCompat.forLanguageTags(id)
                                                    }
                                                    AppCompatDelegate.setApplicationLocales(localeList)
                                                },
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
                                        PresetItem("default", Color(0xFF6750A4), stringResource(R.string.settings_preset_default)),
                                        PresetItem("lavender", Color(0xFF704E9B), stringResource(R.string.settings_preset_lavender)),
                                        PresetItem("emerald", Color(0xFF006B54), stringResource(R.string.settings_preset_emerald)),
                                        PresetItem("sapphire", Color(0xFF005FAF), stringResource(R.string.settings_preset_sapphire)),
                                        PresetItem("amber", Color(0xFF825500), stringResource(R.string.settings_preset_amber)),
                                        PresetItem("monochrome", Color(0xFF1D2023), stringResource(R.string.settings_preset_monochrome)),
                                        PresetItem("tokionight", Color(0xFF7AA2F7), stringResource(R.string.settings_preset_tokionight))
                                    )
                                    Column {
                                        Text(stringResource(R.string.settings_palette_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                            Text(stringResource(R.string.settings_dynamic_color_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text(stringResource(R.string.settings_dynamic_color_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                        Switch(checked = currentDynamicColor, onCheckedChange = onDynamicColorChange)
                                    }
                                }

                                // AMOLED Black switcher
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.settings_amoled_black_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.settings_amoled_black_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Switch(checked = currentAmoledMode, onCheckedChange = onAmoledModeChange)
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_storage)) {
                                SettingsClickableItem(
                                    stringResource(R.string.settings_taildrop_folder_title),
                                    taildropRootUri?.path ?: stringResource(R.string.settings_taildrop_folder_default),
                                    Icons.Default.Folder
                                ) { folderPicker.launch(null) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_system_backup)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showBackupPasswordDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Archive, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.settings_backup_zip), 
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { fullRestoreLauncher.launch("*/*") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.SettingsBackupRestore, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.settings_restore_zip), 
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                SettingsClickableItem(stringResource(R.string.settings_battery_opt_title), stringResource(R.string.settings_battery_opt_desc), Icons.Default.BatteryAlert) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, context.getString(R.string.settings_battery_opt_error), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem(stringResource(R.string.settings_autostart_title), stringResource(R.string.settings_autostart_desc), Icons.Default.PowerSettingsNew, autoStart) {
                                    GlobalSettings.setAutoStartEnabled(context, it)
                                    autoStart = it
                                }
                            }
                        }

                        1 -> { // TAB 1: Network & Proxy
                            SettingsCard(title = stringResource(R.string.settings_sect_socks5)) {
                                SettingsEditItem(stringResource(R.string.settings_socks5_address_title), socks5, Icons.Default.Language) { socks5 = it; saveGlobalPref("socks5", it) }
                                SettingsEditItem(stringResource(R.string.settings_socks5_username_title), socks5User, Icons.Default.Person, onAction = { generateRandomString(8) }, actionIcon = Icons.Default.Casino) { socks5User = it; saveGlobalPref("socks5_user", it) }
                                SettingsEditItem(stringResource(R.string.settings_socks5_password_title), socks5Pass, Icons.Default.Password, onAction = { generateRandomString(12) }, actionIcon = Icons.Default.Casino) { socks5Pass = it; saveGlobalPref("socks5_pass", it) }
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { copySagerNetLink() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                    Icon(Icons.Default.Share, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings_sagernet_copy))
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_http)) {
                                SettingsEditItem(stringResource(R.string.settings_http_address_title), httpProxy, Icons.Default.Http) { httpProxy = it; saveGlobalPref("httpproxy", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_control_proxy)) {
                                val statusText = if (isProxyEnabled) stringResource(R.string.settings_control_proxy_enabled_format, GlobalSettings.getCPField(context, "type")) else stringResource(R.string.settings_control_proxy_disabled)
                                SettingsClickableItem(
                                    stringResource(R.string.settings_control_proxy_title), 
                                    statusText,
                                    Icons.Default.Shield
                                ) { showProxyDialog = true }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_service_ad)) {
                                SettingsEditItem(
                                    title = stringResource(R.string.settings_ad_tags_title),
                                    value = advertiseTags,
                                    icon = Icons.Default.Label,
                                    placeholder = stringResource(R.string.settings_ad_tags_placeholder),
                                    description = stringResource(R.string.settings_ad_tags_desc),
                                    suggestions = availableNetworkTags
                                ) { 
                                    advertiseTags = it
                                    saveProfilePref("advertise_tags", it) 
                                }
                                if (appliedTags.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_applied_tags_label, appliedTags.joinToString(", ")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(
                                    title = stringResource(R.string.settings_ad_routes_title),
                                    value = advertiseRoutes,
                                    icon = Icons.Default.Map,
                                    placeholder = stringResource(R.string.settings_ad_routes_placeholder),
                                    description = stringResource(R.string.settings_ad_routes_desc)
                                ) { 
                                    advertiseRoutes = it
                                    saveProfilePref("advertise_routes", it) 
                                }
                            }
                        }

                        2 -> { // TAB 2: Core Settings
                            SettingsCard(title = stringResource(R.string.settings_sect_dns_proxy)) {
                                SettingsEditItem(stringResource(R.string.settings_dns_proxy_address_title), dnsProxy, Icons.Default.Toll) { dnsProxy = it; saveGlobalPref("dns_proxy", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_fallback_dns)) {
                                SettingsEditItem(stringResource(R.string.settings_dns_fallbacks_title), dnsFallbacks, Icons.Default.List, placeholder = stringResource(R.string.settings_dns_fallbacks_placeholder)) { dnsFallbacks = it; saveGlobalPref("dns_fallbacks", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(stringResource(R.string.settings_doh_fallback_title), dohUrl, Icons.Default.Link, placeholder = stringResource(R.string.settings_doh_fallback_placeholder)) { dohUrl = it; saveGlobalPref("doh_url", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_flags_logs)) {
                                SettingsSwitchItem(stringResource(R.string.settings_accept_routes_title), stringResource(R.string.settings_accept_routes_desc), Icons.Default.Map, acceptRoutes) { acceptRoutes = it; saveGlobalPref("accept_routes", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem(stringResource(R.string.settings_accept_dns_title), stringResource(R.string.settings_accept_dns_desc), Icons.Default.Dns, acceptDns) { acceptDns = it; saveGlobalPref("accept_dns", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem(stringResource(R.string.settings_force_bg_title), stringResource(R.string.settings_force_bg_desc), Icons.Default.BatteryFull, forceBg) { forceBg = it; saveGlobalPref("force_bg", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem(stringResource(R.string.settings_detailed_logs_title), stringResource(R.string.settings_detailed_logs_desc), Icons.Default.BugReport, detailedLogs) { detailedLogs = it; saveGlobalPref("detailed_logs", it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(stringResource(R.string.settings_extra_args_title), extraArgs, Icons.Default.Code, stringResource(R.string.settings_extra_args_placeholder)) { extraArgs = it; saveGlobalPref("extra_args_raw", it) }
                            }
                        }

                        3 -> { // TAB 3: Account Profile & Advanced
                            SettingsCard(title = stringResource(R.string.settings_sect_account_format, activeAccount.name)) {
                                SettingsEditItem(stringResource(R.string.settings_login_server_title), loginServer, Icons.Default.Cloud, placeholder = stringResource(R.string.settings_login_server_placeholder)) { loginServer = it; saveProfilePref("login_server", it) }
                                SettingsEditItem(stringResource(R.string.settings_auth_key_title), authKey, Icons.Default.VpnKey) { authKey = it; saveProfilePref("authkey", it) }
                                SettingsEditItem(stringResource(R.string.settings_hostname_title), hostname, Icons.Default.Badge, onAction = { android.os.Build.MODEL.replace(" ", "-").lowercase() }, actionIcon = Icons.Default.AutoFixHigh) { hostname = it; saveProfilePref("hostname", it) }
                                SettingsExitNodeItem(stringResource(R.string.settings_exit_node_title), exitNodeIp, Icons.Default.Input) { id, ip ->
                                    exitNodeIp = ip
                                    saveProfilePref("exit_node_ip", ip, triggerService = false)
                                    saveProfilePref("exit_node_id", id, triggerService = false)
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_web)) {
                                SettingsSwitchItem(stringResource(R.string.settings_web_enable_title), stringResource(R.string.settings_web_enable_desc), Icons.Default.Web, enableWebUI) { enableWebUI = it; saveProfilePref("enable_webui", it) }
                                if (enableWebUI) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                    SettingsEditItem(stringResource(R.string.settings_web_address_title), webUIAddr, Icons.Default.Link) { webUIAddr = it; saveProfilePref("webui_addr", it) }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_admin_api)) {
                                SettingsEditItem(stringResource(R.string.settings_admin_tailnet_title), adminApiTailnet, Icons.Default.CloudQueue, placeholder = stringResource(R.string.settings_admin_tailnet_placeholder)) { 
                                    val oldTailnet = adminApiTailnet
                                    adminApiTailnet = it
                                    saveProfilePref("last_known_tailnet", it, triggerService = false)
                                    if (it.isNotEmpty() && oldTailnet.isNotEmpty() && oldTailnet != it) {
                                        val tok = globalApiPrefs.getString(oldTailnet, "") ?: ""
                                        if (tok.isNotEmpty()) {
                                            globalApiPrefs.edit().putString(it, tok).apply()
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(stringResource(R.string.settings_admin_token_title), adminApiToken, Icons.Default.VpnKey, placeholder = stringResource(R.string.settings_admin_token_placeholder)) { 
                                    adminApiToken = it
                                    if (adminApiTailnet.isNotEmpty()) {
                                        globalApiPrefs.edit().putString(adminApiTailnet, it).apply()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.settings_admin_tailnet_missing), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_adv_profile)) {
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
                                        Text(stringResource(R.string.settings_backup_account), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                    OutlinedButton(
                                        onClick = { restoreLauncher.launch("application/json") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.SettingsBackupRestore, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.settings_restore_account), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                                    Text(stringResource(R.string.settings_logout_reset_title))
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
            title = { Text(stringResource(R.string.settings_backup_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_backup_password_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(stringResource(R.string.settings_password_label)) },
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
                            Toast.makeText(context, context.getString(R.string.settings_password_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(stringResource(R.string.settings_backup_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackupPasswordDialog = false }) { Text(stringResource(R.string.action_cancel)) }
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
            title = { Text(stringResource(R.string.settings_restore_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_restore_password_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(stringResource(R.string.settings_password_label)) },
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
                            Toast.makeText(context, context.getString(R.string.settings_password_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(stringResource(R.string.settings_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showRestorePasswordDialog = false
                    pendingRestoreUri = null
                }) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(stringResource(R.string.settings_control_proxy_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_control_proxy_enable), Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_control_proxy_type), Modifier.weight(1f))
                    FilterChip(
                        selected = type == "SOCKS5",
                        onClick = { type = "SOCKS5" },
                        label = { Text(stringResource(R.string.settings_proxy_socks5)) }
                    )
                    FilterChip(
                        selected = type == "HTTP",
                        onClick = { type = "HTTP" },
                        label = { Text(stringResource(R.string.settings_proxy_http)) }
                    )
                }
                Spacer(Modifier.height(16.dp))
                
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.settings_control_proxy_host)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.settings_control_proxy_port)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (type == "HTTP") "8080" else "1080") })
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.settings_control_proxy_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.settings_control_proxy_password)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
            }) { Text(stringResource(R.string.settings_proxy_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
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
                        label = { if (placeholder.isNotEmpty()) Text(stringResource(R.string.settings_field_example, placeholder)) },
                        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                        trailingIcon = if (onAction != null && actionIcon != null) {
                            { IconButton(onClick = { text = onAction() }) { Icon(actionIcon, null) } }
                        } else null
                    )
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_suggested), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            confirmButton = { Button(onClick = { onSave(text); showDialog = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
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
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
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
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_local_api_error_format, res), android.widget.Toast.LENGTH_SHORT).show()
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
                Text(stringResource(R.string.settings_exit_node_select), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (isLoading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (exitNodes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { 
                        Text(stringResource(R.string.settings_exit_node_empty), color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.settings_none)) },
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
