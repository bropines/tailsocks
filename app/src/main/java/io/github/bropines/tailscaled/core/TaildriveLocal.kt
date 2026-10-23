package io.github.bropines.tailscaled.core

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * The way the app's own Taildrive client reaches the daemon: the bridge's loopback endpoint
 * (appctr/drive_local.go), used as an HTTP proxy for `http://100.100.100.100:8080`.
 *
 * Behind it the bridge dials the unix socket the daemon serves Taildrive on — so Taildrive no
 * longer depends on the SOCKS5 port, its password or the mode the daemon runs in. Any app on the
 * device can connect to a loopback port, so each request carries the endpoint's token.
 */
object TaildriveLocal {
    /** Must equal DriveLocalHeader in appctr/drive_local.go. */
    const val HEADER = "X-TailSocks-Drive"

    /** Must equal DriveErrorHeader and DriveErrorNoDaemon there: the bridge had no daemon to reach. */
    private const val ERROR_HEADER = "X-TailSocks-Drive-Error"
    private const val NO_DAEMON = "no-daemon"

    class Endpoint(val proxy: Proxy, val token: String)

    @Volatile
    private var known: Endpoint? = null

    /**
     * The endpoint, started by the bridge on the first call and kept for the life of the process;
     * null where there is none — the bridge would not start it, or there is no bridge (a JVM
     * test) — and the client goes through the SOCKS5 port as it used to.
     */
    fun endpoint(): Endpoint? {
        known?.let { return it }
        return synchronized(this) {
            known ?: runCatching {
                val addr = appctr.Appctr.driveLocalAddr()
                val token = appctr.Appctr.driveLocalToken()
                val port = NetAddr.port(addr)
                if (addr.isNullOrBlank() || token.isNullOrBlank() || port == null) null
                else Endpoint(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)), token)
            }.getOrNull()?.also { known = it }
        }
    }

    /**
     * [conn]'s status code. The bridge's answer for a daemon it could not reach is thrown as the
     * refused connection it stands for — what the SOCKS5 port gave for a daemon that was not
     * running — and not passed off as the daemon's own 503.
     */
    fun status(conn: HttpURLConnection): Int {
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_UNAVAILABLE && conn.getHeaderField(ERROR_HEADER) == NO_DAEMON) {
            throw ConnectException("Taildrive: the daemon is not running")
        }
        return code
    }
}
