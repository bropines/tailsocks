package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.os.Build
import appctr.Appctr
import io.github.bropines.tailscaled.models.ServeConfig
import io.github.bropines.tailscaled.models.TCPPortHandler
import io.github.bropines.tailscaled.models.WebServerConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * The facts of core/Exposure.kt as this device has them, and the switches that turn a row off.
 *
 * Read where each screen of the app reads them — the same preferences, the same bridge calls —
 * so the list cannot tell a different story than the page a row links to. What only the running
 * daemon knows (its prefs, Serve, the Taildrive policy) is asked of it while it runs; without it
 * the stored settings stand in, which is what it starts from.
 */
object ExposureRead {

    /** A Taildrive share as ui/TaildriveActivity.kt stores it; only the name and the path are read. */
    @Serializable
    private data class Share(val name: String = "", val path: String = "")

    /** The two daemon prefs [ClientPrefs] does not read. */
    @Serializable
    private data class Advertised(
        @kotlinx.serialization.SerialName("AdvertiseRoutes") val routes: List<String>? = null,
    )

    /**
     * Blocking: LocalAPI round trips, and with [probeRoot] in Root Mode a root shell for the boot
     * script and the CLI wrapper — never on the main thread. A report leaves root alone: a root
     * manager may announce every shell it grants.
     */
    fun facts(context: Context, probeRoot: Boolean): ExposureFacts {
        val ctx = context.applicationContext
        val account = AccountManager.getActiveAccount(ctx)
        val profile = ctx.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
        val running = runCatching { ProxyState.isActualRunning(ctx) }.getOrDefault(false)
        // The bridge answers for a daemon it holds; in Root Mode one can be alive without it.
        val attached = runCatching { Appctr.isRunning() }.getOrDefault(false)

        val prefsJson = if (attached) runCatching { Appctr.getPrefsJSON() }.getOrNull() else null
        val live = prefsJson?.let { ClientPrefs.live(it) }
        val stored = ClientPrefs.stored(profile.all)
        val statusJson = if (attached) runCatching { Appctr.getStatusJSON(false) }.getOrNull() else null

        // What this device offers others to route through: the daemon's list when it runs.
        val liveRoutes = prefsJson?.let { json -> runCatching { AppJson.decodeFromString<Advertised>(json).routes.orEmpty() }.getOrNull() }
        val storedRoutes = (profile.getString("advertise_routes", "") ?: "").split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val storedExit = profile.getBoolean("advertise_exit_node", false)
        val appConnector = live?.appConnector ?: stored.appConnector ?: false
        val exitNode = liveRoutes?.any { it == "0.0.0.0/0" || it == "::/0" } ?: storedExit
        val routes = (liveRoutes ?: storedRoutes).filter { it != "0.0.0.0/0" && it != "::/0" }.filter { r ->
            // The host routes an app connector learns are its own, not routes anyone typed (ClientPrefs).
            !(appConnector && (r.endsWith("/32") || r.endsWith("/128")) && r !in storedRoutes)
        }
        val relay = (live?.relay ?: stored.relay)?.takeIf { it.enabled }
            ?.takeUnless { statusJson != null && ClientPrefs.relayDisabledByControl(statusJson) }

        val serve = if (attached) runCatching { servedPorts(Appctr.getServeConfig()) }.getOrNull() else null

        // Taildrive, as its page reads it: the switch, the list, the storage grant, the policy.
        val sharesJson = profile.getString("taildrive_shares", "[]") ?: "[]"
        val shares = runCatching { AppJson.decodeFromString<List<Share>>(sharesJson) }.getOrDefault(emptyList())
        val drive = DriveProxy.read(profile)
        val driveBind = TailnetReach.bind(ctx, TailnetListener.TAILDRIVE_PROXY)

        val remoteOn = RemoteManage.isEnabled(ctx)
        val remoteState = runCatching { RemoteJson.state(Appctr.remoteStateJSON()) }.getOrNull()
        val paired = runCatching { RemoteJson.paired(Appctr.remotePairedJSON()) }.getOrDefault(emptyList())

        val tailcatOffered = DeviceProfile.availability(ctx, Feature.TAILCAT).reachable
        val server = TailcatServer.load(ctx).takeIf { it.enabled }
        val forwards = TailcatConnections.load(ctx).filter { it.enabled }.flatMap { TailcatConnections.localPorts(it) }.distinct()

        val rootMode = GlobalSettings.isRootModeEnabled(ctx)
        val probe = probeRoot && rootMode
        val byedpi = GlobalSettings.isCPByeDpiEnabled(ctx)

        return ExposureFacts(
            running = running,
            net = ExposureNet.of(rootMode, GlobalSettings.isRootTunEnabled(ctx), GlobalSettings.isTunModeEnabled(ctx), GlobalSettings.getTunEngine(ctx)),
            rootMode = rootMode,
            shieldsUp = live?.shieldsUp ?: stored.shieldsUp ?: false,
            socks5 = GlobalSettings.getSocks5BindAddr(ctx),
            // Both fields or neither, in every part of the app.
            socks5Login = GlobalSettings.getString(ctx, "socks5_user", "").isNotEmpty() && GlobalSettings.getString(ctx, "socks5_pass", "").isNotEmpty(),
            http = GlobalSettings.getHttpProxyBindAddr(ctx),
            dns = GlobalSettings.getDnsProxyBindAddr(ctx),
            tailnetOpen = TailnetReach.opened(ctx).map { it.item }.toSet(),
            lanAccess = GlobalSettings.isLanAccessEnabled(ctx),
            webUi = if (profile.getBoolean("enable_webui", false)) profile.getString("webui_addr", "127.0.0.1:8080") ?: "127.0.0.1:8080" else "",
            // On until switched off, with Downloads shared: what the service sets on a profile's first start.
            taildriveEnabled = profile.getBoolean("taildrive_enabled", true),
            taildriveStorage = FileBrowse.storageAccess(ctx) == StorageAccess.GRANTED,
            taildriveShares = shares.map { it.name },
            taildriveWholeStorage = shares.any { isFullStoragePath(it.path) },
            taildrivePolicy = if (attached) TaildriveAccess.report().share else TaildriveAccess.Verdict.UNKNOWN,
            driveProxy = driveBind,
            driveProxyLogin = drive.auth != null,
            taildropFolder = TaildropFolder.get(ctx)?.let { f -> runCatching { TaildropFolder.label(ctx, f) }.getOrNull() ?: TaildropFolder.quickLabel(f) ?: "" },
            remote = remoteOn,
            remotePort = remoteState?.port?.takeIf { it > 0 } ?: Exposure.REMOTE_PORT,
            pairings = paired.map { RemotePairing(it.nodeId, it.name, it.owner, it.elevated?.until ?: 0) },
            remoteFull = RemoteLevel.isFullControl(ctx),
            remoteFiles = RemoteLevel.isFilesOpen(ctx),
            serveKnown = serve != null,
            served = serve.orEmpty(),
            exitNode = exitNode,
            exitNodeSwitch = storedExit,
            routes = routes,
            appConnector = appConnector,
            relayPort = relay?.port,
            rootTun = rootMode && GlobalSettings.isRootTunEnabled(ctx),
            bootScript = if (probe) runCatching { RootUtils.serviceScriptState(ctx).installed }.getOrNull() else null,
            cli = if (probe) runCatching { RootUtils.isTailscaleCliInstalled() }.getOrNull() else null,
            cliName = RootPaths.current.cliName,
            automation = GlobalSettings.isAutomationEnabled(ctx),
            automationToken = GlobalSettings.getAutomationSecret(ctx).isNotEmpty(),
            appFunctions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA,
            tailcatOffered = tailcatOffered,
            tailcatServer = server?.let { TailcatServing(it.allowAll, it.allowedKeys().size, TailcatConnections.localPorts(it.ports).sorted(), it.exitNode) },
            tailcatPorts = forwards,
            byedpi = byedpi,
            byedpiAddress = if (byedpi) ByeDpiProxy.activeAddress?.let { "${it.first}:${it.second}" }.orEmpty() else "",
        )
    }

