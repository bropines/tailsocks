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

            // "Keep running in background" is a global setting; it used to be read
            // from an unrelated preference file here, so it never took effect.
            val forceBg = GlobalSettings.getBoolean(context, "force_bg", false)
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