package io.github.bropines.tailscaled.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.ExitNodeCache
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.TailscaledService
import io.github.bropines.tailscaled.core.liveExitNodes
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the home-screen widgets in step with the app — the only code that writes their state.
 *
 * A pass builds each widget's model once (WidgetModels.kt) and repaints an instance only when
 * its serialized model differs from what it shows: Glance itself republishes identical
 * output on every update, so this comparison is what makes the service's 15-second tick
 * nearly free. Passes are serialized, and requests that arrive while one is queued fold into
 * it, so the tick, the service's state changes, receivers and taps never interleave.
 *
 * Triggers: the service's tick (screen on) and its state changes (start, stop, the card),
 * waking the screen while it runs, an exit-node choice, a profile switch or rename, a
 * language change, and the system's own update (placement, the 30-minute period, a locale
 * change), which repaints from the cache without asking the daemon.
 */
object WidgetUpdates {
    private const val TAG = "WidgetUpdates"

    /** Once in this long, with the screen on, a pass repaints even an unchanged widget. */
    private const val SAFETY_REPAINT_MS = 30 * 60_000L
    /** The exit-node list is read from the daemon at most this often, unless asked for. */
    private const val LIVE_LIST_MS = 60_000L

    private const val PREFS = "widgets"
    private const val KEY_PENDING = "pending_toggle"

    /** How much a pass may ask the daemon for the exit-node list. */
    enum class Live { NEVER, WHEN_DUE, NOW }

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()
    private val queued = AtomicBoolean(false)
    private val forceQueued = AtomicBoolean(false)

    // Guarded by [lock]. Empty after a process start, so the first pass repaints every
    // instance: whatever the launcher kept is replaced by the truth once per process.
    private val painted = HashMap<Int, String>()
    private var lastForcedAt = 0L
    private var lastLiveAt = 0L

    /** Asks for a pass soon; a pass already waiting absorbs this one. [force] repaints every instance. */
    fun request(context: Context, force: Boolean = false) {
        if (force) forceQueued.set(true)
        if (!queued.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    /** Runs a pass now, after any pass in progress. */
    suspend fun refresh(context: Context, force: Boolean = false, live: Live = Live.WHEN_DUE) {
        lock.withLock {
            queued.set(false)
            val forced = forceQueued.getAndSet(false) || force
            try {
                pass(context.applicationContext, forced, live)
            } catch (e: Exception) {
                Log.w(TAG, "Widget refresh failed: ${e.message}")
            }
        }
    }

    /**
     * The system's update for [receiver] (placement, the period, a locale change): a forced
     * repaint, with the broadcast held open until it is done. The exit-node list is read from
     * an attached daemon when due, so a widget just placed lists the tailnet's nodes at once
     * rather than whatever the cache held; with no daemon it comes from the cache.
     */
    internal fun repaintFromSystem(receiver: BroadcastReceiver, context: Context) {
        val result = receiver.goAsync()
        val app = context.applicationContext
        scope.launch {
            try { refresh(app, force = true, live = Live.WHEN_DUE) } finally { result.finish() }
        }
    }

    /**
     * A tap on Start/Stop: decides on what is happening now and records the move, so every
     * instance shows Starting…/Stopping… until the service gets there or the time is up.
     */
    internal fun beginToggle(context: Context): Boolean {
        val running = ProxyState.isActualRunning(context)
        val now = System.currentTimeMillis()
        val target = toggleTarget(running, nextPending(storedPending(context), running, now))
        storePending(context, PendingToggle(target, now + PENDING_TOGGLE_MS))
        // Nothing may come to clear it if the service never starts: look again when its time is up.
        scope.launch {
            delay(PENDING_TOGGLE_MS + 500)
            request(context)
        }
        return target
    }

    /** Drops a recorded move that could not be carried out. */
    internal fun cancelToggle(context: Context) = storePending(context, null)

    private fun storedPending(context: Context): PendingToggle? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, null)
            ?.let { runCatching { AppJson.decodeFromString(PendingToggle.serializer(), it) }.getOrNull() }

    private fun storePending(context: Context, pending: PendingToggle?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (pending == null) remove(KEY_PENDING)
            else putString(KEY_PENDING, AppJson.encodeToString(PendingToggle.serializer(), pending))
        }
    }

