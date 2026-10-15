package io.github.bropines.tailscaled.core

/*
 * What this device exposes: everything that listens or can be reached right now, who can
 * reach it, and what turns it off — the model behind Settings → Advanced → "What this device
 * exposes" (ui/ExposureScreen.kt) and the matching block of the diagnostics report.
 *
 * Plain values in, plain values out, so the rules have tests: core/ExposureRead.kt reads the
 * facts off the app and the bridge, [Exposure.rows] decides each row's reach and group.
 *
 * Who reaches a local listener depends on how the daemon carries this device's tailnet
 * address ([ExposureNet]). In userspace networking — proxy mode, the hev TUN engine, Root
 * Mode without its kernel interface — netstack ends every connection to that address. Upstream
 * dials the same port on 127.0.0.1 for whatever nothing else took (wgengine/netstack acceptTCP
 * and forwardUDP), which made every loopback listener a service of the tailnet; patch 23
 * refuses that unless the port is open (core/TailnetReach.kt): remote management's while it
 * is on, and the proxies the person opened under Settings → Local proxies → Reachable from the
 * tailnet ([ExposureFacts.tailnetOpen]). Everything else listening on 127.0.0.1 or on every
 * address is this device's, or its local network's. With a real interface — the native TUN
 * engine, Root Mode's tailscale0 — the address belongs to the host stack: what listens on
 * 127.0.0.1 never answers it and the switches change nothing; what listens on every address
 * does, except the app's own listeners under the native engine, whose answers leave outside the
 * VPN the app is excluded from ([ExposureNet.VPN]).
 *
 * Every listener the code opens is a row here or is left out for a reason:
 *  - the daemon: SOCKS5 and HTTP proxies (rows), the peer API — Taildrop and Taildrive
 *    shares are its rows, Serve and Funnel too — the peer relay (row), the LocalAPI socket
 *    (left out: inside the app's private directory, only the app and root reach it; the CLI
 *    wrapper's row is root's way in). Tailscale SSH is compiled out (ts_omit_ssh) and the
 *    daemon's own web client does not build for Android (ipnlocal/web_client.go is !android),
 *    so neither has a row;
 *  - the bridge: the DNS proxy, the Web UI, the Taildrive proxy, the remote-management
 *    endpoint, TailCat's forwards and server (rows). The Taildrive file server
 *    (driveimpl.FileServer on 127.0.0.1) answers only with a per-run secret token the daemon
 *    holds, so it is part of the shares' row and not a door of its own; the sign-in session
 *    of a television (appctr/setup.go) listens only while its screen is open and says so
 *    there;
 *  - Kotlin and JNI: ByeDPI's loopback proxy (row). hev-socks5-tunnel listens on nothing.
 *  - what other apps can call: the automation receiver and the AppFunctions (rows).
 */

/** How the daemon receives connections to this device's tailnet address; see the header. */
enum class ExposureNet {
    /** Userspace networking: netstack hands a connection to the same port on 127.0.0.1 when that port is open. */
    NETSTACK,

    /**
     * The native TUN engine: Android's VPN carries the address and the host stack answers it, but
     * the app itself is outside its own VPN, so answers from its listeners are never routed back
     * into the tunnel (measured 2026-10-15: SOCKS5 on every address did not answer the tailnet).
     * Other apps' listeners on every address do.
     */
    VPN,

    /**
     * Root Mode's tailscale0 carries the address: the host stack answers it, and the root
     * daemon's answers are routed back into it — the app's listeners on every address are taken
     * to be reached (not measured on a device; the safe side of not knowing).
     */
    INTERFACE;

    companion object {
        fun of(rootMode: Boolean, rootTun: Boolean, tunMode: Boolean, tunEngine: String): ExposureNet = when {
            // Root Mode routes by itself and never uses TUN mode.
            rootMode -> if (rootTun) INTERFACE else NETSTACK
            tunMode && tunEngine == TunEngines.NATIVE -> VPN
            // Proxy mode, and the hev engine, which only pushes packets into the SOCKS5 port.
            else -> NETSTACK
        }
    }
}

