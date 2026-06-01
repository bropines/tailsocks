package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import androidx.core.content.edit
import appctr.Appctr

object ProxyState {
    private const val PREF = "proxy_state"
    private const val KEY_DESIRED = "desired_running"

    fun setUserState(context: Context, running: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_DESIRED, running)
            }
    }

    fun isUserLetRunning(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESIRED, false)
    }

    fun isActualRunning(): Boolean = Appctr.isRunning()
}