    /** The ports of a serve config as the bridge returns it; null for an answer that is an error. */
    internal fun servedPorts(json: String): List<ServedPort>? {
        if (json.isBlank()) return null
        val obj = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        if ("Error" in obj) return null
        val config = runCatching { AppJson.decodeFromJsonElement(ServeConfig.serializer(), obj) }.getOrNull() ?: return null
        fun ports(service: String?, tcp: Map<Int, TCPPortHandler>?, web: Map<String, WebServerConfig>?, funnel: (Int) -> Boolean) =
            tcp.orEmpty().map { (port, handler) ->
                val paths = if (handler.tcpForward != null) emptyList()
                else web.orEmpty().entries.firstOrNull { it.key.endsWith(":$port") }?.value?.handlers.orEmpty().keys.sorted().ifEmpty { listOf("/") }
                ServedPort(port, service, paths, funnel(port))
            }
        val node = ports(null, config.tcp, config.web) { port -> config.allowFunnel.orEmpty().any { (key, on) -> on && key.endsWith(":$port") } }
        val services = config.services.orEmpty().flatMap { (name, svc) -> ports(name.removePrefix("svc:"), svc.tcp, svc.web) { false } }
        return (node + services).sortedWith(compareBy<ServedPort> { it.service.orEmpty() }.thenBy { it.port })
    }
}

