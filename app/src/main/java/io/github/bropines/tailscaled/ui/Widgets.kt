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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ----------------------------------------------------------------
// Helpers for widget state
// ----------------------------------------------------------------

private fun readCurrentState(prefs: Preferences): Triple<Boolean, Boolean, String> {
    val isRunning = prefs[WidgetStateKeys.IS_RUNNING] ?: false
    val isPending = prefs[WidgetStateKeys.IS_PENDING] ?: false
    val exitNode  = prefs[WidgetStateKeys.EXIT_NODE]  ?: ""
    return Triple(isRunning, isPending, exitNode)
}

/** Build fresh state from the live daemon / preferences */
private fun liveState(context: Context): Pair<Boolean, String> {
    val isRunning = ProxyState.isActualRunning()
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
    val exitNode = prefs.getString("exit_node_ip", "") ?: ""
    return Pair(isRunning, exitNode)
}

/** Write widget DataStore state and force re-render for ONE glanceId. */
private suspend fun pushState(
    context: Context,
    glanceId: GlanceId,
    widget: GlanceAppWidget,
    isRunning: Boolean,
    isPending: Boolean,
    profileName: String,
    exitNode: String
) {
    updateAppWidgetState(context, WidgetStateDef, glanceId) { prefs ->
        prefs.toMutablePreferences().apply {
            this[WidgetStateKeys.IS_RUNNING]    = isRunning
            this[WidgetStateKeys.IS_PENDING]    = isPending
            this[WidgetStateKeys.PROFILE_NAME]  = profileName
            this[WidgetStateKeys.EXIT_NODE]     = exitNode
        }
    }
    widget.update(context, glanceId)
}

/** Refresh ALL instances of a widget class with real live state */
private suspend fun refreshAllInstances(context: Context, widget: GlanceAppWidget) {
    val (isRunning, exitNode) = liveState(context)
    val activeAccount = AccountManager.getActiveAccount(context)
    for (id in androidx.glance.appwidget.GlanceAppWidgetManager(context).getGlanceIds(widget::class.java)) {
        pushState(context, id, widget, isRunning, false, activeAccount.name, exitNode)
    }
}

/** Fire ACTION_APPWIDGET_UPDATE broadcast for each receiver so MIUI delivers it */
fun forceAppWidgetUpdate(context: Context) {
    val appContext = context.applicationContext
    val awm = AppWidgetManager.getInstance(appContext)
    val receiverClasses = listOf(
        ServiceToggleWidgetReceiver::class.java,
        ExitNodeToggleWidgetReceiver::class.java,
        StatsWidgetReceiver::class.java,
        ServeWidgetReceiver::class.java
    )
    for (cls in receiverClasses) {
        val ids = awm.getAppWidgetIds(ComponentName(appContext, cls))
        if (ids.isEmpty()) continue
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            component = ComponentName(appContext, cls)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        appContext.sendBroadcast(intent)
    }
}

/** Convenience: refresh all four widget types */
suspend fun updateAllWidgetsNow(context: Context) {
    try {
        refreshAllInstances(context, ServiceToggleWidget())
        refreshAllInstances(context, ExitNodeToggleWidget())
        refreshAllInstances(context, StatsWidget())
        refreshAllInstances(context, ServeWidget())
    } catch (e: Exception) { e.printStackTrace() }
}

fun updateAllWidgets(context: Context) {
    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        updateAllWidgetsNow(appContext)
        forceAppWidgetUpdate(appContext)
    }
}

// ----------------------------------------------------------------
// Widget I: Service Toggle (2×2)
// TailSocks / Profile / ExitNode? / Status
// [    Start / Stop    ]
// Background tap → open app
// ----------------------------------------------------------------
class ServiceToggleWidget : GlanceAppWidget() {

