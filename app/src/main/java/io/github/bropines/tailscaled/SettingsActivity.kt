package io.github.bropines.tailscaled

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
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

    // Состояния для всех настроек
    var authKey by remember { mutableStateOf(prefs.getString("authkey", "") ?: "") }
    var hostname by remember { mutableStateOf(prefs.getString("hostname", "") ?: "") }
    var loginServer by remember { mutableStateOf(prefs.getString("login_server", "") ?: "") }
    
    var socks5 by remember { mutableStateOf(prefs.getString("socks5", "127.0.0.1:1055") ?: "127.0.0.1:1055") }
    var httpProxy by remember { mutableStateOf(prefs.getString("httpproxy", "127.0.0.1:1057") ?: "127.0.0.1:1057") }
    
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
                SettingsTextField("Exit Node IP/Name", exitNodeIp, "100.x.y.z") {
                    exitNodeIp = it
                    save("exit_node_ip", it)
                }
                SettingsSwitch("Allow LAN Access", exitNodeAllowLan) {
                    exitNodeAllowLan = it
                    save("exit_node_allow_lan", it)
                }
            }

            item { SectionTitle("Proxy Ports") }
            item {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        SettingsTextField("SOCKS5", socks5, "127.0.0.1:1055") {
                            socks5 = it
                            save("socks5", it)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        SettingsTextField("HTTP", httpProxy, "127.0.0.1:1057") {
                            httpProxy = it
                            save("httpproxy", it)
                        }
                    }
                }
            }

            item { SectionTitle("DNS Settings") }
            item {
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