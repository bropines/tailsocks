package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.TailcatActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Runs `tailcat forward` (github.com/tailscale/tailcat) as a child process:
 * each configured port listens on 127.0.0.1 and is carried to a tailcat server
 * over WireGuard, with no VpnService and no Tailscale account. It is
 * independent of [TailscaledService] — either can run without the other.
 *
 * tailcat is a separate executable (libtailcat.so, built by appctr/build.sh)
 * rather than part of the bridge, because it needs a newer tailscale.com than
 * the patched daemon. Its only state is a client key under files/tailcat, which
 * `forward` picks up by itself once it exists.
 */
class TailcatService : Service() {

    enum class State { STOPPED, RUNNING }

    data class Status(val state: State = State.STOPPED, val detail: String = "")

    companion object {
        private const val TAG = "TailcatService"
        private const val NOTIF_ID = 3
        private const val CHANNEL_ID = "tailcat_channel"
        private const val ACTION_START = "io.github.bropines.tailscaled.TAILCAT_START"
        private const val ACTION_STOP = "io.github.bropines.tailscaled.TAILCAT_STOP"
        private const val MAX_LOG_LINES = 500

        const val PREF_ADDRESS = "tailcat_address"
        const val PREF_PORTS = "tailcat_ports"
        const val PREF_CLIENT_KEY = "tailcat_client_pubkey"

        /** tailcat's name for the client key that client modes load on their own. */
        private const val CLIENT_KEY_NAME = "client-default"

        private val IPV6_LITERAL = Regex("""^[0-9A-Fa-f:.]+$""")

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status.asStateFlow()

        private val _log = MutableStateFlow<List<String>>(emptyList())
        /** The process's own output, newest last, for as long as this app process lives. */
        val log: StateFlow<List<String>> = _log.asStateFlow()

        fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libtailcat.so")

        /** Splits the ports field into mappings; null when any of them is malformed. */
        fun parsePorts(text: String): List<String>? {
            val specs = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            return specs.takeIf { it.isNotEmpty() && it.all(::isForwardSpec) }
        }

        /**
         * The mappings `tailcat forward` accepts (parseForwardSpec in its
         * cmd/tailcat/forward.go), checked here so a typo never reaches Start:
         * `8080`; `18080:8080`, with local port 0 for one the OS picks; and
         * `3001:192.168.1.5:3001` or `5555:[fd7a::1]:5555` through an exit-node
         * server. The remote host must be an IP literal — tailcat resolves no
         * names here and exits on one.
         */
        private fun isForwardSpec(spec: String): Boolean {
            val colon = spec.indexOf(':')
            if (colon < 0) return isPort(spec)
            val local = spec.substring(0, colon)
            val target = spec.substring(colon + 1)
            if (local != "0" && !isPort(local)) return false
            if (isPort(target)) return true
            val portSep = target.lastIndexOf(':')
            if (portSep <= 0 || !isPort(target.substring(portSep + 1))) return false
            val host = target.substring(0, portSep)
            return if (host.startsWith('[') && host.endsWith(']')) {
                host.length > 2 && ':' in host && IPV6_LITERAL.matches(host.substring(1, host.length - 1))
            } else {
                isIpv4(host)
            }
        }

        private fun isPort(s: String) = s.isNotEmpty() && s.all(Char::isDigit) && s.length <= 5 && s.toInt() in 1..65535

        // Four decimal octets without leading zeros, as Go's netip requires.
        private fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 && parts.all {
                it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                    (it == "0" || !it.startsWith('0')) && it.toInt() <= 255
            }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TailcatService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TailcatService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Creates the client key and returns its public half (`nodekey:…`), which
         * a server lists in `tailcat serve --allow=`. Blocking; call off the main
         * thread. Refuses to overwrite a key that exists, as tailcat itself does.
         */
        fun generateClientKey(context: Context): Result<String> = runCatching {
            val proc = processBuilder(context, "genkey", "--client", "--key=$CLIENT_KEY_NAME")
                .redirectErrorStream(true)
                .start()
            // genkey is local work — a key pair and a file — so it never blocks
            // for long; its output ends when it exits.
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val key = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("nodekey:") }
            if (proc.exitValue() != 0 || key == null) error(out.trim().ifEmpty { "tailcat genkey exited with ${proc.exitValue()}" })
            GlobalSettings.setString(context, PREF_CLIENT_KEY, key)
            key
        }