    override val stateDefinition = WidgetStateDef

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                // Read cached state from DataStore — written synchronously via pushState()
                val prefs       = currentState<Preferences>()
                val isRunning   = prefs[WidgetStateKeys.IS_RUNNING]   ?: ProxyState.isActualRunning()
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
                        // Background tap → open app
                        .clickable(actionStartActivity(mainIntent)),
                    horizontalAlignment = Alignment.Horizontal.Start
                ) {
                    // Title
                    Text(
                        text = "TailSocks",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.height(2.dp))

                    // Profile name
                    Text(
                        text = profileName,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    // Exit node (only if active)
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

                    // Status
                    Text(
                        text = statusText,
                        style = TextStyle(
                            color = if (isRunning || isPending) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Spacer(GlanceModifier.defaultWeight())

                    // Toggle button
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
            refreshAllInstances(context, ServiceToggleWidget())
        }
    }
}

// ----------------------------------------------------------------
// Widget II: Exit Node Toggle (2×1)
// ----------------------------------------------------------------
class ExitNodeToggleWidget : GlanceAppWidget() {

    override val stateDefinition = WidgetStateDef

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val activeAccount = AccountManager.getActiveAccount(context)
        val sharedPrefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp = sharedPrefs.getString("exit_node_ip", "") ?: ""
        val isActive = exitNodeIp.isNotEmpty()

        provideContent {
            GlanceTheme {
                val mainIntent = Intent(LocalContext.current, MainActivity::class.java)
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionStartActivity(mainIntent)),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                ) {
                    Text(context.getString(R.string.widget_exit_node_title),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = if (isActive) exitNodeIp else context.getString(R.string.widget_exit_node_inactive),
                        style = TextStyle(
                            color = if (isActive) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                            fontSize = 15.sp))
                    Spacer(GlanceModifier.defaultWeight())
                    Button(
                        text = if (isActive) context.getString(R.string.widget_exit_node_disable) else context.getString(R.string.widget_exit_node_enable),
                        onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isActive) GlanceTheme.colors.errorContainer else GlanceTheme.colors.primary,
                            contentColor = if (isActive) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.onPrimary),
                        modifier = GlanceModifier.fillMaxWidth().height(44.dp)
                    )
                }
            }
        }
    }
}

class ExitNodeToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExitNodeToggleWidget()
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        CoroutineScope(Dispatchers.IO).launch { refreshAllInstances(context, ExitNodeToggleWidget()) }
    }
}

// ----------------------------------------------------------------
// Widget III: Stats Dashboard (3×3)
// ----------------------------------------------------------------
class StatsWidget : GlanceAppWidget() {

    override val stateDefinition = WidgetStateDef

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (isRunning, exitNode) = liveState(context)
        val activeAccount = AccountManager.getActiveAccount(context)
        val profileName = activeAccount.name

        var selfIp = "—"
        var peersTotal = 0; var peersOnline = 0
        var rxBytes = 0L; var txBytes = 0L

