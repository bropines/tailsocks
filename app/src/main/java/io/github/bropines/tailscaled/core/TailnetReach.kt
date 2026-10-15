package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import java.io.File

/*
 * Settings → Local proxies → Reachable from the tailnet: what another device of the tailnet
 * reaches on this one through its tailnet address.
 *
 * Why it is needed: in userspace networking — proxy mode, the hev engine, Root Mode without its
 * interface — the daemon's netstack ends every connection to this device's tailnet address, and
 * upstream hands what nothing else took (Serve, the peer API) to the same port on 127.0.0.1.
 * Every loopback listener of the device answers the tailnet: other apps' servers, which is what
 * any Tailscale node in userspace networking does and what people use (adb over the network, an
 * SSH server, Syncthing), and the app's own, which ask for nothing — the SOCKS5 proxy without a
 * password, the HTTP, DNS and Taildrive proxies, the Web UI, TailCat's forwards. Patch 23
 * (cmd/tailscaled/android_loopback.go) refuses what the policy [sync] writes to
 * files/tailnet-reach does not allow:
 *  - default mode: the app's own listeners the person did not open here, a "close tcp 48115"
 *    line each; every other port is served as upstream serves it;
 *  - strict mode ("Strict: close everything else too"): every port but the proxies opened here
 *    and the ports listed under the switch, an "open tcp 5555" line each.
 * Remote management's port is its own switch's in both (RemoteManage). The daemon reads the
 * policy again at most a second later, so a switch takes effect without a restart; [sync] runs
 * wherever the service builds its options, whenever TailCat starts or stops a connection, and
 * whenever a switch here moves. Without the file the daemon closes the app's listeners at their
 * default ports and serves the rest.
 *
 * With a real interface — the native TUN engine, Root Mode's tailscale0 — the host stack owns
 * the address, and these switches change nothing: a listener on 127.0.0.1 is never reached from
 * the tailnet there. One on every address (Local network access) is under Root Mode, and is not
 * under the native engine, whose VPN the app is outside of (core/Exposure.kt, ExposureNet).
 */

/** A listener of the app that can be opened to the tailnet, and the global preference that opens it. */
enum class TailnetListener(val key: String, val item: ExposureItem, val udp: Boolean = false) {
    SOCKS5("tailnet_socks5", ExposureItem.SOCKS5),
    HTTP_PROXY("tailnet_http_proxy", ExposureItem.HTTP_PROXY),
    DNS_PROXY("tailnet_dns_proxy", ExposureItem.DNS_PROXY, udp = true),
    TAILDRIVE_PROXY("tailnet_drive_proxy", ExposureItem.TAILDRIVE_PROXY);

    companion object {
        fun of(item: ExposureItem): TailnetListener? = entries.firstOrNull { it.item == item }
    }
}

/** A port of this device's tailnet address, in one protocol: what the daemon's policy names. */
data class ReachPort(val port: Int, val udp: Boolean = false) {
    /** "tcp 5555", as the policy writes it. */
    val line: String get() = (if (udp) "udp " else "tcp ") + port

    /** "5555", "udp:1234", as the person writes it. */
    val label: String get() = if (udp) "udp:$port" else port.toString()
}

/**
 * The "Also open these ports" field, read: the ports it opens, and what in it is not a port
 * ([bad]) or is one of the app's own, which their switches open ([own]) — both as typed.
 */
data class ExtraPorts(val ports: List<ReachPort>, val bad: List<String> = emptyList(), val own: List<String> = emptyList()) {
    val valid: Boolean get() = bad.isEmpty() && own.isEmpty()
}

object TailnetReach {
    private const val TAG = "TailnetReach"

    /** Beside the daemon's socket; patch 23's tailnetReachName. */
    const val FILE = "tailnet-reach"

    /** The list of the first builds with patch 23, which only opened ports; [sync] removes it. */
    private const val OLD_FILE = "tailnet-open"

    /** Strict mode, and the ports it leaves open: global preferences, off and empty unless set here. */
    const val KEY_STRICT = "tailnet_strict"
    const val KEY_EXTRA = "tailnet_extra_ports"

    fun isOpen(context: Context, listener: TailnetListener): Boolean = GlobalSettings.getBoolean(context, listener.key, false)

    /** Opens or closes [listener] to the tailnet; a running daemon follows within a second. Blocking: a file. */
    fun setOpen(context: Context, listener: TailnetListener, open: Boolean) {
        // The preferences answer the new value at once; the policy is written from them.
        GlobalSettings.setBoolean(context, listener.key, open)
        sync(context)
    }

