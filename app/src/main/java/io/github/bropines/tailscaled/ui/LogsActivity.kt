package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

@Keep
data class LogEntry(
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("level") val level: String,
    @SerializedName("category") val category: String,
    @SerializedName("message") val message: String
)

class LogsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                LogsScreen(onBack = { finish() })
            }
        }
    }
}

fun getDebugHeader(context: Context): String {
    val verName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "unknown" }
    val coreVer = try { Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
    
    val hostname = prefs.getString("hostname", "") ?: ""
    val socks5 = prefs.getString("socks5", "127.0.0.1:1055") ?: "127.0.0.1:1055"
    val httpProxy = prefs.getString("http_proxy", "") ?: ""
    val dnsProxy = prefs.getString("dns_proxy", "") ?: ""
    val acceptRoutes = prefs.getBoolean("accept_routes", true)
    val acceptDNS = prefs.getBoolean("accept_dns", true)
    val exitNodeSet = prefs.getString("exit_node_id", "")?.isNotEmpty() == true
    val authKeySet = prefs.getString("authkey", "")?.isNotEmpty() == true

    return """
        --- TAILSOCKS DEBUG INFO ---
        App Version: $verName
        Tailscale Core: $coreVer
        Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})
        Arch: ${Build.SUPPORTED_ABIS.joinToString(", ")}
        
        Settings:
        hostname: $hostname
        socks5: $socks5
        httpProxy: $httpProxy
        dnsProxy: $dnsProxy
        acceptRoutes: $acceptRoutes
        acceptDNS: $acceptDNS
        exitNode: ${if (exitNodeSet) "Enabled" else "Disabled"}
        authKey: ${if (authKeySet) "Present" else "Empty"}
        ----------------------------
        
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var allLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val categories = listOf("ALL", "ERROR", "CORE", "TAILSCALE", "OTHER")

    val displayedLogs = remember(allLogs, selectedCategory, searchQuery) {
        allLogs.filter { log ->
            val matchCategory = selectedCategory == "ALL" || log.category == selectedCategory
            val matchQuery = searchQuery.isEmpty() || log.message.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fullLog = getDebugHeader(context) + Appctr.getLogs()
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        OutputStreamWriter(os).use { writer -> writer.write(fullLog) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.logs_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun loadLogsData(manual: Boolean = false) {
        if (manual) isRefreshing = true
        coroutineScope.launch(Dispatchers.IO) {
            val jsonString = try { Appctr.getLogsJSON() } catch (e: Exception) { "[]" }
            val logsList: List<LogEntry> = try {
                Gson().fromJson(jsonString, object : TypeToken<List<LogEntry>>() {}.type)
            } catch (e: Exception) { emptyList() }

            withContext(Dispatchers.Main) {
                allLogs = logsList
                if (manual) isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadLogsData()
            delay(2000)
        }
    }

    LaunchedEffect(displayedLogs.size) {
        if (isAutoScroll && displayedLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayedLogs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.logs_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                    },
                    actions = {
                        IconButton(onClick = { 
                            Appctr.flushDNS()
                            Toast.makeText(context, context.getString(R.string.logs_dns_flushed), Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.CleaningServices, contentDescription = stringResource(R.string.logs_cd_flush_dns)) }

                        IconButton(onClick = { 
                            val fullLog = getDebugHeader(context) + Appctr.getLogs()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks Logs", fullLog))
                            Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
                        
                        IconButton(onClick = { saveFileLauncher.launch("tailsocks_logs_${System.currentTimeMillis()}.txt") }) { Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save)) }
                    }
                )
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.logs_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category; isAutoScroll = true }, label = { Text(category) })
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                coroutineScope.launch(Dispatchers.IO) {
                    Appctr.clearLogs()
                    withContext(Dispatchers.Main) {
                        allLogs = emptyList()
                        Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_clear)) }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadLogsData(true) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 4f) }
                    }
                ) {
                    items(displayedLogs) { log ->
                        val defaultColor = MaterialTheme.colorScheme.onSurface
                        val highlightedText = remember(log, defaultColor) {
                            highlightLogMessage(log.timestamp, log.category, log.message, defaultColor)
                        }
                        Text(
                            text = highlightedText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (12 * scale).sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
