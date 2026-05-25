package io.github.bropines.tailscaled

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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

private val BG_DARK     = ColorProvider(Color(0xFF12181F))
private val BG_CARD     = ColorProvider(Color(0xFF1A2330))
private val BG_DIVIDER  = ColorProvider(Color(0xFF263238))
private val BG_BTN      = ColorProvider(Color(0xFF2A333E))
private val CLR_WHITE   = ColorProvider(Color.White)
private val CLR_BLUE    = ColorProvider(Color(0xFF2196F3))
private val CLR_GREEN   = ColorProvider(Color(0xFF4CAF50))
private val CLR_RED     = ColorProvider(Color(0xFFD32F2F))
private val CLR_GRAY    = ColorProvider(Color(0xFFB0BEC5))
private val CLR_LIGHT   = ColorProvider(Color(0xFFECEFF1))
private val CLR_MUTED   = ColorProvider(Color(0xFFCFD8DC))

// ----------------------------------------------------------------
// Widget I: Service Toggle
// ----------------------------------------------------------------
class ServiceToggleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning = ProxyState.isActualRunning()
        val activeAccount = AccountManager.getActiveAccount(context)

        provideContent {
            val btnBg   = if (isRunning) CLR_RED else ColorProvider(Color(0xFF1565C0))
            val btnText = if (isRunning) "Stop" else "Start"

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(BG_DARK)
                    .cornerRadius(16.dp)
                    .padding(12.dp),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                verticalAlignment   = Alignment.Vertical.CenterVertically
            ) {
                Text("TailSocks Switcher",
                    style = TextStyle(color = CLR_WHITE, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(4.dp))
                Text(activeAccount.name,
                    style = TextStyle(color = CLR_BLUE, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(8.dp))
                Button(
                    text    = btnText,
                    onClick = actionRunCallback<ToggleServiceActionCallback>(),
                    colors  = ButtonDefaults.buttonColors(backgroundColor = btnBg, contentColor = CLR_WHITE),
                    modifier = GlanceModifier.fillMaxWidth().height(36.dp)
                )
            }
        }
    }
}

class ServiceToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServiceToggleWidget()
}

// ----------------------------------------------------------------
// Widget II: Exit Node Toggle
// ----------------------------------------------------------------
class ExitNodeToggleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs         = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp    = prefs.getString("exit_node_ip", "") ?: ""
        val isActive      = exitNodeIp.isNotEmpty()

        provideContent {
            val mainIntent = Intent(context, MainActivity::class.java)
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(BG_DARK)
                    .cornerRadius(16.dp)
                    .padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(
                    modifier  = GlanceModifier.defaultWeight(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text("Exit Node",
                        style = TextStyle(color = CLR_WHITE, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.clickable(actionStartActivity(mainIntent)))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text  = if (isActive) exitNodeIp else "Disabled",
                        style = TextStyle(color = if (isActive) CLR_GREEN else CLR_GRAY, fontSize = 11.sp)
                    )
                }
                Button(
                    text    = if (isActive) "Disable" else "Enable",
                    onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                    colors  = ButtonDefaults.buttonColors(
                        backgroundColor = if (isActive) BG_BTN else ColorProvider(Color(0xFF1565C0)),
                        contentColor    = CLR_WHITE
                    ),
                    modifier = GlanceModifier.width(80.dp).height(32.dp)
                )
            }
        }
    }
}

class ExitNodeToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExitNodeToggleWidget()
}

