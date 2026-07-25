package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.launch

class FirstStartActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(io.github.bropines.tailscaled.core.wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                FirstStartScreen(
                    onFinished = {
                        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("first_start_done", true)
                            .apply()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun FirstStartScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 6 })

    val activeAccount = remember { io.github.bropines.tailscaled.core.AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }

    Scaffold(
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back Button
                    if (pagerState.currentPage > 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.first_start_btn_back))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(80.dp))
                    }

                    // Indicators
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(6) { idx ->
                            val isSelected = pagerState.currentPage == idx
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    )
                            )
                        }
                    }

                    // Next / Finish Button
                    if (pagerState.currentPage < 5) {
                        Button(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.first_start_btn_next))
                        }
                    } else {
                        Button(
                            onClick = onFinished,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.first_start_btn_finish))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            userScrollEnabled = false
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (page) {
                    0 -> SlideWelcome()
                    1 -> SlideHowItWorks()
                    2 -> SlideControlPlane(profilePrefs)
                    3 -> SlideBypassSetup()
                    4 -> SlideLogin(profilePrefs)
                    5 -> SlidePermissions()
                }
            }
        }
    }
}

@Composable
fun SlideContainer(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        lineHeight = 22.sp,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
    Spacer(modifier = Modifier.height(24.dp))
    content()
}

