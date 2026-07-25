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

    fun refresh(doFlush: Boolean) {
        loading = true
        errorText = null
        scope.launch(Dispatchers.IO) {
            if (!Appctr.isRunning()) {
                withContext(Dispatchers.Main) {
                    status = null
                    errorText = context.getString(R.string.dns_error_not_running)
                    loading = false
                }
                return@launch
            }
            if (doFlush) {
                Appctr.flushDNS() // Flush DNS cache in Go
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

    LaunchedEffect(Unit) { refresh(doFlush = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = { refresh(doFlush = true) }) { Icon(Icons.Default.Refresh, null) } }
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
                        Text(stringResource(R.string.dns_lookup_tool), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = queryDomain,
                                onValueChange = { queryDomain = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.dns_lookup_placeholder)) },
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
                                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.dns_cd_query))
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
                Text(stringResource(R.string.dns_config_status), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }

            // 2. STATUS
            status?.let { data ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.dns_global_state), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.dns_global_state_format, data.active ?: false, data.tailnet?.enabled ?: false), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                data.splitRoutes?.forEach { (domain, ips) ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.dns_split_route), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val cleanDomain = domain.trimEnd('.')
                                    Text(cleanDomain, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Domain", cleanDomain))
                                        Toast.makeText(context, context.getString(R.string.dns_domain_copied), Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(32.dp)) { 
                                        Icon(Icons.Default.ContentCopy, stringResource(R.string.dns_cd_copy_domain), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) 
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
                                        Toast.makeText(context, context.getString(R.string.dns_ips_copied), Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(ips.joinToString("\n") { "• ${it.addr}" }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ContentCopy, stringResource(R.string.dns_cd_copy_ips), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
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