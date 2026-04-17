package io.github.bropines.tailscaled

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var connectionSummary by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runDiagnostics() {
        isRunning = true
        output = "Analyzing connection..."
        scope.launch(Dispatchers.IO) {
            try {
                val json = Appctr.runTailscaleCmd("status --json")
                val status = com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
                
                val self = status.getAsJsonObject("Self")
                val online = self.get("Online")?.asBoolean ?: false
                val relay = self.get("Relay")?.asString ?: "Direct (P2P)"
                val tailscaleIp = self.getAsJsonArray("TailscaleIPs")?.get(0)?.asString ?: "Unknown"

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

                healthOutput.append("\n--- PEER SUMMARY ---\n")
                val peers = status.getAsJsonObject("Peer")
                if (peers != null) {
                    val peerCount = peers.size()
                    val onlinePeers = peers.entrySet().count { it.value.asJsonObject.get("Online")?.asBoolean == true }
                    healthOutput.append("Total Peers: $peerCount\n")
                    healthOutput.append("Online Peers: $onlinePeers\n")
                }

                withContext(Dispatchers.Main) {
                    output = healthOutput.toString()
                    isRunning = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    output = "Error fetching diagnostics: ${e.message}\n\nMake sure Tailscale is connected."
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
                    IconButton(onClick = { runDiagnostics() }, enabled = !isRunning) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Run Netcheck")
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
                text = "Note: 'netlinkrib: permission denied' is normal on Android 11+ (system restriction). DERP and UDP checks should still work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
