package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ----------------------------------------------------------------
// Action Parameter Keys
// ----------------------------------------------------------------
val paramExitNodeId = ActionParameters.Key<String>("exit_node_id")
val paramExitNodeIp = ActionParameters.Key<String>("exit_node_ip")

// ----------------------------------------------------------------
// Helpers for widget state
// ----------------------------------------------------------------

/** Build fresh live state from the daemon and account prefs */
private fun liveState(context: Context): Pair<Boolean, String> {
    val isRunning = ProxyState.isActualRunning()
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
    val exitNode = prefs.getString("exit_node_ip", "") ?: ""
    return Pair(isRunning, exitNode)
}

/**
 * The exit nodes to list: the daemon's when it answers, which then replace the cache —
 * a node gone from the tailnet leaves the list — otherwise the cached ones, and with none
 * of those the last node in use. Entries without a StableID are never listed.
 */
fun getAvailableExitNodes(context: Context): List<ExitNodeOption> {
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)

    if (ProxyState.isActualRunning()) {
        val live = runCatching {
            val json = appctr.Appctr.getStatusFromAPI()
            if (json.isEmpty() || json.startsWith("Error") || json.contains("\"Error\"")) null
            else liveExitNodes(AppJson.decodeFromString<StatusResponse>(json))
        }.getOrNull()
        if (live != null) {
            ExitNodeCache.replace(prefs, live)
            return live
        }
    }
    val cached = ExitNodeCache.read(prefs)
    if (cached.isNotEmpty()) return cached
    val lastIp = prefs.getString("last_exit_node_ip", "").orEmpty()
    val lastId = prefs.getString("last_exit_node_id", "").orEmpty()
    return if (lastIp.isNotEmpty() && lastId.isNotEmpty()) listOf(ExitNodeOption(lastId, lastIp, lastIp)) else emptyList()
}

/**
 * Write widget DataStore state and trigger render for ONE glanceId.
 *
 * Returns whether the widget was repainted, and does neither when it already
 * holds exactly this. The service refreshes the widgets every 15 seconds for as
 * long as it runs, and a widget saying "● Running" over a steady connection was
 * written and fully re-rendered 5,760 times a day to say it again: a DataStore
 * write and a RemoteViews round trip to the launcher each time, for two lines
 * that had not changed. The pending flag a tap sets is part of the comparison,
 * so the pass that clears it still repaints.
 */
private suspend fun pushState(
    context: Context,
    glanceId: GlanceId,
    widget: GlanceAppWidget,
    isRunning: Boolean,
    isPending: Boolean,
    profileName: String,
    exitNode: String
): Boolean {
    if (shows(context, glanceId, isRunning, isPending, profileName, exitNode)) return false
    updateAppWidgetState(context, WidgetStateDef, glanceId) { prefs ->
        prefs.toMutablePreferences().apply {
            this[WidgetStateKeys.IS_RUNNING]    = isRunning
            this[WidgetStateKeys.IS_PENDING]    = isPending
            this[WidgetStateKeys.PROFILE_NAME]  = profileName
            this[WidgetStateKeys.EXIT_NODE]     = exitNode
        }
    }
    widget.update(context, glanceId)
    return true
}

/**
 * Whether [glanceId] already holds exactly this state.
 *
 * False when it holds none yet — a widget that has just been placed — so the
 * first pass writes it, and false when the state cannot be read at all, so a
 * failure here costs a render rather than leaving the widget stale.
 */
private suspend fun shows(
    context: Context,
    glanceId: GlanceId,
    isRunning: Boolean,
    isPending: Boolean,
    profileName: String,
    exitNode: String
): Boolean {
    val shown = runCatching { getAppWidgetState(context, WidgetStateDef, glanceId) }.getOrNull()
        ?: return false
    return shown[WidgetStateKeys.IS_RUNNING] == isRunning &&
        shown[WidgetStateKeys.IS_PENDING] == isPending &&
        shown[WidgetStateKeys.PROFILE_NAME] == profileName &&
        shown[WidgetStateKeys.EXIT_NODE] == exitNode
}

/**
 * Refresh ALL instances of ServiceToggleWidget & ExitNodeToggleWidget with real live state.
 *
 * Returns whether anything was repainted. The placed instances are looked up
 * first, because a phone with no widget of ours on its home screen otherwise
 * paid for a status read, a preferences read and the account lookup on every
 * refresh to paint nothing.
 */
