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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.ui.theme.findActivity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import appctr.Appctr
import java.net.URLEncoder

class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

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
    val tabs = listOf(
        Pair(stringResource(R.string.settings_tab_app), Icons.Default.Palette),
        Pair(stringResource(R.string.settings_tab_root), Icons.Default.Security),
        Pair(stringResource(R.string.settings_tab_network), Icons.Default.Language),
        Pair(stringResource(R.string.settings_tab_core), Icons.Default.Tune),
        Pair(stringResource(R.string.settings_tab_byedpi), Icons.Default.Shield),
        Pair(stringResource(R.string.settings_tab_profile), Icons.Default.AccountCircle)
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })

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
    var loginServer by remember { mutableStateOf(profilePrefs.getString("login_server", "") ?: "") }
    
    var autoRefresh by remember { mutableStateOf(GlobalSettings.getBoolean(context, "auto_refresh", false)) }
    var acceptRoutes by remember { mutableStateOf(GlobalSettings.getBoolean(context, "accept_routes", false)) }
    var acceptDns by remember { mutableStateOf(GlobalSettings.getBoolean(context, "accept_dns", true)) }
    var forceBg by remember { mutableStateOf(GlobalSettings.getBoolean(context, "force_bg", false)) }
    var detailedLogs by remember { mutableStateOf(GlobalSettings.getBoolean(context, "detailed_logs", false)) }
    var extraArgs by remember { mutableStateOf(GlobalSettings.getString(context, "extra_args_raw", "")) }

    // TUN Mode State
    var tunModeEnabled by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    var tunFullTunnel by remember { mutableStateOf(GlobalSettings.isTunFullTunnel(context)) }
    var tunExcludedCIDRs by remember { mutableStateOf(GlobalSettings.getTunExcludedCIDRs(context)) }
    var tunExcludedApps by remember { mutableStateOf(GlobalSettings.getTunExcludedApps(context)) }
    var tunAddress by remember { mutableStateOf(GlobalSettings.getTunAddress(context)) }
    var tunIpv6Enabled by remember { mutableStateOf(GlobalSettings.isTunIpv6Enabled(context)) }

    LaunchedEffect(Unit) {
        tunExcludedApps = GlobalSettings.getTunExcludedApps(context)
    }
    val excludedAppsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tunExcludedApps = GlobalSettings.getTunExcludedApps(context)
    }

    // Profile Settings
    var authKey by remember { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }
    var hostname by remember { mutableStateOf(profilePrefs.getString("hostname", "") ?: "") }
    var exitNodeIp by remember { mutableStateOf(profilePrefs.getString("exit_node_ip", "") ?: "") }
    var exitNodeId by remember { mutableStateOf(profilePrefs.getString("exit_node_id", "") ?: "") }
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
                        @Suppress("UNCHECKED_CAST")
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
                        profilePrefs.edit().putBoolean("was_logged_in", false).apply()
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
            LaunchedEffect(pagerState.currentPage) {
                listState.animateScrollToItem(pagerState.currentPage)
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
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        label = { Text(title) },
                        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    when (page) {
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
                                var currentLang by remember { mutableStateOf(GlobalSettings.getString(context, "app_locale", "sys")) }
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
                                                    currentLang = id
                                                    GlobalSettings.setString(context, "app_locale", id)
                                                    val localeList = if (id == "sys") {
                                                        LocaleListCompat.getEmptyLocaleList()
                                                    } else {
                                                        LocaleListCompat.forLanguageTags(id)
                                                    }
                                                    AppCompatDelegate.setApplicationLocales(localeList)
                                                    context.findActivity()?.recreate()
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
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsClickableItem(
                                     stringResource(R.string.settings_show_onboarding),
                                     stringResource(R.string.settings_show_onboarding_desc),
                                     Icons.Default.Info
                                ) {
                                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("first_start_done", false)
                                        .apply()
                                    context.startActivity(Intent(context, FirstStartActivity::class.java))
                                    context.findActivity()?.finish()
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            var automationEnabled by remember { mutableStateOf(GlobalSettings.isAutomationEnabled(context)) }
                            var automationSecret by remember { mutableStateOf(GlobalSettings.getAutomationSecret(context)) }

                            SettingsCard(title = "Tasker & Automation") {
                                SettingsSwitchItem(
                                    title = "Allow External Automation",
                                    subtitle = "Enable background control via Broadcast Intents (Tasker, MacroDroid, ADB)",
                                    icon = Icons.Default.SmartButton,
                                    checked = automationEnabled
                                ) {
                                    automationEnabled = it
                                    GlobalSettings.setAutomationEnabled(context, it)
                                }

                                if (automationEnabled) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                    OutlinedTextField(
                                        value = automationSecret,
                                        onValueChange = {
                                            automationSecret = it
                                            GlobalSettings.setAutomationSecret(context, it)
                                        },
                                        label = { Text("Security Secret Token (Optional)") },
                                        placeholder = { Text("Leave empty to disable token authentication") },
                                        leadingIcon = { Icon(Icons.Default.Key, null) },
                                        trailingIcon = {
                                            if (automationSecret.isNotEmpty()) {
                                                IconButton(onClick = {
                                                    automationSecret = ""
                                                    GlobalSettings.setAutomationSecret(context, "")
                                                }) {
                                                    Icon(Icons.Default.Clear, null)
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = if (automationSecret.isEmpty()) "No secret token set. Any automation app can send intents." else "Token active. Intents must include extra: secret=\"$automationSecret\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (automationSecret.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        1 -> { // TAB 1: Root Mode & System Service (Dedicated Tab)
                            var rootModeEnabled by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
                            var serviceScriptInstalled by remember { mutableStateOf(RootUtils.isServiceScriptInstalled()) }
                            var cliInstalled by remember { mutableStateOf(RootUtils.isTailscaleCliInstalled()) }
                            var killDaemonOnStop by remember { mutableStateOf(GlobalSettings.shouldKillRootDaemonOnStop(context)) }
                            var showRootWarningDialog by remember { mutableStateOf(false) }

                            if (showRootWarningDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRootWarningDialog = false },
                                    title = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.settings_root_warning_dialog_title),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    },
                                    text = {
                                        Text(
                                            text = stringResource(R.string.settings_root_warning_dialog_body),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                showRootWarningDialog = false
                                                if (RootUtils.isRootAvailable()) {
                                                    rootModeEnabled = true
                                                    GlobalSettings.setRootModeEnabled(context, true)
                                                    Toast.makeText(context, "Root Mode enabled", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Root access (su) not granted or unavailable", Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text(stringResource(R.string.settings_root_warning_dialog_confirm))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRootWarningDialog = false }) {
                                            Text(stringResource(R.string.settings_root_warning_dialog_cancel))
                                        }
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "⚠️ ЭКСПЕРИМЕНТАЛЬНЫЙ РЕЖИМ\nRoot-режим находится в стадии разработки. Автор bropines еще не все проверил и отлаживает этот режим.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            SettingsCard(title = stringResource(R.string.settings_root_sect_title)) {
                                SettingsSwitchItem(
                                    title = stringResource(R.string.settings_root_enable_title),
                                    subtitle = stringResource(R.string.settings_root_enable_desc),
                                    icon = Icons.Default.Security,
                                    checked = rootModeEnabled
                                ) {
                                    if (it) {
                                        showRootWarningDialog = true
                                    } else {
                                        rootModeEnabled = false
                                        GlobalSettings.setRootModeEnabled(context, false)
                                        Toast.makeText(context, "Root Mode disabled", Toast.LENGTH_SHORT).show()
                                    }
                                }

                                if (rootModeEnabled) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                                    SettingsSwitchItem(
                                        title = stringResource(R.string.settings_root_service_title),
                                        subtitle = stringResource(R.string.settings_root_service_desc),
                                        icon = Icons.Default.Build,
                                        checked = serviceScriptInstalled
                                    ) {
                                        val success = RootUtils.setServiceScriptInstalled(context, it)
                                        if (success) {
                                            serviceScriptInstalled = it
                                            Toast.makeText(context, if (it) "Service script installed to service.d" else "Service script removed", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to manage service.d script", Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                                    SettingsSwitchItem(
                                        title = stringResource(R.string.settings_root_cli_title),
                                        subtitle = stringResource(R.string.settings_root_cli_desc),
                                        icon = Icons.Default.Terminal,
                                        checked = cliInstalled
                                    ) {
                                        val success = RootUtils.setTailscaleCliInstalled(context, it)
                                        if (success) {
                                            cliInstalled = it
                                            Toast.makeText(context, if (it) "CLI wrapper installed to /system/bin/tailscale" else "CLI wrapper removed", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to manage CLI wrapper", Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                                    SettingsSwitchItem(
                                        title = stringResource(R.string.settings_root_kill_daemon_title),
                                        subtitle = stringResource(R.string.settings_root_kill_daemon_desc),
                                        icon = Icons.Default.Dangerous,
                                        checked = killDaemonOnStop
                                    ) {
                                        killDaemonOnStop = it
                                        GlobalSettings.setKillRootDaemonOnStop(context, it)
                                    }
                                }
                            }

                            if (rootModeEnabled) {
                                Spacer(Modifier.height(12.dp))
                                SettingsCard(title = stringResource(R.string.settings_root_info_title)) {
                                    val socketPath = "${context.filesDir.absolutePath}/tailscaled.sock"
                                    val logsDir = File(context.filesDir.parentFile ?: context.filesDir, "logs").absolutePath
                                    val logPath = "$logsDir/tailscaled.log"
                                    val socketExists = File(socketPath).exists()

                                    Text("Socket Path:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(socketPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    
                                    Spacer(Modifier.height(8.dp))
                                    Text("Log File:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(logPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                    Spacer(Modifier.height(8.dp))
                                    Text("Socket Status:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        if (socketExists) "Connected & Active (srwxrwxrwx)" else "Disconnected / Socket file missing",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (socketExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        2 -> { // TAB 2: Network & Proxy
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
                                SettingsEditItem(stringResource(R.string.settings_http_address_title), httpProxy, Icons.Default.Http, placeholder = "127.0.0.1:8080") { httpProxy = it; saveGlobalPref("httpproxy", it) }
                                Text(
                                    text = stringResource(R.string.settings_http_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_control_proxy)) {
                                val isByeDpi = GlobalSettings.isCPByeDpiEnabled(context)
                                val statusText = if (isProxyEnabled) {
                                    if (isByeDpi) "ByeDPI (DPI Bypass)" else stringResource(R.string.settings_control_proxy_enabled_format, GlobalSettings.getCPField(context, "type"))
                                } else {
                                    stringResource(R.string.settings_control_proxy_disabled)
                                }
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
                                    icon = Icons.AutoMirrored.Filled.Label,
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

                        3 -> { // TAB 3: TUN Mode & Core Settings
                            val isRootModeActive = GlobalSettings.isRootModeEnabled(context)

                            SettingsCard(title = stringResource(R.string.settings_sect_tun_mode)) {
                                SettingsSwitchItem(
                                    title = stringResource(R.string.settings_tun_enable_title),
                                    subtitle = if (isRootModeActive) stringResource(R.string.settings_root_disabled_tun_note) else stringResource(R.string.settings_tun_enable_desc),
                                    icon = Icons.Default.VpnLock,
                                    checked = if (isRootModeActive) false else tunModeEnabled,
                                    enabled = !isRootModeActive
                                ) {
                                    tunModeEnabled = it
                                    saveGlobalPref("tun_mode_enabled", it)
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsSwitchItem(
                                    title = stringResource(R.string.settings_tun_ipv6_title),
                                    subtitle = if (isRootModeActive) stringResource(R.string.settings_root_disabled_general_note) else stringResource(R.string.settings_tun_ipv6_desc),
                                    icon = Icons.Default.Language,
                                    checked = tunIpv6Enabled,
                                    enabled = !isRootModeActive
                                ) {
                                    tunIpv6Enabled = it
                                    saveGlobalPref("tun_ipv6_enabled", it)
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(
                                    title = stringResource(R.string.settings_tun_address_title),
                                    value = tunAddress,
                                    icon = Icons.Default.Settings,
                                    placeholder = "10.0.0.1/8",
                                    description = stringResource(R.string.settings_tun_address_desc)
                                ) {
                                    tunAddress = it
                                    saveGlobalPref("tun_address", it)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsEditItem(
                                    title = stringResource(R.string.settings_tun_excluded_cidrs_title),
                                    value = tunExcludedCIDRs,
                                    icon = Icons.Default.Block,
                                    placeholder = "192.168.0.0/16, 10.0.0.0/8",
                                    description = stringResource(R.string.settings_tun_excluded_cidrs_desc)
                                ) {
                                    tunExcludedCIDRs = it
                                    saveGlobalPref("tun_excluded_cidrs", it)
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                SettingsClickableItem(
                                    title = stringResource(R.string.settings_tun_excluded_apps_title),
                                    subtitle = if (isRootModeActive) stringResource(R.string.settings_root_disabled_general_note) else stringResource(R.string.settings_tun_excluded_apps_desc, tunExcludedApps.size),
                                    icon = Icons.Default.Apps,
                                    enabled = !isRootModeActive
                                ) {
                                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_dns_proxy)) {
                                SettingsEditItem(
                                    title = stringResource(R.string.settings_dns_proxy_address_title),
                                    value = dnsProxy,
                                    icon = Icons.Default.Toll,
                                    enabled = !isRootModeActive,
                                    description = if (isRootModeActive) stringResource(R.string.settings_root_disabled_general_note) else ""
                                ) { dnsProxy = it; saveGlobalPref("dns_proxy", it) }
                            }

                            Spacer(Modifier.height(12.dp))

                            SettingsCard(title = stringResource(R.string.settings_sect_fallback_dns)) {
                                SettingsEditItem(stringResource(R.string.settings_dns_fallbacks_title), dnsFallbacks, Icons.AutoMirrored.Filled.List, placeholder = stringResource(R.string.settings_dns_fallbacks_placeholder)) { dnsFallbacks = it; saveGlobalPref("dns_fallbacks", it) }
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

                        4 -> { // TAB 4: DPI Bypass (ByeByeDPI)
                            var byedpiEnabled by remember { mutableStateOf(GlobalSettings.isCPByeDpiEnabled(context)) }
                            var byedpiFlags by remember { mutableStateOf(GlobalSettings.getCPByeDpiFlags(context)) }
                            var byedpiIpv6Disabled by remember { mutableStateOf(GlobalSettings.isCPByeDpiIpv6Disabled(context)) }
                            val activeBbdAddr = ByeDpiProxy.activeAddress
                            
                            SettingsCard(title = stringResource(R.string.settings_tab_byedpi)) {
                                Text(
                                    text = stringResource(R.string.settings_byedpi_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.settings_byedpi_warning_override),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                
                                SettingsSwitchItem(
                                    title = stringResource(R.string.settings_byedpi_enable),
                                    subtitle = if (byedpiEnabled) {
                                        if (activeBbdAddr != null) {
                                            stringResource(R.string.settings_byedpi_status_active, "${activeBbdAddr.first}:${activeBbdAddr.second}")
                                        } else {
                                            stringResource(R.string.settings_byedpi_status_stopped)
                                        }
                                    } else {
                                        stringResource(R.string.settings_byedpi_status_stopped)
                                    },
                                    icon = Icons.Default.Shield,
                                    checked = byedpiEnabled
                                ) {
                                    byedpiEnabled = it
                                    GlobalSettings.setCPByeDpiEnabled(context, it)
                                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
                                }
                                
                                Spacer(Modifier.height(12.dp))
                                
                                SettingsEditItem(
                                    title = stringResource(R.string.settings_byedpi_flags),
                                    value = byedpiFlags,
                                    icon = Icons.Default.Code,
                                    placeholder = "-o1 -a1 -r-5+se"
                                ) {
                                    byedpiFlags = it
                                    GlobalSettings.setCPByeDpiFlags(context, it)
                                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
                                }

                                Spacer(Modifier.height(12.dp))

                                SettingsSwitchItem(
                                    title = stringResource(R.string.settings_byedpi_ipv6_disabled),
                                    subtitle = stringResource(R.string.settings_byedpi_ipv6_disabled_desc),
                                    icon = Icons.Default.Dns,
                                    checked = byedpiIpv6Disabled
                                ) {
                                    byedpiIpv6Disabled = it
                                    GlobalSettings.setCPByeDpiIpv6Disabled(context, it)
                                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
                                }
                            }
                        }

                        5 -> { // TAB 5: Account Profile & Advanced
                            SettingsCard(title = stringResource(R.string.settings_sect_account_format, activeAccount.name)) {
                                SettingsEditItem(stringResource(R.string.settings_login_server_title), loginServer, Icons.Default.Cloud, placeholder = stringResource(R.string.settings_login_server_placeholder)) { 
                                    if (loginServer != it) {
                                        loginServer = it
                                        saveProfilePref("was_logged_in", false, triggerService = false)
                                        saveProfilePref("login_server", it)
                                    }
                                }
                                SettingsEditItem(stringResource(R.string.settings_auth_key_title), authKey, Icons.Default.VpnKey) { authKey = it; saveProfilePref("authkey", it) }
                                SettingsEditItem(stringResource(R.string.settings_hostname_title), hostname, Icons.Default.Badge, onAction = { android.os.Build.MODEL.replace(" ", "-").lowercase() }, actionIcon = Icons.Default.AutoFixHigh) { hostname = it; saveProfilePref("hostname", it) }
                                 @Suppress("UNCHECKED_CAST")
                                 SettingsExitNodeItem(stringResource(R.string.settings_exit_node_title), exitNodeId, exitNodeIp, Icons.AutoMirrored.Filled.Input) { id, ip ->
                                     exitNodeId = id
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlProxyDialog(onDismiss: () -> Unit, onApply: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(GlobalSettings.isCPProxyEnabled(context)) }
    var type by remember { mutableStateOf(GlobalSettings.getCPField(context, "type", "SOCKS5")) }
    var host by remember { mutableStateOf(GlobalSettings.getCPField(context, "host")) }
    var port by remember { mutableStateOf(GlobalSettings.getCPField(context, "port")) }
    var user by remember { mutableStateOf(GlobalSettings.getCPField(context, "user")) }
    var pass by remember { mutableStateOf(GlobalSettings.getCPField(context, "pass")) }

    var importUri by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(GlobalSettings.getCPPresets(context)) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

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

                if (enabled) {
                    // Import Section
                    OutlinedTextField(
                        value = importUri,
                        onValueChange = { importUri = it },
                        label = { Text(stringResource(R.string.settings_proxy_import)) },
                        placeholder = { Text("socks5://user:pass@host:port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboardManager.primaryClip
                                val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                                    clipData.getItemAt(0).text?.toString()?.trim()
                                } else null

                                val linkToParse = if (!clipboardText.isNullOrEmpty()) {
                                    importUri = clipboardText
                                    clipboardText
                                } else {
                                    importUri.trim()
                                }

                                if (linkToParse.isNotEmpty()) {
                                    val parsed = GlobalSettings.parseProxyUri(linkToParse)
                                    if (parsed != null) {
                                        type = parsed["type"] ?: "SOCKS5"
                                        host = parsed["host"] ?: ""
                                        port = parsed["port"] ?: ""
                                        user = parsed["user"] ?: ""
                                        pass = parsed["pass"] ?: ""
                                        importUri = ""
                                        Toast.makeText(context, "Parsed successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.settings_proxy_import_error), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Clipboard and field are empty", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste and parse")
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InputChip(
                            selected = false,
                            onClick = {
                                if (host.isNotEmpty() && port.isNotEmpty()) {
                                    presetName = "$host:$port"
                                    showSavePresetDialog = true
                                } else {
                                    Toast.makeText(context, context.getString(R.string.settings_proxy_preset_fill_fields_error), Toast.LENGTH_SHORT).show()
                                }
                            },
                            label = { Text("+ Save") },
                            leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                        )

                        presets.forEach { preset ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    type = preset.type
                                    host = preset.host
                                    port = preset.port
                                    user = preset.user
                                    pass = preset.pass
                                },
                                label = { Text(preset.name) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val updated = presets.filter { it != preset }
                                            presets = updated
                                            GlobalSettings.saveCPPresets(context, updated)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp))
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        FilterChip(
                            selected = type == "HTTPS",
                            onClick = { type = "HTTPS" },
                            label = { Text(stringResource(R.string.settings_proxy_type_https)) }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.settings_control_proxy_host)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.settings_control_proxy_port)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (type == "SOCKS5") "1080" else "8080") })
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.settings_control_proxy_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.settings_control_proxy_password)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    
                    Spacer(Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = {
                            val uri = GlobalSettings.buildProxyUri(type, host, port, user, pass)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Proxy URI", uri))
                            Toast.makeText(context, context.getString(R.string.settings_proxy_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_proxy_copy_btn))
                    }
                }
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

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text(stringResource(R.string.settings_proxy_preset_save_title)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.settings_proxy_preset_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameToSave = presetName.trim().ifEmpty { "$host:$port" }
                        val newPreset = GlobalSettings.ProxyPreset(
                            name = nameToSave,
                            type = type,
                            host = host,
                            port = port,
                            user = user,
                            pass = pass
                        )
                        val updated = presets + newPreset
                        presets = updated
                        GlobalSettings.saveCPPresets(context, updated)
                        showSavePresetDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_proxy_preset_save_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun SettingsClickableItem(
    title: String, 
    subtitle: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() }, 
        shape = RoundedCornerShape(12.dp), 
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.1f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String, 
    subtitle: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    checked: Boolean, 
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { if (enabled) onCheckedChange(!checked) }, 
        shape = RoundedCornerShape(12.dp), 
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.1f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
            trailingContent = { Switch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null, enabled = enabled) },
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
    enabled: Boolean = true,
    suggestions: List<String> = emptyList(),
    onAction: (() -> String)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(showDialog) { if (showDialog) text = value }
    Surface(
        onClick = { if (enabled) showDialog = true }, 
        shape = RoundedCornerShape(12.dp), 
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.15f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
            supportingContent = { 
                Text(
                    text = if (!enabled && description.isNotEmpty()) description else if (value.isEmpty()) {
                        if (description.isNotEmpty()) description else (placeholder.ifEmpty { "Not set" })
                    } else value, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                ) 
            },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) },
            trailingContent = if (!enabled) { { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) } } else null,
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
    currentId: String,
    currentIp: String, 
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
            if (GlobalSettings.isTunModeEnabled(context)) {
                context.startService(Intent(context, TunVpnService::class.java).apply {
                    action = TunVpnService.ACTION_START
                })
            }
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
            supportingContent = { Text(if (currentIp.isEmpty()) "None" else currentIp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }

    if (showDialog) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.85f).dp

        ModalBottomSheet(onDismissRequest = { showDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .navigationBarsPadding()
            ) {
                Text(
                    stringResource(R.string.settings_exit_node_select),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isLoading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (exitNodes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { 
                        Text(stringResource(R.string.settings_exit_node_empty), color = MaterialTheme.colorScheme.outline)
                    }
                } else {
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
                                    val isSelected = currentIp.isEmpty()
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode("", ""); showDialog = false },
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
                                                    stringResource(R.string.settings_none),
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

                                items(exitNodes.size) { i ->
                                    val node = exitNodes[i]
                                    val isSelected = node.id == currentId || node.getPrimaryIp() == currentIp
                                    val (osIcon, osColor) = getOsVisuals(node.os).let { (icon, color) ->
                                        if (icon == Icons.Default.Devices) Icons.Default.VpnKey to MaterialTheme.colorScheme.primary
                                        else icon to color
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode(node.id ?: "", node.getPrimaryIp()); showDialog = false },
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
