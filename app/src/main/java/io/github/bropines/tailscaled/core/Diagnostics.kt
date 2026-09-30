package io.github.bropines.tailscaled.core

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import appctr.Appctr
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.models.HealthWarning
import io.github.bropines.tailscaled.models.parseHealthWarnings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything worth knowing about this install when something goes wrong, in one block of
 * plain text: the versions, the device, the permissions that decide whether the service
 * survives, what the tunnel is made of, and how much the app has been used.
 *
 * It answers the questions every issue starts with — which build, which Android, root or
 * not, which engine, was the battery optimisation ever turned off — so that neither side
 * has to ask them. Nothing here identifies a tailnet: no node names, no addresses, no
 * keys, no account names. The auth key and the login server are reported as present or
 * absent and never by value.
 */
object Diagnostics {

    fun report(context: Context): String = buildString {
        appendLine("--- TAILSOCKS DIAGNOSTICS ---")
        appendLine(versions(context))
        appendLine(device())
        appendLine(permissions(context))
        appendLine(network(context))
        appendLine(tunnel(context))
        // What the service is doing now, next to what it is configured to do. The log
        // lines stay out: they name nodes and addresses, which this report promises not to.
        appendLine(liveText(live(context), logLines = false))
        appendLine(profiles(context))
        appendLine(usage(context))
        append("-----------------------------")
    }

    private fun versions(context: Context): String {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull() ?: "sideloaded"
        val core = runCatching { Appctr.getCoreVersion() }.getOrDefault("unknown")
        // longVersionCode arrived in API 28; below it the report would have
        // thrown instead of being written, on the very devices whose reports
        // are worth the most.
        val code = info?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L
        return """
            App: ${info?.versionName ?: "unknown"} (${BuildConfig.GIT_HASH}, code $code, ${BuildConfig.BUILD_TYPE})
            Core: $core
            Installed by: $installer
        """.trimIndent()
    }

    private fun device(): String = """
        Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.VERSION.SECURITY_PATCH}
        ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
    """.trimIndent()