        /**
         * A tailcat invocation with its config and cache kept in app storage.
         * Android sets no HOME for an app process, so without these tailcat has
         * nowhere to save or find the client key.
         */
        private fun processBuilder(context: Context, vararg args: String): ProcessBuilder {
            val home = File(context.filesDir, "tailcat").apply { mkdirs() }
            return ProcessBuilder(listOf(binary(context).absolutePath) + args).apply {
                environment()["HOME"] = home.absolutePath
                environment()["XDG_CONFIG_HOME"] = File(home, "config").absolutePath
                environment()["XDG_CACHE_HOME"] = File(context.cacheDir, "tailcat").absolutePath
                directory(home)
            }
        }

        private fun appendLog(line: String) {
            _log.update { (it + line).takeLast(MAX_LOG_LINES) }
        }
    }

    @Volatile private var process: Process? = null
    /** Set by a requested stop, so the reader thread can tell it from a crash. */
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must reach startForeground, even the one that ends in a stop.
        enterForeground(getString(R.string.tailcat_notif_starting))
        when (intent?.action) {
            ACTION_STOP -> stopForwarding()
            ACTION_START -> if (process == null) startForwarding()
            else -> if (process == null) finish("")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        process?.let { terminate(it) }
        process = null
        // A stop that carried a reason (finish) already set the status; keep it.
        if (_status.value.state == State.RUNNING) _status.value = Status()
        super.onDestroy()
    }

    private fun startForwarding() {
        val address = GlobalSettings.getString(this, PREF_ADDRESS, "").trim()
        val ports = parsePorts(GlobalSettings.getString(this, PREF_PORTS, ""))
        val problem = when {
            address.isEmpty() || address.any { it.isWhitespace() } -> getString(R.string.tailcat_err_address)
            ports == null -> getString(R.string.tailcat_err_ports)
            !binary(this).exists() -> getString(R.string.tailcat_err_binary)
            else -> null
        }
        if (problem != null) {
            finish(problem)
            return
        }

        stopping = false
        _log.value = emptyList()
        val proc = try {
            // --verbose: without it tailcat discards its diagnostics, among them
            // why a forwarded connection could not reach the server — the one
            // thing the output card is for.
            processBuilder(this, "--verbose", "forward", address, *ports!!.toTypedArray())
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "tailcat did not start", e)
            finish(e.message ?: e.javaClass.simpleName)
            return
        }
        process = proc

        val summary = ports!!.joinToString(", ") { "127.0.0.1:${it.substringBefore(':')}" }
        _status.value = Status(State.RUNNING, summary)
        enterForeground(getString(R.string.tailcat_notif_running, summary))

        Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { appendLog(it) }
            } catch (_: Exception) {
                // The stream closes when the process is destroyed.
            }
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            if (process === proc) process = null
            if (!stopping) {
                // tailcat exits on its own only on a fatal error (a malformed
                // address, a port already taken); its last lines say which.
                Log.w(TAG, "tailcat exited with $code")
                finish(getString(R.string.tailcat_exited, code))
            }
        }, "tailcat-output").start()
    }

    private fun stopForwarding() {
        stopping = true
        process?.let { terminate(it) }
        process = null
        finish("")
    }

    /** Leaves the foreground and stops; [detail] is what the screen shows. */
    private fun finish(detail: String) {
        _status.value = Status(State.STOPPED, detail)
        if (detail.isNotEmpty()) appendLog("# $detail")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Process.destroy() sends SIGTERM on Android, and tailcat exits on it at
     * once. Waited for briefly, so a quick stop-then-start does not find the
     * old process still holding its ports. (waitFor with a timeout is API 26.)
     */
    private fun terminate(proc: Process) {
        proc.destroy()
        repeat(30) {
            try {
                proc.exitValue()
                return
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(100)
            }
        }
        Log.w(TAG, "tailcat still running 3 s after SIGTERM")
    }

    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tailcat", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TailcatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TailcatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tailcat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tailcat_stop), stop)
            .build()
    }
}
