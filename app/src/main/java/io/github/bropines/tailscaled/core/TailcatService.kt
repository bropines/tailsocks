package io.github.bropines.tailscaled.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import appctr.Appctr
import appctr.TailcatListener
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.ServeActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** How a tailcat connection is doing, as the bridge reports it. */
@Serializable
data class TailcatStatus(
    /** starting, forwarding, error; "failed" when it never started. */
    val state: String = "",
    val listening: List<String> = emptyList(),
    /** The SOCKS5 proxy's address, or "" without one. */
    val socks: String = "",
    val error: String = "",
    /** "direct", a relay's region code, or "" before the first probe. */
    val path: String = "",
    val latencyMs: Int = 0,
    val active: Int = 0,
    val served: Int = 0,
)

/** How the server is doing (TailcatServerStatusJSON); null in the flow when it is not running. */
@Serializable
data class TailcatServerStatus(
    /** starting, serving, error; "failed" when its settings kept it from starting. */
    val state: String = "",
    val error: String = "",
    val active: Int = 0,
    val served: Int = 0,
    /** Client keys that reached it in the last few minutes. */
    val clients: Int = 0,
)

/**
 * Keeps tailcat running (see appctr/tailcat.go): connections that forward
 * ports on 127.0.0.1 to tailcat servers over WireGuard, and may run a SOCKS5
 * proxy through them, and this device's own server (tailcat_server.go) — no
 * VpnService and no Tailscale account. It runs without [TailscaledService];
 * when that one starts it brings up whatever was switched on ([resume]), and
 * when it stops it stops them too, remembering they were on ([suspend]).
 *
 * The connections live in the bridge, in this process; the service keeps the
 * process in the foreground while any runs, behind one notification, and
 * tells the bridge when the network moves, which tailcat cannot see by
 * itself on Android. It stops when the last connection does.
 *
 * Based on the TailcatService of PR #9 by seffs, which ran the tailcat CLI as
 * a separate process.
 */
class TailcatService : Service() {

