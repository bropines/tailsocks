package io.github.bropines.tailscaled

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.Gson

class ExitNodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        val action = intent.action
        if (action == "TOGGLE_SERVICE") {
            val isRunning = ProxyState.isActualRunning()
            val serviceIntent = Intent(context, TailscaledService::class.java).apply {
                this.action = if (isRunning) "STOP_ACTION" else "START_ACTION"
            }
            try {
                if (isRunning) {
                    context.startService(serviceIntent)
                } else {
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (action == "TOGGLE_EXIT_NODE") {
            val isRunning = ProxyState.isActualRunning()
            if (!isRunning) {
                Toast.makeText(context, "TailSocks is not running", Toast.LENGTH_SHORT).show()
                return
            }
            
            val activeAccount = AccountManager.getActiveAccount(context)
            val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
            val exitNodeId = prefs.getString("exit_node_id", "") ?: ""
            
            val editor = prefs.edit()
            if (exitNodeIp.isNotEmpty()) {
                // Currently enabled, toggle OFF
                editor.putString("last_exit_node_ip", exitNodeIp)
                editor.putString("last_exit_node_id", exitNodeId)
                editor.putString("exit_node_ip", "")
                editor.putString("exit_node_id", "")
                editor.apply()
                
                // Set in core
                Thread {
                    try {
                        appctr.Appctr.setPrefs("{\"ExitNodeID\": \"\", \"ExitNodeIDSet\": true}")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
                Toast.makeText(context, "Exit Node disabled", Toast.LENGTH_SHORT).show()
            } else {
                // Currently disabled, toggle ON if we have last used
                val lastExitIp = prefs.getString("last_exit_node_ip", "") ?: ""
                val lastExitId = prefs.getString("last_exit_node_id", "") ?: ""
                if (lastExitIp.isNotEmpty() && lastExitId.isNotEmpty()) {
                    editor.putString("exit_node_ip", lastExitIp)
                    editor.putString("exit_node_id", lastExitId)
                    editor.apply()
                    
                    Thread {
                        try {
                            appctr.Appctr.setPrefs("{\"ExitNodeID\": \"$lastExitId\", \"ExitNodeIDSet\": true}")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()
                    Toast.makeText(context, "Routing via $lastExitIp", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Select Exit Node in App first", Toast.LENGTH_LONG).show()
                }
            }
            
            // Trigger UI update
            val intentUpdate = Intent(context, ExitNodeWidgetProvider::class.java).apply {
                this.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                    ComponentName(context, ExitNodeWidgetProvider::class.java)
                )
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intentUpdate)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.exit_node_widget)
            
            val isRunning = ProxyState.isActualRunning()
            val activeAccount = AccountManager.getActiveAccount(context)
            val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
            val exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
            
            // 1. Render Basic/Optimistic State instantly
            views.setTextViewText(R.id.widget_account, activeAccount.name)
            
            if (isRunning) {
                views.setTextViewText(R.id.widget_status_text, "ACTIVE")
                views.setTextColor(R.id.widget_status_text, Color.parseColor("#4CAF50")) // Green
                views.setInt(R.id.widget_status_indicator, "setBackgroundColor", Color.parseColor("#4CAF50"))
                views.setTextViewText(R.id.widget_btn_toggle_service, "Stop")
                views.setInt(R.id.widget_btn_toggle_service, "setBackgroundColor", Color.parseColor("#D32F2F"))
                views.setTextViewText(R.id.widget_exit_node, if (exitNodeIp.isNotEmpty()) "Exit: $exitNodeIp" else "Exit: None")
            } else {
                views.setTextViewText(R.id.widget_status_text, "STOPPED")
                views.setTextColor(R.id.widget_status_text, Color.parseColor("#B0BEC5"))
                views.setInt(R.id.widget_status_indicator, "setBackgroundColor", Color.parseColor("#D32F2F"))
                views.setTextViewText(R.id.widget_btn_toggle_service, "Start")
                views.setInt(R.id.widget_btn_toggle_service, "setBackgroundColor", Color.parseColor("#1565C0"))
                views.setTextViewText(R.id.widget_exit_node, "Exit: None")
                views.setTextViewText(R.id.widget_node_ip, "IP: 0.0.0.0")
                views.setTextViewText(R.id.widget_peers_count, "Peers: 0/0")
                views.setTextViewText(R.id.widget_traffic, "Tx: 0 B | Rx: 0 B")
            }
            
            // Set intents
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val serviceIntent = Intent(context, ExitNodeWidgetProvider::class.java).apply { action = "TOGGLE_SERVICE" }
            views.setOnClickPendingIntent(R.id.widget_btn_toggle_service, PendingIntent.getBroadcast(context, 0, serviceIntent, flag))
            
            val exitIntent = Intent(context, ExitNodeWidgetProvider::class.java).apply { action = "TOGGLE_EXIT_NODE" }
            views.setOnClickPendingIntent(R.id.widget_btn_toggle_exit, PendingIntent.getBroadcast(context, 1, exitIntent, flag))
            
            val mainIntent = Intent(context, MainActivity::class.java)
            val mainPendingIntent = PendingIntent.getActivity(context, 2, mainIntent, flag)
            views.setOnClickPendingIntent(R.id.widget_title, mainPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_status_container, mainPendingIntent)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
            
            // 2. Fetch detailed API stats in the background if running
            if (isRunning) {
                Thread {
                    try {
                        val pJson = appctr.Appctr.getStatusFromAPI()
                        if (!pJson.startsWith("Error")) {
                            val status = Gson().fromJson(pJson, StatusResponse::class.java)
                            val selfIp = status.self?.getPrimaryIp() ?: "0.0.0.0"
                            val peersTotal = status.peers?.size ?: 0
                            val peersOnline = status.peers?.values?.filter { it.online == true }?.size ?: 0
                            val rxBytes = status.self?.rxBytes ?: 0L
                            val txBytes = status.self?.txBytes ?: 0L
                            
                            val trafficText = "Tx: ${formatFileSize(txBytes)} | Rx: ${formatFileSize(rxBytes)}"
                            
                            // Re-fetch RemoteViews since thread execution is async
                            val updateViews = RemoteViews(context.packageName, R.layout.exit_node_widget)
                            // Re-apply basic status
                            updateViews.setTextViewText(R.id.widget_account, activeAccount.name)
                            updateViews.setTextViewText(R.id.widget_status_text, "ACTIVE")
                            updateViews.setTextColor(R.id.widget_status_text, Color.parseColor("#4CAF50"))
                            updateViews.setInt(R.id.widget_status_indicator, "setBackgroundColor", Color.parseColor("#4CAF50"))
                            updateViews.setTextViewText(R.id.widget_btn_toggle_service, "Stop")
                            updateViews.setInt(R.id.widget_btn_toggle_service, "setBackgroundColor", Color.parseColor("#D32F2F"))
                            updateViews.setTextViewText(R.id.widget_exit_node, if (exitNodeIp.isNotEmpty()) "Exit: $exitNodeIp" else "Exit: None")
                            
                            // Apply detailed stats
                            updateViews.setTextViewText(R.id.widget_node_ip, "IP: $selfIp")
                            updateViews.setTextViewText(R.id.widget_peers_count, "Peers: $peersOnline/$peersTotal")
                            updateViews.setTextViewText(R.id.widget_traffic, trafficText)
                            
                            // Re-apply pending intents
                            updateViews.setOnClickPendingIntent(R.id.widget_btn_toggle_service, PendingIntent.getBroadcast(context, 0, serviceIntent, flag))
                            updateViews.setOnClickPendingIntent(R.id.widget_btn_toggle_exit, PendingIntent.getBroadcast(context, 1, exitIntent, flag))
                            updateViews.setOnClickPendingIntent(R.id.widget_title, mainPendingIntent)
                            updateViews.setOnClickPendingIntent(R.id.widget_status_container, mainPendingIntent)
                            
                            appWidgetManager.updateAppWidget(appWidgetId, updateViews)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            }
        }
    }
}