/**
 * The switches of the rows that can be turned off where they are listed. Each does what the
 * same switch turned off does on its own page, and asks nothing the page does not ask: no page
 * confirms turning one of these off. Nothing here turns anything on.
 *
 * A local proxy the tailnet reaches because it was opened to it (TailnetReach) is closed to the
 * tailnet first — its row moves to the local network or this device — and stays on.
 */
object ExposureSwitches {

    /** Whether [row] has a switch here: it is on, and its own page turns it off with one. */
    fun canTurnOff(row: ExposureRow, f: ExposureFacts): Boolean = row.on && (Exposure.openedToTailnet(row, f) || when (row.item) {
        ExposureItem.HTTP_PROXY, ExposureItem.LAN_ACCESS, ExposureItem.WEB_UI, ExposureItem.TAILDRIVE_SHARES,
        ExposureItem.TAILDRIVE_PROXY, ExposureItem.REMOTE, ExposureItem.REMOTE_FULL, ExposureItem.REMOTE_FILES,
        ExposureItem.REMOTE_ELEVATED, ExposureItem.AUTOMATION, ExposureItem.TAILCAT_SERVER -> true
        // Only what the app's own switch set: an extra argument or the console would put it back.
        ExposureItem.EXIT_NODE -> f.exitNodeSwitch
        else -> false
    })

    /** Turns [row] off. Blocking for remote management, whose switches are files and bridge calls. */
    fun turnOff(context: Context, row: ExposureRow, f: ExposureFacts) {
        val ctx = context.applicationContext
        val profile = ctx.getSharedPreferences("appctr_${AccountManager.getActiveAccount(ctx).id}", Context.MODE_PRIVATE)
        fun apply() = ctx.startService(Intent(ctx, TailscaledService::class.java).apply { action = TailscaledService.ACTION_APPLY_SETTINGS })
        // Settings → Local proxies → Reachable from the tailnet: the daemon follows its list.
        if (Exposure.openedToTailnet(row, f)) {
            TailnetListener.of(row.item)?.let { TailnetReach.setOpen(ctx, it, false) }
            return
        }
        when (row.item) {
            // Settings → Local proxies → Enable HTTP proxy.
            ExposureItem.HTTP_PROXY -> { GlobalSettings.setString(ctx, "httpproxy", ""); apply() }
            // Settings → Local proxies → LAN access: the listeners are bound again, by a restart.
            ExposureItem.LAN_ACCESS -> {
                GlobalSettings.setLanAccessEnabled(ctx, false)
                if (ProxyState.isUserLetRunning(ctx)) ctx.startService(Intent(ctx, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
            }
            // Settings → Sharing → Web UI.
            ExposureItem.WEB_UI -> { profile.edit().putBoolean("enable_webui", false).apply(); apply() }
            // Taildrive's own two switches.
            ExposureItem.TAILDRIVE_SHARES -> { profile.edit().putBoolean("taildrive_enabled", false).apply(); apply() }
            ExposureItem.TAILDRIVE_PROXY -> { profile.edit().putBoolean(DriveProxy.KEY_ENABLED, false).apply(); apply() }
            // Settings → Account → Run as exit node.
            ExposureItem.EXIT_NODE -> { profile.edit().putBoolean("advertise_exit_node", false).apply(); apply() }
            // Remote management's page: off takes full control and the files with it.
            ExposureItem.REMOTE -> RemoteManage.setManaged(ctx, false)
            ExposureItem.REMOTE_FULL -> RemoteLevel.setFullControl(ctx, false)
            ExposureItem.REMOTE_FILES -> RemoteLevel.setFilesOpen(ctx, false)
            ExposureItem.REMOTE_ELEVATED -> RemoteElevate.takeBack(ctx, row.key.substringAfter(':'))
            // Settings → Automation → Allow external automation.
            ExposureItem.AUTOMATION -> GlobalSettings.setAutomationEnabled(ctx, false)
            // TailCat's server card.
            ExposureItem.TAILCAT_SERVER -> TailcatService.stopServer(ctx)
            else -> Unit
        }
    }
}
