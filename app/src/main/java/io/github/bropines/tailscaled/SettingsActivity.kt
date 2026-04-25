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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        setContent { TailSocksTheme { SettingsScreen(onBack = { finish() }) } }
    }
}

fun generateRandomString(length: Int = 12): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length).map { allowedChars.random() }.joinToString("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val prefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    var taildropRootUri by remember { mutableStateOf(GlobalSettings.getTaildropRootUri(context)) }
    var autoStart by remember { mutableStateOf(GlobalSettings.isAutoStartEnabled(context)) }
    
    var showProxyDialog by remember { mutableStateOf(false) }
    var isProxyEnabled by remember { mutableStateOf(GlobalSettings.isCPProxyEnabled(context)) }

    var authKey by remember { mutableStateOf(prefs.getString("authkey", "") ?: "") }
    var socks5 by remember { mutableStateOf(prefs.getString("socks5", "127.0.0.1:48115") ?: "") }
    var socks5User by remember { mutableStateOf(prefs.getString("socks5_user", "") ?: "") }
    var socks5Pass by remember { mutableStateOf(prefs.getString("socks5_pass", "") ?: "") }
    var httpProxy by remember { mutableStateOf(prefs.getString("httpproxy", "") ?: "") }
    var controlProxy by remember { mutableStateOf(prefs.getString("control_proxy", "") ?: "") }
    var hostname by remember { mutableStateOf(prefs.getString("hostname", "") ?: "") }
    var loginServer by remember { mutableStateOf(prefs.getString("login_server", "") ?: "") }
    var exitNodeIp by remember { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }
    var dnsProxy by remember { mutableStateOf(prefs.getString("dns_proxy", "127.0.0.1:1053") ?: "") }
    var dnsFallbacks by remember { mutableStateOf(prefs.getString("dns_fallbacks", "8.8.8.8:53,1.1.1.1:53") ?: "") }
    var extraArgs by remember { mutableStateOf(prefs.getString("extra_args_raw", "") ?: "") }
    
    var autoRefresh by remember { mutableStateOf(prefs.getBoolean("auto_refresh", false)) }
    var acceptRoutes by remember { mutableStateOf(prefs.getBoolean("accept_routes", false)) }
    var acceptDns by remember { mutableStateOf(prefs.getBoolean("accept_dns", true)) }
    var forceBg by remember { mutableStateOf(prefs.getBoolean("force_bg", false)) }
    
    var enableWebUI by remember { mutableStateOf(prefs.getBoolean("enable_webui", false)) }
    var webUIAddr by remember { mutableStateOf(prefs.getString("webui_addr", "127.0.0.1:8080") ?: "127.0.0.1:8080") }
    var detailedLogs by remember { mutableStateOf(prefs.getBoolean("detailed_logs", false)) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { GlobalSettings.setTaildropRootUri(context, uri); taildropRootUri = uri }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val allPrefs = prefs.all
                    val backupData = mapOf("account" to activeAccount, "settings" to allPrefs)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(Gson().toJson(backupData).toByteArray()) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun savePref(key: String, value: Any?) {
        val editor = prefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        }
        editor.apply()
        if (key == "detailed_logs") Appctr.setLogLevel(if (value as Boolean) 0 else 1)
        context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
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
            title = { Text("Reset Node State?") },
            text = { Text("This will call 'tailscale up --reset', clearing all flags and re-authenticating. Continue?") },
            confirmButton = {
                Button(onClick = { savePref("do_reset", true); showResetDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            
            SettingsSectionHeader("Global Settings")
            SettingsClickableItem("Taildrop Storage Folder", taildropRootUri?.path ?: "Uses app internal folder", Icons.Default.Folder) { folderPicker.launch(null) }
            SettingsSwitchItem("Auto-start on Boot", "Start TailSocks when device turns on", Icons.Default.PowerSettingsNew, autoStart) { GlobalSettings.setAutoStartEnabled(context, it); autoStart = it }

            SettingsClickableItem(
                "Control Plane Proxy", 
                if (isProxyEnabled) "Enabled (${GlobalSettings.getCPField(context, "type")})" else "Disabled",
                Icons.Default.Shield
            ) { showProxyDialog = true }

            if (showProxyDialog) {
                ControlProxyDialog(
                    onDismiss = { showProxyDialog = false },
                    onApply = { 
                        isProxyEnabled = GlobalSettings.isCPProxyEnabled(context)
                        context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
                    }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SettingsSectionHeader("Account: ${activeAccount.name}")
            SettingsEditItem("Auth Key", authKey, Icons.Default.VpnKey) { authKey = it; savePref("authkey", it) }
            SettingsEditItem("Hostname", hostname, Icons.Default.Badge, onAction = { android.os.Build.MODEL.replace(" ", "-").lowercase() }, actionIcon = Icons.Default.AutoFixHigh) { hostname = it; savePref("hostname", it) }
            
            SettingsSectionHeader("Networking")
            SettingsEditItem("SOCKS5 Address", socks5, Icons.Default.Language) { socks5 = it; savePref("socks5", it) }
            SettingsEditItem("SOCKS5 User", socks5User, Icons.Default.Person, onAction = { generateRandomString(8) }, actionIcon = Icons.Default.Casino) { socks5User = it; savePref("socks5_user", it) }
            SettingsEditItem("SOCKS5 Pass", socks5Pass, Icons.Default.Password, onAction = { generateRandomString(12) }, actionIcon = Icons.Default.Casino) { socks5Pass = it; savePref("socks5_pass", it) }
            
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { copySagerNetLink() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Copy SagerNet Link")
            }
            Spacer(Modifier.height(8.dp))

            SettingsEditItem("HTTP Proxy", httpProxy, Icons.Default.Http) { httpProxy = it; savePref("httpproxy", it) }
            SettingsEditItem("DNS Proxy", dnsProxy, Icons.Default.Toll) { dnsProxy = it; savePref("dns_proxy", it) }
            SettingsEditItem("DNS Fallbacks", dnsFallbacks, Icons.Default.List, placeholder = "8.8.8.8:53,1.1.1.1:53") { dnsFallbacks = it; savePref("dns_fallbacks", it) }
            SettingsExitNodeItem("Exit Node", exitNodeIp, Icons.Default.Input) { exitNodeIp = it; savePref("exit_node_ip", it) }

            SettingsSectionHeader("Web Interface")
            SettingsSwitchItem("Enable Web UI", "Run built-in Tailscale web server", Icons.Default.Web, enableWebUI) { enableWebUI = it; savePref("enable_webui", it) }
            if (enableWebUI) SettingsEditItem("Web UI Address", webUIAddr, Icons.Default.Link) { webUIAddr = it; savePref("webui_addr", it) }

            SettingsSectionHeader("Flags & Logs")
            SettingsSwitchItem("Accept Routes", "Allow network to set routes", Icons.Default.Map, acceptRoutes) { acceptRoutes = it; savePref("accept_routes", it) }
            SettingsSwitchItem("Accept DNS", "Allow network to set DNS", Icons.Default.Dns, acceptDns) { acceptDns = it; savePref("accept_dns", it) }
            SettingsSwitchItem("Auto-Refresh", "Sync policies every 15s", Icons.Default.Sync, autoRefresh) { autoRefresh = it; savePref("auto_refresh", it) }
            SettingsSwitchItem("Force Background", "Keep WakeLock active", Icons.Default.BatteryFull, forceBg) { forceBg = it; savePref("force_bg", it) }
            SettingsSwitchItem("Detailed Logs", "Disable log filtering (noisy!)", Icons.Default.BugReport, detailedLogs) { detailedLogs = it; savePref("detailed_logs", it) }

            SettingsSectionHeader("Advanced")
            SettingsEditItem("Extra Arguments", extraArgs, Icons.Default.Code, "--flag=val ...") { extraArgs = it; savePref("extra_args_raw", it) }
            
            Spacer(Modifier.height(16.dp))
            Button(onClick = { backupLauncher.launch("tailsocks_backup_${activeAccount.name}.json") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("Backup Account Settings")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showResetDialog = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text("Reset Node State")
            }
            Spacer(Modifier.height(32.dp))
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
                
                Text("Proxy Type", style = MaterialTheme.typography.labelMedium)
                Row {
                    listOf("SOCKS5", "HTTP").forEach { t ->
                        Row(Modifier.clickable { type = t }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = (type == t), onClick = null)
                            Text(t, Modifier.padding(start = 4.dp))
                        }
                    }
                }
                
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if(type == "HTTP") "8080" else "1080") })
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
fun SettingsSectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
fun SettingsClickableItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
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
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
fun SettingsEditItem(
    title: String, 
    value: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    placeholder: String = "", 
    onAction: (() -> String)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(showDialog) { if (showDialog) text = value }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (value.isEmpty()) placeholder.ifEmpty { "Not set" } else value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { showDialog = true }
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { 
                OutlinedTextField(
                    value = text, 
                    onValueChange = { text = it }, 
                    modifier = Modifier.fillMaxWidth(), 
                    singleLine = true,
                    trailingIcon = if (onAction != null && actionIcon != null) {
                        { IconButton(onClick = { text = onAction() }) { Icon(actionIcon, null) } }
                    } else null
                ) 
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
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { showDialog = true }
    )
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
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (currentValue.isEmpty()) "None" else currentValue, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { 
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
        }
    )

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
                                modifier = Modifier.clickable { onSave(""); showDialog = false }
                            )
                        }
                        items(exitNodes.size) { i ->
                            val node = exitNodes[i]
                            ListItem(
                                headlineContent = { Text(node.getDisplayName()) },
                                supportingContent = { Text(node.getPrimaryIp()) },
                                leadingContent = { Icon(Icons.Default.VpnKey, null) },
                                modifier = Modifier.clickable { onSave(node.getPrimaryIp()); showDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