    fun isStrict(context: Context): Boolean = GlobalSettings.getBoolean(context, KEY_STRICT, false)

    /** Strict mode on or off; a running daemon follows within a second. Blocking: a file. */
    fun setStrict(context: Context, strict: Boolean) {
        GlobalSettings.setBoolean(context, KEY_STRICT, strict)
        sync(context)
    }

    /** The field as saved: "5555, 8022, udp:1234". */
    fun extraText(context: Context): String = GlobalSettings.getString(context, KEY_EXTRA, "")

    /** Saves the field, written the way [parseExtra] reads it back. Blocking: a file. */
    fun setExtra(context: Context, text: String) {
        GlobalSettings.setString(context, KEY_EXTRA, normalExtra(text))
        sync(context)
    }

    /** The ports the field leaves open in strict mode, as the daemon is told them: the app's own left out. */
    fun extraOpen(context: Context): List<ReachPort> = parseExtra(extraText(context), ownPorts(context)).ports

    /** How the daemon carries the tailnet address as set: where these switches matter ([ExposureNet.NETSTACK]). */
    fun net(context: Context): ExposureNet = ExposureNet.of(
        GlobalSettings.isRootModeEnabled(context), GlobalSettings.isRootTunEnabled(context),
        GlobalSettings.isTunModeEnabled(context), GlobalSettings.getTunEngine(context),
    )

    /** The listeners opened to the tailnet, as the switches say. */
    fun opened(context: Context): Set<TailnetListener> = TailnetListener.entries.filter { isOpen(context, it) }.toSet()

    /** Where [listener] listens now: `host:port`, empty while it is off. */
    fun bind(context: Context, listener: TailnetListener): String = when (listener) {
        TailnetListener.SOCKS5 -> GlobalSettings.getSocks5BindAddr(context)
        TailnetListener.HTTP_PROXY -> GlobalSettings.getHttpProxyBindAddr(context)
        TailnetListener.DNS_PROXY -> GlobalSettings.getDnsProxyBindAddr(context)
        TailnetListener.TAILDRIVE_PROXY -> {
            val profile = profile(context)
            val drive = DriveProxy.read(profile)
            val ip = profile.getString(DriveProxy.KEY_IP, DriveLan.LOOPBACK) ?: DriveLan.LOOPBACK
            // An address stored empty listens on every interface (core/TaildriveLan.kt).
            if (drive.enabled) "${ip.ifBlank { DriveLan.EVERY_INTERFACE }}:${drive.port}" else ""
        }
    }

    /**
     * Where the app's other listeners listen now, TCP all of them, none of them ever opened to the
     * tailnet: the Web UI while it is on, and TailCat's forwards and proxies of the connections
     * switched on — on 127.0.0.1, every one (appctr/tailcat.go, tcBind).
     */
    fun otherBinds(context: Context): List<String> {
        val profile = profile(context)
        val web = if (profile.getBoolean("enable_webui", false)) profile.getString("webui_addr", WEB_UI_DEFAULT) ?: WEB_UI_DEFAULT else ""
        val tailcat = TailcatConnections.load(context).filter { it.enabled }
            .flatMap { TailcatConnections.localPorts(it) }
            .map { "${NetAddr.LOOPBACK_V4}:$it" }
        return listOf(web).filter { it.isNotBlank() } + tailcat
    }

    private const val WEB_UI_DEFAULT = "127.0.0.1:8080"

    /** Whether the Taildrive proxy of the active profile asks for a login. */
    fun driveLogin(context: Context): Boolean = DriveProxy.read(profile(context)).auth != null

