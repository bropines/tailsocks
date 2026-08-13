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
    val parts = domain.split(".")
    for (part in parts) {
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

private fun testDnsServer(serverIp: String, serverPort: Int, domain: String = "google.com"): String {
    return try {
        val socket = java.net.DatagramSocket()
        socket.soTimeout = 2000 // 2 seconds timeout
        val dnsQuery = buildDnsQuery(domain)
        val address = java.net.InetAddress.getByName(serverIp)
        val packet = java.net.DatagramPacket(dnsQuery, dnsQuery.size, address, serverPort)
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
    var status by remember { mutableStateOf<DnsStatus?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var queryDomain by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<String?>(null) }
    var isQuerying by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    val dnsProxyPref = remember(context) { GlobalSettings.getString(context, "dns_proxy", "127.0.0.1:1053") }

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

    fun runDnsTest() {
        isTesting = true
        scope.launch(Dispatchers.IO) {
            val hostPort = dnsProxyPref.split(":")
            val dnsIp = hostPort.getOrNull(0) ?: "127.0.0.1"
            val dnsPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 1053
            val out = testDnsServer(dnsIp, dnsPort)
            withContext(Dispatchers.Main) {
                testResult = out
                isTesting = false
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

                // 1. LOCAL DNS Health Check Card
                item {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.dns_test_local_server_title),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                
                                // Status Indicator
                                val (statusText, statusColor) = when {
                                    testResult == null -> stringResource(R.string.dns_test_status_untested) to MaterialTheme.colorScheme.outline
                                    testResult!!.startsWith("Success") -> stringResource(R.string.dns_test_status_active) to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                    else -> stringResource(R.string.dns_test_status_error) to MaterialTheme.colorScheme.error
                                }
                                Surface(
                                    color = statusColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = statusText,
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.dns_test_address_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    Text(dnsProxyPref, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                }
                                Button(
                                    onClick = { runDnsTest() },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.dns_test_btn), fontSize = 12.sp)
                                    }
                                }
                            }
                            testResult?.let { res ->
                                Spacer(Modifier.height(12.dp))
                                val isSuccess = res.startsWith("Success")
                                val latency = if (isSuccess) {
                                    res.substringAfter("latency: ").substringBefore(" ms").toIntOrNull() ?: 0
                                } else 0
                                val replyBytes = if (isSuccess) {
                                    res.substringAfter("reply: ").substringBefore(",").substringAfter("reply: ").toIntOrNull() ?: res.substringAfter("reply: ").substringBefore(" bytes").toIntOrNull() ?: 0
                                } else 0
                                
                                val displayText = if (isSuccess) {
                                    stringResource(R.string.dns_test_success_format, replyBytes, latency)
                                } else {
                                    stringResource(R.string.dns_test_failed_format, res.removePrefix("Failed: "))
                                }
                                
                                Surface(
                                    color = (if (isSuccess) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error).copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (isSuccess) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = displayText,
                                            fontSize = 13.sp,
                                            color = if (isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                                        )
                                    }
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
                                OutlinedTextField(
                                    value = queryDomain,
                                    onValueChange = { queryDomain = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                    placeholder = {
                                        Text(
                                            text = stringResource(R.string.dns_lookup_placeholder),
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            softWrap = false
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                                    trailingIcon = if (queryDomain.isNotEmpty()) {
                                        {
                                            IconButton(
                                                onClick = { queryDomain = "" },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Clear, null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    } else null,
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { performQuery(queryDomain) })
                                )
                                Spacer(Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = { performQuery(queryDomain) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(height = 46.dp, width = 50.dp)
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
                                            shape = RoundedCornerShape(4.dp),
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
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
                                    val ipsText = ips.joinToString("\n") { it.addr }
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("IPs", ipsText))
                                            Toast.makeText(context, context.getString(R.string.dns_ips_copied), Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(ips.joinToString("\n") { "• ${it.addr}" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                            Icon(Icons.Default.ContentCopy, stringResource(R.string.dns_cd_copy_ips), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
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