        if (isRunning) {
            try {
                val pJson = appctr.Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error")) {
                    val status = Gson().fromJson(pJson, StatusResponse::class.java)
                    selfIp = status.self?.getPrimaryIp() ?: "—"
                    peersTotal = status.peers?.size ?: 0
                    peersOnline = status.peers?.values?.filter { it.online == true }?.size ?: 0
                    rxBytes = status.self?.rxBytes ?: 0L
                    txBytes = status.self?.txBytes ?: 0L
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        val trafficText = "↑ ${formatFileSize(txBytes)}  ↓ ${formatFileSize(rxBytes)}"
        val statusLabel = if (isRunning) "● " + context.getString(R.string.status_running)
                          else           "○ " + context.getString(R.string.status_stopped)

        provideContent {
            GlanceTheme {
                val mainIntent = Intent(LocalContext.current, MainActivity::class.java)

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionStartActivity(mainIntent))
                ) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text("TailSocks", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(profileName, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text(statusLabel, style = TextStyle(color = if (isRunning) GlanceTheme.colors.primary else GlanceTheme.colors.outline, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.defaultWeight())
                        if (exitNode.isNotEmpty()) {
                            Text(context.getString(R.string.widget_exit_node_format, exitNode), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                        }
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(GlanceTheme.colors.outline)) {}
                    Spacer(GlanceModifier.height(6.dp))
                    Text("IP: $selfIp", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp))
                    Spacer(GlanceModifier.height(4.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(context.getString(R.string.widget_peers_format, peersOnline, peersTotal), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(trafficText, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Button(text = "↻", onClick = actionRunCallback<RefreshAllActionCallback>(),
                            colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.secondaryContainer, contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.height(40.dp))
                        Spacer(GlanceModifier.defaultWeight())
                        Button(text = context.getString(R.string.widget_exit_node_title), onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.secondaryContainer, contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.height(40.dp))
                        Spacer(GlanceModifier.width(8.dp))
                        Button(text = if (isRunning) context.getString(R.string.action_stop) else context.getString(R.string.action_start),
                            onClick = actionRunCallback<ToggleServiceActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isRunning) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                                contentColor = if (isRunning) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimary),
                            modifier = GlanceModifier.height(40.dp))
                    }
                }
            }
        }
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        CoroutineScope(Dispatchers.IO).launch { refreshAllInstances(context, StatsWidget()) }
    }
}

// ----------------------------------------------------------------
// Widget IV: Serve & Funnel Status (2×1)
// ----------------------------------------------------------------
class ServeWidget : GlanceAppWidget() {

    override val stateDefinition = WidgetStateDef

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning = ProxyState.isActualRunning()
        var statusText = if (isRunning) context.getString(R.string.widget_serve_title) else context.getString(R.string.widget_service_stopped)
        var rulesText = context.getString(R.string.widget_serve_no_rules)
        var hasRules = false

        if (isRunning) {
            try {
                val json = appctr.Appctr.getServeConfig()
                if (json.isNotEmpty() && !json.startsWith("Error")) {
                    val config = Gson().fromJson(json, ServeConfig::class.java)
                    val tcpCount = config.tcp?.size ?: 0
                    val webCount = config.web?.size ?: 0
                    val funnelCnt = config.allowFunnel?.filter { it.value }?.size ?: 0
                    if (tcpCount > 0 || webCount > 0 || funnelCnt > 0) {
                        rulesText = context.getString(R.string.widget_serve_rules_format, tcpCount, webCount, funnelCnt)
                        hasRules = true
                    }
                }
            } catch (e: Exception) { statusText = context.getString(R.string.widget_serve_api_error) }
        }

        provideContent {
            GlanceTheme {
                val mainIntent = Intent(LocalContext.current, MainActivity::class.java)
                val serveIntent = Intent(LocalContext.current, ServeActivity::class.java)

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionStartActivity(mainIntent)),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                ) {
                    Text(statusText, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(rulesText, style = TextStyle(color = if (hasRules) GlanceTheme.colors.primary else GlanceTheme.colors.outline, fontSize = 15.sp))
                    Spacer(GlanceModifier.defaultWeight())
                    if (isRunning && hasRules) {
                        Button(text = context.getString(R.string.widget_serve_purge), onClick = actionRunCallback<ClearServeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.error, contentColor = GlanceTheme.colors.onError),
                            modifier = GlanceModifier.fillMaxWidth().height(44.dp))
                    } else {
                        Button(text = context.getString(R.string.widget_serve_open), onClick = actionStartActivity(serveIntent),
                            colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.secondaryContainer, contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.fillMaxWidth().height(44.dp))
                    }
                }
            }
        }
    }
}

class ServeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServeWidget()
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        CoroutineScope(Dispatchers.IO).launch { refreshAllInstances(context, ServeWidget()) }
    }
}

// ----------------------------------------------------------------
// Action Callbacks
// ----------------------------------------------------------------

class RefreshAllActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAllWidgetsNow(context)
    }
}

class ToggleServiceActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val wasRunning = ProxyState.isActualRunning()
        val targetRunning = !wasRunning
        val activeAccount = AccountManager.getActiveAccount(context)
        val exitNode = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            .getString("exit_node_ip", "") ?: ""

        // STEP 1: Immediately write pending optimistic state to DataStore and re-render.
        // updateAppWidgetState() is a direct DataStore write — no WorkManager involved.
        // widget.update() kicks off a Glance session render with the freshly written state.
        pushState(
            context, glanceId, ServiceToggleWidget(),
            isRunning = wasRunning,  // keep old "isRunning" but set isPending
            isPending = true,
            profileName = activeAccount.name,
            exitNode = exitNode
        )

        // STEP 2: Also update other widget types so they see pending too
        ProxyState.setUserState(context, targetRunning)

        // STEP 3: Fire the actual service intent
        val intent = Intent(context, TailscaledService::class.java).apply {
            action = if (wasRunning) "STOP_ACTION" else "START_ACTION"
        }
        try {
            if (wasRunning) context.startService(intent)
            else androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) { e.printStackTrace() }

        // STEP 4: Wait for the service to actually change state, then commit real state
        kotlinx.coroutines.delay(1200)
        val realRunning = ProxyState.isActualRunning()
        pushState(
            context, glanceId, ServiceToggleWidget(),
            isRunning = realRunning,
            isPending = false,
            profileName = activeAccount.name,
            exitNode = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
                .getString("exit_node_ip", "") ?: ""
        )

        // STEP 5: Broader check after more time (for slow devices / stop that takes longer)
        kotlinx.coroutines.delay(2500)
        updateAllWidgetsNow(context)
        forceAppWidgetUpdate(context)
    }
}

class ToggleExitNodeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!ProxyState.isActualRunning()) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.widget_toast_not_running), Toast.LENGTH_SHORT).show()
            }
            return
        }

        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        val exitNodeId = prefs.getString("exit_node_id", "") ?: ""
        val editor = prefs.edit()

        if (exitNodeIp.isNotEmpty()) {
            editor.putString("last_exit_node_ip", exitNodeIp)
            editor.putString("last_exit_node_id", exitNodeId)
            editor.putString("exit_node_ip", "")
            editor.putString("exit_node_id", "")
            editor.apply()
            try { appctr.Appctr.setPrefs("{\"ExitNodeID\": \"\", \"ExitNodeIDSet\": true}") } catch (e: Exception) { e.printStackTrace() }
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.widget_toast_exit_disabled), Toast.LENGTH_SHORT).show()
            }
        } else {
            val lastIp = prefs.getString("last_exit_node_ip", "") ?: ""
            val lastId = prefs.getString("last_exit_node_id", "") ?: ""
            if (lastIp.isNotEmpty() && lastId.isNotEmpty()) {
                editor.putString("exit_node_ip", lastIp)
                editor.putString("exit_node_id", lastId)
                editor.apply()
                try { appctr.Appctr.setPrefs("{\"ExitNodeID\": \"$lastId\", \"ExitNodeIDSet\": true}") } catch (e: Exception) { e.printStackTrace() }
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, context.getString(R.string.widget_toast_exit_routing_format, lastIp), Toast.LENGTH_SHORT).show()
                }
            } else {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, context.getString(R.string.widget_toast_exit_select_first), Toast.LENGTH_LONG).show()
                }
            }
        }
        updateAllWidgetsNow(context)
        forceAppWidgetUpdate(context)
    }
}

class ClearServeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!ProxyState.isActualRunning()) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.widget_toast_not_running), Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            appctr.Appctr.setServeConfig("{}")
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.widget_toast_serve_cleared), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { e.printStackTrace() }
        updateAllWidgetsNow(context)
        forceAppWidgetUpdate(context)
    }
}
