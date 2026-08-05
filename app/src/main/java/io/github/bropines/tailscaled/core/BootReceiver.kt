package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON" || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            // Auto-refresh root scripts on update if they were previously installed
            if (RootUtils.isServiceScriptInstalled()) {
                RootUtils.setServiceScriptInstalled(context, true)
            }
            if (RootUtils.isTailscaleCliInstalled()) {
                RootUtils.setTailscaleCliInstalled(context, true)
            }

            val prefs = context.getSharedPreferences("appctr", Context.MODE_PRIVATE)
            val forceBg = prefs.getBoolean("force_bg", false)
            val userLetRunning = ProxyState.isUserLetRunning(context)

            if (forceBg && userLetRunning) {
                val serviceIntent = Intent(context, TailscaledService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                ProxyState.setUserState(context, false)
            }
        }
    }
}