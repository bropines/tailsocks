package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import androidx.compose.material.icons.automirrored.filled.Notes
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.safety.AdminAuditLog
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeOutcome
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.GateEvidence
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.SafeChangeRunner
import io.github.bropines.tailscaled.admin.safety.SafetyContext
import io.github.bropines.tailscaled.admin.safety.TargetType
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.admin.secure.findFragmentActivity
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

// A FragmentActivity, not a ComponentActivity: publishing a service goes through
// the Admin API behind the same biometric prompt as the console, and
// BiometricPrompt wants one.
class ServeActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_TAB = "tab"
        private const val EXTRA_TAILCAT_IMPORT = "tailcat_import"
        private const val TAB_TAILCAT = 1

        /**
         * This screen on its TailCat side, for the tailcat notification and
         * tailsocks://tailcat links; with [importText] (an address or a connect
         * command) it opens a new connection's editor filled in from it.
         */
        fun tailcatIntent(context: Context, importText: String? = null): Intent =
            Intent(context, ServeActivity::class.java).putExtra(EXTRA_TAB, TAB_TAILCAT)
                .apply { if (importText != null) putExtra(EXTRA_TAILCAT_IMPORT, importText) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startTab = intent.getIntExtra(EXTRA_TAB, 0)
        // Once: a recreated activity must not open the editor again.
        val importText = if (savedInstanceState == null) intent.getStringExtra(EXTRA_TAILCAT_IMPORT) else null
        setContent {
            TailSocksTheme {
                ServeHost(startTab = startTab, onBack = { finish() }, activity = this, tailcatImport = importText)
            }
        }
    }
}

/**
 * A screen as a page of [ServeHost]: the host draws the title and the switch,
 * and shows the page's top-bar actions while it is the current page.
 */
class ServePage(val actions: MutableState<@Composable RowScope.() -> Unit>)

/**
 * Serve & Funnel and TailCat behind one tile: both carry ports somewhere
 * else — this node's to the tailnet and the internet, or this device's to
 * and from tailcat. One title, a switch under it that follows a swipe, and
 * each screen a page of its own.
 */
