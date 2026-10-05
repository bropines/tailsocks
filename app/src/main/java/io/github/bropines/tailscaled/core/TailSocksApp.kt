package io.github.bropines.tailscaled.core

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import appctr.Appctr
import java.util.TimeZone

/**
 * Process-wide entry point.
 *
 * Every component — activities, the foreground service, the Quick Settings tile,
 * widgets and broadcast receivers — runs in this process, and some of them (the
 * tile in particular) used to read daemon state before any of them had handed a
 * Context to [ProxyState]. Initialising it here makes the state helpers usable
 * from any entry point without each one having to remember to bootstrap them.
 */
class TailSocksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyState.init(this)
        ProfileHostinfo.migrateGlobalKey(this)
        // The launcher alias the icon preference names; binder calls, off the main thread.
        Thread { AppIcons.reconcile(this) }.start()
        // Go cannot find the device's zone on its own, and the log stamps it
        // writes would be UTC; kept current when the user travels.
        applyTimeZone()
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = applyTimeZone()
        }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }

    private fun applyTimeZone() {
        runCatching { Appctr.setTimeZone(TimeZone.getDefault().id) }
    }
}