    /**
     * The three the service's life depends on. A killed service with battery optimisation
     * still on is not a bug report, it is the setting — and this is where that shows.
     */
    private fun permissions(context: Context): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        val exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)?.canScheduleExactAlarms() == true
        } else true
        val notifications = runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        }.getOrDefault(true)
        return """
            Battery unrestricted: $unrestricted
            Exact alarms: $exactAlarms
            Notifications: $notifications
        """.trimIndent()
    }

    private fun transportOf(caps: NetworkCapabilities?): String = when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }

    private fun network(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val transport = transportOf(caps)
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        // Another app holding Android's single VPN slot is behind a whole class of reports.
        val foreignVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return """
            Transport: $transport (metered: $metered)
            VPN transport on the active network: $foreignVpn
        """.trimIndent()
    }

    private fun tunnel(context: Context): String {
        val root = GlobalSettings.isRootModeEnabled(context)
        val tun = GlobalSettings.isTunModeEnabled(context)
        val engine = GlobalSettings.getTunEngine(context)
        return """
            Service running: ${runCatching { Appctr.isRunning() }.getOrDefault(false)}
            Mode: ${if (root) "root" else if (tun) "tun" else "proxy"}
            TUN: ${if (tun) "on (engine: $engine, ipv6: ${GlobalSettings.isTunIpv6Enabled(context)})" else "off"}
            Root: ${if (root) "on (kernel tun: ${GlobalSettings.isRootTunEnabled(context)}, dns redirect: ${GlobalSettings.isRootDnsRedirectEnabled(context)}${if (GlobalSettings.isRootDnsV6Unsupported(context)) " [IPv4 only: no IPv6 nat table]" else ""}, yielded: ${GlobalSettings.isRootRoutingYielded(context)}, shared: ${GlobalSettings.isRootRoutingShared(context)})" else "off"}
            SOCKS5: ${GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")}, HTTP: ${GlobalSettings.getString(context, "httpproxy", "")}
            DNS proxy: ${GlobalSettings.getString(context, "dns_proxy", "")}
            LAN access: ${GlobalSettings.isLanAccessEnabled(context)}
            Control proxy: ${GlobalSettings.isCPProxyEnabled(context)} (ByeDPI: ${GlobalSettings.isCPByeDpiEnabled(context)})
            Accept routes: ${GlobalSettings.getBoolean(context, "accept_routes", false)}, accept DNS: ${GlobalSettings.getBoolean(context, "accept_dns", true)}
            Auto-reconnect: ${GlobalSettings.isAutoReconnectEnabled(context)}, watchdog: ${GlobalSettings.isServiceWatchdogEnabled(context)}, autostart: ${GlobalSettings.isAutoStartEnabled(context)}
        """.trimIndent()
    }

    private fun profiles(context: Context): String {
        val accounts = AccountManager.getAccounts(context)
        val active = AccountManager.getActiveAccount(context)
        val facts = AccountManager.facts(context, active.id)
        val prefs = context.getSharedPreferences("appctr_${active.id}", Context.MODE_PRIVATE)
        return """
            Accounts: ${accounts.size}
            Active profile: registered ${facts.registered}, auth key ${if (facts.hasAuthKey) "present" else "absent"}, honest OS ${facts.honestOs}, custom login server ${facts.loginServer != "tailscale.com"}
            Exit node set: ${prefs.getString("exit_node_id", "")?.isNotEmpty() == true}
        """.trimIndent()
    }

    /** How much the app has been used, as counted for the status card's own amusement. */
    private fun usage(context: Context): String = """
        Holds: ${StatusAsides.count(context, StatusAsides.HOLDS)}, toggles: ${StatusAsides.count(context, StatusAsides.TOGGLES)}, ping-alls: ${StatusAsides.count(context, StatusAsides.PINGS)}, account switches: ${StatusAsides.count(context, StatusAsides.SWITCHES)}
    """.trimIndent()

    // ---- Live state -------------------------------------------------------------------
    //
    // The rest of this report describes the install. This part is the service as it is
    // this second, next to what the user asked of it: a report that says "it stopped
    // working" is answered here or nowhere. The Diagnostics card in Settings redraws the
    // same snapshot every two seconds, so what a user reads out and what they paste agree.

    /** The daemon's last engine status, as GetEngineStatusJSON gives it. Go omits zeros, hence the defaults. */
    @Serializable
    data class Engine(
        @SerialName("RBytes") val rxBytes: Long = 0,
        @SerialName("WBytes") val txBytes: Long = 0,
        @SerialName("NumLive") val livePeers: Int = 0,
        @SerialName("LiveDERPs") val liveRelays: Int = 0,
    )

    /** Android's active network, the one the daemon's traffic leaves by. */
    data class Net(val iface: String, val transport: String, val validated: Boolean)

    /** One look at the SOCKS5 port: whether anything accepted a connection, and when. */
    data class Probe(val listening: Boolean, val atMs: Long)

    /** One ERROR line of the log buffer. */
    data class ErrorLine(val time: String, val category: String, val message: String)

    data class Live(
        /** When it was read, Unix ms. Every age is measured from here, not from the clock at drawing time. */
        val atMs: Long,
        val rootMode: Boolean,
        /** A daemon answers: the bridge holds one, or in Root Mode its socket accepts. */
        val alive: Boolean,
        /** The bridge holds the daemon. In Root Mode a daemon can be alive without it: not attached yet, or let go. */
        val attached: Boolean,
        /** When the bridge started the daemon (Root Mode: attached to it), Unix ms; 0 when it holds none. */
        val sinceMs: Long,
        /** What the user last asked for with the switch. */
        val wanted: Boolean,
        val backend: String,
        val health: List<HealthWarning>,
        /** Null until the daemon has sent an engine status. */
        val engine: Engine?,
        /** When the relays were last reconnected, by hand or by the recovery; 0 never. */
        val relayKickMs: Long,
        /** Null when Android has no active network. */
        val network: Net?,
        /** The SOCKS5 listener's bind address; empty when it is switched off. */
        val socks5: String,
        /** Null when the listener is off. */
        val socksProbe: Probe?,
        val http: String,
        /** TUN mode as the user set it; Root Mode routes by itself and never uses it. */
        val tunMode: Boolean,
        val tunEngine: String,
        /** TunVpnService holds a VPN interface. */
        val vpnUp: Boolean,
        /** Root Mode's own kernel TUN. */
        val rootTun: Boolean,
        /** The newest ERROR lines, newest first. */
        val errors: List<ErrorLine>,
    ) {
        /** Up without the user, or down while the user wants it up. */
        val intentMismatch get() = wanted != alive
        /** A tunnel that is up with no relay: peers are reachable only directly, if at all. */
        val relaysDown get() = alive && backend == "Running" && engine != null && engine.liveRelays == 0
        val networkBad get() = network == null || !network.validated
        /** The daemon runs, and its SOCKS5 port does not answer. */
        val socksDown get() = alive && socksProbe?.listening == false
        /** No daemon, and still something holds its port: the next start cannot bind it. */
        val socksTaken get() = !alive && socksProbe?.listening == true
        val vpnDown get() = !rootMode && tunMode && wanted && !vpnUp
    }

    /** The newest ERROR lines shown. */
    private const val ERROR_LINES = 5

    /**
     * What the daemon logs when a client connects to its SOCKS5 port and leaves before
     * the greeting, which is exactly what [probeSocks] does. It carries "failed", so the
     * log marks it ERROR; shown here it would be the card reporting on itself. Older
     * cores said EOF; 1.102 says the greeting's header could not be read.
     */
    private val PROBE_ECHOES = listOf(
        "client connection failed: EOF",
        "client connection failed: could not read packet header",
    )

    /**
     * Reads the live state. Blocking — a socket check, and the backend state can take a
     * LocalAPI round trip — so never on the main thread.
     *
     * [previous] lets a caller that asks every few seconds skip the costly parts:
     * without [readLogs] the error lines are carried over, since the whole log buffer
     * (up to 10 000 entries) comes over the bridge as one JSON document.
     *
     * The SOCKS5 port is not checked on every call. tailscaled logs every client that
     * hangs up before the SOCKS greeting as a failed connection — an ERROR line in the
     * log, every two seconds, for as long as the card is open. So while the daemon is up
     * it is checked when something changed (first look, the daemon came or went, the
     * backend or the address changed) or when [probe] asks; while it is down there is no
     * daemon to annoy, and a listener that answers then belongs to another app.
     */
    fun live(context: Context, previous: Live? = null, readLogs: Boolean = true, probe: Boolean = false): Live {
        val now = System.currentTimeMillis()
        val attached = runCatching { Appctr.isRunning() }.getOrDefault(false)
        val alive = attached || runCatching { ProxyState.isActualRunning(context) }.getOrDefault(false)
        val backend = runCatching { Appctr.getBackendState() }.getOrDefault("Error")
        // Seconds; Go's zero time, which the bridge holds while it has no daemon, is far negative.
        val startedSec = runCatching { Appctr.getDaemonStartTime() }.getOrDefault(0L)
        val engineJson = runCatching { Appctr.getEngineStatusJSON() }.getOrDefault("{}")
        val socks5 = GlobalSettings.getSocks5BindAddr(context)
        val socksProbe = when {
            socks5.isBlank() -> null
            probe || previous == null || previous.socksProbe == null || !alive ||
                previous.alive != alive || previous.backend != backend || previous.socks5 != socks5 ->
                probeSocks(socks5, alive, now)
            else -> previous.socksProbe
        }
        return Live(
            atMs = now,
            rootMode = GlobalSettings.isRootModeEnabled(context),
            alive = alive,
            attached = attached,
            sinceMs = if (attached && startedSec > 0) startedSec * 1000 else 0L,
            wanted = ProxyState.isUserLetRunning(context),
            backend = backend,
            health = parseHealthWarnings(runCatching { Appctr.getHealthWarningsJSON() }.getOrNull()),
            engine = if (engineJson.isBlank() || engineJson.trim() == "{}") null
                else runCatching { AppJson.decodeFromString<Engine>(engineJson) }.getOrNull(),
            relayKickMs = runCatching { Appctr.lastRelayKickMs() }.getOrDefault(0L),
            network = activeNetwork(context),
            socks5 = socks5,
            socksProbe = socksProbe,
            http = GlobalSettings.getHttpProxyBindAddr(context),
            tunMode = GlobalSettings.isTunModeEnabled(context),
            tunEngine = GlobalSettings.getTunEngine(context),
            vpnUp = TunVpnService.isRunning,
            rootTun = GlobalSettings.isRootTunEnabled(context),
            errors = if (readLogs || previous == null) recentErrors() else previous.errors,
        )
    }

    private fun activeNetwork(context: Context): Net? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return@runCatching null
        val caps = cm.getNetworkCapabilities(network)
        Net(
            iface = cm.getLinkProperties(network)?.interfaceName ?: "?",
            transport = transportOf(caps),
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }.getOrNull()

    /**
     * A TCP connect to the listener, dialed where the app itself would dial it: a
     * wildcard bind is reached over loopback. 200 ms is plenty for loopback and short
     * enough that a refresh never waits on it.
     */
    private fun probeSocks(bind: String, daemonUp: Boolean, now: Long): Probe {
        val port = NetAddr.port(bind) ?: return Probe(false, now)
        // Said before the daemon's own line about it, so whoever reads the log next
        // knows that failed connection was this check and not a client in trouble.
        if (daemonUp) runCatching { Appctr.logAndroid("INFO", "CORE", "Diagnostics: checking the SOCKS5 port $port") }
        val listening = runCatching {
            Socket().use { it.connect(InetSocketAddress(NetAddr.dialableHost(bind), port), 200) }
            true
        }.getOrDefault(false)
        return Probe(listening, now)
    }

    /** A log buffer entry, as GetLogsJSON writes it. */
    @Serializable
    private class LogRecord(
        @SerialName("unix") val unix: Long = 0L,
        @SerialName("timestamp") val timestamp: String = "",
        @SerialName("level") val level: String = "",
        @SerialName("category") val category: String = "",
        @SerialName("message") val message: String = "",
    )

    /**
     * An entry's time of day in the device's zone, as the card's own "Live at" is. The
     * entry's own stamp is formatted by Go, which is UTC until it has been told the zone.
     */
    private fun localTime(r: LogRecord): String =
        if (r.unix > 0) SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(r.unix)) else r.timestamp

    /**
     * The newest ERROR lines of the Go buffer: by level, as the daemon's lines are marked,
     * or by category, as the app's own are — the Logs screen's ERROR chip takes both.
     * In Root Mode the daemon writes its own file instead, which this does not read.
     */
    private fun recentErrors(): List<ErrorLine> {
        val json = runCatching { Appctr.getLogsJSON() }.getOrDefault("[]")
        val all = runCatching { AppJson.decodeFromString<List<LogRecord>>(json) }.getOrDefault(emptyList())
        return all.asReversed().asSequence()
            .filter { it.level == "ERROR" || it.category == "ERROR" }
            .filterNot { line -> PROBE_ECHOES.any { it in line.message } }
            .take(ERROR_LINES)
            .map { ErrorLine(localTime(it), it.category, it.message.lineSequence().first()) }
            .toList()
    }

    /**
     * The live state as report lines. [logLines] adds the error lines themselves; they
     * name nodes and addresses, so the issue report leaves them out and only a Copy the
     * user asked for carries them. A trailing [!] marks what the card draws in red.
     */
    fun liveText(s: Live, logLines: Boolean): String = buildString {
        fun bad(flag: Boolean) = if (flag) " [!]" else ""
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(s.atMs))
        appendLine("Live at $at:")
        val process = when {
            !s.alive -> "not running"
            s.rootMode && !s.attached -> "alive (root), bridge not attached"
            s.sinceMs > 0 -> "alive, ${if (s.rootMode) "attached" else "up"} ${span(s.atMs - s.sinceMs)}"
            else -> "alive"
        }
        appendLine("Process: $process${bad(s.wanted && !s.alive)}")
        appendLine("Want: ${onOff(s.wanted)}, is: ${onOff(s.alive)}${bad(s.intentMismatch)}")
        appendLine("Backend: ${s.backend}")
        appendLine("Health: " + if (s.health.isEmpty()) "none" else s.health.joinToString { w ->
            w.code + if (w.brokenSinceMs > 0) " ${span(s.atMs - w.brokenSinceMs)}" else ""
        })
        val kick = if (s.relayKickMs > 0) "reconnected ${span(s.atMs - s.relayKickMs)} ago" else "never reconnected"
        appendLine("Relays: " + (s.engine?.let { e ->
            "live ${e.liveRelays}, peers ${e.livePeers}, rx ${bytes(e.rxBytes)}, tx ${bytes(e.txBytes)}, $kick"
        } ?: "no engine status, $kick") + bad(s.relaysDown))
        appendLine("Network: " + (s.network?.let { "${it.iface}, ${it.transport}, ${if (it.validated) "validated" else "not validated"}" } ?: "none") + bad(s.networkBad))
        val socks = when {
            s.socks5.isBlank() -> "off"
            s.socksProbe == null -> s.socks5
            else -> "${s.socks5} ${if (s.socksProbe.listening) "listening" else "not listening"}" +
                (if (s.socksTaken) " (no daemon: another app)" else "") +
                " (checked ${span(s.atMs - s.socksProbe.atMs)} ago)"
        }
        appendLine("SOCKS5: $socks${bad(s.socksDown || s.socksTaken)}")
        appendLine("HTTP: ${s.http.ifBlank { "off" }}")
        val tun = when {
            s.rootMode -> if (s.rootTun) "root, kernel tun" else "root, no tun"
            s.tunMode -> "${s.tunEngine}, vpn ${if (s.vpnUp) "up" else "down"}"
            else -> "off"
        }
        appendLine("TUN: $tun${bad(s.vpnDown)}")
        if (!logLines) {
            append("Recent errors: ${s.errors.size}" + (s.errors.firstOrNull()?.let { " (newest at ${it.time})" } ?: ""))
        } else if (s.errors.isEmpty()) {
            append("Recent errors: none")
        } else {
            append("Recent errors, newest first:")
            s.errors.forEach { append("\n  ${it.time} [${it.category}] ${it.message}") }
        }
    }

    /** What the card's Copy puts on the clipboard: which build, then everything the card shows. */
    fun liveClip(context: Context, s: Live): String =
        "--- TAILSOCKS LIVE STATE ---\n" + versions(context) + "\n" + liveText(s, logLines = true)

    private fun onOff(on: Boolean) = if (on) "up" else "down"

    /** A duration in its two largest units, the way a log is skimmed: 12s, 5m, 2h14m, 3d4h. */
    private fun span(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h${(s % 3600) / 60}m"
            else -> "${s / 86_400}d${(s % 86_400) / 3600}h"
        }
    }

    private fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
    }
}