/** Who, besides this device itself, can reach something. */
enum class ExposureReach { INTERNET, TAILNET, LAN }

/** The groups of the screen, in its order: the widest reach first, what is off last. */
enum class ExposureGroup { INTERNET, TAILNET, LAN, DEVICE, OFF }

/** Everything the screen lists, in the order rows stand within a group. */
enum class ExposureItem(
    /** Usable on this device whatever reaches it from outside: a listener, a command. Blocked from the tailnet it is this device's. */
    val local: Boolean = false,
    /** Its tailnet reach passes the daemon's packet filter, which Shields up closes. */
    val filtered: Boolean = true,
) {
    FUNNEL,
    TAILCAT_SERVER(filtered = false),
    REMOTE,
    REMOTE_FULL,
    REMOTE_FILES,
    REMOTE_ELEVATED,
    TAILDRIVE_SHARES,
    TAILDROP,
    SERVE,
    EXIT_NODE,
    SUBNET_ROUTES,
    APP_CONNECTOR,
    PEER_RELAY(filtered = false),
    LAN_ACCESS(local = true),
    SOCKS5(local = true),
    HTTP_PROXY(local = true),
    DNS_PROXY(local = true),
    WEB_UI(local = true),
    TAILDRIVE_PROXY(local = true),
    TAILCAT_FORWARDS(local = true),
    BYEDPI(local = true),
    DEVICE_PORTS,
    ROOT_KERNEL_TUN,
    ROOT_BOOT_SCRIPT(local = true),
    ROOT_CLI(local = true),
    AUTOMATION(local = true),
    APP_FUNCTIONS(local = true),
}

/** Why a row says something other than plain on or off. */
enum class ExposureNote {
    /** The tailnet's policy has not answered yet (Taildrive). */
    POLICY_UNKNOWN,

    /** The tailnet's policy does not let this device do it (Taildrive). */
    POLICY_DENIED,

    /** Folders are set, and the app may not read storage. */
    NO_STORAGE,

    /** On, with no token: every broadcast is refused. */
    NO_TOKEN,

    /** Only the running daemon knows (Serve and Funnel). */
    NOT_KNOWN,

    /** Root was not asked. */
    NOT_CHECKED,

    /** Shields up stands between the tailnet and it. */
    BLOCKED,
}

/** A port this device serves: on the node, or on a Tailscale Service ([service] without `svc:`). */
data class ServedPort(
    val port: Int,
    val service: String? = null,
    /** The mount paths of its web handlers; empty for a TCP forward. */
    val paths: List<String> = emptyList(),
    val funnel: Boolean = false,
)

/** A pairing of remote management, as much of it as the screen says. */
data class RemotePairing(
    val nodeId: String,
    val name: String,
    val owner: Boolean,
    /** Unix seconds a guest's full rights for a while end at; 0 without them. */
    val elevatedUntil: Long = 0,
)

/** This device as a TailCat server, switched on. */
data class TailcatServing(
    /** Anyone holding the address may connect, not only the listed keys. */
    val anyone: Boolean,
    val keys: Int,
    val ports: List<Int> = emptyList(),
    val exitNode: Boolean = false,
)

/**
 * Everything [Exposure.rows] decides from, as read (ExposureRead). A listen address is
 * `host:port`, empty for a listener that is off.
 */
