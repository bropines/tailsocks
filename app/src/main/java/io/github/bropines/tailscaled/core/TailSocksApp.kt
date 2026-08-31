package io.github.bropines.tailscaled.core

import android.app.Application

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
    }
}
