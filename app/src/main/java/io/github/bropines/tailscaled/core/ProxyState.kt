package io.github.bropines.tailscaled.core

import android.content.Context
import androidx.core.content.edit
import appctr.Appctr

object ProxyState {
    private const val PREF = "proxy_state"
    private const val KEY_DESIRED = "desired_running"
    /** Latched from whichever thread first calls in (Application.onCreate, a
     *  Glance callback on a background thread, AppFunctions on Dispatchers.IO)
     *  and read from others in [isActualRunning]. */
    @Volatile private var appContext: Context? = null

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

    /**
     * Whether a daemon we can talk to is alive right now.
     *
     * Read-only by design: it never attaches the Go bridge to a socket. In Root
     * Mode the daemon outlives the app process, so the socket is probed
     * directly; attaching to it is [TailscaledService]'s job, not a getter's.
     */
    fun isActualRunning(context: Context? = null): Boolean {
        if (Appctr.isRunning()) return true

        val ctx = context?.applicationContext ?: appContext ?: return false
        if (GlobalSettings.isRootModeEnabled(ctx)) {
            val socketPath = java.io.File(ctx.filesDir, "tailscaled.sock").absolutePath
            return RootUtils.isDaemonAlive(socketPath)
        }
        return false
    }
}