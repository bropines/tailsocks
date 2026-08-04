package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Refresh ALL instances of ServiceToggleWidget with real live state */
suspend fun refreshAllInstances(context: Context) {
    val widget = ServiceToggleWidget()
    val (isRunning, exitNode) = liveState(context)
    val activeAccount = AccountManager.getActiveAccount(context)
    val manager = GlanceAppWidgetManager(context)
    for (id in manager.getGlanceIds(widget::class.java)) {
        pushState(context, id, widget, isRunning, false, activeAccount.name, exitNode)
    }
}

/** Fire ACTION_APPWIDGET_UPDATE broadcast directly to ServiceToggleWidgetReceiver so MIUI delivers it */
fun forceAppWidgetUpdate(context: Context) {
    val appContext = context.applicationContext
    val awm = AppWidgetManager.getInstance(appContext)
    val cls = ServiceToggleWidgetReceiver::class.java
    val ids = awm.getAppWidgetIds(ComponentName(appContext, cls))
    if (ids.isNotEmpty()) {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            component = ComponentName(appContext, cls)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        appContext.sendBroadcast(intent)
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
                    Text(
                        text = "TailSocks",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.height(2.dp))

                    Text(
                        text = profileName,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )

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
// Action Callbacks
// ----------------------------------------------------------------

class ToggleServiceActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // ALWAYS check REAL daemon state at click time rather than trusting stale UI state
        val isActuallyRunning = ProxyState.isActualRunning()

        val activeAccount = AccountManager.getActiveAccount(context)
        val exitNode = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            .getString("exit_node_ip", "") ?: ""

        // If actually running -> action is STOP (target state = stopped).
        // If actually stopped -> action is START (target state = running).
        val shouldStop = isActuallyRunning

        // STEP 1: Immediately push optimistic pending state to DataStore & re-render
        pushState(
            context, glanceId, ServiceToggleWidget(),
            isRunning = !shouldStop, // if stopping -> display as stop target; if starting -> display as start target
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