    private fun profile(context: Context) =
        context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)

    /**
     * Whether a connection the daemon forwards to 127.0.0.1 reaches a listener on [bind]: one on
     * loopback itself or on every address. Another loopback address (127.x.y.z) or an interface's
     * own is not reached that way, so opening it would open nothing, and closing it close nothing.
     */
    fun reachableThroughLoopback(bind: String): Boolean {
        val host = NetAddr.host(bind)
        return NetAddr.port(bind) != null &&
            (host.isEmpty() || host == NetAddr.WILDCARD_V4 || host == "::" || host == NetAddr.LOOPBACK_V4 || host.equals("localhost", ignoreCase = true))
    }

    /** The port of a listener on [bind] that the daemon's forward reaches, or null. */
    private fun reached(bind: String, udp: Boolean): ReachPort? =
        NetAddr.port(bind)?.takeIf { it in 1..65535 && reachableThroughLoopback(bind) }?.let { ReachPort(it, udp) }

    /** The app's own ports as listening now, as the daemon's forward reaches them, and remote management's. Pure, for tests. */
    fun ownPorts(binds: Map<TailnetListener, String>, others: List<String>): Set<ReachPort> =
        (binds.mapNotNull { (l, bind) -> reached(bind, l.udp) } + others.mapNotNull { reached(it, false) }).toSet() +
            ReachPort(Exposure.REMOTE_PORT)

    fun ownPorts(context: Context): Set<ReachPort> =
        ownPorts(TailnetListener.entries.associateWith { bind(context, it) }, otherBinds(context))

    /**
     * Reads the field: ports separated by commas or spaces, TCP unless written `udp:1234`
     * (`tcp:5555` is TCP too). A port in [own] is reported, not opened: the app's own listeners
     * and remote management have switches of their own. Pure, for tests.
     */
    fun parseExtra(text: String, own: Set<ReachPort> = emptySet()): ExtraPorts {
        val ports = ArrayList<ReachPort>()
        val bad = ArrayList<String>()
        val mine = ArrayList<String>()
        for (token in text.split(',', ' ', ';', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }) {
            val proto = token.substringBefore(':', "tcp").lowercase()
            val port = token.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
            val p = if (port == null || proto !in setOf("tcp", "udp")) null else ReachPort(port, udp = proto == "udp")
            when {
                p == null -> bad += token
                p in own -> mine += token
                else -> ports += p
            }
        }
        return ExtraPorts(ports.distinct(), bad, mine)
    }

    /** The field written back the one way: "5555, 8022, udp:1234"; what is not a port is kept as typed. */
    fun normalExtra(text: String): String {
        val read = parseExtra(text)
        return (read.ports.map { it.label } + read.bad).joinToString(", ")
    }

    /**
     * The policy's lines for the four proxies bound as [binds] (empty: off) with [open] opened,
     * the app's other listeners on [others], [strict] mode and its [extra] ports. Pure, for tests.
     *
     * A listener the daemon's forward cannot reach (off, a loopback alias, an interface's own
     * address) is not named either way. Two listeners on one port cannot both be bound; where an
     * opened one and a closed one name the same port, the closed one wins.
     */
    fun lines(
        binds: Map<TailnetListener, String>,
        open: Set<TailnetListener>,
        others: List<String> = emptyList(),
        strict: Boolean = false,
        extra: List<ReachPort> = emptyList(),
    ): List<String> {
        val opened = TailnetListener.entries.filter { it in open }.mapNotNull { reached(binds[it].orEmpty(), it.udp) }
        val closed = (TailnetListener.entries.filter { it !in open }.mapNotNull { reached(binds[it].orEmpty(), it.udp) } +
            others.mapNotNull { reached(it, false) }).distinct()
        if (!strict) return listOf("mode default") + closed.map { "close " + it.line }
        // A port of the app's own is its switch's, even where the field names it.
        val own = closed.toSet() + ReachPort(Exposure.REMOTE_PORT)
        return listOf("mode strict") + (opened + extra).distinct().filter { it !in own }.map { "open " + it.line }
    }

    /** The policy's file for the daemon of this app: beside tailscaled.sock. */
    fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * Writes the policy from the switches and the listeners' current addresses. False when it
     * could not be written: the daemon then keeps what it had, or — with no file at all — closes
     * the app's listeners at their default ports. Blocking: a file. Serialised: the service, the
     * settings and TailCat write it from their own threads.
     */
    @Synchronized
    fun sync(context: Context): Boolean {
        val strict = isStrict(context)
        val lines = lines(
            TailnetListener.entries.associateWith { bind(context, it) }, opened(context), otherBinds(context),
            strict, if (strict) parseExtra(extraText(context)).ports else emptyList(),
        )
        val text = "# TailSocks: what the tailnet reaches on this device's own addresses (core/TailnetReach.kt)\n" +
            lines.joinToString("\n", postfix = "\n")
        val target = file(context)
        return try {
            File(target.parentFile, OLD_FILE).delete()
            if (target.isFile && target.readText() == text) return true
            // Written beside and renamed over: the daemon never reads half a policy.
            val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(target) || run { tmp.delete(); false }
        } catch (e: Exception) {
            Log.w(TAG, "The policy could not be written: ${e.message}")
            false
        }
    }
}
