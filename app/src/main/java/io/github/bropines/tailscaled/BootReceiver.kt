package io.github.bropines.tailscaled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
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