data class ExposureFacts(
    /** A daemon answers. Without one most rows describe what opens when it starts. */
    val running: Boolean = false,
    val net: ExposureNet = ExposureNet.NETSTACK,
    val rootMode: Boolean = false,
    /** "Block incoming connections": the daemon's own value when it runs. */
    val shieldsUp: Boolean = false,
    val socks5: String = "",
    val socks5Login: Boolean = false,
    val http: String = "",
    val dns: String = "",
    /** The local proxies opened to the tailnet (TailnetReach): SOCKS5, HTTP, DNS, Taildrive's. */
    val tailnetOpen: Set<ExposureItem> = emptySet(),
    val lanAccess: Boolean = false,
    /** The app's Web UI, where it listens; empty when off. */
    val webUi: String = "",
    val taildriveEnabled: Boolean = false,
    val taildriveStorage: Boolean = false,
    val taildriveShares: List<String> = emptyList(),
    val taildriveWholeStorage: Boolean = false,
    val taildrivePolicy: TaildriveAccess.Verdict = TaildriveAccess.Verdict.UNKNOWN,
    val driveProxy: String = "",
    val driveProxyLogin: Boolean = false,
    /** Where received files go; null: they wait in the inbox. */
    val taildropFolder: String? = null,
    val remote: Boolean = false,
    val remotePort: Int = Exposure.REMOTE_PORT,
    val pairings: List<RemotePairing> = emptyList(),
    val remoteFull: Boolean = false,
    val remoteFiles: Boolean = false,
    /** False while the daemon cannot be asked: [served] is then unknown, not empty. */
    val serveKnown: Boolean = false,
    val served: List<ServedPort> = emptyList(),
    val exitNode: Boolean = false,
    /** The exit node is the app's own switch, which can turn it off; false when an extra argument or the console set it. */
    val exitNodeSwitch: Boolean = false,
    val routes: List<String> = emptyList(),
    val appConnector: Boolean = false,
    /** The peer relay's UDP port, 0 for any; null when off. */
    val relayPort: Int? = null,
    val rootTun: Boolean = false,
    /** Null: root was not asked. */
    val bootScript: Boolean? = null,
    val cli: Boolean? = null,
    val cliName: String = "tailscale",
    val automation: Boolean = false,
    val automationToken: Boolean = false,
    /** Android has AppFunctions (16 and later). */
    val appFunctions: Boolean = false,
    /** TailCat is part of the app on this device; its rows are listed only where it is, or where it runs anyway. */
    val tailcatOffered: Boolean = true,
    val tailcatServer: TailcatServing? = null,
    val tailcatPorts: List<Int> = emptyList(),
    val byedpi: Boolean = false,
    /** Where ByeDPI listens now; empty when it does not run. */
    val byedpiAddress: String = "",
)

/**
 * One row. [reach] is who reaches it as it is set up; [effective] what is left of that with
 * Shields up. The rest is what its state line says.
 */
data class ExposureRow(
    val item: ExposureItem,
    val on: Boolean,
    val reach: Set<ExposureReach> = emptySet(),
    val effective: Set<ExposureReach> = reach,
    val note: ExposureNote? = null,
    /** The listen address, or the port alone. */
    val address: String = "",
    /** A password, a login: the one yes-or-no a row has to tell. */
    val guarded: Boolean? = null,
    val count: Int = 0,
    /** Names a row lists: folders, routes, ports, paired devices. */
    val names: List<String> = emptyList(),
    /** Unix seconds, for a row that ends by itself. */
    val until: Long = 0,
    /** Tells two rows of one item apart: the guest of [ExposureItem.REMOTE_ELEVATED]. */
    val key: String = item.name,
) {
    val group: ExposureGroup
        get() = when {
            !on -> ExposureGroup.OFF
            ExposureReach.INTERNET in effective -> ExposureGroup.INTERNET
            ExposureReach.LAN in effective -> ExposureGroup.LAN
            ExposureReach.TAILNET in effective -> ExposureGroup.TAILNET
            // Something the tailnet would reach and Shields up keeps out: nobody reaches it now.
            effective != reach && !item.local -> ExposureGroup.OFF
            else -> ExposureGroup.DEVICE
        }
}

/**
 * How many things stand in each group, for the line at the top of the screen: what the public
 * internet reaches, what the tailnet reaches at most, what the local network reaches as well.
 */
data class ExposureSummary(val internet: Int, val tailnet: Int, val lan: Int, val device: Int) {
    val quiet: Boolean get() = internet == 0 && tailnet == 0 && lan == 0
}

object Exposure {

    /** The remote-management port, appctr/remote.go RemotePort: fixed, so a paired device always knows it. */
    const val REMOTE_PORT = 28115