@Composable
fun ServeHost(startTab: Int, onBack: () -> Unit, activity: FragmentActivity? = null, tailcatImport: String? = null) {
    val pager = rememberPagerState(initialPage = startTab) { 2 }
    val scope = rememberCoroutineScope()
    val pages = remember { List(2) { ServePage(mutableStateOf({})) } }
    // The switch stands over the pages at their own margins: on a large window those are
    // the window's, and the pages fill it (a phone keeps its 16dp, which is the same); a
    // foldable open like a book lays a phone's width on each half, with a phone's margins.
    val window = rememberWindowLayout()
    val switchMargin = if (window.fold is Fold.Vertical) 16.dp else window.margin
    PredictiveBackContainer(onBack = onBack, popsInAppState = false) {
        Scaffold(
            topBar = {
                Column {
                    AppTopBar(
                        title = stringResource(R.string.serve_host_title),
                        onBack = onBack,
                        // Read here, so a page publishing its actions redraws only these.
                        actions = { pages[pager.currentPage].actions.value(this) }
                    )
                    SlidingSegmentedChips(
                        items = listOf(
                            SegmentedChipItem(stringResource(R.string.serve_title), Icons.Default.Public),
                            SegmentedChipItem(stringResource(R.string.tailcat_title), Icons.Default.Pets)
                        ),
                        selectedIndex = pager.currentPage,
                        onOptionSelected = { scope.launch { pager.animateScrollToPage(it) } },
                        positionOffset = pager.currentPage + pager.currentPageOffsetFraction,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = switchMargin, vertical = 4.dp)
                    )
                }
            }
        ) { padding ->
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                modifier = Modifier.padding(padding).fillMaxSize()
            ) { i ->
                if (i == 0) ServeScreen(onBack = onBack, activity = activity, page = pages[0])
                else TailcatScreen(onBack = onBack, page = pages[1], importText = tailcatImport)
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
    val insecureBackend: Boolean,
    /**
     * Held by the app, not by the daemon. The daemon has no "off" for a rule or a
     * service (a configured service is Active whatever AdvertiseServices says), so
     * pausing takes the rule out of the daemon's config and keeps it here until
     * it is resumed.
     */
    val paused: Boolean = false
)

/** What makes two rules the same rule, port included. */
private fun ruleId(r: ServeRule) = "${r.service}|${r.port}|${r.path}|${r.kind}"

@Serializable
private data class PausedRuleDto(
    val service: String? = null, val port: Int = 0, val path: String = "/", val kind: String = "PROXY",
    val tls: Boolean = true, val target: String = "", val funnel: Boolean = false,
    val proxyProtocol: Int = 0, val insecureBackend: Boolean = false
) {
    fun toRule() = ServeRule(
        service, port, path, runCatching { RuleKind.valueOf(kind) }.getOrDefault(RuleKind.PROXY),
        tls, target, funnel, proxyProtocol, insecureBackend, paused = true
    )
    companion object {
        fun of(r: ServeRule) = PausedRuleDto(r.service, r.port, r.path, r.kind.name, r.tls, r.target, r.funnel, r.proxyProtocol, r.insecureBackend)
    }
}

/** Paused rules of the active profile, in its own preferences file. */
private object PausedRules {
    private fun prefs(context: Context) =
        context.getSharedPreferences("serve_paused_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)

    fun load(context: Context): List<ServeRule> = runCatching {
        AppJson.decodeFromString<List<PausedRuleDto>>(prefs(context).getString("rules", "[]") ?: "[]")
    }.getOrDefault(emptyList()).map { it.toRule() }

    fun save(context: Context, rules: List<ServeRule>) {
        prefs(context).edit().putString("rules", AppJson.encodeToString(rules.map { PausedRuleDto.of(it) })).apply()
    }
}

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
    RuleKind.TEXT -> Icons.AutoMirrored.Filled.Notes
    RuleKind.REDIRECT -> Icons.AutoMirrored.Filled.OpenInNew
    RuleKind.TCP -> Icons.Default.Cable
    RuleKind.FILE -> Icons.Default.Folder
}

/**
 * Rules that differ only by port, shown and edited as one: the daemon keeps one
 * entry per port, a person thinks "this service on 443 and 2550".
 */
private data class RuleGroup(val rules: List<ServeRule>) {
    val first: ServeRule get() = rules.first()
    val ports: List<Int> get() = rules.map { it.port }.sorted()
}

private fun groupKey(r: ServeRule) = listOf(r.service, r.kind, r.tls, r.target, r.path, r.funnel, r.proxyProtocol, r.insecureBackend, r.paused)

private fun groupsOf(rules: List<ServeRule>): List<RuleGroup> =
    rules.groupBy { groupKey(it) }.values
        .map { RuleGroup(it.sortedBy { r -> r.port }) }
        .sortedWith(compareBy<RuleGroup> { it.first.service ?: "" }.thenBy { it.ports.first() }.thenBy { it.first.path })

/** A group in the editor: the rules it replaces (empty for a new one) and the values it opens with. */
private data class RuleEditorState(val originals: List<ServeRule>, val initial: ServeRule) {
    val isNew: Boolean get() = originals.isEmpty()
    val ports: List<Int> get() = if (originals.isEmpty()) listOf(initial.port) else originals.map { it.port }.sorted()
}

private fun newRuleTemplate(): ServeRule = ServeRule(
    service = null, port = 443, path = "/", kind = RuleKind.PROXY, tls = true,
    target = "127.0.0.1:8080", funnel = false, proxyProtocol = 0, insecureBackend = false
)

/**
 * What [ServeScreen] reads from the daemon and from its own storage, made up for the
 * preview renderer, which has neither: the status it takes the node's capabilities
 * from, the serve config, the rules paused in the app (PausedRuleDto's JSON) and the
 * health of the rules' targets. Seeded into the first frame; the app never provides it.
 */
class DemoServe(
    val statusJson: String,
    val configJson: String,
    val pausedJson: String = "[]",
    val health: Map<String, Boolean> = emptyMap(),
    /** The node's card opened, its capabilities shown; the app remembers the user's choice. */
    val nodeCardOpen: Boolean = false,
)

val LocalDemoServe = staticCompositionLocalOf<DemoServe?> { null }

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServeScreen(onBack: () -> Unit, activity: FragmentActivity? = null, page: ServePage? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    // The preview renderer has no daemon and no native bridge: there the demo, or its
    // absence, says whether the service counts as running. In the app a load decides.
    val inPreview = LocalInspectionMode.current
    val demo = LocalDemo.current
    // A preview's rules and capabilities, parsed now: nothing a LaunchedEffect loads
    // lands before the renderer takes its picture.
    val demoServe = if (inPreview) LocalDemoServe.current else null
    var config by remember {
        mutableStateOf(demoServe?.let { d -> runCatching { AppJson.decodeFromString<ServeConfig>(d.configJson) }.getOrNull() })
    }
    var caps by remember {
        mutableStateOf(
            demoServe?.let { d -> runCatching { capabilitiesOf(AppJson.decodeFromString<StatusResponse>(d.statusJson)) }.getOrNull() }
                ?: ServeCapabilities()
        )
    }
    var isLoading by remember { mutableStateOf(demoServe == null) }
    var daemonStopped by remember { mutableStateOf(inPreview && demo?.running != true) }
    var healthMap by remember { mutableStateOf(demoServe?.health ?: emptyMap()) }
    var editor by remember { mutableStateOf<RuleEditorState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showCertExportDialog by remember { mutableStateOf(false) }
    var pendingCertData by remember { mutableStateOf("") }
    /** A rule whose link is shown as a QR code, for another device's camera. */
    var qrRule by remember { mutableStateOf<ServeRule?>(null) }
    /** A service whose tailnet-side definition and host approval are being offered. */
    var publishFor by remember { mutableStateOf<String?>(null) }
    var publishBusy by remember { mutableStateOf(false) }
    /** When the last publish succeeded; the netmap is re-read a few seconds after. */
    var publishedAt by remember { mutableStateOf(0L) }
    /** What each publish did, step by step, shown under the service's heading instead of a toast. */
    val publishLogs = remember { mutableStateMapOf<String, List<String>>() }
    var logExpanded by remember { mutableStateOf(setOf<String>()) }
    /** Services whose publish succeeded and whose address the netmap has not shown yet. */
    var awaitingAddress by remember { mutableStateOf(setOf<String>()) }
    val logTime = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    fun logLine(service: String, text: String) {
        publishLogs[service] = (publishLogs[service] ?: emptyList()) + "${logTime.format(Date())}  $text"
    }

    val rules = remember(config) { config?.let { rulesOf(it) } ?: emptyList() }
    var paused by remember {
        mutableStateOf(
            demoServe?.let { d ->
                runCatching { AppJson.decodeFromString<List<PausedRuleDto>>(d.pausedJson) }.getOrDefault(emptyList()).map { it.toRule() }
            } ?: PausedRules.load(context)
        )
    }
    /** Active rules from the daemon plus the paused ones the app holds; what the list shows. */
    val allRules = remember(rules, paused) { rules + paused }
    fun setPaused(list: List<ServeRule>) { paused = list; PausedRules.save(context, list) }

    /**
     * The admin profile that may publish in this tailnet: one for its MagicDNS suffix, with a
     * credential, not read-only, on a phone with a screen lock to unlock the change. Null
     * otherwise, and the screen offers the console's link instead.
     */
    var publishProfile by remember { mutableStateOf<AdminProfile?>(null) }
    LaunchedEffect(caps.dnsName) {
        if (inPreview) return@LaunchedEffect
        // Off the main thread: the first read of the profiles may migrate old credentials into the Keystore.
        publishProfile = withContext(Dispatchers.IO) {
            runCatching {
                AdminProfiles.forTailnet(context, caps.dnsName.trimEnd('.').substringAfter(".", "").ifBlank { null })
                    ?.takeIf { !it.readOnly && AdminProfiles.hasCredential(context, it) && AdminWriteGate.lockState(context) != LockState.NO_SCREEN_LOCK }
            }.getOrNull()
        }
    }
    fun adminSettings(): AdminProfile? = publishProfile

    /**
     * Defines `svc:<service>` in the tailnet (or sets this screen's ports as its definition)
     * and approves this node as its host, through the Admin API: a MEDIUM change. The dialog
     * that asked was its confirm, then the write unlock, which fails closed. Recorded in the
     * console's audit log like every other write.
     */
    fun publishService(service: String, ports: List<Int>) {
        val profile = adminSettings() ?: return
        // The prompt needs the FragmentActivity itself; the one handed in by ServeActivity,
        // not whatever LocalContext resolves to. Without it the tailnet is not touched.
        val host = activity ?: context.findFragmentActivity()
        if (host == null) {
            Toast.makeText(context, context.getString(R.string.serve_svc_publish_failed, "no activity for the credential prompt"), Toast.LENGTH_LONG).show()
            return
        }
        val name = "svc:$service"
        val portList = ports.distinct().sorted().map { "tcp:$it" }
        scope.launch {
            val unlock = AdminWriteGate.unlock(host, context.getString(R.string.admin2_write_unlock_title), context.getString(R.string.serve_svc_biometric_subtitle))
            val grant = when (unlock) {
                is UnlockResult.Granted -> unlock.grant
                UnlockResult.Cancelled -> return@launch
                is UnlockResult.Failed -> {
                    Toast.makeText(context, ConsoleText.unlockFailure(context, unlock.reason), Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            publishBusy = true
            publishLogs[service] = emptyList()
            logExpanded = logExpanded + service
            suspend fun log(text: String) = withContext(Dispatchers.Main) { logLine(service, text) }
            val planned = PlannedChange(
                change = AdminChange(
                    kind = ChangeKind.SERVICE_PUBLISH,
                    changeClass = ChangeClassifier.classify(ChangeKind.SERVICE_PUBLISH),
                    target = ChangeTarget(TargetType.SERVICE, name, name),
                    title = context.getString(R.string.admin2_change_service_publish, name),
                    effect = context.getString(R.string.admin2_change_service_publish_effect),
                ),
                apply = { b ->
                    log(context.getString(R.string.serve_log_reading, name))
                    val existing = b.getService(name)
                    log(
                        if (existing == null) context.getString(R.string.serve_log_not_found)
                        else context.getString(R.string.serve_log_found, existing.ports.joinToString(", "))
                    )
                    // The definition's endpoints are exactly what this node serves for the
                    // service. Merging in the old ones left a port behind when a rule moved
                    // (443 to 2550), and a host that does not serve every defined port is
                    // "needs configuration" in the console: control then hands the service
                    // address to nobody.
                    log(context.getString(R.string.serve_log_defining, portList.joinToString(", ")))
                    b.putService(
                        ApiService(
                            name = name,
                            displayName = existing?.displayName,
                            addrs = existing?.addrs.orEmpty(),
                            comment = existing?.comment,
                            ports = portList,
                            tags = existing?.tags.orEmpty(),
                        )
                    )
                    log(context.getString(R.string.serve_log_defined))
                    if (caps.nodeId.isNotEmpty()) {
                        log(context.getString(R.string.serve_log_approving, caps.nodeId))
                        b.setServiceHostApproved(name, caps.nodeId, true)
                        log(context.getString(R.string.serve_log_approved))
                    } else {
                        log(context.getString(R.string.serve_log_no_node_id))
                    }
                },
            )
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val backend = AdminProfiles.newBackend(context, profile)
                    val runner = SafeChangeRunner(backend, AdminAuditLog.of(context.filesDir), {
                        SafetyContext(profile.id, profile.displayName, readOnlyProfile = profile.readOnly, lockState = AdminWriteGate.lockState(context))
                    })
                    runner.run(planned, GateEvidence(confirmed = true, grant = grant))
                }.getOrElse { ChangeOutcome.Failed(it, emptyList()) }
            }
            publishBusy = false
            when (outcome) {
                is ChangeOutcome.Applied -> {
                    logLine(service, context.getString(R.string.serve_log_waiting))
                    awaitingAddress = awaitingAddress + service
                    publishedAt = System.currentTimeMillis()
                }
                is ChangeOutcome.Failed -> logLine(service, context.getString(R.string.serve_log_error, ConsoleText.error(context, outcome.error)))
                is ChangeOutcome.Refused -> logLine(service, context.getString(R.string.serve_log_error, ConsoleText.refusal(context, outcome.reason)))
            }
        }
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
        // A preview's health comes with its demo; there is nothing to connect to.
        if (inPreview) return@LaunchedEffect
        val targets = rules.filter { it.kind == RuleKind.PROXY || it.kind == RuleKind.TCP }
            .map { it.target }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) { healthMap = emptyMap(); return@LaunchedEffect }
        val checked = withContext(Dispatchers.IO) { targets.associateWith { checkTargetHealth(it) } }
        healthMap = checked
    }

    fun refresh() {
        if (inPreview) return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            // Stopped, the node card waited for a DNS name that could not come and the +
            // opened an editor whose rule had no daemon to go to; now the screen says so.
            if (!ProxyState.isActualRunning(context)) {
                withContext(Dispatchers.Main) {
                    daemonStopped = true
                    isLoading = false
                }
                return@launch
            }
            val statusJson = runCatching { Appctr.getStatusFromAPI() }.getOrDefault("")
            val newCaps = if (statusJson.isNotBlank() && !statusJson.contains("\"Error\""))
                runCatching { capabilitiesOf(AppJson.decodeFromString<StatusResponse>(statusJson)) }.getOrNull()
            else null
            val json = runCatching { Appctr.getServeConfig() }.getOrDefault("")
            val newConfig = if (json.isNotBlank() && !json.contains("\"Error\""))
                runCatching { AppJson.decodeFromString<ServeConfig>(json) }.getOrNull()
            else null
            withContext(Dispatchers.Main) {
                daemonStopped = false
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
            kotlinx.coroutines.delay(5000)
            // Still nothing after 15 s: say so, the console knows why.
            for (svc in awaitingAddress) if (caps.serviceHosts[svc] == null) logLine(svc, context.getString(R.string.serve_log_still_waiting))
        }
    }
    LaunchedEffect(caps.serviceHosts) {
        for (svc in awaitingAddress) {
            val addrs = caps.serviceHosts[svc] ?: continue
            logLine(svc, context.getString(R.string.serve_log_published, addrs.firstOrNull { ':' !in it } ?: addrs.firstOrNull() ?: ""))
            awaitingAddress = awaitingAddress - svc
        }
    }

    // Right after the app starts, the bridge may not have reached the daemon yet
    // (Root Mode attaches a few seconds in); keep asking for a while instead of
    // leaving the card at "Not loaded yet" until the user pulls.
    LaunchedEffect(caps.loaded) {
        var attempts = 0
        while (!caps.loaded && attempts < 10) {
            kotlinx.coroutines.delay(3000)
            // A stopped service is waited for by the Start button, not by asking again.
            if (!isLoading && !daemonStopped) refresh()
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

    /**
     * Pause: out of the daemon, into the app's list. Resume: the reverse, unless
     * an active rule has taken one of the ports meanwhile.
     */
    fun pauseOrResume(group: RuleGroup) {
        if (group.first.paused) {
            val taken = group.rules.firstOrNull { p ->
                rules.any { a -> a.service == p.service && a.port == p.port && (a.kind == RuleKind.TCP || p.kind == RuleKind.TCP || a.path == p.path) }
            }
            if (taken != null) {
                Toast.makeText(context, context.getString(R.string.serve_resume_conflict, taken.port), Toast.LENGTH_LONG).show()
                return
            }
            val ids = group.rules.map { ruleId(it) }.toSet()
            setPaused(paused.filter { ruleId(it) !in ids })
            applyChange { c -> group.rules.fold(c) { acc, r -> acc.with(r.copy(paused = false), caps) } }
        } else {
            setPaused(paused + group.rules.map { it.copy(paused = true) })
            applyChange { c -> group.rules.fold(c) { acc, r -> acc.without(r) } }
        }
    }

    fun deleteGroup(group: RuleGroup) {
        if (group.first.paused) {
            val ids = group.rules.map { ruleId(it) }.toSet()
            setPaused(paused.filter { ruleId(it) !in ids })
        } else {
            applyChange { c -> group.rules.fold(c) { acc, r -> acc.without(r) } }
        }
    }

    fun copyText(text: String) {
        clipboard.copyText(scope, text)
        Toast.makeText(context, context.getString(R.string.serve_link_copied), Toast.LENGTH_SHORT).show()
    }

    // The top bar's actions: in a top bar of its own, or handed to ServeHost,
    // which shows them while this page is the current one.
    val actions: @Composable RowScope.() -> Unit = {
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more)) }
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
    if (page != null) SideEffect { page.actions.value = actions }

    // A rule group's card and a service's heading, the same in the phone's list and in the
    // large window's grid.
    val ruleCard: @Composable (RuleGroup, String?) -> Unit = { group, service ->
        RuleCard(
            group = group,
            url = ruleUrl(group.first, caps),
            description = ruleDescription(context, group.first),
            health = if (group.first.paused) null else healthMap[group.first.target],
            context = context,
            onEdit = { editor = RuleEditorState(group.rules, group.first) },
            onCopy = { copyText(ruleUrl(group.first, caps)) },
            onQr = { qrRule = group.first },
            onPublish = if (service == null || group.first.paused) null else ({ publishFor = service }),
            onPauseResume = { pauseOrResume(group) },
            onDelete = { deleteGroup(group) }
        )
    }
    val serviceHeading: @Composable (String, Modifier, Boolean) -> Unit = { service, modifier, wide ->
        ServiceHeading(
            service = service,
            addresses = caps.serviceHosts[service],
            log = publishLogs[service],
            logExpanded = service in logExpanded,
            onToggleLog = { logExpanded = if (service in logExpanded) logExpanded - service else logExpanded + service },
            context = context,
            onPublish = {
                if (adminSettings() != null) publishService(service, rules.filter { it.service == service }.map { it.port })
                else publishFor = service
            },
            modifier = modifier,
            wide = wide
        )
    }
    // Medium windows and up: the node's card across the top, the rules in as many columns
    // as fit under it. A phone keeps its single list below, exactly as it was.
    val window = rememberWindowLayout()

    val scaffold: @Composable () -> Unit = {
        Scaffold(
            topBar = { if (page == null) AppTopBar(title = stringResource(R.string.serve_title), onBack = onBack, actions = actions) },
            // As a page the host's Scaffold has taken the system bars already.
            contentWindowInsets = if (page != null) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            floatingActionButton = {
                // No rule can be written with the daemon down.
                if (!daemonStopped) {
                    FloatingActionButton(onClick = { editor = RuleEditorState(emptyList(), newRuleTemplate()) }) {
                        Icon(Icons.Default.Add, stringResource(R.string.serve_cd_add_rule))
                    }
                }
            }
        ) { padding ->
            if (window.multiColumn) {
                PullToRefreshBox(
                    isRefreshing = isLoading && !daemonStopped,
                    onRefresh = { refresh() },
                    modifier = Modifier.padding(padding).fillMaxSize()
                ) {
                    if (daemonStopped) LazyColumn(Modifier.fillMaxSize()) {
                        item { DaemonStoppedState(onStarted = { refresh() }, modifier = Modifier.fillParentMaxSize()) }
                    } else ServeRulesGrid(
                        window = window,
                        nodeCard = { NodeCard(caps, context, onCopy = { copyText(it) }, wide = true, demoOpen = demoServe?.nodeCardOpen) },
                        loading = config == null && isLoading,
                        empty = allRules.isEmpty(),
                        groups = remember(allRules) { groupsOf(allRules) },
                        rulesHeading = context.getString(R.string.serve_rules_heading),
                        ruleCard = ruleCard,
                        serviceHeading = serviceHeading,
                        emptyCard = { EmptyRulesCard(context) },
                    )
                }
            } else {
            // Held to a readable width on a tablet; see ReadableWidth.
            ReadableWidth {
            PullToRefreshBox(
                isRefreshing = isLoading && !daemonStopped,
                onRefresh = { refresh() },
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                // In a list, so a pull still re-checks.
                if (daemonStopped) LazyColumn(Modifier.fillMaxSize()) {
                    item { DaemonStoppedState(onStarted = { refresh() }, modifier = Modifier.fillParentMaxSize()) }
                } else LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { NodeCard(caps, context, onCopy = { copyText(it) }, demoOpen = demoServe?.nodeCardOpen) }

                    when {
                        config == null && isLoading -> item {
                            Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { LoadingIndicator() }
                        }
                        allRules.isEmpty() -> item { EmptyRulesCard(context) }
                        else -> {
                            val groups = groupsOf(allRules)
                            val nodeGroups = groups.filter { it.first.service == null }
                            if (nodeGroups.isNotEmpty()) {
                                item { SectionHeading(context.getString(R.string.serve_rules_heading)) }
                                items(nodeGroups) { group -> ruleCard(group, null) }
                            }
                            groups.filter { it.first.service != null }.groupBy { it.first.service!! }.forEach { (service, list) ->
                                item { serviceHeading(service, Modifier, false) }
                                items(list) { group -> ruleCard(group, service) }
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }
    if (page != null) scaffold()
    // Back here only closes the Activity, so the container installs no callback and
    // the platform animates across to the real screen underneath.
    else PredictiveBackContainer(onBack = onBack, popsInAppState = false) { scaffold() }

    editor?.let { state ->
        RuleEditorSheet(
            state = state,
            caps = caps,
            existing = allRules,
            canPublish = adminSettings() != null,
            context = context,
            onDismiss = { editor = null },
            onSave = { newRules, publish ->
                if (state.originals.firstOrNull()?.paused == true) {
                    // A paused rule is edited in place and stays paused; the daemon is not touched.
                    val ids = state.originals.map { ruleId(it) }.toSet()
                    setPaused(paused.filter { ruleId(it) !in ids } + newRules.map { it.copy(paused = true) })
                    editor = null
                    return@RuleEditorSheet
                }
                applyChange { base ->
                    val stripped = state.originals.fold(base) { c, r -> c.without(r) }
                    newRules.fold(stripped) { c, r -> c.with(r, caps) }
                }
                editor = null
                val service = newRules.firstOrNull()?.service
                if (service != null) {
                    // Asked for: define and approve right away. Not possible: say how.
                    if (publish) publishService(
                        service,
                        rules.filter { it.service == service && it !in state.originals }.map { it.port } + newRules.map { it.port }
                    )
                    else if (adminSettings() == null) publishFor = service
                }
            }
        )
    }

    qrRule?.let { rule ->
        // The same address answers on the tailnet and, with Funnel, on the internet;
        // the line under the title says which of the two can open it.
        QrSheet(
            title = context.getString(R.string.qr_serve_title),
            text = ruleUrl(rule, caps),
            help = context.getString(if (rule.funnel) R.string.qr_serve_public_help else R.string.qr_serve_tailnet_help),
            onDismiss = { qrRule = null }
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
                        clipboard.copyText(scope, pem)
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

/**
 * Serve on a medium window and up: the node's card across the top, the rules in as many
 * columns of at least 360dp as fit — two on a tablet held upright, three on its side —
 * each service's heading across the columns above its own rules. The same pieces as the
 * phone's list, laid out for the width.
 *
 * A foldable open like a book gets a column on each half, the hinge between them, and
 * nothing laid across it: the node's card stands in the first column like a rule.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServeRulesGrid(
    window: WindowLayout,
    nodeCard: @Composable () -> Unit,
    loading: Boolean,
    empty: Boolean,
    groups: List<RuleGroup>,
    rulesHeading: String,
    ruleCard: @Composable (RuleGroup, String?) -> Unit,
    serviceHeading: @Composable (String, Modifier, Boolean) -> Unit,
    emptyCard: @Composable () -> Unit,
) {
    val line = StaggeredGridItemSpan.FullLine
    val fold = window.fold as? Fold.Vertical
    // On a book: a phone's margins outside, a lane on each half, the gap around the hinge.
    val margin = if (fold != null) 16.dp else window.margin
    val lane = fold?.let { it.start - margin - 8.dp }
    val gap = fold?.let { it.end - it.start + 16.dp } ?: 12.dp
    // A card that would cross the hinge stays in its lane instead.
    val wide = if (fold != null) StaggeredGridItemSpan.SingleLane else line
    LazyVerticalStaggeredGrid(
        columns = if (fold != null) StaggeredGridCells.Fixed(2) else StaggeredGridCells.Adaptive(360.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = margin,
            end = if (lane != null) window.width - margin - lane * 2 - gap else margin,
            top = 8.dp,
            bottom = 96.dp
        ),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        item(span = wide) { nodeCard() }
        when {
            loading -> item(span = wide) {
                Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { LoadingIndicator() }
            }
            empty -> item(span = wide) { emptyCard() }
            else -> {
                val nodeGroups = groups.filter { it.first.service == null }
                if (nodeGroups.isNotEmpty()) {
                    item(span = line) { SectionHeading(rulesHeading) }
                    gridItems(nodeGroups) { group -> ruleCard(group, null) }
                }
                groups.filter { it.first.service != null }.groupBy { it.first.service!! }.forEach { (service, list) ->
                    // Its status line and its Publish button stay together, not a window apart.
                    item(span = line) {
                        serviceHeading(service, Modifier.wrapContentWidth(Alignment.Start).widthIn(max = lane ?: ReadableContentWidth), true)
                    }
                    gridItems(list) { group -> ruleCard(group, service) }
                }
            }
        }
    }
}

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
 *  gates the tailnet policy holds — certificate, Funnel with its ports, services.
 *  [wide]: the card spans a large window, so the copy button keeps to the name and the
 *  three gates, opened, stand side by side instead of one under another. */
@Composable
private fun NodeCard(
    caps: ServeCapabilities,
    context: Context,
    onCopy: (String) -> Unit,
    wide: Boolean = false,
    demoOpen: Boolean? = null,
) {
    // Collapsed by default: the name is what one comes back for, the
    // capabilities are read once. The choice is remembered.
    var expanded by remember { mutableStateOf(demoOpen ?: GlobalSettings.getBoolean(context, NODE_CARD_EXPANDED_PREF, false)) }
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        onClick = {
            expanded = !expanded
            GlobalSettings.setBoolean(context, NODE_CARD_EXPANDED_PREF, expanded)
        }
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
                val name: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
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
                }
                val copy: @Composable () -> Unit = {
                    if (caps.dnsName.isNotEmpty()) {
                        IconButton(onClick = { onCopy("https://${caps.dnsName}") }) {
                            Icon(Icons.Default.ContentCopy, context.getString(R.string.action_copy), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        name(Modifier.weight(1f, fill = false))
                        copy()
                    }
                } else {
                    name(Modifier.weight(1f))
                    copy()
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = context.getString(if (expanded) R.string.serve_node_collapse else R.string.serve_node_expand),
                    tint = scheme.onSurfaceVariant
                )
            }
            if (!expanded) {
                // The three capabilities as a glance: lit when available.
                Row(
                    modifier = Modifier.padding(top = 10.dp, start = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for ((icon, ok, label) in listOf(
                        Triple(Icons.Default.Lock, caps.loaded && caps.certDomains.isNotEmpty(), R.string.serve_cap_cert),
                        Triple(Icons.Default.Public, caps.loaded && caps.funnel, R.string.serve_cap_funnel),
                        Triple(Icons.Default.Hub, caps.loaded && caps.services, R.string.serve_cap_services)
                    )) {
                        Icon(
                            icon, contentDescription = context.getString(label),
                            modifier = Modifier.size(16.dp),
                            tint = if (ok) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
            Column {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            val unknown = context.getString(R.string.serve_cap_unknown)
            val cert: @Composable () -> Unit = {
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
            }
            val funnel: @Composable () -> Unit = {
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
            }
            val services: @Composable () -> Unit = {
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
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (gate in listOf(cert, funnel, services)) Box(Modifier.weight(1f)) { gate() }
                }
            } else {
                cert()
                funnel()
                services()
            }
            }
            }
        }
    }
}

private const val NODE_CARD_EXPANDED_PREF = "serve_node_card_expanded"

/**
 * A service's heading with where it stands in the tailnet: published (control
 * lists this node as a host, with the service address) or not yet, with the
 * way to get there.
 */
@Composable
private fun ServiceHeading(
    service: String,
    addresses: List<String>?,
    log: List<String>?,
    logExpanded: Boolean,
    onToggleLog: () -> Unit,
    context: Context,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false
) {
    Column(modifier) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Wide, the button follows the line it acts on, not a window's width away.
                    modifier = Modifier.weight(1f, fill = !wide)
                )
                // Re-sync the definition's endpoints with this node's rules after a change.
                TextButton(onClick = onPublish, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(context.getString(R.string.serve_svc_update_button))
                }
            } else {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    context.getString(R.string.serve_svc_status_unpublished),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = !wide)
                )
                TextButton(onClick = onPublish, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(context.getString(R.string.serve_svc_publish_button))
                }
            }
        }
        if (!log.isNullOrEmpty()) {
            // What the last publish did, one line per step; a tap folds it to its last line.
            // Surface(onClick), so the ripple keeps to the corners.
            Surface(
                onClick = onToggleLog,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (logExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (logExpanded) context.getString(R.string.serve_svc_log_title)
                            else context.getString(R.string.serve_svc_log_title) + " · " + log.last().substringAfter("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (logExpanded) {
                        Spacer(Modifier.height(4.dp))
                        log.forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (line.contains(context.getString(R.string.serve_log_error, "").trimEnd()))
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
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
        shape = MaterialTheme.shapes.large,
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
    Surface(shape = MaterialTheme.shapes.small, color = container) {
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
    group: RuleGroup,
    url: String,
    description: String,
    health: Boolean?,
    context: Context,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onPublish: (() -> Unit)?,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit
) {
    val rule = group.first
    val scheme = MaterialTheme.colorScheme
    val iconContainer = if (rule.funnel) scheme.tertiaryContainer else scheme.secondaryContainer
    val iconTint = if (rule.funnel) scheme.onTertiaryContainer else scheme.onSecondaryContainer
    val iconShape: Shape = if (rule.funnel) CircleShape else MaterialTheme.shapes.medium
    Card(
        onClick = onEdit,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (rule.paused) scheme.surfaceContainerLow else scheme.surfaceContainerHigh)
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
                        color = if (rule.paused) scheme.onSurfaceVariant else scheme.onSurface
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
                // The switch is the rule's on/off: a paused rule leaves the daemon but
                // stays in the list.
                Switch(
                    checked = !rule.paused,
                    onCheckedChange = { onPauseResume() },
                    modifier = Modifier.padding(end = 6.dp).semantics {
                        contentDescription = context.getString(if (rule.paused) R.string.serve_menu_resume else R.string.serve_menu_pause)
                    }
                )
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
                if (group.ports.size > 1) Tag(group.ports.joinToString(" · "), scheme.surfaceVariant, scheme.onSurfaceVariant)
                if (rule.paused) Tag(context.getString(R.string.serve_tag_paused), scheme.surfaceVariant, scheme.onSurfaceVariant)
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
            // Actions in the open, not behind an overflow menu: delete on the far
            // left, away from the everyday ones on the right.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardAction(Icons.Default.Delete, context.getString(R.string.action_delete), tint = scheme.error, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                if (onPublish != null) CardAction(Icons.Default.Hub, context.getString(R.string.serve_menu_publish), onClick = onPublish)
                if (url.isNotEmpty()) CardAction(Icons.Default.ContentCopy, context.getString(R.string.serve_menu_copy_link), onClick = onCopy)
                // A web link, for a phone's camera to open; a TCP address or a
                // paused rule has nothing there to open.
                if (url.isNotEmpty() && rule.kind != RuleKind.TCP && !rule.paused) {
                    CardAction(Icons.Default.QrCode2, context.getString(R.string.qr_show), onClick = onQr)
                }
                CardAction(Icons.Default.Edit, context.getString(R.string.action_edit), onClick = onEdit)
            }
        }
    }
}

/** A compact icon action for a card's bottom row; the label is its accessibility name. */
@Composable
private fun CardAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
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
            .clip(MaterialTheme.shapes.medium)
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
    onSave: (List<ServeRule>, Boolean) -> Unit
) {
    val sheetState = rememberFullSheetState()
    val isNew = state.isNew
    val initial = state.initial

    var kind by remember { mutableStateOf(initial.kind) }
    var target by remember { mutableStateOf(initial.target) }
    // One or more ports, "443, 2550": the daemon gets one entry per port.
    var portsText by remember { mutableStateOf(state.ports.joinToString(", ")) }
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
    val ports = remember(portsText) { portsText.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: -1 }.distinct() }
    val portOk = ports.isNotEmpty() && ports.all { it in 1..65535 }
    val tlsNow = if (kind == RuleKind.TCP) tlsTcp else !plainHttp
    val targetOk = target.isNotBlank() && (kind != RuleKind.TCP || target.contains(':'))
    val serviceOk = !scopeService || DNS_LABEL.matches(serviceName.trim().lowercase())
    val normalizedPath = path.trim().let { if (it.isEmpty()) "/" else if (it.startsWith("/")) it else "/$it" }
    val scopeName = if (isNew) serviceName.trim().lowercase().takeIf { scopeService } else initial.service
    // Another rule already on one of these ports: a TCP forward owns the whole
    // port, web handlers share a port but not a path. Saving would replace it.
    val conflict = portOk && existing.any { r ->
        r !in state.originals && r.service == scopeName && r.port in ports &&
            (kind == RuleKind.TCP || r.kind == RuleKind.TCP || r.path == normalizedPath)
    }
    // Why the Funnel switch is off and disabled, or null when it may be turned on.
    val funnelBlock: String? = when {
        !caps.loaded || !caps.funnel -> context.getString(R.string.serve_editor_public_no_cap)
        scopeService -> context.getString(R.string.serve_editor_public_service)
        !tlsNow -> context.getString(R.string.serve_editor_public_tls)
        portOk && !caps.funnelPorts.containsAll(ports) -> context.getString(R.string.serve_editor_public_ports, caps.funnelPorts.joinToString(", "))
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
                                // A sensible port follows the kind; ports the user typed stay.
                                if (k == RuleKind.TCP && (portsText.trim() == "443" || portsText.trim() == "80")) portsText = "10000"
                                if (k != RuleKind.TCP && portsText.trim() == "10000") portsText = "443"
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
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            EditorSection(context.getString(R.string.serve_editor_port)) {
                OutlinedTextField(
                    value = portsText,
                    onValueChange = { v -> if (v.all { it.isDigit() || it == ',' || it == ' ' || it == ';' }) portsText = v },
                    placeholder = { Text("443, 2550") },
                    singleLine = true,
                    isError = !portOk || conflict,
                    supportingText = when {
                        !portOk -> { { Text(context.getString(R.string.serve_editor_port_invalid)) } }
                        conflict -> { { Text(context.getString(R.string.serve_editor_conflict)) } }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portPresets.forEach { p ->
                        val selected = p in ports
                        SuggestionChip(
                            // Toggles the port in the list; the last port cannot be removed.
                            onClick = {
                                val next = if (selected) ports.filter { it != p } else ports + p
                                if (next.isNotEmpty()) portsText = next.filter { it > 0 }.joinToString(", ")
                            },
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
                            shape = MaterialTheme.shapes.medium,
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
                            shape = MaterialTheme.shapes.medium,
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
                        val template = ServeRule(
                            service = scopeName,
                            port = 0,
                            path = if (kind == RuleKind.TCP) "/" else normalizedPath,
                            kind = kind,
                            tls = tlsNow || funnel,
                            target = target.trim(),
                            funnel = funnel,
                            proxyProtocol = if (kind == RuleKind.TCP) proxyProtocol else 0,
                            insecureBackend = kind == RuleKind.PROXY && insecureBackend
                        )
                        onSave(ports.sorted().map { template.copy(port = it) }, scopeName != null && canPublish && publishAfter)
                    }
                ) { Text(context.getString(if (isNew) R.string.action_add else R.string.action_save)) }
            }
        }
    }
}
