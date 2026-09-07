package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import appctr.Appctr
import java.net.URLEncoder

/**
 * One row of the settings hub. [id] is what `openSection` stores, so it is a
 * stable string and must survive a `recreate()` — never reorder-sensitive.
 */
private data class SettingsCategory(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/** The 11 categories of the target structure, in their A..K order. */
private val settingsCategories = listOf(
    SettingsCategory("appearance", R.string.settings_cat_appearance, R.string.settings_cat_appearance_desc, Icons.Default.Palette),
    SettingsCategory("account", R.string.settings_cat_account, R.string.settings_cat_account_desc, Icons.Default.AccountCircle),
    SettingsCategory("tunnel", R.string.settings_cat_tunnel, R.string.settings_cat_tunnel_desc, Icons.Default.VpnLock),
    SettingsCategory("proxies", R.string.settings_cat_proxies, R.string.settings_cat_proxies_desc, Icons.Default.Lan),
    SettingsCategory("dns", R.string.settings_cat_dns, R.string.settings_cat_dns_desc, Icons.Default.Dns),
    SettingsCategory("bypass", R.string.settings_cat_bypass, R.string.settings_cat_bypass_desc, Icons.Default.Shield),
    SettingsCategory("sharing", R.string.settings_cat_sharing, R.string.settings_cat_sharing_desc, Icons.Default.Share),
    SettingsCategory("background", R.string.settings_cat_background, R.string.settings_cat_background_desc, Icons.Default.Bolt),
    SettingsCategory("backup", R.string.settings_cat_backup, R.string.settings_cat_backup_desc, Icons.Default.Backup),
    SettingsCategory("automation", R.string.settings_cat_automation, R.string.settings_cat_automation_desc, Icons.Default.SmartButton),
    SettingsCategory("diagnostics", R.string.settings_cat_diagnostics, R.string.settings_cat_diagnostics_desc, Icons.Default.BugReport)
)

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

/** Flattens a SharedPreferences snapshot into the JSON object an export carries. */
private fun prefsToJson(values: Map<String, *>): JsonObject = buildJsonObject {
    for ((k, v) in values) {
        when (v) {
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Float -> put(k, v)
            is Set<*> -> putJsonArray(k) { v.forEach { add(it.toString()) } }
            null -> {}
            else -> put(k, v.toString())
        }
    }
}

/**
 * Reads back the String/Boolean entries of an exported preference object.
 *
 * Everything else is skipped: the global preference file only ever holds those
 * two types, and a value of any other shape in the file is not something this
 * build wrote.
 */
private fun jsonToPrefValues(obj: JsonObject): Map<String, Any> {
    val values = mutableMapOf<String, Any>()
    obj.forEach { (key, element) ->
        val prim = element as? JsonPrimitive ?: return@forEach
        when {
            // A quoted value is a string even if it reads like a bool.
            prim.isString -> values[key] = prim.content
            prim.booleanOrNull != null -> values[key] = prim.booleanOrNull!!
        }
    }
    return values
}

/**
 * Cryptographically random token for the automation secret. URL-safe alphabet
 * without the look-alike characters (0/O, 1/l/I) so it survives being retyped.
 */
fun generateSecureToken(length: Int = 32): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = java.security.SecureRandom()
    return buildString(length) {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

/** Reduces a device name to what a DNS label may contain. */
fun sanitizeHostnameInput(raw: String): String =
    raw.trim()
        .replace(" ", "-")
        .lowercase()
        .replace(Regex("[^a-z0-9-]"), "")
        .trim('-')
        .take(63)

fun generateRandomLoopbackAddress(): String {
    val x = (1..254).random()
    val y = (1..254).random()
    val z = (1..254).random()
    val port = (1024..65535).random()
    return "127.$x.$y.$z:$port"
}

data class PresetItem(val id: String, val color: Color, val name: String)

/**
 * Where a settings list was left. Deliberately not snapshot state: it is read once, when a
 * list is composed, and written on every scrolled frame — as state it would recompose the
 * very list that is scrolling. The settings surface is composed more than once at a time
 * (the open section, the hub the back gesture uncovers underneath it, and for an instant
 * both hubs as the pop lands), so each instance restores the position from here rather than
 * sharing one LazyListState between two live LazyColumns.
 */
private class ScrollAnchor(var index: Int = 0, var offset: Int = 0)

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
    
    // Two-level navigation: null == the category hub, otherwise the id of the
    // open section (see settingsCategories). rememberSaveable, because the App
    // Language row calls recreate() and a plain remember would drop the user
    // back to the hub mid-edit.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    // Scroll positions of the two levels, kept outside the surfaces that draw them —
    // see ScrollAnchor. Without this the hub the finger uncovers during a back gesture is
    // a fresh LazyColumn at the top, and so is the one the pop lands on: a category low in
    // the list came back to a screen scrolled somewhere the user had never been.
    val hubAnchor = remember { ScrollAnchor() }
    val sectionAnchors = remember { mutableMapOf<String, Int>() }

    // Global Settings
    var taildropRootUri by remember { mutableStateOf(GlobalSettings.getTaildropRootUri(context)) }
    var autoStart by remember { mutableStateOf(GlobalSettings.isAutoStartEnabled(context)) }
    var autoReconnect by remember { mutableStateOf(GlobalSettings.isAutoReconnectEnabled(context)) }
    var autoReconnectAttempts by remember { mutableStateOf(GlobalSettings.getAutoReconnectAttempts(context).toString()) }
    var serviceWatchdog by remember { mutableStateOf(GlobalSettings.isServiceWatchdogEnabled(context)) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var isProxyEnabled by remember { mutableStateOf(GlobalSettings.isCPProxyEnabled(context)) }
    
    var socks5 by remember { mutableStateOf(GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")) }
    var socks5User by remember { mutableStateOf(GlobalSettings.getString(context, "socks5_user", "")) }
    var socks5Pass by remember { mutableStateOf(GlobalSettings.getString(context, "socks5_pass", "")) }
    var httpProxy by remember { mutableStateOf(GlobalSettings.getString(context, "httpproxy", "")) }
    var dnsProxy by remember { mutableStateOf(GlobalSettings.getString(context, "dns_proxy", "127.0.0.1:1053")) }
    var lanAccessEnabled by remember { mutableStateOf(GlobalSettings.isLanAccessEnabled(context)) }
    var dnsFallbacks by remember { mutableStateOf(GlobalSettings.getString(context, "dns_fallbacks", "8.8.8.8:53,1.1.1.1:53")) }
    var dohUrl by remember { mutableStateOf(GlobalSettings.getString(context, "doh_url", "https://1.1.1.1/dns-query")) }
    var loginServer by remember { mutableStateOf(profilePrefs.getString("login_server", "") ?: "") }
    
    var acceptRoutes by remember { mutableStateOf(GlobalSettings.getBoolean(context, "accept_routes", false)) }
    var acceptDns by remember { mutableStateOf(GlobalSettings.getBoolean(context, "accept_dns", true)) }
    var forceBg by remember { mutableStateOf(GlobalSettings.getBoolean(context, "force_bg", false)) }
    var detailedLogs by remember { mutableStateOf(GlobalSettings.getBoolean(context, "detailed_logs", false)) }
    var extraArgs by remember { mutableStateOf(GlobalSettings.getString(context, "extra_args_raw", "")) }

    // TUN Mode State
    var tunModeEnabled by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
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
    var advertiseExitNode by remember { mutableStateOf(profilePrefs.getBoolean("advertise_exit_node", false)) }
    var appliedTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableNetworkTags by remember { mutableStateOf<List<String>>(emptyList()) }

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
                    val backupObj = kotlinx.serialization.json.buildJsonObject {
                        put("manifest", AppJson.encodeToJsonElement(BackupFormat.current(context)))
                        put("account", AppJson.encodeToJsonElement(activeAccount))
                        put("settings", prefsToJson(profilePrefs.all))
                        // The app-wide settings (proxies, DPI bypass, TUN, Root
                        // Mode, recovery, appearance) used to survive only in the
                        // encrypted full backup. Secrets and device-bound entries
                        // stay out — see GlobalSettings.EXPORTED_KEYS.
                        put("global", prefsToJson(GlobalSettings.exportable(context)))
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(backupObj.toString().toByteArray()) }
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
                        // AppJson throws on blank/malformed where Gson returned null;
                        // either way the outer catch turns it into the restore-failed toast.
                        val backupData = AppJson.decodeFromString<JsonObject>(jsonString)

                        // Same provenance rule as the full backup: never apply an
                        // export produced by a build newer than this one.
                        val manifest = (backupData["manifest"] as? JsonObject)?.let {
                            BackupFormat.fromJson(AppJson.encodeToString(it))
                        }
                        val refusal: String? = when (val c = BackupFormat.check(context, manifest)) {
                            is BackupFormat.Compatibility.Ok -> null
                            is BackupFormat.Compatibility.Legacy -> null
                            is BackupFormat.Compatibility.FormatTooNew ->
                                context.getString(R.string.settings_restore_format_too_new, c.backupVersion, c.supported)
                            is BackupFormat.Compatibility.AppTooNew ->
                                context.getString(R.string.settings_restore_app_too_new, c.backupVersion, c.installedVersion)
                            is BackupFormat.Compatibility.WrongPackage ->
                                context.getString(R.string.settings_restore_wrong_package, c.backupPackage)
                        }
                        if (refusal != null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }

                        val settings = backupData["settings"] as? JsonObject
                        if (settings != null) {
                            val editor = profilePrefs.edit()
                            settings.forEach { (k, element) ->
                                val prim = element as? JsonPrimitive ?: return@forEach
                                when {
                                    // A quoted value is a string even if it reads like a
                                    // bool/number, so this branch must come first.
                                    prim.isString -> editor.putString(k, prim.content)
                                    prim.booleanOrNull != null -> editor.putBoolean(k, prim.booleanOrNull!!)
                                    // Gson decoded every JSON number to Double and wrote it
                                    // as a float; keep that lossy-but-identical behaviour.
                                    prim.floatOrNull != null -> editor.putFloat(k, prim.floatOrNull!!)
                                }
                            }
                            editor.apply()

                            // Optional section: files written before app-wide
                            // settings were exported simply have no `global`, and
                            // restore exactly as they did. Keys the file omits keep
                            // their current value; keys outside the allow-list are
                            // dropped by GlobalSettings.importValues.
                            (backupData["global"] as? JsonObject)?.let { globals ->
                                GlobalSettings.importValues(context, jsonToPrefValues(globals))
                            }

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
                val tempStatesDir = File(context.cacheDir, "temp_states_backup")
                var useTempStates = false
                try {
                    val statesDir = File(context.filesDir, "states")
                    if (statesDir.exists() && RootUtils.isRootAvailable()) {
                        val uid = context.applicationInfo.uid
                        val cmd = "rm -rf \"${tempStatesDir.absolutePath}\" && " +
                                  "mkdir -p \"${tempStatesDir.absolutePath}\" && " +
                                  "cp -R \"${statesDir.absolutePath}/\"* \"${tempStatesDir.absolutePath}/\" && " +
                                  "chown -R $uid:$uid \"${tempStatesDir.absolutePath}\" && " +
                                  "chmod -R u+rwX \"${tempStatesDir.absolutePath}\""
                        try {
                            val process = Runtime.getRuntime().exec("su")
                            process.outputStream.use { os ->
                                os.write(("$cmd\nexit\n").toByteArray())
                                os.flush()
                            }
                            process.waitFor()
                            if (tempStatesDir.exists() && tempStatesDir.list()?.isNotEmpty() == true) {
                                useTempStates = true
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SettingsActivity", "Failed to copy states using root", e)
                        }
                    }

                    val baos = java.io.ByteArrayOutputStream()
                    java.util.zip.ZipOutputStream(baos).use { zos ->
                        // Written first so a restore can identify the archive
                        // before touching anything.
                        zos.putNextEntry(java.util.zip.ZipEntry(BackupFormat.MANIFEST_ENTRY))
                        zos.write(BackupFormat.toJson(BackupFormat.current(context)).toByteArray())
                        zos.closeEntry()

                        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                        if (prefsDir.exists()) {
                            prefsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = "shared_prefs/${file.name}"
                                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        val targetStatesDir = if (useTempStates) tempStatesDir else statesDir
                        if (targetStatesDir.exists()) {
                            targetStatesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = "files/states/${file.relativeTo(targetStatesDir).path}"
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
                    try {
                        tempStatesDir.deleteRecursively()
                    } catch (e: Exception) {}
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

                // Refuse an archive this build cannot honour before writing a
                // single file: a half-applied restore is worse than none.
                val manifest = BackupFormat.readManifest(decryptedBytes)
                val refusal: String? = when (val c = BackupFormat.check(context, manifest)) {
                    is BackupFormat.Compatibility.Ok -> null
                    is BackupFormat.Compatibility.Legacy -> null
                    is BackupFormat.Compatibility.FormatTooNew ->
                        context.getString(R.string.settings_restore_format_too_new, c.backupVersion, c.supported)
                    is BackupFormat.Compatibility.AppTooNew ->
                        context.getString(R.string.settings_restore_app_too_new, c.backupVersion, c.installedVersion)
                    is BackupFormat.Compatibility.WrongPackage ->
                        context.getString(R.string.settings_restore_wrong_package, c.backupPackage)
                }
                if (refusal != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Prior to restore, if root is available, change ownership of existing states to app
                if (RootUtils.isRootAvailable()) {
                    val uid = context.applicationInfo.uid
                    val statesDir = File(context.filesDir, "states")
                    if (statesDir.exists()) {
                        val cmd = "chown -R $uid:$uid \"${statesDir.absolutePath}\" && " +
                                  "chmod -R u+rwX \"${statesDir.absolutePath}\""
                        try {
                            val process = Runtime.getRuntime().exec("su")
                            process.outputStream.use { os ->
                                os.write(("$cmd\nexit\n").toByteArray())
                                os.flush()
                            }
                            process.waitFor()
                        } catch (e: Exception) {
                            android.util.Log.e("SettingsActivity", "Failed to chown states before restore", e)
                        }
                    }
                }

                // A backup may only restore what a backup contains: preference files,
                // daemon state directories and the sent-files history. Every other
                // entry is refused — before anything is written. The old prefix match
                // accepted any `files/...` path, including `control_proxy.env`, which
                // the Root Mode boot script sourced as root, and `files/../x` escapes.
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile
                val statesDir = File(context.filesDir, "states").canonicalFile
                val filesDir = context.filesDir.canonicalFile
                fun restoreTarget(name: String): File? {
                    if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) return null
                    val (base, target) = when {
                        name == "files/sent_history.json" -> filesDir to File(filesDir, "sent_history.json")
                        name.startsWith("shared_prefs/") && name.endsWith(".xml") && name.count { it == '/' } == 1 ->
                            prefsDir to File(prefsDir, name.removePrefix("shared_prefs/"))
                        name.startsWith("files/states/") ->
                            statesDir to File(statesDir, name.removePrefix("files/states/"))
                        else -> return null
                    }
                    val canonical = target.canonicalFile
                    return canonical.takeIf { it.path.startsWith(base.path + File.separator) }
                }

                val entries = mutableListOf<Pair<String, File>>()
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(decryptedBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name != BackupFormat.MANIFEST_ENTRY) {
                            val target = restoreTarget(entry.name)
                                ?: throw SecurityException("Backup contains a file outside the restorable set: ${entry.name}")
                            entries.add(entry.name to target)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val targets = entries.toMap()
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(decryptedBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val targetFile = targets[entry.name]
                        if (targetFile != null) {
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { fos -> zis.copyTo(fos) }
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
            // A link is meant to be used from somewhere else, so a wildcard bind
            // has to be published as the address that is actually reachable.
            val bind = GlobalSettings.getSocks5BindAddr(context)
            val endpoint = if (NetAddr.isWildcard(bind)) {
                "${NetAddr.lanIpv4() ?: NetAddr.LOOPBACK_V4}:${NetAddr.port(bind) ?: ""}"
            } else {
                bind
            }
            val link = if (encodedUser.isNotEmpty()) {
                "socks5://$encodedUser:$encodedPass@$endpoint#$label"
            } else {
                "socks5://$endpoint#$label"
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
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsLogoutTitle = stringResource(R.string.settings_logout_title)
        val strSettingsLogoutText = stringResource(R.string.settings_logout_text)
        val strSettingsLogoutConfirm = stringResource(R.string.settings_logout_confirm)
        val strActionCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(strSettingsLogoutTitle) },
            text = { Text(strSettingsLogoutText) },
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
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(strSettingsLogoutConfirm) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(strActionCancel) } }
        )
    }

    // ---------------------------------------------------------------------
    // The 11 section detail pages of the target structure, in their A..K
    // order. Every row below is the original row, moved: the labels, the
    // gates and the onChange side effects are the ones the old tabs had.
    // ---------------------------------------------------------------------

    // A. Account & connection
    val sectionAccount: @Composable () -> Unit = {
        // The tag suggestions and the "applied tags" line are the only readers
        // of this query, so it runs when the page opens instead of on every
        // Settings open.
        LaunchedEffect(activeAccount.id) {
            scope.launch(Dispatchers.IO) {
                try {
                    val pJson = Appctr.getStatusFromAPI()
                    if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                        val status = AppJson.decodeFromString<StatusResponse>(pJson)
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

        SettingsCard(title = stringResource(R.string.settings_sect_account_format, activeAccount.name)) {
            SettingsEditItem(stringResource(R.string.settings_login_server_title), loginServer, Icons.Default.Cloud, placeholder = stringResource(R.string.settings_login_server_placeholder)) { 
                if (loginServer != it) {
                    loginServer = it
                    saveProfilePref("was_logged_in", false, triggerService = false)
                    saveProfilePref("login_server", it)
                }
            }
            SettingsEditItem(stringResource(R.string.settings_auth_key_title), authKey, Icons.Default.VpnKey) { authKey = it; saveProfilePref("authkey", it) }
            SettingsEditItem(
                stringResource(R.string.settings_device_name_title),
                hostname,
                Icons.Default.Badge,
                onAction = { sanitizeHostnameInput(android.os.Build.MODEL) },
                actionIcon = Icons.Default.AutoFixHigh
            ) {
                // The device name becomes a DNS label on the tailnet, so
                // whitespace and stray characters are dropped before it is
                // stored rather than being sent to the control plane.
                val clean = sanitizeHostnameInput(it)
                hostname = clean
                saveProfilePref("hostname", clean)
            }
             @Suppress("UNCHECKED_CAST")
             SettingsExitNodeItem(stringResource(R.string.settings_exit_node_title), exitNodeId, exitNodeIp, Icons.AutoMirrored.Filled.Input) { id, ip ->
                 exitNodeId = id
                exitNodeIp = ip
                saveProfilePref("exit_node_ip", ip, triggerService = false)
                saveProfilePref("exit_node_id", id, triggerService = false)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(stringResource(R.string.settings_accept_subnet_routes_title), stringResource(R.string.settings_accept_routes_desc), Icons.Default.Map, acceptRoutes) { acceptRoutes = it; saveGlobalPref("accept_routes", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(
                stringResource(R.string.settings_advertise_exit_node_title),
                stringResource(R.string.settings_advertise_exit_node_desc),
                Icons.Default.Public,
                advertiseExitNode
            ) {
                advertiseExitNode = it
                saveProfilePref("advertise_exit_node", it)
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_service_ad)) {
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
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
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
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_adv_profile)) {
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

    // B. Tunnel mode
    //
    // One three-way selector owns both mode keys. Proxy / VPN (TUN) / Root are
    // mutually exclusive here: `tun_mode_enabled` and `root_mode_enabled` are
    // never written true together. Root keeps the old carry-over — enabling it
    // while TUN was on forces `root_tun_enabled`, so a user who had a tunnel
    // keeps one, now the native kernel interface. Only the rows of the selected
    // mode are composed, which is why the TUN rows no longer carry the
    // "unavailable in Root mode" notes: in Root mode they are not there at all.
    val sectionTunnel: @Composable () -> Unit = {
        var rootModeEnabled by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
        var rootTunEnabled by remember { mutableStateOf(GlobalSettings.isRootTunEnabled(context)) }
        var serviceScriptInstalled by remember { mutableStateOf(false) }
        var cliInstalled by remember { mutableStateOf(false) }
        var killDaemonOnStop by remember { mutableStateOf(GlobalSettings.shouldKillRootDaemonOnStop(context)) }
        var rootVpnBypass by remember { mutableStateOf(GlobalSettings.isRootVpnBypassEnabled(context)) }
        var rootTakeDevice by remember { mutableStateOf(GlobalSettings.isRootTakeDeviceAnyway(context)) }
        // What the service actually installed last time. It changes while this
        // screen is open — another VPN starts, ours yields — so it is watched
        // rather than read once. Shared is the middle case: the other client
        // bypasses some apps and we took the default route for those alone.
        var rootRoutingYielded by remember { mutableStateOf(GlobalSettings.isRootRoutingYielded(context)) }
        var rootRoutingShared by remember { mutableStateOf(GlobalSettings.isRootRoutingShared(context)) }
        var showRootWarningDialog by remember { mutableStateOf(false) }
        var showTunWarningDialog by remember { mutableStateOf(false) }
        var showTakeDeviceDialog by remember { mutableStateOf(false) }
        // Android hands the VPN slot to one app at a time and revokes it from
        // whoever held it, without asking that app. It does not say who that is,
        // so we can only report that the slot is taken — by AdGuard, a private
        // DNS app, another tunnel — and let the user decide.
        // Read afresh each time the dialog is raised, not once per composition.
        val foreignVpnActive = remember(showTunWarningDialog) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.allNetworks.any { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                } && !TunVpnService.isRunning
            }.getOrDefault(false)
        }
        var isRootBusy by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val prefs = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
                if (key == "root_routing_yielded") {
                    rootRoutingYielded = store.getBoolean("root_routing_yielded", false)
                }
                if (key == "root_routing_shared") {
                    rootRoutingShared = store.getBoolean("root_routing_shared", false)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            rootRoutingYielded = prefs.getBoolean("root_routing_yielded", false)
            rootRoutingShared = prefs.getBoolean("root_routing_shared", false)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // Root wins the display if an older install left both keys set; from the
        // first tap on, the selector keeps them exclusive.
        val selectedMode = if (rootModeEnabled) 2 else if (tunModeEnabled) 1 else 0

        // Probing these spawns a root shell, which must never run on the
        // composition thread — it blocks the UI until su answers.
        LaunchedEffect(rootModeEnabled) {
            if (!rootModeEnabled) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val script = RootUtils.isServiceScriptInstalled()
                val cli = RootUtils.isTailscaleCliInstalled()
                withContext(Dispatchers.Main) {
                    serviceScriptInstalled = script
                    cliInstalled = cli
                }
            }
        }

        // Exactly what the old "Enable Native Root Mode" switch ran when it was
        // switched off. The selector calls it for both of the other two modes, so
        // the teardown lives in one place instead of being duplicated per branch.
        fun disableRootMode() {
            rootModeEnabled = false
            val hadRouting = GlobalSettings.isRootRoutingInstalled(context)
            GlobalSettings.setRootModeEnabled(context, false)
            val hadScript = serviceScriptInstalled
            serviceScriptInstalled = false
            Toast.makeText(context, context.getString(R.string.settings_root_mode_disabled_toast), Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) {
                if (hadScript) RootUtils.setServiceScriptInstalled(context, false)
                // Take the system rules and the daemon down here rather
                // than hoping a later stop does it: nothing else knows
                // Root Mode was ever on once the setting is cleared.
                if (hadRouting) {
                    RootUtils.cleanupTailscale0Routing()
                    GlobalSettings.setRootRoutingInstalled(context, false)
                    GlobalSettings.setRootRoutingYielded(context, false)
                    GlobalSettings.setRootRoutingShared(context, false)
                }
                RootUtils.stopRootDaemon("${context.filesDir.absolutePath}/tailscaled.sock")
                RootUtils.handStateBackToApp(context)
                // Whether the user wants a connection, not whether this process
                // happens to own a daemon: in Root Mode the daemon is a separate
                // root process the app only attaches to, so Appctr.isRunning()
                // reported false here and switching modes quietly did nothing
                // until the service was toggled by hand.
                if (ProxyState.isUserLetRunning(context)) {
                    withContext(Dispatchers.Main) {
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                        context.startService(intent)
                    }
                }
            }
        }

        if (showTunWarningDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsTunWarningTitle = stringResource(R.string.settings_tun_warning_title)
            val strSettingsTunWarningBodyBusy = stringResource(R.string.settings_tun_warning_body_busy)
            val strSettingsTunWarningBody = stringResource(R.string.settings_tun_warning_body)
            val strSettingsTunWarningConfirm = stringResource(R.string.settings_tun_warning_confirm)
            val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AlertDialog(
                onDismissRequest = { showTunWarningDialog = false },
                icon = { Icon(Icons.Default.VpnLock, contentDescription = null) },
                title = { Text(strSettingsTunWarningTitle) },
                text = {
                    Text(
                        text = if (foreignVpnActive) strSettingsTunWarningBodyBusy
                               else strSettingsTunWarningBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showTunWarningDialog = false
                        // Same order the selector used: Root down first, then the
                        // TUN key, so no APPLY_SETTINGS lands with both set.
                        if (rootModeEnabled) disableRootMode()
                        if (!tunModeEnabled) {
                            tunModeEnabled = true
                            saveGlobalPref("tun_mode_enabled", true)
                        }
                    }) {
                        Text(strSettingsTunWarningConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTunWarningDialog = false }) {
                        Text(strSettingsRootWarningDialogCancel)
                    }
                }
            )
        }

        if (showRootWarningDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsRootWarningDialogTitle = stringResource(R.string.settings_root_warning_dialog_title)
            val strSettingsRootWarningDialogBody = stringResource(R.string.settings_root_warning_dialog_body)
            val strSettingsRootWarningDialogConfirm = stringResource(R.string.settings_root_warning_dialog_confirm)
            val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
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
                            text = strSettingsRootWarningDialogTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = strSettingsRootWarningDialogBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRootWarningDialog = false
                            isRootBusy = true
                            scope.launch {
                                val granted = withContext(Dispatchers.IO) { RootUtils.isRootAvailable() }
                                isRootBusy = false
                                if (granted) {
                                    rootModeEnabled = true
                                    GlobalSettings.setRootModeEnabled(context, true)
                                    if (GlobalSettings.isTunModeEnabled(context)) {
                                        GlobalSettings.setRootTunEnabled(context, true)
                                        rootTunEnabled = true
                                        // Root takes the tunnel over, so the
                                        // Android VpnService flag goes off with
                                        // it — the two mode keys are never both
                                        // true. The RESTART_ACTION below stops
                                        // the running VpnService.
                                        GlobalSettings.setTunModeEnabled(context, false)
                                        tunModeEnabled = false
                                    }
                                    Toast.makeText(context, context.getString(R.string.settings_root_mode_enabled_toast), Toast.LENGTH_SHORT).show()
                                    if (ProxyState.isUserLetRunning(context)) {
                                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                        context.startService(intent)
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.settings_root_access_unavailable), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strSettingsRootWarningDialogConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRootWarningDialog = false }) {
                        Text(strSettingsRootWarningDialogCancel)
                    }
                }
            )
        }

        if (showTakeDeviceDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTakeDeviceTitle = stringResource(R.string.settings_root_take_device_dialog_title)
            val strTakeDeviceBody = stringResource(R.string.settings_root_take_device_dialog_body)
            val strTakeDeviceConfirm = stringResource(R.string.settings_root_take_device_confirm)
            val strTakeDeviceCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AlertDialog(
                onDismissRequest = { showTakeDeviceDialog = false },
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
                            text = strTakeDeviceTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = strTakeDeviceBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showTakeDeviceDialog = false
                            rootTakeDevice = true
                            GlobalSettings.setRootTakeDeviceAnyway(context, true)
                            // The decision is read when routing is applied, and
                            // that only happens on a fresh run — APPLY_SETTINGS
                            // never reinstalls the rules.
                            if (ProxyState.isUserLetRunning(context)) {
                                val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                context.startService(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strTakeDeviceConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTakeDeviceDialog = false }) {
                        Text(strTakeDeviceCancel)
                    }
                }
            )
        }

        // A mode is a commitment — it decides how every packet leaves the phone —
        // so it is picked from a list that states what each one does, not from a
        // tab strip that looks like a view switcher. The chosen one expands with
        // its own settings underneath.
        fun selectMode(index: Int) {
            if (index == selectedMode) return
            if (index == 2) {
                // Root is never enabled straight from a tap: the warning dialog
                // owns the su probe and the writes that follow it.
                showRootWarningDialog = true
                return
            }
            if (index == 1) {
                // Nor is TUN: taking the VPN slot kicks out whatever holds it,
                // and the user finds out when their ad blocker goes dark.
                showTunWarningDialog = true
                return
            }
            // Root goes down first, the TUN key is written second — the same
            // order the two old switches produced, so no APPLY_SETTINGS ever
            // lands with Root Mode still on and a TUN start pending.
            if (rootModeEnabled) disableRootMode()
            val wantTun = index == 1
            if (tunModeEnabled != wantTun) {
                tunModeEnabled = wantTun
                saveGlobalPref("tun_mode_enabled", wantTun)
            }
        }

        @Composable
        fun ModeOption(index: Int, icon: ImageVector, title: String, desc: String, danger: Boolean = false) {
            val selected = index == selectedMode
            val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) accent.copy(alpha = 0.10f)
                                     else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ),
                border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.45f)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { selectMode(index) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RadioButton(
                        selected = selected,
                        onClick = { selectMode(index) },
                        colors = RadioButtonDefaults.colors(selectedColor = accent)
                    )
                }
            }
        }

        ModeOption(
            index = 0,
            icon = Icons.Default.SwapHoriz,
            title = stringResource(R.string.settings_tunnel_mode_proxy),
            desc = stringResource(R.string.settings_tunnel_mode_proxy_desc)
        )
        ModeOption(
            index = 1,
            icon = Icons.Default.VpnLock,
            title = stringResource(R.string.settings_tunnel_mode_vpn),
            desc = stringResource(R.string.settings_tunnel_mode_vpn_desc)
        )

        AnimatedVisibility(visible = selectedMode == 1) {
          SettingsCard(title = stringResource(R.string.settings_sect_tun_mode)) {
                // The dependency is real — the tunnel forwards every packet into
                // this local proxy — but the proxy is not TUN's: proxy mode is
                // nothing else, and in Root mode Taildrive and the admin API
                // still dial it. So it is named here and edited in its own
                // category.
                SettingsClickableItem(
                    title = stringResource(R.string.settings_tun_uses_socks_title),
                    subtitle = stringResource(R.string.settings_tun_uses_socks_desc, socks5.ifBlank { "127.0.0.1:48115" }),
                    icon = Icons.Default.SwapHoriz
                ) {
                    openSection = "proxies"
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_tun_ipv6_title),
                    subtitle = stringResource(R.string.settings_tun_ipv6_desc),
                    icon = Icons.Default.Language,
                    checked = tunIpv6Enabled
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
                    subtitle = stringResource(R.string.settings_tun_excluded_apps_desc, tunExcludedApps.size),
                    icon = Icons.Default.Apps
                ) {
                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                }
            }
        }

        ModeOption(
            index = 2,
            icon = Icons.Default.Security,
            title = stringResource(R.string.settings_tunnel_mode_root),
            desc = stringResource(R.string.settings_tunnel_mode_root_desc),
            danger = true
        )

        if (selectedMode == 2) {

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
                    text = stringResource(R.string.settings_root_banner_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            SettingsCard(title = stringResource(R.string.settings_root_sect_title)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_tun_title),
                    subtitle = if (rootTunEnabled)
                        stringResource(R.string.settings_root_tun_desc_native)
                        else stringResource(R.string.settings_root_tun_desc_proxy),
                    icon = Icons.Default.VpnLock,
                    checked = rootTunEnabled
                ) { enabled ->
                    rootTunEnabled = enabled
                    val hadRouting = GlobalSettings.isRootRoutingInstalled(context)
                    GlobalSettings.setRootTunEnabled(context, enabled)
                    scope.launch(Dispatchers.IO) {
                        // Leaving native TUN drops tailscale0, so its policy
                        // routing has to go with it — the refresh loop no longer
                        // looks at Root Mode routing once this is off.
                        if (!enabled && hadRouting) {
                            RootUtils.cleanupTailscale0Routing()
                            GlobalSettings.setRootRoutingInstalled(context, false)
                            GlobalSettings.setRootRoutingYielded(context, false)
                            GlobalSettings.setRootRoutingShared(context, false)
                        }
                        if (ProxyState.isUserLetRunning(context)) {
                            withContext(Dispatchers.Main) {
                                val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                context.startService(intent)
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                // The same list TUN mode uses: in Root Mode it takes the app's
                // uid out of the device-wide DNS redirect and off the exit node.
                SettingsClickableItem(
                    title = stringResource(R.string.settings_tun_excluded_apps_title),
                    subtitle = stringResource(R.string.settings_tun_excluded_apps_desc, tunExcludedApps.size),
                    icon = Icons.Default.Apps
                ) {
                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_service_title),
                    subtitle = stringResource(R.string.settings_root_service_desc),
                    icon = Icons.Default.Build,
                    checked = serviceScriptInstalled,
                    enabled = !isRootBusy
                ) { install ->
                    isRootBusy = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            RootUtils.setServiceScriptInstalled(context, install)
                        }
                        isRootBusy = false
                        if (success) {
                            serviceScriptInstalled = install
                            Toast.makeText(context, context.getString(if (install) R.string.settings_root_service_script_installed else R.string.settings_root_service_script_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_service_script_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (serviceScriptInstalled) {
                    val coroutineScope = rememberCoroutineScope()
                    val msgOk = stringResource(R.string.settings_root_script_reinstalled)
                    val msgFail = stringResource(R.string.error_generic, "reinstall failed")
                    var reinstallStatus by remember { mutableStateOf<String?>(null) }
                    var isReinstallOk by remember { mutableStateOf(true) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The status is a whole sentence and the button never
                        // shrinks: without the weight they were drawn over each other.
                        reinstallStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isReinstallOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } ?: Spacer(Modifier.width(1.dp))

                        Spacer(Modifier.width(12.dp))

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val success = RootUtils.setServiceScriptInstalled(context, true)
                                    withContext(Dispatchers.Main) {
                                        isReinstallOk = success
                                        reinstallStatus = if (success) msgOk else msgFail
                                        Toast.makeText(context, if (success) msgOk else msgFail, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.settings_root_script_reinstall),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_cli_title),
                    subtitle = stringResource(R.string.settings_root_cli_desc),
                    icon = Icons.Default.Terminal,
                    checked = cliInstalled,
                    enabled = !isRootBusy
                ) { install ->
                    isRootBusy = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            RootUtils.setTailscaleCliInstalled(context, install)
                        }
                        isRootBusy = false
                        if (success) {
                            cliInstalled = install
                            Toast.makeText(context, context.getString(if (install) R.string.settings_root_cli_installed else R.string.settings_root_cli_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_cli_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_vpn_bypass_title),
                    subtitle = stringResource(R.string.settings_root_vpn_bypass_desc),
                    icon = Icons.Default.Shield,
                    checked = rootVpnBypass
                ) { enabled ->
                    rootVpnBypass = enabled
                    GlobalSettings.setRootVpnBypassEnabled(context, enabled)
                    if (ProxyState.isUserLetRunning(context)) {
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                        context.startService(intent)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                // Taking the device breaks another app's tunnel, so switching it
                // on asks first; giving the device back needs no confirmation.
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_take_device_title),
                    subtitle = stringResource(R.string.settings_root_take_device_desc),
                    icon = Icons.Default.PriorityHigh,
                    checked = rootTakeDevice
                ) { enabled ->
                    if (enabled) {
                        showTakeDeviceDialog = true
                    } else {
                        rootTakeDevice = false
                        GlobalSettings.setRootTakeDeviceAnyway(context, false)
                        if (ProxyState.isUserLetRunning(context)) {
                            val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                            context.startService(intent)
                        }
                    }
                }

                // Three outcomes, and the override only silences the two that
                // say we stepped aside. Shared answers first: it is the one the
                // yield flag also claims, and it claims less than the truth.
                if ((rootRoutingYielded || rootRoutingShared) && !rootTakeDevice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (rootRoutingShared) stringResource(R.string.settings_root_shared_banner)
                                   else stringResource(R.string.settings_root_yielded_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    }

    // C. Local proxies
    val sectionProxies: @Composable () -> Unit = {
        SettingsCard(title = stringResource(R.string.settings_sect_socks5)) {
            SettingsEditItem(stringResource(R.string.settings_socks5_address_title), socks5, Icons.Default.Language, onAction = { generateRandomLoopbackAddress() }, actionIcon = Icons.Default.Casino) { socks5 = it; saveGlobalPref("socks5", it) }
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
            val isHttpEnabled = httpProxy.isNotEmpty()
            SettingsSwitchItem(
                title = stringResource(R.string.settings_http_enable_title),
                subtitle = stringResource(R.string.settings_http_enable_desc),
                icon = Icons.Default.Http,
                checked = isHttpEnabled
            ) { enabled ->
                if (enabled) {
                    val defaultAddr = "127.0.0.1:8080"
                    httpProxy = defaultAddr
                    saveGlobalPref("httpproxy", defaultAddr)
                } else {
                    httpProxy = ""
                    saveGlobalPref("httpproxy", "")
                }
            }

            if (isHttpEnabled) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SettingsEditItem(
                    title = stringResource(R.string.settings_http_address_title),
                    value = httpProxy,
                    icon = Icons.Default.Http,
                    placeholder = "127.0.0.1:8080",
                    onAction = { generateRandomLoopbackAddress() },
                    actionIcon = Icons.Default.Casino
                ) { 
                    httpProxy = it
                    saveGlobalPref("httpproxy", it) 
                }
                Text(
                    text = stringResource(R.string.settings_http_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Last in the section on purpose: it is the one switch here that can
        // expose the listeners above to anyone on the same Wi-Fi.
        SettingsCard(title = stringResource(R.string.settings_sect_lan)) {
            val lanIp = remember { NetAddr.lanIpv4() }
            val socksHasAuth = socks5User.isNotEmpty() || socks5Pass.isNotEmpty()

            SettingsSwitchItem(
                title = stringResource(R.string.settings_lan_access_title),
                subtitle = if (lanAccessEnabled) {
                    // Show what is actually bound: the stored fields keep the
                    // user's own value, the wildcard is applied on top of it.
                    val ports = listOfNotNull(
                        NetAddr.port(GlobalSettings.getSocks5BindAddr(context))?.let { "SOCKS5 $it" },
                        GlobalSettings.getHttpProxyBindAddr(context)
                            .takeIf { it.isNotEmpty() }?.let { a -> NetAddr.port(a)?.let { "HTTP $it" } },
                        NetAddr.port(GlobalSettings.getDnsProxyBindAddr(context))?.let { "DNS $it" }
                    ).joinToString(", ")
                    stringResource(R.string.settings_lan_access_active, lanIp ?: "?") +
                        if (ports.isEmpty()) "" else " · $ports"
                } else {
                    stringResource(R.string.settings_lan_access_desc)
                },
                icon = Icons.Default.Lan,
                checked = lanAccessEnabled
            ) { enabled ->
                lanAccessEnabled = enabled
                GlobalSettings.setLanAccessEnabled(context, enabled)
                if (ProxyState.isUserLetRunning(context)) {
                    val intent = Intent(context, TailscaledService::class.java).apply {
                        action = "RESTART_ACTION"
                    }
                    context.startService(intent)
                }
            }

            if (lanAccessEnabled && !socksHasAuth) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
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
                        text = stringResource(R.string.settings_lan_access_no_auth_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    // D. DNS
    val sectionDns: @Composable () -> Unit = {
        // Root Mode owns the resolver itself, so both gates below read the
        // stored value the same way the old TS-Core tab did.
        val isRootModeActive = GlobalSettings.isRootModeEnabled(context)
        val isRootTunActive = GlobalSettings.isRootTunEnabled(context)
        var rootDnsRedirect by remember { mutableStateOf(GlobalSettings.isRootDnsRedirectEnabled(context)) }
        // The switch can be on while the redirect is not installed: another VPN
        // owns the resolver and Root Mode yielded it. Say so instead of letting
        // the row claim something that is not on the device. Shared is the third
        // case — the redirect is installed, minus that client's own queries.
        val rootDnsShared = GlobalSettings.isRootRoutingShared(context)
        val rootDnsYielded = GlobalSettings.isRootRoutingYielded(context) && !rootDnsShared

        SettingsCard(title = stringResource(R.string.settings_sect_resolver)) {
            SettingsSwitchItem(stringResource(R.string.settings_magicdns_title), stringResource(R.string.settings_accept_dns_desc), Icons.Default.Dns, acceptDns) { acceptDns = it; saveGlobalPref("accept_dns", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(
                title = stringResource(R.string.settings_dns_proxy_address_title),
                value = dnsProxy,
                icon = Icons.Default.Toll,
                enabled = !isRootModeActive,
                description = if (isRootModeActive) stringResource(R.string.settings_root_disabled_general_note) else ""
            ) { dnsProxy = it; saveGlobalPref("dns_proxy", it) }

            if (isRootModeActive && isRootTunActive) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_dns_redirect_title),
                    subtitle = when {
                        rootDnsRedirect && rootDnsYielded -> stringResource(R.string.settings_root_yielded_banner)
                        rootDnsRedirect && rootDnsShared -> stringResource(R.string.settings_root_dns_shared_desc)
                        else -> stringResource(R.string.settings_root_dns_redirect_desc)
                    },
                    icon = Icons.Default.Dns,
                    checked = rootDnsRedirect
                ) { enabled ->
                    rootDnsRedirect = enabled
                    GlobalSettings.setRootDnsRedirectEnabled(context, enabled)
                    if (ProxyState.isUserLetRunning(context)) {
                        val intent = Intent(context, TailscaledService::class.java).apply {
                            action = "RESTART_ACTION"
                        }
                        context.startService(intent)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_fallback_dns)) {
            SettingsEditItem(stringResource(R.string.settings_dns_fallbacks_title), dnsFallbacks, Icons.AutoMirrored.Filled.List, placeholder = stringResource(R.string.settings_dns_fallbacks_placeholder)) { dnsFallbacks = it; saveGlobalPref("dns_fallbacks", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(stringResource(R.string.settings_doh_fallback_title), dohUrl, Icons.Default.Link, placeholder = stringResource(R.string.settings_doh_fallback_placeholder)) { dohUrl = it; saveGlobalPref("doh_url", it) }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_tools)) {
            SettingsClickableItem(
                stringResource(R.string.dns_title),
                stringResource(R.string.settings_link_dns_desc),
                Icons.Default.Troubleshoot
            ) { context.startActivity(Intent(context, DnsActivity::class.java)) }
        }
    }

    // E. Censorship bypass
    val sectionBypass: @Composable () -> Unit = {
        var byedpiEnabled by remember { mutableStateOf(GlobalSettings.isCPByeDpiEnabled(context)) }
        var byedpiFlags by remember { mutableStateOf(GlobalSettings.getCPByeDpiFlags(context)) }
        var byedpiIpv6Disabled by remember { mutableStateOf(GlobalSettings.isCPByeDpiIpv6Disabled(context)) }
        val activeBbdAddr = ByeDpiProxy.activeAddress

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

    // F. Sharing & access
    val sectionSharing: @Composable () -> Unit = {
        SettingsCard(title = stringResource(R.string.settings_sect_storage)) {
            SettingsClickableItem(
                stringResource(R.string.settings_taildrop_folder_title),
                taildropRootUri?.path ?: stringResource(R.string.settings_taildrop_folder_default),
                Icons.Default.Folder
            ) { folderPicker.launch(null) }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_share_links)) {
            SettingsClickableItem(
                stringResource(R.string.taildrive_title),
                stringResource(R.string.settings_link_taildrive_desc),
                Icons.Default.FolderShared
            ) { context.startActivity(Intent(context, TaildriveActivity::class.java)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsClickableItem(
                stringResource(R.string.serve_title),
                stringResource(R.string.settings_link_serve_desc),
                Icons.Default.Public
            ) { context.startActivity(Intent(context, ServeActivity::class.java)) }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_web)) {
            SettingsSwitchItem(stringResource(R.string.settings_web_enable_title), stringResource(R.string.settings_web_enable_desc), Icons.Default.Web, enableWebUI) { enableWebUI = it; saveProfilePref("enable_webui", it) }
            if (enableWebUI) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsEditItem(stringResource(R.string.settings_web_address_title), webUIAddr, Icons.Default.Link) { webUIAddr = it; saveProfilePref("webui_addr", it) }
            }
        }
    }

    // G. Background & permissions
    val sectionBackground: @Composable () -> Unit = {
        SettingsCard(title = stringResource(R.string.settings_sect_service_perms)) {
            SettingsClickableItem(stringResource(R.string.settings_permissions_title), stringResource(R.string.settings_permissions_desc), Icons.Default.VerifiedUser) {
                context.startActivity(Intent(context, PermissionsActivity::class.java))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(stringResource(R.string.settings_autostart_title), stringResource(R.string.settings_autostart_desc), Icons.Default.PowerSettingsNew, autoStart) {
                GlobalSettings.setAutoStartEnabled(context, it)
                autoStart = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(
                stringResource(R.string.settings_auto_reconnect_title),
                stringResource(R.string.settings_auto_reconnect_desc),
                Icons.Default.Autorenew,
                autoReconnect
            ) {
                GlobalSettings.setAutoReconnectEnabled(context, it)
                autoReconnect = it
            }
            if (autoReconnect) {
                SettingsEditItem(
                    title = stringResource(R.string.settings_auto_reconnect_attempts_title),
                    value = autoReconnectAttempts,
                    icon = Icons.Default.Repeat
                ) {
                    autoReconnectAttempts = it.filter { ch -> ch.isDigit() }.take(2)
                    saveGlobalPref("auto_reconnect_attempts", autoReconnectAttempts)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(
                stringResource(R.string.settings_watchdog_title),
                stringResource(R.string.settings_watchdog_desc),
                Icons.Default.MonitorHeart,
                serviceWatchdog
            ) {
                GlobalSettings.setServiceWatchdogEnabled(context, it)
                serviceWatchdog = it
                if (it) {
                    ServiceWatchdog.schedule(context)
                    // Without "Alarms & reminders" the check still runs, only
                    // later and without the exemption that lets it start the
                    // service from the background. Ask exactly here, where the
                    // user just said they want the feature.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        !ServiceWatchdog.canScheduleExact(context)
                    ) {
                        try {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.settings_watchdog_exact_error), Toast.LENGTH_LONG).show()
                        }
                    }
                } else ServiceWatchdog.cancel(context)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsSwitchItem(stringResource(R.string.settings_keep_awake_title), stringResource(R.string.settings_force_bg_desc), Icons.Default.BatteryFull, forceBg) { forceBg = it; saveGlobalPref("force_bg", it) }
        }
    }

    // H. Appearance & language
    val sectionAppearance: @Composable () -> Unit = {
        SettingsCard(title = stringResource(R.string.settings_sect_personalization)) {
            // Theme selector (Chips row)
            val themeOptions = listOf(
                Triple("system", Icons.Default.Settings, stringResource(R.string.settings_theme_system)),
                Triple("light", Icons.Default.LightMode, stringResource(R.string.settings_theme_light)),
                Triple("dark", Icons.Default.DarkMode, stringResource(R.string.settings_theme_dark))
            )
            val selectedThemeIdx = themeOptions.indexOfFirst { it.first == currentTheme }.coerceAtLeast(0)
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SlidingSegmentedChips(
                    items = themeOptions.map { SegmentedChipItem(it.third, it.second) },
                    selectedIndex = selectedThemeIdx,
                    onOptionSelected = { idx -> onThemeChange(themeOptions[idx].first) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 38.dp
                )
            }

            // Language selector (Chips row)
            var currentLang by remember { mutableStateOf(GlobalSettings.getString(context, "app_locale", "sys")) }
            val languageOptions = listOf(
                Triple("sys", Icons.Default.Language, stringResource(R.string.settings_lang_sys)),
                Triple("en", Icons.Default.Language, stringResource(R.string.settings_lang_en)),
                Triple("ru", Icons.Default.Language, stringResource(R.string.settings_lang_ru))
            )
            val selectedLangIdx = languageOptions.indexOfFirst { it.first == currentLang }.coerceAtLeast(0)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(stringResource(R.string.settings_lang_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SlidingSegmentedChips(
                    items = languageOptions.map { SegmentedChipItem(it.third, it.second) },
                    selectedIndex = selectedLangIdx,
                    onOptionSelected = { idx ->
                        val id = languageOptions[idx].first
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
                    modifier = Modifier.fillMaxWidth(),
                    height = 38.dp
                )
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

            Spacer(Modifier.height(12.dp))
            var showChangelogAfterUpdate by remember { mutableStateOf(GlobalSettings.isShowChangelogAfterUpdate(context)) }
            SettingsSwitchItem(
                stringResource(R.string.settings_show_changelog_title),
                stringResource(R.string.settings_show_changelog_desc),
                Icons.Default.NewReleases,
                showChangelogAfterUpdate
            ) {
                GlobalSettings.setShowChangelogAfterUpdate(context, it)
                showChangelogAfterUpdate = it
            }
        }
    }

    // I. Backup & restore
    val sectionBackup: @Composable () -> Unit = {
        SettingsCard(title = stringResource(R.string.settings_sect_backup_files)) {
            // The encrypted archive carries shared_prefs/ and files/states, so
            // restoring it replaces every account and every setting at once.
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
                        stringResource(R.string.settings_backup_full_title), 
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
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
                        stringResource(R.string.settings_restore_full_title), 
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 12.dp))

            // The JSON pair is scoped to the account that is open right now.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { backupLauncher.launch("tailsocks_backup_${activeAccount.name}.json") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_profile_export_title),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                OutlinedButton(
                    onClick = { restoreLauncher.launch("application/json") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SettingsBackupRestore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_profile_import_title),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    // J. Automation & API
    val sectionAutomation: @Composable () -> Unit = {
        var automationEnabled by remember { mutableStateOf(GlobalSettings.isAutomationEnabled(context)) }
        var automationSecret by remember { mutableStateOf(GlobalSettings.getAutomationSecret(context)) }

        SettingsCard(title = stringResource(R.string.settings_automation_title)) {
            SettingsSwitchItem(
                title = stringResource(R.string.settings_automation_enable_title),
                subtitle = stringResource(R.string.settings_automation_enable_desc),
                icon = Icons.Default.SmartButton,
                checked = automationEnabled
            ) {
                automationEnabled = it
                GlobalSettings.setAutomationEnabled(context, it)
            }

            if (automationEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                 CompactTextField(
                     value = automationSecret,
                     onValueChange = {
                         automationSecret = it
                         GlobalSettings.setAutomationSecret(context, it)
                     },
                     label = stringResource(R.string.settings_automation_token_label),
                     placeholder = stringResource(R.string.settings_automation_token_placeholder),
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
                     }
                 )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val token = generateSecureToken(32)
                            automationSecret = token
                            GlobalSettings.setAutomationSecret(context, token)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Casino, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.settings_automation_generate_token),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_automation_copy_token), automationSecret))
                            // Android 13+ shows its own "Copied" overlay; a Toast would double it.
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, context.getString(R.string.settings_automation_token_copied), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = automationSecret.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.settings_automation_copy_token),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (automationSecret.isEmpty()) stringResource(R.string.settings_automation_no_token) else stringResource(R.string.settings_automation_token_active, automationSecret),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (automationSecret.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
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
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsClickableItem(
                stringResource(R.string.admin_console_title),
                stringResource(R.string.settings_link_admin_desc),
                Icons.Default.AdminPanelSettings
            ) { context.startActivity(Intent(context, AdminApiActivity::class.java)) }
        }
    }

    // K. Diagnostics & developer
    val sectionDiagnostics: @Composable () -> Unit = {
        // Root Mode is read here, not held: the switch that changes it lives in
        // section B, and leaving that page re-composes this one.
        val rootModeActive = GlobalSettings.isRootModeEnabled(context)
        val rootTunActive = GlobalSettings.isRootTunEnabled(context)

        SettingsCard(title = stringResource(R.string.settings_sect_flags_logs)) {
            SettingsSwitchItem(stringResource(R.string.settings_detailed_logs_title), stringResource(R.string.settings_detailed_logs_desc), Icons.Default.BugReport, detailedLogs) { detailedLogs = it; saveGlobalPref("detailed_logs", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsEditItem(
                stringResource(R.string.settings_extra_args_title),
                extraArgs,
                Icons.Default.Code,
                placeholder = stringResource(R.string.settings_extra_args_placeholder),
                description = stringResource(R.string.extra_args_desc)
            ) { extraArgs = it; saveGlobalPref("extra_args_raw", it) }
        }

        if (rootModeActive) {
            Spacer(Modifier.height(12.dp))
            SettingsCard(title = stringResource(R.string.settings_root_info_title)) {
                val socketPath = "${context.filesDir.absolutePath}/tailscaled.sock"
                val logsDir = File(context.filesDir.parentFile ?: context.filesDir, "logs").absolutePath
                val logPath = "$logsDir/tailscaled.log"
                val serviceScriptPath = RootUtils.SERVICE_SCRIPT_PATH
                var daemonAlive by remember { mutableStateOf(false) }

                // Poll real socket liveness every 3s instead of just File.exists()
                LaunchedEffect(Unit) {
                    while (true) {
                        daemonAlive = withContext(Dispatchers.IO) {
                            RootUtils.isDaemonAlive(socketPath)
                        }
                        kotlinx.coroutines.delay(3_000)
                    }
                }

                CopyablePathItem(
                    label = "Socket Path",
                    path = socketPath,
                    context = context
                )

                Spacer(Modifier.height(8.dp))
                CopyablePathItem(
                    label = "Log File",
                    path = logPath,
                    context = context
                )

                Spacer(Modifier.height(8.dp))
                CopyablePathItem(
                    label = "Service Script Path",
                    path = serviceScriptPath,
                    context = context
                )

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_root_daemon_status_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(if (daemonAlive) R.string.settings_root_daemon_status_running else R.string.settings_root_daemon_status_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (daemonAlive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                if (rootTunActive) {
                    var routingDump by remember { mutableStateOf<String?>(null) }
                    var isDumping by remember { mutableStateOf(false) }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            isDumping = true
                            scope.launch {
                                val dump = withContext(Dispatchers.IO) { RootUtils.dumpRoutingState(context) }
                                routingDump = dump.ifBlank { "(no output)" }
                                isDumping = false
                            }
                        },
                        enabled = !isDumping,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.settings_root_routing_check),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    routingDump?.let { dump ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = dump,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_tools)) {
            SettingsClickableItem(
                stringResource(R.string.logs_title),
                stringResource(R.string.settings_link_logs_desc),
                Icons.AutoMirrored.Filled.List
            ) { context.startActivity(Intent(context, LogsActivity::class.java)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsClickableItem(
                stringResource(R.string.console_title),
                stringResource(R.string.settings_link_console_desc),
                Icons.Default.Terminal
            ) { context.startActivity(Intent(context, ConsoleActivity::class.java)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsClickableItem(
                stringResource(R.string.netcheck_title),
                stringResource(R.string.settings_link_netcheck_desc),
                Icons.Default.NetworkCheck
            ) { context.startActivity(Intent(context, NetcheckActivity::class.java)) }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = stringResource(R.string.settings_sect_troubleshooting)) {
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
    }

    // Back means "up one level": out of an open section to the hub, and only from
    // the hub out of the Activity. The toolbar arrow keeps the crossfade below;
    // the gesture draws its own transition and sets [poppedByGesture] so the
    // crossfade stands down instead of replaying a pop the finger already showed.
    var poppedByGesture by remember { mutableStateOf(false) }
    val popSection: () -> Unit = { openSection = null }
    val popSectionByGesture: () -> Unit = {
        poppedByGesture = true
        openSection = null
    }
    // Armed for exactly one transition. The reset runs after the composition that
    // consumed it, and transitionSpec is only consulted when the target changes,
    // so it cannot cancel the transition it just configured.
    LaunchedEffect(openSection) { poppedByGesture = false }

    // One whole rendering of the screen at a given level: the category hub when
    // [section] is null, that category's page otherwise. It takes the level as a
    // parameter instead of reading `openSection` because the back gesture needs
    // two levels on screen at once — the section being dragged away, and the hub
    // coming back underneath it.
    val settingsSurface: @Composable (String?) -> Unit = { section ->
        val openCategory = settingsCategories.firstOrNull { it.id == section }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (openCategory != null) stringResource(openCategory.titleRes)
                            else stringResource(R.string.settings_title),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (section != null) popSection() else onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                )
            }
        ) { padding ->
            if (section == null) {
                val hubState = rememberLazyListState(hubAnchor.index, hubAnchor.offset)
                // Every hub instance starts where the last one was left and writes back
                // where it is now, so the copy under the finger, the copy on top of it and
                // the copy that lands are all at the same place in the list.
                LaunchedEffect(hubState) {
                    snapshotFlow { hubState.firstVisibleItemIndex to hubState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            hubAnchor.index = index
                            hubAnchor.offset = offset
                        }
                }
                LazyColumn(
                    state = hubState,
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(settingsCategories, key = { it.id }) { category ->
                        SettingsClickableItem(
                            title = stringResource(category.titleRes),
                            subtitle = stringResource(category.descRes),
                            icon = category.icon,
                            onClick = { openSection = category.id }
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            } else {
                // Hoisted for the same reason as the hub's: the back container swaps its own
                // structure the moment a pop lands, and a section that jumps back to the top
                // while it is still fading out is worse than the pop it is showing.
                val sectionScroll = rememberScrollState(sectionAnchors[section] ?: 0)
                LaunchedEffect(sectionScroll, section) {
                    snapshotFlow { sectionScroll.value }.collect { sectionAnchors[section] = it }
                }
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(sectionScroll)
                        .padding(16.dp)
                ) {
                    when (section) {
                        "account" -> sectionAccount()
                        "tunnel" -> sectionTunnel()
                        "proxies" -> sectionProxies()
                        "dns" -> sectionDns()
                        "bypass" -> sectionBypass()
                        "sharing" -> sectionSharing()
                        "background" -> sectionBackground()
                        "appearance" -> sectionAppearance()
                        "backup" -> sectionBackup()
                        "automation" -> sectionAutomation()
                        "diagnostics" -> sectionDiagnostics()
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    // The crossfade between the hub and a section: Material 3's own *default effects*
    // spring. Read here rather than inside `transitionSpec`, which is not a composable
    // lambda and so cannot reach `MaterialTheme` itself.
    val sectionFadeSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    // The crossfade sits outside the back container, so that opening a section
    // still fades and each level keeps its own container: the one drawn for a
    // section installs the gesture, the one drawn for the hub does not.
    AnimatedContent(
        targetState = openSection,
        transitionSpec = {
            if (poppedByGesture) {
                // The finger already played this one: the section shrank away and
                // the hub came back up underneath it. Fading them into each other
                // on top of that would show the section again, full size.
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(sectionFadeSpec) togetherWith fadeOut(sectionFadeSpec)
            }
        },
        label = "settings_section",
        // The two levels overlap while they crossfade, and half-transparent over
        // half-transparent would let the window background show through between
        // them; this is the ground they fade over.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) { section ->
        PredictiveBackContainer(
            // Only the section pop is ours to draw. On the hub back leaves the
            // Activity, so nothing is intercepted there and the platform keeps the
            // gesture and its real cross-activity animation.
            // openSection as well as this slot's own section: AnimatedContent keeps the
            // outgoing slot alive for the whole exit fade, and that slot was invoked with a
            // section, so a handler keyed on it alone stayed armed on a screen that is
            // already leaving — a back gesture in that window was swallowed instead of
            // closing the Activity, and it re-ran the pop with nothing left to pop, which
            // latched poppedByGesture and cost the next section its fade.
            onBack = if (section != null && openSection != null) popSectionByGesture else null,
            modifier = Modifier.fillMaxSize(),
            // What the finger uncovers is the real hub, not a hint of it — for the eye
            // only: it is a second live copy of every category row, and a screen reader
            // reaches the hub through the one that lands.
            previousContent = {
                Box(Modifier.fillMaxSize().clearAndSetSemantics { }) { settingsSurface(null) }
            }
        ) {
            settingsSurface(section)
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
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsBackupPasswordTitle = stringResource(R.string.settings_backup_password_title)
        val strSettingsBackupPasswordText = stringResource(R.string.settings_backup_password_text)
        val strSettingsPasswordLabel = stringResource(R.string.settings_password_label)
        val strSettingsBackupAction = stringResource(R.string.settings_backup_action)
        val strActionCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showBackupPasswordDialog = false },
            title = { Text(strSettingsBackupPasswordTitle) },
            text = {
                Column {
                    Text(strSettingsBackupPasswordText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(strSettingsPasswordLabel) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
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
                ) { Text(strSettingsBackupAction) }
            },
            dismissButton = {
                TextButton(onClick = { showBackupPasswordDialog = false }) { Text(strActionCancel) }
            }
        )
    }

    if (showRestorePasswordDialog && pendingRestoreUri != null) {
        var tempPassword by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsRestorePasswordTitle = stringResource(R.string.settings_restore_password_title)
        val strSettingsRestorePasswordText = stringResource(R.string.settings_restore_password_text)
        val strSettingsPasswordLabel = stringResource(R.string.settings_password_label)
        val strSettingsRestoreAction = stringResource(R.string.settings_restore_action)
        val strActionCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { 
                showRestorePasswordDialog = false
                pendingRestoreUri = null
            },
            title = { Text(strSettingsRestorePasswordTitle) },
            text = {
                Column {
                    Text(strSettingsRestorePasswordText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(strSettingsPasswordLabel) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
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
                ) { Text(strSettingsRestoreAction) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showRestorePasswordDialog = false
                    pendingRestoreUri = null
                }) { Text(strActionCancel) }
            }
        )
    }
}