    /**
     * Who reaches one of the app's listeners on [bind] beside this device itself. Every address:
     * the local network, and the tailnet where Root Mode's interface carries the tailnet address
     * or where netstack lets the tailnet in to it ([openToTailnet], TailnetReach). 127.0.0.1: the
     * tailnet only that way. Another loopback address: nobody. A tailnet address: the tailnet; any
     * other: its network. Under the native engine the app's answers never go back into the tunnel,
     * so the tailnet reaches none of its listeners ([ExposureNet.VPN]).
     */
    fun listenerReach(bind: String, net: ExposureNet, openToTailnet: Boolean = false): Set<ExposureReach> {
        val host = NetAddr.host(bind)
        val letIn = net == ExposureNet.NETSTACK && openToTailnet
        val reach = when {
            host.isEmpty() || host == NetAddr.WILDCARD_V4 || host == "::" ->
                if (net == ExposureNet.INTERFACE || letIn) setOf(ExposureReach.LAN, ExposureReach.TAILNET) else setOf(ExposureReach.LAN)
            host == NetAddr.LOOPBACK_V4 || host.equals("localhost", ignoreCase = true) ->
                if (letIn) setOf(ExposureReach.TAILNET) else emptySet()
            host.startsWith("127.") || host == "::1" -> emptySet()
            isTailnetAddress(host) -> setOf(ExposureReach.TAILNET)
            else -> setOf(ExposureReach.LAN)
        }
        return if (net == ExposureNet.VPN) reach - ExposureReach.TAILNET else reach
    }

    /**
     * Whether [row]'s reach into the tailnet is its "Reachable from the tailnet" switch, so that
     * switching that off is what closes it: in userspace networking, for a proxy opened to it.
     */
    fun openedToTailnet(row: ExposureRow, f: ExposureFacts): Boolean =
        row.on && f.net == ExposureNet.NETSTACK && row.item in f.tailnetOpen && ExposureReach.TAILNET in row.reach

