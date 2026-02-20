package io.github.bropines.tailscaled

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast // <-- ДОБАВЛЕН ИМПОРТ TOAST
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // <-- ИСПОЛЬЗУЕМ СТАНДАРТНЫЕ ИКОНКИ
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import appctr.Appctr
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appctr", Context.MODE_PRIVATE) }
    val keyPrefs = remember { context.getSharedPreferences("auth_keys_store", Context.MODE_PRIVATE) }

    fun saveStr(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun saveBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    // Стейты полей
    var forceBg by remember { mutableStateOf(prefs.getBoolean("force_bg", false)) }
    var authKey by remember { mutableStateOf(prefs.getString("authkey", "") ?: "") }
    var hostname by remember { mutableStateOf(prefs.getString("hostname", "") ?: "") }
    var loginServer by remember { mutableStateOf(prefs.getString("login_server", "") ?: "") }
    
    var acceptRoutes by remember { mutableStateOf(prefs.getBoolean("accept_routes", false)) }
    var acceptDns by remember { mutableStateOf(prefs.getBoolean("accept_dns", true)) }
    var socks5Addr by remember { mutableStateOf(prefs.getString("socks5", "127.0.0.1:1055") ?: "") }
    
    var advertiseExitNode by remember { mutableStateOf(prefs.getBoolean("advertise_exit_node", false)) }
    var exitNodeIp by remember { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }
    var allowLan by remember { mutableStateOf(prefs.getBoolean("exit_node_allow_lan", false)) }
    
    var sshAddr by remember { mutableStateOf(prefs.getString("sshserver", "127.0.0.1:1056") ?: "") }
    var extraArgs by remember { mutableStateOf(prefs.getString("extra_args_raw", "") ?: "") }

    var generalExpanded by remember { mutableStateOf(true) }
    var networkExpanded by remember { mutableStateOf(true) }
    var exitNodeExpanded by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(true) }

    var availableExitNodes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var exitNodeDropdownExpanded by remember { mutableStateOf(false) }

    var showKeysDialog by remember { mutableStateOf(false) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var newKeyInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (Appctr.isRunning()) {
                    val jsonOutput = Appctr.runTailscaleCmd("status --json")
                    val status = Gson().fromJson(jsonOutput, StatusResponse::class.java)
                    val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.map {
                        (it.getDisplayName()) to it.getPrimaryIp()
                    } ?: emptyList()
                    availableExitNodes = nodes
                }
            } catch (e: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // ВАЛИДАЦИЯ В НАСТРОЙКАХ
                val currentAuthKey = prefs.getString("authkey", "") ?: ""
                if (currentAuthKey.isBlank()) {
                    Toast.makeText(context, "🚫 Error: Cannot restart without Auth Key!", Toast.LENGTH_LONG).show()
                    return@FloatingActionButton
                }

                val stopIntent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                context.startService(stopIntent)
                Handler(Looper.getMainLooper()).postDelayed({
                    val startIntent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(context, startIntent)
                }, 800)
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart Service")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Force Background Run", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Switch(
                    checked = forceBg,
                    onCheckedChange = { forceBg = it; saveBool("force_bg", it) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            SectionHeader(title = "General", expanded = generalExpanded) { generalExpanded = !generalExpanded }
            AnimatedVisibility(visible = generalExpanded) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    OutlinedTextField(
                        value = authKey,
                        onValueChange = { authKey = it; saveStr("authkey", it) },
                        label = { Text("Auth Key") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showKeysDialog = true }) {
                                Icon(Icons.Default.List, contentDescription = "Keys")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { newValue ->
                            val filtered = newValue.filter { it.isLetterOrDigit() || it == '-' }
                            hostname = filtered
                            saveStr("hostname", filtered)
                        },
                        label = { Text("Device Hostname") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = loginServer,
                        onValueChange = { loginServer = it; saveStr("login_server", it) },
                        label = { Text("Login Server (Headscale)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SectionHeader(title = "Network", expanded = networkExpanded) { networkExpanded = !networkExpanded }
            AnimatedVisibility(visible = networkExpanded) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    RowItemSwitch("Accept Routes", acceptRoutes) { acceptRoutes = it; saveBool("accept_routes", it) }
                    RowItemSwitch("Accept DNS", acceptDns) { acceptDns = it; saveBool("accept_dns", it) }
                    OutlinedTextField(
                        value = socks5Addr,
                        onValueChange = { socks5Addr = it; saveStr("socks5", it) },
                        label = { Text("Socks5 Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SectionHeader(title = "Exit Node", expanded = exitNodeExpanded) { exitNodeExpanded = !exitNodeExpanded }
            AnimatedVisibility(visible = exitNodeExpanded) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    RowItemSwitch("Advertise Exit Node", advertiseExitNode) { advertiseExitNode = it; saveBool("advertise_exit_node", it) }
                    
                    ExposedDropdownMenuBox(
                        expanded = exitNodeDropdownExpanded,
                        onExpandedChange = { exitNodeDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = exitNodeIp,
                            onValueChange = { exitNodeIp = it; saveStr("exit_node_ip", it) },
                            label = { Text("Use Exit Node (IP)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = exitNodeDropdownExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        if (availableExitNodes.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = exitNodeDropdownExpanded,
                                onDismissRequest = { exitNodeDropdownExpanded = false }
                            ) {
                                availableExitNodes.forEach { (name, ip) ->
                                    DropdownMenuItem(
                                        text = { Text("$name ($ip)") },
                                        onClick = {
                                            exitNodeIp = ip
                                            saveStr("exit_node_ip", ip)
                                            exitNodeDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    RowItemSwitch("Allow LAN Access", allowLan) { allowLan = it; saveBool("exit_node_allow_lan", it) }
                }
            }

            SectionHeader(title = "Advanced", expanded = advancedExpanded) { advancedExpanded = !advancedExpanded }
            AnimatedVisibility(visible = advancedExpanded) {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    OutlinedTextField(
                        value = sshAddr,
                        onValueChange = { sshAddr = it; saveStr("sshserver", it) },
                        label = { Text("SSH Server Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extraArgs,
                        onValueChange = { extraArgs = it; saveStr("extra_args_raw", it) },
                        label = { Text("Extra Arguments (Raw)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showKeysDialog) {
        val keysList = keyPrefs.getStringSet("keys_list", emptySet())?.toList()?.sorted() ?: emptyList()
        AlertDialog(
            onDismissRequest = { showKeysDialog = false },
            title = { Text("Manage Auth Keys") },
            text = {
                Column {
                    if (keysList.isEmpty()) {
                        Text("No saved keys.")
                    } else {
                        keysList.forEach { key ->
                            Text(
                                text = key,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        authKey = key
                                        saveStr("authkey", key)
                                        showKeysDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddKeyDialog = true }) { Text("Add New") }
            },
            dismissButton = {
                TextButton(onClick = { keyPrefs.edit().clear().apply(); showKeysDialog = false }) { Text("Clear All") }
            }
        )
    }

    if (showAddKeyDialog) {
        AlertDialog(
            onDismissRequest = { showAddKeyDialog = false },
            title = { Text("Add Auth Key") },
            text = {
                OutlinedTextField(
                    value = newKeyInput,
                    onValueChange = { newKeyInput = it },
                    label = { Text("New Key") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newKeyInput.isNotBlank()) {
                        val current = keyPrefs.getStringSet("keys_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        current.add(newKeyInput.trim())
                        keyPrefs.edit().putStringSet("keys_list", current).apply()
                        
                        authKey = newKeyInput.trim()
                        saveStr("authkey", authKey)
                        newKeyInput = ""
                    }
                    showAddKeyDialog = false
                    showKeysDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddKeyDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun RowItemSwitch(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}