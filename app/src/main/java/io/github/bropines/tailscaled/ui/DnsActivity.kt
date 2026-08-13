package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.graphics.Color

@Keep
data class DnsAddr(@SerializedName("Addr") val addr: String)

@Keep
data class CurrentTailnetInfo(
    @SerializedName("MagicDNSEnabled") val enabled: Boolean,
    @SerializedName("MagicDNSSuffix") val suffix: String?,
    @SerializedName("SelfDNSName") val selfName: String?
)

@Keep
data class DnsStatus(
    @SerializedName("TailscaleDNS") val active: Boolean?,
    @SerializedName("CurrentTailnet") val tailnet: CurrentTailnetInfo?,
    @SerializedName("SplitDNSRoutes") val splitRoutes: Map<String, List<DnsAddr>>?
)

private fun buildDnsQuery(domain: String): ByteArray {
    val clean = domain.trim().trimEnd('.').ifBlank { "google.com" }
    val baos = java.io.ByteArrayOutputStream()
    val dos = java.io.DataOutputStream(baos)
    
    // Transaction ID (2 bytes)
    dos.writeShort(0x1234)
    // Flags (2 bytes) - standard query with recursion desired
    dos.writeShort(0x0100)
    // Questions count (2 bytes) = 1
    dos.writeShort(1)
    // Answer RRs (2 bytes) = 0
    dos.writeShort(0)
    // Authority RRs (2 bytes) = 0
    dos.writeShort(0)
    // Additional RRs (2 bytes) = 0
    dos.writeShort(0)
    
    // Name (variable length)
    val parts = clean.split(".")
    for (part in parts) {
        if (part.isEmpty()) continue
        val bytes = part.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        dos.writeByte(bytes.size)
        dos.write(bytes)
    }
    // Null terminator (1 byte)
    dos.writeByte(0)
    
    // Type (2 bytes) - 0x0001 (Type A)
    dos.writeShort(1)
    // Class (2 bytes) - 0x0001 (Class INET)
    dos.writeShort(1)
    
    return baos.toByteArray()
}

