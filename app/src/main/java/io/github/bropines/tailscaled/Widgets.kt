package io.github.bropines.tailscaled

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
import androidx.glance.unit.ColorProvider
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
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text("TailSocks",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(4.dp))
                    Text(activeAccount.name,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.height(12.dp))
                    Button(
                        text = if (isRunning) "Stop Service" else "Start Service",
                        onClick = actionRunCallback<ToggleServiceActionCallback>(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isRunning) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                            contentColor = if (isRunning) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimary
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
}

// ----------------------------------------------------------------
// Widget II: Exit Node Toggle (2×1)
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

                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Column(
                        modifier = GlanceModifier.defaultWeight()
                            .clickable(actionStartActivity(Intent(ctx, MainActivity::class.java)))
                    ) {
                        Text("Exit Node",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = if (isActive) exitNodeIp else "Not active",
                            style = TextStyle(
                                color = if (isActive) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                                fontSize = 14.sp))
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    Button(
                        text = if (isActive) "OFF" else "ON",
                        onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isActive) GlanceTheme.colors.errorContainer else GlanceTheme.colors.primary,
                            contentColor = if (isActive) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.onPrimary
                        ),
                        modifier = GlanceModifier.width(72.dp).height(44.dp)
                    )
                }
            }
        }
    }
}

class ExitNodeToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExitNodeToggleWidget()
}

// ----------------------------------------------------------------
// Widget III: Stats Dashboard (3×3)
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
        val statusLabel = if (isRunning) "ACTIVE" else "STOPPED"
        val btnLabel = if (isRunning) "Stop" else "Start"

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
                ) {
                    // Header
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Text("TailSocks",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold),
                            modifier = GlanceModifier.clickable(actionStartActivity(mainIntent)))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(activeAccount.name,
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold))
                    }

                    Spacer(GlanceModifier.height(10.dp))

                    // Status row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Box(
                            modifier = GlanceModifier
                                .size(10.dp)
                                .background(if (isRunning) GlanceTheme.colors.primary else GlanceTheme.colors.error)
                                .cornerRadius(5.dp)
                        ) {}
                        Spacer(GlanceModifier.width(8.dp))
                        Text(statusLabel,
                            style = TextStyle(
                                color = if (isRunning) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            text = if (exitNodeIp.isNotEmpty()) "Exit: $exitNodeIp" else "Exit: None",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 13.sp))
                    }

                    Spacer(GlanceModifier.height(10.dp))

                    // Divider
                    Box(modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(GlanceTheme.colors.outline)) {}

                    Spacer(GlanceModifier.height(10.dp))

                    // Stats
                    Text("IP: $selfIp",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 15.sp))
                    Spacer(GlanceModifier.height(4.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text("Peers: $peersOnline / $peersTotal",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 14.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(trafficText,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 14.sp))
                    }

                    Spacer(GlanceModifier.defaultWeight())

                    // Actions
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Button(
                            text = "↻ Refresh",
                            onClick = actionRunCallback<RefreshStatsActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.height(40.dp)
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Button(
                            text = "Exit Node",
                            onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.height(40.dp)
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Button(
                            text = btnLabel,
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
}

// ----------------------------------------------------------------
// Widget IV: Serve & Funnel Status (2×1)
// ----------------------------------------------------------------
class ServeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning = ProxyState.isActualRunning()
        var statusText = if (isRunning) "Serve & Funnel" else "Service stopped"
        var rulesText = "No active rules"
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
                        rulesText = "TCP: $tcpCount  Web: $webCount  Funnel: $funnelCnt"
                        hasRules = true
                    }
                }
            } catch (e: Exception) {
                statusText = "Serve API Error"
            }
        }

        provideContent {
            GlanceTheme {
                val ctx = LocalContext.current
                val serveIntent = Intent(ctx, ServeActivity::class.java)

                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Column(
                        modifier = GlanceModifier.defaultWeight()
                            .clickable(actionStartActivity(serveIntent))
                    ) {
                        Text(statusText,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.height(4.dp))
                        Text(rulesText,
                            style = TextStyle(
                                color = if (hasRules) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                                fontSize = 14.sp))
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    if (isRunning && hasRules) {
                        Button(
                            text = "Purge",
                            onClick = actionRunCallback<ClearServeActionCallback>(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.error,
                                contentColor = GlanceTheme.colors.onError),
                            modifier = GlanceModifier.width(80.dp).height(44.dp)
                        )
                    } else {
                        Button(
                            text = "Open",
                            onClick = actionStartActivity(serveIntent),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.secondaryContainer,
                                contentColor = GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.width(80.dp).height(44.dp)
                        )
                    }
                }
            }
        }
    }
}

class ServeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServeWidget()
}

// ----------------------------------------------------------------
// Action Callbacks
// ----------------------------------------------------------------
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

        // Two-phase update: quick optimistic + delayed real status
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
                Toast.makeText(context, "TailSocks is not running", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Exit Node disabled", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Routing via $lastIp", Toast.LENGTH_SHORT).show()
                }
            } else {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, "Select Exit Node in App first", Toast.LENGTH_LONG).show()
                }
            }
        }
        updateAllWidgets(context)
    }
}

class RefreshStatsActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAllWidgets(context)
    }
}

class ClearServeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!ProxyState.isActualRunning()) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "TailSocks is not running", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            appctr.Appctr.setServeConfig("{}")
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "Serve & Funnel cleared", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { e.printStackTrace() }
        updateAllWidgets(context)
    }
}