@Composable
fun SlideWelcome() {
    val context = LocalContext.current
    var currentLang by remember { mutableStateOf(GlobalSettings.getString(context, "app_locale", "sys")) }

    SlideContainer(
        icon = Icons.Default.Speed,
        title = stringResource(R.string.first_start_welcome_title),
        description = stringResource(R.string.first_start_welcome_desc)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.first_start_select_lang),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "sys" to stringResource(R.string.settings_lang_sys),
                    "en" to stringResource(R.string.settings_lang_en),
                    "ru" to stringResource(R.string.settings_lang_ru)
                ).forEach { (id, label) ->
                    val isSelected = currentLang == id
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            currentLang = id
                            GlobalSettings.setString(context, "app_locale", id)
                            val localeList = if (id == "sys") {
                                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                            } else {
                                androidx.core.os.LocaleListCompat.forLanguageTags(id)
                            }
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
                            (context as? android.app.Activity)?.recreate()
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

@Composable
fun SlideHowItWorks() {
    val context = LocalContext.current
    var isTunMode by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }

    SlideContainer(
        icon = Icons.Default.Language,
        title = stringResource(R.string.first_start_how_title),
        description = stringResource(R.string.first_start_how_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !isTunMode,
                    onClick = {
                        isTunMode = false
                        GlobalSettings.setTunModeEnabled(context, false)
                    },
                    label = { Text("Proxy Mode") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = isTunMode,
                    onClick = {
                        isTunMode = true
                        GlobalSettings.setTunModeEnabled(context, true)
                    },
                    label = { Text("VPN (TUN) Mode") },
                    modifier = Modifier.weight(1f)
                )
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (!isTunMode) stringResource(R.string.first_start_mode_proxy_title) else stringResource(R.string.first_start_mode_vpn_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (!isTunMode) stringResource(R.string.first_start_mode_proxy_desc) else stringResource(R.string.first_start_mode_vpn_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun SlideControlPlane(profilePrefs: android.content.SharedPreferences) {
    var loginServer by remember { mutableStateOf(profilePrefs.getString("login_server", "") ?: "") }

    SlideContainer(
        icon = Icons.Default.Cloud,
        title = stringResource(R.string.first_start_cp_warning_title),
        description = stringResource(R.string.first_start_cp_warning_desc)
    ) {
        OutlinedTextField(
            value = loginServer,
            onValueChange = {
                loginServer = it
                profilePrefs.edit().putString("login_server", it).apply()
            },
            label = { Text(stringResource(R.string.first_start_cp_field_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideBypassSetup() {
    val context = LocalContext.current
    var bypassMode by remember {
        mutableStateOf(
            if (GlobalSettings.isCPByeDpiEnabled(context)) "bbd"
            else if (GlobalSettings.isCPProxyEnabled(context)) "proxy"
            else "none"
        )
    }

    var proxyLink by remember { mutableStateOf("") }
    var proxyType by remember { mutableStateOf(GlobalSettings.getCPField(context, "type", "SOCKS5")) }
    var proxyHost by remember { mutableStateOf(GlobalSettings.getCPField(context, "host")) }
    var proxyPort by remember { mutableStateOf(GlobalSettings.getCPField(context, "port")) }
    var proxyUser by remember { mutableStateOf(GlobalSettings.getCPField(context, "user")) }
    var proxyPass by remember { mutableStateOf(GlobalSettings.getCPField(context, "pass")) }

    var presets by remember { mutableStateOf(GlobalSettings.getCPPresets(context)) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    fun applyBypassSettings(mode: String) {
        bypassMode = mode
        when (mode) {
            "none" -> {
                GlobalSettings.setCPByeDpiEnabled(context, false)
                GlobalSettings.setCPProxyEnabled(context, false)
            }
            "bbd" -> {
                GlobalSettings.setCPByeDpiEnabled(context, true)
                GlobalSettings.setCPProxyEnabled(context, false)
            }
            "proxy" -> {
                GlobalSettings.setCPByeDpiEnabled(context, false)
                GlobalSettings.setCPProxyEnabled(context, true)
            }
        }
    }

    SlideContainer(
        icon = Icons.Default.Shield,
        title = stringResource(R.string.first_start_bypass_title),
        description = stringResource(R.string.first_start_bypass_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                "none" to stringResource(R.string.first_start_bypass_mode_none),
                "bbd" to stringResource(R.string.first_start_bypass_mode_bbd),
                "proxy" to stringResource(R.string.first_start_bypass_mode_proxy)
            ).forEach { (mode, label) ->
                val isSelected = bypassMode == mode
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyBypassSettings(mode) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { applyBypassSettings(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (bypassMode == "proxy") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = proxyLink,
                            onValueChange = { proxyLink = it },
                            label = { Text(stringResource(R.string.first_start_proxy_link_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clipData = clipboardManager.primaryClip
                                        val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                                            clipData.getItemAt(0).text?.toString()?.trim()
                                        } else null

                                        val linkToParse = if (!clipboardText.isNullOrEmpty()) {
                                            proxyLink = clipboardText
                                            clipboardText
                                        } else {
                                            proxyLink.trim()
                                        }

                                        if (linkToParse.isNotEmpty()) {
                                            val parsed = GlobalSettings.parseProxyUri(linkToParse)
                                            if (parsed != null) {
                                                proxyType = parsed["type"] ?: "SOCKS5"
                                                proxyHost = parsed["host"] ?: ""
                                                proxyPort = parsed["port"] ?: ""
                                                proxyUser = parsed["user"] ?: ""
                                                proxyPass = parsed["pass"] ?: ""
                                                
                                                GlobalSettings.setCPField(context, "type", proxyType)
                                                GlobalSettings.setCPField(context, "host", proxyHost)
                                                GlobalSettings.setCPField(context, "port", proxyPort)
                                                GlobalSettings.setCPField(context, "user", proxyUser)
                                                GlobalSettings.setCPField(context, "pass", proxyPass)
                                                Toast.makeText(context, "Proxy parsed!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Failed to parse link", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Clipboard and field are empty", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste and parse")
                                }
                            }
                        )
                        Spacer(Modifier.height(4.dp))

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
                                    if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                                        presetName = "$proxyHost:$proxyPort"
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
                                        proxyType = preset.type
                                        proxyHost = preset.host
                                        proxyPort = preset.port
                                        proxyUser = preset.user
                                        proxyPass = preset.pass
                                        GlobalSettings.setCPField(context, "type", proxyType)
                                        GlobalSettings.setCPField(context, "host", proxyHost)
                                        GlobalSettings.setCPField(context, "port", proxyPort)
                                        GlobalSettings.setCPField(context, "user", proxyUser)
                                        GlobalSettings.setCPField(context, "pass", proxyPass)
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

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = {
                                    proxyHost = it
                                    GlobalSettings.setCPField(context, "host", it)
                                },
                                label = { Text(stringResource(R.string.first_start_proxy_host_label)) },
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = {
                                    proxyPort = it
                                    GlobalSettings.setCPField(context, "port", it)
                                },
                                label = { Text(stringResource(R.string.first_start_proxy_port_label)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }
    }

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
                        val nameToSave = presetName.trim().ifEmpty { "$proxyHost:$proxyPort" }
                        val newPreset = GlobalSettings.ProxyPreset(
                            name = nameToSave,
                            type = proxyType,
                            host = proxyHost,
                            port = proxyPort,
                            user = proxyUser,
                            pass = proxyPass
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
fun SlideLogin(profilePrefs: android.content.SharedPreferences) {
    var authKey by remember { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }

    SlideContainer(
        icon = Icons.Default.VpnKey,
        title = stringResource(R.string.first_start_login_title),
        description = stringResource(R.string.first_start_login_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = authKey,
                onValueChange = {
                    authKey = it
                    profilePrefs.edit().putString("authkey", it).apply()
                },
                label = { Text(stringResource(R.string.first_start_authkey_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.first_start_key_oauth_hint_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.first_start_key_oauth_hint_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SlidePermissions() {
    val context = LocalContext.current
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var isBatteryIgnored by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else true
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    SlideContainer(
        icon = Icons.Default.Lock,
        title = stringResource(R.string.first_start_permissions_title),
        description = stringResource(R.string.first_start_permissions_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Allow Notifications",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryIgnored) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ignore Battery Optimization",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Prevents background service from being killed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                    Toast.makeText(context, "Find TailSocks and disable optimization", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Configure")
                        }
                    }
                }
            }
        }
    }
}