    companion object {
        private const val TAG = "TailcatService"
        private const val NOTIF_ID = 3
        private const val CHANNEL_ID = "tailcat_channel"
        private const val ACTION_START = "io.github.bropines.tailscaled.TAILCAT_START"
        private const val ACTION_RESTART = "io.github.bropines.tailscaled.TAILCAT_RESTART"
        private const val ACTION_STOP = "io.github.bropines.tailscaled.TAILCAT_STOP"
        private const val ACTION_STOP_ALL = "io.github.bropines.tailscaled.TAILCAT_STOP_ALL"
        private const val ACTION_SERVER_START = "io.github.bropines.tailscaled.TAILCAT_SERVER_START"
        private const val ACTION_SERVER_STOP = "io.github.bropines.tailscaled.TAILCAT_SERVER_STOP"
        private const val ACTION_RESUME = "io.github.bropines.tailscaled.TAILCAT_RESUME"
        private const val ACTION_SUSPEND = "io.github.bropines.tailscaled.TAILCAT_SUSPEND"
        private const val EXTRA_ID = "id"

        private val running = MutableStateFlow<Map<String, TailcatStatus>>(emptyMap())
        private val failures = MutableStateFlow<Map<String, String>>(emptyMap())

        /** Every running connection by id, plus "failed" ones that did not start. */
        val statuses: StateFlow<Map<String, TailcatStatus>> get() = merged.asStateFlow()
        private val merged = MutableStateFlow<Map<String, TailcatStatus>>(emptyMap())

        private val serverRunning = MutableStateFlow<TailcatServerStatus?>(null)
        private val serverFailure = MutableStateFlow<String?>(null)
        /** The server while it runs, or "failed" when it did not start; null otherwise. */
        val serverStatus: StateFlow<TailcatServerStatus?> get() = serverMerged.asStateFlow()
        private val serverMerged = MutableStateFlow<TailcatServerStatus?>(null)

        private fun publish() {
            merged.value = failures.value.mapValues { (_, why) -> TailcatStatus(state = "failed", error = why) } + running.value
            serverMerged.value = serverRunning.value ?: serverFailure.value?.let { TailcatServerStatus(state = "failed", error = it) }
        }

        /** Something of tailcat's runs in the bridge. */
        private fun anyRunning() = running.value.isNotEmpty() || serverRunning.value != null

        /** Re-reads the bridge's view; the bridge calls this on every change. */
        private val listener = object : TailcatListener {
            override fun onTailcatChanged(id: String?) = refreshStatuses()
        }

        fun refreshStatuses() {
            val json = runCatching { Appctr.tailcatStatusJSON() }.getOrDefault("{}")
            running.value = runCatching { AppJson.decodeFromString<Map<String, TailcatStatus>>(json) }.getOrDefault(emptyMap())
            val server = runCatching { Appctr.tailcatServerStatusJSON() }.getOrDefault("{}")
            serverRunning.value = if (server == "{}") null
                else runCatching { AppJson.decodeFromString<TailcatServerStatus>(server) }.getOrNull()
            publish()
        }

        /** Switches a connection on: it starts now, and with the core from then on. */
        fun start(context: Context, id: String) {
            TailcatConnections.setEnabled(context, id, true)
            send(context, ACTION_START, id)
        }

        /** Stops the connection if it runs and starts it with its saved settings. */
        fun restart(context: Context, id: String) = send(context, ACTION_RESTART, id)

        /** Switches a connection off, for good: the core no longer brings it up. */
        fun stop(context: Context, id: String) {
            TailcatConnections.setEnabled(context, id, false)
            send(context, ACTION_STOP, id)
        }

        /** Switches the server on, as [start] does a connection; also a restart with new settings. */
        fun startServer(context: Context) {
            TailcatServer.save(context, TailcatServer.load(context).copy(enabled = true))
            send(context, ACTION_SERVER_START, null)
        }

        fun stopServer(context: Context) {
            TailcatServer.save(context, TailcatServer.load(context).copy(enabled = false))
            send(context, ACTION_SERVER_STOP, null)
        }

        /** Switches everything off, connections and server. */
        fun stopAll(context: Context) {
            TailcatConnections.disableAll(context)
            TailcatServer.save(context, TailcatServer.load(context).copy(enabled = false))
            send(context, ACTION_STOP_ALL, null)
        }

        /**
         * Brings up what is switched on and not running; the core calls this as
         * it starts. Nothing happens when nothing is switched on.
         */
        fun resume(context: Context) {
            val any = TailcatConnections.load(context).any { it.enabled } || TailcatServer.load(context).enabled
            if (any) runCatching { send(context, ACTION_RESUME, null) }
                .onFailure { Log.w(TAG, "tailcat not resumed: ${it.message}") }
        }

        /** Stops everything and keeps the switches as they are; the core calls this as it stops. */
        fun suspend(context: Context) {
            if (anyRunning()) runCatching { send(context, ACTION_SUSPEND, null) }
                .onFailure { Log.w(TAG, "tailcat not suspended: ${it.message}") }
        }

        /** Forgets a connection's failure, after it was deleted or edited. */
        fun forget(id: String) {
            failures.update { it - id }
            publish()
        }

        fun forgetServerFailure() {
            serverFailure.value = null
            publish()
        }

        private fun send(context: Context, action: String, id: String?) {
            val intent = Intent(context, TailcatService::class.java).setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** Starts and stops happen in order, off the main thread: a stop waits for ports to be free. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tailcat-worker") }
    /** Commands handed to [worker] and not yet applied; the service stays while any is queued. */
    private val pending = AtomicInteger()
    private lateinit var connectivity: ConnectivityManager
    private var lastNetwork = ""

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = reportNetwork()
        override fun onLost(network: Network) = reportNetwork()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = reportNetwork()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            Appctr.setTailcatCacheDir(File(cacheDir, "tailcat").absolutePath)
            Appctr.setTailcatListener(listener)
        }
        // Before the first connection starts: the bridge's interface list may be
        // empty, or left over from a daemon run on another network.
        reportNetwork()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
    }

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
                        runCatching { Appctr.tailcatStop(id) }
                        startConnection(id)
                    }
                    ACTION_STOP -> if (id != null) runCatching { Appctr.tailcatStop(id) }
                    ACTION_STOP_ALL, ACTION_SUSPEND -> {
                        runCatching { Appctr.tailcatStopAll() }
                        runCatching { Appctr.tailcatServerStop() }
                    }
                    ACTION_SERVER_START -> {
                        runCatching { Appctr.tailcatServerStop() }
                        startServer()
                    }
                    ACTION_SERVER_STOP -> runCatching { Appctr.tailcatServerStop() }
                    ACTION_RESUME -> {
                        refreshStatuses()
                        TailcatConnections.load(this).filter { it.enabled && it.id !in running.value }.forEach { startConnection(it.id) }
                        if (TailcatServer.load(this).enabled && serverRunning.value == null) startServer()
                    }
                }
            } finally {
                pending.decrementAndGet()
            }
            refreshStatuses()
            refresh()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        worker.execute {
            runCatching { Appctr.tailcatStopAll() }
            runCatching { Appctr.tailcatServerStop() }
            refreshStatuses()
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startConnection(id: String) {
        val conn = TailcatConnections.get(this, id) ?: return
        failures.update { it - id }
        val runningIds = running.value.keys - id
        val why = when (val problem = TailcatConnections.problem(this, conn, running = runningIds)) {
            is TailcatConnections.Problem.Address -> getString(R.string.tailcat_err_address)
            is TailcatConnections.Problem.Ports -> getString(R.string.tailcat_err_ports, problem.detail)
            TailcatConnections.Problem.Socks -> getString(R.string.tailcat_err_socks)
            is TailcatConnections.Problem.Clash -> getString(R.string.tailcat_err_port_clash, problem.port, problem.name)
            null -> null
        } ?: runCatching {
            Appctr.tailcatStart(
                id, conn.name, conn.address.trim(), TailcatKey.private(this) ?: "", conn.ports,
                conn.socks.toLong(), conn.socksUser, conn.socksPass
            )
            null
        }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
        if (why != null) {
            Log.w(TAG, "tailcat connection ${conn.name} did not start: $why")
            failures.update { it + (id to why) }
        }
        publish()
    }

    private fun startServer() {
        serverFailure.value = null
        val config = TailcatServer.load(this)
        val keyFile = TailcatServer.keyFile(this)
        val why = when {
            !keyFile.exists() -> getString(R.string.tailcat_server_err_no_address)
            else -> when (val problem = TailcatServer.problem(config)) {
                is TailcatServer.Problem.Ports -> getString(R.string.tailcat_err_ports, problem.detail)
                is TailcatServer.Problem.Keys -> getString(R.string.tailcat_server_err_keys, problem.detail)
                TailcatServer.Problem.Nothing -> getString(R.string.tailcat_server_err_nothing)
                null -> null
            }
        } ?: runCatching {
            Appctr.tailcatServerStart(keyFile.absolutePath, config.ports, config.exitNode, config.allowed, config.allowAll)
            null
        }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
        if (why != null) {
            Log.w(TAG, "tailcat server did not start: $why")
            serverFailure.value = why
        }
        publish()
    }

    /** Tells the bridge which interfaces there are and which carries the default route. */
    private fun reportNetwork() {
        Thread {
            runCatching {
                Appctr.setDefaultRouteInterface(NetworkSnapshot.defaultRouteInterface(connectivity))
                val json = NetworkSnapshot.interfacesJson()
                if (json != lastNetwork) {
                    lastNetwork = json
                    Appctr.injectNetworkState(json)
                }
            }.onFailure { Log.w(TAG, "network state not reported: ${it.message}") }
        }.start()
    }

    /**
     * Redraws the notification, or leaves the foreground once nothing runs and
     * no command is queued — a start that arrives while the last connection
     * stops must not find the service gone.
     */
    private fun refresh() {
        if (!anyRunning() && pending.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
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

    /** One card for every connection: the running ones on the collapsed line, each on a line of its own when expanded. */
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.tailcat_title), NotificationManager.IMPORTANCE_LOW))
        }
        val statuses = running.value
        val connections = TailcatConnections.load(this).filter { it.id in statuses }
        val server = serverRunning.value
        fun ports(conn: TailcatConnection) = TailcatConnections.localPorts(conn).sorted().joinToString(",") { ":$it" }
        val parts = connections.map { "${it.name} ${ports(it)}" } + listOfNotNull(server?.let { getString(R.string.tailcat_server_title) })
        val collapsed = if (parts.isEmpty()) getString(R.string.tailcat_notif_starting) else parts.joinToString(" · ")
        val style = NotificationCompat.InboxStyle()
        connections.forEach { conn ->
            val st = statuses[conn.id] ?: return@forEach
            style.addLine("${conn.name} — ${stateLine(this, st)}")
        }
        server?.let { style.addLine("${getString(R.string.tailcat_server_title)} — ${serverStateLine(this, it)}") }
        val open = PendingIntent.getActivity(
            this, 0, ServeActivity.tailcatIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, TailcatService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tailcat_notif_title, connections.size + (if (server != null) 1 else 0)))
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

/** A connection's state in a few words, for the card and the notification. */
fun stateLine(context: Context, st: TailcatStatus): String = when (st.state) {
    "starting" -> context.getString(R.string.tailcat_state_starting)
    "forwarding" -> when {
        st.path == "direct" -> context.getString(R.string.tailcat_state_direct, st.latencyMs)
        st.path.isNotEmpty() -> context.getString(R.string.tailcat_state_relay, st.path, st.latencyMs)
        else -> context.getString(R.string.tailcat_state_forwarding)
    }
    "error", "failed" -> st.error.ifEmpty { context.getString(R.string.tailcat_state_error) }
    else -> context.getString(R.string.tailcat_state_stopped)
}

/** The server's state in a few words, for its card and the notification. */
fun serverStateLine(context: Context, st: TailcatServerStatus): String = when (st.state) {
    "starting" -> context.getString(R.string.tailcat_state_starting)
    "serving" -> if (st.clients > 0) context.getString(R.string.tailcat_server_state_clients, st.clients)
                 else context.getString(R.string.tailcat_server_state_serving)
    "error", "failed" -> st.error.ifEmpty { context.getString(R.string.tailcat_state_error) }
    else -> context.getString(R.string.tailcat_state_stopped)
}
