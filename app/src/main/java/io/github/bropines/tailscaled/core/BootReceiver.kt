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
import android.util.Log

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // "Auto-start on Boot" is the switch that says so. "Keep the connection
        // awake" used to be the only thing read here, so it keeps deciding a
        // reboot too: nobody who relied on it loses the start after an update.
        val autoStart = GlobalSettings.isAutoStartEnabled(context)
        val forceBg = GlobalSettings.getBoolean(context, "force_bg", false)
        val userLetRunning = ProxyState.isUserLetRunning(context)

        // An update kills the running service; the user did not ask for that, so
        // resume whenever it was running before the install. The two switches
        // only decide whether we also come back after a reboot.
        val replaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        Log.i(TAG, "$action: userLetRunning=$userLetRunning autoStart=$autoStart forceBg=$forceBg")
        if (userLetRunning && (autoStart || forceBg || replaced)) {
            val serviceIntent = Intent(context, TailscaledService::class.java).apply { this.action = "START_ACTION" }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "Service start requested")
            } catch (e: Exception) {
                // Android 12+ and OEM skins can refuse a background start. An
                // uncaught exception here would take down whatever process the
                // system happened to run this receiver in; the watchdog alarm
                // and the next app launch both retry.
                Log.e(TAG, "Service start refused: ${e.message}")
                ServiceWatchdog.noteRevivalRefused(context)
            }
        } else if (action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            ProxyState.setUserState(context, false)
        }

        // Refreshing the root scripts spawns su shells and can block for seconds
        // (the Magisk prompt may not resolve at boot). Run it off the main thread
        // so it never ANRs the boot/update broadcast.
        val onlyIfRoot = GlobalSettings.isRootModeEnabled(context)
        if (!onlyIfRoot) return
        val pending = goAsync()
        Thread {
            try {
                if (RootUtils.isServiceScriptInstalled()) {
                    RootUtils.setServiceScriptInstalled(context, true)
                }
                if (RootUtils.isTailscaleCliInstalled()) {
                    RootUtils.setTailscaleCliInstalled(context, true)
                }
            } catch (e: Exception) {
                // Best effort; nothing to recover at boot.
            } finally {
                pending.finish()
            }
        }.start()
    }
}