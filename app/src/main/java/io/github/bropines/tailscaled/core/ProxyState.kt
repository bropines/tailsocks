package io.github.bropines.tailscaled.core

import android.content.Context
import androidx.core.content.edit
import appctr.Appctr

object ProxyState {
    private const val PREF = "proxy_state"
    private const val KEY_DESIRED = "desired_running"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setUserState(context: Context, running: Boolean) {
        appContext = context.applicationContext
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DESIRED, running) }
    }

    fun isUserLetRunning(context: Context): Boolean {
        appContext = context.applicationContext
        return context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESIRED, false)
    }

    fun isActualRunning(context: Context? = null): Boolean {
        val ctx = context?.applicationContext ?: appContext
        if (Appctr.isRunning()) return true

        if (ctx != null && GlobalSettings.isRootModeEnabled(ctx)) {
            val socketFile = java.io.File(ctx.filesDir, "tailscaled.sock")
            if (socketFile.exists()) {
                Appctr.setExternalSocketPath(socketFile.absolutePath)
                return Appctr.isRunning()
            }
        }
        return false
    }
}