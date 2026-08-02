package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ----------------------------------------------------------------
// Global Widgets Update Helper
// ----------------------------------------------------------------
fun updateAllWidgets(context: Context) {
    CoroutineScope(Dispatchers.Default).launch {
        try {
            ServiceToggleWidget().updateAll(context)
            ExitNodeToggleWidget().updateAll(context)
            StatsWidget().updateAll(context)
            ServeWidget().updateAll(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ----------------------------------------------------------------
// Widget I: Service Toggle (2×1)
// Text top, button bottom
// ----------------------------------------------------------------
class ServiceToggleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning = ProxyState.isActualRunning()
        val activeAccount = AccountManager.getActiveAccount(context)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionRunCallback<RefreshAllActionCallback>()),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                ) {
                    Text("TailSocks",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(activeAccount.name,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = if (isRunning) "● " + context.getString(R.string.status_running) else "○ " + context.getString(R.string.status_stopped),
                        style = TextStyle(
                            color = if (isRunning) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                            fontSize = 14.sp))

                    Spacer(GlanceModifier.defaultWeight())

                    Button(
                        text = if (isRunning) context.getString(R.string.widget_service_stop) else context.getString(R.string.widget_service_start),
                        onClick = actionRunCallback<ToggleServiceActionCallback>(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isRunning) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                            contentColor = if (isRunning) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimary),
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
        updateAllWidgets(context)
    }
}

// ----------------------------------------------------------------
// Widget II: Exit Node Toggle (2×1)
// Text top, button bottom
// ----------------------------------------------------------------
class ExitNodeToggleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        val isActive = exitNodeIp.isNotEmpty()

        provideContent {
            GlanceTheme {
                val ctx = LocalContext.current

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionRunCallback<RefreshAllActionCallback>()),
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
        updateAllWidgets(context)
    }
}

// ----------------------------------------------------------------
// Widget III: Stats Dashboard (3×3)
// Header → status → divider → stats → buttons
// ----------------------------------------------------------------
class StatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning = ProxyState.isActualRunning()
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""

        var selfIp = "—"
        var peersTotal = 0
        var peersOnline = 0
        var rxBytes = 0L
        var txBytes = 0L

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
        val statusLabel = if (isRunning) "● " + context.getString(R.string.status_running) else "○ " + context.getString(R.string.status_stopped)

        provideContent {
            GlanceTheme {
                val ctx = LocalContext.current
                val mainIntent = Intent(ctx, MainActivity::class.java)

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionRunCallback<RefreshAllActionCallback>())
                ) {
                    // Header
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Text("TailSocks",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold),
                            modifier = GlanceModifier.clickable(actionStartActivity(mainIntent)))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(activeAccount.name,
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold))
                    }

                    Spacer(GlanceModifier.height(8.dp))

                    // Status row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Text(statusLabel,
                            style = TextStyle(
                                color = if (isRunning) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            text = if (exitNodeIp.isNotEmpty()) context.getString(R.string.widget_exit_node_format, exitNodeIp) else context.getString(R.string.widget_exit_node_format, context.getString(R.string.settings_none)),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 14.sp))
                    }

                    Spacer(GlanceModifier.height(8.dp))

                    // Divider
                    Box(modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(GlanceTheme.colors.outline)) {}

                    Spacer(GlanceModifier.height(8.dp))

                    // Stats
                    Text("IP: $selfIp",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 16.sp))
                    Spacer(GlanceModifier.height(4.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(context.getString(R.string.widget_peers_format, peersOnline, peersTotal),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 15.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(trafficText,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 15.sp))
                    }

                    Spacer(GlanceModifier.defaultWeight())

                    // Actions row at bottom
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Button(
                            text = "↻",
                            onClick = actionRunCallback<RefreshAllActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.height(40.dp)
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Button(
                            text = context.getString(R.string.widget_exit_node_title),
                            onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.height(40.dp)
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Button(
                            text = if (isRunning) context.getString(R.string.action_stop) else context.getString(R.string.action_start),
                            onClick = actionRunCallback<ToggleServiceActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isRunning) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                                contentColor = if (isRunning) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimary),
                            modifier = GlanceModifier.height(40.dp)
                        )
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
        updateAllWidgets(context)
    }
}

// ----------------------------------------------------------------
// Widget IV: Serve & Funnel Status (2×1)
// Text top, button bottom
// ----------------------------------------------------------------
class ServeWidget : GlanceAppWidget() {
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
            } catch (e: Exception) {
                statusText = context.getString(R.string.widget_serve_api_error)
            }
        }

        provideContent {
            GlanceTheme {
                val ctx = LocalContext.current
                val serveIntent = Intent(ctx, ServeActivity::class.java)

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionRunCallback<RefreshAllActionCallback>()),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                ) {
                    Text(statusText,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(rulesText,
                        style = TextStyle(
                            color = if (hasRules) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                            fontSize = 15.sp))

                    Spacer(GlanceModifier.defaultWeight())

                    if (isRunning && hasRules) {
                        Button(
                            text = context.getString(R.string.widget_serve_purge),
                            onClick = actionRunCallback<ClearServeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.error,
                                contentColor = GlanceTheme.colors.onError),
                            modifier = GlanceModifier.fillMaxWidth().height(44.dp)
                        )
                    } else {
                        Button(
                            text = context.getString(R.string.widget_serve_open),
                            onClick = actionStartActivity(serveIntent),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.fillMaxWidth().height(44.dp)
                        )
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
        updateAllWidgets(context)
    }
}

// ----------------------------------------------------------------
// Action Callbacks
// ----------------------------------------------------------------

/** Refresh all widgets — used as background tap handler */
class RefreshAllActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAllWidgets(context)
    }
}

class ToggleServiceActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val isRunning = ProxyState.isActualRunning()
        val intent = Intent(context, TailscaledService::class.java).apply {
            action = if (isRunning) "STOP_ACTION" else "START_ACTION"
        }
        try {
            if (isRunning) context.startService(intent)
            else androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) { e.printStackTrace() }

        // Multi-phase update: immediate optimistic + quick real + delayed final status
        updateAllWidgets(context)
        delay(500)
        updateAllWidgets(context)
        delay(2000)
        updateAllWidgets(context)
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
        updateAllWidgets(context)
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
        updateAllWidgets(context)
    }
}