    private suspend fun pass(context: Context, force: Boolean, live: Live) {
        val manager = GlanceAppWidgetManager(context)
        val serviceIds = manager.getGlanceIds(ServiceToggleWidget::class.java)
        val exitIds = manager.getGlanceIds(ExitNodeToggleWidget::class.java)
        if (serviceIds.isEmpty() && exitIds.isEmpty()) return

        val now = System.currentTimeMillis()
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val forced = force || (interactive && now - lastForcedAt >= SAFETY_REPAINT_MS)
        if (forced) lastForcedAt = now

        val res = TailscaledService.localized(context)
        val running = ProxyState.isActualRunning(context)
        val attached = Appctr.isRunning()
        val account = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
        val exitId = prefs.getString("exit_node_id", "").orEmpty()
        val exitIp = prefs.getString("exit_node_ip", "").orEmpty()

        val stored = storedPending(context)
        val pending = nextPending(stored, running, now)
        if (pending != stored) storePending(context, pending)

        // The daemon's exit-node list: for the exit-node widget when due, and for the service
        // widget's label when the cache does not know the selected node's name.
        val cached = ExitNodeCache.read(prefs)
        val nameUnknown = exitId.isNotEmpty() && cached.none { it.id == exitId }
        val wantLive = attached && live != Live.NEVER && (
            (exitIds.isNotEmpty() && (live == Live.NOW || forced || now - lastLiveAt >= LIVE_LIST_MS)) ||
                (serviceIds.isNotEmpty() && nameUnknown)
            )
        val status = if (wantLive) readStatus() else null
        if (wantLive) lastLiveAt = now
        val liveNodes = status?.let { liveExitNodes(it) }
        liveNodes?.let { ExitNodeCache.replace(prefs, it) }
        val online = onlineById(status)
        val options = exitNodeOptions(
            liveNodes, cached,
            prefs.getString("last_exit_node_id", "").orEmpty(), prefs.getString("last_exit_node_ip", "").orEmpty()
        )
        val rows = exitRows(options, online, exitId, exitIp, limit = Int.MAX_VALUE)
        val exitName = selectedExitName(rows, exitIp)

        val locale = res.resources.configuration.locales[0].toLanguageTag()
        val night = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            (res.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else null
        val exitLabel = if (exitIp.isNotEmpty()) res.getString(R.string.widget_exit_node_format, exitName) else ""

        if (serviceIds.isNotEmpty()) {
            val card = TailscaledService.shownCardState()
            val backend = if (card == null && attached) runCatching { Appctr.getBackendState() }.getOrDefault("") else ""
            val widgetStatus = widgetStatus(running, pending, card, backend)
            val on = pending?.target ?: running
            val model = ServiceWidgetModel(
                on = on,
                status = widgetStatus,
                statusText = res.getString(statusRes(widgetStatus)),
                profile = account.name,
                exitLabel = exitLabel,
                tailnetIp = if (running && attached && widgetStatus == WidgetStatus.CONNECTED)
                    firstIpv4(runCatching { Appctr.getSelfIPs() }.getOrDefault("")) else "",
                toggleLabel = res.getString(if (on) R.string.widget_service_stop else R.string.widget_service_start),
                refreshLabel = res.getString(R.string.widget_refresh),
                copyLabel = res.getString(R.string.widget_copy_ip),
                locale = locale,
                night = night,
            )
            val encoded = encodeModel(model)
            val widget = ServiceToggleWidget()
            serviceIds.forEach { publish(context, manager, widget, it, encoded, forced) }
        }

        if (exitIds.isNotEmpty()) {
            val base = ExitWidgetModel(
                title = res.getString(R.string.widget_exit_node_title),
                subtitle = exitLabel.ifEmpty { res.getString(R.string.widget_exit_node_inactive) },
                active = exitIp.isNotEmpty(),
                directLabel = res.getString(R.string.widget_exit_direct),
                emptyText = res.getString(R.string.main_no_exit_nodes),
                refreshLabel = res.getString(R.string.widget_refresh),
                offlineLabel = res.getString(R.string.widget_offline),
                locale = locale,
                night = night,
            )
            val bestLabel = if (rows.isNotEmpty()) res.getString(R.string.exit_best_title) else ""
            val widget = ExitNodeToggleWidget()
            exitIds.forEach { id ->
                // Each instance has its own favourites, so its own model.
                val config = readConfig(context, id)
                val model = base.copy(
                    bestLabel = if (config.showBest) bestLabel else "",
                    rows = instanceRows(rows, config.favourites),
                    favourites = config.favourites,
                )
                publish(context, manager, widget, id, encodeModel(model), forced)
            }
        }
    }

    /**
     * Compare-and-set: writes [encoded] and repaints only when the instance shows something
     * else — or when [force]d, or when this process has not painted it yet.
     */
    private suspend fun publish(
        context: Context,
        manager: GlanceAppWidgetManager,
        widget: GlanceAppWidget,
        id: GlanceId,
        encoded: String,
        force: Boolean,
    ) {
        val key = manager.getAppWidgetId(id)
        val shown = painted[key]
        if (!force && shown == encoded) return
        var repaint = force || shown == null
        updateAppWidgetState(context, WidgetStateDef, id) { prefs: Preferences ->
            // Handing back the same preferences writes nothing.
            if (prefs[WidgetStateKeys.MODEL] == encoded) prefs
            else {
                repaint = true
                prefs.toMutablePreferences().apply { this[WidgetStateKeys.MODEL] = encoded }
            }
        }
        if (repaint) widget.update(context, id)
        painted[key] = encoded
    }

    private suspend fun readConfig(context: Context, id: GlanceId): ExitWidgetConfig =
        decodeConfig(runCatching { getAppWidgetState(context, WidgetStateDef, id)[WidgetStateKeys.CONFIG] }.getOrNull())

    /** Stores an exit-node widget's configuration and repaints it with the choice. */
    internal suspend fun saveConfig(context: Context, appWidgetId: Int, config: ExitWidgetConfig) {
        val app = context.applicationContext
        val id = GlanceAppWidgetManager(app).getGlanceIdBy(appWidgetId)
        updateAppWidgetState(app, WidgetStateDef, id) { prefs: Preferences ->
            prefs.toMutablePreferences().apply { this[WidgetStateKeys.CONFIG] = encodeConfig(config) }
        }
        refresh(app)
    }

    /**
     * The exit nodes a configuration screen offers, from the source the widget lists:
     * the daemon when this process is attached to one, otherwise the cache. Blocking.
     */
    internal fun exitNodeChoices(context: Context): List<ExitRow> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("appctr_${AccountManager.getActiveAccount(app).id}", Context.MODE_PRIVATE)
        val status = if (Appctr.isRunning()) readStatus() else null
        val live = status?.let { liveExitNodes(it) }
        live?.let { ExitNodeCache.replace(prefs, it) }
        val online = onlineById(status)
        val options = exitNodeOptions(
            live, ExitNodeCache.read(prefs),
            prefs.getString("last_exit_node_id", "").orEmpty(), prefs.getString("last_exit_node_ip", "").orEmpty()
        )
        return exitRows(options, online, "", "", limit = Int.MAX_VALUE)
    }