suspend fun refreshAllInstances(context: Context): Boolean {
    val manager = GlanceAppWidgetManager(context)
    val serviceIds = manager.getGlanceIds(ServiceToggleWidget::class.java)
    val exitNodeIds = manager.getGlanceIds(ExitNodeToggleWidget::class.java)
    if (serviceIds.isEmpty() && exitNodeIds.isEmpty()) return false

    val (isRunning, exitNode) = liveState(context)
    val profileName = AccountManager.getActiveAccount(context).name
    val serviceWidget = ServiceToggleWidget()
    val exitNodeWidget = ExitNodeToggleWidget()

    var painted = false
    for (id in serviceIds) {
        if (pushState(context, id, serviceWidget, isRunning, false, profileName, exitNode)) painted = true
    }
    for (id in exitNodeIds) {
        if (pushState(context, id, exitNodeWidget, isRunning, false, profileName, exitNode)) painted = true
    }
    return painted
}

/** Fire ACTION_APPWIDGET_UPDATE broadcast directly to registered receivers so MIUI delivers it */
fun forceAppWidgetUpdate(context: Context) {
    val appContext = context.applicationContext
    val awm = AppWidgetManager.getInstance(appContext)
    val receivers = listOf(
        ServiceToggleWidgetReceiver::class.java,
        ExitNodeToggleWidgetReceiver::class.java
    )
    for (cls in receivers) {
        val ids = awm.getAppWidgetIds(ComponentName(appContext, cls))
        if (ids.isNotEmpty()) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = ComponentName(appContext, cls)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            appContext.sendBroadcast(intent)
        }
    }
}

/**
 * One scope for the widget refreshes rather than one per call: updateAllWidgets
 * is driven by the service's 15-second tick, so the old per-call CoroutineScope
 * was an allocation, and a dispatcher's worth of bookkeeping, on every tick of
 * the session.
 */
private val widgetRefreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * Repaints the widgets with the live state, and asks the system to deliver the
 * new views only when something was really painted.
 *
 * The delivery broadcast makes every one of our receivers render again, so sent
 * on a tick that changed nothing it was a second full pass per instance to
 * deliver what the launcher already had.
 */
fun updateAllWidgets(context: Context) {
    val appContext = context.applicationContext
    widgetRefreshScope.launch {
        if (refreshAllInstances(appContext)) forceAppWidgetUpdate(appContext)
    }
}

// ----------------------------------------------------------------
// TailSocks Service Switcher Widget (2×2)
// ----------------------------------------------------------------
class ServiceToggleWidget : GlanceAppWidget() {

    override val stateDefinition = WidgetStateDef

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs       = currentState<Preferences>()
                val actualRunning = ProxyState.isActualRunning()
                val isRunning   = prefs[WidgetStateKeys.IS_RUNNING]   ?: actualRunning
                val isPending   = prefs[WidgetStateKeys.IS_PENDING]   ?: false
                val profileName = prefs[WidgetStateKeys.PROFILE_NAME] ?: AccountManager.getActiveAccount(context).name
                val exitNode    = prefs[WidgetStateKeys.EXIT_NODE]    ?: ""

                val statusText = when {
                    isPending && !isRunning -> "○ Stopping…"
                    isPending &&  isRunning -> "● Starting…"
                    isRunning               -> "● " + context.getString(R.string.status_running)
                    else                    -> "○ " + context.getString(R.string.status_stopped)
                }

                val mainIntent = Intent(LocalContext.current, MainActivity::class.java)

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .clickable(actionStartActivity(mainIntent)),
                    horizontalAlignment = Alignment.Horizontal.Start
                ) {
                    // Header Row with title & refresh button
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = "TailSocks",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = profileName,
                                style = TextStyle(
                                    color = GlanceTheme.colors.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                        Button(
                            text = "↻",
                            onClick = actionRunCallback<RefreshAllActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer
                            ),
                            modifier = GlanceModifier.height(34.dp)
                        )
                    }

                    if (exitNode.isNotEmpty()) {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            text = context.getString(R.string.widget_exit_node_format, exitNode),
                            style = TextStyle(
                                color = GlanceTheme.colors.tertiary,
                                fontSize = 13.sp
                            )
                        )
                    }

                    Spacer(GlanceModifier.height(4.dp))

                    Text(
                        text = statusText,
                        style = TextStyle(
                            color = if (isRunning || isPending) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Spacer(GlanceModifier.defaultWeight())

                    Button(
                        text = if (isRunning || isPending)
                            context.getString(R.string.widget_service_stop)
                        else
                            context.getString(R.string.widget_service_start),
                        onClick = actionRunCallback<ToggleServiceActionCallback>(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isRunning || isPending)
                                GlanceTheme.colors.error
                            else
                                GlanceTheme.colors.primary,
                            contentColor = if (isRunning || isPending)
                                GlanceTheme.colors.onError
                            else
                                GlanceTheme.colors.onPrimary
                        ),
                        modifier = GlanceModifier.fillMaxWidth().height(44.dp)
                    )
                }
            }
        }
    }
}

class ServiceToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServiceToggleWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        CoroutineScope(Dispatchers.IO).launch {
            refreshAllInstances(context)
        }
    }
}

// ----------------------------------------------------------------
// Vertical Exit Node Selector Widget (2×3 / 2×4)
// ----------------------------------------------------------------
class ExitNodeToggleWidget : GlanceAppWidget() {

    override val stateDefinition = WidgetStateDef

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs        = currentState<Preferences>()
                val activeAccount = AccountManager.getActiveAccount(context)
                val profilePrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
                val exitNodeIp   = prefs[WidgetStateKeys.EXIT_NODE] ?: profilePrefs.getString("exit_node_ip", "") ?: ""
                val exitNodeId   = profilePrefs.getString("exit_node_id", "") ?: ""

                val availableNodes = getAvailableExitNodes(context)
                val activeNodeName = availableNodes.find { it.ip == exitNodeIp || (it.id.isNotEmpty() && it.id == exitNodeId) }?.name ?: exitNodeIp
                val mainIntent = Intent(LocalContext.current, MainActivity::class.java)

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .clickable(actionStartActivity(mainIntent)),
                    horizontalAlignment = Alignment.Horizontal.Start
                ) {
                    // Header Row with Title, Active Subtitle & Refresh Button
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = context.getString(R.string.widget_exit_node_title),
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = if (exitNodeIp.isNotEmpty())
                                    context.getString(R.string.widget_exit_node_format, activeNodeName)
                                else
                                    context.getString(R.string.widget_exit_node_inactive),
                                style = TextStyle(
                                    color = if (exitNodeIp.isNotEmpty()) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }

                        Button(
                            text = "↻",
                            onClick = actionRunCallback<RefreshAllActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer
                            ),
                            modifier = GlanceModifier.height(34.dp)
                        )
                    }

                    Spacer(GlanceModifier.height(10.dp))

                    // Option 0: Disable Exit Node (Direct Traffic)
                    val isDirectActive = exitNodeIp.isEmpty()
                    Button(
                        text = if (isDirectActive) "● Direct / Off" else "○ Direct / Off",
                        onClick = actionRunCallback<SelectExitNodeActionCallback>(
                            actionParametersOf(paramExitNodeId to "", paramExitNodeIp to "")
                        ),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isDirectActive)
                                GlanceTheme.colors.secondaryContainer
                            else
                                GlanceTheme.colors.surfaceVariant,
                            contentColor = if (isDirectActive)
                                GlanceTheme.colors.onSecondaryContainer
                            else
                                GlanceTheme.colors.onSurfaceVariant
                        ),
                        modifier = GlanceModifier.fillMaxWidth().height(38.dp)
                    )

                    Spacer(GlanceModifier.height(6.dp))

                    // Option 1: the node Tailscale recommends. Asked when tapped, not when
                    // drawn: a widget is rendered on its own schedule and the answer moves.
                    if (availableNodes.isNotEmpty()) {
                        Button(
                            text = "★ " + context.getString(R.string.exit_best_title),
                            onClick = actionRunCallback<SelectBestExitNodeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.surfaceVariant,
                                contentColor = GlanceTheme.colors.onSurfaceVariant
                            ),
                            modifier = GlanceModifier.fillMaxWidth().height(38.dp)
                        )
                        Spacer(GlanceModifier.height(6.dp))
                    }

                    // Exit Node Items (up to 6 items in vertical list)
                    if (availableNodes.isEmpty()) {
                        Text(
                            text = context.getString(R.string.main_no_exit_nodes),
                            style = TextStyle(
                                color = GlanceTheme.colors.outline,
                                fontSize = 12.sp
                            ),
                            modifier = GlanceModifier.padding(vertical = 8.dp)
                        )
                    } else {
                        availableNodes.take(6).forEach { node ->
                            val isSelected = (node.ip == exitNodeIp) || (node.id.isNotEmpty() && node.id == exitNodeId)
                            val labelText = if (isSelected) "● ${node.name}" else "○ ${node.name}"

                            Button(
                                text = labelText,
                                onClick = actionRunCallback<SelectExitNodeActionCallback>(
                                    actionParametersOf(paramExitNodeId to node.id, paramExitNodeIp to node.ip)
                                ),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (isSelected)
                                        GlanceTheme.colors.primary
                                    else
                                        GlanceTheme.colors.secondaryContainer,
                                    contentColor = if (isSelected)
                                        GlanceTheme.colors.onPrimary
                                    else
                                        GlanceTheme.colors.onSecondaryContainer
                                ),
                                modifier = GlanceModifier.fillMaxWidth().height(38.dp)
                            )
                            Spacer(GlanceModifier.height(6.dp))
                        }
                    }

                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

class ExitNodeToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExitNodeToggleWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        CoroutineScope(Dispatchers.IO).launch {
            refreshAllInstances(context)
        }
    }
}

// ----------------------------------------------------------------
// Action Callbacks
// ----------------------------------------------------------------

class RefreshAllActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        refreshAllInstances(context)
        forceAppWidgetUpdate(context)
        if (ProxyState.isActualRunning()) {
            delay(400)
            refreshAllInstances(context)
            forceAppWidgetUpdate(context)
        }
    }
}

class ToggleServiceActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val actualRunning = ProxyState.isActualRunning()
        val prefs = getAppWidgetState(context, WidgetStateDef, glanceId)
        val widgetShownRunning = prefs[WidgetStateKeys.IS_RUNNING] ?: actualRunning

        val activeAccount = AccountManager.getActiveAccount(context)
        val exitNode = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            .getString("exit_node_ip", "") ?: ""

        // MISMATCH GUARD:
        if (widgetShownRunning != actualRunning) {
            pushState(
                context, glanceId, ServiceToggleWidget(),
                isRunning = actualRunning,
                isPending = false,
                profileName = activeAccount.name,
                exitNode = exitNode
            )
            return
        }

        val shouldStop = actualRunning

        // STEP 1: Immediately push optimistic pending state to DataStore & re-render
        pushState(
            context, glanceId, ServiceToggleWidget(),
            isRunning = !shouldStop,
            isPending = true,
            profileName = activeAccount.name,
            exitNode = exitNode
        )

        ProxyState.setUserState(context, !shouldStop)

        // STEP 2: Send exact Intent based on ACTUAL status
        val intent = Intent(context, TailscaledService::class.java).apply {
            action = if (shouldStop) "STOP_ACTION" else "START_ACTION"
        }
        try {
            if (shouldStop) {
                context.startService(intent)
            } else {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // STEP 3: Wait 1.2s for daemon state transition, then update DataStore with real state
        delay(1200)
        val realRunning1 = ProxyState.isActualRunning()
        pushState(
            context, glanceId, ServiceToggleWidget(),
            isRunning = realRunning1,
            isPending = false,
            profileName = activeAccount.name,
            exitNode = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
                .getString("exit_node_ip", "") ?: ""
        )

        // STEP 4: Secondary check after 2.5s for slow daemon startup/shutdown
        delay(2500)
        refreshAllInstances(context)
        forceAppWidgetUpdate(context)
    }
}

class SelectExitNodeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val targetId = parameters[paramExitNodeId] ?: ""
        val targetIp = parameters[paramExitNodeIp] ?: ""

        val result = withContext(Dispatchers.IO) { ExitNodeApply.select(context, targetId, targetIp) }
        val message = when (result) {
            ExitNodeApply.Result.Applied ->
                if (targetIp.isEmpty()) context.getString(R.string.widget_toast_exit_disabled)
                else context.getString(R.string.widget_toast_exit_routing_format, targetIp)
            ExitNodeApply.Result.Incomplete -> context.getString(R.string.main_no_exit_nodes)
            is ExitNodeApply.Result.Refused -> context.getString(R.string.settings_local_api_error_format, result.message)
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * "Best exit node" on the widget: asks the daemon for its recommendation and selects that
 * node exactly as its own button would; with none to be had, a toast says why.
 */
class SelectBestExitNodeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (val best = withContext(Dispatchers.IO) { ExitNodeSuggestion.fetch() }) {
            is ExitNodeSuggestion.Outcome.Suggested -> SelectExitNodeActionCallback().onAction(
                context, glanceId, actionParametersOf(paramExitNodeId to best.id, paramExitNodeIp to best.ip)
            )
            is ExitNodeSuggestion.Outcome.Unavailable -> withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(bestExitNodeReasonRes(best.reason)), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
