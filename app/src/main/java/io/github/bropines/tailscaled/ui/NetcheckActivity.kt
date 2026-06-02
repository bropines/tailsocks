package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectionStatus(
    val online: Boolean,
    val tailscaleIp: String,
    val relayNode: String,
    val trafficModeRelay: Boolean
)

data class DiagnosticsReport(
    val udpWorking: Boolean,
    val ipv4Working: Boolean,
    val ipv4Address: String,
    val ipv6Working: Boolean,
    val ipv6Address: String,
    val mappingVaries: Boolean,
    val preferredDerpId: Int,
    val preferredDerpName: String,
    val totalPeers: Int,
    val onlinePeers: Int
)

data class DerpLatencyItem(
    val regionId: Int,
    val code: String,
    val name: String,
    val latencyMs: Double,
    val isPreferred: Boolean
)

object NetcheckCache {
    var lastReportTime: Long = 0
    var daemonStartTime: Long = 0
    var rawTextReport: String = ""
    var connectionStatus: ConnectionStatus? = null
    var diagnosticsReport: DiagnosticsReport? = null
    var derpLatencies: List<DerpLatencyItem> = emptyList()
    var errorMessage: String? = null

    fun clear() {
        lastReportTime = 0
        daemonStartTime = 0
        rawTextReport = ""
        connectionStatus = null
        diagnosticsReport = null
        derpLatencies = emptyList()
        errorMessage = null
    }
}

class NetcheckActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

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
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var rawTextReport by remember { mutableStateOf(NetcheckCache.rawTextReport) }
    
    var connectionStatus by remember { mutableStateOf<ConnectionStatus?>(NetcheckCache.connectionStatus) }
    var diagnosticsReport by remember { mutableStateOf<DiagnosticsReport?>(NetcheckCache.diagnosticsReport) }
    var derpLatencies by remember { mutableStateOf<List<DerpLatencyItem>>(NetcheckCache.derpLatencies) }
    var errorMessage by remember { mutableStateOf<String?>(NetcheckCache.errorMessage) }

    val scope = rememberCoroutineScope()

    fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Netcheck Report", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.netcheck_report_copied), Toast.LENGTH_SHORT).show()
    }

    fun runDiagnostics() {
        isRunning = true
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            var rawStatus = ""
            var rawNetcheck = ""
            try {
                val currentDaemonStart = Appctr.getDaemonStartTime()
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

                rawNetcheck = Appctr.getNetcheckFromAPI()
                android.util.Log.d("Netcheck", "Raw Netcheck: $rawNetcheck")

                val netcheckElement = com.google.gson.JsonParser.parseString(rawNetcheck)
                if (netcheckElement != null && !netcheckElement.isJsonNull && netcheckElement.isJsonObject) {
                    val netcheckObj = netcheckElement.asJsonObject
                    if (!netcheckObj.has("Error") || netcheckObj.get("Error").isJsonNull) {
                        val netcheck = netcheckObj.getAsJsonObject("Report")
                        val derpMetaObj = netcheckObj.getAsJsonObject("DERPMeta")

                        val udp = netcheck.get("UDP")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        val ipv4 = netcheck.get("IPv4")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        val ipv6 = netcheck.get("IPv6")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        val mappingVaries = netcheck.get("MappingVariesByDestIP")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                        
                        val globalV4 = netcheck.get("GlobalV4")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                        val globalV6 = netcheck.get("GlobalV6")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""

                        // Сборка текстового отчета для копирования
                        val healthOutput = StringBuilder()
                        healthOutput.append(context.getString(R.string.netcheck_connection_health))
                        healthOutput.append(context.getString(R.string.netcheck_status, if (online) "🟢 ONLINE" else "🔴 OFFLINE"))
                        healthOutput.append(context.getString(R.string.netcheck_tailscale_ip, tailscaleIp))
                        healthOutput.append(context.getString(R.string.netcheck_relay_node, relay))
                        
                        if (relay != "Direct (P2P)") {
                            healthOutput.append(context.getString(R.string.netcheck_traffic_relay))
                        } else {
                            healthOutput.append(context.getString(R.string.netcheck_traffic_direct))
                        }

                        healthOutput.append(context.getString(R.string.netcheck_running_diagnostics))
                        healthOutput.append(context.getString(R.string.netcheck_udp, if (udp) "✅ Working" else "❌ Blocked"))
                        healthOutput.append(context.getString(R.string.netcheck_ipv4, if (ipv4) "✅ Yes, $globalV4" else "❌ No"))
                        healthOutput.append(context.getString(R.string.netcheck_ipv6, if (ipv6) "✅ Yes, $globalV6" else "❌ No"))
                        healthOutput.append(context.getString(R.string.netcheck_nat_mapping, if (mappingVaries) "⚠️ Yes (Symmetric NAT)" else "✅ No"))

                        val preferredDerp = netcheck.get("PreferredDERP")?.let { if (it.isJsonPrimitive) it.asInt else 0 } ?: 0
                        var preferredDerpName = "Unknown"
                        if (preferredDerp != 0) {
                            val meta = derpMetaObj?.getAsJsonObject(preferredDerp.toString())
                            if (meta != null) {
                                val code = meta.get("Code")?.asString ?: ""
                                val name = meta.get("Name")?.asString ?: ""
                                preferredDerpName = if (name.isNotEmpty()) "$name ($code)" else code
                            } else {
                                preferredDerpName = "Region $preferredDerp"
                            }
                            healthOutput.append(context.getString(R.string.netcheck_nearest_derp, preferredDerp))
                        }

                        val regionLatency = if (netcheck.has("RegionLatency") && !netcheck.get("RegionLatency").isJsonNull) netcheck.getAsJsonObject("RegionLatency") else null
                        val latencyList = mutableListOf<DerpLatencyItem>()
                        if (regionLatency != null && regionLatency.size() > 0) {
                            healthOutput.append(context.getString(R.string.netcheck_derp_latency))
                            regionLatency.entrySet().forEach { entry ->
                                val rId = entry.key.toIntOrNull() ?: 0
                                if (rId != 0 && !entry.value.isJsonNull) {
                                    val durationNs = entry.value.asDouble
                                    val latencyVal = if (durationNs < 1000.0) durationNs * 1000.0 else durationNs / 1_000_000.0
                                    
                                    val meta = derpMetaObj?.getAsJsonObject(rId.toString())
                                    val code = meta?.get("Code")?.asString ?: "region$rId"
                                    val name = meta?.get("Name")?.asString ?: "Region $rId"
                                    
                                    latencyList.add(
                                        DerpLatencyItem(
                                            regionId = rId,
                                            code = code,
                                            name = name,
                                            latencyMs = latencyVal,
                                            isPreferred = rId == preferredDerp
                                        )
                                    )
                                    
                                    healthOutput.append("$code: ${"%.1f".format(latencyVal)}ms ($name)\n")
                                }
                            }
                        }
                        latencyList.sortBy { it.latencyMs }

                        healthOutput.append(context.getString(R.string.netcheck_peer_summary))
                        val peers = if (status.has("Peer") && !status.get("Peer").isJsonNull) status.getAsJsonObject("Peer") else null
                        var peerCount = 0
                        var onlinePeers = 0
                        if (peers != null) {
                            peerCount = peers.size()
                            onlinePeers = peers.entrySet().count { entry ->
                                val p = entry.value
                                p.isJsonObject && p.asJsonObject.get("Online")?.let { o -> if (o.isJsonPrimitive) o.asBoolean else false } ?: false 
                            }
                            healthOutput.append(context.getString(R.string.netcheck_total_peers, peerCount))
                            healthOutput.append(context.getString(R.string.netcheck_online_peers, onlinePeers))
                        }

                        val diagnosticsReportObj = DiagnosticsReport(
                            udpWorking = udp,
                            ipv4Working = ipv4,
                            ipv4Address = globalV4,
                            ipv6Working = ipv6,
                            ipv6Address = globalV6,
                            mappingVaries = mappingVaries,
                            preferredDerpId = preferredDerp,
                            preferredDerpName = preferredDerpName,
                            totalPeers = peerCount,
                            onlinePeers = onlinePeers
                        )

                        val connectionStatusObj = ConnectionStatus(
                            online = online,
                            tailscaleIp = tailscaleIp,
                            relayNode = relay,
                            trafficModeRelay = relay != "Direct (P2P)"
                        )

                        withContext(Dispatchers.Main) {
                            // Update cache
                            NetcheckCache.lastReportTime = System.currentTimeMillis()
                            NetcheckCache.daemonStartTime = currentDaemonStart
                            NetcheckCache.rawTextReport = healthOutput.toString()
                            NetcheckCache.connectionStatus = connectionStatusObj
                            NetcheckCache.diagnosticsReport = diagnosticsReportObj
                            NetcheckCache.derpLatencies = latencyList
                            NetcheckCache.errorMessage = null

                            rawTextReport = NetcheckCache.rawTextReport
                            connectionStatus = connectionStatusObj
                            diagnosticsReport = diagnosticsReportObj
                            derpLatencies = latencyList
                            errorMessage = null
                            isRunning = false
                        }
                    } else {
                        throw Exception(netcheckObj.get("Error").asString)
                    }
                } else {
                    throw Exception("Received invalid response from bridge")
                }
            } catch (e: Exception) {
                android.util.Log.e("Netcheck", "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    NetcheckCache.clear()
                    NetcheckCache.errorMessage = e.message ?: "Unknown error"
                    
                    errorMessage = NetcheckCache.errorMessage
                    connectionStatus = null
                    diagnosticsReport = null
                    derpLatencies = emptyList()
                    isRunning = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val currentDaemonStart = try { Appctr.getDaemonStartTime() } catch (e: Exception) { 0L }
        val isCacheValid = NetcheckCache.lastReportTime > 0 &&
                (System.currentTimeMillis() - NetcheckCache.lastReportTime < 30 * 60 * 1000) &&
                (NetcheckCache.daemonStartTime == currentDaemonStart)

        if (isCacheValid) {
            // Use cached values
            rawTextReport = NetcheckCache.rawTextReport
            connectionStatus = NetcheckCache.connectionStatus
            diagnosticsReport = NetcheckCache.diagnosticsReport
            derpLatencies = NetcheckCache.derpLatencies
            errorMessage = NetcheckCache.errorMessage
        } else {
            runDiagnostics()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.netcheck_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (rawTextReport.isNotEmpty()) {
                        IconButton(onClick = { copyToClipboard(rawTextReport) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.netcheck_cd_copy_report))
                        }
                    }
                    IconButton(onClick = { runDiagnostics() }, enabled = !isRunning) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.netcheck_cd_run_diagnostics))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                isRunning && connectionStatus == null -> {
                    // Loading State
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.netcheck_analyzing),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.netcheck_testing_latency_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                errorMessage != null -> {
                    // Error State
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.netcheck_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage ?: "Unknown error",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { runDiagnostics() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }

                connectionStatus != null && diagnosticsReport != null -> {
                    // Success State Dashboard
                    val status = connectionStatus!!
                    val report = diagnosticsReport!!
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Overview Card
                            ElevatedCard(
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (status.online) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (status.online) Color(0xFF4CAF50).copy(alpha = 0.2f)
                                                else Color(0xFFF44336).copy(alpha = 0.2f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (status.online) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                            contentDescription = null,
                                            tint = if (status.online) Color(0xFF4CAF50) else Color(0xFFF44336),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (status.online) stringResource(R.string.netcheck_connected) else stringResource(R.string.netcheck_offline),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (status.online) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = stringResource(R.string.netcheck_ip_label, status.tailscaleIp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = if (status.trafficModeRelay) stringResource(R.string.netcheck_traffic_relay_desc, status.relayNode)
                                                   else stringResource(R.string.netcheck_traffic_direct_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Protocol capabilities Card
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.netcheck_sect_protocol),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                                    
                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_udp_stun),
                                        success = report.udpWorking,
                                        successText = stringResource(R.string.netcheck_working),
                                        failText = stringResource(R.string.netcheck_blocked)
                                    )
                                    
                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_ipv4_conn),
                                        success = report.ipv4Working,
                                        successText = stringResource(R.string.netcheck_available),
                                        failText = stringResource(R.string.netcheck_unavailable),
                                        subText = report.ipv4Address
                                    )

                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_ipv6_conn),
                                        success = report.ipv6Working,
                                        successText = stringResource(R.string.netcheck_available),
                                        failText = stringResource(R.string.netcheck_unavailable),
                                        subText = report.ipv6Address
                                    )

                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_nat_varies),
                                        success = !report.mappingVaries,
                                        successText = stringResource(R.string.netcheck_nat_varies_no),
                                        failText = stringResource(R.string.netcheck_nat_varies_yes),
                                        warnStyle = report.mappingVaries
                                    )

                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_peers_map),
                                        success = report.onlinePeers > 0,
                                        successText = stringResource(R.string.netcheck_peers_online_format, report.onlinePeers, report.totalPeers),
                                        failText = stringResource(R.string.netcheck_peers_online_none, report.totalPeers)
                                    )
                                }
                            }
                        }

                        // DERP Server Latencies Card
                        if (derpLatencies.isNotEmpty()) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.netcheck_sect_derp),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (report.preferredDerpId != 0) {
                                                Text(
                                                    text = stringResource(R.string.netcheck_nearest_format, report.preferredDerpName),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            derpLatencies.forEach { item ->
                                                DerpLatencyRow(item = item)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                text = stringResource(R.string.netcheck_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CapabilityRow(
    label: String,
    success: Boolean,
    successText: String,
    failText: String,
    subText: String = "",
    warnStyle: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subText.isNotEmpty()) {
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (success) successText else failText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    warnStyle && !success -> Color(0xFFE91E63) // Pinkish warn
                    !success && label.contains("NAT") -> Color(0xFFFF9800) // Amber warning
                    success -> Color(0xFF4CAF50)
                    else -> Color(0xFFF44336)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (success) Icons.Default.CheckCircle else if (label.contains("NAT")) Icons.Default.Warning else Icons.Default.Cancel,
                contentDescription = null,
                tint = when {
                    warnStyle && !success -> Color(0xFFE91E63)
                    !success && label.contains("NAT") -> Color(0xFFFF9800)
                    success -> Color(0xFF4CAF50)
                    else -> Color(0xFFF44336)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun DerpLatencyRow(item: DerpLatencyItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.code,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.isPreferred) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.netcheck_nearest_label),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${"%.1f".format(item.latencyMs)} ms",
                fontWeight = FontWeight.SemiBold,
                color = when {
                    item.latencyMs < 60.0 -> Color(0xFF4CAF50)
                    item.latencyMs < 150.0 -> Color(0xFFFFC107)
                    else -> Color(0xFFF44336)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Visual latency meter bar
            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                val fraction = (item.latencyMs / 300.0).coerceIn(0.05, 1.0).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = fraction)
                        .background(
                            color = when {
                                item.latencyMs < 60.0 -> Color(0xFF4CAF50)
                                item.latencyMs < 150.0 -> Color(0xFFFFC107)
                                else -> Color(0xFFF44336)
                            }
                        )
                )
            }
        }
    }
}
