package io.github.bropines.tailscaled

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

class DnsActivity : ComponentActivity() {
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

    fun refresh() {
        loading = true
        errorText = null
        scope.launch(Dispatchers.IO) {
            if (!Appctr.isRunning()) {
                withContext(Dispatchers.Main) {
                    status = null
                    errorText = "Tailscale is not running.\nStart the service on the main screen."
                    loading = false
                }
                return@launch
            }
            Appctr.flushDNS() // Очищаем кэш в Go
            val json = Appctr.getDnsStatusJSON()
            val parsed = try {
                Gson().fromJson(json, DnsStatus::class.java)
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                status = parsed
                if (parsed == null) {
                    errorText = "Failed to parse DNS status.\n\nRaw output:\n$json"
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
            } catch (e: Exception) { "Error: ${e.message}" }
            withContext(Dispatchers.Main) {
                queryResult = out.trim()
                isQuerying = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DNS Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null) } }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {

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
                                "⚠ DNS Status Unavailable",
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
                    Spacer(Modifier.height(16.dp))
                }
            }

            // 1. DNS QUERY TOOL
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("DNS Lookup Tool", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = queryDomain,
                                onValueChange = { queryDomain = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("e.g. peer.ts.net or google.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { performQuery(queryDomain) })
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = { performQuery(queryDomain) },
                                modifier = Modifier.height(56.dp)
                            ) {
                                if (isQuerying) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = "Query")
                                }
                            }
                        }
                        if (queryResult != null) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = queryResult!!,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Configuration Status", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }

            // 2. СТАТУС
            status?.let { data ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Global State", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Active: ${data.active}\nMagicDNS: ${data.tailnet?.enabled}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                data.splitRoutes?.forEach { (domain, ips) ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Split Route", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val cleanDomain = domain.trimEnd('.')
                                    Text(cleanDomain, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Domain", cleanDomain))
                                        Toast.makeText(context, "Domain copied", Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(32.dp)) { 
                                        Icon(Icons.Default.ContentCopy, "Copy domain", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) 
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                val ipsText = ips.joinToString("\n") { it.addr }
                                Surface(
                                    shape = MaterialTheme.shapes.small, 
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("IPs", ipsText))
                                        Toast.makeText(context, "IPs copied", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(ips.joinToString("\n") { "• ${it.addr}" }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ContentCopy, "Copy IPs", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}