package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// --- Typed models for the daemon's raw status / netcheck JSON ---

@Serializable
private data class NetcheckStatusError(
    @SerialName("Error") val error: String? = null
)

@Serializable
private data class NetcheckResponse(
    @SerialName("Report") val report: NetcheckReport? = null,
    @SerialName("DERPMeta") val derpMeta: Map<String, DerpMetaEntry>? = null,
    @SerialName("Error") val error: String? = null
)

@Serializable
private data class NetcheckReport(
    @SerialName("UDP") val udp: Boolean = false,
    @SerialName("IPv4") val ipv4: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false,
    @SerialName("MappingVariesByDestIP") val mappingVaries: Boolean = false,
    @SerialName("GlobalV4") val globalV4: String = "",
    @SerialName("GlobalV6") val globalV6: String = "",
    @SerialName("PreferredDERP") val preferredDerp: Int = 0,
    @SerialName("RegionLatency") val regionLatency: Map<String, Double?>? = null
)

@Serializable
private data class DerpMetaEntry(
    @SerialName("Code") val code: String? = null,
    @SerialName("Name") val name: String? = null
)

// --- In-memory report holders for the Compose UI ---

data class ConnectionStatus(
    val online: Boolean,
    val tailscaleIp: String,
    /**
     * The home DERP region as "Name (code)", empty when the node has none.
     * Taken from Self.Relay, which magicsock sets to the home region's code: it
     * is where peers reach this node when no direct path works. It does not say
     * whether traffic is relayed right now — that is decided per peer — and
     * reading it as that made a healthy node always show "Relay" and a node
     * with no relay at all show "Direct P2P".
     */
    val homeDerp: String,
    /** This run's latency to [homeDerp], when that region answered the probes. */
    val homeDerpLatencyMs: Double?
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

/** One finished run: what the screen draws, and the text the copy button hands out. */
data class NetcheckResult(
    val connection: ConnectionStatus,
    val diagnostics: DiagnosticsReport,
    val derpLatencies: List<DerpLatencyItem>,
    val textReport: String
)

object NetcheckCache {
    var lastReportTime: Long = 0
    var daemonStartTime: Long = 0
    var result: NetcheckResult? = null
    var errorMessage: String? = null

    fun clear() {
        lastReportTime = 0
        daemonStartTime = 0
        result = null
        errorMessage = null
    }
}

/**
 * The status document, or the daemon's own complaint as the exception. Checked
 * before the netcheck is started: that takes seconds and is pointless once the
 * daemon has said it is not running.
 */
internal fun decodeNetcheckStatus(rawStatus: String): StatusResponse {
    if (rawStatus.isBlank()) throw Exception("Status API returned null")
    val statusError = runCatching { AppJson.decodeFromString<NetcheckStatusError>(rawStatus) }.getOrNull()?.error
    if (!statusError.isNullOrEmpty()) throw Exception(statusError)
    return AppJson.decodeFromString<StatusResponse>(rawStatus)
}

/**
 * Turns the status document and the bridge's netcheck answer — the report plus
 * the DERP region names, as Appctr.getNetcheckFromAPI returns it — into what the
 * screen shows. The live run and a preview's LocalDemo both come through here,
 * so a render cannot show something the app would read differently.
 */
internal fun parseNetcheck(context: Context, status: StatusResponse, rawNetcheck: String): NetcheckResult {
    val netcheckResp = if (rawNetcheck.isBlank()) null
        else runCatching { AppJson.decodeFromString<NetcheckResponse>(rawNetcheck) }.getOrNull()
    if (netcheckResp == null) throw Exception("Received invalid response from bridge")
    netcheckResp.error?.let { throw Exception(it) }
    val netcheck = netcheckResp.report ?: throw Exception("Received invalid response from bridge")
    val derpMeta = netcheckResp.derpMeta.orEmpty()

    val self = status.self
    val online = self?.online ?: false
    val tailscaleIp = self?.tailscaleIPs?.firstOrNull() ?: "Unknown"

    // "Frankfurt (fra)"; the code alone when the map has no name for it.
    fun regionLabel(id: Int): String {
        val meta = derpMeta[id.toString()] ?: return "Region $id"
        val code = meta.code ?: ""
        val name = meta.name ?: ""
        return if (name.isNotEmpty()) "$name ($code)" else code
    }

    val preferredDerp = netcheck.preferredDerp
    val preferredDerpName = if (preferredDerp != 0) regionLabel(preferredDerp) else "Unknown"

    val latencyList = netcheck.regionLatency.orEmpty().mapNotNull { (key, value) ->
        val rId = key.toIntOrNull() ?: 0
        if (rId == 0 || value == null) return@mapNotNull null
        val meta = derpMeta[key]
        DerpLatencyItem(
            regionId = rId,
            code = meta?.code ?: "region$rId",
            name = meta?.name ?: "Region $rId",
            latencyMs = if (value < 1000.0) value * 1000.0 else value / 1_000_000.0,
            isPreferred = rId == preferredDerp
        )
    }.sortedBy { it.latencyMs }

    // Self.Relay names the home region by its code; the report keys regions by id.
    val homeCode = self?.relay ?: ""
    val homeId = if (homeCode.isEmpty()) null
        else derpMeta.entries.firstOrNull { it.value.code == homeCode }?.key?.toIntOrNull()
    val homeDerp = when {
        homeCode.isEmpty() -> ""
        homeId != null -> regionLabel(homeId)
        else -> homeCode
    }
    val homeLatency = homeId?.let { id -> latencyList.firstOrNull { it.regionId == id }?.latencyMs }

    val udp = netcheck.udp
    val ipv4 = netcheck.ipv4
    val ipv6 = netcheck.ipv6
    val mappingVaries = netcheck.mappingVaries
    val globalV4 = netcheck.globalV4
    val globalV6 = netcheck.globalV6

    // Build text report for copying
    val healthOutput = StringBuilder()
    healthOutput.append(context.getString(R.string.netcheck_connection_health))
    healthOutput.append(context.getString(R.string.netcheck_status, if (online) "🟢 ONLINE" else "🔴 OFFLINE"))
    healthOutput.append(context.getString(R.string.netcheck_tailscale_ip, tailscaleIp))
    healthOutput.append(
        when {
            homeDerp.isEmpty() -> context.getString(R.string.netcheck_report_no_home_derp)
            homeLatency != null -> context.getString(R.string.netcheck_report_home_derp, "$homeDerp · ${"%.1f".format(homeLatency)} ms")
            else -> context.getString(R.string.netcheck_report_home_derp, homeDerp)
        }
    )

    healthOutput.append(context.getString(R.string.netcheck_running_diagnostics))
    healthOutput.append(context.getString(R.string.netcheck_udp, if (udp) "✅ Working" else "❌ Blocked"))
    healthOutput.append(context.getString(R.string.netcheck_ipv4, if (ipv4) "✅ Yes, $globalV4" else "❌ No"))
    healthOutput.append(context.getString(R.string.netcheck_ipv6, if (ipv6) "✅ Yes, $globalV6" else "❌ No"))
    healthOutput.append(context.getString(R.string.netcheck_nat_mapping, if (mappingVaries) "⚠️ Yes (Symmetric NAT)" else "✅ No"))
    if (preferredDerp != 0) {
        healthOutput.append(context.getString(R.string.netcheck_nearest_derp, preferredDerp))
    }
    if (latencyList.isNotEmpty()) {
        healthOutput.append(context.getString(R.string.netcheck_derp_latency))
        latencyList.forEach { healthOutput.append("${it.code}: ${"%.1f".format(it.latencyMs)}ms (${it.name})\n") }
    }

    healthOutput.append(context.getString(R.string.netcheck_peer_summary))
    val peers = status.peers
    var peerCount = 0
    var onlinePeers = 0
    if (peers != null) {
        peerCount = peers.size
        onlinePeers = peers.values.count { it.online == true }
        healthOutput.append(context.getString(R.string.netcheck_total_peers, peerCount))
        healthOutput.append(context.getString(R.string.netcheck_online_peers, onlinePeers))
    }

    return NetcheckResult(
        connection = ConnectionStatus(
            online = online,
            tailscaleIp = tailscaleIp,
            homeDerp = homeDerp,
            homeDerpLatencyMs = homeLatency
        ),
        diagnostics = DiagnosticsReport(
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
        ),
        derpLatencies = latencyList,
        textReport = healthOutput.toString()
    )
}

/**
 * Starts the service and returns once its daemon can answer a netcheck; false
 * when the start was given up instead.
 *
 * The Start button used to run the diagnostics right after the start command,
 * which only reproduced the error it was pressed to fix: the daemon was nowhere
 * near up yet. The service announces START once the daemon is launched and STOP
 * when a start is abandoned (the root daemon refused, a stop came in meanwhile).
 * START alone is not enough either: the netcheck asks the daemon for its DERP
 * map, which it holds only once the backend is Running, so the wait goes on
 * until it is, or settles somewhere no waiting helps (logged out). A daemon that
 * is already up is not started again — the service would announce nothing.
 */
private suspend fun startServiceAndWait(context: Context): Boolean {
    val alreadyUp = withContext(Dispatchers.IO) { runCatching { Appctr.isRunning() }.getOrDefault(false) }
    if (!alreadyUp) {
        val heard = CompletableDeferred<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { heard.complete(intent.action) }
        }
        // Registered before the start, or a quick start could announce itself unheard.
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply { addAction("START"); addAction("STOP") },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
            )
            // A Root Mode start goes through su and can take a while. Past this the
            // diagnostics run anyway and say for themselves what is wrong.
            if (withTimeoutOrNull(45_000) { heard.await() } == "STOP") return false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    withContext(Dispatchers.IO) {
        withTimeoutOrNull(15_000) {
            while (runCatching { Appctr.getBackendState() }.getOrDefault("") in setOf("", "NoState", "Starting", "Error")) {
                delay(500)
            }
        }
    }
    return true
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetcheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // LocalInspectionMode is true only in the preview renderer, which has no
    // daemon and no native bridge: touching Appctr there loads gojni and fails.
    val inPreview = LocalInspectionMode.current
    // A preview may hand in the daemon's answers. They are parsed here and now —
    // nothing started from LaunchedEffect would land before the picture is taken —
    // and one that does not parse shows as the error it would be in the app.
    val demo = LocalDemo.current
    val demoOutcome = remember(demo) {
        demo?.let { d ->
            runCatching { parseNetcheck(context, decodeNetcheckStatus(d.statusJson ?: ""), d.netcheckJson ?: "") }
        }
    }
    var isRunning by remember { mutableStateOf(false) }
    // Set while the Start button's service start is under way; the diagnostics follow it.
    var startingService by remember { mutableStateOf(false) }
    var result by remember {
        mutableStateOf(if (demoOutcome != null) demoOutcome.getOrNull() else NetcheckCache.result)
    }
    var errorMessage by remember {
        mutableStateOf(
            if (demoOutcome != null) demoOutcome.exceptionOrNull()?.let { it.message ?: "Unknown error" }
            else NetcheckCache.errorMessage
        )
    }
    val busy = isRunning || startingService

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
            try {
                val currentDaemonStart = Appctr.getDaemonStartTime()
                val rawStatus = Appctr.getStatusFromAPI()
                android.util.Log.d("Netcheck", "Raw Status: $rawStatus")
                val status = decodeNetcheckStatus(rawStatus)

                val rawNetcheck = Appctr.getNetcheckFromAPI()
                android.util.Log.d("Netcheck", "Raw Netcheck: $rawNetcheck")
                val fresh = parseNetcheck(context, status, rawNetcheck)

                withContext(Dispatchers.Main) {
                    // Update cache
                    NetcheckCache.lastReportTime = System.currentTimeMillis()
                    NetcheckCache.daemonStartTime = currentDaemonStart
                    NetcheckCache.result = fresh
                    NetcheckCache.errorMessage = null

                    result = fresh
                    errorMessage = null
                    isRunning = false
                }
            } catch (e: Exception) {
                android.util.Log.e("Netcheck", "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    NetcheckCache.clear()
                    NetcheckCache.errorMessage = e.message ?: "Unknown error"

                    errorMessage = NetcheckCache.errorMessage
                    result = null
                    isRunning = false
                }
            }
        }
    }

    fun startServiceThenDiagnose() {
        startingService = true
        errorMessage = null
        scope.launch {
            // The system may refuse the foreground start itself; that is an error
            // to show here, not a crash.
            val cameUp = try {
                startServiceAndWait(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Netcheck", "Service start failed: ${e.message}", e)
                startingService = false
                NetcheckCache.errorMessage = e.message ?: context.getString(R.string.netcheck_service_stopped)
                errorMessage = NetcheckCache.errorMessage
                return@launch
            }
            startingService = false
            if (cameUp) {
                runDiagnostics()
            } else {
                NetcheckCache.errorMessage = context.getString(R.string.netcheck_service_stopped)
                errorMessage = NetcheckCache.errorMessage
            }
        }
    }

    LaunchedEffect(Unit) {
        // A preview shows what LocalDemo handed in, or the waiting state: it has
        // no daemon to ask.
        if (demo != null || inPreview) return@LaunchedEffect
        val currentDaemonStart = try { Appctr.getDaemonStartTime() } catch (e: Exception) { 0L }
        val isCacheValid = NetcheckCache.lastReportTime > 0 &&
                (System.currentTimeMillis() - NetcheckCache.lastReportTime < 30 * 60 * 1000) &&
                (NetcheckCache.daemonStartTime == currentDaemonStart)

        if (isCacheValid) {
            // Use cached values
            result = NetcheckCache.result
            errorMessage = NetcheckCache.errorMessage
        } else {
            runDiagnostics()
        }
    }

    PredictiveBackContainer(
        onBack = onBack,
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
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
                    result?.let { shown ->
                        IconButton(onClick = { copyToClipboard(shown.textReport) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.netcheck_cd_copy_report))
                        }
                    }
                    IconButton(onClick = { runDiagnostics() }, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.netcheck_cd_run_diagnostics))
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Held to a readable width on a tablet; see ReadableWidth.
        ReadableWidth {
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val shown = result
            when {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { startServiceThenDiagnose() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_start_service))
                            }
                            OutlinedButton(
                                onClick = { runDiagnostics() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }

                shown == null -> {
                    // Loading State — also the first frame, before the first run has begun,
                    // which used to be an empty page.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(if (startingService) R.string.netcheck_starting_service else R.string.netcheck_analyzing),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(if (startingService) R.string.netcheck_starting_service_desc else R.string.netcheck_testing_latency_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    // Success State Dashboard
                    val status = shown.connection
                    val report = shown.diagnostics
                    val derpLatencies = shown.derpLatencies
                    val homeMissing = status.homeDerp.isEmpty()
                    // Online without a home relay is connected in name only for every
                    // peer it has no direct path to, so it gets a warning, not a tick.
                    val (statusIcon, statusTint) = when {
                        !status.online -> Icons.Default.Cancel to MaterialTheme.colorScheme.error
                        homeMissing -> Icons.Default.Warning to MaterialTheme.colorScheme.error
                        else -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))

                            // Overview Card. The tint is flattened onto the background: an
                            // elevated card with a see-through container shows its own shadow
                            // through it, a grey cast over the whole card in a light theme.
                            ElevatedCard(
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = (if (status.online) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                                     else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                                        .compositeOver(MaterialTheme.colorScheme.background)
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
                                            .background(statusTint.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = statusIcon,
                                            contentDescription = null,
                                            tint = statusTint,
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
                                        if (homeMissing) {
                                            Text(
                                                text = stringResource(R.string.netcheck_no_home_derp),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            HelpText(stringResource(R.string.netcheck_no_home_derp_help))
                                        } else {
                                            Text(
                                                text = status.homeDerpLatencyMs?.let {
                                                    stringResource(R.string.netcheck_home_derp_latency, status.homeDerp, it.roundToInt())
                                                } ?: stringResource(R.string.netcheck_home_derp, status.homeDerp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
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

                                    // Plenty of networks have no IPv6 and Tailscale works on
                                    // IPv4 alone: worth knowing, not an error.
                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_ipv6_conn),
                                        success = report.ipv6Working,
                                        successText = stringResource(R.string.netcheck_available),
                                        failText = stringResource(R.string.netcheck_unavailable),
                                        subText = report.ipv6Address,
                                        warnStyle = true
                                    )

                                    CapabilityRow(
                                        label = stringResource(R.string.netcheck_nat_varies),
                                        success = !report.mappingVaries,
                                        successText = stringResource(R.string.netcheck_nat_varies_no),
                                        failText = stringResource(R.string.netcheck_nat_varies_yes),
                                        warnStyle = true
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
                                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                     textAlign = TextAlign.End,
                                                     modifier = Modifier
                                                         .weight(1f)
                                                         .padding(start = 8.dp)
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
}
}

/**
 * One line of the capabilities card. [warnStyle] marks a check whose failure
 * degrades the connection rather than breaking it (symmetric NAT, no IPv6): it
 * is drawn as a warning instead of an error.
 *
 * The colours are theme roles, not fixed hues. Primary, tertiary and error are
 * each made to read on the theme's own surfaces, light or dark; the Material
 * green and amber that were here sat at 2.8:1 and 1.6:1 on a light background.
 */
@Composable
fun CapabilityRow(
    label: String,
    success: Boolean,
    successText: String,
    failText: String,
    subText: String = "",
    warnStyle: Boolean = false
) {
    val tint = when {
        success -> MaterialTheme.colorScheme.primary
        warnStyle -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
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
                color = tint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = when {
                    success -> Icons.Default.CheckCircle
                    warnStyle -> Icons.Default.Warning
                    else -> Icons.Default.Cancel
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Fast, middling, slow — theme roles, for the reason given at [CapabilityRow]. */
@Composable
private fun latencyTint(latencyMs: Double): Color = when {
    latencyMs < 60.0 -> MaterialTheme.colorScheme.primary
    latencyMs < 150.0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun DerpLatencyRow(item: DerpLatencyItem) {
    val tint = latencyTint(item.latencyMs)
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
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        // labelSmall is 11sp; the 8sp this badge had was below legible.
                        Text(
                            text = stringResource(R.string.netcheck_nearest_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
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
                color = tint,
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
                        .background(color = tint)
                )
            }
        }
    }
}
