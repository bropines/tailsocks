package io.github.bropines.tailscaled

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

class ServeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                ServeScreen(onBack = { finish() })
            }
        }
    }
}

fun checkTargetHealth(target: String): Boolean {
    val cleanTarget = target.removePrefix("http://").removePrefix("https://").substringBefore("/")
    if (cleanTarget.isBlank()) return false
    val hostPort = cleanTarget.split(":")
    val host = hostPort.getOrNull(0) ?: "127.0.0.1"
    val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 80
    return try {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(host, port), 1000)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<ServeConfig?>(null) }
    var selfDns by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    var showEditDialog by remember { mutableStateOf<ServeRuleEditData?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    var healthMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var serveLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    fun loadServeLogs() {
        scope.launch(Dispatchers.IO) {
            val jsonString = try { Appctr.getLogsJSON() } catch (e: Exception) { "[]" }
            val logsList: List<LogEntry> = try {
                Gson().fromJson(jsonString, object : com.google.gson.reflect.TypeToken<List<LogEntry>>() {}.type)
            } catch (e: Exception) { emptyList() }
            
            val filtered = logsList.filter { log ->
                val msg = log.message.lowercase()
                msg.contains("serve") || msg.contains("funnel") || msg.contains("ingress") || msg.contains("accept: tcp") || msg.contains("tls")
            }
            
            withContext(Dispatchers.Main) {
                serveLogs = filtered
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 2) {
            while (true) {
                loadServeLogs()
                delay(3000)
            }
        }
    }

    LaunchedEffect(config) {
        val currentConfig = config ?: return@LaunchedEffect
        val targets = mutableListOf<String>()
        
        currentConfig.tcp?.forEach { (_, handler) ->
            handler.tcpForward?.let { targets.add(it) }
        }
        currentConfig.web?.forEach { (_, webConfig) ->
            webConfig.handlers?.values?.forEach { handler ->
                handler.proxy?.let { targets.add(it) }
            }
        }
        currentConfig.services?.forEach { (_, svcConfig) ->
            svcConfig.tcp?.forEach { (_, handler) ->
                handler.tcpForward?.let { targets.add(it) }
            }
            svcConfig.web?.forEach { (_, webConfig) ->
                webConfig.handlers?.values?.forEach { handler ->
                    handler.proxy?.let { targets.add(it) }
                }
            }
        }
        
        withContext(Dispatchers.IO) {
            val newHealth = mutableMapOf<String, Boolean>()
            targets.distinct().forEach { target ->
                newHealth[target] = checkTargetHealth(target)
            }
            withContext(Dispatchers.Main) {
                healthMap = newHealth
            }
        }
    }

    fun refresh() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                selfDns = Appctr.getSelfDNSName()
                val json = Appctr.getServeConfig()
                if (!json.startsWith("Error")) {
                    config = Gson().fromJson(json, ServeConfig::class.java)
                }
            } catch (e: Exception) { e.printStackTrace() }
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    fun saveConfig(newConfig: ServeConfig) {
        isLoading = true
        config = newConfig
        scope.launch(Dispatchers.IO) {
            // Если всё пусто - принудительно шлем пустой конфиг для очистки AllowFunnel и т.д.
            val jsonPayload = if (newConfig.tcp == null && newConfig.web == null && newConfig.services == null && newConfig.allowFunnel == null) {
                if (newConfig.etag != null) "{\"etag\": \"${newConfig.etag}\", \"TCP\": {}, \"Web\": {}, \"AllowFunnel\": {}}" else "{\"TCP\": {}, \"Web\": {}, \"AllowFunnel\": {}}"
            } else {
                Gson().toJson(newConfig)
            }
            val res = Appctr.setServeConfig(jsonPayload)
            withContext(Dispatchers.Main) {
                isLoading = false
                if (res != "OK") {
                    Toast.makeText(context, "Error: $res", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    fun getLink(port: Int, protocol: String, isFunnel: Boolean, serviceName: String? = null): String {
        if (selfDns.isEmpty()) return "Waiting for DNS..."
        val realProto = if (isFunnel) "https" else protocol
        val baseDns = if (serviceName != null) {
            val parts = selfDns.split(".", limit = 2)
            if (parts.size < 2) "$serviceName.$selfDns" else "$serviceName.${parts[1]}"
        } else selfDns
        // Для стандартных портов 80/443 не показываем порт в ссылке
        val portSuffix = if ((realProto == "http" && port == 80) || (realProto == "https" && port == 443)) "" else ":$port"
        return "$realProto://$baseDns$portSuffix"
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Serve & Funnel") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { showClearDialog = true }) { Icon(Icons.Default.DeleteSweep, "Clear All") }
                        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                )
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    Tab(selected = pagerState.currentPage == 0, onClick = { scope.launch { pagerState.animateScrollToPage(0) } }, text = { Text("Serve") })
                    Tab(selected = pagerState.currentPage == 1, onClick = { scope.launch { pagerState.animateScrollToPage(1) } }, text = { Text("Funnel") })
                    Tab(selected = pagerState.currentPage == 2, onClick = { scope.launch { pagerState.animateScrollToPage(2) } }, text = { Text("Logs") })
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { 
                showEditDialog = ServeRuleEditData(
                    isFunnel = pagerState.currentPage == 1,
                    port = if (pagerState.currentPage == 1) "443" else "10000"
                ) 
            }) {
                Icon(Icons.Default.Add, "Add Rule")
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { refresh() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 2) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(serveLogs.size) {
                        if (serveLogs.isNotEmpty()) {
                            listState.animateScrollToItem(serveLogs.size - 1)
                        }
                    }
                    if (serveLogs.isEmpty()) {
                        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.List, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                            Spacer(Modifier.height(16.dp))
                            Text("No request logs found", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        ) {
                            items(serveLogs) { log ->
                                val textColor = if (log.category == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                Text(
                                    text = "${log.timestamp} ${log.message}",
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = textColor,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                } else {
                    val isFunnelTab = page == 1
                    val serveItems = mutableListOf<@Composable () -> Unit>()
                    val funnelItems = mutableListOf<@Composable () -> Unit>()

                    // 1. Process Node-scoped Rules
                    config?.tcp?.forEach { (port, handler) ->
                        val hostKey = config?.web?.keys?.find { it.endsWith(":$port") } ?: "*:$port"
                        val isWeb = handler.https == true || handler.http == true
                        val protocol = if (handler.https == true) "https" else "http"
                        
                        val funnelKey = if (selfDns.isNotEmpty()) "$selfDns:$port" else "*:$port"
                        val isFunnel = config?.allowFunnel?.get(funnelKey) == true || config?.allowFunnel?.get("*:$port") == true

                        val webHandler = config?.web?.get(hostKey)?.handlers?.get("/")
                        val detailText = when {
                            webHandler?.proxy != null -> "Proxy -> ${webHandler.proxy}"
                            webHandler?.text != null -> "Text: ${webHandler.text}"
                            webHandler?.redirect != null -> "Redirect -> ${webHandler.redirect}"
                            handler.tcpForward != null -> "Forward -> ${handler.tcpForward}"
                            else -> "Web ($protocol)"
                        }

                        val target = when {
                            webHandler?.proxy != null -> webHandler.proxy
                            handler.tcpForward != null -> handler.tcpForward
                            else -> null
                        }
                        val health = if (target != null) healthMap[target] else null

                        val card = @Composable {
                            ServeRuleCard(
                                title = "Node Port $port",
                                subtitle = detailText,
                                fullUrl = getLink(port, protocol, isFunnel),
                                protocol = if (isWeb) protocol.uppercase() else "TCP",
                                isDisabled = handler.disabled == true,
                                healthStatus = health,
                                onClick = {
                                    showEditDialog = ServeRuleEditData(
                                        port = port.toString(),
                                        oldPort = port.toString(),
                                        target = when {
                                            webHandler?.proxy != null -> webHandler.proxy
                                            webHandler?.text != null -> webHandler.text
                                            webHandler?.redirect != null -> webHandler.redirect
                                            else -> handler.tcpForward ?: ""
                                        },
                                        mode = if (isWeb) "Web" else "TCP",
                                        transport = if (handler.https == true) "HTTPS" else "HTTP",
                                        handlerType = when {
                                            webHandler?.proxy != null -> "Proxy"
                                            webHandler?.text != null -> "Text"
                                            webHandler?.redirect != null -> "Redirect"
                                            else -> "Proxy"
                                        },
                                        isDisabled = handler.disabled == true,
                                        isFunnel = isFunnel,
                                        isEditing = true
                                    )
                                },
                                onCopy = { 
                                    clipboard.setText(AnnotatedString(getLink(port, protocol, isFunnel)))
                                    Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { 
                                    val newTcp = config?.tcp?.toMutableMap() ?: mutableMapOf()
                                    newTcp.remove(port)
                                    val newWeb = config?.web?.toMutableMap() ?: mutableMapOf()
                                    newWeb.remove(hostKey)
                                    val newFunnel = config?.allowFunnel?.toMutableMap() ?: mutableMapOf()
                                    newFunnel.remove(funnelKey)
                                    newFunnel.remove("*:$port")
                                    saveConfig(config!!.copy(
                                        tcp = if (newTcp.isNotEmpty()) newTcp else null,
                                        web = if (newWeb.isNotEmpty()) newWeb else null,
                                        allowFunnel = if (newFunnel.isNotEmpty()) newFunnel else null
                                    ))
                                }
                            )
                        }

                        if (isFunnel) funnelItems.add(card) else serveItems.add(card)
                    }

                    // 2. Process Services
                    config?.services?.forEach { (svcName, svcConfig) ->
                        val cleanSvcName = svcName.removePrefix("svc:")
                        svcConfig.tcp?.forEach { (port, handler) ->
                            val suffix = selfDns.substringAfter(".")
                            val fqdn = "$cleanSvcName.$suffix"
                            val hostKey = svcConfig.web?.keys?.find { it.endsWith(":$port") } ?: "$fqdn:$port"

                            val isWeb = handler.https == true || handler.http == true
                            val protocol = if (handler.https == true) "https" else "http"
                            
                            val webHandler = svcConfig.web?.get(hostKey)?.handlers?.get("/")
                            val detailText = when {
                                webHandler?.proxy != null -> "Proxy -> ${webHandler.proxy}"
                                webHandler?.text != null -> "Text: ${webHandler.text}"
                                webHandler?.redirect != null -> "Redirect -> ${webHandler.redirect}"
                                handler.tcpForward != null -> "Forward -> ${handler.tcpForward}"
                                else -> "Web ($protocol)"
                            }

                            val target = when {
                                webHandler?.proxy != null -> webHandler.proxy
                                handler.tcpForward != null -> handler.tcpForward
                                else -> null
                            }
                            val health = if (target != null) healthMap[target] else null

                            serveItems.add {
                                ServeRuleCard(
                                    title = "Service: $cleanSvcName (Port $port)",
                                    subtitle = detailText,
                                    fullUrl = getLink(port, protocol, false, cleanSvcName),
                                    protocol = if (isWeb) protocol.uppercase() else "TCP",
                                    isDisabled = handler.disabled == true,
                                    healthStatus = health,
                                    onClick = { 
                                        showEditDialog = ServeRuleEditData(
                                            port = port.toString(),
                                            oldPort = port.toString(),
                                            target = when {
                                                webHandler?.proxy != null -> webHandler.proxy
                                                webHandler?.text != null -> webHandler.text
                                                webHandler?.redirect != null -> webHandler.redirect
                                                else -> handler.tcpForward ?: ""
                                            },
                                            mode = if (isWeb) "Web" else "TCP",
                                            transport = if (handler.https == true) "HTTPS" else "HTTP",
                                            handlerType = when {
                                                webHandler?.proxy != null -> "Proxy"
                                                webHandler?.text != null -> "Text"
                                                webHandler?.redirect != null -> "Redirect"
                                                else -> "Proxy"
                                            },
                                            serviceName = cleanSvcName,
                                            oldServiceName = cleanSvcName,
                                            isDisabled = handler.disabled == true,
                                            isFunnel = false,
                                            isEditing = true
                                        )
                                    },
                                    onCopy = { 
                                        clipboard.setText(AnnotatedString(getLink(port, protocol, false, cleanSvcName)))
                                        Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    onDelete = { 
                                        val newServices = config?.services?.toMutableMap() ?: mutableMapOf()
                                        newServices.remove(svcName)
                                        saveConfig(config!!.copy(services = if (newServices.isNotEmpty()) newServices else null))
                                    }
                                )
                            }
                        }
                    }

                    val currentItems = if (isFunnelTab) funnelItems else serveItems
                    
                    if (currentItems.isEmpty() && !isLoading) {
                        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(if (isFunnelTab) Icons.Default.Language else Icons.Default.PublicOff, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                            Spacer(Modifier.height(16.dp))
                            Text(if (isFunnelTab) "No active Funnel rules" else "No active Serve rules", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(currentItems.size) { index -> currentItems[index]() }
                        }
                    }
                }
            }
        }
    }

    showEditDialog?.let { editData ->
        AddServeRuleDialog(
            data = editData,
            onDismiss = { showEditDialog = null },
            onConfirm = { port, target, mode, transport, handlerType, serviceName, isDisabled, proxyProtocol ->
                val currentConfig = config ?: ServeConfig()
                
                // 1. Prepare mutable copies
                val newTcp = currentConfig.tcp?.toMutableMap() ?: mutableMapOf()
                val newWeb = currentConfig.web?.toMutableMap() ?: mutableMapOf()
                val newGlobalFunnel = currentConfig.allowFunnel?.toMutableMap() ?: mutableMapOf()
                val newServices = currentConfig.services?.toMutableMap() ?: mutableMapOf()

                // 2. DELETE OLD RULE (if editing)
                if (editData.isEditing) {
                    val oPort = editData.oldPort.toIntOrNull() ?: editData.port.toIntOrNull() ?: 0
                    if (editData.oldServiceName.isNotEmpty()) {
                        val oldSvcKey = "svc:${editData.oldServiceName}"
                        newServices[oldSvcKey]?.let { oldSvc ->
                            val sTcp = oldSvc.tcp?.toMutableMap() ?: mutableMapOf()
                            val sWeb = oldSvc.web?.toMutableMap() ?: mutableMapOf()
                            
                            sTcp.remove(oPort)
                            sWeb.keys.filter { it.endsWith(":$oPort") }.forEach { sWeb.remove(it) }
                            
                            if (sTcp.isEmpty() && sWeb.isEmpty()) {
                                newServices.remove(oldSvcKey)
                            } else {
                                newServices[oldSvcKey] = oldSvc.copy(
                                    tcp = if (sTcp.isNotEmpty()) sTcp else null,
                                    web = if (sWeb.isNotEmpty()) sWeb else null
                                )
                            }
                        }
                    } else {
                        newTcp.remove(oPort)
                        newWeb.keys.filter { it.endsWith(":$oPort") }.forEach { newWeb.remove(it) }
                        newGlobalFunnel.remove("$selfDns:$oPort")
                        newGlobalFunnel.remove("*:$oPort")
                    }
                }

                // 3. ADD NEW RULE
                if (serviceName.isNotEmpty() && !editData.isFunnel) {
                    val svcKey = "svc:$serviceName"
                    val sCfg = newServices[svcKey] ?: ServiceConfig()
                    val sTcp = sCfg.tcp?.toMutableMap() ?: mutableMapOf()
                    val sWeb = sCfg.web?.toMutableMap() ?: mutableMapOf()
                    
                    val suffix = selfDns.substringAfter(".")
                    val fqdn = "$serviceName.$suffix"
                    val hostKey = "$fqdn:$port"

                    if (mode == "Web") {
                        val handler = when (handlerType) {
                            "Proxy" -> HTTPHandler(proxy = if (target.startsWith("http")) target else "http://$target")
                            "Text" -> HTTPHandler(text = target)
                            "Redirect" -> HTTPHandler(redirect = if (target.startsWith("http")) target else "https://$target")
                            else -> HTTPHandler(proxy = "http://$target")
                        }
                        sWeb[hostKey] = WebServerConfig(handlers = mapOf("/" to handler))
                        val useHttps = transport == "HTTPS"
                        sTcp[port] = TCPPortHandler(
                            https = if (useHttps) true else null,
                            http  = if (!useHttps) true else null,
                            disabled = isDisabled
                        )
                    } else {
                        sTcp[port] = TCPPortHandler(
                            tcpForward = target, 
                            disabled = isDisabled,
                            proxyProtocol = if (proxyProtocol > 0) proxyProtocol else null
                        )
                    }
                    newServices[svcKey] = sCfg.copy(
                        tcp = if (sTcp.isNotEmpty()) sTcp else null,
                        web = if (sWeb.isNotEmpty()) sWeb else null
                    )
                } else {
                    val hostKey = "*:$port"
                    if (mode == "Web") {
                        val handler = when (handlerType) {
                            "Proxy" -> HTTPHandler(proxy = if (target.startsWith("http")) target else "http://$target")
                            "Text" -> HTTPHandler(text = target)
                            "Redirect" -> HTTPHandler(redirect = if (target.startsWith("http")) target else "https://$target")
                            else -> HTTPHandler(proxy = "http://$target")
                        }
                        newWeb[hostKey] = WebServerConfig(handlers = mapOf("/" to handler))
                        val useHttps = editData.isFunnel || transport == "HTTPS"
                        newTcp[port] = TCPPortHandler(
                            https = if (useHttps) true else null,
                            http  = if (!useHttps) true else null,
                            disabled = isDisabled
                        )
                    } else {
                        newTcp[port] = TCPPortHandler(
                            tcpForward = target, 
                            disabled = isDisabled,
                            proxyProtocol = if (proxyProtocol > 0) proxyProtocol else null
                        )
                    }

                    if (editData.isFunnel) {
                        newGlobalFunnel["$selfDns:$port"] = true
                    }
                }

                saveConfig(ServeConfig(
                    tcp = if (newTcp.isNotEmpty()) newTcp else null,
                    web = if (newWeb.isNotEmpty()) newWeb else null,
                    allowFunnel = if (newGlobalFunnel.isNotEmpty()) newGlobalFunnel else null,
                    services = if (newServices.isNotEmpty()) newServices else null,
                    etag = currentConfig.etag
                ))
                showEditDialog = null
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Serve & Funnel?") },
            text = { Text("This will aggressively purge all local and remote Serve/Funnel configurations for this node. Are you sure?") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        saveConfig(ServeConfig(etag = config?.etag))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Purge") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
        )
    }
}

data class ServeRuleEditData(
    val port: String = "10000",
    val target: String = "127.0.0.1:8080",
    val mode: String = "Web", // Web or TCP
    val transport: String = "HTTPS", // HTTP or HTTPS
    val handlerType: String = "Proxy", // Proxy, Text, Redirect
    val proxyProtocol: Int = 0, // 0=None, 1=v1, 2=v2
    val isEditing: Boolean = false,
    val isFunnel: Boolean = false,
    val serviceName: String = "",
    val oldPort: String = "",
    val oldServiceName: String = "",
    val isDisabled: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServeRuleCard(title: String, subtitle: String, fullUrl: String, protocol: String, isDisabled: Boolean, healthStatus: Boolean?, onClick: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = if (isDisabled) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = when(protocol) {
                                "HTTPS" -> Color(0xFF4CAF50)
                                "HTTP" -> Color(0xFFFF9800)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(protocol, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (isDisabled) {
                            Spacer(Modifier.width(8.dp))
                            Surface(color = Color.Gray, shape = MaterialTheme.shapes.extraSmall) {
                                Text("OFF", color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        } else if (healthStatus != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = if (healthStatus) Color(0xFF4CAF50) else Color(0xFFF44336),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    if (healthStatus) "HEALTHY" else "UNREACHABLE",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red) }
            }
            
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().clickable { onCopy() }
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fullUrl, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun AddServeRuleDialog(data: ServeRuleEditData, onDismiss: () -> Unit, onConfirm: (Int, String, String, String, String, String, Boolean, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var port by remember { mutableStateOf(data.port) }
    var target by remember { mutableStateOf(data.target) }
    var mode by remember { mutableStateOf(data.mode) }
    var transport by remember { mutableStateOf(data.transport) }
    var handlerType by remember { mutableStateOf(data.handlerType) }
    var serviceName by remember { mutableStateOf(data.serviceName) }
    var isDisabled by remember { mutableStateOf(data.isDisabled) }
    var proxyProtocol by remember { mutableIntStateOf(data.proxyProtocol) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (data.isEditing) "Edit ${if (data.isFunnel) "Funnel" else "Serve"} Rule" else "Add ${if (data.isFunnel) "Funnel" else "Serve"} Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!data.isFunnel) {
                    OutlinedTextField(
                        value = serviceName,
                        onValueChange = { serviceName = it },
                        label = { Text("Service Name (optional)") },
                        placeholder = { Text("e.g. webapp") },
                        supportingText = { Text("Leave empty for Device-scoped rule") },
                        enabled = !data.isEditing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                Column {
                    Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Web", "TCP").forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { 
                                    mode = m
                                    if (m == "Web" && port == "10000") port = "443"
                                    if (m == "TCP" && (port == "443" || port == "80")) port = "10000"
                                },
                                label = { Text(m) }
                            )
                        }
                    }
                }

                if (mode == "Web") {
                    Column {
                        Text("Transport", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Funnel forces HTTPS
                            val transports = if (data.isFunnel) listOf("HTTPS") else listOf("HTTPS", "HTTP")
                            transports.forEach { t ->
                                FilterChip(
                                    selected = transport == t,
                                    onClick = { 
                                        transport = t 
                                        if (t == "HTTPS" && port == "80") port = "443"
                                        if (t == "HTTP" && port == "443") port = "80"
                                    },
                                    label = { Text(t) }
                                )
                            }
                        }
                    }

                    Column {
                        Text("Handler Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("Proxy", "Text", "Redirect").forEach { h ->
                                FilterChip(
                                    selected = handlerType == h,
                                    onClick = { handlerType = h },
                                    label = { Text(h) }
                                )
                            }
                        }
                    }
                } else {
                    Column {
                        Text("Proxy Protocol", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "None", 1 to "v1", 2 to "v2").forEach { (v, label) ->
                                FilterChip(
                                    selected = proxyProtocol == v,
                                    onClick = { proxyProtocol = v },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isDisabled, onCheckedChange = { isDisabled = it })
                    Text("Disable Rule (Offline)", modifier = Modifier.clickable { isDisabled = !isDisabled })
                }
                
                OutlinedTextField(
                    value = port, 
                    onValueChange = { port = it }, 
                    label = { Text("Tailscale Port") },
                    supportingText = { Text(if (data.isFunnel) "Funnel supports: 443, 8443, 10000" else "Any port for internal Serve") },
                    enabled = !data.isEditing,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = target, 
                    onValueChange = { target = it }, 
                    label = { 
                        Text(when {
                            mode == "TCP" -> "Target Address (e.g. 127.0.0.1:8080)"
                            handlerType == "Proxy" -> "Target URL (e.g. 127.0.0.1:80)"
                            handlerType == "Text" -> "Text content"
                            handlerType == "Redirect" -> "Destination URL (e.g. https://google.com)"
                            else -> "Target"
                        })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                val p = port.toIntOrNull() ?: 10000
                if (data.isFunnel && p !in listOf(443, 8443, 10000)) {
                    Toast.makeText(context, "Funnel only supports ports 443, 8443, 10000", Toast.LENGTH_LONG).show()
                } else {
                    onConfirm(p, target, mode, transport, handlerType, serviceName, isDisabled, proxyProtocol)
                }
            }) { Text(if (data.isEditing) "Save" else "Add") }
        },

        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