    /** 100.64.0.0/10 or fd7a:115c:a1e0::/48. */
    private fun isTailnetAddress(host: String): Boolean {
        val v4 = host.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4) return v4[0] == 100 && v4[1] in 64..127
        return host.lowercase().startsWith("fd7a:115c:a1e0:")
    }

    /** Every row for [f], in the screen's order: by group, then by item. */
    fun rows(f: ExposureFacts): List<ExposureRow> {
        val tailnet = setOf(ExposureReach.TAILNET)
        val out = ArrayList<ExposureRow>()
        fun add(row: ExposureRow) {
            val shut = f.shieldsUp && row.item.filtered
            val effective = if (shut) row.reach - ExposureReach.TAILNET - ExposureReach.INTERNET else row.reach
            out += row.copy(effective = effective, note = row.note ?: ExposureNote.BLOCKED.takeIf { row.on && effective != row.reach })
        }
        fun listener(item: ExposureItem, bind: String, guarded: Boolean? = null) =
            ExposureRow(item, bind.isNotBlank(), if (bind.isBlank()) emptySet() else listenerReach(bind, f.net, item in f.tailnetOpen), address = bind, guarded = guarded)

        // ─── Served, and how far ─────────────────────────────────────────────────────────
        val funnel = f.served.filter { it.funnel }
        val tailnetOnly = f.served.filter { !it.funnel }
        if (f.serveKnown) {
            add(ExposureRow(ExposureItem.FUNNEL, funnel.isNotEmpty(), setOf(ExposureReach.INTERNET, ExposureReach.TAILNET), names = funnel.map(::servedName)))
            add(ExposureRow(ExposureItem.SERVE, tailnetOnly.isNotEmpty(), tailnet, names = tailnetOnly.map(::servedName)))
        } else {
            // Nothing to say until the daemon is asked; Funnel waits with it rather than saying "off".
            add(ExposureRow(ExposureItem.SERVE, false, note = ExposureNote.NOT_KNOWN))
        }
        if (f.tailcatOffered || f.tailcatServer != null) {
            val s = f.tailcatServer
            add(ExposureRow(ExposureItem.TAILCAT_SERVER, s != null, setOf(ExposureReach.INTERNET), guarded = s?.let { !it.anyone }, count = s?.keys ?: 0, names = s?.ports.orEmpty().map { it.toString() }))
        }

        // ─── Remote management ───────────────────────────────────────────────────────────
        add(ExposureRow(ExposureItem.REMOTE, f.remote, tailnet, address = ":${f.remotePort}", count = f.pairings.size, names = f.pairings.map { it.name }))
        if (f.remote) {
            add(ExposureRow(ExposureItem.REMOTE_FULL, f.remoteFull, tailnet, count = f.pairings.count { it.owner }))
            add(ExposureRow(ExposureItem.REMOTE_FILES, f.remoteFiles, tailnet))
            for (p in f.pairings.filter { it.elevatedUntil > 0 }) {
                add(ExposureRow(ExposureItem.REMOTE_ELEVATED, true, tailnet, names = listOf(p.name), until = p.elevatedUntil, key = "${ExposureItem.REMOTE_ELEVATED.name}:${p.nodeId}"))
            }
        }

        // ─── Files ───────────────────────────────────────────────────────────────────────
        val shares = f.taildriveShares
        val sharing = f.taildriveEnabled && shares.isNotEmpty()
        val shareNote = when {
            !sharing -> null
            !f.taildriveStorage -> ExposureNote.NO_STORAGE
            f.taildrivePolicy == TaildriveAccess.Verdict.DENIED -> ExposureNote.POLICY_DENIED
            f.taildrivePolicy == TaildriveAccess.Verdict.UNKNOWN -> ExposureNote.POLICY_UNKNOWN
            else -> null
        }
        val sharesOn = sharing && shareNote != ExposureNote.NO_STORAGE && shareNote != ExposureNote.POLICY_DENIED
        add(ExposureRow(ExposureItem.TAILDRIVE_SHARES, sharesOn, tailnet, note = shareNote, guarded = f.taildriveWholeStorage.takeIf { it }, count = shares.size, names = shares))
        // Received with the daemon, always: only Shields up keeps senders out.
        add(ExposureRow(ExposureItem.TAILDROP, true, tailnet, names = listOfNotNull(f.taildropFolder)))

        // ─── What others route through this device ───────────────────────────────────────
        add(ExposureRow(ExposureItem.EXIT_NODE, f.exitNode, tailnet))
        add(ExposureRow(ExposureItem.SUBNET_ROUTES, f.routes.isNotEmpty(), tailnet, count = f.routes.size, names = f.routes))
        add(ExposureRow(ExposureItem.APP_CONNECTOR, f.appConnector, tailnet))
        add(ExposureRow(ExposureItem.PEER_RELAY, f.relayPort != null, tailnet, address = f.relayPort?.takeIf { it > 0 }?.let { ":$it" }.orEmpty()))

        // ─── Local listeners ─────────────────────────────────────────────────────────────
        add(ExposureRow(ExposureItem.LAN_ACCESS, f.lanAccess, setOf(ExposureReach.LAN)))
        add(listener(ExposureItem.SOCKS5, f.socks5, guarded = f.socks5Login))
        add(listener(ExposureItem.HTTP_PROXY, f.http, guarded = false))
        add(listener(ExposureItem.DNS_PROXY, f.dns))
        add(listener(ExposureItem.WEB_UI, f.webUi))
        add(listener(ExposureItem.TAILDRIVE_PROXY, f.driveProxy, guarded = f.driveProxyLogin))
        if (f.tailcatOffered || f.tailcatPorts.isNotEmpty()) {
            val ports = f.tailcatPorts.sorted()
            // Every forward and proxy listens on 127.0.0.1 (appctr/tailcat.go, tcBind).
            val reach = if (ports.isEmpty()) emptySet() else listenerReach("${NetAddr.LOOPBACK_V4}:${ports.first()}", f.net)
            add(ExposureRow(ExposureItem.TAILCAT_FORWARDS, ports.isNotEmpty(), reach, address = NetAddr.LOOPBACK_V4, names = ports.map { it.toString() }))
        }
        add(ExposureRow(ExposureItem.BYEDPI, f.byedpi, if (f.byedpiAddress.isBlank()) emptySet() else listenerReach(f.byedpiAddress, f.net), address = f.byedpiAddress))
        // The ports of this device as a whole, other apps' among them: what the mode lets the tailnet
        // reach. With a real interface, whatever listens on every address; in userspace networking
        // nothing beyond what the rows above open.
        add(ExposureRow(ExposureItem.DEVICE_PORTS, f.net != ExposureNet.NETSTACK, tailnet))

        // ─── Root Mode ───────────────────────────────────────────────────────────────────
        if (f.rootMode) {
            add(ExposureRow(ExposureItem.ROOT_KERNEL_TUN, f.rootTun, tailnet))
            add(ExposureRow(ExposureItem.ROOT_BOOT_SCRIPT, f.bootScript == true, note = ExposureNote.NOT_CHECKED.takeIf { f.bootScript == null }))
            add(ExposureRow(ExposureItem.ROOT_CLI, f.cli == true, address = f.cliName, note = ExposureNote.NOT_CHECKED.takeIf { f.cli == null }))
        }

        // ─── Other apps on this device ───────────────────────────────────────────────────
        add(ExposureRow(ExposureItem.AUTOMATION, f.automation && f.automationToken, note = ExposureNote.NO_TOKEN.takeIf { f.automation && !f.automationToken }))
        if (f.appFunctions) add(ExposureRow(ExposureItem.APP_FUNCTIONS, true, guarded = f.automation))

        return out.sortedWith(compareBy<ExposureRow> { it.group.ordinal }.thenBy { it.item.ordinal })
    }

    /** "443 /, /api", "8443 svc:web", "5432 TCP" — a served port and what is on it. */
    private fun servedName(p: ServedPort): String = buildString {
        append(p.port)
        p.service?.let { append(" svc:").append(it) }
        if (p.paths.isNotEmpty()) append(' ').append(p.paths.joinToString(", ")) else append(" TCP")
    }

    /** The rows by group, as the groups' own headers count them. */
    fun summary(rows: List<ExposureRow>): ExposureSummary {
        val by = rows.groupingBy { it.group }.eachCount()
        return ExposureSummary(
            internet = by[ExposureGroup.INTERNET] ?: 0,
            tailnet = by[ExposureGroup.TAILNET] ?: 0,
            lan = by[ExposureGroup.LAN] ?: 0,
            device = by[ExposureGroup.DEVICE] ?: 0,
        )
    }

    // ─── The diagnostics report ──────────────────────────────────────────────────────────

    /** What a report calls each item: plain English, fixed, never translated. */
    private val ExposureItem.label: String
        get() = when (this) {
            ExposureItem.FUNNEL -> "Funnel"
            ExposureItem.TAILCAT_SERVER -> "TailCat server"
            ExposureItem.REMOTE -> "Remote management"
            ExposureItem.REMOTE_FULL -> "Remote full control"
            ExposureItem.REMOTE_FILES -> "Remote access to files"
            ExposureItem.REMOTE_ELEVATED -> "Guest with full rights for a while"
            ExposureItem.TAILDRIVE_SHARES -> "Taildrive shares"
            ExposureItem.TAILDROP -> "Taildrop receiving"
            ExposureItem.SERVE -> "Serve"
            ExposureItem.EXIT_NODE -> "Exit node offered"
            ExposureItem.SUBNET_ROUTES -> "Subnet routes advertised"
            ExposureItem.APP_CONNECTOR -> "App connector"
            ExposureItem.PEER_RELAY -> "Peer relay"
            ExposureItem.LAN_ACCESS -> "Proxies open to the local network"
            ExposureItem.SOCKS5 -> "SOCKS5"
            ExposureItem.HTTP_PROXY -> "HTTP proxy"
            ExposureItem.DNS_PROXY -> "DNS proxy"
            ExposureItem.WEB_UI -> "Web UI"
            ExposureItem.TAILDRIVE_PROXY -> "Taildrive proxy"
            ExposureItem.TAILCAT_FORWARDS -> "TailCat forwards"
            ExposureItem.BYEDPI -> "ByeDPI proxy"
            ExposureItem.DEVICE_PORTS -> "This device's ports"
            ExposureItem.ROOT_KERNEL_TUN -> "Root kernel interface"
            ExposureItem.ROOT_BOOT_SCRIPT -> "Root boot script"
            ExposureItem.ROOT_CLI -> "Root CLI wrapper"
            ExposureItem.AUTOMATION -> "Automation broadcasts"
            ExposureItem.APP_FUNCTIONS -> "AppFunctions"
        }

    private val ExposureGroup.label: String
        get() = when (this) {
            ExposureGroup.INTERNET -> "internet"
            ExposureGroup.TAILNET -> "tailnet"
            ExposureGroup.LAN -> "local network"
            ExposureGroup.DEVICE -> "device"
            ExposureGroup.OFF -> "off"
        }

    /**
     * The rows as report lines. Like the rest of the report it names nothing of the tailnet:
     * no peer, share, route or service names, no tailnet addresses, no secrets — counts, local
     * listen addresses and port numbers only.
     */
    fun reportText(f: ExposureFacts, rows: List<ExposureRow> = rows(f)): String = buildString {
        val s = summary(rows)
        append("Exposure (")
        append(if (f.running) "running" else "stopped, as set")
        append(", net ").append(f.net.name.lowercase())
        append(", shields up ").append(f.shieldsUp)
        appendLine("):")
        append("  Widest reach: internet ${s.internet}, tailnet ${s.tailnet}, local network ${s.lan}, device only ${s.device}")
        for (row in rows.filter { it.group != ExposureGroup.OFF }) {
            append("\n  [").append(row.group.label).append("] ").append(row.item.label)
            reportDetail(row).takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
        }
        val off = rows.filter { it.group == ExposureGroup.OFF }
        if (off.isNotEmpty()) {
            append("\n  Off: ")
            append(off.joinToString { r -> r.item.label + (r.note?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "") })
        }
    }

    private fun reportDetail(row: ExposureRow): String = buildList {
        when (row.item) {
            // Local listen addresses, as the report's own SOCKS5 and DNS lines give them.
            ExposureItem.SOCKS5, ExposureItem.HTTP_PROXY, ExposureItem.DNS_PROXY, ExposureItem.WEB_UI,
            ExposureItem.TAILDRIVE_PROXY, ExposureItem.BYEDPI -> if (row.address.isNotEmpty()) add(row.address)
            ExposureItem.REMOTE, ExposureItem.PEER_RELAY -> if (row.address.isNotEmpty()) add("port ${row.address.removePrefix(":")}")
            else -> Unit
        }
        when (row.item) {
            ExposureItem.SOCKS5 -> add(if (row.guarded == true) "password" else "no password")
            ExposureItem.TAILDRIVE_PROXY -> add(if (row.guarded == true) "login" else "no login")
            ExposureItem.TAILCAT_SERVER -> add(if (row.guarded == true) "client keys: ${row.count}" else "anyone with the address")
            ExposureItem.REMOTE -> add("paired: ${row.count}")
            ExposureItem.REMOTE_FULL -> add("owners: ${row.count}")
            ExposureItem.TAILDRIVE_SHARES -> {
                add("folders: ${row.count}")
                if (row.guarded == true) add("whole storage")
            }
            ExposureItem.TAILDROP -> add(if (row.names.isEmpty()) "to the inbox" else "to a chosen folder")
            ExposureItem.SUBNET_ROUTES -> add("routes: ${row.count}")
            // Port numbers only: a service's name is the tailnet's.
            ExposureItem.FUNNEL, ExposureItem.SERVE -> add("ports " + row.names.map { it.substringBefore(' ') }.distinct().joinToString(","))
            ExposureItem.TAILCAT_FORWARDS -> add("ports " + row.names.joinToString(","))
            ExposureItem.APP_FUNCTIONS -> add(if (row.guarded == true) "changes allowed" else "reading only")
            else -> Unit
        }
        row.note?.let { add(it.name.lowercase().replace('_', ' ')) }
    }.joinToString(", ")
}
