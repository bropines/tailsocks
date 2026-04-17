package io.github.bropines.tailscaled

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("appctr", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // Состояния для всех настроек
    var authKey by remember { mutableStateOf(prefs.getString("authkey", "") ?: "") }
    var hostname by remember { mutableStateOf(prefs.getString("hostname", "") ?: "") }
    var loginServer by remember { mutableStateOf(prefs.getString("login_server", "") ?: "") }
    
    var socks5 by remember { mutableStateOf(prefs.getString("socks5", "127.0.0.1:48115") ?: "127.0.0.1:48115") }
    var httpProxy by remember { mutableStateOf(prefs.getString("httpproxy", "") ?: "") }

    var socks5User by remember { mutableStateOf(prefs.getString("socks5_user", "") ?: "") }
    var socks5Pass by remember { mutableStateOf(prefs.getString("socks5_pass", "") ?: "") }
    
    var exitNodeIp by remember { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }
    var exitNodeAllowLan by remember { mutableStateOf(prefs.getBoolean("exit_node_allow_lan", false)) }
    
    var dnsFallback1 by remember { mutableStateOf(prefs.getString("dns_fallback1", "8.8.8.8:53") ?: "8.8.8.8:53") }
    var dnsFallback2 by remember { mutableStateOf(prefs.getString("dns_fallback2", "1.1.1.1:53") ?: "1.1.1.1:53") }
    var dohUrl by remember { mutableStateOf(prefs.getString("doh_url", "https://1.1.1.1/dns-query") ?: "https://1.1.1.1/dns-query") }
    
    var acceptRoutes by remember { mutableStateOf(prefs.getBoolean("accept_routes", false)) }
    var acceptDns by remember { mutableStateOf(prefs.getBoolean("accept_dns", true)) }
    //var forceReset by remember { mutableStateOf(prefs.getBoolean("force_reset", false)) }
    var extraArgs by remember { mutableStateOf(prefs.getString("extra_args_raw", "") ?: "") }

    var enableWebUi by remember { mutableStateOf(prefs.getBoolean("enable_webui", false)) }
    var webUiPort by remember { mutableStateOf(prefs.getString("webui_port", "127.0.0.1:8080") ?: "127.0.0.1:8080") }

    var dnsProxy by remember { mutableStateOf(prefs.getString("dns_proxy", "127.0.0.1:1053") ?: "127.0.0.1:1053") }

    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoadingExitNodes by remember { mutableStateOf(false) }
    var exitNodesExpanded by remember { mutableStateOf(false) }

    var latestVersion by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var autoRefreshConfig by remember { mutableStateOf(prefs.getBoolean("auto_refresh_config", false)) }
    var forceBg by remember { mutableStateOf(prefs.getBoolean("force_bg", false)) }
    var settingsChanged by remember { mutableStateOf(false) }
    
    val currentVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" }
        catch (e: Exception) { "0.0.0" }
    }

    // Лаунчер для создания файла бэкапа
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val allPrefs = prefs.all
                    val json = Gson().toJson(allPrefs)
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(json.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Settings exported successfully!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Лаунчер для выбора файла для восстановления
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val json = reader.use { r -> r.readText() }
                    
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val importedData: Map<String, Any> = Gson().fromJson(json, type)
                    
                    val editor = prefs.edit()
                    importedData.forEach { (key, value) ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Double -> {
                                // GSON парсит числа как Double по умолчанию, пробуем сохранить как Int если это возможно
                                if (value == value.toInt().toDouble()) {
                                    editor.putInt(key, value.toInt())
                                } else if (value == value.toLong().toDouble()) {
                                    editor.putLong(key, value.toLong())
                                }
                            }
                        }
                    }
                    editor.apply()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Settings restored! Restarting UI...", Toast.LENGTH_SHORT).show()
                        // Перезагружаем текущую активность для обновления состояний
                        (context as? SettingsActivity)?.recreate()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Функция сохранения
    fun save(key: String, value: Any) {
        val editor = prefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
        editor.apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item { SectionTitle("Authentication") }
            item {
                SettingsTextField("Auth Key", authKey, "tskey-auth-...") {
                    authKey = it
                    save("authkey", it)
                }
            }

            item { SectionTitle("Node Configuration") }
            item {
                SettingsTextField("Hostname", hostname, "android-device") {
                    hostname = it
                    save("hostname", it)
                }
                SettingsTextField("Login Server", loginServer, "https://controlplane.tailscale.com") {
                    loginServer = it
                    save("login_server", it)
                }
            }

            item { SectionTitle("Exit Node") }
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = exitNodeIp,
                        onValueChange = { 
                            exitNodeIp = it
                            save("exit_node_ip", it)
                        },
                        label = { Text("Exit Node IP/Name") },
                        placeholder = { Text("100.x.y.z") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { 
                                exitNodesExpanded = true 
                                if (exitNodes.isEmpty()) {
                                    isLoadingExitNodes = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val json = Appctr.runTailscaleCmd("status --json")
                                            val status = Gson().fromJson(json, StatusResponse::class.java)
                                            val nodes = status.peers?.values?.filter { it.exitNodeOption == true } ?: emptyList()
                                            withContext(Dispatchers.Main) {
                                                exitNodes = nodes
                                                isLoadingExitNodes = false
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                isLoadingExitNodes = false
                                            }
                                        }
                                    }
                                }
                            }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Exit Node")
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = exitNodesExpanded,
                        onDismissRequest = { exitNodesExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        if (isLoadingExitNodes) {
                            DropdownMenuItem(text = { Text("Loading...") }, onClick = {})
                        } else {
                            DropdownMenuItem(
                                text = { Text("None (Clear)") },
                                onClick = { 
                                    exitNodeIp = ""
                                    save("exit_node_ip", "")
                                    exitNodesExpanded = false
                                }
                            )
                            if (exitNodes.isEmpty()) {
                                DropdownMenuItem(text = { Text("No exit nodes found") }, onClick = {})
                            } else {
                                exitNodes.forEach { node ->
                                    DropdownMenuItem(
                                        text = { Text("${node.getDisplayName()} (${node.getPrimaryIp()})") },
                                        onClick = { 
                                            exitNodeIp = node.getPrimaryIp()
                                            save("exit_node_ip", exitNodeIp)
                                            exitNodesExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                SettingsSwitch("Allow LAN Access", exitNodeAllowLan) {
                    exitNodeAllowLan = it
                    save("exit_node_allow_lan", it)
                }
            }

            item { SectionTitle("Proxy Ports") }
            item {
                OutlinedTextField(
                    value = socks5,
                    onValueChange = { 
                        socks5 = it
                        save("socks5", it)
                    },
                    label = { Text("SOCKS5 Proxy") },
                    placeholder = { Text("127.0.0.1:1055") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val randomPort = Random.nextInt(10000, 60000)
                            val ip2 = Random.nextInt(0, 256)
                            val ip3 = Random.nextInt(0, 256)
                            val ip4 = Random.nextInt(1, 255)
                            val newAddress = "127.$ip2.$ip3.$ip4:$randomPort"
                            socks5 = newAddress
                            save("socks5", newAddress)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Random Port and IP")
                        }
                    }
                )
                
                SettingsTextField("HTTP Proxy", httpProxy, "127.0.0.1:1057") {
                    httpProxy = it
                    save("httpproxy", it)
                }
                
                Spacer(Modifier.height(8.dp))
                SettingsTextField("SOCKS5 Username (optional)", socks5User, "Leave blank to disable auth") {
                    socks5User = it
                    save("socks5_user", it)
                }
                SettingsTextField("SOCKS5 Password (optional)", socks5Pass, "Leave blank to disable auth") {
                    socks5Pass = it
                    save("socks5_pass", it)
                }
            }

            item { SectionTitle("DNS Settings") }
            item {
                SettingsTextField("DNS Proxy Listen Address", dnsProxy, "127.0.0.1:1053") {
                    dnsProxy = it
                    save("dns_proxy", it)
                }
                SettingsTextField("DNS Fallback 1", dnsFallback1, "8.8.8.8:53") {
                    dnsFallback1 = it
                    save("dns_fallback1", it)
                }
                SettingsTextField("DNS Fallback 2", dnsFallback2, "1.1.1.1:53") {
                    dnsFallback2 = it
                    save("dns_fallback2", it)
                }
                SettingsTextField("DoH URL (Fallback)", dohUrl, "https://dns.google/dns-query") {
                    dohUrl = it
                    save("doh_url", it)
                }
                SettingsSwitch("Accept DNS from Tailscale", acceptDns) {
                    acceptDns = it
                    save("accept_dns", it)
                }
            }

            item { SectionTitle("Web UI") }
            item {
                SettingsSwitch("Enable Local Web Admin", enableWebUi) {
                    enableWebUi = it
                    save("enable_webui", it)
                }
                if (enableWebUi) {
                    SettingsTextField("Listen Address", webUiPort, "127.0.0.1:8080") {
                        webUiPort = it
                        save("webui_port", it)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW, 
                                android.net.Uri.parse("http://$webUiPort")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Open Web UI in Browser")
                    }
                }
            }

            item { SectionTitle("Advanced") }
            item {
                SettingsSwitch("Force Background Run", forceBg) {
                    forceBg = it
                    save("force_bg", it)
                }
                Text(
                    "Try to hold WakeLock and restart service if killed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SettingsSwitch("Accept Routes", acceptRoutes) {
                    acceptRoutes = it
                    save("accept_routes", it)
                }
                //SettingsSwitch("Force Hard Reset (--reset)", forceReset) {
                //    forceReset = it
                //    save("force_reset", it)
                //}
                SettingsTextField("Extra Arguments (Raw)", extraArgs, "--advertise-tags=tag:server") {
                    extraArgs = it
                    save("extra_args_raw", it)
                }
            }

            item { SectionTitle("Maintenance") }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Button(
                        onClick = { createDocumentLauncher.launch("tailsocks_backup.json") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { openDocumentLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Upload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import")
                    }
                }
            }

            item { SectionTitle("Tailscale Admin") }
            item {
                SettingsSwitch("Auto-refresh configuration", autoRefreshConfig) {
                    autoRefreshConfig = it
                    save("auto_refresh_config", it)
                }
                Text(
                    "Automatically runs 'tailscale up' to sync tags/policies (every 15s).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsTextField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true
    )
}

@Composable
fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}