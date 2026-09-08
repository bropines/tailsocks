package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

// A FragmentActivity, not a ComponentActivity: publishing a service goes through
// the Admin API behind the same biometric prompt as the console, and
// BiometricPrompt wants one.
class ServeActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                ServeScreen(onBack = { finish() })
            }
        }
    }
}

/** Whether something answers on a rule's target, as a plain TCP connect with a 1 s deadline. */
fun checkTargetHealth(target: String): Boolean {
    val cleanTarget = target
        .removePrefix("https+insecure://").removePrefix("http://").removePrefix("https://")
        .substringBefore("/")
    if (cleanTarget.isBlank()) return false
    val hostPort = cleanTarget.split(":")
    val host = hostPort.getOrNull(0)?.ifEmpty { "127.0.0.1" } ?: "127.0.0.1"
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

// ---------------------------------------------------------------------------------------------
// The model the screen works with. The daemon's ServeConfig is three maps keyed by port, by
// "host:port" and by service; a person thinks in rules — "port 443 proxies to 127.0.0.1:8080,
// public". rulesOf() reads the maps into rules, with()/without() write one rule back.
// ---------------------------------------------------------------------------------------------

/** What a rule does with what arrives on its port. FILE is the daemon's directory handler:
 *  shown when the CLI created one, never created here. */
private enum class RuleKind { PROXY, TEXT, REDIRECT, TCP, FILE }

private data class ServeRule(
    /** Service name without `svc:`, or null for a rule on the node itself. */
    val service: String?,
    val port: Int,
    /** Mount path of a web handler; several can share one port. `/` for TCP. */
    val path: String,
    val kind: RuleKind,
    /** Web: HTTPS rather than plain HTTP. TCP: TLS is terminated before forwarding. */
    val tls: Boolean,
    val target: String,
    val funnel: Boolean,
    val proxyProtocol: Int,
    /** The proxy target is `https+insecure://`: an HTTPS backend with an untrusted certificate. */
    val insecureBackend: Boolean
)

/** The ports Funnel accepts when the node carries no funnel-ports capability (ipn.CheckFunnelPort). */
private val FUNNEL_DEFAULT_PORTS = listOf(443, 8443, 10000)

/** What this node may do, read off the daemon status once per refresh. */
private data class ServeCapabilities(
    val loaded: Boolean = false,
    val dnsName: String = "",
    val https: Boolean = false,
    val funnel: Boolean = false,
    val funnelPorts: List<Int> = FUNNEL_DEFAULT_PORTS,
    val services: Boolean = false,
    val certDomains: List<String> = emptyList(),
    /** This node's stable ID, what the Admin API approves a service host by. */
    val nodeId: String = "",
    /**
     * Services the tailnet has made this node a host of, by name without `svc:`,
     * with their addresses: the netmap's `service-host` capability. Present only
     * once the service is defined and this node approved — the in-app answer to
     * "did the publish work".
     */
    val serviceHosts: Map<String, List<String>> = emptyMap()
) {
    private val tailnetSuffix: String get() = dnsName.substringAfter(".", "")

    /** The host a rule is reached at: the node, or `<service>.<tailnet>`. */
    fun host(service: String?): String = when {
        service == null -> dnsName
        tailnetSuffix.isEmpty() -> service
        else -> "$service.$tailnetSuffix"
    }
}

private fun capabilitiesOf(status: StatusResponse): ServeCapabilities {
    val self = status.self
    val caps = self?.capMap?.keys ?: emptySet()
    val ports = caps.firstOrNull { it.startsWith("https://tailscale.com/cap/funnel-ports") }
        ?.substringAfter("ports=", "")
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: FUNNEL_DEFAULT_PORTS
    return ServeCapabilities(
        loaded = true,
        dnsName = self?.dnsName?.trimEnd('.') ?: "",
        https = "https" in caps,
        funnel = "funnel" in caps,
        funnelPorts = ports,
        // Service hosts must be tagged nodes (the daemon refuses others); the
        // capability is what the coordinator grants such a node.
        services = "cap:advertise-services" in caps || !self?.tags.isNullOrEmpty(),
        certDomains = status.certDomains ?: emptyList(),
        nodeId = self?.id ?: "",
        serviceHosts = serviceHostsOf(self?.capMap?.get("service-host"))
    )
}

/** `service-host` is an array of `{"svc:name": ["100.x", "fd7a:…"]}` objects (tailcfg.ServiceIPMappings). */
private fun serviceHostsOf(cap: JsonElement?): Map<String, List<String>> {
    val out = HashMap<String, List<String>>()
    val items = (cap as? JsonArray) ?: return out
    for (item in items) {
        val obj = item as? JsonObject ?: continue
        for ((name, addrs) in obj) {
            out[name.removePrefix("svc:")] = (addrs as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        }
    }
    return out
}

private fun rulesOf(config: ServeConfig): List<ServeRule> {
    val out = ArrayList<ServeRule>()
    fun add(service: String?, tcp: Map<Int, TCPPortHandler>?, web: Map<String, WebServerConfig>?, funnelFor: (Int) -> Boolean) {
        tcp?.forEach { (port, h) ->
            if (h.tcpForward != null) {
                out += ServeRule(
                    service, port, "/", RuleKind.TCP,
                    tls = !h.terminateTLS.isNullOrEmpty(), target = h.tcpForward, funnel = funnelFor(port),
                    proxyProtocol = h.proxyProtocol ?: 0, insecureBackend = false
                )
                return@forEach
            }
            val handlers = web?.entries?.firstOrNull { it.key.endsWith(":$port") }?.value?.handlers.orEmpty()
            if (handlers.isEmpty()) {
                out += ServeRule(
                    service, port, "/", RuleKind.PROXY,
                    tls = h.https == true, target = "", funnel = funnelFor(port),
                    proxyProtocol = 0, insecureBackend = false
                )
            }
            handlers.forEach { (path, wh) ->
                val kind = when {
                    wh.proxy != null -> RuleKind.PROXY
                    wh.text != null -> RuleKind.TEXT
                    wh.redirect != null -> RuleKind.REDIRECT
                    wh.path != null -> RuleKind.FILE
                    else -> RuleKind.PROXY
                }
                val raw = wh.proxy ?: wh.text ?: wh.redirect ?: wh.path ?: ""
                val insecure = raw.startsWith("https+insecure://")
                val target = when (kind) {
                    RuleKind.PROXY -> raw.removePrefix("https+insecure://").removePrefix("http://")
                    else -> raw
                }
                out += ServeRule(
                    service, port, path.ifEmpty { "/" }, kind,
                    tls = h.https == true, target = target, funnel = funnelFor(port),
                    proxyProtocol = 0, insecureBackend = insecure
                )
            }
        }
    }
    add(null, config.tcp, config.web) { port ->
        config.allowFunnel?.any { (key, on) -> on && key.endsWith(":$port") } == true
    }
    config.services?.forEach { (name, svc) -> add(name.removePrefix("svc:"), svc.tcp, svc.web) { false } }
    return out.sortedWith(compareBy<ServeRule> { it.service ?: "" }.thenBy { it.port }.thenBy { it.path })
}

/** The proxy target as the daemon wants it: a URL, `http://` unless the user said otherwise. */
private fun proxyTarget(rule: ServeRule): String {
    val t = rule.target.trim()
    return when {
        rule.insecureBackend -> "https+insecure://" + t.removePrefix("https+insecure://").removePrefix("https://").removePrefix("http://")
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("https+insecure://") -> t
        else -> "http://$t"
    }
}

private fun ServeConfig.without(rule: ServeRule): ServeConfig {
    fun strip(tcp: Map<Int, TCPPortHandler>?, web: Map<String, WebServerConfig>?): Pair<Map<Int, TCPPortHandler>?, Map<String, WebServerConfig>?> {
        val newTcp = tcp?.toMutableMap() ?: mutableMapOf()
        val newWeb = web?.toMutableMap() ?: mutableMapOf()
        if (rule.kind == RuleKind.TCP) {
            newTcp.remove(rule.port)
        } else {
            val key = newWeb.keys.firstOrNull { it.endsWith(":${rule.port}") }
            val handlers = key?.let { newWeb[it]?.handlers }?.toMutableMap() ?: mutableMapOf()
            handlers.remove(rule.path)
            if (handlers.isEmpty()) {
                // The last handler on the port takes the port's listener with it.
                if (key != null) newWeb.remove(key)
                newTcp.remove(rule.port)
            } else {
                newWeb[key!!] = WebServerConfig(handlers)
            }
        }
        return newTcp.ifEmpty { null } to newWeb.ifEmpty { null }
    }
    if (rule.service != null) {
        val key = "svc:${rule.service}"
        val svc = services?.get(key) ?: return this
        val (t, w) = strip(svc.tcp, svc.web)
        val newServices = services!!.toMutableMap()
        if (t == null && w == null) newServices.remove(key) else newServices[key] = ServiceConfig(t, w)
        return copy(services = newServices.ifEmpty { null })
    }
    val (t, w) = strip(tcp, web)
    val portGone = t?.containsKey(rule.port) != true
    val funnel = if (portGone) allowFunnel?.filterKeys { !it.endsWith(":${rule.port}") } else allowFunnel
    return copy(tcp = t, web = w, allowFunnel = funnel?.ifEmpty { null })
}

private fun ServeConfig.with(rule: ServeRule, caps: ServeCapabilities): ServeConfig {
    val host = caps.host(rule.service)
    val hostKey = "$host:${rule.port}"
    fun place(tcp: Map<Int, TCPPortHandler>?, web: Map<String, WebServerConfig>?): Pair<Map<Int, TCPPortHandler>, Map<String, WebServerConfig>?> {
        val newTcp = tcp?.toMutableMap() ?: mutableMapOf()
        val newWeb = web?.toMutableMap() ?: mutableMapOf()
        if (rule.kind == RuleKind.TCP) {
            newTcp[rule.port] = TCPPortHandler(
                tcpForward = rule.target.trim(),
                terminateTLS = host.takeIf { rule.tls },
                proxyProtocol = rule.proxyProtocol.takeIf { it > 0 }
            )
        } else {
            val handler = when (rule.kind) {
                RuleKind.TEXT -> HTTPHandler(text = rule.target)
                RuleKind.REDIRECT -> HTTPHandler(redirect = rule.target.trim().let { if (it.startsWith("http")) it else "https://$it" })
                RuleKind.FILE -> HTTPHandler(path = rule.target)
                else -> HTTPHandler(proxy = proxyTarget(rule))
            }
            val tls = rule.tls || rule.funnel
            val key = newWeb.keys.firstOrNull { it.endsWith(":${rule.port}") } ?: hostKey
            newWeb[key] = WebServerConfig((newWeb[key]?.handlers ?: emptyMap()) + (rule.path to handler))
            newTcp[rule.port] = TCPPortHandler(
                https = true.takeIf { tls },
                http = true.takeIf { !tls }
            )
        }
        return newTcp to newWeb.ifEmpty { null }
    }
    if (rule.service != null) {
        val key = "svc:${rule.service}"
        val svc = services?.get(key) ?: ServiceConfig()
        val (t, w) = place(svc.tcp, svc.web)
        return copy(services = (services ?: emptyMap()) + (key to ServiceConfig(t, w)))
    }
    val (t, w) = place(tcp, web)
    val funnel = (allowFunnel ?: emptyMap())
        .filterKeys { !it.endsWith(":${rule.port}") }
        .let { if (rule.funnel) it + (hostKey to true) else it }
    return copy(tcp = t, web = w, allowFunnel = funnel.ifEmpty { null })
}

private fun ruleUrl(rule: ServeRule, caps: ServeCapabilities): String {
    val host = caps.host(rule.service)
    if (host.isEmpty()) return ""
    if (rule.kind == RuleKind.TCP) return (if (rule.tls) "tls://" else "tcp://") + "$host:${rule.port}"
    val scheme = if (rule.tls || rule.funnel) "https" else "http"
    val defaultPort = if (scheme == "https") 443 else 80
    val base = if (rule.port == defaultPort) "$scheme://$host" else "$scheme://$host:${rule.port}"
    return if (rule.path == "/" || rule.path.isEmpty()) base else base + rule.path
}

private fun ruleDescription(context: Context, rule: ServeRule): String = when (rule.kind) {
    RuleKind.PROXY -> context.getString(R.string.serve_desc_proxy, rule.target.ifEmpty { "?" })
    RuleKind.TEXT -> context.getString(R.string.serve_desc_text, rule.target)
    RuleKind.REDIRECT -> context.getString(R.string.serve_desc_redirect, rule.target)
    RuleKind.TCP -> context.getString(R.string.serve_desc_tcp, rule.target)
    RuleKind.FILE -> context.getString(R.string.serve_desc_file, rule.target)
}

private fun kindLabel(context: Context, kind: RuleKind): String = when (kind) {
    RuleKind.PROXY -> context.getString(R.string.serve_kind_proxy)
    RuleKind.TEXT -> context.getString(R.string.serve_kind_text)
    RuleKind.REDIRECT -> context.getString(R.string.serve_kind_redirect)
    RuleKind.TCP -> context.getString(R.string.serve_kind_tcp)
    RuleKind.FILE -> context.getString(R.string.serve_kind_file)
}

private fun kindIcon(kind: RuleKind): ImageVector = when (kind) {
    RuleKind.PROXY -> Icons.Default.SwapHoriz
    RuleKind.TEXT -> Icons.Default.Notes
    RuleKind.REDIRECT -> Icons.AutoMirrored.Filled.OpenInNew
    RuleKind.TCP -> Icons.Default.Cable
    RuleKind.FILE -> Icons.Default.Folder
}

/** A rule in the editor: the one it replaces (null for a new one) and the values it opens with. */
private data class RuleEditorState(val original: ServeRule?, val initial: ServeRule)

private fun newRuleTemplate(): ServeRule = ServeRule(
    service = null, port = 443, path = "/", kind = RuleKind.PROXY, tls = true,
    target = "127.0.0.1:8080", funnel = false, proxyProtocol = 0, insecureBackend = false
)

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var config by remember { mutableStateOf<ServeConfig?>(null) }
    var caps by remember { mutableStateOf(ServeCapabilities()) }
    var isLoading by remember { mutableStateOf(true) }
    var healthMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var editor by remember { mutableStateOf<RuleEditorState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showCertExportDialog by remember { mutableStateOf(false) }
    var pendingCertData by remember { mutableStateOf("") }
    /** A service whose tailnet-side definition and host approval are being offered. */
    var publishFor by remember { mutableStateOf<String?>(null) }
    var publishBusy by remember { mutableStateOf(false) }
    /** When the last publish succeeded; the netmap is re-read a few seconds after. */
    var publishedAt by remember { mutableStateOf(0L) }

    val rules = remember(config) { config?.let { rulesOf(it) } ?: emptyList() }

    /** The Admin API credentials for this tailnet, if the console was ever set up for it. */
    fun adminSettings(): AdminApiSettings? {
        val tailnet = AdminApiSettings.lastKnownTailnet(context).ifBlank { caps.dnsName.substringAfter(".", "") }
        if (tailnet.isBlank()) return null
        return AdminApiSettings.read(context, tailnet).takeIf { it.hasCredentials }
    }

    /**
     * Defines `svc:<service>` in the tailnet (or adds this screen's ports to its
     * definition) and approves this node as its host, through the Admin API and
     * behind the device credential. The two steps the admin console otherwise
     * asks for by hand.
     */
    fun publishService(service: String, ports: List<Int>) {
        val settings = adminSettings() ?: return
        val run = {
            publishBusy = true
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val client = settings.newClient(context)
                    val name = "svc:$service"
                    val existing = client.getTailnetService(name)
                    // The definition's endpoints are exactly what this node serves for
                    // the service. Merging in the old ones left a port behind when a
                    // rule moved (443 → 2550), and a host that does not serve every
                    // defined port is "needs configuration" in the console: control
                    // then hands the service address to nobody.
                    client.createOrUpdateService(
                        VIPServiceInfo(
                            name = name,
                            addrs = existing?.addrs,
                            comment = existing?.comment,
                            ports = ports.distinct().sorted().map { "tcp:$it" },
                            tags = existing?.tags
                        )
                    )
                    if (caps.nodeId.isNotEmpty()) client.setServiceDeviceApproved(name, caps.nodeId, true)
                    name
                }
                withContext(Dispatchers.Main) {
                    publishBusy = false
                    result.onSuccess {
                        Toast.makeText(context, context.getString(R.string.serve_svc_publish_done, it), Toast.LENGTH_LONG).show()
                        publishedAt = System.currentTimeMillis()
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.serve_svc_publish_failed, it.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val activity = context.findFragmentActivity()
        if (activity == null) run()
        else activity.authenticateWithBiometrics(
            context.getString(R.string.admin_biometric_title),
            context.getString(R.string.serve_svc_biometric_subtitle)
        ) { ok -> if (ok) run() }
    }

    val certSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        if (uri != null && pendingCertData.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(pendingCertData.toByteArray())
                        out.flush()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.serve_cert_saved), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.serve_save_failed_format, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // One connect per distinct target whenever the rules change; the result rides on the card.
    LaunchedEffect(rules) {
        val targets = rules.filter { it.kind == RuleKind.PROXY || it.kind == RuleKind.TCP }
            .map { it.target }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) { healthMap = emptyMap(); return@LaunchedEffect }
        val checked = withContext(Dispatchers.IO) { targets.associateWith { checkTargetHealth(it) } }
        healthMap = checked
    }

    fun refresh() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val statusJson = runCatching { Appctr.getStatusFromAPI() }.getOrDefault("")
            val newCaps = if (statusJson.isNotBlank() && !statusJson.contains("\"Error\""))
                runCatching { capabilitiesOf(AppJson.decodeFromString<StatusResponse>(statusJson)) }.getOrNull()
            else null
            val json = runCatching { Appctr.getServeConfig() }.getOrDefault("")
            val newConfig = if (json.isNotBlank() && !json.contains("\"Error\""))
                runCatching { AppJson.decodeFromString<ServeConfig>(json) }.getOrNull()
            else null
            withContext(Dispatchers.Main) {
                if (newCaps != null) caps = newCaps
                if (newConfig != null) config = newConfig
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Control hands the node its service-host capability a few seconds after a
    // publish; read the netmap again then, so the service heading flips.
    LaunchedEffect(publishedAt) {
        if (publishedAt != 0L) {
            kotlinx.coroutines.delay(3000); refresh()
            kotlinx.coroutines.delay(7000); refresh()
        }
    }

    // Right after the app starts, the bridge may not have reached the daemon yet
    // (Root Mode attaches a few seconds in); keep asking for a while instead of
    // leaving the card at "Not loaded yet" until the user pulls.
    LaunchedEffect(caps.loaded) {
        var attempts = 0
        while (!caps.loaded && attempts < 10) {
            kotlinx.coroutines.delay(3000)
            if (!isLoading) refresh()
            attempts++
        }
    }

    /**
     * Applies [change] to the config the daemon holds right now, not to the one
     * the screen loaded: the CLI (or another client) may have changed it since,
     * and the daemon refuses a write whose ETag is stale. The fresh read also
     * brings the current ETag along.
     */
    fun applyChange(change: (ServeConfig) -> ServeConfig) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val json = runCatching { Appctr.getServeConfig() }.getOrDefault("")
            val fresh = if (json.isNotBlank() && !json.contains("\"Error\""))
                runCatching { AppJson.decodeFromString<ServeConfig>(json) }.getOrNull() ?: ServeConfig()
            else ServeConfig()
            val newConfig = change(fresh)
            withContext(Dispatchers.Main) { config = newConfig }
            // An emptied config must still be sent as explicit empty maps, or the daemon keeps AllowFunnel.
            val jsonPayload = if (newConfig.tcp == null && newConfig.web == null && newConfig.services == null && newConfig.allowFunnel == null) {
                if (newConfig.etag != null) "{\"etag\": \"${newConfig.etag}\", \"TCP\": {}, \"Web\": {}, \"AllowFunnel\": {}}" else "{\"TCP\": {}, \"Web\": {}, \"AllowFunnel\": {}}"
            } else {
                AppJson.encodeToString(newConfig)
            }
            val res = Appctr.setServeConfig(jsonPayload)
            updateAllWidgets(context)
            withContext(Dispatchers.Main) {
                isLoading = false
                if (res != "OK") {
                    Toast.makeText(context, context.getString(R.string.serve_error_format, res), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    fun copyText(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, context.getString(R.string.serve_link_copied), Toast.LENGTH_SHORT).show()
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
                    title = { Text(stringResource(R.string.serve_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    },
                    actions = {
                        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.serve_cd_export_cert)) },
                                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                                    enabled = caps.dnsName.isNotEmpty() && caps.certDomains.isNotEmpty(),
                                    onClick = { menuOpen = false; showCertExportDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.serve_cd_clear_all), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                    enabled = rules.isNotEmpty(),
                                    onClick = { menuOpen = false; showClearDialog = true }
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { editor = RuleEditorState(null, newRuleTemplate()) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.serve_cd_add_rule))
                }
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { refresh() },
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { NodeCard(caps, context, onCopy = { copyText(it) }) }

                    when {
                        config == null && isLoading -> item {
                            Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { LoadingIndicator() }
                        }
                        rules.isEmpty() -> item { EmptyRulesCard(context) }
                        else -> {
                            val nodeRules = rules.filter { it.service == null }
                            if (nodeRules.isNotEmpty()) {
                                item { SectionHeading(context.getString(R.string.serve_rules_heading)) }
                                items(nodeRules) { rule ->
                                    RuleCard(
                                        rule = rule,
                                        url = ruleUrl(rule, caps),
                                        description = ruleDescription(context, rule),
                                        health = healthMap[rule.target],
                                        context = context,
                                        onEdit = { editor = RuleEditorState(rule, rule) },
                                        onCopy = { copyText(ruleUrl(rule, caps)) },
                                        onPublish = rule.service?.let { svc -> { publishFor = svc } },
                                        onDelete = { applyChange { it.without(rule) } }
                                    )
                                }
                            }
                            rules.filter { it.service != null }.groupBy { it.service!! }.forEach { (service, list) ->
                                item {
                                    ServiceHeading(
                                        service = service,
                                        addresses = caps.serviceHosts[service],
                                        context = context,
                                        onPublish = { publishFor = service }
                                    )
                                }
                                items(list) { rule ->
                                    RuleCard(
                                        rule = rule,
                                        url = ruleUrl(rule, caps),
                                        description = ruleDescription(context, rule),
                                        health = healthMap[rule.target],
                                        context = context,
                                        onEdit = { editor = RuleEditorState(rule, rule) },
                                        onCopy = { copyText(ruleUrl(rule, caps)) },
                                        onPublish = rule.service?.let { svc -> { publishFor = svc } },
                                        onDelete = { applyChange { it.without(rule) } }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editor?.let { state ->
        RuleEditorSheet(
            state = state,
            caps = caps,
            existing = rules,
            canPublish = adminSettings() != null,
            context = context,
            onDismiss = { editor = null },
            onSave = { rule, publish ->
                applyChange { base -> (state.original?.let { base.without(it) } ?: base).with(rule, caps) }
                editor = null
                if (rule.service != null) {
                    // Asked for: define and approve right away. Not possible: say how.
                    if (publish) publishService(
                        rule.service,
                        rules.filter { it.service == rule.service && it != state.original }.map { it.port } + rule.port
                    )
                    else if (adminSettings() == null) publishFor = rule.service
                }
            }
        )
    }

    publishFor?.let { service ->
        // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
        val hasApi = adminSettings() != null
        AlertDialog(
            onDismissRequest = { if (!publishBusy) publishFor = null },
            icon = { Icon(Icons.Default.Hub, null) },
            title = { Text(context.getString(R.string.serve_svc_publish_title, "svc:$service")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(context.getString(if (hasApi) R.string.serve_svc_publish_text else R.string.serve_svc_publish_no_api))
                    if (publishBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                if (hasApi) {
                    Button(enabled = !publishBusy, onClick = {
                        publishService(service, rules.filter { it.service == service }.map { it.port })
                        publishFor = null
                    }) {
                        Text(context.getString(R.string.serve_svc_publish_action))
                    }
                } else {
                    Button(onClick = {
                        publishFor = null
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://login.tailscale.com/admin/services")))
                        }
                    }) { Text(context.getString(R.string.serve_svc_open_console)) }
                }
            },
            dismissButton = { TextButton(onClick = { publishFor = null }) { Text(context.getString(R.string.action_close)) } }
        )
    }

    if (showClearDialog) {
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strServeClearTitle = stringResource(R.string.serve_clear_title)
        val strServeClearText = stringResource(R.string.serve_clear_text)
        val strServeClearConfirm = stringResource(R.string.serve_clear_confirm)
        val strActionCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(strServeClearTitle) },
            text = { Text(strServeClearText) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        applyChange { ServeConfig(etag = it.etag) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(strServeClearConfirm) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text(strActionCancel) } }
        )
    }

    if (showCertExportDialog) {
        // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
        val domain = caps.dnsName
        fun fetchCert(onPem: (String) -> Unit) {
            scope.launch(Dispatchers.IO) {
                val pemData = try { Appctr.getCertificatePair(domain) } catch (e: Exception) { context.getString(R.string.serve_error_format, e.message) }
                withContext(Dispatchers.Main) {
                    if (pemData.startsWith("Error")) Toast.makeText(context, pemData, Toast.LENGTH_LONG).show()
                    else onPem(pemData)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showCertExportDialog = false },
            title = { Text(context.getString(R.string.serve_export_title)) },
            text = { Text(context.getString(R.string.serve_export_text_format, domain)) },
            confirmButton = {
                Button(onClick = {
                    fetchCert { pem -> pendingCertData = pem; certSaveLauncher.launch("$domain.pem") }
                    showCertExportDialog = false
                }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.serve_export_save_file))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    fetchCert { pem ->
                        clipboard.setText(AnnotatedString(pem))
                        Toast.makeText(context, context.getString(R.string.serve_cert_copied), Toast.LENGTH_SHORT).show()
                    }
                    showCertExportDialog = false
                }) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.serve_export_copy_clipboard))
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

private val SERVE_CARD_SHAPE = RoundedCornerShape(24.dp)
private val RULE_CARD_SHAPE = RoundedCornerShape(20.dp)

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** What the node can do, before any rule: the address rules are reached at, and the three
 *  gates the tailnet policy holds — certificate, Funnel with its ports, services. */
@Composable
private fun NodeCard(caps: ServeCapabilities, context: Context, onCopy: (String) -> Unit) {
    Card(
        shape = SERVE_CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        context.getString(R.string.serve_node_card_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (caps.dnsName.isEmpty()) context.getString(R.string.serve_waiting_dns) else caps.dnsName.withBreakOpportunities(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (caps.dnsName.isNotEmpty()) {
                    IconButton(onClick = { onCopy("https://${caps.dnsName}") }) {
                        Icon(Icons.Default.ContentCopy, context.getString(R.string.action_copy), modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            val unknown = context.getString(R.string.serve_cap_unknown)
            CapabilityRow(
                icon = Icons.Default.Lock,
                label = context.getString(R.string.serve_cap_cert),
                value = when {
                    !caps.loaded -> unknown
                    caps.certDomains.isNotEmpty() -> context.getString(R.string.serve_cap_cert_ok, caps.certDomains.joinToString(", ")).withBreakOpportunities()
                    else -> context.getString(R.string.serve_cap_cert_no)
                },
                ok = caps.loaded && caps.certDomains.isNotEmpty()
            )
            CapabilityRow(
                icon = Icons.Default.Public,
                label = context.getString(R.string.serve_cap_funnel),
                value = when {
                    !caps.loaded -> unknown
                    caps.funnel -> context.getString(R.string.serve_cap_funnel_ok, caps.funnelPorts.joinToString(", "))
                    else -> context.getString(R.string.serve_cap_funnel_no)
                },
                ok = caps.loaded && caps.funnel
            )
            CapabilityRow(
                icon = Icons.Default.Hub,
                label = context.getString(R.string.serve_cap_services),
                value = when {
                    !caps.loaded -> unknown
                    caps.services -> context.getString(R.string.serve_cap_services_ok)
                    else -> context.getString(R.string.serve_cap_services_no)
                },
                ok = caps.loaded && caps.services
            )
        }
    }
}

/**
 * A service's heading with where it stands in the tailnet: published (control
 * lists this node as a host, with the service address) or not yet, with the
 * way to get there.
 */
@Composable
private fun ServiceHeading(service: String, addresses: List<String>?, context: Context, onPublish: () -> Unit) {
    Column {
        SectionHeading(context.getString(R.string.serve_service_heading, service))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (addresses != null) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    context.getString(R.string.serve_svc_status_published, addresses.firstOrNull { ':' !in it } ?: addresses.firstOrNull() ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    context.getString(R.string.serve_svc_status_unpublished),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPublish, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(context.getString(R.string.serve_svc_publish_button))
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(icon: ImageVector, label: String, value: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun EmptyRulesCard(context: Context) {
    Card(
        shape = SERVE_CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Language, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                context.getString(R.string.serve_empty_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                context.getString(R.string.serve_empty_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** A small label: audience, protocol, off. */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun RuleCard(
    rule: ServeRule,
    url: String,
    description: String,
    health: Boolean?,
    context: Context,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onPublish: (() -> Unit)?,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val iconContainer = if (rule.funnel) scheme.tertiaryContainer else scheme.secondaryContainer
    val iconTint = if (rule.funnel) scheme.onTertiaryContainer else scheme.onSecondaryContainer
    val iconShape: Shape = if (rule.funnel) CircleShape else RoundedCornerShape(10.dp)
    Card(
        onClick = onEdit,
        shape = RULE_CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(iconShape).background(iconContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(kindIcon(rule.kind), null, modifier = Modifier.size(18.dp), tint = iconTint)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        url.ifEmpty { context.getString(R.string.serve_waiting_dns) }.withBreakOpportunities(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; onEdit() }
                        )
                        if (url.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.serve_menu_copy_link)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = { menu = false; onCopy() }
                            )
                        }
                        if (onPublish != null) {
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.serve_menu_publish)) },
                                leadingIcon = { Icon(Icons.Default.Hub, null) },
                                onClick = { menu = false; onPublish() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.action_delete), color = scheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = scheme.error) },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (rule.funnel) Tag(context.getString(R.string.serve_tag_public), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                else Tag(context.getString(R.string.serve_tag_tailnet), scheme.secondaryContainer, scheme.onSecondaryContainer)
                val protocol = when {
                    rule.kind == RuleKind.TCP && rule.tls -> "TLS → TCP"
                    rule.kind == RuleKind.TCP -> "TCP"
                    rule.tls || rule.funnel -> "HTTPS"
                    else -> "HTTP"
                }
                Tag(protocol, scheme.surfaceVariant, scheme.onSurfaceVariant)
                if (health != null) {
                    Spacer(Modifier.width(2.dp))
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape)
                            .background(if (health) scheme.primary else scheme.error)
                    )
                    Text(
                        context.getString(if (health) R.string.serve_health_ok else R.string.serve_health_bad),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private val DNS_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * The editor, in the order a person decides: what to expose, on which port, who may reach
 * it. The daemon's terms (transport, handler, TLS termination, PROXY protocol, mount path)
 * live under Advanced. Funnel is a switch that explains itself when it cannot be turned on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    state: RuleEditorState,
    caps: ServeCapabilities,
    existing: List<ServeRule>,
    canPublish: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onSave: (ServeRule, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isNew = state.original == null
    val initial = state.initial

    var kind by remember { mutableStateOf(initial.kind) }
    var target by remember { mutableStateOf(initial.target) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var path by remember { mutableStateOf(initial.path) }
    var funnel by remember { mutableStateOf(initial.funnel) }
    var plainHttp by remember { mutableStateOf(initial.kind != RuleKind.TCP && !initial.tls) }
    var tlsTcp by remember { mutableStateOf(initial.kind == RuleKind.TCP && initial.tls) }
    var proxyProtocol by remember { mutableIntStateOf(initial.proxyProtocol) }
    var insecureBackend by remember { mutableStateOf(initial.insecureBackend) }
    var scopeService by remember { mutableStateOf(initial.service != null) }
    var serviceName by remember { mutableStateOf(initial.service ?: "") }
    /** Define the service and approve this node right after saving, through the Admin API. */
    var publishAfter by remember { mutableStateOf(canPublish) }
    var advanced by remember {
        mutableStateOf(!isNew && (plainHttp || tlsTcp || proxyProtocol > 0 || insecureBackend || initial.path != "/"))
    }

    val kinds = remember(initial.kind) {
        if (initial.kind == RuleKind.FILE) listOf(RuleKind.FILE) else listOf(RuleKind.PROXY, RuleKind.TEXT, RuleKind.REDIRECT, RuleKind.TCP)
    }
    val portInt = port.toIntOrNull()
    val portOk = portInt != null && portInt in 1..65535
    val tlsNow = if (kind == RuleKind.TCP) tlsTcp else !plainHttp
    val targetOk = target.isNotBlank() && (kind != RuleKind.TCP || target.contains(':'))
    val serviceOk = !scopeService || DNS_LABEL.matches(serviceName.trim().lowercase())
    val normalizedPath = path.trim().let { if (it.isEmpty()) "/" else if (it.startsWith("/")) it else "/$it" }
    val scopeName = if (isNew) serviceName.trim().lowercase().takeIf { scopeService } else initial.service
    // Another rule already on this port: a TCP forward owns the whole port, web
    // handlers share a port but not a path. Saving would silently replace it.
    val conflict = portOk && existing.any { r ->
        r != state.original && r.service == scopeName && r.port == portInt &&
            (kind == RuleKind.TCP || r.kind == RuleKind.TCP || r.path == normalizedPath)
    }
    // Why the Funnel switch is off and disabled, or null when it may be turned on.
    val funnelBlock: String? = when {
        !caps.loaded || !caps.funnel -> context.getString(R.string.serve_editor_public_no_cap)
        scopeService -> context.getString(R.string.serve_editor_public_service)
        !tlsNow -> context.getString(R.string.serve_editor_public_tls)
        portOk && portInt !in caps.funnelPorts -> context.getString(R.string.serve_editor_public_ports, caps.funnelPorts.joinToString(", "))
        else -> null
    }
    LaunchedEffect(funnelBlock) { if (funnelBlock != null) funnel = false }

    val targetLabel = when (kind) {
        RuleKind.PROXY -> context.getString(R.string.serve_editor_target_proxy)
        RuleKind.TEXT -> context.getString(R.string.serve_editor_target_text)
        RuleKind.REDIRECT -> context.getString(R.string.serve_editor_target_redirect)
        RuleKind.TCP -> context.getString(R.string.serve_editor_target_tcp)
        RuleKind.FILE -> context.getString(R.string.serve_kind_file)
    }
    val targetPlaceholder = when (kind) {
        RuleKind.PROXY -> "127.0.0.1:8080"
        RuleKind.TEXT -> "Hello"
        RuleKind.REDIRECT -> "https://example.com"
        RuleKind.TCP -> "127.0.0.1:22"
        RuleKind.FILE -> "/sdcard/www"
    }
    val portPresets = remember(kind, plainHttp, caps.funnelPorts) {
        (if (kind != RuleKind.TCP && plainHttp) listOf(80) else emptyList()) + caps.funnelPorts
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                context.getString(if (isNew) R.string.serve_editor_new else R.string.serve_editor_edit),
                style = MaterialTheme.typography.titleLarge
            )

            if (kinds.size > 1) {
                EditorSection(context.getString(R.string.serve_editor_what)) {
                    ScrollableSlidingSegmentedChips(
                        options = kinds.map { kindLabel(context, it) },
                        selectedIndex = kinds.indexOf(kind).coerceAtLeast(0),
                        onOptionSelected = { idx ->
                            val k = kinds[idx]
                            if (k != kind) {
                                kind = k
                                // A sensible port follows the kind; a port the user typed stays.
                                if (k == RuleKind.TCP && (port == "443" || port == "80")) port = "10000"
                                if (k != RuleKind.TCP && port == "10000") port = "443"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 36.dp
                    )
                }
            }

            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text(targetLabel) },
                placeholder = { Text(targetPlaceholder) },
                singleLine = kind != RuleKind.TEXT,
                isError = !targetOk && target.isNotEmpty(),
                supportingText = if (!targetOk) { { Text(context.getString(R.string.serve_editor_target_required)) } } else null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            EditorSection(context.getString(R.string.serve_editor_port)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() }) port = v },
                    singleLine = true,
                    isError = !portOk || conflict,
                    supportingText = when {
                        !portOk -> { { Text(context.getString(R.string.serve_editor_port_invalid)) } }
                        conflict -> { { Text(context.getString(R.string.serve_editor_conflict)) } }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portPresets.forEach { p ->
                        val selected = portInt == p
                        SuggestionChip(
                            onClick = { port = p.toString() },
                            label = { Text(p.toString()) },
                            colors = if (selected) SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) else SuggestionChipDefaults.suggestionChipColors()
                        )
                    }
                }
            }

            EditorSection(context.getString(R.string.serve_editor_who)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (funnel) Icons.Default.Public else Icons.Default.Lan,
                        null,
                        tint = if (funnel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(context.getString(R.string.serve_editor_public), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            funnelBlock ?: context.getString(if (funnel) R.string.serve_editor_public_anyone else R.string.serve_editor_tailnet_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = funnel, onCheckedChange = { funnel = it }, enabled = funnelBlock == null)
                }
            }

            if (!isNew && initial.service != null && canPublish) {
                SwitchRow(
                    label = context.getString(R.string.serve_editor_publish_after),
                    checked = publishAfter,
                    onChange = { publishAfter = it }
                )
            }

            if (isNew) {
                EditorSection(context.getString(R.string.serve_editor_scope)) {
                    SlidingSegmentedChips(
                        options = listOf(context.getString(R.string.serve_editor_scope_node), context.getString(R.string.serve_editor_scope_service)),
                        selectedIndex = if (scopeService) 1 else 0,
                        onOptionSelected = { scopeService = it == 1 },
                        modifier = Modifier.fillMaxWidth(),
                        height = 36.dp
                    )
                    if (scopeService) {
                        if (canPublish) {
                            SwitchRow(
                                label = context.getString(R.string.serve_editor_publish_after),
                                checked = publishAfter,
                                onChange = { publishAfter = it }
                            )
                        }
                        OutlinedTextField(
                            value = serviceName,
                            onValueChange = { serviceName = it },
                            label = { Text(context.getString(R.string.serve_editor_service_name)) },
                            placeholder = { Text("webapp") },
                            singleLine = true,
                            isError = serviceName.isNotEmpty() && !serviceOk,
                            supportingText = {
                                Text(
                                    if (!caps.services && caps.loaded) context.getString(R.string.serve_cap_services_no)
                                    else if (!serviceOk && serviceName.isNotEmpty()) context.getString(R.string.serve_editor_service_required)
                                    else context.getString(R.string.serve_service_note)
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(context.getString(R.string.serve_editor_advanced))
            }
            AnimatedVisibility(visible = advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (kind != RuleKind.TCP) {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text(context.getString(R.string.serve_editor_path)) },
                            placeholder = { Text("/") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        SwitchRow(
                            label = context.getString(R.string.serve_editor_plain_http),
                            checked = plainHttp,
                            enabled = !funnel,
                            onChange = { plainHttp = it }
                        )
                        if (kind == RuleKind.PROXY) {
                            SwitchRow(
                                label = context.getString(R.string.serve_editor_insecure_backend),
                                checked = insecureBackend,
                                onChange = { insecureBackend = it }
                            )
                        }
                    } else {
                        SwitchRow(
                            label = context.getString(R.string.serve_editor_tls_tcp),
                            checked = tlsTcp,
                            enabled = !funnel,
                            onChange = { tlsTcp = it }
                        )
                        EditorSection(context.getString(R.string.serve_editor_proxy_protocol)) {
                            SlidingSegmentedChips(
                                options = listOf(context.getString(R.string.serve_editor_proxy_protocol_none), "v1", "v2"),
                                selectedIndex = proxyProtocol.coerceIn(0, 2),
                                onOptionSelected = { proxyProtocol = it },
                                modifier = Modifier.fillMaxWidth(),
                                height = 36.dp
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_cancel)) }
                Button(
                    enabled = targetOk && portOk && serviceOk && !conflict && (!scopeService || serviceName.isNotBlank()),
                    onClick = {
                        onSave(
                            ServeRule(
                                service = scopeName,
                                port = portInt!!,
                                path = if (kind == RuleKind.TCP) "/" else normalizedPath,
                                kind = kind,
                                tls = tlsNow || funnel,
                                target = target.trim(),
                                funnel = funnel,
                                proxyProtocol = if (kind == RuleKind.TCP) proxyProtocol else 0,
                                insecureBackend = kind == RuleKind.PROXY && insecureBackend
                            ),
                            scopeName != null && canPublish && publishAfter
                        )
                    }
                ) { Text(context.getString(if (isNew) R.string.action_add else R.string.action_save)) }
            }
        }
    }
}