private fun testDnsServer(serverIp: String, serverPort: Int = 53, domain: String = "google.com"): String {
    return try {
        var targetHost = serverIp.trim()
        var targetPort = serverPort
        if (targetHost.startsWith("[")) {
            val closingIndex = targetHost.indexOf("]")
            if (closingIndex != -1) {
                val hostPart = targetHost.substring(1, closingIndex)
                val portPart = targetHost.substring(closingIndex + 1).removePrefix(":")
                targetHost = hostPart
                if (portPart.isNotEmpty()) targetPort = portPart.toIntOrNull() ?: serverPort
            }
        } else if (targetHost.count { it == ':' } == 1) {
            val parts = targetHost.split(":")
            targetHost = parts[0]
            targetPort = parts[1].toIntOrNull() ?: serverPort
        }

        val socket = java.net.DatagramSocket()
        socket.soTimeout = 2500
        val dnsQuery = buildDnsQuery(domain)
        val address = java.net.InetAddress.getByName(targetHost)
        val packet = java.net.DatagramPacket(dnsQuery, dnsQuery.size, address, targetPort)
        val startTime = System.currentTimeMillis()
        socket.send(packet)
        val buffer = ByteArray(512)
        val receivePacket = java.net.DatagramPacket(buffer, buffer.size)
        socket.receive(receivePacket)
        val latency = System.currentTimeMillis() - startTime
        socket.close()
        "Success (reply: ${receivePacket.length} bytes, latency: ${latency} ms)"
    } catch (e: Exception) {
        "Failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

class DnsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                DnsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var status by remember { mutableStateOf<DnsStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var queryDomain by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<String?>(null) }
    var isQuerying by remember { mutableStateOf(false) }

    // Split Route & Local DNS test states
    var routeTestResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var routeTestingState by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var localTestResult by remember { mutableStateOf<String?>(null) }
    var isTestingLocal by remember { mutableStateOf(false) }

    fun runLocalDnsTest() {
        isTestingLocal = true
        scope.launch(Dispatchers.IO) {
            var res = testDnsServer("100.100.100.100", 53, "google.com")
            if (res.startsWith("Failed")) {
                val resLocal = testDnsServer("127.0.0.1", 1053, "google.com")
                if (resLocal.startsWith("Success")) {
                    res = resLocal
                }
            }
            withContext(Dispatchers.Main) {
                localTestResult = res
                isTestingLocal = false
            }
        }
    }

    fun refresh(doFlush: Boolean) {
        loading = true
        errorText = null
        scope.launch(Dispatchers.IO) {
            if (!ProxyState.isActualRunning(context)) {
                withContext(Dispatchers.Main) {
                    status = null
                    errorText = context.getString(R.string.dns_error_not_running)
                    loading = false
                }
                return@launch
            }
            val json = Appctr.getDnsStatusJSON()
            val parsed = try {
                Gson().fromJson(json, DnsStatus::class.java)
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                status = parsed
                if (parsed == null) {
                    errorText = context.getString(R.string.dns_error_parse_failed, json)
                }
                loading = false
            }
        }
    }

    fun performQuery(domain: String) {
        if (domain.isBlank()) return
        isQuerying = true
        focusManager.clearFocus()
        scope.launch(Dispatchers.IO) {
            val out = try {
                Appctr.nativeDnsQuery(domain.trim(), "A")
            } catch (e: Exception) { context.getString(R.string.error_generic, e.message) }
            withContext(Dispatchers.Main) {
                queryResult = out.trim()
                isQuerying = false
            }
        }
    }

    fun runRouteTest(domain: String, ip: String) {
        val key = "${domain}_${ip}"
        routeTestingState = routeTestingState + (key to true)
        scope.launch(Dispatchers.IO) {
            val cleanDomain = domain.trimEnd('.')
            val res = testDnsServer(ip, 53, cleanDomain)
            withContext(Dispatchers.Main) {
                routeTestResults = routeTestResults + (key to res)
                routeTestingState = routeTestingState + (key to false)
            }
        }
    }

    LaunchedEffect(Unit) { refresh(doFlush = false) }

    PredictiveBackContainer(
        onBack = onBack,
        targetTitle = stringResource(R.string.predictive_back_target_settings),
        targetIcon = Icons.Default.Settings
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.dns_title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    actions = { IconButton(onClick = { refresh(doFlush = false) }) { Icon(Icons.Default.Refresh, null) } }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                errorText?.let { msg ->
                    item {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.dns_status_unavailable),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    msg,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }



                // 1.5 LOCAL DNS SERVER TEST CARD
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.dns_test_local_server_title),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Button(
                                    onClick = { runLocalDnsTest() },
                                    enabled = !isTestingLocal,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    if (isTestingLocal) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.dns_test_btn), fontSize = 12.sp)
                                    }
                                }
                            }
                            localTestResult?.let { res ->
                                Spacer(Modifier.height(8.dp))
                                val isSuccess = res.startsWith("Success")
                                val latency = if (isSuccess) {
                                    res.substringAfter("latency: ").substringBefore(" ms").toIntOrNull() ?: 0
                                } else 0
                                val textMsg = if (isSuccess) {
                                    stringResource(R.string.dns_test_success_format, 512, latency)
                                } else {
                                    stringResource(R.string.dns_test_failed_format, res.substringAfter("Failed: "))
                                }
                                Surface(
                                    color = (if (isSuccess) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = textMsg,
                                        color = if (isSuccess) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. DNS QUERY TOOL
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.dns_lookup_tool), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactSearchBar(
                                    value = queryDomain,
                                    onValueChange = { queryDomain = it },
                                    placeholderText = stringResource(R.string.dns_lookup_placeholder),
                                    modifier = Modifier.weight(1f),
                                    onSearch = { performQuery(queryDomain) }
                                )
                                Spacer(Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = { performQuery(queryDomain) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(height = 40.dp, width = 50.dp)
                                ) {
                                    if (isQuerying) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.dns_cd_query), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            if (queryResult != null) {
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = queryResult!!,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. CONFIG STATUS & PEERS
                status?.let { data ->
                    item {
                        Text(stringResource(R.string.dns_config_status), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("MagicDNS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Статус", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                    val magicActive = data.tailnet?.enabled ?: false
                                    Surface(
                                        color = (if (magicActive) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline).copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (magicActive) "ВКЛЮЧЕН" else "ВЫКЛЮЧЕН",
                                            color = if (magicActive) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                data.tailnet?.suffix?.let { suffix ->
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Домен сети", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                        Text(suffix, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                    }
                                }
                                data.tailnet?.selfName?.let { name ->
                                    Spacer(Modifier.height(8.dp))
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("DNS-имя устройства", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.height(2.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("SelfName", name))
                                                Toast.makeText(context, context.getString(R.string.dns_domain_copied), Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Split DNS Routes
                    data.splitRoutes?.forEach { (domain, ips) ->
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(stringResource(R.string.dns_split_route), color = MaterialTheme.colorScheme.outline, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val cleanDomain = domain.trimEnd('.')
                                        Text(cleanDomain, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        IconButton(onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Domain", cleanDomain))
                                            Toast.makeText(context, context.getString(R.string.dns_domain_copied), Toast.LENGTH_SHORT).show()
                                        }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.ContentCopy, stringResource(R.string.dns_cd_copy_domain), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    ips.forEach { dnsAddr ->
                                        val ip = dnsAddr.addr
                                        val key = "${domain}_${ip}"
                                        val testRes = routeTestResults[key]
                                        val isTesting = routeTestingState[key] ?: false
                                        
                                        Spacer(Modifier.height(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(ip, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                                    if (testRes != null) {
                                                        val isSuccess = testRes.startsWith("Success")
                                                        val latency = if (isSuccess) {
                                                            testRes.substringAfter("latency: ").substringBefore(" ms").toIntOrNull() ?: 0
                                                        } else 0
                                                        Text(
                                                            text = if (isSuccess) "Ping: $latency ms" else "Failed",
                                                            fontSize = 11.sp,
                                                            color = if (isSuccess) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                                
                                                // Test button
                                                IconButton(
                                                    onClick = { runRouteTest(domain, ip) },
                                                    modifier = Modifier.size(28.dp),
                                                    enabled = !isTesting
                                                ) {
                                                    if (isTesting) {
                                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                    } else {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = "Test DNS Server",
                                                            modifier = Modifier.size(16.dp),
                                                            tint = if (testRes != null && testRes.startsWith("Success")) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                                                        )
                                                    }
                                                }
                                                
                                                Spacer(Modifier.width(4.dp))
                                                
                                                // Copy button
                                                IconButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("IP", ip))
                                                        Toast.makeText(context, context.getString(R.string.dns_ips_copied), Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ContentCopy,
                                                        contentDescription = "Copy IP",
                                                        modifier = Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.outline
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

                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}