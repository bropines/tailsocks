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

/**
 * Keeps tailcat connections running (see appctr/tailcat.go): each forwards
 * ports on 127.0.0.1 to a tailcat server over WireGuard, and may run a SOCKS5
 * proxy through it, with no VpnService and no Tailscale account. Independent of [TailscaledService] — either runs
 * without the other.
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
        private const val EXTRA_ID = "id"

        private val running = MutableStateFlow<Map<String, TailcatStatus>>(emptyMap())
        private val failures = MutableStateFlow<Map<String, String>>(emptyMap())

        /** Every running connection by id, plus "failed" ones that did not start. */
        val statuses: StateFlow<Map<String, TailcatStatus>> get() = merged.asStateFlow()
        private val merged = MutableStateFlow<Map<String, TailcatStatus>>(emptyMap())

        private fun publish() {
            merged.value = failures.value.mapValues { (_, why) -> TailcatStatus(state = "failed", error = why) } + running.value
        }

        /** Re-reads the bridge's view; the bridge calls this on every change. */
        private val listener = object : TailcatListener {
            override fun onTailcatChanged(id: String?) = refreshStatuses()
        }

        fun refreshStatuses() {
            val json = runCatching { Appctr.tailcatStatusJSON() }.getOrDefault("{}")
            running.value = runCatching { AppJson.decodeFromString<Map<String, TailcatStatus>>(json) }.getOrDefault(emptyMap())
            publish()
        }

        fun start(context: Context, id: String) = send(context, ACTION_START, id)

        /** Stops the connection if it runs and starts it with its saved settings. */
        fun restart(context: Context, id: String) = send(context, ACTION_RESTART, id)

        fun stop(context: Context, id: String) = send(context, ACTION_STOP, id)

        fun stopAll(context: Context) = send(context, ACTION_STOP_ALL, null)

        /** Forgets a connection's failure, after it was deleted or edited. */
        fun forget(id: String) {
            failures.update { it - id }
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
                    ACTION_STOP_ALL -> runCatching { Appctr.tailcatStopAll() }
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
        if (running.value.isEmpty() && pending.get() == 0) {
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
        fun ports(conn: TailcatConnection) = TailcatConnections.localPorts(conn).sorted().joinToString(",") { ":$it" }
        val collapsed = if (connections.isEmpty()) getString(R.string.tailcat_notif_starting)
                        else connections.joinToString(" · ") { "${it.name} ${ports(it)}" }
        val style = NotificationCompat.InboxStyle()
        connections.forEach { conn ->
            val st = statuses[conn.id] ?: return@forEach
            style.addLine("${conn.name} — ${stateLine(this, st)}")
        }
        val open = PendingIntent.getActivity(
            this, 0, ServeActivity.tailcatIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, TailcatService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tailcat_notif_title, connections.size))
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