// ----------------------------------------------------------------
// Widget III: Stats Dashboard
// ----------------------------------------------------------------
class StatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning     = ProxyState.isActualRunning()
        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs         = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp    = prefs.getString("exit_node_ip", "") ?: ""

        var selfIp      = "0.0.0.0"
        var peersTotal  = 0
        var peersOnline = 0
        var rxBytes     = 0L
        var txBytes     = 0L

        if (isRunning) {
            try {
                val pJson = appctr.Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error")) {
                    val status  = Gson().fromJson(pJson, StatusResponse::class.java)
                    selfIp      = status.self?.getPrimaryIp() ?: "0.0.0.0"
                    peersTotal  = status.peers?.size ?: 0
                    peersOnline = status.peers?.values?.filter { it.online == true }?.size ?: 0
                    rxBytes     = status.self?.rxBytes ?: 0L
                    txBytes     = status.self?.txBytes ?: 0L
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        val trafficText  = "Tx: ${formatFileSize(txBytes)} | Rx: ${formatFileSize(rxBytes)}"
        val dotColor     = if (isRunning) CLR_GREEN else CLR_RED
        val statusLabel  = if (isRunning) "ACTIVE" else "STOPPED"
        val statusColor  = if (isRunning) CLR_GREEN else CLR_GRAY
        val btnBg        = if (isRunning) CLR_RED else ColorProvider(Color(0xFF1565C0))
        val btnLabel     = if (isRunning) "Stop" else "Start"

        provideContent {
            val ctx        = LocalContext.current
            val mainIntent = Intent(ctx, MainActivity::class.java)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(BG_DARK)
                    .cornerRadius(16.dp)
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    modifier          = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text("TailSocks",
                        style    = TextStyle(color = CLR_WHITE, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.clickable(actionStartActivity(mainIntent)))
                    Spacer(GlanceModifier.defaultWeight())
                    Text(activeAccount.name,
                        style = TextStyle(color = CLR_BLUE, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }

                Spacer(GlanceModifier.height(6.dp))

                // Status row
                Row(
                    modifier          = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Box(
                        modifier = GlanceModifier
                            .width(8.dp).height(8.dp)
                            .background(dotColor)
                            .cornerRadius(4.dp)
                    ) {}
                    Spacer(GlanceModifier.width(6.dp))
                    Text(statusLabel, style = TextStyle(color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        text  = if (exitNodeIp.isNotEmpty()) "Exit: $exitNodeIp" else "Exit: None",
                        style = TextStyle(color = CLR_MUTED, fontSize = 11.sp)
                    )
                }

                Spacer(GlanceModifier.height(6.dp))

                // Divider
                Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(BG_DIVIDER)) {}

                Spacer(GlanceModifier.height(6.dp))

                // Stats
                Text("IP: $selfIp", style = TextStyle(color = CLR_LIGHT, fontSize = 12.sp))
                Spacer(GlanceModifier.height(2.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text("Peers: $peersOnline/$peersTotal", style = TextStyle(color = CLR_GRAY, fontSize = 11.sp))
                    Spacer(GlanceModifier.defaultWeight())
                    Text(trafficText, style = TextStyle(color = CLR_GRAY, fontSize = 11.sp))
                }

                Spacer(GlanceModifier.defaultWeight())

                // Actions
                Row(
                    modifier          = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Button(
                        text    = "↻",
                        onClick = actionRunCallback<RefreshStatsActionCallback>(),
                        colors  = ButtonDefaults.buttonColors(backgroundColor = BG_BTN, contentColor = CLR_WHITE),
                        modifier = GlanceModifier.width(36.dp).height(32.dp)
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Button(
                        text    = "Exit Node",
                        onClick = actionRunCallback<ToggleExitNodeActionCallback>(),
                        colors  = ButtonDefaults.buttonColors(backgroundColor = BG_BTN, contentColor = CLR_WHITE),
                        modifier = GlanceModifier.width(90.dp).height(32.dp)
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Button(
                        text    = btnLabel,
                        onClick = actionRunCallback<ToggleServiceActionCallback>(),
                        colors  = ButtonDefaults.buttonColors(backgroundColor = btnBg, contentColor = CLR_WHITE),
                        modifier = GlanceModifier.width(70.dp).height(32.dp)
                    )
                }
            }
        }
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}

// ----------------------------------------------------------------
// Widget IV: Serve & Funnel Status
// ----------------------------------------------------------------
class ServeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning    = ProxyState.isActualRunning()
        var statusText   = "TailSocks Serve"
        var rulesText    = "No active rules"
        var hasRules     = false

        if (isRunning) {
            try {
                val json = appctr.Appctr.getServeConfig()
                if (json.isNotEmpty() && !json.startsWith("Error")) {
                    val config    = Gson().fromJson(json, ServeConfig::class.java)
                    val tcpCount  = config.tcp?.size ?: 0
                    val webCount  = config.web?.size ?: 0
                    val funnelCnt = config.allowFunnel?.filter { it.value }?.size ?: 0
                    if (tcpCount > 0 || webCount > 0 || funnelCnt > 0) {
                        rulesText = "TCP: $tcpCount | Web: $webCount | Funnel: $funnelCnt"
                        hasRules  = true
                    }
                }
            } catch (e: Exception) {
                statusText = "Serve API Error"
            }
        } else {
            statusText = "Service stopped"
        }

        provideContent {
            val ctx         = LocalContext.current
            val serveIntent = Intent(ctx, ServeActivity::class.java)

            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(BG_DARK)
                    .cornerRadius(16.dp)
                    .padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(
                    modifier          = GlanceModifier.defaultWeight(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(statusText,
                        style    = TextStyle(color = CLR_WHITE, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.clickable(actionStartActivity(serveIntent)))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(rulesText,
                        style = TextStyle(color = if (hasRules) CLR_BLUE else CLR_GRAY, fontSize = 11.sp))
                }

                if (isRunning && hasRules) {
                    Button(
                        text    = "Purge",
                        onClick = actionRunCallback<ClearServeActionCallback>(),
                        colors  = ButtonDefaults.buttonColors(backgroundColor = CLR_RED, contentColor = CLR_WHITE),
                        modifier = GlanceModifier.width(64.dp).height(32.dp)
                    )
                } else {
                    Button(
                        text    = "Open",
                        onClick = actionStartActivity(serveIntent),
                        colors  = ButtonDefaults.buttonColors(backgroundColor = BG_BTN, contentColor = CLR_WHITE),
                        modifier = GlanceModifier.width(64.dp).height(32.dp)
                    )
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
        val intent    = Intent(context, TailscaledService::class.java).apply {
            action = if (isRunning) "STOP_ACTION" else "START_ACTION"
        }
        try {
            if (isRunning) context.startService(intent)
            else androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) { e.printStackTrace() }
        delay(600)
        updateAllWidgets(context)
    }
}

class ToggleExitNodeActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val isRunning = ProxyState.isActualRunning()
        if (!isRunning) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "TailSocks is not running", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs         = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val exitNodeIp    = prefs.getString("exit_node_ip", "") ?: ""
        val exitNodeId    = prefs.getString("exit_node_id", "") ?: ""
        val editor        = prefs.edit()

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
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "Stats refreshed", Toast.LENGTH_SHORT).show()
        }
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
            // Reset-then-Apply: send empty config to wipe daemon state
            appctr.Appctr.setServeConfig("{}")
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "Serve & Funnel cleared", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { e.printStackTrace() }
        updateAllWidgets(context)
    }
}