    /** An exit-node widget's configuration, for its configuration screen. */
    internal suspend fun loadConfig(context: Context, appWidgetId: Int): ExitWidgetConfig =
        readConfig(context.applicationContext, GlanceAppWidgetManager(context.applicationContext).getGlanceIdBy(appWidgetId))

    /** Which peers are online, by StableID; empty without a status. */
    private fun onlineById(status: StatusResponse?): Map<String, Boolean> =
        status?.peers?.values.orEmpty()
            .mapNotNull { p -> p.id?.takeIf { it.isNotEmpty() }?.let { it to (p.online == true) } }.toMap()

    private fun readStatus(): StatusResponse? = runCatching {
        val json = Appctr.getStatusFromAPI()
        if (json.isBlank() || json.startsWith("{\"Error\"")) null
        else AppJson.decodeFromString(StatusResponse.serializer(), json)
    }.getOrNull()

    private fun statusRes(status: WidgetStatus): Int = when (status) {
        WidgetStatus.STOPPED -> R.string.status_stopped
        WidgetStatus.RUNNING -> R.string.status_running
        WidgetStatus.CONNECTED -> R.string.notif_connected
        WidgetStatus.CONNECTING -> R.string.notif_connecting
        WidgetStatus.STARTING -> R.string.main_status_starting
        WidgetStatus.STOPPING -> R.string.notif_stopping
        WidgetStatus.RESTARTING -> R.string.notif_restarting
        WidgetStatus.RECONNECTING -> R.string.notif_reconnecting
        WidgetStatus.WAITING_FOR_NETWORK -> R.string.notif_waiting_network
        WidgetStatus.DEGRADED -> R.string.main_status_degraded
        WidgetStatus.NEEDS_LOGIN -> R.string.main_status_needs_login
        WidgetStatus.AWAITING_APPROVAL -> R.string.notif_awaiting_approval
        WidgetStatus.OS_REFUSED -> R.string.coordinator_os_refused_short
        WidgetStatus.CONNECTION_LOST -> R.string.notif_connection_lost
        WidgetStatus.ROOT_FAILED -> R.string.notif_root_failed
    }
}
