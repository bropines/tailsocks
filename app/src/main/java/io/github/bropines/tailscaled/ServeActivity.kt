package io.github.bropines.tailscaled

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<ServeConfig?>(null) }
    var selfDns by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf<ServeRuleEditData?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current

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
            val res = Appctr.setServeConfig(Gson().toJson(newConfig))
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
            TopAppBar(
                title = { Text("Serve & Funnel") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showEditDialog = ServeRuleEditData() }) {
                Icon(Icons.Default.Add, "Add Rule")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading && config == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (config == null || (config?.tcp.isNullOrEmpty() && config?.web.isNullOrEmpty() && config?.services.isNullOrEmpty())) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PublicOff, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("No active rules", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Node-scoped TCP Rules
                    config?.tcp?.let { tcpMap ->
                        items(tcpMap.toList()) { (port, handler) ->
                            val isWeb = handler.https == true || handler.http == true
                            val protocol = if (handler.https == true) "https" else "http"
                            val isFunnel = config?.allowFunnel?.get(":$port") == true
                            val fullUrl = getLink(port, protocol, isFunnel)
                            
                            val webHandler = config?.web?.get(":$port")?.handlers?.get("/")
                            val detailText = when {
                                webHandler?.proxy != null -> "Proxy -> ${webHandler.proxy}"
                                webHandler?.text != null -> "Text: ${webHandler.text}"
                                webHandler?.redirect != null -> "Redirect -> ${webHandler.redirect}"
                                handler.tcpForward != null -> "Forward -> ${handler.tcpForward}"
                                else -> "Web ($protocol)"
                            }

                            ServeRuleCard(
                                title = "Node Port $port",
                                subtitle = detailText,
                                fullUrl = fullUrl,
                                isFunnel = isFunnel,
                                protocol = if (isWeb) protocol.uppercase() else "TCP",
                                isDisabled = handler.disabled == true,
                                onClick = {
                                    showEditDialog = ServeRuleEditData(
                                        port = port.toString(),
                                        target = when {
                                            webHandler?.proxy != null -> webHandler.proxy
                                            webHandler?.text != null -> webHandler.text
                                            webHandler?.redirect != null -> webHandler.redirect
                                            else -> handler.tcpForward ?: ""
                                        },
                                        mode = if (isWeb) "Web" else "TCP",
                                        transport = if (handler.http == true) "HTTP" else "HTTPS",
                                        handlerType = when {
                                            webHandler?.proxy != null -> "Proxy"
                                            webHandler?.text != null -> "Text"
                                            webHandler?.redirect != null -> "Redirect"
                                            else -> "Proxy"
                                        },
                                        isDisabled = handler.disabled == true,
                                        isEditing = true
                                    )
                                },
                                onCopy = { 
                                    clipboard.setText(AnnotatedString(fullUrl))
                                    Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { 
                                    val newTcp = config?.tcp?.toMutableMap() ?: mutableMapOf()
                                    newTcp.remove(port)
                                    val newWeb = config?.web?.toMutableMap() ?: mutableMapOf()
                                    newWeb.remove(":$port")
                                    val newFunnel = config?.allowFunnel?.toMutableMap() ?: mutableMapOf()
                                    newFunnel.remove(":$port")
                                    saveConfig(config!!.copy(tcp = newTcp, web = newWeb, allowFunnel = newFunnel))
                                },
                                onFunnelToggle = { enabled ->
                                    val newFunnel = config?.allowFunnel?.toMutableMap() ?: mutableMapOf()
                                    val newTcp = config?.tcp?.toMutableMap() ?: mutableMapOf()
                                    if (enabled) {
                                        newFunnel[":$port"] = true
                                        val oldH = newTcp[port]
                                        if (oldH != null && oldH.http == true) {
                                            newTcp[port] = oldH.copy(https = true, http = false)
                                        }
                                    } else {
                                        newFunnel.remove(":$port")
                                    }
                                    saveConfig(config!!.copy(allowFunnel = newFunnel, tcp = newTcp))
                                }
                            )
                        }
                    }

                    // Tailscale Services
                    config?.services?.let { servicesMap ->
                        items(servicesMap.toList()) { (svcName, svcConfig) ->
                            val cleanSvcName = svcName.removePrefix("svc:")
                            svcConfig.tcp?.forEach { (port, handler) ->
                                val isWeb = handler.https == true || handler.http == true
                                val protocol = if (handler.https == true) "https" else "http"
                                val isFunnel = svcConfig.allowFunnel?.get(":$port") == true
                                val fullUrl = getLink(port, protocol, isFunnel, cleanSvcName)
                                
                                val webHandler = svcConfig.web?.get(":$port")?.handlers?.get("/")
                                val detailText = when {
                                    webHandler?.proxy != null -> "Proxy -> ${webHandler.proxy}"
                                    webHandler?.text != null -> "Text: ${webHandler.text}"
                                    webHandler?.redirect != null -> "Redirect -> ${webHandler.redirect}"
                                    handler.tcpForward != null -> "Forward -> ${handler.tcpForward}"
                                    else -> "Web ($protocol)"
                                }

                                ServeRuleCard(
                                    title = "Service: $cleanSvcName (Port $port)",
                                    subtitle = detailText,
                                    fullUrl = fullUrl,
                                    isFunnel = isFunnel,
                                    protocol = if (isWeb) protocol.uppercase() else "TCP",
                                    isDisabled = handler.disabled == true,
                                    onClick = { 
                                        showEditDialog = ServeRuleEditData(
                                            port = port.toString(),
                                            target = when {
                                                webHandler?.proxy != null -> webHandler.proxy
                                                webHandler?.text != null -> webHandler.text
                                                webHandler?.redirect != null -> webHandler.redirect
                                                else -> handler.tcpForward ?: ""
                                            },
                                            mode = if (isWeb) "Web" else "TCP",
                                            transport = if (handler.http == true) "HTTP" else "HTTPS",
                                            handlerType = when {
                                                webHandler?.proxy != null -> "Proxy"
                                                webHandler?.text != null -> "Text"
                                                webHandler?.redirect != null -> "Redirect"
                                                else -> "Proxy"
                                            },
                                            serviceName = cleanSvcName,
                                            isDisabled = handler.disabled == true,
                                            isEditing = true
                                        )
                                    },
                                    onCopy = { 
                                        clipboard.setText(AnnotatedString(fullUrl))
                                        Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    onDelete = { 
                                        val newServices = config?.services?.toMutableMap() ?: mutableMapOf()
                                        newServices.remove(svcName)
                                        saveConfig(config!!.copy(services = newServices))
                                    },
                                    onFunnelToggle = { enabled ->
                                        val newServices = config?.services?.toMutableMap() ?: mutableMapOf()
                                        val oldSvc = newServices[svcName] ?: ServiceConfig()
                                        val newFunnel = oldSvc.allowFunnel?.toMutableMap() ?: mutableMapOf()
                                        val newTcp = oldSvc.tcp?.toMutableMap() ?: mutableMapOf()
                                        if (enabled) {
                                            newFunnel[":$port"] = true
                                            val oldH = newTcp[port]
                                            if (oldH != null && oldH.http == true) {
                                                newTcp[port] = oldH.copy(https = true, http = false)
                                            }
                                        } else {
                                            newFunnel.remove(":$port")
                                        }
                                        newServices[svcName] = oldSvc.copy(allowFunnel = newFunnel, tcp = newTcp)
                                        saveConfig(config!!.copy(services = newServices))
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (isLoading && config != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    showEditDialog?.let { data ->
        AddServeRuleDialog(
            data = data,
            onDismiss = { showEditDialog = null },
            onConfirm = { port, target, mode, transport, handlerType, serviceName, isDisabled ->
                val currentConfig = config ?: ServeConfig()
                
                if (serviceName.isNotEmpty()) {
                    val svcKey = "svc:$serviceName"
                    val newServices = currentConfig.services?.toMutableMap() ?: mutableMapOf()
                    val oldSvc = newServices[svcKey] ?: ServiceConfig()
                    val svcTcp = oldSvc.tcp?.toMutableMap() ?: mutableMapOf()
                    val svcWeb = oldSvc.web?.toMutableMap() ?: mutableMapOf()
                    val isFunnel = oldSvc.allowFunnel?.get("*:$port") == true
                    
                    // Для сервисов (VIP) ОБЯЗАТЕЛЬНО используем полный FQDN, иначе будет 404
                    val suffix = selfDns.substringAfter(".")
                    val fqdn = "$serviceName.$suffix"
                    val hostKey = "$fqdn:$port"

                    if (mode == "Web") {
                        val handler = when (handlerType) {
                            "Proxy" -> HTTPHandler(proxy = if (target.startsWith("http")) target else "http://$target")
                            "Text" -> HTTPHandler(text = target)
                            // Normalise: daemon requires a full URL with scheme.
                            "Redirect" -> HTTPHandler(redirect = if (target.startsWith("http")) target else "https://$target")
                            else -> HTTPHandler(proxy = "http://$target")
                        }
                        svcWeb[hostKey] = WebServerConfig(handlers = mapOf("/" to handler))
                        val useHttps = isFunnel || transport == "HTTPS"
                        svcTcp[port] = TCPPortHandler(
                            https = if (useHttps) true else null,
                            http  = if (!useHttps) true else null,
                            tcpForward = null,
                            terminateTLS = null,
                            disabled = isDisabled
                        )
                    } else {
                        svcTcp[port] = TCPPortHandler(
                            tcpForward = target, 
                            https = null, 
                            http = null, 
                            terminateTLS = null,
                            disabled = isDisabled
                        )
                        svcWeb.remove(hostKey)
                    }
                    newServices[svcKey] = ServiceConfig(tcp = svcTcp, web = if (svcWeb.isNotEmpty()) svcWeb else null, allowFunnel = oldSvc.allowFunnel)
                    saveConfig(currentConfig.copy(services = newServices))
                } else {
                    val newTcp = currentConfig.tcp?.toMutableMap() ?: mutableMapOf()
                    val newWeb = currentConfig.web?.toMutableMap() ?: mutableMapOf()
                    val isFunnel = currentConfig.allowFunnel?.get("*:$port") == true
                    
                    if (mode == "Web") {
                        val handler = when (handlerType) {
                            "Proxy" -> HTTPHandler(proxy = if (target.startsWith("http")) target else "http://$target")
                            "Text" -> HTTPHandler(text = target)
                            // Normalise: daemon requires a full URL with scheme.
                            "Redirect" -> HTTPHandler(redirect = if (target.startsWith("http")) target else "https://$target")
                            else -> HTTPHandler(proxy = "http://$target")
                        }
                        newWeb["*:$port"] = WebServerConfig(handlers = mapOf("/" to handler))
                        val useHttps = isFunnel || transport == "HTTPS"
                        // Set only the active protocol field; the other must be null (absent in JSON),
                        // not false — the daemon rejects configs where both fields are present.
                        newTcp[port] = TCPPortHandler(
                            https = if (useHttps) true else null,
                            http  = if (!useHttps) true else null,
                            tcpForward = null,
                            terminateTLS = null,
                            disabled = isDisabled
                        )
                    } else {
                        newTcp[port] = TCPPortHandler(
                            tcpForward = target, 
                            https = null, 
                            http = null, 
                            terminateTLS = null,
                            disabled = isDisabled
                        )
                        newWeb.remove("*:$port")
                    }
                    saveConfig(currentConfig.copy(tcp = newTcp, web = newWeb))
                }
                showEditDialog = null
            }
        )
    }
}

data class ServeRuleEditData(
    val port: String = "10000",
    val target: String = "127.0.0.1:8080",
    val mode: String = "Web", // Web or TCP
    val transport: String = "HTTPS", // HTTP or HTTPS
    val handlerType: String = "Proxy", // Proxy, Text, Redirect
    val isEditing: Boolean = false,
    val serviceName: String = "",
    val isDisabled: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServeRuleCard(title: String, subtitle: String, fullUrl: String, isFunnel: Boolean, protocol: String, isDisabled: Boolean, onClick: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onFunnelToggle: (Boolean) -> Unit) {
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
                        }
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                FilterChip(
                    selected = isFunnel,
                    onClick = { onFunnelToggle(!isFunnel) },
                    label = { Text("Funnel", fontSize = 10.sp) },
                    leadingIcon = { if (isFunnel) Icon(Icons.Default.Language, null, modifier = Modifier.size(16.dp)) else null }
                )
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
fun AddServeRuleDialog(data: ServeRuleEditData, onDismiss: () -> Unit, onConfirm: (Int, String, String, String, String, String, Boolean) -> Unit) {
    var port by remember { mutableStateOf(data.port) }
    var target by remember { mutableStateOf(data.target) }
    var mode by remember { mutableStateOf(data.mode) }
    var transport by remember { mutableStateOf(data.transport) }
    var handlerType by remember { mutableStateOf(data.handlerType) }
    var serviceName by remember { mutableStateOf(data.serviceName) }
    var isDisabled by remember { mutableStateOf(data.isDisabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (data.isEditing) "Edit Serve Rule" else "Add Serve Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = serviceName,
                    onValueChange = { serviceName = it },
                    label = { Text("Service Name (optional)") },
                    placeholder = { Text("e.g. webapp") },
                    supportingText = { Text("Leave empty for Device-scoped rule") },
                    enabled = !data.isEditing,
                    modifier = Modifier.fillMaxWidth()
                )
                
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
                            listOf("HTTPS", "HTTP").forEach { t ->
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
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isDisabled, onCheckedChange = { isDisabled = it })
                    Text("Disable Rule (Offline)", modifier = Modifier.clickable { isDisabled = !isDisabled })
                }
                
                OutlinedTextField(
                    value = port, 
                    onValueChange = { port = it }, 
                    label = { Text("Tailscale Port") },
                    supportingText = { Text("Funnel supports: 443, 8443, 10000") },
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
            Button(onClick = { onConfirm(port.toIntOrNull() ?: 10000, target, mode, transport, handlerType, serviceName, isDisabled) }) { Text(if (data.isEditing) "Save" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
