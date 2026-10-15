package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import java.io.File

/*
 * Settings → Local proxies → Reachable from the tailnet: which of the app's own listeners another
 * device of the tailnet may use through this device's tailnet address. Off for every one of them
 * unless the person turned it on here.
 *
 * Why it is needed: in userspace networking — proxy mode, the hev engine, Root Mode without its
 * interface — the daemon's netstack ends every connection to this device's tailnet address, and
 * upstream handed what nothing else took (Serve, the peer API) to the same port on 127.0.0.1.
 * Every loopback listener of the device was a service of the tailnet: the SOCKS5 proxy without a
 * password, the HTTP proxy, the DNS proxy, the Taildrive proxy. Patch 23
 * (cmd/tailscaled/android_loopback.go) refuses those connections unless the port is open:
 * remote management's while it is on (RemoteManage), and the ports [sync] lists in
 * files/tailnet-open, one "tcp 48115" or "udp 1053" a line. The daemon reads the list again at
 * most a second later, so a switch takes effect without a restart; [sync] runs wherever the
 * service builds its options and whenever a switch moves.
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

object TailnetReach {
    private const val TAG = "TailnetReach"

    /** Beside the daemon's socket; patch 23's tailnetOpenName. */
    const val FILE = "tailnet-open"

    fun isOpen(context: Context, listener: TailnetListener): Boolean = GlobalSettings.getBoolean(context, listener.key, false)

    /** Opens or closes [listener] to the tailnet; a running daemon follows within a second. Blocking: a file. */
    fun setOpen(context: Context, listener: TailnetListener, open: Boolean) {
        // The preferences answer the new value at once; the list is written from them.
        GlobalSettings.setBoolean(context, listener.key, open)
        sync(context)
    }

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

    /** Whether the Taildrive proxy of the active profile asks for a login. */
    fun driveLogin(context: Context): Boolean = DriveProxy.read(profile(context)).auth != null

    private fun profile(context: Context) =
        context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)

    /**
     * Whether a connection the daemon forwards to 127.0.0.1 reaches a listener on [bind]: one on
     * loopback itself or on every address. Another loopback address (127.x.y.z) or an interface's
     * own is not reached that way, so opening it would open nothing.
     */
    fun reachableThroughLoopback(bind: String): Boolean {
        val host = NetAddr.host(bind)
        return NetAddr.port(bind) != null &&
            (host.isEmpty() || host == NetAddr.WILDCARD_V4 || host == "::" || host == NetAddr.LOOPBACK_V4 || host.equals("localhost", ignoreCase = true))
    }

    /** The list's lines for listeners bound as [binds] (empty: off) with [open] opened. Pure, for tests. */
    fun lines(binds: Map<TailnetListener, String>, open: Set<TailnetListener>): List<String> =
        TailnetListener.entries
            .filter { it in open }
            .mapNotNull { l ->
                val bind = binds[l].orEmpty()
                val port = NetAddr.port(bind)?.takeIf { it in 1..65535 }
                if (bind.isBlank() || port == null || !reachableThroughLoopback(bind)) null
                else (if (l.udp) "udp" else "tcp") + " " + port
            }
            .distinct()

    /** The list's file for the daemon of this app: beside tailscaled.sock. */
    fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * Writes the list from the switches and the listeners' current addresses, and removes it when
     * nothing is open. False when it could not be written: the daemon then keeps what it had.
     * Blocking: a file.
     */
    fun sync(context: Context): Boolean {
        val open = opened(context)
        val lines = lines(TailnetListener.entries.associateWith { bind(context, it) }, open)
        val target = file(context)
        return try {
            if (lines.isEmpty()) {
                !target.exists() || target.delete()
            } else {
                val text = "# TailSocks: ports of this device's own listeners open to the tailnet (core/TailnetReach.kt)\n" +
                    lines.joinToString("\n", postfix = "\n")
                if (target.isFile && target.readText() == text) return true
                // Written beside and renamed over: the daemon never reads half a list.
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(text)
                tmp.renameTo(target) || run { tmp.delete(); false }
            }
        } catch (e: Exception) {
            Log.w(TAG, "The list could not be written: ${e.message}")
            false
        }
    }
}
