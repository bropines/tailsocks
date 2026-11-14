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
        // Engine statuses only while something reads them (appctr/engine_watch.go): otherwise
        // the bridge's bus subscription makes the daemon walk its WireGuard peers and broadcast
        // the result every 2 s for its whole uptime, screen off included, for one reader — the
        // diagnostics card (Diagnostics.live), which asks every 2 s while it is shown and so
        // keeps the watch open; a one-off read (the issue report) opens it and waits for the
        // first status. Nothing else reads it, and nothing is pushed to the app. Read when the
        // bus listener connects, so it is set here, before any component can start the daemon.
        runCatching { Appctr.setEngineUpdatesOnDemand(true) }
        // The bridge tells the screens when the daemon's state changes (DaemonEvents), so they
        // read it then instead of asking every few seconds whether it did.
        DaemonEvents.register()
        TaskRecents.watch(this)
        ProfileHostinfo.migrateGlobalKey(this)
        // A key sent from a phone that the last process did not live to clear (KeyHandover).
        KeyHandover.sweep(this)
        // The launcher alias the icon preference names; binder calls, off the main thread.
        Thread { AppIcons.reconcile(this) }.start()
        // Root Mode rules a daemon that died with this process left behind
        // (RootOrphanSweep); ordered against every start by the start executor.
        TailscaledService.sweepOrphanedRootRouting(this)
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
