package io.github.bropines.tailscaled

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetcheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                NetcheckScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetcheckScreen(onBack: () -> Unit) {
    var output by remember { mutableStateOf("Press Refresh to run health diagnostics...") }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Netcheck Report", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun runDiagnostics() {
        isRunning = true
        output = "Analyzing connection..."
        scope.launch(Dispatchers.IO) {
            var rawStatus = ""
            var rawNetcheck = ""
            try {
                rawStatus = Appctr.getStatusFromAPI()
                android.util.Log.d("Netcheck", "Raw Status: $rawStatus")
                
                val statusElement = com.google.gson.JsonParser.parseString(rawStatus)
                if (statusElement == null || statusElement.isJsonNull) throw Exception("Status API returned null")
                val status = statusElement.asJsonObject
                
                if (status.has("Error") && !status.get("Error").isJsonNull) {
                    throw Exception(status.get("Error").asString)
                }

                val self = if (status.has("Self") && !status.get("Self").isJsonNull) status.getAsJsonObject("Self") else null
                
                val online = self?.get("Online")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                val relay = self?.get("Relay")?.let { if (it.isJsonPrimitive) it.asString else "Direct (P2P)" } ?: "Direct (P2P)"
                val tailscaleIp = self?.getAsJsonArray("TailscaleIPs")?.let { 
                    if (it.size() > 0 && !it.get(0).isJsonNull) it.get(0).asString else "Unknown" 
                } ?: "Unknown"

                val healthOutput = StringBuilder()
                healthOutput.append("--- CONNECTION HEALTH ---\n")
                healthOutput.append("Status: ${if (online) "🟢 ONLINE" else "🔴 OFFLINE"}\n")
                healthOutput.append("Tailscale IP: $tailscaleIp\n")
                healthOutput.append("Relay Node: $relay\n")
                
                if (relay != "Direct (P2P)") {
                    healthOutput.append("Traffic Mode: 🔀 Via Relay (UDP might be restricted)\n")
                } else {
                    healthOutput.append("Traffic Mode: 🚀 Direct P2P (UDP is working)\n")
                }

                // Perform NATIVE netcheck via bridge
                healthOutput.append("\n--- RUNNING DIAGNOSTICS ---\n")
                rawNetcheck = Appctr.getNetcheckFromAPI()
                android.util.Log.d("Netcheck", "Raw Netcheck: $rawNetcheck")

                val netcheckElement = com.google.gson.JsonParser.parseString(rawNetcheck)
                if (netcheckElement != null && !netcheckElement.isJsonNull && netcheckElement.isJsonObject) {
                    val netcheck = netcheckElement.asJsonObject
                    if (!netcheck.has("Error") || netcheck.get("Error").isJsonNull) {
                        val udp = netcheck.get("UDP")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        val ipv4 = netcheck.get("IPv4")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        val ipv6 = netcheck.get("IPv6")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        val mappingVaries = netcheck.get("MappingVariesByDestIP")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        
                        healthOutput.append("UDP: ${if (udp) "✅ Working" else "❌ Blocked"}\n")
                        healthOutput.append("IPv4: ${if (ipv4) "✅ Yes" else "❌ No"}\n")
                        healthOutput.append("IPv6: ${if (ipv6) "✅ Yes" else "❌ No"}\n")
                        healthOutput.append("NAT Mapping Varies: ${if (mappingVaries) "⚠️ Yes (Symmetric NAT)" else "✅ No"}\n")
                        
                        val preferredDerp = netcheck.get("PreferredDERP")?.let { if (it.isJsonPrimitive) it.asInt else 0 } ?: 0
                        if (preferredDerp != 0) {
                            healthOutput.append("Nearest DERP ID: $preferredDerp\n")
                        }
                        
                        val derpLatency = if (netcheck.has("DERPLatency") && !netcheck.get("DERPLatency").isJsonNull) netcheck.getAsJsonObject("DERPLatency") else null
                        if (derpLatency != null && derpLatency.size() > 0) {
                            healthOutput.append("\n--- DERP LATENCY ---\n")
                            derpLatency.entrySet().take(10).forEach { entry ->
                                if (!entry.value.isJsonNull) {
                                    val latency = entry.value.asDouble * 1000
                                    healthOutput.append("${entry.key}: ${"%.1f".format(latency)}ms\n")
                                }
                            }
                        }
                    } else {
                        healthOutput.append("❌ Diagnostic Error: ${netcheck.get("Error").asString}\n")
                    }
                } else {
                    healthOutput.append("❌ Diagnostic Error: Received invalid response from bridge\n")
                }

                healthOutput.append("\n--- PEER SUMMARY ---\n")
                val peers = if (status.has("Peer") && !status.get("Peer").isJsonNull) status.getAsJsonObject("Peer") else null
                if (peers != null) {
                    val peerCount = peers.size()
                    val onlinePeers = peers.entrySet().count { entry ->
                        val p = entry.value
                        p.isJsonObject && p.asJsonObject.get("Online")?.let { o -> if (o.isJsonPrimitive) o.asBoolean else false } ?: false 
                    }
                    healthOutput.append("Total Peers: $peerCount\n")
                    healthOutput.append("Online Peers: $onlinePeers\n")
                }

                withContext(Dispatchers.Main) {
                    output = healthOutput.toString()
                    isRunning = false
                }
            } catch (e: Exception) {
                android.util.Log.e("Netcheck", "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    output = "Error fetching diagnostics: ${e.message}\n\n" +
                             "Raw Status: ${rawStatus.take(200)}...\n" +
                             "Raw Netcheck: ${rawNetcheck.take(200)}...\n\n" +
                             "Make sure Tailscale is connected and running."
                    isRunning = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { copyToClipboard(output) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report")
                    }
                    IconButton(onClick = { runDiagnostics() }, enabled = !isRunning) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Run Diagnostics")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Diagnostics help you understand NAT traversal and DERP relay usage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
