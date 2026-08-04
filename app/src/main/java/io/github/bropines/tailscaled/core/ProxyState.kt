package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import androidx.core.content.edit
import appctr.Appctr
import java.util.concurrent.atomic.AtomicBoolean

object ProxyState {
    private const val PREF = "proxy_state"
    private const val KEY_DESIRED = "desired_running"

    // Optimistic pending flags set immediately on button press so the widget
    // can display the expected state before isActualRunning() catches up.
    private val _pendingStop = AtomicBoolean(false)
    private val _pendingStart = AtomicBoolean(false)

    fun setPendingStop(v: Boolean) {
        _pendingStop.set(v)
        if (v) _pendingStart.set(false)
    }

    fun setPendingStart(v: Boolean) {
        _pendingStart.set(v)
        if (v) _pendingStop.set(false)
    }

    fun clearPending() {
        _pendingStop.set(false)
        _pendingStart.set(false)
    }

    /** Returns effective "is running" for UI, considering pending transitions. */
    fun effectiveRunning(): Boolean {
        if (_pendingStop.get()) return false
        if (_pendingStart.get()) return true
        return Appctr.isRunning()
    }

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