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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ----------------------------------------------------------------
// Action Parameter Keys
// ----------------------------------------------------------------
val paramExitNodeId = ActionParameters.Key<String>("exit_node_id")
val paramExitNodeIp = ActionParameters.Key<String>("exit_node_ip")

// ----------------------------------------------------------------
// Helpers for widget state
// ----------------------------------------------------------------

@Serializable
data class ExitNodeOption(
    val id: String = "",
    val ip: String = "",
    val name: String = ""
)

/** Build fresh live state from the daemon and account prefs */
private fun liveState(context: Context): Pair<Boolean, String> {
    val isRunning = ProxyState.isActualRunning()
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
    val exitNode = prefs.getString("exit_node_ip", "") ?: ""
    return Pair(isRunning, exitNode)
}

/** Get list of available exit nodes from daemon or persistent cache */
fun getAvailableExitNodes(context: Context): List<ExitNodeOption> {
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
    val options = mutableListOf<ExitNodeOption>()

    // 1. Load cached exit nodes first so we never lose previously discovered nodes
    val cachedJson = prefs.getString("cached_exit_nodes_json", "") ?: ""
    if (cachedJson.isNotEmpty()) {
        try {
            val cachedList: List<ExitNodeOption> = AppJson.decodeFromString<List<ExitNodeOption>>(cachedJson)
            options.addAll(cachedList)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 2. Query live daemon status if running & merge newly discovered nodes
    if (ProxyState.isActualRunning()) {
        try {
            val json = appctr.Appctr.getStatusFromAPI()
            if (json.isNotEmpty() && !json.startsWith("Error")) {
                val status = AppJson.decodeFromString<StatusResponse>(json)
                val liveNodes = mutableListOf<ExitNodeOption>()
                status.peers?.values?.filter { it.exitNodeOption == true }?.forEach { peer ->
                    val ip = peer.getPrimaryIp()
                    val name = peer.getDisplayName()
                    if (ip.isNotEmpty()) {
                        liveNodes.add(ExitNodeOption(peer.id ?: "", ip, name))
                    }
                }
                if (liveNodes.isNotEmpty()) {
                    for (liveNode in liveNodes) {
                        val idx = options.indexOfFirst { it.ip == liveNode.ip }
                        if (idx >= 0) {
                            options[idx] = liveNode
                        } else {
                            options.add(liveNode)
                        }
                    }
                    try {
                        val jsonStr = AppJson.encodeToString<List<ExitNodeOption>>(options)
                        prefs.edit().putString("cached_exit_nodes_json", jsonStr).apply()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 3. Fallback: add last_exit_node if still empty
    if (options.isEmpty()) {
        val lastIp = prefs.getString("last_exit_node_ip", "") ?: ""
        val lastId = prefs.getString("last_exit_node_id", "") ?: ""
        if (lastIp.isNotEmpty()) {
            options.add(ExitNodeOption(lastId, lastIp, lastIp))
        }
    }

    return options.distinctBy { it.ip }
}

/** Write widget DataStore state and trigger render for ONE glanceId. */
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

/** Refresh ALL instances of ServiceToggleWidget & ExitNodeToggleWidget with real live state */
suspend fun refreshAllInstances(context: Context) {
    val (isRunning, exitNode) = liveState(context)
    val activeAccount = AccountManager.getActiveAccount(context)
    val manager = GlanceAppWidgetManager(context)

    val serviceWidget = ServiceToggleWidget()
    for (id in manager.getGlanceIds(serviceWidget::class.java)) {
        pushState(context, id, serviceWidget, isRunning, false, activeAccount.name, exitNode)
    }

    val exitNodeWidget = ExitNodeToggleWidget()
    for (id in manager.getGlanceIds(exitNodeWidget::class.java)) {
        pushState(context, id, exitNodeWidget, isRunning, false, activeAccount.name, exitNode)
    }
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

fun updateAllWidgets(context: Context) {
    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        refreshAllInstances(appContext)
        forceAppWidgetUpdate(appContext)
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

        val activeAccount = AccountManager.getActiveAccount(context)
        val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
        val currentIp = prefs.getString("exit_node_ip", "") ?: ""
        val currentId = prefs.getString("exit_node_id", "") ?: ""

        // REACTIVE INSTANT UPDATE TO DATASTORE & WIDGET UI (<50ms)
        // Update DataStore state FIRST before doing any disk I/O, Toast or JNI calls!
        pushState(
            context, glanceId, ExitNodeToggleWidget(),
            isRunning = ProxyState.isActualRunning(),
            isPending = false,
            profileName = activeAccount.name,
            exitNode = targetIp
        )

        val editor = prefs.edit()
        if (targetIp.isEmpty()) {
            // Disable Exit Node
            if (currentIp.isNotEmpty()) {
                editor.putString("last_exit_node_ip", currentIp)
                editor.putString("last_exit_node_id", currentId)
            }
            editor.putString("exit_node_ip", "")
            editor.putString("exit_node_id", "")
            editor.apply()

            if (appctr.Appctr.isRunning()) {
                try { appctr.Appctr.setPrefs("{\"ExitNodeID\": \"\", \"ExitNodeIDSet\": true}") } catch (e: Exception) { e.printStackTrace() }
            } else if (ProxyState.isActualRunning(context)) {
                TailscaledService.requestApplySettings(context)
            }
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.widget_toast_exit_disabled), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Enable / Switch to selected Exit Node
            editor.putString("exit_node_ip", targetIp)
            editor.putString("exit_node_id", targetId)
            editor.putString("last_exit_node_ip", targetIp)
            editor.putString("last_exit_node_id", targetId)
            editor.apply()

            if (appctr.Appctr.isRunning()) {
                try { appctr.Appctr.setPrefs("{\"ExitNodeID\": \"$targetId\", \"ExitNodeIDSet\": true}") } catch (e: Exception) { e.printStackTrace() }
            } else if (ProxyState.isActualRunning(context)) {
                TailscaledService.requestApplySettings(context)
            }
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.widget_toast_exit_routing_format, targetIp), Toast.LENGTH_SHORT).show()
            }
        }

        refreshAllInstances(context)
        forceAppWidgetUpdate(context)
    }
}
