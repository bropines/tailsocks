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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one `tailcat forward` (github.com/tailscale/tailcat) child process per
 * [TailcatConnection]: each of its ports listens on 127.0.0.1 and is carried
 * to that connection's tailcat server over WireGuard, with no VpnService and
 * no Tailscale account. Independent of [TailscaledService] — either can run
 * without the other.
 *
 * tailcat is a separate executable (libtailcat.so, built by appctr/build.sh)
 * rather than part of the bridge, because it needs a newer tailscale.com than
 * the patched daemon. Its only state is a client key under files/tailcat,
 * which `forward` picks up by itself once it exists, for every connection.
 *
 * The service is foreground while at least one connection runs, behind one
 * notification for all of them, and stops when the last one does. A process
 * that exits on its own is not restarted: its connection shows why.
 */
class TailcatService : Service() {

    enum class State { STOPPED, RUNNING, EXITED }

    /** [detail]: what a running connection listens on, or why it is not running. */
    data class Status(val state: State = State.STOPPED, val detail: String = "")

    companion object {
        private const val TAG = "TailcatService"
        private const val NOTIF_ID = 3
        private const val CHANNEL_ID = "tailcat_channel"
        private const val ACTION_START = "io.github.bropines.tailscaled.TAILCAT_START"
        private const val ACTION_RESTART = "io.github.bropines.tailscaled.TAILCAT_RESTART"
        private const val ACTION_STOP = "io.github.bropines.tailscaled.TAILCAT_STOP"
        private const val ACTION_STOP_ALL = "io.github.bropines.tailscaled.TAILCAT_STOP_ALL"
        private const val EXTRA_ID = "id"
        private const val MAX_LOG_LINES = 500

        const val PREF_CLIENT_KEY = "tailcat_client_pubkey"

        /** tailcat's name for the client key that client modes load on their own. */
        private const val CLIENT_KEY_NAME = "client-default"

        private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
        /** Per connection id; a connection with no entry is stopped. */
        val statuses: StateFlow<Map<String, Status>> = _statuses.asStateFlow()

        private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        /** Each connection's own process output, newest last, for as long as this app process lives. */
        val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

        fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libtailcat.so")

        fun start(context: Context, id: String) = send(context, ACTION_START, id)

        /** Stops the connection if it runs and starts it again with its saved settings. */
        fun restart(context: Context, id: String) = send(context, ACTION_RESTART, id)

        fun stop(context: Context, id: String) = send(context, ACTION_STOP, id)

        /** Forgets a deleted connection's status and output. Call after stopping it. */
        fun forget(id: String) {
            _statuses.update { it - id }
            _logs.update { it - id }
        }

        private fun send(context: Context, action: String, id: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, TailcatService::class.java).setAction(action).putExtra(EXTRA_ID, id)
            )
        }

        /**
         * Creates the client key and returns its public half (`nodekey:…`), which
         * a server lists in `tailcat serve --allow`. Blocking; call off the main
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

        private fun setStatus(id: String, status: Status) {
            _statuses.update { it + (id to status) }
        }

        private fun appendLog(id: String, line: String) {
            _logs.update { it + (id to ((it[id] ?: emptyList()) + line).takeLast(MAX_LOG_LINES)) }
        }
    }

    private class Runner(val process: Process) {
        /** Set by a requested stop, so the exit is not reported as a failure. */
        @Volatile var stopping = false
    }

    /** Touched only on [worker]: starts, stops and exits are applied in order. */
    private val runners = mutableMapOf<String, Runner>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tailcat-worker") }
    /** Commands handed to [worker] and not yet applied; the service stays while any is queued. */
    private val pending = AtomicInteger()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must reach startForeground in time, even one that ends in a stop.
        pending.incrementAndGet()
        enterForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        worker.execute {
            try {
                when (intent?.action) {
                    ACTION_START -> if (id != null) startConnection(id)
                    ACTION_RESTART -> if (id != null) {
                        stopConnection(id, awaitExit = true)
                        startConnection(id)
                    }
                    ACTION_STOP -> if (id != null) stopConnection(id, awaitExit = false)
                    ACTION_STOP_ALL -> runners.keys.toList().forEach { stopConnection(it, awaitExit = false) }
                }
            } finally {
                pending.decrementAndGet()
            }
            refresh()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.execute {
            runners.forEach { (id, runner) ->
                runner.stopping = true
                runner.process.destroy()
                setStatus(id, Status())
            }
            runners.clear()
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startConnection(id: String) {
        if (runners.containsKey(id)) return
        val conn = TailcatConnections.get(this, id) ?: return
        val ports = TailcatConnections.parsePorts(conn.ports)
        // Another running connection on the same local port would make this
        // one's listen fail; say which instead of letting tailcat exit.
        val clash = TailcatConnections.clashes(this, conn.ports, except = id)
            .firstOrNull { (_, other) -> runners.containsKey(other.id) }
        val problem = when {
            !TailcatConnections.isValidAddress(conn.address) -> getString(R.string.tailcat_err_address)
            ports == null -> getString(R.string.tailcat_err_ports)
            clash != null -> getString(R.string.tailcat_err_port_clash, clash.first, clash.second.name)
            !binary(this).exists() -> getString(R.string.tailcat_err_binary)
            else -> null
        }
        if (problem != null) {
            appendLog(id, "# $problem")
            setStatus(id, Status(State.EXITED, problem))
            return
        }

        _logs.update { it - id }
        val proc = try {
            // --verbose: without it tailcat discards its diagnostics, among them
            // why a forwarded connection could not reach the server — the one
            // thing the output is for.
            processBuilder(this, "--verbose", "forward", conn.address.trim(), *ports!!.toTypedArray())
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "tailcat did not start for ${conn.name}", e)
            val reason = e.message ?: e.javaClass.simpleName
            appendLog(id, "# $reason")
            setStatus(id, Status(State.EXITED, reason))
            return
        }
        val runner = Runner(proc)
        runners[id] = runner
        setStatus(id, Status(State.RUNNING, TailcatConnections.summary(conn.ports)))

        Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { appendLog(id, it) }
            } catch (_: Exception) {
                // The stream closes when the process is destroyed.
            }
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            try {
                worker.execute { onExited(id, runner, code) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // The service was destroyed first; onDestroy already set the status.
            }
        }, "tailcat-${conn.name}").start()
    }

    private fun onExited(id: String, runner: Runner, code: Int) {
        // A restart may already have put a new process in this slot.
        if (runners[id] === runner) runners.remove(id)
        if (runner.stopping) {
            // Unless a restart has replaced it, or failed with a reason to show.
            if (!runners.containsKey(id) && _statuses.value[id]?.state == State.RUNNING) setStatus(id, Status())
        } else {
            // tailcat exits on its own only on a fatal error (a malformed
            // address, a port already taken); its last lines say which.
            Log.w(TAG, "tailcat for $id exited with $code")
            val reason = getString(R.string.tailcat_exited, code)
            appendLog(id, "# $reason")
            setStatus(id, Status(State.EXITED, reason))
        }
        refresh()
    }

    private fun stopConnection(id: String, awaitExit: Boolean) {
        val runner = runners[id] ?: return
        runner.stopping = true
        // Process.destroy() is SIGTERM on Android, and tailcat exits on it at once.
        runner.process.destroy()
        if (awaitExit) {
            // A restart binds the same ports again: wait until the old process
            // has let go of them. (waitFor with a timeout is API 26.)
            var waited = 0
            while (waited < 3000 && isAlive(runner.process)) {
                Thread.sleep(100)
                waited += 100
            }
            runners.remove(id)
        }
    }

    private fun isAlive(proc: Process) = try {
        proc.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /**
     * Redraws the notification, or leaves the foreground once nothing runs and
     * no command is queued — a start that arrives while the last connection is
     * exiting must not find the service gone.
     */
    private fun refresh() {
        if (runners.isEmpty() && pending.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            // startForeground again rather than notify(): it redraws the same
            // card, and keeps the service foreground in every order of events.
            enterForeground()
        }
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * One card for every connection: the running ones in the title and the
     * collapsed line, and one line each — failures included — when expanded.
     */
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tailcat", NotificationManager.IMPORTANCE_LOW))
        }
        val statuses = _statuses.value
        val connections = TailcatConnections.load(this).filter { statuses[it.id]?.state?.let { s -> s != State.STOPPED } == true }
        val running = connections.filter { statuses[it.id]?.state == State.RUNNING }

        fun ports(conn: TailcatConnection) = TailcatConnections.localPorts(conn.ports).joinToString(",") { ":$it" }
        val collapsed = if (running.isEmpty()) getString(R.string.tailcat_notif_starting)
                        else running.joinToString(" · ") { "${it.name} ${ports(it)}" }
        val style = NotificationCompat.InboxStyle()
        connections.forEach { conn ->
            val status = statuses[conn.id] ?: return@forEach
            style.addLine("${conn.name} — ${status.detail}")
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TailcatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, TailcatService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tailcat_notif_title, running.size))
            .setContentText(collapsed)
            .setStyle(style)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tailcat_stop_all), stopAll)
            .build()
    }
}
